package com.petcare.module.customer.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.customer.api.PetDeceasedEvent;
import com.petcare.module.customer.api.PetOwnerTransferredEvent;
import com.petcare.module.customer.dto.MarkDeceasedRequest;
import com.petcare.module.customer.dto.PetResponse;
import com.petcare.module.customer.dto.TransferOwnerRequest;
import com.petcare.module.customer.entity.Pet;
import com.petcare.module.customer.repository.CustomerRepository;
import com.petcare.module.customer.repository.PetRepository;
import com.petcare.module.customer.repository.WeightRecordRepository;
import com.petcare.module.sales.api.OrderQueryApi;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.platform.audit.AuditEntry;
import com.petcare.platform.audit.AuditRecorder;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ConcurrencyConflictException;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.exception.ResourceNotFoundException;

/**
 * UC24, UC26 — đánh dấu đã mất, xóa thú chưa giao dịch, chuyển chủ (customer-v1 #17–19; BR-KH-05, 06, 08;
 * docs/adr/0041). Mỗi use case một {@code @Transactional}, không {@code noRollbackFor}, không bắt lỗi nào.
 * <ul>
 *   <li><b>Khóa trước, đọc sau.</b> Dòng {@code pets} (và {@code customers} khi chuyển chủ, theo mã tăng dần — thứ tự
 *       {@code customers → pets}) bị khóa {@code FOR NO KEY UPDATE} trước mọi câu hỏi tới visit / boarding / sales; bên
 *       chèn dòng trỏ tới thú phải khóa chia sẻ qua {@code PetQueryApi.findPetForShare} (06 §4) nên xếp hàng sau chứ
 *       không chen vào giữa lúc customer vừa kiểm xong.</li>
 *   <li><b>Đã mất → 409</b> (bấm lần hai, không hoàn tác) chạy trước các kiểm khác của đánh dấu đã mất; với xóa và chuyển
 *       chủ thú đã mất là 400 BR-KH-05 (chỉ đọc, A2).</li>
 *   <li><b>Sự kiện</b> {@code PetDeceasedEvent} / {@code PetOwnerTransferredEvent} phát đồng bộ trong cùng transaction,
 *       sau {@code flush()}: listener ném lỗi thì rollback cả việc đổi thú (06 §3). Chưa module nào cài listener — nợ ghi
 *       ở docs/dept.</li>
 *   <li><b>Audit</b> chỉ chuyển chủ ({@code PET_OWNER_TRANSFERRED}, BR-KH-08); các thao tác khác convention 08 §8.3 không
 *       liệt kê.</li>
 * </ul>
 */
@Service
public class PetLifecycleService {

    private final PetRepository pets;
    private final CustomerRepository customers;
    private final WeightRecordRepository weights;
    private final PetAccess access;
    private final PetViews views;
    private final VisitQueryApi visits;
    private final BoardingQueryApi boardings;
    private final OrderQueryApi orders;
    private final ApplicationEventPublisher events;
    private final AuditRecorder audit;
    private final Clock clock;

    public PetLifecycleService(PetRepository pets, CustomerRepository customers, WeightRecordRepository weights,
            PetAccess access, PetViews views, VisitQueryApi visits, BoardingQueryApi boardings, OrderQueryApi orders,
            ApplicationEventPublisher events, AuditRecorder audit, Clock clock) {
        this.pets = pets;
        this.customers = customers;
        this.weights = weights;
        this.access = access;
        this.views = views;
        this.visits = visits;
        this.boardings = boardings;
        this.orders = orders;
        this.events = events;
        this.audit = audit;
        this.clock = clock;
    }

    /** customer-v1 #17. */
    @Transactional
    public PetResponse markDeceased(Long petId, MarkDeceasedRequest request) {
        PetAccess.Viewer viewer = access.viewer();
        Pet pet = lockVisible(petId, viewer);
        if (pet.isDeceased()) {
            throw new InvalidStateTransitionException("thú cưng", "đã mất", "đã mất");
        }
        LocalDate deceasedOn = request.deceasedOn();
        if (deceasedOn.isAfter(LocalDate.now(clock))) {
            throw new BusinessRuleViolationException("BR-KH-05", "Ngày mất không được ở tương lai");
        }
        if (pet.getBirthDate() != null && deceasedOn.isBefore(pet.getBirthDate())) {
            throw new BusinessRuleViolationException("BR-KH-05", "Ngày mất không được trước ngày sinh");
        }
        ArrayList<String> blockers = new ArrayList<>();
        if (visits.hasOpenVisit(petId)) {
            blockers.add("đang có lượt khám chưa kết thúc");
        }
        if (boardings.isPetInStay(petId)) {
            blockers.add("đang lưu trú (kết thúc lưu trú trước theo BR-LT-12)");
        }
        if (!blockers.isEmpty()) {
            throw new BusinessRuleViolationException("BR-KH-05",
                    "Không thể đánh dấu đã mất: thú cưng " + String.join("; ", blockers));
        }

        pet.markDeceased(deceasedOn);
        pets.flush();
        events.publishEvent(new PetDeceasedEvent(petId, pet.getCustomerId(), deceasedOn, access.current().accountId()));
        return views.view(pet);
    }

