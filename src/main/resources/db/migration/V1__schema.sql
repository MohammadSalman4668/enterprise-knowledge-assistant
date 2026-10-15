-- =====================================================================
-- Enterprise Knowledge Assistant - schema (PostgreSQL 14+)
-- Enum-like columns are VARCHAR + CHECK so they map 1:1 to Java enums
-- (EnumType.STRING) and stay readable in ad-hoc SQL.
-- =====================================================================

-- ---------- Identity & access ---------------------------------------
-- Accounts. Users sign up (status PENDING) and sign in with their email once approved:
-- Employee requests are decided by HR, HR and Admin requests by an Admin. The first Admin
-- is created at startup from configuration (AdminBootstrap); no account is seeded here.
CREATE TABLE app_user (
    id            BIGSERIAL    PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,      -- sign-in id, stored lower-case
    display_name  VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,      -- BCrypt
    role          VARCHAR(16)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    decided_by    VARCHAR(254),               -- email of the approver / rejecter
    decided_at    TIMESTAMPTZ,
    CONSTRAINT uk_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_email_lower CHECK (email = lower(email)),
    CONSTRAINT ck_app_user_role CHECK (role IN ('EMPLOYEE', 'HR', 'ADMIN')),
    CONSTRAINT ck_app_user_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_app_user_decision CHECK ((status = 'PENDING') = (decided_at IS NULL))
);
CREATE INDEX idx_app_user_status_role ON app_user (status, role);

