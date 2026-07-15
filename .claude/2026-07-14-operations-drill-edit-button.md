# Operations drill: per-feed EDIT button

## Problem / goal
When a feed fails, the Operations detail let you open the workflow page or the
last run, but not jump straight into editing. Add an EDIT action per row so you
can go directly to the designer to fix the feed.

## Approach (frontend only: templates/overview.html)
In the drill row actions cell, next to "open workflow" / "open last run", add a
"✎ edit" link to `GET /designer/{feedId}` (the existing designer-edit route,
same target as the workflow page's "Edit in designer" and the dashboard "Edit").

## Backward compatibility
Pure UI, one anchor added. No endpoint/model change.

## Verify
overview.html passes node --check; no literal \n/\r; no unsafe Thymeleaf inline.
