package com.petcare.platform.enums;

/** docs/03-state-machines.md FSM 14 · docs/06-erd.md §4 incident_status_enum */
public enum IncidentStatus {
    RECORDED,
    CLASSIFIED,
    UNDER_INVESTIGATION,
    ESCALATED,
    RESOLVED,
    CLOSED
}
