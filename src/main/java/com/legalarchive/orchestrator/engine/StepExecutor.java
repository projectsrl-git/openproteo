package com.legalarchive.orchestrator.engine;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
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
 * Common behaviour for all runners: stdout+stderr is captured line by line,
 * timestamped into the step log; lines "##VAR name=value" become output variables;
 * the exit code is propagated; timeout and operator-abort kill the process.
 */
public class StepExecutor {

    public enum Kind { POWERSHELL, CMD, JAR }

    public static class Result {
        public int exitCode = -1;
        public boolean timedOut = false;
        public Map<String, String> outVars = new LinkedHashMap<String, String>();
        public String lastLines = "";
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String VAR_MARKER = "##VAR ";
    private static final String LF = String.valueOf((char) 10);

    private final String powershellExe;
    private final String javaExe;
    private final String cmdExe;

    public StepExecutor(String powershellExe, String javaExe, String cmdExe) {
        this.powershellExe = powershellExe;
        this.javaExe = javaExe;
        this.cmdExe = cmdExe;
    }

    /** Resolve the runner from an explicit exec attribute or from the file extension. */
    public static Kind resolveKind(String exec, String scriptPath) {
        if (exec != null && !exec.trim().isEmpty() && !"auto".equalsIgnoreCase(exec)) {
            String e = exec.trim().toLowerCase();
            if (e.equals("powershell") || e.equals("ps1")) return Kind.POWERSHELL;
            if (e.equals("cmd") || e.equals("bat")) return Kind.CMD;
            if (e.equals("jar") || e.equals("java")) return Kind.JAR;
        }
        String p = scriptPath == null ? "" : scriptPath.toLowerCase();
        if (p.endsWith(".bat") || p.endsWith(".cmd")) return Kind.CMD;
        if (p.endsWith(".jar")) return Kind.JAR;
        return Kind.POWERSHELL; // default / .ps1
    }

    public Result execute(Kind kind, String scriptPath, Map<String, String> params, Path logFile,
                          int timeoutSec, File workingDir, RunControl control) throws Exception {

        Result res = new Result();
        List<String> command = buildCommand(kind, scriptPath, params);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);   // keep stderr separate so the live console can colour it
        if (workingDir != null && workingDir.isDirectory()) pb.directory(workingDir);

        Files.createDirectories(logFile.getParent());
        final List<String> tail = new ArrayList<String>();

        Process process = pb.start();
        if (control != null) control.process = process;

        final BufferedReader outR = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        final BufferedReader errR = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
        final BufferedWriter log = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        final Object lock = new Object();

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
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        pumpOut.join(5000);
        pumpErr.join(5000);
        synchronized (lock) {
            if (aborted) writeRaw(log, 'S', "!!! ABORTED - process stopped by operator");
            else if (res.timedOut) writeRaw(log, 'S', "!!! TIMEOUT after " + timeoutSec + "s - process killed by the orchestrator");
            log.flush();
            log.close();
        }
        if (control != null) control.process = null;

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

    private List<String> buildCommand(Kind kind, String scriptPath, Map<String, String> params) {
        List<String> command = new ArrayList<String>();
        if (kind == Kind.CMD) {
            command.add(cmdExe);
            command.add("/c");
            command.add(scriptPath);
            for (String v : params.values()) command.add(v == null ? "" : v);
        } else if (kind == Kind.JAR) {
            command.add(javaExe);
            command.add("-Dfile.encoding=UTF-8");
            command.add("-jar");
            command.add(scriptPath);
            for (String v : params.values()) command.add(v == null ? "" : v);
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
            command.add("-EncodedCommand");
            command.add(encoded);
        }
        return command;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("'", "''");
    }
}
