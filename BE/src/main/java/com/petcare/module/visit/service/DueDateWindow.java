package com.petcare.module.visit.service;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Cửa sổ nhắc của ST04 (BR-TB-01, BR-TB-06; docs/adr/0082): ngày đến hạn được nhắc khi {@code from ≤ ngày ≤ to}, với
 * {@code from = hôm nay} và {@code to = hôm nay + daysBefore} theo giờ Việt Nam. Cả hai cận đều tính: đúng
 * {@code daysBefore} ngày trước hạn là đến lượt nhắc, và ngày đến hạn trùng hôm nay vẫn còn nhắc được. Ngày đã qua thì
 * không nhắc nữa (tái chủng quá hạn đã có BR-TB-04). Lớp thuần.
 *
 * @param from ngày sớm nhất được nhắc (hôm nay)
 * @param to   ngày muộn nhất được nhắc
 */
public record DueDateWindow(LocalDate from, LocalDate to) {

    public DueDateWindow {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("Cửa sổ nhắc rỗng: from=" + from + " to=" + to);
        }
    }

    public static DueDateWindow at(LocalDate today, int daysBefore) {
        Objects.requireNonNull(today, "today");
        if (daysBefore <= 0) {
            throw new IllegalArgumentException("Số ngày nhắc phải dương: daysBefore=" + daysBefore);
        }
        return new DueDateWindow(today, today.plusDays(daysBefore));
    }

    public boolean contains(LocalDate dueDate) {
        return !dueDate.isBefore(from) && !dueDate.isAfter(to);
    }
}
