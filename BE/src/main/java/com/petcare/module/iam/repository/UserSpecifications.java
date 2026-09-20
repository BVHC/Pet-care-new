package com.petcare.module.iam.repository;

import com.petcare.module.iam.entity.User;
import com.petcare.platform.enums.UserRole;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** docs/api/iam-v1.md C2 (`GET /users`) — lọc động role/storeId/organizationId. */
public final class UserSpecifications {

    private UserSpecifications() {
    }

    public static Specification<User> withFilters(UUID organizationId, UserRole role, UUID storeId) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (organizationId != null) {
                predicates.add(cb.equal(root.get("organizationId"), organizationId));
            }
            if (role != null) {
                predicates.add(cb.equal(root.get("role"), role));
            }
            if (storeId != null) {
                predicates.add(cb.equal(root.get("storeId"), storeId));
            }
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };
    }
}
