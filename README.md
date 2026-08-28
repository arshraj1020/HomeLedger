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

### Running the app

```bash
docker compose up
```

This builds the app image, starts Postgres 16, runs Flyway migrations on startup, and serves the app on `http://localhost:8080`.

### Running tests

Requires a Docker daemon for Testcontainers (Docker Desktop running, `docker info` succeeds):

```bash
mvn clean compile  # compile only
mvn test           # unit + property tests, no database
mvn verify         # adds integration tests + ArchUnit against a real Postgres container, plus JaCoCo coverage report
```

Coverage report after `mvn verify`: `target/site/jacoco/index.html`.

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
- **Architecture tests (ArchUnit)** — module boundary enforcement: no module reaches another's `internal` package, every cross-module dependency is one-way (so the graph stays acyclic), `shared` depends on nothing, and every `domain`/`api` package is free of Spring, JPA and Jakarta types.

## Build sequence

See PRD §8. Phase 0 (this scaffold) prototypes and proves out the deferred-trigger mechanism before any application code is built on top of it — the PRD identifies this as the single highest-risk item in the whole project (§10).
