package com.petcare.module.content.controller;

/** Biểu thức {@code @PreAuthorize} của content-v1 (mục B). Endpoint public không có biểu thức. */
final class ContentAccess {

    static final String SUPER_MANAGER = "hasRole('SUPER_MANAGER')";
    static final String CUSTOMER = "hasRole('CUSTOMER')";
    static final String FEEDBACK_REVIEWER = "hasAnyRole('SUPER_MANAGER','BRANCH_MANAGER')";

    private ContentAccess() {
    }
}
