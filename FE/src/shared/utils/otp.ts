/** Độ dài mã OTP mặc định của BE (`otp.code_length` [CFG] 4–8, mặc định 6, BR-TK-04). */
export const DEFAULT_OTP_LENGTH = 6;

/**
 * Độ dài mã mà BE báo trong response gửi mã (`codeLength`, đăng ký: `otpCodeLength`).
 * Vắng (BE chưa trả) hoặc không phải số nguyên trong 4–8 thì dùng mặc định.
 */
export const otpLengthOf = (value?: number | null): number =>
  value != null && Number.isInteger(value) && value >= 4 && value <= 8 ? value : DEFAULT_OTP_LENGTH;
