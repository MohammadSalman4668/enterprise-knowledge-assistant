-- Reference data: the access policy matrix.
-- No user accounts are seeded: the first Admin is created at startup from configuration
-- (AdminBootstrap, values in the git-ignored local.properties); everyone else signs up.
-- Seed documents are ingested through the real pipeline by SeedDataLoader (so they are
-- parsed, chunked and versioned exactly like uploads), see src/main/resources/seed/.

INSERT INTO access_policy (user_role, document_access_level) VALUES
    ('EMPLOYEE', 'PUBLIC'),
    ('EMPLOYEE', 'INTERNAL'),
    ('HR',       'PUBLIC'),
    ('HR',       'INTERNAL'),
    ('HR',       'RESTRICTED'),
    ('ADMIN',    'PUBLIC'),
    ('ADMIN',    'INTERNAL'),
    ('ADMIN',    'CONFIDENTIAL'),
    ('ADMIN',    'RESTRICTED');
