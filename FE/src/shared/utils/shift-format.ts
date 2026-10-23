// Chênh lệch tiền mặt của ca thu ngân. BE chỉ ghi `difference` khi đối soát (ADR-0064 §3.2); ca CLOSED thì FE tự tính từ
// số thực đếm và tiền mặt theo hệ thống để lễ tân / quản lý thấy ngay.
import { formatCurrency } from '../../lib/utils';

/** countedCash − expectedCash: âm là thiếu tiền so với phiếu thu, dương là thừa. */
export const shiftDifference = (countedCash: number, expectedCash: number): number => countedCash - expectedCash;

export function describeDifference(difference: number): string {
  if (difference === 0) return `${formatCurrency(0)} (khớp)`;
  return difference > 0 ? `+${formatCurrency(difference)} (thừa)` : `${formatCurrency(difference)} (thiếu)`;
}
