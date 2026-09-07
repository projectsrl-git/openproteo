package com.legalarchive.orchestrator.ftps;

/**
 * One row of the ordered mask list: a file mask plus how and where its files are sent.
 *
 * <p>The list is ordered and the order is the operator's, edited with ADD, DELETE, UP and DOWN.
 * The executor deliberately does not know which file completes a delivery, because that belongs to
 * the receiving system and differs per feed.
 */
public final class SendMask {

    private final Glob glob;
    private final TransferMode transfer;
    private final String remoteDir;
    private final boolean optional;
    private final boolean enabled;

    public SendMask(String pattern, TransferMode transfer, String remoteDir,
                    boolean optional, boolean enabled) {
        this.glob = Glob.compile(pattern);
        this.transfer = transfer == null ? TransferMode.BINARY : transfer;
        this.remoteDir = remoteDir == null ? "" : remoteDir.trim();
        this.optional = optional;
        this.enabled = enabled;
    }

    /** A required, enabled, binary mask sending to the step's default remote directory. */
    public static SendMask of(String pattern) {
        return new SendMask(pattern, TransferMode.BINARY, "", false, true);
    }

    public String pattern() {
        return glob.pattern();
    }

    public boolean matches(String fileName) {
        return glob.matches(fileName);
    }

    public TransferMode transfer() {
        return transfer;
    }

    /** Empty means "use the remote directory configured on the step". */
    public String remoteDir() {
        return remoteDir;
    }

    /** When false, a mask matching no file fails the step. */
    public boolean optional() {
        return optional;
    }

    /** A parked mask: kept in the list, not evaluated, and never a reason to fail. */
    public boolean enabled() {
        return enabled;
    }

    @Override
    public String toString() {
        return glob.pattern();
    }
}
