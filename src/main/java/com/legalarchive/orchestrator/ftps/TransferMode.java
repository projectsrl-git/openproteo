package com.legalarchive.orchestrator.ftps;

/** FTP representation type: TYPE I or TYPE A. */
public enum TransferMode {

    /** TYPE I. Bytes cross unchanged. The default, and the only mode the UNIX target uses. */
    BINARY,

    /**
     * TYPE A. The local line separator is translated to CRLF on the wire, which is what the
     * protocol requires and what lets a z/OS RECFM=VB dataset receive records rather than one very
     * long one. Deferred with the z/OS target; see the specification.
     */
    ASCII;

    public static TransferMode parse(String s) {
        if (s == null || s.trim().isEmpty()) {
            return BINARY;
        }
        String v = s.trim().toUpperCase();
        if ("BINARY".equals(v) || "I".equals(v)) {
            return BINARY;
        }
        if ("ASCII".equals(v) || "A".equals(v)) {
            return ASCII;
        }
        throw new IllegalArgumentException("unknown transfer mode: " + s);
    }

    /** The argument of the TYPE command. */
    public String typeCode() {
        return this == ASCII ? "A" : "I";
    }
}
