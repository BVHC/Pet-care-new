package com.petcare.module.content.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.petcare.module.content.api.FeedbackQueryApi;

/**
 * {@link FeedbackQueryApi} cho BR-TK-19 (hồ sơ online đã có feedback thì không liên kết được). BE-2 thêm 2026-10-16 (T17)
 * vì {@code customer} cần bean này khi liên kết hồ sơ; module content (T33…) mở rộng lớp này, không tạo bean thứ hai.
 * Đọc thẳng {@code feedbacks} trong transaction của bên gọi.
 */
@Service
public class FeedbackQueryService implements FeedbackQueryApi {

    private final JdbcTemplate jdbc;

    public FeedbackQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean existsByCustomer(Long customerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM feedbacks WHERE customer_id = ?)", Boolean.class, customerId));
    }
}
