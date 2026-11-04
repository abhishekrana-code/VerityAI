# VerityAI — Technical Implementation Plan & Execution Runbook

**Document Version:** 1.0.0  
**Status:** Approved for Implementation  
**Execution Environment:** Windows 10/11 (Development) $\rightarrow$ Ubuntu Server 24.04 LTS (Production Host)  

---

## 1. Guiding Principles & Verification Rules

1. **Incremental Execution**: Never make unverified multi-component changes. Each step must compile, run, and pass automated or manual verification before proceeding to the next.
2. **Zero Regressions**: Run backend compilation (`mvn test-compile`) and frontend typechecking (`npm run typecheck`) after every modification.
3. **Preserve Security & Data Integrity**: Parameterize all SQL queries, validate file streams before storage, enforce tenant isolation on every service call, and keep secrets in `.env`.

---

## 2. Phase 1: Environment & Foundation Hardening

### Step 1.1: Regenerate Maven Wrapper
* **Issue**: `backend/.mvn/wrapper/maven-wrapper.jar` is missing a valid manifest attribute, preventing `.\mvnw.cmd` from executing.
* **Execution**:
  ```powershell
  cd backend
  mvn wrapper:wrapper -Dmaven=3.9.9
  .\mvnw.cmd -version
  ```
* **Verification**: `.\mvnw.cmd -version` displays `Apache Maven 3.9.9` and `Java version: 21.0.12.1`.

### Step 1.2: Validate Groq Model Configuration
* **Issue**: `.env` and `application.yml` reference `openai/gpt-oss-20b`, which may not be enabled or available on all Groq Cloud tiers.
* **Execution**:
  * Set default model in `backend/src/main/resources/application.yml` and `.env` to a universally available Groq model:
    ```yaml
    verityai:
      groq:
        model: ${GROQ_MODEL:llama-3.3-70b-versatile}
    ```
  * Supported active alternatives: `llama-3.1-8b-instant`, `llama-3.3-70b-versatile`.
* **Verification**: Test completions endpoint with a simple HTTP ping using the configured API key.

### Step 1.3: Tune HikariCP Connection Pool Settings
* **Issue**: On sleeping development hosts or idle home servers, dead connections can linger in HikariPool, causing connection timeout exceptions during scheduled session cleanup.
* **Execution**: In `backend/src/main/resources/application.yml`, update `spring.datasource.hikari`:
  ```yaml
  spring:
    datasource:
      hikari:
        maximum-pool-size: 15
        minimum-idle: 3
        connection-timeout: 30000
        idle-timeout: 300000
        max-lifetime: 1200000
        keepalive-time: 30000
        connection-test-query: SELECT 1
  ```
* **Verification**: Start backend with `mvn spring-boot:run` and verify successful database startup and Flyway migration check.

---

## 3. Phase 2: RAG Pipeline & Document Scoping

