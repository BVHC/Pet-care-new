# Pet-care-new: Database Schema & Implementation

> **Date:** 2026-08-18  
> **Duration:** 6 tuần  
> **Scope:** 22 modules (6 FSMs)  
> **Pattern:** Monolithic, Flyway migrations

---

## 1. Roles (5 Roles - Final)

### Nhóm 1: Admin (1 role)

| Role | Mô tả | Ai |
|------|-------|-----|
| SUPER_ADMIN | Quản trị toàn hệ thống, setup | Dev / PO |

### Nhóm 2: Store-Level (4 roles)

| Role | Mô tả | Ai |
|------|-------|-----|
| STORE_MANAGER | Quản lý store, duyệt refunds, inventory, finance, reports | Chủ store |
| RECEPTIONIST | Tiếp khách, check-in, tạo đơn, thu tiền | Lễ tân |
| VETERINARIAN | Khám bệnh, kê đơn, tiêm phòng, tạo bệnh án | Bác sĩ |
| GROOMER | Làm đẹp thú cưng, thêm dịch vụ phát sinh | Stylist |

### Nhóm 3: Customer (1 role)

| Role | Mô tả | Ai |
|------|-------|-----|
| CUSTOMER | Đặt lịch, mua hàng, thanh toán, quản lý pets | Khách hàng |

---

## 2. Phạm vi 22 Modules theo Phase

### Phase 1: Foundation (W1)

| Module | FSM? |
|--------|-------|
| Auth + OTP | ❌ |
| Users | ❌ |
| Organizations | ❌ |
| Stores | ❌ |
| Pets + Caregivers | ❌ |
| Products | ❌ |
| Inventory | ❌ |

### Phase 2: Core Domain (W2)

| Module | FSM? |
|--------|-------|
| **Appointments** | ✅ |
| **Orders** | ✅ |
| **Payments** | ✅ |

### Phase 3: Commerce (W3-W4)

| Module | FSM? |
|--------|-------|
| **Invoices** | ✅ |
| **Refunds** | ✅ |
| Promotions + Vouchers | ❌ |
| **Clinical (FULL)** | ❌ |
| Vaccinations | ❌ |

### Phase 4: Polish (W5-W6)

| Module | FSM? |
|--------|-------|
| FE Integration | ❌ |
| Notifications | ❌ |
| Walk-ins | ❌ |
| **Grooming FSM** | ✅ |
| Workforce | ❌ |
| Reports | ❌ |
| Audit Logs | ❌ |

---

## 3. Database Schema

### 3.1 Enums

```sql
-- V1__core_enums.sql

-- 5 Roles
CREATE TYPE user_role AS ENUM (
    'SUPER_ADMIN',
    'STORE_MANAGER',
    'RECEPTIONIST',
    'VETERINARIAN',
    'GROOMER',
    'CUSTOMER'
);

-- Account Status
CREATE TYPE account_status AS ENUM (
    'PENDING_VERIFICATION',
    'ACTIVE',
    'LOCKED'
);

-- Store Status
CREATE TYPE store_status AS ENUM (
    'ACTIVE', 'SUSPENDED', 'DEACTIVATED', 'ARCHIVED'
);

-- Appointment Status (FSM)
CREATE TYPE appointment_status AS ENUM (
    'BOOKED', 'CONFIRMED', 'CHECKED_IN', 
    'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'NO_SHOW'
);

-- Order Status (FSM)
CREATE TYPE order_status AS ENUM (
    'PENDING_PAYMENT', 'PAID', 'CONFIRMED', 'PROCESSING',
    'READY', 'DELIVERED', 'CANCELLED', 'REFUNDED'
);

-- Payment Status (FSM)
CREATE TYPE payment_status AS ENUM (
    'PENDING', 'PROCESSING', 'SUCCESS', 'FAILED', 'CANCELLED', 'REFUNDED'
);

-- Invoice Status (FSM)
CREATE TYPE invoice_status AS ENUM (
    'DRAFT', 'ISSUED', 'PARTIALLY_PAID', 'PAID', 'VOID', 'REFUNDED'
);

-- Refund Status (FSM)
CREATE TYPE refund_status AS ENUM (
    'REQUESTED', 'APPROVED', 'REJECTED', 'PROCESSING', 'COMPLETED', 'FAILED'
);

-- Caregiver Status
CREATE TYPE caregiver_status AS ENUM (
    'INVITED', 'ACTIVE', 'REJECTED', 'EXPIRED', 'REVOKED'
);

-- Grooming Status (FSM)
CREATE TYPE grooming_status AS ENUM (
    'WAITING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'
);
```