    /** customer-v1 #18. Xóa cứng; chỉ thú chưa phát sinh lịch hẹn, đặt chỗ, Visit (Order luôn kèm Visit / đặt chỗ). */
    @Transactional
    public void deletePet(Long petId) {
        PetAccess.Viewer viewer = access.viewer();
        Pet pet = lockVisible(petId, viewer);
        pet.requireAlive();
        if (views.hasTransactions(petId)) {
            throw new BusinessRuleViolationException("BR-KH-06",
                    "Thú cưng đã có lịch hẹn, đặt chỗ, Visit hoặc Order nên không xóa được; hãy đánh dấu đã mất");
        }
        weights.deleteByPet(petId); // chỉ có thể là cân do chủ tự khai (nguồn OWNER)
        pets.delete(pet);
        pets.flush();
    }

    /** customer-v1 #19. Lễ tân; Order và hóa đơn cũ ở lại với chủ cũ, lịch sử khám / tiêm / lưu trú đi theo thú. */
    @Transactional
    public PetResponse transferOwner(Long petId, TransferOwnerRequest request) {
        Long newOwnerId = request.newCustomerId();
        Long oldOwnerId = pets.findCustomerIdById(petId).orElseThrow(() -> PetAccess.notFound(petId));
        if (newOwnerId.equals(oldOwnerId)) {
            throw new BusinessRuleViolationException("BR-KH-08", "Chủ mới trùng chủ hiện tại của thú cưng");
        }
        // customers theo mã tăng dần rồi tới pets: thứ tự khóa chung của module (docs/adr/0041).
        lockCustomersAscending(oldOwnerId, newOwnerId);
        Pet pet = pets.findByIdForUpdate(petId).orElseThrow(() -> PetAccess.notFound(petId));
        if (!oldOwnerId.equals(pet.getCustomerId())) {
            throw new ConcurrencyConflictException("thú cưng", petId);
        }
        pet.requireAlive();
        ArrayList<String> blockers = new ArrayList<>();
        if (visits.hasOpenVisit(petId)) {
            blockers.add("đang có lượt khám chưa kết thúc");
        }
        if (orders.hasPendingOrderForPet(petId)) {
            blockers.add("có Order chờ thanh toán");
        }
        if (boardings.isPetInStay(petId)) {
            blockers.add("đang lưu trú");
        }
        if (!blockers.isEmpty()) {
            throw new BusinessRuleViolationException("BR-KH-08",
                    "Không thể chuyển chủ: thú cưng " + String.join("; ", blockers));
        }

        pet.transferTo(newOwnerId);
        pets.flush();
        Long actor = access.current().accountId();
        events.publishEvent(new PetOwnerTransferredEvent(petId, oldOwnerId, newOwnerId, actor));
        audit.record(AuditEntry.of(CustomerAuditActions.PET_OWNER_TRANSFERRED).entity("pets", petId)
                .before(Map.of("customerId", oldOwnerId)).after(Map.of("customerId", newOwnerId)));
        return views.view(pet);
    }

    /** Khóa dòng thú rồi mới đọc entity (docs/adr/0029 mục 4), kiểm chủ dưới khóa; thú của người khác = không có. */
    private Pet lockVisible(Long petId, PetAccess.Viewer viewer) {
        Pet pet = pets.findByIdForUpdate(petId).orElseThrow(() -> PetAccess.notFound(petId));
        access.requireVisible(viewer, pet);
        return pet;
    }

    private void lockCustomersAscending(Long first, Long second) {
        Long low = Math.min(first, second);
        Long high = Math.max(first, second);
        lockCustomer(low);
        lockCustomer(high);
    }

    private void lockCustomer(Long customerId) {
        customers.findByIdForUpdate(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("khách hàng", customerId));
    }
}
