package com.petcare.platform.enums;

/** docs/05-domain-model.md §4.14 · docs/06-erd.md orders.channel (VARCHAR thường, không Postgres native enum) */
public enum OrderChannel {
    POS_RETAIL,
    ONLINE_APP
}
