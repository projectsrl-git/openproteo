package com.legalarchive.orchestrator.web.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON model exchanged between the visual Workflow Designer and the server.
 * Mirrors the XML definition one-to-one; node order is significant.
 */
public class WorkflowDto {

    public String feedId;
    public String sourceId;
    public String targetId;
    public String name;
    public String cron;
    public String baseDir;
    public String description;
    public List<KV> variables = new ArrayList<KV>();
    public List<NodeDto> nodes = new ArrayList<NodeDto>();

    public static class KV {
        public String name;
        public String value;
        public KV() {}
        public KV(String name, String value) { this.name = name; this.value = value; }
    }

    public static class NodeDto {
        public String kind;        // STEP | GATE | LOOP | ENDLOOP
        public String id;
        public String name;
        // loop (kind=LOOP)
        public String over;
        public String loopDelimiter;
        public String itemVar;
        public String indexVar;
        public String countVar;
        // step fields
        public String script;
        public String exec;        // auto | powershell | cmd | jar | sql | ifscopy | filecopy | setvar
        public Integer timeoutSec;
        public Integer retry;
        public Integer retryDelaySec;
        public List<KV> params = new ArrayList<KV>();
        public List<String> outputs = new ArrayList<String>();
        // built-in step fields
        public String datasource;
        public String query;
        public String ifsPath;
        public String source;
        public String dest;
        public String pattern;
        public String mode;
        public Boolean overwrite;
        public String outputVar;
        public String csvFile;
        public static class ReplacementDto { public String from; public String to; public java.util.List<String> columns; }
        public Integer csvSplitRows;
        public Integer csvSplitMb;
        public java.util.List<String> validateChecks;
        public java.util.List<ReplacementDto> replacements;
        public String delimiter;
        // parallel fan-out
        public String forEach;
        public Integer concurrency;
        // gate fields
        public String type;        // auto | manual
        public String condition;
        public String onTrue;
        public String onFalse;
    }
}
