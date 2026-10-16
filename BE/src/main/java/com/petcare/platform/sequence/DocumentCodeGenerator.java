package com.petcare.platform.sequence;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sinh mã chứng từ liên tục tuyệt đối theo loại × tháng, ví dụ {@code PN-2610-00001} (BR-KO-03 "dãy số liên tục",
 * docs/adr/0043). Một câu duy nhất {@code INSERT … ON CONFLICT DO UPDATE … RETURNING} trên {@code document_counters}:
 * giữ khóa dòng tới hết transaction của bên gọi nên transaction rollback thì số cũng rollback (không để lỗ), và mọi
 * chứng từ cùng loại trong cùng tháng tạo tuần tự. Vì vậy bên gọi phải gọi {@link #next} <b>sau cùng</b> trong
 * transaction tạo chứng từ (sau mọi kiểm tra) để giữ khóa ngắn.
 * <p>
 * Kỳ theo ngày nghiệp vụ truyền vào (bên gọi dùng {@code LocalDate.now(clock)}), không theo ngày trên chứng từ. Dãy toàn
 * chuỗi. Mỗi kỳ tối đa {@value #MAX_PER_PERIOD} chứng từ; vượt thì {@link IllegalStateException} (cột {@code code} là
 * {@code VARCHAR(20)}). {@code platform} không biết module nào: bên gọi truyền loại và tiền tố.
 */
@Component
public class DocumentCodeGenerator {

    public static final int MAX_PER_PERIOD = 99_999;

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyMM");

    private final JdbcTemplate jdbc;

    public DocumentCodeGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param docType khóa của dãy, ví dụ {@code stock_receipts}
     * @param prefix  tiền tố hiển thị, ví dụ {@code PN}
     * @param date    ngày nghiệp vụ lúc tạo (quyết định tháng của dãy)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(String docType, String prefix, LocalDate date) {
        String period = date.format(PERIOD);
        Integer value = jdbc.queryForObject("""
                INSERT INTO document_counters (doc_type, period, last_value) VALUES (?, ?, 1)
                ON CONFLICT (doc_type, period) DO UPDATE SET last_value = document_counters.last_value + 1
                RETURNING last_value
                """, Integer.class, docType, period);
        if (value == null || value > MAX_PER_PERIOD) {
            throw new IllegalStateException("Document counter " + docType + "/" + period + " exceeded " + MAX_PER_PERIOD);
        }
        return "%s-%s-%05d".formatted(prefix, period, value);
    }
}
