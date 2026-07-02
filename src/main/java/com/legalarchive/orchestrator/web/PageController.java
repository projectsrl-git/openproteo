package com.legalarchive.orchestrator.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    private final com.legalarchive.orchestrator.config.AppProperties props;

    public PageController(WorkflowRegistry registry, RunStore store, AuditLogger audit, WorkflowScheduler scheduler,
                          com.legalarchive.orchestrator.config.AppProperties props) {
        this.registry = registry;
        this.store = store;
        this.audit = audit;
        this.scheduler = scheduler;
        this.props = props;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        java.util.List<String> homeVarNames = props.homeListVarNames();
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (WorkflowDef wf : registry.all()) {
            FeedLayout layout = registry.layout(wf.feedId);
            List<WorkflowRun> last = store.list(layout, 1);
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("wf", wf);
            row.put("lastRun", last.isEmpty() ? null : last.get(0));
            row.put("feedDir", layout.feedDir.toString());
            row.put("scheduled", scheduler.isScheduled(wf.feedId));
            java.util.List<String> varValues = new java.util.ArrayList<String>();
            StringBuilder varSearch = new StringBuilder();
            for (String vn : homeVarNames) {
                String v = wf.variables != null ? wf.variables.get(vn) : null;
                if (v == null) v = "";
                varValues.add(v);
                if (!v.isEmpty()) varSearch.append(' ').append(v);
            }
            row.put("varValues", varValues);
            row.put("varSearch", varSearch.toString());
            java.util.List<String> tagList = new java.util.ArrayList<String>();
            StringBuilder tagSearch = new StringBuilder();
            if (wf.tags != null) for (String t : wf.tags) {
                String rt = com.legalarchive.orchestrator.engine.VarResolver.resolve(t, wf.variables);
                if (rt == null) rt = "";
                rt = rt.trim();
                if (!rt.isEmpty()) { tagList.add(rt); tagSearch.append(' ').append(rt); }
            }
            row.put("tags", tagList);
            row.put("tagSearch", tagSearch.toString());
            rows.add(row);
        }
        model.addAttribute("rows", rows);
        model.addAttribute("homeVarNames", homeVarNames);
        model.addAttribute("errors", registry.errors());
        model.addAttribute("cronErrors", scheduler.errors());

        // --- source distribution (counts + percentage) for the top summary / source selector ---
        Map<String, Integer> srcCount = new LinkedHashMap<String, Integer>();
        for (WorkflowDef wf : registry.all()) {
            String s = (wf.sourceId == null || wf.sourceId.trim().isEmpty()) ? "—" : wf.sourceId.trim();
            Integer c = srcCount.get(s);
            srcCount.put(s, c == null ? 1 : c + 1);
        }
        int wfTotal = 0;
        for (Integer c : srcCount.values()) wfTotal += c;
        List<Map<String, Object>> sourceStats = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Integer> e : srcCount.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("source", e.getKey());
            m.put("count", e.getValue());
            m.put("pct", wfTotal > 0 ? Math.round(e.getValue() * 1000.0 / wfTotal) / 10.0 : 0.0);
            sourceStats.add(m);
        }
        Collections.sort(sourceStats, new Comparator<Map<String, Object>>() {
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return ((Integer) b.get("count")) - ((Integer) a.get("count"));
            }
        });
        model.addAttribute("sourceStats", sourceStats);
        model.addAttribute("wfTotal", wfTotal);

        // --- last run per workflow, most-recent first, top 5 (one row per workflow) ---
        List<Map<String, Object>> recent = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            if (row.get("lastRun") != null) {
                WorkflowDef wf = (WorkflowDef) row.get("wf");
                WorkflowRun lr = (WorkflowRun) row.get("lastRun");
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("feedId", wf.feedId);
                m.put("name", wf.name);
                m.put("source", (wf.sourceId == null || wf.sourceId.trim().isEmpty()) ? "—" : wf.sourceId.trim());
                m.put("run", lr);
                recent.add(m);
            }
        }
        Collections.sort(recent, new Comparator<Map<String, Object>>() {
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                String ta = ((WorkflowRun) a.get("run")).startTs, tb = ((WorkflowRun) b.get("run")).startTs;
                return (tb == null ? "" : tb).compareTo(ta == null ? "" : ta);
            }
        });
        if (recent.size() > 5) recent = new ArrayList<Map<String, Object>>(recent.subList(0, 5));
        model.addAttribute("recentRuns", recent);
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

    @GetMapping("/pools")
    public String poolFiles() {
        return "pools";
    }

    @GetMapping("/docs")
    public String docs() {
        return "docs";
    }

    @GetMapping("/view")
    public String view() {
        return "view";
    }

    @GetMapping("/designer")    public String designerNew(Model model) {
        model.addAttribute("feedId", null);
        return "designer";
    }

    @GetMapping("/bulk")
    public String bulk(Model model) {
        model.addAttribute("rows", buildSimpleRows());
        return "bulk";
    }

    @GetMapping("/variables")
    public String variables() {
        return "variables";
    }

    @GetMapping("/import")
    public String importPage() {
        return "import";
    }

    @GetMapping("/overview")
    public String overview() {
        return "overview";
    }

    private List<Map<String, Object>> buildSimpleRows() {
        List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
        for (WorkflowDef wf : registry.all()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("feedId", wf.feedId);
            m.put("name", wf.name);
            String src = (wf.sourceId == null || wf.sourceId.trim().isEmpty()) ? "" : wf.sourceId.trim();
            m.put("sourceId", src);
            m.put("label", wf.feedId + " — " + (wf.name == null ? "" : wf.name) + (src.isEmpty() ? "" : "  [src: " + src + "]"));
            list.add(m);
        }
        return list;
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
