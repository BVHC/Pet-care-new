-- =============================================================================
-- V28 — trả nợ D043 (một phần): 4 index FK của bệnh án / mũi tiêm + CHECK lý do mua ngoài — docs/adr/0093.
-- Số V28: BE-1 (visit); BE-2 từ V29 (06-module-contracts §6). Chỉ CREATE INDEX / ALTER ADD.
--
-- 1. Index (D043 nhóm 1). Cả bốn cột đều NOT NULL nên index thường, không partial:
--    * medical_record_addenda.medical_record_id, prescription_items.medical_record_id: xem bệnh án (visit-v1 #12) và
--      hồ sơ sức khỏe (#21) đọc theo bệnh án (findByMedicalRecordIdOrderByIdAsc) và kiểm FK khi xóa dòng cha.
--    * vaccinations.visit_id: kiểm FK khi xóa visits; hasVaccinationInVisit vẫn lọc thêm pet_id (đi index cũ), giữ nguyên.
--    * vaccinations.vaccine_type_id: existsByVaccineTypeId và kiểm FK khi catalog xóa / sửa vaccine_types (BR-SP-07).
--
-- 2. CHECK "mua ngoài ⇔ có lý do" (D043 nhóm 3, docs/adr/0076 C3, BR-KB-03, BR-BH-04). V1 chỉ có
--    external_reason IN (...): NULL cho kết quả UNKNOWN nên lọt. Hợp lệ sau CHECK mới:
--      (is_external_purchase, order_line_id, external_reason) = (true, NULL, 'OUT_OF_STOCK_AT_PRESCRIBE' | '..._AT_PAYMENT')
--                                                              | (false, <dòng Order>, NULL)
--    ck_prescription_items_external_xor_line (V1) vẫn giữ "mua ngoài ⇔ không có dòng Order".
--    ADD CONSTRAINT kiểm dòng sẵn có: DB dev có dòng ghi tay sai bất biến thì V28 lỗi lúc khởi động; sửa dòng đó
--    (SELECT ... FROM prescription_items WHERE is_external_purchase <> (external_reason IS NOT NULL)) rồi chạy lại.
--
-- Không làm (chấp nhận, docs/adr/0093): trigger chặn sửa bệnh án đã khóa và sửa / xóa bản bổ sung.
-- Lưu ý: dòng 3 của V22 ghi "V23 giữ cho CHECK prescription_items (D043)" đã lỗi thời; V23 dùng cho ST18.
-- =============================================================================

CREATE INDEX ix_medical_record_addenda_medical_record_id ON medical_record_addenda (medical_record_id);
CREATE INDEX ix_prescription_items_medical_record_id ON prescription_items (medical_record_id);
CREATE INDEX ix_vaccinations_visit_id ON vaccinations (visit_id);
CREATE INDEX ix_vaccinations_vaccine_type_id ON vaccinations (vaccine_type_id);

ALTER TABLE prescription_items
    ADD CONSTRAINT ck_prescription_items_external_reason_tied CHECK (is_external_purchase = (external_reason IS NOT NULL));
