import { describe, expect, it } from 'vitest';
import { DEFAULT_OTP_LENGTH, otpLengthOf } from './otp';

// BE `otp.code_length` [CFG] nằm trong 4–8, mặc định 6; response gửi mã trả thêm độ dài đó (codeLength / otpCodeLength).
// Trường vắng (BE chưa làm) hoặc không hợp lệ thì FE dùng 6.

describe('otpLengthOf', () => {
  it('mặc định là 6', () => {
    expect(DEFAULT_OTP_LENGTH).toBe(6);
  });

  it.each([undefined, null])('%s (BE chưa trả độ dài) -> 6', (value) => {
    expect(otpLengthOf(value)).toBe(6);
  });

  it.each([4, 5, 6, 7, 8])('nhận %i (số nguyên trong 4–8)', (value) => {
    expect(otpLengthOf(value)).toBe(value);
  });

  it.each([3, 9, 0, -6, 100])('%i nằm ngoài 4–8 -> 6', (value) => {
    expect(otpLengthOf(value)).toBe(6);
  });

  it.each([6.5, 4.0001, NaN, Infinity, -Infinity])('%s không phải số nguyên hợp lệ -> 6', (value) => {
    expect(otpLengthOf(value)).toBe(6);
  });

  it.each(['8', true])('kiểu sai %j (BE trả bậy) -> 6', (value) => {
    expect(otpLengthOf(value as unknown as number)).toBe(6);
  });
});
