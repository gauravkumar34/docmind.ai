# DocMind Backend

DocMind is an AI document assistant. Users upload documents (PDF, DOCX, TXT, MD, CSV, …), and DocMind indexes them as vector embeddings in PostgreSQL + pgvector. Users can then ask questions about their documents and get answers grounded in the content, with citations (file name, page, snippet, similarity score).

The backend is a Spring Boot 4 + Spring AI 2 application. It runs its models **locally through Ollama**, so no external AI API key is needed. A React frontend is in [docmind-frontend/](docmind-frontend/).

---

## Table of contents

- [Features](#features)
- [Tech stack](#tech-stack)
- [Architecture](#architecture)
- [Project structure](#project-structure)
- [Prerequisites](#prerequisites)
- [Getting started](#getting-started)
- [Configuration](#configuration)
- [API reference](#api-reference)
- [Data model](#data-model)
- [How RAG works in DocMind](#how-rag-works-in-docmind)
- [Chat memory](#chat-memory)
- [Security](#security)
- [Frontend](#frontend)
- [Troubleshooting](#troubleshooting)

---

## Features

- **JWT authentication.** Register, log in, and use stateless bearer-token auth with `USER` and `ADMIN` roles.
- **Document ingestion.** Upload one file or several. PDFs are parsed page by page, and every other format goes through Apache Tika.
- **Chunking and embeddings.** Text is split with `TokenTextSplitter`, embedded with `nomic-embed-text` (768 dimensions), and stored in pgvector with an HNSW index and cosine distance.
- **Per-user isolation.** Every chunk is tagged with its owner's `user_id`, and every search is filtered by it, so users only see their own documents.
- **Document-grounded Q&A.** Questions run against all of your documents or one specific document. Answers come with citations.
- **Streaming answers.** Tokens are streamed as they're generated, ending with a `[DONE]` marker.
- **Semantic search.** The search endpoint returns the most similar chunks without generating an answer.
- **Conversation memory.** Chat history is saved in Postgres (`conversations`, `chat_messages`) and sent back to the model on each turn.
- **OpenAPI / Swagger UI** is included.

## Tech stack

| Layer          | Technology                                                       |
|----------------|------------------------------------------------------------------|
| Language       | Java 17                                                          |
| Framework      | Spring Boot 4.1 (Web MVC, Data JPA, Security, Validation)        |
| AI             | Spring AI 2.0 (`ChatClient`, `MessageChatMemoryAdvisor`)         |
| LLM            | Ollama `qwen3:8b` (chat), `nomic-embed-text` (embeddings)        |
| Vector store   | PostgreSQL 16 + pgvector (`pgvector/pgvector:pg16` Docker image) |
| Parsing        | Spring AI PDF reader (`PagePdfDocumentReader`), Apache Tika      |
| Auth           | Spring Security + JJWT 0.12                                      |
| API docs       | springdoc-openapi 2.8 (Swagger UI)                               |
| Misc           | Lombok, ModelMapper                                              |
| Frontend       | React 19, Vite, Tailwind, Zustand, axios                         |

## Architecture

```
            ┌──────────────────────┐
            │  React frontend      │
            └──────────┬───────────┘
                       │  REST + JWT (Bearer)
            ┌──────────▼───────────┐
            │ JwtAuthenticationFilter│
            └──────────┬───────────┘
     ┌─────────────────┼──────────────────┐
     ▼                 ▼                  ▼
AuthController   DocumentController   ChatController
     │                 │                  │
UserService     DocumentMetaDataService   RagService ──► ChatClient (Ollama qwen3:8b)
JwtService        │          │              │               │
                  ▼          ▼              │        MessageChatMemoryAdvisor
     DocumentParserService  DocumentIngestionService         │
       (PDF / Tika)          (split + embed)│          JpaChatMemory
                                  │         │               │
                                  ▼         ▼               ▼
                        ┌─────────────────────────────────────────┐
                        │ PostgreSQL + pgvector                   │
                        │ users, document_metadata, vector_store, │
                        │ conversations, chat_messages            │
                        └─────────────────────────────────────────┘
```

## Project structure

```
src/main/java/com/docmind
├── DocmindBackendApplication.java
├── config/
│   ├── SecurityConfig.java          # filter chain, role rules, password encoder
│   ├── JwtAuthenticationFilter.java # reads and validates the Bearer token
│   ├── CorsConfig.java              # CORS from app.cors.*
│   ├── AppProperties.java           # app.rag.* and app.cors.* properties
│   └── ProjectConfig.java           # ChatClient (system prompt + advisors), OpenAPI, ModelMapper
├── controller/
│   ├── AuthController.java          # /api/v1/auth
│   ├── DocumentController.java      # /api/v1/documents
│   └── ChatController.java          # /api/v1/chat
├── service/
│   ├── UserService.java, CustomUserDetailService.java, CustomUserDetails.java, JwtService.java
│   ├── DocumentMetaDataService.java # upload orchestration, CRUD on document metadata
│   ├── DocumentParserService.java   # file → Spring AI Documents
│   ├── DocumentIngestionService.java# chunk, enrich metadata, write to vector store
│   ├── RagService.java              # retrieval, prompt building, Q&A / stream / search
│   └── JpaChatMemory.java           # ChatMemory backed by chat_messages
├── entity/        # User, DocumentMetadata, Conversation, ChatMessage, enums
├── repository/    # Spring Data JPA repositories
├── dto/           # request/response DTOs, ApiResponse wrapper
└── exception/     # GlobalExceptionHandler and custom exceptions
```

## Prerequisites

- **JDK 17+**
- **Docker** (for PostgreSQL + pgvector)
- **Ollama** installed and running on `http://localhost:11434`
- Node 20+ (only if you want to run the frontend)

## Getting started

### 1. Start PostgreSQL with pgvector

```bash
docker compose up -d
```

This starts `pgvector/pgvector:pg16` on **host port 5434**, with database `docmind`, user `postgres` and password `postgres`. [init.sql](init.sql) enables the `vector` and `uuid-ossp` extensions.

### 2. Pull the Ollama models

```bash
ollama pull qwen3:8b
ollama pull nomic-embed-text
```

### 3. Run the backend

```bash
./mvnw spring-boot:run
```

The `dev` profile is active by default ([application.properties](src/main/resources/application.properties)), and the app starts on **http://localhost:8080**. Hibernate creates or updates the tables automatically (`ddl-auto: update`), and Spring AI creates the `vector_store` table (`initialize-schema: true`).

> **Note:** `pom.xml` currently declares `maven-compiler-plugin` twice, which makes the Maven CLI fail with *"must be unique but found duplicate declaration"*. Merge the two declarations into one before building from the command line.

### 4. Open Swagger UI

http://localhost:8080/swagger-ui.html

Call `/api/v1/auth/login`, copy the `accessToken`, click **Authorize**, and paste it in.

### 5. (Optional) Run the frontend

```bash
cd docmind-frontend
npm install
npm run dev
```

## Configuration

Most settings are in [application-dev.yml](src/main/resources/application-dev.yml).

### Environment variables

| Variable      | Default     | Purpose                    |
|---------------|-------------|----------------------------|
| `DB_HOST`     | `localhost` | Postgres host              |
| `DB_PORT`     | `5434`      | Postgres port              |
| `DB_NAME`     | `docmind`   | Database name              |
| `DB_USER`     | `postgres`  | Database user              |
| `DB_PASSWORD` | `postgres`  | Database password          |
| `JWT_SECRET`  | dev value   | HMAC key for signing JWTs. **Always override it in production.** |

### AI settings (`spring.ai.*`)

| Property                                   | Value              |
|--------------------------------------------|--------------------|
| `ollama.base-url`                          | `http://localhost:11434` |
| `ollama.chat.model`                        | `qwen3:8b`         |
| `ollama.chat.temperature`                  | `0.2`              |
| `ollama.embedding.model`                   | `nomic-embed-text` |
| `vectorstore.pgvector.dimensions`          | `768`. Must match the embedding model. |
| `vectorstore.pgvector.index-type`          | `HNSW`             |
| `vectorstore.pgvector.distance-type`       | `cosine_distance`  |
| `vectorstore.pgvector.table-name`          | `vector_store`     |

If you switch to an embedding model with a different dimension, update `dimensions` and drop and recreate `vector_store`.

### RAG settings (`app.rag.*`)

| Property                    | Default | Description                                  |
|-----------------------------|---------|----------------------------------------------|
| `chunk-size`                | 700 (yml) / 600 (code) | Target tokens per chunk       |
| `min-chunk-size-chars`      | 350     | Minimum characters per chunk                 |
| `min-chunk-length-to-embed` | 5       | Shorter chunks are discarded                 |
| `max-num-chunk`             | 10000   | Maximum number of chunks per document        |
| `top-k`                     | 5       | Chunks retrieved per question                |
| `similarity-threshold`      | 0.0     | Minimum similarity. `0` turns the threshold off. |

### Other settings

- `app.cors.*`: allowed origins, methods and headers.
- `jwt.expiration`: token lifetime in milliseconds (default `3600000` = 1 hour).
- `spring.servlet.multipart`: up to 25 MB per file and 50 MB per request.

## API reference

Every endpoint except `/api/v1/auth/**` and Swagger needs this header:

```
Authorization: Bearer <accessToken>
```

Most endpoints wrap their response in `ApiResponse`:

```json
{
  "success": true,
  "message": "…",
  "data": { },
  "timestamp": "2026-11-03T10:15:30"
}
```

### Auth: `/api/v1/auth`

| Method | Path        | Body                                   | Description                          |
|--------|-------------|----------------------------------------|--------------------------------------|
| POST   | `/register` | `{ "username", "email", "password" }`  | Creates a user with role `USER`      |
| POST   | `/login`    | `{ "username", "password" }`           | Returns `{ accessToken, user }`      |

### Documents: `/api/v1/documents`

| Method | Path               | Role  | Description                                         |
|--------|--------------------|-------|-----------------------------------------------------|
| POST   | `/upload`          | USER  | Multipart field `file`. Uploads and indexes one document. |
| POST   | `/upload-multiple` | USER  | Multipart field `files`. Uploads several documents. |
| GET    | `/user`            | USER  | Lists the current user's documents and their status |
| GET    | `/{id}`            | USER  | Gets one document's metadata                        |
| DELETE | `/{id}`            | USER  | Deletes a document                                  |
| GET    | `/`                | ADMIN | Lists all documents from all users                  |

Document status moves through `UPLOADING → PROCESSING → INDEXED`, or ends at `FAILED` with an `errorMessage`.

```bash
curl -X POST http://localhost:8080/api/v1/documents/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@./handbook.pdf"
```

### Chat: `/api/v1/chat`

#### `POST /query`: ask a question

```json
{
  "question": "What is the leave policy?",
  "documentId": "optional-uuid-to-limit-the-search-to-one-document",
  "conversationId": "optional; omit it to start a new conversation",
  "topK": 5,
  "minSimilarity": 0.0
}
```

Response `data`:

```json
{
  "answer": "## Leave policy\n…",
  "conversationId": "b0c2…",
  "citations": [
    {
      "documentId": "…",
      "fileName": "handbook.pdf",
      "pageNumber": 4,
      "chunkIndex": 12,
      "snippet": "…",
      "similarityScore": 0.83,
      "metadata": { }
    }
  ],
  "responseTimeMs": 2140
}
```

To continue the same conversation, send the returned `conversationId` with your next question.

#### `POST /stream`: streaming answer

Takes the same body as `/query`. It returns a `Flux<String>` of answer tokens, and the last element is `[DONE]`. Citations aren't included in the stream. Use `/search/similarity` if you need them.

#### `POST /search/similarity`: semantic search

```json
{ "query": "termination notice", "documentId": null, "topK": 5, "similaritySearch": 0.5 }
```

Returns `{ query, totalMatches, matches: [CitationDto…] }`.

## Data model

| Table               | Purpose                                                              |
|---------------------|----------------------------------------------------------------------|
| `users`             | `id`, `username` (unique), `email` (unique), `password` (BCrypt), `role` |
| `document_metadata` | UUID `id`, `filename`, `content_type`, `file_size`, `total_pages`, `total_chunks`, `status`, `error_message`, timestamps, `user_id` |
| `vector_store`      | Managed by Spring AI: `id`, `content`, `metadata` (JSON), `embedding vector(768)` |
| `conversations`     | String `id` (UUID), `title` (first 50 characters of the first question), `user_id`, timestamps |
| `chat_messages`     | `id`, `conversation_id`, `message_type` (`USER`/`ASSISTANT`/`SYSTEM`), `content`, `created_at` |

Each chunk in `vector_store` has this metadata: `documentId`, `fileName`, `contentType`, `chunkIndex`, `user_id`, and `pageNumber` (for PDFs).

## How RAG works in DocMind

1. **Parse.** [DocumentParserService](src/main/java/com/docmind/service/DocumentParserService.java) reads PDFs one page at a time, so each page keeps its page number. Every other format is read with Tika.
2. **Chunk.** [DocumentIngestionService](src/main/java/com/docmind/service/DocumentIngestionService.java) splits the pages with `TokenTextSplitter`, using the `app.rag.*` settings.
3. **Enrich.** Each chunk gets document, file, owner and page metadata.
4. **Embed and store.** `vectorStore.add(...)` embeds the chunks with `nomic-embed-text` and writes them to pgvector. The document is then marked `INDEXED`.
5. **Retrieve.** [RagService](src/main/java/com/docmind/service/RagService.java) runs a similarity search for the top K chunks. The search always filters on `user_id`, and also on `documentId` when one is given.
6. **Augment.** The chunks are formatted as `[Source: file | Page: n]` blocks and inserted into the `{doc_context}` placeholder of the system prompt, which is defined in [ProjectConfig](src/main/java/com/docmind/config/ProjectConfig.java).
7. **Generate.** `ChatClient` calls `qwen3:8b` with the system prompt, the conversation history and the question. The response includes the retrieved chunks as citations.

The system prompt tells the model to use the document context first. If the context doesn't cover the question, the model may fall back to general knowledge. Answers are written in Markdown.

## Chat memory

`ChatClient` is set up with `MessageChatMemoryAdvisor`, which uses the custom [JpaChatMemory](src/main/java/com/docmind/service/JpaChatMemory.java):

- `add()` saves each user and assistant message to `chat_messages` under the conversation.
- `get()` loads that conversation's messages, oldest first, and adds them to the prompt.
- `clear()` deletes a conversation's messages.

`RagService` creates the `conversations` row, linked to the user, before calling the model. It passes the same `conversationId` to the advisor through `ChatMemory.CONVERSATION_ID`.

## Security

- Sessions are stateless and CSRF is disabled, because the API uses token auth.
- `JwtAuthenticationFilter` reads `Authorization: Bearer …`, validates the token, and fills the `SecurityContext`.
- Access rules:
  - `/api/v1/auth/**`, `/swagger-ui/**`, `/v3/api-docs/**` and `/error` are public.
  - `/api/v1/admin/**` requires `ADMIN`.
  - `/api/v1/**` requires `USER`.
  - Method-level `@PreAuthorize` applies on top of these. For example, `GET /api/v1/documents` requires `ADMIN`.
- Passwords are hashed with BCrypt.
- Unauthenticated requests get `401 Unauthorized`, and requests without the right role get `403 Access Denied`.

## Frontend

[docmind-frontend/](docmind-frontend/) is a React 19 + Vite + TypeScript app. It uses Tailwind, Zustand, axios, react-dropzone for uploads, and react-markdown with Prism for rendering answers. It talks to the backend at `http://localhost:8080`. See [docmind-frontend/README.md](docmind-frontend/README.md) for details.

## Troubleshooting

| Symptom | Likely cause / fix |
|---------|--------------------|
| Answers ignore uploaded documents | Check the logs for `Retrieved 0 chunks`. Make sure the document status is `INDEXED`, and that the filter key in `RagService` (`user_id`) matches the metadata key written during ingestion. |
| `Connection refused` to `localhost:11434` | Ollama isn't running. Start it with `ollama serve`. |
| `model "qwen3:8b" not found` | Run `ollama pull qwen3:8b` and `ollama pull nomic-embed-text`. |
| Vector dimension mismatch error | `dimensions` doesn't match the embedding model. Fix the setting, then drop `vector_store` and re-upload your documents. |
| Can't connect to Postgres | The container listens on host port **5434**, not 5432. Check `docker compose ps`. |
| `chat_messages` stays empty | Make sure the same `conversationId` is passed to both `ensureConversationExists` and the memory advisor, and that the conversation has a `user`. |
| Template error for documents containing `{` or `}` | The document context is rendered as a prompt-template parameter. Escape the braces, or append the context to the user message instead. |
| Maven fails: *duplicate declaration of maven-compiler-plugin* | Merge the two `maven-compiler-plugin` blocks in `pom.xml`. |
| `401` on every request | The token is missing or expired (tokens last 1 hour by default). Log in again. |
