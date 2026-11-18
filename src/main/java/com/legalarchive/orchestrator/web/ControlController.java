package com.legalarchive.orchestrator.web;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
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
    private final ApplicationContext context;

    public ControlController(ControlService control, ApplicationContext context) {
        this.control = control;
        this.context = context;
    }

    @GetMapping("/api/control")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.putAll(control.status());
        out.put("embeddedServer", embeddedServer());
        return ResponseEntity.ok(out);
    }

    /**
     * Saves the keys present in the body: {@code controlDir}, {@code serverAddress}, or both, in one
     * write. A key that is absent is left as it is.
     */
    @PostMapping("/api/control/settings")
    public ResponseEntity<Map<String, Object>> save(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Map<String, String> values = new LinkedHashMap<String, String>();
        try {
            if (body != null && body.containsKey("controlDir")) {
                String dir = text(body.get("controlDir"));
                if (!dir.isEmpty() && !Paths.get(dir).isAbsolute()) {
                    return refuse(out, "The control directory must be an absolute path: the command line and the instance "
                            + "run from different working directories, and must find the same folder.");
                }
                values.put(InstanceSettings.CONTROL_DIR, dir);
            }
            if (body != null && body.containsKey("serverAddress")) {
                String address = text(body.get("serverAddress"));
                String why = addressProblem(address);
                if (why != null) return refuse(out, why);
                values.put(InstanceSettings.SERVER_ADDRESS, address);
            }
            if (values.isEmpty()) return refuse(out, "Nothing to save.");
            String who = user(req);
            InstanceSettings.write(control.settingsFile(), values, who);
            log.info("instance settings: {} saved by {}, applies at the next start", values, who);
            out.put("ok", true);
            out.putAll(control.status());
            out.put("embeddedServer", embeddedServer());
        } catch (Exception e) {
            return refuse(out, "The setting could not be saved: " + e.getMessage());
        }
        return ResponseEntity.ok(out);
    }

    /**
     * Null when the address can be bound on this server: empty (all interfaces), or an address of
     * one of its own interfaces. Anything else would leave the GUI unable to start at the next
     * start, so it is refused here rather than discovered then.
     */
    static String addressProblem(String address) {
        if (address.isEmpty()) return null;
        try {
            InetAddress a = InetAddress.getByName(address);
            if (a.isLoopbackAddress() || a.isAnyLocalAddress() || NetworkInterface.getByInetAddress(a) != null) return null;
            return "'" + address + "' is not an address of this server: the GUI could not start on it.";
        } catch (Exception e) {
            return "'" + address + "' is not an address this server can resolve: " + e.getMessage();
        }
    }

    /** True when this instance runs its own Tomcat (standalone); false under an external Tomcat. */
    private boolean embeddedServer() {
        return context instanceof WebServerApplicationContext
                && ((WebServerApplicationContext) context).getWebServer() != null;
    }

    private static ResponseEntity<Map<String, Object>> refuse(Map<String, Object> out, String error) {
        out.put("ok", false);
        out.put("error", error);
        return ResponseEntity.ok(out);
    }

    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    /** As {@code ApiController.user}: the identity the GUI has always recorded. */
    private static String user(HttpServletRequest req) {
        String u = req.getHeader("X-User");
        if (u == null || u.trim().isEmpty()) u = req.getRemoteUser();
        if (u == null || u.trim().isEmpty()) u = "operator@" + req.getRemoteAddr();
        return u;
    }
}
