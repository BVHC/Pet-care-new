package com.petcare.module.branch.schedule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.petcare.module.branch.api.BranchQueryApi.TimeRange;

/**
 * Lịch mở cửa của một chi nhánh: các phiên bản giờ mở cửa theo ngày hiệu lực (BR-CN-02, BR-CN-04) và ngày nghỉ
 * (BR-CN-03). Lớp thuần, không phụ thuộc Spring hay DB, để kiểm thử mọi biên giờ ở một chỗ; dùng chung cho
 * {@code BranchQueryApi} và cho việc tính lịch hẹn / đặt chỗ bị ảnh hưởng khi đổi giờ hoặc thêm ngày nghỉ.
 * Bất biến: mọi {@code with*} trả đối tượng mới.
 */
public final class BranchSchedule {

    /** Khung giờ đặt lịch dài 30 phút (BR-LH-02). */
    public static final int SLOT_MINUTES = 30;

    /** Số ngày tối đa {@link #nextOpeningStart} nhìn tới; chi nhánh không có giờ nào thì dừng thay vì lặp vô hạn. */
    static final int HORIZON_DAYS = 366;

    /**
     * Một phiên bản giờ mở cửa. {@code days} khóa theo thứ ISO (1 = thứ Hai … 7 = Chủ nhật); thứ vắng mặt hoặc
     * danh sách rỗng là ngày nghỉ cố định hằng tuần.
     */
    public record Version(LocalDate effectiveFrom, Map<Integer, List<TimeRange>> days) {

        public List<TimeRange> rangesOn(LocalDate date) {
            List<TimeRange> ranges = days.getOrDefault(date.getDayOfWeek().getValue(), List.of());
            List<TimeRange> sorted = new ArrayList<>(ranges);
            sorted.sort(Comparator.comparing(TimeRange::open));
            return sorted;
        }
    }

    private final TreeMap<LocalDate, Version> versions = new TreeMap<>();
    private final Set<LocalDate> holidays = new TreeSet<>();

    public BranchSchedule(Collection<Version> versions, Collection<LocalDate> holidays) {
        versions.forEach(v -> this.versions.put(v.effectiveFrom(), v));
        this.holidays.addAll(holidays);
    }

    /** Thêm phiên bản; cùng ngày hiệu lực thì thay bản cũ (branch-v1 mục B, đặt giờ mở cửa). */
    public BranchSchedule withVersion(Version version) {
        List<Version> all = new ArrayList<>(versions.values());
        all.removeIf(v -> v.effectiveFrom().equals(version.effectiveFrom()));
        all.add(version);
        return new BranchSchedule(all, holidays);
    }

    public BranchSchedule withHoliday(LocalDate date) {
        List<LocalDate> all = new ArrayList<>(holidays);
        all.add(date);
        return new BranchSchedule(versions.values(), all);
    }

    public boolean isHoliday(LocalDate date) {
        return holidays.contains(date);
    }

    /**
     * Các khoảng giờ mở cửa của {@code date}, sắp theo giờ mở: phiên bản có ngày hiệu lực lớn nhất không quá
     * {@code date}. Rỗng nếu là ngày nghỉ cố định, ngày nghỉ, hoặc chưa có phiên bản nào áp dụng.
     */
    public List<TimeRange> rangesOn(LocalDate date) {
        if (isHoliday(date)) {
            return List.of();
        }
        Map.Entry<LocalDate, Version> applicable = versions.floorEntry(date);
        return applicable == null ? List.of() : applicable.getValue().rangesOn(date);
    }

    public boolean isOpenOn(LocalDate date) {
        return !rangesOn(date).isEmpty();
    }

    /** Giờ đóng cửa loại trừ: đúng giờ đóng là đã đóng. */
    public boolean isOpenAt(LocalDateTime at) {
        LocalTime time = at.toLocalTime();
        return rangesOn(at.toLocalDate()).stream()
                .anyMatch(r -> !time.isBefore(r.open()) && time.isBefore(r.close()));
    }

    /** Giờ bắt đầu khoảng mở cửa đầu tiên <b>sau</b> {@code after} (không tính đúng {@code after}). */
    public Optional<LocalDateTime> nextOpeningStart(LocalDateTime after) {
        LocalDate day = after.toLocalDate();
        for (int i = 0; i <= HORIZON_DAYS; i++) {
            LocalDate date = day.plusDays(i);
            for (TimeRange range : rangesOn(date)) {
                LocalDateTime start = date.atTime(range.open());
                if (start.isAfter(after)) {
                    return Optional.of(start);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Khung 30 phút bắt đầu ở {@code start} nằm trọn trong một khoảng mở cửa (BR-LH-02): không vắt qua giờ nghỉ
     * giữa ca, khung cuối kết thúc không muộn hơn giờ đóng.
     */
    public boolean slotFits(LocalDate date, LocalTime start) {
        int from = start.toSecondOfDay();
        int to = from + SLOT_MINUTES * 60;
        return rangesOn(date).stream()
                .anyMatch(r -> from >= r.open().toSecondOfDay() && to <= r.close().toSecondOfDay());
    }

    /** Lịch hẹn BOOKED không còn khung hợp lệ dưới lịch này (BR-CN-04, BR-LH-10). */
    public boolean appointmentAffected(LocalDate slotDate, LocalTime slotStart) {
        return !slotFits(slotDate, slotStart);
    }

    /**
     * Đặt chỗ lưu trú BOOKED có ngày nhận hoặc ngày trả (từ {@code fromDate}) rơi vào ngày không mở cửa
     * (BR-CN-03, BR-CN-04, BR-LT-04).
     */
    public boolean stayAffected(LocalDate checkIn, LocalDate checkOut, LocalDate fromDate) {
        return (!checkIn.isBefore(fromDate) && !isOpenOn(checkIn))
                || (!checkOut.isBefore(fromDate) && !isOpenOn(checkOut));
    }

    public boolean hasVersions() {
        return !versions.isEmpty();
    }
}
