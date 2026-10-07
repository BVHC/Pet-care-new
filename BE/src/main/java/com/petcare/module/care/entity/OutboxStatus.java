package com.petcare.module.care.entity;

/** {@code notification_outbox.status} (erd §11). Worker ST20 chuyển {@code PENDING} → {@code SENT} / {@code FAILED}. */
public enum OutboxStatus { PENDING, SENT, FAILED }
