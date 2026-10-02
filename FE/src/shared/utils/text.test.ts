import { describe, expect, it } from 'vitest';
import { foldVi } from './text';

describe('foldVi', () => {
  it('drops Vietnamese tone marks and đ for search', () => {
    expect(foldVi('Nguyễn Đức Huy')).toBe('nguyen duc huy');
  });

  it('leaves plain text lower-cased', () => {
    expect(foldVi('Pate Whiskas 85g')).toBe('pate whiskas 85g');
  });
});
