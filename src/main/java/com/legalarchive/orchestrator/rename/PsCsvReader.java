package com.legalarchive.orchestrator.rename;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads a CSV the way PowerShell's {@code Import-Csv} does, because the executor that uses it
 * replaces a script built on {@code Import-Csv} and has to see the same rows the script saw.
 *
 * <p>This is a line-for-line port of {@code ImportCsvHelper} in PowerShell 7.4.6
 * ({@code src/Microsoft.PowerShell.Commands.Utility/commands/utility/CsvCommands.cs}, MIT
 * licence), and it was verified against the real {@code pwsh} 7.4.6. The behaviours that a
 * "normal" CSV reader would get differently, all kept on purpose:
 *
 * <ul>
 *   <li>a quoted field may contain the delimiter and real line breaks; {@code ""} is a quote;</li>
 *   <li>leading blanks (space, tab) of a field are dropped; trailing blanks of an UNQUOTED field
 *       are kept; blanks after a closing quote are dropped only if nothing else follows them;</li>
 *   <li>{@code "ab"cd} reads as {@code abcd}; {@code ab"cd} keeps the quote;</li>
 *   <li>blank and blank-only lines are skipped;</li>
 *   <li>a row shorter than the header leaves the missing fields NULL; extra fields are dropped;</li>
 *   <li>trailing empty header names are removed; an empty header name becomes {@code H1},
 *       {@code H2}...; header names differing only in case are an error;</li>
 *   <li>a first line starting with {@code #} is consumed (the {@code #TYPE} line), and header
 *       candidates starting with {@code #} are skipped ({@code #Fields: } is W3C syntax);</li>
 *   <li>a byte-order mark overrides the charset the caller named, as {@code StreamReader} does.</li>
 * </ul>
 *
 * <p>Known differences from Windows PowerShell 5.1, taken from the 6.0.0-alpha.9 source (the
 * 5.1 code base as first opened): 5.1 assumes every CR is followed by LF, and does not skip
 * {@code #} lines after the first. Neither shape occurs in a mapping CSV.
 */
public final class PsCsvReader {

    /** Parsed CSV: header names (after H-naming) and rows; a missing field is null. */
    public static final class Table {
        public final List<String> header = new ArrayList<String>();
        public final List<String[]> rows = new ArrayList<String[]>();
        /** Charset actually used: the named one, or the one a BOM announced. */
        public Charset charsetUsed;
        /** True when an empty header name had to be replaced by an H-name. */
        public boolean unspecifiedNames;
    }

    private final String s;
    private final char delim;
    private int pos;

    private PsCsvReader(String text, char delim) {
        this.s = text;
        this.delim = delim;
    }

    /** Reads the whole file; a BOM wins over {@code charset}. */
    public static Table read(Path file, Charset charset, char delim) throws IOException {
        byte[] b;
        InputStream in = Files.newInputStream(file);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            b = bo.toByteArray();
        } finally {
            in.close();
        }
        int skip = 0;
        Charset cs = charset;
        // Same precedence as .NET's StreamReader: UTF-32LE is FF FE 00 00, which starts like UTF-16LE.
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            cs = StandardCharsets.UTF_8; skip = 3;
        } else if (b.length >= 4 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE && b[2] == 0 && b[3] == 0) {
            cs = Charset.forName("UTF-32LE"); skip = 4;
        } else if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            cs = StandardCharsets.UTF_16LE; skip = 2;
        } else if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            cs = StandardCharsets.UTF_16BE; skip = 2;
        } else if (b.length >= 4 && b[0] == 0 && b[1] == 0 && (b[2] & 0xFF) == 0xFE && (b[3] & 0xFF) == 0xFF) {
            cs = Charset.forName("UTF-32BE"); skip = 4;
        }
        String text = new String(b, skip, b.length - skip, cs);
        Table t = parse(text, delim);
        t.charsetUsed = cs;
        return t;
    }

    /** Parses already decoded text. Throws IllegalArgumentException for duplicate header names. */
    public static Table parse(String text, char delim) {
        PsCsvReader r = new PsCsvReader(text, delim);
        Table t = new Table();
        List<String> header = r.readHeader();
        List<String> values = new ArrayList<String>();
        boolean first = true;
        while (true) {
            r.parseNextRecord(values);
            if (values.isEmpty()) break;
            if (values.size() == 1 && values.get(0).isEmpty()) continue;   // blank line
            if (first) {
                buildNames(header, t);
                first = false;
            }
            String[] row = new String[t.header.size()];
            for (int i = 0; i < row.length; i++) row[i] = i < values.size() ? values.get(i) : null;
            t.rows.add(row);
        }
        if (first && header != null) {
            // No data row: PowerShell never builds an object, so the names are the raw header.
            for (String h : header) t.header.add(h);
        }
        return t;
    }

    /** BuildMshobject: empty names become H1, H2...; a clash among the final names is an error. */
    private static void buildNames(List<String> header, Table t) {
        if (header == null) return;
        int idx = 1;
        Set<String> seen = new HashSet<String>();
        for (String h : header) {
            String name = h;
            if (name.isEmpty()) { name = "H" + idx; idx++; t.unspecifiedNames = true; }
            if (!seen.add(CaseInsensitive.key(name))) {
                throw new IllegalArgumentException("The member \"" + name + "\" is already present.");
            }
            t.header.add(name);
        }
    }

    // ------------------------------------------------------------------ the port

    private boolean eof() { return pos >= s.length(); }

    private char readChar() { return s.charAt(pos++); }

    private boolean peekNextChar(char c) { return pos < s.length() && s.charAt(pos) == c; }

    private String readLine() {
        int start = pos;
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == '\r' || c == '\n') {
                String line = s.substring(start, pos);
                pos++;
                if (c == '\r' && pos < s.length() && s.charAt(pos) == '\n') pos++;
                return line;
            }
            pos++;
        }
        return s.substring(start);
    }

    private List<String> readHeader() {
        // ReadTypeInformation: a first line starting with '#' is consumed whatever it says.
        if (!eof() && peekNextChar('#')) readLine();
        List<String> header = null;
        List<String> values = new ArrayList<String>();
        while (header == null && !eof()) {
            parseNextRecord(values);
            while (values.size() > 1 && values.get(values.size() - 1).isEmpty()) values.remove(values.size() - 1);
            if (!values.isEmpty() && values.get(0).startsWith("#Fields: ")) {
                values.set(0, values.get(0).substring(9));
                header = new ArrayList<String>(values);
            } else if (!values.isEmpty() && values.get(0).startsWith("#")) {
                // skipped, as PowerShell 7 does
            } else {
                header = new ArrayList<String>(values);
            }
        }
        if (header != null && !header.isEmpty()) {
            Set<String> seen = new HashSet<String>();
            for (String h : header) {
                if (h.isEmpty()) continue;
                if (!seen.add(CaseInsensitive.key(h))) {
                    throw new IllegalArgumentException("The member \"" + h + "\" is already present.");
                }
            }
        }
        return header;
    }

    private void parseNextRecord(List<String> result) {
        result.clear();
        StringBuilder current = new StringBuilder();
        boolean seenBeginQuote = false;
        boolean[] endOfRecord = new boolean[1];
        while (!eof()) {
            char ch = readChar();
            if (ch == delim) {
                if (seenBeginQuote) current.append(ch);
                else { result.add(current.toString()); current.setLength(0); }
            } else if (ch == '"') {
                if (seenBeginQuote) {
                    if (peekNextChar('"')) {
                        readChar();
                        current.append('"');
                    } else {
                        seenBeginQuote = false;
                        endOfRecord[0] = false;
                        readTillNextDelimiter(current, endOfRecord, true);
                        result.add(current.toString());
                        current.setLength(0);
                        if (endOfRecord[0]) break;
                    }
                } else if (current.length() == 0) {
                    seenBeginQuote = true;
                } else {
                    endOfRecord[0] = false;
                    current.append(ch);
                    readTillNextDelimiter(current, endOfRecord, false);
                    result.add(current.toString());
                    current.setLength(0);
                    if (endOfRecord[0]) break;
                }
            } else if (ch == ' ' || ch == '\t') {
                if (seenBeginQuote) {
                    current.append(ch);
                } else if (current.length() == 0) {
                    continue;   // leading blanks are ignored
                } else {
                    endOfRecord[0] = false;
                    current.append(ch);
                    readTillNextDelimiter(current, endOfRecord, true);
                    result.add(current.toString());
                    current.setLength(0);
                    if (endOfRecord[0]) break;
                }
            } else {
                String nl = newLine(ch);
                if (nl != null) {
                    if (seenBeginQuote) {
                        current.append(nl);
                    } else {
                        result.add(current.toString());
                        current.setLength(0);
                        break;
                    }
                } else {
                    current.append(ch);
                }
            }
        }
        if (current.length() != 0) result.add(current.toString());
    }

    /** IsNewLine: CR, LF or CRLF, consuming the LF of a CRLF. */
    private String newLine(char ch) {
        if (ch == '\r') {
            if (peekNextChar('\n')) { readChar(); return "\r\n"; }
            return "\r";
        }
        if (ch == '\n') return "\n";
        return null;
    }

    private void readTillNextDelimiter(StringBuilder current, boolean[] endOfRecord, boolean eatTrailingBlanks) {
        StringBuilder temp = new StringBuilder();
        boolean nonWhiteSpace = false;
        while (true) {
            if (eof()) { endOfRecord[0] = true; break; }
            char ch = readChar();
            if (ch == delim) break;
            if (newLine(ch) != null) { endOfRecord[0] = true; break; }
            temp.append(ch);
            if (ch != ' ' && ch != '\t') nonWhiteSpace = true;
        }
        if (eatTrailingBlanks && !nonWhiteSpace) current.append(DotNet.trim(temp.toString()));
        else current.append(temp);
    }
}
