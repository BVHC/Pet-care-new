package com.petcare.module.auth.repository;

import com.petcare.module.auth.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    boolean existsByEmail(String email);

<<<<<<< HEAD
    /** RULE-01-10: phone là UNIQUE ở DB (uq_accounts_phone) nên phải pre-check như email. */
=======
>>>>>>> 8bfc5bd (feat: triển khai module iam)
    boolean existsByPhone(String phone);

    Optional<Account> findByEmail(String email);
}
