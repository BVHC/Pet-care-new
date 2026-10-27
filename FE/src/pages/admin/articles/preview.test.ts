import { describe, expect, it } from 'vitest';
import { formatDateTimeVn } from './format';
import { previewDocument } from './preview';

describe('previewDocument: khung srcDoc của iframe xem trước', () => {
  it('đặt nguyên HTML người soạn vào body, kèm CSS tối thiểu để đọc được', () => {
    const doc = previewDocument('<h2>Tiêu đề</h2><p>Nội dung</p>');
    expect(doc).toContain('<body><h2>Tiêu đề</h2><p>Nội dung</p></body>');
    expect(doc).toMatch(/<style>[^<]*font[^<]*<\/style>/);
    expect(doc).toContain('<meta charset="utf-8">');
  });

  it('thêm lớp CSP: không script, không kết nối, chỉ ảnh https và CSS nội tuyến', () => {
    const csp = /<meta http-equiv="Content-Security-Policy" content="([^"]+)">/.exec(previewDocument(''))?.[1] ?? '';
    expect(csp).toContain("default-src 'none'");
    expect(csp).toContain('img-src https:');
    expect(csp).not.toMatch(/script-src|unsafe-eval|connect-src/);
  });

  it('chính khung không chứa script', () => {
    expect(previewDocument('')).not.toMatch(/<script/i);
  });
});

describe('formatDateTimeVn: dd/MM/yyyy HH:mm theo giờ Việt Nam, không phụ thuộc múi giờ máy', () => {
  it.each([
    ['2026-07-28T09:00:00+07:00', '28/07/2026 09:00'],
    ['2026-10-26T03:00:00Z', '26/10/2026 10:00'],
    ['2026-10-25T18:30:00Z', '26/10/2026 01:30'],
    ['2026-12-31T16:59:00Z', '31/12/2026 23:59'],
  ])('%s → %s', (iso, text) => expect(formatDateTimeVn(iso)).toBe(text));

  it('chuỗi không phải ngày giờ trả rỗng', () => expect(formatDateTimeVn('hôm qua')).toBe(''));
});
