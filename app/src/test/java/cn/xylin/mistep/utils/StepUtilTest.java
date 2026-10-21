package cn.xylin.mistep.utils;

import org.junit.Test;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.Arrays;
import java.util.Random;
import static org.junit.Assert.*;

public class StepUtilTest {
    @Test
    public void legacyNegativeRecordRepairPreservesTotalAndWalkingHistory() {
        long[] records = {20000, 500, 100000, -103500};
        long[] planned = StepUtil.planNonNegativeSteps(records, 17000);
        assertArrayEquals(new long[]{16500, 500, 0, 0}, planned);
        assertArrayEquals(new long[]{20000, 500, 100000, -103500}, records);
        assertArrayEquals(planned, StepUtil.planNonNegativeSteps(planned, 17000));
    }

    @Test
    public void decreasingStepsNeverCreatesANegativeRecord() {
        assertArrayEquals(new long[]{900, 10}, StepUtil.planNonNegativeSteps(new long[]{1000, 10}, 910));
        assertArrayEquals(new long[]{0, 5}, StepUtil.planNonNegativeSteps(new long[]{1000, 10}, 5));
        assertArrayEquals(new long[]{0, 0}, StepUtil.planNonNegativeSteps(new long[]{1000, 10}, 0));
        assertArrayEquals(new long[]{}, StepUtil.planNonNegativeSteps(new long[]{}, 0));
    }

    @Test
    public void normalizationAlwaysHasTheRequestedNonNegativeSum() {
        Random random = new Random(20261014);
        for (int sample = 0; sample < 1000; sample++) {
            long[] records = new long[1 + random.nextInt(30)];
            long positiveSum = 0;
            for (int index = 0; index < records.length; index++) {
                records[index] = random.nextInt(20001) - 10000;
                positiveSum += Math.max(0, records[index]);
            }
            long target = positiveSum == 0 ? 0 : random.nextInt((int) positiveSum + 1);
            long[] planned = StepUtil.planNonNegativeSteps(records, target);
            assertTrue(Arrays.stream(planned).allMatch(count -> count >= 0));
            assertEquals(target, Arrays.stream(planned).sum());
            assertArrayEquals(planned, StepUtil.planNonNegativeSteps(planned, target));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void normalizationDoesNotUseUpdatesToIncreaseTotal() {
        StepUtil.planNonNegativeSteps(new long[]{10}, 11);
    }

    @Test
    public void dayRangeStartsAtMidnightAndEndsAtNextMidnight() {
        long[] bounds = StepUtil.getDayBounds();
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(bounds[0]);
        assertEquals(0, start.get(Calendar.HOUR_OF_DAY));
        assertEquals(0, start.get(Calendar.MINUTE));
        assertEquals(0, start.get(Calendar.SECOND));
        assertEquals(0, start.get(Calendar.MILLISECOND));
        start.add(Calendar.DAY_OF_MONTH, 1);
        assertEquals(start.getTimeInMillis(), bounds[1]);
        assertTrue(bounds[0] <= System.currentTimeMillis());
        assertTrue(bounds[1] > System.currentTimeMillis());
    }

    @Test
    public void dayRangeUsesDeviceTimezone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            dayRangeStartsAtMidnightAndEndsAtNextMidnight();
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            dayRangeStartsAtMidnightAndEndsAtNextMidnight();
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    public void failedReadNeverBecomesZeroAndDoesNotWrite() {
        assertEquals(-1, StepUtil.getTodaySteps(null, false));
        assertFalse(StepUtil.modifySteps(null, true, false, 12345));
        assertFalse(StepUtil.modifySteps(null, false, false, 10));
        assertFalse(StepUtil.modifySteps(null, true, false, 0));
    }

    @Test
    public void totalsRejectNegativeAndOverflow() throws Exception {
        Method check = StepUtil.class.getDeclaredMethod("checkedTotal", long.class);
        check.setAccessible(true);
        assertEquals(0, check.invoke(null, 0L));
        assertEquals(Integer.MAX_VALUE, check.invoke(null, (long) Integer.MAX_VALUE));
        for (long invalid : new long[]{-1L, (long) Integer.MAX_VALUE + 1}) {
            try {
                check.invoke(null, invalid);
                fail("invalid step total accepted");
            } catch (InvocationTargetException error) {
                assertTrue(error.getCause() instanceof IllegalStateException);
            }
        }
    }
}
