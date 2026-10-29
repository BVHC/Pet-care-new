package com.petcare.module.boarding.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.petcare.module.boarding.dto.CareLogAddendumRequest;
import com.petcare.module.boarding.dto.CareLogAddendumResponse;
import com.petcare.module.boarding.dto.CareLogRequest;
import com.petcare.module.boarding.dto.CareLogResponse;
import com.petcare.module.boarding.dto.UpdateCareLogRequest;
import com.petcare.module.boarding.entity.BoardingBooking;
import com.petcare.module.boarding.entity.BoardingBookingStatus;
import com.petcare.module.boarding.entity.CareLog;
import com.petcare.module.boarding.entity.CareLogAddendum;
import com.petcare.module.boarding.exception.BoardingConflictException;
import com.petcare.module.boarding.exception.InvalidBoardingRequestException;
import com.petcare.module.boarding.repository.BoardingBookingRepository;
import com.petcare.module.boarding.repository.BoardingBookingRepository.BookingKeys;
import com.petcare.module.boarding.repository.CareLogAddendumRepository;
import com.petcare.module.boarding.repository.CareLogRepository;
import com.petcare.module.boarding.repository.CareLogRepository.LogKeys;
import com.petcare.module.customer.api.CustomerQueryApi;
import com.petcare.module.identity.api.ConfigKey;
import com.petcare.module.identity.api.StaffDirectoryApi;
import com.petcare.module.identity.api.StaffDirectoryApi.StaffSummary;
import com.petcare.platform.config.TimeConfig;
import com.petcare.platform.exception.AccessDeniedScopeException;
import com.petcare.platform.exception.BusinessRuleViolationException;
import com.petcare.platform.exception.ResourceNotFoundException;
import com.petcare.module.identity.api.SystemConfigApi;
import com.petcare.platform.security.BranchScope;
import com.petcare.platform.security.SecurityPrincipal;

/**
 * Nhật ký chăm sóc (UC57, UC60, boarding-v1 #19–22; Đặt chỗ#8; docs/adr/0085). Ghi khóa
 * {@code boarding_bookings FOR SHARE → INSERT care_logs → outbox}; sửa khóa {@code care_logs FOR NO KEY UPDATE} và không
 * khóa gì sau đó; bổ sung chỉ INSERT. Thứ tự lỗi: 404 / 403 → 409 → 400 (ADR-0049). Khách xem nhật ký của hồ sơ mình (404
 * nếu không), nhân viên theo chi nhánh (403).
 */
@Service
public class CareLogService {

    static final int MAX_URL_LENGTH = 500;
    private static final Pattern HTTP_URL = Pattern.compile("^https?://\\S+$");

    private final BranchScope branchScope;
    private final CustomerQueryApi customers;
    private final StaffDirectoryApi staff;
    private final SystemConfigApi configs;
    private final BoardingBookingRepository bookings;
    private final CareLogRepository careLogs;
    private final CareLogAddendumRepository addenda;
    private final CareLogAbnormalNotices notices;
    private final Clock clock;

