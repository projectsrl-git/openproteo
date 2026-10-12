# 2026-10-12 — check-quarantine deletes what it extracted from a sent package

Base: `98dbc65`. Request: put in `_TEMPLATE-objunpack-check-quarantine.xml` the author's manual
change (delete after a successful as-is send), extended to the extracted audit JSON.

- FTPS step: `deleteOnSuccess=${dir.PRECHECK}/${runId}/${packageNoString}`,
  `deleteOnSuccessType=directory`. The per-package folder, not the run folder: the run folder on the
  FTPS step would delete listed packages' folders too, depending on name order (spec §24, ∩ U39).
- `deleteOnSuccessType` is not read by the engine (designer/import only).
- Template description, USAGE ("The same work in other shapes"), spec §24 (∩ U38, ∩ U39).
- Walker now runs the real `WorkflowEngine.deleteOnSuccess` (lifted). Pair suite 32/32, 6/6
  mutations caught; other suites 8, 23, 6.

Verified on Linux (JDK): Temurin 1.8.0_432 and JDK 21.0.12, walker.
Verified on Windows: nothing.
Not verified: a real FTPS send (a failed send deleting nothing rests on the engine code), AIX, the engine itself.
