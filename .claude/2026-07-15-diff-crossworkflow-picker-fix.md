# diff cross-workflow picker: File A/B not populated on selection — fix

## Symptom
Selecting File A / File B from the "…or from a workflow" dropdowns sometimes left
the File A / File B validation still complaining that the boxes are required.

## Cause (fragility)
The picker was fragile at two points:
1. `diffFileChosen` returned early `if (!fsel || !fld)` — if the File input
   element wasn't found at that instant, `setNodeParam` was never called and the
   `fileA`/`fileB` param stayed empty (→ validation fails).
2. The absolute path was rebuilt from a `data-dir` attribute stored on the file
   `<select>`. Any re-render of the editor (e.g. adding a match) rebuilds that
   select and drops `data-dir`, so a later selection could resolve to a wrong or
   relative path.

## Fix (designer only)
- `diffWfChosen` now bakes the **absolute path** straight into each file option's
  `value` (`dir + path`), so selection no longer depends on `data-dir` surviving.
- `diffFileChosen` guards only on the select, reads the (already absolute) value,
  and calls `setNodeParam` **unconditionally** (updating the input only if it is
  present). So picking a file always sets `fileA`/`fileB`.

Picking from a workflow is meant to fill the File A/B box with the resolved
absolute path — that is by design (the box shows exactly what will be compared);
the user does not type anything.

## Verify
The real `diffFileChosen` / `setNodeParam` / `nodeParam` were executed with a mock
DOM, including the worst case where the File input element is absent: the param is
still set and validation passes. designer.html passes node --check; no literal
\n/\r; no unsafe Thymeleaf.
