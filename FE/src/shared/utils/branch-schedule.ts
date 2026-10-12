// Lịch của chi nhánh, tính ở client giống BE (module branch, BranchSchedule): ngày, bản giờ mở cửa, khung 30 phút.
// Dùng cho màn hình T20 và cho mock của module branch.
import type { DayHours, OpeningHoursVersion, ScheduleImpact, TimeRange } from '../types/branch';

/** Thứ trong tuần theo BE: 1 = thứ Hai … 7 = Chủ nhật. */
export const WEEK_DAYS = [1, 2, 3, 4, 5, 6, 7] as const;

export const DAY_LABELS: Record<number, string> = {
  1: 'Thứ Hai',
  2: 'Thứ Ba',
  3: 'Thứ Tư',
  4: 'Thứ Năm',
  5: 'Thứ Sáu',
  6: 'Thứ Bảy',
  7: 'Chủ nhật',
};

/** Khung đặt lịch dài 30 phút (BR-LH-02). */
export const SLOT_MINUTES = 30;

export const toMinutes = (hhmm: string) => Number(hhmm.slice(0, 2)) * 60 + Number(hhmm.slice(3, 5));
export const toHHmm = (minutes: number) =>
  `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;

/** Ngày theo giờ máy (người dùng ở Việt Nam), dạng `yyyy-MM-dd`. */
export const localIsoDate = (date: Date = new Date()) => date.toLocaleDateString('sv-SE');

const parse = (iso: string) => {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d);
};

export function addDays(iso: string, days: number): string {
  const date = parse(iso);
  date.setDate(date.getDate() + days);
  return localIsoDate(date);
}

export function isoDayOfWeek(iso: string): number {
  const day = parse(iso).getDay();
  return day === 0 ? 7 : day;
}

/** Ngày gần nhất sau `fromIso` (không tính chính nó) rơi vào thứ `isoDay`. */
export function nextWeekday(fromIso: string, isoDay: number): string {
  const ahead = ((isoDay - isoDayOfWeek(fromIso) + 6) % 7) + 1;
  return addDays(fromIso, ahead);
}

/** Lỗi BR-CN-02 đầu tiên (nhãn thứ tiếng Việt), null nếu hợp lệ. Thiếu ngày nào coi như nghỉ. */
export function hoursError(days: DayHours[]): string | null {
  for (const day of WEEK_DAYS) {
    const label = DAY_LABELS[day];
    const ranges = days.find((d) => d.dayOfWeek === day)?.ranges ?? [];
    if (ranges.length > 2) return `${label} có quá 2 khoảng giờ mở cửa`;
    if (ranges.some((r) => !r.open || !r.close)) return `${label}: nhập đủ giờ mở và giờ đóng cửa`;
    const sorted = [...ranges].sort((a, b) => a.open.localeCompare(b.open));
    let previousClose = '';
    for (const range of sorted) {
      if (range.open >= range.close) return `${label}: giờ mở cửa phải trước giờ đóng cửa`;
      if (previousClose && range.open < previousClose) return `${label}: các khoảng giờ mở cửa không được chồng nhau`;
      previousClose = range.close;
    }
  }
  return null;
}

/** Bản giờ đang áp dụng vào ngày `date`: `effectiveFrom` lớn nhất mà ≤ `date`. */
export function versionOn(versions: OpeningHoursVersion[], date: string): OpeningHoursVersion | null {
  return versions.reduce<OpeningHoursVersion | null>(
    (best, v) => (v.effectiveFrom <= date && (!best || v.effectiveFrom > best.effectiveFrom) ? v : best),
    null,
  );
}

/** Các khoảng giờ mở cửa của ngày `date` (chưa tính ngày nghỉ). */
export function rangesOn(versions: OpeningHoursVersion[], date: string): TimeRange[] {
  const day = isoDayOfWeek(date);
  return versionOn(versions, date)?.days.find((d) => d.dayOfWeek === day)?.ranges ?? [];
}

/** Khung `[start, start + 30')` nằm trọn trong một khoảng. */
export const slotFits = (ranges: TimeRange[], start: string) =>
  ranges.some((r) => toMinutes(start) >= toMinutes(r.open) && toMinutes(start) + SLOT_MINUTES <= toMinutes(r.close));

/**
 * Khung đặt lịch của ngày `date` (BR-LH-02): bước 30 phút tính từ giờ mở của từng khoảng,
 * khung cuối kết thúc trước hoặc đúng giờ đóng; ngày nghỉ (BR-CN-03) không có khung.
 */
export function slotsOn(versions: OpeningHoursVersion[], holidayDates: readonly string[], date: string): string[] {
  if (holidayDates.includes(date)) return [];
  const slots: string[] = [];
  for (const range of rangesOn(versions, date)) {
    for (let t = toMinutes(range.open); t + SLOT_MINUTES <= toMinutes(range.close); t += SLOT_MINUTES) {
      slots.push(toHHmm(t));
    }
  }
  return slots;
}

export const isEmptyImpact = (impact: ScheduleImpact) => impact.appointments.length === 0 && impact.boardingBookings.length === 0;

/** "08:00–12:00, 13:30–19:00" hoặc "Nghỉ". */
export const formatRanges = (ranges: TimeRange[]) =>
  ranges.length ? ranges.map((r) => `${r.open}–${r.close}`).join(', ') : 'Nghỉ';
