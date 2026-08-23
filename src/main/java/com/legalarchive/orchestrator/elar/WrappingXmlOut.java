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
 *   <li>inside any other text node - <b>never</b>, and that includes the two positions that look
 *       like element boundaries and are not: immediately after the {@code >} of the start tag and
 *       immediately before the {@code </} of the end tag. Both are inside the character data. An
 *       element carrying a value is therefore written by {@link #textElement} as one unbreakable
 *       unit, measured before it is begun. If the unit cannot fit a line the document fails naming
 *       the tag, rather than emitting an over-length line or a corrupted value.</li>
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
     * A complete {@code <tag attrs>value</tag>}, written as ONE UNBREAKABLE UNIT.
     *
     * This is the only way a value reaches the file, and it exists because the two positions around a
     * text node are not what they look like. Immediately after the {@code >} of the start tag, and
     * immediately before the {@code </} of the end tag, are <b>inside the character data</b>, not
     * between elements. Breaking there does not corrupt the markup and does not corrupt the Base64 -
     * it corrupts the VALUE, by giving it a leading or trailing line break that nothing downstream
     * would flag. Confirmed in the field: 43 documents in 1000 came back from the equivalence
     * comparator with the line break on OUR side, always at one of those two positions.
     *
     * So the whole unit is measured first and the break, if any, is taken BEFORE the start tag, where
     * it is genuinely between elements. Once the room is reserved nothing inside can break, which is
     * why this writes through {@link #raw} and never through {@link #fit}.
     *
     * The content tag does not come through here: a Base64 payload is written by
     * {@link #base64Chunk}, which breaks at quad boundaries where whitespace is ignored by any
     * decoder. It is exempt by construction, not by exception.
     */
    public void textElement(String qname, String[][] attrs, String value) throws IOException {
        String esc = escapeText(check(value, qname));
        String[][] escAttrs = attrs == null ? new String[0][] : new String[attrs.length][];
        int width = 1 + qname.length();                       // <q
        for (int i = 0; i < escAttrs.length; i++) {
            String an = attrs[i][0];
            String av = escapeAttr(check(attrs[i][1], qname + "/@" + an));
            escAttrs[i] = new String[] { an, av };
            width += 1 + an.length() + 2 + av.length() + 1;   // ' n="v"'
        }
        width += 1;                                           // >
        width += esc.length();
        width += 3 + qname.length();                          // </q>

        if (width > max) {
            throw new IOException("<" + qname + "> and its value need " + width
                    + " characters on one line, which is more than the maximum of " + max
                    + " (the value alone is " + esc.length() + " after escaping; the tags add "
                    + (width - esc.length()) + "). A line break inside a value would change the value,"
                    + " so the document is refused rather than delivered corrupted."
                    + " Raise max.line.length for this family, or shorten the field.");
        }

        fit(width);                                           // the ONLY break, and it is before the tag
        StringBuilder sb = new StringBuilder(width);
        sb.append('<').append(qname);
        for (int i = 0; i < escAttrs.length; i++) {
            sb.append(' ').append(escAttrs[i][0]).append("=\"").append(escAttrs[i][1]).append('"');
        }
        sb.append('>').append(esc).append("</").append(qname).append('>');
        raw(sb.toString());
    }

    /**
     * Element text. PRIVATE and non-breaking on purpose: room for the whole element is reserved by
     * {@link #textElement} before this is reached, so there is nothing left to decide here. Exposing
     * a breaking text writer is what produced the field defect.
     */
    private void text(String qname, String value) throws IOException {
        raw(escapeText(check(value, qname)));
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
