# Demo script and interview prep

## Before the demo (2 minutes, off camera)

```bash
docker compose up --build -d      # API + PostgreSQL 16; Flyway migrates, 5 demo orders are seeded
docker compose ps                 # api should be "healthy"
```

Open three things: Swagger UI at http://localhost:8080 (it redirects there), `requests.http` in VS Code with the REST Client extension, and a terminal.

## The demo (3 to 5 minutes)

**1. What it is (20 s).** "A Spring Boot 3 / Java 21 order service: create, read, list, confirm and cancel orders. The point is the parts that are easy to get wrong in a real service: lifecycle rules, concurrent updates, safe retries and consistent errors. It's an educational starter, so there's no auth."

**2. Health and docs (20 s).** In the terminal:

```bash
curl -s localhost:8080/actuator/health/readiness
```

"Readiness includes the database check, so a load balancer (Render, here) only sends traffic once Flyway has run and the pool can reach Postgres. Liveness doesn't include the DB, so a database blip doesn't get the container restarted." Show Swagger UI briefly: every endpoint, request and error schema is generated from the code.

**3. Create, with an idempotency key (45 s).** Run `requests.http` request 1. Point out: 201, the `Location` header, `ETag: "0"`, and that the total (7797.50) was computed by the server; the request body has no total field at all. Run request 2 (the same key and body): 200, the **same id**, `Idempotent-Replayed: true`. "A client that timed out can retry blindly without creating a second order." Run request 3 (same key, different body): 422, because that is a client bug, not a retry.

**4. Lifecycle (45 s).** Request 6: confirm with `If-Match: "0"` gives CONFIRMED, version 1. Request 8: confirm again gives **409** `invalid-state-transition`, with `currentStatus` and `targetStatus` in the body. "The rule lives in the entity: `Order.confirm()` refuses unless the state machine allows it, so no service or controller can bypass it."

**5. Optimistic locking (60 s).** Request 7: cancel with the stale `If-Match: "0"` gives **412** `stale-version`, with expected and current versions. "That's the explicit check, for clients that send the version they last read." Then open `src/test/java/.../support/OrderApiScenarios.java`, method `parallelConfirmsYieldExactlyOneConflict`: "Two confirms that both read version 0 before either writes. The test clock is a barrier, so both threads are guaranteed to have loaded the row before either flushes; the race reproduces on every run, not by luck. Hibernate's `UPDATE ... WHERE version = 0` matches no row for the loser, so it gets **409** `concurrent-modification`, and the order ends at version 1. The same test runs on H2 and on real Postgres via Testcontainers."

**6. Errors are consistent (30 s).** Request 11 (invalid input): 400 `application/problem+json` with one entry per bad field. Request 14: 404. "Every error, including Spring's own (malformed JSON, a bad enum value), has the same RFC 9457 shape, so a client needs one error parser."

**7. Wrap-up (20 s).** "`./mvnw verify` runs the unit, web-slice, JPA and end-to-end tests, then the Postgres tests; CI also builds the Docker image and runs gitleaks over the history. `render.yaml` deploys it with a managed Postgres."

## 10 likely interview questions

**1. Why do the lifecycle rules live in the entity rather than the service?**
`Order` is the aggregate root: its fields have no setters, and the only way to change status is `confirm()` or `cancel()`, which ask `OrderStatus.canTransitionTo()` and throw `InvalidOrderStateException` otherwise. Any new caller (a message consumer, an admin job, the demo seeder) gets the same rules for free, and the rules are unit-tested without Spring or a database. The service only orchestrates: load, call the domain method, flush.

**2. Optimistic vs pessimistic locking: why optimistic here?**
Conflicting writes to one order are rare, and requests are short. Optimistic locking (`@Version`) takes no lock while reading; Hibernate adds `WHERE version = ?` to the update and the loser gets zero rows, which becomes a 409. Pessimistic locking (`SELECT ... FOR UPDATE`) would make the second request wait instead of failing, which is better when conflicts are frequent and retrying is expensive (e.g. decrementing stock on a hot SKU), but it holds row locks, risks deadlocks and doesn't work across a client's read-then-write over HTTP. The `If-Match`/ETag check extends the optimistic check to the client: they can say "only if it's still the version I saw" and get 412 otherwise.

