package org.bluedb.disk;

import java.io.Serializable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.bluedb.api.exceptions.BlueDbException;
import org.bluedb.api.keys.BlueKey;
import org.bluedb.disk.recovery.IndividualChange;
import org.bluedb.disk.segment.Range;
import org.bluedb.disk.segment.ReadWriteSegment;
import org.bluedb.disk.segment.SegmentBatch;
import org.bluedb.disk.segment.ReadWriteSegmentManager;

public class BatchUtils {
	public static <T extends Serializable> void apply(ReadWriteSegmentManager<T> segmentManager, List<IndividualChange<T>> sortedChanges) throws BlueDbException {
		LinkedList<IndividualChange<T>> unqueuedChanges = new LinkedList<>(sortedChanges);
		while (!unqueuedChanges.isEmpty()) {
			ReadWriteSegment<T> nextSegment = getFirstSegmentAffected(segmentManager, unqueuedChanges);
			LinkedList<IndividualChange<T>> queuedChanges = pollChangesInSegment(unqueuedChanges, nextSegment);
			while (!queuedChanges.isEmpty()) {
				nextSegment.applyChanges(queuedChanges);
				removeChangesThatEndInOrBeforeSegment(queuedChanges, nextSegment);
				nextSegment = segmentManager.getSegmentAfter(nextSegment);
				queuedChanges.addAll( pollChangesInSegment(unqueuedChanges, nextSegment) );
			}
		}
	}

	public static <T extends Serializable> LinkedList<IndividualChange<T>> pollChangesInSegment(LinkedList<IndividualChange<T>> sortedChanges, ReadWriteSegment<T> segment) {
		long maxGroupingNumber = segment.getRange().getEnd();
		return SegmentBatch.pollChangesBeforeOrAt(sortedChanges, maxGroupingNumber);
	}

	protected static <T extends Serializable> ReadWriteSegment<T> getFirstSegmentAffected(ReadWriteSegmentManager<T> segmentManager, LinkedList<IndividualChange<T>> sortedChanges) {
		BlueKey firstChangeKey = sortedChanges.peek().getKey();
		ReadWriteSegment<T> firstSegment = segmentManager.getFirstSegment(firstChangeKey);
		return firstSegment;
	}

	protected static <T extends Serializable> void removeChangesThatEndInOrBeforeSegment(List<IndividualChange<T>> sortedChanges, ReadWriteSegment<T> segment) {
		Range segmentRange = segment.getRange();
		Range beyondSegment = new Range(segmentRange.getEnd() + 1, Long.MAX_VALUE);
		Iterator<IndividualChange<T>> iterator = sortedChanges.iterator();
		while (iterator.hasNext()) {
			IndividualChange<T> nextChange = iterator.next();
			if (!nextChange.overlaps(beyondSegment)) {
				iterator.remove();
			} else if (!nextChange.overlaps(segmentRange)) {
				return;  // we've past all the changes in this segment
			}
		}
	}
}
