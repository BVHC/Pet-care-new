package com.petcare.platform.enums;

/** docs/04-glossary.md §26.1 · docs/06-erd.md §4 security_scope_enum — 5-tier Multi-Tenancy */
public enum SecurityScope {
    PLATFORM,
    ORGANIZATION,
    STORE,
    WAREHOUSE,
    CUSTOMER
}
