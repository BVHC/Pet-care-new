package com.petcare.module.ai.chat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.petcare.module.ai.dto.PetChoice;
import com.petcare.module.ai.entity.ChatResult;
import com.petcare.module.ai.service.VaccinationSummaryService;
import com.petcare.module.appointment.api.AppointmentQueryApi;
import com.petcare.module.appointment.api.AppointmentQueryApi.UpcomingBooked;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.branch.api.BranchQueryApi.BranchSummary;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.customer.api.PetQueryApi.LivingPet;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;

/**
 * Trả lời ý định cá nhân bằng câu mẫu, không qua LLM (BR-AI-27, docs/adr/0132). Khách và các thú còn sống do bên gọi đưa
 * vào: khách lấy từ tài khoản của token, không bao giờ từ câu hỏi; tên thú chỉ để chọn trong số thú của chính khách.
 * <p>
 * Lịch hẹn: I-10 đã lọc {@code >= bây giờ} trong SQL, nhưng đặc tả vẫn đòi lọc lại ở module {@code ai} (lịch {@code BOOKED}
 * đã quá giờ mà ST05 chưa chuyển {@code NO_SHOW} nằm trong dữ liệu) và chỉ lấy thú còn sống của khách (thú đã chuyển chủ
 * hay đã mất không hiện lịch cũ). Lọc sau {@code limit} nên có thể trả ít dòng hơn giới hạn dù còn lịch (ADR-0132).
 */
@Service
public class PersonalService {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Kết quả cá nhân: {@code PERSONAL} hoặc {@code NEED_PET_CHOICE} (kèm {@code pets}). */
    public record Reply(ChatResult result, String answer, boolean bookingHint, List<PetChoice> pets) {

        static Reply personal(String answer, boolean bookingHint) {
            return new Reply(ChatResult.PERSONAL, answer, bookingHint, List.of());
        }

        static Reply needPetChoice(List<LivingPet> choices) {
            return new Reply(ChatResult.NEED_PET_CHOICE, ChatTexts.NEED_PET_CHOICE, false,
                    choices.stream().map(pet -> new PetChoice(pet.petId(), pet.name())).toList());
        }
    }

    private final AppointmentQueryApi appointments;
    private final CatalogQueryApi catalog;
    private final BranchQueryApi branches;
    private final VaccinationSummaryService vaccinations;
    private final SystemConfigApi config;
    private final Clock clock;

    public PersonalService(AppointmentQueryApi appointments, CatalogQueryApi catalog, BranchQueryApi branches,
            VaccinationSummaryService vaccinations, SystemConfigApi config, Clock clock) {
        this.appointments = appointments;
        this.catalog = catalog;
        this.branches = branches;
        this.vaccinations = vaccinations;
        this.config = config;
        this.clock = clock;
    }

    /**
     * @param customerId     null khi tài khoản chưa có hồ sơ khách (BR-TK-19): coi như không có thú và không có lịch
     * @param livingPets     thú còn sống của khách này
     * @param mentionedNames tên các thú được nhắc trong câu (đã khớp theo BR-AI-26)
     */
    public Reply answer(PersonalIntent intent, Long customerId, List<LivingPet> livingPets, List<String> mentionedNames) {
        return switch (intent) {
            case APPOINTMENT_UPCOMING -> upcomingAppointments(customerId, livingPets);
            case VACCINATION_SCHEDULE -> vaccinationSchedule(customerId, livingPets, mentionedNames);
        };
    }

    private Reply upcomingAppointments(Long customerId, List<LivingPet> livingPets) {
        if (customerId == null) {
            return noAppointment();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        int limit = config.getInt(ConfigKey.AI_PERSONAL_APPOINTMENTS_LIMIT);
        Map<Long, String> petNames = livingPets.stream()
                .collect(Collectors.toMap(LivingPet::petId, LivingPet::name, (first, second) -> first));
        List<UpcomingBooked> upcoming = appointments.findUpcomingBookedOfCustomer(customerId, now, limit).stream()
                .filter(booked -> !booked.slotDate().atTime(booked.slotStart()).isBefore(now))
                .filter(booked -> petNames.containsKey(booked.petId()))
                .sorted(Comparator.comparing(UpcomingBooked::slotDate).thenComparing(UpcomingBooked::slotStart))
                .limit(limit).toList();
        if (upcoming.isEmpty()) {
            return noAppointment();
        }
        Map<Long, String> services = catalog
                .findServices(upcoming.stream().map(UpcomingBooked::serviceId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(ServiceInfo::serviceId, ServiceInfo::name, (first, second) -> first));
        Map<Long, String> branchNames = upcoming.stream().map(UpcomingBooked::branchId).distinct()
                .collect(Collectors.toMap(Function.identity(),
                        id -> branches.findBranch(id).map(BranchSummary::name).orElse("Chi nhánh #" + id)));

        StringBuilder text = new StringBuilder("Bạn có ").append(upcoming.size()).append(" lịch hẹn sắp tới:");
        for (int i = 0; i < upcoming.size(); i++) {
            UpcomingBooked booked = upcoming.get(i);
            text.append('\n').append(i + 1).append(") ").append(petNames.get(booked.petId())).append(" — ")
                    .append(services.getOrDefault(booked.serviceId(), "Dịch vụ #" + booked.serviceId())).append(" — ")
                    .append(WHEN.format(booked.slotDate().atTime(booked.slotStart()))).append(" — ")
                    .append(branchNames.get(booked.branchId())).append(" (").append(booked.code()).append(')');
        }
        return Reply.personal(text.toString(), false);
    }

    private static Reply noAppointment() {
        return Reply.personal(ChatTexts.NO_APPOINTMENT, true);
    }

    /** Thú nêu tên thì dùng thú đó; không nêu tên mà chỉ có một thú thì dùng thú đó; còn lại hỏi lại (BR-AI-27). */
    private Reply vaccinationSchedule(Long customerId, List<LivingPet> livingPets, List<String> mentionedNames) {
        if (livingPets.isEmpty()) {
            return Reply.personal(ChatTexts.NO_PET, false);
        }
        Set<String> named = mentionedNames.stream().map(PhraseMatcher::norm).collect(Collectors.toCollection(LinkedHashSet::new));
        List<LivingPet> candidates = livingPets.stream().filter(pet -> named.contains(PhraseMatcher.norm(pet.name()))).toList();
        if (candidates.isEmpty()) {
            candidates = livingPets;
        }
        if (candidates.size() > 1) {
            return Reply.needPetChoice(candidates);
        }
        return Reply.personal(vaccinations.summarize(customerId, candidates.get(0).petId()).message(), false);
    }
}
