-- V4: bổ sung audit columns cho pets khớp platform.model.BaseEntity (accounts/users đã đủ ở V1).
ALTER TABLE pets
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN created_by UUID REFERENCES accounts(id),
    ADD COLUMN updated_by UUID REFERENCES accounts(id),
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
