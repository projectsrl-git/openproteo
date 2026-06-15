package com.legalarchive.orchestrator.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import com.legalarchive.orchestrator.model.def.StepDef;
import com.legalarchive.orchestrator.model.def.WorkflowDef;

/**
 * Layout su disco di un feed. Tutto vive sotto {baseDir}/{feedId}:
 *
 *   {baseDir}/{feedId}/
 *     00_landing_in/          dati grezzi in arrivo (mondo AS400)
 *     10_{step1}/             directory di lavoro dello step 1
 *     20_{step2}/             ...una per ogni step del workflow, in ordine
 *     99_landing_out/         pacchetti pronti per la spedizione FTPS
 *     _logs/
 *       audit_{feedId}.jsonl  log di audit append-only con hash chain
 *       runs/{runId}/{stepId}.log   output PowerShell per step
 *     _runs/
 *       {runId}.json          stato persistito di ogni esecuzione
 */
public class FeedLayout {

    public final Path feedDir;
    public final Path landingIn;
    public final Path landingOut;
    public final Path logsDir;
    public final Path runsDir;
    /** stepId -> directory dello step */
    public final Map<String, Path> stepDirs = new LinkedHashMap<String, Path>();

    public FeedLayout(WorkflowDef wf, String defaultBaseDir) {
        String base = wf.baseDir != null ? wf.baseDir : defaultBaseDir;
        this.feedDir = Paths.get(base, wf.feedId).toAbsolutePath().normalize();
        this.landingIn = feedDir.resolve("00_landing_in");
        this.landingOut = feedDir.resolve("99_landing_out");
        this.logsDir = feedDir.resolve("_logs");
        this.runsDir = feedDir.resolve("_runs");
        int i = 1;
        for (StepDef s : wf.steps()) {
            stepDirs.put(s.id, feedDir.resolve(String.format("%02d_%s", i * 10, s.id)));
            i++;
        }
    }

    /** Crea tutte le directory mancanti. Ritorna true se ne ha create di nuove. */
    public boolean provision() throws IOException {
        boolean created = false;
        created |= mkdir(landingIn);
        for (Path p : stepDirs.values()) created |= mkdir(p);
        created |= mkdir(landingOut);
        created |= mkdir(logsDir.resolve("runs"));
        created |= mkdir(runsDir);
        return created;
    }

    private boolean mkdir(Path p) throws IOException {
        if (Files.isDirectory(p)) return false;
        Files.createDirectories(p);
        return true;
    }

    public Path auditFile() {
        return logsDir.resolve("audit_" + feedDir.getFileName() + ".jsonl");
    }

    public Path stepLog(String runId, String stepId) {
        return logsDir.resolve("runs").resolve(runId).resolve(stepId + ".log");
    }

    public Path runFile(String runId) {
        return runsDir.resolve(runId + ".json");
    }

    /** Variabili builtin di directory per il run. */
    public Map<String, String> dirVars() {
        Map<String, String> v = new LinkedHashMap<String, String>();
        v.put("feedDir", feedDir.toString());
        v.put("landingIn", landingIn.toString());
        v.put("landingOut", landingOut.toString());
        v.put("logDir", logsDir.toString());
        for (Map.Entry<String, Path> e : stepDirs.entrySet()) {
            v.put("dir." + e.getKey(), e.getValue().toString());
        }
        return v;
    }
}
