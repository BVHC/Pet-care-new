package com.petcare.module.sales.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.sales.entity.OrderLine;
import com.petcare.module.sales.entity.OrderLineType;
import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.entity.SalesOrder;
import com.petcare.module.sales.service.OrderLinePolicy.Denial;
import com.petcare.module.sales.service.OrderLinePolicy.Operation;

/**
 * Ma trận quyền dòng Order của BR-BH-02 và 03 #5. Hai lớp kiểm: các ví dụ nêu thẳng trong rule (đọc được như tài liệu)
 * và bản liệt kê toàn bộ tổ hợp vai trò × trạng thái × loại dòng × chủ dòng × thao tác với kỳ vọng viết độc lập.
 */
class OrderLinePolicyTest {

    private static final long ME = 7L;
    private static final long SOMEONE_ELSE = 8L;
    private static final List<String> ROLES =
            List.of("RECEPTIONIST", "VET", "CARETAKER", "BRANCH_MANAGER", "ADMIN", "SUPER_MANAGER", "CUSTOMER");

    private static SalesOrder order(OrderStatus status) {
        SalesOrder order = SalesOrder.openForVisit("DH-2610-00001", 1L, 2L, 3L, 4L);
        ReflectionTestUtils.setField(order, "status", status);
        return order;
    }

    private static OrderLine line(OrderLineType type, long owner) {
        boolean service = type == OrderLineType.SERVICE || type == OrderLineType.BOARDING;
        return OrderLine.of(type, service ? 10L : null, service ? null : 20L, "x", 1, 1000,
                false, owner, owner);
    }

    private static Optional<Denial> edit(String role, OrderStatus status, OrderLineType type, long owner) {
        return OrderLinePolicy.evaluate(Operation.EDIT, role, ME, order(status), line(type, owner));
    }

    private static Optional<Denial> delete(String role, OrderStatus status, OrderLineType type, long owner) {
        return OrderLinePolicy.evaluate(Operation.DELETE, role, ME, order(status), line(type, owner));
    }

    // ---------------------------------------------------------------- ví dụ nêu trong rule

    @Test
    void ownerVetEditsAndDeletesOwnServiceLineWhileOpen() {
        assertThat(edit("VET", OrderStatus.OPEN, OrderLineType.SERVICE, ME)).isEmpty();
        assertThat(delete("VET", OrderStatus.OPEN, OrderLineType.SERVICE, ME)).isEmpty();
    }

    @Test
    void anotherVetCannotTouchTheLineEvenInTheSameBranch() {
        assertThat(delete("VET", OrderStatus.OPEN, OrderLineType.SERVICE, SOMEONE_ELSE)).contains(Denial.NOT_OWNER);
        assertThat(edit("VET", OrderStatus.OPEN, OrderLineType.SERVICE, SOMEONE_ELSE)).contains(Denial.NOT_OWNER);
    }

    @Test
    void anyReceptionistRemovesRetailGoodsWhileOpenOrPending() {
        for (OrderStatus status : List.of(OrderStatus.OPEN, OrderStatus.PENDING)) {
            assertThat(delete("RECEPTIONIST", status, OrderLineType.GOODS, SOMEONE_ELSE)).as(status.name()).isEmpty();
            assertThat(edit("RECEPTIONIST", status, OrderLineType.GOODS, SOMEONE_ELSE)).as(status.name()).isEmpty();
        }
    }

    @Test
    void serviceLinesAreLockedOnceThePendingOrderWaitsForPayment() {
        assertThat(delete("VET", OrderStatus.PENDING, OrderLineType.SERVICE, ME)).contains(Denial.LOCKED_WHEN_PENDING);
        assertThat(edit("RECEPTIONIST", OrderStatus.PENDING, OrderLineType.SERVICE, ME))
                .contains(Denial.LOCKED_WHEN_PENDING);
    }

