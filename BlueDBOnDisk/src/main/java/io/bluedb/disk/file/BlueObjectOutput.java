package io.bluedb.disk.file;

import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;

import io.bluedb.api.exceptions.BlueDbException;
import io.bluedb.disk.lock.BlueWriteLock;
import io.bluedb.disk.lock.LockManager;
import io.bluedb.disk.serialization.BlueSerializer;

public class BlueObjectOutput<T> implements Closeable {

	private final BlueWriteLock<Path> lock;
	private final Path path;
	private final BlueSerializer serializer;
	private final DataOutputStream dataOutputStream;

	public BlueObjectOutput(BlueWriteLock<Path> writeLock, BlueSerializer serializer) throws BlueDbException {
		lock = writeLock;
		path = lock.getKey();
		this.serializer = serializer;
		File file = path.toFile();
		FileManager.ensureDirectoryExists(file);
		dataOutputStream = openDataOutputStream(file);
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

	public void writeBytes(byte[] bytes) throws BlueDbException {
		if (bytes == null) {
			throw new BlueDbException("cannot write null to " + this.getClass().getSimpleName());
		}
		try {
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
		while(input.hasNext()) {
			input.next();
			writeBytes(input.getLastBytes());
		}
	}

	@Override
	public void close() {
		try {
			dataOutputStream.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		lock.close();
	}

	protected static DataOutputStream openDataOutputStream(File file) throws BlueDbException {
		try {
			return new DataOutputStream(new FileOutputStream(file));
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			throw new BlueDbException("cannot open write to file " + file.toPath(), e);
		}
	}
}