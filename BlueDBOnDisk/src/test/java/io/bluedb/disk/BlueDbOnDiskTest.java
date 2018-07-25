package io.bluedb.disk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.junit.Test;

import io.bluedb.api.BlueQuery;
import io.bluedb.api.exceptions.BlueDbException;
import io.bluedb.api.keys.BlueKey;
import io.bluedb.api.keys.TimeKey;
import io.bluedb.disk.collection.BlueCollectionOnDisk;
import io.bluedb.zip.ZipUtils;

public class BlueDbOnDiskTest extends BlueDbDiskTestBase {

	@Test
	public void test_shutdown() {
		try {
			db.shutdown();
		} catch (BlueDbException e) {
			e.printStackTrace();
			fail();
		}
	}

	@Test
	public void test_getCollection_untyped() {
		try {
			assertNotNull(db.getCollection("testing1"));
			assertNotNull(db.getCollection("testing2"));  // this time it should create the collection
		} catch (BlueDbException e1) {
			e1.printStackTrace();
			fail();
		}

		try {
			@SuppressWarnings("rawtypes")
			BlueCollectionOnDisk newUntypedCollection = db.getCollection("testing2"); // make sure it's created
			Path serializedClassesPath = Paths.get(newUntypedCollection.getPath().toString(), ".meta", "serialized_classes");
			getFileManager().saveObject(serializedClassesPath, "some_nonsense");  // serialize a string where there should be a list

			db.getCollection("testing2"); // this time it should exception out
			fail();
		} catch (Throwable e) {
		}
	}

	@Test
	public void test_getCollection_existing_correct_type() {
		try {
			db.getCollection(TestValue.class, TimeKey.class, getTimeCollectionName());
			db.getCollection(TestValue.class, TimeKey.class, getTimeCollectionName());
		} catch (BlueDbException e) {
			e.printStackTrace();
			fail();
		}
	}

	@Test
	public void test_getCollection_invalid_type() {
		insert(10, new TestValue("Bob"));
		try {
			db.getCollection(TestValue2.class, TimeKey.class, getTimeCollectionName());
			fail();
		} catch(BlueDbException e) {
		}
	}
	
	@Test
	public void test_query_count() {
		try {
			assertEquals(0, getTimeCollection().query().count());
			BlueKey key = insert(10, new TestValue("Joe", 0));
			assertEquals(1, getTimeCollection().query().count());
			getTimeCollection().delete(key);
			assertEquals(0, getTimeCollection().query().count());
		} catch (BlueDbException e) {
			e.printStackTrace();
			fail();
		}
	}

	@Test
	public void test_query_where() {
		TestValue valueJoe = new TestValue("Joe");
		TestValue valueBob = new TestValue("Bob");
		insert(1, valueJoe);
		insert(2, valueBob);
		List<TestValue> storedValues;
		try {
			storedValues = getTimeCollection().query().getList();
			assertEquals(2, storedValues.size());
			List<TestValue> joeOnly = getTimeCollection().query().where((v) -> v.getName().equals("Joe")).getList();
			assertEquals(1, joeOnly.size());
			assertEquals(valueJoe, joeOnly.get(0));
		} catch (BlueDbException e) {
			e.printStackTrace();
			fail();
		}
	}

	@Test
	public void test_query_beforeTime_timeframe() {
		TestValue value1to2 = new TestValue("Joe");
		TestValue value2to3 = new TestValue("Bob");
		insert(1, 2, value1to2);
		insert(2, 3, value2to3);
		try {
			List<TestValue> before3 = getTimeCollection().query().beforeTime(3).getList();
			List<TestValue> before2 = getTimeCollection().query().beforeTime(2).getList();
			List<TestValue> before1 = getTimeCollection().query().beforeTime(1).getList();
			assertEquals(2, before3.size());
			assertEquals(1, before2.size());
			assertEquals(0, before1.size());
			assertTrue(before3.contains(value2to3));
			assertTrue(before3.contains(value1to2));
			assertTrue(before2.contains(value1to2));
			assertFalse(before1.contains(value1to2));
		} catch (BlueDbException e) {
			e.printStackTrace();
			fail();
		}
	}

