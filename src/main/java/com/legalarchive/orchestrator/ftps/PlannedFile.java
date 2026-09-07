package com.legalarchive.orchestrator.ftps;

/** One file in the plan, with everything the transport needs and nothing it does not. */
public final class PlannedFile {

    private final String name;
    private final long bytes;
    private final TransferMode transfer;
    private final String remoteDir;
    private final int maskIndex;
    private final int order;

    PlannedFile(String name, long bytes, TransferMode transfer, String remoteDir,
                int maskIndex, int order) {
        this.name = name;
        this.bytes = bytes;
        this.transfer = transfer;
        this.remoteDir = remoteDir;
        this.maskIndex = maskIndex;
        this.order = order;
    }

    public String name() {
        return name;
    }

    public long bytes() {
        return bytes;
    }

    public TransferMode transfer() {
        return transfer;
    }

    public String remoteDir() {
        return remoteDir;
    }

    /** Zero-based position of the mask that claimed this file. */
    public int maskIndex() {
        return maskIndex;
    }

    /** One-based position in the send order. */
    public int order() {
        return order;
    }
}
