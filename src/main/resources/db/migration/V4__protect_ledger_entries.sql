-- Official ledger entries are append-only, including for direct application SQL.
CREATE FUNCTION flowpay.reject_ledger_entry_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Ledger entries are append-only; use a new compensating transaction'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER ledger_entries_append_only
BEFORE UPDATE OR DELETE ON flowpay.ledger_entries
FOR EACH ROW EXECUTE FUNCTION flowpay.reject_ledger_entry_mutation();
