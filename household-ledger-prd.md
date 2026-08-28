# Household Ledger — Product Requirements Document

**Version:** 1.0
**Status:** Draft for build
**Owner:** Solo developer
**Target build time:** 2–3 weeks part-time

---

## 1. Overview

### 1.1 One-line description

A double-entry accounting system for shared household finances, exposed as a REST API with a minimal web interface.

### 1.2 Problem statement

Money in a household is not a list of expenses. It is a set of balances between people and accounts. When Papa pays the electricity bill from his salary account and Mummy buys groceries on a shared credit card, a conventional expense tracker records two rows with a rupee amount and a category — and loses everything that matters. It cannot tell you what the credit card balance is. It cannot tell you who has effectively funded more of the month. It cannot tell you whether the numbers are even internally consistent, because there is nothing to be consistent with.

Consumer expense trackers optimise for fast data entry at the cost of correctness. Accounting software is correct but is built for businesses and is unusable for a family. This project sits deliberately in between: real accounting mechanics, household-shaped vocabulary.

### 1.3 Why double-entry

Single-entry systems store an amount and a sign. There is no way to verify that the data is right, because there is no relationship between records that can be checked. Double-entry stores every transaction as a set of postings that must sum to zero, which means:

- Every rupee spent has an identifiable source. Money does not appear or vanish.
- Errors surface immediately at write time rather than silently accumulating.
- Account balances are derived, not stored, so they cannot drift out of sync with the underlying data.
- Liabilities (credit cards, borrowed money) model naturally instead of being bolted on.

This is the technical spine of the project and the reason it is not a tutorial expense tracker.

### 1.4 Non-goals for v1

Explicitly out of scope. Listed here so scope creep is a conscious decision rather than an accident.

- Bank statement import and reconciliation
- Inter-member debt settlement and simplification
- Budget periods, allocations, and overspend projection
- Recurring bill scheduling and reminders
- PDF statement generation
- Multi-currency
- Mobile app
- Multi-household support (one household per deployment instance)
- Rich React frontend

Several of these are natural v2 features and are noted in §11.

---

## 2. Users and use cases

### 2.1 Personas

**The record-keeper.** One member of the household who actually enters transactions. Wants entry to be fast and wants to trust the numbers. Technical enough to not be scared of the word "account."

**The viewer.** Other household members. Wants to see where the money went and what the card balance is. Does not enter data.

### 2.2 Core user stories

| ID | As a… | I want to… | So that… |
|---|---|---|---|
| US-1 | record-keeper | record a cash or card expense in one form | entry is fast enough that I actually do it |
| US-2 | record-keeper | record income arriving in an account | the ledger reflects real money in |
| US-3 | record-keeper | record a transfer between two accounts | moving money between my own accounts is not counted as spending |
| US-4 | record-keeper | record a credit card payment | the card liability reduces and the bank balance drops |
| US-5 | viewer | see current balance of every account | I know what we actually have and owe |
| US-6 | viewer | see spending by expense account for a date range | I know where the money went |
| US-7 | record-keeper | reverse a transaction I entered wrongly | mistakes are correctable without destroying the audit trail |
| US-8 | viewer | list and filter transactions | I can find a specific past entry |
| US-9 | record-keeper | create accounts for our real bank accounts and cards | the model matches our actual finances |
| US-10 | any member | log in securely | household financial data is not public |

### 2.3 Key user flows

**Recording an expense (US-1)**
The user picks a payment source (e.g. "HDFC Card"), an expense category (e.g. "Groceries"), an amount, a date, and an optional note. The system constructs a two-posting transaction: debit the expense account, credit the source account. The user never sees the word "posting."

**Correcting a mistake (US-7)**
The user selects a transaction and chooses "reverse." The system creates a new transaction whose postings are the sign-inverse of the original, linked back to it. Both remain visible. Neither is deleted or edited.

---

## 3. Domain model

### 3.1 Concepts

**Household** — the top-level container. One per deployment in v1.

**Member** — a person in the household with login credentials and a role. Members do not hold balances in v1; they are actors, not accounts.

**Account** — a bucket that money flows into or out of. Five types, following standard accounting classification:

