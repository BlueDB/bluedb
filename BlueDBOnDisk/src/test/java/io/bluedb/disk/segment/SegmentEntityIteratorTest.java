package io.bluedb.disk.segment;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import io.bluedb.api.exceptions.BlueDbException;
import io.bluedb.api.keys.BlueKey;
import io.bluedb.disk.BlueDbDiskTestBase;
import io.bluedb.disk.TestValue;
import io.bluedb.disk.file.BlueObjectInput;
import io.bluedb.disk.file.BlueObjectOutput;
import io.bluedb.disk.serialization.BlueEntity;

public class SegmentEntityIteratorTest extends BlueDbDiskTestBase {

	@Test
	public void test_close() throws Exception {
		Segment<TestValue> segment = getSegment(1);
		BlueKey key = createKey(1, 1);
		TestValue value = createValue("Anna");

		segment.insert(key, value);
		SegmentEntityIterator<TestValue> iterator = segment.getIterator(1, 1);
		assertNull(iterator.getCurrentPath());  // it should not have opened anything until it needs it
		iterator.hasNext();  // force it to open the next file
		assertNotNull(iterator.getCurrentPath());
		assertTrue(getLockManager().isLocked(iterator.getCurrentPath()));
		iterator.close();
		assertFalse(getLockManager().isLocked(iterator.getCurrentPath()));
	}

	@Test
	public void test_different_ranges() throws Exception {
		Segment<TestValue> segment = getSegment(1);
		BlueKey key1 = createKey(1, 1);
		BlueKey key2 = createKey(2, 2);
		TestValue value1 = createValue("Anna");
		TestValue value2 = createValue("Bob");
		
		segment.insert(key1, value1);
		segment.insert(key2, value2);

		SegmentEntityIterator<TestValue> iterator = segment.getIterator(0, 0);
		List<BlueEntity<TestValue>> entities = toList(iterator);
		iterator.close();
		assertEquals(0, entities.size());

		iterator = segment.getIterator(3, 4);
		entities = toList(iterator);
		iterator.close();
		assertEquals(0, entities.size());

		iterator = segment.getIterator(1, 1);
		entities = toList(iterator);
		iterator.close();
		assertEquals(1, entities.size());

		iterator = segment.getIterator(1, 2);
		entities = toList(iterator);
		iterator.close();
		assertEquals(2, entities.size());
	}

	@Test
	public void test_hasNext() throws Exception {
		Segment<TestValue> segment = getSegment(1);
		BlueKey key1 = createKey(1, 1);
		BlueKey key2 = createKey(2, 2);
		TestValue value1 = createValue("Anna");
		TestValue value2 = createValue("Bob");

		segment.insert(key1, value1);
		segment.insert(key2, value2);
		SegmentEntityIterator<TestValue> iterator = segment.getIterator(1, 2);
		assertTrue(iterator.hasNext());
		assertTrue(iterator.hasNext());  // make sure the second call doesn't break anything
		iterator.next();
		assertTrue(iterator.hasNext());
		iterator.next();
		assertFalse(iterator.hasNext());
		iterator.close();
	}

	@Test
	public void test_next() throws Exception {
		Segment<TestValue> segment = getSegment(1);
		BlueKey key1 = createKey(1, 1);
		BlueKey key2 = createKey(2, 2);
		TestValue value1 = createValue("Anna");
		TestValue value2 = createValue("Bob");

		segment.insert(key1, value1);
		segment.insert(key2, value2);
		SegmentEntityIterator<TestValue> iterator = segment.getIterator(1, 2);
		assertTrue(iterator.hasNext());
		iterator.next();
		assertTrue(iterator.hasNext());
		iterator.next();
		assertFalse(iterator.hasNext());
		iterator.close();
	}

	@Test
	public void test_next_rollup_before_reads() throws Exception {
		Segment<TestValue> segment = getSegment(1);
		BlueKey key1 = createKey(1, 1);
		BlueKey key2 = createKey(2, 2);
		TestValue value1 = createValue("Anna");
		TestValue value2 = createValue("Bob");

		segment.insert(key1, value1);
		segment.insert(key2, value2);
		SegmentEntityIterator<TestValue> iterator = segment.getIterator(1, 2); // it should now have two ranges to search
		List<BlueEntity<TestValue>> entities = toList(iterator);
		assertEquals(2, entities.size());
		
		iterator = segment.getIterator(1, 2); // it should now have two ranges to search
		long segmentSize = getTimeCollection().getSegmentManager().getSegmentSize();
		Range range = new Range(0, segmentSize -1);
		segment.rollup(range);
		entities = toList(iterator);
		assertEquals(2, entities.size());

		iterator.close();
	}

	@Test
	public void test_next_rollup_during_reads() throws Exception {
		Segment<TestValue> segment = getSegment(1);
		BlueKey key1 = createKey(1, 1);
		BlueKey key2 = createKey(2, 2);
		TestValue value1 = createValue("Anna");
		TestValue value2 = createValue("Bob");

		segment.insert(key1, value1);
		segment.insert(key2, value2);
		SegmentEntityIterator<TestValue> iterator = segment.getIterator(1, 2); // it should now have two files to read
		List<BlueEntity<TestValue>> entities = new ArrayList<>();
		entities.add(iterator.next()); // read one from the first file;

		// simulate a rollup from underneath us
		long segmentSize = getTimeCollection().getSegmentManager().getSegmentSize();
		Range range = new Range(0, segmentSize -1);
		Path rolledUpPath = Paths.get(segment.getPath().toString(), range.toUnderscoreDelimitedString());
		try (BlueObjectOutput<BlueEntity<TestValue>> output = segment.getObjectOutputFor(rolledUpPath)) {
			output.write(new BlueEntity<TestValue>(key1, value1));
			output.write(new BlueEntity<TestValue>(key2, value2));
		}
		Range rangeToRemove = new Range(2,2);
		Path pathToRemove = Paths.get(segment.getPath().toString(), rangeToRemove.toUnderscoreDelimitedString());
		assertTrue(pathToRemove.toFile().delete());

		// add the remaining items
		while (iterator.hasNext()) {
			BlueEntity<TestValue> next = iterator.next();
			entities.add(next);
		}

		assertEquals(2, entities.size());

		iterator.close();
	}

