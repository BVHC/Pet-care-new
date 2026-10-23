package com.petcare.module.content.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.content.dto.CreateFeedbackRequest;
import com.petcare.module.content.dto.MyFeedbackResponse;
import com.petcare.module.content.service.FeedbackSubmitService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;

/** Feedback của khách (UC83, content-v1 #18–19). Không chứa logic: mọi rule ở {@link FeedbackSubmitService}. */
@RestController
@RequestMapping("/api/me/feedbacks")
public class MyFeedbackController {

    private final FeedbackSubmitService feedbacks;

    public MyFeedbackController(FeedbackSubmitService feedbacks) {
        this.feedbacks = feedbacks;
    }

    @Operation(operationId = "submitFeedback", tags = "Feedback", summary = "Gửi feedback",
            description = "UC83 · BR-DG-01, 02, BR-TK-19. Actor: A02. Quyền: Chỉ CUSTOMER. "
                + "Chuyển trạng thái: — → NEW.")
    @ApiErrorDoc(status = 400, description = "`BR-DG-01` nội dung ngoài 10–2000 ký tự, thiếu chi nhánh khi "
            + "BRANCH_SERVICE, điểm ngoài 1–5, vượt 5 feedback / ngày [CFG] (message ghi thời điểm gửi tiếp) · "
            + "`BR-TK-19` hồ sơ còn chờ quyết định liên kết")
    @ApiErrorDoc(status = 403)
    @PostMapping
    @PreAuthorize(ContentAccess.CUSTOMER)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MyFeedbackResponse> submit(@Valid @RequestBody CreateFeedbackRequest request) {
        return ApiResponse.created(feedbacks.submit(request), "Đã gửi feedback");
    }

    @Operation(operationId = "listMyFeedbacks", tags = "Feedback", summary = "Feedback tôi đã gửi",
            description = "UC83 · BR-DG-02, 04. Actor: A02. Quyền: Chỉ CUSTOMER. Chuyển trạng thái: —. "
                + "Không sửa, không xóa.")
    @ApiErrorDoc(status = 403)
    @GetMapping
    @PreAuthorize(ContentAccess.CUSTOMER)
    public ApiResponse<List<MyFeedbackResponse>> listMine() {
        return ApiResponse.ok(feedbacks.listMine(), "Feedback của tôi");
    }
}
