# DocMind Backend: Interview Points

These are interview questions and answers based on how the DocMind backend is actually built (Spring Boot 4, Spring AI 2, Ollama, PostgreSQL + pgvector, JWT). Use them to explain the project or prepare for backend interviews. Each answer refers to the real classes in this repo, so you can point to the code.

> For setup and API details, see [README.md](README.md).

---

## Table of contents

1. [Project overview: how to pitch it](#1-project-overview-how-to-pitch-it)
2. [RAG fundamentals](#2-rag-fundamentals)
3. [Document ingestion pipeline](#3-document-ingestion-pipeline)
4. [Embeddings and vector search (pgvector)](#4-embeddings-and-vector-search-pgvector)
5. [Spring AI: ChatClient, advisors, memory](#5-spring-ai-chatclient-advisors-memory)
6. [Spring Security and JWT](#6-spring-security-and-jwt)
7. [JPA / Hibernate and the data model](#7-jpa--hibernate-and-the-data-model)
8. [REST API design and error handling](#8-rest-api-design-and-error-handling)
9. [Streaming (SSE / Reactor Flux)](#9-streaming-sse--reactor-flux)
10. [Bugs found and fixed: debugging stories](#10-bugs-found-and-fixed-debugging-stories)
11. [Scalability, performance and production readiness](#11-scalability-performance-and-production-readiness)
12. [System design follow-ups](#12-system-design-follow-ups)
13. [Quick-fire questions](#13-quick-fire-questions)

---

## 1. Project overview: how to pitch it

**Q: Describe DocMind in 30 seconds.**

> DocMind is a RAG (Retrieval-Augmented Generation) backend built with Spring Boot and Spring AI. Users upload documents. These are parsed, split into chunks, embedded with a local Ollama model, and stored in PostgreSQL with pgvector. When a user asks a question, I run a similarity search over that user's chunks only, put the best matches into the system prompt, and have the LLM answer with citations (file, page, snippet, score). Conversation history is saved in Postgres through a custom `ChatMemory`. Auth is stateless JWT with USER and ADMIN roles.

**Q: Why did you build it with local models (Ollama) instead of OpenAI?**

- No API cost and no per-token billing while developing.
- **Data privacy:** the documents never leave the machine.
- Spring AI hides the model provider, so switching to OpenAI or Azure is a starter and config change. The commented-out `spring-ai-starter-model-openai` in `pom.xml` shows this.

**Q: What was the hardest part?**

Getting retrieval right. Good answers depend on good chunks and correct metadata filters. For example, a metadata key mismatch (`userId` vs `user_id`) silently returned zero chunks, so the model answered from general knowledge (see [section 10](#10-bugs-found-and-fixed-debugging-stories)).

---

## 2. RAG fundamentals

**Q: What is RAG and why use it instead of fine-tuning?**

RAG retrieves relevant data at question time and adds it to the prompt. Compared with fine-tuning:
- New documents can be used as soon as they're indexed, with no retraining.
- Answers can be traced back to sources through citations.
- It's cheaper, and each user's data can be kept separate.
- It reduces hallucination because the model is grounded in the retrieved context.

**Q: Walk through the RAG flow in your code.**

1. `ChatController` → `RagService.askQuestion()`
2. `ensureConversationExists()` creates the `Conversation` row
3. `retrieveRelevantDocuments()` builds a `SearchRequest` (query, topK, threshold, filter `user_id` [+ `documentId`])
4. `vectorStore.similaritySearch()` → `List<Document>`
5. `buildContextString()` formats the chunks as `[Source: file | Page: n] text`
6. `chatClient.prompt().system(s -> s.param("doc_context", ctx)).user(question)`, with the memory advisor's `CONVERSATION_ID` set
7. The chunks are mapped to `CitationDto`, and the response is wrapped in `ChatResponseDto`

**Q: What are `topK` and `similarityThreshold`? How do you tune them?**

- `topK` is how many chunks to retrieve (default 5). Too low and you miss context. Too high and you add noise and use up the context window.
- `similarityThreshold` is the minimum score a chunk needs (0 means off). It filters out weak matches.
- Tune them with a small evaluation set of questions and expected sources. Measure recall@k and answer quality.

**Q: How do you reduce hallucinations?**

- Low temperature (`0.2`).
- A system prompt that tells the model to use the document context first and cite sources.
- Return citations so users can check the answer.
- Possible improvement: make the model say "I don't know" when retrieval returns nothing (right now the prompt allows general knowledge).

**Q: What is "lost in the middle", and how would you handle it?**

LLMs pay less attention to context in the middle of a long prompt. Ways to handle it: fewer but better chunks, a re-ranking step (a cross-encoder) that puts the best chunks first, and context compression.

---

## 3. Document ingestion pipeline

**Q: How are different file types parsed?**

`DocumentParserService`:
- **PDF** → `PagePdfDocumentReader`, one `Document` per page, which keeps page numbers for citations.
- **Everything else** (DOCX, TXT, MD, CSV, …) → `TikaDocumentReader` (Apache Tika detects the format).
- The file is wrapped in a `ByteArrayResource` that overrides `getFilename()`, because the readers need a file name.

**Q: Explain your chunking strategy.**

`TokenTextSplitter` with settings from `app.rag.*`:
- `chunkSize` is about 600–700 tokens, which fits several chunks into the prompt.
- `minChunkSizeChars` and `minChunkLengthToEmbed` throw away tiny or useless chunks.
- `maxNumChunks` protects against huge files.

Trade-off: small chunks give precise matches but lose context, and large chunks keep more context but dilute the embedding. Overlap (`chunkOverlap`) keeps sentences from being cut off at chunk boundaries.

**Q: Why enrich each chunk with metadata?**

The metadata is `documentId`, `fileName`, `contentType`, `chunkIndex`, `user_id` and `pageNumber`. It's used for:
- **Filtering** (per-user isolation, limiting a question to one document)
- **Citations** (file, page, chunk)
- **Deletion** (remove every vector belonging to a document)

**Q: How do you track ingestion status?**

`DocumentMetadata.status` moves through `UPLOADING → PROCESSING → INDEXED`, or ends at `FAILED`. If anything fails, the catch block saves `FAILED` with an `errorMessage` and throws `DocumentProcessingException`.

**Q: Upload and indexing are synchronous. What's the problem, and how would you fix it?**

Large files block the HTTP thread and can time out. Fixes:
- Save the metadata, return `202 Accepted`, and process in the background with `@Async`, a queue (Kafka/RabbitMQ) or Spring Batch.
- The client polls `GET /documents/{id}` or gets a push notification (WebSocket or SSE) for status changes.
- Make processing idempotent so retries are safe.

---

## 4. Embeddings and vector search (pgvector)

**Q: What is an embedding?**

A fixed-length vector of numbers that represents the meaning of a text. Texts with similar meaning have vectors that are close together. DocMind uses `nomic-embed-text`, which produces **768 dimensions**. The `vectorstore.pgvector.dimensions` setting must match this.

**Q: Why pgvector instead of Pinecone, Qdrant or Weaviate?**

- One database for relational data and vectors: simpler operations, transactions, joins and backups.
- Works well up to millions of vectors.
- Spring AI has first-class support for it (`spring-ai-starter-vector-store-pgvector`).
- A dedicated vector database makes more sense at very large scale or for advanced hybrid search.

**Q: Cosine distance vs Euclidean vs inner product?**

- **Cosine** compares direction and ignores length. It's the standard choice for text embeddings and is what DocMind uses (`cosine_distance`).
- **Euclidean (L2)** compares absolute distance.
- **Inner product** is fastest when vectors are normalized, and then it ranks the same way as cosine.
- Similarity score = `1 - distance` (see `mapToCitation`).

**Q: What is an HNSW index?**

Hierarchical Navigable Small World is a graph-based approximate nearest-neighbour (ANN) index. Searches take roughly logarithmic time, with high recall. Compared with IVFFlat: HNSW builds slower and uses more memory, but it's faster and more accurate to query, and it doesn't need training data.

**Q: How does metadata filtering work in pgvector?**

Spring AI turns `FilterExpressionBuilder` expressions (e.g. `b.and(b.eq("user_id", …), b.eq("documentId", …))`) into JSONPath conditions on the `metadata` JSON column. The filter keys must exactly match the keys written during ingestion.

**Q: What if you change the embedding model?**

The old vectors aren't compatible with the new ones. Change `dimensions`, recreate `vector_store`, and re-embed every document. In production, store the embedding model version in the metadata and do a background re-index.

---

## 5. Spring AI: ChatClient, advisors, memory

**Q: What is `ChatClient`?**

Spring AI's fluent API for calling models. It's set up once in `ProjectConfig`:
```java
builder.defaultSystem("... {doc_context} ...")
       .defaultAdvisors(new SimpleLoggerAdvisor(), MessageChatMemoryAdvisor.builder(memory).build())
       .build();
```
Then each call is `chatClient.prompt().system(...).user(...).advisors(...).call().content()` or `.stream().content()`.

**Q: What are advisors?**

Interceptors, similar to servlet filters, around a model call. They can change the request and response.
- `SimpleLoggerAdvisor` logs requests and responses (its logging is set to DEBUG in the yml).
- `MessageChatMemoryAdvisor` loads the history before the call and saves new messages after it.
- Others: `QuestionAnswerAdvisor` / `RetrievalAugmentationAdvisor` for built-in RAG, and `SafeGuardAdvisor`.

**Q: Why do RAG by hand instead of using `QuestionAnswerAdvisor`?**

Doing it by hand gives full control over the user filter, `topK` and threshold for each request, the context format (file and page tags), and returning citations to the client. The built-in advisor hides the retrieved documents.

**Q: How does conversation memory work?**

- `JpaChatMemory implements ChatMemory` and is a `@Service`, so Spring AI's auto-configured in-memory `ChatMemory` steps aside (`@ConditionalOnMissingBean`).
- `add()` saves `ChatMessage` rows (USER/ASSISTANT) linked to a `Conversation`.
- `get()` loads `findByConversation_IdOrderByCreatedAtAsc(conversationId)`.
- The conversation ID is passed per request with `a.param(ChatMemory.CONVERSATION_ID, conversationId)`.

**Q: What's the risk of sending all of the history every time?**

The token count grows without limit, it gets slow, and old turns can bury the document context. Fixes: a sliding window (`findLastNMessages`, which already exists in the repository), summarising older turns, or a token budget.

**Q: Why is a prompt-template placeholder for document text risky?**

The system prompt is rendered as a template, so `{` or `}` inside the document text (code, JSON) can break rendering. Escape the braces, or put the context in the user message as plain text.

---

## 6. Spring Security and JWT

**Q: Explain the authentication flow.**

1. `POST /auth/register` saves the user with a BCrypt-hashed password and role `USER`.
2. `POST /auth/login` checks the credentials with `AuthenticationManager` (which uses `CustomUserDetailService`), and `JwtService` returns a signed token.
3. On later requests, `JwtAuthenticationFilter` (which extends `OncePerRequestFilter`) reads `Authorization: Bearer …`, validates the signature and expiry, loads the user, and puts the `Authentication` into the `SecurityContext`.
4. Controllers get the user with `(User) authentication.getPrincipal()`.

**Q: Why stateless JWT instead of sessions?**

No session storage on the server, it scales horizontally without sticky sessions, and it suits SPAs and mobile clients. The downside is that a token can't be revoked before it expires. Fixes: short expiry plus refresh tokens, or a denylist in Redis.

**Q: Why is CSRF disabled?**

CSRF attacks rely on the browser sending cookies automatically. Here the token travels in the `Authorization` header, which browsers never add automatically, so CSRF protection isn't needed.

**Q: `addFilterBefore` vs `addFilterAfter` `UsernamePasswordAuthenticationFilter`?**

The JWT filter must run **before** the authorization checks so the `SecurityContext` is filled in. The code uses `addFilterBefore`, which is the standard approach.

**Q: URL rules vs `@PreAuthorize`?**

- URL rules in `SecurityConfig`: `/api/v1/admin/**` → ADMIN, `/api/v1/**` → USER.
- Method-level rules need `@EnableMethodSecurity`, e.g. `@PreAuthorize("hasRole('ADMIN')")` on `GET /documents`.
- `hasRole("ADMIN")` checks for the authority `ROLE_ADMIN`.

**Q: What does 401 vs 403 mean here?**

- **401** (not authenticated): the custom `authenticationEntryPoint` returns it.
- **403** (authenticated but wrong role): the `accessDeniedHandler` returns it.

**Q: What security problems would you fix before production?**

- The default `JWT_SECRET` is in the yml. It should come from a secrets manager and be at least 256 bits.
- `GET/DELETE /documents/{id}` should check that the document belongs to the caller (IDOR risk).
- CORS allows `*` origins together with `allowCredentials=true`, which should be restricted.
- Add rate limiting to login and chat (LLM calls are expensive), file type and size checks, and virus scanning.
- `ddl-auto: update` → use migrations with Flyway or Liquibase.

---

## 7. JPA / Hibernate and the data model

**Q: Describe the entities and their relationships.**

- `User 1─* DocumentMetadata` (`user_id`, LAZY)
- `User 1─* Conversation`
- `Conversation 1─* ChatMessage` (`cascade = ALL`, `orphanRemoval = true`)
- The vectors live in `vector_store`, which Spring AI manages and isn't a JPA entity. They're linked to documents through metadata.

**Q: Why `FetchType.LAZY` on `@ManyToOne`?**

`@ManyToOne` loads eagerly by default, which leads to extra joins and N+1 queries. LAZY loads the related entity only when it's used. Watch out for `LazyInitializationException` outside a transaction. Fixes: DTO projections or `JOIN FETCH`.

**Q: What is the N+1 problem? Where could it happen here?**

One query loads a list, then one more query runs for each item's association. Example: listing documents and reading `doc.getUser().getUsername()` for each one. Fixes: `JOIN FETCH`, `@EntityGraph`, or batch fetching.

**Q: Why is `Conversation.id` a String UUID while `ChatMessage.id` is IDENTITY?**

The client creates or receives the conversation ID and sends it back to continue the chat, and UUIDs can't be guessed. Messages are internal, so an auto-increment ID is simpler. Note: IDENTITY turns off Hibernate's JDBC batch inserts. Use SEQUENCE when you need bulk writes.

**Q: Derived query methods vs `@Query`?**

`findByConversation_IdOrderByCreatedAtAsc` is derived from the method name (the `_` walks into a nested property). `findLastNMessages` uses a native `@Query` because it needs `LIMIT`. JPQL has no `LIMIT`, so you'd use `Pageable` instead.

**Q: Why does a derived delete like `deleteByConversation_Id` need `@Transactional`?**

Derived delete methods load the entities and then delete them one by one, which has to happen inside a transaction. Without one you get `TransactionRequiredException`.

**Q: What does `@Transactional(readOnly = true)` do?**

It tells Hibernate to skip dirty checking and flushing, and can send queries to read replicas. It's used on `JpaChatMemory.get()`.

---

## 8. REST API design and error handling

**Q: Why wrap every response in `ApiResponse<T>`?**

The client gets the same shape every time (`success`, `message`, `data`, `timestamp`), which makes the frontend simpler and keeps errors consistent.

**Q: How are errors handled?**

`GlobalExceptionHandler` (`@RestControllerAdvice`) maps:
- `ResourceNotFoundException` → 404
- `DocumentProcessingException` and `MaxUploadSizeExceededException` → 422 Unprocessable Content
- `MethodArgumentNotValidException` (from `@Valid` + `@NotBlank`) and `IllegalArgumentException` → 400
- any other `Exception` → currently 400. *Good interview point:* unexpected errors should return **500**, because a 400 tells the client its request was wrong when the fault is actually on the server.

**Q: Why `201 Created` for uploads?**

A new resource (the document) is created. If processing were asynchronous, `202 Accepted` would be more accurate.

**Q: How is API versioning done?**

With a URI prefix: `/api/v1/...`. Other options are header or media-type versioning.

**Q: How are file uploads handled?**

`@RequestParam("file") MultipartFile` with `consumes = MULTIPART_FORM_DATA_VALUE`, and limits set in `spring.servlet.multipart` (25 MB per file, 50 MB per request).

---

## 9. Streaming (SSE / Reactor Flux)

**Q: How does `/chat/stream` work in a Spring MVC (non-WebFlux) app?**

The controller returns `Flux<String>`. Spring MVC supports reactive return types and writes each element as it's emitted, using async servlet processing. The service calls `.stream().content()` and appends `Flux.just("[DONE]")` so the client knows the stream has finished.

**Q: Why stream?**

LLM answers take seconds. Streaming improves *perceived* latency (time to first token) and gives a typing effect like ChatGPT.

**Q: How is memory saved when streaming?**

`MessageChatMemoryAdvisor` saves the user message before the call, collects the streamed chunks, and saves the full assistant message when the stream completes.

**Q: Limitations?**

Citations aren't sent in the stream. You could send a final JSON event with them, or use typed SSE events (`ServerSentEvent<T>` with `event: token` / `event: citations`).

---

## 10. Bugs found and fixed: debugging stories

Interviewers love these. Use the STAR format (Situation, Task, Action, Result).

**Story 1: The model ignored uploaded documents**
- *Symptom:* answers were generic, and the logs showed `Retrieved 0 chunks`.
- *Root cause:* ingestion saved the metadata key `user_id`, but retrieval filtered on `userId`. pgvector found no rows matching the filter, so the context was empty, and the system prompt allowed general-knowledge answers.
- *Fix:* changed the filter to `user_id`, which keeps already-indexed documents working.
- *Lesson:* define metadata keys as constants shared by ingestion and retrieval, and add an integration test that ingests a document and retrieves it again.

**Story 2: `chat_messages` was never saved**
- *Root cause:* `RagService` created a local `conversationId` but passed `request.getConversationId()` (null for a new chat) to the memory advisor. `JpaChatMemory.add()` then tried to create a placeholder `Conversation` without a `user`, which broke the `NOT NULL` constraint. The response also returned a different random UUID.
- *Fix:* use the same `conversationId` everywhere.
- *Lesson:* don't let one value travel through different variables with different meanings, and don't silently create rows that break database constraints.

**Story 3: Chat history leaked between users**
- *Root cause:* `JpaChatMemory.get()` used `findAll()`, which loads every message from every conversation.
- *Fix:* `findByConversation_IdOrderByCreatedAtAsc(conversationId)`.
- *Lesson:* every data access in a multi-user system has to be filtered by owner. Write tests for data isolation.

---

## 11. Scalability, performance and production readiness

**Q: Where are the bottlenecks?**

1. LLM inference (seconds per request): the main one.
2. Embedding at ingestion time.
3. Vector search (HNSW keeps it fast).
4. Loading the full chat history on every request.

**Q: How would you scale this?**

- Make the app stateless (it already is with JWT) and run several instances behind a load balancer.
- Move ingestion to a queue with worker services.
- Run the model on GPU instances, or use a managed LLM API.
- Cache answers to repeated questions with a semantic cache (Redis + embeddings).
- Use read replicas for Postgres, and partition `vector_store` by tenant if it gets very large.
- Use connection pooling (HikariCP, the default) and tune its size.

**Q: How would you improve retrieval quality?**

- **Hybrid search:** combine BM25/full-text (Postgres `tsvector`) with vector search, merged with Reciprocal Rank Fusion.
- **Re-ranking:** use a cross-encoder on the top 20 chunks and keep the best 5.
- **Query rewriting:** turn follow-up questions into standalone queries using the chat history.
- **Better chunking:** semantic or heading-aware chunking, and parent-child chunks.
- **Evaluation:** RAGAS-style metrics (faithfulness, context precision and recall).

**Q: What's missing for production?**

- Flyway or Liquibase migrations instead of `ddl-auto: update`
- Async ingestion, retries, and handling for permanently failing jobs
- Ownership checks on every document and conversation endpoint
- Deleting vectors when a document is deleted (by its `documentId` filter)
- Observability: Micrometer metrics for token usage and latency, tracing, structured logs
- Rate limiting and per-user quotas
- Tests: unit tests (Mockito), integration tests (Testcontainers with pgvector), contract tests
- Externalised secrets and a Dockerfile plus CI/CD

**Q: How would you test `RagService`?**

- **Unit tests:** mock `VectorStore` and `ChatClient`, and check the filter expression, the context format and the citation mapping.
- **Integration tests:** Testcontainers `pgvector/pgvector:pg16` plus a stub or small embedding model. Ingest a document, query it, and check the chunks come back and belong to the right user.
- **Evaluation tests:** a fixed set of questions with expected source documents.

---

## 12. System design follow-ups

**Q: Turn DocMind into a multi-tenant SaaS for organisations.**

- Add a `tenant_id` to every table and to the vector metadata, and filter on it everywhere (or use Postgres row-level security).
- Store original files in object storage (S3), with only metadata in Postgres.
- Event-driven ingestion: an upload event triggers parse, chunk, embed and index workers.
- Per-tenant quotas and usage billing based on tokens and storage.
- Sharing documents within an organisation through ACLs stored in metadata.

**Q: How would you support 1M documents?**

Batch embedding, partitioned or sharded vector storage (or a dedicated vector database), HNSW parameters tuned (`m`, `ef_construction`, `ef_search`), async pipelines, and putting metadata filters on indexed columns instead of JSON.

**Q: How do you keep deleted documents out of answers?**

Delete the vectors with `vectorStore.delete(filter documentId == X)` in the same workflow as the metadata delete, or soft-delete and filter on `status = INDEXED`.

---

## 13. Quick-fire questions

| Question | Short answer |
|----------|--------------|
| What does `@RequiredArgsConstructor` do? | Lombok creates a constructor for the final fields, which Spring uses for constructor injection |
| Constructor vs field injection? | Constructor injection: dependencies can't change, they're obvious, and it's easy to test |
| `@Configuration` + `@ConfigurationProperties(prefix="app")`? | Binds `app.rag.*` and `app.cors.*` from the yml to type-safe `AppProperties` |
| What is `OncePerRequestFilter`? | A filter that runs exactly once per request, even with forwards and dispatches |
| BCrypt: why not SHA-256? | BCrypt is deliberately slow, salted and has a configurable cost, so brute-forcing is hard |
| What is `SecurityContextHolder`? | Thread-local storage for the current `Authentication` |
| Spring profiles? | `spring.profiles.active=dev` loads `application-dev.yml` |
| What does `ddl-auto: update` risk? | Unexpected schema changes, and it never drops columns. Use migrations in production |
| Why `@Enumerated(EnumType.STRING)`? | Adding or reordering enum values doesn't corrupt existing data (unlike ORDINAL) |
| What's in a JWT? | Header.Payload.Signature in Base64URL. It's signed, not encrypted, so never put secrets in it |
| Temperature 0.2? | More predictable, factual answers, which suits a document assistant |
| What is `TokenTextSplitter`? | Splits text by token count (not characters), so chunks match the model's limits |
| Why 768 dimensions? | That's the output size of `nomic-embed-text`. It must match the column definition |
| What is Reactor `Flux`? | A stream of 0 to N async elements. It's used here for token streaming |
| How do you get the current user in a controller? | Inject `Authentication` and cast `getPrincipal()` to `User` |
