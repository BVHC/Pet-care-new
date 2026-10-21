package com.petcare.module.visit.service;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.petcare.platform.sequence.DocumentCodeGenerator;

/**
 * Mã lượt tiếp nhận hiển thị (docs/adr/0058): {@code TN-<yyMM>-<5 chữ số>}, ví dụ {@code TN-2610-00001}, qua
 * {@link DocumentCodeGenerator} (docs/adr/0043): dãy toàn chuỗi, liên tục, reset theo tháng nghiệp vụ lúc tiếp nhận,
 * không cần migration.
 * <p>
 * Bộ đếm giữ khóa dòng {@code document_counters} tới hết transaction, nên Visit#1 gọi hàm này <b>sau</b> mọi guard và
 * sau khóa lịch hẹn ({@code AppointmentApi.checkIn}), ngay trước INSERT {@code visits}, rồi mới gọi
 * {@code VisitOrderApi.openForVisit} (bộ đếm {@code ORDER}). Thứ tự hai bộ đếm luôn là {@code visits → ORDER}; bán lẻ
 * chỉ lấy {@code ORDER}, nên không có vòng chờ. Cần transaction ghi.
 */
@Component
public class VisitCodeGenerator {

    static final String DOC_TYPE = "visits";
    static final String PREFIX = "TN";

    private final DocumentCodeGenerator codes;
    private final Clock clock;

    public VisitCodeGenerator(DocumentCodeGenerator codes, Clock clock) {
        this.codes = codes;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String nextCode() {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("VisitCodeGenerator.nextCode cần transaction ghi, không được readOnly");
        }
        return codes.next(DOC_TYPE, PREFIX, LocalDate.now(clock));
    }
}
