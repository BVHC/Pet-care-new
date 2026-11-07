import { formatSlotDate } from '@/components/appointments/format';
import { toMinutes } from '@/shared/utils/branch-schedule';
import { vnClock } from '@/shared/utils/vn-time';
import type { ShiftAssignmentStatus, ShiftRequest, ShiftRequestStatus, ShiftRequestType } from '@/shared/types/workforce';

// Nhãn và tông màu của nhân sự (UC34–38). Màu luôn đi kèm chữ.

type Tone = 'success' | 'warning' | 'danger' | 'info' | 'neutral';

export const ASSIGNMENT_STATUS_LABELS: Record<ShiftAssignmentStatus, string> = {
  SCHEDULED: 'Đã phân',
  ON_LEAVE: 'Nghỉ phép',
  ABSENT: 'Vắng',
  CANCELLED: 'Đã hủy',
};
export const ASSIGNMENT_STATUS_TONE: Record<ShiftAssignmentStatus, Tone> = { SCHEDULED: 'info', ON_LEAVE: 'warning', ABSENT: 'danger', CANCELLED: 'neutral' };

export const REQUEST_TYPE_LABELS: Record<ShiftRequestType, string> = { LEAVE: 'Xin nghỉ', SWAP: 'Đổi ca' };

export const REQUEST_STATUS_LABELS: Record<ShiftRequestStatus, string> = {
  PENDING: 'Chờ duyệt',
  APPROVED: 'Đã duyệt',
  REJECTED: 'Từ chối',
  WITHDRAWN: 'Đã rút',
};
export const REQUEST_STATUS_TONE: Record<ShiftRequestStatus, Tone> = { PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger', WITHDRAWN: 'neutral' };

export const shiftTime = (s: { startTime: string; endTime: string }) => `${s.startTime}–${s.endTime}`;

/** "Thứ Hai, 26/10/2026". */
export const dayLabel = formatSlotDate;

/** "Xin nghỉ ca Ca sáng" / "Đổi ca Ca sáng sang Ca chiều". */
export const describeRequest = (r: Pick<ShiftRequest, 'type' | 'currentShiftName' | 'targetShiftName'>) =>
  r.type === 'LEAVE' ? `Xin nghỉ ${r.currentShiftName}` : `Đổi ${r.currentShiftName} sang ${r.targetShiftName ?? 'ca khác'}`;

/** Ca đã bắt đầu (giờ Việt Nam): hết hủy phân ca được (BR-NS-02), chỉ còn ghi vắng. */
export function hasStarted(a: { workDate: string; startTime: string }, now = Date.now()): boolean {
  const clock = vnClock(now);
  return a.workDate < clock.date || (a.workDate === clock.date && toMinutes(a.startTime) <= clock.minutes);
}
