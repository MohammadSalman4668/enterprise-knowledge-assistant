package com.salman.ekg.retrieval;

import com.salman.ekg.domain.AccessLevel;

/**
 * A ranked passage with everything needed to cite it (document, version, section/page/chunk).
 *
 * @param score      relevance from the retriever (keyword rank, or similarity for semantic-only hits)
 * @param similarity meaning similarity to the query (cosine), or -1 when not computed
 * @param matchedBy  how it was found: KEYWORD, SEMANTIC or KEYWORD+SEMANTIC
 */
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
        double score,
        double similarity,
        String matchedBy) {

    public static final String KEYWORD = "KEYWORD";
    public static final String SEMANTIC = "SEMANTIC";

    /** Keyword hit (no similarity computed). */
    public RetrievedChunk(long chunkId, long documentId, String title, String category, AccessLevel accessLevel,
                          int version, int chunkIndex, String sectionRef, Integer pageNumber, String content, double score) {
        this(chunkId, documentId, title, category, accessLevel, version, chunkIndex, sectionRef, pageNumber, content,
                score, -1, KEYWORD);
    }

    public boolean hasSimilarity() {
        return similarity >= 0;
    }

    RetrievedChunk withMatch(double newScore, double newSimilarity, String newMatchedBy) {
        return new RetrievedChunk(chunkId, documentId, title, category, accessLevel, version, chunkIndex, sectionRef,
                pageNumber, content, newScore, newSimilarity, newMatchedBy);
    }

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
