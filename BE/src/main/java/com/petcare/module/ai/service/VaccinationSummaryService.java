package com.petcare.module.ai.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.petcare.module.ai.chat.ChatTexts;
import com.petcare.module.ai.dto.VaccinationItem;
import com.petcare.module.ai.dto.VaccinationStatus;
import com.petcare.module.ai.dto.VaccinationSummary;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProtocolDose;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.visit.api.VaccinationQueryApi;
import com.petcare.module.visit.api.VaccinationQueryApi.DoseRecord;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;

/**
 * Tra lịch tiêm tất định (BR-AI-11, UC91, docs/adr/0132): với mỗi loại vaccine có trong phác đồ đang dùng của loài, lấy mũi
 * mới nhất chưa bị thay thế rồi xếp vào một trong năm trạng thái. Câu {@code message} sinh từ mẫu, không qua LLM.
 * <p>
 * Quyền sở hữu kiểm lại <b>mỗi lần gọi</b> (không cache) vì module {@code ai} không nghe {@code PetOwnerTransferredEvent}:
 * thú của người khác, của chủ cũ, không tồn tại, hoặc tài khoản chưa có hồ sơ khách (BR-TK-19) đều là 404 cùng một
 * message; kiểm chủ chạy TRƯỚC kiểm "đã mất" nên thú mất của người khác vẫn là 404, không lộ sự tồn tại (ADR-0006).
 * Ngưỡng {@code DUE_SOON} đọc từ [CFG] {@code reminder.vaccine_days_before} ở mỗi lần gọi, cùng biên với cửa sổ nhắc ST04
 * (hôm nay và hôm nay + N đều tính).
 */
