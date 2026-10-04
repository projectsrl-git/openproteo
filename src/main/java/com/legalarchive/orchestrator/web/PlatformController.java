package com.legalarchive.orchestrator.web;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.legalarchive.orchestrator.config.AppProperties;
import com.legalarchive.orchestrator.platform.PlatformProbe;
import com.legalarchive.orchestrator.platform.PlatformProbe.PathSpec;
import com.legalarchive.orchestrator.store.GlobalVarsStore;

/**
 * Platform diagnostics (spec {@code .claude/LINUX_AND_GUI_CONFIG.md} section 7). Read-only: it
 * shows what the instance thinks its host is and configures nothing.
 *
 * <p>This class only chooses WHICH configured values are described; every decision about how is
 * in {@link PlatformProbe}, which has no Spring in it and is tested against a real file system.
 *
 * <p><b>Public until authentication exists.</b> So only the paths and interpreters listed here
 * are passed on - never the properties object, never a secret, never the content of a
 * configuration file. Always answers 200: a part that cannot be computed carries its own error.
 */
@RestController
public class PlatformController {

    private final AppProperties props;
    private final GlobalVarsStore globals;
    private final Environment environment;
    /** One instance: it holds the case-probe cache and its once-a-minute floor. */
    private final PlatformProbe probe = new PlatformProbe();

    public PlatformController(AppProperties props, GlobalVarsStore globals, Environment environment) {
        this.props = props;
        this.globals = globals;
        this.environment = environment;
    }

    @GetMapping("/api/platform")
    public ResponseEntity<Map<String, Object>> platform() {
        return ResponseEntity.ok(report(false));
    }

    /**
     * Runs the case-sensitivity probe again. A POST because it writes a file; the probe itself
     * refuses to run more than once a minute and then answers from its cache, saying so.
     */
    @PostMapping("/api/platform/case-probe")
    public ResponseEntity<Map<String, Object>> caseProbe() {
        return ResponseEntity.ok(report(true));
    }

    private Map<String, Object> report(boolean refreshProbe) {
        try {
            List<PathSpec> paths = new ArrayList<PathSpec>();
            paths.add(new PathSpec("workflowsDir", props.getWorkflowsDir(), true));
            paths.add(new PathSpec("scriptsDir", props.getScriptsDir(), true));
            paths.add(new PathSpec("sharedDir", props.getSharedDir(), true));
            paths.add(new PathSpec("defaultBaseDir", props.getDefaultBaseDir(), true));
            paths.add(new PathSpec("datasourcesFile", props.getDatasourcesFile(), false));
            paths.add(new PathSpec("ftpTargetsFile", props.getFtpTargetsFile(), false));
            paths.add(new PathSpec("maskPoolsDir", props.getMaskPoolsDir(), true));
            paths.add(new PathSpec("globalVarsFile", globalVarsFile(), false));
            paths.add(new PathSpec("logDir", logDir(), true));

            Map<String, String> interpreters = new LinkedHashMap<String, String>();
            interpreters.put("powershell", props.getPowershellExe());
            interpreters.put("cmd", props.getCmdExe());
            interpreters.put("java", props.getJavaExe());

            return probe.report(paths, interpreters, props.getDefaultBaseDir(), refreshProbe);
        } catch (RuntimeException e) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("ok", Boolean.FALSE);
            out.put("error", e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            return out;
        }
    }

    /** The EFFECTIVE file, as the store itself resolves it - not a second copy of its rule. */
    private String globalVarsFile() {
        File f = globals.file();
        return f == null ? "" : f.getPath();
    }

    /** Directory of the application log: {@code logging.file.name}'s parent, else {@code logging.file.path}. */
    private String logDir() {
        String name = environment.getProperty("logging.file.name");
        if (name != null && !name.trim().isEmpty()) {
            File parent = new File(name.trim()).getParentFile();
            return parent == null ? "." : parent.getPath();
        }
        String path = environment.getProperty("logging.file.path");
        return path == null ? "" : path.trim();
    }
}
