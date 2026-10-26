// Hàm thuần cho trang công khai (T46): nhãn giá (BR-CK-02), ngày xuất bản, tên chi nhánh còn hàng (BR-CK-03).
import type { PublicBranch } from '../types/branch';
import type { PublicService } from '../types/catalog';
import { vnDate } from './vn-time';

/** `450000` thành `450.000 đ`. */
const vnd = (amount: number) => `${new Intl.NumberFormat('vi-VN').format(amount)} đ`;

/**
 * Khám / tiêm / thẩm mỹ: "Từ X đ" khi `priceIsFrom`, ngược lại "X đ" (ghi chú chi phí phụ thuộc chỉ định do trang thêm).
 * Loại chuồng (BOARDING): giá theo đêm, không có "Từ".
 */
export function servicePriceLabel({ group, price, priceIsFrom }: Pick<PublicService, 'group' | 'price' | 'priceIsFrom'>): string {
  if (group === 'BOARDING') return `${vnd(price)} / đêm`;
  return `${priceIsFrom ? 'Từ ' : ''}${vnd(price)}`;
}

/** Giá bán sản phẩm, thống nhất toàn chuỗi. */
export const productPriceLabel = (price: number): string => vnd(price);

/**
 * `dd/MM/yyyy` theo giờ Việt Nam (+07:00 cố định, docs/time.md), không phụ thuộc múi giờ máy; nhận cả `…+07:00` và `…Z`.
 * Chuỗi không phải ngày giờ trả rỗng.
 */
export function formatPublishedDate(iso: string): string {
  const ms = Date.parse(iso);
  if (Number.isNaN(ms)) return '';
  const [year, month, day] = vnDate(ms).split('-');
  return `${day}/${month}/${year}`;
}

/**
 * Tên các chi nhánh trong `ids`, theo thứ tự danh sách `branches` (BE đã sắp theo tên); id không có trong danh sách
 * (chi nhánh không ACTIVE) bị bỏ, id trùng chỉ hiện một lần.
 */
export function branchNamesByIds(ids: number[], branches: Pick<PublicBranch, 'branchId' | 'name'>[]): string[] {
  const wanted = new Set(ids);
  return branches.filter((b) => wanted.has(b.branchId)).map((b) => b.name);
}
