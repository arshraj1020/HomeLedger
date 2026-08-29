# Household Ledger

A double-entry accounting system for shared household finances, exposed as a REST API with a minimal web interface.

> Status: Phases 0–6 complete (scaffold, ledger core, identity, account management, transaction API, querying, reporting). See `household-ledger-prd.md` for the full product requirements document and build sequence.

## Why double-entry

Single-entry systems store an amount and a sign, and there is no relationship between records that can be checked — so there is no way to know whether the data is right. Double-entry stores every transaction as a set of postings that must sum to zero, which means every rupee spent has an identifiable source, errors surface at write time instead of accumulating silently, and liabilities like credit cards model naturally rather than being bolted on.

Balances are a consequence of that structure rather than a stored number, so they cannot drift out of sync with the transactions beneath them.

## The invariant

Every transaction's postings must sum to exactly zero. This is enforced at three layers, but the one that actually matters is the database: a `DEFERRABLE INITIALLY DEFERRED` constraint trigger on `posting`, which fires once at commit rather than after each individual row insert. An immediate trigger would reject every multi-posting transaction, because postings are written one row at a time.

See `src/main/resources/db/migration/V1__baseline_schema.sql` for the trigger definition, and `src/test/java/com/householdledger/InvariantTriggerIT.java` for the test that proves it against a real Postgres container.

## Deliberate tradeoffs

**Derived balances, never stored.** `balance(account)` is `SUM(amount_minor)` over its postings, computed on demand. A stored balance is a cache that can silently diverge from the transactions it summarises; at household scale (thousands of postings, not millions) the aggregate is trivially fast. If it ever became a bottleneck the fix is a materialised rolling balance updated in the same transaction — a deferred decision, not an oversight.

**Money as `long` minor units.** Amounts are paise in a `BIGINT`, wrapped by a `Money` value type that owns all arithmetic and throws on overflow rather than wrapping. Never `float`, never `double`, never `BigDecimal` in a column.

**A transaction has no amount field.** The amount is emergent from the postings. Storing one would create a second source of truth that could disagree with them.

**Append-only.** There is no UPDATE and no DELETE path for postings — at the service layer, or in the database, which rejects both by trigger. Correction is reversal followed by a fresh entry, so the ledger is a complete audit log by construction. Accounts are likewise never deleted, only deactivated.

**Modular monolith, not microservices.** There is no scaling requirement, no independent deployment need, and no team boundary that would justify network calls between components. Module boundaries are enforced by ArchUnit instead: no module may reach another's `internal` package, the ledger core knows nothing about identity, and `shared` depends on nothing.

## Local setup

### Java 21, specifically

This project targets Java 21 (PRD §6.2) and enforces it at build time — `mvn` refuses to run at all on anything outside the `[21,22)` range (see the `maven-enforcer-plugin` binding in `pom.xml`), and `maven-compiler-plugin` is pinned to `<release>21</release>` regardless. This is not pedantry: a newer default JDK (23, 25, ...) compiles test classes at a newer bytecode level, and ArchUnit's bytecode reader (built on ASM) rejects those with `Unsupported class file major version <N>` — a confusing failure that looks like an ArchUnit bug but is actually a JDK mismatch.

One-time setup on macOS:

```bash
# 1. See what's already installed
/usr/libexec/java_home -V

# 2. If no 21.x is listed, install Temurin 21 via Homebrew
brew install --cask temurin21
# (or: brew install openjdk@21 && sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk \
#      /Library/Java/JavaVirtualMachines/openjdk-21.jdk)

# 3. Point THIS shell at it before running Maven
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
java -version   # should now print 21.x
mvn -version    # "Java version:" line should also say 21.x

# 4. (Recommended, and required if your default JAVA_HOME can't be changed
#    globally — e.g. another project needs a newer JDK) — pin a Maven
#    toolchain so `mvn` always builds this project on JDK 21 even if step 3
#    is skipped in a future shell:
JDK21_HOME=$(/usr/libexec/java_home -v 21)
sed "s#REPLACE_WITH_JDK21_HOME#$JDK21_HOME#" toolchains.xml.example > ~/.m2/toolchains.xml
```

