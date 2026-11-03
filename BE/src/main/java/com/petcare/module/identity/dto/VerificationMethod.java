package com.petcare.module.identity.dto;

/**
 * Cách lễ tân xác minh khách tại quầy (identity-v1 giả định A6, BR-TK-16): chỉ có đối chiếu CCCD trực tiếp với họ tên
 * hồ sơ; hệ thống không lưu số hay ảnh CCCD, audit chỉ ghi tên phương thức. Dùng cho sửa hộ email (#25) và liên kết
 * hồ sơ tại quầy (#27).
 */
public enum VerificationMethod {
    ID_CARD_IN_PERSON
}
