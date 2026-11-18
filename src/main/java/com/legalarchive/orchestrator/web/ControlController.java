package com.legalarchive.orchestrator.web;

import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.legalarchive.orchestrator.control.ControlService;
import com.legalarchive.orchestrator.control.InstanceSettings;

/**
 * The "Command-line control" block of the Platform page (spec {@code .claude/HEADLESS.md} section
 * 4.4): the state of the control channel, and the control directory the next start will use.
 *
 * <p>A saved value applies at the next start, never now: the channel is the authorisation boundary
 * of the CLI, and moving it under a running instance would leave requests in a folder nobody reads.
 * Always answers 200; an outcome the user has to act on travels in {@code ok}/{@code error}.
 */
@RestController
public class ControlController {

    private static final Logger log = LoggerFactory.getLogger(ControlController.class);

    private final ControlService control;

    public ControlController(ControlService control) {
        this.control = control;
    }

    @GetMapping("/api/control")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.putAll(control.status());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/api/control/settings")
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Object raw = body == null ? null : body.get("controlDir");
        String dir = raw == null ? "" : String.valueOf(raw).trim();
        try {
            if (!dir.isEmpty() && !Paths.get(dir).isAbsolute()) {
                out.put("ok", false);
                out.put("error", "The control directory must be an absolute path: the command line and the instance "
                        + "run from different working directories, and must find the same folder.");
                return ResponseEntity.ok(out);
            }
            String who = user(req);
            InstanceSettings.write(control.settingsFile(), InstanceSettings.CONTROL_DIR, dir, who);
            log.info("instance settings: control directory set to '{}' by {}, applies at the next start", dir, who);
            out.put("ok", true);
            out.putAll(control.status());
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", "The setting could not be saved: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /** As {@code ApiController.user}: the identity the GUI has always recorded. */
    private static String user(HttpServletRequest req) {
        String u = req.getHeader("X-User");
        if (u == null || u.trim().isEmpty()) u = req.getRemoteUser();
        if (u == null || u.trim().isEmpty()) u = "operator@" + req.getRemoteAddr();
        return u;
    }
}
