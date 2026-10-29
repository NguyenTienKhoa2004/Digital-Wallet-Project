ALTER TABLE flowpay.provider_payments
    DROP CONSTRAINT uq_provider_payments_request,
    DROP COLUMN provider_request_id;
