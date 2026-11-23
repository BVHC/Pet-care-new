import { formatShortDate } from '@/components/appointments/format';
import type { ShiftAssignment, ShiftDefinition } from '@/shared/types/workforce';
import { addDays, isoDayOfWeek } from '@/shared/utils/branch-schedule';
import { vnDate } from '@/shared/utils/vn-time';

// Tuần đăng ký ca (UC35, BR-NS-11, BR-NS-12): tuần là thứ Hai → Chủ nhật; hạn đăng ký là 00:00 thứ Hai của tuần (giờ Việt Nam).
// Dùng chung cho màn nhân viên (MyRegistration) và màn quản lý (WeeklySummaryTab).

/** Thứ Hai của tuần chứa `iso`. */
export const weekStartOf = (iso: string) => addDays(iso, 1 - isoDayOfWeek(iso));

export const weekDays = (weekStart: string) => Array.from({ length: 7 }, (_, i) => addDays(weekStart, i));

/** Tuần mặc định để đăng ký / theo dõi: tuần kế tiếp. */
export const nextWeekStart = () => addDays(weekStartOf(vnDate()), 7);

/** "09/11/2026 – 15/11/2026". */
export const weekRange = (weekStart: string) => `${formatShortDate(weekStart)} – ${formatShortDate(addDays(weekStart, 6))}`;

/** Thời điểm `iso` (UTC) đã tới chưa; tách ra khỏi component để hàm render không gọi `Date.now()` trực tiếp. */
export const isPast = (iso: string, now = Date.now()) => now >= Date.parse(iso);

export const keyOf = (workDate: string, shiftDefinitionId: number) => `${workDate}|${shiftDefinitionId}`;

export interface GridShift {
  shiftDefinitionId: number;
  name: string;
  startTime: string;
  endTime: string;
}

/**
 * Các ca làm hàng của lưới: ca đang dùng của chi nhánh (#1) cộng ca xuất hiện trong phân ca của tuần mà không còn trong danh sách
 * (ca đã ngừng dùng, chỉ gặp ở tuần đã qua) để ô đã có ca vẫn hiện. Sắp theo giờ bắt đầu.
 */
export function gridShifts(definitions: ShiftDefinition[], assignments: ShiftAssignment[]): GridShift[] {
  const byId = new Map<number, GridShift>();
  for (const d of definitions) if (d.active) byId.set(d.shiftDefinitionId, { shiftDefinitionId: d.shiftDefinitionId, name: d.name, startTime: d.startTime, endTime: d.endTime });
  for (const a of assignments) {
    if (!byId.has(a.shiftDefinitionId)) byId.set(a.shiftDefinitionId, { shiftDefinitionId: a.shiftDefinitionId, name: a.shiftName, startTime: a.startTime, endTime: a.endTime });
  }
  return [...byId.values()].sort((a, b) => a.startTime.localeCompare(b.startTime) || a.shiftDefinitionId - b.shiftDefinitionId);
}
