package com.petcare.module.catalog.api;

/**
 * Loại dịch vụ trong nhóm MEDICAL (BR-SP-06). {@code INPATIENT} (V41, docs/adr/0124, 0148): dịch vụ nội trú, giá theo ngày,
 * chỉ dùng qua nhập viện (BR-LT-18): không đặt lịch, không chọn khi tiếp nhận, không hiện công khai, không thêm dòng tay.
 */
public enum MedicalType { EXAM, VACCINE, INPATIENT }
