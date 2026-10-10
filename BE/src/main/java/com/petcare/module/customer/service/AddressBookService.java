package com.petcare.module.customer.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.customer.dto.AddressRequest;
import com.petcare.module.customer.dto.AddressResponse;
import com.petcare.module.customer.dto.UpdateAddressRequest;
import com.petcare.module.customer.entity.Address;
import com.petcare.module.customer.entity.Customer;
import com.petcare.module.customer.mapper.AddressMapper;
import com.petcare.module.customer.repository.AddressRepository;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.BranchScope;

/**
 * UC06 — sổ địa chỉ của khách ({@code /api/me/addresses}, customer-v1 #3–7; BR-TK-18; docs/adr/0028). Không audit.
 * <ul>
 *   <li>Mọi lệnh ghi khóa dòng {@code customers} của chủ trước (thứ tự {@code customers → addresses}), nên đếm giới
 *       hạn [CFG] và đổi cờ mặc định của cùng một khách luôn chạy tuần tự.</li>
 *   <li>Đổi mặc định: gỡ cờ cũ bằng câu UPDATE chạy ngay ({@link AddressRepository#clearDefault}) <b>trước</b> khi ghi
 *       cờ mới, vì Hibernate flush INSERT trước UPDATE và {@code uq_addresses_default_per_customer} không deferrable.</li>
 *   <li>Sổ đang rỗng thì địa chỉ thêm vào luôn là mặc định (customer-v1 A6). Xóa địa chỉ mặc định khi còn địa chỉ khác →
 *       BR-TK-18; là địa chỉ duy nhất thì xóa được. Xóa cứng (docs/adr/0028).</li>
 *   <li>Địa chỉ không thuộc chủ = không có → 404 cùng message (customer-v1 A8).</li>
 * </ul>
 * Mọi lần từ chối xảy ra trước lệnh ghi đầu tiên. {@code flush()} trước khi map để lỗi ràng buộc / khóa của DB ném ra
 * trong method (→ 409) thay vì lúc commit.
 */
@Service
public class AddressBookService {

    private static final String RESOURCE = "Địa chỉ";

    private final AddressRepository addresses;
    private final MyCustomerLookup lookup;
    private final BranchScope branchScope;
    private final SystemConfigApi configs;
    private final AddressMapper mapper;
    private final Clock clock;

    public AddressBookService(AddressRepository addresses, MyCustomerLookup lookup, BranchScope branchScope,
            SystemConfigApi configs, AddressMapper mapper, Clock clock) {
        this.addresses = addresses;
        this.lookup = lookup;
        this.branchScope = branchScope;
        this.configs = configs;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AddressResponse> listMyAddresses() {
        Customer owner = lookup.requireOwner(branchScope.current().accountId());
        return mapper.toResponses(addresses.findOwnedBy(owner.getId()));
    }

    @Transactional
    public AddressResponse addAddress(AddressRequest request) {
        Customer owner = lookup.lockOwner(branchScope.current().accountId());
        long count = addresses.countOwnedBy(owner.getId());
        int max = configs.getInt(ConfigKey.ADDRESS_MAX_PER_CUSTOMER);
        if (count >= max) {
            throw new BusinessRuleViolationException("BR-TK-18", "Sổ địa chỉ đã đủ " + max + " địa chỉ");
        }

        boolean makeDefault = count == 0 || Boolean.TRUE.equals(request.isDefault());
        if (makeDefault && count > 0) {
            addresses.clearDefault(owner.getId(), Instant.now(clock));
        }
        Address address = addresses.saveAndFlush(Address.create(owner.getId(),
                request.receiverName().strip(),
                request.receiverPhone(),
                request.addressLine().strip(),
                Values.optional(request.ward(), null),
                request.province().strip(),
                makeDefault));
        return mapper.toResponse(address);
    }

    @Transactional
    public AddressResponse updateAddress(Long addressId, UpdateAddressRequest request) {
        Customer owner = lookup.lockOwner(branchScope.current().accountId());
        Address address = findOwned(addressId, owner);
        address.update(
                Values.required(request.receiverName(), address.getReceiverName()),
                Values.required(request.receiverPhone(), address.getReceiverPhone()),
                Values.required(request.addressLine(), address.getAddressLine()),
                Values.optional(request.ward(), address.getWard()),
                Values.required(request.province(), address.getProvince()));
        addresses.flush();
        return mapper.toResponse(address);
    }

    @Transactional
    public void deleteAddress(Long addressId) {
        Customer owner = lookup.lockOwner(branchScope.current().accountId());
        Address address = findOwned(addressId, owner);
        if (address.isDefaultAddress() && addresses.countOwnedBy(owner.getId()) > 1) {
            throw new BusinessRuleViolationException("BR-TK-18",
                    "Không thể xóa địa chỉ mặc định khi còn địa chỉ khác; hãy đặt địa chỉ khác làm mặc định trước");
        }
        addresses.delete(address);
        addresses.flush();
    }

    @Transactional
    public AddressResponse setDefaultAddress(Long addressId) {
        Customer owner = lookup.lockOwner(branchScope.current().accountId());
        Address address = findOwned(addressId, owner);
        if (!address.isDefaultAddress()) {
            addresses.clearDefault(owner.getId(), Instant.now(clock));
            address.markDefault();
            addresses.flush();
        }
        return mapper.toResponse(address);
    }

    private Address findOwned(Long addressId, Customer owner) {
        return addresses.findOwned(addressId, owner.getId())
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, addressId));
    }
}