    @Test
    void vaccineLinesAreNeverRemovedDirectlyInAnyStatus() {
        for (OrderStatus status : List.of(OrderStatus.OPEN, OrderStatus.PENDING)) {
            assertThat(delete("VET", status, OrderLineType.VACCINE, ME)).as(status.name())
                    .contains(Denial.NOT_DIRECTLY_EDITABLE);
            assertThat(delete("RECEPTIONIST", status, OrderLineType.VACCINE, ME)).as(status.name())
                    .contains(Denial.NOT_DIRECTLY_EDITABLE);
        }
    }

    @Test
    void drugLinesGoThroughThePrescription() {
        assertThat(delete("VET", OrderStatus.OPEN, OrderLineType.DRUG, ME)).contains(Denial.NOT_DIRECTLY_EDITABLE);
        assertThat(edit("VET", OrderStatus.OPEN, OrderLineType.DRUG, ME)).contains(Denial.NOT_DIRECTLY_EDITABLE);
        assertThat(edit("VET", OrderStatus.PENDING, OrderLineType.DRUG, ME)).contains(Denial.LOCKED_WHEN_PENDING);
        assertThat(delete("VET", OrderStatus.PENDING, OrderLineType.DRUG, ME)).contains(Denial.NOT_DIRECTLY_EDITABLE);
    }

    @Test
    void finishedOrdersAreClosedForEveryone() {
        for (OrderStatus status : List.of(OrderStatus.PAID, OrderStatus.CANCELLED)) {
            for (OrderLineType type : OrderLineType.values()) {
                assertThat(delete("RECEPTIONIST", status, type, ME)).as(status + " " + type)
                        .contains(Denial.ORDER_FINISHED);
            }
        }
    }

    @Test
    void managerAndOtherRolesNeverEditLines() {
        for (String role : List.of("BRANCH_MANAGER", "ADMIN", "SUPER_MANAGER", "CUSTOMER")) {
            assertThat(delete(role, OrderStatus.OPEN, OrderLineType.GOODS, ME)).as(role).contains(Denial.NOT_OWNER);
            assertThat(delete(role, OrderStatus.OPEN, OrderLineType.SERVICE, ME)).as(role).contains(Denial.NOT_OWNER);
        }
    }

    @Test
    void whoMayAddWhat() {
        assertThat(OrderLinePolicy.mayAdd("RECEPTIONIST", OrderLineType.GOODS, null)).isTrue();
        assertThat(OrderLinePolicy.mayAdd("RECEPTIONIST", OrderLineType.SERVICE, ServiceGroup.MEDICAL)).isFalse();
        assertThat(OrderLinePolicy.mayAdd("VET", OrderLineType.SERVICE, ServiceGroup.MEDICAL)).isTrue();
        assertThat(OrderLinePolicy.mayAdd("VET", OrderLineType.SERVICE, ServiceGroup.GROOMING)).isFalse();
        assertThat(OrderLinePolicy.mayAdd("VET", OrderLineType.SERVICE, ServiceGroup.BOARDING)).isFalse();
        assertThat(OrderLinePolicy.mayAdd("VET", OrderLineType.GOODS, null)).isFalse();
        assertThat(OrderLinePolicy.mayAdd("CARETAKER", OrderLineType.SERVICE, ServiceGroup.GROOMING)).isTrue();
        assertThat(OrderLinePolicy.mayAdd("CARETAKER", OrderLineType.SERVICE, ServiceGroup.MEDICAL)).isFalse();
        assertThat(OrderLinePolicy.mayAdd("CARETAKER", OrderLineType.GOODS, null)).isFalse();
        for (OrderLineType derived : List.of(OrderLineType.DRUG, OrderLineType.VACCINE, OrderLineType.BOARDING)) {
            for (String role : ROLES) {
                assertThat(OrderLinePolicy.mayAdd(role, derived, ServiceGroup.MEDICAL)).as(role + " " + derived)
                        .isFalse();
            }
        }
    }

    @Test
    void serviceGroupOfRole() {
        assertThat(OrderLinePolicy.serviceGroupOf("VET")).isEqualTo(ServiceGroup.MEDICAL);
        assertThat(OrderLinePolicy.serviceGroupOf("CARETAKER")).isEqualTo(ServiceGroup.GROOMING);
        assertThat(OrderLinePolicy.serviceGroupOf("RECEPTIONIST")).isNull();
        assertThat(OrderLinePolicy.serviceGroupOf("BRANCH_MANAGER")).isNull();
    }