Step 3 (or a permanent JAVA_HOME/PATH change, e.g. via `jenv` or your shell profile) satisfies the enforcer rule; step 4's toolchain is what actually routes compilation and test execution to JDK 21, and is the piece that protects CI or a teammate whose default JDK is something else. Both are checked into how the build is configured — see the `maven-enforcer-plugin`, `maven-compiler-plugin`, `maven-toolchains-plugin`, and the `<jdkToolchain>` blocks on `maven-surefire-plugin`/`maven-failsafe-plugin` in `pom.xml`.

### Prerequisites

| | |
|---|---|
| JDK 21 | Enforced by the build (`maven-enforcer-plugin`). See "Java 21, specifically" above. |
| Maven 3.9+ | |
| Docker | Runs the local Postgres, and the integration tests (Testcontainers). |
| `~/.m2/toolchains.xml` | Declares JDK 21 to the build — see `toolchains.xml.example`. The Docker image and CI generate their own. |

### Running the app locally

```bash
cp .env.example .env          # then fill in DB_PASSWORD and JWT_SECRET
docker compose up --build
```

Compose reads `.env`, starts Postgres 16, runs Flyway on first boot, and serves the app on `http://localhost:8080`. It refuses to start if a required value is missing rather than substituting an empty one.

To run the app from Maven against just the containerised database:

```bash
docker compose up -d postgres
set -a; source .env; set +a      # same .env Compose reads — one source of truth
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### Environment variables

**Required in every environment.** The application will not start without these — there are no defaults, because a default secret is a published secret.

| Variable | Purpose |
|---|---|
| `DB_USER` | Postgres username |
| `DB_PASSWORD` | Postgres password |
| `JWT_SECRET` | Access-token signing key, **min 32 bytes**. Generate with `openssl rand -base64 48`. Rotating it invalidates every issued access token. |

**Optional, with production-safe defaults.**

| Variable | Default | Notes |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5432` / `household_ledger` | Or override the whole JDBC URL with `SPRING_DATASOURCE_URL`, which most platforms supply. |
| `PORT` | `8080` | The app binds `0.0.0.0`. |
| `SESSION_COOKIE_SECURE` | `true` | Set `false` only for plain-HTTP local runs. |
| `FORWARD_HEADERS_STRATEGY` | `framework` | Honours `X-Forwarded-Proto/Host` so redirects come back as HTTPS behind a proxy. |
| `DB_POOL_MAX_SIZE` / `DB_POOL_MIN_IDLE` | `10` / `2` | Per instance. Managed Postgres plans cap total connections. |
| `SWAGGER_UI_ENABLED` / `API_DOCS_ENABLED` | `true` | Set `false` to stop publishing a live API console. |
| `LOG_LEVEL_ROOT` / `LOG_LEVEL_APP` | `INFO` | Do not set Hibernate to DEBUG in production — it logs SQL with account names and transaction descriptions. |
| `SPRING_PROFILES_ACTIVE` | *(none)* | **Never set this to `dev` in a deployed environment.** |

**Development only** (`dev` profile): `DEV_ADMIN_EMAIL`, `DEV_ADMIN_PASSWORD`, optionally `DEV_HOUSEHOLD_NAME`, `DEV_ADMIN_NAME`.

Secrets belong in your platform's secret store or a local `.env` (gitignored). Nothing secret is committed to this repository, and nothing secret is baked into the Docker image.

### Database

PostgreSQL 16. Flyway owns the schema and runs on startup; Hibernate is `ddl-auto: validate` and will refuse to start if the two disagree. A **fresh, empty database is the expected starting state** — `V1__baseline_schema.sql` creates everything, and `baseline-on-migrate` is deliberately off so an unrecognised existing database stops the deploy instead of being assumed correct.

