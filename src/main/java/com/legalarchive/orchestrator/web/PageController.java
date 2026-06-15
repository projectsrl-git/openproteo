package com.legalarchive.orchestrator.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.legalarchive.orchestrator.audit.AuditLogger;
import com.legalarchive.orchestrator.engine.WorkflowScheduler;
import com.legalarchive.orchestrator.model.def.WorkflowDef;
import com.legalarchive.orchestrator.model.run.WorkflowRun;
import com.legalarchive.orchestrator.registry.WorkflowRegistry;
import com.legalarchive.orchestrator.store.FeedLayout;
import com.legalarchive.orchestrator.store.RunStore;

@Controller
public class PageController {

    private final WorkflowRegistry registry;
    private final RunStore store;
    private final AuditLogger audit;
    private final WorkflowScheduler scheduler;

    public PageController(WorkflowRegistry registry, RunStore store, AuditLogger audit, WorkflowScheduler scheduler) {
        this.registry = registry;
        this.store = store;
        this.audit = audit;
        this.scheduler = scheduler;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (WorkflowDef wf : registry.all()) {
            FeedLayout layout = registry.layout(wf.feedId);
            List<WorkflowRun> last = store.list(layout, 1);
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("wf", wf);
            row.put("lastRun", last.isEmpty() ? null : last.get(0));
            row.put("feedDir", layout.feedDir.toString());
            row.put("scheduled", scheduler.isScheduled(wf.feedId));
            rows.add(row);
        }
        model.addAttribute("rows", rows);
        model.addAttribute("errors", registry.errors());
        model.addAttribute("cronErrors", scheduler.errors());
        return "dashboard";
    }

    @GetMapping("/workflow/{feedId}")
    public String workflow(@PathVariable String feedId, Model model) {
        WorkflowDef wf = registry.get(feedId);
        if (wf == null) return "redirect:/";
        FeedLayout layout = registry.layout(feedId);
        model.addAttribute("wf", wf);
        model.addAttribute("layout", layout);
        model.addAttribute("stepDirs", layout.stepDirs);
        model.addAttribute("runs", store.list(layout, 50));
        model.addAttribute("scheduled", scheduler.isScheduled(feedId));
        return "workflow";
    }

    @GetMapping("/run/{feedId}/{runId}")
    public String run(@PathVariable String feedId, @PathVariable String runId, Model model) {
        WorkflowDef wf = registry.get(feedId);
        if (wf == null) return "redirect:/";
        model.addAttribute("wf", wf);
        model.addAttribute("feedId", feedId);
        model.addAttribute("runId", runId);
        return "run";
    }

    @GetMapping("/datasources")
    public String datasources() {
        return "datasources";
    }

    @GetMapping("/files")
    public String sharedFiles() {
        return "shared";
    }

    @GetMapping("/view")
    public String view() {
        return "view";
    }

    @GetMapping("/designer")
    public String designerNew(Model model) {
        model.addAttribute("feedId", null);
        return "designer";
    }

    @GetMapping("/designer/{feedId}")
    public String designerEdit(@PathVariable String feedId, Model model) {
        if (registry.get(feedId) == null) return "redirect:/designer";
        model.addAttribute("feedId", feedId);
        return "designer";
    }

    @GetMapping("/runs/{feedId}")
    public String runsPage(@PathVariable String feedId, Model model) {
        WorkflowDef wf = registry.get(feedId);
        if (wf == null) return "redirect:/";
        model.addAttribute("wf", wf);
        return "runs";
    }

    @GetMapping("/audit/{feedId}")
    public String auditPage(@PathVariable String feedId,
                            @RequestParam(value = "last", defaultValue = "300") int last,
                            Model model) {
        WorkflowDef wf = registry.get(feedId);
        if (wf == null) return "redirect:/";
        FeedLayout layout = registry.layout(feedId);
        List<AuditLogger.Entry> entries = audit.read(layout.auditFile(), last);
        java.util.Collections.reverse(entries); // piu' recenti in alto
        model.addAttribute("wf", wf);
        model.addAttribute("entries", entries);
        model.addAttribute("auditFile", layout.auditFile().toString());
        model.addAttribute("verify", audit.verify(layout.auditFile()));
        return "audit";
    }
}
