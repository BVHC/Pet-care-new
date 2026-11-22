package com.petcare.module.inpatient.entity;

/**
 * {@code admission_orders.order_type} (BR-LT-15). Chỉ {@link #MEDICATION} bắt buộc liên kết một dòng đơn thuốc và chỉ
 * VET thực hiện; bốn loại còn lại VET hoặc CARETAKER thực hiện.
 */
public enum AdmissionOrderType {
    MEDICATION, FEEDING, MONITORING, PROCEDURE, OTHER
}
