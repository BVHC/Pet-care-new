// Lớp thuần của trang chi tiết lưu trú (UC58 gia hạn / hủy, UC60 xem): không React, không gọi API.
import { formatShortDate, formatVnDateTime } from '@/components/appointments/format';
import { APPOINTMENT_CFG } from '@/shared/types/appointment';
import {
  BOARDING_CFG,
  BOOKING_CANCEL_SOURCE_LABELS,
  BOOKING_STATUS_LABELS,
  type Booking,
  type BookingStatus,
  type CareLog,
} from '@/shared/types/boarding';
import { BOOKING_STATUS_TONE, nightsBetween, stayAmount, type Tone } from '@/shared/utils/boarding';

/** Chỉ nhận số nguyên dương viết thường ("abc", "0", "1.5", "007x" đều không): đường dẫn sai thì không gọi API. */
export const parseBookingId = (raw: string | undefined): number | null => (raw && /^[1-9]\d{0,14}$/.test(raw) ? Number(raw) : null);

/** Nhật ký chỉ có khi thú đã vào chuồng (BOOKED / CANCELLED / NO_SHOW không gọi API nhật ký). */
export const hasCareLogs = (status: BookingStatus): boolean => status === 'CHECKED_IN' || status === 'OVERDUE' || status === 'CHECKED_OUT';

/** Kết thúc do thú mất (BR-LT-12): trạng thái vẫn là CHECKED_OUT nhưng không gọi là "đã trả thú". */
export const isDeceasedEnd = (b: Pick<Booking, 'status' | 'endReason'>): boolean => b.status === 'CHECKED_OUT' && b.endReason === 'PET_DECEASED';

export const statusLabel = (b: Pick<Booking, 'status' | 'endReason'>): string => (isDeceasedEnd(b) ? 'Đã kết thúc' : BOOKING_STATUS_LABELS[b.status]);
export const statusTone = (b: Pick<Booking, 'status' | 'endReason'>): Tone => (isDeceasedEnd(b) ? 'neutral' : BOOKING_STATUS_TONE[b.status]);

/** Tạm tính chỉ có nghĩa khi thú còn ở hoặc đã được trả: hủy, không đến, thú mất thì không in số tiền. */
export const showsAmount = (b: Pick<Booking, 'status' | 'endReason'>): boolean =>
  b.status === 'BOOKED' || b.status === 'CHECKED_IN' || b.status === 'OVERDUE' || (b.status === 'CHECKED_OUT' && !isDeceasedEnd(b));

export const bookingNights = (b: Pick<Booking, 'checkInDate' | 'checkOutDate'>): number => nightsBetween(b.checkInDate, b.checkOutDate);
export const bookingAmount = (b: Pick<Booking, 'checkInDate' | 'checkOutDate' | 'nightlyPrice'>): number => stayAmount(bookingNights(b), b.nightlyPrice);

/** BR-LH-09: hủy muộn và không đến cộng dồn vào hạn chế đặt online (cùng bộ đếm với lịch hẹn). */
export const RESTRICTION_RULE = `đủ ${APPOINTMENT_CFG.violationThreshold} lần hủy muộn hoặc không đến trong ${APPOINTMENT_CFG.violationWindowDays} ngày sẽ bị tạm ngừng đặt online ${APPOINTMENT_CFG.restrictionDays} ngày (vẫn đặt được qua lễ tân)`;

export const AMOUNT_NOTE = `Tiền chốt khi trả thú, tính theo đêm thực tế; trả sau ${BOARDING_CFG.checkoutTime} tính thêm 1 đêm.`;

/** Mốc bắt đầu hủy muộn (00:00 giờ VN của ngày nhận trừ số giờ [CFG]) để nói rõ cho khách, ví dụ "00:00 25/10/2026". */
export function lateCancelFromLabel(checkInDate: string): string {
  const startMs = Date.parse(`${checkInDate}T00:00:00+07:00`) - BOARDING_CFG.lateCancelHours * 3_600_000;
  return formatVnDateTime(new Date(startMs).toISOString());
}

// ───── Dòng thời gian trạng thái (SM #6) ─────

export type StepState = 'done' | 'current' | 'upcoming';

export interface TimelineStep {
  /** Trạng thái đặt chỗ mà bước này biểu diễn. */
  key: BookingStatus;
  label: string;
  detail: string;
  state: StepState;
}

