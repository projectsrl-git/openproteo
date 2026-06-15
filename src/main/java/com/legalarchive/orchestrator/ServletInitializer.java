package com.legalarchive.orchestrator;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * Entry point used when the application is deployed as a WAR on an external
 * servlet container (Tomcat 8.5 / 9, javax.* namespace).
 *
 * When run standalone with "java -jar openproteo.war" this class is not used:
 * OrchestratorApplication.main() starts the embedded Tomcat from
 * WEB-INF/lib-provided instead.
 */
public class ServletInitializer extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(OrchestratorApplication.class);
    }
}
