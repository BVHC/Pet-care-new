package com.petcare.module.iam.service;

import com.petcare.module.iam.entity.User;
import com.petcare.module.iam.repository.UserRepository;
import com.petcare.platform.enums.UserRole;
import com.petcare.platform.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserProvisioningServiceImpl implements UserProvisioningService {

    private final UserRepository userRepository;

    /**
     * Propagation.MANDATORY: bắt buộc được gọi trong transaction đang mở của
     * caller (AuthServiceImpl.registerAccount) — tạo User phải cùng atomic
     * với tạo Account, không tự mở transaction riêng.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public User createCustomerProfile(UUID accountId, String fullName) {
        return userRepository.save(new User(accountId, fullName));
    }

    /**
     * Propagation.MANDATORY — cùng lý do như createCustomerProfile (AuthServiceImpl.createStaff).
     * saveAndFlush (không phải save thường) — bắt buộc để INSERT chạy NGAY tại đây thay vì bị
     * Hibernate defer tới lúc transaction commit: organizationId/storeId chỉ được kiểm tra
     * HÌNH DẠNG (RULE-02-02), không kiểm tra tồn tại thật, nên FK violation (fk_users_org/
     * fk_users_store) là cách duy nhất bắt được org/store "ảo" — AuthServiceImpl.createStaff()
     * cần exception này ném ra NGAY trong try/catch của nó, không phải lúc commit (lúc đó đã
     * ra khỏi method, catch ở caller không còn tác dụng, rơi thẳng xuống handleGeneric -> 500).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public User createStaffProfile(UUID accountId, String fullName, UserRole role, UUID organizationId, UUID storeId) {
        User user = new User(accountId, fullName);
        user.setRole(role);
        user.setOrganizationId(organizationId);
        user.setStoreId(storeId);
        return userRepository.saveAndFlush(user);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public User findByAccountId(UUID accountId) {
        return userRepository.findByAccountId(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("User", accountId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public User findById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }
}
