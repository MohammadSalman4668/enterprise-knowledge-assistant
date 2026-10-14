package com.salman.ekg.embedding;

import java.util.List;

/** Turns texts into embedding vectors (one per input, same order). Implementations may throw on failure. */
public interface EmbeddingClient {

    List<float[]> embed(List<String> texts);
}
