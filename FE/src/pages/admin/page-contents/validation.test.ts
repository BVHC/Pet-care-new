import { describe, expect, it } from 'vitest';
import { PAGE_DEFAULTS, PAGE_KEYS, PAGE_LIMITS } from '../../../shared/constants/page-content';
import { validatePage } from './validation';

const errorsOf = (key: Parameters<typeof validatePage>[0], draft: unknown) => {
  const result = validatePage(key, draft);
  if (result.ok) throw new Error('đáng ra phải có lỗi');
  return result.errors;
};

describe('validatePage', () => {
  it.each(PAGE_KEYS)('nội dung mặc định của %s hợp lệ và được trả về nguyên vẹn', (key) => {
    const result = validatePage(key, PAGE_DEFAULTS[key]);
    expect(result.ok).toBe(true);
    if (result.ok) expect(result.data).toStrictEqual(PAGE_DEFAULTS[key]);
  });

  it('trả bản đã bỏ khoảng trắng hai đầu (không sửa khoảng trắng ở giữa)', () => {
    const draft = { ...PAGE_DEFAULTS.HOME_BANNER, ctaLabel: '  Đặt  lịch \n', heroStat: { value: ' 1K ', label: ' Khách\n hàng ' } };
    const result = validatePage('HOME_BANNER', draft);
    expect(result.ok && result.data.ctaLabel).toBe('Đặt  lịch');
    expect(result.ok && result.data.heroStat).toStrictEqual({ value: '1K', label: 'Khách\n hàng' });
  });

  it('chuỗi trống hoặc chỉ có khoảng trắng: "Không được để trống." theo đường dẫn chấm', () => {
    const draft = structuredClone(PAGE_DEFAULTS.HOME_BANNER);
    draft.ctaLabel = '   ';
    draft.stats[2]!.label = '';
    expect(errorsOf('HOME_BANNER', draft)).toStrictEqual({
      ctaLabel: 'Không được để trống.',
      'stats.2.label': 'Không được để trống.',
    });
  });

  it('quá giới hạn: nêu giới hạn lấy từ PAGE_LIMITS và số ký tự hiện có (đã bỏ khoảng trắng hai đầu)', () => {
    const draft = structuredClone(PAGE_DEFAULTS.ABOUT);
    draft.values[1]!.title = `  ${'Â'.repeat(PAGE_LIMITS.valueTitle + 3)}  `;
    draft.stats[0]!.value = '1'.repeat(PAGE_LIMITS.statValue + 1);
    expect(errorsOf('ABOUT', draft)).toStrictEqual({
      'values.1.title': 'Tối đa 30 ký tự (đang 33).',
      'stats.0.value': 'Tối đa 10 ký tự (đang 11).',
    });
  });

  it('đúng giới hạn thì hợp lệ', () => {
    const draft = structuredClone(PAGE_DEFAULTS.FAQ);
    draft.items[0]!.answer = 'ế'.repeat(PAGE_LIMITS.answer);
    expect(validatePage('FAQ', draft).ok).toBe(true);
  });

  it('đường dẫn lồng nhau của chính sách', () => {
    const draft = structuredClone(PAGE_DEFAULTS.POLICY);
    draft.privacy.sections[3]!.body = '';
    draft.terms.updatedOn = 'x'.repeat(PAGE_LIMITS.docUpdatedOn + 1);
    expect(errorsOf('POLICY', draft)).toStrictEqual({
      'privacy.sections.3.body': 'Không được để trống.',
      'terms.updatedOn': 'Tối đa 20 ký tự (đang 21).',
    });
  });

  it('số mục của danh sách: tối thiểu, tối đa, và số ô cố định', () => {
    const faq = structuredClone(PAGE_DEFAULTS.FAQ);
    expect(errorsOf('FAQ', { items: [] })).toStrictEqual({ items: 'Cần ít nhất 1 mục.' });
    expect(errorsOf('FAQ', { items: Array.from({ length: PAGE_LIMITS.faqItems + 1 }, () => faq.items[0]) })).toStrictEqual({
      items: 'Tối đa 20 mục.',
    });
    const banner = structuredClone(PAGE_DEFAULTS.HOME_BANNER);
    expect(errorsOf('HOME_BANNER', { ...banner, stats: banner.stats.slice(0, 3) })).toStrictEqual({ stats: 'Cần đúng 4 mục.' });
    expect(errorsOf('HOME_BANNER', { ...banner, stats: [...banner.stats, banner.stats[0]] })).toStrictEqual({ stats: 'Cần đúng 4 mục.' });
  });

  it('thiếu trường hoặc sai kiểu cũng là lỗi theo trường, không phải thông điệp thô của zod', () => {
    const errors = errorsOf('HOME_BANNER', { ...PAGE_DEFAULTS.HOME_BANNER, ctaLabel: undefined, headline: 5 });
    expect(errors).toStrictEqual({ ctaLabel: 'Không được để trống.', headline: 'Giá trị không hợp lệ.' });
    for (const message of Object.values(errors)) expect(message).not.toMatch(/Required|Expected|Invalid|String must/i);
  });
});
