package com.legalarchive.orchestrator.diag;

import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only diagnostic endpoint to determine HOW the personal browser certificate
 * identity actually reaches OpenProteo in the UBS deployment topology.
 *
 * It is intentionally topology-agnostic: it dumps BOTH
 *   (1) the TLS client cert chain, present only if TLS is terminated at Tomcat with clientAuth, and
 *   (2) all request headers, where an upstream tier (F5 / Apache / proxy) would inject the
 *       validated identity (SSL_CLIENT_S_DN, SSL_CLIENT_VERIFY, custom UBS headers, etc.).
 *
 * No new dependencies (pure JDK + Servlet API). No JavaScript (avoids the UBS proxy
 * escape-sequence issue entirely). Remove or lock down after diagnosis.
 *
 * GET /diag/whoami
 */
@RestController
public class CertIdentityProbeController {

    @GetMapping(value = "/diag/whoami", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String whoami(HttpServletRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset='utf-8'>");
        sb.append("<title>OpenProteo - Certificate Identity Probe</title>");
        sb.append("<style>")
          .append("body{font-family:Calibri,Arial,sans-serif;margin:24px;color:#222}")
          .append("h2{color:#A8123A}h3{margin-top:28px}")
          .append("table{border-collapse:collapse;width:100%;margin-bottom:8px}")
          .append("th,td{border:1px solid #ccc;padding:6px 10px;text-align:left;vertical-align:top;font-size:13px}")
          .append("th{background:#f4f4f4}code{word-break:break-all}.none{color:#999}")
          .append("</style></head><body>");
        sb.append("<h2>OpenProteo &mdash; Certificate Identity Probe</h2>");

        // ---- 1. TLS terminated at Tomcat with clientAuth -> X509 attribute is present ----
        sb.append("<h3>1. TLS client certificate (javax.servlet.request.X509Certificate)</h3>");
        Object certObj = request.getAttribute("javax.servlet.request.X509Certificate");
        if (certObj instanceof X509Certificate[]) {
            X509Certificate[] chain = (X509Certificate[]) certObj;
            sb.append("<table><tr><th>#</th><th>Subject DN</th><th>Issuer DN</th><th>SAN entries</th><th>Serial</th></tr>");
            for (int i = 0; i < chain.length; i++) {
                X509Certificate c = chain[i];
                sb.append("<tr><td>").append(i).append("</td>");
                sb.append("<td><code>").append(esc(c.getSubjectX500Principal().getName())).append("</code></td>");
                sb.append("<td><code>").append(esc(c.getIssuerX500Principal().getName())).append("</code></td>");
                sb.append("<td><code>");
                try {
                    Collection<List<?>> sans = c.getSubjectAlternativeNames();
                    if (sans != null && !sans.isEmpty()) {
                        for (List<?> san : sans) {
                            sb.append(esc(String.valueOf(san))).append("<br>");
                        }
                    } else {
                        sb.append("<span class='none'>none</span>");
                    }
                } catch (Exception e) {
                    sb.append("<span class='none'>error: ").append(esc(e.getMessage())).append("</span>");
                }
                sb.append("</code></td>");
                sb.append("<td><code>").append(esc(String.valueOf(c.getSerialNumber()))).append("</code></td></tr>");
            }
            sb.append("</table>");
        } else {
            sb.append("<p class='none'>No X509Certificate attribute present. TLS is most likely NOT terminated at Tomcat "
                    + "with clientAuth &mdash; the cert identity would instead arrive via headers injected by an upstream "
                    + "tier (see section 2).</p>");
        }

        // ---- 2. TLS terminated upstream -> identity injected as headers ----
        sb.append("<h3>2. Request headers (look for SSL_CLIENT_* / SSL_CLIENT_VERIFY / UBS-specific identity headers)</h3>");
        sb.append("<table><tr><th>Header</th><th>Value</th></tr>");
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames != null && headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            sb.append("<tr><td>").append(esc(name)).append("</td><td><code>")
              .append(esc(request.getHeader(name))).append("</code></td></tr>");
        }
        sb.append("</table>");

        // ---- 3. Connection facts (needed to reason about the trust boundary) ----
        sb.append("<h3>3. Connection facts</h3>");
        sb.append("<table>");
        row(sb, "request.isSecure()", String.valueOf(request.isSecure()));
        row(sb, "request.getScheme()", request.getScheme());
        row(sb, "request.getRemoteAddr() (who connected to Tomcat: proxy IP or end user?)", request.getRemoteAddr());
        row(sb, "request.getServerName()", request.getServerName());
        row(sb, "request.getRequestURL()", String.valueOf(request.getRequestURL()));
        row(sb, "cipher (javax.servlet.request.cipher_suite)",
                String.valueOf(request.getAttribute("javax.servlet.request.cipher_suite")));
        sb.append("</table>");

        sb.append("</body></html>");
        return sb.toString();
    }

    private static void row(StringBuilder sb, String k, String v) {
        sb.append("<tr><td>").append(esc(k)).append("</td><td><code>").append(esc(v)).append("</code></td></tr>");
    }

    private static String esc(String s) {
        if (s == null) {
            return "<span class='none'>null</span>";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
