package com.legalarchive.orchestrator.engine;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs a step's executable via ProcessBuilder. Supported runners:
 *
 *   POWERSHELL (.ps1) - the script content is read as DATA and executed with
 *       [ScriptBlock]::Create, so the execution policy never requires a signature
 *       (works under GPO-enforced AllSigned) and there is no command-line length
 *       limit (large scripts are fine). Parameters are passed as named -Name 'Value'.
 *
 *   CMD (.bat/.cmd) - executed with cmd.exe /c. Parameters are passed as positional
 *       arguments in order (the param names are just labels for readability).
 *
 *   JAR (.jar) - executed with: java -Dfile.encoding=UTF-8 -jar <jar> <args...>.
 *       Parameters are passed as positional arguments in order.
 *
 *   BASH (.sh) - executed through the interpreter, never as a file, so a scripts directory
 *       on a noexec mount or a script without the x bit still runs. Parameters are passed
 *       as NAMED environment variables, OP_<name>. They travel inside an all-ASCII
 *       bootstrap, as PowerShell's travel as base64: a JVM started without a UTF-8 locale
 *       turns a non-ASCII argument or environment value into '?' (measured), and a
 *       bootstrap made only of ASCII cannot be damaged that way. See buildBashBootstrap.
 *
 * Common behaviour for all runners: stdout+stderr is captured line by line,
 * timestamped into the step log; lines "##VAR name=value" become output variables;
 * the exit code is propagated; timeout and operator-abort kill the process.
 */
public class StepExecutor {

    public enum Kind { POWERSHELL, CMD, JAR, BASH }

