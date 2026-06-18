# 2026-06-17 — ##VAR script doc + dequote executor

## #1 doc: publishing ##VAR from a script
USAGE/README now has a "Publishing output variables from a script (##VAR)" subsection: print
`##VAR name=value` to stdout (marker "##VAR ", split on first '='); the engine exposes it as
${name} and ${<stepId>.name}, and ##VAR outputFile=... also sets ${<stepId>.outputFile}.
PowerShell: Write-Output "##VAR outputFile=$out" (or [Console]::Out.WriteLine). Same for
.bat (echo) and .sh. Mechanism confirmed in StepExecutor (VAR_MARKER) + WorkflowEngine
(global + namespaced + canonical outputFile).

## #2 dequote executor (exec="dequote")
Reads an input CSV and writes an output CSV with double quotes (escaped "" or literal ")
stripped from the chosen text columns. parseCsv() removes wrapping quotes and unescapes "",
then remaining " are removed from targeted columns; output re-quoted (rfcField) only when a
field still contains the delimiter/newline, or never with quoteIfNeeded=false. Params: source,
outFile (default <name>_dequoted), delimiter (empty=sniff), hasHeader (default true), columns
(names or 1-based indexes; empty=all), bom, quoteIfNeeded. Outputs outputFile/dataRows/
columns/cells/quotesRemoved. Engine internal list + dispatch; designer option+branch+validate.
Tested: escaped+unescaped quotes, delimiter-containing field re-quoted, selective columns,
never-quote mode. UTF-8 CRLF output.

76 classes compile; designer JS OK; no \n/\r; Thymeleaf-safe.
