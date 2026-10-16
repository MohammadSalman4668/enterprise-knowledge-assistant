# Enterprise Knowledge Assistant (RAG)

An internal knowledge portal where employees search approved policy documents, runbooks and FAQs.
An AI research agent retrieves evidence, writes an answer **grounded only in that evidence**, cites
its sources, and **abstains** when the evidence is missing. Every business rule (permissions,
document lifecycle, evidence sufficiency, citation checks) is enforced in Java; the AI model is only
trusted to choose searches and phrase the answer.

| | |
|---|---|
| Backend | Java 21, Spring Boot 4.1, Spring Data JPA, Spring Security, Flyway |
| Database | PostgreSQL 14+ (full-text `tsvector` + embedding vectors as `REAL[]`) |
| AI | [Ollama](https://ollama.com) (free, local): `qwen2.5:3b` for answers, `nomic-embed-text` for semantic search |
| Frontend | HTML5, CSS3, plain JavaScript ES modules (no framework, no build step) |
| Tests | JUnit 5, Mockito, Spring MockMvc, integration tests on a real PostgreSQL test database |

---

## Contents
1. [Features](#1-features)
2. [Prerequisites](#2-prerequisites)
3. [Setup](#3-setup)
4. [Run](#4-run)
5. [Architecture](#5-architecture)
6. [Data model](#6-data-model)
7. [API reference](#7-api-reference)
8. [UI screens](#8-ui-screens)
9. [SQL tasks](#9-sql-tasks)
10. [Tests](#10-tests)
11. [Configuration](#11-configuration)
12. [Project structure](#12-project-structure)
13. [AI usage log](#13-ai-usage-log)
14. [Limitations](#14-limitations)

---

## 1. Features

- **Document ingestion**: upload `.txt`, `.md` or `.pdf` (max 5 MB). Files are validated (type, content
  matches extension, not empty), split into chunks that keep their **document, version, section and
  page**, and indexed for keyword and semantic search.
- **Document lifecycle with an approval gate**: `PENDING_APPROVAL → ACTIVE → NEEDS_REINDEX → ACTIVE`,
  plus `REJECTED` and `ARCHIVED`. HR uploads need admin approval; admin uploads are live immediately.
  A revision is stored as *pending* and the approved version keeps being served until an admin re-indexes,
  so an obsolete version is never presented as current.
- **Hybrid search**: PostgreSQL full-text search and semantic (embedding) search run in parallel and
  are merged with Reciprocal Rank Fusion. Filters: category, access level.
- **Agentic RAG**: a goal-driven research agent (plan → research with controlled tools → evidence gate →
  conflict check → synthesis → citation validation → record), with a full, inspectable trace.
- **Permissions everywhere**: role-based access is applied **inside the SQL** of every search, re-checked
  afterwards, and enforced on every read (search, answers, source preview, document details).
- **Prompt-injection defence**: retrieved text is treated as data; instruction-like text is neutralised
  before any model sees it and can never change roles, tools or permissions.
- **Feedback and analytics**: 1–5 star ratings linked to the answer and document; admin dashboard
  with the five required SQL reports.
- **Accounts**: email sign-in, self sign-up with approval (Employee requests are approved by HR,
  HR and Admin requests by an Admin), and a profile page.
- **Fallbacks**: no embedding model → keyword search; no AI model → clearly labelled deterministic
  simulation; every retriever down → clear `503` error.

## 2. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21+ | `java -version` |
| PostgreSQL | 14+ | tested with 18 |
| Ollama | latest | optional: without it the app runs in the labelled simulation mode |
| Maven | not needed | the Maven wrapper (`mvnw` / `mvnw.cmd`) is included |
| Browser | any modern browser | |

## 3. Setup

### 3.1 Database
Create the application database and a separate test database (the tests wipe theirs on every run):

```sql
CREATE DATABASE ekg;
CREATE DATABASE ekg_test;
```

Optionally use `db/setup-local-db.sql`, which also creates a dedicated `ekg_app` login role.
Tables, constraints, indexes and reference data are created automatically by **Flyway**
(`src/main/resources/db/migration`) when the application starts.

### 3.2 Local settings (secrets are never committed)
Create `local.properties` in the project root. It is git-ignored.

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/ekg
spring.datasource.username=postgres
spring.datasource.password=<your-db-password>

# The first Admin, created at startup (approves HR and Admin sign-ups)
ekg.admin.name=<Admin Name>
ekg.admin.email=<admin@example.com>
ekg.admin.password=<a strong password>
```

### 3.3 AI models (free, local)
Install Ollama, then pull the two models:

```bash
ollama pull qwen2.5:3b
ollama pull nomic-embed-text
```

To run without any AI model, add `ekg.llm.provider=simulated` to `local.properties`. Answers are then
produced by deterministic Java code and labelled **"Simulated agent"** in the UI.

## 4. Run

```bash
./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run
```

Open **http://localhost:8080** and sign in with the admin account from `local.properties`.

On first start the app migrates the schema, creates the admin account and loads **10 synthetic seed
documents** (`src/main/resources/seed`) through the real ingestion pipeline. Semantic vectors are
computed in the background; until then search runs keyword-only and says so.

Other users: open **/signup**, request an account, and approve it as Admin (or as HR for Employees)
on the **Approvals** screen.

## 5. Architecture

### 5.1 Layers

```
Browser (HTML/CSS/JS, History-API router)
   │  REST + JSON, HTTP Basic (stateless)
   ▼
controller/   REST endpoints, request validation, no business logic
service/      business rules: lifecycle, approvals, feedback, accounts   (@Transactional)
agent/        research agent, tools, evidence gate, validator, conflict detector
retrieval/    keyword + semantic retrievers, fusion, permission re-check
ingestion/    file parsing (PDFBox) and structure-aware chunking
embedding/    Ollama embedding client, vector storage, background indexer
repository/   Spring Data JPA + JDBC queries
domain/       JPA entities and enums
security/     authentication, roles, bootstrap admin
exception/    ApiException + one global handler → consistent error JSON
```

Frontend: plain ES modules were chosen over a framework to keep every line explainable with no build
step. All page content is built with DOM APIs (never `innerHTML`), so document text cannot inject markup.

### 5.2 Agent workflow (`POST /api/knowledge/ask`)

```
PLAN        split the question into sub-questions (goals)                       QueryPlanner
RESEARCH    the model requests tool calls; Java decides if they run             OllamaEvidencePlanner + AgentToolbox
              search_knowledge(query, category)  → permission-filtered hits
              read_chunk(chunk_id)               → only chunks this run's searches returned
GATE        per goal: is there enough evidence? (word coverage ≥ 0.6           EvidenceGate
              or meaning similarity ≥ 0.635). No evidence for any goal → abstain, no model call
CONFLICT    compare figures across documents (e.g. two hotel caps)              ConflictDetector
SYNTHESIZE  the model writes the answer from the gated evidence only            OllamaSynthesizer
VALIDATE    Java drops uncited sentences, unknown citations, unsupported        CitationValidator
              claims and invented numbers; one repair attempt, then an
              extractive answer quoting the evidence
RECORD      run, citations and the full trace are stored                        AgentRunService
```

**Model integration (Spring AI equivalent).** The agent talks to the model through a small hand-written
adapter, `OllamaClient` (about 100 lines on Spring's `RestClient` and the OpenAI-compatible
`/v1/chat/completions` API), instead of Spring AI. This keeps tool execution fully under Java control
(the model only *requests* a tool call; `AgentToolbox` decides whether it runs), keeps time-outs and
fallbacks explicit, and adds no extra dependency. Moving to Spring AI's `ChatClient` would only replace
`OllamaClient`; the workflow, tools and validation stay the same. The same adapter works with any
OpenAI-compatible provider.

Controls enforced in Java: an allow-list of two read-only tools, the caller's identity taken from the
security context (never from tool arguments), a tool-call budget, a model time-out, and sanitisation of
every passage by `InjectionGuard`. If the model fails or times out, the deterministic planner and the
extractive synthesizer take over and the answer is marked **Degraded**.

### 5.3 Retrieval
- **Keyword**: `tsvector` column generated by the database (always in sync) with a GIN index, `ts_rank_cd`.
- **Semantic**: `nomic-embed-text` vectors (768 dimensions, L2-normalised) stored as `REAL[]`; cosine
  similarity computed in Java over the permission-filtered candidates.
- Both retrievers apply the role's access policy, document status (`ACTIVE`/`NEEDS_REINDEX`) and current
  version **inside the SQL**; `RetrievalService` re-validates every hit against the database.
- Results are fused with Reciprocal Rank Fusion (k = 60) plus a lexical-coverage ranking.

### 5.4 Permissions

| Role | Can read | Can do |
|---|---|---|
| EMPLOYEE | PUBLIC, INTERNAL | search, ask, rate answers |
| HR | PUBLIC, INTERNAL, RESTRICTED | + upload (admin approval needed), approve Employee sign-ups |
| ADMIN | all levels incl. CONFIDENTIAL | + approve/reject/re-index/archive documents, approve HR/Admin sign-ups, analytics |

The mapping lives in the `access_policy` table (single source of truth). Documents a user may not read
are reported as **404 Not Found**, so their existence is not revealed.

### 5.5 Transactions, duplicates and concurrency
- Every state change (upload, approve, revise, re-index, archive) is one transaction: a document is never
  left half-indexed, and old chunks are superseded in the same transaction that writes the new ones.
- Optimistic locking (`@Version`) rejects a second concurrent writer with `409 CONCURRENT_MODIFICATION`.
- Database constraints are the final guarantee: unique live content hash (no duplicate uploads), at most
  one pending and exactly one current revision per document, one rating per user per answer.
- Sign-up approval uses a conditional update, so two approvers cannot both decide the same request.

## 6. Data model

Migrations: `V1__schema.sql` (schema), `V2__seed_reference_data.sql` (access policy), `V3__chunk_embeddings.sql`.

| Table | Purpose | Keys, constraints, indexes |
|---|---|---|
| `app_user` | accounts (email, BCrypt hash, role, status) | unique lower-case email; status/decision consistency check |
| `access_policy` | role → readable access level | unique (role, level) |
| `knowledge_document` | title, category, owner, access level, current version, status, file, hash | status/level checks; partial unique index on live content hash; `@Version` lock column |
| `document_version` | every revision's text (audit + re-index source) | unique (document, version); one PENDING and one CURRENT per document |
| `knowledge_chunk` | passages with document, **version**, section, page, chunk index | FK to document; unique position; generated `tsvector` + GIN index; partial index on live chunks |
| `chunk_embedding` | one vector per chunk, tagged with model and dimensions | PK chunk; length check |
| `search_log`, `agent_run`, `agent_citation` | search analytics, answers with full trace and citations | indexes for reports |
| `knowledge_feedback` | rating 1–5 + comment, linked to answer and document | unique (answer, user) |

## 7. API reference

All `/api/**` endpoints except sign-up require HTTP Basic authentication (email + password).

| Method | Path | Role | Purpose |
|---|---|---|---|
| POST | `/api/auth/signup` | public | request an account (status PENDING) |
| GET | `/api/knowledge/me` | any | profile of the signed-in user |
| GET | `/api/users/pending` | HR, ADMIN | sign-up requests this approver may decide |
| POST | `/api/users/{id}/approve` · `/reject` | HR, ADMIN | decide a sign-up request |
| POST | `/api/knowledge/documents` (multipart) | HR, ADMIN | upload `file`, `title`, `category`, `accessLevel` |
| GET | `/api/knowledge/documents?category=&status=&accessLevel=&page=&size=` | any | list visible documents |
| GET | `/api/knowledge/documents/{id}` | any | details, live chunks, version history |
| GET | `/api/knowledge/chunks/{chunkId}` | any | source preview of one passage |
| POST | `/api/knowledge/documents/{id}/approve` · `/reject` · `/archive` | ADMIN | lifecycle actions |
| POST | `/api/knowledge/documents/{id}/revisions` (multipart) | owner HR, ADMIN | submit a new version (pending) |
| PUT | `/api/knowledge/documents/{id}/reindex?expectedVersion=` | ADMIN | build the new version, supersede the old |
| POST | `/api/knowledge/search` | any | hybrid search |
| POST | `/api/knowledge/ask` | any | agentic answer with citations |
| GET | `/api/knowledge/answers/{id}` | asker, ADMIN | a stored answer |
| GET | `/api/knowledge/agent/status` | any | live model or simulation |
| POST | `/api/knowledge/feedback` | any | rate an answer |
| GET | `/api/knowledge/analytics/*` | ADMIN | the five SQL reports + summary |

### Examples

```bash
# Upload (HR/Admin): any new .txt, .md or .pdf file (identical content to an existing document is rejected with 409)
curl -u admin@example.com:PASSWORD -F file=@path/to/parking-policy.md \
     -F title="Parking Policy" -F category=Facilities -F accessLevel=INTERNAL \
     http://localhost:8080/api/knowledge/documents
# 201 Created  {"id":12,"title":"Parking Policy","status":"ACTIVE","version":1,...}

# Search
curl -u user@example.com:PASSWORD -H "Content-Type: application/json" \
     -d '{"query":"carry over leave","category":"HR","limit":5}' \
     http://localhost:8080/api/knowledge/search

# Ask
curl -u user@example.com:PASSWORD -H "Content-Type: application/json" \
     -d '{"question":"How many days of annual leave do I get?"}' \
     http://localhost:8080/api/knowledge/ask
# {"answerId":31,"answer":"Full-time employees receive 25 days of paid annual leave per calendar year [E1].",
#  "outcome":"ANSWERED","citations":[{"ref":"E1","title":"Annual Leave Policy","version":1,
#  "location":"§ Annual Leave Policy > Entitlement · chunk 2",...}],"evidence":[...],"trace":[...]}

# Feedback
curl -u user@example.com:PASSWORD -H "Content-Type: application/json" \
     -d '{"answerId":31,"rating":5,"comment":"Correct"}' http://localhost:8080/api/knowledge/feedback
```

### Errors and status codes
Every error has the same shape:

```json
{"timestamp":"2026-10-15T10:00:00Z","status":400,"code":"VALIDATION_FAILED",
 "message":"Request validation failed","path":"/api/knowledge/search",
 "fieldErrors":[{"field":"query","message":"must not be blank"}]}
```

| Status | Example codes |
|---|---|
| 400 | `VALIDATION_FAILED`, `UNSUPPORTED_FILE_TYPE`, `EMPTY_DOCUMENT`, `UNREADABLE_DOCUMENT` |
| 401 | `UNAUTHENTICATED`, `ACCOUNT_PENDING`, `ACCOUNT_REJECTED` |
| 403 | `FORBIDDEN`, `ACCESS_LEVEL_NOT_PERMITTED`, `NOT_DOCUMENT_OWNER`, `NOT_YOUR_APPROVAL` |
| 404 | `NOT_FOUND` (also for documents the caller may not read) |
| 409 | `DUPLICATE_DOCUMENT`, `INVALID_STATE_TRANSITION`, `VERSION_CONFLICT`, `CONCURRENT_MODIFICATION`, `ALREADY_DECIDED` |
| 413 | `FILE_TOO_LARGE` |
| 503 | `RETRIEVAL_UNAVAILABLE` (every retriever failed) |

## 8. UI screens

| Required screen | Where |
|---|---|
| Document upload and management | **Documents**: upload panel, filters, approve/reject/revise/re-index/archive |
| Knowledge search | **Search**: query, category, access level, result count |
| Answer with citations | **Ask**: answer with `[E1]` citation links and a sources list |
| Source preview | click any citation or search result (current/obsolete version clearly labelled) |
| Feedback form | under every answer: 1–5 stars, document, comment |
| Search/feedback analytics | **Analytics** (Admin): summary tiles, charts with table view, the five reports |

Extra screens: Sign-in, Sign-up, Approvals, Profile. The Ask screen also shows the evidence the agent
read, the evidence check per sub-question, and the full agent trace.
Screenshots: [`docs/screenshots`](docs/screenshots).

## 9. SQL tasks

All five queries are in [`docs/sql/reports.sql`](docs/sql/reports.sql) and are served live on the
Analytics screen (`AnalyticsRepository`):

1. Documents grouped by category and status
2. Documents awaiting re-indexing
3. Most frequently searched categories
4. Feedback ratings by document
5. Documents accessible to a given role

## 10. Tests

```bash
./mvnw test                                   # all tests (needs PostgreSQL with database ekg_test)
./mvnw test -Dtest=AskApiIntegrationTest      # one class
./mvnw test -Dtest=AskApiIntegrationTest#tc07_noRestrictedLeak   # one test
```

- Integration tests use the real PostgreSQL database `ekg_test` (reset with Flyway before each test) and
  never call a model: the agent runs in simulation mode with a deterministic fake embedding model.
- Results: `target/surefire-reports/`. The answer-quality evaluation writes `target/eval-report.md`.

| Test case | Requirement | Automated in |
|---|---|---|
| TC-05-01 | supported upload is indexed and searchable | `DocumentApiIntegrationTest.tc01_uploadIndexedAndSearchable` |
| TC-05-02 | unsupported or empty file rejected | `DocumentApiIntegrationTest.tc02_…`, `IngestionUnitTest` |
| TC-05-03 | answer with correct facts and citations | `AskApiIntegrationTest.tc03_…`, `ResearchAgentTest` |
| TC-05-04 | no evidence → abstain | `AskApiIntegrationTest.tc04_…`, `ResearchAgentTest` |
| TC-05-05 | two documents combined and both cited | `AskApiIntegrationTest.tc05_…`, `ResearchAgentTest` |
| TC-05-06 | metadata filters return only accessible matches | `DocumentApiIntegrationTest.tc06_…` |
| TC-05-07 | no restricted text leaked to unauthorised users | `AskApiIntegrationTest.tc07_…` |
| TC-05-08 | prompt injection cannot change behaviour | `AskApiIntegrationTest.tc08_…`, `AgentComponentsTest`, `ResearchAgentTest` |
| TC-05-09 | re-index serves the new version only | `DocumentApiIntegrationTest.tc09_…` |
| TC-05-10 | model / vector store time-out → fallback or clear error | `RetrievalServiceTest.tc10_…`, `ResearchAgentTest` |
| TC-05-11 | feedback persisted and linked | `AskApiIntegrationTest.tc11_…` |
| TC-05-12 | fixed question set scored on facts and citations | `AnswerQualityEvaluationTest` (`src/test/resources/eval/questions.json`) |

Other suites: `AccountIntegrationTest` (sign-up and approval routing), `PersistenceAndReportsIntegrationTest`
(constraints and SQL reports), `SemanticSearchIntegrationTest`, `PageRoutesIntegrationTest`,
`OllamaAdapterTest` (model client against a mocked server), `ServiceRulesTest`, `SemanticUnitTest`.

## 11. Configuration

`src/main/resources/application.properties` (override any key in `local.properties` or with environment variables):

| Key | Default | Meaning |
|---|---|---|
| `ekg.llm.provider` | `ollama` | `ollama` or `simulated` |
| `ekg.llm.model` | `qwen2.5:3b` | answer model (env `EKG_LLM_MODEL`) |
| `ekg.llm.base-url` | `http://localhost:11434/v1` | Ollama OpenAI-compatible endpoint |
| `ekg.llm.timeout` | `120s` | per model call; on time-out the fallback is used |
| `ekg.embedding.model` | `nomic-embed-text` | embedding model (env `EKG_EMBEDDING_MODEL`) |
| `ekg.embedding.min-similarity` | `0.635` | similarity at which a passage counts as evidence by meaning (calibrated on the seed corpus) |
| `ekg.ingestion.chunk-min-tokens` / `chunk-max-tokens` / `chunk-overlap-tokens` | `350` / `400` / `50` | chunk size in approximate tokens, and the overlap between chunks of one section |
| `ekg.ingestion.max-bytes` | `5242880` | upload limit (5 MB) |
| `ekg.retrieval.timeout` | `10s` | time budget for the parallel retrievers |
| `ekg.agent.max-tool-calls` | `10` | tool-call budget per question |
| `ekg.agent.min-coverage` | `0.6` | word coverage needed to count as evidence |
| `ekg.seed.enabled` | `true` | load the synthetic seed documents into an empty database |

## 12. Project structure

```
src/main/java/com/salman/ekg/
  agent/  agent/llm/  config/  controller/  domain/  dto/  embedding/  exception/
  ingestion/  repository/  retrieval/  security/  service/  text/
src/main/resources/
  application.properties     db/migration/ (Flyway)     seed/ (synthetic corpus)
  static/ (index.html, css/, js/, js/views/)
src/test/java/...            unit and integration tests
src/test/resources/          test profile, evaluation question set
docs/sql/reports.sql         the five SQL tasks
docs/screenshots/            screenshots of the main screens and test results
scripts/capture-screenshots.mjs   regenerates the screenshots (headless Edge/Chrome)
db/setup-local-db.sql        optional database/role setup
```

## 13. AI usage log

AI assistance (Claude Code) was used to plan, generate, review and debug this project; every change was
reviewed, tested and is explainable.

**Key prompts / tool calls**
- Plan first: "Give me a structured plan for the complete implementation, then build after my confirmation."
- Iterations: switching from a hosted model to a free local Ollama model; fixing "Degraded" answers;
  adding hybrid (keyword + semantic) search with PostgreSQL arrays; sign-up with role-based approval;
  a full UI redesign without breaking behaviour.
- Tool use during development: running the test suite after every change, checking the live
  application in a browser, inspecting the database with read-only SQL, and reading agent traces of
  failing questions to find root causes.

**My design decisions**
- **Approval flow by role**: sign-ups start as *pending*. An **Employee** request is approved by **HR**;
  an **HR** or **Admin** request is approved by an **Admin**. The first Admin is created from configuration,
  so there is always someone who can approve.
- **Semantic search, not only keywords**: questions are matched by **meaning** (embeddings) as well as by
  words, so "paid time off" finds the *Annual Leave Policy*. Keyword search stays as the fallback when the
  embedding model is unavailable.
- **Agent / model comparison**: I compared local models (`qwen2.5:3b`, `qwen2.5:7b`, `qwen3:4b`, `qwen3:8b`)
  and embedding models (`nomic-embed-text`, `bge-m3`) on answer quality, speed on a CPU and reliability,
  and also considered hosted APIs. I chose the current setup, **`qwen2.5:3b` + `nomic-embed-text`**, as
  final: free, runs offline (no company data leaves the machine), and fast enough on a laptop CPU, while the
  Java evidence checks keep answers grounded.
- **UI**: designed as a senior UI/UX developer would: a clean, calm interface (sidebar navigation, one
  accent colour, readable typography, light and dark mode), clear loading and error states, and the
  evidence and agent trace one click away for transparency.

**One defect found and fixed**
*Valid answers were thrown away as "uncited".* The local model often wrote citation lists as `[E2, E3]`
or `INSUFFICIENT_Evidence` in mixed case. `CitationValidator` only recognised `[E2][E3]`, so correct
sentences were dropped and the user received a degraded, extractive answer. Found by reading the agent
trace (`VALIDATE` step listed `UNCITED_SENTENCE_REMOVED` for sentences that clearly had citations).
Fix: normalise citation groups (`[E2, E3]`, `[E2; E3]`, `[e2]`) and match the insufficient-evidence
marker case-insensitively; the case rule has a regression test in `AgentComponentsTest`
(`insufficientEvidenceAnyCase`).
A second example: the 3B model copied an example sentence from the system prompt into its answers;
the prompt now contains no example facts and a regression test checks it (`OllamaAdapterTest`).

**Validation approach**
- Business rules are tested without any model (deterministic simulation mode and a fake embedding model),
  so tests are repeatable.
- Every model output passes the Java citation validator; facts and numbers must exist in the cited evidence.
- `AnswerQualityEvaluationTest` scores a fixed question set on factual support and citation
  recall/precision, not exact wording.
- Live behaviour was checked manually in the browser and through the stored agent traces.

## 14. Limitations

- **Speed**: the local model runs on the CPU; an answer can take from about 10 seconds to a few minutes.
  A GPU or a hosted model would be much faster.
- **Small model**: `qwen2.5:3b` sometimes makes weak tool calls or terse answers; the validator and
  fallbacks keep answers grounded, but more answers are then marked **Degraded**.
- **Scale**: vectors are compared in Java, which is fine for this corpus; for tens of thousands of chunks
  use pgvector with an HNSW index.
- **Conflict detection** compares numbers per quantity across documents; it does not understand meaning.
- **Authentication** is HTTP Basic over a stateless API for simplicity; production would use HTTPS with
  SSO/OAuth2.
- **Chunking** uses an approximate token count (words, numbers and punctuation; long words count twice),
  not the model's own tokenizer. Most seed documents are shorter than 350 tokens, so each is one chunk.
- **Meaning threshold**: with `nomic-embed-text`, scores of right and wrong documents overlap slightly
  (answerable 0.64-0.73, unrelated up to 0.64), so the threshold trades a few refusals for safety.
