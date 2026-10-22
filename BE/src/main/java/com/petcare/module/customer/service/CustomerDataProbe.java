package com.petcare.module.customer.service;

import org.springframework.stereotype.Component;

import com.petcare.module.appointment.api.AppointmentQueryApi;
import com.petcare.module.boarding.api.BoardingQueryApi;
import com.petcare.module.content.api.FeedbackQueryApi;
import com.petcare.module.customer.repository.PetRepository;
import com.petcare.module.sales.api.OrderQueryApi;

/**
 * BR-TK-19: hồ sơ đã phát sinh dữ liệu chưa — thú cưng, lịch hẹn, đặt chỗ, Order, feedback. Dùng chung cho
 * {@code CustomerApiService} (kiểm lại dưới khóa) và {@code CustomerQueryService} (đọc không khóa).
 */
@Component
class CustomerDataProbe {

    private final PetRepository pets;
    private final AppointmentQueryApi appointments;
    private final BoardingQueryApi boardings;
    private final OrderQueryApi orders;
    private final FeedbackQueryApi feedbacks;

    CustomerDataProbe(PetRepository pets, AppointmentQueryApi appointments, BoardingQueryApi boardings,
            OrderQueryApi orders, FeedbackQueryApi feedbacks) {
        this.pets = pets;
        this.appointments = appointments;
        this.boardings = boardings;
        this.orders = orders;
        this.feedbacks = feedbacks;
    }

    boolean hasData(Long customerId) {
        return pets.existsByCustomer(customerId) || appointments.existsByCustomer(customerId)
                || boardings.existsByCustomer(customerId) || orders.existsByCustomer(customerId)
                || feedbacks.existsByCustomer(customerId);
    }
}
