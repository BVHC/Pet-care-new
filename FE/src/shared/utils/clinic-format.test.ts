import { describe, expect, it } from 'vitest';
import { ageLabel, formatElapsed, petKind } from './clinic-format';

const NOW = Date.parse('2026-10-02T10:00:00+07:00');
const ago = (min: number) => new Date(NOW - min * 60_000).toISOString();

describe('formatElapsed', () => {
  it('says "vừa xong" under a minute', () => {
    expect(formatElapsed(ago(0.5), NOW)).toBe('vừa xong');
  });

  it('counts minutes under an hour', () => {
    expect(formatElapsed(ago(12), NOW)).toBe('12 phút');
  });

  it('shows hours and minutes', () => {
    expect(formatElapsed(ago(65), NOW)).toBe('1 giờ 5 phút');
  });

  it('drops zero minutes', () => {
    expect(formatElapsed(ago(120), NOW)).toBe('2 giờ');
  });
});

describe('ageLabel', () => {
  it('labels age in years', () => {
    expect(ageLabel(2019, NOW)).toBe('7 tuổi');
  });

  it('labels a pet born this year', () => {
    expect(ageLabel(2026, NOW)).toBe('dưới 1 tuổi');
  });
});

describe('petKind', () => {
  it('prefixes the species to a breed name', () => {
    expect(petKind('DOG', 'Golden Retriever')).toBe('Chó Golden Retriever');
  });

  it('does not repeat a species the breed already names', () => {
    expect(petKind('CAT', 'Mèo ta')).toBe('Mèo ta');
  });
});
