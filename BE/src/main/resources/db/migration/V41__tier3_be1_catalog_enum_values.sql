-- =============================================================================
-- V41 — Giá trị mới của hai cột có sẵn do BE-1 cần (T55b, docs/adr/0113; docs/05 §13 mục 26, docs/06 §6b hàng 7):
--   services.medical_type thêm SURGERY, INPATIENT; order_lines.line_type thêm HOME_FEE.
--
--   * Hai bảng thuộc BE-2 (catalog, sales). 06 §6b hàng 7 ghi bên cung cấp là BE-2; T55b viết ở V41 theo mặc định của
--     prompt T55b và CHƯA được BE-2 xác nhận. DROP ... IF EXISTS rồi ADD để migration lặp lại được an toàn: nếu T55c cũng
--     viết cùng thay đổi ở dải V42+ thì bản sau chỉ khẳng định lại đúng tập giá trị này.
--   * ck_order_lines_service_ref và ck_order_lines_product_ref (V1) KHÔNG đổi: HOME_FEE không thuộc hai tập đó nên cả hai
--     vế đều false, dòng HOME_FEE hợp lệ khi service_id và product_id cùng NULL, và bị chặn nếu mang một trong hai
--     (Tier3Be1SchemaIT.homeFeeLineHasNeitherServiceNorProduct).
--   * orders.source thêm ONLINE làm ở V42 (docs/adr/0116), không ở đây.
-- =============================================================================

ALTER TABLE services DROP CONSTRAINT IF EXISTS ck_services_medical_type;
ALTER TABLE services ADD CONSTRAINT ck_services_medical_type
    CHECK (medical_type IN ('EXAM', 'VACCINE', 'SURGERY', 'INPATIENT'));

ALTER TABLE order_lines DROP CONSTRAINT IF EXISTS ck_order_lines_line_type;
ALTER TABLE order_lines ADD CONSTRAINT ck_order_lines_line_type
    CHECK (line_type IN ('SERVICE', 'GOODS', 'DRUG', 'VACCINE', 'BOARDING', 'HOME_FEE'));
