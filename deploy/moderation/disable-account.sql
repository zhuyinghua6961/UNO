\set ON_ERROR_STOP on
BEGIN;
UPDATE accounts SET status = 'DISABLED', updated_at = GREATEST(created_at, now())
WHERE id = :'user_id'::uuid RETURNING id, status;
UPDATE sessions SET revoked_at = GREATEST(created_at, now())
WHERE account_id = :'user_id'::uuid AND revoked_at IS NULL;
UPDATE account_tokens SET consumed_at = GREATEST(created_at, now())
WHERE account_id = :'user_id'::uuid AND consumed_at IS NULL;
COMMIT;
