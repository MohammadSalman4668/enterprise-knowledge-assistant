package com.salman.ekg.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Document lifecycle:
 * <pre>
 * upload ─► PENDING_APPROVAL ─approve─► ACTIVE ─submit revision─► NEEDS_REINDEX ─reindex─► ACTIVE
 *                 └─reject─► REJECTED              any non-archived state ─archive─► ARCHIVED
 * </pre>
 * Only ACTIVE and NEEDS_REINDEX documents are searchable; a NEEDS_REINDEX document
 * keeps serving its current approved version until the revision is re-indexed.
 */
public enum DocumentStatus {
    PENDING_APPROVAL,
    ACTIVE,
    NEEDS_REINDEX,
    REJECTED,
    ARCHIVED;

    public static final Set<DocumentStatus> SEARCHABLE = EnumSet.of(ACTIVE, NEEDS_REINDEX);

    public boolean isSearchable() {
        return SEARCHABLE.contains(this);
    }
}
