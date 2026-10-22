package com.petcare.module.customer.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.dto.CreatePetRequest;
import com.petcare.module.customer.dto.CustomerDetailResponse;
import com.petcare.module.customer.dto.PetResponse;
import com.petcare.module.customer.dto.PetWriteResultResponse;
import com.petcare.module.customer.dto.UpdatePetRequest;
import com.petcare.module.customer.entity.Customer;
import com.petcare.module.customer.entity.Pet;
import com.petcare.module.customer.mapper.CustomerStaffMapper;
import com.petcare.module.customer.repository.CustomerRepository;
import com.petcare.module.customer.repository.PetRepository;
import com.petcare.module.visit.api.VisitQueryApi;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;

/**
 * UC24 — thêm, xem, sửa thú cưng (customer-v1 #10, #12–16; BR-KH-02, 03, 05; docs/adr/0041). Không audit (convention 08
 * §8.3 chỉ ghi chuyển chủ). Mỗi use case một {@code @Transactional}, không {@code noRollbackFor}.
 * <ul>
 *   <li>Thứ tự khóa {@code customers → pets}: thêm thú khóa dòng {@code customers} của chủ trước, nên tuần tự với UC07
 *       (liên kết kiểm "chưa có thú" dưới khóa rồi xóa hồ sơ online) và với chuyển chủ.</li>
 *   <li>Sửa khóa dòng {@code pets} <b>rồi mới</b> đọc entity (bẫy persistence context, docs/adr/0029 mục 4), kiểm chủ
 *       dưới khóa, nên chuyển chủ xen giữa làm khách cũ nhận 404 thay vì sửa nhầm thú đã sang người khác.</li>
 *   <li>Thứ tự kiểm khi sửa: BR-TK-19 (khách) → 404 → chủ (404) → đã mất (BR-KH-05) → đổi loài khi có bệnh án / mũi tiêm
 *       (BR-KH-03) → ngày sinh ở tương lai (BR-KH-02). Mọi lần từ chối trước lệnh ghi đầu tiên.</li>
 * </ul>
 */
@Service
public class PetService {

    private final PetRepository pets;
    private final CustomerRepository customers;
    private final PetAccess access;
    private final PetViews views;
    private final VisitQueryApi visits;
    private final CustomerStaffMapper customerMapper;
    private final Clock clock;

    public PetService(PetRepository pets, CustomerRepository customers, PetAccess access, PetViews views,
            VisitQueryApi visits, CustomerStaffMapper customerMapper, Clock clock) {
        this.pets = pets;
        this.customers = customers;
        this.access = access;
        this.views = views;
        this.visits = visits;
        this.customerMapper = customerMapper;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- đọc

    @Transactional(readOnly = true)
    public List<PetResponse> listMyPets() {
        PetAccess.Viewer viewer = access.viewer();
        return views.views(pets.findByCustomer(viewer.ownerCustomerId()));
    }

    @Transactional(readOnly = true)
    public PetResponse getPet(Long petId) {
        PetAccess.Viewer viewer = access.viewer();
        Pet pet = pets.findById(petId).orElseThrow(() -> PetAccess.notFound(petId));
        access.requireVisible(viewer, pet);
        return views.view(pet);
    }

    /** customer-v1 #10: hồ sơ + mọi thú (kể cả đã mất), theo mã tăng dần. */
    @Transactional(readOnly = true)
    public CustomerDetailResponse getCustomerDetail(Long customerId) {
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("khách hàng", customerId));
        return new CustomerDetailResponse(customerMapper.toProfile(customer),
                views.views(pets.findByCustomer(customerId)));
    }

    // ---------------------------------------------------------------- thêm

    /** customer-v1 #14. Khóa dòng hồ sơ của khách rồi kiểm cờ chờ liên kết trên bản đọc dưới khóa. */
    @Transactional
    public PetWriteResultResponse createMyPet(CreatePetRequest request) {
        Customer owner = access.lockedOwner();
        return create(owner.getId(), request);
    }

    /** customer-v1 #12: lễ tân thêm thú cho khách bất kỳ (kể cả hồ sơ online còn cờ chờ liên kết). */
    @Transactional
    public PetWriteResultResponse createForCustomer(Long customerId, CreatePetRequest request) {
        customers.findByIdForUpdate(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("khách hàng", customerId));
        return create(customerId, request);
    }

    private PetWriteResultResponse create(Long customerId, CreatePetRequest request) {
        requireNotInFuture(request.birthDate());
        String name = request.name().strip();
        Pet pet = pets.saveAndFlush(Pet.create(customerId, name, request.species(), request.sex(),
                Values.optional(request.breed(), null), request.birthDate(),
                Boolean.TRUE.equals(request.birthDateEstimated()), Values.optional(request.color(), null),
                request.isNeutered(), Values.optional(request.photoUrl(), null)));
        boolean duplicate = pets.existsDuplicateName(customerId, request.species().name(), name, pet.getId());
        return new PetWriteResultResponse(views.view(pet), duplicate);
    }

    // ---------------------------------------------------------------- sửa

    @Transactional
    public PetWriteResultResponse updatePet(Long petId, UpdatePetRequest request) {
        PetAccess.Viewer viewer = access.viewer();
        Pet pet = pets.findByIdForUpdate(petId).orElseThrow(() -> PetAccess.notFound(petId));
        access.requireVisible(viewer, pet);
        pet.requireAlive();
        if (request.species() != null && request.species() != pet.getSpecies()
                && visits.hasMedicalHistory(petId)) {
            throw new BusinessRuleViolationException("BR-KH-03",
                    "Không đổi được loài của thú cưng đã có bệnh án hoặc mũi tiêm");
        }
        requireNotInFuture(request.birthDate());

        String oldName = pet.getName();
        var oldSpecies = pet.getSpecies();
        pet.update(
                Values.required(request.name(), pet.getName()),
                request.species() != null ? request.species() : pet.getSpecies(),
                request.sex() != null ? request.sex() : pet.getSex(),
                Values.optional(request.breed(), pet.getBreed()),
                request.birthDate() != null ? request.birthDate() : pet.getBirthDate(),
                request.birthDateEstimated() != null ? request.birthDateEstimated() : pet.isBirthDateEstimated(),
                Values.optional(request.color(), pet.getColor()),
                request.isNeutered() != null ? request.isNeutered() : pet.getNeutered(),
                Values.optional(request.photoUrl(), pet.getPhotoUrl()));
        pets.flush();

        boolean identityChanged = !oldName.equals(pet.getName()) || oldSpecies != pet.getSpecies();
        boolean duplicate = identityChanged
                && pets.existsDuplicateName(pet.getCustomerId(), pet.getSpecies().name(), pet.getName(), petId);
        return new PetWriteResultResponse(views.view(pet), duplicate);
    }

    /** BR-KH-02: ngày sinh không ở tương lai, theo ngày nghiệp vụ của bean {@code Clock}. */
    private void requireNotInFuture(LocalDate birthDate) {
        if (birthDate != null && birthDate.isAfter(LocalDate.now(clock))) {
            throw new BusinessRuleViolationException("BR-KH-02", "Ngày sinh không được ở tương lai");
        }
    }
}
