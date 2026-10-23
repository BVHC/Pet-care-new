import { describe, expect, it } from 'vitest';
import { describeDifference, shiftDifference } from './shift-format';

describe('chênh lệch tiền mặt của ca (countedCash − expectedCash)', () => {
  it('lấy số thực đếm trừ tiền mặt theo hệ thống', () => {
    expect(shiftDifference(570_000, 576_000)).toBe(-6_000);
    expect(shiftDifference(100_000, 100_000)).toBe(0);
    expect(shiftDifference(110_000, 100_000)).toBe(10_000);
  });

  it('nói rõ thừa, thiếu hay khớp', () => {
    expect(describeDifference(-6_000)).toMatch(/^-6\.000\s*₫ \(thiếu\)$/);
    expect(describeDifference(10_000)).toMatch(/^\+10\.000\s*₫ \(thừa\)$/);
    expect(describeDifference(0)).toMatch(/^0\s*₫ \(khớp\)$/);
  });
});
