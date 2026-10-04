package com.legalarchive.orchestrator.objunpack;

import java.io.IOException;

/**
 * A refusal by {@code objunpack}: the configuration or the package is something it will not unpack.
 *
 * <p>It carries a {@link Reason} so a test - and the step log - can tell WHICH rule refused, not
 * only that something did. Refusals raised by the reused readers keep their own types
 * ({@code UnarchiveException}, {@code ObjPackException}); the executor treats all three alike.
 */
public final class ObjUnpackException extends IOException {

    private static final long serialVersionUID = 1L;

    /** Why. The names appear in the step log. */
    public enum Reason {
        /** A parameter, the host, or the archive selection. */
        CONFIGURATION,
        MD5_MISSING, MD5_FORMAT, MD5_MISMATCH,
        /** A member that is not a bare-named regular file. */
        MEMBER,
        /** The audit file is missing, repeated or lacks what the restore needs. */
        AUDIT,
        /** The metadata file is missing, undecodable or lacks a needed column. */
        METADATA,
        /** The join audit - metadata - tar cannot be made without guessing. */
        MAPPING,
        /** An original name the host cannot hold as one file name. */
        NAME,
        /** Two objects would be restored to the same file. */
        DUPLICATE_NAME,
        /** A conformance check failed and {@code onInconsistency=fail}. */
        INCONSISTENT,
        /** The output already exists and {@code onExisting=fail}. */
        EXISTS,
        COMMIT,
        STOPPED
    }

    private final Reason reason;

    public ObjUnpackException(Reason reason, String message) {
        super(reason + ": " + message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
