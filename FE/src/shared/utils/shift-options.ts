// Nút mở ca thu ngân nào hiện (UC71; BR-TG-05, BR-CN-05; state machine Ca thu ngân#1, #2). Chạy trên dữ liệu API của chi nhánh
// (giờ mở cửa branch-v1 #6, ngày nghỉ #9, cờ cấp cứu ngoài giờ #3), giống `BranchSchedule.isOpenAt` của BE:
// "đang mở cửa" = không phải ngày nghỉ và giờ Việt Nam nằm trong một khoảng [mở, đóng) của bản giờ có hiệu lực hôm nay
// (nghỉ trưa giữa hai khoảng là ngoài giờ). BE vẫn kiểm lại và trả 400 nếu lệch.
import type { OpeningHoursVersion } from '../types/branch';
import { rangesOn, toMinutes } from './branch-schedule';
import { vnClock } from './vn-time';

export interface ShiftOptionsInput {
  /** Bản giờ đang áp dụng và các bản tương lai (branch-v1 #6). */
  hoursVersions: readonly OpeningHoursVersion[];
  /** Ngày nghỉ của chi nhánh (branch-v1 #9); chỉ cần các ngày từ hôm nay. */
  holidays: readonly { holidayDate: string }[];
  /** `acceptsAfterHoursEmergency` của chi nhánh (BR-CN-05). */
  acceptsAfterHours: boolean;
  nowMs: number;
}

export interface ShiftOptions {
  /** Ca thường: chỉ trong giờ mở cửa, không ngày nghỉ. */
  normal: boolean;
  /** Ca ngoài giờ: chi nhánh có cờ và đang ngoài giờ mở cửa hoặc trong ngày nghỉ. */
  afterHours: boolean;
  /** Có khi không mở được ca nào. */
  reason?: string;
}

export function shiftOptions({ hoursVersions, holidays, acceptsAfterHours, nowMs }: ShiftOptionsInput): ShiftOptions {
  const { date, minutes } = vnClock(nowMs);
  const holiday = holidays.some((h) => h.holidayDate === date);
  const open =
    !holiday && rangesOn([...hoursVersions], date).some((r) => toMinutes(r.open) <= minutes && minutes < toMinutes(r.close));

  const normal = open;
  const afterHours = acceptsAfterHours && !open;
  if (normal || afterHours) return { normal, afterHours };

  return {
    normal,
    afterHours,
    reason: holiday
      ? 'Hôm nay là ngày nghỉ của chi nhánh và chi nhánh không nhận cấp cứu ngoài giờ nên chưa mở được ca thu ngân.'
      : 'Đang ngoài giờ mở cửa (kể cả nghỉ trưa) và chi nhánh không nhận cấp cứu ngoài giờ nên chưa mở được ca thu ngân.',
  };
}
