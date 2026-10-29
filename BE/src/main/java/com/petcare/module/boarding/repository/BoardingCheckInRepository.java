package com.petcare.module.boarding.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.petcare.module.boarding.entity.BoardingCheckIn;

/** Tình trạng lúc nhận thú (docs/adr/0085): chỉ INSERT khi nhận, không có luồng sửa. */
public interface BoardingCheckInRepository extends JpaRepository<BoardingCheckIn, Long> {
}
