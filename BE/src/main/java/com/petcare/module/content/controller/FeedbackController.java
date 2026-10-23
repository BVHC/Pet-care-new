package com.petcare.module.content.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.content.dto.FeedbackResponse;
import com.petcare.module.content.dto.ResolveFeedbackRequest;
import com.petcare.module.content.entity.FeedbackStatus;
import com.petcare.module.content.entity.FeedbackTopic;
import com.petcare.module.content.service.FeedbackReviewService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.model.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

/** Xem và xử lý feedback (UC84, content-v1 #20–22). Không chứa logic: mọi rule ở {@link FeedbackReviewService}. */
@RestController
@RequestMapping("/api/feedbacks")
public class FeedbackController {

    private final FeedbackReviewService feedbacks;

    public FeedbackController(FeedbackReviewService feedbacks) {
        this.feedbacks = feedbacks;
    }

    @Operation(operationId = "listFeedbacks", tags = "Feedback", summary = "Danh sách feedback",
            description = "UC84 · BR-DG-02, 03. Actor: A04, A05. Quyền: SUPER_MANAGER: tất cả · BRANCH_MANAGER: "
                + "chi nhánh mình. Chuyển trạng thái: —.",
            parameters = {
                    @Parameter(name = "branchId", in = ParameterIn.QUERY, example = "2"),
                    @Parameter(name = "status", in = ParameterIn.QUERY, example = "NEW"),
                    @Parameter(name = "topic", in = ParameterIn.QUERY, example = "BRANCH_SERVICE"),
                    @Parameter(name = "from", in = ParameterIn.QUERY, example = "2026-10-01"),
                    @Parameter(name = "to", in = ParameterIn.QUERY, example = "2026-10-31")})
    @ApiErrorDoc(status = 400)
    @ApiErrorDoc(status = 403)
    @GetMapping
    @PreAuthorize(ContentAccess.FEEDBACK_REVIEWER)
    public ApiResponse<PageResponse<FeedbackResponse>> list(
            @RequestParam(required = false) @Positive(message = "branchId phải là số dương") Long branchId,
            @RequestParam(required = false) FeedbackStatus status,
            @RequestParam(required = false) FeedbackTopic topic,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(feedbacks.list(branchId, status, topic, from, to, page, size), "Danh sách feedback");
    }

    @Operation(operationId = "getFeedback", tags = "Feedback", summary = "Xem feedback",
            description = "UC84 · BR-DG-03, 04. Actor: A04, A05. Quyền: Như listFeedbacks. "
                + "Chuyển trạng thái: NEW → SEEN khi mở lần đầu (A2).",
            parameters = {@Parameter(name = "feedbackId", in = ParameterIn.PATH, example = "17")})
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @GetMapping("/{feedbackId}")
    @PreAuthorize(ContentAccess.FEEDBACK_REVIEWER)
    public ApiResponse<FeedbackResponse> get(@PathVariable Long feedbackId) {
        return ApiResponse.ok(feedbacks.open(feedbackId), "Chi tiết feedback");
    }

    @Operation(operationId = "resolveFeedback", tags = "Feedback", summary = "Đánh dấu đã xử lý",
            description = "UC84 · BR-DG-04. Actor: A04, A05. Quyền: Như listFeedbacks. "
                + "Chuyển trạng thái: SEEN → RESOLVED.",
            parameters = {@Parameter(name = "feedbackId", in = ParameterIn.PATH, example = "17")})
    @ApiErrorDoc(status = 400, description = "`BR-DG-04` thiếu ghi chú")
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 404)
    @ApiErrorDoc(status = 409, description = "Đã RESOLVED")
    @PostMapping("/{feedbackId}/resolve")
    @PreAuthorize(ContentAccess.FEEDBACK_REVIEWER)
    public ApiResponse<FeedbackResponse> resolve(@PathVariable Long feedbackId,
            @Valid @RequestBody ResolveFeedbackRequest request) {
        return ApiResponse.ok(feedbacks.resolve(feedbackId, request), "Đã đánh dấu xử lý");
    }
}
