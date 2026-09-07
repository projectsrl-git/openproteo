package com.legalarchive.orchestrator.ftps;

/**
 * The six numbers of a 227 reply: four for the address the server advertises and two for the port.
 *
 * <p>Both are parsed, but the caller is expected to use only the port. Against the UAT estate this
 * server answered 227 with 10.117.145.81, an address that is not routable from where the client
 * runs; the working client substitutes the control host and this executor does the same by default
 * (ignorePasvAddress). Parsing the address anyway is what lets the step log say which address was
 * discarded, which is the difference between a diagnosable failure and a mysterious one.
 *
 * <p>The text around the numbers is not fixed by the standard, so the parser looks for the run of
 * six comma-separated numbers rather than matching a sentence. Parentheses are common but not
 * guaranteed.
 */
public final class PasvReply {

    private final String advertisedHost;
    private final int port;

    private PasvReply(String advertisedHost, int port) {
        this.advertisedHost = advertisedHost;
        this.port = port;
    }

    public String advertisedHost() {
        return advertisedHost;
    }

    public int port() {
        return port;
    }

    public static PasvReply parse(String replyLine) throws FtpProtocolException {
        if (replyLine == null) {
            throw new FtpProtocolException("no 227 reply to parse");
        }
        int[] parts = new int[6];
        int found = 0;
        int i = 0;
        final int n = replyLine.length();

        while (i < n && found < 6) {
            if (!isDigit(replyLine.charAt(i))) {
                i++;
                continue;
            }
            // A candidate run of up to six comma-separated numbers starts here.
            int save = i;
            found = 0;
            while (found < 6) {
                int start = i;
                int value = 0;
                while (i < n && isDigit(replyLine.charAt(i))) {
                    value = value * 10 + (replyLine.charAt(i) - '0');
                    if (value > 255) {
                        break;
                    }
                    i++;
                }
                if (i == start || value > 255) {
                    break;
                }
                parts[found++] = value;
                if (found == 6) {
                    break;
                }
                if (i < n && replyLine.charAt(i) == ',') {
                    i++;
                } else {
                    break;
                }
            }
            if (found < 6) {
                // Not a six-number run. Resume after the whole number that started this attempt,
                // never inside it: restarting one character in turns a rejected "999" into an
                // accepted "99" and yields a plausible, wrong port.
                i = save;
                while (i < n && isDigit(replyLine.charAt(i))) {
                    i++;
                }
                found = 0;
            }
        }

        if (found < 6) {
            throw new FtpProtocolException(
                    "227 reply does not carry six address bytes: " + replyLine);
        }
        String host = parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3];
        int port = parts[4] * 256 + parts[5];
        if (port <= 0 || port > 65535) {
            throw new FtpProtocolException("227 reply carries an impossible port: " + replyLine);
        }
        return new PasvReply(host, port);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    @Override
    public String toString() {
        return advertisedHost + ":" + port;
    }
}
