package com.petcare.module.workforce.entity;

/**
 * {@code shift_requests.status} (erd §14.1, 03 #11): {@code PENDING} là INIT; {@code APPROVED}, {@code REJECTED},
 * {@code WITHDRAWN} là FINAL.
 */
public enum ShiftRequestStatus {
    PENDING, APPROVED, REJECTED, WITHDRAWN
}
