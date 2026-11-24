-- ADR-0152: đăng nhập Google (identity-v1 #40). Chỉ khách gắn Google (BR-TK-17).
ALTER TABLE accounts ADD COLUMN google_sub VARCHAR(255);
ALTER TABLE accounts ADD CONSTRAINT uq_accounts_google_sub UNIQUE (google_sub);
-- chỉ tài khoản khách gắn Google (BR-TK-17)
ALTER TABLE accounts ADD CONSTRAINT ck_accounts_google_customer_only CHECK (google_sub IS NULL OR role = 'CUSTOMER');
