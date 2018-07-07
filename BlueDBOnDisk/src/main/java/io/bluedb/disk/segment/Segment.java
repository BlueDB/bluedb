package io.bluedb.disk.segment;

import java.io.File;
import java.io.Serializable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import io.bluedb.api.exceptions.BlueDbException;
import io.bluedb.api.exceptions.DuplicateKeyException;
import io.bluedb.api.keys.BlueKey;
import io.bluedb.api.keys.TimeFrameKey;
import io.bluedb.disk.Blutils;
import io.bluedb.disk.file.BlueObjectInput;
import io.bluedb.disk.file.BlueObjectOutput;
import io.bluedb.disk.file.BlueReadLock;
import io.bluedb.disk.file.BlueWriteLock;
import io.bluedb.disk.file.FileManager;
import io.bluedb.disk.file.LockManager;
import io.bluedb.disk.serialization.BlueEntity;

public class Segment <T extends Serializable> {

	private final static long SEGMENT_SIZE = SegmentManager.LEVEL_0;
	private final static long[] ROLLUP_LEVELS = {1, 3125, SEGMENT_SIZE};

	private final FileManager fileManager;
	private final Path segmentPath;
	private final LockManager<Path> lockManager;

	public Segment(Path segmentPath, FileManager fileManager) {
		this.segmentPath = segmentPath;
		this.fileManager = fileManager;
		lockManager = fileManager.getLockManager();
	}

	// for testing only
	protected Segment() {
		segmentPath = null;
		fileManager = null;
		lockManager = null;
	}

	@Override
	public String toString() {
		return "<Segment for path " + segmentPath.toString() + ">";
	}

	public boolean contains(BlueKey key) throws BlueDbException {
		return get(key) != null;
	}

	interface Processor<X extends Serializable> {
		public void process(BlueObjectInput<BlueEntity<X>> input, BlueObjectOutput<BlueEntity<X>> output) throws BlueDbException;
	}

	public void update(BlueKey newKey, T newValue) throws BlueDbException {
		long groupingNumber = newKey.getGroupingNumber();
		modifyChunk(groupingNumber, new Processor<T>() {
			@Override
			public void process(BlueObjectInput<BlueEntity<T>> input, BlueObjectOutput<BlueEntity<T>> output) throws BlueDbException {
				BlueEntity<T> newEntity = new BlueEntity<T>(newKey, newValue);
				while (input.hasNext()) {
					BlueEntity<T> iterEntity = input.next();
					BlueKey iterKey = iterEntity.getKey();
					if (iterKey.equals(newKey)) {
						output.write(newEntity);
						newEntity = null;
					} else if (newEntity != null && iterKey.getGroupingNumber() > groupingNumber) {
						output.write(newEntity);
						newEntity = null;
						output.write(iterEntity);
					} else {
						output.write(iterEntity);
					}
				}
				if (newEntity != null) {
					output.write(newEntity);
				}
			}
		});
	}

	public void insert(BlueKey newKey, T newValue) throws BlueDbException {
		BlueEntity<T> newEntity = new BlueEntity<T>(newKey, newValue);
		long groupingNumber = newKey.getGroupingNumber();
		modifyChunk(groupingNumber, new Processor<T>() {
			@Override
			public void process(BlueObjectInput<BlueEntity<T>> input, BlueObjectOutput<BlueEntity<T>> output) throws BlueDbException {
				BlueEntity<T> toInsert = newEntity;
				while (input.hasNext()) {
					BlueEntity<T> iterEntity = input.next();
					BlueKey iterKey = iterEntity.getKey();
					if (iterKey.equals(newKey)) {
						throw new DuplicateKeyException("attempt to insert duplicate key", newKey);
					} else if (toInsert != null && iterKey.getGroupingNumber() > groupingNumber) {
						output.write(newEntity);
						toInsert = null;
						output.write(iterEntity);
					} else {
						output.write(iterEntity);
					}
				}
				if (toInsert != null) {
					output.write(newEntity);
				}
			}
		});
	}

