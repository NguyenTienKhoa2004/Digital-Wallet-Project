-- FlowPayERD.md: seven P0 tables and the P1 reconciliation history table.
-- Amounts and balances are USD cents. All timestamps have a time zone.
-- UUIDs and creation timestamps have defaults; applications must set updated_at
-- on insert and update it explicitly on every subsequent change.
-- Lengths below are MVP storage limits; request_hash stores hexadecimal SHA-256.
-- Cross-table posting invariants and state transitions require service-level
-- transactions/locking. This migration does not provision application DB roles;
-- a separate non-owner role must not receive UPDATE/DELETE on ledger_entries.

CREATE TABLE flowpay.users (
    user_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username varchar(255) NOT NULL,
    email varchar(320) NOT NULL,
    role varchar(16) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_lowercase CHECK (email = lower(email)),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'))
);

CREATE TABLE flowpay.wallets (
    wallet_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,
    balance bigint NOT NULL DEFAULT 0,
    currency varchar(3) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_wallets_user FOREIGN KEY (user_id)
        REFERENCES flowpay.users (user_id) ON DELETE RESTRICT,
    CONSTRAINT uq_wallets_user UNIQUE (user_id),
    CONSTRAINT ck_wallets_balance CHECK (balance >= 0),
    CONSTRAINT ck_wallets_currency CHECK (currency = 'USD'),
    CONSTRAINT ck_wallets_status CHECK (status IN ('ACTIVE', 'FROZEN'))
);

