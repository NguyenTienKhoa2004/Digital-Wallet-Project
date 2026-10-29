-- Funds held by the simulated provider are assets; wallet balances are liabilities.
INSERT INTO flowpay.ledger_accounts (account_code, purpose, accounting_type, currency)
VALUES ('MOCK_PROVIDER_CLEARING', 'PROVIDER_CLEARING', 'ASSET', 'USD');

-- Callback delivery is durable and retried after a crash or lost acknowledgement.
ALTER TABLE mock_provider.payments
    ADD COLUMN callback_delivered_at timestamptz,
    ADD COLUMN callback_next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN callback_attempt_count integer NOT NULL DEFAULT 0
        CHECK (callback_attempt_count >= 0);

CREATE INDEX idx_mock_payment_callback_due
    ON mock_provider.payments (callback_next_attempt_at)
    WHERE callback_delivered_at IS NULL AND status IN ('SUCCESS', 'FAILED');
