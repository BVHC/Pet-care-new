-- ============================================================================
-- V1: Pet Care Ecosystem — Full Relational Schema
-- Nguồn chân lý: docs/06-erd.md (PostgreSQL 17, UUID PK, đa tenant 5 tầng).
-- Quyết định kỹ thuật (xem plan C:\Users\Admin\.claude\plans\c-to-n-b-docs-eager-beacon.md):
--   - PK UUID DEFAULT gen_random_uuid() cho mọi bảng trừ role_permissions (composite PK).
--     PostgreSQL 17 có gen_random_uuid() built-in, không cần extension.
--   - Cột trạng thái/lifecycle có FSM tương ứng trong docs/03-state-machines.md dùng
--     PostgreSQL native ENUM type theo đúng docs/06-erd.md §4 (32 CREATE TYPE).
--   - Cột phân loại không có CREATE TYPE tường minh trong §4 (species, gender, channel,
--     resource_type, category, reason, severity...) giữ nguyên VARCHAR(n) như mô tả §3.
--   - Không chèn seed/demo data — ngoài phạm vi đặc tả schema của docs/06-erd.md.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 0. PostgreSQL Enum Types (docs/06-erd.md §4 — 32 types)
-- ----------------------------------------------------------------------------

CREATE TYPE account_status_enum AS ENUM ('PENDING_VERIFICATION', 'ACTIVE', 'LOCKED', 'DEACTIVATED');
CREATE TYPE user_role_enum AS ENUM ('SUPER_ADMIN', 'ORGANIZATION_ADMIN', 'STORE_MANAGER', 'RECEPTIONIST', 'VETERINARIAN', 'GROOMER', 'INVENTORY_STAFF', 'FINANCE_STAFF', 'CUSTOMER');
CREATE TYPE security_scope_enum AS ENUM ('PLATFORM', 'ORGANIZATION', 'STORE', 'WAREHOUSE', 'CUSTOMER');

