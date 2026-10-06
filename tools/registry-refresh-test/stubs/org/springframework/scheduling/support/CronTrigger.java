package org.springframework.scheduling.support;
public class CronTrigger {
    public CronTrigger(String expr) {
        if (expr.trim().split("\\s+").length != 6) throw new IllegalArgumentException("Cron expression must consist of 6 fields (found " + expr.trim().split("\\s+").length + " in \"" + expr + "\")");
    }
}