-- Which role may read which access level. Not hierarchical on purpose:
-- HR can read RESTRICTED (people data) but not CONFIDENTIAL runbooks, which only Admin can read.
CREATE TABLE access_policy (
    id                    BIGSERIAL   PRIMARY KEY,
    user_role             VARCHAR(16) NOT NULL,
    document_access_level VARCHAR(16) NOT NULL,
    CONSTRAINT uk_access_policy UNIQUE (user_role, document_access_level),
    CONSTRAINT ck_access_policy_role  CHECK (user_role IN ('EMPLOYEE', 'HR', 'ADMIN')),
    CONSTRAINT ck_access_policy_level CHECK (document_access_level IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'))
);

-- ---------- Documents -------------------------------------------------
CREATE TABLE knowledge_document (
    id              BIGSERIAL    PRIMARY KEY,
    title           VARCHAR(200) NOT NULL,
    category        VARCHAR(64)  NOT NULL,
    owner           VARCHAR(64)  NOT NULL,
    access_level    VARCHAR(16)  NOT NULL,
    current_version INT          NOT NULL DEFAULT 1,
    status          VARCHAR(20)  NOT NULL,
    file_name       VARCHAR(255) NOT NULL,
    content_type    VARCHAR(100) NOT NULL,
    content_hash    VARCHAR(64)  NOT NULL,     -- SHA-256 of normalised text of the current version
    uploaded_at     TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    lock_version    BIGINT       NOT NULL DEFAULT 0,   -- JPA @Version (optimistic locking)
    CONSTRAINT ck_doc_access_level CHECK (access_level IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')),
    CONSTRAINT ck_doc_status CHECK (status IN ('PENDING_APPROVAL', 'ACTIVE', 'NEEDS_REINDEX', 'REJECTED', 'ARCHIVED')),
    CONSTRAINT ck_doc_version CHECK (current_version >= 1),
    CONSTRAINT ck_doc_title_not_blank CHECK (length(btrim(title)) > 0)
);
-- Filters used by GET /documents and the "grouped by category and status" report.
CREATE INDEX idx_doc_category_status ON knowledge_document (lower(category), status);
CREATE INDEX idx_doc_access_level    ON knowledge_document (access_level);
-- The same content cannot be live twice. The service checks first for a friendly 409;
-- this partial unique index is the race-proof guarantee for concurrent uploads.
CREATE UNIQUE INDEX uk_doc_live_content_hash ON knowledge_document (content_hash)
    WHERE status NOT IN ('ARCHIVED', 'REJECTED');

-- Every revision of a document's extracted text (audit + re-index source).
CREATE TABLE document_version (
    id           BIGSERIAL    PRIMARY KEY,
    document_id  BIGINT       NOT NULL REFERENCES knowledge_document (id) ON DELETE CASCADE,
    version      INT          NOT NULL,
    content_hash VARCHAR(64)  NOT NULL,
    raw_text     TEXT         NOT NULL,
    file_name    VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    status       VARCHAR(16)  NOT NULL,
    created_by   VARCHAR(64)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_document_version UNIQUE (document_id, version),
    CONSTRAINT ck_document_version_status CHECK (status IN ('PENDING', 'CURRENT', 'SUPERSEDED')),
    CONSTRAINT ck_document_version_positive CHECK (version >= 1)
);
-- At most one pending revision and exactly one current revision per document.
CREATE UNIQUE INDEX uk_document_version_one_pending ON document_version (document_id) WHERE status = 'PENDING';
CREATE UNIQUE INDEX uk_document_version_one_current ON document_version (document_id) WHERE status = 'CURRENT';

-- Retrievable passages. Each chunk keeps its source document AND version.
CREATE TABLE knowledge_chunk (
    id              BIGSERIAL    PRIMARY KEY,
    document_id     BIGINT       NOT NULL REFERENCES knowledge_document (id) ON DELETE CASCADE,
    version         INT          NOT NULL,
    chunk_index     INT          NOT NULL,
    section_ref     VARCHAR(200),
    page_number     INT,
    content         TEXT         NOT NULL,
    searchable_text TEXT         NOT NULL,
    embedding_ref   VARCHAR(128),
    superseded      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL,
    -- Keyword index maintained by the database itself, so it can never drift from the text.
    search_vector   TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', searchable_text)) STORED,
    CONSTRAINT uk_chunk_position UNIQUE (document_id, version, chunk_index),
    CONSTRAINT ck_chunk_index CHECK (chunk_index >= 0),
    CONSTRAINT ck_chunk_content_not_blank CHECK (length(btrim(content)) > 0),
    CONSTRAINT ck_chunk_page CHECK (page_number IS NULL OR page_number >= 1)
);
CREATE INDEX idx_chunk_search_vector ON knowledge_chunk USING GIN (search_vector);
CREATE INDEX idx_chunk_live ON knowledge_chunk (document_id, version) WHERE superseded = FALSE;

-- ---------- Search & agent audit ---------------------------------------
CREATE TABLE search_log (
    id                BIGSERIAL     PRIMARY KEY,
    username          VARCHAR(64)   NOT NULL,
    user_role         VARCHAR(16)   NOT NULL,
    query             VARCHAR(1000) NOT NULL,
    category_filter   VARCHAR(64),
    resolved_category VARCHAR(64),
    result_count      INT           NOT NULL,
    retrieval_mode    VARCHAR(16)   NOT NULL,
    source            VARCHAR(8)    NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_search_log_source CHECK (source IN ('SEARCH', 'AGENT')),
    CONSTRAINT ck_search_log_count CHECK (result_count >= 0)
);
CREATE INDEX idx_search_log_created  ON search_log (created_at);
CREATE INDEX idx_search_log_category ON search_log (resolved_category);

CREATE TABLE agent_run (
    id         BIGSERIAL     PRIMARY KEY,
    username   VARCHAR(64)   NOT NULL,
    user_role  VARCHAR(16)   NOT NULL,
    question   VARCHAR(1000) NOT NULL,
    answer     TEXT          NOT NULL,
    outcome    VARCHAR(16)   NOT NULL,
    mode       VARCHAR(16)   NOT NULL,
    degraded   BOOLEAN       NOT NULL,
    trace_json TEXT          NOT NULL,
    latency_ms BIGINT        NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_agent_run_outcome CHECK (outcome IN ('ANSWERED', 'PARTIAL', 'CONFLICT', 'ABSTAINED')),
    CONSTRAINT ck_agent_run_mode CHECK (mode IN ('SIMULATED', 'LIVE'))
);
CREATE INDEX idx_agent_run_user ON agent_run (username, created_at);

CREATE TABLE agent_citation (
    id               BIGSERIAL   PRIMARY KEY,
    agent_run_id     BIGINT      NOT NULL REFERENCES agent_run (id) ON DELETE CASCADE,
    ref_label        VARCHAR(8)  NOT NULL,
    chunk_id         BIGINT      NOT NULL REFERENCES knowledge_chunk (id),
    document_id      BIGINT      NOT NULL REFERENCES knowledge_document (id),
    document_version INT         NOT NULL,
    section_ref      VARCHAR(200),
    CONSTRAINT uk_agent_citation UNIQUE (agent_run_id, ref_label)
);
CREATE INDEX idx_agent_citation_document ON agent_citation (document_id);

-- ---------- Feedback ---------------------------------------------------
CREATE TABLE knowledge_feedback (
    id           BIGSERIAL     PRIMARY KEY,
    agent_run_id BIGINT        NOT NULL REFERENCES agent_run (id) ON DELETE CASCADE,
    document_id  BIGINT        REFERENCES knowledge_document (id),
    username     VARCHAR(64)   NOT NULL,
    question     VARCHAR(1000) NOT NULL,
    rating       INT           NOT NULL,
    comment      VARCHAR(1000),
    created_at   TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_feedback_rating CHECK (rating BETWEEN 1 AND 5),
    -- One rating per user per answer: a double-click or retry cannot create duplicates.
    CONSTRAINT uk_feedback_run_user UNIQUE (agent_run_id, username)
);
CREATE INDEX idx_feedback_document ON knowledge_feedback (document_id);
