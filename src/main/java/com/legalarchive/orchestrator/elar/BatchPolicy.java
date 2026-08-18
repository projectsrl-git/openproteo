package com.legalarchive.orchestrator.elar;

/**
 * Decides when a batch rolls over. One rule is selected; the other limit is not read.
 *
 * The two triggers are <b>alternatives, not a race</b>. That is a divergence from the {@code sql}
 * executor, where {@code CsvWriter} ORs a row limit and a byte limit so both may be armed and
 * whichever hits first wins - the divergence is deliberate and recorded in the spec. With one trigger
 * there is never a question of which fired, which is also why the per-batch attribution counters of
 * the earlier draft are gone.
 */
public final class BatchPolicy {

    public enum By { DOCUMENTS, BYTES }
    public enum Oversize { WRITE_ALONE, FAIL }

    private final By by;
    private final int maxDocs;
    private final long maxBytes;
    private final Oversize oversize;

    private int docsInBatch = 0;
    private long bytesInBatch = 0;

    public BatchPolicy(By by, int maxDocs, long maxBytes, Oversize oversize) {
        this.by = by == null ? By.DOCUMENTS : by;
        this.maxDocs = maxDocs;
        this.maxBytes = maxBytes;
        this.oversize = oversize == null ? Oversize.WRITE_ALONE : oversize;
        if (this.by == By.DOCUMENTS && maxDocs <= 0) {
            throw new IllegalArgumentException("batchBy=DOCUMENTS needs output.max_index_docs to be a positive"
                    + " number; it is " + maxDocs);
        }
        if (this.by == By.BYTES && maxBytes <= 0) {
            throw new IllegalArgumentException("batchBy=BYTES needs maxBytesPerBatch to be a positive number of"
                    + " bytes; it is " + maxBytes);
        }
    }

    public By by() { return by; }
    public int docsInBatch() { return docsInBatch; }
    public long bytesInBatch() { return bytesInBatch; }

    /**
     * What the step logs at start.
     *
     * The ignored limit is named <b>with its value</b>, because it sits in the family's properties
     * file where anyone can read it and would otherwise silently stop mattering. A setting that can be
     * read but has no effect is worse than one that is absent.
     */
    public String describe() {
        if (by == By.DOCUMENTS) {
            return "batching by DOCUMENTS at " + maxDocs + " per INDX"
                    + "; maxBytesPerBatch and oversizeDocumentPolicy are NOT in effect";
        }
        return "batching by BYTES at " + maxBytes + " per INDX (oversize policy " + oversize + ")"
                + "; output.max_index_docs=" + (maxDocs > 0 ? String.valueOf(maxDocs) : "(unset)")
                + " from the properties file is NOT in effect";
    }

    /** What to do with the next document, decided BEFORE it is written. */
    public enum Action {
        /** Add it to the batch in progress. */
        APPEND,
        /** Close the batch in progress first, then add it to a new one. */
        ROLL_THEN_APPEND,
        /**
         * Close the batch in progress, write this document alone in its own batch, and close that
         * too. Only under {@link By#BYTES}: a document larger than the whole budget cannot be split,
         * and it is not "alone" if the open batch is left around it.
         */
        ROLL_THEN_ALONE
    }

    /** The decision, plus whether the document overshoots the budget on its own. */
    public static final class Decision {
        public final Action action;
        public final boolean oversizeDocument;
        public final long estimatedBytes;
        Decision(Action a, boolean o, long b) { action = a; oversizeDocument = o; estimatedBytes = b; }
    }

    /**
     * @param estimatedBytes what this document is expected to add, from
     *        {@link ContentEmbedder#encodedLength} plus wrapper separators and tag overhead
     * @throws IllegalStateException under {@link Oversize#FAIL} when a single document exceeds the
     *         whole budget - it can never be written, so failing is the only honest answer
     */
    public Decision decide(long estimatedBytes) {
        if (by == By.DOCUMENTS) {
            if (docsInBatch > 0 && docsInBatch >= maxDocs) return new Decision(Action.ROLL_THEN_APPEND, false, estimatedBytes);
            return new Decision(Action.APPEND, false, estimatedBytes);
        }
        boolean tooBigAlone = estimatedBytes > maxBytes;
        if (tooBigAlone) {
            if (oversize == Oversize.FAIL) {
                throw new IllegalStateException("a single document is estimated at " + estimatedBytes
                        + " bytes, more than the whole batch budget of " + maxBytes
                        + ", and oversizeDocumentPolicy=FAIL. It cannot be split, so it can never be written:"
                        + " raise maxBytesPerBatch, or set oversizeDocumentPolicy=WRITE_ALONE to deliver it"
                        + " in a batch of its own and have the overshoot reported.");
            }
            return new Decision(Action.ROLL_THEN_ALONE, true, estimatedBytes);
        }
        if (docsInBatch > 0 && bytesInBatch + estimatedBytes > maxBytes) {
            return new Decision(Action.ROLL_THEN_APPEND, false, estimatedBytes);
        }
        return new Decision(Action.APPEND, false, estimatedBytes);
    }

    /** Record that a document went into the current batch. */
    public void appended(long actualBytes) {
        docsInBatch++;
        bytesInBatch += actualBytes;
    }

    /** Record that the current batch was closed. */
    public void rolled() {
        docsInBatch = 0;
        bytesInBatch = 0;
    }

    /**
     * Whether the estimate and the reality have drifted far enough to be worth a warning.
     * A budget built on an estimator that is wrong would roll over in the wrong place, and nothing
     * downstream would ever say so.
     */
    public static boolean estimateDrifted(long estimated, long actual) {
        if (estimated <= 0) return actual > 4096;
        long diff = Math.abs(actual - estimated);
        return diff > 4096 && diff * 100 > estimated * 5;      // more than 4 KB AND more than 5%
    }
}
