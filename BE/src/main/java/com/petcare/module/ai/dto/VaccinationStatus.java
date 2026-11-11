package com.petcare.module.ai.dto;

/** ai-v1 {@code VaccinationStatus} (BR-AI-11): trạng thái một loại vaccine của một thú. */
public enum VaccinationStatus {
    NOT_YET_ELIGIBLE,
    NOT_STARTED,
    UP_TO_DATE,
    DUE_SOON,
    OVERDUE
}
