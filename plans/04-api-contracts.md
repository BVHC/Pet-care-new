# Pet-care-new: REST API Contracts

> **Date:** 2026-08-18  
> **Scope:** MVP - 11 modules P0+P1, 8 roles  
> **Base URL:** `/api`

---

## 1. Roles (Final — 8 Roles)

### Nhóm 1: Platform (1 role)

| Role | Mô tả | Ai |
|------|-------|-----|
| SUPER_ADMIN | Quản trị toàn hệ thống, setup Organization/Stores đầu tiên | Dev / PO |

### Nhóm 2: Organization (1 role)

| Role | Mô tả | Ai |
|------|-------|-----|
| ORG_ADMIN | Quản lý chuỗi cửa hàng, setup organization | Chủ chuỗi |

### Nhóm 3: Store-Level (6 roles)

| Role | Mô tả | Ai |
|------|-------|-----|
| STORE_MANAGER | Quản lý store, duyệt refunds, reports | Chủ store |
| FINANCE_STAFF | Thu ngân, thanh toán, hoàn tiền, đối soát | Kế toán |
| INVENTORY_STAFF | Quản lý kho, chuyển kho, mua hàng | Thủ kho |
| RECEPTIONIST | Tiếp khách, check-in, tạo đơn, thu tiền mặt | Lễ tân |
| VETERINARIAN | Khám bệnh, kê đơn, tiêm phòng, tạo bệnh án | Bác sĩ |
| GROOMER | Làm đẹp thú cưng, thêm dịch vụ phát sinh | Stylist |

### Nhóm 4: Customer (1 role)

| Role | Mô tả | Ai |
|------|-------|-----|
| CUSTOMER | Đặt lịch, mua hàng, thanh toán, quản lý pets | Khách hàng |

---

## 2. Authentication & OTP

### 2.1 Register Account

```
POST /api/auth/register
Content-Type: application/json

Request:
{
  "phone": "0912345678",
  "email": "user@example.com",     // optional
  "password": "password123",
  "name": "Nguyen Van A"
}

Response (201):
{
  "data": {
    "accountId": 1,
    "message": "OTP sent to phone"
  },
  "message": "Registration initiated",
  "code": 201
}
```

### 2.2 Verify OTP

```
POST /api/auth/verify-otp
Content-Type: application/json

Request:
{
  "phone": "0912345678",
  "otp": "123456"
}

Response (200):
{
  "data": {
    "userId": 1,
    "accountId": 1,
    "role": "CUSTOMER",
    "name": "Nguyen Van A"
  },
  "message": "Account activated",
  "code": 200
}
```

### 2.3 Login

```
POST /api/auth/login
Content-Type: application/json

Request:
{
  "phone": "0912345678",
  "password": "password123"
}

Response (200):
{
  "data": {
    "accessToken": "eyJhbG...",
    "refreshToken": "eyJhbG...",
    "tokenType": "Bearer",
    "expiresIn": 3600,
    "user": {
      "userId": 1,
      "name": "Nguyen Van A",
      "role": "CUSTOMER"
    }
  },
  "message": "Login successful",
  "code": 200
}
```

### 2.4 Refresh Token

```
POST /api/auth/refresh-token
Content-Type: application/json

Request:
{
  "refreshToken": "eyJhbG..."
}

Response (200):
{
  "data": {
    "accessToken": "eyJhbG...",
    "refreshToken": "eyJhbG...",
    "tokenType": "Bearer",
    "expiresIn": 3600
  },
  "message": "Token refreshed",
  "code": 200
}
```

### 2.5 Logout

```
POST /api/auth/logout
Authorization: Bearer <token>

Response (200):
{
  "data": null,
  "message": "Logged out successfully",
  "code": 200
}
```

---

## 3. Users & Organizations

### 3.1 Get Current User

```
GET /api/users/me
Authorization: Bearer <token>

Response (200):
{
  "data": {
    "userId": 1,
    "accountId": 1,
    "name": "Nguyen Van A",
    "phone": "0912345678",
    "email": "user@example.com",
    "role": "CUSTOMER",
    "storeId": null
  },
  "message": "success",
  "code": 200
}
```

### 3.2 Create User (Admin)

```
POST /api/users
Authorization: Bearer <token>
Roles: SUPER_ADMIN

Request:
{
  "phone": "0912345679",
  "password": "password123",
  "name": "Staff User",
  "role": "RECEPTIONIST",
  "storeId": 1
}

Response (201):
{
  "data": { "userId": 5, ... },
  "message": "User created",
  "code": 201
}
```

