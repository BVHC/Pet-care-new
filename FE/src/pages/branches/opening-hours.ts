// Giờ làm việc theo tuần của chi nhánh công khai (UC15): sắp Thứ Hai đến Chủ nhật, ngày không có khoảng giờ là "Nghỉ" (BR-CN-02).
import type { DayHours } from '../../shared/types/branch';

const DAY_LABELS = ['Thứ Hai', 'Thứ Ba', 'Thứ Tư', 'Thứ Năm', 'Thứ Sáu', 'Thứ Bảy', 'Chủ nhật'];

/** Dấu cách không ngắt dòng: một khoảng giờ không bị xé đôi khi cột hẹp. */
const NBSP = ' ';

export interface HoursRow {
  dayOfWeek: number;
  label: string;
  /** "08:00 - 12:00 và 13:30 - 19:00", hoặc "Nghỉ". */
  text: string;
  closed: boolean;
}

/** Không tự thêm ngày thiếu (hợp đồng luôn trả đủ 7 ngày) và bỏ `dayOfWeek` ngoài 1..7. */
export function weekRows(days: DayHours[]): HoursRow[] {
  return days
    .filter((d) => Number.isInteger(d.dayOfWeek) && d.dayOfWeek >= 1 && d.dayOfWeek <= 7)
    .sort((a, b) => a.dayOfWeek - b.dayOfWeek)
    .map(({ dayOfWeek, ranges }) => ({
      dayOfWeek,
      label: DAY_LABELS[dayOfWeek - 1],
      closed: ranges.length === 0,
      text: ranges.length === 0 ? 'Nghỉ' : ranges.map((r) => `${r.open}${NBSP}-${NBSP}${r.close}`).join(' và '),
    }));
}
