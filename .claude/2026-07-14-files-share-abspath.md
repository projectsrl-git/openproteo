# Files panel: Share direct link + Copy absolute path

## Problem / goal
In the file manager (filespanel.js, used on the workflow page, shared files and
pools) each file needs:
- a **Share** button that yields a direct link to the file (to paste in mail/
  chat);
- **Copy path** must copy the **absolute** path (not the scope-relative one) so a
  step in one feed can reference a file living under another feed.

## Approach (frontend only: static/js/filespanel.js)
- `doList` already returns `dir` = the absolute base of the scope (feed dir or
  shared dir). The panel now stores it as `scopeDir` on load; no backend change.
- `normAbs(rel)` = `scopeDir` (backslashes normalised to `/`) + `/` + relative
  path → the absolute filesystem path. **Copy path** now copies that (tooltip
  updated: "reference this file from any feed step, across feeds"). The
  scope-relative path is still visible in the File column for same-feed use.
- New **Share** button: `absUrl(dl)` resolves the (context-relative) download URL
  to an absolute URL via `new URL(dl, location.href)`; the button copies it. Both
  Share and Copy path reuse the existing `copyPath()` clipboard helper (async +
  textarea fallback, "Copied!" flash). Actions column widened 260→330px.

## Backward compatibility
Pure UI in the shared component, so it applies everywhere the panel is mounted.
No endpoint/model change; Download/View/Delete unchanged.

## Verify
filespanel.js passes node --check; zero literal \n/\r. (Static .js, not a
Thymeleaf template, so `[[` is irrelevant there.)