	public void delete(BlueKey key) throws BlueDbException {
		long groupingNumber = key.getGroupingNumber();
		modifyChunk(groupingNumber, new Processor<T>() {
			@Override
			public void process(BlueObjectInput<BlueEntity<T>> input, BlueObjectOutput<BlueEntity<T>> output) throws BlueDbException {
				while (input.hasNext()) {
					BlueEntity<T> entry = input.next();
					if (!entry.getKey().equals(key)) {
						output.write(entry);
					}
				}
			}
		});
	}

	public void modifyChunk(long groupingNumber, Processor<T> processor) throws BlueDbException {
		Path targetPath, tmpPath;

		try (BlueReadLock<Path> readLock = getReadLockFor(groupingNumber)) {
			targetPath = readLock.getKey();
			FileManager.ensureFileExists(targetPath);
			tmpPath = FileManager.createTempFilePath(targetPath);
			try (BlueWriteLock<Path> tempFileLock = lockManager.acquireWriteLock(tmpPath)) {
				try(BlueObjectOutput<BlueEntity<T>> output = fileManager.getBlueOutputStream(tempFileLock)) {
					try(BlueObjectInput<BlueEntity<T>> input = fileManager.getBlueInputStream(readLock)) {
						processor.process(input, output);
					}
				}
			}
		}

		try (BlueWriteLock<Path> targetFileLock = lockManager.acquireWriteLock(targetPath)) {
			FileManager.moveFile(tmpPath, targetFileLock);
		}
	}

	public T get(BlueKey key) throws BlueDbException {
		try (BlueReadLock<Path> readLock = getReadLockFor(key)) {
			if (!readLock.getKey().toFile().exists()) {
				return null;
			}
			try(BlueObjectInput<BlueEntity<T>> inputStream = fileManager.getBlueInputStream(readLock)) {
				return get(key, inputStream);
			}
		}
	}

	public List<T> getAll() throws BlueDbException {
		return getRange(Long.MIN_VALUE, Long.MAX_VALUE)
				.stream()
				.map((e) -> e.getValue())
				.collect(Collectors.toList());
	}

    public List<BlueEntity<T>> getRange(long minTime, long maxTime) throws BlueDbException {
		File folder = segmentPath.toFile();
		// Note that we cannot bound this from below because a TimeRangeKey that overlaps the target range
		//      will be stored at the start time;
		List<File> relevantFiles = FileManager.getFolderContents(folder, (f) -> doesfileNameRangeOverlap(f, Long.MIN_VALUE, maxTime));
		List<BlueEntity<T>> results = new ArrayList<>();
		for (File file: relevantFiles) {
			Path path = file.toPath();
			try (BlueReadLock<Path> readLock = lockManager.acquireReadLock(path)) {
				if (!readLock.getKey().toFile().exists())
					continue;
				try(BlueObjectInput<BlueEntity<T>> inputStream = fileManager.getBlueInputStream(readLock)) {
					while(inputStream.hasNext()) {
						BlueEntity<T> next = inputStream.next();
						BlueKey key = next.getKey();
						if (inTimeRange(minTime, maxTime, key))
							results.add(next);
					}
				}
			}
		}
		return results;
    }

    // TODO make private?  or somehow enforce standard levels
	public void rollup(long start, long end) throws BlueDbException {
		List<File> filesToRollup = getFilesInRange(start, end);
		Path path = Paths.get(segmentPath.toString(), start + "_" + end);
		Path tmpPath = FileManager.createTempFilePath(path);

		copy(tmpPath, filesToRollup);
		moveRolledUpFileAndDeleteSourceFiles(path, tmpPath, filesToRollup);
	}

	// TODO keep sorted, here and everywhere you write
	private void copy(Path destination, List<File> sources) throws BlueDbException {
		try (BlueWriteLock<Path> tempFileLock = lockManager.acquireWriteLock(destination)) {
			try(BlueObjectOutput<BlueEntity<T>> outputStream = fileManager.getBlueOutputStream(tempFileLock)) {
				for (File file: sources) {
					try(BlueReadLock<Path> readLock = lockManager.acquireReadLock(file.toPath())) {
						try(BlueObjectInput<BlueEntity<T>> inputStream = fileManager.getBlueInputStream(readLock)) {
							transferAllObjects(inputStream, outputStream);
						}
					}
				}
			}
		}
	}

