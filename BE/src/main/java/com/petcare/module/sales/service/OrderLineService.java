package com.petcare.module.sales.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.branch.api.BranchQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi;
import com.petcare.module.catalog.api.CatalogQueryApi.ProductInfo;
import com.petcare.module.catalog.api.CatalogQueryApi.ServiceInfo;
import com.petcare.module.catalog.api.ProductType;
import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.inventory.api.StockQueryApi;
import com.petcare.module.sales.api.OrderSource;
import com.petcare.module.sales.dto.AddOrderLineRequest;
import com.petcare.module.sales.dto.AddOrderLineRequest.AddableLineType;
import com.petcare.module.sales.dto.AddOrderLineResult;
import com.petcare.module.sales.dto.OrderResponse;
import com.petcare.module.sales.dto.StockWarningResponse;
import com.petcare.module.sales.dto.UpdateOrderLineRequest;
import com.petcare.module.sales.entity.OrderLine;
import com.petcare.module.sales.entity.OrderLineType;
import com.petcare.module.sales.entity.OrderStatus;
import com.petcare.module.sales.entity.SalesOrder;
import com.petcare.module.sales.exception.InvalidOrderLineRequestException;
import com.petcare.module.sales.exception.OrderLineAccessDeniedException;
import com.petcare.module.sales.repository.SalesOrderRepository;
import com.petcare.module.sales.service.OrderLinePolicy.Denial;
import com.petcare.module.sales.service.OrderLinePolicy.Operation;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

import lombok.RequiredArgsConstructor;

/**
 * Thêm / sửa / xóa dòng Order qua REST (sales-v1 #4–6, UC66, UC67; BR-BH-02, 03, 04, BR-SP-01, BR-TN-01; docs/adr/0044).
 * Mỗi use case là một {@code @Transactional}, không {@code noRollbackFor}. Thứ tự kiểm: hình dạng yêu cầu 400 → vai trò
 * 403 → <b>khóa dòng Order</b> (404) → chi nhánh 403 → người phụ trách Visit 403 → trạng thái 409 → danh mục / rule 400.
 * Không bao giờ khóa {@code visits} (Visit đọc không khóa), nên thứ tự khóa luôn là {@code orders → products → lô kho}.
 */
@Service
@RequiredArgsConstructor
public class OrderLineService {

    private final SalesOrderRepository orders;
    private final CatalogQueryApi catalog;
    private final BranchQueryApi branches;
    private final StockQueryApi stock;
    private final VisitQueryApi visits;
    private final OrderAssembler assembler;
    private final BranchScope branchScope;

    // ---------------------------------------------------------------- thêm dòng

    @Transactional
    public AddOrderLineResult addLine(Long orderId, AddOrderLineRequest request) {
        OrderLineType type = requireShape(request);
        SecurityPrincipal me = branchScope.current();
        requireRoleMayAddType(me.role(), type);

        SalesOrder order = lockOwned(orderId);
        OrderLine line = type == OrderLineType.SERVICE
                ? newServiceLine(order, me, request)
                : newGoodsLine(order, me, request);
        order.addLine(line);
        orders.flush();

        StockWarningResponse warning = type == OrderLineType.GOODS ? stockWarning(order, line) : null;
        return new AddOrderLineResult(assembler.assemble(order, me), line.getId(), warning);
    }

    private static OrderLineType requireShape(AddOrderLineRequest request) {
        if (request.lineType() == AddableLineType.SERVICE) {
            if (request.serviceId() == null || request.productId() != null) {
                throw new InvalidOrderLineRequestException("Dòng dịch vụ cần serviceId và không có productId");
            }
            return OrderLineType.SERVICE;
        }
        if (request.productId() == null || request.serviceId() != null) {
            throw new InvalidOrderLineRequestException("Dòng hàng cần productId và không có serviceId");
        }
        return OrderLineType.GOODS;
    }

    /** Lễ tân chỉ thêm hàng bán lẻ; VET / CARETAKER chỉ thêm dịch vụ; vai trò khác không thêm gì (BR-BH-02). */
    private static void requireRoleMayAddType(String role, OrderLineType type) {
        boolean ok = type == OrderLineType.GOODS
                ? OrderLinePolicy.RECEPTIONIST.equals(role)
                : OrderLinePolicy.serviceGroupOf(role) != null;
        if (!ok) {
            throw new OrderLineAccessDeniedException("add:" + type, "role:" + role);
        }
    }