    public CareLogService(BranchScope branchScope, CustomerQueryApi customers, StaffDirectoryApi staff,
            SystemConfigApi configs, BoardingBookingRepository bookings, CareLogRepository careLogs,
            CareLogAddendumRepository addenda, CareLogAbnormalNotices notices, Clock clock) {
        this.branchScope = branchScope;
        this.customers = customers;
        this.staff = staff;
        this.configs = configs;
        this.bookings = bookings;
        this.careLogs = careLogs;
        this.addenda = addenda;
        this.notices = notices;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<CareLogResponse> list(Long bookingId) {
        authorizeView(bookingId);
        return toResponses(careLogs.findByBookingIdOrderByLogDateAscIdAsc(bookingId));
    }

    @Transactional
    public CareLogResponse create(Long bookingId, CareLogRequest request) {
        SecurityPrincipal principal = branchScope.current();
        BookingKeys keys = authorizeStaff(bookingId);
        BoardingBookingStatus status = BoardingBookingStatus.valueOf(bookings.lockStatusForShare(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("đặt chỗ lưu trú", bookingId)));
        if (status != BoardingBookingStatus.CHECKED_IN && status != BoardingBookingStatus.OVERDUE) {
            throw new BoardingConflictException("Thú không đang lưu trú, không ghi nhật ký được");
        }
        BoardingBooking booking = bookings.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("đặt chỗ lưu trú", bookingId));
        LocalDate today = LocalDate.now(clock);
        LocalDate logDate = request.logDate() == null ? today : request.logDate();
        LocalDate stayStart = booking.getActualCheckInAt() == null ? booking.getCheckInDate()
                : booking.getActualCheckInAt().atZone(TimeConfig.BUSINESS_ZONE).toLocalDate();
        if (logDate.isAfter(today) || logDate.isBefore(stayStart)) {
            throw new InvalidBoardingRequestException("Ngày nhật ký phải từ ngày nhận thú tới hôm nay");
        }
        String eating = blankToNull(request.eating());
        String drinking = blankToNull(request.drinking());
        String hygiene = blankToNull(request.hygiene());
        String activity = blankToNull(request.activity());
        String note = blankToNull(request.note());
        List<String> photos = checkedPhotos(request.photoUrls());
        requireContent(eating, drinking, hygiene, activity, note, photos);

        boolean abnormal = Boolean.TRUE.equals(request.isAbnormal());
        CareLog saved = careLogs.saveAndFlush(CareLog.record(bookingId, logDate, eating, drinking, hygiene, activity,
                note, photos, abnormal, principal.accountId()));
        if (abnormal) {
            notices.enqueue(booking, saved);
        }
        return toResponses(List.of(saved)).get(0);
    }

    @Transactional
    public CareLogResponse update(Long careLogId, UpdateCareLogRequest request) {
        SecurityPrincipal principal = branchScope.current();
        LogKeys keys = careLogs.findKeys(careLogId)
                .orElseThrow(() -> new ResourceNotFoundException("nhật ký chăm sóc", careLogId));
        authorizeStaff(keys.getBookingId());
        if (!keys.getRecordedBy().equals(principal.accountId())) {
            throw new AccessDeniedScopeException("careLog:" + careLogId, "recordedBy:" + principal.accountId());
        }
        CareLog careLog = careLogs.findByIdForUpdate(careLogId)
                .orElseThrow(() -> new ResourceNotFoundException("nhật ký chăm sóc", careLogId));
        Instant now = clock.instant();
        if (!now.isBefore(editableUntil(careLog))) {
            throw new BoardingConflictException("Đã quá thời hạn sửa nhật ký, hãy thêm bản bổ sung (BR-LT-11)");
        }
        List<String> photos = request.photoUrls() == null ? careLog.getPhotoUrls() : checkedPhotos(request.photoUrls());
        String eating = merge(request.eating(), careLog.getEating());
        String drinking = merge(request.drinking(), careLog.getDrinking());
        String hygiene = merge(request.hygiene(), careLog.getHygiene());
        String activity = merge(request.activity(), careLog.getActivity());
        String note = merge(request.note(), careLog.getNote());
        requireContent(eating, drinking, hygiene, activity, note, photos);

        careLog.edit(eating, drinking, hygiene, activity, note, photos);
        return toResponses(List.of(careLogs.saveAndFlush(careLog))).get(0);
    }

    @Transactional
    public CareLogAddendumResponse addAddendum(Long careLogId, CareLogAddendumRequest request) {
        SecurityPrincipal principal = branchScope.current();
        LogKeys keys = careLogs.findKeys(careLogId)
                .orElseThrow(() -> new ResourceNotFoundException("nhật ký chăm sóc", careLogId));
        authorizeStaff(keys.getBookingId());
        CareLogAddendum saved = addenda.saveAndFlush(
                CareLogAddendum.of(careLogId, request.content().strip(), principal.accountId()));
        return toResponse(saved);
    }

