# Cert Identity Probe — Integration Handoff

> Place this file under `.claude/` in the OpenProteo repo.
> Companion artifact: `CertIdentityProbeController.java`.

## Purpose

Temporary, **read-only** diagnostic endpoint to determine *how* the UBS personal
browser certificate identity actually reaches OpenProteo in the production topology.
The output drives the design of the real authentication filter. Remove or lock down
after diagnosis.

## What it does

Exposes a single endpoint that renders, on one HTML page, three sections:

1. **TLS client certificate** — the `javax.servlet.request.X509Certificate` chain.
   Populated **only** if TLS is terminated at Tomcat with `clientAuth`.
2. **Request headers** — every header, so an upstream tier (F5 / Apache / proxy)
   that validates the cert and injects identity (`SSL_CLIENT_S_DN`,
   `SSL_CLIENT_VERIFY`, or UBS-specific header names) is visible.
3. **Connection facts** — `isSecure`, scheme, `remoteAddr`, server name, request URL,
   cipher suite. `remoteAddr` tells us whether Tomcat is reached by the proxy or by
   the end user directly (basis for the trust-boundary decision).

## Dependencies

**None new.** Pure JDK + Servlet API + `spring-web` (already on the classpath).
No Thymeleaf is used — the controller returns HTML directly via `@RestController`,
so no view resolver is involved. **No JavaScript is included, intentionally**, to
avoid the UBS proxy escape-sequence corruption. Do **not** add JS or rewrite this as
a JS-bearing template.

No `pom.xml` change is required.

## Integration steps

1. **Confirm the application base package** — the package containing the
   `@SpringBootApplication` (and the `SpringBootServletInitializer` subclass used for
   WAR deployment). Call it `<base>`.
2. **Place the file** at `<base>/diag/CertIdentityProbeController.java` and set its
   `package` declaration to `<base>.diag`. It **must** live under the component-scan
   root (`<base>` or a subpackage) so it is auto-detected. If `<base>` is
   `com.ubs.openproteo`, the file is already correct as written.
3. **No build changes** — build the WAR as usual (Maven), then deploy to the existing
   Tomcat 8.5 instance, replacing the running WAR.
4. **If a Spring Security filter chain exists** (currently OpenProteo has none),
   permit `/diag/**` so the endpoint is reachable; otherwise skip.

## URI to get the data

- **Mapping:** `GET /diag/whoami`
- **Full URL:** `https://<host>[:<port>]/<context-path>/diag/whoami`
- **Context path:** the WAR deploys under a context path. Check
  `server.servlet.context-path` in the external `application.properties`
  (`CATALINA_HOME/config/`) or the deployed WAR name.
  Example: `https://<host>/openproteo/diag/whoami`.
- **Critical:** open it in the browser that holds the personal certificate, and
  through the **same HTTPS entry point users use for SSO** (i.e. via the proxy / the
  cert-protected vhost). Hitting a plain-HTTP or internal connector that bypasses the
  proxy will leave sections 1 and 2 empty and tell us nothing.

## Interpreting the output

- **Section 1 populated** → TLS terminates at Tomcat (clientAuth). The real filter
  reads `javax.servlet.request.X509Certificate`. Record the **Subject DN format**.
- **Section 1 empty, identity visible in Section 2 headers** → TLS terminates
  upstream. The real filter reads headers. Record the **exact header names**, the
  value of **`SSL_CLIENT_VERIFY`** (must be `SUCCESS`), and the **Subject DN format**.
- **Section 3 `remoteAddr`** → if it is the proxy IP rather than the end-user IP, this
  confirms upstream termination and is the basis for locking the trust boundary (only
  the proxy may reach Tomcat; the proxy must strip inbound copies of the identity
  headers).

## What to report back

Structure only — redact sensitive values. Specifically:

- Which section was populated (1 = Tomcat clientAuth, 2 = upstream headers).
- The **exact header names** carrying identity (if section 2).
- The **Subject DN format**: field order, and **which field carries the user id**
  (CN / email / SAN rfc822 / UPN).
- The **`SSL_CLIENT_VERIFY`** value (if present).

This is enough to write the identity-extraction `Filter` plus the trust-boundary
protection, incrementally on the stable base.

## Cleanup

Remove the controller (or restrict it to a known operator) once the identity source is
confirmed — it exposes all request headers and should not remain in a deployed build.