	@Test
	public void test_query_beforeTime() {
		TestValue valueAt1 = new TestValue("Joe");
		TestValue valueAt2 = new TestValue("Bob");
		insert(1, valueAt1);
		insert(2, valueAt2);
		try {
			List<TestValue> before3 = getTimeCollection().query().beforeTime(3).getList();
			List<TestValue> before2 = getTimeCollection().query().beforeTime(2).getList();
			List<TestValue> before1 = getTimeCollection().query().beforeTime(1).getList();
			assertEquals(2, before3.size());
			assertEquals(1, before2.size());
			assertEquals(0, before1.size());
			assertTrue(before3.contains(valueAt2));
			assertTrue(before3.contains(valueAt1));
			assertTrue(before2.contains(valueAt1));
			assertFalse(before1.contains(valueAt1));
		} catch (BlueDbException e) {
			e.printStackTrace();
			fail();
		}
	}

	@Test
	public void test_query_beforeOrAtTime_timeframe() {
		TestValue value1to2 = new TestValue("Joe");
		TestValue value2to3 = new TestValue("Bob");
		insert(1, 2, value1to2);
		insert(2, 3, value2to3);
		try {
			List<TestValue> beforeOrAt3 = getTimeCollection().query().beforeOrAtTime(3).getList();
			List<TestValue> beforeOrAt2 = getTimeCollection().query().beforeOrAtTime(2).getList();
			List<TestValue> beforeOrAt1 = getTimeCollection().query().beforeOrAtTime(1).getList();
			List<TestValue> beforeOrAt0 = getTimeCollection().query().beforeOrAtTime(0).getList();
			assertEquals(2, beforeOrAt3.size());
			assertEquals(2, beforeOrAt2.size());
			assertEquals(1, beforeOrAt1.size());
			assertEquals(0, beforeOrAt0.size());
			assertTrue(beforeOrAt3.contains(value2to3));
			assertTrue(beforeOrAt3.contains(value1to2));
			assertTrue(beforeOrAt2.contains(value2to3));
			assertTrue(beforeOrAt2.contains(value1to2));
			assertTrue(beforeOrAt1.contains(value1to2));
			assertFalse(beforeOrAt0.contains(value1to2));
		} catch (BlueDbException e) {
			e.printStackTrace();
			fail();
		}
	}

	@Test
	public void test_query_beforeOrAtTime() {
		TestValue valueAt1 = new TestValue("Joe");
		TestValue valueAt2 = new TestValue("Bob");
		insert(1, valueAt1);
		insert(2, valueAt2);
		try {
			List<TestValue> beforeOrAt3 = getTimeCollection().query().beforeOrAtTime(3).getList();
			List<TestValue> beforeOrAt2 = getTimeCollection().query().beforeOrAtTime(2).getList();
			List<TestValue> beforeOrAt1 = getTimeCollection().query().beforeOrAtTime(1).getList();
			assertEquals(2, beforeOrAt3.size());
			assertEquals(2, beforeOrAt2.size());
			assertEquals(1, beforeOrAt1.size());
			assertTrue(beforeOrAt3.contains(valueAt2));
			assertTrue(beforeOrAt3.contains(valueAt1));
			assertTrue(beforeOrAt2.contains(valueAt2));
			assertTrue(beforeOrAt2.contains(valueAt1));
			assertFalse(beforeOrAt1.contains(valueAt2));
			assertTrue(beforeOrAt1.contains(valueAt1));
		} catch (BlueDbException e) {
			e.printStackTrace();
			fail();
		}
	}

	@Test
	public void test_query_AfterTime_timeframe() {
		TestValue value1to2 = new TestValue("Joe");
		TestValue value2to3 = new TestValue("Bob");
		insert(1, 2, value1to2);
		insert(2, 3, value2to3);
		try {
			List<TestValue> after3 = getTimeCollection().query().afterTime(3).getList();
			List<TestValue> after2 = getTimeCollection().query().afterTime(2).getList();
			List<TestValue> after1 = getTimeCollection().query().afterTime(1).getList();
			List<TestValue> after0 = getTimeCollection().query().afterTime(0).getList();
			assertEquals(2, after0.size());
			assertEquals(2, after1.size());
			assertEquals(1, after2.size());
			assertEquals(0, after3.size());
			assertTrue(after0.contains(value2to3));
			assertTrue(after0.contains(value1to2));
			assertTrue(after1.contains(value2to3));
			assertTrue(after1.contains(value1to2));
			assertTrue(after2.contains(value2to3));
			assertFalse(after3.contains(value2to3));
		} catch (BlueDbException e) {
			e.printStackTrace();
			fail();
		}
	}

