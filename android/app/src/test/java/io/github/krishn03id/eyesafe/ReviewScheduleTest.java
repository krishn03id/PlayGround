package io.github.krishn03id.eyesafe;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReviewScheduleTest {
    @Test public void againReturnsInTenMinutes() {
        assertEquals(600000L, ReviewSchedule.nextDue(0L, 20, "again"));
        assertEquals(0, ReviewSchedule.nextInterval(20, "again"));
    }

    @Test public void goodAndEasyExpandIntervalsWithAnUpperBound() {
        assertEquals(1, ReviewSchedule.nextInterval(0, "good"));
        assertEquals(2, ReviewSchedule.nextInterval(1, "good"));
        assertEquals(4, ReviewSchedule.nextInterval(0, "easy"));
        assertEquals(12, ReviewSchedule.nextInterval(4, "easy"));
        assertEquals(365, ReviewSchedule.nextInterval(365, "easy"));
        assertEquals(100L + 12L * ReviewSchedule.DAY, ReviewSchedule.nextDue(100L, 4, "easy"));
    }
}
