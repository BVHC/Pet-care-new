package com.petcare.module.customer.service;

import org.springframework.stereotype.Component;

import com.petcare.module.customer.entity.Customer;
import com.petcare.module.customer.entity.Pet;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.platform.security.AccessScope;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Ai được thấy thú nào (docs/adr/0041, 0006): nhân viên (A06–A08) thấy mọi thú — hồ sơ dùng chung toàn chuỗi, không lọc
 * chi nhánh; khách chỉ thấy thú của chủ hiện tại. Thú của người khác (kể cả của chính khách trước khi chuyển chủ) là "không
 * có" → 404 cùng message với id không tồn tại (customer-v1 D1). Hồ sơ khách còn cờ chờ liên kết thì mọi thao tác thú cưng
 * của khách bị chặn 400 BR-TK-19 (kiểm <b>trước</b> chủ sở hữu). Chỉ gọi bên trong transaction của service.
 */
@Component
class PetAccess {

    /** {@code ownerCustomerId = null} là nhân viên (không giới hạn theo chủ). */
    record Viewer(Long ownerCustomerId) {

        boolean isCustomer() {
            return ownerCustomerId != null;
        }
    }

    private final BranchScope branchScope;
    private final MyCustomerLookup lookup;

    PetAccess(BranchScope branchScope, MyCustomerLookup lookup) {
        this.branchScope = branchScope;
        this.lookup = lookup;
    }

    SecurityPrincipal current() {
        return branchScope.current();
    }

    /** Đọc không khóa. Khách còn cờ chờ liên kết → BR-TK-19. */
    Viewer viewer() {
        SecurityPrincipal principal = branchScope.current();
        if (principal.accessScope() != AccessScope.OWNER) {
            return new Viewer(null);
        }
        Customer owner = lookup.requireOwner(principal.accountId());
        rejectIfLinkPending(owner);
        return new Viewer(owner.getId());
    }

    /** Dưới khóa dòng {@code customers} của chủ (lệnh ghi của khách): kiểm cờ trên bản đọc dưới khóa. */
    Customer lockedOwner() {
        Customer owner = lookup.lockOwner(branchScope.current().accountId());
        rejectIfLinkPending(owner);
        return owner;
    }

    /** Thú của người khác = không có (404 cùng message). */
    void requireVisible(Viewer viewer, Pet pet) {
        if (viewer.isCustomer() && !viewer.ownerCustomerId().equals(pet.getCustomerId())) {
            throw notFound(pet.getId());
        }
    }

    static ResourceNotFoundException notFound(Long petId) {
        return new ResourceNotFoundException("thú cưng", petId);
    }

    private static void rejectIfLinkPending(Customer owner) {
        if (owner.isLinkDecisionPending()) {
            throw new BusinessRuleViolationException("BR-TK-19",
                    "Hồ sơ đang chờ quyết định liên kết; hãy chọn liên kết hoặc 'Không phải tôi' trước khi quản lý thú cưng");
        }
    }
}
