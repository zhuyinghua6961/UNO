ALTER TABLE game.matches DROP CONSTRAINT matches_mode_check;
ALTER TABLE game.matches ADD CONSTRAINT matches_mode_check CHECK (mode IN ('CLASSIC', 'TEAM_2V2'));

ALTER TABLE game.match_players ADD COLUMN team_snapshot VARCHAR(1)
    CHECK (team_snapshot IS NULL OR (seat % 2 = 0 AND team_snapshot = 'A')
        OR (seat % 2 = 1 AND team_snapshot = 'B'));
