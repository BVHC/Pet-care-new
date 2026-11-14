package com.petcare.module.ai.dto;

import java.util.List;

import com.petcare.module.ai.chat.ChatTexts;
import com.petcare.module.ai.entity.ChatResult;
import com.petcare.module.ai.entity.ChatSourceType;

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
        List<PetChoice> pets,

        @Schema(description = "Nguồn của câu trả lời (BR-AI-34), trường tùy chọn; không có với OUT_OF_SCOPE và UNAVAILABLE",
                nullable = true)
        ChatSourceType sourceType) {

    /**
     * Câu mẫu cố định của {@code result}; {@code bookingHint} cho giao diện hiện nút "Đặt lịch khám". Nguồn suy từ
     * {@code result} (BR-AI-34): an toàn, cơ sở dữ liệu hoặc không có.
     */
    public static ChatResponse canned(ChatResult result, String answer, boolean bookingHint) {
        ChatSourceType source = switch (result) {
            case EMERGENCY, MEDICAL_REFUSAL -> ChatSourceType.SAFETY;
            case PERSONAL, NEED_PET_CHOICE, NEED_LOGIN -> ChatSourceType.DATABASE;
            case ANSWERED, OUT_OF_SCOPE, UNAVAILABLE -> null;
        };
        return new ChatResponse(result, answer, List.of(), null, bookingHint, List.of(), List.of(), source);
    }

    /** {@code ANSWERED} từ CSDL, không qua mô hình: giá dịch vụ, chi nhánh (BR-AI-31). */
    public static ChatResponse database(String answer) {
        return new ChatResponse(ChatResult.ANSWERED, answer, List.of(), null, false, List.of(), List.of(),
                ChatSourceType.DATABASE);
    }

    /** {@code EMERGENCY}: cảnh báo cố định kèm chi nhánh nhận cấp cứu (BR-AI-03). */
    public static ChatResponse emergency(String warning, List<EmergencyBranch> branches) {
        return new ChatResponse(ChatResult.EMERGENCY, warning, List.of(), null, false, List.copyOf(branches), List.of(),
                ChatSourceType.SAFETY);
    }

    /** {@code PERSONAL}: câu mẫu về lịch hẹn / lịch tiêm của khách, không qua LLM (BR-AI-27). */
    public static ChatResponse personal(String answer, boolean bookingHint) {
        return canned(ChatResult.PERSONAL, answer, bookingHint);
    }

    /** {@code NEED_PET_CHOICE}: hỏi lại thú nào, kèm các thú để giao diện cho chọn (BR-AI-27). */
    public static ChatResponse needPetChoice(String question, List<PetChoice> pets) {
        return new ChatResponse(ChatResult.NEED_PET_CHOICE, question, List.of(), null, false, List.of(),
                List.copyOf(pets), ChatSourceType.DATABASE);
    }

    /**
     * {@code ANSWERED}: câu trả lời của LLM kèm nguồn từ siêu dữ liệu truy hồi và lời nhắc tham khảo (BR-AI-05). Không
     * có đoạn nào làm căn cứ thì là kiến thức chung; có đoạn mà câu mở đầu bằng tiền tố kiến thức chung thì là cả hai.
     */
    public static ChatResponse answered(String answer, List<ChatSource> sources, String disclaimer) {
        boolean general = answer.startsWith(ChatTexts.GENERAL_PREFIX);
        ChatSourceType source = sources.isEmpty() ? ChatSourceType.GENERAL_KNOWLEDGE
                : general ? ChatSourceType.KNOWLEDGE_BASE_AND_GENERAL : ChatSourceType.KNOWLEDGE_BASE;
        return new ChatResponse(ChatResult.ANSWERED, answer, List.copyOf(sources), disclaimer, false, List.of(),
                List.of(), source);
    }
}
