package com.legalarchive.orchestrator.ftps;

/** How an upload is confirmed once the data channel has closed. */
public enum VerifyMode {

    /**
     * Ask the server for SIZE and accept only if it equals the local byte count. The default, and
     * the only mode that catches a file which arrived truncated or empty.
     */
    SIZE,

    /**
     * Take the server's own 226 as the confirmation and ask nothing further.
     *
     * <p>For accounts that are permitted to store and not to stat: a delivery drop box answers SIZE
     * with 550 either because the command is not allowed or because the file has already been
     * collected, and neither of those is a failed transfer. It is weaker than SIZE and deliberately
     * so - 226 is the server saying it received the stream and closed the file, which is more than
     * "the file exists" and less than "the file is the right length". A truncation the server did
     * not notice would pass. Every run in this mode says so in the step log.
     */
    NONE;

    public static VerifyMode parse(String s) {
        if (s == null || s.trim().isEmpty()) {
            return SIZE;
        }
        return valueOf(s.trim().toUpperCase());
    }
}
