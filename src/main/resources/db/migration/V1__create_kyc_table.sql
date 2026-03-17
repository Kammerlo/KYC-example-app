
CREATE TABLE IF NOT EXISTS kyc (
    session_id VARCHAR(128) PRIMARY KEY,
    aid        VARCHAR(128),
    oobi       TEXT
);