The application needs a role that can create tables, extensions (`pgcrypto`) and triggers in its own schema on first run.

### Getting a login for the first time

There is no sign-up page, and there is not going to be one. PRD §6.4 has no registration endpoint and PRD §FR-1 gives members no way to create themselves — an ADMIN manages members. That is correct for a household ledger and wrong for a fresh checkout, which starts with a login page and nobody able to use it.

The `dev` profile closes that gap by creating one household and one ADMIN member at startup. It is off unless you ask for it:

Put `DEV_ADMIN_EMAIL` and `DEV_ADMIN_PASSWORD` in your `.env` (see `.env.example`), then:

```bash
docker compose up -d postgres
set -a; source .env; set +a
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

Or with the whole stack in Compose: set `SPRING_PROFILES_ACTIVE=dev` and the two `DEV_ADMIN_*` values in `.env`, then `docker compose up --build`.

Sign in at `http://localhost:8080/login` with that email and password. The startup log names the address it created; it does **not** print the password, because a log line is a file, a scrollback buffer and a shipped container's stdout.

Re-running is safe: the bootstrap keys on the member's email, so a restart with the same settings does nothing. Nothing outside the `dev` profile ever writes a user — asserted by `DevelopmentAdminBootstrapDisabledIT`, which sets perfectly valid bootstrap properties and proves the bean does not exist.

### Running tests

Requires a Docker daemon for Testcontainers (Docker Desktop running, `docker info` succeeds):

```bash
mvn clean compile  # compile only
mvn test           # unit + property tests, no database
mvn verify         # adds integration tests + ArchUnit against a real Postgres container, plus JaCoCo coverage report
```

Coverage report after `mvn verify`: `target/site/jacoco/index.html`.

## Web interface

Server-rendered Thymeleaf (PRD §FR-7), reachable at `http://localhost:8080` once the app is running. Sign in with a member's email and password.

```
/                          Dashboard — position, this month's spending, recent entries, balances by type
/transactions              List with date, account and description filters, paginated
/transactions/new          Record: simple (one source, one destination), split, or advanced
/transactions/{id}         Detail as debits and credits, with the reverse action
/accounts                  Chart of accounts with balances; create and edit are ADMIN-only
/reports/balance-sheet     All accounts grouped by type, optionally as of a date
/reports/expenses          Totals by category over a range
/reports/trial-balance     The sum of every posting, which must be zero
```

There is **no JavaScript** — not "minimal", none. Everything is a link or a form submission, which is why the pages are served with `Content-Security-Policy: script-src 'none'` and why the split and advanced entry forms offer a fixed set of blank lines instead of an "add row" button. There is no build step and no bundler; the single stylesheet is served as-is.

### Two security chains, deliberately

The API and the UI are authenticated separately, by two `SecurityFilterChain` beans:

| | `/api/**`, docs, actuator | everything else |
|---|---|---|
| Credential | `Authorization: Bearer <jwt>` | session cookie from form login |
| CSRF | off — no cookie to ride | **on** — Thymeleaf's `th:action` adds the token |
| Anonymous request | `401` | redirect to `/login` |
| Session | stateless | `IF_REQUIRED`, id rotated on login |

Merging them would mean either running the browser UI without CSRF protection or making the API answer clients with HTML login pages. **No JWT is ever issued to the browser**, so there is no token in a cookie or in the page for a script to read; the session cookie is the only credential a browser holds, and a browser session does not authenticate the API.

Both doors enforce the same rules with the same code: household scoping comes from the authenticated principal (`AuthenticatedMember`) in both cases, `@PreAuthorize("hasRole('ADMIN')")` guards account management in both, and another household's resource is **404, not 403** in both. Only the error rendering differs — the API returns RFC 7807 problem documents, the UI returns pages — and the UI's pages carry fixed messages, never the exception's, so no identifier reaches the browser.

## Docker

