package com.petcare.module.ai.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.petcare.module.ai.chat.ChatService;
import com.petcare.module.ai.dto.ChatRequest;
import com.petcare.module.ai.dto.ChatResponse;
import com.petcare.platform.config.ApiErrorDoc;
import com.petcare.platform.model.ApiResponse;
import com.petcare.platform.security.BranchScope;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;

/**
 * Hỏi đáp chatbot — ai-v1 #1 (UC90, UC91; docs/adr/0131) và #2 (UC91; docs/adr/0132). Path public: token gửi kèm bị bỏ
 * qua (ADR-0005), nên câu hỏi về lịch của khách nhận {@code NEED_LOGIN}; {@code /me} lấy khách từ token và trả
 * {@code PERSONAL} / {@code NEED_PET_CHOICE}. Không chứa logic: {@link ChatService} dựng mọi kết quả; chỉ đầu vào sai là
 * 400 và vượt tần suất là 429 (do {@code RateLimitFilter}, BR-AI-09). Bean Validation của body chạy trước
 * {@code @PreAuthorize} (docs/adr/0028).
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final String CHAT_400 = "`VALIDATION_FAILED` / `BR-AI-08` câu hỏi rỗng hoặc quá 500 ký tự · quá 3 câu "
            + "hỏi trước · một câu hỏi trước quá 500 ký tự";
    private static final String TOO_MANY_REQUESTS = "`TOO_MANY_REQUESTS` một IP gọi quá nhiều lần (BR-AI-09, cơ chế "
            + "ADR-0100): header `Retry-After` là số giây nguyên phải chờ; request bị chặn trước khi nhúng hay gọi LLM, "
            + "không ghi nhật ký";

    private final ChatService chat;
    private final BranchScope scope;

    public ChatController(ChatService chat, BranchScope scope) {
        this.chat = chat;
        this.scope = scope;
    }

    /** ai-v1 #1. */
    @Operation(operationId = "askChatbot", tags = "Chatbot", summary = "Hỏi đáp chatbot (khách vãng lai)",
            description = "UC90, UC91 · BR-AI-01…10, 12, 24, 28. Actor: A01. Quyền: Public. Chuyển trạng thái: —. "
                + "Luôn 200 với mọi kết quả nghiệp vụ; giao diện rẽ theo `result`. Cấp cứu, từ chối bệnh án, "
                + "`NEED_LOGIN` và dưới ngưỡng truy hồi không gọi LLM; lỗi nhà cung cấp hoặc hết hạn mức trả "
                + "`UNAVAILABLE` (BR-AI-10). Token gửi kèm bị bỏ qua (ADR-0005) nên không bao giờ trả `PERSONAL` / "
                + "`NEED_PET_CHOICE`.")
    @ApiErrorDoc(status = 400, description = CHAT_400)
    @ApiErrorDoc(status = 429, description = TOO_MANY_REQUESTS)
    @PostMapping("/public/ai/chat")
    public ApiResponse<ChatResponse> askChatbot(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.ok(chat.ask(request));
    }

    /** ai-v1 #2. */
    @Operation(operationId = "askChatbotAsCustomer", tags = "Chatbot",
            summary = "Hỏi đáp chatbot (khách đăng nhập, có cá nhân hóa)",
            description = "UC90, UC91 · BR-AI-01…10, 12, 24, 26…28. Actor: A02. Quyền: CUSTOMER. Chuyển trạng thái: —. "
                + "Cùng luồng với endpoint công khai, thêm bước định tuyến ý định cá nhân trước truy hồi (BR-AI-26): lịch "
                + "hẹn sắp tới và lịch tiêm trả `PERSONAL` bằng câu mẫu (không qua LLM, không ghi nội dung vào nhật ký); "
                + "nhiều thú mà câu không nêu tên thì `NEED_PET_CHOICE` kèm `pets`. Khách lấy từ token, không lấy `petId` "
                + "từ câu hỏi (BR-AI-27).")
    @ApiErrorDoc(status = 400, description = CHAT_400)
    @ApiErrorDoc(status = 403)
    @ApiErrorDoc(status = 429, description = TOO_MANY_REQUESTS)
    @PostMapping("/me/ai/chat")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ApiResponse<ChatResponse> askChatbotAsCustomer(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.ok(chat.askAsCustomer(scope.current().accountId(), request));
    }
}