### 3.3 Lock/Unlock User

```
POST /api/users/{userId}/lock
POST /api/users/{userId}/unlock
Authorization: Bearer <token>
Roles: SUPER_ADMIN, STORE_MANAGER

Response (200):
{
  "data": { "status": "LOCKED" },
  "message": "Account locked",
  "code": 200
}
```

### 3.4 Organizations

```
POST /api/organizations
Authorization: Bearer <token>
Roles: SUPER_ADMIN

Request:
{
  "name": "Pet Care Vietnam",
  "code": "PCV"
}

Response (201):
{
  "data": { "id": 1, "name": "Pet Care Vietnam" },
  "message": "Organization created",
  "code": 201
}
```

### 3.5 Stores

```
POST /api/organizations/{orgId}/stores
Authorization: Bearer <token>
Roles: SUPER_ADMIN

Request:
{
  "name": "Pet Care District 1",
  "code": "PCV-D1",
  "address": "123 Nguyen Trai, Q1, HCMC",
  "phone": "02812345678"
}

Response (201):
{
  "data": { "id": 1, "organizationId": 1, "name": "Pet Care District 1" },
  "message": "Store created",
  "code": 201
}
```

---

## 4. Pets

### 4.1 Create Pet

```
POST /api/pets
Authorization: Bearer <token>
Roles: CUSTOMER, RECEPTIONIST

Request:
{
  "name": "Meo",
  "species": "CAT",
  "breed": "Persian",
  "birthDate": "2024-01-15",
  "weight": 4.5,
  "imageUrl": "https://..."
}

Response (201):
{
  "data": {
    "id": 1,
    "ownerId": 1,
    "name": "Meo",
    "species": "CAT"
  },
  "message": "Pet added",
  "code": 201
}
```

### 4.2 Get My Pets

```
GET /api/pets
Authorization: Bearer <token>
Roles: CUSTOMER

Response (200):
{
  "data": [
    { "id": 1, "name": "Meo", "species": "CAT", "breed": "Persian" }
  ],
  "message": "success",
  "code": 200
}
```



---

## 5. Products & Inventory

### 5.1 List Products

```
GET /api/products?categoryId=1&search=shampoo&page=0&size=20
Authorization: Bearer <token>

Response (200):
{
  "data": {
    "content": [
      {
        "id": 1,
        "name": "Pet Shampoo",
        "price": 150000,
        "finalPrice": 135000,
        "discountPercent": 10,
        "stockQuantity": 50,
        "imageUrl": "..."
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 150
  },
  "message": "success",
  "code": 200
}
```

### 5.2 Get Inventory

```
GET /api/inventory?storeId=1&lowStock=true
Authorization: Bearer <token>
Roles: STORE_MANAGER, RECEPTIONIST

Response (200):
{
  "data": {
    "content": [
      {
        "productId": 1,
        "productName": "Pet Shampoo",
        "quantity": 5,
        "threshold": 10,
        "status": "LOW_STOCK"
      }
    ]
  },
  "message": "success",
  "code": 200
}
```

---

## 6. Appointments (FSM)

### 6.1 Book Appointment

```
POST /api/appointments
Authorization: Bearer <token>
Roles: CUSTOMER, RECEPTIONIST

Request:
{
  "petId": 1,
  "storeId": 1,
  "serviceId": 1,
  "scheduledAt": "2026-08-20T09:00:00",
  "notes": "First visit"
}

Response (201):
{
  "data": {
    "id": 1,
    "status": "BOOKED",
    "scheduledAt": "2026-08-20T09:00:00"
  },
  "message": "Appointment booked",
  "code": 201
}
```

### 6.2 List Appointments

```
GET /api/appointments?status=BOOKED&storeId=1&page=0&size=20
Authorization: Bearer <token>
Roles: STORE_MANAGER, RECEPTIONIST, VETERINARIAN, GROOMER, CUSTOMER

Response (200):
{
  "data": {
    "content": [
      {
        "id": 1,
        "pet": { "id": 1, "name": "Meo" },
        "service": { "id": 1, "name": "General Checkup" },
        "store": { "id": 1, "name": "Pet Care District 1" },
        "scheduledAt": "2026-08-20T09:00:00",
        "status": "BOOKED"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 45
  },
  "message": "success",
  "code": 200
}
```

