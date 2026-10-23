package com.petcare.module.content.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchStatus;
import com.petcare.module.content.dto.CreateFeedbackRequest;
import com.petcare.module.content.dto.MyFeedbackResponse;
import com.petcare.module.content.entity.Feedback;
import com.petcare.module.content.entity.FeedbackTopic;
import com.petcare.module.content.repository.FeedbackRepository;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/**
 * Khách gửi và xem lại feedback (content-v1 #18, #19; UC83; BR-DG-01, 02, BR-TK-19). Feedback không sửa, không xóa,
 * không công khai. Thứ tự: hồ sơ khách → BR-TK-19 → nội dung / điểm / chi nhánh (BR-DG-01) → advisory 1003 theo khách →
 * đếm trong ngày lịch giờ Việt Nam → ghi. Advisory tuần tự hóa các request đồng thời của cùng khách để hai request cùng
 * thấy "4" không cùng qua được; transaction giữ READ COMMITTED để lần đếm sau khóa thấy dòng vừa commit. Giới hạn đọc
 * từ [CFG] lúc kiểm (ADR-0057). Không audit, không thông báo.
 */
@Service
public class FeedbackSubmitService {

    static final int FEEDBACK_LOCK_NAMESPACE = 1003;
    static final int MIN_CONTENT = 10;
    static final int MAX_CONTENT = 2000;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final FeedbackRepository feedbacks;
    private final CustomerQueryApi customers;
    private final BranchQueryApi branches;
    private final SystemConfigApi configs;
    private final BranchScope scope;
    private final Clock clock;

    public FeedbackSubmitService(FeedbackRepository feedbacks, CustomerQueryApi customers, BranchQueryApi branches,
            SystemConfigApi configs, BranchScope scope, Clock clock) {
        this.feedbacks = feedbacks;
        this.customers = customers;
        this.branches = branches;
        this.configs = configs;
        this.scope = scope;
        this.clock = clock;
    }

    @Transactional
    public MyFeedbackResponse submit(CreateFeedbackRequest request) {
        Long customerId = ownCustomerId();
        if (customers.findContact(customerId).orElseThrow().linkDecisionPending()) {
            throw new BusinessRuleViolationException("BR-TK-19",
                    "Hồ sơ của bạn đang chờ quyết định liên kết với hồ sơ tại phòng khám");
        }
        String content = ContentText.normalize(request.content());
        int length = ContentText.codePoints(content);
        if (length < MIN_CONTENT || length > MAX_CONTENT) {
            throw new BusinessRuleViolationException("BR-DG-01",
                    "Nội dung feedback phải từ " + MIN_CONTENT + " đến " + MAX_CONTENT + " ký tự");
        }
        if (request.rating() != null && (request.rating() < 1 || request.rating() > 5)) {
            throw new BusinessRuleViolationException("BR-DG-01", "Mức hài lòng phải từ 1 đến 5");
        }
        if (request.topic() == FeedbackTopic.BRANCH_SERVICE && request.branchId() == null) {
            throw new BusinessRuleViolationException("BR-DG-01",
                    "Vui lòng chọn chi nhánh khi góp ý về dịch vụ tại chi nhánh");
        }
        if (request.branchId() != null && branches.findBranch(request.branchId())
                .filter(b -> b.status() == BranchStatus.ACTIVE).isEmpty()) {
            throw new ResourceNotFoundException("chi nhánh", request.branchId());
        }

        feedbacks.acquireTransactionLock(FEEDBACK_LOCK_NAMESPACE, (int) (customerId % Integer.MAX_VALUE));
        Instant now = clock.instant();
        LocalDate today = now.atZone(TimeConfig.BUSINESS_ZONE).toLocalDate();
        int limit = configs.getInt(ConfigKey.FEEDBACK_MAX_PER_DAY);
        long sentToday = feedbacks.countByCustomerIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(customerId,
                today.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant(),
                today.plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant());
        if (sentToday >= limit) {
            throw new BusinessRuleViolationException("BR-DG-01", "Bạn đã gửi đủ " + limit
                    + " feedback hôm nay, có thể gửi tiếp từ 00:00 ngày " + DAY.format(today.plusDays(1)));
        }
        Short rating = request.rating() == null ? null : request.rating().shortValue();
        return toResponse(feedbacks.saveAndFlush(
                new Feedback(customerId, request.topic(), request.branchId(), rating, content)));
    }

    @Transactional(readOnly = true)
    public List<MyFeedbackResponse> listMine() {
        return feedbacks.findByCustomerIdOrderByCreatedAtDescIdDesc(ownCustomerId()).stream()
                .map(FeedbackSubmitService::toResponse).toList();
    }

    private Long ownCustomerId() {
        Long accountId = scope.current().accountId();
        return customers.findCustomerIdByAccountId(accountId).orElseThrow(() ->
                new IllegalStateException("Tài khoản khách #" + accountId + " không có hồ sơ khách"));
    }

    private static MyFeedbackResponse toResponse(Feedback f) {
        return new MyFeedbackResponse(f.getId(), f.getTopic(), f.getBranchId(),
                f.getRating() == null ? null : f.getRating().intValue(), f.getContent(), f.getStatus(),
                ArticleService.micros(f.getCreatedAt()));
    }
}
