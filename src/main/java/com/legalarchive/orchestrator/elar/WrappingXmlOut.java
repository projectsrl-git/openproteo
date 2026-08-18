package com.legalarchive.orchestrator.elar;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;

/**
 * Writes the INDX, and owns the decision of where a line may break.
 *
 * The wrapping is driven by the emitter rather than by a stream underneath it, because a Writer that
 * counts characters and inserts a break every N cannot know whether it is inside markup or inside a
 * text node - which is the legacy defect, not a fix for it. {@code IndxBuilder} serialized the whole
 * document to a String and chopped it at blind character offsets.
 *
 * That survived for a reason worth recording: at 25 000 characters a line and payloads of megabytes,
 * essentially every break landed inside the Base64 of the content tag, where whitespace is ignored by
 * any decoder. A metadata value straddling a boundary would have been <b>silently corrupted</b>, and
 * nothing downstream would have flagged it.
 *
 * So the rule here is positional, not arithmetic:
 * <ul>
 *   <li>between elements, and between attributes inside a start tag - always legal;</li>
 *   <li>inside a Base64 payload - legal at any multiple of 4, the natural quad boundary;</li>
 *   <li>inside any other text node - <b>never</b>. If a value cannot fit on a line of its own the
 *       document fails naming the tag, rather than emitting an over-length line or a corrupted value.</li>
 * </ul>
 *
 * {@code maxLineLength} is a maximum and not an exact width (confirmed with the receiving team), which
 * is what makes safe positions possible at all.
 */
public final class WrappingXmlOut implements Closeable {

    private final BufferedWriter w;
    private final CharsetEncoder probe;
    private final String charsetName;
    private final int max;
    private int col = 0;
    private long chars = 0;

    /** Line separator built without a literal escape: the corporate proxy rewrites those in sources. */
    private static final String NL = String.valueOf((char) 10);

    public WrappingXmlOut(OutputStream os, String charsetName, int maxLineLength) {
        if (maxLineLength < 64) {
            throw new IllegalArgumentException("maxLineLength " + maxLineLength + " is too small to hold a start tag");
        }
        Charset cs = Charset.forName(charsetName);
        this.charsetName = cs.name();
        // REPORT on the writer is the backstop; values are checked before writing so the message can
        // name the tag. Silent '?' substitution is the one outcome that must be impossible: it would
        // place a corrupted value inside a legally archived document with nothing to flag it.
        CharsetEncoder enc = cs.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        this.w = new BufferedWriter(new OutputStreamWriter(os, enc), 1 << 16);
        this.probe = cs.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        this.max = maxLineLength;
    }

    public long charsWritten() { return chars; }
    public String charsetName() { return charsetName; }

    /**
     * The XML declaration, GENERATED from the output charset and never copied from the template.
     * This is what actually closes the legacy defect: delivered INDX files declare ISO-8859-1 while
     * being written with the JVM platform default. With the declaration derived from the encoder the
     * two cannot disagree, on any platform, under any locale.
     */
    public void declaration() throws IOException {
        raw("<?xml version=\"1.0\" encoding=\"" + charsetName + "\"?>");
        newLine();
    }

    // ------------------------------------------------------------------ markup

    public void startElement(String qname) throws IOException {
        fit(1 + qname.length());
        raw("<" + qname);
    }
    public void attribute(String name, String value) throws IOException {
        String esc = escapeAttr(check(value, name));
        // a break between attributes is legal XML; a break inside one is not
        fit(2 + name.length() + esc.length() + 2);
        raw(" " + name + "=\"" + esc + "\"");
    }
    public void closeStartTag() throws IOException { fit(1); raw(">"); }
    public void selfClose() throws IOException { fit(2); raw("/>"); }
    public void endElement(String qname) throws IOException {
        fit(3 + qname.length());
        raw("</" + qname + ">");
    }

    /**
     * Element text. Never broken, so it must fit a line by itself; when it cannot, the failure names
     * the tag rather than letting the line run over or the value be split.
     */
    public void text(String qname, String value) throws IOException {
        String esc = escapeText(check(value, qname));
        if (esc.length() > max) {
            throw new IOException("the value of <" + qname + "> is " + esc.length()
                    + " characters after escaping and cannot fit a line of " + max
                    + ". A line break inside a value would change the value, so the document is refused."
                    + " Raise max.line.length for this family, or shorten the field.");
        }
        fit(esc.length());
        raw(esc);
    }

    /**
     * A slice of a Base64 payload. Broken only at multiples of 4, so every line holds whole quads and
     * a decoder sees exactly the bytes that were encoded.
     */
    public void base64Chunk(String quads) throws IOException {
        if ((quads.length() & 3) != 0) {
            throw new IOException("internal: a Base64 chunk of " + quads.length()
                    + " characters is not a whole number of quads");
        }
        int i = 0;
        while (i < quads.length()) {
            int room = max - col;
            if (room < 4) { newLine(); room = max; }
            int take = Math.min(room - (room & 3), quads.length() - i);   // largest multiple of 4 that fits
            raw(quads.substring(i, i + take));
            i += take;
            if (i < quads.length()) newLine();
        }
    }

    /** Verbatim, for template markup already known to be safe. Still counted. */
    public void raw(String s) throws IOException {
        w.write(s);
        col += s.length();
        chars += s.length();
    }

    public void newLine() throws IOException {
        w.write(NL);
        chars += NL.length();
        col = 0;
    }

    /** Break first if the token would not fit; a token longer than a whole line is a caller error. */
    private void fit(int len) throws IOException {
        if (col > 0 && col + len > max) newLine();
    }

    // ------------------------------------------------------------------ escaping and encodability

    /**
     * A value that cannot be represented in the output charset fails HERE, naming the tag and the
     * code point, rather than at flush time where only a byte offset would be available.
     */
    private String check(String v, String where) throws IOException {
        if (v == null) return "";
        if (probe.canEncode(v)) return v;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (!probe.canEncode(c)) {
                throw new IOException("the value of <" + where + "> contains U+"
                        + String.format("%04X", (int) c) + " ('" + c + "'), which cannot be written in "
                        + charsetName + ". Correct the source value, or set outputCharset to a charset"
                        + " that can represent it - the encoding named in the declaration is always the"
                        + " one actually used, so changing it stays consistent.");
            }
        }
        return v;   // unreachable in practice: canEncode disagreed with itself
    }

    static String escapeText(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') sb.append("&amp;");
            else if (c == '<') sb.append("&lt;");
            else if (c == '>') sb.append("&gt;");
            else sb.append(c);
        }
        return sb.toString();
    }

    static String escapeAttr(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') sb.append("&amp;");
            else if (c == '<') sb.append("&lt;");
            else if (c == '>') sb.append("&gt;");
            else if (c == '"') sb.append("&quot;");
            else if (c == 9 || c == 10 || c == 13) sb.append("&#").append((int) c).append(';');
            else sb.append(c);
        }
        return sb.toString();
    }

    public void flush() throws IOException { w.flush(); }
    public void close() throws IOException { w.close(); }
}