    private OrderLine newServiceLine(SalesOrder order, SecurityPrincipal me, AddOrderLineRequest request) {
        ServiceInfo service = catalog.findService(request.serviceId())
                .orElseThrow(() -> new ResourceNotFoundException("dịch vụ", request.serviceId()));
        if (!OrderLinePolicy.mayAdd(me.role(), OrderLineType.SERVICE, service.group())) {
            throw new OrderLineAccessDeniedException("service:" + service.group(), "role:" + me.role());
        }
        requireAssignedVisit(order, me);
        requireAcceptsLine(order, OrderLineType.SERVICE);
        requireServiceUsable(order, service);
        return OrderLine.of(OrderLineType.SERVICE, service.serviceId(), null, service.name(), request.quantity(),
                service.price(), false, me.accountId(), me.accountId());
    }

    private OrderLine newGoodsLine(SalesOrder order, SecurityPrincipal me, AddOrderLineRequest request) {
        requireAcceptsLine(order, OrderLineType.GOODS);
        ProductInfo product = catalog.findProductForShare(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("sản phẩm", request.productId()));
        if (!product.active()) {
            throw new InvalidOrderLineRequestException("Sản phẩm #" + product.productId() + " đã ngừng kinh doanh");
        }
        if (product.prescription()) {
            throw new BusinessRuleViolationException("BR-SP-01",
                    "Thuốc kê đơn chỉ được đưa vào Order qua kê đơn của bác sĩ");
        }
        if (product.type() == ProductType.VACCINE) {
            throw new BusinessRuleViolationException("BR-KB-04",
                    "Vaccine chỉ được đưa vào Order khi ghi nhận mũi tiêm");
        }
        return OrderLine.of(OrderLineType.GOODS, null, product.productId(), product.name(), request.quantity(),
                product.price(), false, me.accountId(), me.accountId());
    }

    /** VET / CARETAKER chỉ thêm dịch vụ cho Visit mình phụ trách (BR-BH-02). */
    private void requireAssignedVisit(SalesOrder order, SecurityPrincipal me) {
        boolean assigned = order.getSource() == OrderSource.VISIT && order.getVisitId() != null
                && visits.findVisit(order.getVisitId())
                        .map(v -> me.accountId().equals(v.assigneeId())).orElse(false);
        if (!assigned) {
            throw new OrderLineAccessDeniedException("visit-assignee", "account:" + me.accountId());
        }
    }

    private static void requireAcceptsLine(SalesOrder order, OrderLineType type) {
        if (!OrderLinePolicy.orderAcceptsLine(order.getStatus(), type)) {
            throw new InvalidStateTransitionException("đơn hàng (không thêm được dòng này ở trạng thái hiện tại)",
                    order.getStatus().name(), OrderStatus.OPEN.name());
        }
    }

    private void requireServiceUsable(SalesOrder order, ServiceInfo service) {
        if (!service.active()) {
            throw new InvalidOrderLineRequestException("Dịch vụ #" + service.serviceId() + " đã ngừng kinh doanh");
        }
        if (!branches.isServiceEnabled(order.getBranchId(), service.serviceId())) {
            throw new InvalidOrderLineRequestException(
                    "Dịch vụ #" + service.serviceId() + " không được bật tại chi nhánh của Order");
        }
    }

    /** Cảnh báo, không chặn (BR-BH-04): tổng số lượng sản phẩm trong Order so với tồn khả dụng của chi nhánh. */
    private StockWarningResponse stockWarning(SalesOrder order, OrderLine added) {
        Long productId = added.getProductId();
        int requested = order.getLines().stream()
                .filter(l -> productId.equals(l.getProductId()))
                .mapToInt(OrderLine::getQuantity).sum();
        int available = stock.availableQuantity(order.getBranchId(), productId);
        return available < requested ? new StockWarningResponse(productId, requested, available) : null;
    }

    // ---------------------------------------------------------------- sửa dòng

