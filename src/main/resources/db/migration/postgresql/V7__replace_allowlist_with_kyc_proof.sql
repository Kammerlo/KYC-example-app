-- Remove Allow List tables (no longer needed — replaced by off-chain KYC proof)
DROP TABLE IF EXISTS allow_list_node;
DROP TABLE IF EXISTS wl_config;

-- Replace allowlist_tx_hash with KYC proof columns
ALTER TABLE kyc DROP COLUMN IF EXISTS allowlist_tx_hash;
ALTER TABLE kyc ADD COLUMN IF NOT EXISTS kyc_proof_payload VARCHAR(74);
ALTER TABLE kyc ADD COLUMN IF NOT EXISTS kyc_proof_signature VARCHAR(128);
ALTER TABLE kyc ADD COLUMN IF NOT EXISTS kyc_proof_entity_vkey VARCHAR(64);
ALTER TABLE kyc ADD COLUMN IF NOT EXISTS kyc_proof_tel_utxo_ref VARCHAR(128);
ALTER TABLE kyc ADD COLUMN IF NOT EXISTS kyc_proof_valid_until BIGINT;
