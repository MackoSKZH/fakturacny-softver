-- Pozvanka na konkretnu smenu/rolu aktivity: kto ju prijme, zapise sa do timu. Vytvara ju aj vlastnik aktivity
-- (projektovy manazer), ale len s rolami na citanie - nikdy nie financie, ludia, admin ani vlastnictvo.
ALTER TABLE invite ADD COLUMN shift_id bigint REFERENCES activity_role (id) ON DELETE CASCADE;
CREATE INDEX invite_shift_idx ON invite (shift_id) WHERE shift_id IS NOT NULL;