    /** Khách: đặt chỗ của hồ sơ mình (404 nếu không); nhân viên: đặt chỗ của chi nhánh mình (403 nếu không). */
    private void authorizeView(Long bookingId) {
        SecurityPrincipal principal = branchScope.current();
        BookingKeys keys = bookings.findKeys(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("đặt chỗ lưu trú", bookingId));
        if ("CUSTOMER".equals(principal.role())) {
            Long own = customers.findCustomerIdByAccountId(principal.accountId()).orElseThrow(
                    () -> new IllegalStateException("Tài khoản khách #" + principal.accountId() + " không có hồ sơ khách"));
            if (!own.equals(keys.getCustomerId())) {
                throw new ResourceNotFoundException("đặt chỗ lưu trú", bookingId);
            }
        } else {
            branchScope.check(keys.getBranchId());
        }
    }

    /** VET / CARETAKER của chi nhánh (vai trò đã chặn ở controller). */
    private BookingKeys authorizeStaff(Long bookingId) {
        BookingKeys keys = bookings.findKeys(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("đặt chỗ lưu trú", bookingId));
        branchScope.check(keys.getBranchId());
        return keys;
    }

    private Instant editableUntil(CareLog careLog) {
        return careLog.getCreatedAt().plus(Duration.ofMinutes(configs.getInt(ConfigKey.CARE_LOG_EDIT_WINDOW_MINUTES)));
    }

    /** BR-LT-11: tối đa {@code care_log.max_photos} [CFG] ảnh; mỗi URL http / https, ≤ 500 ký tự (E6). */
    private List<String> checkedPhotos(List<String> urls) {
        int max = configs.getInt(ConfigKey.CARE_LOG_MAX_PHOTOS);
        if (urls.size() > max) {
            throw new BusinessRuleViolationException("BR-LT-11", "Mỗi mục nhật ký tối đa " + max + " ảnh");
        }
        for (String url : urls) {
            if (url == null || url.length() > MAX_URL_LENGTH || !HTTP_URL.matcher(url).matches()) {
                throw new InvalidBoardingRequestException("Ảnh phải là địa chỉ http / https dài tối đa "
                        + MAX_URL_LENGTH + " ký tự");
            }
        }
        return List.copyOf(urls);
    }

    private static void requireContent(String eating, String drinking, String hygiene, String activity, String note,
            List<String> photos) {
        if (eating == null && drinking == null && hygiene == null && activity == null && note == null
                && photos.isEmpty()) {
            throw new InvalidBoardingRequestException("Mục nhật ký phải có ít nhất một nội dung hoặc ảnh");
        }
    }

    /** {@code null} giữ giá trị cũ, chuỗi trống xóa (E9). */
    private static String merge(String sent, String current) {
        return sent == null ? current : blankToNull(sent);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private List<CareLogResponse> toResponses(List<CareLog> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<CareLogAddendumResponse>> addendaByLog = addenda
                .findByCareLogIdInOrderByIdAsc(rows.stream().map(CareLog::getId).toList()).stream()
                .collect(Collectors.groupingBy(CareLogAddendum::getCareLogId,
                        Collectors.mapping(CareLogService::toResponse, Collectors.toList())));
        Map<Long, String> names = names(rows.stream().map(CareLog::getRecordedBy).collect(Collectors.toSet()));
        return rows.stream().map(l -> new CareLogResponse(l.getId(), l.getBookingId(), l.getLogDate(), l.getEating(),
                l.getDrinking(), l.getHygiene(), l.getActivity(), l.getNote(), l.getPhotoUrls(), l.isAbnormal(),
                l.getRecordedBy(), names.getOrDefault(l.getRecordedBy(), "Nhân viên #" + l.getRecordedBy()),
                l.getCreatedAt(), editableUntil(l), addendaByLog.getOrDefault(l.getId(), List.of()))).toList();
    }

    private Map<Long, String> names(Collection<Long> accountIds) {
        Map<Long, String> names = new HashMap<>();
        for (StaffSummary summary : staff.findStaffByIds(accountIds)) {
            names.put(summary.accountId(), summary.fullName());
        }
        return names;
    }

    private static CareLogAddendumResponse toResponse(CareLogAddendum row) {
        return new CareLogAddendumResponse(row.getId(), row.getContent(), row.getCreatedBy(),
                Objects.requireNonNull(row.getCreatedAt()));
    }
}
