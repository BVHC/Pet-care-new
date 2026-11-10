package com.petcare.module.ai.dto;

import java.util.List;

import com.petcare.module.ai.entity.ChatResult;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * ai-v1 {@code ChatResponse} (#1, #2): giao diện rẽ theo {@code result} (07 §10). Luôn đủ {@code sources},
 * {@code bookingHint}, {@code emergencyBranches}, {@code pets}; {@code disclaimer} và {@code sources} chỉ có khi
 * {@code ANSWERED}, {@code emergencyBranches} chỉ khi {@code EMERGENCY}, {@code pets} chỉ khi {@code NEED_PET_CHOICE}.
 */
public record ChatResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChatResult result,

        @Schema(description = "Câu trả lời hoặc câu mẫu cố định",
                example = "Chó con thường được tiêm mũi phòng dại đầu tiên từ 12 tuần tuổi…",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String answer,

        @Schema(description = "Tối đa 3 tài liệu; rỗng khi không ANSWERED", requiredMode = Schema.RequiredMode.REQUIRED)
        List<ChatSource> sources,

        @Schema(description = "Có với ANSWERED (BR-AI-05)", example = "Thông tin tham khảo, không thay thế khám thú y.",
                nullable = true)
        String disclaimer,

        @Schema(description = "true thì giao diện hiện nút đặt lịch khám (UC39)", example = "false",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean bookingHint,

        @Schema(description = "Chỉ có với EMERGENCY (BR-AI-03)", requiredMode = Schema.RequiredMode.REQUIRED)
        List<EmergencyBranch> emergencyBranches,

        @Schema(description = "Chỉ có với NEED_PET_CHOICE: giao diện cho chọn rồi gọi `…/vaccination-summary` (BR-AI-27)",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<PetChoice> pets) {

    /** Câu mẫu cố định của {@code result}; {@code bookingHint} cho giao diện hiện nút "Đặt lịch khám". */
    public static ChatResponse canned(ChatResult result, String answer, boolean bookingHint) {
        return new ChatResponse(result, answer, List.of(), null, bookingHint, List.of(), List.of());
    }

    /** {@code EMERGENCY}: cảnh báo cố định kèm chi nhánh nhận cấp cứu (BR-AI-03). */
    public static ChatResponse emergency(String warning, List<EmergencyBranch> branches) {
        return new ChatResponse(ChatResult.EMERGENCY, warning, List.of(), null, false, List.copyOf(branches), List.of());
    }

    /** {@code ANSWERED}: câu trả lời của LLM kèm nguồn từ siêu dữ liệu truy hồi và lời nhắc tham khảo (BR-AI-05). */
    public static ChatResponse answered(String answer, List<ChatSource> sources, String disclaimer) {
        return new ChatResponse(ChatResult.ANSWERED, answer, List.copyOf(sources), disclaimer, false, List.of(),
                List.of());
    }
}
