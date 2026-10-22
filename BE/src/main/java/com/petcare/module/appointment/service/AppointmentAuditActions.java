package com.petcare.module.appointment.service;

/** Mã action audit của module appointment (convention 08 §8.3). */
final class AppointmentAuditActions {

    /**
     * Gỡ hạn chế đặt online sớm (BR-LH-09, docs/adr/0055): {@code entity = booking_restrictions}, before/after =
     * {@code {liftedAt, liftedBy}}, {@code reason} = lý do gỡ.
     */
    static final String BOOKING_RESTRICTION_LIFTED = "BOOKING_RESTRICTION_LIFTED";

    private AppointmentAuditActions() {
    }
}
