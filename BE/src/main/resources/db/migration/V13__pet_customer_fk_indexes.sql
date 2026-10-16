-- T17 (khach hang & thu cung): index cho FK tro toi pets / customers ma V1 chua co (xem D013).
-- Moi lan xoa thu (BR-KH-06), xoa ho so online (BR-TK-08, BR-TK-19) va moi lan kiem existsByPet / existsByCustomer
-- truoc day phai quet toan bang boarding_bookings, visits, payments, care_tasks.
CREATE INDEX ix_boarding_bookings_pet_id ON boarding_bookings (pet_id);
CREATE INDEX ix_boarding_bookings_customer_id ON boarding_bookings (customer_id);
CREATE INDEX ix_care_tasks_pet_id ON care_tasks (pet_id);
CREATE INDEX ix_care_tasks_customer_id ON care_tasks (customer_id);
CREATE INDEX ix_visits_customer_id ON visits (customer_id);
CREATE INDEX ix_payments_customer_id ON payments (customer_id);
