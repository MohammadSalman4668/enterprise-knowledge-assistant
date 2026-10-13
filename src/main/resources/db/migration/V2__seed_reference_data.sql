-- Reference data: access policy matrix and synthetic demo users.
-- Passwords are NOT seeded; DemoUserInitializer sets them from EKG_DEMO_PASSWORD at startup.
-- Seed documents are ingested through the real pipeline by SeedDataLoader (so they are
-- parsed, chunked and versioned exactly like uploads), see src/main/resources/seed/.

INSERT INTO access_policy (user_role, document_access_level) VALUES
    ('EMPLOYEE', 'PUBLIC'),
    ('EMPLOYEE', 'INTERNAL'),
    ('ENGINEER', 'PUBLIC'),
    ('ENGINEER', 'INTERNAL'),
    ('ENGINEER', 'CONFIDENTIAL'),
    ('HR',       'PUBLIC'),
    ('HR',       'INTERNAL'),
    ('HR',       'RESTRICTED'),
    ('ADMIN',    'PUBLIC'),
    ('ADMIN',    'INTERNAL'),
    ('ADMIN',    'CONFIDENTIAL'),
    ('ADMIN',    'RESTRICTED');

INSERT INTO app_user (username, display_name, role) VALUES
    ('employee', 'Emma Employee', 'EMPLOYEE'),
    ('engineer', 'Eli Engineer',  'ENGINEER'),
    ('hr',       'Hana HR',       'HR'),
    ('admin',    'Ada Admin',     'ADMIN');
