package com.legalarchive.orchestrator.json2csv;

/** What an ObjectName column writes (requirement 5). The default is the bare file name. */
public enum ObjectNameValue {
    FILENAME,
    FILENAME_NOEXT,
    RELATIVE_PATH,
    ABSOLUTE_PATH;

    public static ObjectNameValue parse(String s) {
        String v = s == null ? "" : s.trim();
        if (v.isEmpty()) return FILENAME;
        String u = v.toUpperCase(java.util.Locale.ROOT).replace("_", "");
        if (u.equals("FILENAME")) return FILENAME;
        if (u.equals("FILENAMENOEXT")) return FILENAME_NOEXT;
        if (u.equals("RELATIVEPATH")) return RELATIVE_PATH;
        if (u.equals("ABSOLUTEPATH")) return ABSOLUTE_PATH;
        throw new Json2CsvException("objectNameValue '" + s
                + "' is not one of FILENAME, FILENAME_NOEXT, RELATIVE_PATH, ABSOLUTE_PATH");
    }
}
