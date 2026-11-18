package com.legalarchive.orchestrator.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * The Host/Origin guard of the loopback GUI (spec {@code .claude/HEADLESS.md} section 4.4, Q11).
 *
 * <p><b>Registered only when {@code server.address} is a loopback address.</b> Without that setting -
 * every installation before H2 - there is no bean and no filter, so the default does not change.
 *
 * <p>It judges only requests that arrived on a loopback address. Under an external Tomcat the
 * address setting has no effect on the connector, so remote users there are never touched; a
 * request from the server's own browser is still guarded.
 *
 * <p>The refusal is a plain 403 with a fixed line of text, written with {@code setStatus}: no error
 * page is rendered and no header value is echoed; the reason goes to the application log. The rules
 * are in {@link LoopbackRules}.
 */
@Configuration
public class LoopbackGuard {

    private static final Logger log = LoggerFactory.getLogger(LoopbackGuard.class);

    @Bean
    @ConditionalOnProperty(name = "server.address")
    public FilterRegistrationBean<Filter> loopbackGuardFilter(Environment env) {
        String address = env.getProperty("server.address");
        boolean on = LoopbackRules.isLoopbackAddress(address);
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<Filter>(new GuardFilter());
        reg.setName("loopbackGuard");
        reg.addUrlPatterns("/*");
        reg.setDispatcherTypes(DispatcherType.REQUEST);
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        reg.setEnabled(on);
        if (on) log.info("loopback GUI: bound to {}, Host and Origin checked on every request", address);
        return reg;
    }

    static final class GuardFilter implements Filter {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest req = (HttpServletRequest) request;
            if (LoopbackRules.isLoopbackAddress(req.getLocalAddr())) {
                String why = LoopbackRules.refuse(req.getMethod(), req.getHeader("Host"),
                        req.getHeader("Origin"), req.getHeader("Referer"));
                if (why != null) {
                    log.warn("loopback GUI: refused {} {} - {}", req.getMethod(), req.getRequestURI(), why);
                    HttpServletResponse res = (HttpServletResponse) response;
                    res.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    res.setContentType("text/plain;charset=UTF-8");
                    // a fixed text: the header values the reason quotes are the caller's, never echoed back
                    res.getOutputStream().write("Refused by the loopback guard of OpenProteo: see the application log."
                            .getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            chain.doFilter(request, response);
        }
    }
}