### 6.3 Appointment Transitions (Updated per C-565e7b1)

```
POST /api/appointments/{id}/confirm      → CONFIRMED
POST /api/appointments/{id}/check-in     → CHECKED_IN
POST /api/appointments/{id}/start        → IN_PROGRESS
POST /api/appointments/{id}/complete     → COMPLETED
POST /api/appointments/{id}/cancel       → CANCELLED
POST /api/appointments/{id}/no-show       → NO_SHOW
POST /api/appointments/{id}/reschedule    → BOOKED (NEW)

Authorization: Bearer <token>
Roles: RECEPTIONIST (most), CUSTOMER (cancel, reschedule)

Response (200):
{
  "data": { "id": 1, "status": "COMPLETED" },
  "message": "Appointment completed",
  "code": 200
}
```

### 6.4 Reschedule Appointment (NEW - C-565e7b1)

```
POST /api/appointments/{id}/reschedule
Authorization: Bearer <token>
Roles: CUSTOMER, RECEPTIONIST

Request:
{
  "newScheduledAt": "2026-08-22T10:00:00"
}

Response (200):
{
  "data": {
    "id": 1,
    "status": "BOOKED",
    "scheduledAt": "2026-08-22T10:00:00"
  },
  "message": "Appointment rescheduled",
  "code": 200
}
```

Note: Reschedule về trạng thái BOOKED để tái kiểm tra StoreResource availability.

---

## 7. Orders & Cart

### 7.1 Cart

```
GET /api/cart
Authorization: Bearer <token>
Roles: CUSTOMER

Response (200):
{
  "data": {
    "items": [
      { "productId": 1, "name": "Pet Shampoo", "quantity": 2, "price": 150000, "subtotal": 300000 }
    ],
    "subtotal": 300000,
    "discount": 30000,
    "total": 270000
  }
}
```

```
POST /api/cart/items
Authorization: Bearer <token>
Roles: CUSTOMER

Request:
{
  "productId": 1,
  "quantity": 2
}
```

### 7.2 Checkout

```
POST /api/orders/checkout
Authorization: Bearer <token>
Roles: CUSTOMER, RECEPTIONIST

Request:
{
  "storeId": 1,
  "paymentMethod": "ONLINE",
  "voucherCode": "SUMMER10",
  "notes": "Please gift wrap"
}

Response (201):
{
  "data": {
    "orderId": 1,
    "orderNumber": "ORD-20260818-001",
    "status": "PENDING_PAYMENT",
    "subtotal": 300000,
    "discount": 80000,
    "total": 215000,
    "paymentUrl": "https://payment-gateway.example.com/pay/xxx"
  },
  "message": "Order created",
  "code": 201
}
```

### 7.3 Order Transitions (Updated per C-565e7b1)

```
POST /api/orders/{id}/confirm          → CONFIRMED
POST /api/orders/{id}/process          → PROCESSING
POST /api/orders/{id}/prepare          → READY
POST /api/orders/{id}/deliver          → DELIVERED
POST /api/orders/{id}/cancel           → CANCELLED (PENDING_PAYMENT/CONFIRMED only)
POST /api/orders/{id}/cancel-with-refund → CANCELLED (PROCESSING/READY - NEW)
POST /api/orders/{id}/reschedule       → BOOKED (NEW - if Order has appointment)

Authorization: Bearer <token>
Roles: RECEPTIONIST (most), CUSTOMER (cancel only)
```

Note: Inventory Reservation có TTL 15 phút. Quá hạn → hệ thống tự động cancel + giải phóng tồn kho.

---

## 8. Payments

### 8.1 Make Payment

```
POST /api/payments
Authorization: Bearer <token>
Roles: CUSTOMER

Request:
{
  "orderId": 1,
  "amount": 215000,
  "method": "ONLINE"
}

Response (201):
{
  "data": {
    "paymentId": 1,
    "status": "PENDING",
    "paymentUrl": "https://..."
  },
  "message": "Payment created",
  "code": 201
}
```

### 8.2 Cash Payment (Receptionist)

```
POST /api/payments/cash
Authorization: Bearer <token>
Roles: RECEPTIONIST

Request:
{
  "orderId": 1,
  "amount": 215000
}

Response (201):
{
  "data": { "paymentId": 1, "status": "SUCCESS" },
  "message": "Cash payment recorded",
  "code": 201
}
```

### 8.3 Payment Callback (Webhook)

