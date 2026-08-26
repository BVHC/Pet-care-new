-- ===========================================
-- Pet-care Database Schema
-- Initial Migration V1
-- ===========================================

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ===========================================
-- ENUMS (theo docs/02-database-and-implementation.md)
-- ===========================================

-- Account Status (Updated: có PENDING_VERIFICATION)
CREATE TYPE account_status AS ENUM ('PENDING_VERIFICATION', 'ACTIVE', 'LOCKED', 'SUSPENDED', 'DELETED');

-- 8 System Roles
CREATE TYPE user_role AS ENUM ('SUPER_ADMIN', 'ORG_ADMIN', 'STORE_MANAGER', 'FINANCE_STAFF', 'INVENTORY_STAFF', 'RECEPTIONIST', 'VETERINARIAN', 'GROOMER', 'CUSTOMER');

-- Store Status
CREATE TYPE store_status AS ENUM ('ACTIVE', 'SUSPENDED', 'DEACTIVATED', 'ARCHIVED');

-- Appointment Status (FSM)
CREATE TYPE appointment_status AS ENUM ('BOOKED', 'CONFIRMED', 'CHECKED_IN', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'NO_SHOW');

-- Order Status (FSM) - theo docs: có PAID
CREATE TYPE order_status AS ENUM ('PENDING_PAYMENT', 'PAID', 'CONFIRMED', 'PROCESSING', 'READY', 'DELIVERED', 'CANCELLED', 'REFUNDED');

-- Payment Status (FSM) - theo docs: có PROCESSING
CREATE TYPE payment_status AS ENUM ('PENDING', 'PROCESSING', 'SUCCESS', 'FAILED', 'CANCELLED', 'PARTIALLY_REFUNDED', 'REFUNDED');

-- Invoice Status (FSM)
CREATE TYPE invoice_status AS ENUM ('DRAFT', 'ISSUED', 'PARTIALLY_PAID', 'PAID', 'VOID', 'REFUNDED');

-- Refund Status (FSM)
CREATE TYPE refund_status AS ENUM ('REQUESTED', 'APPROVED', 'REJECTED', 'PROCESSING', 'COMPLETED', 'FAILED');

-- Grooming Status (FSM)
CREATE TYPE grooming_status AS ENUM ('WAITING', 'IN_PROGRESS', 'AWAITING_CUSTOMER_APPROVAL', 'COMPLETED', 'CANCELLED');

-- Queue Entry Status (C-565e7b1)
CREATE TYPE queue_entry_status AS ENUM ('WAITING', 'CALLED', 'IN_SERVICE', 'COMPLETED', 'CANCELLED', 'NO_SHOW');

-- Consent Status (C-565e7b1)
CREATE TYPE consent_status AS ENUM ('ACTIVE', 'REVOKED', 'EXPIRED');

-- Cross-Store Consent Status (C-565e7b1)
CREATE TYPE cross_store_consent_status AS ENUM ('PENDING', 'APPROVED', 'EXPIRED', 'REVOKED');

-- ===========================================
-- ACCOUNTS
-- ===========================================

