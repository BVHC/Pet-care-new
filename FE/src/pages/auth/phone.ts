/** SĐT VN: 0 + 9 số, cho phép khoảng trắng / gạch khi gõ. Dùng chung cho RegisterPage và hộp thoại Google. */
export const PHONE_RE = /^0\d{9}$/;

/** Bỏ khoảng trắng và gạch; trống thì `undefined` (không bao giờ gửi chuỗi rỗng). */
export const normalizePhone = (raw: string): string | undefined => raw.replace(/[\s-]/g, '') || undefined;

/** Lỗi của ô SĐT; ô không bắt buộc nên để trống là hợp lệ. */
export function phoneError(raw: string): string | undefined {
  if (!raw.trim()) return undefined;
  return PHONE_RE.test(raw.replace(/[\s-]/g, '')) ? undefined : 'Số điện thoại phải gồm 10 số và bắt đầu bằng 0.';
}
