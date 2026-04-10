-- Add valid_until column and widen payload column for 37-byte (74 hex) payloads
ALTER TABLE kyc ADD COLUMN IF NOT EXISTS kyc_proof_valid_until BIGINT;
ALTER TABLE kyc ALTER COLUMN kyc_proof_payload TYPE VARCHAR(74);
