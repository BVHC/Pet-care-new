package com.petcare.module.identity.service;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.identity.dto.AuditLogEntryResponse;
import com.petcare.module.identity.dto.AuditLogFilter;
import com.petcare.module.identity.mapper.AuditLogMapper;
import com.petcare.module.identity.repository.AuditLogQueryRepository;
import com.petcare.platform.model.PageResponse;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.validation.Validator;

/**
 * identity-v1 #36 — ADMIN xem nhật ký audit (UC11; BR-QT-15, 16; docs/adr/0038). Chỉ ADMIN — kiểm bằng
 * {@code @PreAuthorize} ở controller; {@code audit_logs} không có {@code branch_id} nên không qua {@code BranchScope}.
 * Xem <b>không</b> ghi audit (Q5): lớp này cố ý không có {@code AuditRecorder}.
 *
 * <p>Transaction readOnly + REPEATABLE READ (docs/adr/0038 mục 8): câu đếm và câu trang dùng cùng một snapshot, nên
 * {@code totalElements} khớp {@code content} dù audit đang được ghi liên tục; Postgres ép không ghi
 * ({@code BEGIN READ ONLY}). Spring trả isolation / readOnly của connection về mặc định khi transaction kết thúc
 * (docs/adr/0020 mục quyết định).
 */
@Service
public class AuditLogQueryService {

    static final int MAX_PAGE_SIZE = 100;

    /** Thứ tự cố định của lỗi R1 → R2 → R3 trong message (tên tham số mà validator gắn lỗi). */
    private static final List<String> RULE_ORDER = List.of("to", "entityType", "from");

    private final AuditLogQueryRepository auditLogs;
    private final AuditLogMapper mapper;
    private final Validator validator;

    public AuditLogQueryService(AuditLogQueryRepository auditLogs, AuditLogMapper mapper, Validator validator) {
        this.auditLogs = auditLogs;
        this.mapper = mapper;
        this.validator = validator;
    }

    /**
     * Sắp {@code created_at DESC, id DESC}; {@code page} ≥ 0, {@code size} 1–100 (kẹp như
     * {@code AccountAdminQueryService}, docs/api/00-method.md §3.4). R1–R3 sai → {@link ConstraintViolationException}
     * (400 {@code VALIDATION_FAILED}) trước mọi câu SQL.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<AuditLogEntryResponse> listAuditLogs(AuditLogFilter filter, int page, int size) {
        Set<ConstraintViolation<AuditLogFilter>> violations = validator.validate(filter);
        if (!violations.isEmpty()) {
            throw new OrderedConstraintViolationException(ordered(violations));
        }
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        long total = auditLogs.count(filter);
        List<AuditLogEntryResponse> content = auditLogs
                .findPage(filter, pageable.getOffset(), pageable.getPageSize()).stream()
                .map(mapper::toResponse)
                .toList();
        return PageResponse.of(new PageImpl<>(content, pageable, total));
    }

    private static Set<ConstraintViolation<?>> ordered(Set<ConstraintViolation<AuditLogFilter>> violations) {
        Set<ConstraintViolation<?>> ordered = new LinkedHashSet<>();
        violations.stream()
                .sorted(Comparator.comparingInt(violation -> RULE_ORDER.indexOf(lastNode(violation.getPropertyPath()))))
                .forEach(ordered::add);
        return ordered;
    }

    private static String lastNode(Path path) {
        String name = null;
        for (Path.Node node : path) {
            name = node.getName();
        }
        return name;
    }

    /**
     * Constructor của {@link ConstraintViolationException} chép tập lỗi sang {@code HashSet}, làm mất thứ tự R1 → R2 → R3
     * mà {@code GlobalExceptionHandler} nối vào message. Lớp con chỉ trả lại đúng tập đã sắp; handler, errorCode
     * ({@code VALIDATION_FAILED}) và HTTP 400 không đổi.
     */
    private static final class OrderedConstraintViolationException extends ConstraintViolationException {

        private final transient Set<ConstraintViolation<?>> ordered;

        OrderedConstraintViolationException(Set<ConstraintViolation<?>> ordered) {
            super(ordered);
            this.ordered = ordered;
        }

        @Override
        public Set<ConstraintViolation<?>> getConstraintViolations() {
            return ordered;
        }
    }
}
