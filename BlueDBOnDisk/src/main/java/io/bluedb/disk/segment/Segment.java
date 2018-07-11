package io.bluedb.disk.segment;

import java.io.File;
import java.io.FileFilter;
import java.io.Serializable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import io.bluedb.api.exceptions.BlueDbException;
import io.bluedb.api.exceptions.DuplicateKeyException;
import io.bluedb.api.keys.BlueKey;
import io.bluedb.disk.file.BlueObjectInput;
import io.bluedb.disk.file.BlueObjectOutput;
import io.bluedb.disk.file.BlueReadLock;
import io.bluedb.disk.file.BlueWriteLock;
import io.bluedb.disk.file.FileManager;
import io.bluedb.disk.file.LockManager;
import io.bluedb.disk.serialization.BlueEntity;

public class Segment <T extends Serializable> {

	private final static Long SEGMENT_SIZE = SegmentManager.LEVEL_3;
	private final static Long[] ROLLUP_LEVELS = {1L, 3125L, SEGMENT_SIZE};

	private final FileManager fileManager;
	private final Path segmentPath;
	private final LockManager<Path> lockManager;

	public Segment(Path segmentPath, FileManager fileManager) {
		this.segmentPath = segmentPath;
		this.fileManager = fileManager;
		lockManager = fileManager.getLockManager();
	}

	// for testing only
	protected Segment() {segmentPath = null;fileManager = null;lockManager = null;}

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
//					} else if (newEntity != null && iterKey.getGroupingNumber() > groupingNumber) {
//						output.write(newEntity);
//						newEntity = null;
//						output.write(iterEntity);
					} else {
						output.write(iterEntity);
					}
				}
//				if (newEntity != null) {
//					output.write(newEntity);
//				}
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

		try (BlueObjectInput<BlueEntity<T>> input = getObjectInputFor(groupingNumber)) {
			targetPath = input.getPath();
			tmpPath = FileManager.createTempFilePath(targetPath);
			try(BlueObjectOutput<BlueEntity<T>> output = getObjectOutputFor(tmpPath)) {
				processor.process(input, output);
			}
		}

		try (BlueWriteLock<Path> targetFileLock = lockManager.acquireWriteLock(targetPath)) {
			FileManager.moveFile(tmpPath, targetFileLock);
		}
	}

	public T get(BlueKey key) throws BlueDbException {
		long groupingNumber = key.getGroupingNumber();
		try(BlueObjectInput<BlueEntity<T>> inputStream = getObjectInputFor(groupingNumber)) {
			return get(key, inputStream);
		}
	}

	public SegmentEntityIterator<T> getIterator(long min, long max) {
		return new SegmentEntityIterator<>(this, min, max);
	}

	public void rollup(TimeRange timeRange) throws BlueDbException {
		long rollupSize = timeRange.getEnd() - timeRange.getStart() + 1;  // Note: can overflow
		boolean isValidRollupSize = Arrays.asList(ROLLUP_LEVELS).contains(rollupSize);
		if (!isValidRollupSize) {
			throw new BlueDbException("Not a valid rollup size: " + timeRange);
		}
		List<File> filesToRollup = getOrderedFilesInRange(timeRange);
		Path path = Paths.get(segmentPath.toString(), timeRange.toUnderscoreDelimitedString());
		Path tmpPath = FileManager.createTempFilePath(path);

		copy(tmpPath, filesToRollup);
		moveRolledUpFileAndDeleteSourceFiles(path, tmpPath, filesToRollup);
	}

	private void copy(Path destination, List<File> sources) throws BlueDbException {
		try(BlueObjectOutput<BlueEntity<T>> output = getObjectOutputFor(destination)) {
			for (File file: sources) {
				try(BlueObjectInput<BlueEntity<T>> inputStream = getObjectInputFor(file.toPath())) {
					output.writeAll(inputStream);
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

			// TODO figure out how to recover if we crash here, must be done before any writes
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

	protected List<File> getOrderedFilesInRange(TimeRange range) {
		return getOrderedFilesInRange(segmentPath, range);
	}

	protected static List<File> getOrderedFilesInRange(Path segmentPath, TimeRange range) {
		long min = range.getStart();
		long max = range.getEnd();
		File segmentFolder = segmentPath.toFile();
		FileFilter filter = (f) -> doesfileNameRangeOverlap(f, min, max);
		List<File> filesInFolder = FileManager.getFolderContents(segmentFolder, filter);
		sortByRange(filesInFolder);
		return filesInFolder;
	}

	protected static void sortByRange(List<File> files) {
		Comparator<File> comparator = new Comparator<File>() {
			@Override
			public int compare(File o1, File o2) {
				TimeRange r1 = TimeRange.fromUnderscoreDelmimitedString(o1.getName());
				TimeRange r2 = TimeRange.fromUnderscoreDelmimitedString(o2.getName());
				return r1.compareTo(r2);
			}
		};
		Collections.sort(files, comparator);
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

	protected BlueObjectOutput<BlueEntity<T>> getObjectOutputFor(Path path) throws BlueDbException {
		BlueWriteLock<Path> lock = lockManager.acquireWriteLock(path);
		return fileManager.getBlueOutputStream(lock);
	}

	protected BlueObjectInput<BlueEntity<T>> getObjectInputFor(Path path) throws BlueDbException {
		BlueReadLock<Path> lock = lockManager.acquireReadLock(path);
		return fileManager.getBlueInputStream(lock);
	}

	protected BlueObjectInput<BlueEntity<T>> getObjectInputFor(long groupingNumber) throws BlueDbException {
		BlueReadLock<Path> lock = getReadLockFor(groupingNumber);
		return fileManager.getBlueInputStream(lock);
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

	public Path getPath() {
		return segmentPath;
	}

	private Path getPathFor(long groupingNumber, long rollupLevel) {
		String fileName = SegmentManager.getRangeFileName(groupingNumber, rollupLevel);
		return Paths.get(segmentPath.toString(), fileName);
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
