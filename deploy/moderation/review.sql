\set ON_ERROR_STOP on
SELECT id, room_id, message_id, reporter_user_id, reported_user_id,
       reason, content_snapshot, created_at, expires_at
FROM game.chat_reports
WHERE status = 'OPEN' AND expires_at > now()
ORDER BY created_at, id
LIMIT 50;
