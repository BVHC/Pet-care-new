package com.petcare.module.visit.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.petcare.module.visit.api.VisitQueryApi;

/**
 * Giữ chỗ cho {@link VisitQueryApi} tới khi module visit (BE-1, T26, hạn giao 21/10 — 06 §6) cài thật — nợ D016
 * (docs/dept/INDEX.md). Theo 06-module-contracts §1: mọi method ném lỗi, không trả giá trị "an toàn" (ví dụ danh sách
 * Visit dở dang rỗng sẽ cho vô hiệu hóa đi qua sai BR-QT-08). Hệ quả: điều chuyển và vô hiệu hóa nhân viên (T14,
 * docs/adr/0029) trả 500 trên app thật tới khi có bản thật. BE-1 xóa lớp này (cùng
 * {@code VisitQueryApiPlaceholderTest}) khi thêm implementation thật.
 */
@Service
public class VisitQueryApiPlaceholder implements VisitQueryApi {

    static final String MESSAGE = "VisitQueryApi chưa được cài (module visit, BE-1) — nợ D016";

    @Override
    public Optional<VisitSummary> findVisit(Long visitId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public List<Long> findUnfinishedVisitIdsAssignedTo(Long accountId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public boolean hasOpenVisit(Long petId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public boolean existsByPet(Long petId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public List<Long> findVisitIdsByPet(Long petId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public boolean hasMedicalHistory(Long petId) {
        throw new UnsupportedOperationException(MESSAGE);
    }
}