CREATE TYPE facility_type_enum AS ENUM ('RETAIL_STORE', 'CENTRAL_WAREHOUSE');
CREATE TYPE store_status_enum AS ENUM ('DRAFT', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED', 'ARCHIVED');
CREATE TYPE caregiver_status_enum AS ENUM ('INVITED', 'ACTIVE', 'REJECTED', 'EXPIRED', 'REVOKED');
CREATE TYPE pet_status_enum AS ENUM ('ACTIVE', 'DECEASED', 'TRANSFERRED');

CREATE TYPE booking_hold_status_enum AS ENUM ('HOLDING', 'CONFIRMED', 'RELEASED', 'EXPIRED');
CREATE TYPE appointment_status_enum AS ENUM ('BOOKED', 'CONFIRMED', 'CHECKED_IN', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'NO_SHOW', 'ABORTED');
CREATE TYPE queue_entry_status_enum AS ENUM ('WAITING', 'CALLED', 'IN_SERVICE', 'COMPLETED', 'CANCELLED', 'NO_SHOW');

CREATE TYPE grooming_status_enum AS ENUM ('WAITING', 'IN_PROGRESS', 'AWAITING_CUSTOMER_APPROVAL', 'COMPLETED', 'CANCELLED', 'ABORTED', 'REJECTED');
CREATE TYPE consent_status_enum AS ENUM ('REQUESTED', 'ACTIVE', 'REVOKED', 'EXPIRED');
CREATE TYPE medical_record_status_enum AS ENUM ('DRAFT', 'FINALIZED', 'LOCKED');

CREATE TYPE order_status_enum AS ENUM ('PENDING_PAYMENT', 'PAID', 'CONFIRMED', 'PROCESSING', 'READY', 'DELIVERED', 'CANCELLED', 'REFUNDED');
CREATE TYPE invoice_status_enum AS ENUM ('DRAFT', 'ISSUED', 'PAID', 'VOID', 'CANCELLED');
CREATE TYPE invoice_type_enum AS ENUM ('SERVICE_INVOICE', 'PACKAGE_INVOICE', 'SURCHARGE_INVOICE');
CREATE TYPE payment_status_enum AS ENUM ('PENDING', 'PROCESSING', 'SUCCESS', 'FAILED', 'CANCELLED', 'PARTIALLY_REFUNDED', 'REFUNDED');
CREATE TYPE payment_method_enum AS ENUM ('CASH', 'ONLINE_GATEWAY');
CREATE TYPE refund_status_enum AS ENUM ('REQUESTED', 'APPROVED', 'REJECTED', 'PROCESSING', 'COMPLETED', 'FAILED');

CREATE TYPE stock_transfer_status_enum AS ENUM ('REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED', 'IN_TRANSIT', 'DISCREPANCY_RECORDED', 'RECEIVED');
CREATE TYPE purchase_request_status_enum AS ENUM ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED');
CREATE TYPE purchase_order_status_enum AS ENUM ('ISSUED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CLOSED', 'CANCELLED');
CREATE TYPE supplier_status_enum AS ENUM ('ACTIVE', 'INACTIVE');
CREATE TYPE membership_status_enum AS ENUM ('ACTIVE', 'UPGRADED', 'EXPIRED');
CREATE TYPE package_status_enum AS ENUM ('PURCHASED', 'ACTIVATED', 'PARTIALLY_CONSUMED', 'FULLY_CONSUMED', 'CANCELLED', 'EXPIRED');
CREATE TYPE incident_status_enum AS ENUM ('RECORDED', 'CLASSIFIED', 'UNDER_INVESTIGATION', 'ESCALATED', 'RESOLVED', 'CLOSED');

CREATE TYPE promotion_status_enum AS ENUM ('DRAFT', 'ACTIVE', 'PAUSED', 'EXPIRED');
CREATE TYPE voucher_discount_type_enum AS ENUM ('PERCENTAGE', 'FIXED_AMOUNT');
CREATE TYPE voucher_status_enum AS ENUM ('ACTIVE', 'DISABLED', 'EXPIRED');
CREATE TYPE notification_channel_enum AS ENUM ('IN_APP', 'SMS', 'EMAIL', 'PUSH');
CREATE TYPE notification_status_enum AS ENUM ('PENDING', 'PROCESSING', 'SENT', 'FAILED');

CREATE TYPE outbox_status_enum AS ENUM ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED');

-- ----------------------------------------------------------------------------
-- 1. Auth, IAM, Organization & Store (docs/06-erd.md §3.1)
-- ----------------------------------------------------------------------------

CREATE TABLE accounts (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone                  VARCHAR(20),
    email                  VARCHAR(100) NOT NULL,
    password_hash          VARCHAR(255) NOT NULL,
    status                 account_status_enum NOT NULL DEFAULT 'PENDING_VERIFICATION',
    must_change_password   BOOLEAN NOT NULL DEFAULT false,
    failed_login_attempts  INT NOT NULL DEFAULT 0,
    lock_reason            VARCHAR(30),
    locked_until           TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by             UUID,
    updated_by             UUID,
    deleted_at             TIMESTAMPTZ,
    version                BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_accounts_phone UNIQUE (phone),
    CONSTRAINT uq_accounts_email UNIQUE (email),
    CONSTRAINT fk_accounts_created_by FOREIGN KEY (created_by) REFERENCES accounts(id),
    CONSTRAINT fk_accounts_updated_by FOREIGN KEY (updated_by) REFERENCES accounts(id)
);
CREATE INDEX idx_accounts_status ON accounts(status);

-- otps.email (không phải phone) là khoá định danh OTP — quyết định đổi danh tính
-- chính Auth sang email (xem docs/02-business-rules.md RULE-01-10, sửa lại).
-- otps.locked_until: verification lockout 15 phút sau 5 lần nhập sai (RULE-01-05),
-- cùng pattern accounts.locked_until.
CREATE TABLE otps (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email          VARCHAR(100) NOT NULL,
    otp_code       VARCHAR(10) NOT NULL,
    purpose        VARCHAR(50) NOT NULL DEFAULT 'REGISTRATION',
    is_used        BOOLEAN NOT NULL DEFAULT false,
    attempt_count  INT NOT NULL DEFAULT 0,
    expires_at     TIMESTAMPTZ NOT NULL,
    locked_until   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_otps_email_purpose ON otps(email, purpose, is_used, expires_at);

CREATE TABLE organizations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(50) NOT NULL,
    name        VARCHAR(255) NOT NULL,
    tax_code    VARCHAR(50),
    status      VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_organizations_code UNIQUE (code)
);

CREATE TABLE roles (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID REFERENCES organizations(id),
    code             VARCHAR(50) NOT NULL,
    name             VARCHAR(100) NOT NULL,
    scope            security_scope_enum NOT NULL DEFAULT 'STORE',
    description      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_roles_org_code UNIQUE (organization_id, code)
);

CREATE TABLE permissions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code         VARCHAR(100) NOT NULL,
    module       VARCHAR(50) NOT NULL,
    description  TEXT,
    CONSTRAINT uq_permissions_code UNIQUE (code)
);

