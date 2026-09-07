package com.legalarchive.orchestrator.ftps;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.List;

/** A logged-in FTPS connection. Not thread-safe: one session belongs to one step. */
public interface FtpsSession extends Closeable {

    /** Sends a SITE command verbatim. A non-2xx reply is an IOException. */
    void site(String command) throws IOException;

    /**
     * Uploads one file and returns the number of bytes written to the data channel.
     *
     * @param remoteDir  the directory to upload into; empty means the login directory
     * @param remoteName the name to store under
     */
    long store(File local, String remoteDir, String remoteName, TransferMode mode)
            throws IOException;

    /**
     * The server's SIZE for a remote name.
     *
     * <p>The caller compares it with the local byte count and accepts nothing else. A remote file
     * existing proves nothing: servers create the destination on accepting STOR, before a byte
     * crosses the data channel, so a failed transfer leaves a plausible zero-byte file behind.
     */
    long size(String remoteDir, String remoteName) throws IOException;

    /** The command and reply trace, for the step log. Passwords never appear in it. */
    List<String> trace();
}
