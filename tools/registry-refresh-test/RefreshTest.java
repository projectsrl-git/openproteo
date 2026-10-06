import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

import com.legalarchive.orchestrator.audit.AuditLogger;
import com.legalarchive.orchestrator.config.AppProperties;
import com.legalarchive.orchestrator.engine.WorkflowEngine;
import com.legalarchive.orchestrator.engine.WorkflowScheduler;
import com.legalarchive.orchestrator.model.def.WorkflowDef;
import com.legalarchive.orchestrator.registry.WorkflowRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * refresh() against reload(): the real WorkflowRegistry, WorkflowScheduler, WorkflowXmlParser and
 * FeedLayout, on a real directory. AuditLogger, slf4j and the Spring scheduler are test doubles.
 */
public class RefreshTest {
    static int checks = 0, failures = 0, timersCreated, timersCancelled;
    static File root, wfDir, base;
    static AppProperties props;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) { failures++; System.out.println("FAIL " + what); }
    }
    static void eq(Object a, Object b, String what) {
        checks++;
        if (!String.valueOf(a).equals(String.valueOf(b))) {
            failures++;
            System.out.println("FAIL " + what + System.lineSeparator() + "   got      " + a + System.lineSeparator() + "   expected " + b);
        }
    }

    static String xml(String feedId, String name, String cron, int steps) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<workflow feedId=\"" + feedId + "\" name=\"" + name + "\"");
        if (cron != null) sb.append(" cron=\"").append(cron).append("\"");
        sb.append(">\n  <steps>\n");
        for (int i = 0; i < steps; i++)
            sb.append("    <step id=\"s").append(i).append("\" name=\"Step ").append(i)
              .append("\" exec=\"filecopy\" mode=\"list\" source=\"${landingIn}\" dest=\"${stepDir}\" pattern=\"*.csv\"/>\n");
        sb.append("  </steps>\n</workflow>\n");
        return sb.toString();
    }
    static void write(String file, String content) throws Exception {
        Files.write(new File(wfDir, file).toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
    /** A change made by hand must be visible in the stamp: push the time forward. */
    static void bump(String file, long by) { File f = new File(wfDir, file); f.setLastModified(f.lastModified() + by); }

    static void freshDirs(String tag) throws Exception {
        wfDir = new File(root, tag + "/workflows"); base = new File(root, tag + "/feeds");
        wfDir.mkdirs(); base.mkdirs();
        props = new AppProperties();
        props.setWorkflowsDir(wfDir.getPath());
        props.setDefaultBaseDir(base.getPath());
    }
    static WorkflowRegistry registry() { WorkflowRegistry r = new WorkflowRegistry(props, new AuditLogger()); r.reload(); return r; }

    /** Everything a caller can observe of a registry. */
    static String state(WorkflowRegistry r) {
        StringBuilder sb = new StringBuilder();
        for (WorkflowDef wf : r.all()) {
            sb.append(wf.feedId).append('|').append(wf.sourceFile).append('|').append(wf.name).append('|').append(wf.cron)
              .append('|').append(wf.nodes.size()).append('|').append(r.layout(wf.feedId).feedDir)
              .append('|').append(r.layout(wf.feedId).stepDirs.keySet()).append(';');
            if (r.get(wf.feedId) != wf) sb.append("GET-MISMATCH;");
        }
        sb.append(" ERR=").append(r.errors());
        return sb.toString();
    }
    static String sched(WorkflowScheduler s, WorkflowRegistry r) {
        StringBuilder sb = new StringBuilder();
        for (WorkflowDef wf : r.all()) if (s.isScheduled(wf.feedId)) sb.append(wf.feedId).append(',');
        return sb + " ERR=" + s.errors();
    }
    static int loadedEvents() { int n = 0; for (String e : AuditLogger.EVENTS) if (e.startsWith("WORKFLOW_LOADED:")) n++; return n; }
    static List<String> loadedIds() { List<String> o = new ArrayList<String>(); for (String e : AuditLogger.EVENTS) if (e.startsWith("WORKFLOW_LOADED:")) o.add(e.substring(16)); return o; }

    /** refresh on the live registry, then a brand-new registry reloading the same directory: same state. */
    static WorkflowRegistry.Refresh refreshAndCompare(WorkflowRegistry r, WorkflowScheduler s, List<String> written, String what) {
        AuditLogger.EVENTS.clear();
        WorkflowRegistry.Refresh res = r.refresh(written);
        List<String> audited = loadedIds();
        int c0 = ThreadPoolTaskScheduler.created, x0 = ThreadPoolTaskScheduler.cancelled;
        if (s != null) s.reschedule(res);
        timersCreated = ThreadPoolTaskScheduler.created - c0; timersCancelled = ThreadPoolTaskScheduler.cancelled - x0;
        String got = state(r);
        String gotSched = s == null ? null : sched(s, r);
        WorkflowRegistry fresh = registry();
        eq(got, state(fresh), what + " - registry after refresh vs a fresh reload");
        if (s != null) {
            WorkflowScheduler fs = new WorkflowScheduler(fresh, new WorkflowEngine()); fs.init();
            eq(gotSched, sched(fs, fresh), what + " - scheduler after refresh vs a fresh reschedule");
        }
        if (!res.full) eq(audited, res.loaded, what + " - WORKFLOW_LOADED written exactly for what was read");
        return res;
    }

    public static void main(String[] a) throws Exception {
        root = Files.createTempDirectory(new File(a[0]).toPath(), "reg").toFile();
        long seed = a.length > 1 ? Long.parseLong(a[1]) : 20261004L;
        int rounds = a.length > 2 ? Integer.parseInt(a[2]) : 400;

        // ---------------------------------------------------------------- 1. the request itself
        freshDirs("t1");
        for (int i = 0; i < 30; i++) write(String.format("F%02d.xml", i), xml("F" + i, "Feed " + i, i % 5 == 0 ? "0 0 6 * * *" : null, 2));
        AuditLogger.EVENTS.clear();
        WorkflowRegistry r = registry();
        eq(loadedEvents(), 30, "1 positive control: a reload audits every workflow");
        WorkflowScheduler s = new WorkflowScheduler(r, new WorkflowEngine()); s.init();
        WorkflowDef untouched = r.get("F3");

        write("F10.xml", xml("F10", "Feed ten, edited", "0 30 7 * * *", 3));
        WorkflowRegistry.Refresh res = refreshAndCompare(r, s, Collections.singletonList("F10.xml"), "1 save one of thirty");
        check(!res.full, "1 one saved workflow is not a full reload");
        eq(res.loaded, "[F10]", "1 only the saved workflow was read");
        eq(r.get("F10").name, "Feed ten, edited", "1 the saved definition is the one in the registry");
        eq(r.get("F10").nodes.size(), 3, "1 the saved steps are the ones in the registry");
        eq(r.layout("F10").stepDirs.size(), 3, "1 the layout follows the saved steps");
        check(new File(base, "F10/30_s2").isDirectory(), "1 the directory of the added step exists");
        check(r.get("F3") == untouched, "1 an untouched workflow is the same object: not parsed again");
        eq(timersCreated, 1, "1 one timer created (F10 has a cron)");
        eq(timersCancelled, 1, "1 one timer cancelled (F10 had one)");

        // nothing written, nothing changed: nothing read
        AuditLogger.EVENTS.clear();
        res = r.refresh(null);
        check(!res.full && res.loaded.isEmpty() && res.removed.isEmpty() && loadedEvents() == 0, "1 refresh with nothing changed reads nothing");

        // ---------------------------------------------------------------- 2. same stamp, forced
        // Two saves of the same length inside one tick of the file-system clock: the stamp cannot
        // tell them apart, the name the caller passes must.
        File f7 = new File(wfDir, "F07.xml");
        long t = f7.lastModified(), len = f7.length();
        write("F07.xml", xml("F7", "Feed X", null, 2));       // "Feed 7" -> "Feed X": same length
        f7.setLastModified(t);
        eq(f7.length() + "/" + f7.lastModified(), len + "/" + t, "2 fixture: the stamp really is unchanged");
        res = refreshAndCompare(r, s, Collections.singletonList("F07.xml"), "2 same stamp, named by the caller");
        eq(r.get("F7").name, "Feed X", "2 a written file is read even when its stamp did not move");
        // the name on disk may differ in case from the one the caller built (case-insensitive host)
        write("F07.xml", xml("F7", "Feed Y", null, 2));
        f7.setLastModified(t);
        res = r.refresh(Collections.singletonList("f07.XML"));
        eq(r.get("F7").name, "Feed Y", "2 the caller's name matches ignoring case");
        res = r.refresh(Collections.singletonList(new File(wfDir, "F07.xml").getAbsolutePath()));
        check(!res.full && res.loaded.equals(Collections.singletonList("F7")), "2 a path is reduced to its name");

        // ---------------------------------------------------------------- 3. changes made by hand
        write("F20.xml", xml("F20", "Feed 20 edited by hand, longer", null, 2));
        write("A-NEW.xml", xml("ANEW", "Dropped in by hand", "0 0 1 * * *", 1));
        new File(wfDir, "F21.xml").delete();
        write("F10.xml", xml("F10", "Feed ten, saved again", "0 30 7 * * *", 3));
        res = refreshAndCompare(r, s, Collections.singletonList("F10.xml"), "3 a save after three changes by hand");
        check(!res.full, "3 changes local to their files do not need a full reload");
        eq(new TreeSet<String>(res.loaded), "[ANEW, F10, F20]", "3 the files changed by hand are picked up by the next save");
        eq(res.removed, "[F21]", "3 the file deleted by hand is dropped");
        eq(r.all().get(0).feedId, "ANEW", "3 a new workflow lands where a reload puts it (file order), not at the end");
        check(s.isScheduled("ANEW") && !s.isScheduled("F21"), "3 the scheduler follows");

        // ---------------------------------------------------------------- 4. new workflow, delete
        write("M-MID.xml", xml("MID", "Created from the designer", null, 1));
        res = refreshAndCompare(r, s, Collections.singletonList("M-MID.xml"), "4 create");
        check(!res.full && res.loaded.equals(Collections.singletonList("MID")), "4 a new workflow is one read");
        check(r.all().indexOf(r.get("MID")) > r.all().indexOf(r.get("F29")), "4 M-MID.xml sorts after F29.xml");
        new File(wfDir, "F05.xml").delete();      // F5 has a cron
        res = refreshAndCompare(r, s, Collections.singletonList("F05.xml"), "4 delete");
        check(!res.full && res.removed.equals(Collections.singletonList("F5")) && r.get("F5") == null && r.layout("F5") == null, "4 a deleted workflow is one removal");
        check(!s.isScheduled("F5"), "4 a deleted workflow has no timer when the caller reschedules");

        // ---------------------------------------------------------------- 5. broken files
        write("F12.xml", "<workflow feedId=\"F12\"><steps></workflow>");
        res = refreshAndCompare(r, s, Collections.singletonList("F12.xml"), "5 a file that no longer parses");
        check(!res.full, "5 a broken file is handled without reloading the others");
        check(r.get("F12") == null && r.errors().size() == 1 && r.errors().get(0).startsWith("F12.xml: "), "5 the workflow is gone and the error is listed: " + r.errors());
        // a broken file sitting in the directory must not turn every later save into a full reload
        write("F13.xml", xml("F13", "Feed 13 edited", null, 2));
        res = refreshAndCompare(r, s, Collections.singletonList("F13.xml"), "5 a save while a broken file is in the directory");
        check(!res.full && res.loaded.equals(Collections.singletonList("F13")), "5 still one read: " + res.reason + " " + res.loaded);
        write("F12.xml", xml("F12", "Feed 12 repaired", null, 2));
        res = refreshAndCompare(r, s, Collections.singletonList("F12.xml"), "5 the broken file repaired");
        check(!res.full && r.errors().isEmpty() && r.get("F12") != null, "5 the error goes away with the repair");

        // ---------------------------------------------------------------- 6. the cases that need the whole directory
        write("F14-copy.xml", xml("F14", "Second file declaring F14", null, 1));
        res = refreshAndCompare(r, s, Collections.singletonList("F14-copy.xml"), "6 a second file with a feedId already held");
        check(res.full && res.reason.contains("F14"), "6 a duplicate feedId is decided by a full reload: " + res.reason);
        eq(r.errors(), "[F14.xml: duplicate feedId 'F14']", "6 file order decides the loser (F14-copy.xml sorts first)");
        new File(wfDir, "F14-copy.xml").delete();
        res = refreshAndCompare(r, s, Collections.singletonList("F14-copy.xml"), "6 the winner of a duplicate is deleted");
        check(res.full, "6 deleting the winner promotes the loser: full reload");
        check(r.get("F14") != null && "F14.xml".equals(r.get("F14").sourceFile) && r.errors().isEmpty(), "6 the loser is now loaded");
        write("F15.xml", xml("F15-RENAMED", "feedId changed inside the file", null, 1));
        res = refreshAndCompare(r, s, Collections.singletonList("F15.xml"), "6 the feedId changes inside a file");
        check(res.full && r.get("F15") == null && r.get("F15-RENAMED") != null, "6 a feedId that moves is a full reload");
        // a duplicate that stays: saving the winner in place stays local
        write("Z-dup.xml", xml("F16", "Loser", null, 1));
        refreshAndCompare(r, s, null, "6 a losing duplicate appears");
        write("F16.xml", xml("F16", "Winner, edited in place", null, 4));
        res = refreshAndCompare(r, s, Collections.singletonList("F16.xml"), "6 the winner of a standing duplicate is saved");
        check(!res.full && r.get("F16").nodes.size() == 4, "6 an in-place save next to a standing duplicate stays local");

        // ---------------------------------------------------------------- 7. directory problems
        freshDirs("t7");
        File realDir = wfDir;
        props.setWorkflowsDir(new File(root, "t7/absent").getPath());
        r = registry();
        check(r.errors().size() == 1 && r.errors().get(0).startsWith("Workflows directory does not exist"), "7 fixture: missing directory");
        props.setWorkflowsDir(realDir.getPath()); wfDir = realDir;
        write("ONE.xml", xml("ONE", "One", null, 1));
        res = r.refresh(Collections.singletonList("ONE.xml"));
        check(res.full && r.get("ONE") != null && r.errors().isEmpty(), "7 after a failed listing the first refresh reloads everything");
        // a workflow whose directories cannot be set up: retried by every refresh, as a reload would
        File blocker = new File(base, "BLOCKED"); Files.write(blocker.toPath(), new byte[] { 1 });
        write("BLOCKED.xml", xml("BLOCKED", "Its feed directory is a file", null, 1));
        res = r.refresh(Collections.singletonList("BLOCKED.xml"));
        check(res.full && r.get("BLOCKED") == null && r.errors().size() == 1, "7 a set-up failure is an error, by full reload: " + r.errors());
        blocker.delete();
        res = refreshAndCompare(r, null, Collections.<String>emptyList(), "7 the obstacle removed, nothing written");
        check(res.full && r.get("BLOCKED") != null && r.errors().isEmpty(), "7 a set-up failure is retried by the next refresh");

        // ---------------------------------------------------------------- 8. random walk
        freshDirs("t8");
        Random rnd = new Random(seed);
        String[] names = new String[14];
        for (int i = 0; i < names.length; i++) names[i] = (i % 3 == 0 ? "b" : i % 3 == 1 ? "A" : "c") + i + ".xml";   // mixed case: order is the host's
        String[] ids = { "W0", "W1", "W2", "W3", "W4", "W5", "W6", "W7", "W8", "W9", "W10", "W11" };                    // fewer ids than files: duplicates happen
        for (int i = 0; i < 8; i++) write(names[i], xml(ids[i], "w" + i, null, 1));
        r = registry();
        s = new WorkflowScheduler(r, new WorkflowEngine()); s.init();
        int fullCount = 0, localCount = 0;
        for (int round = 0; round < rounds; round++) {
            List<String> written = new ArrayList<String>();
            int ops = 1 + rnd.nextInt(3);
            StringBuilder desc = new StringBuilder("8 round " + round + ":");
            for (int o = 0; o < ops; o++) {
                String n = names[rnd.nextInt(names.length)];
                File f = new File(wfDir, n);
                boolean tell = rnd.nextInt(3) != 0;       // two thirds through the application, one third by hand
                int kind = rnd.nextInt(10);
                long before = f.exists() ? f.lastModified() : 0;
                if (kind < 5) {            // save: usually keeping the file's feedId, sometimes not
                    String id = null;
                    if (f.exists() && rnd.nextInt(8) != 0) for (WorkflowDef wf : r.all()) if (n.equals(wf.sourceFile)) id = wf.feedId;
                    if (id == null) id = ids[rnd.nextInt(ids.length)];
                    String cron = rnd.nextInt(4) == 0 ? "0 0 " + rnd.nextInt(24) + " * * *" : rnd.nextInt(9) == 0 ? "not a cron" : null;
                    write(n, xml(id, "r" + round + "o" + o, cron, 1 + rnd.nextInt(3)));
                    desc.append(" save ").append(n).append('=').append(id);
                } else if (kind < 7) {
                    if (f.exists()) { f.delete(); desc.append(" delete ").append(n); }
                } else if (kind < 9) {
                    write(n, "<workflow feedId=\"x\"><broken round=\"" + round + "\">");
                    desc.append(" break ").append(n);
                } else {
                    desc.append(" touch-nothing");
                }
                if (tell) { written.add(n); desc.append("(told)"); }
                else if (f.exists()) f.setLastModified(Math.max(before, f.lastModified()) + 2000L * (round + 2));   // by hand: time has passed
            }
            res = refreshAndCompare(r, s, written, desc.toString());
            if (res.full) fullCount++; else localCount++;
        }
        check(localCount > rounds / 4 && fullCount > 0, "8 the walk exercised both paths (local " + localCount + ", full " + fullCount + ")");
        System.out.println("random walk: " + rounds + " rounds, seed " + seed + ", local " + localCount + ", full " + fullCount);

        System.out.println(checks + " checks, " + failures + " failures  [java " + System.getProperty("java.version") + ", user " + System.getProperty("user.name") + "]");
        System.exit(failures == 0 ? 0 : 1);
    }
}
