package com.petcare.module.ai.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * ai-v1 {@code ChatRequest} (#1, #2). Cố ý <b>không có trường cho câu trả lời của trợ lý</b>: client gửi thêm trường lạ
 * thì Jackson bỏ qua, nên khách không thể đưa chỉ thị giả dạng lời trợ lý vào ngữ cảnh (BR-AI-08). Độ dài và số câu hỏi
 * trước là [CFG] {@code ai.chat.*} nên do {@code ChatService} kiểm, không phải Bean Validation.
 */
public record ChatRequest(
        @NotBlank(message = "Câu hỏi không được để trống")
        @Schema(description = "1–500 ký tự sau khi cắt khoảng trắng (BR-AI-08)", example = "Chó con tiêm phòng dại lúc mấy tuần?",
                maxLength = 500, requiredMode = Schema.RequiredMode.REQUIRED)
        String question,

        @Schema(description = "Tối đa 3 câu hỏi trước **của chính người dùng**, mỗi câu ≤ 500 ký tự. Không có trường cho "
                + "câu trả lời của trợ lý (BR-AI-08)", example = "[\"Mochi tiêm khi nào?\"]", nullable = true)
        List<String> previousQuestions) {
}
