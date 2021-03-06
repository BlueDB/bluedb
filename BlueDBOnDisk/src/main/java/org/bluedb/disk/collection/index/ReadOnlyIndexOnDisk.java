package org.bluedb.disk.collection.index;

import java.io.Serializable;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.bluedb.api.exceptions.BlueDbException;
import org.bluedb.api.index.BlueIndex;
import org.bluedb.api.index.KeyExtractor;
import org.bluedb.api.keys.BlueKey;
import org.bluedb.api.keys.ValueKey;
import org.bluedb.disk.collection.ReadOnlyCollectionOnDisk;
import org.bluedb.disk.file.ReadOnlyFileManager;
import org.bluedb.disk.segment.ReadOnlySegmentManager;
import org.bluedb.disk.segment.SegmentSizeSetting;

public class ReadOnlyIndexOnDisk<I extends ValueKey, T extends Serializable> extends ReadableIndexOnDisk<I, T> implements BlueIndex<I, T> {

	private final ReadOnlySegmentManager<BlueKey> segmentManager;
	private final ReadOnlyFileManager fileManager;

	public static <K extends ValueKey, T extends Serializable> ReadOnlyIndexOnDisk<K, T> fromExisting(ReadOnlyCollectionOnDisk<T> collection, Path indexPath) throws BlueDbException {
		ReadOnlyFileManager fileManager = collection.getFileManager();
		Path keyExtractorPath = Paths.get(indexPath.toString(), FILE_KEY_EXTRACTOR);
		@SuppressWarnings("unchecked")
		KeyExtractor<K, T> keyExtractor = (KeyExtractor<K, T>) fileManager.loadObject(keyExtractorPath);
		return new ReadOnlyIndexOnDisk<K, T>(collection, indexPath, keyExtractor);
	}

	private ReadOnlyIndexOnDisk(ReadOnlyCollectionOnDisk<T> collection, Path indexPath, KeyExtractor<I, T> keyExtractor) throws BlueDbException {
		super(collection, indexPath, keyExtractor);
		this.fileManager = collection.getFileManager();
		SegmentSizeSetting sizeSetting = determineSegmentSize(keyExtractor.getType());
		segmentManager = new ReadOnlySegmentManager<BlueKey>(indexPath, fileManager, sizeSetting.getConfig());
	}

	@Override
	public ReadOnlySegmentManager<BlueKey> getSegmentManager() {
		return segmentManager;
	}

}