### 3.2 Auth + Users (V2)

```sql
-- V2__accounts_users.sql
CREATE TABLE accounts (
    id BIGSERIAL PRIMARY KEY,
    phone VARCHAR(20) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    status account_status NOT NULL DEFAULT 'PENDING_VERIFICATION',
    role user_role NOT NULL DEFAULT 'CUSTOMER',
    refresh_token VARCHAR(500),
    refresh_token_expiry TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    name VARCHAR(100) NOT NULL,
    avatar VARCHAR(500),
    organization_id BIGINT,
    store_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE otps (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    code VARCHAR(6) NOT NULL,
    type VARCHAR(20) NOT NULL,  -- REGISTRATION, PASSWORD_RESET
    expires_at TIMESTAMP NOT NULL,
    used_at TIMESTAMP,
    attempts INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_account ON users(account_id);
CREATE INDEX idx_users_org ON users(organization_id);
CREATE INDEX idx_users_store ON users(store_id);
```

### 3.3 Organizations + Stores (V3)

```sql
-- V3__organizations_stores.sql
CREATE TABLE organizations (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    code VARCHAR(50) UNIQUE NOT NULL,
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE stores (
    id BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id),
    name VARCHAR(255) NOT NULL,
    code VARCHAR(50) UNIQUE NOT NULL,
    address VARCHAR(500),
    phone VARCHAR(20),
    status store_status NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE operating_hours (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL REFERENCES stores(id),
    day_of_week SMALLINT NOT NULL,  -- 1-7
    open_time TIME,
    close_time TIME,
    is_closed BOOLEAN DEFAULT FALSE,
    UNIQUE(store_id, day_of_week)
);

CREATE TABLE store_services (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL REFERENCES stores(id),
    service_id BIGINT NOT NULL,
    price DECIMAL(12,2) NOT NULL,
    is_available BOOLEAN DEFAULT TRUE,
    UNIQUE(store_id, service_id)
);
```

### 3.4 Pets + Caregivers (V4)

