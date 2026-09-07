package com.legalarchive.orchestrator.model.def;

/**
 * ftpsend: one row of the ordered mask list.
 *
 * <p>The list is ordered and the order is the operator's, edited with ADD, DELETE, UP and DOWN.
 * The executor deliberately does not decide which file completes a delivery: file sets differ per
 * feed and the completion semantics belong to the receiving system.
 */
public class SendSpec {

    /** DOS/UNIX file mask: * and ? only, matched on the file name. */
    public String pattern;

    /** BINARY (default) or ASCII. */
    public String transfer;

    /** Overrides the step's remote directory for the files this mask claims. */
    public String remoteDir;

    /** When false (the default), a mask matching no file fails the step. */
    public boolean optional;

    /** A parked mask: kept in the list, not evaluated, never a reason to fail. */
    public boolean enabled = true;
}
