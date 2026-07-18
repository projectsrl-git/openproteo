# Light-theme polish

The dark theme was well tuned; the light theme needed contrast work, chiefly the
field placeholders reading almost like real values.

## Cause
Only `.field input/.field textarea` had a light-theme placeholder colour; every
other input (inline-input, search-box, ms-input, generic inputs) fell back to the
browser default placeholder, which is roughly the text colour at reduced opacity —
i.e. a dark grey that looks like a typed value. And `--ink-faint` (#7a8694) was a
touch dark for a hint.

## Fix (app.css, appended, light theme only)
- A global light-theme `::placeholder` for all inputs/textareas: lighter
  (#98a4b3), full opacity, italic — so hints clearly read as hints.
- Any input/select/textarea that still carried the dark base now reads light
  (white bg, --ink text, --line border).
- Real values sit at font-weight 500 so they stand apart from the italic hints.
- A clearer focus ring (accent border + soft amber glow).
- Dim/secondary text (.dim, kv labels, footer, node ids) nudged to keep contrast
  on the light background.

Dark theme is untouched (all rules are scoped to :root[data-theme="light"]).

## Verify
CSS braces balanced; all additions scoped to the light theme. Frontend only —
Ctrl+F5 to see it. (CSS can't be node-checked.)
