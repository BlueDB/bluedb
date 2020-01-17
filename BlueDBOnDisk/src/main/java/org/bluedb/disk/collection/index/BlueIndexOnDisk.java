package org.bluedb.disk.collection.index;

import java.io.Serializable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.bluedb.api.exceptions.BlueDbException;
import org.bluedb.api.index.BlueIndex;
import org.bluedb.api.index.KeyExtractor;
import org.bluedb.api.keys.BlueKey;
import org.bluedb.api.keys.ValueKey;
import org.bluedb.disk.BatchUtils;
import org.bluedb.disk.collection.BlueCollectionOnDisk;
import org.bluedb.disk.collection.CollectionEntityIterator;
import org.bluedb.disk.collection.ReadableBlueCollectionOnDisk;
import org.bluedb.disk.file.FileManager;
import org.bluedb.disk.recovery.IndividualChange;
import org.bluedb.disk.segment.Range;
import org.bluedb.disk.segment.Segment;
import org.bluedb.disk.segment.SegmentManager;
import org.bluedb.disk.segment.SegmentSizeSetting;
import org.bluedb.disk.segment.rollup.IndexRollupTarget;
import org.bluedb.disk.segment.rollup.RollupScheduler;
import org.bluedb.disk.segment.rollup.RollupTarget;
import org.bluedb.disk.segment.rollup.Rollupable;
import org.bluedb.disk.serialization.BlueEntity;

public class BlueIndexOnDisk<I extends ValueKey, T extends Serializable> extends ReadableBlueIndexOnDisk<I, T> implements BlueIndex<I, T>, Rollupable {

	private final RollupScheduler rollupScheduler;
	private final String indexName;
	private final SegmentManager<BlueKey> segmentManager;
	private final FileManager fileManager;

	public static <K extends ValueKey, T extends Serializable> BlueIndexOnDisk<K, T> createNew(BlueCollectionOnDisk<T> collection, Path indexPath, KeyExtractor<K, T> keyExtractor) throws BlueDbException {
		indexPath.toFile().mkdirs();
		FileManager fileManager = collection.getFileManager();
		Path keyExtractorPath = Paths.get(indexPath.toString(), FILE_KEY_EXTRACTOR);
		fileManager.saveObject(keyExtractorPath, keyExtractor);
		BlueIndexOnDisk<K, T> index = new BlueIndexOnDisk<K, T>(collection, indexPath, keyExtractor);
		populateNewIndex(collection, index);
		return index;
	}

	private static <K extends ValueKey, T extends Serializable> void populateNewIndex(ReadableBlueCollectionOnDisk<T> collection, BlueIndexOnDisk<K, T> index) throws BlueDbException {
		Range allTime = new Range(Long.MIN_VALUE, Long.MAX_VALUE);
		try (CollectionEntityIterator<T> iterator = new CollectionEntityIterator<T>(collection.getSegmentManager(), allTime, false, Arrays.asList())) {
			while (iterator.hasNext()) {
				List<BlueEntity<T>> entities = iterator.next(1000);
				index.addEntities(entities);
			}
		}
	}

	public static <K extends ValueKey, T extends Serializable> BlueIndexOnDisk<K, T> fromExisting(BlueCollectionOnDisk<T> collection, Path indexPath) throws BlueDbException {
		FileManager fileManager = collection.getFileManager();
		Path keyExtractorPath = Paths.get(indexPath.toString(), FILE_KEY_EXTRACTOR);
		@SuppressWarnings("unchecked")
		KeyExtractor<K, T> keyExtractor = (KeyExtractor<K, T>) fileManager.loadObject(keyExtractorPath);
		return new BlueIndexOnDisk<K, T>(collection, indexPath, keyExtractor);
	}

	private BlueIndexOnDisk(BlueCollectionOnDisk<T> collection, Path indexPath, KeyExtractor<I, T> keyExtractor) throws BlueDbException {
		super(collection, indexPath, keyExtractor);
		this.indexName = indexPath.toFile().getName();
		this.fileManager = collection.getFileManager();
		SegmentSizeSetting sizeSetting = determineSegmentSize(keyExtractor.getType());
		segmentManager = new SegmentManager<BlueKey>(indexPath, fileManager, this, sizeSetting.getConfig());
		if (collection instanceof BlueCollectionOnDisk) {
			rollupScheduler = ((BlueCollectionOnDisk<T>) collection).getRollupScheduler();
		} else {
			rollupScheduler = null;
		}
	}

	public SegmentManager<BlueKey> getSegmentManager() {
		return segmentManager;
	}

	public FileManager getFileManager() {
		return fileManager;
	}

