package com.petcare.module.ai.chat;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Định tuyến ý định cá nhân bằng luật (BR-AI-26, 07 A6): từ khóa ý định <b>cùng với</b> dấu hiệu sở hữu. Dấu hiệu mạnh
 * (của tôi, nhà mình… hoặc tên một thú còn sống của khách) đủ một mình; dấu hiệu yếu (sắp tới, quá hạn…) chỉ đủ với lịch
 * hẹn. Câu chứa cụm chỉ giá không bao giờ là ý định cá nhân. Luật nằm ở {@code ai/ai-personal-intent.txt}.
 * <p>
 * {@code petNames} là tên các thú còn sống của khách; endpoint công khai không biết khách là ai nên truyền danh sách rỗng
 * (BR-AI-28a). Cũng dùng cho câu hỏi bệnh án (BR-AI-28b), cùng định nghĩa "dấu hiệu sở hữu mạnh".
 */
@Component
public class PersonalIntentRouter {

    private static final String RESOURCE = "ai/ai-personal-intent.txt";
    private static final List<String> SECTIONS = List.of("STRONG", "WEAK", "APPOINTMENT", "VACCINATION", "PRICE",
            "RECORD", "COMMON_PET_NAMES", "PET_NAME_PREFIX");

    private final List<String> strong;
    private final List<String> weak;
    private final List<String> appointment;
    private final List<String> vaccination;
    private final List<String> price;
    private final List<String> record;
    private final List<String> commonPetNames;
    private final List<String> petNamePrefixes;

    public PersonalIntentRouter() {
        Map<String, List<String>> rules = ResourceLines.readSections(RESOURCE, SECTIONS);
        this.strong = rules.get("STRONG");
        this.weak = rules.get("WEAK");
        this.appointment = rules.get("APPOINTMENT");
        this.vaccination = rules.get("VACCINATION");
        this.price = rules.get("PRICE");
        this.record = rules.get("RECORD");
        this.commonPetNames = rules.get("COMMON_PET_NAMES").stream().map(PhraseMatcher::norm).toList();
        this.petNamePrefixes = rules.get("PET_NAME_PREFIX");
    }

    public Optional<PersonalIntent> detect(String question, List<String> petNames) {
        if (PhraseMatcher.anyPhrase(question, price)) {
            return Optional.empty();
        }
        boolean strongSign = hasStrongSign(question, petNames);
        if (PhraseMatcher.anyPhrase(question, appointment) && (strongSign || PhraseMatcher.anyPhrase(question, weak))) {
            return Optional.of(PersonalIntent.APPOINTMENT_UPCOMING);
        }
        if (PhraseMatcher.anyPhrase(question, vaccination) && strongSign) {
            return Optional.of(PersonalIntent.VACCINATION_SCHEDULE);
        }
        return Optional.empty();
    }

    /** BR-AI-28b: hỏi bệnh án, đơn thuốc, kết quả xét nghiệm của thú mình (dấu hiệu sở hữu mạnh hoặc tên thú). */
    public boolean isMedicalRecordQuestion(String question, List<String> petNames) {
        return PhraseMatcher.anyPhrase(question, record) && hasStrongSign(question, petNames);
    }

    /**
     * Các tên thú còn sống được nhắc trong câu. Tên trùng từ thông dụng (Vàng, Mít…) chỉ tính khi đi sau bé / con / chú /
     * em; đứng một mình ("giá vàng hôm nay") thì không.
     */
    public List<String> mentionedPetNames(String question, List<String> petNames) {
        return petNames.stream().filter(name -> isMentioned(question, name)).toList();
    }

    private boolean hasStrongSign(String question, List<String> petNames) {
        return PhraseMatcher.anyPhrase(question, strong) || !mentionedPetNames(question, petNames).isEmpty();
    }

    private boolean isMentioned(String question, String petName) {
        String name = PhraseMatcher.norm(petName);
        if (!commonPetNames.contains(name)) {
            return PhraseMatcher.hasPhrase(question, name);
        }
        return petNamePrefixes.stream().anyMatch(prefix -> PhraseMatcher.hasPhrase(question, prefix + " " + name));
    }
}
