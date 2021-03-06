package org.bluedb.disk.file;

import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import org.bluedb.api.exceptions.BlueDbException;
import org.bluedb.disk.lock.BlueWriteLock;
import org.bluedb.disk.lock.LockManager;
import org.bluedb.disk.serialization.BlueSerializer;

public class BlueObjectOutput<T> implements Closeable {

	private final BlueWriteLock<Path> lock;
	private final Path path;
	private final BlueSerializer serializer;
	private final DataOutputStream dataOutputStream;

	public BlueObjectOutput(BlueWriteLock<Path> writeLock, BlueSerializer serializer) throws BlueDbException {
		try {
			lock = writeLock;
			path = lock.getKey();
			this.serializer = serializer;
			File file = path.toFile();
			FileUtils.ensureDirectoryExists(file);
			dataOutputStream = FileUtils.openDataOutputStream(file);
		} catch(Throwable t) {
			close();
			throw new BlueDbException(t.getMessage(), t);
		}
	}

	protected static <T> BlueObjectOutput<T> getTestOutput(Path path, BlueSerializer serializer, DataOutputStream dataOutputStream) {
		return new BlueObjectOutput<T>(path, serializer, dataOutputStream);
	}

	private BlueObjectOutput(Path path, BlueSerializer serializer, DataOutputStream dataOutputStream) {
		LockManager<Path> lockManager = new LockManager<Path>();
		lock = lockManager.acquireWriteLock(path);
		this.path = path;
		this.serializer = serializer;
		this.dataOutputStream = dataOutputStream;
	}
	
	public static <T> BlueObjectOutput<T> createWithoutLockOrSerializer(Path path) throws BlueDbException {
		return createWithoutLock(path, null);
	}
	
	public static <T> BlueObjectOutput<T> createWithoutLock(Path path, BlueSerializer serializer) throws BlueDbException {
		return new BlueObjectOutput<>(path, serializer);
	}

	private BlueObjectOutput(Path path, BlueSerializer serializer) throws BlueDbException {
		try {
			this.lock = null;
			this.path = path;
			this.serializer = serializer;
			this.dataOutputStream = FileUtils.openDataOutputStream(path.toFile());
		} catch (IOException e) {
			throw new BlueDbException("Failed to create BlueObjectOutput for path " + path, e);
		}
	}

	public void writeBytes(byte[] bytes) throws BlueDbException {
		try {
			FileUtils.validateBytes(bytes);
			int len = bytes.length;
			dataOutputStream.writeInt(len);
			dataOutputStream.write(bytes);
		} catch (Throwable t) {
			t.printStackTrace();
			throw new BlueDbException("error writing to file " + path, t);
		}
	}

	public void write(T value) throws BlueDbException {
		if (value == null) {
			throw new BlueDbException("cannot write null to " + this.getClass().getSimpleName());
		}
		try {
			byte[] bytes = serializer.serializeObjectToByteArray(value);
			int len = bytes.length;
			dataOutputStream.writeInt(len);
			dataOutputStream.write(bytes);
		} catch (Throwable t) {
			t.printStackTrace();
			throw new BlueDbException("error writing to file " + path, t);
		}
	}

	public void writeAll(BlueObjectInput<T> input) throws BlueDbException {
		// TODO better protection against hitting overlapping ranges.
		//      There's some protection against this in rollup recovery and 
		//      from single-threaded writes.
		byte[] nextBytes = input.nextWithoutDeserializing();
		while(nextBytes != null && nextBytes.length > 0) {
			writeBytes(nextBytes);
			nextBytes = input.nextWithoutDeserializing();
		}
	}

	@Override
	public void close() {
		if(dataOutputStream != null) {
			try {
				dataOutputStream.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		if(lock != null) {
			lock.close();
		}
	}

}