```
POST /api/payments/callback
Content-Type: application/json

Request:
{
  "transactionId": "TXN123456",
  "orderId": "ORD-20260818-001",
  "status": "SUCCESS",
  "amount": 215000,
  "signature": "abc123..."
}

Response (200):
{
  "data": { "orderId": 1, "status": "PAID" },
  "message": "Payment processed",
  "code": 200
}
```

---

## 9. Invoices (P1)

### 9.1 Get Invoice

```
GET /api/invoices/{id}
Authorization: Bearer <token>

Response (200):
{
  "data": {
    "id": 1,
    "invoiceNumber": "INV-20260818-001",
    "status": "ISSUED",
    "items": [
      { "type": "SERVICE", "name": "General Checkup", "quantity": 1, "price": 200000 }
    ],
    "subtotal": 200000,
    "total": 200000,
    "paidAmount": 0
  }
}
```

### 9.2 Issue Invoice

```
POST /api/invoices/{id}/issue
Authorization: Bearer <token>
Roles: STORE_MANAGER, RECEPTIONIST

Response (200):
{
  "data": { "id": 1, "status": "ISSUED" },
  "message": "Invoice issued",
  "code": 200
}
```

### 9.3 Void Invoice

```
POST /api/invoices/{id}/void
Authorization: Bearer <token>
Roles: STORE_MANAGER

Response (200):
{
  "data": { "id": 1, "status": "VOID" },
  "message": "Invoice voided",
  "code": 200
}
```

---

## 10. Refunds (P1)

### 10.1 Request Refund

```
POST /api/refunds
Authorization: Bearer <token>
Roles: CUSTOMER, RECEPTIONIST

Request:
{
  "paymentId": 1,
  "amount": 100000,
  "reason": "Product damaged"
}

Response (201):
{
  "data": { "id": 1, "status": "REQUESTED" },
  "message": "Refund requested",
  "code": 201
}
```

### 10.2 Approve/Reject Refund

```
POST /api/refunds/{id}/approve
POST /api/refunds/{id}/reject
Authorization: Bearer <token>
Roles: STORE_MANAGER

Response (200):
{
  "data": { "id": 1, "status": "APPROVED" },
  "message": "Refund approved",
  "code": 200
}
```

### 10.3 Process Refund

```
POST /api/refunds/{id}/process
POST /api/refunds/{id}/complete
Authorization: Bearer <token>
Roles: STORE_MANAGER

Response (200):
{
  "data": { "id": 1, "status": "COMPLETED" },
  "message": "Refund completed",
  "code": 200
}
```

---

## 11. Vouchers (P1)

### 11.1 Validate Voucher

```
POST /api/vouchers/validate
Authorization: Bearer <token>
Roles: CUSTOMER, RECEPTIONIST

Request:
{
  "code": "SUMMER10",
  "orderTotal": 300000
}

Response (200):
{
  "data": {
    "code": "SUMMER10",
    "discountValue": 30000,
    "discountType": "PERCENT",
    "isValid": true
  }
}
```

---

## 12. Notifications (P1)

### 12.1 List Notifications

```
GET /api/notifications?unreadOnly=true&page=0&size=20
Authorization: Bearer <token>

Response (200):
{
  "data": {
    "content": [
      {
        "id": 1,
        "type": "APPOINTMENT_REMINDER",
        "title": "Upcoming Appointment",
        "message": "Your appointment for Meo is tomorrow at 9:00 AM",
        "isRead": false,
        "createdAt": "..."
      }
    ],
    "totalUnread": 5
  }
}
```

### 12.2 Mark as Read

```
PATCH /api/notifications/{id}/read
Authorization: Bearer <token>

Response (200):
{
  "data": { "id": 1, "isRead": true },
  "message": "Marked as read",
  "code": 200
}
```

---

## 13. Error Responses

### Standard Error

```json
{
  "data": null,
  "message": "Appointment not found",
  "code": 404
}
```

### Validation Error

```json
{
  "data": {
    "phone": "Invalid phone format",
    "password": "Password must be at least 8 characters"
  },
  "message": "Validation failed",
  "code": 400
}
```

### Business Rule Violation

```json
{
  "data": null,
  "message": "Cannot cancel appointment - too close to scheduled time (RULE-06-08)",
  "code": 400
}
```

### Unauthorized

```json
{
  "data": null,
  "message": "Access denied",
  "code": 403
}
```

### Token Expired

```json
{
  "data": null,
  "message": "Token expired",
  "code": 401
}
```

