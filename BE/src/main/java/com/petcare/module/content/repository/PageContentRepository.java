package com.petcare.module.content.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.content.entity.PageContent;

import jakarta.persistence.LockModeType;

public interface PageContentRepository extends JpaRepository<PageContent, String> {

    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PageContent p WHERE p.key = :key")
    Optional<PageContent> findByKeyForUpdate(@Param("key") String key);
}
