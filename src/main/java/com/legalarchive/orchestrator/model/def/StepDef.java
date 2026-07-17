package com.legalarchive.orchestrator.model.def;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Step eseguibile: lancia uno script PowerShell con parametri risolti. */
public class StepDef extends NodeDef {

    /** Path dello script/eseguibile (assoluto o relativo a orchestrator.scriptsDir). */
    public String script;

    /**
     * Runner: auto (default, dedotto dall'estensione), powershell, cmd, jar.
     * .ps1 -> powershell, .bat/.cmd -> cmd, .jar -> jar.
     */
    public String exec;

    /** Timeout in secondi (0 = default applicativo). */
    public int timeoutSec = 0;

    /** Numero di tentativi aggiuntivi in caso di exit code != 0. */
    public int retry = 0;

    /** Attesa in secondi tra i tentativi. */
    public int retryDelaySec = 30;

    /** Parametri passati allo script come -Nome 'Valore' (con ${var} risolte). */
    public Map<String, String> params = new LinkedHashMap<String, String>();

    /** Variabili di output attese dallo script (righe "##VAR nome=valore" su stdout). */
    public List<String> outputs = new ArrayList<String>();

    // ---- built-in step kinds (exec = sql | ifscopy | filecopy | setvar) ----
    /** Datasource id (sql, ifscopy). */
    public String datasource;
    /** SQL query (sql). */
    public String query;
    /** IFS source path on AS400 (ifscopy). */
    public String ifsPath;
    /** Local source directory (filecopy). */
    public String source;
    /** Destination directory (ifscopy, filecopy). */
    public String dest;
    /** Glob pattern, e.g. *.csv (ifscopy, filecopy). */
    public String pattern;
    /** filecopy mode: copy | move | list. */
    public String mode;
    /** Overwrite existing files (ifscopy). */
    public boolean overwrite;

    /** SKIP: execute as passthrough (copy the previous step output / landing_in into this step dir). */
    public boolean skip;
    /** Name of an output variable to collect a joined list (sql first column). */
    public String outputVar;
    /** sql: if set, the full result set is streamed to this CSV file. */
    public String csvFile;
    /** sql: split the CSV every N data rows (0 = no split). */
    public int csvSplitRows;
    /** sql: split the CSV every N megabytes (0 = no split). */
    public int csvSplitMb;
    /** validate: lista dei controlli selezionati. */
    public java.util.List<String> validateChecks = new java.util.ArrayList<String>();
    /** csvreplace: sostituzioni da applicare. */
    public java.util.List<Replacement> replacements = new java.util.ArrayList<Replacement>();
    /** csvsql: input CSVs + their table aliases. */
    public java.util.List<CsvInput> inputs = new java.util.ArrayList<CsvInput>();
    /** xlsx2csv: selected columns (src header/letter -> as output name). */
    public java.util.List<ColumnSel> columns = new java.util.ArrayList<ColumnSel>();
    /** Delimiter for joined list output vars (default ;). */
    public String delimiter;

    // ---- parallel fan-out ----
    /** If set, the step runs once per item of this resolved list; ${item} is exposed. */
    public String forEach;
    /** Max concurrent executions for forEach (default 4). */
    public int concurrency = 4;

    @Override
    public String getKind() { return "STEP"; }
}
