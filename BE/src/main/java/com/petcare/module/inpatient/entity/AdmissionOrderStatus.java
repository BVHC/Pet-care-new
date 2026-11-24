package com.petcare.module.inpatient.entity;

/** {@code admission_orders.status} (09 §C FSM #25): {@code ORDERED} là INIT; {@code DONE}, {@code CANCELLED} là FINAL. */
public enum AdmissionOrderStatus {
    ORDERED, DONE, CANCELLED
}
