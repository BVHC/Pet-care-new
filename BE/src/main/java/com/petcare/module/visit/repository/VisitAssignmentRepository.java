package com.petcare.module.visit.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.petcare.module.visit.entity.VisitAssignment;

/** Bảng {@code visit_assignments} (erd §6, LOG): chỉ thêm mới, không sửa / xóa (docs/adr/0061). */
public interface VisitAssignmentRepository extends JpaRepository<VisitAssignment, Long> {
}