	@Test
	public void test_query_AfterTime() {
		TestValue valueAt1 = new TestValue("Joe");
		TestValue valueAt2 = new TestValue("Bob");
		insert(1, valueAt1);
		insert(2, valueAt2);
		try {
			List<TestValue> after2 = getTimeCollection().query().afterTime(2).getList();
			List<TestValue> after1 = getTimeCollection().query().afterTime(1).getList();
			List<TestValue> after0 = getTimeCollection().query().afterTime(0).getList();
			assertEquals(2, after0.size());
			assertEquals(1, after1.size());
			assertEquals(0, after2.size());
			assertTrue(after0.contains(valueAt2));
			assertTrue(after0.contains(valueAt1));
			assertTrue(after1.contains(valueAt2));
			assertFalse(after2.contains(valueAt2));
		} catch (BlueDbException e) {
			e.printStackTrace();
			fail();
		}
	}

	@Test
	public void test_query_AfterOrAtTime_timeframe() {
		TestValue value1to2 = new TestValue("Joe");
		TestValue value2to3 = new TestValue("Bob");
		insert(1, 2, value1to2);
		insert(2, 3, value2to3);
		try {
			List<TestValue> afterOrAt4 = getTimeCollection().query().afterOrAtTime(4).getList();
			List<TestValue> afterOrAt3 = getTimeCollection().query().afterOrAtTime(3).getList();
			List<TestValue> afterOrAt2 = getTimeCollection().query().afterOrAtTime(2).getList();
			List<TestValue> afterOrAt1 = getTimeCollection().query().afterOrAtTime(1).getList();
			List<TestValue> afterOrAt0 = getTimeCollection().query().afterOrAtTime(0).getList();
			assertEquals(2, afterOrAt0.size());
			assertEquals(2, afterOrAt1.size());
			assertEquals(2, afterOrAt2.size());
			assertEquals(1, afterOrAt3.size());
			assertEquals(0, afterOrAt4.size());
			assertTrue(afterOrAt0.contains(value2to3));
			assertTrue(afterOrAt0.contains(value1to2));
			assertTrue(afterOrAt1.contains(value2to3));
			assertTrue(afterOrAt1.contains(value1to2));
			assertTrue(afterOrAt2.contains(value2to3));
			assertTrue(afterOrAt2.contains(value1to2));
			assertTrue(afterOrAt3.contains(value2to3));
			assertFalse(afterOrAt4.contains(value2to3));
		} catch (BlueDbException e) {
			e.printStackTrace();
			fail();
		}
	}

	@Test
	public void test_query_AfterOrAtTime() {
		TestValue valueAt1 = new TestValue("Joe");
		TestValue valueAt2 = new TestValue("Bob");
		insert(1, valueAt1);
		insert(2, valueAt2);
		try {
			List<TestValue> afterOrAt2 = getTimeCollection().query().afterOrAtTime(2).getList();
			List<TestValue> afterOrAt1 = getTimeCollection().query().afterOrAtTime(1).getList();
			List<TestValue> afterOrAt0 = getTimeCollection().query().afterOrAtTime(0).getList();
			assertEquals(2, afterOrAt0.size());
			assertEquals(2, afterOrAt1.size());
			assertEquals(1, afterOrAt2.size());
			assertTrue(afterOrAt0.contains(valueAt2));
			assertTrue(afterOrAt0.contains(valueAt1));
			assertTrue(afterOrAt1.contains(valueAt2));
			assertTrue(afterOrAt1.contains(valueAt1));
			assertTrue(afterOrAt2.contains(valueAt2));
			assertFalse(afterOrAt2.contains(valueAt1));
		} catch (BlueDbException e) {
			e.printStackTrace();
			fail();
		}
	}

