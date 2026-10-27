import { formatPublishedDate } from '../../../shared/utils/public-format';
import { vnClock } from '../../../shared/utils/vn-time';

const two = (n: number) => String(n).padStart(2, '0');

/** `dd/MM/yyyy HH:mm` theo giờ Việt Nam (+07:00 cố định), không phụ thuộc múi giờ máy; chuỗi không phải ngày giờ trả rỗng. */
export function formatDateTimeVn(iso: string): string {
  const ms = Date.parse(iso);
  if (Number.isNaN(ms)) return '';
  const { minutes } = vnClock(ms);
  return `${formatPublishedDate(iso)} ${two(Math.floor(minutes / 60))}:${two(minutes % 60)}`;
}
