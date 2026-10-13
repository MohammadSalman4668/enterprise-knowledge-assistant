package com.salman.ekg.retrieval;

import com.salman.ekg.domain.AccessLevel;

/** A ranked passage with everything needed to cite it (document, version, section/page/chunk). */
public record RetrievedChunk(
        long chunkId,
        long documentId,
        String title,
        String category,
        AccessLevel accessLevel,
        int version,
        int chunkIndex,
        String sectionRef,
        Integer pageNumber,
        String content,
        double score) {

    /** Human-readable location used in citations, e.g. "§ Carry-over · chunk 3". */
    public String locationLabel() {
        StringBuilder sb = new StringBuilder();
        if (sectionRef != null) {
            sb.append("§ ").append(sectionRef);
        }
        if (pageNumber != null) {
            sb.append(sb.isEmpty() ? "" : " · ").append("p. ").append(pageNumber);
        }
        sb.append(sb.isEmpty() ? "" : " · ").append("chunk ").append(chunkIndex + 1);
        return sb.toString();
    }
}
