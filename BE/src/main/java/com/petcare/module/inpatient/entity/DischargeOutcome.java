package com.petcare.module.inpatient.entity;

/**
 * {@code admissions.discharge_outcome} (BR-LT-14): {@code HOME} về nhà hoặc trở lại lưu trú, {@code DECEASED} thú mất.
 * Xuất viện {@code DECEASED} không tự đánh dấu thú mất hay kết thúc lưu trú.
 */
public enum DischargeOutcome {
    HOME, DECEASED
}
