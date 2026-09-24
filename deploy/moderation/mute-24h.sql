\set ON_ERROR_STOP on
INSERT INTO game.chat_mutes(user_id, muted_until, reason, updated_at)
VALUES (:'user_id'::uuid, now() + interval '24 hours', 'Operator review', now())
ON CONFLICT (user_id) DO UPDATE SET
    muted_until = GREATEST(game.chat_mutes.muted_until, EXCLUDED.muted_until),
    reason = EXCLUDED.reason,
    updated_at = EXCLUDED.updated_at;
SELECT user_id, muted_until FROM game.chat_mutes WHERE user_id = :'user_id'::uuid;