	@Test
	public void test_getNextStream_file_deleted() throws Exception {
		Segment<TestValue> segment = getSegment(1);
		BlueKey key1 = createKey(1, 1);
		BlueKey key2 = createKey(2, 2);
		TestValue value1 = createValue("Anna");
		TestValue value2 = createValue("Bob");

		segment.insert(key1, value1);
		segment.insert(key2, value2);
		SegmentEntityIterator<TestValue> iterator = segment.getIterator(1, 2); // it should now have two files to read
		List<BlueEntity<TestValue>> entities = new ArrayList<>();
		entities.add(iterator.next()); // read one from the first file;

		// rip out the second file
		Range rangeToRemove = new Range(2,2);
		Path pathToRemove = Paths.get(segment.getPath().toString(), rangeToRemove.toUnderscoreDelimitedString());
		assertTrue(pathToRemove.toFile().delete());

		// add the remaining items
		while (iterator.hasNext()) {
			BlueEntity<TestValue> next = iterator.next();
			entities.add(next);
		}

		assertEquals(1, entities.size());

		iterator.close();
	}

	@Test
	public void test_getNextStream_exception() throws Exception {
		Segment<TestValue> segment = getSegment(1);
		BlueKey key1 = createKey(1, 1);
		BlueKey key2 = createKey(2, 2);
		TestValue value1 = createValue("Anna");
		TestValue value2 = createValue("Bob");
		segment.insert(key1, value1);
		segment.insert(key2, value2);

		
		SegmentEntityIterator<TestValue> iterator = segment.getIterator(1, 2);
		List<BlueEntity<TestValue>> entitiesInRealSegment = new ArrayList<BlueEntity<TestValue>>();
		while(iterator.hasNext()) {
			entitiesInRealSegment.add(iterator.next());
		}
		
		assertEquals(2, entitiesInRealSegment.size());

		Segment<TestValue> mockSegment = new Segment<TestValue>(segment.getPath(), null, getTimeCollection(), null) {
			@Override
			protected BlueObjectInput<BlueEntity<TestValue>> getObjectInputFor(long groupingNumber) throws BlueDbException {
				throw new BlueDbException("segment fail");
			}
		};
		
		SegmentEntityIterator<TestValue> iterator2 = mockSegment.getIterator(1, 2);
		List<BlueEntity<TestValue>> entitiesFromBrokenSegment = toEntityList(iterator2);
		assertEquals(0, entitiesFromBrokenSegment.size());
	}

	@Test
	public void test_getNext_multiple_time_frames() {
		long segmentSize = getTimeCollection().getSegmentManager().getSegmentSize();
		Segment<TestValue> firstSegment = getSegment(0);
		Segment<TestValue> secondSegment = getSegment(segmentSize);
		
		TestValue valueInFirstSegment = new TestValue("first");
		TestValue valueInBothSegments = new TestValue("both");
		TestValue valueInSecondSegment = new TestValue("second");
		TestValue valueAfterSecondSegment = new TestValue("after");
		insertAtTimeFrame(0, 1, valueInFirstSegment);
		insertAtTimeFrame(0, segmentSize, valueInBothSegments);
		insertAtTimeFrame(segmentSize, segmentSize + 1, valueInSecondSegment);
		insertAtTimeFrame(segmentSize * 2, segmentSize * 2 + 1, valueAfterSecondSegment);
		List<TestValue> valuesExpectedInFirstSegment = Arrays.asList(valueInFirstSegment, valueInBothSegments);
		List<TestValue> valuesExpectedInSecondSegment = Arrays.asList(valueInBothSegments, valueInSecondSegment);
		List<TestValue> valuesExpectedInSecondSegmentOnly = Arrays.asList(valueInSecondSegment);

		SegmentEntityIterator<TestValue> firstSegmentIterator = firstSegment.getIterator(0, segmentSize - 1);
		List<TestValue> valuesFromFirstSegment = toValueList(firstSegmentIterator);
		SegmentEntityIterator<TestValue> secondSegmentIterator = secondSegment.getIterator(0, segmentSize * 2 - 1);
		List<TestValue> valuesFromSecondSegment = toValueList(secondSegmentIterator);
		SegmentEntityIterator<TestValue> secondSegmentIteratorOnly = secondSegment.getIterator(segmentSize - 1, 0, segmentSize * 2 - 1);
		List<TestValue> valuesFromSecondSegmentOnly = toValueList(secondSegmentIteratorOnly);

		assertEquals(valuesExpectedInFirstSegment, valuesFromFirstSegment);
		assertEquals(valuesExpectedInSecondSegment, valuesFromSecondSegment);
		assertEquals(valuesExpectedInSecondSegmentOnly, valuesFromSecondSegmentOnly);
	}
}
