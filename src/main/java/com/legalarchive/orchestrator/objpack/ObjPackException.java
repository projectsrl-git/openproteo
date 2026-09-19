package com.legalarchive.orchestrator.objpack;

/**
 * A packaging failure with a message meant for whoever has to fix the feed, not for a stack trace.
 *
 * <p>Every throw site names the artifact, the row or the file it was looking at, because "invalid
 * submission" without a row number costs more time than the check saves.
 */
public class ObjPackException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ObjPackException(String message) {
        super(message);
    }

    public ObjPackException(String message, Throwable cause) {
        super(message, cause);
    }
}
