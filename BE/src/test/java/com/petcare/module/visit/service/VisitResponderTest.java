package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.customer.api.CustomerQueryApi.CustomerContact;
import com.petcare.module.customer.api.PetQueryApi;
import com.petcare.module.customer.api.PetQueryApi.PetSummary;
import com.petcare.module.customer.api.Species;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.module.sales.api.OrderQueryApi;
import com.petcare.module.visit.dto.VisitResponse;
import com.petcare.module.visit.entity.PriorityClass;
import com.petcare.module.visit.entity.Visit;
import com.petcare.module.visit.mapper.VisitMapper;

/**
 * {@link VisitResponder} (docs/adr/0060 E4, F-F2): ghép theo id chứ không theo thứ tự trả về, mỗi module một lời gọi cho
 * mỗi lô ≤ 100 id phân biệt theo thứ tự Order → thú → khách → nhân viên, thiếu / trùng id → 500, không bao giờ đưa tên
 * null vào mapper thật.
 */
class VisitResponderTest {

    private static final Instant T = Instant.parse("2026-10-20T02:00:00.123456Z");

    private final OrderQueryApi orders = mock(OrderQueryApi.class);
    private final PetQueryApi pets = mock(PetQueryApi.class);
    private final CustomerQueryApi customers = mock(CustomerQueryApi.class);
    private final StaffDirectoryApi staff = mock(StaffDirectoryApi.class);
    private final VisitResponder responder = new VisitResponder(orders, pets, customers, staff,
            Mappers.getMapper(VisitMapper.class));

    @BeforeEach
    void echoEveryRequestedId() {
        // Mặc định: trả đúng các id được hỏi, theo thứ tự ĐẢO NGƯỢC (API hứa "thứ tự tùy ý").
        doAnswer(call -> {
            Map<Long, Long> map = new HashMap<>();
            for (Long id : ids(call.getArgument(0))) {
                map.put(id, 7000 + id);
            }
            return map;
        }).when(orders).findOrderIdsByVisitIds(anyCollection());
        doAnswer(call -> reversed(ids(call.getArgument(0))).stream()
                .map(id -> new PetSummary(id, 1L, "Thú-" + id, id % 2 == 0 ? Species.DOG : Species.CAT, null, null,
                        BigDecimal.ONE))
                .toList()).when(pets).findPets(anyCollection());
        doAnswer(call -> reversed(ids(call.getArgument(0))).stream()
                .map(id -> new CustomerContact(id, "Khách-" + id, "0900000000", "k@x.vn", null, false))
                .toList()).when(customers).findContacts(anyCollection());
        doAnswer(call -> reversed(ids(call.getArgument(0))).stream()
                .map(id -> new StaffSummary(id, "NV-" + id, Role.VET, 3L, true, true))
                .toList()).when(staff).findStaffByIds(anyCollection());
    }

    @Test
    void batchResultsInAnyOrderStillMatchByIdAndNamesNeverReachTheMapperAsNull() {
        Visit first = visit(901L, 11L, 21L, 31L);
        Visit second = visit(902L, 12L, 22L, null);

        List<VisitResponse> responses = responder.all(List.of(first, second));

        assertThat(responses).extracting(VisitResponse::visitId).containsExactly(901L, 902L);
        VisitResponse a = responses.get(0);
        assertThat(a.petName()).isEqualTo("Thú-11");
        assertThat(a.species()).isEqualTo(Species.CAT);
        assertThat(a.customerName()).isEqualTo("Khách-21");
        assertThat(a.assigneeName()).isEqualTo("NV-31");
        assertThat(a.orderId()).isEqualTo(7901L);
        VisitResponse b = responses.get(1);
        assertThat(b.petName()).isEqualTo("Thú-12");
        assertThat(b.species()).isEqualTo(Species.DOG);
        assertThat(b.customerName()).isEqualTo("Khách-22");
        assertThat(b.assigneeName()).isNull();
        assertThat(b.orderId()).isEqualTo(7902L);
    }

