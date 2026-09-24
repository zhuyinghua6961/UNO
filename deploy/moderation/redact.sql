\set ON_ERROR_STOP on
UPDATE game.chat_messages
SET content = '[消息已移除]', redacted_at = now()
WHERE id = :'message_id'::uuid AND redacted_at IS NULL
RETURNING id, room_id, channel, sequence, redacted_at;
