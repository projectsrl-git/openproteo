package com.legalarchive.orchestrator.store;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.legalarchive.orchestrator.model.run.WorkflowRun;

/**
 * Persistenza dei run come singoli file JSON leggibili (pretty-printed)
 * in {feedDir}/_runs/{runId}.json. Nessun database: lo storico e' consultabile
 * anche senza applicazione, con un editor o PowerShell (ConvertFrom-Json).
 */
@Component
public class RunStore {

    private static final Logger log = LoggerFactory.getLogger(RunStore.class);

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public synchronized void save(FeedLayout layout, WorkflowRun run) {
        try {
            Path f = layout.runFile(run.runId);
            Files.createDirectories(f.getParent());
            // scrittura atomica: tmp + move
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), run);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.error("Salvataggio run {} fallito: {}", run.runId, e.getMessage(), e);
        }
    }

    public WorkflowRun load(FeedLayout layout, String runId) {
        try {
            File f = layout.runFile(runId).toFile();
            if (!f.exists()) return null;
            return mapper.readValue(f, WorkflowRun.class);
        } catch (Exception e) {
            log.error("Lettura run {} fallita: {}", runId, e.getMessage());
            return null;
        }
    }

    /** Storico run del feed, dal piu' recente. */
    /** Removes a run's JSON and its per-step logs. Returns true if the run file existed. */
    public synchronized boolean delete(FeedLayout layout, String runId) {
        boolean existed = false;
        try {
            File rf = layout.runFile(runId).toFile();
            if (rf.isFile()) { existed = rf.delete() || existed; existed = true; }
            // best-effort cleanup of this run's step logs (named with the runId)
            File logsDir = layout.runsDir.getParent() != null ? layout.runsDir.resolve("_logs").toFile() : null;
            // step logs live under _logs/<runId>/... ; remove that subtree if present
            File runLogs = layout.runsDir.resolveSibling("_logs").resolve(runId).toFile();
            deleteTree(runLogs);
        } catch (Exception ignored) {
        }
        return existed;
    }

    private void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    public List<WorkflowRun> list(FeedLayout layout, int max) {
        File dir = layout.runsDir.toFile();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return new ArrayList<WorkflowRun>();
        // run filenames embed a zero-padded timestamp+counter, so name order == chronological order.
        // Sort by name descending and parse ONLY the newest `max` files instead of every run on disk:
        // the dashboard (max=1) then costs one JSON parse per feed, not one per run.
        java.util.Arrays.sort(files, Comparator.comparing(File::getName).reversed());
        int limit = (max > 0) ? Math.min(max, files.length) : files.length;
        List<WorkflowRun> out = new ArrayList<WorkflowRun>(limit);
        for (int i = 0; i < limit; i++) {
            try {
                out.add(mapper.readValue(files[i], WorkflowRun.class));
            } catch (Exception ignored) {
            }
        }
        // re-sort the parsed subset by actual start timestamp (cheap: at most `max` items)
        out.sort(Comparator.comparing((WorkflowRun r) -> r.startTs == null ? "" : r.startTs).reversed());
        return out;
    }
}