```bash
docker build -t household-ledger:latest .

docker run --rm -p 8080:8080 \
  -e DB_HOST=host.docker.internal -e DB_NAME=household_ledger \
  -e DB_USER=household_ledger -e DB_PASSWORD='...' \
  -e JWT_SECRET="$(openssl rand -base64 48)" \
  household-ledger:latest
```

Multi-stage: Maven + JDK 21 to build, `eclipse-temurin:21-jre-jammy` to run. The container runs as an unprivileged `app` user, sizes the heap from the container's own memory limit (`MaxRAMPercentage=75`), and exits on OOM so an orchestrator restarts it rather than leaving a wedged JVM. `java` runs as PID 1, so `SIGTERM` reaches it and in-flight requests drain before exit.

No secrets are in the image. CI asserts this by grepping image layer history for secret names.

## Deploying

The deployment model is deliberately plain: **one container, one PostgreSQL database.** Nothing is provider-specific — it runs anywhere that can run a container and hand it environment variables.

1. Provision PostgreSQL 16 and an empty database.
2. Build and push the image, or point the platform at this repository's `Dockerfile`.
3. Set `DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` and `JWT_SECRET` (and `PORT` if the platform does not set it). Leave `SPRING_PROFILES_ACTIVE` unset.
4. Deploy. Flyway migrates on first boot; watch for `Successfully applied 2 migrations`.
5. Point the platform's health check at `/actuator/health`.

**Creating the first admin in production.** The `dev` bootstrap is not the mechanism — it must never run in a deployed environment. Create the first household and ADMIN with a one-off `psql` insert (bcrypt the password yourself), or run the image once with a task/console command against the production database. Treat it as a deliberate, audited step.

### Health checks

| Endpoint | Auth | Use |
|---|---|---|
| `/actuator/health` | public | Platform health check. Anonymous callers see `{"status":"UP"}` only — component detail is `when-authorized`. |
| `/actuator/health/liveness` | public | "Restart me." |
| `/actuator/health/readiness` | public | "Stop sending me traffic." |
| `/actuator/metrics` | **requires a token** | |

Nothing else is exposed — no `env`, `beans`, `configprops`, `heapdump` or `threaddump`.

### Deployment notes

- **Rotating `JWT_SECRET`** invalidates every outstanding access token. Refresh tokens are stored hashed in the database and survive.
- **Logs go to stdout only.** There is no file appender anywhere in the project.
- **Run one instance, or add sticky sessions.** The browser UI uses an in-memory HTTP session; the JSON API is stateless and scales freely.
- **Scale the connection pool with care.** `DB_POOL_MAX_SIZE` is *per instance*, and managed Postgres plans cap total connections.
- The `dev` profile is the single most dangerous setting in this application. It creates an ADMIN account. `SPRING_PROFILES_ACTIVE` must not contain `dev` in production.

## API

All endpoints require a bearer token except the auth endpoints themselves. The household is taken from the verified JWT and never from a path or body, so a request cannot name someone else's household. Resources belonging to another household return **404, not 403** — a 403 would confirm they exist.

```
POST   /api/auth/login                     # email + password -> access + refresh token
POST   /api/auth/refresh                   # rotates: the presented token is revoked
POST   /api/auth/logout                    # idempotent

GET    /api/accounts                       # any member
POST   /api/accounts                       # ADMIN only
PATCH  /api/accounts/{id}                  # ADMIN only - rename and/or activate/deactivate
GET    /api/accounts/{id}/balance?asOf=    # any member; derived, never stored

POST   /api/transactions                   # any member - mode: SIMPLE | SPLIT | RAW
GET    /api/transactions                   # any member - filtered, paginated, newest first
GET    /api/transactions/{id}              # any member - full posting detail
POST   /api/transactions/{id}/reverse      # any member - creates the exact inverse

GET    /api/reports/balance-sheet?asOf=    # any member - accounts by type with balances
GET    /api/reports/expenses?from=&to=     # any member - totals per expense category
GET    /api/reports/trial-balance          # any member - sum of all postings, must be zero
```

