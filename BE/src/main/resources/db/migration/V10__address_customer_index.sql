-- V10 — index cho addresses.customer_id (docs/adr/0028, erd §13 mục 16; số V10 đã chốt với BE-2 2026-10-10).
-- Mọi endpoint sổ địa chỉ (/api/me/addresses, customer-v1 #3–7) đọc và đếm theo customer_id; index partial
-- uq_addresses_default_per_customer chỉ phủ dòng mặc định. Index này cũng phục vụ kiểm FK RESTRICT khi xóa customers
-- (trả một phần nợ D013: addresses.customer_id).
CREATE INDEX ix_addresses_customer_id ON addresses (customer_id);
