package com.petcare.module.ai.chat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.petcare.module.ai.dto.VaccinationItem;
import com.petcare.module.ai.dto.VaccinationStatus;
import com.petcare.module.ai.dto.VaccinationSummary;
import com.petcare.module.ai.service.VaccinationSummaryService;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.LivingPet;
import com.petcare.module.customer.api.PetQueryApi.PetProfile;
import com.petcare.module.customer.api.Species;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.module.visit.api.VisitQueryApi.AgeGroup;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;

/**
 * Dựng khối {@code THÔNG TIN THÚ} của lời nhắc (BR-AI-32, docs/adr/0135 D2): văn bản tất định tiếng Việt, tối thiểu, theo ý
 * định của câu hỏi. Chọn thú trước (đúng một thú được nêu tên, hoặc khách chỉ có một thú còn sống; còn lại là không có
 * khối), kiểm chủ sở hữu bằng hồ sơ đọc mới ở mỗi lượt (không cache), rồi mới đọc phần dữ liệu theo nhóm trường.
 * <p>
 * Không bao giờ có trong khối: {@code petId}, {@code customerId}, tên thú, ngày sinh, ngày đủ ngày tháng năm, liên hệ,
 * bệnh án. Thiếu dữ liệu thì bỏ dòng, không đoán. Chuỗi tự do (giống, tên loại vaccine) chỉ vào khối khi là chữ ngắn thuần
 * túy, để không chèn chỉ thị vào lời nhắc (cùng tinh thần {@code ChatPrompt.fenceSafe}, BR-AI-12). Không {@code @Transactional}:
 * mỗi lần đọc là một lệnh gọi {@code *QueryApi} ngắn và chạy trước lệnh LLM (docs/adr/0141).
 */
@Component
public class PetContextBuilder {

    private static final String NUTRITION = "NUTRITION";
    private static final String VACCINATION = "VACCINATION";
    private static final Map<String, List<String>> KEYWORDS = ResourceLines.readSections("ai/ai-pet-context-keywords.txt",
            List.of(NUTRITION, VACCINATION));

