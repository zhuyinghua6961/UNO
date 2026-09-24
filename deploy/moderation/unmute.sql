\set ON_ERROR_STOP on
DELETE FROM game.chat_mutes WHERE user_id = :'user_id'::uuid RETURNING user_id;