Account management is ADMIN-only; recording transactions is not. A household where only the admin could enter an expense would defeat the point.

### Recording a transaction

Three entry modes, all producing balanced transactions. The user never types a sign — the destination is debited and the source credited.

**SIMPLE** — one source, one destination, one amount. Covers expenses, income, transfers and card payments alike; only the account types differ.

```json
POST /api/transactions
{ "mode": "SIMPLE", "occurredOn": "2026-08-24", "description": "Weekly groceries",
  "fromAccountId": "…hdfc-card…", "toAccountId": "…groceries…", "amountMinor": 420000 }
```

```json
{ "id": "…", "occurredOn": "2026-08-24", "description": "Weekly groceries",
  "postings": [ { "accountId": "…groceries…", "accountName": "Groceries", "amountMinor":  420000 },
                { "accountId": "…hdfc-card…", "accountName": "HDFC Card", "amountMinor": -420000 } ],
  "reversed": false, "reversesTransactionId": null }
```

**SPLIT** — one source funding several categories, each with its own amount. The source is credited the exact sum, so there is no remainder to allocate.

```json
{ "mode": "SPLIT", "occurredOn": "2026-08-24", "description": "Combined bill",
  "fromAccountId": "…hdfc-card…",
  "destinations": [ { "accountId": "…groceries…",   "amountMinor": 30000 },
                    { "accountId": "…electricity…", "amountMinor": 20000 } ] }
```

**RAW** — an arbitrary, already-signed posting list, for completeness and tests. This is the only mode that can produce an unbalanced request, and it is rejected with 422.

Validation applies identically across all three: at least two postings, postings sum to zero, every account active and in the caller's household, amounts strictly positive (SIMPLE/SPLIT), and the date no more than one day in the future — enough slack for a timezone ahead of the server, not enough for a typo'd year to distort every as-of balance.

### Listing transactions

```
GET /api/transactions?from=2026-08-01&to=2026-08-31&accountId=…&memberId=…&q=grocer&page=0&size=25
```

All parameters are optional and compose. `from`/`to` are inclusive; `accountId` matches a posting on either leg of the transaction; `q` is a case-insensitive substring match on the description, with `%` and `_` treated as literal text. Filters are built as dynamically composed Spring Data JPA **Specifications**, one named predicate per criterion.

The response is a page envelope: `content`, `page`, `size`, `totalElements`, `totalPages`, `hasNext`, `hasPrevious`.

Sort order is fixed at date descending and is not a client parameter — a client-controlled sort would let a caller request an unindexed ordering for no product benefit. Two tiebreakers follow the date: entry time, then id. That is not decoration — several transactions routinely share one date, and a sort with ties is not a total order, so without them a row could appear on two pages or on none.

`page` and `size` are **clamped, not rejected** (`size` to 1..200). An unbounded page size would let one request pull an entire ledger into memory, which is how the latency target gets missed; clamping makes that impossible rather than merely discouraged.

A filter naming an account or member from another household returns an empty page, not an error — the household predicate sits beneath every filter, so there is nothing to leak and nothing to report as missing.

### Reports

**Balance sheet** — every account grouped by type, including deactivated accounts and accounts that have never been posted to (at zero). `asOf` bounds it to postings on or before that date, inclusive; omit it for the current position.

**Expense summary** — totals per expense category over an inclusive date range. Both `from` and `to` are required. Only categories with activity in the range appear; an empty result is a successful "nothing was spent", not a 404.

**Trial balance** — the sum of every posting in the household, which must be zero. The response also carries `postingCount`, because an empty ledger sums to zero too and only one of those is evidence of anything, plus any offending transaction ids. It returns 200 even when unbalanced: that is a successfully computed report of a broken ledger, and a client checking integrity needs to read the body rather than catch an error.

