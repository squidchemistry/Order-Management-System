# Checkpoint status

## checkpoint-2: core runs locally and is tested (2026-11-05)

Verified on Windows 11 with Docker Desktop (engine 29.8.2). There is no JDK on this PC, so every build ran in `maven:3-eclipse-temurin-21`, with a named volume caching `~/.m2`.

### `./mvnw verify`: 52 tests, 0 failures

```bash
docker run --rm -v "$PWD":/workspace -w /workspace -v orders-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  maven:3-eclipse-temurin-21 ./mvnw -B -ntp verify
```

| Suite | Runner | Tests | Result |
|---|---|---|---|
| `OrderTest` (domain, state machine) | surefire | 18 | pass |
| `CreateOrderCommandTest` (idempotency fingerprint) | surefire | 2 | pass |
| `OrderControllerTest` (`@WebMvcTest`) | surefire | 18 | pass |
| `OrderRepositoryTest` (`@DataJpaTest`, Flyway schema on H2) | surefire | 5 | pass |
| `OrderApiH2Test` (full app over HTTP, H2) | surefire | 5 | pass |
| `OrderApiPostgresIT` (Testcontainers, PostgreSQL 16, `prod` profile) | failsafe | 4 | pass |

Surefire: 48 run, 0 failures. Failsafe: 4 run, 0 failures. `BUILD SUCCESS`; the whole run took 138 s on this PC with dependencies already cached.

- **Optimistic locking:** `twoParallelConfirmsGiveOne200AndOne409` passes on H2 and on PostgreSQL. A barrier clock makes both confirms load version 0 before either flushes. The loser gets 409 `concurrent-modification`, and the order ends at version 1.
- **Concurrent idempotent retries:** 8 parallel creates with one key produce exactly one 201 and one order id. The same key with a different body gets 422.
- **IT without Docker:** the same build with the Docker socket not mounted reports the IT as `Skipped: 4`, with the reason "disabledWithoutDocker is true and Docker is not available", and the build still succeeds.

Fixed during this checkpoint (commit `392bd11`): the meter `orders.created` was exported to Prometheus as `orders_total`, because `_created` is a reserved suffix. It's now `orders.placed` (`orders_placed_total`). The H2 test also needed `@AutoConfigureObservability`, because Spring Boot tests turn off metrics export.

### `docker compose up`: API + PostgreSQL 16

```bash
docker compose up --build -d --wait
scripts/smoke.sh            # 20 passed, 0 failed
```

- Both containers reported `healthy`. The API log shows the `prod` profile, `Migrating schema "public" to version "1 - create orders"`, `Successfully applied 1 migration`, and `Seeded 5 demo orders`.
- `/actuator/health`, `/health/liveness` and `/health/readiness` all return `UP`; readiness includes `db` (PostgreSQL).
- `/actuator/prometheus` exports `orders_placed_total`, `orders_confirmed_total`, `orders_cancelled_total` and `orders_idempotent_replays_total`.
- `/` returns 302 to `/swagger-ui/index.html`, which renders all 5 endpoints in a browser.
- Errors are sent as `Content-Type: application/problem+json`.
- After `docker compose restart api`, Flyway reports `Schema "public" is up to date`, the seeder logs `Seed skipped: orders table already has data`, and the data is still there.
- `scripts/smoke.sh` runs the whole lifecycle and every error case: create 201, replay 200 with the same id, key reuse 422, confirm 200, stale `If-Match` 412, illegal transitions 409, invalid input, malformed JSON and a bad filter 400, unknown id 404, OpenAPI and Swagger 200.

### Design note: stale version is 412, a lost race is 409

A client that sends `If-Match` with an old version gets **412 Precondition Failed** (`stale-version`), which is the HTTP status for a failed precondition. Two writes racing on the same version, with no `If-Match`, are caught by the JPA `@Version` check and get **409 Conflict** (`concurrent-modification`).

## checkpoint-3: CI, deploy config and docs (2026-11-05)

| Deliverable | File | Verified how |
|---|---|---|
| GitHub Actions CI: `./mvnw verify` on JDK 21 (Testcontainers ITs included), `docker build`, gitleaks over the full history | `.github/workflows/ci.yml` | `actionlint` 1.7.7: 0 errors. The gitleaks release URL resolves (HTTP 200). It hasn't run on GitHub yet, because the repo isn't pushed |
| Secret scan | (CI job above) | gitleaks 8.21.2 run locally in Docker, over the git history and over the working tree: "no leaks found" |
| Render Blueprint: Docker web service + managed Postgres 16, DB credentials from the database, health check `/actuator/health/readiness` | `render.yaml` | Parses as YAML; field names follow Render's Blueprint spec. Not deployed (needs the user's Render account) |
| Request collection: the whole lifecycle plus 400 / 404 / 409 / 412 / 422 and the idempotent replay | `requests.http` | The same flow runs as `scripts/smoke.sh`: 20/20 against `docker compose` (checkpoint-2 above) |
| Demo script (3 to 5 min) + 10 interview Q&As | `DEMO.md` | Each claim checked against the code and the tests |
| README: mermaid architecture and state diagrams, endpoint table, run with and without Docker, tests, deploy, configuration | `README.md` | Both mermaid diagrams render with mermaid 10.9.1 (no parse errors) |
| License | `LICENSE` (MIT) | |

### Left for the user

1. ~~Create the GitHub repo and push~~ Done 2026-11-05: `main` and the tags are pushed to https://github.com/squidchemistry/Order-Management-System. Check that the first CI run is green.
2. In Render, go to **New → Blueprint**, pick the repo and apply. Then run `scripts/smoke.sh https://<service>.onrender.com`.
