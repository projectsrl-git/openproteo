package com.legalarchive.orchestrator.elar;

/**
 * A {@link ContentStore} whose documents may be deleted once they have been embedded.
 *
 * <b>Why a separate interface rather than a flag.</b> Only the local store implements it, and that is the
 * whole point: the documents under a family's {@code documentPath} are <b>copies</b>, put there by an
 * {@code ifscopy} step, so deleting one frees staging space and loses nothing. A document on the AS/400
 * IFS is the archive's own copy. Expressing the restriction in the type means {@link IfsContentStore}
 * cannot be asked to delete anything even by a misconfiguration - there is no method to call - instead of
 * relying on a check somewhere in {@link ElarRun} that a later edit could move or invert.
 *
 * <b>Ordering.</b> Nothing here says WHEN a document may be deleted. {@link ElarRun} answers that, and the
 * answer is: only once the INDX the document went into has reached its final deliverable name. Between
 * {@code writeDocument} and that moment there are three paths that discard everything the batch wrote - an
 * exception aborts it, the disk guard cuts the input in three, and an oversize document rolls the batch -
 * and a document deleted before the commit would be gone with no INDX to show for it.
 */
public interface DeletableContentStore extends ContentStore {

    /**
     * Deletes the document behind a resolved handle.
     *
     * @return whether the document is gone afterwards. A file that had already disappeared counts as
     *         gone: the caller asked for it not to be there, and it is not there. The distinction that
     *         matters to an operator is the file that is <b>still</b> there, which is the one reported.
     */
    boolean delete(String resolved);
}
