package com.legalarchive.orchestrator.elar;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the run did, counted by category.
 *
 * The legacy tool printed {@code Skipping doc: file path is null} and {@code Skipping doc: file not
 * found} to stdout and carried on, with no counter and no aggregate. A batch that had silently
 * dropped half its documents looked exactly like a successful one, and the only way to find out was
 * to count the blocks in the delivered XML by hand.
 *
 * Nothing here holds a record, a value or a path that embeds a customer identifier: counters and file
 * names only, so the whole object can go into the step log and the audit trail.
 */
public final class ElarCounters {

    public long filesProcessed;
    public long filesFailed;
    public long documentsWritten;
    public long documentsSkippedNoPath;
    public long documentsSkippedFileMissing;
    public long rowsMalformed;
    public long tagsWritten;
    public long batchesWritten;
    public long documentsOversize;
    public long bytesEmbedded;
    public long sameDayPairsFound;

    /** Why a document was not written, or null when it was. */
    public enum Skip { NO_PATH, FILE_MISSING }

    public void skipped(Skip s) {
        if (s == Skip.NO_PATH) documentsSkippedNoPath++;
        else if (s == Skip.FILE_MISSING) documentsSkippedFileMissing++;
    }

    /** Everything a document that was written contributes. */
    public void wrote(int tags, long contentBytes) {
        documentsWritten++;
        tagsWritten += tags;
        bytesEmbedded += contentBytes;
    }

    /** For {@code run.vars} and the cross-feed log report. */
    public Map<String, String> asVars() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("filesProcessed", String.valueOf(filesProcessed));
        m.put("filesFailed", String.valueOf(filesFailed));
        m.put("documentsWritten", String.valueOf(documentsWritten));
        m.put("documentsSkippedNoPath", String.valueOf(documentsSkippedNoPath));
        m.put("documentsSkippedFileMissing", String.valueOf(documentsSkippedFileMissing));
        m.put("rowsMalformed", String.valueOf(rowsMalformed));
        m.put("tagsWritten", String.valueOf(tagsWritten));
        m.put("batchesWritten", String.valueOf(batchesWritten));
        m.put("documentsOversize", String.valueOf(documentsOversize));
        m.put("bytesEmbedded", String.valueOf(bytesEmbedded));
        m.put("sameDayPairsFound", String.valueOf(sameDayPairsFound));
        return m;
    }

    /**
     * One line for the step log. Skips are stated even when zero: "0 skipped" is information, while a
     * line that only appears when something went wrong trains people not to look for it.
     */
    public String summary() {
        return "documents written " + documentsWritten
                + ", skipped " + (documentsSkippedNoPath + documentsSkippedFileMissing)
                + " (" + documentsSkippedNoPath + " with no content path, "
                + documentsSkippedFileMissing + " whose file was missing)"
                + ", malformed rows " + rowsMalformed
                + ", batches " + batchesWritten
                + ", embedded " + bytesEmbedded + " byte(s)";
    }
}
