package com.legalarchive.orchestrator.control;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import com.legalarchive.orchestrator.config.AppProperties;
import com.legalarchive.orchestrator.config.BuildInfo;

/**
 * Opens the control channel when {@code orchestrator.control-dir} is set (spec
 * {@code .claude/HEADLESS.md} section 4.4). With the key empty - the default - it opens nothing,
 * starts no thread and touches no file: the instance behaves as it did before H1.
 *
 * <p>The channel opens once the application is ready, so a request never reaches a half-built
 * engine; it closes before the engine does.
 */
@Component
public class ControlService {

    private static final Logger log = LoggerFactory.getLogger(ControlService.class);

    /** The mode written in the heartbeat. */
    public static final String MODE_GUI = "gui";
    public static final String MODE_HEADLESS = "headless";
    /** Set by {@code HeadlessApplication} before the context starts. */
    public static final String HEADLESS_PROPERTY = "openproteo.headless";

    public static String mode() {
        return Boolean.getBoolean(HEADLESS_PROPERTY) ? MODE_HEADLESS : MODE_GUI;
    }

    private final AppProperties props;
    private final ControlBackend backend;
    private final ConfigurableEnvironment env;
    private volatile ControlChannel channel;

    public ControlService(AppProperties props, EngineBackend backend, ConfigurableEnvironment env) {
        this.props = props;
        this.backend = backend;
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void open() {
        String outcome = InstanceSettingsPostProcessor.lastOutcome();
        if (outcome.startsWith("IGNORED")) log.error("instance settings {}", outcome);
        else if (!outcome.isEmpty()) log.info("instance settings {}", outcome);
        String dir = configured();
        if (dir.isEmpty()) return;
        ControlChannel c = new ControlChannel(Paths.get(dir), backend, mode(), BuildInfo.id());
        c.start();
        channel = c;
    }

    /** True when the channel is open and serving. The headless instance refuses to run without it. */
    public boolean serving() {
        ControlChannel c = channel;
        return c != null && c.enabled();
    }

    /** Why the channel is not serving, for the headless start-up message. */
    public String reason() {
        ControlChannel c = channel;
        if (configured().isEmpty()) return "no control directory is set";
        return c == null ? "the channel did not open" : c.reason();
    }

    @PreDestroy
    public void close() {
        ControlChannel c = channel;
        if (c != null) c.stop();
    }

    private String configured() {
        String d = props.getControlDir();
        return d == null ? "" : d.trim();
    }

    /** For the Platform page: what is in effect now, where it comes from, and what the next start will use. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        String dir = configured();
        m.put("controlDir", dir);
        m.put("source", source(dir));
        ControlChannel c = channel;
        if (dir.isEmpty()) {
            m.put("state", "OFF");
            m.put("reason", "no control directory is set: the command line is not available");
        } else if (c == null) {
            m.put("state", "OFF");
            m.put("reason", "the channel opens when the application has finished starting");
        } else {
            m.put("state", c.enabled() ? "SERVING" : "OFF");
            m.put("reason", c.reason());
        }
        Path file = settingsFile();
        m.put("settingsFile", file.toString());
        try {
            Map<String, Object> settings = InstanceSettings.read(file);
            String saved = InstanceSettings.string(settings, InstanceSettings.CONTROL_DIR);
            m.put("savedControlDir", saved);
            String winner = winningSource();
            boolean overridden = saved != null && winner != null && !InstanceSettingsPostProcessor.SOURCE_NAME.equals(winner)
                    && !winner.contains("Config resource") && !winner.startsWith("applicationConfig");
            if (overridden) m.put("overriddenBy", source(dir));
            m.put("restartNeeded", saved != null && !overridden && !saved.equals(dir));
        } catch (Exception e) {
            m.put("settingsError", e.getMessage());
        }
        return m;
    }

    public Path settingsFile() {
        return InstanceSettings.file(props.getWorkflowsDir());
    }

    /** The name of the property source the effective value comes from: the first one that has the key. */
    private String winningSource() {
        for (PropertySource<?> ps : env.getPropertySources()) {
            // Boot's "configurationProperties" is a view over all the others, attached first: not a source
            if ("configurationProperties".equals(ps.getName())) continue;
            if (ps.containsProperty("orchestrator.control-dir")) return ps.getName();
        }
        return null;
    }

    private String source(String dir) {
        if (dir.isEmpty()) return "none";
        String n = winningSource();
        if (n == null) return "none";
        if (InstanceSettingsPostProcessor.SOURCE_NAME.equals(n)) return "instance settings file";
        if ("commandLineArgs".equals(n)) return "command line";
        if ("systemProperties".equals(n)) return "Java system property";
        if ("systemEnvironment".equals(n)) return "environment variable";
        return "application.properties";
    }
}
