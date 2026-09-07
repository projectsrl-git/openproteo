package com.legalarchive.orchestrator.ftps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One complete FTP reply: its three-digit code and every line that belonged to it. */
public final class FtpReply {

    private final int code;
    private final List<String> lines;

    FtpReply(int code, List<String> lines) {
        this.code = code;
        this.lines = Collections.unmodifiableList(new ArrayList<String>(lines));
    }

    public int code() {
        return code;
    }

    /** Every line of the reply, in order, with the code prefix left as the server sent it. */
    public List<String> lines() {
        return lines;
    }

    /** The last line, which is the one carrying the code that terminated the reply. */
    public String lastLine() {
        return lines.isEmpty() ? "" : lines.get(lines.size() - 1);
    }

    /** 2xx: the command succeeded and nothing further is expected for it. */
    public boolean isPositiveCompletion() {
        return code >= 200 && code < 300;
    }

    /** 1xx: the server has started and will send a second reply when it is done. */
    public boolean isPositivePreliminary() {
        return code >= 100 && code < 200;
    }

    /** 3xx: the server accepted the command and is waiting for more input, e.g. 331 after USER. */
    public boolean isPositiveIntermediate() {
        return code >= 300 && code < 400;
    }

    public boolean isNegative() {
        return code >= 400;
    }

    /** The whole reply as one string, for a log line or an exception message. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return text();
    }
}
