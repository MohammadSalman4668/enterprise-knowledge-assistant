package com.salman.ekg.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.salman.ekg.config.KnowledgeProperties;
import com.salman.ekg.domain.AccessLevel;
import com.salman.ekg.domain.AppUser;
import com.salman.ekg.domain.KnowledgeDocument;
import com.salman.ekg.domain.Role;
import com.salman.ekg.repository.AppUserRepository;
import com.salman.ekg.repository.KnowledgeDocumentRepository;
import com.salman.ekg.security.CurrentUser;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads the synthetic seed corpus on first start (empty document table). Documents go
 * through the real upload → approve pipeline, so seed data is parsed, chunked and
 * versioned exactly like a user upload.
 */
@Component
@Order(2)
public class SeedDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataLoader.class);

    record SeedEntry(String file, String title, String category, AccessLevel accessLevel, String owner) {}

    private final DocumentService documentService;
    private final KnowledgeDocumentRepository documents;
    private final AppUserRepository users;
    private final ObjectMapper mapper;
    private final KnowledgeProperties props;

    public SeedDataLoader(DocumentService documentService, KnowledgeDocumentRepository documents,
                          AppUserRepository users, ObjectMapper mapper, KnowledgeProperties props) {
        this.documentService = documentService;
        this.documents = documents;
        this.users = users;
        this.mapper = mapper;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (props.seed() != null && props.seed().enabled() && documents.count() == 0) {
            int loaded = seed();
            log.info("Seeded {} synthetic documents", loaded);
        }
    }

    /** Public so integration tests can restore a known corpus after resetting the database. */
    public int seed() {
        CurrentUser admin = new CurrentUser("admin", Role.ADMIN);
        List<SeedEntry> entries;
        try (InputStream in = new ClassPathResource("seed/manifest.json").getInputStream()) {
            entries = mapper.readValue(in, new TypeReference<List<SeedEntry>>() { });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed manifest", e);
        }
        for (SeedEntry entry : entries) {
            Role ownerRole = users.findByUsername(entry.owner()).map(AppUser::getRole).orElse(Role.ADMIN);
            byte[] bytes;
            try (InputStream in = new ClassPathResource("seed/docs/" + entry.file()).getInputStream()) {
                bytes = in.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read seed document " + entry.file(), e);
            }
            KnowledgeDocument doc = documentService.upload(new DocumentService.UploadCommand(entry.title(),
                    entry.category(), entry.accessLevel(), entry.file(), bytes),
                    new CurrentUser(entry.owner(), ownerRole));
            documentService.approve(doc.getId(), admin);
        }
        return entries.size();
    }
}
