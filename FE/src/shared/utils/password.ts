// BR-TK-03 phía client, cùng câu báo lỗi với BE (PasswordPolicy). BE vẫn là nơi kiểm cuối.

/** Mặc định của [CFG] độ dài tối thiểu; FE không đọc được [CFG]. */
const MIN_LENGTH = 8;
/** BCrypt chỉ nhận 72 byte (ADR-0009). */
const MAX_BYTES = 72;

export function passwordPolicyError(password: string): string | null {
  if (password.length < MIN_LENGTH) return `Mật khẩu phải có ít nhất ${MIN_LENGTH} ký tự`;
  if (!/\p{L}/u.test(password) || !/\d/.test(password)) return 'Mật khẩu phải có cả chữ và số';
  if (new TextEncoder().encode(password).length > MAX_BYTES) return `Mật khẩu quá dài (tối đa ${MAX_BYTES} byte)`;
  return null;
}
