// Lớp CSS dùng chung của trang chi tiết lưu trú: đúng token và hình khối của trang khách hiện có (AppointmentsPage):
// thẻ bo 16 px viền nâu nhạt, nút bo 12 px, nhãn trạng thái bo tròn, màu nhấn `accent` duy nhất.
import { CalendarCheck, CircleCheck, CircleX, House, TriangleAlert, UserX, type LucideIcon } from 'lucide-react';
import type { BookingStatus } from '@/shared/types/boarding';
import type { Tone } from '@/shared/utils/boarding';

/** Biểu tượng đi kèm chữ của mỗi trạng thái (không dựa vào màu). */
export const STATUS_ICON: Record<BookingStatus, LucideIcon> = {
  BOOKED: CalendarCheck,
  CHECKED_IN: House,
  OVERDUE: TriangleAlert,
  CHECKED_OUT: CircleCheck,
  CANCELLED: CircleX,
  NO_SHOW: UserX,
};

export const CARD =
  'rounded-2xl border border-(--color-border-default) bg-white shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)]';

export const H2 = 'font-(family-name:--font-friendly) text-[18px] font-bold leading-tight text-(--color-text-primary)';

/** Nhãn trạng thái luôn kèm chữ (và biểu tượng), không chỉ dựa vào màu. */
export const TONE_CLASS: Record<Tone, string> = {
  info: 'bg-blue-100 text-blue-800',
  warning: 'bg-amber-100 text-amber-900',
  success: 'bg-emerald-100 text-emerald-800',
  neutral: 'bg-gray-100 text-gray-700',
  danger: 'bg-red-100 text-red-800',
};

// Mục tiêu chạm tối thiểu 44 px (min-h-11).
const BTN =
  'inline-flex min-h-11 items-center justify-center gap-2 whitespace-nowrap rounded-xl px-5 py-2 text-[13.5px] font-bold transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:pointer-events-none disabled:opacity-60';
export const BTN_PRIMARY = `${BTN} bg-accent text-white hover:bg-(--color-accent-hover)`;
export const BTN_OUTLINE = `${BTN} border border-(--color-border-default) bg-white text-(--color-text-primary) hover:border-accent hover:text-accent`;
export const BTN_DANGER_OUTLINE = `${BTN} border border-red-200 bg-white text-red-700 hover:bg-red-50`;
export const BTN_DANGER = `${BTN} bg-red-600 text-white hover:bg-red-700`;

export const TEXTAREA =
  'w-full rounded-xl border border-(--color-border-default) bg-white px-3 py-2.5 text-[14px] text-(--color-text-primary) focus-visible:outline-2 focus-visible:outline-accent';
export const DATE_INPUT = 'h-11 w-full rounded-xl border border-(--color-border-default) bg-white px-3 text-[14px] text-(--color-text-primary) focus-visible:outline-2 focus-visible:outline-accent';

/** Khung hộp thoại như ChangeAppointmentDialogs. */
export const DIALOG_CONTENT = 'max-h-[90dvh] overflow-y-auto bg-white text-(--color-text-primary) sm:max-w-xl';
