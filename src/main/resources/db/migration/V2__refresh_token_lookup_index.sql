-- Phase 2 (identity). V1 already created the `member` and `refresh_token`
-- tables per PRD §6.3; this migration adds only what the refresh-token flow
-- actually needs at runtime.

-- Refresh and logout both resolve a presented token by looking up the hash
-- of that token (the raw value is never stored — see RefreshTokenStore).
-- Without this index that lookup is a sequential scan of every token row
-- ever issued, on the hot path of every token refresh.
--
-- UNIQUE rather than a plain index, for two reasons: the hash of a 256-bit
-- random token should never legitimately repeat, so a collision indicates a
-- generation bug worth failing loudly on rather than silently accepting;
-- and uniqueness lets the query planner treat the lookup as at most one row.
CREATE UNIQUE INDEX idx_refresh_token_hash ON refresh_token(token_hash);

-- Supports revoking or auditing all sessions belonging to one member.
-- V1 created idx_refresh_token_member on member_id alone; this partial index
-- narrows the common question ("which of this member's tokens are still
-- live?") to just the unrevoked rows, which stays small as revoked history
-- accumulates — history that is deliberately retained rather than deleted,
-- consistent with the project's append-only stance (PRD §3.5).
CREATE INDEX idx_refresh_token_active
    ON refresh_token(member_id, expires_at)
    WHERE revoked_at IS NULL;
