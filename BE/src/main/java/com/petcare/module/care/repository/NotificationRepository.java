package com.petcare.module.care.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.petcare.module.care.entity.Notification;

/** Bảng {@code notifications}. Hiện chỉ worker ST20 IN_APP ghi (docs/adr/0014); truy vấn cho UC88 thêm ở task sau. */
public interface NotificationRepository extends JpaRepository<Notification, Long> {
}
