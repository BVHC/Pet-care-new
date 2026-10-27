-- ST18 quá hạn tái chủng (BR-TB-04, docs/adr/0083): mỗi mũi được xét đúng một lần; cờ đặt cùng transaction với Care Task
-- VACCINE_OVERDUE. NULL = chưa xét. Không backfill.
ALTER TABLE vaccinations ADD COLUMN overdue_handled_at TIMESTAMPTZ;

-- Câu quét keyset (next_due_date, id) của VaccineOverdueJob: chỉ mũi còn chờ xét nên index không lớn theo lịch sử.
CREATE INDEX ix_vaccinations_overdue_scan ON vaccinations (next_due_date, id)
    WHERE superseded_at IS NULL AND overdue_handled_at IS NULL;
