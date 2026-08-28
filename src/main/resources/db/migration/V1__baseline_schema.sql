-- Baseline schema for household-ledger.
-- See PRD §3 (domain model) and §6.3 (schema) for the rationale behind each
-- design choice called out in comments below.

-- Needed for gen_random_uuid() as a column default (Postgres < 16), and
-- harmless on 16 where it is already built in. Must precede any table that
-- uses it as a DEFAULT.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE household (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name           TEXT NOT NULL,
    currency       CHAR(3) NOT NULL DEFAULT 'INR',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE member (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id   UUID NOT NULL REFERENCES household(id),
    name           TEXT NOT NULL,
    email          TEXT NOT NULL UNIQUE,
    password_hash  TEXT NOT NULL,
    role           TEXT NOT NULL CHECK (role IN ('ADMIN', 'MEMBER')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_member_household ON member(household_id);

CREATE TABLE account (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id   UUID NOT NULL REFERENCES household(id),
    type           TEXT NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'INCOME', 'EXPENSE', 'EQUITY')),
    name           TEXT NOT NULL,
    is_active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (household_id, name)
);

CREATE TABLE ledger_transaction (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id            UUID NOT NULL REFERENCES household(id),
    occurred_on             DATE NOT NULL,
    description             TEXT NOT NULL,
    created_by              UUID NOT NULL REFERENCES member(id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    reverses_transaction_id UUID REFERENCES ledger_transaction(id) UNIQUE
);
-- note: no amount column. this is the design (PRD §3.1) — the amount is an
-- emergent property of the transaction's postings, never stored directly.

CREATE INDEX idx_txn_household_date ON ledger_transaction(household_id, occurred_on DESC);

CREATE TABLE posting (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL REFERENCES ledger_transaction(id),
    account_id     UUID NOT NULL REFERENCES account(id),
    amount_minor   BIGINT NOT NULL,          -- signed; paise. never float/double.
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_posting_account ON posting(account_id);
CREATE INDEX idx_posting_txn     ON posting(transaction_id);

CREATE TABLE refresh_token (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id      UUID NOT NULL REFERENCES member(id),
    token_hash     TEXT NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    revoked_at     TIMESTAMPTZ
);

CREATE INDEX idx_refresh_token_member ON refresh_token(member_id);

-- ---------------------------------------------------------------------------
-- The invariant (PRD §3.2, §6.3): every transaction's postings must sum to
-- zero. This is enforced here at the database layer — the layer that matters,
-- because it makes the guarantee real regardless of which code path wrote
-- the data. Domain and service layers (later phases) add early, friendly
-- validation; this trigger is the backstop that cannot be bypassed.
--
-- DEFERRABLE INITIALLY DEFERRED is essential: postings are inserted one row
-- at a time within a transaction, so an IMMEDIATE trigger would fire after
-- the very first insert and reject every multi-posting transaction. Deferring
-- to commit time lets the full posting set land before the check runs.
-- ---------------------------------------------------------------------------

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

-- Immutability (PRD §3.5): postings are append-only. There is no UPDATE and
-- no DELETE path, at the application layer or otherwise — enforced here so
-- it cannot be bypassed by a stray migration or console session either.

CREATE OR REPLACE FUNCTION reject_posting_mutation()
RETURNS TRIGGER AS $$
BEGIN
  RAISE EXCEPTION 'posting rows are immutable: % is not permitted on posting', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_posting_no_update
  BEFORE UPDATE ON posting
  FOR EACH ROW
  EXECUTE FUNCTION reject_posting_mutation();

CREATE TRIGGER trg_posting_no_delete
  BEFORE DELETE ON posting
  FOR EACH ROW
  EXECUTE FUNCTION reject_posting_mutation();