---

## 13. Clinical + Cross-Store Medical Records (NEW - C-565e7b1)

### 13.1 Examine Pet

```
POST /api/clinical/examinations
Authorization: Bearer <token>
Roles: VETERINARIAN

Request:
{
  "petId": 1,
  "appointmentId": 1,
  "chiefComplaint": "Khó thở nhẹ",
  "symptoms": ["ho", "khó thở"],
  "examinationResults": {
    "temperature": 38.5,
    "heartRate": 120,
    "weight": 4.2
  },
  "diagnosis": "Viêm đường hô hấp nhẹ",
  "treatmentPlan": "Thuốc kháng sinh 5 ngày"
}

Response (201):
{
  "data": { "medicalRecordId": 1, "status": "ACTIVE" },
  "message": "Medical record created",
  "code": 201
}
```

### 13.2 Cross-Store Medical Record Consent (NEW)

```
# Bác sĩ gửi yêu cầu truy cập bệnh án Store khác
POST /api/clinical/cross-store-consent/request
Authorization: Bearer <token>
Roles: VETERINARIAN

Request:
{
  "petId": 1,
  "targetStoreId": 2,
  "reason": "Pet có tiền sử dị ứng"
}

Response (200):
{
  "data": { "consentRequestId": 1, "status": "PENDING" },
  "message": "OTP sent to pet owner",
  "code": 200
}

# Customer xác thực OTP
POST /api/clinical/cross-store-consent/verify
Authorization: Bearer <token>
Roles: CUSTOMER

Request:
{
  "consentRequestId": 1,
  "otp": "123456"
}

Response (200):
{
  "data": { "accessGranted": true, "expiresAt": "..." },
  "message": "Cross-store access granted for 24 hours",
  "code": 200
}

# Emergency Override (không cần OTP)
POST /api/clinical/cross-store-consent/emergency
Authorization: Bearer <token>
Roles: VETERINARIAN

Request:
{
  "petId": 1,
  "reason": "Cấp cứu nguy kịch - cần biết tiền sử dị ứng"
}

Response (200):
{
  "data": { "accessGranted": true },
  "message": "Emergency access granted - audit log recorded",
  "code": 200
}
```

---

## 14. Vaccinations with Barcode Scanning (NEW - C-565e7b1)

### 14.1 Administer Vaccine with Barcode

```
POST /api/vaccinations/administer
Authorization: Bearer <token>
Roles: VETERINARIAN

Request:
{
  "petId": 1,
  "vaccineId": 1,
  "scannedBarcode": "VAC-2024-001",  // Bắt buộc
  "site": "right_shoulder",
  "nextDueDate": "2027-08-25",
  "notes": "Pet khỏe"
}

Response (201):
{
  "data": {
    "vaccinationId": 1,
    "batchNumber": "VAC-2024-001",
    "expiryDate": "2027-08-01",
    "manufacturer": "Zoetis"
  },
  "message": "Vaccination recorded",
  "code": 201
}
```

### 14.2 Validate Barcode

```
POST /api/vaccinations/validate-barcode
Authorization: Bearer <token>
Roles: VETERINARIAN

Request:
{
  "barcode": "VAC-2024-001",
  "storeId": 1
}

Response (200):
{
  "data": {
    "valid": true,
    "batchId": 1,
    "vaccineName": "Rabies",
    "expiryDate": "2027-08-01",
    "availableQuantity": 50
  },
  "code": 200
}
```

---

## 15. Walk-in to Appointment Bridge (NEW - C-565e7b1)

### 15.1 Check-in Walk-in

```
POST /api/walkins/{id}/check-in
Authorization: Bearer <token>
Roles: RECEPTIONIST

Response (200):
{
  "data": {
    "walkinId": 1,
    "appointmentId": 100,  // Auto-created
    "status": "IN_PROGRESS",
    "channel": "WALK_IN"
  },
  "message": "Walk-in checked in - internal appointment created",
  "code": 200
}
```

---

## 16. Skip (P2 - Không có endpoints)

| Module | Lý do |
|--------|-------|
| Clinical | Quá phức tạp |
| Vaccinations | Phụ thuộc Clinical |
| Grooming FSM | Tests nhiều |
| Membership + Packages | Loyalty |
| Walk-ins | Queue |
| Workforce | Schedule |
| Procurement | Purchase |
| Incidents | Reports |
| Reports + Audit | Analytics |
| Consent/Privacy | GDPR |