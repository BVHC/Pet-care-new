// Lớp CSS dùng chung của các trang mua hàng online: cùng token và hình khối với trang khách hiện có (thẻ bo 16 px viền nâu nhạt, nút bo 12 px, màu nhấn `accent`).
import { productPriceLabel } from '@/shared/utils/public-format';

export const CARD =
  'rounded-2xl border border-(--color-border-default) bg-white shadow-[0_1px_2px_rgba(56,36,23,0.06),0_4px_12px_-8px_rgba(56,36,23,0.4)]';
export const H2 = 'font-(family-name:--font-friendly) text-[18px] font-bold leading-tight text-(--color-text-primary)';

const BTN =
  'inline-flex min-h-11 items-center justify-center gap-2 whitespace-nowrap rounded-xl px-5 py-2 text-[13.5px] font-bold transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:pointer-events-none disabled:opacity-60';
export const BTN_PRIMARY = `${BTN} bg-accent text-white hover:bg-(--color-accent-hover)`;
export const BTN_OUTLINE = `${BTN} border border-(--color-border-default) bg-white text-(--color-text-primary) hover:border-accent hover:text-accent`;
export const BTN_DANGER_OUTLINE = `${BTN} border border-red-200 bg-white text-red-700 hover:bg-red-50`;
export const BTN_DANGER = `${BTN} bg-red-600 text-white hover:bg-red-700`;

export const TEXTAREA =
  'w-full rounded-xl border border-(--color-border-default) bg-white px-3 py-2.5 text-[14px] text-(--color-text-primary) focus-visible:outline-2 focus-visible:outline-accent';
export const SELECT = 'h-11 w-full rounded-xl border border-(--color-border-default) bg-white px-3 text-[14px] text-(--color-text-primary) focus-visible:outline-2 focus-visible:outline-accent';

/** Tiền đồng: `450000` thành `450.000 đ`, cùng định dạng với trang sản phẩm công khai. */
export const vnd = productPriceLabel;

const DATE_TIME = new Intl.DateTimeFormat('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Asia/Ho_Chi_Minh' });
/** `dd/MM/yyyy HH:mm` giờ Việt Nam; chuỗi không phải ngày giờ trả rỗng. */
export const formatDateTime = (iso?: string | null): string => (iso && !Number.isNaN(Date.parse(iso)) ? DATE_TIME.format(new Date(iso)) : '');
