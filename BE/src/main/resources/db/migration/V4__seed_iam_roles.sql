-- Module 02 (IAM) — seed 9 role chuẩn (RULE-02-02, docs/06-erd.md `user_role_enum`)
-- làm global role (organization_id = NULL) phục vụ GET /api/roles (catalog đọc-only).
-- Scope theo đúng bảng đã dùng ở AuthServiceImpl#deriveScope /
-- RoleScopeGuard#validateRoleScopeBinding. Không đụng bảng permissions — chưa
-- module nào định nghĩa permission code thật (xem plan mục A).

INSERT INTO roles (organization_id, code, name, scope, description) VALUES
    (NULL, 'SUPER_ADMIN', 'Super Admin', 'PLATFORM', 'Quản trị toàn hệ thống'),
    (NULL, 'ORGANIZATION_ADMIN', 'Organization Admin', 'ORGANIZATION', 'Quản lý chuỗi cửa hàng'),
    (NULL, 'STORE_MANAGER', 'Store Manager', 'STORE', 'Quản lý vận hành store'),
    (NULL, 'RECEPTIONIST', 'Receptionist', 'STORE', 'Tiếp khách, check-in, tạo đơn'),
    (NULL, 'VETERINARIAN', 'Veterinarian', 'STORE', 'Khám bệnh, kê đơn, tiêm phòng'),
    (NULL, 'GROOMER', 'Groomer', 'STORE', 'Làm đẹp thú cưng'),
    (NULL, 'INVENTORY_STAFF', 'Inventory Staff', 'STORE', 'Quản lý kho, chuyển kho'),
    (NULL, 'FINANCE_STAFF', 'Finance Staff', 'STORE', 'Thu ngân, thanh toán, hoàn tiền'),
    (NULL, 'CUSTOMER', 'Customer', 'CUSTOMER', 'Khách hàng');
