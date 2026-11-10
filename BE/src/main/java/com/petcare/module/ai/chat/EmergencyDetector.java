package com.petcare.module.ai.chat;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Dấu hiệu cấp cứu (BR-AI-03): so khớp từ khóa trong {@code ai/ai-emergency-keywords.txt} theo cụm từ có ranh giới từ
 * ({@link PhraseMatcher}). Trúng thì ChatService không gọi embedding hay LLM.
 */
@Component
class EmergencyDetector {

    private static final String RESOURCE = "ai/ai-emergency-keywords.txt";

    private final List<String> keywords = ResourceLines.read(RESOURCE);

    boolean isEmergency(String question) {
        return PhraseMatcher.anyPhrase(question, keywords);
    }
}
