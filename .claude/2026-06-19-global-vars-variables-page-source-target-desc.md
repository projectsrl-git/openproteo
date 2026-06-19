# 2026-06-19 — Global variables, Variables page, source/target descriptions

## Summary
Three related features delivered together.

### 1. Global variables (common to all workflows) — file + properties
- `store/GlobalVarsStore` (@Component): loads a properties file
  (`orchestrator.global-vars-file`, default `<sharedDir>/global-vars.properties`)
  and merges `orchestrator.global-vars.*` from application.properties on top
  (properties win). `all()` is the runtime view; `fileVars()`/`propsVars()` keep
  the two sources separate for the UI; `saveFile()` persists + reloads.
- `config/AppProperties`: `Map<String,String> globalVars` + `String globalVarsFile`
  with getters/setters.
- `engine/WorkflowEngine`: constructor now takes GlobalVarsStore; run vars seed
  `globalVars.all()` first (lowest precedence), before built-ins and
  `def.variables`.

### 2. Variables manager page (`/variables`)
- `web/PageController` route `/variables`; dashboard nav link "Variables".
- `templates/variables.html` (inline JS, no external page js):
  - Global variables editor (file-based editable rows + Save; properties-based
    listed locked).
  - Three cascading multi-selects (source / target / feed) that filter each
    other via `distinct(kind)` over the catalog.
  - 1 feed selected -> `renderSingle`: workflow variables section + one section
    per step with its params, all editable (dirty-tracked).
  - >=2 feeds -> `renderCommon`: intersection of variable names present in all
    selected feeds; "(values differ)" when they are not equal; one value applied
    to all on save.
- Endpoints in `web/ApiController`:
  - `GET /api/var-catalog` — globals + per-workflow {ids, descriptions, vars,
    steps[params]}.
  - `POST /api/globals/save` — validates names, writes file-based globals.
  - `POST /api/variables/save` — applies var/step-param edits to a DTO per feed,
    regenerates + parses ALL before writing any (transactional); writes valid set,
    reloads registry + scheduler; returns per-feed results.

### 3. Source / target descriptions
- `sourceDescription` / `targetDescription` added to `WorkflowDef`, `WorkflowDto`,
  `WorkflowXmlParser` (reads attrs), `WorkflowXmlWriter` (emits attrs),
  `ApiController.toDto`.
- `designer.html`: two new fields + upd()/fill()/preview head + initial wf object.
- `BulkWorkflowGenerator.Mapping` + bulk endpoint params + `bulk.html` fields, so
  both are loadable from the bulk feed CSV.

## Verification
- Full compile: 82 classes (Spring/Jackson/jt400/slf4j stubs rebuilt after the
  sandbox reset; JDK 21 reinstalled).
- Tests (pure Java, run locally):
  - parser reads + writer emits + XML round-trip of source/target descriptions.
  - bulk generation emits both and round-trips through the parser.
  - GlobalVarsStore file/properties precedence, fileVars/propsVars separation.
- Inline JS for variables/designer/bulk passes `node --check`; no bare `\n`/`\r`;
  no unsafe `[[`/`[(`.

## Not testable in sandbox
Browser rendering, real HTTP/Spring binding, and full Spring wiring of the new
endpoints. Verify on UBS with Ctrl+F5.
