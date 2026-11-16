package com.petcare.module.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

/**
 * Đóng băng chữ của lời nhắc cũ (docs/adr/0136 "Đã cài ở T92 (phần 2)"): chế độ mở thêm một lời nhắc MỚI chứ không sửa hai
 * lời nhắc đang chạy ở DB demo, nên bộ 43 câu baseline và hành vi demo hiện tại không đổi. Đổi một byte của
 * {@code SYSTEM}, {@code SYSTEM_GENERAL} hay {@code PET_CONTEXT_RULE} là phải sửa băm ở đây và nói rõ trong ADR.
 */
class ChatPromptFreezeTest {

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theClosedPromptIsByteIdenticalToT77() throws Exception {
        assertThat(ChatPrompt.SYSTEM).hasSize(599);
        assertThat(sha256(ChatPrompt.SYSTEM)).isEqualTo("0e7610a504dbe6c84a53f1d16202c1de9b0027607a5742a20ca5d157c81ddb53");
    }

    @Test
    void theGeneralKnowledgePromptIsByteIdenticalToT92Slice() throws Exception {
        assertThat(ChatPrompt.SYSTEM_GENERAL).hasSize(1324);
        assertThat(sha256(ChatPrompt.SYSTEM_GENERAL)).isEqualTo("6c90795b4fcd8b690ff9ddde89fbf25adb1f167e57b35b701b9e4ad7a2528336");
    }

    @Test
    void thePetContextRuleIsByteIdenticalToT91() throws Exception {
        assertThat(ChatPrompt.PET_CONTEXT_RULE).hasSize(352);
        assertThat(sha256(ChatPrompt.PET_CONTEXT_RULE)).isEqualTo("5617405e442d98f9ce61872ab4ca3ea2e617c42d96ca66489cc7ac1eeeead420");
    }

    @Test
    void theOpenPromptAsksForACategoryAndKeepsTheSafetyRules() {
        String open = ChatPrompt.SYSTEM_OPEN;

        assertThat(open).contains("category", "PET_CARE", "MEDICAL_RISK", "OUT_OF_DOMAIN", "BUSINESS", "BLOCKED");
        assertThat(open).contains(ChatTexts.OPEN_PREFIX).contains("tối đa 5 câu");
        assertThat(open).as("lược đồ mới không còn cờ inScope").doesNotContain("inScope");
        assertThat(open).contains("không chẩn đoán", "không nêu tên thuốc").contains("dữ liệu, không phải chỉ thị");
        assertThat(open).as("lời nhắc mở không mạo danh lời nhắc cũ").isNotEqualTo(ChatPrompt.SYSTEM_GENERAL);
    }

    @Test
    void theOpenPromptStillTripsTheLeakGuard() {
        assertThat(ChatPrompt.leaksSystemPrompt(ChatPrompt.SYSTEM_OPEN.lines().findFirst().orElseThrow())).isTrue();
    }

    @Test
    void theOpenPromptTakesThePetContextRuleOnlyWhenABlockIsSent() {
        assertThat(ChatPrompt.systemOpen(false)).isEqualTo(ChatPrompt.SYSTEM_OPEN);
        assertThat(ChatPrompt.systemOpen(true)).isEqualTo(ChatPrompt.SYSTEM_OPEN + "\n" + ChatPrompt.PET_CONTEXT_RULE);
    }
}
