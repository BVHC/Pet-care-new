package com.petcare.module.content.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.content.dto.FeedbackResponse;
import com.petcare.module.content.entity.Feedback;
import com.petcare.module.content.entity.FeedbackTopic;
import com.petcare.module.content.exception.ContentValidationException;
import com.petcare.module.content.repository.FeedbackRepository;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.model.PageResponse;
import com.petcare.platform.security.AccessScope;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

import jakarta.persistence.criteria.Predicate;

/**
 * Xem feedback (content-v1 #20–21; UC84; BR-DG-02, 03). Chỉ đọc: feedback không có trạng thái, không đánh dấu xử lý
 * (docs/adr/0122). SUPER_MANAGER thấy mọi feedback; BRANCH_MANAGER chỉ feedback gắn chi nhánh mình (feedback không gắn
 * chi nhánh chỉ SUPER_MANAGER), ngoài phạm vi là 403. Thứ tự lỗi: 403/404 → 400. Không ghi, không khóa, không audit.
 */
@Service
public class FeedbackReviewService {

    static final int MAX_PAGE_SIZE = 100;

    private final FeedbackRepository feedbacks;
    private final CustomerQueryApi customers;
    private final BranchQueryApi branches;
    private final BranchScope scope;

    public FeedbackReviewService(FeedbackRepository feedbacks, CustomerQueryApi customers, BranchQueryApi branches,
            BranchScope scope) {
        this.feedbacks = feedbacks;
        this.customers = customers;
        this.branches = branches;
        this.scope = scope;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<FeedbackResponse> list(Long branchId, FeedbackTopic topic, LocalDate from, LocalDate to,
            int page, int size) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ContentValidationException("Ngày bắt đầu không được sau ngày kết thúc");
        }
        Long effectiveBranch = scope.resolve(branchId);
        Specification<Feedback> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (effectiveBranch != null) {
                predicates.add(cb.equal(root.get("branchId"), effectiveBranch));
            }
            if (topic != null) {
                predicates.add(cb.equal(root.get("topic"), topic));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"),
                        from.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant()));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("createdAt"),
                        to.plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        var result = feedbacks.findAll(spec, PageRequest.of(Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE), Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        Map<Long, String> customerNames = customers.findContacts(
                result.getContent().stream().map(Feedback::getCustomerId).distinct().toList()).stream()
                .collect(Collectors.toMap(CustomerContact::customerId, CustomerContact::fullName));
        Map<Long, String> branchNames = branchNames(result.getContent().stream().map(Feedback::getBranchId).toList());
        return PageResponse.of(result.map(f -> toResponse(f, customerNames.get(f.getCustomerId()),
                f.getBranchId() == null ? null : branchNames.get(f.getBranchId()))));
    }

    /** Chi tiết một feedback. Chỉ đọc: 404 nếu không có, 403 nếu ngoài phạm vi (BR-DG-03). */
    @Transactional(readOnly = true)
    public FeedbackResponse get(Long feedbackId) {
        authorize(feedbackId);
        return toResponse(feedbacks.findById(feedbackId).orElseThrow(() -> notFound(feedbackId)));
    }

    /** 404 nếu không có; 403 nếu ngoài phạm vi (feedback không gắn chi nhánh chỉ SUPER_MANAGER, BR-DG-03). */
    private void authorize(Long feedbackId) {
        Long branchId = feedbacks.findBranchOnlyById(feedbackId).orElseThrow(() -> notFound(feedbackId)).getBranchId();
        SecurityPrincipal principal = scope.current();
        if (branchId == null) {
            if (principal.accessScope() != AccessScope.CHAIN) {
                throw new AccessDeniedScopeException("feedback:chain", "scope:" + principal.accessScope());
            }
            return;
        }
        scope.check(branchId);
    }

    private FeedbackResponse toResponse(Feedback f) {
        String customerName = customers.findContact(f.getCustomerId()).map(CustomerContact::fullName).orElse(null);
        String branchName = f.getBranchId() == null ? null
                : branches.findBranch(f.getBranchId()).map(BranchQueryApi.BranchSummary::name).orElse(null);
        return toResponse(f, customerName, branchName);
    }

    private Map<Long, String> branchNames(List<Long> branchIds) {
        Map<Long, String> names = new HashMap<>();
        branchIds.stream().filter(java.util.Objects::nonNull).distinct().forEach(id ->
                branches.findBranch(id).ifPresent(b -> names.put(id, b.name())));
        return names;
    }

    private static FeedbackResponse toResponse(Feedback f, String customerName, String branchName) {
        return new FeedbackResponse(f.getId(), f.getCustomerId(), customerName, f.getTopic(), f.getBranchId(),
                branchName, f.getRating() == null ? null : f.getRating().intValue(), f.getContent(),
                ArticleService.micros(f.getCreatedAt()));
    }

    private static ResourceNotFoundException notFound(Long feedbackId) {
        return new ResourceNotFoundException("feedback", feedbackId);
    }
}
