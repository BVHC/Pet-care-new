package com.petcare.module.sales.service;

import java.util.Optional;
import java.util.Set;

import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.sales.entity.OrderLine;
import com.petcare.module.sales.entity.OrderLineType;
import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.entity.SalesOrder;

/**
 * Quyền thêm / sửa / xóa dòng Order (BR-BH-02, 03 #5), thuần túy: không Spring, không đọc DB. Dùng cho cả guard của
 * {@link OrderLineService} lẫn cờ {@code canEdit} / {@code canDelete} trong response, nên cờ hiển thị luôn khớp hành
 * vi thật. Thứ tự đánh giá cố định (kiểm thử liệt kê toàn bộ ma trận).
 */
final class OrderLinePolicy {

    static final String RECEPTIONIST = "RECEPTIONIST";
    static final String VET = "VET";
    static final String CARETAKER = "CARETAKER";
    private static final Set<String> LINE_EDITORS = Set.of(RECEPTIONIST, VET, CARETAKER);

    /** Lý do từ chối; {@link OrderLineService} đổi thành exception tương ứng. */
    enum Denial {
        /** Order đã PAID / CANCELLED → 409. */
        ORDER_FINISHED,
        /** Dòng không sửa / xóa trực tiếp (VACCINE, BOARDING, DRUG) → 400 BR-BH-02. */
        NOT_DIRECTLY_EDITABLE,
        /** Dòng dịch vụ / thuốc bị khóa khi Order PENDING → 403 BR-BH-02. */
        LOCKED_WHEN_PENDING,
        /** Không phải chủ dòng, hoặc vai trò không được sửa dòng → 403 BR-BH-02. */
        NOT_OWNER
    }

    enum Operation { EDIT, DELETE }

    private OrderLinePolicy() {
    }

    /**
     * Vai trò có thêm được loại dòng này không: lễ tân thêm hàng bán lẻ; VET thêm dịch vụ nhóm Khám/Tiêm; CARETAKER
     * thêm dịch vụ nhóm Thẩm mỹ (BR-BH-02). {@code serviceGroup} chỉ dùng với {@code SERVICE}.
     */
    static boolean mayAdd(String role, OrderLineType type, ServiceGroup serviceGroup) {
        return switch (type) {
            case GOODS -> RECEPTIONIST.equals(role);
            case SERVICE -> (VET.equals(role) && serviceGroup == ServiceGroup.MEDICAL)
                    || (CARETAKER.equals(role) && serviceGroup == ServiceGroup.GROOMING);
            default -> false;
        };
    }

    /** Nhóm dịch vụ mà vai trò thêm / đổi được; {@code null} nếu vai trò không thêm dịch vụ. */
    static ServiceGroup serviceGroupOf(String role) {
        if (VET.equals(role)) {
            return ServiceGroup.MEDICAL;
        }
        return CARETAKER.equals(role) ? ServiceGroup.GROOMING : null;
    }

    /** Có thêm được dòng khi Order ở trạng thái này không: OPEN mọi dòng, PENDING chỉ hàng bán lẻ (03 #5). */
    static boolean orderAcceptsLine(OrderStatus status, OrderLineType type) {
        return status == OrderStatus.OPEN || (status == OrderStatus.PENDING && type == OrderLineType.GOODS);
    }

    /** Lý do từ chối sửa / xóa dòng, rỗng nếu được phép. */
    static Optional<Denial> evaluate(Operation operation, String role, long accountId, SalesOrder order,
            OrderLine line) {
        OrderStatus status = order.getStatus();
        if (status == OrderStatus.PAID || status == OrderStatus.CANCELLED) {
            return Optional.of(Denial.ORDER_FINISHED);
        }
        switch (line.getLineType()) {
            case VACCINE, BOARDING -> {
                return Optional.of(Denial.NOT_DIRECTLY_EDITABLE);
            }
            case DRUG -> {
                // Thuốc sửa / xóa qua đơn thuốc; PENDING thì đã khóa (hợp đồng PATCH 403, DELETE 400)
                if (operation == Operation.EDIT && status == OrderStatus.PENDING) {
                    return Optional.of(Denial.LOCKED_WHEN_PENDING);
                }
                return Optional.of(Denial.NOT_DIRECTLY_EDITABLE);
            }
            case SERVICE -> {
                if (status == OrderStatus.PENDING) {
                    return Optional.of(Denial.LOCKED_WHEN_PENDING);
                }
                boolean owner = LINE_EDITORS.contains(role) && line.getOwnerAccountId().equals(accountId);
                return owner ? Optional.empty() : Optional.of(Denial.NOT_OWNER);
            }
            default -> {
                // GOODS: mọi lễ tân thao tác được cả khi OPEN lẫn PENDING (BR-BH-02)
                return RECEPTIONIST.equals(role) ? Optional.empty() : Optional.of(Denial.NOT_OWNER);
            }
        }
    }

    static boolean canEdit(String role, long accountId, SalesOrder order, OrderLine line) {
        return evaluate(Operation.EDIT, role, accountId, order, line).isEmpty();
    }

    static boolean canDelete(String role, long accountId, SalesOrder order, OrderLine line) {
        return evaluate(Operation.DELETE, role, accountId, order, line).isEmpty();
    }
}
