# Household Ledger

A double-entry accounting system for shared household finances, exposed as a REST API with a minimal web interface.

> Status: Phase 0 (scaffold) complete. See `household-ledger-prd.md` for the full product requirements document and build sequence.

## Why double-entry

*(To be filled in — PRD §1.3 / §12.3 — once the ledger core (Phase 1) exists.)*

## The invariant

Every transaction's postings must sum to exactly zero. This is enforced at three layers, but the one that actually matters is the database: a `DEFERRABLE INITIALLY DEFERRED` constraint trigger on `posting`, which fires once at commit rather than after each individual row insert. An immediate trigger would reject every multi-posting transaction, because postings are written one row at a time.

See `src/main/resources/db/migration/V1__baseline_schema.sql` for the trigger definition, and `src/test/java/com/householdledger/InvariantTriggerIT.java` for the test that proves it against a real Postgres container.

## Deliberate tradeoffs

*(To be filled in as each is implemented — PRD §12.5: derived vs stored balances, monolith vs microservices, Specifications vs Criteria API.)*

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
- **Property-based tests (jqwik)** — random posting sets; balanced sets accepted, unbalanced sets rejected, across 1000+ generated cases (Phase 1).
- **Integration tests (Testcontainers, real Postgres)** — the invariant, immutability, and cross-household isolation are tested against a real Postgres container. The invariant cannot be verified against H2, so this project never uses an in-memory database for anything invariant-related.
- **Architecture tests (ArchUnit)** — module boundary enforcement.

## Build sequence

See PRD §8. Phase 0 (this scaffold) prototypes and proves out the deferred-trigger mechanism before any application code is built on top of it — the PRD identifies this as the single highest-risk item in the whole project (§10).
