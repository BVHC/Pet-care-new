-- V13 (đổi số từ V5 gốc — trùng version với V5__organization_audit_columns.sql khi merge
-- PR #4/manh, xem V12 cho V4 cùng lý do): Caregiver delegation (M04, FSM-3) — bổ sung cột
-- audit khớp platform.model.BaseEntity (bảng V1 thiếu cả created_at), đổi định danh lời mời
-- sang email theo RULE-01-10 (xem spec D-01), và thêm valid_until cho RULE-04-07 (spec D-03).
-- Bảng chưa từng có code nào ghi vào nên rỗng trên mọi môi trường — đổi cột an toàn.
ALTER TABLE pet_caregiver_delegations
    ADD COLUMN caregiver_email VARCHAR(100) NOT NULL,
    ALTER COLUMN caregiver_phone DROP NOT NULL,
    ADD COLUMN valid_until TIMESTAMPTZ,
    ADD COLUMN created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN created_by  UUID REFERENCES accounts(id),
    ADD COLUMN updated_by  UUID REFERENCES accounts(id),
    ADD COLUMN deleted_at  TIMESTAMPTZ,
    ADD COLUMN version     BIGINT NOT NULL DEFAULT 0;

-- Tên cột không được nói dối: chỉ lưu SHA-256 hex, không lưu raw token (spec D-07,
-- precedent platform/security/token/RefreshTokenService).
ALTER TABLE pet_caregiver_delegations
    RENAME COLUMN invitation_token TO invitation_token_hash;

-- Chặn mời trùng ở tầng DB thay vì check-then-insert ở service (tránh hở race).
CREATE UNIQUE INDEX uq_pcd_outstanding
    ON pet_caregiver_delegations (pet_id, caregiver_email)
    WHERE status IN ('INVITED', 'ACTIVE');

-- Phục vụ job quét hết hạn (lối V3__refresh_token_cleanup_index.sql).
CREATE INDEX idx_pcd_invitation_expiry
    ON pet_caregiver_delegations (expires_at) WHERE status = 'INVITED';
CREATE INDEX idx_pcd_delegation_expiry
    ON pet_caregiver_delegations (valid_until) WHERE status = 'ACTIVE';

-- Phục vụ PetAccessGuard + query GET /pets.
CREATE INDEX idx_pcd_caregiver
    ON pet_caregiver_delegations (caregiver_user_id, status);
