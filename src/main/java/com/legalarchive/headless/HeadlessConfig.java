package com.legalarchive.headless;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;

import com.legalarchive.orchestrator.OrchestratorApplication;
import com.legalarchive.orchestrator.config.AppProperties;

/**
 * The Spring configuration of the headless instance (spec {@code .claude/HEADLESS.md} section 4.2).
 *
 * <p>The same beans as {@link OrchestratorApplication} - engine, registry, cron, stores, the control
 * channel - minus everything that serves HTTP:
 * <ul>
 *   <li>every {@code @Controller} (and so every {@code @RestController}) and {@code @ControllerAdvice},
 *       by annotation, so a controller added later is left out without anyone editing this file;</li>
 *   <li>the whole {@code web} package, whose classes all serve HTTP;</li>
 *   <li>{@link OrchestratorApplication} itself, whose own component scan would bring the controllers back.</li>
 * </ul>
 * The servlet API is not in the artifact at all, so a class that slipped through would fail the
 * start instead of serving a page.
 *
 * <p>This class lives OUTSIDE {@code com.legalarchive.orchestrator} on purpose: the component scan
 * of the GUI artifacts never sees it.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
@ComponentScan(basePackages = "com.legalarchive.orchestrator", excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = { Controller.class, ControllerAdvice.class }),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.legalarchive\\.orchestrator\\.web\\..*"),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = OrchestratorApplication.class)
})
public class HeadlessConfig {
}
