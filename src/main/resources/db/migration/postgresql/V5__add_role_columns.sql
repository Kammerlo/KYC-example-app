-- Add role to te_node: 0=User, 1=Institutional, 2=vLEI (default vLEI for existing nodes)
ALTER TABLE te_node ADD COLUMN IF NOT EXISTS role INTEGER NOT NULL DEFAULT 2;

-- Add role to allow_list_node: 0=User, 1=Institutional, 2=vLEI (default User for existing nodes)
ALTER TABLE allow_list_node ADD COLUMN IF NOT EXISTS role INTEGER NOT NULL DEFAULT 0;

-- Add dynamic credential attributes storage to kyc table
ALTER TABLE kyc ADD COLUMN IF NOT EXISTS credential_attributes TEXT;
ALTER TABLE kyc ADD COLUMN IF NOT EXISTS credential_role INTEGER;
