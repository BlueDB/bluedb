package org.bluedb.disk.segment.path;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class SegmentPathManagerTest {


	@Test
	public void test_lock_rollup_levels() {
		assertEquals(Arrays.asList(1L, 64L), LongSegmentPathManager.DEFAULT_ROLLUP_LEVELS);
		assertEquals(Arrays.asList(1L, 6_000L, 3_600_000L), TimeSegmentPathManager.DEFAULT_ROLLUP_LEVELS);
		assertEquals(Arrays.asList(1L, 256L), IntegerSegmentPathManager.DEFAULT_ROLLUP_LEVELS);
		assertEquals(Arrays.asList(1L, 524288L), HashSegmentPathManager.DEFAULT_ROLLUP_LEVELS);
	}

	List<Long> rollupsLong = Arrays.asList(72057594037927936L, 562949953421312L, 2199023255552L, 4294967296L, 8388608L, 16384L, 64L);
	List<Long> rollupsTime = Arrays.asList(31104000000L, 2592000000L, 86400000L, 3600000L);
	List<Long> rollupsInt = Arrays.asList(67108864L, 1048576L, 16384L, 256L);
	List<Long> rollupsHash = Arrays.asList(4294967296L, 67108864L, 524288L);

	@Test
	public void test_lock_folder_levels() {
		assertEquals(rollupsLong, LongSegmentPathManager.DEFAULT_SIZE_FOLDERS);
		assertEquals(rollupsTime, TimeSegmentPathManager.DEFAULT_SIZE_FOLDERS);
		assertEquals(rollupsInt, IntegerSegmentPathManager.DEFAULT_SIZE_FOLDERS);
		assertEquals(rollupsHash, HashSegmentPathManager.DEFAULT_SIZE_FOLDERS);
	}
}
