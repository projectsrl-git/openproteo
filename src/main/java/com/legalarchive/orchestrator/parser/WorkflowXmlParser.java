package com.legalarchive.orchestrator.parser;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.legalarchive.orchestrator.model.def.GateDef;
import com.legalarchive.orchestrator.model.def.LoopDef;
import com.legalarchive.orchestrator.model.def.LoopEndDef;
import com.legalarchive.orchestrator.model.def.StepDef;
import com.legalarchive.orchestrator.model.def.WorkflowDef;

/**
 * Parser delle definizioni XML di workflow. Solo JDK (DOM), nessuna dipendenza.
 *
 * <workflow feedId="LA-EOR-001" name="..." cron="0 30 6 * * MON-FRI" baseDir="D:\feeds">
 *   <description>...</description>
 *   <variables><var name="x" value="y"/></variables>
 *   <steps>
 *     <step id="..." name="..." script="..." timeoutSec="300" retry="3" retryDelaySec="60">
 *       <param name="Path" value="${landingIn}"/>
 *       <output var="fileCount"/>
 *     </step>
 *     <gate id="..." name="..." type="auto|manual" condition="${fileCount} > 0"
 *           onTrue="next_step_id" onFalse="END:SKIPPED"/>
 *   </steps>
 * </workflow>
 */
public class WorkflowXmlParser {

    public WorkflowDef parse(File xmlFile) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // hardening: niente entita' esterne
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = dbf.newDocumentBuilder().parse(xmlFile);
            Element root = doc.getDocumentElement();
            if (!"workflow".equals(root.getTagName())) {
                throw new IllegalArgumentException("Expected root element <workflow>, found <" + root.getTagName() + ">");
            }

            WorkflowDef wf = new WorkflowDef();
            wf.sourceFile = xmlFile.getName();
            wf.feedId = req(root, "feedId", xmlFile);
            wf.name = root.hasAttribute("name") ? root.getAttribute("name") : wf.feedId;
            wf.sourceId = trimToNull(root.getAttribute("sourceId"));
            wf.targetId = trimToNull(root.getAttribute("targetId"));
            wf.sourceDescription = trimToNull(root.getAttribute("sourceDescription"));
            wf.targetDescription = trimToNull(root.getAttribute("targetDescription"));
            wf.production = "true".equalsIgnoreCase(root.getAttribute("production"));
            wf.cron = trimToNull(root.getAttribute("cron"));
            wf.baseDir = trimToNull(root.getAttribute("baseDir"));

            if (!wf.feedId.matches("[A-Za-z0-9._-]+")) {
                throw new IllegalArgumentException("Invalid feedId '" + wf.feedId + "': only letters, digits, . _ - are allowed");
            }

            NodeList descNodes = root.getElementsByTagName("description");
            if (descNodes.getLength() > 0) wf.description = descNodes.item(0).getTextContent().trim();

            for (Element var : children(root, "variables", "var")) {
                wf.variables.put(req(var, "name", xmlFile), var.getAttribute("value"));
            }

