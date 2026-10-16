package com.petcare.module.visit.service;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.petcare.module.visit.api.VisitQueryApi;

/**
 * Giữ chỗ cho {@link VisitQueryApi} tới khi module visit (BE-1, T26, hạn giao 21/10 — 06 §6) cài thật — nợ D016
 * (docs/dept/INDEX.md). Theo 06-module-contracts §1: mọi method ném lỗi, không trả giá trị "an toàn" (ví dụ danh sách
 * Visit dở dang rỗng sẽ cho vô hiệu hóa đi qua sai BR-QT-08). Hệ quả: điều chuyển, vô hiệu hóa và khóa nhân viên
 * A05–A08 (T14, docs/adr/0029, 0035) trả 500 trên app thật tới khi có bản thật. BE-1 xóa lớp này (cùng
 * {@code VisitQueryApiPlaceholderTest}) khi thêm implementation thật.
 * <p>
 * <b>Cài thật một phần (2026-10-16, T17, BE-2 làm thay để khóa loài / xóa thú / đánh dấu đã mất / chuyển chủ chạy
 * được):</b> {@code hasOpenVisit}, {@code existsByPet}, {@code findVisitIdsByPet}, {@code hasMedicalHistory} đọc thẳng
 * bảng {@code visits}, {@code medical_records}, {@code vaccinations} bằng SQL trong transaction của bên gọi; các method
 * còn lại vẫn ném. BE-1 thay bằng bản của mình ở T26 (giữ ngữ nghĩa ghi ở javadoc {@link VisitQueryApi}).
 */
@Service
public class VisitQueryApiPlaceholder implements VisitQueryApi {

    static final String MESSAGE = "VisitQueryApi chưa được cài (module visit, BE-1) — nợ D016";

    private final JdbcTemplate jdbc;

    public VisitQueryApiPlaceholder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<VisitSummary> findVisit(Long visitId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public List<AssignedVisit> findUnfinishedVisitsAssignedTo(Long accountId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public boolean hasOpenVisit(Long petId) {
        return exists("SELECT EXISTS (SELECT 1 FROM visits WHERE pet_id = ? AND status IN ('WAITING', 'IN_PROGRESS'))",
                petId);
    }

    @Override
    public boolean existsByPet(Long petId) {
        return exists("SELECT EXISTS (SELECT 1 FROM visits WHERE pet_id = ?)", petId);
    }

    @Override
    public List<Long> findVisitIdsByPet(Long petId) {
        return jdbc.queryForList("SELECT id FROM visits WHERE pet_id = ? ORDER BY id", Long.class, petId);
    }

    @Override
    public boolean hasMedicalHistory(Long petId) {
        return exists("""
                SELECT EXISTS (SELECT 1 FROM medical_records mr JOIN visits v ON v.id = mr.visit_id WHERE v.pet_id = ?)
                    OR EXISTS (SELECT 1 FROM vaccinations WHERE pet_id = ?)
                """, petId, petId);
    }

    private boolean exists(String sql, Object... args) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args));
    }
}
