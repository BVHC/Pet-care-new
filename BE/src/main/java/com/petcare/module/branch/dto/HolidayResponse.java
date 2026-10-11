package com.petcare.module.branch.dto;

import java.time.LocalDate;

/** branch-v1 {@code Holiday}. */
public record HolidayResponse(Long holidayId, LocalDate holidayDate, String reason) {
}
