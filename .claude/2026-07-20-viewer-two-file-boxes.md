# Standalone viewer: two distinct file-browse boxes

The load controls moved out of the (cramped) top bar into a dedicated panel with two
separate, labelled boxes:
  1 · CSV file (required) — Browse... + selected filename
  2 · displayschema.json (optional) — Browse... + selected filename
Each box has its own Browse button (a <label> wrapping a hidden <input type=file>) and
shows the chosen filename (green once set). Drag & drop still works and updates the
matching filename. csv-viewer.html only (delivered raw in the zip; the deploy script
extracts it into the repo root). JS uses String.fromCharCode (no literal \n/\r).