A daily scheduled job runs the same check across every household and logs an ERROR naming the affected transaction ids. The deferred trigger already makes an unbalanced commit impossible, but a trigger only protects writes that go through the normal path — not a restore from a mangled dump, a bulk load run with triggers off, or a future migration that drops it. The check is what would notice.

#### Sign conventions

Postings are stored as plain signed minor units, debits positive, and **nothing below the reporting layer applies an account-type convention** — that is the mitigation the PRD names for its sign-confusion risk. Reporting is the one place the flip happens: debit-normal accounts (asset, expense) read as stored, while credit-normal accounts (liability, income, equity) are negated, so a ₹4,200 card balance stored as `-420000` reads as `420000` to the person who owes it.

Every balance-sheet line therefore carries both figures — `balanceMinor` (what to display) and `signedBalanceMinor` (what is stored) — along with `signFlipped` so a client can label the column. The signed figures across all five sections sum to zero, which makes the balance sheet self-checking against the trial balance without a second call. The presented figures do not sum to zero, and are not meant to.

### Errors

RFC 7807 Problem Details throughout. `422` unbalanced or future-dated or deactivated account, `409` already reversed or reversing a reversal or duplicate account name, `404` not in your household, `403` not an ADMIN, `401` unauthenticated.

### Reversal

`POST /api/transactions/{id}/reverse` creates a new transaction whose postings are the exact sign-inverse of the original, linked back to it. Neither is deleted or edited and both remain queryable. A transaction may be reversed exactly once, and a reversal cannot itself be reversed (both `409`). Reversal works even if an account has since been deactivated — otherwise deactivating an account could strand a mistake as permanently uncorrectable.

### Accounts

Every new household is seeded with `Cash` (asset), `Opening Balances` (equity), and a default expense set. `Opening Balances` is what lets a household open its ledger with existing balances without inventing money — the counterpart posting goes to equity, so the invariant holds on day one.

## Architecture

Modular monolith — see PRD §6.1 for the rationale. Module boundaries are enforced by ArchUnit tests in CI (`ModuleBoundaryArchTest`).

```
household-ledger/
├── ledger/          # accounts, transactions, postings, invariant  ← core
│   ├── domain/      # entities, Money, value objects
│   ├── api/         # public interface exposed to other modules
│   └── internal/    # repositories, services (package-private)
├── identity/        # household, members, auth, JWT
├── reporting/       # balance sheet, expense summary, trial balance
├── web/             # controllers, DTOs, Thymeleaf templates
└── shared/          # common errors, base types, config
```

## Testing approach

- **Domain unit tests** — plain JUnit, no Spring context, no database.
- **Property-based tests (jqwik)** — random posting sets; balanced sets accepted, unbalanced sets rejected, across 1000+ generated cases each. Phase 4 adds the complementary property for the entry modes: however a user describes a movement of money, the postings produced always sum to zero — so a well-formed request can never reach, let alone trip, the database trigger.
- **Integration tests (Testcontainers, real Postgres)** — the invariant, immutability, and cross-household isolation are tested against a real Postgres container. The invariant cannot be verified against H2, so this project never uses an in-memory database for anything invariant-related.
- **Architecture tests (ArchUnit)** — module boundary enforcement: no module reaches another's `internal` package, every cross-module dependency is one-way (so the graph stays acyclic), `shared` depends on nothing, and every `domain`/`api` package is free of Spring, JPA and Jakarta types. Phase 7 adds four rules for the UI: it renders views rather than serialising response bodies, it never touches JWTs or persistence frameworks, and nothing depends on it — so it stays replaceable (PRD §11 lists a React frontend as future scope).
- **Web tests (MockMvc against real Postgres)** — every screen, the real form-login and logout flow, CSRF enforcement on every write, the ADMIN-only restriction, and the household-isolation rule that another household's resource is 404 and its page names nothing about what was asked for.

## Build sequence

See PRD §8. Phase 0 (this scaffold) prototypes and proves out the deferred-trigger mechanism before any application code is built on top of it — the PRD identifies this as the single highest-risk item in the whole project (§10).