**3. How do idempotency keys work, and what about two retries arriving at the same time?**
The client sends `Idempotency-Key` on create. The service first looks the key up; if it exists with the same request fingerprint (SHA-256 of the normalised body) it returns the original order with 200 and `Idempotent-Replayed: true`; with a different body it returns 422. If two requests race past the lookup, the database's unique constraint on `idempotency_key` lets exactly one insert commit; the loser catches `DataIntegrityViolationException` and, in a fresh transaction, reads and returns the winner's order. A test fires 8 parallel requests with one key and checks there is one 201, seven 200s and a single id. In production I'd add an expiry for old keys.

**4. Where are the transaction boundaries, and why is `create` not `@Transactional`?**
Confirm and cancel are one `@Transactional` method each: load, change, flush. Flushing inside the method makes an optimistic-lock failure surface there, and the response carries the new version. `create` uses `TransactionTemplate` explicitly because, after a unique-key violation, the failed transaction is rollback-only; the lookup of the winner's order has to run in a new transaction. Reads are `readOnly = true`, and `open-in-view` is off, so no lazy loading happens in the web layer.

**5. Why Flyway instead of `ddl-auto=update`?**
The schema is versioned SQL in git (`V1__create_orders.sql`), reviewed like code, and applied identically in every environment. Hibernate runs with `ddl-auto=validate`, so the app refuses to start if the entities and the schema disagree. The constraints that matter (unique idempotency key, status check, positive quantity and price) are in the database, which is the last guard if application code has a bug. The same migration runs on H2 (PostgreSQL mode) and Postgres, and a Testcontainers test checks `flyway_schema_history` on real Postgres.

**6. How do profiles work here?**
`dev` is the default: in-memory H2 in PostgreSQL mode, H2 console, demo seed data, so `./mvnw spring-boot:run` needs nothing installed. `prod` reads every connection and pool setting from environment variables (see `.env.example`); docker compose and Render use it. Shared settings live in `application.yml`. No credentials are in the repo.

**7. Why problem+json?**
RFC 9457 (which replaced RFC 7807) gives every error one shape: `type`, `title`, `status`, `detail`, `instance`, plus extensions such as `errors[]` for validation or `currentStatus` for a bad transition. `ApiExceptionHandler` extends Spring's `ResponseEntityExceptionHandler`, so framework errors (malformed JSON, type mismatch, 405) share the format too. The catch-all handler logs the stack trace server-side and returns a generic 500, so internals never leak.

**8. How does pagination work, and what would you change at scale?**
Spring Data `Pageable`: `page`, `size` (default 20, capped at 100) and `sort` restricted to an allow-list, so clients can't sort by an unindexed column. Filters are JPA Specifications; composite indexes match the common queries (customer + status + created_at desc). The response is a `PageResponse` record rather than Spring's `PageImpl`, whose JSON shape isn't a stable API. Line items for a page load in one batch query (`default_batch_fetch_size`), avoiding N+1. At large offsets I'd switch to keyset (cursor) pagination, and maybe drop the `count(*)` query.

**9. How would this scale horizontally?**
The service is stateless, so you run more instances behind the load balancer. Correctness doesn't depend on a single instance: optimistic locking and the idempotency unique key are enforced by the database, and the demo seeder uses fixed idempotency keys so two instances starting together can't double-seed. The limits are the database: size the Hikari pool so instances × pool size stays under Postgres's connection limit (or add PgBouncer), add read replicas for list queries, and partition or archive old orders.

**10. What would you add before production?**
Authentication and authorisation (OAuth2 resource server with JWTs; customers see only their orders). An outbox table so "order confirmed" events reach other services reliably in the same transaction. Expiry for idempotency keys. Rate limiting. Distributed tracing (Micrometer Tracing with OpenTelemetry; Prometheus metrics are already exposed, including custom `orders.placed` / `orders.confirmed` counters). Inventory and payment integration with a saga for confirm. Contract tests for clients. Load testing to set real pool sizes and SLOs.
