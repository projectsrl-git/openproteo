package com.legalarchive.orchestrator.json2csv;

/**
 * Anything this executor refuses, with a message written for whoever configured the step.
 *
 * <p>Unchecked on purpose: every one of these is a refusal that should reach the step log intact,
 * and a checked exception threaded through the row loop would invite a catch that swallows it.
 *
 * <p><b>No field value is ever put in one of these messages.</b> They carry column names, paths,
 * file names, positions and counts — the elarcheck rule, for the same reason: the step log is not a
 * place where a banking document's content may end up.
 */
public class Json2CsvException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public Json2CsvException(String message) { super(message); }
    public Json2CsvException(String message, Throwable cause) { super(message, cause); }
}
