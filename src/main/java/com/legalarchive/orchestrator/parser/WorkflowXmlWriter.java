package com.legalarchive.orchestrator.parser;

import java.io.StringWriter;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.legalarchive.orchestrator.web.dto.WorkflowDto;

/**
 * Generates the workflow XML definition from the designer DTO.
 * JDK-only (DOM + Transformer). The produced XML is then re-validated with
 * {@link WorkflowXmlParser} before being written to the workflows directory.
 */
public class WorkflowXmlWriter {

    public String toXml(WorkflowDto dto) {
        try {
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Element root = doc.createElement("workflow");
            doc.appendChild(root);
            attr(root, "feedId", dto.feedId);
            attr(root, "name", dto.name);
            attr(root, "sourceId", dto.sourceId);
            attr(root, "targetId", dto.targetId);
            attr(root, "sourceDescription", dto.sourceDescription);
            attr(root, "targetDescription", dto.targetDescription);
            if (dto.production) root.setAttribute("production", "true");
            attr(root, "cron", dto.cron);
            attr(root, "baseDir", dto.baseDir);

            if (notBlank(dto.description)) {
                Element d = doc.createElement("description");
                d.setTextContent(dto.description.trim());
                root.appendChild(d);
            }

            if (dto.variables != null && !dto.variables.isEmpty()) {
                Element vars = doc.createElement("variables");
                root.appendChild(vars);
                for (WorkflowDto.KV v : dto.variables) {
                    if (v == null || !notBlank(v.name)) continue;
                    Element var = doc.createElement("var");
                    var.setAttribute("name", v.name.trim());
                    var.setAttribute("value", v.value == null ? "" : v.value);
                    vars.appendChild(var);
                }
            }

            Element steps = doc.createElement("steps");
            root.appendChild(steps);
            if (dto.nodes != null) {
                for (WorkflowDto.NodeDto n : dto.nodes) {
                    if (n == null) continue;
                    if ("GATE".equalsIgnoreCase(n.kind)) {
                        Element g = doc.createElement("gate");
                        attr(g, "id", n.id);
                        attr(g, "name", n.name);
                        attr(g, "type", n.type == null ? "auto" : n.type);
                        if (!"manual".equalsIgnoreCase(n.type == null ? "auto" : n.type)) {
                            attr(g, "condition", n.condition);
                        }
                        attr(g, "onTrue", n.onTrue);
                        attr(g, "onFalse", n.onFalse);
                        steps.appendChild(g);
                    } else if ("LOOP".equalsIgnoreCase(n.kind)) {
                        Element lp = doc.createElement("loop");
                        attr(lp, "id", n.id);
                        attr(lp, "name", n.name);
                        attr(lp, "over", n.over);
                        attr(lp, "delimiter", n.loopDelimiter);
                        if (n.itemVar != null && !n.itemVar.trim().isEmpty()) attr(lp, "itemVar", n.itemVar);
                        if (n.indexVar != null && !n.indexVar.trim().isEmpty()) attr(lp, "indexVar", n.indexVar);
                        if (n.indexStringVar != null && !n.indexStringVar.trim().isEmpty()) attr(lp, "indexStringVar", n.indexStringVar);
                        if (n.indexPad != null && !n.indexPad.trim().isEmpty()) attr(lp, "indexPad", n.indexPad);
                        if (n.countVar != null && !n.countVar.trim().isEmpty()) attr(lp, "countVar", n.countVar);
                        steps.appendChild(lp);
                    } else if ("ENDLOOP".equalsIgnoreCase(n.kind)) {
                        Element le = doc.createElement("endloop");
                        attr(le, "id", n.id);
                        steps.appendChild(le);
                    } else {
                        Element s = doc.createElement("step");
                        attr(s, "id", n.id);
                        attr(s, "name", n.name);
                        attr(s, "script", n.script);
                        if (n.exec != null && !n.exec.trim().isEmpty() && !"auto".equalsIgnoreCase(n.exec.trim())) {
                            s.setAttribute("exec", n.exec.trim());
                        }
                        attr(s, "datasource", n.datasource);
                        attr(s, "ifsPath", n.ifsPath);
                        attr(s, "source", n.source);
                        attr(s, "dest", n.dest);
                        attr(s, "pattern", n.pattern);
                        attr(s, "mode", n.mode);
                        if (n.overwrite != null && n.overwrite) s.setAttribute("overwrite", "true");
                        attr(s, "outputVar", n.outputVar);
                        attr(s, "csvFile", n.csvFile);
                        if (n.validateChecks != null && !n.validateChecks.isEmpty()) s.setAttribute("checks", String.join(",", n.validateChecks));
                        if (n.replacements != null) for (com.legalarchive.orchestrator.web.dto.WorkflowDto.NodeDto.ReplacementDto rp : n.replacements) {
                            org.w3c.dom.Element re = doc.createElement("replace");
                            re.setAttribute("from", rp.from == null ? "" : rp.from);
                            re.setAttribute("to", rp.to == null ? "" : rp.to);
                            if (rp.columns != null && !rp.columns.isEmpty()) re.setAttribute("columns", String.join(",", rp.columns));
                            s.appendChild(re);
                        }
                        if (n.inputs != null) for (com.legalarchive.orchestrator.web.dto.WorkflowDto.NodeDto.CsvInputDto ci : n.inputs) {
                            org.w3c.dom.Element ie = doc.createElement("input");
                            ie.setAttribute("csv", ci.csv == null ? "" : ci.csv);
                            ie.setAttribute("table", ci.table == null ? "" : ci.table);
                            s.appendChild(ie);
                        }
                        if (n.columns != null) for (com.legalarchive.orchestrator.web.dto.WorkflowDto.NodeDto.ColumnSelDto cs : n.columns) {
                            org.w3c.dom.Element ce = doc.createElement("column");
                            ce.setAttribute("src", cs.src == null ? "" : cs.src);
                            if (cs.as != null && !cs.as.isEmpty()) ce.setAttribute("as", cs.as);
                            s.appendChild(ce);
                        }
                        if (n.csvSplitRows != null && n.csvSplitRows > 0) s.setAttribute("csvSplitRows", String.valueOf(n.csvSplitRows));
                        if (n.csvSplitMb != null && n.csvSplitMb > 0) s.setAttribute("csvSplitMb", String.valueOf(n.csvSplitMb));
                        attr(s, "delimiter", n.delimiter);
                        attr(s, "forEach", n.forEach);
                        if (n.concurrency != null && n.concurrency > 0 && n.concurrency != 4) {
                            s.setAttribute("concurrency", String.valueOf(n.concurrency));
                        }
                        if (n.timeoutSec != null && n.timeoutSec > 0) s.setAttribute("timeoutSec", String.valueOf(n.timeoutSec));
                        if (n.retry != null && n.retry > 0) {
                            s.setAttribute("retry", String.valueOf(n.retry));
                            if (n.retryDelaySec != null && n.retryDelaySec > 0) {
                                s.setAttribute("retryDelaySec", String.valueOf(n.retryDelaySec));
                            }
                        }
                        if (notBlank(n.query)) {
                            Element q = doc.createElement("query");
                            q.setTextContent(n.query);
                            s.appendChild(q);
                        }
                        if (n.params != null) {
                            for (WorkflowDto.KV p : n.params) {
                                if (p == null || !notBlank(p.name)) continue;
                                Element pe = doc.createElement("param");
                                pe.setAttribute("name", p.name.trim());
                                pe.setAttribute("value", p.value == null ? "" : p.value);
                                s.appendChild(pe);
                            }
                        }
                        if (n.outputs != null) {
                            for (String o : n.outputs) {
                                if (!notBlank(o)) continue;
                                Element oe = doc.createElement("output");
                                oe.setAttribute("var", o.trim());
                                s.appendChild(oe);
                            }
                        }
                        steps.appendChild(s);
                    }
                }
            }

            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
            StringWriter out = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            throw new RuntimeException("XML generation failed: " + e.getMessage(), e);
        }
    }

    private static void attr(Element el, String name, String value) {
        if (notBlank(value)) el.setAttribute(name, value.trim());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
