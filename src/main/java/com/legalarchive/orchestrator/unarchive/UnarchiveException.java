package com.legalarchive.orchestrator.unarchive;

import java.io.IOException;

/**
 * A refusal: the archive, or one of its entries, is something this executor will not extract.
 *
 * <p>It carries a {@link Rule} so that tests - and the step log - can tell WHICH rule refused,
 * not just that something did. The spec requires every hostile fixture to be refused by the rule
 * written for it, and a message alone cannot show that.
 *
 * <p>It is an {@link IOException} so it travels through stream code unchanged.
 */
public final class UnarchiveException extends IOException {

    private static final long serialVersionUID = 1L;

    /** Why something was refused. The names appear in the step log. */
    public enum Rule {
        // format detection (spec section 3)
        UNRECOGNISED, UNSUPPORTED_FORMAT, FORMAT_MISMATCH, EMPTY_FILE,
        // entry names (section 5)
        EMPTY_NAME, ABSOLUTE, DRIVE_LETTER, TRAVERSAL, DOT_SEGMENT, EMPTY_SEGMENT, COLON,
        INVALID_CHAR, TRAILING_DOT_OR_SPACE, RESERVED_NAME, SEGMENT_TOO_LONG, PATH_TOO_LONG,
        DUPLICATE, CASE_COLLISION, FILE_DIRECTORY_CONFLICT, DIRECTORY_CASE_MISMATCH,
        // tar structure (section 4.1)
        BAD_CHECKSUM, BAD_NUMBER, NEGATIVE_SIZE, TRUNCATED, BAD_EXTENDED_HEADER, AMBIGUOUS_NAME,
        UNSUPPORTED_ENTRY_TYPE, BAD_NAME_ENCODING, OVERSIZED_EXTENDED_HEADER,
        // zip (section 4.2)
        ZIP_STRUCTURE, ZIP_ENCRYPTED, ZIP_METHOD, BAD_CRC,
        // policy, limits, layout (sections 6-8), added in batch 2
        LINK_OR_SPECIAL, LIMIT_ENTRIES, LIMIT_ENTRY_SIZE, LIMIT_ARCHIVE_SIZE, LIMIT_RATIO, DISK_SPACE,
        CONFIGURATION, TARGET_EXISTS, SUBDIR_COLLISION, DONE_EXISTS, COMMIT_FAILED,
        // links on Linux hosts (section 21.6), added in batch L2
        LINK_ESCAPE, LINK_TARGET_MISSING, BAD_LINK_TARGET
    }

    private final Rule rule;

    public UnarchiveException(Rule rule, String message) {
        super(rule + ": " + message);
        this.rule = rule;
    }

    public Rule rule() {
        return rule;
    }
}
