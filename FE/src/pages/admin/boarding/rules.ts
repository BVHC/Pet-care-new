// Quy tắc thuần của màn lưu trú nhân viên (UC57–59): ma trận vai trò × trạng thái (boarding-v1 §B, A07 / A08) và ghép tab + bộ lọc thành query.
import { formatShortDate } from '@/components/appointments/format';
import type { Species } from '@/domains/pets/types';
import type { Role } from '@/shared/types/auth';
import type { Booking, BookingQuery, BookingStatus, VaccineGap } from '@/shared/types/boarding';
import type { KennelTypeSpec } from '@/shared/types/catalog';
import { canCancel, canExtend } from '@/shared/utils/boarding';

export type BoardingTab = 'upcoming' | 'inStay' | 'history';
/** API chỉ nhận một `status`, nên Lịch sử lọc từng trạng thái cuối. */
export type HistoryStatus = Extract<BookingStatus, 'CHECKED_OUT' | 'CANCELLED' | 'NO_SHOW'>;

export const TAB_LABELS: Record<BoardingTab, string> = { upcoming: 'Sắp nhận', inStay: 'Đang lưu trú', history: 'Lịch sử' };
export const HISTORY_STATUSES: readonly HistoryStatus[] = ['CHECKED_OUT', 'CANCELLED', 'NO_SHOW'];

export interface BoardingFilters {
  tab: BoardingTab;
  historyStatus: HistoryStatus;
  /** `YYYY-MM-DD` hoặc rỗng: lọc giao với [ngày nhận, ngày trả]. */
  from: string;
  to: string;
  /** Chỉ có nghĩa ở tab Đang lưu trú (BR-LT-11). */
  missingCareLogToday: boolean;
}

export function bookingQueryOf(f: BoardingFilters, page: number, size: number): BookingQuery {
  const query: BookingQuery = {};
  if (f.tab === 'upcoming') query.status = 'BOOKED';
  else if (f.tab === 'history') query.status = f.historyStatus;
  else {
    query.inStay = true;
    if (f.missingCareLogToday) query.missingCareLogToday = true;
  }
  if (f.from) query.from = f.from;
  if (f.to) query.to = f.to;
  query.page = page;
  query.size = size;
  return query;
}

export type BookingAction = 'checkIn' | 'noKennel' | 'extend' | 'cancel' | 'careLog' | 'checkout' | 'deceased';

export const ACTION_LABELS: Record<BookingAction, string> = {
  checkIn: 'Nhận thú',
  noKennel: 'Hủy vì hết chuồng',
  extend: 'Gia hạn',
  cancel: 'Hủy đặt chỗ',
  careLog: 'Nhật ký',
  checkout: 'Trả thú',
  deceased: 'Thú mất',
};

/** BR-LT-11: VET và CARETAKER ghi nhật ký; các vai trò còn lại chỉ xem. */
export const canWriteCareLog = (role: Role | undefined): boolean => role === 'VET' || role === 'CARETAKER';

/**
 * Thao tác ghi / mở dialog của một đặt chỗ, theo `booking.status` (không theo tab). BRANCH_MANAGER chỉ xem nhật ký;
 * vai trò ngoài 4 vai trò nhân viên chi nhánh không có thao tác nào (BE trả 403).
 */
export function actionsOf(
  role: Role | undefined,
  booking: Pick<Booking, 'status' | 'checkInDate' | 'checkOutDate'>,
  today: string,
): BookingAction[] {
  const desk = role === 'RECEPTIONIST';
  const actions: BookingAction[] = [];
  if (booking.status === 'BOOKED') {
    if (desk || role === 'CARETAKER') actions.push('checkIn');
    if (desk) actions.push('noKennel');
    if (desk && canExtend(booking, today)) actions.push('extend');
    if (desk && canCancel(booking, today)) actions.push('cancel');
  } else if (booking.status === 'CHECKED_IN' || booking.status === 'OVERDUE') {
    if (desk || role === 'CARETAKER' || role === 'VET' || role === 'BRANCH_MANAGER') actions.push('careLog');
    if (desk) actions.push('checkout', 'deceased');
    if (desk && canExtend(booking, today)) actions.push('extend');
  }
  return actions;
}

/** `YYYY-MM-DD` → `dd/MM` (dạng ngày trong câu BR-LT-03 "Hết chỗ các đêm: 30/10, 31/10"). */
export const shortDay = (date: string): string => `${date.slice(8, 10)}/${date.slice(5, 7)}`;

/**
 * BR-LT-02: loại chuồng phải hợp loài của thú và cân nặng hiện tại không vượt ngưỡng; thú chưa cân thì qua (BE cũng cho qua).
 * Chỉ để ẩn lựa chọn sai ở giao diện — BE vẫn kiểm lại.
 */
export function kennelFits(type: { kennelType?: KennelTypeSpec | null }, pet: { species: Species; currentWeightKg?: number | null }): boolean {
  const spec = type.kennelType;
  if (!spec || spec.species !== pet.species) return false;
  return pet.currentWeightKg == null || pet.currentWeightKg <= spec.maxWeightKg;
}

/** Một dòng cảnh báo thiếu mũi tiêm bắt buộc (BR-LT-05): chưa tiêm lần nào, hoặc đã tiêm nhưng hạn trước ngày nhận. */
export function vaccineGapText(gap: Pick<VaccineGap, 'lastAdministeredOn' | 'nextDueDate'>): string {
  if (!gap.lastAdministeredOn) return 'Chưa tiêm';
  const last = `Tiêm lần cuối ${formatShortDate(gap.lastAdministeredOn)}`;
  return gap.nextDueDate ? `${last} · hạn ${formatShortDate(gap.nextDueDate)}` : last;
}