	private void moveRolledUpFileAndDeleteSourceFiles(Path newRolledupPath, Path tempRolledupPath, List<File> filesToRollup) throws BlueDbException {
		List<BlueWriteLock<Path>> sourceFileWriteLocks = new ArrayList<>();
		try (BlueWriteLock<Path> targetFileLock = lockManager.acquireWriteLock(newRolledupPath)){
			for (File file: filesToRollup) {
				sourceFileWriteLocks.add(lockManager.acquireWriteLock(file.toPath()));
			}

			// TODO figure out how to recover if we crash here
			FileManager.moveFile(tempRolledupPath, targetFileLock);
			for (BlueWriteLock<Path> writeLock: sourceFileWriteLocks) {
				FileManager.deleteFile(writeLock);
			}
		} finally {
			for (BlueWriteLock<Path> lock: sourceFileWriteLocks) {
				lock.release();
			}
		}
	}

	// TODO test
	protected List<File> getFilesInRange(long min, long max) {
		List<File> filesInFolder = FileManager.getFolderContents(segmentPath.toFile());
		return Blutils.filter(filesInFolder, (f) -> doesfileNameRangeOverlap(f, min, max));
	}

	protected static boolean doesfileNameRangeOverlap(File file, long min, long max ) {
		try {
			String[] splits = file.getName().split("_");
			if (splits.length < 2) {
				return false;
			}
			long start = Long.valueOf(splits[0]);
			long end = Long.valueOf(splits[1]);
			return (start <= max) && (end >= min);
		} catch (Throwable t) {
			return false;
		}
	}

	protected BlueReadLock<Path> getReadLockFor(BlueKey key) {
		long groupingNumber = key.getGroupingNumber();
		return getReadLockFor(groupingNumber);
	}

	protected BlueReadLock<Path> getReadLockFor(long groupingNumber) {
		for (long rollupLevel: ROLLUP_LEVELS) {
			Path path = getPathFor(groupingNumber, rollupLevel);
			BlueReadLock<Path> lock = lockManager.acquireReadLock(path);
			try {
				if (lock.getKey().toFile().exists()) {
					return lock;
				}
			} catch (Throwable t) { // make sure we don't hold onto the lock if there's an exception
			}
			lock.release();
		}
		Path path = getPathFor(groupingNumber, 1);
		return lockManager.acquireReadLock(path);
	}

	protected Path getPath() {
		return segmentPath;
	}

    protected static <X> void transferAllObjects(BlueObjectInput<X> input, BlueObjectOutput<X> output) throws BlueDbException {
		while(input.hasNext()) {
			X next = input.next();
			output.write(next);
		}
    }

	private Path getPathFor(long groupingNumber, long rollupLevel) {
		String fileName = SegmentManager.getRangeFileName(groupingNumber, rollupLevel);
		return Paths.get(segmentPath.toString(), fileName);
	}

	private static boolean inTimeRange(long minTime, long maxTime, BlueKey key) {
		if (key instanceof TimeFrameKey) {
			TimeFrameKey timeFrameKey = (TimeFrameKey) key;
			return timeFrameKey.getEndTime() >= minTime && timeFrameKey.getStartTime() <= maxTime;
		} else {
			return key.getGroupingNumber() >= minTime && key.getGroupingNumber() <= maxTime;
		}
	}

	protected static <T extends Serializable> T get(BlueKey key, BlueObjectInput<BlueEntity<T>> inputStream) {
		while(inputStream.hasNext()) {
			BlueEntity<T> next = inputStream.next();
			if (next.getKey().equals(key)) {
				return next.getValue();
			}
		}
		return null;
	}

	@Override
	public int hashCode() {
		return 31 + ((segmentPath == null) ? 0 : segmentPath.hashCode());
	}

	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof Segment)) {
			return false;
		}
		Segment<?> other = (Segment<?>) obj;
		if (segmentPath == null) {
			return other.segmentPath == null;
		} else {
			return segmentPath.equals(other.segmentPath);
		}
	}
}