```sql
-- V4__pets.sql
CREATE TABLE pets (
    id BIGSERIAL PRIMARY KEY,
    owner_id BIGINT NOT NULL REFERENCES users(id),
    name VARCHAR(100) NOT NULL,
    species VARCHAR(50) NOT NULL,  -- DOG, CAT, BIRD, OTHER
    breed VARCHAR(100),
    birth_date DATE,
    weight DECIMAL(5,2),
    image_url VARCHAR(500),
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_pets_owner ON pets(owner_id);

CREATE TABLE caregiver_invitations (
    id BIGSERIAL PRIMARY KEY,
    pet_id BIGINT NOT NULL REFERENCES pets(id),
    inviter_id BIGINT NOT NULL REFERENCES users(id),
    caregiver_phone VARCHAR(20) NOT NULL,
    permissions TEXT[],  -- pet:view, appointment:create, ...
    status caregiver_status NOT NULL DEFAULT 'INVITED',
    expires_at TIMESTAMP NOT NULL,
    accepted_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 3.5 Services + Products + Inventory (V5)

```sql
-- V5__services_products_inventory.sql
CREATE TABLE services (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    category VARCHAR(50),  -- CHECKUP, VACCINATION, GROOMING, SURGERY
    duration_minutes INT DEFAULT 30,
    price DECIMAL(12,2),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE products (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    category VARCHAR(50),
    price DECIMAL(12,2) NOT NULL,
    discount_percent INT DEFAULT 0,
    image_url VARCHAR(500),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE inventory (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL REFERENCES stores(id),
    product_id BIGINT NOT NULL REFERENCES products(id),
    quantity INT NOT NULL DEFAULT 0,
    min_threshold INT DEFAULT 5,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(store_id, product_id)
);

CREATE INDEX idx_inventory_store ON inventory(store_id);
CREATE INDEX idx_inventory_low_stock ON inventory(store_id, quantity) WHERE quantity <= min_threshold;
```

### 3.6 Appointments (V6) - FSM

```sql
-- V6__appointments.sql
CREATE TABLE appointments (
    id BIGSERIAL PRIMARY KEY,
    pet_id BIGINT NOT NULL REFERENCES pets(id),
    store_id BIGINT NOT NULL REFERENCES stores(id),
    service_id BIGINT NOT NULL REFERENCES services(id),
    staff_id BIGINT REFERENCES users(id),
    customer_id BIGINT NOT NULL REFERENCES users(id),
    scheduled_at TIMESTAMP NOT NULL,
    status appointment_status NOT NULL DEFAULT 'BOOKED',
    notes TEXT,
    checked_in_at TIMESTAMP,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    cancelled_at TIMESTAMP,
    cancellation_reason TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_appointments_pet ON appointments(pet_id);
CREATE INDEX idx_appointments_store ON appointments(store_id);
CREATE INDEX idx_appointments_status ON appointments(status);
CREATE INDEX idx_appointments_scheduled ON appointments(scheduled_at);
```

### 3.7 Orders + Cart (V7) - FSM

```sql
-- V7__orders.sql
CREATE TABLE carts (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES users(id),
    store_id BIGINT NOT NULL REFERENCES stores(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(customer_id, store_id)
);

CREATE TABLE cart_items (
    id BIGSERIAL PRIMARY KEY,
    cart_id BIGINT NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES products(id),
    quantity INT NOT NULL,
    unit_price DECIMAL(12,2) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    order_number VARCHAR(50) UNIQUE NOT NULL,
    customer_id BIGINT NOT NULL REFERENCES users(id),
    store_id BIGINT NOT NULL REFERENCES stores(id),
    status order_status NOT NULL DEFAULT 'PENDING_PAYMENT',
    subtotal DECIMAL(12,2) NOT NULL,
    discount DECIMAL(12,2) DEFAULT 0,
    total DECIMAL(12,2) NOT NULL,
    voucher_code VARCHAR(50),
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES products(id),
    quantity INT NOT NULL,
    unit_price DECIMAL(12,2) NOT NULL,
    subtotal DECIMAL(12,2) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_orders_customer ON orders(customer_id);
CREATE INDEX idx_orders_store ON orders(store_id);
CREATE INDEX idx_orders_status ON orders(status);
```

### 3.8 Payments (V8) - FSM

```sql
-- V8__payments.sql
CREATE TABLE payments (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id),
    amount DECIMAL(12,2) NOT NULL,
    method VARCHAR(50) NOT NULL,  -- ONLINE, CASH
    status payment_status NOT NULL DEFAULT 'PENDING',
    transaction_id VARCHAR(100),
    gateway_response JSONB,
    paid_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_payments_order ON payments(order_id);
CREATE INDEX idx_payments_status ON payments(status);
CREATE INDEX idx_payments_transaction ON payments(transaction_id);
```

### 3.9 Invoices (V9) - FSM

```sql
-- V9__invoices.sql
CREATE TABLE invoices (
    id BIGSERIAL PRIMARY KEY,
    invoice_number VARCHAR(50) UNIQUE NOT NULL,
    order_id BIGINT REFERENCES orders(id),
    appointment_id BIGINT REFERENCES appointments(id),
    customer_id BIGINT NOT NULL REFERENCES users(id),
    store_id BIGINT NOT NULL REFERENCES stores(id),
    status invoice_status NOT NULL DEFAULT 'DRAFT',
    subtotal DECIMAL(12,2) NOT NULL,
    tax DECIMAL(12,2) DEFAULT 0,
    discount DECIMAL(12,2) DEFAULT 0,
    total DECIMAL(12,2) NOT NULL,
    paid_amount DECIMAL(12,2) DEFAULT 0,
    issued_at TIMESTAMP,
    voided_at TIMESTAMP,
    void_reason TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE invoice_items (
    id BIGSERIAL PRIMARY KEY,
    invoice_id BIGINT NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    type VARCHAR(20) NOT NULL,  -- SERVICE, PRODUCT
    reference_id BIGINT NOT NULL,
    name VARCHAR(200) NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(12,2) NOT NULL,
    subtotal DECIMAL(12,2) NOT NULL
);

CREATE INDEX idx_invoices_customer ON invoices(customer_id);
CREATE INDEX idx_invoices_status ON invoices(status);
```

### 3.10 Refunds (V10) - FSM

```sql
-- V10__refunds.sql
CREATE TABLE refunds (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT NOT NULL REFERENCES payments(id),
    order_id BIGINT NOT NULL REFERENCES orders(id),
    amount DECIMAL(12,2) NOT NULL,
    reason TEXT NOT NULL,
    status refund_status NOT NULL DEFAULT 'REQUESTED',
    approved_by BIGINT REFERENCES users(id),
    approved_at TIMESTAMP,
    rejection_reason TEXT,
    processed_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_refunds_payment ON refunds(payment_id);
CREATE INDEX idx_refunds_status ON refunds(status);
```

### 3.11 Promotions + Vouchers (V11)

```sql
-- V11__promotions.sql
CREATE TABLE vouchers (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(50) UNIQUE NOT NULL,
    description TEXT,
    discount_type VARCHAR(20) NOT NULL,  -- PERCENT, FIXED
    discount_value DECIMAL(12,2) NOT NULL,
    min_order_value DECIMAL(12,2),
    max_usage INT,
    current_usage INT DEFAULT 0,
    max_per_user INT DEFAULT 1,
    valid_from TIMESTAMP NOT NULL,
    valid_to TIMESTAMP NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE voucher_usages (
    id BIGSERIAL PRIMARY KEY,
    voucher_id BIGINT NOT NULL REFERENCES vouchers(id),
    customer_id BIGINT NOT NULL REFERENCES users(id),
    order_id BIGINT REFERENCES orders(id),
    discount_amount DECIMAL(12,2) NOT NULL,
    used_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 3.12 Clinical (V12) - FULL

```sql
-- V12__clinical.sql
CREATE TABLE medical_records (
    id BIGSERIAL PRIMARY KEY,
    pet_id BIGINT NOT NULL REFERENCES pets(id),
    appointment_id BIGINT REFERENCES appointments(id),
    veterinarian_id BIGINT NOT NULL REFERENCES users(id),
    store_id BIGINT NOT NULL REFERENCES stores(id),
    examination_date TIMESTAMP NOT NULL,
    chief_complaint TEXT,  -- Lý do khám
    symptoms TEXT[],  -- Triệu chứng
    examination_results JSONB,  -- {temperature, heartRate, weight, notes}
    diagnosis TEXT,
    treatment_plan TEXT,
    status VARCHAR(20) DEFAULT 'ACTIVE',  -- ACTIVE, FOLLOW_UP, CLOSED
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE diagnoses (
    id BIGSERIAL PRIMARY KEY,
    medical_record_id BIGINT NOT NULL REFERENCES medical_records(id),
    diagnosis_code VARCHAR(20),
    diagnosis_name VARCHAR(200) NOT NULL,
    severity VARCHAR(20),  -- MILD, MODERATE, SEVERE
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE prescriptions (
    id BIGSERIAL PRIMARY KEY,
    medical_record_id BIGINT NOT NULL REFERENCES medical_records(id),
    veterinarian_id BIGINT NOT NULL REFERENCES users(id),
    prescription_date TIMESTAMP NOT NULL,
    instructions TEXT,  -- Hướng dẫn sử dụng
    notes TEXT,
    status VARCHAR(20) DEFAULT 'ACTIVE',  -- ACTIVE, COMPLETED, CANCELLED
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE prescription_items (
    id BIGSERIAL PRIMARY KEY,
    prescription_id BIGINT NOT NULL REFERENCES prescriptions(id) ON DELETE CASCADE,
    medication_name VARCHAR(200) NOT NULL,
    dosage VARCHAR(100),
    frequency VARCHAR(100),
    duration VARCHAR(100),
    quantity VARCHAR(100),
    instructions TEXT,
    price DECIMAL(12,2),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE follow_ups (
    id BIGSERIAL PRIMARY KEY,
    medical_record_id BIGINT NOT NULL REFERENCES medical_records(id),
    pet_id BIGINT NOT NULL REFERENCES pets(id),
    scheduled_date TIMESTAMP NOT NULL,
    purpose VARCHAR(200),
    notes TEXT,
    status VARCHAR(20) DEFAULT 'SCHEDULED',  -- SCHEDULED, COMPLETED, CANCELLED
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_medical_records_pet ON medical_records(pet_id);
CREATE INDEX idx_medical_records_vet ON medical_records(veterinarian_id);
CREATE INDEX idx_prescriptions_medical_record ON prescriptions(medical_record_id);
```

### 3.13 Vaccinations (V13)

```sql
-- V13__vaccinations.sql
CREATE TABLE vaccines (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    manufacturer VARCHAR(200),
    valid_months INT,  -- Thời hạn vaccine (VD: 12 tháng)
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE vaccine_batches (
    id BIGSERIAL PRIMARY KEY,
    vaccine_id BIGINT NOT NULL REFERENCES vaccines(id),
    store_id BIGINT NOT NULL REFERENCES stores(id),
    batch_number VARCHAR(100) NOT NULL,
    expiry_date DATE NOT NULL,
    quantity INT NOT NULL,
    price DECIMAL(12,2),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(vaccine_id, batch_number)
);

CREATE TABLE vaccinations (
    id BIGSERIAL PRIMARY KEY,
    pet_id BIGINT NOT NULL REFERENCES pets(id),
    vaccine_id BIGINT NOT NULL REFERENCES vaccines(id),
    batch_id BIGINT REFERENCES vaccine_batches(id),
    store_id BIGINT NOT NULL REFERENCES stores(id),
    veterinarian_id BIGINT NOT NULL REFERENCES users(id),
    administered_at TIMESTAMP NOT NULL,
    next_due_date DATE,
    site VARCHAR(100),  -- Vị trí tiêm
    batch_number VARCHAR(100),
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE vaccination_schedules (
    id BIGSERIAL PRIMARY KEY,
    pet_id BIGINT NOT NULL REFERENCES pets(id),
    vaccine_id BIGINT NOT NULL REFERENCES vaccines(id),
    due_date DATE NOT NULL,
    reminder_sent BOOLEAN DEFAULT FALSE,
    status VARCHAR(20) DEFAULT 'PENDING',  -- PENDING, COMPLETED, OVERDUE
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_vaccinations_pet ON vaccinations(pet_id);
CREATE INDEX idx_vaccinations_due ON vaccination_schedules(pet_id, due_date) WHERE status = 'PENDING';
```

### 3.14 Notifications (V14)

```sql
-- V14__notifications.sql
CREATE TABLE notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    type VARCHAR(50) NOT NULL,  -- APPOINTMENT_REMINDER, PAYMENT_SUCCESS, etc.
    title VARCHAR(200) NOT NULL,
    message TEXT NOT NULL,
    reference_type VARCHAR(50),  -- ORDER, APPOINTMENT, PAYMENT
    reference_id BIGINT,
    is_read BOOLEAN DEFAULT FALSE,
    read_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notifications_user ON notifications(user_id);
CREATE INDEX idx_notifications_unread ON notifications(user_id, is_read) WHERE is_read = FALSE;
```

### 3.15 Walk-ins + Queue (V15)

```sql
-- V15__walkins.sql
CREATE TABLE walkins (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL REFERENCES stores(id),
    pet_id BIGINT NOT NULL REFERENCES pets(id),
    customer_id BIGINT NOT NULL REFERENCES users(id),
    service_id BIGINT REFERENCES services(id),
    queue_number INT NOT NULL,
    position INT NOT NULL,
    status VARCHAR(20) DEFAULT 'WAITING',  -- WAITING, CALLED, SERVED, CANCELLED
    called_at TIMESTAMP,
    served_at TIMESTAMP,
    estimated_wait_minutes INT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_walkins_store ON walkins(store_id, status);
CREATE INDEX idx_walkins_queue ON walkins(store_id, queue_number);
```

### 3.16 Grooming (V16) - FSM

```sql
-- V16__grooming.sql
CREATE TABLE grooming_sessions (
    id BIGSERIAL PRIMARY KEY,
    appointment_id BIGINT REFERENCES appointments(id),
    pet_id BIGINT NOT NULL REFERENCES pets(id),
    store_id BIGINT NOT NULL REFERENCES stores(id),
    groomer_id BIGINT NOT NULL REFERENCES users(id),
    status grooming_status NOT NULL DEFAULT 'WAITING',
    scheduled_at TIMESTAMP,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE grooming_services (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES grooming_sessions(id),
    service_type VARCHAR(50) NOT NULL,  -- HAIRCUT, BATH, NAIL_TRIM, etc.
    price DECIMAL(12,2) NOT NULL,
    is_additional BOOLEAN DEFAULT FALSE,  -- TRUE = phát sinh
    customer_confirmed BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_grooming_store ON grooming_sessions(store_id, status);
CREATE INDEX idx_grooming_groomer ON grooming_sessions(groomer_id);
```

### 3.17 Workforce (V17)

```sql
-- V17__workforce.sql
CREATE TABLE work_schedules (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    store_id BIGINT NOT NULL REFERENCES stores(id),
    day_of_week SMALLINT NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, day_of_week)
);

CREATE TABLE staff_absences (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    store_id BIGINT NOT NULL REFERENCES stores(id),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    reason VARCHAR(200),
    status VARCHAR(20) DEFAULT 'PENDING',  -- PENDING, APPROVED, REJECTED
    approved_by BIGINT REFERENCES users(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 3.18 Reports (V18)

```sql
-- V18__reports.sql
-- Reports là read-only aggregates, không cần bảng riêng
-- Tính toán từ orders, payments, appointments
```

### 3.19 Audit Logs (V19)

```sql
-- V19__audit_logs.sql
CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id),
    action VARCHAR(100) NOT NULL,  -- CREATE, UPDATE, DELETE
    entity_type VARCHAR(100) NOT NULL,  -- ORDER, PAYMENT, USER
    entity_id BIGINT,
    changes JSONB,  -- {before: {}, after: {}}
    ip_address VARCHAR(50),
    user_agent TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_logs_entity ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_logs_user ON audit_logs(user_id);
CREATE INDEX idx_audit_logs_created ON audit_logs(created_at);
```

---

## 4. Seed Data (cho Demo)

```sql
-- SUPER_ADMIN account
INSERT INTO accounts (phone, email, password_hash, status, role) VALUES
('0900000000', 'admin@petcare.vn', '$2a$10$...', 'ACTIVE', 'SUPER_ADMIN');

-- Demo Organization
INSERT INTO organizations (name, code) VALUES
('Pet Care Vietnam', 'PCV');

-- Demo Stores
INSERT INTO stores (organization_id, name, code, address, phone, status) VALUES
(1, 'Pet Care Quận 1', 'PCV-D1', '123 Nguyễn Trãi, Q1, HCM', '02812345678', 'ACTIVE'),
(1, 'Pet Care Quận 7', 'PCV-D7', '456 Nguyễn Văn Linh, Q7, HCM', '02812345679', 'ACTIVE');

-- Demo Services
INSERT INTO services (name, category, duration_minutes, price) VALUES
('Khám tổng quát', 'CHECKUP', 30, 200000),
('Tiêm phòng dại', 'VACCINATION', 15, 150000),
('Tiêm phòng 5 bệnh', 'VACCINATION', 15, 350000),
('Cắt tỉa lông', 'GROOMING', 60, 250000),
('Tắm spa', 'GROOMING', 45, 180000),
('Nail trim', 'GROOMING', 15, 50000);

-- Demo Vaccines
INSERT INTO vaccines (name, valid_months) VALUES
('Rabies (Dại)', 12),
('5 bệnh (Distemper)', 12),
('6 bệnh (6 in 1)', 12),
('Vaccine cúm mèo', 12);
```

---

## 5. Implementation Checklist

### Phase 1: Foundation (W1)

| Task | Owner | Done |
|------|-------|------|
| Maven setup + pom.xml | P1 | ☐ |
| Docker Compose | P1 | ☐ |
| SecurityConfig + JWT | P1 | ☐ |
| Auth module | P1 | ☐ |
| Users module | P1 | ☐ |
| Organizations module | P1 | ☐ |
| Stores module | P1 | ☐ |
| Pets module | P2 | ☐ |
| Caregivers FSM | P2 | ☐ |
| Products module | P3 | ☐ |
| Inventory module | P3 | ☐ |

### Phase 2: Core Domain (W2)

| Task | Owner | Done |
|------|-------|------|
| Appointments FSM | P1 | ☐ |
| Orders FSM | P2 | ☐ |
| Payments FSM | P3 | ☐ |

### Phase 3: Commerce (W3-W4)

| Task | Owner | Done |
|------|-------|------|
| Invoices FSM | P1 | ☐ |
| Refunds FSM | P1 | ☐ |
| Clinical (FULL) | P2 | ☐ |
| Promotions + Vouchers | P2 | ☐ |
| Vaccinations | P3 | ☐ |
| Event bridges | All | ☐ |

### Phase 4: Polish (W5-W6)

| Task | Owner | Done |
|------|-------|------|
| FE Integration | All | ☐ |
| Notifications | P1 | ☐ |
| Walk-ins | P2 | ☐ |
| Grooming FSM | P3 | ☐ |
| Workforce | P1 | ☐ |
| Reports | P2 | ☐ |
| Audit Logs | P1 | ☐ |
| Docker | P3 | ☐ |
| Tests | All | ☐ |