	@Override
	public void reportReads(List<RollupTarget> rollupTargets) {
		if (rollupScheduler != null) {
			List<IndexRollupTarget> indexRollupTargets = toIndexRollupTargets(rollupTargets);
			rollupScheduler.reportReads(indexRollupTargets);
			
		}
	}

	@Override
	public void reportWrites(List<RollupTarget> rollupTargets) {
		if (rollupScheduler != null) {
			List<IndexRollupTarget> indexRollupTargets = toIndexRollupTargets(rollupTargets);
			rollupScheduler.reportWrites(indexRollupTargets);
		}
	}

	private List<IndexRollupTarget> toIndexRollupTargets(List<RollupTarget> rollupTargets) {
		return rollupTargets.stream()
				.map( this::toIndexRollupTarget )
				.collect( Collectors.toList() );
	}

	private IndexRollupTarget toIndexRollupTarget(RollupTarget rollupTarget) {
		return new IndexRollupTarget(indexName, rollupTarget.getSegmentGroupingNumber(), rollupTarget.getRange() );
	}

	public void add(BlueKey key, T newItem) throws BlueDbException {
		if (newItem == null) {
			return;
		}
		for (IndexCompositeKey<I> compositeKey: toCompositeKeys(key, newItem)) {
			Segment<BlueKey> segment = getSegmentManager().getFirstSegment(compositeKey);
			segment.insert(compositeKey, key);
		}
	}

	public void addEntities(Collection<BlueEntity<T>> entities) throws BlueDbException {
		List<IndividualChange<BlueKey>> sortedIndexChanges = entities.stream()
				.map( this::toIndexChanges )
				.flatMap(List::stream)
				.sorted()
				.collect(Collectors.toList());
		BatchUtils.apply(getSegmentManager(), sortedIndexChanges);
	}

	public void add(Collection<IndividualChange<T>> changes) throws BlueDbException {
		List<IndividualChange<BlueKey>> sortedIndexChanges = toSortedIndexChanges(changes);
		BatchUtils.apply(getSegmentManager(), sortedIndexChanges);
	}

	public void remove(BlueKey key, T oldItem) throws BlueDbException {
		if (oldItem == null) {
			return;
		}
		for (IndexCompositeKey<I> compositeKey: toCompositeKeys(key, oldItem)) {
			Segment<BlueKey> segment = getSegmentManager().getFirstSegment(compositeKey);
			segment.delete(compositeKey);
		}
	}

	public void rollup(Range range) throws BlueDbException {
		Segment<?> segment = getSegmentManager().getSegment(range.getStart());
		segment.rollup(range);
	}

	private List<IndividualChange<BlueKey>> toSortedIndexChanges(Collection<IndividualChange<T>> changes) {
		return changes.stream()
				.map( this::toIndexChanges )
				.flatMap(List::stream)
				.sorted()
				.collect(Collectors.toList());
	}

	private List<IndividualChange<BlueKey>> toIndexChanges(BlueEntity<T> entity) {
		BlueKey key = entity.getKey();
		T newValue = entity.getValue();
		List<IndexCompositeKey<I>> compositeKeys = toCompositeKeys(key, newValue);
		return toIndexChanges(compositeKeys, key);
	}

	private List<IndividualChange<BlueKey>> toIndexChanges(IndividualChange<T> change) {
		List<IndexCompositeKey<I>> compositeKeys = toCompositeKeys(change);
		BlueKey underlyingKey = change.getKey();
		return toIndexChanges(compositeKeys, underlyingKey);
	}

	private static <I extends ValueKey> List<IndividualChange<BlueKey>> toIndexChanges(List<IndexCompositeKey<I>> compositeKeys, BlueKey destinationKey) {
		List<IndividualChange<BlueKey>> indexChanges = new ArrayList<>();
		for (IndexCompositeKey<I> compositeKey: compositeKeys) {
			IndividualChange<BlueKey> indexChange = IndividualChange.createInsertChange(compositeKey, destinationKey);
			indexChanges.add(indexChange);
		}
		return indexChanges;
	}

	private List<IndexCompositeKey<I>> toCompositeKeys(IndividualChange<T> change) {
		BlueKey destinationKey = change.getKey();
		T newValue = change.getNewValue();
		return toCompositeKeys(destinationKey, newValue);
	}

	private List<IndexCompositeKey<I>> toCompositeKeys(BlueKey destination, T newItem) {
		List<I> indexKeys = keyExtractor.extractKeys(newItem);
		return indexKeys.stream()
				.map( (indexKey) -> new IndexCompositeKey<I>(indexKey, destination) )
				.collect( Collectors.toList() );
	}
}