CREATE TABLE flowpay.transactions (
    transaction_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    reference varchar(128) NOT NULL,
    initiated_by uuid NOT NULL,
    source_wallet_id uuid,
    destination_wallet_id uuid,
    type varchar(16) NOT NULL,
    amount bigint NOT NULL,
    currency varchar(3) NOT NULL,
    status varchar(16) NOT NULL,
    description text,
    failure_code varchar(128),
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_transactions_reference UNIQUE (reference),
    CONSTRAINT fk_transactions_initiator FOREIGN KEY (initiated_by)
        REFERENCES flowpay.users (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_transactions_source FOREIGN KEY (source_wallet_id)
        REFERENCES flowpay.wallets (wallet_id) ON DELETE RESTRICT,
    CONSTRAINT fk_transactions_destination FOREIGN KEY (destination_wallet_id)
        REFERENCES flowpay.wallets (wallet_id) ON DELETE RESTRICT,
    CONSTRAINT ck_transactions_amount CHECK (amount > 0),
    CONSTRAINT ck_transactions_currency CHECK (currency = 'USD'),
    CONSTRAINT ck_transactions_status CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_transactions_wallets CHECK (
        (type = 'TRANSFER' AND source_wallet_id IS NOT NULL
            AND destination_wallet_id IS NOT NULL
            AND source_wallet_id <> destination_wallet_id)
        OR (type = 'TOP_UP' AND source_wallet_id IS NULL
            AND destination_wallet_id IS NOT NULL)
    ),
    CONSTRAINT ck_transactions_completion CHECK (
        (status = 'PENDING' AND completed_at IS NULL)
        OR (status IN ('SUCCESS', 'FAILED') AND completed_at IS NOT NULL)
    )
);

CREATE TABLE flowpay.ledger_accounts (
    account_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_code varchar(128) NOT NULL,
    wallet_id uuid,
    purpose varchar(32) NOT NULL,
    accounting_type varchar(16) NOT NULL,
    currency varchar(3) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ledger_accounts_code UNIQUE (account_code),
    CONSTRAINT uq_ledger_accounts_wallet UNIQUE (wallet_id),
    CONSTRAINT fk_ledger_accounts_wallet FOREIGN KEY (wallet_id)
        REFERENCES flowpay.wallets (wallet_id) ON DELETE RESTRICT,
    CONSTRAINT ck_ledger_accounts_currency CHECK (currency = 'USD'),
    CONSTRAINT ck_ledger_accounts_purpose CHECK (
        (purpose = 'WALLET' AND wallet_id IS NOT NULL AND accounting_type = 'LIABILITY')
        OR (purpose = 'PROVIDER_CLEARING' AND wallet_id IS NULL AND accounting_type = 'ASSET')
    )
);

CREATE TABLE flowpay.ledger_entries (
    entry_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id uuid NOT NULL,
    account_id uuid NOT NULL,
    entry_type varchar(8) NOT NULL,
    amount bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ledger_entries_transaction FOREIGN KEY (transaction_id)
        REFERENCES flowpay.transactions (transaction_id) ON DELETE RESTRICT,
    CONSTRAINT fk_ledger_entries_account FOREIGN KEY (account_id)
        REFERENCES flowpay.ledger_accounts (account_id) ON DELETE RESTRICT,
    CONSTRAINT uq_ledger_entries_posting UNIQUE (transaction_id, account_id, entry_type),
    CONSTRAINT ck_ledger_entries_type CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_ledger_entries_amount CHECK (amount > 0)
);

CREATE TABLE flowpay.provider_payments (
    payment_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id uuid NOT NULL,
    provider varchar(64) NOT NULL,
    provider_request_id varchar(255) NOT NULL,
    provider_payment_id varchar(255),
    provider_status varchar(16) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    last_checked_at timestamptz,
    next_check_at timestamptz,
    last_error_code varchar(128),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_provider_payments_transaction FOREIGN KEY (transaction_id)
        REFERENCES flowpay.transactions (transaction_id) ON DELETE RESTRICT,
    CONSTRAINT uq_provider_payments_transaction UNIQUE (transaction_id),
    CONSTRAINT uq_provider_payments_request UNIQUE (provider, provider_request_id),
    CONSTRAINT uq_provider_payments_external UNIQUE (provider, provider_payment_id),
    CONSTRAINT ck_provider_payments_status
        CHECK (provider_status IN ('UNKNOWN', 'PENDING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_provider_payments_attempts CHECK (attempt_count >= 0)
);

CREATE TABLE flowpay.idempotency_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,
    transaction_id uuid,
    operation_type varchar(16) NOT NULL,
    idempotency_key varchar(255) NOT NULL,
    request_hash varchar(64) NOT NULL,
    status varchar(16) NOT NULL,
    response_status smallint,
    response_body jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_idempotency_requests_user FOREIGN KEY (user_id)
        REFERENCES flowpay.users (user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_idempotency_requests_transaction FOREIGN KEY (transaction_id)
        REFERENCES flowpay.transactions (transaction_id) ON DELETE RESTRICT,
    CONSTRAINT uq_idempotency_requests_transaction UNIQUE (transaction_id),
    CONSTRAINT uq_idempotency_requests_key UNIQUE (user_id, operation_type, idempotency_key),
    CONSTRAINT ck_idempotency_requests_operation CHECK (operation_type IN ('TRANSFER', 'TOP_UP')),
    CONSTRAINT ck_idempotency_requests_key CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT ck_idempotency_requests_hash CHECK (request_hash ~ '^[0-9a-fA-F]{64}$'),
    CONSTRAINT ck_idempotency_requests_status CHECK (status IN ('PROCESSING', 'COMPLETED', 'REJECTED')),
    CONSTRAINT ck_idempotency_requests_response CHECK (
        (status = 'PROCESSING' AND response_status IS NULL AND response_body IS NULL)
        OR (status IN ('COMPLETED', 'REJECTED')
            AND response_status IS NOT NULL AND response_status BETWEEN 100 AND 599
            AND response_body IS NOT NULL)
    ),
    CONSTRAINT ck_idempotency_requests_completed CHECK (
        status <> 'COMPLETED' OR transaction_id IS NOT NULL
    )
);

CREATE TABLE flowpay.reconciliation_checks (
    check_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id uuid NOT NULL,
    previous_transaction_status varchar(16) NOT NULL,
    observed_provider_status varchar(16) NOT NULL,
    result varchar(16) NOT NULL,
    detail text,
    checked_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_reconciliation_checks_payment FOREIGN KEY (payment_id)
        REFERENCES flowpay.provider_payments (payment_id) ON DELETE RESTRICT,
    CONSTRAINT ck_reconciliation_checks_previous
        CHECK (previous_transaction_status IN ('PENDING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_reconciliation_checks_observed
        CHECK (observed_provider_status IN ('UNKNOWN', 'PENDING', 'SUCCESS', 'FAILED')),
    CONSTRAINT ck_reconciliation_checks_result
        CHECK (result IN ('MATCHED', 'RESOLVED', 'RETRY', 'MANUAL_REVIEW'))
);

CREATE INDEX idx_transactions_source_history
    ON flowpay.transactions (source_wallet_id, created_at DESC, transaction_id DESC);
CREATE INDEX idx_transactions_destination_history
    ON flowpay.transactions (destination_wallet_id, created_at DESC, transaction_id DESC);
CREATE INDEX idx_transactions_initiator_history
    ON flowpay.transactions (initiated_by, created_at DESC);
CREATE INDEX idx_ledger_entries_account_history
    ON flowpay.ledger_entries (account_id, created_at, entry_id);
CREATE INDEX idx_provider_payments_next_check
    ON flowpay.provider_payments (next_check_at) WHERE next_check_at IS NOT NULL;
CREATE INDEX idx_reconciliation_checks_payment_history
    ON flowpay.reconciliation_checks (payment_id, checked_at DESC);