    @Transactional
    public OrderResponse updateLine(Long orderId, Long lineId, UpdateOrderLineRequest request) {
        if (request.quantity() == null && request.serviceId() == null) {
            throw new InvalidOrderLineRequestException("Cần gửi quantity hoặc serviceId");
        }
        SecurityPrincipal me = branchScope.current();
        SalesOrder order = lockOwned(orderId);
        OrderLine line = requireLine(order, lineId);
        requireAllowed(Operation.EDIT, me, order, line);

        if (request.quantity() != null) {
            if (request.quantity() > line.getQuantity()) {
                throw new InvalidOrderLineRequestException(
                        "Chỉ được giảm số lượng; muốn thêm hãy thêm dòng mới để được cảnh báo tồn");
            }
            line.changeQuantity(request.quantity());
        }
        if (request.serviceId() != null) {
            replaceService(order, line, me, request.serviceId());
        }
        order.recalculateTotal();
        orders.flush();
        return assembler.assemble(order, me);
    }

    /** Đổi dịch vụ của dòng tự sinh (hợp đồng A5, BR-TN-01): đúng nhóm của vai trò, giá chụp lại lúc đổi. */
    private void replaceService(SalesOrder order, OrderLine line, SecurityPrincipal me, Long serviceId) {
        if (line.getLineType() != OrderLineType.SERVICE || !line.isAutoGenerated()
                || order.getSource() != OrderSource.VISIT) {
            throw new InvalidOrderLineRequestException("Chỉ đổi được dịch vụ của dòng tự sinh khi tiếp nhận");
        }
        ServiceInfo service = catalog.findService(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("dịch vụ", serviceId));
        ServiceGroup group = OrderLinePolicy.serviceGroupOf(me.role());
        if (group == null || service.group() != group) {
            throw new OrderLineAccessDeniedException("service:" + service.group(), "role:" + me.role());
        }
        requireServiceUsable(order, service);
        line.replaceService(service.serviceId(), service.name(), service.price());
    }

    // ---------------------------------------------------------------- xóa dòng

    @Transactional
    public OrderResponse deleteLine(Long orderId, Long lineId) {
        SecurityPrincipal me = branchScope.current();
        SalesOrder order = lockOwned(orderId);
        OrderLine line = requireLine(order, lineId);
        requireAllowed(Operation.DELETE, me, order, line);
        order.removeLine(line);
        orders.flush();
        return assembler.assemble(order, me);
    }

    // ---------------------------------------------------------------- dùng chung

    /** Khóa dòng Order trước khi đọc (404), rồi kiểm chi nhánh (403). */
    private SalesOrder lockOwned(Long orderId) {
        SalesOrder order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("đơn hàng", orderId));
        branchScope.check(order.getBranchId());
        return order;
    }

    private static OrderLine requireLine(SalesOrder order, Long lineId) {
        return order.findLine(lineId).orElseThrow(() -> new ResourceNotFoundException("dòng đơn hàng", lineId));
    }

    private static void requireAllowed(Operation operation, SecurityPrincipal me, SalesOrder order, OrderLine line) {
        Optional<Denial> denial = OrderLinePolicy.evaluate(operation, me.role(), me.accountId(), order, line);
        if (denial.isEmpty()) {
            return;
        }
        switch (denial.get()) {
            case ORDER_FINISHED -> throw new InvalidStateTransitionException(
                    "đơn hàng (đã kết thúc, không sửa dòng được)", order.getStatus().name(), OrderStatus.OPEN.name());
            case NOT_DIRECTLY_EDITABLE -> throw new BusinessRuleViolationException("BR-BH-02", switch (line.getLineType()) {
                case VACCINE -> "Dòng vaccine không xóa trực tiếp, hãy xóa mũi tiêm";
                case DRUG -> "Dòng thuốc chỉ sửa hoặc xóa qua đơn thuốc";
                default -> "Dòng này sinh tự động, không sửa trực tiếp";
            });
            case LOCKED_WHEN_PENDING -> throw new OrderLineAccessDeniedException("line-unlocked", "order:PENDING");
            case NOT_OWNER -> throw new OrderLineAccessDeniedException("owner:" + line.getOwnerAccountId(),
                    "account:" + me.accountId());
        }
    }
}
