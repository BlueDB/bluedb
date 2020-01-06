package org.bluedb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.bluedb.api.exceptions.BlueDbException;
import org.bluedb.api.keys.BlueKey;
import org.bluedb.api.keys.TimeFrameKey;
import org.bluedb.api.keys.TimeKey;
import org.bluedb.disk.IndexableTestValue;
import org.bluedb.disk.collection.BlueCollectionOnDisk;
import org.bluedb.disk.models.calls.Call;
import org.bluedb.disk.serialization.BlueEntity;
import org.bluedb.disk.serialization.ThreadLocalFstSerializer;

public class TestUtils {
	public static Path getResourcePath(String relativePath) throws URISyntaxException, IOException {
		Path pathToStartFrom = Paths.get(TestUtils.class.getResource("").toURI());
		while(!pathToStartFrom.endsWith("BlueDBOnDisk")) {
			pathToStartFrom = pathToStartFrom.resolve("../").toRealPath();
		}
		return pathToStartFrom.resolve("src/test/resources").resolve(relativePath);
	}

	public static BlueEntity<Call> loadCorruptCall() throws URISyntaxException, IOException {
		ThreadLocalFstSerializer serializer = new ThreadLocalFstSerializer(Call.getClassesToRegister());
		Path invalidObjectPath = TestUtils.getResourcePath("corruptCall-1.bin");
		@SuppressWarnings({ "deprecation", "unchecked" })
		BlueEntity<Call> invalidCall = (BlueEntity<Call>) serializer.deserializeObjectFromByteArrayWithoutChecks(Files.readAllBytes(invalidObjectPath));
		return invalidCall;
	}

	public static void assertThrowable(Class<? extends Throwable> expected, Throwable error) {
		String message = "Expected throwable of type " + expected + " but was actually of type " + error;
		if(error == null) {
			if(expected != null) {
				fail(message);
			}
		}
		else if(!error.getClass().isAssignableFrom(expected)) {
			fail(message);
		}
	}

	public static void assertCollectionAndValue(BlueCollectionOnDisk<IndexableTestValue> collection, BlueKey key, IndexableTestValue value) throws BlueDbException {
		assertGet(collection, key, value);
		assertUpdate(collection, key, value);
		assertDeleteAndInsert(collection, key, value);
		assertWhere(collection, key, value);
		
		if(key instanceof TimeFrameKey) {
			assertTimeFrameCases(collection, (TimeFrameKey) key, value);
		}
		else if(key instanceof TimeKey) {
			assertTimeCases(collection, (TimeKey) key, value);
		}
	}

	private static void assertGet(BlueCollectionOnDisk<IndexableTestValue> collection, BlueKey key, IndexableTestValue value) throws BlueDbException {
		assertEquals(value, collection.get(key));
	}

	private static void assertUpdate(BlueCollectionOnDisk<IndexableTestValue> collection, BlueKey key, IndexableTestValue value) throws BlueDbException {
		String originalStringValue = value.getStringValue();
		String newStringValue = "changed";
		
		collection.update(key, valueToUpdate -> {
			valueToUpdate.setStringValue(newStringValue);
		});
		
		assertEquals(newStringValue, collection.get(key).getStringValue());
		
		collection.update(key, valueToUpdate -> {
			valueToUpdate.setStringValue(originalStringValue);
		});
		
		assertEquals(originalStringValue, collection.get(key).getStringValue());
	}

	private static void assertDeleteAndInsert(BlueCollectionOnDisk<IndexableTestValue> collection, BlueKey key, IndexableTestValue value) throws BlueDbException {
		assertEquals(true, collection.contains(key));
		collection.delete(key);
		assertEquals(false, collection.contains(key));
		collection.insert(key, value);
		assertEquals(true, collection.contains(key));
	}

	private static void assertWhere(BlueCollectionOnDisk<IndexableTestValue> collection, BlueKey key, IndexableTestValue value) throws BlueDbException {
		assertEquals(true, collection.query().where(iterValue -> value.getId().equals(iterValue.getId())).getList().contains(value));
	}

	private static void assertTimeCases(BlueCollectionOnDisk<IndexableTestValue> collection, TimeKey key, IndexableTestValue value) throws BlueDbException {
		assertEquals(false, collection.query().beforeTime(key.getTime()).getList().contains(value));
		assertEquals(true, collection.query().beforeTime(key.getTime()+1).getList().contains(value));
		assertEquals(true, collection.query().beforeOrAtTime(key.getTime()).getList().contains(value));
		
		assertEquals(false, collection.query().afterTime(key.getTime()).getList().contains(value));
		assertEquals(true, collection.query().afterTime(key.getTime()-1).getList().contains(value));
		assertEquals(true, collection.query().afterOrAtTime(key.getTime()).getList().contains(value));
	}

	private static void assertTimeFrameCases(BlueCollectionOnDisk<IndexableTestValue> collection, TimeFrameKey key, IndexableTestValue value) throws BlueDbException {
		assertEquals(false, collection.query().beforeTime(key.getStartTime()).getList().contains(value));
		assertEquals(true, collection.query().beforeTime(key.getStartTime()+1).getList().contains(value));
		assertEquals(true, collection.query().beforeTime(key.getEndTime()).getList().contains(value));
		assertEquals(true, collection.query().beforeTime(key.getEndTime()+1).getList().contains(value));
		assertEquals(true, collection.query().beforeOrAtTime(key.getStartTime()).getList().contains(value));
		assertEquals(false, collection.query().beforeOrAtTime(key.getStartTime()-1).getList().contains(value));
		
		assertEquals(false, collection.query().afterTime(key.getEndTime()).getList().contains(value));
		assertEquals(true, collection.query().afterTime(key.getEndTime()-1).getList().contains(value));
		assertEquals(true, collection.query().afterTime(key.getStartTime()).getList().contains(value));
		assertEquals(true, collection.query().afterTime(key.getStartTime() + 1).getList().contains(value));
		assertEquals(true, collection.query().afterOrAtTime(key.getEndTime()).getList().contains(value));
		assertEquals(false, collection.query().afterOrAtTime(key.getEndTime()+1).getList().contains(value));
	
		long segmentSize = collection.getSegmentManager().getSegmentSize();
		boolean overlapsASegment = (key.getStartTime() + segmentSize) < key.getEndTime();
		
		assertEquals(overlapsASegment, collection.query().afterTime(key.getStartTime() + segmentSize).getList().contains(value));
		
		//By Start Time
		assertEquals(false, collection.query().byStartTime().beforeTime(key.getStartTime()).getList().contains(value));
		assertEquals(true, collection.query().byStartTime().beforeTime(key.getStartTime()+1).getList().contains(value));
		assertEquals(true, collection.query().byStartTime().beforeOrAtTime(key.getStartTime()).getList().contains(value));
		
		assertEquals(false, collection.query().byStartTime().afterTime(key.getStartTime()).getList().contains(value));
		assertEquals(true, collection.query().byStartTime().afterTime(key.getStartTime()-1).getList().contains(value));
		assertEquals(true, collection.query().byStartTime().afterOrAtTime(key.getStartTime()).getList().contains(value));
	}
}