const planned = (date: string) => `Dự kiến ${formatShortDate(date)}`;

/**
 * Đã đặt → Đang lưu trú → [Quá hạn đón] → Đã trả thú; hoặc Đã đặt → Đã hủy / Không đến.
 * Bước "Quá hạn đón" hiện khi đang quá hạn hoặc đã từng quá hạn (`overdueSince`). Bước cuối của đặt chỗ đã kết thúc là bước hiện tại.
 */
export function timelineSteps(b: Booking): TimelineStep[] {
  const booked: TimelineStep = { key: 'BOOKED', label: BOOKING_STATUS_LABELS.BOOKED, detail: `Ngày nhận dự kiến ${formatShortDate(b.checkInDate)}`, state: 'done' };
  if (b.status === 'CANCELLED') {
    const source = b.cancelSource ? BOOKING_CANCEL_SOURCE_LABELS[b.cancelSource] : '';
    return [booked, { key: 'CANCELLED', label: BOOKING_STATUS_LABELS.CANCELLED, detail: source, state: 'current' }];
  }
  if (b.status === 'NO_SHOW') {
    return [booked, { key: 'NO_SHOW', label: BOOKING_STATUS_LABELS.NO_SHOW, detail: `Ngày nhận dự kiến ${formatShortDate(b.checkInDate)}`, state: 'current' }];
  }

  const steps: TimelineStep[] = [
    { ...booked, state: 'upcoming' },
    {
      key: 'CHECKED_IN',
      label: BOOKING_STATUS_LABELS.CHECKED_IN,
      detail: b.actualCheckInAt ? formatVnDateTime(b.actualCheckInAt) : planned(b.checkInDate),
      state: 'upcoming',
    },
  ];
  if (b.status === 'OVERDUE' || b.overdueSince) {
    steps.push({
      key: 'OVERDUE',
      label: BOOKING_STATUS_LABELS.OVERDUE,
      detail: b.overdueSince ? `Từ ${formatVnDateTime(b.overdueSince)}` : 'Chưa đón sau giờ trả',
      state: 'upcoming',
    });
  }
  steps.push({
    key: 'CHECKED_OUT',
    label: isDeceasedEnd(b) ? 'Kết thúc lưu trú' : BOOKING_STATUS_LABELS.CHECKED_OUT,
    detail: b.actualCheckOutAt ? formatVnDateTime(b.actualCheckOutAt) : planned(b.checkOutDate),
    state: 'upcoming',
  });
  const current = steps.findIndex((s) => s.key === b.status);
  return steps.map((s, i) => ({ ...s, state: i < current ? 'done' : i === current ? 'current' : 'upcoming' }));
}

// ───── Nhật ký chăm sóc ─────

export interface CareLogDay {
  date: string;
  logs: CareLog[];
}

/** Nhóm theo `logDate`, ngày mới → cũ; trong một ngày mục ghi sau (id lớn) lên trước, như thứ tự BE. */
export function groupCareLogs(logs: readonly CareLog[]): CareLogDay[] {
  const sorted = [...logs].sort((a, b) => b.logDate.localeCompare(a.logDate) || b.careLogId - a.careLogId);
  const days: CareLogDay[] = [];
  for (const log of sorted) {
    const last = days[days.length - 1];
    if (last && last.date === log.logDate) last.logs.push(log);
    else days.push({ date: log.logDate, logs: [log] });
  }
  return days;
}

// ───── Gia hạn ─────

export const isIsoDate = (value: string): boolean => /^\d{4}-\d{2}-\d{2}$/.test(value);

export interface ExtendPreview {
  newNights: number;
  addedNights: number;
  extraAmount: number;
  /** Vượt số đêm tối đa [CFG]: chỉ gợi ý, BE quyết định (BR-LT-02). */
  overMax: boolean;
}

/** Số đêm mới tính từ ngày nhận gốc (BR-LT-02), số đêm thêm và tạm tính thêm theo giá đêm đã chốt. */
export function extendPreview(b: Pick<Booking, 'checkInDate' | 'checkOutDate' | 'nightlyPrice'>, newCheckOut: string): ExtendPreview {
  const newNights = nightsBetween(b.checkInDate, newCheckOut);
  const addedNights = newNights - bookingNights(b);
  return { newNights, addedNights, extraAmount: stayAmount(addedNights, b.nightlyPrice), overMax: newNights > BOARDING_CFG.maxNights };
}
