\set ON_ERROR_STOP on
UPDATE game.chat_reports SET status = 'RESOLVED'
WHERE id = :'report_id'::uuid AND status = 'OPEN' RETURNING id, status;