	@Test
	public void test_query_Between() {
		TestValue valueAt2 = new TestValue("Joe");
		TestValue valueAt3 = new TestValue("Bob");
		insert(2, valueAt2);
		insert(3, valueAt3);
		try {
			// various queries outside the range
			List<TestValue> after0before1 = getTimeCollection().query().afterTime(0).beforeTime(1).getList();
			List<TestValue> after0beforeOrAt1 = getTimeCollection().query().afterTime(0).beforeOrAtTime(1).getList();
			List<TestValue> afterOrAt0before1 = getTimeCollection().query().afterOrAtTime(0).beforeTime(1).getList();
			List<TestValue> afterOrAt0beforeOrAt1 = getTimeCollection().query().afterOrAtTime(0).beforeOrAtTime(1).getList();
			assertEquals(0, after0before1.size());
			assertEquals(0, after0beforeOrAt1.size());
			assertEquals(0, afterOrAt0before1.size());
			assertEquals(0, afterOrAt0beforeOrAt1.size());

			// various queries inside the range
			List<TestValue> after2before3 = getTimeCollection().query().afterTime(2).beforeTime(3).getList();
			List<TestValue> after2beforeOrAt3 = getTimeCollection().query().afterTime(2).beforeOrAtTime(3).getList();
			List<TestValue> afterOrAt2before3 = getTimeCollection().query().afterOrAtTime(2).beforeTime(3).getList();
			List<TestValue> afterOrAt2beforeOrAt3 = getTimeCollection().query().afterOrAtTime(2).beforeOrAtTime(3).getList();
			assertEquals(0, after2before3.size());
			assertEquals(1, after2beforeOrAt3.size());
			assertEquals(1, afterOrAt2before3.size());
			assertEquals(2, afterOrAt2beforeOrAt3.size());
			assertFalse(after2beforeOrAt3.contains(valueAt2));
			assertTrue(after2beforeOrAt3.contains(valueAt3));
			assertTrue(afterOrAt2before3.contains(valueAt2));
			assertFalse(afterOrAt2before3.contains(valueAt3));
			assertTrue(afterOrAt2beforeOrAt3.contains(valueAt2));
			assertTrue(afterOrAt2beforeOrAt3.contains(valueAt3));
		} catch (BlueDbException e) {
			e.printStackTrace();
			fail();
		}
	}

	@Test
	public void test_getList() {
		TestValue valueJoe = new TestValue("Joe");
		TestValue valueBob = new TestValue("Bob");
		insert(1, valueJoe);
		insert(2, valueBob);
		List<TestValue> storedValues;
		try {
			storedValues = getTimeCollection().query().getList();
			assertEquals(2, storedValues.size());
			List<TestValue> joeOnly = getTimeCollection().query().where((v) -> v.getName().equals("Joe")).getList();
			assertEquals(1, joeOnly.size());
			assertEquals(valueJoe, joeOnly.get(0));
		} catch (BlueDbException e) {
			e.printStackTrace();
			fail();
		}
	}
	
	@Test
	public void test_getIterator() {
		TestValue valueJoe = new TestValue("Joe");
		TestValue valueBob = new TestValue("Bob");
		insert(1, valueJoe);
		insert(2, valueBob);
		Iterator<TestValue> iter;
		try {
			iter = getTimeCollection().query().getIterator();
			List<TestValue> list = new ArrayList<>();
			iter.forEachRemaining(list::add);
			assertEquals(2, list.size());
			assertTrue(list.contains(valueJoe));
			assertTrue(list.contains(valueBob));
		} catch (BlueDbException e) {
			e.printStackTrace();
			fail();
		}
	}
	
	@Test
	public void test_query_update() {
		BlueKey keyJoe   = insert(1, new TestValue("Joe", 0));
		BlueKey keyBob   = insert(2, new TestValue("Bob", 0));
		BlueKey keyJosey = insert(2,  new TestValue("Josey", 0));
		BlueKey keyBobby = insert(3, new TestValue("Bobby", 0));
		BlueQuery<TestValue> queryForJosey = getTimeCollection().query().afterTime(1).where((v) -> v.getName().startsWith("Jo"));
		try {
			// sanity check
			assertCupcakes(keyJoe, 0);
			assertCupcakes(keyBob, 0);
			assertCupcakes(keyJosey, 0);
			assertCupcakes(keyBobby, 0);

			// test update with conditions
			queryForJosey.update((v) -> v.addCupcake());
			assertCupcakes(keyJoe, 0);
			assertCupcakes(keyBob, 0);
			assertCupcakes(keyJosey, 1);
			assertCupcakes(keyBobby, 0);

			// test update all
			getTimeCollection().query().update((v) -> v.addCupcake());
			assertCupcakes(keyJoe, 1);
			assertCupcakes(keyBob, 1);
			assertCupcakes(keyJosey, 2);
			assertCupcakes(keyBobby, 1);
		} catch (BlueDbException e) {
			e.printStackTrace();
			fail();
		}
	}
	
