-- V1__core_enums.sql
-- Core enum types for FSM states

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
    'ACTIVE',
    'SUSPENDED',
    'DEACTIVATED',
    'ARCHIVED'
);

-- Appointment Status (FSM)
CREATE TYPE appointment_status AS ENUM (
    'BOOKED',
    'CONFIRMED',
    'CHECKED_IN',
    'IN_PROGRESS',
    'COMPLETED',
    'CANCELLED',
    'NO_SHOW'
);

-- Order Status (FSM)
CREATE TYPE order_status AS ENUM (
    'PENDING_PAYMENT',
    'PAID',
    'CONFIRMED',
    'PROCESSING',
    'READY',
    'DELIVERED',
    'CANCELLED',
    'REFUNDED'
);

-- Payment Status (FSM)
CREATE TYPE payment_status AS ENUM (
    'PENDING',
    'PROCESSING',
    'SUCCESS',
    'FAILED',
    'CANCELLED',
    'REFUNDED'
);

-- Invoice Status (FSM)
CREATE TYPE invoice_status AS ENUM (
    'DRAFT',
    'ISSUED',
    'PARTIALLY_PAID',
    'PAID',
    'VOID',
    'REFUNDED'
);

-- Refund Status (FSM)
CREATE TYPE refund_status AS ENUM (
    'REQUESTED',
    'APPROVED',
    'REJECTED',
    'PROCESSING',
    'COMPLETED',
    'FAILED'
);

-- Caregiver Status
CREATE TYPE caregiver_status AS ENUM (
    'INVITED',
    'ACTIVE',
    'REJECTED',
    'EXPIRED',
    'REVOKED'
);

-- Grooming Status (FSM)
CREATE TYPE grooming_status AS ENUM (
    'WAITING',
    'IN_PROGRESS',
    'COMPLETED',
    'CANCELLED'
);
