package com.legalarchive.orchestrator.registry;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 */
@Component
public class WorkflowRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRegistry.class);

    private final AppProperties props;
    private final AuditLogger audit;
    private final WorkflowXmlParser parser = new WorkflowXmlParser();

    private final Map<String, WorkflowDef> workflows = new LinkedHashMap<String, WorkflowDef>();
    private final Map<String, FeedLayout> layouts = new LinkedHashMap<String, FeedLayout>();
    private final List<String> loadErrors = new ArrayList<String>();

    public WorkflowRegistry(AppProperties props, AuditLogger audit) {
        this.props = props;
        this.audit = audit;
    }

    @PostConstruct
    public synchronized void reload() {
        workflows.clear();
        layouts.clear();
        loadErrors.clear();

        File dir = new File(props.getWorkflowsDir());
        log.info("Caricamento workflow da {}", dir.getAbsolutePath());
        if (!dir.isDirectory()) {
            log.error("Directory workflow inesistente: {}", dir.getAbsolutePath());
            loadErrors.add("Workflows directory does not exist: " + dir.getAbsolutePath());
            return;
        }
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".xml"));
        if (files == null) return;
        java.util.Arrays.sort(files);

        for (File f : files) {
            try {
                WorkflowDef wf = parser.parse(f);
                if (workflows.containsKey(wf.feedId)) {
                    loadErrors.add(f.getName() + ": duplicate feedId '" + wf.feedId + "'");
                    continue;
                }
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
            } catch (Exception e) {
                log.error("Errore caricamento {}: {}", f.getName(), e.getMessage());
                loadErrors.add(f.getName() + ": " + e.getMessage());
            }
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
        return new ArrayList<String>(loadErrors);
    }
}
