package com.salman.ekg.domain;

/** State of one stored content revision of a document. */
public enum RevisionStatus {
    /** Submitted but not yet re-indexed; never served. */
    PENDING,
    /** The revision whose chunks are currently searchable. */
    CURRENT,
    /** Replaced by a newer revision; kept for audit only. */
    SUPERSEDED
}
