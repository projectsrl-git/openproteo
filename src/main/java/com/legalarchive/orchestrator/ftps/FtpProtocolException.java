package com.legalarchive.orchestrator.ftps;

import java.io.IOException;

/**
 * The server said something the protocol does not allow, or said nothing when a reply was due.
 *
 * <p>An IOException, because to every caller a server that cannot be understood and a socket that
 * broke are the same event: the transfer did not happen and must not be reported as if it had.
 */
public class FtpProtocolException extends IOException {

    private static final long serialVersionUID = 1L;

    public FtpProtocolException(String message) {
        super(message);
    }
}