    @Test
    void batchOrderIsOrdersPetsCustomersStaff() {
        responder.all(List.of(visit(901L, 11L, 21L, 31L)));

        InOrder order = inOrder(orders, pets, customers, staff);
        order.verify(orders).findOrderIdsByVisitIds(List.of(901L));
        order.verify(pets).findPets(List.of(11L));
        order.verify(customers).findContacts(List.of(21L));
        order.verify(staff).findStaffByIds(List.of(31L));
    }

    @Test
    void sharedIdsAreAskedOnceAndUnassignedPageCallsNoStaffLookup() {
        responder.all(List.of(visit(901L, 11L, 21L, null), visit(902L, 11L, 21L, null)));

        verify(pets).findPets(List.of(11L));
        verify(customers).findContacts(List.of(21L));
        verifyNoInteractions(staff);
    }

    @Test
    void emptyRowsCallNoModule() {
        assertThat(responder.all(List.of())).isEmpty();

        verifyNoInteractions(orders, pets, customers, staff);
    }

    @Test
    void moreThanOneHundredIdsAreSplit() {
        List<Visit> rows = new ArrayList<>();
        for (long i = 1; i <= 101; i++) {
            rows.add(visit(1000 + i, 2000 + i, 3000 + i, 4000 + i));
        }

        assertThat(responder.all(rows)).hasSize(101);

        ArgumentCaptor<Collection<Long>> orderBatches = captor();
        ArgumentCaptor<Collection<Long>> petBatches = captor();
        ArgumentCaptor<Collection<Long>> customerBatches = captor();
        ArgumentCaptor<Collection<Long>> staffBatches = captor();
        verify(orders, times(2)).findOrderIdsByVisitIds(orderBatches.capture());
        verify(pets, times(2)).findPets(petBatches.capture());
        verify(customers, times(2)).findContacts(customerBatches.capture());
        verify(staff, times(2)).findStaffByIds(staffBatches.capture());
        for (ArgumentCaptor<Collection<Long>> batches : List.of(orderBatches, petBatches, customerBatches,
                staffBatches)) {
            assertThat(batches.getAllValues()).extracting(Collection::size).containsExactly(100, 1);
        }
    }

    @Test
    void missingPetCustomerStaffOrOrderIs500() {
        Visit visit = visit(901L, 11L, 21L, 31L);

        doReturn(List.of()).when(pets).findPets(anyCollection());
        assertThatThrownBy(() -> responder.one(visit)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("thú cưng");
        echoEveryRequestedId();

        doReturn(List.of()).when(customers).findContacts(anyCollection());
        assertThatThrownBy(() -> responder.one(visit)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hồ sơ khách");
        echoEveryRequestedId();

        doReturn(List.of()).when(staff).findStaffByIds(anyCollection());
        assertThatThrownBy(() -> responder.one(visit)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nhân viên");
        echoEveryRequestedId();

        doReturn(Map.of()).when(orders).findOrderIdsByVisitIds(anyCollection());
        assertThatThrownBy(() -> responder.one(visit)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Order");
    }

    @Test
    void duplicateIdInBatchResultIs500() {
        PetSummary pet = new PetSummary(11L, 1L, "Thú-11", Species.CAT, null, null, BigDecimal.ONE);
        doReturn(List.of(pet, pet)).when(pets).findPets(anyCollection());

        assertThatThrownBy(() -> responder.one(visit(901L, 11L, 21L, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("trùng");
    }

    private static Visit visit(long id, long petId, long customerId, Long assigneeId) {
        Visit visit = Visit.checkIn("TN-2610-" + id, 3L, customerId, petId, null, ServiceGroup.MEDICAL,
                PriorityClass.WALK_IN, T, 70L, T);
        ReflectionTestUtils.setField(visit, "id", id);
        ReflectionTestUtils.setField(visit, "assigneeId", assigneeId);
        return visit;
    }

    @SuppressWarnings("unchecked")
    private static List<Long> ids(Object argument) {
        return new ArrayList<>((Collection<Long>) argument);
    }

    private static List<Long> reversed(List<Long> ids) {
        List<Long> copy = new ArrayList<>(ids);
        java.util.Collections.reverse(copy);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Collection<Long>> captor() {
        return ArgumentCaptor.forClass(Collection.class);
    }
}
