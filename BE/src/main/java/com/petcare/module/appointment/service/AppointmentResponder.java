package com.petcare.module.appointment.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.petcare.module.appointment.dto.AppointmentResponse;
import com.petcare.module.appointment.entity.Appointment;
import com.petcare.module.appointment.mapper.AppointmentMapper;
import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.module.visit.api.VisitQueryApi;

/**
 * Lịch hẹn → response {@code Appointment} cho xem (#3, #4, docs/adr/0048) và đổi khung / hủy (#5, #6, docs/adr/0049):
 * tên hiển thị lấy theo lô (mỗi module một lần, theo id khác nhau), cờ theo người xem với cùng {@code now}. Lớp hỗ trợ
 * thuần (không phải bean): service dựng nó từ các dependency đã inject; chạy trong transaction của service (ADR-0020).
 */
final class AppointmentResponder {

    /** Người xem, quyết định hai cờ (và phạm vi ở service). */
    enum Viewer {
        CUSTOMER, RECEPTIONIST, READ_ONLY_STAFF
    }

    private final CustomerQueryApi customers;
    private final PetQueryApi pets;
    private final BranchQueryApi branches;
    private final CatalogQueryApi catalog;
    private final VisitQueryApi visits;
    private final SystemConfigApi configs;
    private final AppointmentMapper mapper;

    AppointmentResponder(CustomerQueryApi customers, PetQueryApi pets, BranchQueryApi branches, CatalogQueryApi catalog,
            VisitQueryApi visits, SystemConfigApi configs, AppointmentMapper mapper) {
        this.customers = customers;
        this.pets = pets;
        this.branches = branches;
        this.catalog = catalog;
        this.visits = visits;
        this.configs = configs;
        this.mapper = mapper;
    }

    /** Một lịch (đổi khung, hủy, chi tiết). */
    AppointmentResponse one(Appointment appointment, Viewer viewer, Instant now) {
        return forRows(List.of(appointment), viewer, now).apply(appointment);
    }

    /**
     * Hàm map một lịch của {@code rows} sang response. {@code rows} rỗng → không gọi module nào. Thiếu một id (FK bảo đảm
     * có) hoặc trùng id trong kết quả → {@link IllegalStateException} (500), không trả tên null.
     */
    Function<Appointment, AppointmentResponse> forRows(List<Appointment> rows, Viewer viewer, Instant now) {
        if (rows.isEmpty()) {
            return appointment -> {
                throw new IllegalStateException("Không có lịch hẹn nào để map");
            };
        }
        Map<Long, String> petNames = index(pets.findPets(ids(rows, Appointment::getPetId)),
                PetSummary::petId, PetSummary::name);
        Map<Long, String> customerNames = index(customers.findContacts(ids(rows, Appointment::getCustomerId)),
                CustomerContact::customerId, CustomerContact::fullName);
        Map<Long, String> serviceNames = index(catalog.findServices(ids(rows, Appointment::getServiceId)),
                ServiceInfo::serviceId, ServiceInfo::name);
        // visitId theo lô cho mọi trạng thái: lịch CHECKED_IN / COMPLETED và lịch CANCELLED do hủy lượt (Lịch hẹn#8) có
        // Visit; lịch chưa từng check-in không có khóa trong map → null (nợ D028, docs/adr/0058).
        Map<Long, Long> visitIds = visits.findVisitIdsByAppointmentIds(ids(rows, Appointment::getId));
        Map<Long, String> branchNames = new HashMap<>();
        for (Long branchId : ids(rows, Appointment::getBranchId)) {
            branchNames.put(branchId, branches.findBranch(branchId)
                    .orElseThrow(() -> new IllegalStateException("Không đọc được chi nhánh #" + branchId))
                    .name());
        }

        boolean canAct = viewer != Viewer.READ_ONLY_STAFF;
        int maxReschedules = canAct ? configs.getInt(ConfigKey.APPOINTMENT_MAX_RESCHEDULES) : 0;
        int leadHours = canAct ? configs.getInt(ConfigKey.APPOINTMENT_RESCHEDULE_MIN_LEAD_HOURS) : 0;
        return appointment -> {
            AppointmentMapper.Names names = new AppointmentMapper.Names(
                    require(customerNames, appointment.getCustomerId(), "hồ sơ khách", appointment),
                    require(petNames, appointment.getPetId(), "thú cưng", appointment),
                    require(branchNames, appointment.getBranchId(), "chi nhánh", appointment),
                    require(serviceNames, appointment.getServiceId(), "dịch vụ", appointment));
            AppointmentActions.Flags flags = canAct
                    ? AppointmentActions.forViewer(appointment, viewer == Viewer.CUSTOMER, now, maxReschedules,
                            leadHours)
                    : AppointmentActions.Flags.NONE;
            return mapper.toResponse(appointment, names, flags, visitIds.get(appointment.getId()));
        };
    }

    private static Set<Long> ids(List<Appointment> rows, Function<Appointment, Long> id) {
        return rows.stream().map(id).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static <T> Map<Long, String> index(List<T> found, Function<T, Long> id, Function<T, String> name) {
        return found.stream().collect(Collectors.toMap(id, name, (first, second) -> {
            throw new IllegalStateException("Kết quả tra theo lô có id trùng");
        }));
    }

    private static String require(Map<Long, String> names, Long id, String what, Appointment appointment) {
        String name = names.get(id);
        if (name == null) {
            throw new IllegalStateException("Lịch hẹn #" + appointment.getId() + " trỏ tới " + what
                    + " không đọc được: #" + id);
        }
        return name;
    }
}
