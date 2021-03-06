package org.bluedb.disk.recovery;

import java.io.File;
import java.util.List;
import org.junit.Test;
import org.bluedb.disk.BlueDbDiskTestBase;
import org.bluedb.disk.TestValue;

public class ChangeHistoryCleanerTest extends BlueDbDiskTestBase {

	@Test
	public void test_cleanupHistory() throws Exception {
		long thirtyMinutesAgo = System.currentTimeMillis() - 30 * 60 * 1000;
		long sixtyMinutesAgo = System.currentTimeMillis() - 60 * 60 * 1000;
		long ninetyMinutesAgo = System.currentTimeMillis() - 90 * 60 * 1000;
		long oneHundredMinutesAgo = System.currentTimeMillis() - 100 * 60 * 1000;
		Recoverable<TestValue> changePending = createRecoverable(thirtyMinutesAgo);
		Recoverable<TestValue> change30 = createRecoverable(thirtyMinutesAgo);
		Recoverable<TestValue> change60 = createRecoverable(sixtyMinutesAgo);
		Recoverable<TestValue> change90 = createRecoverable(ninetyMinutesAgo);
		Recoverable<TestValue> change100 = createRecoverable(oneHundredMinutesAgo);
		assertEquals(thirtyMinutesAgo, change30.getTimeCreated());
		assertEquals(sixtyMinutesAgo, change60.getTimeCreated());
		assertEquals(ninetyMinutesAgo, change90.getTimeCreated());
		assertEquals(oneHundredMinutesAgo, change100.getTimeCreated());
		getRecoveryManager().getChangeHistoryCleaner().setWaitBetweenCleanups(100_000);  // to prevent automatic cleanup
//		getRecoveryManager().cleanupHistory(); // to reset timer and prevent automatic cleanup
		List<File> changesBeforeInsert = getRecoveryManager().getChangeHistory(Long.MIN_VALUE, Long.MAX_VALUE);
		getRecoveryManager().saveChange(changePending);
		getRecoveryManager().saveChange(change30);
		getRecoveryManager().saveChange(change60);
		getRecoveryManager().saveChange(change90);
		getRecoveryManager().saveChange(change100);
		getRecoveryManager().markComplete(change30);
		getRecoveryManager().markComplete(change60);
		getRecoveryManager().markComplete(change90);
		getRecoveryManager().markComplete(change100);
		List<File> changesBeforeCleanup = getRecoveryManager().getChangeHistory(Long.MIN_VALUE, Long.MAX_VALUE);

		getRecoveryManager().placeHoldOnHistoryCleanup();
		getRecoveryManager().getChangeHistoryCleaner().setRetentionLimit(2);

		getRecoveryManager().getChangeHistoryCleaner().cleanupHistory();
		List<File> changesAfterCleanupDuringHold = getRecoveryManager().getChangeHistory(Long.MIN_VALUE, Long.MAX_VALUE);
		getRecoveryManager().removeHoldOnHistoryCleanup();

		getRecoveryManager().getChangeHistoryCleaner().cleanupHistory();
		List<File> changesAfterCleanup2 = getRecoveryManager().getChangeHistory(Long.MIN_VALUE, Long.MAX_VALUE);

		getRecoveryManager().getChangeHistoryCleaner().setRetentionLimit(0);
		getRecoveryManager().getChangeHistoryCleaner().cleanupHistory();
		List<File> changesAfterCleanup0 = getRecoveryManager().getChangeHistory(Long.MIN_VALUE, Long.MAX_VALUE);

//		assertFalse(getRecoveryManager().isTimeForHistoryCleanup());  // since we already just did cleanup
		assertEquals(0, changesBeforeInsert.size());
		assertEquals(5, changesBeforeCleanup.size());
		assertEquals(5, changesAfterCleanupDuringHold.size());
		assertEquals(3, changesAfterCleanup2.size());  // two completed stay, plus the pending
		assertEquals(1, changesAfterCleanup0.size());  // everything goes except pending
	}

	private Recoverable<TestValue> createRecoverable(long time){
		return new TestRecoverable(time);
	}
}
