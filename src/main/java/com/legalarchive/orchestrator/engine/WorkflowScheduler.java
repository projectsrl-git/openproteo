package com.legalarchive.orchestrator.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import com.legalarchive.orchestrator.model.def.WorkflowDef;
import com.legalarchive.orchestrator.registry.WorkflowRegistry;

/**
 * Pianificazione cron dei workflow (espressioni Spring a 6 campi:
 * sec min ora giorno mese giornoSettimana, es. "0 30 6 * * MON-FRI").
 * I workflow senza attributo cron sono solo manuali.
 */
@Component
public class WorkflowScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowScheduler.class);

    private final WorkflowRegistry registry;
    private final WorkflowEngine engine;
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final Map<String, ScheduledFuture<?>> scheduled = new LinkedHashMap<String, ScheduledFuture<?>>();
    private final Map<String, String> scheduleErrors = new LinkedHashMap<String, String>();

    public WorkflowScheduler(WorkflowRegistry registry, WorkflowEngine engine) {
        this.registry = registry;
        this.engine = engine;
    }

    @PostConstruct
    public void init() {
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("wf-cron-");
        scheduler.initialize();
        reschedule();
    }

    public synchronized void reschedule() {
        for (ScheduledFuture<?> f : scheduled.values()) f.cancel(false);
        scheduled.clear();
        scheduleErrors.clear();

        for (WorkflowDef wf : registry.all()) schedule(wf);
    }

    /**
     * After {@link WorkflowRegistry#refresh}: re-plans only the workflows the refresh touched, and
     * leaves the timer of every other workflow alone. When the refresh reloaded everything, so
     * does this. The result is what {@link #reschedule()} would leave behind.
     */
    public synchronized void reschedule(WorkflowRegistry.Refresh refresh) {
        if (refresh == null || refresh.full) {
            reschedule();
            return;
        }
        for (String feedId : refresh.removed) unschedule(feedId);
        for (String feedId : refresh.loaded) {
            unschedule(feedId);
            WorkflowDef wf = registry.get(feedId);
            if (wf != null) schedule(wf);
        }
        // cron errors are shown in the order of the workflows, as reschedule() leaves them
        Map<String, String> ordered = new LinkedHashMap<String, String>();
        for (WorkflowDef wf : registry.all()) {
            String err = scheduleErrors.get(wf.feedId);
            if (err != null) ordered.put(wf.feedId, err);
        }
        scheduleErrors.clear();
        scheduleErrors.putAll(ordered);
    }

    private void unschedule(String feedId) {
        ScheduledFuture<?> f = scheduled.remove(feedId);
        if (f != null) f.cancel(false);
        scheduleErrors.remove(feedId);
    }

    private void schedule(WorkflowDef wf) {
        if (wf.cron == null) return;
        final String feedId = wf.feedId;
        try {
            ScheduledFuture<?> f = scheduler.schedule(
                    () -> {
                        try {
                            engine.start(feedId, "CRON", "scheduler");
                        } catch (Exception e) {
                            log.error("[{}] avvio da scheduler fallito: {}", feedId, e.getMessage(), e);
                        }
                    },
                    new CronTrigger(wf.cron));
            scheduled.put(feedId, f);
            log.info("[{}] pianificato con cron '{}'", feedId, wf.cron);
        } catch (Exception e) {
            log.error("[{}] cron non valida '{}': {}", feedId, wf.cron, e.getMessage());
            scheduleErrors.put(feedId, "Invalid cron '" + wf.cron + "': " + e.getMessage());
        }
    }

    public synchronized boolean isScheduled(String feedId) {
        return scheduled.containsKey(feedId);
    }

    public synchronized Map<String, String> errors() {
        return new LinkedHashMap<String, String>(scheduleErrors);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
    }
}
