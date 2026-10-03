-- =============================================================================
-- V1 — Schema khởi tạo theo docs/05-erd.md (v16), 55 bảng (§1–§11).
--
-- Quy ước (erd §0):
--   * PK `id BIGINT GENERATED ALWAYS AS IDENTITY`; bảng 1–1 dùng FK làm PK.
--   * ROOT/PART/REF có created_at + updated_at; LOG chỉ có created_at.
--     updated_at do Hibernate cập nhật (platform/model/TimestampedEntity).
--   * Mọi FK `ON DELETE RESTRICT`, không CASCADE; ứng dụng tự xóa bản ghi con
--     trong các ca xóa cứng được phép (BR-KH-06, BR-TK-08, BR-TK-19, BR-BV-02, BR-KB-04).
--   * Cột trạng thái/loại: VARCHAR(30) + CHECK, giá trị là mã ASCII (erd L25, L28–37).
--   * Ràng buộc erd ghi "kiểm tra ở ứng dụng" không có ở đây.
--
-- Quyết định [ERD] riêng của V1 (erd §13 mục 6–10):
--   6. audit_logs chỉ INSERT bằng trigger (thay cho REVOKE vì app kết nối bằng owner).
--   7. Mọi cột enum VARCHAR(30) (prescription_items.external_reason cần 25 ký tự).
--   8. EXCLUDE trên boarding_bookings cần extension btree_gist.
--   9. audit_logs.actor_account_id không có FK (BR-TK-08 phải xóa được tài khoản PENDING).
--  10. payments.amount >= 0 (Order 0đ vẫn thu được, BR-TG-02).
--
-- Giả định đã duyệt:
--   A1. visit_assignments.visit_status_at_assign, stock_movements.source_type: CHECK theo
--       danh sách giá trị erd ghi kèm.
--   A2. kennel_types.species, vaccination_protocols.species: DOG/CAT/OTHER như pets.species.
--   A3. accounts.email lưu chữ thường: chuẩn hóa ở ứng dụng.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- =============================================================================
-- erd §1 Định danh & quản trị (TK, QT) — branches (§2) tạo sớm vì staff_profiles trỏ tới
-- =============================================================================

CREATE TABLE accounts (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email                 VARCHAR(255) NOT NULL,
    phone                 VARCHAR(15),
    password_hash         VARCHAR(255) NOT NULL,
    role                  VARCHAR(30)  NOT NULL,
    status                VARCHAR(30)  NOT NULL,
    is_locked             BOOLEAN      NOT NULL DEFAULT false,
    locked_reason         TEXT,
    locked_until          TIMESTAMPTZ,
    failed_login_count    INT          NOT NULL DEFAULT 0,
    first_failed_login_at TIMESTAMPTZ,
    must_change_password  BOOLEAN      NOT NULL DEFAULT false,
    last_seen_at          TIMESTAMPTZ,
    notification_settings JSONB        NOT NULL DEFAULT '{}',
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_accounts_email UNIQUE (email),
    CONSTRAINT ck_accounts_role CHECK (role IN ('CUSTOMER', 'ADMIN', 'SUPER_MANAGER', 'BRANCH_MANAGER',
                                                'RECEPTIONIST', 'VET', 'CARETAKER')),
    CONSTRAINT ck_accounts_status CHECK (status IN ('PENDING', 'ACTIVE', 'DISABLED')),
    -- Nhân viên bắt buộc có SĐT, tài khoản khách không lưu SĐT (BR-TK-01)
    CONSTRAINT ck_accounts_phone_by_role CHECK ((role = 'CUSTOMER') = (phone IS NULL)),
    -- Chỉ nhân viên bị vô hiệu hóa (BR-QT-07)
    CONSTRAINT ck_accounts_disabled_staff_only CHECK (status <> 'DISABLED' OR role <> 'CUSTOMER')
);
CREATE INDEX ix_accounts_status_created_at ON accounts (status, created_at);

CREATE TABLE branches (
    id                            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name                          VARCHAR(150)  NOT NULL,
    address                       VARCHAR(300)  NOT NULL,
    phone                         VARCHAR(15)   NOT NULL,
    latitude                      NUMERIC(9, 6) NOT NULL,
    longitude                     NUMERIC(9, 6) NOT NULL,
    status                        VARCHAR(30)   NOT NULL,
    accepts_after_hours_emergency BOOLEAN       NOT NULL DEFAULT false,
    activated_at                  TIMESTAMPTZ,
    created_at                    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_branches_name UNIQUE (name),
    CONSTRAINT ck_branches_status CHECK (status IN ('DRAFT', 'ACTIVE'))
);

