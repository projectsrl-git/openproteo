package org.springframework.scheduling.concurrent;
import java.util.concurrent.*;
public class ThreadPoolTaskScheduler {
    public static int created = 0, cancelled = 0;
    public void setPoolSize(int n) {}
    public void setThreadNamePrefix(String s) {}
    public void initialize() {}
    public void shutdown() {}
    public ScheduledFuture<?> schedule(Runnable r, org.springframework.scheduling.support.CronTrigger t) {
        created++;
        return new ScheduledFuture<Object>() {
            boolean c;
            public long getDelay(TimeUnit u) { return 0; }
            public int compareTo(Delayed o) { return 0; }
            public boolean cancel(boolean b) { if (!c) cancelled++; c = true; return true; }
            public boolean isCancelled() { return c; }
            public boolean isDone() { return c; }
            public Object get() { return null; }
            public Object get(long l, TimeUnit u) { return null; }
        };
    }
}
