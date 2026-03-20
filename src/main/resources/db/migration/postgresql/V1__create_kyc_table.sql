
CREATE TABLE IF NOT EXISTS kyc (
    session_id VARCHAR(128) PRIMARY KEY,
    aid        VARCHAR(128),
    credential_aid VARCHAR(128),
    credential_said VARCHAR(128),
    oobi       TEXT
);
CREATE TABLE allow_list_node (
     node_policy_id VARCHAR(255) NOT NULL,
     tx_hash        VARCHAR(255) NOT NULL,
     output_index   INTEGER      NOT NULL,
     pkh            VARCHAR(255) NOT NULL,
     active         BOOLEAN      NOT NULL,

     CONSTRAINT pk_allow_list_node PRIMARY KEY (node_policy_id)
);

CREATE SEQUENCE te_node_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE te_node (
    id BIGINT PRIMARY KEY DEFAULT nextval('te_node_seq'),
     node_policy_id VARCHAR(255) NOT NULL,
     tx_hash        VARCHAR(255) NOT NULL,
     output_index   INTEGER      NOT NULL,
     vkey           VARCHAR(255) NOT NULL
);