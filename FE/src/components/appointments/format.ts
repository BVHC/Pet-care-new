// Hiển thị ngày giờ lịch hẹn theo giờ Việt Nam, không phụ thuộc múi giờ máy (BE: TimeConfig.BUSINESS_ZONE).
import { addDays, SLOT_MINUTES, toHHmm, toMinutes } from '@/shared/utils/branch-schedule';
import { APPOINTMENT_CFG, type Appointment, type AppointmentStatus } from '@/shared/types/appointment';

const ZONE = 'Asia/Ho_Chi_Minh';
const HOUR = 3_600_000;

/** Hôm nay theo giờ Việt Nam, dạng yyyy-MM-dd. */
export const todayVn = (now: number = Date.now()) => new Intl.DateTimeFormat('en-CA', { timeZone: ZONE }).format(now);

/** Ngày sớm nhất khách đặt / đổi được: ngày của now + 24 giờ [CFG] (BR-LH-04); ngày trước đó không còn khung nào. */
export const customerMinDate = (now: number = Date.now()) => todayVn(now + APPOINTMENT_CFG.minLeadHours * HOUR);

/** Ngày xa nhất khách đặt / đổi được: hôm nay + 30 ngày [CFG] (BR-LH-04, tính theo ngày — ADR-0046). */
export const customerMaxDate = (now: number = Date.now()) => addDays(todayVn(now), APPOINTMENT_CFG.maxAdvanceDays);

/** Giờ bắt đầu của khung (UTC+7, không đổi giờ mùa hè). */
export const startInstant = (a: Pick<Appointment, 'slotDate' | 'slotStart'>) => Date.parse(`${a.slotDate}T${a.slotStart}:00+07:00`);

export const hoursUntil = (a: Pick<Appointment, 'slotDate' | 'slotStart'>, now: number = Date.now()) => (startInstant(a) - now) / HOUR;

/** Hủy lúc này có là hủy muộn không (BR-LH-07: còn dưới 12 giờ [CFG]). */
export const isLateCancelNow = (a: Pick<Appointment, 'slotDate' | 'slotStart'>, now: number = Date.now()) =>
  hoursUntil(a, now) < APPOINTMENT_CFG.lateCancelHours;

export const slotEnd = (start: string) => toHHmm(toMinutes(start) + SLOT_MINUTES);

const DATE_FMT = new Intl.DateTimeFormat('vi-VN', { weekday: 'long', day: '2-digit', month: '2-digit', year: 'numeric', timeZone: 'UTC' });
const SHORT_DATE_FMT = new Intl.DateTimeFormat('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric', timeZone: 'UTC' });

const utcOf = (iso: string) => {
  const [y, m, d] = iso.split('-').map(Number);
  return Date.UTC(y, m - 1, d);
};

/** "Thứ Hai, 26/10/2026" */
export const formatSlotDate = (date: string) => DATE_FMT.format(utcOf(date));
/** "26/10/2026" */
export const formatShortDate = (date: string) => SHORT_DATE_FMT.format(utcOf(date));
/** "09:00–09:30, Thứ Hai, 26/10/2026" */
export const formatSlot = (a: Pick<Appointment, 'slotDate' | 'slotStart'>) =>
  `${a.slotStart}–${slotEnd(a.slotStart)}, ${formatSlotDate(a.slotDate)}`;

const PARTS = new Intl.DateTimeFormat('en-GB', {
  timeZone: ZONE,
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

/** "HH:mm dd/MM/yyyy" giờ Việt Nam, như message BR-LH-09 của BE. */
export function formatVnDateTime(iso: string) {
  const p = Object.fromEntries(PARTS.formatToParts(Date.parse(iso)).map((x) => [x.type, x.value]));
  return `${p.hour}:${p.minute} ${p.day}/${p.month}/${p.year}`;
}

/** Tông màu của nhãn trạng thái (luôn kèm chữ, không dùng màu đơn lẻ). */
export const STATUS_TONE: Record<AppointmentStatus, 'info' | 'warning' | 'success' | 'neutral' | 'danger'> = {
  BOOKED: 'info',
  CHECKED_IN: 'warning',
  COMPLETED: 'success',
  CANCELLED: 'neutral',
  NO_SHOW: 'danger',
};