CREATE TABLE staff_profiles (
    account_id BIGINT       PRIMARY KEY REFERENCES accounts (id) ON DELETE RESTRICT,
    full_name  VARCHAR(100) NOT NULL,
    avatar_url VARCHAR(500),
    branch_id  BIGINT       REFERENCES branches (id) ON DELETE RESTRICT,
    specialty  VARCHAR(200),
    bio        VARCHAR(500),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE sessions (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    token_hash VARCHAR(255) NOT NULL,
    ip_address VARCHAR(45),
    user_agent VARCHAR(500),
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_sessions_token_hash UNIQUE (token_hash)
);
CREATE INDEX ix_sessions_active_account ON sessions (account_id) WHERE revoked_at IS NULL;

-- LOG. actor_account_id cố ý không có FK [ERD 9]: dòng audit phải tồn tại độc lập với
-- vòng đời tài khoản (BR-QT-16), kể cả khi ST02 xóa tài khoản PENDING (BR-TK-08).
-- Ứng dụng luôn ghi kèm actor_email.
CREATE TABLE audit_logs (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_account_id BIGINT,
    actor_email      VARCHAR(255),
    action           VARCHAR(50) NOT NULL,
    entity_type      VARCHAR(50),
    entity_id        BIGINT,
    before_data      JSONB,
    after_data       JSONB,
    reason           TEXT,
    ip_address       VARCHAR(45),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_logs_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX ix_audit_logs_actor_created_at ON audit_logs (actor_account_id, created_at);
CREATE INDEX ix_audit_logs_created_at ON audit_logs (created_at);

CREATE TABLE system_configs (
    key         VARCHAR(100) PRIMARY KEY,
    value       VARCHAR(255) NOT NULL,
    value_type  VARCHAR(30)  NOT NULL,
    min_value   VARCHAR(50),
    max_value   VARCHAR(50),
    unit        VARCHAR(20),
    description TEXT         NOT NULL,
    updated_by  BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_system_configs_value_type CHECK (value_type IN ('INT', 'DECIMAL', 'BOOL', 'TIME'))
);

CREATE TABLE notification_templates (
    code            VARCHAR(50)  PRIMARY KEY,
    channel         VARCHAR(30)  NOT NULL,
    subject         VARCHAR(255),
    body            TEXT         NOT NULL,
    default_subject VARCHAR(255),
    default_body    TEXT         NOT NULL,
    allowed_vars    JSONB        NOT NULL,
    required_vars   JSONB        NOT NULL,
    updated_by      BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_notification_templates_channel CHECK (channel IN ('EMAIL', 'IN_APP'))
);

-- =============================================================================
-- erd §2 Chi nhánh (CN) — branch_services ở nhóm §4 vì trỏ tới services
-- =============================================================================

CREATE TABLE opening_hours (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_id      BIGINT      NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    effective_from DATE        NOT NULL,
    day_of_week    SMALLINT    NOT NULL,
    open_1         TIME,
    close_1        TIME,
    open_2         TIME,
    close_2        TIME,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_opening_hours_branch_day UNIQUE (branch_id, effective_from, day_of_week),
    CONSTRAINT ck_opening_hours_day_of_week CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT ck_opening_hours_pairs CHECK ((open_1 IS NULL) = (close_1 IS NULL)
                                             AND (open_2 IS NULL) = (close_2 IS NULL)),
    CONSTRAINT ck_opening_hours_range_1 CHECK (open_1 IS NULL OR open_1 < close_1),
    CONSTRAINT ck_opening_hours_range_2 CHECK (open_2 IS NULL
                                               OR (open_1 IS NOT NULL AND close_1 <= open_2 AND open_2 < close_2))
);

CREATE TABLE holidays (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_id    BIGINT       NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    holiday_date DATE         NOT NULL,
    reason       VARCHAR(200),
    created_by   BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_holidays_branch_date UNIQUE (branch_id, holiday_date)
);

CREATE TABLE branch_quota_defaults (
    branch_id     BIGINT      NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    service_group VARCHAR(30) NOT NULL,
    default_quota SMALLINT    NOT NULL,
    updated_by    BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (branch_id, service_group),
    CONSTRAINT ck_branch_quota_defaults_service_group CHECK (service_group IN ('MEDICAL', 'GROOMING')),
    CONSTRAINT ck_branch_quota_defaults_quota CHECK (default_quota >= 0)
);

CREATE TABLE slot_quotas (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_id     BIGINT      NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    service_group VARCHAR(30) NOT NULL,
    slot_date     DATE        NOT NULL,
    slot_start    TIME        NOT NULL,
    quota         SMALLINT    NOT NULL,
    updated_by    BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_slot_quotas_slot UNIQUE (branch_id, service_group, slot_date, slot_start),
    CONSTRAINT ck_slot_quotas_service_group CHECK (service_group IN ('MEDICAL', 'GROOMING')),
    CONSTRAINT ck_slot_quotas_quota CHECK (quota >= 0)
);

-- =============================================================================
-- erd §3 Khách hàng & thú cưng (KH) — weight_records ở nhóm §7 vì trỏ tới visits, boarding_bookings
-- =============================================================================

CREATE TABLE customers (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id            BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    full_name             VARCHAR(100) NOT NULL,
    phone                 VARCHAR(15),
    email                 VARCHAR(255),
    avatar_url            VARCHAR(500),
    created_channel       VARCHAR(30)  NOT NULL,
    created_by            BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    link_decision_pending BOOLEAN      NOT NULL DEFAULT false,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_customers_account_id UNIQUE (account_id),
    CONSTRAINT ck_customers_created_channel CHECK (created_channel IN ('ONLINE', 'COUNTER')),
    -- Hồ sơ tại quầy bắt buộc có SĐT (BR-KH-01)
    CONSTRAINT ck_customers_counter_phone CHECK (created_channel = 'ONLINE' OR phone IS NOT NULL),
    -- Hồ sơ online luôn gắn tài khoản
    CONSTRAINT ck_customers_online_account CHECK (created_channel = 'COUNTER' OR account_id IS NOT NULL),
    CONSTRAINT ck_customers_link_pending_online CHECK (created_channel = 'ONLINE' OR NOT link_decision_pending)
);
CREATE INDEX ix_customers_phone ON customers (phone);
CREATE INDEX ix_customers_email ON customers (email);
CREATE INDEX ix_customers_full_name ON customers (full_name);

-- LOG, nhưng cho phép cập nhật failed_attempts, consumed_at, invalidated_at (erd L146)
CREATE TABLE otp_tokens (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id      BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    purpose         VARCHAR(30)  NOT NULL,
    target_email    VARCHAR(255) NOT NULL,
    customer_id     BIGINT       REFERENCES customers (id) ON DELETE RESTRICT,
    code_hash       VARCHAR(255) NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    failed_attempts INT          NOT NULL DEFAULT 0,
    consumed_at     TIMESTAMPTZ,
    invalidated_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_otp_tokens_purpose CHECK (purpose IN ('REGISTER', 'RESET_PASSWORD', 'CHANGE_EMAIL', 'LINK_PROFILE')),
    CONSTRAINT ck_otp_tokens_link_customer CHECK ((purpose = 'LINK_PROFILE') = (customer_id IS NOT NULL))
);
CREATE INDEX ix_otp_tokens_target_email_created_at ON otp_tokens (target_email, created_at);

CREATE TABLE addresses (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id    BIGINT       NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    receiver_name  VARCHAR(100) NOT NULL,
    receiver_phone VARCHAR(15)  NOT NULL,
    address_line   VARCHAR(300) NOT NULL,
    ward           VARCHAR(100),
    province       VARCHAR(100) NOT NULL,
    is_default     BOOLEAN      NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Đúng 1 địa chỉ mặc định (BR-TK-18)
CREATE UNIQUE INDEX uq_addresses_default_per_customer ON addresses (customer_id) WHERE is_default;

CREATE TABLE pets (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id          BIGINT       NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    name                 VARCHAR(100) NOT NULL,
    species              VARCHAR(30)  NOT NULL,
    sex                  VARCHAR(30)  NOT NULL,
    breed                VARCHAR(100),
    birth_date           DATE,
    birth_date_estimated BOOLEAN      NOT NULL DEFAULT false,
    color                VARCHAR(50),
    is_neutered          BOOLEAN,
    photo_url            VARCHAR(500),
    deceased_on          DATE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_pets_species CHECK (species IN ('DOG', 'CAT', 'OTHER')),
    CONSTRAINT ck_pets_sex CHECK (sex IN ('MALE', 'FEMALE', 'UNKNOWN'))
);
CREATE INDEX ix_pets_customer_id ON pets (customer_id);
CREATE INDEX ix_pets_customer_name_species ON pets (customer_id, name, species);

-- =============================================================================
-- erd §4 Danh mục sản phẩm & dịch vụ (SP) + branch_services (§2)
-- =============================================================================

CREATE TABLE product_categories (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    is_active  BOOLEAN      NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_product_categories_name UNIQUE (name)
);

CREATE TABLE vaccine_types (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    species    VARCHAR(30)  NOT NULL,
    is_active  BOOLEAN      NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_vaccine_types_name_species UNIQUE (name, species),
    CONSTRAINT ck_vaccine_types_species CHECK (species IN ('DOG', 'CAT', 'OTHER'))
);

CREATE TABLE products (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category_id     BIGINT       NOT NULL REFERENCES product_categories (id) ON DELETE RESTRICT,
    sku             VARCHAR(50)  NOT NULL,
    name            VARCHAR(200) NOT NULL,
    product_type    VARCHAR(30)  NOT NULL,
    is_prescription BOOLEAN      NOT NULL DEFAULT false,
    tracks_expiry   BOOLEAN      NOT NULL DEFAULT false,
    vaccine_type_id BIGINT       REFERENCES vaccine_types (id) ON DELETE RESTRICT,
    unit            VARCHAR(20)  NOT NULL,
    price           BIGINT       NOT NULL,
    description     TEXT,
    image_url       VARCHAR(500),
    is_active       BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_products_sku UNIQUE (sku),
    CONSTRAINT ck_products_product_type CHECK (product_type IN ('GOODS', 'DRUG', 'VACCINE')),
    CONSTRAINT ck_products_price CHECK (price >= 0),
    -- Vaccine gắn đúng 1 loại vaccine (BR-SP-07)
    CONSTRAINT ck_products_vaccine_type CHECK ((product_type = 'VACCINE') = (vaccine_type_id IS NOT NULL)),
    -- Chỉ thuốc mới là thuốc kê đơn (BR-SP-01)
    CONSTRAINT ck_products_prescription_is_drug CHECK (NOT is_prescription OR product_type = 'DRUG'),
    -- Thuốc kê đơn và vaccine bắt buộc quản lý hạn dùng (BR-SP-05)
    CONSTRAINT ck_products_tracks_expiry CHECK (NOT (is_prescription OR product_type = 'VACCINE') OR tracks_expiry)
);

CREATE TABLE services (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          VARCHAR(150) NOT NULL,
    service_group VARCHAR(30)  NOT NULL,
    medical_type  VARCHAR(30),
    price         BIGINT       NOT NULL,
    price_is_from BOOLEAN      NOT NULL DEFAULT true,
    description   TEXT,
    is_active     BOOLEAN      NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_services_name UNIQUE (name),
    CONSTRAINT ck_services_service_group CHECK (service_group IN ('MEDICAL', 'GROOMING', 'BOARDING')),
    CONSTRAINT ck_services_medical_type CHECK (medical_type IN ('EXAM', 'VACCINE')),
    CONSTRAINT ck_services_price CHECK (price >= 0),
    -- Dịch vụ nhóm Khám/Tiêm bắt buộc có loại (BR-SP-06)
    CONSTRAINT ck_services_medical_type_by_group CHECK ((service_group = 'MEDICAL') = (medical_type IS NOT NULL)),
    -- Loại chuồng có giá > 0 (BR-SP-04)
    CONSTRAINT ck_services_boarding_price CHECK (service_group <> 'BOARDING' OR price > 0)
);

CREATE TABLE kennel_types (
    service_id    BIGINT        PRIMARY KEY REFERENCES services (id) ON DELETE RESTRICT,
    species       VARCHAR(30)   NOT NULL,
    max_weight_kg NUMERIC(6, 2) NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_kennel_types_species CHECK (species IN ('DOG', 'CAT', 'OTHER')),
    CONSTRAINT ck_kennel_types_max_weight CHECK (max_weight_kg > 0)
);

CREATE TABLE vaccination_protocols (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    species               VARCHAR(30) NOT NULL,
    vaccine_type_id       BIGINT      NOT NULL REFERENCES vaccine_types (id) ON DELETE RESTRICT,
    dose_number           SMALLINT    NOT NULL,
    interval_days         INT         NOT NULL,
    min_age_weeks         SMALLINT    NOT NULL,
    required_for_boarding BOOLEAN     NOT NULL DEFAULT false,
    is_active             BOOLEAN     NOT NULL DEFAULT true,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_vaccination_protocols_dose UNIQUE (species, vaccine_type_id, dose_number),
    CONSTRAINT ck_vaccination_protocols_species CHECK (species IN ('DOG', 'CAT', 'OTHER')),
    CONSTRAINT ck_vaccination_protocols_dose_number CHECK (dose_number >= 1),
    CONSTRAINT ck_vaccination_protocols_interval CHECK (interval_days > 0),
    CONSTRAINT ck_vaccination_protocols_min_age CHECK (min_age_weeks >= 0)
);

CREATE TABLE branch_services (
    branch_id  BIGINT      NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    service_id BIGINT      NOT NULL REFERENCES services (id) ON DELETE RESTRICT,
    is_enabled BOOLEAN     NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (branch_id, service_id)
);

-- =============================================================================
-- erd §5 Lịch hẹn (LH)
-- =============================================================================

CREATE TABLE appointments (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code             VARCHAR(20)  NOT NULL,
    customer_id      BIGINT       NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    pet_id           BIGINT       NOT NULL REFERENCES pets (id) ON DELETE RESTRICT,
    branch_id        BIGINT       NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    service_id       BIGINT       NOT NULL REFERENCES services (id) ON DELETE RESTRICT,
    service_group    VARCHAR(30)  NOT NULL,
    slot_date        DATE         NOT NULL,
    slot_start       TIME         NOT NULL,
    status           VARCHAR(30)  NOT NULL,
    channel          VARCHAR(30)  NOT NULL,
    booked_by        BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    reschedule_count SMALLINT     NOT NULL DEFAULT 0,
    late_cancel      BOOLEAN      NOT NULL DEFAULT false,
    cancel_source    VARCHAR(30),
    cancel_reason    VARCHAR(300),
    cancelled_at     TIMESTAMPTZ,
    reminded_at      TIMESTAMPTZ,
    note             VARCHAR(500),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_appointments_code UNIQUE (code),
    -- Chỉ nhóm Khám/Tiêm và Thẩm mỹ đặt được lịch (BR-LH-01)
    CONSTRAINT ck_appointments_service_group CHECK (service_group IN ('MEDICAL', 'GROOMING')),
    CONSTRAINT ck_appointments_status CHECK (status IN ('BOOKED', 'CHECKED_IN', 'COMPLETED', 'CANCELLED', 'NO_SHOW')),
    CONSTRAINT ck_appointments_channel CHECK (channel IN ('ONLINE', 'COUNTER')),
    CONSTRAINT ck_appointments_cancel_source CHECK (cancel_source IN ('CUSTOMER', 'STAFF', 'CLINIC')),
    CONSTRAINT ck_appointments_cancel_fields CHECK (status = 'CANCELLED'
                                                    OR (cancel_source IS NULL AND late_cancel = false))
);
-- Đếm quota: lịch CANCELLED, NO_SHOW trả lại quota (BR-LH-03)
CREATE INDEX ix_appointments_quota ON appointments (branch_id, service_group, slot_date, slot_start)
    WHERE status IN ('BOOKED', 'CHECKED_IN', 'COMPLETED');
CREATE INDEX ix_appointments_pet_status ON appointments (pet_id, status);
CREATE INDEX ix_appointments_customer_created_at ON appointments (customer_id, created_at);
CREATE INDEX ix_appointments_status_slot ON appointments (status, slot_date, slot_start);
-- Thú không trùng khung (BR-LH-05)
CREATE UNIQUE INDEX uq_appointments_pet_slot_booked ON appointments (pet_id, slot_date, slot_start)
    WHERE status = 'BOOKED';

CREATE TABLE booking_restrictions (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id     BIGINT       NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    starts_at       TIMESTAMPTZ  NOT NULL,
    ends_at         TIMESTAMPTZ  NOT NULL,
    violation_count SMALLINT     NOT NULL,
    lifted_at       TIMESTAMPTZ,
    lifted_by       BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    lift_reason     VARCHAR(300),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_booking_restrictions_customer_ends_at ON booking_restrictions (customer_id, ends_at);

-- =============================================================================
-- erd §6 Tiếp nhận & khám (TN, KB) — prescription_items ở nhóm §8, vaccinations ở nhóm §9
-- =============================================================================

CREATE TABLE visits (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code           VARCHAR(20)  NOT NULL,
    branch_id      BIGINT       NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    customer_id    BIGINT       NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    pet_id         BIGINT       NOT NULL REFERENCES pets (id) ON DELETE RESTRICT,
    appointment_id BIGINT       REFERENCES appointments (id) ON DELETE RESTRICT,
    service_group  VARCHAR(30)  NOT NULL,
    status         VARCHAR(30)  NOT NULL,
    priority_class VARCHAR(30)  NOT NULL,
    queue_sort_key TIMESTAMPTZ  NOT NULL,
    assignee_id    BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    needs_reassign BOOLEAN      NOT NULL DEFAULT false,
    checked_in_by  BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    checked_in_at  TIMESTAMPTZ  NOT NULL,
    called_at      TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ,
    cancelled_at   TIMESTAMPTZ,
    cancel_reason  VARCHAR(300),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_visits_code UNIQUE (code),
    CONSTRAINT uq_visits_appointment_id UNIQUE (appointment_id),
    CONSTRAINT ck_visits_service_group CHECK (service_group IN ('MEDICAL', 'GROOMING')),
    CONSTRAINT ck_visits_status CHECK (status IN ('WAITING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_visits_priority_class CHECK (priority_class IN ('EMERGENCY', 'APPOINTMENT', 'WALK_IN')),
    -- Lượt đã gọi luôn có người phụ trách (BR-TN-05, BR-TN-09)
    CONSTRAINT ck_visits_assignee CHECK (status = 'WAITING' OR status = 'CANCELLED' OR assignee_id IS NOT NULL)
);
CREATE INDEX ix_visits_queue ON visits (branch_id, status, priority_class, queue_sort_key);
CREATE INDEX ix_visits_assignee_status ON visits (assignee_id, status);
CREATE INDEX ix_visits_pet_checked_in_at ON visits (pet_id, checked_in_at DESC);

-- LOG
CREATE TABLE visit_assignments (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    visit_id               BIGINT       NOT NULL REFERENCES visits (id) ON DELETE RESTRICT,
    from_account_id        BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    to_account_id          BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    assigned_by            BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    visit_status_at_assign VARCHAR(30)  NOT NULL,
    reason                 VARCHAR(300),
    assignee_was_offline   BOOLEAN      NOT NULL DEFAULT false,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- A1
    CONSTRAINT ck_visit_assignments_visit_status CHECK (visit_status_at_assign IN ('WAITING', 'IN_PROGRESS'))
);

CREATE TABLE medical_records (
    visit_id               BIGINT      PRIMARY KEY REFERENCES visits (id) ON DELETE RESTRICT,
    examination            TEXT,
    diagnosis              TEXT,
    treatment_plan         TEXT,
    internal_note          TEXT,
    follow_up_date         DATE,
    follow_up_reminded_at  TIMESTAMPTZ,
    follow_up_cancelled_at TIMESTAMPTZ,
    last_edited_by         BIGINT      REFERENCES accounts (id) ON DELETE RESTRICT,
    locked_at              TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- ST04 quét ngày tái khám
CREATE INDEX ix_medical_records_follow_up_due ON medical_records (follow_up_date)
    WHERE follow_up_date IS NOT NULL AND follow_up_reminded_at IS NULL AND follow_up_cancelled_at IS NULL;

-- LOG
CREATE TABLE medical_record_addenda (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    medical_record_id BIGINT      NOT NULL REFERENCES medical_records (visit_id) ON DELETE RESTRICT,
    content           TEXT        NOT NULL,
    is_internal       BOOLEAN     NOT NULL DEFAULT false,
    created_by        BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =============================================================================
-- erd §7 Lưu trú (LT) + weight_records (§3)
-- =============================================================================

CREATE TABLE kennels (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_id      BIGINT       NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    kennel_type_id BIGINT       NOT NULL REFERENCES kennel_types (service_id) ON DELETE RESTRICT,
    code           VARCHAR(20)  NOT NULL,
    status         VARCHAR(30)  NOT NULL,
    note           VARCHAR(300),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_kennels_branch_code UNIQUE (branch_id, code),
    CONSTRAINT ck_kennels_status CHECK (status IN ('AVAILABLE', 'OCCUPIED', 'MAINTENANCE'))
);
CREATE INDEX ix_kennels_branch_type_status ON kennels (branch_id, kennel_type_id, status);

CREATE TABLE boarding_bookings (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                   VARCHAR(20)  NOT NULL,
    customer_id            BIGINT       NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    pet_id                 BIGINT       NOT NULL REFERENCES pets (id) ON DELETE RESTRICT,
    branch_id              BIGINT       NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    kennel_type_id         BIGINT       NOT NULL REFERENCES kennel_types (service_id) ON DELETE RESTRICT,
    kennel_id              BIGINT       REFERENCES kennels (id) ON DELETE RESTRICT,
    check_in_date          DATE         NOT NULL,
    check_out_date         DATE         NOT NULL,
    actual_check_in_at     TIMESTAMPTZ,
    actual_check_out_at    TIMESTAMPTZ,
    nightly_price          BIGINT       NOT NULL,
    status                 VARCHAR(30)  NOT NULL,
    end_reason             VARCHAR(30),
    end_note               TEXT,
    channel                VARCHAR(30)  NOT NULL,
    booked_by              BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    late_cancel            BOOLEAN      NOT NULL DEFAULT false,
    cancel_source          VARCHAR(30),
    cancel_reason          VARCHAR(300),
    overdue_since          TIMESTAMPTZ,
    last_overdue_notice_on DATE,
    manager_notified_at    TIMESTAMPTZ,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_boarding_bookings_code UNIQUE (code),
    CONSTRAINT ck_boarding_bookings_nightly_price CHECK (nightly_price > 0),
    CONSTRAINT ck_boarding_bookings_status CHECK (status IN ('BOOKED', 'CHECKED_IN', 'OVERDUE', 'CHECKED_OUT',
                                                             'CANCELLED', 'NO_SHOW')),
    CONSTRAINT ck_boarding_bookings_end_reason CHECK (end_reason IN ('RETURNED', 'PET_DECEASED')),
    CONSTRAINT ck_boarding_bookings_channel CHECK (channel IN ('ONLINE', 'COUNTER')),
    CONSTRAINT ck_boarding_bookings_cancel_source CHECK (cancel_source IN ('CUSTOMER', 'STAFF', 'CLINIC')),
    CONSTRAINT ck_boarding_bookings_dates CHECK (check_out_date > check_in_date),
    CONSTRAINT ck_boarding_bookings_end_reason_when_checked_out CHECK ((status = 'CHECKED_OUT') = (end_reason IS NOT NULL)),
    CONSTRAINT ck_boarding_bookings_kennel_when_in CHECK (status NOT IN ('CHECKED_IN', 'OVERDUE', 'CHECKED_OUT')
                                                          OR kennel_id IS NOT NULL),
    -- Một thú không có 2 đặt chỗ chồng ngày (BR-LT-02) — cần btree_gist [ERD 8]
    CONSTRAINT ex_boarding_bookings_pet_no_overlap EXCLUDE USING gist (
        pet_id WITH =,
        daterange(check_in_date, check_out_date) WITH &&
    ) WHERE (status IN ('BOOKED', 'CHECKED_IN', 'OVERDUE'))
);
-- 1 chuồng chứa 1 thú
CREATE UNIQUE INDEX uq_boarding_bookings_kennel_in_use ON boarding_bookings (kennel_id)
    WHERE status IN ('CHECKED_IN', 'OVERDUE');
CREATE INDEX ix_boarding_bookings_capacity ON boarding_bookings
    (branch_id, kennel_type_id, status, check_in_date, check_out_date);

-- LOG
CREATE TABLE weight_records (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pet_id              BIGINT        NOT NULL REFERENCES pets (id) ON DELETE RESTRICT,
    weight_kg           NUMERIC(6, 2) NOT NULL,
    source              VARCHAR(30)   NOT NULL,
    visit_id            BIGINT        REFERENCES visits (id) ON DELETE RESTRICT,
    boarding_booking_id BIGINT        REFERENCES boarding_bookings (id) ON DELETE RESTRICT,
    recorded_by         BIGINT        REFERENCES accounts (id) ON DELETE RESTRICT,
    measured_at         TIMESTAMPTZ   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_weight_records_weight CHECK (weight_kg > 0),
    CONSTRAINT ck_weight_records_source CHECK (source IN ('VET', 'INTAKE', 'OWNER'))
);
CREATE INDEX ix_weight_records_pet_measured_at ON weight_records (pet_id, measured_at DESC);

CREATE TABLE boarding_check_ins (
    boarding_booking_id BIGINT      PRIMARY KEY REFERENCES boarding_bookings (id) ON DELETE RESTRICT,
    weight_record_id    BIGINT      NOT NULL REFERENCES weight_records (id) ON DELETE RESTRICT,
    health_condition    TEXT        NOT NULL,
    belongings          TEXT,
    diet_instructions   TEXT,
    emergency_phone     VARCHAR(15) NOT NULL,
    received_by         BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    received_at         TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_boarding_check_ins_weight_record_id UNIQUE (weight_record_id)
);

CREATE TABLE care_logs (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    boarding_booking_id BIGINT       NOT NULL REFERENCES boarding_bookings (id) ON DELETE RESTRICT,
    log_date            DATE         NOT NULL,
    eating              VARCHAR(300),
    drinking            VARCHAR(300),
    hygiene             VARCHAR(300),
    activity            VARCHAR(300),
    note                TEXT,
    photo_urls          JSONB        NOT NULL DEFAULT '[]',
    is_abnormal         BOOLEAN      NOT NULL DEFAULT false,
    recorded_by         BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_care_logs_booking_log_date ON care_logs (boarding_booking_id, log_date);

-- LOG
CREATE TABLE care_log_addenda (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    care_log_id BIGINT      NOT NULL REFERENCES care_logs (id) ON DELETE RESTRICT,
    content     TEXT        NOT NULL,
    created_by  BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =============================================================================
-- erd §8 Bán hàng & thu ngân (BH, TG) + prescription_items (§6)
-- =============================================================================

CREATE TABLE cashier_shifts (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_id         BIGINT      NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    cashier_id        BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    is_after_hours    BOOLEAN     NOT NULL DEFAULT false,
    business_date     DATE        NOT NULL,
    status            VARCHAR(30) NOT NULL,
    opened_at         TIMESTAMPTZ NOT NULL,
    closed_at         TIMESTAMPTZ,
    auto_closed       BOOLEAN     NOT NULL DEFAULT false,
    expected_cash     BIGINT,
    expected_transfer BIGINT,
    counted_cash      BIGINT,
    difference        BIGINT,
    reconcile_note    TEXT,
    reconciled_by     BIGINT      REFERENCES accounts (id) ON DELETE RESTRICT,
    reconciled_at     TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_cashier_shifts_status CHECK (status IN ('OPEN', 'CLOSED', 'RECONCILED')),
    CONSTRAINT ck_cashier_shifts_reconciled_counted CHECK (status <> 'RECONCILED' OR counted_cash IS NOT NULL),
    CONSTRAINT ck_cashier_shifts_closed_counted CHECK (status <> 'CLOSED' OR auto_closed OR counted_cash IS NOT NULL)
);
-- Tối đa 1 ca mở mỗi lễ tân (BR-TG-05)
CREATE UNIQUE INDEX uq_cashier_shifts_one_open ON cashier_shifts (cashier_id) WHERE status = 'OPEN';
CREATE INDEX ix_cashier_shifts_open ON cashier_shifts (branch_id, is_after_hours) WHERE status = 'OPEN';

CREATE TABLE payments (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code             VARCHAR(20) NOT NULL,
    branch_id        BIGINT      NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    customer_id      BIGINT      NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    cashier_shift_id BIGINT      NOT NULL REFERENCES cashier_shifts (id) ON DELETE RESTRICT,
    method           VARCHAR(30) NOT NULL,
    amount           BIGINT      NOT NULL,
    received_by      BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    paid_at          TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_payments_code UNIQUE (code),
    CONSTRAINT ck_payments_method CHECK (method IN ('CASH', 'TRANSFER')),
    -- [ERD 10] >= 0: Order 0đ vẫn thu được (BR-TG-02)
    CONSTRAINT ck_payments_amount CHECK (amount >= 0)
);
CREATE INDEX ix_payments_shift_method ON payments (cashier_shift_id, method);

CREATE TABLE orders (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                VARCHAR(20)  NOT NULL,
    source              VARCHAR(30)  NOT NULL,
    customer_id         BIGINT       NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    branch_id           BIGINT       NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    visit_id            BIGINT       REFERENCES visits (id) ON DELETE RESTRICT,
    boarding_booking_id BIGINT       REFERENCES boarding_bookings (id) ON DELETE RESTRICT,
    payment_id          BIGINT       REFERENCES payments (id) ON DELETE RESTRICT,
    status              VARCHAR(30)  NOT NULL,
    total_amount        BIGINT       NOT NULL DEFAULT 0,
    created_by          BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    pending_at          TIMESTAMPTZ,
    paid_at             TIMESTAMPTZ,
    cancelled_at        TIMESTAMPTZ,
    cancelled_by        BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    cancel_type         VARCHAR(30),
    cancel_reason       VARCHAR(300),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_orders_code UNIQUE (code),
    CONSTRAINT uq_orders_visit_id UNIQUE (visit_id),
    CONSTRAINT ck_orders_source CHECK (source IN ('VISIT', 'RETAIL', 'BOARDING')),
    CONSTRAINT ck_orders_status CHECK (status IN ('OPEN', 'PENDING', 'PAID', 'CANCELLED')),
    CONSTRAINT ck_orders_cancel_type CHECK (cancel_type IN ('VISIT_CANCELLED', 'RETAIL_UNCLOSED',
                                                            'CHECKOUT_ABORTED', 'UNPAID')),
    -- Đúng 1 nguồn (BR-BH-01)
    CONSTRAINT ck_orders_single_source CHECK (
        (source = 'VISIT' AND visit_id IS NOT NULL AND boarding_booking_id IS NULL)
        OR (source = 'BOARDING' AND boarding_booking_id IS NOT NULL AND visit_id IS NULL)
        OR (source = 'RETAIL' AND visit_id IS NULL AND boarding_booking_id IS NULL)),
    CONSTRAINT ck_orders_paid_payment CHECK ((status = 'PAID') = (payment_id IS NOT NULL))
);
-- Tối đa 1 Order lưu trú chưa kết thúc mỗi đặt chỗ (BR-BH-01)
CREATE UNIQUE INDEX uq_orders_boarding_unfinished ON orders (boarding_booking_id)
    WHERE status IN ('OPEN', 'PENDING');
CREATE INDEX ix_orders_branch_status_pending_at ON orders (branch_id, status, pending_at);
CREATE INDEX ix_orders_branch_paid_at ON orders (branch_id, paid_at);
CREATE INDEX ix_orders_customer_status ON orders (customer_id, status);

CREATE TABLE order_lines (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id          BIGINT       NOT NULL REFERENCES orders (id) ON DELETE RESTRICT,
    line_type         VARCHAR(30)  NOT NULL,
    service_id        BIGINT       REFERENCES services (id) ON DELETE RESTRICT,
    product_id        BIGINT       REFERENCES products (id) ON DELETE RESTRICT,
    description       VARCHAR(200) NOT NULL,
    quantity          INT          NOT NULL,
    unit_price        BIGINT       NOT NULL,
    line_total        BIGINT       NOT NULL,
    is_auto_generated BOOLEAN      NOT NULL DEFAULT false,
    added_by          BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    owner_account_id  BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_order_lines_line_type CHECK (line_type IN ('SERVICE', 'GOODS', 'DRUG', 'VACCINE', 'BOARDING')),
    CONSTRAINT ck_order_lines_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_lines_unit_price CHECK (unit_price >= 0),
    CONSTRAINT ck_order_lines_service_ref CHECK ((line_type IN ('SERVICE', 'BOARDING')) = (service_id IS NOT NULL)),
    CONSTRAINT ck_order_lines_product_ref CHECK ((line_type IN ('GOODS', 'DRUG', 'VACCINE')) = (product_id IS NOT NULL)),
    CONSTRAINT ck_order_lines_total CHECK (line_total = quantity * unit_price)
);

CREATE TABLE prescription_items (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    medical_record_id    BIGINT       NOT NULL REFERENCES medical_records (visit_id) ON DELETE RESTRICT,
    product_id           BIGINT       NOT NULL REFERENCES products (id) ON DELETE RESTRICT,
    quantity             INT          NOT NULL,
    dosage_instructions  VARCHAR(500) NOT NULL,
    is_external_purchase BOOLEAN      NOT NULL DEFAULT false,
    order_line_id        BIGINT       REFERENCES order_lines (id) ON DELETE RESTRICT,
    external_reason      VARCHAR(30),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_prescription_items_order_line_id UNIQUE (order_line_id),
    CONSTRAINT ck_prescription_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_prescription_items_external_reason CHECK (external_reason IN ('OUT_OF_STOCK_AT_PRESCRIBE',
                                                                                'OUT_OF_STOCK_AT_PAYMENT')),
    -- Không tách một dòng thành hai phần (BR-KB-03)
    CONSTRAINT ck_prescription_items_external_xor_line CHECK (is_external_purchase = (order_line_id IS NULL))
);

-- =============================================================================
-- erd §9 Kho (KO) + vaccinations (§6)
-- =============================================================================

CREATE TABLE suppliers (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    phone      VARCHAR(15)  NOT NULL,
    address    VARCHAR(300),
    is_active  BOOLEAN      NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE inventory_items (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_id    BIGINT      NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    product_id   BIGINT      NOT NULL REFERENCES products (id) ON DELETE RESTRICT,
    min_quantity INT         NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_inventory_items_branch_product UNIQUE (branch_id, product_id),
    CONSTRAINT ck_inventory_items_min_quantity CHECK (min_quantity >= 0)
);

CREATE TABLE stock_lots (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    inventory_item_id BIGINT      NOT NULL REFERENCES inventory_items (id) ON DELETE RESTRICT,
    lot_number        VARCHAR(50),
    expiry_date       DATE,
    quantity          INT         NOT NULL,
    is_default        BOOLEAN     NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Tồn không bao giờ âm (BR-KO-01)
    CONSTRAINT ck_stock_lots_quantity CHECK (quantity >= 0),
    CONSTRAINT ck_stock_lots_default_has_no_lot CHECK (is_default = (lot_number IS NULL AND expiry_date IS NULL))
);
CREATE UNIQUE INDEX uq_stock_lots_default ON stock_lots (inventory_item_id) WHERE is_default;
CREATE UNIQUE INDEX uq_stock_lots_lot ON stock_lots (inventory_item_id, lot_number, expiry_date) WHERE NOT is_default;
-- FEFO (BR-KO-05)
CREATE INDEX ix_stock_lots_fefo ON stock_lots (inventory_item_id, expiry_date) WHERE quantity > 0;

CREATE TABLE stock_receipts (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code          VARCHAR(20)  NOT NULL,
    branch_id     BIGINT       NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    supplier_id   BIGINT       NOT NULL REFERENCES suppliers (id) ON DELETE RESTRICT,
    receipt_date  DATE         NOT NULL,
    status        VARCHAR(30)  NOT NULL,
    note          TEXT,
    created_by    BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    confirmed_by  BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    confirmed_at  TIMESTAMPTZ,
    cancelled_by  BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    cancelled_at  TIMESTAMPTZ,
    cancel_reason VARCHAR(300),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_stock_receipts_code UNIQUE (code),
    CONSTRAINT ck_stock_receipts_status CHECK (status IN ('DRAFT', 'CONFIRMED', 'CANCELLED'))
);

CREATE TABLE stock_receipt_lines (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    stock_receipt_id BIGINT      NOT NULL REFERENCES stock_receipts (id) ON DELETE RESTRICT,
    product_id       BIGINT      NOT NULL REFERENCES products (id) ON DELETE RESTRICT,
    quantity         INT         NOT NULL,
    unit_cost        BIGINT      NOT NULL,
    lot_number       VARCHAR(50),
    expiry_date      DATE,
    stock_lot_id     BIGINT      REFERENCES stock_lots (id) ON DELETE RESTRICT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_stock_receipt_lines_quantity CHECK (quantity > 0),
    CONSTRAINT ck_stock_receipt_lines_unit_cost CHECK (unit_cost >= 0)
);

CREATE TABLE stock_adjustments (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code       VARCHAR(20) NOT NULL,
    branch_id  BIGINT      NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    reason     VARCHAR(30) NOT NULL,
    note       TEXT,
    created_by BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_stock_adjustments_code UNIQUE (code),
    CONSTRAINT ck_stock_adjustments_reason CHECK (reason IN ('DAMAGED', 'EXPIRED', 'COUNT_MISMATCH', 'OTHER')),
    CONSTRAINT ck_stock_adjustments_other_note CHECK (reason <> 'OTHER' OR note IS NOT NULL)
);

CREATE TABLE stock_adjustment_lines (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    stock_adjustment_id BIGINT      NOT NULL REFERENCES stock_adjustments (id) ON DELETE RESTRICT,
    stock_lot_id        BIGINT      NOT NULL REFERENCES stock_lots (id) ON DELETE RESTRICT,
    quantity_delta      INT         NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_stock_adjustment_lines_delta CHECK (quantity_delta <> 0)
);

-- LOG. source_id không có FK vì đa hình (erd L910)
CREATE TABLE stock_movements (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    stock_lot_id   BIGINT      NOT NULL REFERENCES stock_lots (id) ON DELETE RESTRICT,
    movement_type  VARCHAR(30) NOT NULL,
    quantity_delta INT         NOT NULL,
    balance_after  INT         NOT NULL,
    source_type    VARCHAR(30) NOT NULL,
    source_id      BIGINT      NOT NULL,
    created_by     BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_stock_movements_movement_type CHECK (movement_type IN ('RECEIPT', 'RECEIPT_CANCEL', 'SALE',
                                                                         'VACCINATION', 'VACCINATION_REVERT',
                                                                         'ADJUSTMENT')),
    CONSTRAINT ck_stock_movements_delta CHECK (quantity_delta <> 0),
    CONSTRAINT ck_stock_movements_balance CHECK (balance_after >= 0),
    -- A1
    CONSTRAINT ck_stock_movements_source_type CHECK (source_type IN ('stock_receipts', 'orders', 'vaccinations',
                                                                     'stock_adjustments'))
);
CREATE INDEX ix_stock_movements_lot_created_at ON stock_movements (stock_lot_id, created_at);
CREATE INDEX ix_stock_movements_source ON stock_movements (source_type, source_id);

CREATE TABLE vaccinations (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pet_id          BIGINT      NOT NULL REFERENCES pets (id) ON DELETE RESTRICT,
    vaccine_type_id BIGINT      NOT NULL REFERENCES vaccine_types (id) ON DELETE RESTRICT,
    visit_id        BIGINT      NOT NULL REFERENCES visits (id) ON DELETE RESTRICT,
    branch_id       BIGINT      NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    administered_on DATE        NOT NULL,
    protocol_id     BIGINT      NOT NULL REFERENCES vaccination_protocols (id) ON DELETE RESTRICT,
    dose_number     SMALLINT    NOT NULL,
    product_id      BIGINT      NOT NULL REFERENCES products (id) ON DELETE RESTRICT,
    stock_lot_id    BIGINT      NOT NULL REFERENCES stock_lots (id) ON DELETE RESTRICT,
    order_line_id   BIGINT      NOT NULL REFERENCES order_lines (id) ON DELETE RESTRICT,
    next_due_date   DATE        NOT NULL,
    due_reminded_at TIMESTAMPTZ,
    superseded_at   TIMESTAMPTZ,
    recorded_by     BIGINT      NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_vaccinations_order_line_id UNIQUE (order_line_id),
    CONSTRAINT ck_vaccinations_dose_number CHECK (dose_number >= 1)
);
-- Mũi gần nhất cùng loại vaccine (BR-KB-04, BR-LT-05, BR-TB-02)
CREATE INDEX ix_vaccinations_pet_type_administered ON vaccinations (pet_id, vaccine_type_id, administered_on DESC);
-- ST04 quét ngày tái chủng
CREATE INDEX ix_vaccinations_next_due ON vaccinations (next_due_date) WHERE superseded_at IS NULL;

-- =============================================================================
-- erd §10 Nội dung & feedback (BV, CK, DG)
-- =============================================================================

CREATE TABLE article_categories (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    slug       VARCHAR(120) NOT NULL,
    is_hidden  BOOLEAN      NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_article_categories_name UNIQUE (name),
    CONSTRAINT uq_article_categories_slug UNIQUE (slug)
);

CREATE TABLE articles (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category_id         BIGINT       NOT NULL REFERENCES article_categories (id) ON DELETE RESTRICT,
    title               VARCHAR(250) NOT NULL,
    slug                VARCHAR(270) NOT NULL,
    cover_image_url     VARCHAR(500),
    summary             VARCHAR(500),
    content             TEXT         NOT NULL,
    author_display_name VARCHAR(100) NOT NULL,
    status              VARCHAR(30)  NOT NULL,
    first_published_at  TIMESTAMPTZ,
    created_by          BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_articles_slug UNIQUE (slug),
    CONSTRAINT ck_articles_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'HIDDEN'))
);
CREATE INDEX ix_articles_status_published ON articles (status, first_published_at DESC);
CREATE INDEX ix_articles_category_status ON articles (category_id, status);

CREATE TABLE page_contents (
    key        VARCHAR(50)  PRIMARY KEY,
    title      VARCHAR(250),
    content    JSONB        NOT NULL,
    updated_by BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE feedbacks (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id     BIGINT        NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    topic           VARCHAR(30)   NOT NULL,
    branch_id       BIGINT        REFERENCES branches (id) ON DELETE RESTRICT,
    rating          SMALLINT,
    content         VARCHAR(2000) NOT NULL,
    status          VARCHAR(30)   NOT NULL,
    seen_at         TIMESTAMPTZ,
    resolved_by     BIGINT        REFERENCES accounts (id) ON DELETE RESTRICT,
    resolved_at     TIMESTAMPTZ,
    resolution_note TEXT,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_feedbacks_topic CHECK (topic IN ('BRANCH_SERVICE', 'WEBSITE', 'OTHER')),
    CONSTRAINT ck_feedbacks_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_feedbacks_status CHECK (status IN ('NEW', 'SEEN', 'RESOLVED')),
    -- BR-DG-01
    CONSTRAINT ck_feedbacks_branch_required CHECK (topic <> 'BRANCH_SERVICE' OR branch_id IS NOT NULL),
    CONSTRAINT ck_feedbacks_content_length CHECK (char_length(content) >= 10),
    -- BR-DG-04
    CONSTRAINT ck_feedbacks_resolution_note CHECK (status <> 'RESOLVED' OR resolution_note IS NOT NULL)
);
CREATE INDEX ix_feedbacks_customer_created_at ON feedbacks (customer_id, created_at);
CREATE INDEX ix_feedbacks_branch_status ON feedbacks (branch_id, status);

-- =============================================================================
-- erd §11 Chăm sóc khách & thông báo (TB)
-- =============================================================================

CREATE TABLE care_tasks (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    branch_id           BIGINT       NOT NULL REFERENCES branches (id) ON DELETE RESTRICT,
    customer_id         BIGINT       NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    pet_id              BIGINT       NOT NULL REFERENCES pets (id) ON DELETE RESTRICT,
    task_type           VARCHAR(30)  NOT NULL,
    vaccination_id      BIGINT       REFERENCES vaccinations (id) ON DELETE RESTRICT,
    medical_record_id   BIGINT       REFERENCES medical_records (visit_id) ON DELETE RESTRICT,
    boarding_booking_id BIGINT       REFERENCES boarding_bookings (id) ON DELETE RESTRICT,
    due_date            DATE         NOT NULL,
    status              VARCHAR(30)  NOT NULL,
    result              VARCHAR(30),
    note                TEXT,
    cancel_reason       VARCHAR(300),
    handled_by          BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    handled_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_care_tasks_task_type CHECK (task_type IN ('VACCINE_DUE', 'VACCINE_OVERDUE', 'FOLLOW_UP_DUE',
                                                            'PICKUP_OVERDUE')),
    CONSTRAINT ck_care_tasks_status CHECK (status IN ('OPEN', 'DONE', 'CANCELLED')),
    CONSTRAINT ck_care_tasks_result CHECK (result IN ('REACHED', 'UNREACHABLE')),
    -- Đúng 1 đối tượng theo loại
    CONSTRAINT ck_care_tasks_target CHECK (
        (task_type IN ('VACCINE_DUE', 'VACCINE_OVERDUE') AND vaccination_id IS NOT NULL
            AND medical_record_id IS NULL AND boarding_booking_id IS NULL)
        OR (task_type = 'FOLLOW_UP_DUE' AND medical_record_id IS NOT NULL
            AND vaccination_id IS NULL AND boarding_booking_id IS NULL)
        OR (task_type = 'PICKUP_OVERDUE' AND boarding_booking_id IS NOT NULL
            AND vaccination_id IS NULL AND medical_record_id IS NULL)),
    CONSTRAINT ck_care_tasks_done_result CHECK ((status = 'DONE') = (result IS NOT NULL))
);
-- Tối đa 1 task quá hạn mỗi mũi (BR-TB-04)
CREATE UNIQUE INDEX uq_care_tasks_vaccine_overdue ON care_tasks (vaccination_id) WHERE task_type = 'VACCINE_OVERDUE';
CREATE UNIQUE INDEX uq_care_tasks_pickup_overdue ON care_tasks (boarding_booking_id) WHERE task_type = 'PICKUP_OVERDUE';
CREATE INDEX ix_care_tasks_branch_status_due ON care_tasks (branch_id, status, due_date);

CREATE TABLE notifications (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT       NOT NULL REFERENCES accounts (id) ON DELETE RESTRICT,
    type       VARCHAR(40)  NOT NULL,
    title      VARCHAR(200) NOT NULL,
    body       TEXT         NOT NULL,
    link_url   VARCHAR(500),
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_notifications_account_read_created ON notifications (account_id, read_at, created_at DESC);

-- LOG. Ghi cùng transaction với sự kiện nghiệp vụ, worker ST20 gửi sau
CREATE TABLE notification_outbox (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    channel              VARCHAR(30)  NOT NULL,
    template_code        VARCHAR(50)  NOT NULL REFERENCES notification_templates (code) ON DELETE RESTRICT,
    recipient_email      VARCHAR(255),
    recipient_account_id BIGINT       REFERENCES accounts (id) ON DELETE RESTRICT,
    payload              JSONB        NOT NULL,
    status               VARCHAR(30)  NOT NULL,
    attempts             SMALLINT     NOT NULL DEFAULT 0,
    next_attempt_at      TIMESTAMPTZ  NOT NULL,
    last_error           TEXT,
    sent_at              TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_notification_outbox_channel CHECK (channel IN ('EMAIL', 'IN_APP')),
    CONSTRAINT ck_notification_outbox_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);
CREATE INDEX ix_notification_outbox_status_next_attempt ON notification_outbox (status, next_attempt_at);

-- =============================================================================
-- audit_logs chỉ cho phép INSERT (BR-QT-16) [ERD 6]
-- =============================================================================

CREATE FUNCTION forbid_audit_log_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_logs chỉ cho phép INSERT (BR-QT-16)';
END;
$$;

CREATE TRIGGER trg_audit_logs_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_log_change();

CREATE TRIGGER trg_audit_logs_no_truncate
    BEFORE TRUNCATE ON audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_audit_log_change();
