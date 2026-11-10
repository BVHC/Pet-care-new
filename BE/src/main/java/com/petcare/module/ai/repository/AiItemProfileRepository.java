package com.petcare.module.ai.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.petcare.module.ai.entity.AiItemProfile;

/** Bảng {@code ai_item_profiles} (docs/adr/0130). Chưa có truy vấn: UC94 là việc của T78. */
public interface AiItemProfileRepository extends JpaRepository<AiItemProfile, Long> {
}
