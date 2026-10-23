-- Existing users (including the former local demo user) have no password.
-- They cannot log in until a password is explicitly provisioned in a future
-- reset flow. Never assign a shared/default password to existing accounts.
ALTER TABLE flowpay.users ADD COLUMN password_hash varchar(255);