@Service
public class VaccinationSummaryService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final PetQueryApi pets;
    private final VaccinationQueryApi doses;
    private final CatalogQueryApi catalog;
    private final CustomerQueryApi customers;
    private final SystemConfigApi config;
    private final Clock clock;

    public VaccinationSummaryService(PetQueryApi pets, VaccinationQueryApi doses, CatalogQueryApi catalog,
            CustomerQueryApi customers, SystemConfigApi config, Clock clock) {
        this.pets = pets;
        this.doses = doses;
        this.catalog = catalog;
        this.customers = customers;
        this.config = config;
        this.clock = clock;
    }

    /** ai-v1 #3: khách lấy từ token. */
    public VaccinationSummary summarizeForAccount(Long accountId, Long petId) {
        return summarize(customers.findCustomerIdByAccountId(accountId).orElse(null), petId);
    }

    /** {@code customerId} null (chưa có hồ sơ khách) thì không sở hữu thú nào: 404. */
    public VaccinationSummary summarize(Long customerId, Long petId) {
        PetSummary pet = pets.findPet(petId).filter(p -> customerId != null && customerId.equals(p.customerId()))
                .orElseThrow(() -> new ResourceNotFoundException("thú cưng", petId));
        if (pet.deceased()) {
            throw new BusinessRuleViolationException("BR-KH-05", "Thú cưng đã mất, không tra được lịch tiêm");
        }
        LocalDate today = LocalDate.now(clock);
        int soonDays = config.getInt(ConfigKey.REMINDER_VACCINE_DAYS_BEFORE);

        Map<Long, List<ProtocolDose>> protocol = catalog.listActiveProtocols(pet.species()).stream()
                .collect(Collectors.groupingBy(ProtocolDose::vaccineTypeId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, DoseRecord> latest = doses.findDoseHistory(petId).stream().filter(dose -> !dose.superseded())
                .collect(Collectors.toMap(DoseRecord::vaccineTypeId, Function.identity(), latestOf()));
        Map<Long, String> names = catalog.findVaccineTypeNames(protocol.keySet());

        List<VaccinationItem> items = protocol.entrySet().stream()
                .map(entry -> item(entry.getKey(), names.getOrDefault(entry.getKey(), "Vaccine #" + entry.getKey()),
                        entry.getValue(), latest.get(entry.getKey()), pet, today, soonDays))
                .toList();
        return new VaccinationSummary(petId, pet.name(), items, message(pet.name(), items), ChatTexts.DISCLAIMER);
    }

    /** Mũi tiêm sau cùng của một loại: ngày tiêm lớn hơn thắng, cùng ngày thì mũi ghi sau (id lớn hơn). */
    private static BinaryOperator<DoseRecord> latestOf() {
        return BinaryOperator.maxBy(Comparator.comparing(DoseRecord::administeredOn).thenComparing(DoseRecord::vaccinationId));
    }

    private static VaccinationItem item(Long vaccineTypeId, String name, List<ProtocolDose> rows, DoseRecord last,
            PetSummary pet, LocalDate today, int soonDays) {
        if (last == null) {
            int firstDoseWeeks = rows.stream().min(Comparator.comparingInt(ProtocolDose::doseNumber)).orElseThrow()
                    .minAgeWeeks();
            boolean tooYoung = pet.birthDate() != null && ChronoUnit.WEEKS.between(pet.birthDate(), today) < firstDoseWeeks;
            return new VaccinationItem(vaccineTypeId, name,
                    tooYoung ? VaccinationStatus.NOT_YET_ELIGIBLE : VaccinationStatus.NOT_STARTED, null, null, null, null,
                    firstDoseWeeks);
        }
        Integer nextDose = rows.stream().anyMatch(row -> row.doseNumber() == last.doseNumber() + 1)
                ? last.doseNumber() + 1 : null;
        return new VaccinationItem(vaccineTypeId, name, statusOf(last.nextDueDate(), today, soonDays), last.doseNumber(),
                last.administeredOn(), nextDose, last.nextDueDate(), null);
    }

    private static VaccinationStatus statusOf(LocalDate nextDue, LocalDate today, int soonDays) {
        if (nextDue == null) {
            return VaccinationStatus.UP_TO_DATE;
        }
        if (nextDue.isBefore(today)) {
            return VaccinationStatus.OVERDUE;
        }
        return nextDue.isAfter(today.plusDays(soonDays)) ? VaccinationStatus.UP_TO_DATE : VaccinationStatus.DUE_SOON;
    }

    private static String message(String petName, List<VaccinationItem> items) {
        if (items.isEmpty()) {
            return "Chưa có phác đồ tiêm cho loài của " + petName + " trong danh mục.";
        }
        return "Lịch tiêm của " + petName + " (chỉ tính các mũi ghi nhận tại hệ thống):\n"
                + items.stream().map(VaccinationSummaryService::line).collect(Collectors.joining("\n"));
    }

    private static String line(VaccinationItem item) {
        String name = "- " + item.vaccineName() + ": ";
        String done = item.lastDoseNumber() == null ? ""
                : "đã tiêm mũi " + item.lastDoseNumber() + " ngày " + DAY.format(item.lastAdministeredOn());
        return switch (item.status()) {
            case NOT_YET_ELIGIBLE -> name + "bé còn nhỏ, mũi đầu tiên từ " + item.firstDoseFromAgeWeeks() + " tuần tuổi.";
            case NOT_STARTED -> name + "chưa tiêm tại hệ thống, mũi đầu tiên từ " + item.firstDoseFromAgeWeeks()
                    + " tuần tuổi.";
            case UP_TO_DATE -> name + "còn hạn, " + done
                    + (item.nextDueDate() == null ? "." : "; mũi tiếp theo hạn " + DAY.format(item.nextDueDate()) + ".");
            case DUE_SOON -> name + "sắp đến hạn mũi tiếp theo vào " + DAY.format(item.nextDueDate()) + " (" + done + ").";
            case OVERDUE -> name + "đã quá hạn mũi tiếp theo từ " + DAY.format(item.nextDueDate()) + " (" + done + ").";
        };
    }
}