            Set<String> ids = new HashSet<String>();
            for (Element el : children(root, "steps", null)) {
                String tag = el.getTagName();
                if ("step".equals(tag)) {
                    StepDef s = new StepDef();
                    s.id = req(el, "id", xmlFile);
                    s.name = el.hasAttribute("name") ? el.getAttribute("name") : s.id;
                    s.exec = trimToNull(el.getAttribute("exec"));
                    if (s.exec != null && !java.util.Arrays.asList(
                            "auto", "powershell", "cmd", "jar", "sql", "ifscopy", "filecopy", "setvar", "validate", "csvreplace", "encoding", "anonymize", "mask", "split", "safecopy", "dequote", "csvsql", "xlsx2csv")
                            .contains(s.exec.toLowerCase())) {
                        throw new IllegalArgumentException("Step '" + s.id + "': exec must be auto, powershell, cmd, jar, sql, ifscopy, filecopy, setvar, validate, csvreplace, encoding, anonymize, mask, split, safecopy, dequote, csvsql or xlsx2csv");
                    }
                    String ik = s.exec == null ? null : s.exec.toLowerCase();
                    boolean internal = "sql".equals(ik) || "ifscopy".equals(ik) || "filecopy".equals(ik) || "setvar".equals(ik) || "validate".equals(ik) || "csvreplace".equals(ik) || "encoding".equals(ik) || "anonymize".equals(ik) || "mask".equals(ik) || "split".equals(ik) || "safecopy".equals(ik) || "dequote".equals(ik) || "csvsql".equals(ik) || "xlsx2csv".equals(ik);
                    // script is required only for external (process) steps
                    s.script = internal ? trimToNull(el.getAttribute("script")) : req(el, "script", xmlFile);
                    // built-in step attributes
                    s.datasource = trimToNull(el.getAttribute("datasource"));
                    s.query = trimToNull(textOrAttr(el, "query"));
                    s.ifsPath = trimToNull(el.getAttribute("ifsPath"));
                    s.source = trimToNull(el.getAttribute("source"));
                    s.dest = trimToNull(el.getAttribute("dest"));
                    s.pattern = trimToNull(el.getAttribute("pattern"));
                    s.mode = trimToNull(el.getAttribute("mode"));
                    s.overwrite = "true".equalsIgnoreCase(el.getAttribute("overwrite"));
                    s.outputVar = trimToNull(el.getAttribute("outputVar"));
                    s.csvFile = trimToNull(el.getAttribute("csvFile"));
                    s.csvSplitRows = intAttr(el, "csvSplitRows", 0);
                    s.csvSplitMb = intAttr(el, "csvSplitMb", 0);
                    s.delimiter = trimToNull(el.getAttribute("delimiter"));
                    String checksAttr = trimToNull(el.getAttribute("checks"));
                    if (checksAttr != null) for (String c : checksAttr.split(",")) { String cc = c.trim(); if (!cc.isEmpty()) s.validateChecks.add(cc); }
                    for (Element rep : directChildren(el, "replace")) {
                        com.legalarchive.orchestrator.model.def.Replacement rp = new com.legalarchive.orchestrator.model.def.Replacement();
                        rp.from = rep.getAttribute("from");
                        rp.to = rep.hasAttribute("to") ? rep.getAttribute("to") : "";
                        String cols = trimToNull(rep.getAttribute("columns"));
                        if (cols != null) for (String cn : cols.split(",")) { String c2 = cn.trim(); if (!c2.isEmpty()) rp.columns.add(c2); }
                        s.replacements.add(rp);
                    }
                    for (Element in : directChildren(el, "input")) {
                        com.legalarchive.orchestrator.model.def.CsvInput ci = new com.legalarchive.orchestrator.model.def.CsvInput();
                        ci.csv = in.getAttribute("csv");
                        ci.table = in.getAttribute("table");
                        ci.delimiter = in.getAttribute("delimiter");
                        ci.index = in.getAttribute("index");
                        s.inputs.add(ci);
                    }
                    for (Element col : directChildren(el, "column")) {
                        com.legalarchive.orchestrator.model.def.ColumnSel cs = new com.legalarchive.orchestrator.model.def.ColumnSel();
                        cs.src = col.getAttribute("src");
                        cs.as = col.hasAttribute("as") ? col.getAttribute("as") : col.getAttribute("src");
                        s.columns.add(cs);
                    }
                    s.forEach = trimToNull(el.getAttribute("forEach"));
                    s.concurrency = intAttr(el, "concurrency", 4);
                    s.timeoutSec = intAttr(el, "timeoutSec", 0);
                    s.retry = intAttr(el, "retry", 0);
                    s.retryDelaySec = intAttr(el, "retryDelaySec", 30);
                    for (Element p : directChildren(el, "param")) {
                        s.params.put(req(p, "name", xmlFile), p.getAttribute("value"));
                    }
                    for (Element o : directChildren(el, "output")) {
                        s.outputs.add(req(o, "var", xmlFile));
                    }
                    addNode(wf, s, ids, xmlFile);
                } else if ("gate".equals(tag)) {
                    GateDef g = new GateDef();
                    g.id = req(el, "id", xmlFile);
                    g.name = el.hasAttribute("name") ? el.getAttribute("name") : g.id;
                    g.type = el.hasAttribute("type") ? el.getAttribute("type") : "auto";
                    g.condition = trimToNull(el.getAttribute("condition"));
                    g.onTrue = trimToNull(el.getAttribute("onTrue"));
                    g.onFalse = trimToNull(el.getAttribute("onFalse"));
                    if (!Arrays.asList("auto", "manual").contains(g.type)) {
                        throw new IllegalArgumentException("Gate '" + g.id + "': type must be auto or manual");
                    }
                    if ("auto".equals(g.type) && g.condition == null) {
                        throw new IllegalArgumentException("Auto gate '" + g.id + "' has no condition");
                    }
                    addNode(wf, g, ids, xmlFile);
                } else if ("loop".equals(tag)) {
                    LoopDef lp = new LoopDef();
                    lp.id = req(el, "id", xmlFile);
                    lp.name = el.hasAttribute("name") ? el.getAttribute("name") : lp.id;
                    lp.over = trimToNull(el.getAttribute("over"));
                    lp.delimiter = trimToNull(el.getAttribute("delimiter"));
                    if (el.hasAttribute("itemVar")) lp.itemVar = el.getAttribute("itemVar");
                    if (el.hasAttribute("indexVar")) lp.indexVar = el.getAttribute("indexVar");
                    if (el.hasAttribute("indexStringVar")) lp.indexStringVar = el.getAttribute("indexStringVar");
                    if (el.hasAttribute("indexPad")) {
                        try { lp.indexPad = Integer.parseInt(el.getAttribute("indexPad").trim()); } catch (Exception ignore) {}
                    }
                    if (el.hasAttribute("countVar")) lp.countVar = el.getAttribute("countVar");
                    if (lp.over == null) throw new IllegalArgumentException("Loop '" + lp.id + "' is missing required attribute 'over'");
                    addNode(wf, lp, ids, xmlFile);
                } else if ("endloop".equals(tag)) {
                    LoopEndDef le = new LoopEndDef();
                    le.id = el.hasAttribute("id") ? el.getAttribute("id") : ("__endloop_" + wf.nodes.size());
                    le.name = el.hasAttribute("name") ? el.getAttribute("name") : "end loop";
                    addNode(wf, le, ids, xmlFile);
                }
            }

