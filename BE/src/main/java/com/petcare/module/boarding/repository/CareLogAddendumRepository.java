package com.petcare.module.boarding.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.petcare.module.boarding.entity.CareLogAddendum;

/** Bản bổ sung nhật ký (docs/adr/0085): chỉ thêm. */
public interface CareLogAddendumRepository extends JpaRepository<CareLogAddendum, Long> {

    List<CareLogAddendum> findByCareLogIdInOrderByIdAsc(Collection<Long> careLogIds);
}
