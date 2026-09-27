-- Import vypisu z banky: jedinecna referencia pohybu z banky zabrani dvojitemu zapisu toho isteho vypisu.
ALTER TABLE ledger_entry ADD COLUMN bank_ref text;
CREATE UNIQUE INDEX ledger_entry_bank_ref_idx ON ledger_entry (bank_ref) WHERE bank_ref IS NOT NULL;
