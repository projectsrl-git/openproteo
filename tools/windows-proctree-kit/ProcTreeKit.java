import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * MEASUREMENT KIT - Windows only. Not part of the application; nothing in the WAR uses it.
 *
 * On Linux, OpenProteo kills the whole process tree of a step on timeout and Stop. On Windows it
 * still kills the interpreter only, because Java 8 holds a HANDLE there, not a process id, and
 * nobody has measured what a pure-Java way around that would do. This kit measures it, so that
 * the Windows design is written on numbers (spec .claude/LINUX_AND_GUI_CONFIG.md, section 8.7).
 *
 * WRITTEN BLIND: it was compiled with Java 8 and its non-Windows exit was run, but none of the
 * Windows steps has ever been executed by its author. Every step is wrapped so that a failure is
 * reported and the next step still runs. If something looks wrong, the raw output is the finding.
 *
 * It starts a few "ping" processes as stand-ins for a script's children, looks at which survive
 * each way of killing, and removes whatever is left at the end. It changes nothing else.
 *
 * Run:  run-kit.cmd      (compiles this file and writes proctree-report.txt next to it)
 */
public class ProcTreeKit {

    static String powershell = "powershell.exe";

    public static void main(String[] args) throws Exception {
        String os = System.getProperty("os.name");
        line("ProcTreeKit - os.name=" + os + " java.version=" + System.getProperty("java.version")
                + " vendor=" + System.getProperty("java.vendor"));
        if (os == null || !os.toLowerCase().startsWith("windows")) {
            line("This kit measures Windows. Nothing was run.");
            System.exit(2);
        }
        if (args.length > 0) powershell = args[0];
        String jvm = ManagementFactory.getRuntimeMXBean().getName();
        String jvmPid = jvm.indexOf('@') > 0 ? jvm.substring(0, jvm.indexOf('@')) : "?";
        line("JVM pid (from RuntimeMXBean name '" + jvm + "'): " + jvmPid);

        File dir = Files.createTempDirectory("op-proctree-kit").toFile();
        File cmdA = script(dir, "childA.cmd", "171");
        File cmdB = script(dir, "childB.cmd", "172");
        File cmdC = script(dir, "childC.cmd", "174");

        section("1. What Java knows about a process it started");
        try {
            Process p = new ProcessBuilder("cmd.exe", "/c", "ping -n 3 127.0.0.1 >nul").start();
            line("class: " + p.getClass().getName());
            for (Field f : p.getClass().getDeclaredFields()) line("  field: " + f.getType().getSimpleName() + " " + f.getName());
            line("Process.pid() by reflection: " + pidByApi(p));
            p.waitFor();
        } catch (Throwable t) { line("FAILED: " + t); }

        section("2. TODAY: destroyForcibly() on cmd.exe /c script (a foreground ping and a background one)");
        try {
            Process p = new ProcessBuilder("cmd.exe", "/c", cmdA.getAbsolutePath()).start();
            Thread.sleep(3000);
            line("alive before the kill:"); List<String> before = withMarker("171");
            long t0 = System.currentTimeMillis();
            p.destroyForcibly(); p.waitFor(10, TimeUnit.SECONDS);
            line("destroyForcibly + wait: " + (System.currentTimeMillis() - t0) + " ms");
            Thread.sleep(1500);
            line("alive AFTER the kill (these are what survives today):"); List<String> after = withMarker("171");
            line("RESULT 2: before=" + before.size() + " after=" + after.size());
        } catch (Throwable t) { line("FAILED: " + t); }

        section("3. Can the step be found among the JVM's children? (CIM, ParentProcessId = JVM pid)");
        String foundPid = null;
        Process pb = null;
        try {
            pb = new ProcessBuilder("cmd.exe", "/c", cmdB.getAbsolutePath()).start();
            Thread.sleep(3000);
            long t0 = System.currentTimeMillis();
            List<String> kids = ps("Get-CimInstance Win32_Process -Filter 'ParentProcessId=" + jvmPid + "' | ForEach-Object { "
                    + "$_.ProcessId.ToString() + '|' + $_.Name + '|' + $_.CommandLine }");
            line("CIM lookup took " + (System.currentTimeMillis() - t0) + " ms; children of the JVM:");
            int matches = 0;
            for (String k : kids) {
                line("  " + k);
                if (k.contains("childB.cmd")) { matches++; foundPid = k.substring(0, k.indexOf('|')); }
            }
            line("RESULT 3: children=" + kids.size() + " matching 'childB.cmd'=" + matches + " pid=" + foundPid
                    + " | Process.pid() says: " + pidByApi(pb));
        } catch (Throwable t) { line("FAILED: " + t); }

        section("4. taskkill /PID <found> /T /F");
        try {
            if (foundPid == null) line("skipped: no pid from step 3");
            else {
                line("alive before:"); List<String> before = withMarker("172");
                long t0 = System.currentTimeMillis();
                List<String> out = run(Arrays.asList("taskkill", "/PID", foundPid, "/T", "/F"));
                line("taskkill took " + (System.currentTimeMillis() - t0) + " ms, said:");
                for (String o : out) line("  " + o);
                Thread.sleep(1500);
                line("alive after:"); List<String> after = withMarker("172");
                line("RESULT 4: before=" + before.size() + " after=" + after.size());
            }
            if (pb != null) { pb.destroyForcibly(); }
        } catch (Throwable t) { line("FAILED: " + t); }

        section("5. The same for a PowerShell step started the way OpenProteo starts one (-EncodedCommand)");
        try {
            String inner = "Start-Process -FilePath ping -ArgumentList '-n','173','127.0.0.1' -WindowStyle Hidden; Start-Sleep -Seconds 120";
            String enc = Base64.getEncoder().encodeToString(inner.getBytes(StandardCharsets.UTF_16LE));
            Process p = new ProcessBuilder(powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", enc).start();
            Thread.sleep(5000);
            List<String> kids = ps("Get-CimInstance Win32_Process -Filter 'ParentProcessId=" + jvmPid + "' | ForEach-Object { "
                    + "$_.ProcessId.ToString() + '|' + $_.Name + '|' + $_.CommandLine }");
            String pid = null; int matches = 0;
            for (String k : kids) if (k.contains(enc)) { matches++; pid = k.substring(0, k.indexOf('|')); }
            line("children of the JVM whose command line contains our exact base64: " + matches + " pid=" + pid);
            line("alive before:"); List<String> before = withMarker("173");
            p.destroyForcibly(); p.waitFor(10, TimeUnit.SECONDS); Thread.sleep(1500);
            line("alive after destroyForcibly (today):"); List<String> mid = withMarker("173");
            line("RESULT 5: identified=" + matches + " children-before=" + before.size() + " surviving-today=" + mid.size());
        } catch (Throwable t) { line("FAILED: " + t); }

        section("6. Two identical steps at once: can they be told apart?");
        try {
            Process p1 = new ProcessBuilder("cmd.exe", "/c", cmdC.getAbsolutePath()).start();
            Process p2 = new ProcessBuilder("cmd.exe", "/c", cmdC.getAbsolutePath()).start();
            Thread.sleep(3000);
            List<String> kids = ps("Get-CimInstance Win32_Process -Filter 'ParentProcessId=" + jvmPid + "' | ForEach-Object { "
                    + "$_.ProcessId.ToString() + '|' + $_.CreationDate.ToString('o') + '|' + $_.CommandLine }");
            int matches = 0;
            for (String k : kids) if (k.contains("childC.cmd")) { matches++; line("  " + k); }
            line("RESULT 6: identical command lines found=" + matches + " (2 means the command line alone cannot tell them apart)");
            p1.destroyForcibly(); p2.destroyForcibly();
        } catch (Throwable t) { line("FAILED: " + t); }

        section("7. Is wmic there? (it is being removed from Windows)");
        try { for (String o : run(Arrays.asList("cmd.exe", "/c", "where wmic"))) line("  " + o); } catch (Throwable t) { line("FAILED: " + t); }

        section("8. Cleaning up");
        try {
            for (String m : new String[] { "171", "172", "173", "174" }) {
                for (String l : withMarker(m)) {
                    String pid = l.trim().substring(0, l.trim().indexOf('|'));
                    run(Arrays.asList("taskkill", "/PID", pid, "/F"));
                }
            }
            line("left after cleanup: " + (withMarker("171").size() + withMarker("172").size() + withMarker("173").size() + withMarker("174").size()));
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        } catch (Throwable t) { line("FAILED: " + t); }
        line("");
        line("Done. Send back this whole report.");
    }

    /** A script with one foreground and one background child, each recognisable by its ping count. */
    static File script(File dir, String name, String marker) throws Exception {
        File f = new File(dir, name);
        String body = "@echo off\r\nstart \"\" /b ping -n " + marker + " 127.0.0.1 >nul\r\nping -n " + marker + " 127.0.0.1 >nul\r\n";
        Files.write(f.toPath(), body.getBytes(StandardCharsets.US_ASCII));
        return f;
    }

    /** Every process whose command line carries "-n <marker>", except the query itself. Prints and returns them. */
    static List<String> withMarker(String marker) throws Exception {
        List<String> out = ps("Get-CimInstance Win32_Process | Where-Object { $_.ProcessId -ne $PID -and $_.CommandLine -like '*-n*" + marker
                + "*127.0.0.1*' } | ForEach-Object { $_.ProcessId.ToString() + '|parent ' + $_.ParentProcessId + '|' + $_.Name + '|' + $_.CommandLine }");
        List<String> rows = new ArrayList<String>();
        for (String o : out) if (o.indexOf('|') > 0) { rows.add(o); line("    " + o); }
        if (rows.isEmpty()) line("    (none)");
        return rows;
    }

    static List<String> ps(String command) throws Exception {
        return run(Arrays.asList(powershell, "-NoProfile", "-NonInteractive", "-Command", command));
    }

    static List<String> run(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getOutputStream().close();
        List<String> out = new ArrayList<String>();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String l;
        while ((l = r.readLine()) != null) if (!l.trim().isEmpty()) out.add(l);
        p.waitFor(60, TimeUnit.SECONDS);
        return out;
    }

    static String pidByApi(Process p) {
        try {
            Method m = Process.class.getMethod("pid");
            return String.valueOf(m.invoke(p));
        } catch (NoSuchMethodException e) {
            return "not available (Java 8)";
        } catch (Throwable t) {
            return "failed: " + t;
        }
    }

    static void section(String s) { line(""); line("==== " + s); }
    static void line(String s) { System.out.println(s); }
}