| Type | Normal balance | Household examples |
|---|---|---|
| `ASSET` | Debit | HDFC Savings, Cash in wallet, Fixed deposit |
| `LIABILITY` | Credit | HDFC Credit Card, Loan from uncle |
| `INCOME` | Credit | Papa's Salary, Rental income |
| `EXPENSE` | Debit | Groceries, Electricity, School Fees |
| `EQUITY` | Credit | Opening Balances |

**Transaction** — an event that moved money. Has a date, a description, a creator, and a set of postings. **Has no amount field.** The amount is an emergent property of its postings.

**Posting** — one leg of a transaction: an account, a signed amount, and a direction. Postings are immutable once written.

### 3.2 The invariant

> For every transaction, the sum of the signed amounts of its postings is exactly zero.

This is enforced in three independent places, deliberately:

1. **Domain layer** — the `Transaction` aggregate refuses to be constructed unbalanced.
2. **Service layer** — validated before persistence, producing a clean API error.
3. **Database layer** — a deferred constraint trigger that fires at commit and rejects the transaction.

Layer 3 is the one that matters. Layers 1 and 2 give good error messages; layer 3 makes the guarantee real regardless of what code path wrote the data. A reviewer asking "how do you know your data is correct" gets a concrete answer: the database will not accept an unbalanced entry.

### 3.3 Money representation

Amounts are stored as `BIGINT` in minor units (paise). Never `float`, never `double`, never `BigDecimal` in the column. A dedicated `Money` value type wraps the long in Java and owns all arithmetic. Division and splitting are not required in v1; if added later, remainder allocation must be explicit.

Currency is fixed to INR in v1 and stored on the household for forward compatibility.

### 3.4 Balance derivation

Balances are **never stored**. `balance(account) = SUM(signed_amount) OVER postings WHERE account_id = ?`, optionally bounded by date.

This is the correct default: a stored balance is a cache that can silently diverge from truth. At household scale (thousands of postings, not millions) the aggregate query is trivially fast. If performance ever became a concern, the fix is a materialised rolling balance updated in the same transaction — noted in the README as a deliberate deferred decision, not an oversight.

### 3.5 Immutability and correction

Postings are append-only. There is no UPDATE and no DELETE path.

- **Reversal** — creates a mirror transaction with inverted postings, linked via `reverses_transaction_id`.
- **Correction** — reversal followed by a fresh correct entry. Not a distinct operation.

Consequence: the ledger is a complete audit log by construction. This is also the honest answer to "what happens when a user edits a six-month-old entry."

---

## 4. Functional requirements

### FR-1: Authentication and authorisation
- Members log in with email and password. Passwords hashed with bcrypt.
- JWT access token (15 min) plus rotating refresh token (7 days), refresh tokens persisted and revocable.
- Two roles: `ADMIN` (full write access, can manage accounts and members) and `MEMBER` (can record transactions, read everything).
- Every request is scoped to the authenticated member's household. Cross-household access must be impossible even with a forged ID in the path.

### FR-2: Account management
- Create, rename, and deactivate accounts. Accounts are never deleted.
- Account must have a type from the five-value enum and a name unique within its household.
- Deactivated accounts reject new postings but remain in historical queries.
- Seeded on household creation: a default expense set, an `Opening Balances` equity account, and a `Cash` asset account.

### FR-3: Transaction recording
Three entry modes, all producing balanced transactions:

- **Simple** — one source, one destination, one amount. Covers expenses, income, transfers, and card payments. This is the default path and handles well over 90% of real entries.
- **Split** — one source, multiple destination accounts with individual amounts. For a single bill covering several categories.
- **Raw** — arbitrary posting list. Exposed via API for completeness and used in tests; not surfaced prominently in the UI.

Validation:
- At least two postings.
- Postings sum to zero.
- All accounts active and belonging to the caller's household.
- Amount strictly positive on each posting.
- Date not in the future beyond a small tolerance.

### FR-4: Transaction reversal
- Any transaction may be reversed exactly once.
- Reversing an already-reversed transaction returns `409 Conflict`.
- A reversal transaction cannot itself be reversed.

### FR-5: Querying
- List transactions filtered by date range, account, member, and free-text description match. Paginated, sorted by date descending.
- Retrieve a single transaction with its full posting detail.
- Account balance, optionally as-of a date.
- Balance sheet: all accounts grouped by type with balances.
- Expense summary: totals grouped by expense account over a date range.

Filtering is implemented with Spring Data JPA **Specifications**, composed dynamically. Raw Criteria API is avoided — it is verbose, hard to read, and offers no advantage here.