    /**
     * A step refused BEFORE anything is started: the message is the whole explanation. It is
     * written to the step log and then thrown, so the run page and the audit both carry it.
     */
    public static class StepRefused extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public StepRefused(String message) { super(message); }
    }

    public static class Result {
        public int exitCode = -1;
        public boolean timedOut = false;
        public Map<String, String> outVars = new LinkedHashMap<String, String>();
        public String lastLines = "";
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String VAR_MARKER = "##VAR ";
    private static final String LF = String.valueOf((char) 10);

    /** Linux caps ONE argument at 128 KiB; the bash bootstrap is one argument. Refuse below it. */
    static final int BASH_BOOTSTRAP_MAX = 120 * 1024;
    /** A script larger than this is not read for the line-ending check; it just runs. */
    static final long CRLF_CHECK_MAX = 16L * 1024 * 1024;

    private final String powershellExe;
    private final String javaExe;
    private final String cmdExe;
    private final String bashExe;
    private final ProcessTree tree;

    public StepExecutor(String powershellExe, String javaExe, String cmdExe) {
        this(powershellExe, javaExe, cmdExe, "/bin/bash", ProcessTree.host());
    }

    public StepExecutor(String powershellExe, String javaExe, String cmdExe, String bashExe) {
        this(powershellExe, javaExe, cmdExe, bashExe, ProcessTree.host());
    }

    /** For tests: a process tree built for another host. */
    public StepExecutor(String powershellExe, String javaExe, String cmdExe, String bashExe, ProcessTree tree) {
        this.powershellExe = powershellExe;
        this.javaExe = javaExe;
        this.cmdExe = cmdExe;
        this.bashExe = bashExe;
        this.tree = tree;
    }

    /** Resolve the runner from an explicit exec attribute or from the file extension. */
    public static Kind resolveKind(String exec, String scriptPath) {
        if (exec != null && !exec.trim().isEmpty() && !"auto".equalsIgnoreCase(exec)) {
            String e = exec.trim().toLowerCase();
            if (e.equals("powershell") || e.equals("ps1")) return Kind.POWERSHELL;
            if (e.equals("cmd") || e.equals("bat")) return Kind.CMD;
            if (e.equals("jar") || e.equals("java")) return Kind.JAR;
            if (e.equals("bash")) return Kind.BASH;
        }
        String p = scriptPath == null ? "" : scriptPath.toLowerCase();
        if (p.endsWith(".bat") || p.endsWith(".cmd")) return Kind.CMD;
        if (p.endsWith(".jar")) return Kind.JAR;
        if (p.endsWith(".sh")) return Kind.BASH;
        return Kind.POWERSHELL; // default / .ps1
    }

    public Result execute(Kind kind, String scriptPath, Map<String, String> params, Path logFile,
                          int timeoutSec, File workingDir, RunControl control) throws Exception {

        Result res = new Result();
        Files.createDirectories(logFile.getParent());
        List<String> command;
        try {
            command = buildCommand(kind, scriptPath, params);
        } catch (StepRefused refused) {
            // Nothing was started. Say why in the step log, where the operator looks first.
            BufferedWriter w = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            try {
                writeRaw(w, 'S', "!!! NOT STARTED - " + refused.getMessage());
            } finally {
                w.close();
            }
            throw refused;
        }

        final List<String> tail = new ArrayList<String>();

        // stderr stays separate so the live console can colour it (ProcessTree.launch keeps it so)
        final ProcessTree.Handle handle = tree.launch(command, workingDir, childEnvironment(kind));
        final Process process = handle.process;
        if (control != null) {
            control.process = process;
            control.live.add(handle);
            // Stop may have swept the set a moment before this process joined it.
            if (control.aborted) tree.kill(handle);
        }

        final BufferedReader outR = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        final BufferedReader errR = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
        final BufferedWriter log = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        final Object lock = new Object();
        // A Linux path joined with a Windows separator is a file NAME there: say so, change nothing.
        for (String w : com.legalarchive.orchestrator.platform.HostFiles.backslashWarnings(params)) writeLine(log, lock, 'S', w);

        // stdout: parse ##VAR, keep tail
        Thread pumpOut = new Thread(new Runnable() {
            public void run() {
                try {
                    String line;
                    while ((line = outR.readLine()) != null) {
                        writeLine(log, lock, 'O', line);
                        if (line.startsWith(VAR_MARKER)) {
                            String kv = line.substring(VAR_MARKER.length()).trim();
                            int eq = kv.indexOf('=');
                            if (eq > 0) synchronized (res.outVars) { res.outVars.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim()); }
                        }
                        synchronized (tail) { tail.add(line); if (tail.size() > 20) tail.remove(0); }
                    }
                } catch (Exception ignored) { }
            }
        }, "step-out-pump");
        // stderr: tagged as error stream
        Thread pumpErr = new Thread(new Runnable() {
            public void run() {
                try {
                    String line;
                    while ((line = errR.readLine()) != null) writeLine(log, lock, 'E', line);
                } catch (Exception ignored) { }
            }
        }, "step-err-pump");
        pumpOut.setDaemon(true); pumpErr.setDaemon(true);
        pumpOut.start(); pumpErr.start();

        boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
        boolean aborted = control != null && control.aborted;
        if (!finished) {
            if (!aborted) res.timedOut = true;
            tree.kill(handle);                 // the step AND what it started; ends with destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS);
        }
        pumpOut.join(5000);
        pumpErr.join(5000);
        handle.awaitKill(3000);   // a Stop in progress on another thread: let it finish and report
        synchronized (lock) {
            if (aborted) writeRaw(log, 'S', "!!! ABORTED - process stopped by operator");
            else if (res.timedOut) writeRaw(log, 'S', "!!! TIMEOUT after " + timeoutSec + "s - process killed by the orchestrator");
            // What the kill actually reached - set here on timeout, or by Stop from another thread.
            if (handle.killReport != null) writeRaw(log, 'S', "!!! process tree: " + handle.killReport);
            log.flush();
            log.close();
        }
        if (control != null) { control.live.remove(handle); control.process = null; }

        if (res.timedOut) {
            res.exitCode = -999;
        } else {
            try {
                res.exitCode = process.exitValue();
            } catch (IllegalThreadStateException e) {
                res.exitCode = -998;
            }
        }
        StringBuilder sb = new StringBuilder();
        synchronized (tail) {
            for (String t : tail) { sb.append(t); sb.append(' '); }
        }
        res.lastLines = sb.toString().trim();
        return res;
    }

    /** Log line format: STREAM \t TS \t content  (STREAM = O stdout, E stderr, S system). */
    private void writeLine(BufferedWriter log, Object lock, char stream, String content) throws java.io.IOException {
        synchronized (lock) { log.write(stream + "\t" + LocalDateTime.now().format(TS) + "\t" + content); log.newLine(); log.flush(); }
    }
    private void writeRaw(BufferedWriter log, char stream, String content) throws java.io.IOException {
        log.write(stream + "\t" + LocalDateTime.now().format(TS) + "\t" + content); log.newLine();
    }

    /**
     * Extra environment for the child. Outside Windows PowerShell colours its error output with
     * ANSI escapes even when it is not writing to a terminal; TERM=dumb is the one setting that
     * stops it (NO_COLOR, $PSStyle.OutputRendering and $ErrorView do not - measured on 7.4).
     */
    private Map<String, String> childEnvironment(Kind kind) {
        if (kind == Kind.POWERSHELL && !tree.windows()) {
            Map<String, String> env = new LinkedHashMap<String, String>();
            env.put("TERM", "dumb");
            return env;
        }
        return null;
    }

    List<String> buildCommand(Kind kind, String scriptPath, Map<String, String> paramsIn) {
        // Strip orchestrator-internal params: they configure the engine, they are not script arguments.
        Map<String, String> params = new LinkedHashMap<String, String>();
        if (paramsIn != null) {
            for (Map.Entry<String, String> e : paramsIn.entrySet()) {
                if (!isReservedParam(e.getKey())) params.put(e.getKey(), e.getValue());
            }
        }
        List<String> command = new ArrayList<String>();
        if (kind == Kind.BASH) {
            refuseCrlf(scriptPath);
            command.add(bashExe);
            command.add("-c");
            command.add(buildBashBootstrap(scriptPath, params));
        } else if (kind == Kind.CMD) {
            command.add(cmdExe);
            command.add("/c");
            command.add(scriptPath);
            for (String v : params.values()) command.add(v == null ? "" : v);
            refuseUnencodable(scriptPath, params);
        } else if (kind == Kind.JAR) {
            command.add(javaExe);
            command.add("-Dfile.encoding=UTF-8");
            command.add("-jar");
            command.add(scriptPath);
            for (String v : params.values()) command.add(v == null ? "" : v);
            refuseUnencodable(scriptPath, params);
        } else {
            // POWERSHELL: tiny bootstrap that runs the script as a runtime script block
            StringBuilder inner = new StringBuilder();
            inner.append("[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; ");
            inner.append("$ErrorActionPreference='Stop'; ");
            inner.append("$__path='").append(esc(scriptPath)).append("'; ");
            inner.append("$__code=[System.IO.File]::ReadAllText($__path,[System.Text.Encoding]::UTF8); ");
            inner.append("$__sb=[ScriptBlock]::Create($__code); ");
            inner.append("& $__sb");
            for (Map.Entry<String, String> p : params.entrySet()) {
                inner.append(" -").append(p.getKey()).append(" '").append(esc(p.getValue())).append("'");
            }
            inner.append("; exit $LASTEXITCODE");
            String encoded = Base64.getEncoder().encodeToString(inner.toString().getBytes(StandardCharsets.UTF_16LE));
            command.add(powershellExe);
            command.add("-NoProfile");
            command.add("-NonInteractive");
            command.add("-ExecutionPolicy");
            command.add("Bypass");
            if (!tree.windows()) {
                // Without it pwsh writes errors to a redirected stderr as "#< CLIXML" plus one line
                // of XML. Windows is left exactly as it was: its step logs are readable text today.
                command.add("-OutputFormat");
                command.add("Text");
            }
            command.add("-EncodedCommand");
            command.add(encoded);
        }
        return command;
    }

    // ------------------------------------------------------------------ bash

    /** The environment variable a parameter is exported as: OP_ + the name, anything outside [A-Za-z0-9_] as '_'. */
    static String bashVariable(String paramName) {
        StringBuilder sb = new StringBuilder("OP_");
        for (int i = 0; i < paramName.length(); i++) {
            char c = paramName.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
            sb.append(ok ? c : '_');
        }
        return sb.toString();
    }

    /**
     * The one argument after {@code bash -c}. ASCII only, on one line:
     * <pre>
     * [ -n "$BASH_VERSION" ] || { echo "...not bash" &gt;&amp;2; exit 126; }; export OP_a=$'...'; exec "$BASH" $'/path/script.sh'
     * </pre>
     * After the {@code exec} the process IS {@code bash script.sh}: same pid, {@code $0} is the
     * script, the exit code is the script's, and the command line carries no parameter value.
     *
     * <p>The OP_ prefix is not decoration: without it a parameter named PATH, IFS or LD_PRELOAD
     * would reconfigure the shell that runs the script.
     */
    String buildBashBootstrap(String scriptPath, Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        sb.append("[ -n \"$BASH_VERSION\" ] || { echo \"orchestrator.bash-exe is not bash: this runner needs bash\" >&2; exit 126; }; ");
        Map<String, String> seen = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            String name = e.getKey() == null ? "" : e.getKey().trim();
            if (name.isEmpty()) throw new StepRefused("a parameter has no name: bash receives parameters by name, as OP_<name>");
            String var = bashVariable(name);
            String clash = seen.put(var, name);
            if (clash != null) {
                throw new StepRefused("parameters '" + clash + "' and '" + name + "' are both passed to bash as " + var
                        + ": rename one of them");
            }
            sb.append("export ").append(var).append('=').append(bashQuote(e.getValue(), "parameter '" + name + "'")).append("; ");
        }
        sb.append("exec \"$BASH\" ").append(bashQuote(scriptPath, "the script path"));
        if (sb.length() > BASH_BOOTSTRAP_MAX) {
            throw new StepRefused("the parameters of this step take " + sb.length() + " bytes once quoted for bash; the limit is "
                    + BASH_BOOTSTRAP_MAX + " (Linux caps one argument at 128 KiB). Pass large data in a file");
        }
        return sb.toString();
    }

    /**
     * A value as a bash ANSI-C string, {@code $'...'}, made of ASCII only: every byte of its UTF-8
     * form that is not a plain letter, digit or one of a few harmless signs is written as
     * {@code \xHH}. Nothing in it is expanded by the shell.
     */
    static String bashQuote(String value, String what) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length + 8).append("$'");
        for (int i = 0; i < bytes.length; i++) {
            int b = bytes[i] & 0xFF;
            if (b == 0) throw new StepRefused(what + " contains a NUL character, which no program argument can carry");
            boolean plain = (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9')
                    || b == ' ' || b == '_' || b == '.' || b == '/' || b == ':' || b == '=' || b == '@' || b == ','
                    || b == '+' || b == '-';
            if (plain) {
                sb.append((char) b);
            } else {
                sb.append("\\x").append(Character.forDigit(b >> 4, 16)).append(Character.forDigit(b & 0xF, 16));
            }
        }
        return sb.append('\'').toString();
    }

    /**
     * A script with CRLF line endings fails under bash in ways that never mention line endings
     * ("syntax error: unexpected end of file", or output with a stray carriage return). Refused
     * here, with the line. Not repaired: running a corrected copy would mean the text that ran
     * is not the file in the scripts directory.
     */
    static void refuseCrlf(String scriptPath) {
        try {
            Path p = java.nio.file.Paths.get(scriptPath);
            if (!Files.isRegularFile(p) || Files.size(p) > CRLF_CHECK_MAX) return;   // missing: bash says so itself
            byte[] b = Files.readAllBytes(p);
            int line = 1;
            for (int i = 0; i < b.length; i++) {
                if (b[i] == 10) line++;
                else if (b[i] == 13 && i + 1 < b.length && b[i + 1] == 10) {
                    throw new StepRefused("the script has Windows (CRLF) line endings, first at line " + line
                            + ": bash needs LF line endings. Convert the file and upload it again");
                }
            }
        } catch (StepRefused r) {
            throw r;
        } catch (Exception unreadable) {
            // not readable here: let bash report it
        }
    }

    /**
     * CMD and JAR receive their parameters as program arguments. Outside Windows the JVM encodes
     * those with the file-name encoding, and a character that encoding lacks becomes '?' without
     * any error - with a service started without a locale that is every non-ASCII character.
     * Refused here instead. Cannot happen on Windows, where arguments are passed as UTF-16.
     */
    private void refuseUnencodable(String scriptPath, Map<String, String> params) {
        if (tree.windows()) return;
        Charset cs;
        try {
            cs = Charset.forName(System.getProperty("sun.jnu.encoding"));
        } catch (Exception unknown) {
            cs = Charset.defaultCharset();
        }
        if (!cs.newEncoder().canEncode(scriptPath == null ? "" : scriptPath)) {
            throw new StepRefused("the script path contains characters this server cannot pass to a program"
                    + " (file-name encoding " + cs.name() + "). See the Platform page");
        }
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() != null && !cs.newEncoder().canEncode(e.getValue())) {
                throw new StepRefused("parameter '" + e.getKey() + "' contains characters this server cannot pass to a program"
                        + " (file-name encoding " + cs.name() + "): they would arrive as '?'. See the Platform page");
            }
        }
    }

    /** Orchestrator-internal step params that configure the engine and must never be
     *  passed to the external script (deleteOnSuccess[Type] and outputData.* metadata). */
    private static boolean isReservedParam(String name) {
        if (name == null) return false;
        return name.equals("deleteOnSuccess")
            || name.equals("deleteOnSuccessType")
            || name.startsWith("outputData.");
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("'", "''");
    }
}
