package com.salman.ekg.retrieval;

import java.util.List;

import com.salman.ekg.domain.AccessLevel;
import com.salman.ekg.domain.Role;

/**
 * A retrieval strategy. Implementations are tried in {@code @Order}: a semantic/vector
 * retriever (when configured) first, the database keyword retriever last as the
 * always-available fallback. Every implementation must apply the role's access policy
 * itself; {@link RetrievalService} then re-validates the results independently.
 */
public interface Retriever {

    String name();

    List<RetrievedChunk> retrieve(Query query);

    record Query(String text, Role role, String category, AccessLevel accessLevel, int limit) {}
}
