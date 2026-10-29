CREATE SCHEMA mock_provider;

CREATE TABLE mock_provider.payments (
    payment_id uuid PRIMARY KEY,
    provider_payment_id varchar(64) NOT NULL UNIQUE,
    amount bigint NOT NULL CHECK (amount > 0),
    currency varchar(3) NOT NULL CHECK (currency = 'USD'),
    status varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);