### FR-6: Trial balance
- An endpoint returning the sum of all postings across the entire household, which must be zero.
- A scheduled job runs the same check daily and logs an error with affected transaction IDs on failure.

This exists to be demonstrable. It is the observable proof that the invariant holds across the whole dataset, not just at write time.

### FR-7: Minimal web interface
Server-rendered, Thymeleaf. Deliberately plain.

- Login page
- Dashboard: account balances grouped by type
- Transaction list with filters
- Quick-entry form (simple mode)
- Transaction detail with reverse action

No React, no build step, no SPA state management. The backend is the deliverable; the UI exists so the project is clickable.

---

## 5. Non-functional requirements

| Requirement | Target |
|---|---|
| API latency | p95 under 200ms for reads at 10k postings |
| Correctness | Trial balance zero at all times; enforced at DB level |
| Test coverage | Domain and service layers above 80% line coverage |
| Availability | Best-effort; single instance is acceptable |
| Security | No plaintext secrets in repo; JWT secret via env var |
| Observability | Actuator health and metrics exposed |
| Auditability | Full history reconstructable; no destructive operations |

---

## 6. Technical architecture

### 6.1 Shape

**Modular monolith.** Not microservices. There is no scaling requirement, no independent deployment need, and no team boundary that would justify network calls between components. A well-organised monolith with enforced module boundaries is the correct engineering choice and should be stated as such in the README.

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

Module boundaries enforced by **ArchUnit** tests in CI: no module may reach into another's `internal` package. This is cheap to add and is a strong signal of intent.

### 6.2 Stack

| Layer | Choice | Rationale |
|---|---|---|
| Language | Java 21 | Records for DTOs and value objects, sealed interfaces for the posting hierarchy, pattern matching |
| Framework | Spring Boot 3.x | |
| Web | Spring MVC | Blocking is correct here; WebFlux adds complexity for zero benefit at this scale |
| Persistence | Spring Data JPA + Hibernate | |
| Database | PostgreSQL 16 | Deferred constraint triggers are required for the invariant; SQLite and MySQL cannot do this cleanly |
| Migrations | Flyway | Versioned, reviewable, checked into git |
| Security | Spring Security + JJWT | |
| Templates | Thymeleaf | |
| API docs | springdoc-openapi | Swagger UI at `/swagger-ui.html` |
| Testing | JUnit 5, Testcontainers, ArchUnit, AssertJ | Real Postgres in tests — the invariant cannot be tested against H2 |
| Property tests | jqwik | Generate random posting sets, assert the invariant holds or is rejected |
| Build | Maven | |
| Container | Docker + Docker Compose | app + postgres |
| CI | GitHub Actions | build → unit → integration → ArchUnit |
| Deploy | Railway or Fly.io + managed Postgres | Must be publicly clickable |

### 6.3 Schema

```sql
household (
  id             UUID PRIMARY KEY,
  name           TEXT NOT NULL,
  currency       CHAR(3) NOT NULL DEFAULT 'INR',
  created_at     TIMESTAMPTZ NOT NULL
);

member (
  id             UUID PRIMARY KEY,
  household_id   UUID NOT NULL REFERENCES household(id),
  name           TEXT NOT NULL,
  email          TEXT NOT NULL UNIQUE,
  password_hash  TEXT NOT NULL,
  role           TEXT NOT NULL,            -- ADMIN | MEMBER
  created_at     TIMESTAMPTZ NOT NULL
);

account (
  id             UUID PRIMARY KEY,
  household_id   UUID NOT NULL REFERENCES household(id),
  type           TEXT NOT NULL,            -- ASSET|LIABILITY|INCOME|EXPENSE|EQUITY
  name           TEXT NOT NULL,
  is_active      BOOLEAN NOT NULL DEFAULT TRUE,
  created_at     TIMESTAMPTZ NOT NULL,
  UNIQUE (household_id, name)
);

ledger_transaction (
  id                      UUID PRIMARY KEY,
  household_id            UUID NOT NULL REFERENCES household(id),
  occurred_on             DATE NOT NULL,
  description             TEXT NOT NULL,
  created_by              UUID NOT NULL REFERENCES member(id),
  created_at              TIMESTAMPTZ NOT NULL,
  reverses_transaction_id UUID REFERENCES ledger_transaction(id) UNIQUE
);
-- note: no amount column. this is the design.

posting (
  id             UUID PRIMARY KEY,
  transaction_id UUID NOT NULL REFERENCES ledger_transaction(id),
  account_id     UUID NOT NULL REFERENCES account(id),
  amount_minor   BIGINT NOT NULL,          -- signed; paise
  created_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_posting_account   ON posting(account_id);
CREATE INDEX idx_posting_txn       ON posting(transaction_id);
CREATE INDEX idx_txn_household_date ON ledger_transaction(household_id, occurred_on DESC);

refresh_token (
  id             UUID PRIMARY KEY,
  member_id      UUID NOT NULL REFERENCES member(id),
  token_hash     TEXT NOT NULL,
  expires_at     TIMESTAMPTZ NOT NULL,
  revoked_at     TIMESTAMPTZ
);
```

