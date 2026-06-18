# 2026-06-18 — safecopy multi-pattern + diagnose save HTTP 400 (proxy/WAF)

## safecopy multiple patterns
runSafeCopy now accepts several wildcard patterns separated by comma/semicolon
(e.g. "*.md5, *.tar"): it was previously passed as a single glob to DirectoryStream, so only
one pattern effectively matched. Now each pattern is streamed and results are de-duplicated by
name (LinkedHashSet). Designer placeholder/hint + USAGE updated. Tested (*.md5,*.tar matches
a.md5,b.tar,d.tar; skips .txt and .on_fly_ temps). 76 classes compile.

## Save "HTTP 400 text/html Bad Request" (user report)
Not an OpenProteo validation error. save() POSTs JSON.stringify(wf) to api/workflows/save and
readJson() flags any non-JSON response. A 400 with a terse text/html "Bad Request" body is the
corporate proxy/WAF rejecting the POST body (or an expired SSO session) before/around the app.
Guidance given to the user: re-login then retry; isolate by saving with the safecopy step
removed (then toggling individual fields, e.g. simplifying the wildcard) to find the token the
filter blocks; capture the blocked-request id from the Network tab for UBS security; the SQL
step's query and the Azure blob URLs in variables are other common WAF triggers. No code change
can guarantee a fix for a WAF block; if confirmed, mitigations are whitelisting the save URL or
changing how the payload is transmitted.
