package com.legalarchive.orchestrator.ftps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The ordered list of files a step will send, decided in full before the first byte moves.
 *
 * <p>Building the whole plan up front is what makes the order inspectable: the step logs every file
 * with its position, so an operator compares the order they configured against the order that will
 * happen beforehand rather than in a post-mortem. It is also what lets a mask that matches nothing
 * fail the step before anything has been uploaded, instead of halfway through a delivery.
 *
 * <p>The rules, all of them decisions rather than accidents:
 * <ul>
 *   <li>Masks in list order; within a mask, files sorted by name. The sort is explicit because
 *       directory enumeration is name order on NTFS and hash order on ext4, so a plan that
 *       inherited the filesystem's order would be right on the target and untestable anywhere
 *       else.</li>
 *   <li>First match wins: a file claimed by an earlier mask is not sent again by a later one, so a
 *       trailing catch-all is safe.</li>
 *   <li>A mask matching nothing fails, unless it is marked optional. A delivery missing its .md5 is
 *       broken, and the way it breaks is silently.</li>
 *   <li>Disabled masks are not evaluated and never cause a failure.</li>
 * </ul>
 */
public final class SendPlan {

    /**
     * Case-insensitive first so the order matches the case-insensitive matching, then the natural
     * order as a tie-break so that names differing only by case still have one definite position.
     */
    private static final Comparator<FileEntry> BY_NAME = new Comparator<FileEntry>() {
        @Override
        public int compare(FileEntry a, FileEntry b) {
            int c = String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name());
            return c != 0 ? c : a.name().compareTo(b.name());
        }
    };

    private final List<PlannedFile> files;
    private final int masksEvaluated;

    private SendPlan(List<PlannedFile> files, int masksEvaluated) {
        this.files = Collections.unmodifiableList(files);
        this.masksEvaluated = masksEvaluated;
    }

    public List<PlannedFile> files() {
        return files;
    }

    public int fileCount() {
        return files.size();
    }

    /** How many masks were enabled and therefore actually applied. */
    public int masksEvaluated() {
        return masksEvaluated;
    }

    public long totalBytes() {
        long total = 0;
        for (PlannedFile f : files) {
            total += f.bytes();
        }
        return total;
    }

    /**
     * Builds the plan.
     *
     * @param masks       the ordered mask list, as the operator arranged it
     * @param candidates  every regular file in the source directory, in any order
     * @param defaultRemoteDir the step's remote directory, used by masks that do not override it
     * @throws PlanException if a required mask matches nothing, or the list is empty
     */
    public static SendPlan build(List<SendMask> masks, List<FileEntry> candidates,
                                 String defaultRemoteDir) throws PlanException {
        if (masks == null || masks.isEmpty()) {
            throw new PlanException("no file mask configured: the step would send nothing");
        }
        String fallbackDir = defaultRemoteDir == null ? "" : defaultRemoteDir.trim();

        List<FileEntry> pool = new ArrayList<FileEntry>();
        if (candidates != null) {
            pool.addAll(candidates);
        }
        Collections.sort(pool, BY_NAME);

        boolean[] claimed = new boolean[pool.size()];
        List<PlannedFile> planned = new ArrayList<PlannedFile>();
        int evaluated = 0;

        for (int m = 0; m < masks.size(); m++) {
            SendMask mask = masks.get(m);
            if (!mask.enabled()) {
                continue;
            }
            evaluated++;
            int matchedHere = 0;
            for (int i = 0; i < pool.size(); i++) {
                if (claimed[i]) {
                    continue;
                }
                FileEntry entry = pool.get(i);
                if (!mask.matches(entry.name())) {
                    continue;
                }
                claimed[i] = true;
                matchedHere++;
                String dir = mask.remoteDir().isEmpty() ? fallbackDir : mask.remoteDir();
                planned.add(new PlannedFile(entry.name(), entry.bytes(), mask.transfer(), dir,
                        m, planned.size() + 1));
            }
            if (matchedHere == 0 && !mask.optional()) {
                throw new PlanException("file mask '" + mask.pattern()
                        + "' (position " + (m + 1) + ") matched no file"
                        + ", and it is not marked optional");
            }
        }

        if (evaluated == 0) {
            throw new PlanException("every file mask is disabled: the step would send nothing");
        }
        return new SendPlan(planned, evaluated);
    }

    /**
     * The plan as log lines, one per file, emitted before the first transfer starts. Same
     * key=value shape as the rest of the step log, parseable from stdout.
     */
    public List<String> describe() {
        List<String> out = new ArrayList<String>();
        for (PlannedFile f : files) {
            out.add("plan file=" + f.name()
                    + " bytes=" + f.bytes()
                    + " transfer=" + f.transfer()
                    + " order=" + f.order());
        }
        return out;
    }
}
