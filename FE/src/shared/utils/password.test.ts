import { describe, expect, it } from 'vitest';
import { passwordPolicyError } from './password';

// BR-TK-03: tối thiểu 8 ký tự [CFG], có cả chữ và số; BCrypt nhận tối đa 72 byte (ADR-0009).
describe('passwordPolicyError', () => {
  it.each([
    ['MatKhau1', null],
    ['Mật khẩu 1', null],
    ['Ab1', 'Mật khẩu phải có ít nhất 8 ký tự'],
    ['abcdefgh', 'Mật khẩu phải có cả chữ và số'],
    ['12345678', 'Mật khẩu phải có cả chữ và số'],
  ])('%s → %s', (password, expected) => {
    expect(passwordPolicyError(password)).toBe(expected);
  });

  it('accepts exactly 72 bytes and refuses one byte more', () => {
    const at72 = 'a1' + 'é'.repeat(35); // 2 + 35 × 2 byte UTF-8
    expect(passwordPolicyError(at72)).toBeNull();
    expect(passwordPolicyError(at72 + 'x')).toBe('Mật khẩu quá dài (tối đa 72 byte)');
  });
});
