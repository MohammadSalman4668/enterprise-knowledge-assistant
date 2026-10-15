package com.salman.ekg.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;
import com.salman.ekg.security.AdminBootstrap;
import com.salman.ekg.service.SeedDataLoader;

import tools.jackson.databind.ObjectMapper;

/**
 * Base for API + persistence integration tests. Runs the full application against the
 * real PostgreSQL test database ({@code ekg_test}); every test starts from a freshly
 * migrated schema and the seeded synthetic corpus, so tests are independent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeEmbeddingConfig.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper mapper;

    @Autowired
    private Flyway flyway;

    @Autowired
    private SeedDataLoader seeder;

    @Autowired
    private AdminBootstrap adminBootstrap;

    @BeforeEach
    void resetDatabase() {
        flyway.clean();
        flyway.migrate();
        adminBootstrap.run(null);
        seeder.seed();
    }

    protected static RequestPostProcessor as(String role) {
        return user(role.toLowerCase()).roles(role);
    }

    protected static final RequestPostProcessor EMPLOYEE = as("EMPLOYEE");

    protected static final RequestPostProcessor HR = as("HR");
    protected static final RequestPostProcessor ADMIN = as("ADMIN");

    protected MockHttpServletRequestBuilder postJson(String url, Object body) {
        return MockMvcRequestBuilders.post(url).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body));
    }

    protected static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content.getBytes(StandardCharsets.UTF_8));
    }

    protected static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content);
    }

    /** Multipart upload request with metadata fields. */
    protected static MockMultipartHttpServletRequestBuilder upload(MockMultipartFile file, String title, String category, String level) {
        return MockMvcRequestBuilders.multipart("/api/knowledge/documents").file(file)
                .param("title", title).param("category", category).param("accessLevel", level);
    }

    protected static <T> T json(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), path);
    }

    protected static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    protected Map<String, Object> ask(String question, RequestPostProcessor who) throws Exception {
        MvcResult r = mvc.perform(postJson("/api/knowledge/ask", Map.of("question", question)).with(who)).andReturn();
        if (r.getResponse().getStatus() != 200) {
            throw new AssertionError("ask failed: " + r.getResponse().getStatus() + " " + body(r));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = mapper.readValue(body(r), Map.class);
        return map;
    }
}
