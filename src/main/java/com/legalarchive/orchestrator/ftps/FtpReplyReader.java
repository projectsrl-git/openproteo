package com.legalarchive.orchestrator.ftps;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads one complete FTP reply, honouring the multiline form of RFC 959.
 *
 * <p>A reply whose first line is "NNN-" continues until a line that starts with the same NNN
 * followed by a space. Intermediate lines are arbitrary and may themselves begin with digits or
 * with "NNN-" again, so termination is decided only by "same code, then a space".
 *
 * <p>This is not a theoretical concern. The z/OS session log opens with a two-line 220 and logs in
 * with "230-User S210834 is an authorized user" followed by "230 S210834 is logged on." A reader
 * that takes the first line and moves on leaves the rest of the banner in the buffer, reads it as
 * the answer to the next command, and from there every error it reports belongs to the wrong
 * command.
 *
 * <p>The source is an interface rather than a socket so that the parser can be exercised without a
 * network.
 */
public final class FtpReplyReader {

    /** A source of already-decoded protocol lines, without their CRLF. */
    public interface LineSource {
        /** The next line, or null at end of stream. */
        String readLine() throws IOException;
    }

    private FtpReplyReader() {
    }

    public static FtpReply read(LineSource source) throws IOException {
        String first = source.readLine();
        if (first == null) {
            throw new FtpProtocolException("connection closed while waiting for a reply");
        }
        if (!hasCodePrefix(first)) {
            throw new FtpProtocolException("malformed reply, no three-digit code: " + first);
        }
        int code = Integer.parseInt(first.substring(0, 3));
        List<String> lines = new ArrayList<String>();
        lines.add(first);

        if (first.charAt(3) != '-') {
            return new FtpReply(code, lines);
        }

        String terminator = first.substring(0, 3) + " ";
        while (true) {
            String line = source.readLine();
            if (line == null) {
                throw new FtpProtocolException(
                        "connection closed inside a multiline " + code + " reply");
            }
            lines.add(line);
            if (line.startsWith(terminator) || line.equals(first.substring(0, 3))) {
                return new FtpReply(code, lines);
            }
        }
    }

    private static boolean hasCodePrefix(String line) {
        if (line.length() < 4) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            char c = line.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        char sep = line.charAt(3);
        return sep == ' ' || sep == '-';
    }
}
