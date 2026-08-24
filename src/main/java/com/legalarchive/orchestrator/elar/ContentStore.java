package com.legalarchive.orchestrator.elar;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * Where the document embedded in {@code <ELAR:Content>} comes from.
 *
 * <b>Why this exists.</b> The payload used to be a {@code java.io.File} everywhere: resolved in the
 * pre-scan, measured for the byte budget, read twice by the embedder, and asked for its extension to
 * fill the DSAK. Reading it from an AS/400 IFS directory instead means none of those can assume a local
 * file - but it must not mean this package learns about JTOpen or Spring. This package compiles and runs
 * standalone under {@code javac --release 8}, which is what allows the whole executor to be executed
 * against real files in a test rather than only inspected, and that is not worth trading away.
 *
 * So the seam is here, in JDK terms only, and the IFS implementation lives in the layer that already
 * has JTOpen and the datasource registry.
 *
 * <b>Resolution belongs to the store.</b> {@link #resolve} turns a raw CSV value into whatever handle
 * this store understands, and the two implementations deliberately disagree, because the same column
 * serves two topologies. Locally the documents have been copied down by an {@code ifscopy} step, which
 * flattens whatever tree they came from into one directory, so only the file name is still meaningful.
 * Over IFS the tree is still there and the full path is the only thing that finds the file. Nothing
 * outside a store sees either rule: the pre-scan and the writer hold an opaque handle.
 *
 * <b>Ownership.</b> {@link ElarRun} closes the store when the run ends, on every path including the
 * failing ones, so an implementation holding a connection is released even when a delivery fails.
 * A store is therefore built per run and not shared between them.
 */
public interface ContentStore extends Closeable {

    /** A raw CSV value as this store reads it. Never null; the caller has already rejected blank values. */
    String resolve(String csvValue);

    /** Whether the resolved handle names a readable document - not a directory, not absent. */
    boolean exists(String resolved) throws IOException;

    /** Size in bytes, used for the batch byte budget. Undefined when {@link #exists} is false. */
    long length(String resolved) throws IOException;

    /**
     * Last modification time. Together with {@link #length} this is the stamp the embedder takes before
     * the digest pass and re-checks after the encode pass, so a document that changed underneath is
     * refused rather than delivered with a digest that does not match its bytes.
     */
    long lastModified(String resolved) throws IOException;

    /** A fresh stream over the document. The caller closes it. Called twice per document. */
    InputStream open(String resolved) throws IOException;

    /**
     * The last path segment. The DSAK is its extension in upper case, so this must be the real name of
     * the document rather than the raw column value.
     */
    String fileName(String resolved);

    /** Something an operator can act on: a full path, not just a name. For messages and findings. */
    String describe(String resolved);
}
