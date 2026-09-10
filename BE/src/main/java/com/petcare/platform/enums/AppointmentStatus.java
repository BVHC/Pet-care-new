package com.petcare.platform.enums;

/** docs/03-state-machines.md FSM 4.2 · docs/06-erd.md §4 appointment_status_enum */
public enum AppointmentStatus {
    BOOKED,
    CONFIRMED,
    CHECKED_IN,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    NO_SHOW,
    ABORTED
}