    @Test
    void pendingOrderAcceptsRetailGoodsOnly() {
        for (OrderLineType type : OrderLineType.values()) {
            assertThat(OrderLinePolicy.orderAcceptsLine(OrderStatus.OPEN, type)).as("OPEN " + type).isTrue();
            assertThat(OrderLinePolicy.orderAcceptsLine(OrderStatus.PENDING, type)).as("PENDING " + type)
                    .isEqualTo(type == OrderLineType.GOODS);
            assertThat(OrderLinePolicy.orderAcceptsLine(OrderStatus.PAID, type)).isFalse();
            assertThat(OrderLinePolicy.orderAcceptsLine(OrderStatus.CANCELLED, type)).isFalse();
        }
    }

    // ---------------------------------------------------------------- toàn bộ tổ hợp

    @TestFactory
    List<DynamicTest> everyCombinationMatchesTheIndependentTable() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Operation operation : Operation.values()) {
            for (String role : ROLES) {
                for (OrderStatus status : OrderStatus.values()) {
                    for (OrderLineType type : OrderLineType.values()) {
                        for (long owner : new long[] {ME, SOMEONE_ELSE}) {
                            String name = operation + " " + role + " " + status + " " + type + " owner="
                                    + (owner == ME ? "me" : "other");
                            tests.add(DynamicTest.dynamicTest(name, () -> assertThat(OrderLinePolicy.evaluate(
                                    operation, role, ME, order(status), line(type, owner)))
                                    .isEqualTo(expected(operation, role, status, type, owner == ME))));
                        }
                    }
                }
            }
        }
        assertThat(tests).hasSize(2 * 7 * 4 * 5 * 2);
        return tests;
    }

    /** Kỳ vọng viết độc lập với {@code evaluate}: đi từ trạng thái Order, rồi loại dòng, rồi người thao tác. */
    private static Optional<Denial> expected(Operation operation, String role, OrderStatus status, OrderLineType type,
            boolean iOwn) {
        if (status == OrderStatus.PAID || status == OrderStatus.CANCELLED) {
            return Optional.of(Denial.ORDER_FINISHED);
        }
        boolean pending = status == OrderStatus.PENDING;
        if (type == OrderLineType.VACCINE || type == OrderLineType.BOARDING) {
            return Optional.of(Denial.NOT_DIRECTLY_EDITABLE);
        }
        if (type == OrderLineType.DRUG) {
            return Optional.of(pending && operation == Operation.EDIT
                    ? Denial.LOCKED_WHEN_PENDING : Denial.NOT_DIRECTLY_EDITABLE);
        }
        boolean staffThatEditsLines = role.equals("RECEPTIONIST") || role.equals("VET") || role.equals("CARETAKER");
        if (type == OrderLineType.SERVICE) {
            if (pending) {
                return Optional.of(Denial.LOCKED_WHEN_PENDING);
            }
            return staffThatEditsLines && iOwn ? Optional.empty() : Optional.of(Denial.NOT_OWNER);
        }
        return role.equals("RECEPTIONIST") ? Optional.empty() : Optional.of(Denial.NOT_OWNER);
    }

    @Test
    void viewerFlagsFollowTheSameRules() {
        SalesOrder open = order(OrderStatus.OPEN);
        OrderLine mine = line(OrderLineType.SERVICE, ME);
        OrderLine drug = line(OrderLineType.DRUG, ME);

        assertThat(OrderLinePolicy.canEdit("VET", ME, open, mine)).isTrue();
        assertThat(OrderLinePolicy.canDelete("VET", ME, open, mine)).isTrue();
        assertThat(OrderLinePolicy.canEdit("VET", ME, open, drug)).isFalse();
        assertThat(OrderLinePolicy.canDelete("VET", ME, open, drug)).isFalse();
        assertThat(OrderLinePolicy.canEdit("BRANCH_MANAGER", ME, open, mine)).isFalse();
    }
}
