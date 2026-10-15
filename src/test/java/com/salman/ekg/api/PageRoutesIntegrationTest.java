package com.salman.ekg.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import com.salman.ekg.support.AbstractIntegrationTest;

/** Clean page URLs return the app shell; data endpoints stay protected. */
class PageRoutesIntegrationTest extends AbstractIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"/login", "/signup", "/ask", "/search", "/documents", "/documents/5", "/source/40", "/analytics", "/approvals", "/profile"})
    @DisplayName("Page URLs opened directly (or refreshed) return the single-page app without sign-in")
    void pagePathsServeAppShell(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
    }

    @Test
    @DisplayName("The app shell carries no data: API endpoints still require sign-in")
    void apiStillProtected() throws Exception {
        mvc.perform(get("/api/knowledge/documents")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }
}
