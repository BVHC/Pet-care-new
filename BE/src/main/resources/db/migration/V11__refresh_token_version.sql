-- Phát hiện qua code review — rotate() (refresh) và doRevoke()/revokeByAccessTokenJti() (logout)
-- đều read-modify-save cùng 1 row refresh_tokens mà không có cơ chế phát hiện xung đột, cho phép
-- lost update khi 2 request chạm cùng 1 row gần như đồng thời (revoked_at/revoke_reason/replaced_by
-- của thread thắng-sau ghi đè mất field thread thua-trước vừa set). Thêm optimistic locking, cùng
-- pattern đã dùng cho organization_policies/store_policies.
ALTER TABLE refresh_tokens
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
