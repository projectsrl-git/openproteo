# 2026-06-18 — bulk extra fields, dashboard target, JSON/XML format, light hover, template sourceId

1) Template dropdown (Bulk create) now shows the source: buildSimpleRows() adds sourceId and a
   precomputed label "<feedId> — <name>  [src: <sourceId>]"; bulk.html option uses ${r.label}.

2) Light theme: button hover was using dark fills (#1d2636 / #2a2210) hardcoded for the dark
   theme, making text unreadable on hover under light. Added light overrides for .btn:hover,
   .btn.primary:hover, .btn.ok:hover and the ghost/addline/nc-tools button hovers.

3) Dashboard: added a Target column next to Source (row.wf.targetId), colspans 8->9, and
   targetId included in the row data-search string.

4) Viewer/editor: a "Format" button pretty-prints JSON (JSON.parse/stringify) or XML (DOMParser
   + recursive indenter) for inline/unformatted files. Added in the file viewer edit toolbar
   (viewer.js) and in the designer's direct-XML editor (designer.html). Type chosen by extension,
   then by first non-space char. No literal \n/\r in JS (String.fromCharCode(10)).

5) Bulk create — two new FEED CSV mapped columns:
     recordBusinessDate       -> injected as workflow variable ${recordBusinessDate}
     recordBusinessDateFormat -> injected as workflow variable ${recordBusinessDateFormat}
   Wired through bulk.html (fields + JS params), ApiController.bulkCreate (@RequestParam +
   Mapping), and BulkWorkflowGenerator (Mapping fields + setVariable in generate()).
   Verified: generated workflow carries both vars and the targetId attribute.

77 classes compile; viewer.js + designer.html + bulk.html + dashboard.html JS valid; no \n/\r;
Thymeleaf-safe.
