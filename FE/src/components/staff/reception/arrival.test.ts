import { describe, expect, it } from 'vitest';
import { arrivalWindow } from './arrival';

const MIN = 60_000;
const slot = Date.parse('2026-10-22T11:00:00+07:00');
const at = (offsetMin: number, offsetMs = 0) => arrivalWindow(slot, slot + offsetMin * MIN + offsetMs);

describe('arrivalWindow (BR-TN-02, BR-TN-03, BR-LH-08)', () => {
  it('đến sớm trong cửa sổ: giữ ưu tiên lịch hẹn', () => {
    expect(at(-11)).toEqual({ kind: 'ON_TIME', label: 'Đến sớm 11 phút' });
  });

  it('đúng giờ', () => {
    expect(at(0)).toEqual({ kind: 'ON_TIME', label: 'Đúng giờ' });
  });

  it('trễ tới 15 phút (tính cả mốc 15:00) vẫn giữ ưu tiên lịch hẹn', () => {
    expect(at(7)).toEqual({ kind: 'ON_TIME', label: 'Trễ 7 phút: vẫn giữ ưu tiên lịch hẹn' });
    expect(at(15).kind).toBe('ON_TIME');
  });

  it('trễ quá 15 phút (15:00,001): xếp như khách vãng lai, vẫn tiếp nhận được', () => {
    expect(at(15, 1)).toEqual({ kind: 'LATE', label: 'Trễ 15 phút: xếp như khách vãng lai' });
    expect(at(22)).toEqual({ kind: 'LATE', label: 'Trễ 22 phút: xếp như khách vãng lai' });
    expect(at(30).kind).toBe('LATE');
  });

  it('trễ quá 30 phút: lịch tính là không đến, tiếp nhận như khách vãng lai', () => {
    expect(at(30, 1)).toEqual({ kind: 'NO_SHOW', label: 'Quá 30 phút: lịch tính là không đến, tiếp nhận như khách vãng lai' });
  });
});