CREATE TABLE role_permissions (
    role_id        UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id  UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE stores (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  UUID NOT NULL,
    code             VARCHAR(50) NOT NULL,
    name             VARCHAR(255) NOT NULL,
    facility_type    facility_type_enum NOT NULL DEFAULT 'RETAIL_STORE',
    address          TEXT NOT NULL,
    phone            VARCHAR(20) NOT NULL,
    status           store_status_enum NOT NULL DEFAULT 'DRAFT',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_stores_org FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE,
    CONSTRAINT uq_stores_org_code UNIQUE (organization_id, code)
);

CREATE TABLE users (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id       UUID NOT NULL,
    organization_id  UUID,
    store_id         UUID,
    full_name        VARCHAR(100) NOT NULL,
    gender           VARCHAR(10),
    date_of_birth    DATE,
    avatar_url       VARCHAR(255),
    role             user_role_enum NOT NULL DEFAULT 'CUSTOMER',
    staff_code       VARCHAR(50),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by       UUID,
    updated_by       UUID,
    deleted_at       TIMESTAMPTZ,
    version          BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_users_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE RESTRICT,
    CONSTRAINT fk_users_org FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE SET NULL,
    CONSTRAINT fk_users_store FOREIGN KEY (store_id) REFERENCES stores(id) ON DELETE SET NULL,
    CONSTRAINT fk_users_created_by FOREIGN KEY (created_by) REFERENCES accounts(id),
    CONSTRAINT fk_users_updated_by FOREIGN KEY (updated_by) REFERENCES accounts(id)
);
CREATE INDEX idx_users_org_store ON users(organization_id, store_id);
CREATE INDEX idx_users_role ON users(role);

CREATE TABLE user_roles (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id     UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    scope_id    UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ----------------------------------------------------------------------------
-- 2. Store Config & Service/Product Catalog (docs/06-erd.md §3.2)
-- ----------------------------------------------------------------------------

CREATE TABLE operating_hours (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id     UUID NOT NULL,
    day_of_week  INT NOT NULL,
    open_time    TIME,
    close_time   TIME,
    is_closed    BOOLEAN NOT NULL DEFAULT false,
    CONSTRAINT fk_operating_hours_store FOREIGN KEY (store_id) REFERENCES stores(id) ON DELETE CASCADE,
    CONSTRAINT uq_operating_hours_store_day UNIQUE (store_id, day_of_week)
);

CREATE TABLE store_resources (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id       UUID NOT NULL,
    resource_code  VARCHAR(50) NOT NULL,
    resource_name  VARCHAR(100) NOT NULL,
    resource_type  VARCHAR(50) NOT NULL,
    is_active      BOOLEAN NOT NULL DEFAULT true,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_resources_store FOREIGN KEY (store_id) REFERENCES stores(id) ON DELETE CASCADE,
    CONSTRAINT uq_store_resources_code UNIQUE (store_id, resource_code)
);

CREATE TABLE services (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    UUID NOT NULL REFERENCES organizations(id),
    code               VARCHAR(50) NOT NULL,
    name               VARCHAR(255) NOT NULL,
    category           VARCHAR(50) NOT NULL,
    base_price         DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    duration_minutes   INT NOT NULL DEFAULT 30,
    is_active          BOOLEAN NOT NULL DEFAULT true,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE service_required_resources (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id          UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    resource_type       VARCHAR(50) NOT NULL,
    quantity_required   INT NOT NULL DEFAULT 1
);

CREATE TABLE products (
    id                                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id                   UUID NOT NULL REFERENCES organizations(id),
    sku                               VARCHAR(50) NOT NULL,
    barcode                           VARCHAR(50),
    name                              VARCHAR(255) NOT NULL,
    category                          VARCHAR(50) NOT NULL,
    unit                              VARCHAR(20) NOT NULL DEFAULT 'ITEM',
    is_fractional                     BOOLEAN NOT NULL DEFAULT false,
    base_unit                         VARCHAR(10) NOT NULL DEFAULT 'UNIT',
    purchase_unit_conversion_factor   INT NOT NULL DEFAULT 1,
    base_price                        DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    cost_price                        DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    is_active                         BOOLEAN NOT NULL DEFAULT true,
    created_at                        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_products_org_sku UNIQUE (organization_id, sku),
    CONSTRAINT chk_products_conversion_factor CHECK (purchase_unit_conversion_factor >= 1)
);

CREATE TABLE store_products (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id    UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    product_id  UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    price       DECIMAL(12,2) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_store_products_store_product UNIQUE (store_id, product_id)
);

CREATE TABLE store_services (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id    UUID NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    service_id  UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    price       DECIMAL(12,2) NOT NULL,
    is_active   BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_store_services_store_service UNIQUE (store_id, service_id)
);

CREATE TABLE suppliers (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL REFERENCES organizations(id),
    code            VARCHAR(50) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    contact_phone   VARCHAR(20),
    contact_email   VARCHAR(100),
    address         TEXT,
    status          supplier_status_enum NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_suppliers_org_code UNIQUE (organization_id, code)
);

-- ----------------------------------------------------------------------------
-- 3. Customer, Pet, Delegation, Membership & Package (docs/06-erd.md §3.2/§3.3)
-- ----------------------------------------------------------------------------

CREATE TABLE pets (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id           UUID NOT NULL REFERENCES users(id),
    name               VARCHAR(100) NOT NULL,
    species            VARCHAR(50) NOT NULL DEFAULT 'DOG',
    breed              VARCHAR(100),
    gender             VARCHAR(10) NOT NULL DEFAULT 'UNKNOWN',
    date_of_birth      DATE,
    weight_kg          DECIMAL(5,2),
    microchip_number   VARCHAR(50),
    avatar_url         VARCHAR(255),
    status             pet_status_enum NOT NULL DEFAULT 'ACTIVE',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE pet_caregiver_delegations (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pet_id              UUID NOT NULL REFERENCES pets(id),
    primary_owner_id    UUID NOT NULL REFERENCES users(id),
    caregiver_user_id   UUID REFERENCES users(id),
    caregiver_phone     VARCHAR(20) NOT NULL,
    invitation_token    VARCHAR(100) NOT NULL,
    status              caregiver_status_enum NOT NULL DEFAULT 'INVITED',
    expires_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_pet_caregiver_delegations_token UNIQUE (invitation_token)
);

CREATE TABLE memberships (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES users(id),
    organization_id  UUID NOT NULL REFERENCES organizations(id),
    tier             VARCHAR(30) NOT NULL DEFAULT 'BRONZE',
    status           membership_status_enum NOT NULL DEFAULT 'ACTIVE',
    current_points   INT NOT NULL DEFAULT 0,
    valid_until      TIMESTAMPTZ NOT NULL
);

CREATE TABLE loyalty_point_ledgers (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    membership_id      UUID NOT NULL REFERENCES memberships(id) ON DELETE CASCADE,
    points_change      INT NOT NULL,
    transaction_type   VARCHAR(30) NOT NULL,
    reference_id       UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE service_packages (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES users(id),
    service_id        UUID NOT NULL REFERENCES services(id),
    package_code      VARCHAR(50) NOT NULL,
    status            package_status_enum NOT NULL DEFAULT 'PURCHASED',
    total_units       INT NOT NULL,
    remaining_units   INT NOT NULL,
    purchase_price    DECIMAL(12,2) NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_service_packages_code UNIQUE (package_code)
);

-- ----------------------------------------------------------------------------
-- 4. Appointment, Queue & Workforce (docs/06-erd.md §3.3)
-- ----------------------------------------------------------------------------

CREATE TABLE booking_holds (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id              UUID NOT NULL REFERENCES stores(id),
    resource_id           UUID REFERENCES store_resources(id),
    staff_id              UUID REFERENCES users(id),
    customer_id           UUID NOT NULL REFERENCES users(id),
    pet_id                UUID NOT NULL REFERENCES pets(id),
    service_id            UUID NOT NULL REFERENCES services(id),
    service_package_id    UUID REFERENCES service_packages(id),
    start_time            TIMESTAMPTZ NOT NULL,
    end_time              TIMESTAMPTZ NOT NULL,
    status                booking_hold_status_enum NOT NULL DEFAULT 'HOLDING',
    expires_at            TIMESTAMPTZ NOT NULL
);

CREATE TABLE appointments (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id               UUID NOT NULL REFERENCES stores(id),
    customer_id            UUID NOT NULL REFERENCES users(id),
    pet_id                 UUID NOT NULL REFERENCES pets(id),
    service_id             UUID NOT NULL REFERENCES services(id),
    staff_id               UUID REFERENCES users(id),
    resource_id            UUID REFERENCES store_resources(id),
    queue_entry_id         UUID,
    service_package_id     UUID REFERENCES service_packages(id),
    appointment_number     VARCHAR(50) NOT NULL,
    start_time             TIMESTAMPTZ NOT NULL,
    end_time               TIMESTAMPTZ NOT NULL,
    actual_start_time      TIMESTAMPTZ,
    actual_end_time        TIMESTAMPTZ,
    channel                VARCHAR(20) NOT NULL DEFAULT 'ONLINE',
    status                 appointment_status_enum NOT NULL DEFAULT 'BOOKED',
    cancellation_reason    TEXT,
    abort_reason           TEXT,
    version                BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_appointments_number UNIQUE (appointment_number),
    CONSTRAINT chk_appointment_walkin_queue CHECK (
        (channel = 'WALK_IN' AND queue_entry_id IS NOT NULL) OR
        (channel != 'WALK_IN' AND queue_entry_id IS NULL)
    )
    -- FK appointments.queue_entry_id -> queue_entries.id: xem ALTER TABLE cuối §4
    -- (queue_entries.appointment_id tham chiếu ngược appointments.id => vòng lặp FK).
);

CREATE TABLE appointment_stage_histories (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id    UUID NOT NULL REFERENCES appointments(id) ON DELETE CASCADE,
    from_status       VARCHAR(30) NOT NULL,
    to_status         VARCHAR(30) NOT NULL,
    changed_by        UUID NOT NULL REFERENCES users(id),
    notes             TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE package_usage_records (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_package_id    UUID NOT NULL REFERENCES service_packages(id) ON DELETE CASCADE,
    appointment_id        UUID NOT NULL REFERENCES appointments(id),
    units_consumed        INT NOT NULL DEFAULT 1,
    consumed_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE staff_work_schedules (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id        UUID NOT NULL REFERENCES stores(id),
    schedule_date   DATE NOT NULL,
    created_by      UUID NOT NULL REFERENCES users(id)
);

CREATE TABLE shift_assignments (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schedule_id   UUID NOT NULL REFERENCES staff_work_schedules(id) ON DELETE CASCADE,
    staff_id      UUID NOT NULL REFERENCES users(id),
    shift_type    VARCHAR(30) NOT NULL DEFAULT 'MORNING',
    start_time    TIME NOT NULL,
    end_time      TIME NOT NULL
);

CREATE TABLE staff_absences (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    staff_id       UUID NOT NULL REFERENCES users(id),
    store_id       UUID NOT NULL REFERENCES stores(id),
    absence_type   VARCHAR(30) NOT NULL DEFAULT 'VACATION',
    start_date     DATE NOT NULL,
    end_date       DATE NOT NULL,
    status         VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    approved_by    UUID REFERENCES users(id)
);

CREATE TABLE daily_queues (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id    UUID NOT NULL REFERENCES stores(id),
    queue_date  DATE NOT NULL DEFAULT CURRENT_DATE,
    is_active   BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE queue_entries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    queue_id        UUID NOT NULL REFERENCES daily_queues(id) ON DELETE CASCADE,
    store_id        UUID NOT NULL REFERENCES stores(id),
    customer_id     UUID REFERENCES users(id),
    pet_id          UUID REFERENCES pets(id),
    service_id      UUID NOT NULL REFERENCES services(id),
    appointment_id  UUID REFERENCES appointments(id),
    queue_number    VARCHAR(20) NOT NULL,
    priority        VARCHAR(30) NOT NULL DEFAULT 'NORMAL',
    status          queue_entry_status_enum NOT NULL DEFAULT 'WAITING',
    called_times    INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_queue_entries_appointment_id UNIQUE (appointment_id)
);

ALTER TABLE appointments
    ADD CONSTRAINT fk_appointments_queue_entry FOREIGN KEY (queue_entry_id) REFERENCES queue_entries(id);

-- ----------------------------------------------------------------------------
-- 5. EMR, Vaccination, Grooming & Cross-Store Consent (docs/06-erd.md §3.4)
-- ----------------------------------------------------------------------------

CREATE TABLE medical_records (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id         UUID REFERENCES appointments(id),
    store_id               UUID NOT NULL REFERENCES stores(id),
    pet_id                 UUID NOT NULL REFERENCES pets(id),
    veterinarian_id        UUID NOT NULL REFERENCES users(id),
    record_number          VARCHAR(50) NOT NULL,
    chief_complaint        TEXT NOT NULL,
    clinical_signs         TEXT,
    temperature_celsius    DECIMAL(4,1),
    weight_kg              DECIMAL(5,2) NOT NULL,
    treatment_plan         TEXT,
    status                 medical_record_status_enum NOT NULL DEFAULT 'DRAFT',
    finalized_at           TIMESTAMPTZ,
    is_locked              BOOLEAN NOT NULL DEFAULT false,
    locked_at              TIMESTAMPTZ,
    CONSTRAINT uq_medical_records_number UNIQUE (record_number)
);

CREATE TABLE diagnoses (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    medical_record_id   UUID NOT NULL REFERENCES medical_records(id) ON DELETE CASCADE,
    diagnostic_code     VARCHAR(50) NOT NULL,
    description         TEXT NOT NULL
);

CREATE TABLE treatments (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    medical_record_id   UUID NOT NULL REFERENCES medical_records(id) ON DELETE CASCADE,
    diagnosis_id        UUID REFERENCES diagnoses(id),
    description         TEXT NOT NULL,
    created_by          UUID NOT NULL REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE follow_ups (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    medical_record_id   UUID NOT NULL REFERENCES medical_records(id) ON DELETE CASCADE,
    pet_id              UUID NOT NULL REFERENCES pets(id),
    scheduled_date      DATE NOT NULL,
    veterinarian_id     UUID NOT NULL REFERENCES users(id),
    notes               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE prescriptions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    medical_record_id   UUID NOT NULL REFERENCES medical_records(id) ON DELETE CASCADE,
    veterinarian_id     UUID NOT NULL REFERENCES users(id),
    prescribed_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE prescription_items (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    prescription_id   UUID NOT NULL REFERENCES prescriptions(id) ON DELETE CASCADE,
    product_id        UUID NOT NULL REFERENCES products(id),
    quantity          INT NOT NULL,
    dosage            VARCHAR(100) NOT NULL,
    frequency         VARCHAR(100) NOT NULL,
    duration_days     INT NOT NULL,
    instructions      TEXT
);

CREATE TABLE vaccine_batches (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id              UUID NOT NULL REFERENCES stores(id),
    vaccine_id            UUID NOT NULL REFERENCES products(id),
    batch_number          VARCHAR(50) NOT NULL,
    expiry_date           DATE NOT NULL,
    quantity_initial      INT NOT NULL,
    quantity_remaining    INT NOT NULL
);

CREATE TABLE vaccinations (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pet_id             UUID NOT NULL REFERENCES pets(id),
    store_id           UUID NOT NULL REFERENCES stores(id),
    vaccine_id         UUID NOT NULL REFERENCES products(id),
    batch_id           UUID NOT NULL REFERENCES vaccine_batches(id),
    veterinarian_id    UUID NOT NULL REFERENCES users(id),
    dose_volume_ml     DECIMAL(4,2) NOT NULL DEFAULT 1.00,
    administered_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    next_due_date      DATE
);

CREATE TABLE grooming_sessions (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    appointment_id         UUID NOT NULL REFERENCES appointments(id),
    store_id               UUID NOT NULL REFERENCES stores(id),
    pet_id                 UUID NOT NULL REFERENCES pets(id),
    groomer_id             UUID NOT NULL REFERENCES users(id),
    status                 grooming_status_enum NOT NULL DEFAULT 'WAITING',
    has_surcharge          BOOLEAN NOT NULL DEFAULT false,
    surcharge_invoice_id   UUID,
    abort_reason           TEXT,
    CONSTRAINT uq_grooming_sessions_appointment UNIQUE (appointment_id)
    -- FK grooming_sessions.surcharge_invoice_id -> invoices.id: xem ALTER TABLE ở §7
    -- (invoices được tạo sau trong thứ tự phụ thuộc FK).
);

CREATE TABLE health_inspection_reports (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    grooming_session_id    UUID NOT NULL REFERENCES grooming_sessions(id) ON DELETE CASCADE,
    skin_coat_condition    TEXT NOT NULL,
    ear_eye_condition      TEXT NOT NULL,
    temperament            VARCHAR(30) NOT NULL DEFAULT 'CALM',
    is_accepted            BOOLEAN NOT NULL DEFAULT true,
    rejection_reason       TEXT
);

CREATE TABLE grooming_service_lines (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    grooming_session_id    UUID NOT NULL REFERENCES grooming_sessions(id) ON DELETE CASCADE,
    service_id             UUID NOT NULL REFERENCES services(id),
    is_addon               BOOLEAN NOT NULL DEFAULT false,
    price                  DECIMAL(12,2) NOT NULL,
    customer_approved      BOOLEAN NOT NULL DEFAULT false
);

CREATE TABLE cross_store_consents (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    pet_id                  UUID NOT NULL REFERENCES pets(id),
    requesting_store_id     UUID NOT NULL REFERENCES stores(id),
    source_store_id         UUID REFERENCES stores(id),
    scope_type              VARCHAR(30) NOT NULL DEFAULT 'ALL_STORES',
    requesting_doctor_id    UUID NOT NULL REFERENCES users(id),
    status                  consent_status_enum NOT NULL DEFAULT 'REQUESTED',
    is_emergency            BOOLEAN NOT NULL DEFAULT false,
    emergency_reason        TEXT,
    expires_at              TIMESTAMPTZ NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ----------------------------------------------------------------------------
-- 6. Inventory, Warehouse & Procurement (docs/06-erd.md §3.5)
-- ----------------------------------------------------------------------------

CREATE TABLE inventory_items (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id              UUID NOT NULL REFERENCES stores(id),
    product_id            UUID NOT NULL REFERENCES products(id),
    quantity_physical     INT NOT NULL DEFAULT 0,
    quantity_reserved     INT NOT NULL DEFAULT 0,
    quantity_available    INT NOT NULL DEFAULT 0,
    min_stock_level       INT NOT NULL DEFAULT 5,
    version               BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE inventory_adjustments (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id              UUID NOT NULL REFERENCES stores(id),
    product_id            UUID NOT NULL REFERENCES products(id),
    quantity_adjusted     INT NOT NULL,
    reason                VARCHAR(50) NOT NULL,
    status                VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    created_by            UUID NOT NULL REFERENCES users(id),
    approved_by           UUID REFERENCES users(id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE stock_transfers (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_number     VARCHAR(50) NOT NULL,
    from_store_id       UUID NOT NULL REFERENCES stores(id),
    to_store_id         UUID NOT NULL REFERENCES stores(id),
    status              stock_transfer_status_enum NOT NULL DEFAULT 'REQUESTED',
    created_by          UUID NOT NULL REFERENCES users(id),
    approved_by         UUID REFERENCES users(id),
    shipped_at          TIMESTAMPTZ,
    received_at         TIMESTAMPTZ,
    CONSTRAINT uq_stock_transfers_number UNIQUE (transfer_number)
);

CREATE TABLE stock_transfer_lines (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stock_transfer_id     UUID NOT NULL REFERENCES stock_transfers(id) ON DELETE CASCADE,
    product_id            UUID NOT NULL REFERENCES products(id),
    shipped_quantity      INT NOT NULL,
    received_quantity     INT NOT NULL DEFAULT 0,
    damaged_quantity      INT NOT NULL DEFAULT 0,
    lost_quantity         INT NOT NULL DEFAULT 0
);

CREATE TABLE purchase_requests (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_number      VARCHAR(50) NOT NULL,
    store_id            UUID NOT NULL REFERENCES stores(id),
    status              purchase_request_status_enum NOT NULL DEFAULT 'DRAFT',
    created_by          UUID NOT NULL REFERENCES users(id),
    approved_by         UUID REFERENCES users(id),
    rejection_reason    TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_purchase_requests_number UNIQUE (request_number)
);

CREATE TABLE purchase_request_lines (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    purchase_request_id       UUID NOT NULL REFERENCES purchase_requests(id) ON DELETE CASCADE,
    product_id                UUID NOT NULL REFERENCES products(id),
    requested_quantity        INT NOT NULL,
    estimated_unit_price      DECIMAL(12,2) NOT NULL DEFAULT 0.00
);

CREATE TABLE purchase_orders (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    po_number              VARCHAR(50) NOT NULL,
    purchase_request_id    UUID NOT NULL REFERENCES purchase_requests(id),
    store_id               UUID NOT NULL REFERENCES stores(id),
    supplier_id            UUID NOT NULL REFERENCES suppliers(id),
    status                 purchase_order_status_enum NOT NULL DEFAULT 'ISSUED',
    total_amount           DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    created_by             UUID NOT NULL REFERENCES users(id),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_purchase_orders_number UNIQUE (po_number)
);

CREATE TABLE purchase_order_lines (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    purchase_order_id     UUID NOT NULL REFERENCES purchase_orders(id) ON DELETE CASCADE,
    product_id            UUID NOT NULL REFERENCES products(id),
    ordered_quantity      INT NOT NULL,
    received_quantity     INT NOT NULL DEFAULT 0,
    unit_price            DECIMAL(12,2) NOT NULL
);

CREATE TABLE goods_receipts (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    purchase_order_id    UUID NOT NULL REFERENCES purchase_orders(id),
    received_by          UUID NOT NULL REFERENCES users(id),
    inspected_by         UUID NOT NULL REFERENCES users(id),
    received_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ----------------------------------------------------------------------------
-- 7. Order, Invoice, Payment & Refund (docs/06-erd.md §3.6)
-- ----------------------------------------------------------------------------

CREATE TABLE orders (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id                 UUID NOT NULL REFERENCES stores(id),
    customer_id              UUID NOT NULL REFERENCES users(id),
    order_number             VARCHAR(50) NOT NULL,
    channel                  VARCHAR(20) NOT NULL DEFAULT 'POS_RETAIL',
    status                   order_status_enum NOT NULL DEFAULT 'PENDING_PAYMENT',
    subtotal                 DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    discount_amount          DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    total_amount             DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    total_refunded_amount    DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    version                  BIGINT NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_orders_number UNIQUE (order_number)
);

CREATE TABLE order_items (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id     UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id   UUID NOT NULL REFERENCES products(id),
    quantity     INT NOT NULL DEFAULT 1,
    unit_price   DECIMAL(12,2) NOT NULL,
    line_total   DECIMAL(12,2) NOT NULL
);

CREATE TABLE fulfillment_stage_logs (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id     UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    stage        VARCHAR(30) NOT NULL,
    handled_by   UUID NOT NULL REFERENCES users(id),
    notes        TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE inventory_reservations (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id             UUID NOT NULL REFERENCES orders(id),
    inventory_item_id    UUID NOT NULL REFERENCES inventory_items(id),
    quantity             INT NOT NULL,
    status               VARCHAR(30) NOT NULL DEFAULT 'HELD',
    expires_at           TIMESTAMPTZ NOT NULL
);

CREATE TABLE invoices (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id          UUID NOT NULL REFERENCES organizations(id),
    store_id                 UUID NOT NULL REFERENCES stores(id),
    customer_id              UUID NOT NULL REFERENCES users(id),
    appointment_id           UUID REFERENCES appointments(id),
    order_id                 UUID REFERENCES orders(id),
    invoice_number           VARCHAR(50) NOT NULL,
    invoice_type             invoice_type_enum NOT NULL DEFAULT 'SERVICE_INVOICE',
    status                   invoice_status_enum NOT NULL DEFAULT 'DRAFT',
    subtotal                 DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    discount_amount          DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    tax_amount               DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    total_amount              DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    total_refunded_amount    DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at                  TIMESTAMPTZ,
    CONSTRAINT uq_invoices_number UNIQUE (invoice_number)
);

ALTER TABLE grooming_sessions
    ADD CONSTRAINT fk_grooming_surcharge_invoice FOREIGN KEY (surcharge_invoice_id) REFERENCES invoices(id);

CREATE TABLE invoice_items (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id    UUID NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    item_type     VARCHAR(20) NOT NULL,
    service_id    UUID REFERENCES services(id),
    product_id    UUID REFERENCES products(id),
    description   VARCHAR(255) NOT NULL,
    quantity      INT NOT NULL DEFAULT 1,
    unit_price    DECIMAL(12,2) NOT NULL,
    line_total    DECIMAL(12,2) NOT NULL
);

CREATE TABLE payments (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id          UUID NOT NULL REFERENCES invoices(id) ON DELETE RESTRICT,
    transaction_code    VARCHAR(100) NOT NULL,
    payment_method      payment_method_enum NOT NULL DEFAULT 'CASH',
    amount              DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    status              payment_status_enum NOT NULL DEFAULT 'PENDING',
    idempotency_key     VARCHAR(100),
    gateway_response    JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_payments_transaction_code UNIQUE (transaction_code)
);
CREATE INDEX idx_payments_invoice_id ON payments(invoice_id);

CREATE TABLE refunds (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id      UUID NOT NULL REFERENCES payments(id),
    invoice_id      UUID NOT NULL REFERENCES invoices(id),
    refund_number   VARCHAR(50) NOT NULL,
    amount          DECIMAL(12,2) NOT NULL,
    reason          TEXT NOT NULL,
    status          refund_status_enum NOT NULL DEFAULT 'REQUESTED',
    requested_by    UUID NOT NULL REFERENCES users(id),
    approved_by     UUID REFERENCES users(id),
    retry_count     INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    TIMESTAMPTZ,
    CONSTRAINT uq_refunds_number UNIQUE (refund_number)
);

CREATE TABLE refund_execution_logs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    refund_id           UUID NOT NULL REFERENCES refunds(id) ON DELETE CASCADE,
    attempt_number      INT NOT NULL DEFAULT 1,
    gateway_status      VARCHAR(50) NOT NULL,
    response_payload    JSONB,
    error_message       TEXT,
    executed_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ----------------------------------------------------------------------------
-- 8. Promotion/Voucher, Notification, Incident, Audit, Config & Outbox
--    (docs/06-erd.md §3.7)
-- ----------------------------------------------------------------------------

CREATE TABLE promotion_campaigns (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   UUID NOT NULL REFERENCES organizations(id),
    name              VARCHAR(150) NOT NULL,
    campaign_code     VARCHAR(50) NOT NULL,
    start_date        TIMESTAMPTZ NOT NULL,
    end_date          TIMESTAMPTZ NOT NULL,
    status            promotion_status_enum NOT NULL DEFAULT 'DRAFT',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_promotion_campaigns_code UNIQUE (campaign_code)
);

CREATE TABLE vouchers (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id            UUID NOT NULL REFERENCES promotion_campaigns(id) ON DELETE CASCADE,
    code                   VARCHAR(50) NOT NULL,
    discount_type          voucher_discount_type_enum NOT NULL DEFAULT 'PERCENTAGE',
    discount_value         DECIMAL(12,2) NOT NULL,
    max_discount_amount    DECIMAL(12,2),
    min_order_amount       DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    total_usage_limit      INT NOT NULL DEFAULT 100,
    used_count             INT NOT NULL DEFAULT 0,
    valid_from             TIMESTAMPTZ NOT NULL,
    valid_until            TIMESTAMPTZ NOT NULL,
    status                 voucher_status_enum NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT uq_vouchers_code UNIQUE (code)
);

CREATE TABLE voucher_usages (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    voucher_id         UUID NOT NULL REFERENCES vouchers(id),
    customer_id        UUID NOT NULL REFERENCES users(id),
    order_id           UUID REFERENCES orders(id),
    invoice_id         UUID REFERENCES invoices(id),
    discount_amount    DECIMAL(12,2) NOT NULL,
    used_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_voucher_customer_usage UNIQUE (voucher_id, customer_id, order_id)
);

CREATE TABLE notification_tasks (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_user_id    UUID NOT NULL REFERENCES users(id),
    channel              notification_channel_enum NOT NULL DEFAULT 'IN_APP',
    event_type           VARCHAR(100) NOT NULL,
    title                VARCHAR(255),
    content              TEXT NOT NULL,
    status               notification_status_enum NOT NULL DEFAULT 'PENDING',
    scheduled_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE notification_delivery_logs (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id               UUID NOT NULL REFERENCES notification_tasks(id) ON DELETE CASCADE,
    gateway_provider      VARCHAR(50) NOT NULL,
    gateway_message_id    VARCHAR(100),
    status                VARCHAR(30) NOT NULL,
    response_payload      JSONB,
    delivered_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE incident_reports (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_number    VARCHAR(50) NOT NULL,
    store_id           UUID NOT NULL REFERENCES stores(id),
    pet_id             UUID REFERENCES pets(id),
    category           VARCHAR(50) NOT NULL,
    severity           VARCHAR(30) NOT NULL DEFAULT 'MEDIUM',
    status             incident_status_enum NOT NULL DEFAULT 'RECORDED',
    description        TEXT NOT NULL,
    created_by         UUID NOT NULL REFERENCES users(id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_incident_reports_number UNIQUE (incident_number)
);

CREATE TABLE audit_logs (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id         UUID REFERENCES accounts(id),
    organization_id    UUID REFERENCES organizations(id),
    store_id           UUID REFERENCES stores(id),
    action             VARCHAR(100) NOT NULL,
    resource_type      VARCHAR(50) NOT NULL,
    resource_id        VARCHAR(100) NOT NULL,
    client_ip          VARCHAR(50),
    user_agent         TEXT,
    snapshot_before    JSONB,
    snapshot_after     JSONB,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE system_configs (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id    UUID REFERENCES organizations(id),
    store_id           UUID REFERENCES stores(id),
    config_key         VARCHAR(100) NOT NULL,
    config_value       TEXT NOT NULL,
    value_type         VARCHAR(30) NOT NULL DEFAULT 'STRING',
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_system_configs_key UNIQUE (organization_id, store_id, config_key)
);

CREATE TABLE outbox_events (
    event_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type    VARCHAR(50) NOT NULL,
    aggregate_id      VARCHAR(100) NOT NULL,
    event_type        VARCHAR(100) NOT NULL,
    payload           JSONB NOT NULL,
    status            outbox_status_enum NOT NULL DEFAULT 'PENDING',
    retry_count       INT NOT NULL DEFAULT 0,
    error_message     TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at      TIMESTAMPTZ
);
CREATE INDEX idx_outbox_status_created ON outbox_events(status, created_at);