    private static final int MAX_FREE_TEXT = 40;
    private static final Pattern PLAIN_TEXT = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} \\-]*");
    private static final int MAX_VACCINES = 5;
    private static final int MAX_SERVICES = 3;

    private final PetQueryApi pets;
    private final VaccinationSummaryService vaccinations;
    private final VisitQueryApi visits;
    private final CatalogQueryApi catalog;
    private final SystemConfigApi config;
    private final Clock clock;

    public PetContextBuilder(PetQueryApi pets, VaccinationSummaryService vaccinations, VisitQueryApi visits,
            CatalogQueryApi catalog, SystemConfigApi config, Clock clock) {
        this.pets = pets;
        this.vaccinations = vaccinations;
        this.visits = visits;
        this.catalog = catalog;
        this.config = config;
        this.clock = clock;
    }

    /**
     * @param livingPets     các thú còn sống của khách
     * @param mentionedNames tên các thú của khách được nhắc trong câu ({@code PersonalIntentRouter.mentionedPetNames})
     * @param question       câu hỏi đã che tên thú, dùng để chọn nhóm trường
     * @return khối văn bản, hoặc rỗng khi không chọn được thú, thú không thuộc khách, thú đã mất, hay đọc không được
     */
    public Optional<String> build(Long customerId, List<LivingPet> livingPets, List<String> mentionedNames, String question) {
        Optional<LivingPet> chosen = choosePet(livingPets, mentionedNames);
        if (chosen.isEmpty()) {
            return Optional.empty();
        }
        Optional<PetProfile> profile = pets.findPetProfile(chosen.get().petId())
                .filter(p -> p.customerId().equals(customerId) && !p.deceased());
        if (profile.isEmpty()) {
            return Optional.empty();
        }
        boolean nutrition = PhraseMatcher.anyPhrase(question, KEYWORDS.get(NUTRITION));
        boolean vaccination = PhraseMatcher.anyPhrase(question, KEYWORDS.get(VACCINATION));
        boolean care = !nutrition && !vaccination;
        PetProfile pet = profile.get();
        LocalDate today = LocalDate.now(clock);

        List<String> lines = new ArrayList<>();
        lines.add(identityLine(pet, today, nutrition || care));
        if (nutrition) {
            lines.add(bodyLine(pet));
        }
        if (vaccination) {
            Optional<VaccinationSummary> summary = summarize(customerId, pet.petId());
            if (summary.isEmpty()) {
                return Optional.empty();
            }
            lines.add(vaccinationLine(summary.get()));
        }
        if (care) {
            lines.add(servicesLine(pet.petId()));
        }
        return Optional.of(lines.stream().filter(line -> !line.isEmpty()).collect(Collectors.joining("\n")));
    }

    /** Đúng một thú được nêu tên, hoặc không nêu tên nào và khách chỉ có một thú còn sống; còn lại không chọn. */
    private static Optional<LivingPet> choosePet(List<LivingPet> livingPets, List<String> mentionedNames) {
        if (mentionedNames.isEmpty()) {
            return livingPets.size() == 1 ? Optional.of(livingPets.get(0)) : Optional.empty();
        }
        Set<String> named = mentionedNames.stream().map(PhraseMatcher::norm).collect(Collectors.toSet());
        List<LivingPet> matching = livingPets.stream().filter(pet -> named.contains(PhraseMatcher.norm(pet.name()))).toList();
        return matching.size() == 1 ? Optional.of(matching.get(0)) : Optional.empty();
    }

    private String identityLine(PetProfile pet, LocalDate today, boolean withBreed) {
        List<String> parts = new ArrayList<>();
        parts.add("Loài: " + speciesWord(pet.species()) + ".");
        if (withBreed) {
            plain(pet.breed()).ifPresent(breed -> parts.add("Giống: " + breed + "."));
        }
        ageWord(AgeGroup.of(pet.birthDate(), today, seniorAgeYears(pet.species()))).ifPresent(age -> parts.add("Nhóm tuổi: " + age + "."));
        return String.join(" ", parts);
    }

    private static String bodyLine(PetProfile pet) {
        List<String> parts = new ArrayList<>();
        BigDecimal kg = pet.currentWeightKg();
        if (kg != null && kg.signum() > 0) {
            parts.add("Cân nặng gần nhất: " + kg.setScale(1, RoundingMode.HALF_UP).toPlainString().replace('.', ',') + " kg.");
        }
        if (pet.isNeutered() != null) {
            parts.add("Đã triệt sản: " + (pet.isNeutered() ? "có" : "chưa") + ".");
        }
        return String.join(" ", parts);
    }

    /** Thú không thuộc khách hay đã mất (đọc lại ở đây) là "không có khối", không phải lỗi của lượt hỏi. */
    private Optional<VaccinationSummary> summarize(Long customerId, Long petId) {
        try {
            return Optional.of(vaccinations.summarize(customerId, petId));
        } catch (ResourceNotFoundException | BusinessRuleViolationException e) {
            return Optional.empty();
        }
    }

    private static String vaccinationLine(VaccinationSummary summary) {
        List<String> doses = new ArrayList<>();
        for (VaccinationItem item : summary.items()) {
            Optional<String> name = plain(item.vaccineName());
            if (item.lastAdministeredOn() == null || name.isEmpty() || doses.size() == MAX_VACCINES) {
                continue;
            }
            doses.add(name.get() + " (đã tiêm " + monthYear(item.lastAdministeredOn()) + statusWord(item.status()) + ")");
        }
        return doses.isEmpty() ? "" : "Tiêm phòng: " + String.join("; ", doses) + ".";
    }

    private String servicesLine(Long petId) {
        Map<Long, LocalDate> lastUsed = visits.findLastServiceDates(petId);
        List<Map.Entry<Long, LocalDate>> newest = lastUsed.entrySet().stream()
                .sorted(Map.Entry.<Long, LocalDate>comparingByValue().reversed().thenComparing(Map.Entry.<Long, LocalDate>comparingByKey().reversed()))
                .limit(MAX_SERVICES).toList();
        if (newest.isEmpty()) {
            return "";
        }
        Map<Long, ServiceInfo> info = catalog.findServices(newest.stream().map(Map.Entry::getKey).toList()).stream()
                .collect(Collectors.toMap(ServiceInfo::serviceId, Function.identity(), (a, b) -> a));
        List<String> recent = newest.stream().filter(entry -> info.containsKey(entry.getKey()))
                .map(entry -> groupWord(info.get(entry.getKey()).group()) + " " + monthYear(entry.getValue())).toList();
        return recent.isEmpty() ? "" : "Dịch vụ gần đây: " + String.join("; ", recent) + ".";
    }

    /** Chuỗi tự do chỉ vào khối khi là chữ ngắn thuần túy và không chứa liên hệ; sai thì bỏ trường. */
    private static Optional<String> plain(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String value = PhraseMatcher.nfc(text).strip();
        boolean ok = value.length() <= MAX_FREE_TEXT && PLAIN_TEXT.matcher(value).matches() && PiiMasker.mask(value).equals(value);
        return ok ? Optional.of(value) : Optional.empty();
    }

    private int seniorAgeYears(Species species) {
        return config.getInt(switch (species) {
            case DOG -> ConfigKey.AI_RECO_SENIOR_AGE_YEARS_DOG;
            case CAT -> ConfigKey.AI_RECO_SENIOR_AGE_YEARS_CAT;
            case OTHER -> ConfigKey.AI_RECO_SENIOR_AGE_YEARS_OTHER;
        });
    }

    private static String monthYear(LocalDate date) {
        return "tháng " + date.getMonthValue() + "/" + date.getYear();
    }

    private static String speciesWord(Species species) {
        return switch (species) {
            case DOG -> "chó";
            case CAT -> "mèo";
            case OTHER -> "khác";
        };
    }

    private static Optional<String> ageWord(AgeGroup group) {
        return switch (group) {
            case JUNIOR -> Optional.of("còn nhỏ");
            case ADULT -> Optional.of("trưởng thành");
            case SENIOR -> Optional.of("lớn tuổi");
            case UNKNOWN -> Optional.empty();
        };
    }

    private static String statusWord(VaccinationStatus status) {
        return switch (status) {
            case DUE_SOON -> ", sắp đến hạn";
            case OVERDUE -> ", quá hạn";
            default -> "";
        };
    }

    private static String groupWord(ServiceGroup group) {
        return switch (group) {
            case MEDICAL -> "khám/tiêm";
            case GROOMING -> "thẩm mỹ";
            case BOARDING -> "lưu trú";
        };
    }
}
