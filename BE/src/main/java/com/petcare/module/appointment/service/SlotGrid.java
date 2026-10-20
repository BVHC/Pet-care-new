package com.petcare.module.appointment.service;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.petcare.module.branch.api.BranchQueryApi.TimeRange;

/**
 * Khung đặt lịch 30 phút sinh từ các khoảng giờ mở cửa của một ngày (BR-LH-02): bước 30 phút tính từ giờ mở của
 * <b>từng</b> khoảng (mở 08:15 → 08:15, 08:45…), khung nằm trọn trong khoảng (không vắt qua giờ nghỉ giữa ca), khung cuối
 * kết thúc không muộn hơn giờ đóng. Ngày nghỉ / đóng cửa ({@code ranges} rỗng) không có khung.
 * <p>
 * Ngữ nghĩa phải bằng đúng {@code BranchSchedule.isGeneratedSlot} của module branch (quota riêng của khung, BR-LH-03);
 * hai lớp ở hai module nên không dùng chung code — {@code SlotGridParityTest} giữ chúng khớp nhau (docs/adr/0045).
 * <p>
 * Lớp thuần. Khoảng sai (mở ≥ đóng, chồng nhau) là dữ liệu mà BR-CN-02 đã chặn lúc ghi
 * ({@code OpeningHoursMapping}); gặp thì ném {@code IllegalArgumentException} thay vì tự sinh khung từ dữ liệu hỏng.
 */
public final class SlotGrid {

    /** Độ dài một khung (BR-LH-02). */
    public static final int SLOT_MINUTES = 30;

    private static final int STEP_SECONDS = SLOT_MINUTES * 60;

    private SlotGrid() {
    }

    /** Giờ bắt đầu mọi khung của ngày, tăng dần, không trùng; danh sách bất biến. */
    public static List<LocalTime> slotStarts(List<TimeRange> ranges) {
        List<LocalTime> starts = new ArrayList<>();
        for (TimeRange range : sortedValid(ranges)) {
            int close = range.close().toSecondOfDay();
            for (int from = range.open().toSecondOfDay(); from + STEP_SECONDS <= close; from += STEP_SECONDS) {
                starts.add(LocalTime.ofSecondOfDay(from));
            }
        }
        return List.copyOf(starts);
    }

    /** {@code start} đúng là giờ bắt đầu của một khung trong ngày. Giờ có phần lẻ dưới giây không bao giờ là khung. */
    public static boolean isSlotStart(List<TimeRange> ranges, LocalTime start) {
        Objects.requireNonNull(start, "start");
        List<TimeRange> valid = sortedValid(ranges);
        if (start.getNano() != 0) {
            return false;
        }
        int from = start.toSecondOfDay();
        return valid.stream().anyMatch(r -> {
            int open = r.open().toSecondOfDay();
            return from >= open && from + STEP_SECONDS <= r.close().toSecondOfDay() && (from - open) % STEP_SECONDS == 0;
        });
    }

    private static List<TimeRange> sortedValid(List<TimeRange> ranges) {
        Objects.requireNonNull(ranges, "ranges");
        List<TimeRange> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparing(TimeRange::open));
        LocalTime previousClose = null;
        for (TimeRange range : sorted) {
            if (!range.open().isBefore(range.close())) {
                throw new IllegalArgumentException("Khoảng giờ mở cửa không hợp lệ: " + range);
            }
            if (previousClose != null && range.open().isBefore(previousClose)) {
                throw new IllegalArgumentException("Các khoảng giờ mở cửa chồng nhau: " + sorted);
            }
            previousClose = range.close();
        }
        return sorted;
    }
}
