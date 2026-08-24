package com.legalarchive.orchestrator.json2csv;

/**
 * The counters the step publishes.
 *
 * <p>{@link #filesRead} and {@link #rowsWritten} must be equal — one file is one document is one row
 * — and the step log says so rather than leaving it to be worked out. It is the cheapest possible
 * assertion that the executor did what it claims, and the one number a gate can branch on.
 *
 * <p>No value is ever counted into a name here: these are counts, and the findings that go with them
 * carry column names and paths only.
 */
public final class Json2CsvCounters {
    public long filesRead;
    public long filesFailed;
    public long rowsWritten;
    public long valuesMissing;
    public long valuesNonScalar;

    /** The inputs that produced a row, for a rename that may only happen once the CSV is closed. */
    public java.util.List<java.io.File> processed;

    /** True when one row came out per file read, which is the invariant of this executor. */
    public boolean rowPerFileHolds() { return filesRead == rowsWritten; }

    @Override public String toString() {
        return "filesRead=" + filesRead + " filesFailed=" + filesFailed + " rowsWritten=" + rowsWritten
                + " valuesMissing=" + valuesMissing + " valuesNonScalar=" + valuesNonScalar;
    }
}
