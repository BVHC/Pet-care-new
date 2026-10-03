package com.petcare.module.content.api;

/** Owner: content (DG) · BE-2. */
public interface FeedbackQueryApi {

    /** BR-TK-19: hồ sơ online đã có feedback thì không liên kết được. */
    boolean existsByCustomer(Long customerId);
}
