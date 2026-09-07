package com.legalarchive.orchestrator.ftps;

import java.io.IOException;

/**
 * The seam between the executor and whatever speaks FTPS.
 *
 * <p>It exists so the executor can be tested without a server, and so the JDK-only client can be
 * replaced by a library implementation without touching anything above it, should the real
 * conversation turn out to need something this one does not cover.
 */
public interface FtpsTransport {

    /** Connects, negotiates TLS and logs in. The returned session is ready to transfer. */
    FtpsSession open(FtpsTarget target) throws IOException;
}
