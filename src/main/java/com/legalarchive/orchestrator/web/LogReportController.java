package com.legalarchive.orchestrator.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.legalarchive.orchestrator.logreport.LogIndexer;
import com.legalarchive.orchestrator.logreport.LogQueryService;

/**
 * Cross-feed log report API. Deliberately its own controller: ApiController is already the whole app
 * in one file, and this feature grows (timeseries, metrics, facets, export) over the next batches.
 *
 * <p>Batch 1 ships search plus two operational endpoints (status, reindex); the UI comes later.</p>
 */
@RestController
public class LogReportController {

    private final LogQueryService query;
    private final LogIndexer indexer;

    public LogReportController(LogQueryService query, LogIndexer indexer) {
        this.query = query;
        this.indexer = indexer;
    }

    @GetMapping("/api/logs/search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam(required = false) List<String> feedId,
            @RequestParam(required = false) List<String> sourceId,
            @RequestParam(required = false) List<String> targetId,
            @RequestParam(required = false) String step,
            @RequestParam(required = false) List<String> event,
            @RequestParam(required = false) List<String> severity,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {

        LogQueryService.Filters f = new LogQueryService.Filters();
        f.feedId = feedId; f.sourceId = sourceId; f.targetId = targetId;
        f.step = step; f.event = event; f.severity = severity;
        f.user = user; f.from = from; f.to = to; f.q = q;

        try {
            return ResponseEntity.ok(query.search(f, sort, page, size));
        } catch (Exception e) {
            return error(e);
        }
    }

    /** Runs with their output data: the OUTPUT DATA of Operations and the run history, searchable. */
    @GetMapping("/api/logs/runs")
    public ResponseEntity<Map<String, Object>> runs(
            @RequestParam(required = false) List<String> feedId,
            @RequestParam(required = false) List<String> sourceId,
            @RequestParam(required = false) List<String> targetId,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        LogQueryService.Filters f = new LogQueryService.Filters();
        f.feedId = feedId; f.sourceId = sourceId; f.targetId = targetId;
        f.user = user; f.from = from; f.to = to; f.q = q;
        try {
            return ResponseEntity.ok(query.runs(f, status, page, size));
        } catch (Exception e) {
            return error(e);
        }
    }

    /** Distinct values for the filter controls. */
    @GetMapping("/api/logs/facets")
    public ResponseEntity<Map<String, Object>> facets() {
        try {
            return ResponseEntity.ok(query.facets());
        } catch (Exception e) {
            return error(e);
        }
    }

    /** How much is loaded and when it was last refreshed - the first thing to check if a row is missing. */
    @GetMapping("/api/logs/status")
    public ResponseEntity<Map<String, Object>> status() {
        indexer.ensureFresh(false);
        return ResponseEntity.ok(indexer.status());
    }

    /** Full rebuild from the files. Will be restricted to PROTEO_MASTER once RBAC Phase 1 is merged. */
    @PostMapping("/api/logs/reindex")
    public ResponseEntity<Map<String, Object>> reindex() {
        try {
            long t0 = System.currentTimeMillis();
            indexer.reindex();
            Map<String, Object> out = new LinkedHashMap<String, Object>(indexer.status());
            out.put("ok", true);
            out.put("elapsedMs", System.currentTimeMillis() - t0);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return error(e);
        }
    }

    private static ResponseEntity<Map<String, Object>> error(Exception e) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", false);
        out.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(out);
    }
}
