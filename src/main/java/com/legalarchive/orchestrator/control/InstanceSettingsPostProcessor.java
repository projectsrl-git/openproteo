package com.legalarchive.orchestrator.control;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

/**
 * Puts the GUI-saved instance settings above {@code application.properties} (and below the command
 * line, system properties and environment), before the context is created (spec {@code .claude/HEADLESS.md} section 4.4). Registered in
 * {@code META-INF/spring.factories}; runs in every artifact.
 *
 * <p><b>Without the file it adds nothing</b>, so an installation that never saved a setting starts
 * exactly as before. A malformed file is ignored here, and the reason is kept in
 * {@link #lastOutcome()} for the control service to log once logging exists (this runs before
 * the logging system is initialised).
 *
 * <p>H1 maps {@code controlDir} only. {@code serverAddress} is H2's.
 */
public class InstanceSettingsPostProcessor implements EnvironmentPostProcessor {

    public static final String SOURCE_NAME = "openproteo-instance";

    private static volatile String outcome = "";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        Path file = InstanceSettings.file(env.getProperty("orchestrator.workflows-dir"));
        try {
            Map<String, Object> s = InstanceSettings.read(file);
            if (s.isEmpty()) { outcome = ""; return; }
            Map<String, Object> props = new LinkedHashMap<String, Object>();
            String controlDir = InstanceSettings.string(s, InstanceSettings.CONTROL_DIR);
            if (controlDir != null) props.put("orchestrator.control-dir", controlDir);
            if (props.isEmpty()) { outcome = "read " + file + ": no setting this build applies"; return; }
            place(env.getPropertySources(), new MapPropertySource(SOURCE_NAME, props));
            outcome = "read " + file + ": " + props.keySet();
        } catch (Exception e) {
            outcome = "IGNORED " + file + ": " + e.getMessage();
        }
    }

    /**
     * Just above the first configuration file ({@code application.properties}, bundled or
     * external): the precedence the contract gives a GUI-saved value. The command line, Java
     * system properties and environment variables stay above it, so whoever installs the service
     * keeps the last word. Without any configuration file the source goes last.
     */
    static void place(MutablePropertySources sources, MapPropertySource ours) {
        for (PropertySource<?> ps : sources) {
            String n = ps.getName();
            if (n.contains("Config resource") || n.startsWith("applicationConfig")) {
                sources.addBefore(n, ours);
                return;
            }
        }
        sources.addLast(ours);
    }

    /** What the last run of this processor did, "" when there was no file. */
    public static String lastOutcome() {
        return outcome;
    }
}
