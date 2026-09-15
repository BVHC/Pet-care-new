package com.petcare.module.iam.repository;

import com.petcare.module.iam.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

/**
 * {@link JpaSpecificationExecutor} — lọc động role/storeId/organizationId cho
 * {@code GET /users} (docs/api/iam-v1.md C2, Q7 pagination/filter PROPOSED)
 * mà không cần tổ hợp nhiều derived-query method.
 */
public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    Optional<User> findByAccountId(UUID accountId);
}
