package com.petcare.module.visit.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.sales.api.OrderQueryApi;
import com.petcare.module.visit.dto.VisitResponse;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.mapper.VisitMapper;

/**
 * Lượt → response {@code Visit} cho hàng đợi và chi tiết (visit-v1 #2–4; docs/adr/0060). Lớp hỗ trợ thuần (không phải
 * bean, khuôn {@code AppointmentResponder}): chạy trong transaction readOnly của service (ADR-0020).
 * <ul>
 * <li>Mỗi module một lời gọi cho mỗi lô ≤ {@value #BATCH} id phân biệt, theo thứ tự cố định Order → thú → khách → nhân
 * viên (docs/adr/0060 F-F2: IT chèn thay đổi ở lời gọi Order để chứng minh cả response dùng một snapshot).</li>
 * <li>Ghép theo id, không theo thứ tự trả về. Thiếu id (FK + không xóa cứng bảo đảm có) hoặc trùng id trong kết quả →
 * {@link IllegalStateException} (500), không trả tên / {@code orderId} null.</li>
 * <li>Lượt chưa gán thì không gọi identity.</li>
 * </ul>
 */
final class VisitResponder {

    static final int BATCH = 100;

    private final OrderQueryApi orders;
    private final PetQueryApi pets;
    private final CustomerQueryApi customers;
    private final StaffDirectoryApi staff;
    private final VisitMapper mapper;

    VisitResponder(OrderQueryApi orders, PetQueryApi pets, CustomerQueryApi customers, StaffDirectoryApi staff,
            VisitMapper mapper) {
        this.orders = orders;
        this.pets = pets;
        this.customers = customers;
        this.staff = staff;
        this.mapper = mapper;
    }

    VisitResponse one(Visit visit) {
        return all(List.of(visit)).get(0);
    }

    /** Giữ nguyên thứ tự của {@code rows}; rỗng → rỗng, không gọi module nào. */
    List<VisitResponse> all(List<Visit> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> orderIds = new HashMap<>();
        for (List<Long> batch : batches(ids(rows, Visit::getId))) {
            orders.findOrderIdsByVisitIds(batch).forEach((visitId, orderId) -> {
                if (orderIds.putIfAbsent(visitId, orderId) != null) {
                    throw new IllegalStateException("Kết quả tra theo lô có id trùng");
                }
            });
        }
        Map<Long, PetSummary> petById = lookup(ids(rows, Visit::getPetId), pets::findPets, PetSummary::petId);
        Map<Long, CustomerContact> customerById = lookup(ids(rows, Visit::getCustomerId), customers::findContacts,
                CustomerContact::customerId);
        Map<Long, StaffSummary> staffById = lookup(ids(rows, Visit::getAssigneeId), staff::findStaffByIds,
                StaffSummary::accountId);

        List<VisitResponse> responses = new ArrayList<>(rows.size());
        for (Visit visit : rows) {
            PetSummary pet = require(petById, visit.getPetId(), "thú cưng", visit);
            CustomerContact customer = require(customerById, visit.getCustomerId(), "hồ sơ khách", visit);
            String assigneeName = visit.getAssigneeId() == null ? null
                    : require(staffById, visit.getAssigneeId(), "nhân viên", visit).fullName();
            Long orderId = require(orderIds, visit.getId(), "Order", visit);
            responses.add(mapper.toResponse(visit,
                    new VisitMapper.Parties(customer.fullName(), pet.name(), pet.species(), assigneeName), orderId));
        }
        return responses;
    }

    /** Id phân biệt, bỏ null (lượt chưa gán), giữ thứ tự gặp. */
    private static List<Long> ids(List<Visit> rows, Function<Visit, Long> id) {
        return List.copyOf(rows.stream().map(id).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    private static List<List<Long>> batches(List<Long> ids) {
        List<List<Long>> batches = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += BATCH) {
            batches.add(List.copyOf(ids.subList(from, Math.min(from + BATCH, ids.size()))));
        }
        return batches;
    }

    private static <T> Map<Long, T> lookup(List<Long> ids, Function<List<Long>, List<T>> call, Function<T, Long> id) {
        Map<Long, T> found = new HashMap<>();
        for (List<Long> batch : batches(ids)) {
            for (T row : call.apply(batch)) {
                if (found.putIfAbsent(id.apply(row), row) != null) {
                    throw new IllegalStateException("Kết quả tra theo lô có id trùng");
                }
            }
        }
        return found;
    }

    private static <T> T require(Map<Long, T> found, Long id, String what, Visit visit) {
        T value = found.get(id);
        if (value == null) {
            throw new IllegalStateException("Lượt tiếp nhận #" + visit.getId() + " trỏ tới " + what
                    + " không đọc được: #" + id);
        }
        return value;
    }
}
