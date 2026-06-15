package com.legalarchive.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Proprieta configurabili in application.properties con prefisso "orchestrator.".
 */
@ConfigurationProperties(prefix = "orchestrator")
public class AppProperties {

    /** Directory contenente le definizioni XML dei workflow. */
    private String workflowsDir = "./workflows";

    /** Directory base degli script PowerShell (per path relativi nelle definizioni). */
    private String scriptsDir = "./scripts";
    /** Directory dei file condivisi a livello applicazione (usabili da tutti i workflow). */
    private String sharedDir = "./shared";

    /** Base directory di default per i feed, usata se il workflow non specifica baseDir. */
    private String defaultBaseDir = "./feeds";

    /** Eseguibile PowerShell. Su server Windows: powershell.exe (o pwsh.exe per PS7). */
    private String powershellExe = "powershell.exe";

    /** Eseguibile Java per gli step .jar (deve essere nel PATH o path assoluto). */
    private String javaExe = "java";

    /** Interprete per gli step .bat/.cmd. */
    private String cmdExe = "cmd.exe";

    /** Timeout di default in secondi per uno step, se non specificato nella definizione. */
    private int defaultStepTimeoutSec = 1800;

    /** Numero massimo di transizioni di nodo per run (guardia anti-loop nei gate). */
    private int maxTransitions = 500;

    /** File JSON con le definizioni dei datasource (connessioni riusabili). */
    private String datasourcesFile = "./datasources.json";

    // --- anonymize (ARX) preflight thresholds — conservative fail-fast guards ---
    private long anonymizeMaxRows = 5_000_000L;        // 0 = no limit
    private long anonymizeMaxCells = 200_000_000L;     // 0 = no limit (rough proxy of load)
    private int anonymizeBytesPerCell = 64;            // conservative heap estimate per cell
    private int anonymizeHeapHeadroomMb = 256;         // require at least this much headroom after the estimate
    private int dateSampleSize = 200;                  // values sampled per column for date detection
    private double datePassthroughThreshold = 0.95;    // fraction of matching values to treat a column as date
    private int dateMinYear = 1900;
    private int dateMaxYear = 2099;

    // --- mask (deterministic streaming masking) ---
    private String maskingSecret = "";              // HMAC salt; MUST be set in external application.properties
    private String maskNormalize = "trimUpper";     // none | trim | trimUpper (collapses value variants for consistency)
    private String maskPoolsDir = "";               // optional external dir overriding bundled /maskdata pools

    public String getWorkflowsDir() { return workflowsDir; }
    public void setWorkflowsDir(String v) { this.workflowsDir = v; }
    public String getScriptsDir() { return scriptsDir; }
    public void setScriptsDir(String v) { this.scriptsDir = v; }
    public String getSharedDir() { return sharedDir; }
    public void setSharedDir(String v) { this.sharedDir = v; }
    public String getDefaultBaseDir() { return defaultBaseDir; }
    public void setDefaultBaseDir(String v) { this.defaultBaseDir = v; }
    public String getPowershellExe() { return powershellExe; }
    public void setPowershellExe(String v) { this.powershellExe = v; }
    public String getJavaExe() { return javaExe; }
    public void setJavaExe(String v) { this.javaExe = v; }
    public String getCmdExe() { return cmdExe; }
    public void setCmdExe(String v) { this.cmdExe = v; }
    public int getDefaultStepTimeoutSec() { return defaultStepTimeoutSec; }
    public void setDefaultStepTimeoutSec(int v) { this.defaultStepTimeoutSec = v; }
    public int getMaxTransitions() { return maxTransitions; }
    public void setMaxTransitions(int v) { this.maxTransitions = v; }
    public String getDatasourcesFile() { return datasourcesFile; }

    public long getAnonymizeMaxRows() { return anonymizeMaxRows; }
    public void setAnonymizeMaxRows(long v) { this.anonymizeMaxRows = v; }
    public long getAnonymizeMaxCells() { return anonymizeMaxCells; }
    public void setAnonymizeMaxCells(long v) { this.anonymizeMaxCells = v; }
    public int getAnonymizeBytesPerCell() { return anonymizeBytesPerCell; }
    public void setAnonymizeBytesPerCell(int v) { this.anonymizeBytesPerCell = v; }
    public int getAnonymizeHeapHeadroomMb() { return anonymizeHeapHeadroomMb; }
    public void setAnonymizeHeapHeadroomMb(int v) { this.anonymizeHeapHeadroomMb = v; }
    public int getDateSampleSize() { return dateSampleSize; }
    public void setDateSampleSize(int v) { this.dateSampleSize = v; }
    public double getDatePassthroughThreshold() { return datePassthroughThreshold; }
    public void setDatePassthroughThreshold(double v) { this.datePassthroughThreshold = v; }
    public int getDateMinYear() { return dateMinYear; }
    public void setDateMinYear(int v) { this.dateMinYear = v; }
    public int getDateMaxYear() { return dateMaxYear; }
    public void setDateMaxYear(int v) { this.dateMaxYear = v; }

    public String getMaskingSecret() { return maskingSecret; }
    public void setMaskingSecret(String v) { this.maskingSecret = v; }
    public String getMaskNormalize() { return maskNormalize; }
    public void setMaskNormalize(String v) { this.maskNormalize = v; }

    public String getMaskPoolsDir() { return maskPoolsDir; }
    public void setMaskPoolsDir(String v) { this.maskPoolsDir = v; }
    public void setDatasourcesFile(String v) { this.datasourcesFile = v; }
}