            if (wf.steps().isEmpty()) {
                throw new IllegalArgumentException("Workflow " + wf.feedId + " contains no steps");
            }

            // valida i target dei gate
            for (com.legalarchive.orchestrator.model.def.NodeDef n : wf.nodes) {
                if (n instanceof GateDef) {
                    GateDef g = (GateDef) n;
                    validateTarget(wf, g, g.onTrue, "onTrue");
                    validateTarget(wf, g, g.onFalse, "onFalse");
                }
            }
            return wf;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Parse error in " + xmlFile.getName() + ": " + e.getMessage(), e);
        }
    }

    private void addNode(WorkflowDef wf, com.legalarchive.orchestrator.model.def.NodeDef n, Set<String> ids, File f) {
        if (!ids.add(n.id)) {
            throw new IllegalArgumentException("Duplicate node id '" + n.id + "' in " + f.getName());
        }
        wf.nodes.add(n);
    }

    private void validateTarget(WorkflowDef wf, GateDef g, String target, String attr) {
        if (target == null) return;
        if (target.startsWith("END:")) return;
        if (wf.indexOfNode(target) < 0) {
            throw new IllegalArgumentException("Gate '" + g.id + "' " + attr + "='" + target + "': no such node");
        }
    }

    private String req(Element el, String attr, File f) {
        String v = el.getAttribute(attr);
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalArgumentException("<" + el.getTagName() + "> is missing required attribute '" + attr + "' in " + f.getName());
        }
        return v.trim();
    }

    private int intAttr(Element el, String attr, int def) {
        String v = el.getAttribute(attr);
        return (v == null || v.trim().isEmpty()) ? def : Integer.parseInt(v.trim());
    }

    private String trimToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    /** Returns the text of a child element with the given tag, or the attribute of the same name. */
    private String textOrAttr(Element el, String name) {
        for (Element c : directChildren(el, name)) {
            return c.getTextContent();
        }
        return el.getAttribute(name);
    }

    /** Figli elemento di primo livello con un dato tag. */
    private java.util.List<Element> directChildren(Element parent, String tag) {
        java.util.List<Element> out = new java.util.ArrayList<Element>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n instanceof Element && (tag == null || tag.equals(((Element) n).getTagName()))) {
                out.add((Element) n);
            }
        }
        return out;
    }

    /** Figli di root/container/tag; se tag e' null, tutti i figli del container. */
    private java.util.List<Element> children(Element root, String container, String tag) {
        java.util.List<Element> out = new java.util.ArrayList<Element>();
        for (Element c : directChildren(root, container)) {
            out.addAll(directChildren(c, tag));
        }
        return out;
    }
}