	@Test
	public void test_query_delete() {
		TestValue valueJoe = new TestValue("Joe");
		TestValue valueBob = new TestValue("Bob");
		TestValue valueJosey = new TestValue("Josey");
		TestValue valueBobby = new TestValue("Bobby");
		insert(1, valueJoe);
		insert(2, valueBob);
		insert(2, valueJosey);
		insert(3, valueBobby);
		List<TestValue> storedValues;
		BlueQuery<TestValue> queryForJosey = getTimeCollection().query().afterTime(1).where((v) -> v.getName().startsWith("Jo"));
		try {
			// sanity check
			storedValues = getTimeCollection().query().getList();
			assertEquals(4, storedValues.size());
			assertTrue(storedValues.contains(valueJosey));

			// test if delete works with query conditions
			queryForJosey.delete();
			storedValues = getTimeCollection().query().getList();
			assertEquals(3, storedValues.size());
			assertFalse(storedValues.contains(valueJosey));
			assertTrue(storedValues.contains(valueJoe));

			// test if delete works without conditions
			getTimeCollection().query().delete();
			storedValues = getTimeCollection().query().getList();
			assertEquals(0, storedValues.size());
		} catch (BlueDbException e) {
			e.printStackTrace();
			fail();
		}
	}

	@Test
	public void test_getAllCollectionsFromDisk() {
		try {
			getTimeCollection();
			List<BlueCollectionOnDisk<?>> allCollections = db().getAllCollectionsFromDisk();
			assertEquals(1, allCollections.size());
			db().getCollection(String.class, BlueKey.class, "string");
			db().getCollection(Long.class, BlueKey.class, "long");
			allCollections = db().getAllCollectionsFromDisk();
			assertEquals(3, allCollections.size());
		} catch (BlueDbException e) {
			e.printStackTrace();
			fail();
		}
	}

	@Test
	public void test_backup() {
		try {
			BlueKey key1At1 = createKey(1, 1);
			TestValue value1 = createValue("Anna");
			getTimeCollection().insert(key1At1, value1);

			BlueCollectionOnDisk<TestValue2> secondCollection = (BlueCollectionOnDisk<TestValue2>) db().getCollection(TestValue2.class, TimeKey.class, "testing_2");
			TestValue2 valueInSecondCollection = new TestValue2("Joe", 3);
			secondCollection.insert(key1At1, valueInSecondCollection);

			Path tempFolder = Files.createTempDirectory(this.getClass().getSimpleName());
			tempFolder.toFile().deleteOnExit();
			Path backedUpPath = Paths.get(tempFolder.toString(), "backup_test.zip");
			db().backup(backedUpPath);

			Path restoredPath = Paths.get(tempFolder.toString(), "restore_test");
			ZipUtils.extractFiles(backedUpPath, restoredPath);
			Path restoredBlueDbPath = Paths.get(restoredPath.toString(), "bluedb");

			BlueDbOnDisk restoredDb = new BlueDbOnDiskBuilder().setPath(restoredBlueDbPath).build();
			BlueCollectionOnDisk<TestValue> restoredCollection = (BlueCollectionOnDisk<TestValue>) restoredDb.getCollection(TestValue.class, TimeKey.class, "testing");
			assertTrue(restoredCollection.contains(key1At1));
			assertEquals(value1, restoredCollection.get(key1At1));
			Long restoredMaxLong = restoredCollection.getMaxLongId();
			assertNotNull(restoredMaxLong);
			assertEquals(getTimeCollection().getMaxLongId().longValue(), restoredMaxLong.longValue());

			BlueCollectionOnDisk<TestValue2> secondCollectionRestored = (BlueCollectionOnDisk<TestValue2>) restoredDb.getCollection(TestValue2.class, TimeKey.class, "testing_2");
			assertTrue(secondCollectionRestored.contains(key1At1));
			assertEquals(valueInSecondCollection, secondCollectionRestored.get(key1At1));
		} catch (IOException | BlueDbException e) {
			e.printStackTrace();
			fail();
		}
	}

	@Test
	public void test_backup_fail() {
		try {
			@SuppressWarnings("rawtypes")
			BlueCollectionOnDisk newUntypedCollection = db.getCollection("testing2"); // create a new bogus collection
			Path serializedClassesPath = Paths.get(newUntypedCollection.getPath().toString(), ".meta", "serialized_classes");
			getFileManager().saveObject(serializedClassesPath, "some_nonsense");  // serialize a string where there should be a list
			
			Path tempFolder = Files.createTempDirectory(this.getClass().getSimpleName());
			tempFolder.toFile().deleteOnExit();
			Path backedUpPath = Paths.get(tempFolder.toString(), "backup_test.zip");
			db().backup(backedUpPath);
			fail();  // because the "test2" collection was broken, the backup should error out;

		} catch (IOException | BlueDbException e) {
		}
	}
}