### Step 2.1: Add Document Scoping to Repository Queries
* **Goal**: Enable RAG queries to filter exclusively by the documents associated with a conversation.
* **File to Modify**: [`DocumentChunkRepository.java`](file:///C:/Users/iamab/VerityAI/backend/src/main/java/com/verityai/repository/DocumentChunkRepository.java)
* **Code Implementation**:
  ```java
  @Query(value = """
      SELECT dc.*, ts_rank_cd(dc.content_tsv, websearch_to_tsquery('english', :query)) as rank
      FROM document_chunks dc
      JOIN documents d ON dc.document_id = d.id
      WHERE d.user_id = :userId
        AND d.deleted_at IS NULL
        AND (:docIds IS NULL OR d.id IN (:docIds))
        AND dc.content_tsv @@ websearch_to_tsquery('english', :query)
      ORDER BY rank DESC
      LIMIT :limit
      """, nativeQuery = true)
  List<Object[]> fullTextSearchWithRank(
      @Param("userId") UUID userId,
      @Param("query") String query,
      @Param("docIds") List<UUID> docIds,
      @Param("limit") int limit
  );
  ```

### Step 2.2: Update RetrievalService and ChatService
* **Goal**: Thread `List<UUID> docIds` through `RetrievalService.retrieve(userId, query, docIds)`.
* **Files to Modify**:
  * [`RetrievalService.java`](file:///C:/Users/iamab/VerityAI/backend/src/main/java/com/verityai/service/retrieval/RetrievalService.java): Update method signature and pass `docIds` to repository query.
  * [`ChatService.java`](file:///C:/Users/iamab/VerityAI/backend/src/main/java/com/verityai/service/ai/ChatService.java): Pass the verified `docIds` into `retrievalService.retrieve(userId, question, docIds.isEmpty() ? null : docIds)`.

### Step 2.3: Wire Conversation Document Scope Updates
* **Goal**: Implement the empty placeholder in [`ConversationController.update`](file:///C:/Users/iamab/VerityAI/backend/src/main/java/com/verityai/controller/ConversationController.java#L120-L122).
* **Code Implementation**:
  ```java
  if (request.getDocumentIds() != null) {
      conversation.getDocuments().clear();
      if (!request.getDocumentIds().isEmpty()) {
          List<Document> docs = documentRepository.findAllById(request.getDocumentIds());
          for (Document doc : docs) {
              if (!doc.getUser().getId().equals(user.getId())) {
                  throw ApiException.forbidden("Document access denied: " + doc.getId());
              }
              ConversationDocument cd = new ConversationDocument();
              cd.setConversationId(conversation.getId());
              cd.setDocumentId(doc.getId());
              conversation.getDocuments().add(cd);
          }
      }
  }
  ```

---

## 4. Phase 3: Research Workspace & Citations UI Polish

### Step 3.1: Interactive Citation Drawer
* **Goal**: Clicking `[S1]`, `[S2]` inside an assistant message bubble opens a side panel displaying the exact source provenance.
* **Component**: Enhance `MessageBubble` in [`ConversationPage.tsx`](file:///C:/Users/iamab/VerityAI/frontend/src/features/workspace/ConversationPage.tsx).
* **UI Features**:
  * Document name with PDF badge.
  * Page number indicator (e.g., `Page 14`).
  * Relevance score bar (`89% Match`).
  * Verbatim excerpt formatted with quotation styling.
  * "Copy Excerpt" button.

### Step 3.2: Scoped Document Picker in Workspace
* **Goal**: Allow users to select which documents from their library are active in a conversation.
* **Component**: In [`WorkspacePage.tsx`](file:///C:/Users/iamab/VerityAI/frontend/src/features/workspace/WorkspacePage.tsx), add a multi-select modal or pill badges allowing users to toggle active research documents.

---

## 5. Phase 4: Automated Testing Suite

### Step 5.1: Backend JUnit 5 Tests
* **Target Directory**: `backend/src/test/java/com/verityai/`
* **Test Suites to Add**:
  1. `DocumentServiceTest`: Tests sliding-window chunking logic, boundary handling, and SHA-256 calculation.
  2. `RetrievalServiceTest`: Verifies ranking thresholding and document-scoping filters.
  3. `GroqLlmServiceTest`: Uses a local mock HTTP server to verify JSON serialization, header creation, 429 rate limit backoff, and citation parsing regex.
  4. `SecurityAccessTest`: Verifies that User B cannot query or retrieve documents owned by User A.
* **Execution Command**:
  ```powershell
  cd backend
  mvn test
  ```

### Step 5.2: Frontend Vitest Tests
* **Target Directory**: `frontend/src/test/`
* **Test Suites to Add**:
  1. `LoginPage.test.tsx`: Validates form inputs, Zod error messages, and submission.
  2. `DocumentsPage.test.tsx`: Validates dropzone file acceptance and status filtering.
  3. `ConversationPage.test.tsx`: Verifies markdown rendering and citation toggle actions.
* **Execution Command**:
  ```powershell
  cd frontend
  npm test
  ```

---

## 6. Phase 5: Self-Hosted Server Deployment Runbook (8GB Laptop)

### Step 6.1: OS Installation & Headless Preparation
1. Flash a USB drive with **Ubuntu Server 24.04 LTS** (minimal installation, no desktop GUI).
2. Install onto the laptop, choosing **OpenSSH server**.
3. Once booted, configure systemd so the laptop does not suspend when the lid is closed:
   ```bash
   sudo nano /etc/systemd/logind.conf
   ```
   Uncomment and set:
   ```ini
   HandleLidSwitch=ignore
   HandleLidSwitchExternalPower=ignore
   HandleLidSwitchDocked=ignore
   ```
   Apply changes: `sudo systemctl restart systemd-logind`.

### Step 6.2: Install Docker & Dependencies
```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y curl git ufw nginx

# Install Docker Engine
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh
sudo usermod -aG docker $USER
```

### Step 6.3: Deploy Database & Backend
1. Clone repository to `/opt/verityai`:
   ```bash
   git clone https://github.com/your-username/VerityAI.git /opt/verityai
   cd /opt/verityai
   cp .env.example .env
   nano .env  # Enter your real GROQ_API_KEY
   ```
2. Start PostgreSQL container:
   ```bash
   docker compose up -d postgres
   ```
3. Build the frontend:
   ```bash
   cd /opt/verityai/frontend
   npm install
   npm run build
   sudo cp -r dist/* /var/www/verityai/
   ```
4. Build the backend:
   ```bash
   cd /opt/verityai/backend
   mvn clean package -DskipTests
   ```
5. Configure systemd service for Spring Boot (`/etc/systemd/system/verityai.service`):
   ```ini
   [Unit]
   Description=VerityAI Backend Service
   After=network.target

   [Service]
   User=ubuntu
   WorkingDirectory=/opt/verityai/backend
   ExecStart=/usr/bin/java -Xmx768m -jar /opt/verityai/backend/target/verityai-backend-0.1.0-SNAPSHOT.jar
   Restart=always
   RestartSec=10
   EnvironmentFile=/opt/verityai/.env

   [Install]
   WantedBy=multi-user.target
   ```
   Start service: `sudo systemctl daemon-reload && sudo systemctl enable --now verityai`.

### Step 6.4: Configure Nginx Reverse Proxy
Create `/etc/nginx/sites-available/verityai`:
```nginx
server {
    listen 80;
    server_name localhost;

    root /var/www/verityai;
    index index.html;

    # Static Frontend SPA
    location / {
        try_files $uri $uri/ /index.html;
    }

    # Backend API Proxy
    location /api/ {
        proxy_pass http://127.0.0.1:8080/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```
Enable site: `sudo ln -s /etc/nginx/sites-available/verityai /etc/nginx/sites-enabled/ && sudo nginx -t && sudo systemctl restart nginx`.

### Step 6.5: Public HTTPS Access via Cloudflare Tunnel
1. Install Cloudflare daemon:
   ```bash
   curl -L --output cloudflared.deb https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb
   sudo dpkg -i cloudflared.deb
   ```
2. Login and create tunnel:
   ```bash
   cloudflared tunnel login
   cloudflared tunnel create verityai-server
   ```
3. Route tunnel to local Nginx (`http://localhost:80`) in `~/.cloudflared/config.yml`:
   ```yaml
   tunnel: <TUNNEL_UUID>
   credentials-file: /home/ubuntu/.cloudflared/<TUNNEL_UUID>.json
   ingress:
     - hostname: verity.yourdomain.com
       service: http://localhost:80
     - service: http_status:404
   ```
4. Install and start as a service: `sudo cloudflared service install`.
* **Result**: Your server is now accessible securely over HTTPS globally with zero open router ports and full immunity to home ISP CGNAT.

---

## 7. Step-by-Step Future Scope Implementation Blueprint

### 7.1 Hybrid Search (`pgvector` + BM25 FTS)
1. **Migration**: Create `V3__add_pgvector.sql`:
   ```sql
   CREATE EXTENSION IF NOT EXISTS vector;
   ALTER TABLE document_chunks ADD COLUMN embedding vector(384);
   CREATE INDEX idx_document_chunks_embedding ON document_chunks USING hnsw (embedding vector_cosine_ops);
   ```
2. **Embedding Pipeline**: In `DocumentService.java`, integrate an ONNX runtime or Groq/HuggingFace embedding call to populate the `embedding` column for each chunk.
3. **Reciprocal Rank Fusion (RRF)**: Implement hybrid SQL query:
   ```sql
   WITH fts AS (
       SELECT id, ts_rank_cd(content_tsv, websearch_to_tsquery('english', :q)) AS fts_score,
              ROW_NUMBER() OVER (ORDER BY ts_rank_cd(content_tsv, websearch_to_tsquery('english', :q)) DESC) AS fts_rank
       FROM document_chunks WHERE document_id IN (:docIds) LIMIT 20
   ),
   vec AS (
       SELECT id, (1 - (embedding <=> :query_vec::vector)) AS vec_score,
              ROW_NUMBER() OVER (ORDER BY embedding <=> :query_vec::vector ASC) AS vec_rank
       FROM document_chunks WHERE document_id IN (:docIds) LIMIT 20
   )
   SELECT COALESCE(fts.id, vec.id) as chunk_id,
          (COALESCE(1.0 / (60 + fts.fts_rank), 0.0) + COALESCE(1.0 / (60 + vec.vec_rank), 0.0)) AS rrf_score
   FROM fts FULL OUTER JOIN vec ON fts.id = vec.id
   ORDER BY rrf_score DESC LIMIT :limit;
   ```

### 7.2 In-Browser PDF Highlighting Canvas
1. **Coordinate Capture**: Update PDFBox extraction in `DocumentService.java` using a custom `PDFTextStripper` subclass that records `TextPosition.getXDirAdj()`, `getYDirAdj()`, `getWidthDirAdj()`, and `getHeightDirAdj()` for each chunk into a JSONB column `bounding_boxes` on `document_chunks`.
2. **Frontend Canvas Viewer**: Install `pdfjs-dist` in `frontend/`. When a citation `[S1]` is clicked:
   * Load the PDF binary via `GET /api/v1/documents/{id}/file`.
   * Render the cited page onto an HTML5 `<canvas>`.
   * Draw translucent yellow rectangles (`rgba(255, 235, 59, 0.45)`) over the recorded coordinates.

### 7.3 Real-Time SSE Token Streaming
1. Update `GroqLlmService.java` to set `"stream": true` in the JSON payload sent to Groq.
2. Read the HTTP response body as an `InputStream` lines stream.
3. Expose endpoint in `ConversationController.java`:
   ```java
   @GetMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
   public ResponseBodyEmitter streamMessage(@CurrentUser User user, @PathVariable UUID id, @RequestParam String q) {
       ResponseBodyEmitter emitter = new ResponseBodyEmitter(60000L);
       chatService.streamAsk(user, id, q, emitter);
       return emitter;
   }
   ```
4. Frontend `ConversationPage.tsx` connects via `fetchEventSource` (`@microsoft/fetch-event-source`), appending tokens directly to the active assistant bubble.

---

## 8. Verification & Acceptance Checklist

Before declaring the implementation complete, verify each step:

- [ ] `.\mvnw.cmd test-compile` finishes with `BUILD SUCCESS`.
- [ ] `npm run typecheck` in `frontend/` reports 0 errors.
- [ ] Docker container `verityai-postgres` is active on port `5434`.
- [ ] Uploading a multi-page PDF successfully transitions status from `UPLOADED` $\rightarrow$ `PROCESSING` $\rightarrow$ `READY`.
- [ ] `document_chunks` table contains populated rows with non-empty `content_tsv` vectors.
- [ ] Submitting a question returns an assistant message with valid `[S1]` in-text citations.
- [ ] Asking a question about an unrelated topic triggers the `Insufficient evidence` indicator without hallucinations.
- [ ] Document scoping restricts answers exclusively to the selected PDFs.
- [ ] User B cannot query or view User A's documents.
