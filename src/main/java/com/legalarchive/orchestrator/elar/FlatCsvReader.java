package com.legalarchive.orchestrator.elar;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the FLAT source CSV: one row per document, one pass, nothing accumulated.
 *
 * The legacy pipeline read the same data through three different decoders - {@code Files.newBufferedReader}
 * (hardcoded UTF-8) in one half and {@code new FileReader} (platform default) in the other two - and
 * then wrote a vertical intermediate that the next stage immediately regrouped back into the map the
 * row already was. Both the intermediate and the decoder disagreement are gone: one charset, stated
 * explicitly, and no round trip.
 *
 * The reader is deliberately free of Spring and of the orchestrator's own types, so it can be
 * compiled and exercised on its own.
 */
public final class FlatCsvReader implements AutoCloseable {

    /** One data row, with the physical line it came from. */
    public static final class Row {
        public final long lineNo;
        public final String[] fields;
        /**
         * The line exactly as it was read, before splitting. Kept so a discarded row can be copied
         * out VERBATIM: re-joining the fields with the separator would silently rewrite quoting and
         * turn the discards file into something that no longer round-trips.
         */
        public final String raw;
        Row(long lineNo, String[] fields, String raw) { this.lineNo = lineNo; this.fields = fields; this.raw = raw; }
    }

    private final File file;
    private final char separator;
    private final char quote;          // 0 = quoting disabled
    private final CountingInputStream counter;
    private final BufferedReader reader;
    private final String[] header;
    private final String headerLine;
    private long lineNo = 0;

    /**
     * @param charsetName  the source charset, always explicit; never the platform default
     * @param failOnMalformed  true = REPORT (a byte that is not valid in the charset fails the file),
     *                         false = REPLACE, which hides corruption and is documented as such
     * @param quoteChar    0 disables quoting, keeping the parse identical to the legacy split
     */
    public FlatCsvReader(File file, String charsetName, boolean failOnMalformed,
                         char separator, char quoteChar) throws IOException {
        this.file = file;
        this.separator = separator;
        this.quote = quoteChar;
        Charset cs = Charset.forName(charsetName);
        CharsetDecoder dec = cs.newDecoder()
                .onMalformedInput(failOnMalformed ? CodingErrorAction.REPORT : CodingErrorAction.REPLACE)
                .onUnmappableCharacter(failOnMalformed ? CodingErrorAction.REPORT : CodingErrorAction.REPLACE);
        this.counter = new CountingInputStream(new FileInputStream(file));
        this.reader = new BufferedReader(new InputStreamReader(counter, dec), 1 << 16);
        String first = readLineChecked();
        if (first == null) {
            throw new IOException("the file is empty, so it has no header row: " + file.getAbsolutePath());
        }
        if (first.length() > 0 && first.charAt(0) == '\uFEFF') first = first.substring(1);   // BOM
        this.headerLine = first;
        this.header = split(first);
        for (int i = 0; i < header.length; i++) header[i] = header[i].trim();
    }

    public String[] header() { return header; }
    /** The header exactly as read, BOM stripped: the first line of a discards file. */
    public String headerLine() { return headerLine; }
    public int headerSize() { return header.length; }
    public String fileName() { return file.getName(); }

    /** Next data row, or null at end of file. Blank trailing lines are skipped, as legacy did. */
    public Row next() throws IOException {
        String line;
        while ((line = readLineChecked()) != null) {
            if (line.isEmpty()) continue;
            return new Row(lineNo, split(line), line);
        }
        return null;
    }

    /**
     * Wraps the decoder's failure with the information an operator can act on: which file, which line,
     * and roughly where in the file. The byte offset is approximate because the reader buffers ahead -
     * saying so is better than printing a precise-looking number that is wrong by up to 64 KB.
     */
    private String readLineChecked() throws IOException {
        try {
            String s = reader.readLine();
            if (s != null) lineNo++;
            return s;
        } catch (CharacterCodingException e) {
            throw new IOException(file.getAbsolutePath() + ": byte sequence not valid for the declared charset,"
                    + " at or shortly after line " + (lineNo + 1)
                    + " (approximately byte " + counter.count() + "; the reader buffers ahead, so this is the"
                    + " end of the block being decoded, not the exact offset)."
                    + " Set inputCharset to the charset the file is really in, or onMalformedInput=REPLACE"
                    + " to accept substitution - which hides the corruption rather than fixing it.", e);
        }
    }

    /**
     * Splits one line. With quoting disabled this is exactly the legacy behaviour except for the
     * limit: {@code split(sep, -1)} keeps trailing empty fields, which the legacy code dropped, so a
     * row ending in empty columns no longer looks like a field-count mismatch.
     */
    String[] split(String line) {
        if (quote == 0) {
            return line.split(java.util.regex.Pattern.quote(String.valueOf(separator)), -1);
        }
        List<String> out = new ArrayList<String>();
        StringBuilder sb = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQ) {
                if (c == quote) {
                    if (i + 1 < line.length() && line.charAt(i + 1) == quote) { sb.append(quote); i++; }
                    else inQ = false;
                } else sb.append(c);
            } else {
                if (c == quote) inQ = true;
                else if (c == separator) { out.add(sb.toString()); sb.setLength(0); }
                else sb.append(c);
            }
        }
        out.add(sb.toString());
        return out.toArray(new String[out.size()]);
    }

    /** Column name -> value for one row, for the columns the header declares. */
    public Map<String, String> asColumnMap(Row r) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i < header.length; i++) {
            m.put(header[i], i < r.fields.length ? r.fields[i] : "");
        }
        return m;
    }

    /**
     * ELAR tag -> value for one row. Only mapped columns are carried, which is what the vertical
     * intermediate used to express by writing one line per mapped column and reading it straight back.
     */
    public Map<String, String> asTagMap(Row r, Map<String, String> tagNameMapping) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i < header.length; i++) {
            String tag = tagNameMapping.get(header[i]);
            if (tag == null) continue;
            m.put(tag, i < r.fields.length ? r.fields[i] : "");
        }
        return m;
    }

    public void close() throws IOException { reader.close(); }

    /** Counts bytes consumed so the decoder failure can say roughly where it happened. */
    private static final class CountingInputStream extends FilterInputStream {
        private long n = 0;
        CountingInputStream(InputStream in) { super(in); }
        long count() { return n; }
        public int read() throws IOException { int c = super.read(); if (c >= 0) n++; return c; }
        public int read(byte[] b, int off, int len) throws IOException {
            int r = super.read(b, off, len);
            if (r > 0) n += r;
            return r;
        }
    }
}
