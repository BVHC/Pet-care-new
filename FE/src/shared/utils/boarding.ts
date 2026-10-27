// Hàm thuần cho lưu trú (UC54, UC57–UC60). Mọi ngày là `YYYY-MM-DD` giờ Việt Nam; không dùng múi giờ máy.
import { vnClock } from './vn-time';
import {
  ACTIVE_BOOKING_STATUSES,
  BOARDING_CFG,
  IN_STAY_STATUSES,
  type Booking,
  type BookingStatus,
  type CareLog,
  type KennelStatus,
} from '../types/boarding';

export type Tone = 'info' | 'warning' | 'success' | 'neutral' | 'danger';

export const BOOKING_STATUS_TONE: Record<BookingStatus, Tone> = {
  BOOKED: 'info',
  CHECKED_IN: 'warning',
  OVERDUE: 'danger',
  CHECKED_OUT: 'success',
  CANCELLED: 'neutral',
  NO_SHOW: 'danger',
};

export const KENNEL_STATUS_TONE: Record<KennelStatus, Tone> = { AVAILABLE: 'success', OCCUPIED: 'warning', MAINTENANCE: 'neutral' };

const DAY_MS = 24 * 60 * 60 * 1000;
const dayNumber = (iso: string) => Date.UTC(Number(iso.slice(0, 4)), Number(iso.slice(5, 7)) - 1, Number(iso.slice(8, 10))) / DAY_MS;

/** Cộng ngày vào `YYYY-MM-DD` (lịch Gregorian thuần, không qua múi giờ máy). */
export const shiftDate = (iso: string, days: number): string => new Date((dayNumber(iso) + days) * DAY_MS).toISOString().slice(0, 10);

/** Số đêm = ngày trả − ngày nhận (có thể ≤ 0 khi nhập sai). */
export const nightsBetween = (checkInDate: string, checkOutDate: string): number => dayNumber(checkOutDate) - dayNumber(checkInDate);

/** Các đêm của một khoảng lưu trú: ngày nhận … ngày trả − 1 (A1). */
export function nightDates(checkInDate: string, checkOutDate: string): string[] {
  const nights = nightsBetween(checkInDate, checkOutDate);
  return Array.from({ length: Math.max(0, nights) }, (_, i) => shiftDate(checkInDate, i));
}

export const isInStay = (status: BookingStatus): boolean => IN_STAY_STATUSES.includes(status);
export const isActiveBooking = (status: BookingStatus): boolean => ACTIVE_BOOKING_STATUSES.includes(status);
export const isFinished = (status: BookingStatus): boolean => status === 'CHECKED_OUT' || status === 'CANCELLED' || status === 'NO_SHOW';

/** SM #6 Đặt chỗ#2: `BOOKED` / `CHECKED_IN`; `OVERDUE` không gia hạn. Trước ngày trả dự kiến (BR-LT-06). */
export const canExtend = (b: Pick<Booking, 'status' | 'checkOutDate'>, today: string = vnClock().date): boolean =>
  (b.status === 'BOOKED' || b.status === 'CHECKED_IN') && today < b.checkOutDate;

/** Đặt chỗ#4: chỉ `BOOKED`, trước ngày nhận. */
export const canCancel = (b: Pick<Booking, 'status' | 'checkInDate'>, today: string = vnClock().date): boolean =>
  b.status === 'BOOKED' && today < b.checkInDate;

/**
 * Hủy muộn (BR-LT-06, A6): bây giờ ≥ 00:00 giờ Việt Nam của ngày nhận − `late_cancel_hours` [CFG] (mặc định 24 h).
 * Mặc định: hủy bất kỳ giờ nào ngày D−1 là muộn, ≤ D−2 không muộn.
 */
export function isLateCancel(checkInDate: string, atMs: number = Date.now(), hours: number = BOARDING_CFG.lateCancelHours): boolean {
  const startOfCheckIn = Date.parse(`${checkInDate}T00:00:00+07:00`);
  return atMs >= startOfCheckIn - hours * 60 * 60 * 1000;
}

/** BR-LT-11: người ghi sửa được tới `editableUntil`; sau đó chỉ thêm bản bổ sung. */
export const canEditCareLog = (log: Pick<CareLog, 'editableUntil'>, atMs: number = Date.now()): boolean => atMs < Date.parse(log.editableUntil);

/** Thành tiền tạm tính = đêm × giá đêm (đã snapshot lúc đặt, BR-LT-02). */
export const stayAmount = (nights: number, nightlyPrice: number): number => Math.max(0, nights) * nightlyPrice;