**The invariant trigger:**

```sql
CREATE OR REPLACE FUNCTION assert_transaction_balanced()
RETURNS TRIGGER AS $$
DECLARE
  total BIGINT;
  leg_count INT;
BEGIN
  SELECT COALESCE(SUM(amount_minor), 0), COUNT(*)
    INTO total, leg_count
    FROM posting WHERE transaction_id = NEW.transaction_id;

  IF leg_count < 2 THEN
    RAISE EXCEPTION 'Transaction % has fewer than two postings', NEW.transaction_id;
  END IF;

  IF total <> 0 THEN
    RAISE EXCEPTION 'Transaction % is unbalanced by % minor units',
      NEW.transaction_id, total;
  END IF;

  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_posting_balanced
  AFTER INSERT ON posting
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW
  EXECUTE FUNCTION assert_transaction_balanced();
```

`DEFERRABLE INITIALLY DEFERRED` is the essential detail. Postings are inserted one at a time; an immediate trigger would fire after the first insert and fail every time. Deferring to commit lets the full set land before the check runs.

Additionally, an immutability trigger blocks `UPDATE` and `DELETE` on `posting` entirely.

### 6.4 API surface

```
POST   /api/auth/login
POST   /api/auth/refresh
POST   /api/auth/logout

GET    /api/accounts
POST   /api/accounts
PATCH  /api/accounts/{id}                 # rename, activate/deactivate
GET    /api/accounts/{id}/balance?asOf=

POST   /api/transactions                  # simple | split | raw
GET    /api/transactions                  # filtered, paginated
GET    /api/transactions/{id}
POST   /api/transactions/{id}/reverse

GET    /api/reports/balance-sheet?asOf=
GET    /api/reports/expenses?from=&to=
GET    /api/reports/trial-balance
```

**Sample request — simple expense:**
```json
POST /api/transactions
{
  "mode": "SIMPLE",
  "occurredOn": "2026-08-24",
  "description": "Weekly groceries",
  "fromAccountId": "…hdfc-card…",
  "toAccountId":   "…groceries…",
  "amountMinor": 420000
}
```

**Sample response:**
```json
{
  "id": "…",
  "occurredOn": "2026-08-24",
  "description": "Weekly groceries",
  "postings": [
    { "accountId": "…groceries…", "accountName": "Groceries",   "amountMinor":  420000 },
    { "accountId": "…hdfc-card…", "accountName": "HDFC Card",   "amountMinor": -420000 }
  ],
  "reversed": false
}
```

**Error contract** — RFC 7807 Problem Details:
```json
{
  "type": "https://…/errors/unbalanced-transaction",
  "title": "Transaction is not balanced",
  "status": 422,
  "detail": "Postings sum to 500 minor units; expected 0",
  "instance": "/api/transactions"
}
```

---

## 7. Testing strategy

The tests are part of the deliverable, not an afterthought. They are what make the correctness claim credible.

**Domain unit tests** — `Money` arithmetic, transaction construction rejecting unbalanced posting sets, reversal producing exact inverses.

**Property-based tests (jqwik)** — generate random sets of postings; assert that balanced sets are accepted and unbalanced sets are rejected, across thousands of generated cases. This is the headline test and is worth calling out in the README.

**Integration tests (Testcontainers, real Postgres)** —
- Attempting to persist an unbalanced transaction by bypassing the service layer must fail at commit.
- `UPDATE` and `DELETE` on `posting` must be rejected.
- Balance derivation matches hand-computed expectations across a fixture ledger.
- A member of household A cannot read or write anything belonging to household B.

