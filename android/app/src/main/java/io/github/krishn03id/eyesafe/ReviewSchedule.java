package io.github.krishn03id.eyesafe;

final class ReviewSchedule {
    static final long DAY = 24L * 60 * 60 * 1000;

    static int nextInterval(int previousDays, String rating) {
        if ("again".equals(rating)) return 0;
        if ("easy".equals(rating)) return Math.min(365, Math.max(4, Math.max(1, previousDays) * 3));
        return Math.min(365, Math.max(1, previousDays * 2));
    }

    static long nextDue(long now, int previousDays, String rating) {
        return now + ("again".equals(rating) ? 10L * 60 * 1000 : nextInterval(previousDays, rating) * DAY);
    }
}