CREATE TABLE accounts (
    id BIGSERIAL PRIMARY KEY,
    phone VARCHAR(20) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    status account_status NOT NULL DEFAULT 'PENDING_VERIFICATION',
    role user_role NOT NULL DEFAULT 'CUSTOMER',
    refresh_token VARCHAR(500),
    refresh_token_expiry TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_accounts_phone ON accounts(phone);
CREATE INDEX idx_accounts_email ON accounts(email);
CREATE INDEX idx_accounts_status ON accounts(status);

-- ===========================================
-- OTPS
-- ===========================================

CREATE TABLE otps (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    code VARCHAR(6) NOT NULL,
    type VARCHAR(20) NOT NULL,  -- REGISTRATION, PASSWORD_RESET
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE,
    attempts INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_otps_account_id ON otps(account_id);
CREATE INDEX idx_otps_code ON otps(code);
CREATE INDEX idx_otps_expires_at ON otps(expires_at);

-- ===========================================
-- ORGANIZATIONS
-- ===========================================

CREATE TABLE organizations (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    code VARCHAR(50) UNIQUE NOT NULL,
    description TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_organizations_code ON organizations(code);

-- ===========================================
-- STORES
-- ===========================================

CREATE TABLE stores (
    id BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    name VARCHAR(255) NOT NULL,
    code VARCHAR(50) UNIQUE NOT NULL,
    address TEXT,
    phone VARCHAR(20),
    status store_status NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_stores_organization_id ON stores(organization_id);
CREATE INDEX idx_stores_code ON stores(code);
CREATE INDEX idx_stores_status ON stores(status);

-- ===========================================
-- OPERATING_HOURS
-- ===========================================

CREATE TABLE operating_hours (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    day_of_week SMALLINT NOT NULL CHECK (day_of_week >= 0 AND day_of_week <= 6),
    open_time TIME NOT NULL,
    close_time TIME NOT NULL,
    is_closed BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE(store_id, day_of_week)
);

CREATE INDEX idx_operating_hours_store_id ON operating_hours(store_id);

-- ===========================================
-- STORE_SERVICES
-- ===========================================

CREATE TABLE store_services (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    service_id BIGINT NOT NULL,
    price DECIMAL(12,2),
    is_available BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_store_services_store_id ON store_services(store_id);

-- ===========================================
-- USERS
-- ===========================================

CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    avatar VARCHAR(500),
    organization_id BIGINT REFERENCES organizations(id) ON DELETE SET NULL,
    store_id BIGINT REFERENCES stores(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_account_id ON users(account_id);
CREATE INDEX idx_users_store_id ON users(store_id);
CREATE INDEX idx_users_organization_id ON users(organization_id);

-- ===========================================
-- PETS
-- ===========================================

CREATE TABLE pets (
    id BIGSERIAL PRIMARY KEY,
    owner_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    name VARCHAR(100) NOT NULL,
    species VARCHAR(50) NOT NULL,
    breed VARCHAR(100),
    birth_date DATE,
    weight DECIMAL(6,2),
    image_url VARCHAR(500),
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pets_owner_id ON pets(owner_id);
CREATE INDEX idx_pets_species ON pets(species);

-- ===========================================
-- SERVICES (Service catalog)
-- ===========================================

CREATE TABLE services (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    category VARCHAR(100),
    duration_minutes INT NOT NULL DEFAULT 30,
    price DECIMAL(12,2) NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_services_category ON services(category);
CREATE INDEX idx_services_is_active ON services(is_active);

-- ===========================================
-- PRODUCTS
-- ===========================================

CREATE TABLE products (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    category VARCHAR(100),
    price DECIMAL(12,2) NOT NULL DEFAULT 0,
    discount_percent INT NOT NULL DEFAULT 0 CHECK (discount_percent >= 0 AND discount_percent <= 100),
    image_url VARCHAR(500),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_products_category ON products(category);
CREATE INDEX idx_products_is_active ON products(is_active);

-- ===========================================
-- INVENTORY
-- ===========================================

CREATE TABLE inventory (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    quantity INT NOT NULL DEFAULT 0,
    min_threshold INT DEFAULT 5,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE(store_id, product_id)
);

CREATE INDEX idx_inventory_store_id ON inventory(store_id);
CREATE INDEX idx_inventory_product_id ON inventory(product_id);
CREATE INDEX idx_inventory_low_stock ON inventory(quantity, min_threshold);

-- ===========================================
-- CARTS
-- ===========================================

CREATE TABLE carts (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    store_id BIGINT NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_carts_customer_id ON carts(customer_id);
CREATE INDEX idx_carts_store_id ON carts(store_id);

-- ===========================================
-- CART_ITEMS
-- ===========================================

CREATE TABLE cart_items (
    id BIGSERIAL PRIMARY KEY,
    cart_id BIGINT NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    quantity INT NOT NULL DEFAULT 1 CHECK (quantity > 0),
    unit_price DECIMAL(12,2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cart_items_cart_id ON cart_items(cart_id);
CREATE INDEX idx_cart_items_product_id ON cart_items(product_id);

-- ===========================================
-- ORDERS
-- ===========================================

CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    order_number VARCHAR(50) UNIQUE NOT NULL,
    customer_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    store_id BIGINT NOT NULL REFERENCES stores(id) ON DELETE RESTRICT,
    status order_status NOT NULL DEFAULT 'PENDING_PAYMENT',
    subtotal DECIMAL(12,2) NOT NULL DEFAULT 0,
    discount DECIMAL(12,2) NOT NULL DEFAULT 0,
    total DECIMAL(12,2) NOT NULL DEFAULT 0,
    voucher_code VARCHAR(50),
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_orders_order_number ON orders(order_number);
CREATE INDEX idx_orders_customer_id ON orders(customer_id);
CREATE INDEX idx_orders_store_id ON orders(store_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_created_at ON orders(created_at);

-- ===========================================
-- ORDER_ITEMS
-- ===========================================

CREATE TABLE order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id BIGINT NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    quantity INT NOT NULL CHECK (quantity > 0),
    unit_price DECIMAL(12,2) NOT NULL,
    subtotal DECIMAL(12,2) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_order_items_order_id ON order_items(order_id);
CREATE INDEX idx_order_items_product_id ON order_items(product_id);

-- ===========================================
-- PAYMENTS
-- ===========================================

CREATE TABLE payments (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    amount DECIMAL(12,2) NOT NULL,
    method VARCHAR(50) NOT NULL,
    status payment_status NOT NULL DEFAULT 'PENDING',
    transaction_id VARCHAR(100),
    gateway_response JSONB,
    paid_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payments_order_id ON payments(order_id);
CREATE INDEX idx_payments_status ON payments(status);
CREATE INDEX idx_payments_transaction_id ON payments(transaction_id);

-- ===========================================
-- REFUNDS
-- ===========================================

CREATE TABLE refunds (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT NOT NULL REFERENCES payments(id) ON DELETE RESTRICT,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    amount DECIMAL(12,2) NOT NULL,
    reason TEXT,
    status refund_status NOT NULL DEFAULT 'REQUESTED',
    approved_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    approved_at TIMESTAMP WITH TIME ZONE,
    rejection_reason TEXT,
    processed_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refunds_payment_id ON refunds(payment_id);
CREATE INDEX idx_refunds_order_id ON refunds(order_id);
CREATE INDEX idx_refunds_status ON refunds(status);

-- ===========================================
-- APPOINTMENTS
-- ===========================================

CREATE TABLE appointments (
    id BIGSERIAL PRIMARY KEY,
    pet_id BIGINT NOT NULL REFERENCES pets(id) ON DELETE RESTRICT,
    store_id BIGINT NOT NULL REFERENCES stores(id) ON DELETE RESTRICT,
    service_id BIGINT NOT NULL REFERENCES services(id) ON DELETE RESTRICT,
    staff_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    customer_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status appointment_status NOT NULL DEFAULT 'BOOKED',
    notes TEXT,
    checked_in_at TIMESTAMP WITH TIME ZONE,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    cancellation_reason TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_appointments_pet_id ON appointments(pet_id);
CREATE INDEX idx_appointments_store_id ON appointments(store_id);
CREATE INDEX idx_appointments_service_id ON appointments(service_id);
CREATE INDEX idx_appointments_customer_id ON appointments(customer_id);
CREATE INDEX idx_appointments_staff_id ON appointments(staff_id);
CREATE INDEX idx_appointments_status ON appointments(status);
CREATE INDEX idx_appointments_scheduled_at ON appointments(scheduled_at);

-- ===========================================
-- INVOICES
-- ===========================================

CREATE TABLE invoices (
    id BIGSERIAL PRIMARY KEY,
    invoice_number VARCHAR(50) UNIQUE NOT NULL,
    order_id BIGINT REFERENCES orders(id) ON DELETE SET NULL,
    appointment_id BIGINT REFERENCES appointments(id) ON DELETE SET NULL,
    customer_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    store_id BIGINT NOT NULL REFERENCES stores(id) ON DELETE RESTRICT,
    status invoice_status NOT NULL DEFAULT 'DRAFT',
    subtotal DECIMAL(12,2) NOT NULL DEFAULT 0,
    tax DECIMAL(12,2) NOT NULL DEFAULT 0,
    discount DECIMAL(12,2) NOT NULL DEFAULT 0,
    total DECIMAL(12,2) NOT NULL DEFAULT 0,
    paid_amount DECIMAL(12,2) NOT NULL DEFAULT 0,
    issued_at TIMESTAMP WITH TIME ZONE,
    voided_at TIMESTAMP WITH TIME ZONE,
    void_reason TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_invoices_invoice_number ON invoices(invoice_number);
CREATE INDEX idx_invoices_order_id ON invoices(order_id);
CREATE INDEX idx_invoices_customer_id ON invoices(customer_id);
CREATE INDEX idx_invoices_store_id ON invoices(store_id);
CREATE INDEX idx_invoices_status ON invoices(status);

-- ===========================================
-- INVOICE_ITEMS
-- ===========================================

CREATE TABLE invoice_items (
    id BIGSERIAL PRIMARY KEY,
    invoice_id BIGINT NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    type VARCHAR(20) NOT NULL,
    reference_id BIGINT,
    name VARCHAR(255) NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    unit_price DECIMAL(12,2) NOT NULL,
    subtotal DECIMAL(12,2) NOT NULL
);

CREATE INDEX idx_invoice_items_invoice_id ON invoice_items(invoice_id);

-- ===========================================
-- MEDICAL_RECORDS
-- ===========================================

CREATE TABLE medical_records (
    id BIGSERIAL PRIMARY KEY,
    pet_id BIGINT NOT NULL REFERENCES pets(id) ON DELETE RESTRICT,
    appointment_id BIGINT REFERENCES appointments(id) ON DELETE SET NULL,
    veterinarian_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    store_id BIGINT NOT NULL REFERENCES stores(id) ON DELETE RESTRICT,
    examination_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    chief_complaint TEXT,
    symptoms TEXT[],
    examination_results JSONB,
    diagnosis TEXT,
    treatment_plan TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_medical_records_pet_id ON medical_records(pet_id);
CREATE INDEX idx_medical_records_veterinarian_id ON medical_records(veterinarian_id);
CREATE INDEX idx_medical_records_store_id ON medical_records(store_id);

-- ===========================================
-- DIAGNOSES
-- ===========================================

CREATE TABLE diagnoses (
    id BIGSERIAL PRIMARY KEY,
    medical_record_id BIGINT NOT NULL REFERENCES medical_records(id) ON DELETE CASCADE,
    diagnosis_code VARCHAR(50),
    diagnosis_name VARCHAR(255) NOT NULL,
    severity VARCHAR(20),
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_diagnoses_medical_record_id ON diagnoses(medical_record_id);

-- ===========================================
-- PRESCRIPTIONS
-- ===========================================

CREATE TABLE prescriptions (
    id BIGSERIAL PRIMARY KEY,
    medical_record_id BIGINT NOT NULL REFERENCES medical_records(id) ON DELETE CASCADE,
    veterinarian_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    prescription_date TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    instructions TEXT,
    notes TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_prescriptions_medical_record_id ON prescriptions(medical_record_id);
CREATE INDEX idx_prescriptions_veterinarian_id ON prescriptions(veterinarian_id);

-- ===========================================
-- PRESCRIPTION_ITEMS
-- ===========================================

CREATE TABLE prescription_items (
    id BIGSERIAL PRIMARY KEY,
    prescription_id BIGINT NOT NULL REFERENCES prescriptions(id) ON DELETE CASCADE,
    medication_name VARCHAR(255) NOT NULL,
    dosage VARCHAR(100),
    frequency VARCHAR(100),
    duration VARCHAR(100),
    quantity VARCHAR(100),
    instructions TEXT,
    price DECIMAL(12,2),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_prescription_items_prescription_id ON prescription_items(prescription_id);

-- ===========================================
-- FOLLOW_UPS
-- ===========================================

CREATE TABLE follow_ups (
    id BIGSERIAL PRIMARY KEY,
    medical_record_id BIGINT NOT NULL REFERENCES medical_records(id) ON DELETE CASCADE,
    pet_id BIGINT NOT NULL REFERENCES pets(id) ON DELETE CASCADE,
    scheduled_date TIMESTAMP WITH TIME ZONE NOT NULL,
    purpose VARCHAR(255),
    notes TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_follow_ups_medical_record_id ON follow_ups(medical_record_id);
CREATE INDEX idx_follow_ups_pet_id ON follow_ups(pet_id);

-- ===========================================
-- VACCINES
-- ===========================================

CREATE TABLE vaccines (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    manufacturer VARCHAR(255),
    valid_months INT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_vaccines_is_active ON vaccines(is_active);

-- ===========================================
-- VACCINE_BATCHES
-- ===========================================

CREATE TABLE vaccine_batches (
    id BIGSERIAL PRIMARY KEY,
    vaccine_id BIGINT NOT NULL REFERENCES vaccines(id) ON DELETE RESTRICT,
    store_id BIGINT NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    batch_number VARCHAR(100) NOT NULL,
    barcode VARCHAR(100),
    expiry_date DATE NOT NULL,
    quantity INT NOT NULL,
    available_quantity INT NOT NULL,
    price DECIMAL(12,2),
    status VARCHAR(20) DEFAULT 'ACTIVE',
    manufacturer VARCHAR(200),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE(vaccine_id, batch_number)
);

CREATE INDEX idx_vaccine_batches_vaccine_id ON vaccine_batches(vaccine_id);
CREATE INDEX idx_vaccine_batches_store_id ON vaccine_batches(store_id);
CREATE INDEX idx_vaccine_batches_barcode ON vaccine_batches(barcode);
CREATE INDEX idx_vaccine_batches_expiry_date ON vaccine_batches(expiry_date);

-- ===========================================
-- VACCINATIONS
-- ===========================================

CREATE TABLE vaccinations (
    id BIGSERIAL PRIMARY KEY,
    pet_id BIGINT NOT NULL REFERENCES pets(id) ON DELETE RESTRICT,
    vaccine_id BIGINT NOT NULL REFERENCES vaccines(id) ON DELETE RESTRICT,
    batch_id BIGINT REFERENCES vaccine_batches(id) ON DELETE SET NULL,
    store_id BIGINT NOT NULL REFERENCES stores(id) ON DELETE RESTRICT,
    veterinarian_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    administered_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    next_due_date DATE,
    site VARCHAR(100),
    batch_number VARCHAR(100),
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_vaccinations_pet_id ON vaccinations(pet_id);
CREATE INDEX idx_vaccinations_vaccine_id ON vaccinations(vaccine_id);
CREATE INDEX idx_vaccinations_store_id ON vaccinations(store_id);

-- ===========================================
-- VACCINATION_SCHEDULES
-- ===========================================

CREATE TABLE vaccination_schedules (
    id BIGSERIAL PRIMARY KEY,
    pet_id BIGINT NOT NULL REFERENCES pets(id) ON DELETE CASCADE,
    vaccine_id BIGINT NOT NULL REFERENCES vaccines(id) ON DELETE CASCADE,
    due_date DATE NOT NULL,
    reminder_sent BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_vaccination_schedules_pet_id ON vaccination_schedules(pet_id);
CREATE INDEX idx_vaccination_schedules_due_date ON vaccination_schedules(due_date);

-- ===========================================
-- VOUCHERS
-- ===========================================

CREATE TABLE vouchers (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(50) UNIQUE NOT NULL,
    description TEXT,
    discount_type VARCHAR(20) NOT NULL,
    discount_value DECIMAL(12,2) NOT NULL,
    min_order_value DECIMAL(12,2) NOT NULL DEFAULT 0,
    max_usage INT,
    current_usage INT NOT NULL DEFAULT 0,
    max_per_user INT NOT NULL DEFAULT 1,
    valid_from TIMESTAMP WITH TIME ZONE NOT NULL,
    valid_to TIMESTAMP WITH TIME ZONE NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_vouchers_code ON vouchers(code);
CREATE INDEX idx_vouchers_valid_from ON vouchers(valid_from);
CREATE INDEX idx_vouchers_valid_to ON vouchers(valid_to);

-- ===========================================
-- VOUCHER_USAGES
-- ===========================================

CREATE TABLE voucher_usages (
    id BIGSERIAL PRIMARY KEY,
    voucher_id BIGINT NOT NULL REFERENCES vouchers(id) ON DELETE RESTRICT,
    customer_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    discount_amount DECIMAL(12,2) NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_voucher_usages_voucher_id ON voucher_usages(voucher_id);
CREATE INDEX idx_voucher_usages_customer_id ON voucher_usages(customer_id);
CREATE INDEX idx_voucher_usages_order_id ON voucher_usages(order_id);

-- ===========================================
-- NOTIFICATIONS
-- ===========================================

CREATE TABLE notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(50) NOT NULL,
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    reference_type VARCHAR(50),
    reference_id BIGINT,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    read_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notifications_user_id ON notifications(user_id);
CREATE INDEX idx_notifications_is_read ON notifications(is_read);
CREATE INDEX idx_notifications_created_at ON notifications(created_at);

-- ===========================================
-- QUEUES
-- ===========================================

CREATE TABLE queues (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    date DATE NOT NULL,
    current_position INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE(store_id, date)
);

CREATE INDEX idx_queues_store_id ON queues(store_id);
CREATE INDEX idx_queues_date ON queues(date);

-- ===========================================
-- QUEUE_ENTRIES
-- ===========================================

CREATE TABLE queue_entries (
    id BIGSERIAL PRIMARY KEY,
    queue_id BIGINT NOT NULL REFERENCES queues(id) ON DELETE CASCADE,
    walkin_id BIGINT,
    position INT NOT NULL,
    status queue_entry_status NOT NULL DEFAULT 'WAITING',
    called_at TIMESTAMP WITH TIME ZONE,
    service_started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_queue_entries_queue_id ON queue_entries(queue_id);
CREATE INDEX idx_queue_entries_walkin_id ON queue_entries(walkin_id);
CREATE INDEX idx_queue_entries_status ON queue_entries(status);

-- ===========================================
-- WALKINS
-- ===========================================

CREATE TABLE walkins (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL REFERENCES stores(id) ON DELETE RESTRICT,
    pet_id BIGINT NOT NULL REFERENCES pets(id) ON DELETE RESTRICT,
    customer_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    service_id BIGINT REFERENCES services(id) ON DELETE SET NULL,
    queue_number INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    called_at TIMESTAMP WITH TIME ZONE,
    served_at TIMESTAMP WITH TIME ZONE,
    estimated_wait_minutes INT,
    appointment_id BIGINT REFERENCES appointments(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_walkins_store_id ON walkins(store_id);
CREATE INDEX idx_walkins_customer_id ON walkins(customer_id);
CREATE INDEX idx_walkins_status ON walkins(status);

-- ===========================================
-- GROOMING_SESSIONS
-- ===========================================

CREATE TABLE grooming_sessions (
    id BIGSERIAL PRIMARY KEY,
    appointment_id BIGINT REFERENCES appointments(id) ON DELETE SET NULL,
    pet_id BIGINT NOT NULL REFERENCES pets(id) ON DELETE RESTRICT,
    store_id BIGINT NOT NULL REFERENCES stores(id) ON DELETE RESTRICT,
    groomer_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    status grooming_status NOT NULL DEFAULT 'WAITING',
    scheduled_at TIMESTAMP WITH TIME ZONE,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_grooming_sessions_appointment_id ON grooming_sessions(appointment_id);
CREATE INDEX idx_grooming_sessions_pet_id ON grooming_sessions(pet_id);
CREATE INDEX idx_grooming_sessions_store_id ON grooming_sessions(store_id);
CREATE INDEX idx_grooming_sessions_groomer_id ON grooming_sessions(groomer_id);
CREATE INDEX idx_grooming_sessions_status ON grooming_sessions(status);

-- ===========================================
-- GROOMING_SERVICES
-- ===========================================

CREATE TABLE grooming_services (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES grooming_sessions(id) ON DELETE CASCADE,
    service_type VARCHAR(100) NOT NULL,
    price DECIMAL(12,2) NOT NULL,
    is_additional BOOLEAN NOT NULL DEFAULT FALSE,
    customer_confirmed BOOLEAN,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_grooming_services_session_id ON grooming_services(session_id);

-- ===========================================
-- WORK_SCHEDULES
-- ===========================================

CREATE TABLE work_schedules (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    store_id BIGINT NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    day_of_week SMALLINT NOT NULL CHECK (day_of_week >= 0 AND day_of_week <= 6),
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, day_of_week)
);

CREATE INDEX idx_work_schedules_user_id ON work_schedules(user_id);
CREATE INDEX idx_work_schedules_store_id ON work_schedules(store_id);

-- ===========================================
-- STAFF_ABSENCES
-- ===========================================

CREATE TABLE staff_absences (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    store_id BIGINT NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    reason VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    approved_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_staff_absences_user_id ON staff_absences(user_id);
CREATE INDEX idx_staff_absences_store_id ON staff_absences(store_id);
CREATE INDEX idx_staff_absences_status ON staff_absences(status);

-- ===========================================
-- AUDIT_LOGS
-- ===========================================

CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(50) NOT NULL,
    entity_type VARCHAR(100) NOT NULL,
    entity_id BIGINT,
    changes JSONB,
    ip_address VARCHAR(45),
    user_agent TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_logs_user_id ON audit_logs(user_id);
CREATE INDEX idx_audit_logs_entity ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs(created_at);

-- ===========================================
-- STORE_RESOURCES
-- ===========================================

CREATE TABLE store_resources (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    resource_type VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    capacity INT NOT NULL DEFAULT 1,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_store_resources_store_id ON store_resources(store_id);

-- ===========================================
-- SERVICE_REQUIRED_RESOURCES
-- ===========================================

CREATE TABLE service_required_resources (
    id BIGSERIAL PRIMARY KEY,
    service_id BIGINT NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    resource_type VARCHAR(50) NOT NULL,
    quantity_required INT NOT NULL DEFAULT 1
);

CREATE INDEX idx_service_required_resources_service_id ON service_required_resources(service_id);

-- ===========================================
-- OUTBOX (Event Sourcing)
-- ===========================================

CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_outbox_events_status ON outbox_events(status);
CREATE INDEX idx_outbox_events_aggregate ON outbox_events(aggregate_type, aggregate_id);

-- ===========================================
-- CROSS_STORE_CONSENTS
-- ===========================================

CREATE TABLE cross_store_consents (
    id BIGSERIAL PRIMARY KEY,
    pet_id BIGINT NOT NULL REFERENCES pets(id) ON DELETE CASCADE,
    requesting_store_id BIGINT NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    requesting_veterinarian_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason TEXT,
    otp_code VARCHAR(6),
    otp_expires_at TIMESTAMP WITH TIME ZONE,
    status cross_store_consent_status NOT NULL DEFAULT 'PENDING',
    access_granted_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cross_store_consents_pet_id ON cross_store_consents(pet_id);
CREATE INDEX idx_cross_store_consents_status ON cross_store_consents(status);

-- ===========================================
-- INVENTORY_RESERVATIONS
-- ===========================================

CREATE TABLE inventory_reservations (
    id BIGSERIAL PRIMARY KEY,
    inventory_id BIGINT NOT NULL REFERENCES inventory(id) ON DELETE CASCADE,
    order_id BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    quantity INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'RESERVED',
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_inventory_reservations_inventory_id ON inventory_reservations(inventory_id);
CREATE INDEX idx_inventory_reservations_order_id ON inventory_reservations(order_id);
CREATE INDEX idx_inventory_reservations_status ON inventory_reservations(status);
CREATE INDEX idx_inventory_reservations_expires_at ON inventory_reservations(expires_at);

-- ===========================================
-- POSTGRES FUNCTIONS
-- ===========================================

-- Function to update updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

-- ===========================================
-- TRIGGERS for updated_at
-- ===========================================

CREATE TRIGGER update_accounts_updated_at BEFORE UPDATE ON accounts
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_organizations_updated_at BEFORE UPDATE ON organizations
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_stores_updated_at BEFORE UPDATE ON stores
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_pets_updated_at BEFORE UPDATE ON pets
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_products_updated_at BEFORE UPDATE ON products
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_inventory_updated_at BEFORE UPDATE ON inventory
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_carts_updated_at BEFORE UPDATE ON carts
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_orders_updated_at BEFORE UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_payments_updated_at BEFORE UPDATE ON payments
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_refunds_updated_at BEFORE UPDATE ON refunds
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_appointments_updated_at BEFORE UPDATE ON appointments
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_invoices_updated_at BEFORE UPDATE ON invoices
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_medical_records_updated_at BEFORE UPDATE ON medical_records
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_grooming_sessions_updated_at BEFORE UPDATE ON grooming_sessions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ===========================================
-- SEED DATA (theo docs/02-database-and-implementation.md)
-- ===========================================

-- SUPER_ADMIN account (password: Admin@123 - BCrypt hash)
INSERT INTO accounts (phone, email, password_hash, status, role) VALUES
('0900000000', 'admin@petcare.vn', '$2a$10$N9qo8uLOickgx2ZMRZoMye.IjqQBrkHx7NQyLPg3iC1z.aHR.P5Oa', 'ACTIVE', 'SUPER_ADMIN');

-- Demo Organization
INSERT INTO organizations (name, code, description) VALUES
('Pet Care Vietnam', 'PCV', 'Chuỗi cửa hàng thú cưng');

-- Demo Stores
INSERT INTO stores (organization_id, name, code, address, phone, status) VALUES
(1, 'Pet Care Quận 1', 'PCV-D1', '123 Nguyễn Trãi, Q1, HCM', '02812345678', 'ACTIVE'),
(1, 'Pet Care Quận 7', 'PCV-D7', '456 Nguyễn Văn Linh, Q7, HCM', '02812345679', 'ACTIVE');

-- Demo Users (tạo sau khi có account)
INSERT INTO users (account_id, name, store_id) VALUES
(1, 'System Admin', NULL);

-- Demo Services (theo docs)
INSERT INTO services (name, description, category, duration_minutes, price, is_active) VALUES
('Khám tổng quát', 'Khám sức khỏe tổng quát cho thú cưng', 'CHECKUP', 30, 200000, TRUE),
('Tiêm phòng dại', 'Tiêm vaccine phòng bệnh dại', 'VACCINATION', 15, 150000, TRUE),
('Tiêm phòng 5 bệnh', 'Tiêm vaccine 5 bệnh cho chó', 'VACCINATION', 15, 350000, TRUE),
('Tiêm phòng 6 bệnh', 'Tiêm vaccine 6 bệnh cho mèo', 'VACCINATION', 15, 380000, TRUE),
('Cắt tỉa lông', 'Cắt tỉa lông, tạo kiểu', 'GROOMING', 60, 250000, TRUE),
('Tắm spa', 'Tắm, dưỡng lông cho thú cưng', 'GROOMING', 45, 180000, TRUE),
('Nail trim', 'Cắt và mài móng', 'GROOMING', 15, 50000, TRUE),
('Siêu âm', 'Siêu âm chẩn đoán', 'DIAGNOSTIC', 30, 400000, TRUE),
('Xét nghiệm máu', 'Xét nghiệm máu cơ bản', 'DIAGNOSTIC', 20, 300000, TRUE),
('Phẫu thuật nhỏ', 'Phẫu thuật các ca nhỏ', 'SURGERY', 60, 800000, TRUE);

-- Demo Vaccines
INSERT INTO vaccines (name, description, manufacturer, valid_months, is_active) VALUES
('Rabies (Dại)', 'Vaccine phòng bệnh dại', 'Zoetis', 12, TRUE),
('5 bệnh (Distemper)', 'Vaccine 5 bệnh cho chó', 'Zoetis', 12, TRUE),
('6 bệnh (6 in 1)', 'Vaccine 6 bệnh cho mèo', 'Merial', 12, TRUE),
('Vaccine cúm mèo', 'Phòng cúm mèo', 'Nobivac', 12, TRUE),
('Vaccine Leptospira', 'Phòng Leptospira cho chó', 'Zoetis', 12, TRUE);

-- Demo Vaccine Batches (batch_number phải unique theo vaccine_id)
INSERT INTO vaccine_batches (vaccine_id, store_id, batch_number, barcode, expiry_date, quantity, available_quantity, price, status) VALUES
(1, 1, 'RAB-2024-001', 'RAB2024001', '2027-08-01', 100, 100, 150000, 'ACTIVE'),
(1, 2, 'RAB-2024-002', 'RAB2024002', '2027-08-01', 50, 50, 150000, 'ACTIVE'),
(2, 1, '5B-2024-001', '5B2024001', '2027-08-01', 80, 80, 350000, 'ACTIVE'),
(3, 1, '6B-2024-001', '6B2024001', '2027-08-01', 60, 60, 380000, 'ACTIVE');

-- Demo Products
INSERT INTO products (name, description, category, price, discount_percent, is_active) VALUES
('Thức ăn Royal Canin cho mèo', 'Thức ăn hạt cho mèo trưởng thành', 'FOOD', 450000, 10, TRUE),
('Thức ăn Pedigree cho chó', 'Thức ăn hạt cho chó', 'FOOD', 380000, 5, TRUE),
('Sữa tắm cho chó', 'Sữa tắm dịu nhẹ', 'GROOMING', 120000, 0, TRUE),
('Sữa tắm cho mèo', 'Sữa tắm chuyên dụng cho mèo', 'GROOMING', 110000, 0, TRUE),
('Vitamin tổng hợp', 'Vitamin cho thú cưng', 'HEALTH', 250000, 15, TRUE),
('Thuốc xổ giun', 'Thuốc xổ giun cho chó/mèo', 'HEALTH', 80000, 0, TRUE),
('Vòng cổ cho chó', 'Vòng cổ da', 'ACCESSORY', 150000, 20, TRUE),
('Đồ chơi cho mèo', 'Bóng nhỏ', 'ACCESSORY', 50000, 0, TRUE);

-- Demo Inventory (Store 1)
INSERT INTO inventory (store_id, product_id, quantity, min_threshold) VALUES
(1, 1, 50, 10),
(1, 2, 40, 10),
(1, 3, 30, 5),
(1, 4, 25, 5),
(1, 5, 20, 5),
(1, 6, 100, 20),
(1, 7, 15, 3),
(1, 8, 50, 10);

-- Demo Inventory (Store 2)
INSERT INTO inventory (store_id, product_id, quantity, min_threshold) VALUES
(2, 1, 30, 10),
(2, 2, 25, 10),
(2, 3, 20, 5),
(2, 4, 15, 5);
