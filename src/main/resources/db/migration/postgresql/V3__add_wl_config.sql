CREATE TABLE wl_config (
    id             INTEGER      PRIMARY KEY DEFAULT 1,
    script_address TEXT         NOT NULL,
    policy_id      TEXT         NOT NULL,
    boot_tx_hash   TEXT         NOT NULL,
    boot_index     INTEGER      NOT NULL,
    te_policy_id   TEXT         NOT NULL,
    CONSTRAINT chk_wl_config_singleton CHECK (id = 1)
);