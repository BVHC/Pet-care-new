package com.petcare.module.boarding.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.care.api.NotificationApi;
import com.petcare.module.care.api.NotificationApi.Channel;
import com.petcare.module.care.api.NotificationApi.NotificationRequest;
import com.petcare.module.identity.api.NotificationTemplateCode;
import com.petcare.module.identity.api.Role;
import com.petcare.module.identity.api.StaffDirectoryApi;

/**
 * Thông báo trong app cho BRANCH_MANAGER khi không còn chuồng đúng loại lúc nhận thú (BR-LT-08, Đặt chỗ#5). Chỉ ghi
 * outbox trong transaction của bên gọi. Chi nhánh không còn quản lý hoạt động thì gửi mọi SUPER_MANAGER (cùng cách
 * ADR-0035), để ca không bị im lặng.
 */
@Component
class BoardingManagerNotices {

    /** Màn hình quản lý lưu trú của nhân viên. */
    static final String LINK_URL = "/admin/boarding";

    private static final Logger log = LoggerFactory.getLogger(BoardingManagerNotices.class);

    private final NotificationApi notifications;
    private final StaffDirectoryApi staff;

    BoardingManagerNotices(NotificationApi notifications, StaffDirectoryApi staff) {
        this.notifications = notifications;
        this.staff = staff;
    }

    void enqueueNoKennel(BoardingBooking booking, String petName, String kennelTypeName) {
        List<Long> recipients = staff.findActiveStaffIds(booking.getBranchId(), Role.BRANCH_MANAGER);
        if (recipients.isEmpty()) {
            recipients = staff.findActiveSuperManagerIds();
            log.warn("BOARDING_NO_KENNEL_FALLBACK_SUPER_MANAGER bookingId={} recipients={}", booking.getId(),
                    recipients.size());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ten_thu_cung", petName);
        payload.put("ma_dat_cho", booking.getCode());
        payload.put("ngay_nhan", BoardingClinicCancellationNotices.DATE_FORMAT.format(booking.getCheckInDate()));
        payload.put("loai_chuong", kennelTypeName);
        for (Long accountId : recipients) {
            notifications.enqueue(new NotificationRequest(NotificationTemplateCode.BOARDING_NO_KENNEL_APP,
                    Channel.IN_APP, accountId, null, payload, LINK_URL));
        }
    }
}