**Architecture tests (ArchUnit)** — module boundary enforcement, no controller reaching a repository directly, no JPA entity leaking past the module boundary into a DTO.

**API tests** — MockMvc against the full context for the main flows.

---

## 8. Build sequence

Ordered so that each phase is independently demoable and the risky part comes first.

| Phase | Deliverable | Est. |
|---|---|---|
| 0 | Repo, Maven, Docker Compose, Flyway baseline, CI skeleton | 0.5 d |
| 1 | **Ledger core** — entities, `Money`, invariant at all three layers, property tests, Testcontainers integration tests | 3 d |
| 2 | Identity — members, JWT auth, household scoping, authorisation tests | 2 d |
| 3 | Account management + seeding | 1 d |
| 4 | Transaction API — simple, split, raw; reversal | 2 d |
| 5 | Querying — Specifications, pagination, filters | 1.5 d |
| 6 | Reporting — balance sheet, expense summary, trial balance, scheduled check | 1.5 d |
| 7 | Thymeleaf UI — login, dashboard, list, entry form, detail | 2 d |
| 8 | ArchUnit, OpenAPI polish, Actuator, error contract | 1 d |
| 9 | Deploy, seed demo data, README | 1 d |

**Phase 1 is where the project lives or dies.** If the ledger is correct, everything above it is straightforward plumbing. Do not move past it until the property tests and the database-level rejection test both pass.

---

## 9. Acceptance criteria

The project is done when all of the following hold:

- [ ] An unbalanced transaction cannot be persisted by any code path, demonstrated by a test that bypasses the service layer.
- [ ] `UPDATE` and `DELETE` on `posting` are rejected by the database.
- [ ] Trial balance endpoint returns zero on a ledger with at least 200 seeded transactions.
- [ ] Reversal produces an exact inverse and both transactions remain queryable.
- [ ] A member of one household receives 404 (not 403 — no existence leak) for another household's resources.
- [ ] Property tests run at least 1000 generated cases and pass.
- [ ] CI is green: build, unit, integration, ArchUnit.
- [ ] Application is deployed at a public URL with demo credentials in the README.
- [ ] Swagger UI is reachable and every endpoint is documented.
- [ ] README explains the double-entry rationale, the deferred-trigger detail, and the derived-balance tradeoff.

---

## 10. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Deferred trigger behaves unexpectedly with Hibernate's flush ordering | High — blocks the core feature | Prototype this in phase 0 before building anything on top |
| Scope creep into reconciliation and settlement | High — project never finishes | Non-goals in §1.4 are binding; v2 is a separate decision |
| Thymeleaf UI absorbs disproportionate time | Medium | Timebox to 2 days; ugly is acceptable, unfinished is not |
| Account-type sign conventions confuse the implementation | Medium | Store signed amounts only; apply presentation sign at the reporting layer, never in the domain |

---

## 11. Future scope (v2+)

In rough priority order, should the project be extended:

1. **Bank statement reconciliation** — CSV/PDF import, fuzzy matching on amount + date window + merchant string, unmatched-line review queue. Highest value; reconciliation is a genuinely hard and unglamorous problem.
2. **Inter-member settlement** — per-member balances and debt simplification to minimise transfer count. Contains a real graph algorithm.
3. **Budget periods and projection** — burn-rate forecast accounting for known upcoming recurring bills.
4. **Recurring bills** — RRULE schedules, generation, reminders with ShedLock to prevent duplicate dispatch across instances.
5. **PDF statements** — OpenPDF or PDFBox.
6. **React frontend** — replacing Thymeleaf once the API is stable.

---

## 12. README requirements

The README is the project's advocate when the author is not in the room. It must contain, in this order:

1. One-line description and a screenshot.
2. Live demo URL and demo credentials.
3. **Why double-entry** — the correctness argument from §1.3, in three sentences.
4. **The invariant** — the trigger, the deferred-constraint detail, and why an immediate trigger fails.
5. **Deliberate tradeoffs** — derived vs stored balances, monolith vs microservices, Specifications vs Criteria API. Each with the reasoning. This section is what distinguishes an engineer from a tutorial-follower.
6. Local setup: `docker compose up`, one command.
7. Architecture diagram (module boundaries).
8. Testing approach, with the property-test and DB-rejection tests called out.
