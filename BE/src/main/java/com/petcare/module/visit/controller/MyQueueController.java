package com.petcare.module.visit.controller;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.visit.dto.VisitResponse;
import com.petcare.module.visit.service.VisitQueueService;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;

/**
 * Hàng đợi của tôi — visit-v1 #3 (UC46, A07, A08; BR-TN-05; docs/adr/0060): lượt {@code WAITING} / {@code IN_PROGRESS}
 * gán cho người đang đăng nhập, thứ tự BR-TN-04. Tách khỏi {@link VisitController} vì path nằm dưới {@code /api/me}
 * (khuôn {@code MyNotificationController}).
 */
@RestController
@RequestMapping("/api/me/queue")
public class MyQueueController {

    /** Hợp đồng #3: VET, CARETAKER (UC46). */
    static final String MY_QUEUE_ROLES = "hasAnyRole('VET','CARETAKER')";

    private final VisitQueueService queue;

    public MyQueueController(VisitQueueService queue) {
        this.queue = queue;
    }

    /** visit-v1 #3. */
    @Operation(operationId = "listMyQueue", tags = "Queue",
            summary = "Hàng đợi của tôi",
            description = "UC46 · BR-TN-05. Actor: A07, A08. Quyền: VET, CARETAKER. Chuyển trạng thái: —. Visit "
                + "WAITING / IN_PROGRESS gán cho tôi.")
    @ApiErrorDoc(status = 400, description = "BR-TK-17")
    @ApiErrorDoc(status = 403)
    @GetMapping
    @PreAuthorize(MY_QUEUE_ROLES)
    public ApiResponse<List<VisitResponse>> listMyQueue() {
        return ApiResponse.ok(queue.listMyQueue());
    }
}
