package com.legalarchive.orchestrator.registry;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.legalarchive.orchestrator.audit.AuditLogger;
import com.legalarchive.orchestrator.config.AppProperties;
import com.legalarchive.orchestrator.model.def.WorkflowDef;
import com.legalarchive.orchestrator.parser.WorkflowXmlParser;
import com.legalarchive.orchestrator.store.FeedLayout;

/**
 * Registro dei workflow: carica i file XML da orchestrator.workflowsDir,
 * crea (provisiona) la struttura directory di ogni feed e tiene traccia
 * degli errori di parsing per mostrarli in dashboard.
 *
 * <p>Two ways to bring the registry up to date with the directory. {@link #reload()} reads every
 * file again; it runs at start-up and behind the Reload button. {@link #refresh(Collection)} reads
 * only the files that changed, and is what a save uses. The contract between the two: after
 * {@code refresh} the registry holds exactly what {@code reload} would have put there - same
 * workflows, same order, same load errors - and whenever {@code refresh} cannot be sure of that,
 * it calls {@code reload} and says why.
 */
@Component
public class WorkflowRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRegistry.class);

    private final AppProperties props;
    private final AuditLogger audit;
    private final WorkflowXmlParser parser = new WorkflowXmlParser();

    private final Map<String, WorkflowDef> workflows = new LinkedHashMap<String, WorkflowDef>();
    private final Map<String, FeedLayout> layouts = new LinkedHashMap<String, FeedLayout>();
    private final List<LoadError> loadErrors = new ArrayList<LoadError>();

    /** Every *.xml as it was when it was last read: file name -> { lastModified, length }. */
    private final Map<String, long[]> seen = new HashMap<String, long[]>();
    /** False until a reload has listed the directory: nothing to compare a refresh against. */
    private boolean listed;

    public WorkflowRegistry(AppProperties props, AuditLogger audit) {
        this.props = props;
        this.audit = audit;
    }

    @PostConstruct
    public synchronized void reload() {
        workflows.clear();
        layouts.clear();
        loadErrors.clear();
        seen.clear();
        listed = false;

        File dir = new File(props.getWorkflowsDir());
        log.info("Caricamento workflow da {}", dir.getAbsolutePath());
        if (!dir.isDirectory()) {
            log.error("Directory workflow inesistente: {}", dir.getAbsolutePath());
            loadErrors.add(new LoadError(null, "Workflows directory does not exist: " + dir.getAbsolutePath(), null, false));
            return;
        }
        File[] files = listXml(dir);
        if (files == null) return;
        java.util.Arrays.sort(files);
        listed = true;

        for (File f : files) {
            // Taken before the file is read: a write that lands in between leaves a stamp that no
            // longer matches, and the next refresh reads the file again.
            seen.put(f.getName(), stamp(f));
            WorkflowDef wf;
            try {
                wf = parser.parse(f);
            } catch (Exception e) {
                log.error("Errore caricamento {}: {}", f.getName(), e.getMessage());
                loadErrors.add(new LoadError(f.getName(), f.getName() + ": " + e.getMessage(), null, false));
                continue;
            }
            if (workflows.containsKey(wf.feedId)) {
                loadErrors.add(new LoadError(f.getName(), f.getName() + ": duplicate feedId '" + wf.feedId + "'", wf.feedId, false));
                continue;
            }
            try {
                admit(f, wf);
            } catch (Exception e) {
                log.error("Errore caricamento {}: {}", f.getName(), e.getMessage());
                loadErrors.add(new LoadError(f.getName(), f.getName() + ": " + e.getMessage(), null, true));
            }
        }
    }

    /**
     * Brings the registry up to date reading only what changed: the files named in
     * {@code written} (always read, whatever their timestamp says - two saves of the same length
     * within one tick of a coarse file-system clock look identical), and every other *.xml whose
     * modification time or length differs from when it was last read, or that appeared or
     * disappeared. A file dropped into the directory by hand is therefore still picked up by the
     * next save, as it was when a save reloaded everything.
     *
     * <p>Nothing is written, logged as loaded or audited for a workflow whose file did not change.
     *
     * <p>Falls back to {@link #reload()} - and returns {@code full} - in the cases where the
     * outcome depends on files other than the changed ones: a feedId that moves between files or
     * is declared twice, a workflow whose directories could not be set up, a directory that was
     * not listed last time. Those are decided by the order of the whole directory, and reload is
     * the one place that knows it.
     *
     * @param written file names (a path is reduced to its name) the caller has just written or
     *                deleted; may be empty or null
     */
    public synchronized Refresh refresh(Collection<String> written) {
        File dir = new File(props.getWorkflowsDir());
        if (!listed) return full("the workflows directory was not listed at the last load");
        File[] files = dir.isDirectory() ? listXml(dir) : null;
        if (files == null) return full("the workflows directory cannot be listed");
        java.util.Arrays.sort(files);
        for (LoadError le : loadErrors) {
            if (le.setup) return full(le.file + " could not be set up at the last load");
        }

        Set<String> forced = new HashSet<String>();
        if (written != null) {
            for (String w : written) if (w != null) forced.add(new File(w).getName().toLowerCase(java.util.Locale.ROOT));
        }

        // 1. What is different on disk.
        List<File> toRead = new ArrayList<File>();
        Map<String, long[]> stamps = new HashMap<String, long[]>();
        for (File f : files) {
            String n = f.getName();
            long[] now = stamp(f);
            stamps.put(n, now);
            long[] was = seen.get(n);
            // The forced match ignores case on every host: on a case-insensitive one the name on
            // disk may differ in case from the name the caller built, and reading one file more
            // than needed costs nothing.
            if (was == null || was[0] != now[0] || was[1] != now[1]
                    || forced.contains(n.toLowerCase(java.util.Locale.ROOT))) {
                toRead.add(f);
            }
        }
        List<String> gone = new ArrayList<String>();
        for (String n : seen.keySet()) if (!stamps.containsKey(n)) gone.add(n);

        // 2. Decide, changing nothing: either every change is local to its own file, or reload.
        Map<String, WorkflowDef> byFile = new HashMap<String, WorkflowDef>();
        for (WorkflowDef wf : workflows.values()) byFile.put(wf.sourceFile, wf);
        Set<String> contested = new HashSet<String>();
        for (LoadError le : loadErrors) if (le.duplicateOf != null) contested.add(le.duplicateOf);
        Set<String> held = new HashSet<String>(workflows.keySet());

        for (String n : gone) {
            WorkflowDef old = byFile.get(n);
            if (old == null) continue;
            if (contested.contains(old.feedId)) return full("feedId '" + old.feedId + "' is declared by more than one file");
            held.remove(old.feedId);
        }
        Map<String, WorkflowDef> parsed = new HashMap<String, WorkflowDef>();
        Map<String, String> failed = new HashMap<String, String>();
        for (File f : toRead) {
            String n = f.getName();
            WorkflowDef old = byFile.get(n);
            WorkflowDef wf;
            try {
                wf = parser.parse(f);
            } catch (Exception e) {
                if (old != null) {
                    if (contested.contains(old.feedId)) return full("feedId '" + old.feedId + "' is declared by more than one file");
                    held.remove(old.feedId);
                }
                failed.put(n, e.getMessage());
                continue;
            }
            if (old != null) {
                if (old.feedId == null ? wf.feedId != null : !old.feedId.equals(wf.feedId)) {
                    return full(n + " changed its feedId");
                }
            } else {
                // A contested feedId is always a held one (its winner is in the registry, or this
                // refresh has already gone to reload over it), so this one test covers both.
                if (held.contains(wf.feedId)) {
                    return full("feedId '" + wf.feedId + "' is declared by more than one file");
                }
                held.add(wf.feedId);
            }
            parsed.put(n, wf);
        }

        // 3. Apply.
        List<String> loaded = new ArrayList<String>();
        List<String> removed = new ArrayList<String>();
        for (String n : gone) {
            WorkflowDef old = byFile.get(n);
            if (old != null) {
                workflows.remove(old.feedId);
                layouts.remove(old.feedId);
                removed.add(old.feedId);
                log.info("[{}] removed: {} is no longer in the workflows directory", old.feedId, n);
            }
            dropError(n);
            seen.remove(n);
        }
        for (File f : toRead) {
            String n = f.getName();
            dropError(n);
            seen.put(n, stamps.get(n));
            WorkflowDef wf = parsed.get(n);
            if (wf == null) {
                WorkflowDef old = byFile.get(n);
                if (old != null) {
                    workflows.remove(old.feedId);
                    layouts.remove(old.feedId);
                    removed.add(old.feedId);
                }
                log.error("Errore caricamento {}: {}", n, failed.get(n));
                loadErrors.add(new LoadError(n, n + ": " + failed.get(n), null, false));
                continue;
            }
            try {
                admit(f, wf);
            } catch (Exception e) {
                // What reload does with this depends on how far admit got; let reload do it.
                return full(n + " could not be set up: " + e.getMessage());
            }
            loaded.add(wf.feedId);
        }
        if (toRead.isEmpty() && gone.isEmpty()) {
            return new Refresh(false, null, loaded, removed);
        }
        inFileOrder(dir);
        log.info("Workflow refresh: {} file(s) read, {} removed, {} untouched", toRead.size(), gone.size(),
                files.length - toRead.size());
        return new Refresh(false, null, loaded, removed);
    }

    private Refresh full(String reason) {
        log.info("Workflow refresh: reloading every workflow - {}", reason);
        reload();
        return new Refresh(true, reason, Collections.<String>emptyList(), Collections.<String>emptyList());
    }

    /** Layout, directories, registration, log line and audit record of one parsed workflow. */
    private void admit(File f, WorkflowDef wf) throws Exception {
        FeedLayout layout = new FeedLayout(wf, props.getDefaultBaseDir());
        boolean created = layout.provision();
        workflows.put(wf.feedId, wf);
        layouts.put(wf.feedId, layout);
        log.info("[{}] caricato '{}' da {} ({} nodi, cron={}) feedDir={}", wf.feedId, wf.name,
                f.getName(), wf.nodes.size(), wf.cron == null ? "manuale" : wf.cron, layout.feedDir);
        if (created) log.info("[{}] struttura directory provisionata", wf.feedId);

        Map<String, String> det = new LinkedHashMap<String, String>();
        det.put("file", f.getName());
        det.put("name", wf.name);
        det.put("cron", wf.cron == null ? "(manual)" : wf.cron);
        det.put("feedDir", layout.feedDir.toString());
        audit.log(layout.auditFile(), wf.feedId, null, null, "WORKFLOW_LOADED", "system", det);
        if (created) {
            audit.log(layout.auditFile(), wf.feedId, null, null, "DIRS_PROVISIONED", "system",
                    java.util.Collections.singletonMap("feedDir", layout.feedDir.toString()));
        }
    }

    /**
     * Puts workflows, layouts and load errors back in the order reload gives them: the order of
     * {@code Arrays.sort(File[])} over the directory, which is the host's own (it ignores case on
     * Windows and not on Linux). The same comparison is used here, on the same kind of File, so a
     * workflow added by a refresh lands where a reload would have put it.
     */
    private void inFileOrder(File dir) {
        final Map<String, File> at = new HashMap<String, File>();
        List<WorkflowDef> defs = new ArrayList<WorkflowDef>(workflows.values());
        for (WorkflowDef wf : defs) at.put(wf.sourceFile, new File(dir, wf.sourceFile));
        for (LoadError le : loadErrors) if (le.file != null) at.put(le.file, new File(dir, le.file));

        Collections.sort(defs, new Comparator<WorkflowDef>() {
            public int compare(WorkflowDef a, WorkflowDef b) { return at.get(a.sourceFile).compareTo(at.get(b.sourceFile)); }
        });
        Map<String, FeedLayout> lay = new HashMap<String, FeedLayout>(layouts);
        workflows.clear();
        layouts.clear();
        for (WorkflowDef wf : defs) {
            workflows.put(wf.feedId, wf);
            layouts.put(wf.feedId, lay.get(wf.feedId));
        }
        Collections.sort(loadErrors, new Comparator<LoadError>() {
            public int compare(LoadError a, LoadError b) {
                if (a.file == null || b.file == null) return a.file == null ? (b.file == null ? 0 : -1) : 1;
                return at.get(a.file).compareTo(at.get(b.file));
            }
        });
    }

    private void dropError(String file) {
        for (java.util.Iterator<LoadError> it = loadErrors.iterator(); it.hasNext();) {
            if (file.equals(it.next().file)) it.remove();
        }
    }

    private static File[] listXml(File dir) {
        return dir.listFiles((d, n) -> n.toLowerCase().endsWith(".xml"));
    }

    private static long[] stamp(File f) {
        return new long[] { f.lastModified(), f.length() };
    }

    /** One line of the dashboard's load errors, with what refresh needs to know about it. */
    private static final class LoadError {
        /** The file the error is about; null for the error about the directory itself. */
        final String file;
        final String message;
        /** The feedId this file lost to an earlier file, or null. */
        final String duplicateOf;
        /** True when the file parsed and the failure came after: it depends on the machine, not
            on the file, so reading the file again may give a different answer. */
        final boolean setup;

        LoadError(String file, String message, String duplicateOf, boolean setup) {
            this.file = file;
            this.message = message;
            this.duplicateOf = duplicateOf;
            this.setup = setup;
        }
    }

    /** What a {@link #refresh(Collection)} did. */
    public static final class Refresh {
        /** True when every workflow was reloaded; {@link #loaded} and {@link #removed} are then empty. */
        public final boolean full;
        /** Why everything was reloaded; null otherwise. */
        public final String reason;
        /** feedIds read again or read for the first time. */
        public final List<String> loaded;
        /** feedIds no longer in the registry. */
        public final List<String> removed;

        Refresh(boolean full, String reason, List<String> loaded, List<String> removed) {
            this.full = full;
            this.reason = reason;
            this.loaded = Collections.unmodifiableList(loaded);
            this.removed = Collections.unmodifiableList(removed);
        }
    }

    public synchronized List<WorkflowDef> all() {
        return new ArrayList<WorkflowDef>(workflows.values());
    }

    public synchronized WorkflowDef get(String feedId) {
        return workflows.get(feedId);
    }

    public synchronized FeedLayout layout(String feedId) {
        return layouts.get(feedId);
    }

    public synchronized List<String> errors() {
        List<String> out = new ArrayList<String>(loadErrors.size());
        for (LoadError le : loadErrors) out.add(le.message);
        return out;
    }
}
