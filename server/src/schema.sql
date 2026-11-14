PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;

CREATE TABLE IF NOT EXISTS families (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS members (
    id TEXT PRIMARY KEY,
    family_id TEXT NOT NULL REFERENCES families(id),
    display_name TEXT NOT NULL,
    token_hash BLOB NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS invites (
    code_hash BLOB PRIMARY KEY,
    family_id TEXT NOT NULL REFERENCES families(id),
    expires_at INTEGER NOT NULL,
    redeemed_by TEXT REFERENCES members(id)
);

CREATE TABLE IF NOT EXISTS alerts (
    id TEXT PRIMARY KEY,
    family_id TEXT NOT NULL REFERENCES families(id),
    sender_id TEXT NOT NULL REFERENCES members(id),
    request_id TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE (sender_id, request_id)
);

CREATE INDEX IF NOT EXISTS alerts_family_created ON alerts (family_id, created_at DESC);

CREATE TABLE IF NOT EXISTS acknowledgements (
    alert_id TEXT NOT NULL REFERENCES alerts(id),
    member_id TEXT NOT NULL REFERENCES members(id),
    acknowledged_at INTEGER NOT NULL,
    PRIMARY KEY (alert_id, member_id)
);
