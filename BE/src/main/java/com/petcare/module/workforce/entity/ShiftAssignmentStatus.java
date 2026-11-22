package com.petcare.module.workforce.entity;

/**
 * {@code shift_assignments.status} (erd §14.1, 03 #12): {@code SCHEDULED} là INIT; {@code ON_LEAVE}, {@code ABSENT},
 * {@code CANCELLED} là FINAL.
 */
public enum ShiftAssignmentStatus {
    SCHEDULED, ON_LEAVE, ABSENT, CANCELLED
}
