import { describe, expect, it } from 'vitest';
import type { PageKey } from '../types/page-content';
import {
  PAGE_CONTENT_MAX_BYTES,
  PAGE_DEFAULTS,
  PAGE_KEYS,
  PAGE_LABELS,
  PAGE_LIMITS,
  PAGE_SCHEMAS,
  pageContentBytes,
  parsePageContent,
} from './page-content';

describe('khóa và nhãn trang', () => {
  it('đúng 4 khóa theo thứ tự BE trả (HOME_BANNER, ABOUT, POLICY, FAQ), mỗi khóa có nhãn tiếng Việt', () => {
    expect(PAGE_KEYS).toEqual(['HOME_BANNER', 'ABOUT', 'POLICY', 'FAQ']);
    expect(Object.keys(PAGE_LABELS)).toEqual([...PAGE_KEYS]);
    expect(Object.keys(PAGE_DEFAULTS)).toEqual([...PAGE_KEYS]);
    expect(Object.keys(PAGE_SCHEMAS)).toEqual([...PAGE_KEYS]);
    expect(PAGE_LABELS).toEqual({ HOME_BANNER: 'Banner trang chủ', ABOUT: 'Giới thiệu', POLICY: 'Chính sách', FAQ: 'Câu hỏi thường gặp' });
  });
});

describe('mặc định = đúng chữ các trang hiển thị trước UC21', () => {
  it.each(PAGE_KEYS)('%s: mặc định qua được schema của chính nó và không bị bỏ khoảng trắng / cắt bớt', (key) => {
    const parsed = PAGE_SCHEMAS[key].safeParse(PAGE_DEFAULTS[key]);
    expect(parsed.success, JSON.stringify(parsed.success ? null : parsed.error.issues)).toBe(true);
    expect(parsed.success && parsed.data).toEqual(PAGE_DEFAULTS[key]);
  });

  it('chữ mặc định lấy nguyên văn từ các khối tĩnh cũ (hero, dải số liệu, giá trị, FAQ, điều khoản, bảo mật)', () => {
    const { HOME_BANNER: home, ABOUT: about, POLICY: policy, FAQ: faq } = PAGE_DEFAULTS;
    expect(home.headline).toBe('Pet\nCare');
    expect(home.subheadline).toBe('Tận tâm chăm sóc\nbé cưng của bạn');
    expect(home.ctaLabel).toBe('Đặt Lịch Ngay');
    expect(home.heroStat).toEqual({ value: '2.500+', label: 'Chủ thú cưng\ntin tưởng' });
    expect(home.stats.map((s) => s.label)).toEqual(['Thú cưng được chăm sóc', 'Bác sĩ & chuyên gia', 'Giao hàng tận nơi', 'Đánh giá hài lòng']);
    expect(about.stats.map((s) => `${s.value} ${s.label}`)).toEqual([
      '10K+ Khách hàng tin tưởng',
      '15+ Năm kinh nghiệm',
      '50+ Bác sĩ chuyên nghiệp',
      '4.9/5 Đánh giá trung bình',
    ]);
    expect(about.values.map((v) => v.title)).toEqual(['Yêu thương', 'An toàn', 'Chuyên nghiệp', 'Chất lượng']);
    expect(policy.terms.sections).toHaveLength(7);
    expect(policy.privacy.sections).toHaveLength(7);
    expect(policy.terms.updatedOn).toBe('16/09/2026');
    expect(policy.terms.sections[0].body.split('\n')).toHaveLength(2);
    expect(faq.items.map((f) => f.question)[0]).toBe('PetCare hoạt động ở khu vực nào?');
    expect(faq.items).toHaveLength(4);
  });

  it('chỉ văn bản thuần: không có thẻ HTML nào trong mặc định (ADR-0071)', () => {
    expect(JSON.stringify(PAGE_DEFAULTS)).not.toMatch(/<[a-z/!]/i);
  });
});

describe('parsePageContent: dùng mặc định CHỈ khi {} hoặc sai dạng', () => {
  it.each(PAGE_KEYS)('%s: {} (BE seed), null, mảng, chuỗi, số → mặc định', (key) => {
    for (const raw of [{}, undefined, null, [], 'x', 5, true]) expect(parsePageContent(key, raw)).toEqual(PAGE_DEFAULTS[key]);
  });

  it('nội dung hợp lệ thì dùng nội dung đó, bỏ khoảng trắng hai đầu và trường lạ', () => {
    const custom = { ...PAGE_DEFAULTS.HOME_BANNER, headline: '  Mèo\nChó  ', unknown: 'x' };
    const parsed = parsePageContent('HOME_BANNER', custom);
    expect(parsed.headline).toBe('Mèo\nChó');
    expect(parsed).not.toHaveProperty('unknown');
    expect(parsed.stats).toEqual(PAGE_DEFAULTS.HOME_BANNER.stats);
  });

  it('sai dạng (kiểu, thiếu trường, rỗng, vượt giới hạn, sai số ô cố định) thì cả bộ về mặc định, không trộn từng trường', () => {
    const base = PAGE_DEFAULTS.HOME_BANNER;
    const broken: unknown[] = [
      { ...base, headline: 5 },
      { ...base, headline: '   ' },
      { ...base, headline: 'x'.repeat(PAGE_LIMITS.headline + 1) },
      { ...base, ctaLabel: undefined },
      { ...base, heroStat: { value: '1' } },
      { ...base, stats: base.stats.slice(0, 3) },
      { ...base, stats: [...base.stats, base.stats[0]] },
      { ...base, stats: 'nhiều' },
    ];
    for (const raw of broken) expect(parsePageContent('HOME_BANNER', raw)).toEqual(base);
    // Một phần đúng, một phần sai: bản đúng KHÔNG lọt qua.
    expect(parsePageContent('HOME_BANNER', { ...base, headline: 'Mèo', subheadline: 7 }).headline).toBe(base.headline);
  });

  it('FAQ: 1..20 mục; điều khoản: 1..20 mục mỗi tài liệu; ABOUT: đúng 4 giá trị', () => {
    const item = { question: 'Hỏi?', answer: 'Đáp.' };
    expect(parsePageContent('FAQ', { items: [item] }).items).toEqual([item]);
    expect(parsePageContent('FAQ', { items: [] })).toEqual(PAGE_DEFAULTS.FAQ);
    expect(parsePageContent('FAQ', { items: Array(PAGE_LIMITS.faqItems).fill(item) }).items).toHaveLength(PAGE_LIMITS.faqItems);
    expect(parsePageContent('FAQ', { items: Array(PAGE_LIMITS.faqItems + 1).fill(item) })).toEqual(PAGE_DEFAULTS.FAQ);
    const about = PAGE_DEFAULTS.ABOUT;
    expect(parsePageContent('ABOUT', { ...about, values: about.values.slice(1) })).toEqual(about);
    const policy = PAGE_DEFAULTS.POLICY;
    expect(parsePageContent('POLICY', { ...policy, terms: { ...policy.terms, sections: [] } })).toEqual(policy);
  });

  it('thẻ HTML gõ vào chỉ là chữ: parse giữ nguyên chuỗi, không lọc, không escape', () => {
    const evil = { ...PAGE_DEFAULTS.HOME_BANNER, headline: '<b>Mèo</b><i>x</i>' };
    expect(parsePageContent('HOME_BANNER', evil).headline).toBe('<b>Mèo</b><i>x</i>');
  });

  it('kiểu trả về theo khóa: mỗi khóa đúng nhóm trường của nó', () => {
    const keys: Record<PageKey, string[]> = {
      HOME_BANNER: ['ctaLabel', 'headline', 'heroStat', 'stats', 'subheadline'],
      ABOUT: ['stats', 'values'],
      POLICY: ['privacy', 'terms'],
      FAQ: ['items'],
    };
    for (const key of PAGE_KEYS) expect(Object.keys(parsePageContent(key, {})).sort()).toEqual(keys[key]);
  });
});

describe('pageContentBytes: byte UTF-8 của JSON gọn, như BE đếm', () => {
  it('ASCII: 1 byte/ký tự; tiếng Việt có dấu: 2–3 byte/ký tự', () => {
    expect(PAGE_CONTENT_MAX_BYTES).toBe(65_536);
    expect(pageContentBytes({ a: 'x' })).toBe(9); // {"a":"x"}
    expect(pageContentBytes({ a: 'ế' })).toBe(11); // 'ế' = 3 byte
    expect(pageContentBytes({ a: 'ă' })).toBe(10); // 'ă' = 2 byte
    expect(pageContentBytes({})).toBe(2);
  });

  it('khác số ký tự của chuỗi JSON', () => {
    const content = { a: 'Chăm sóc thú cưng ế'.repeat(10) };
    expect(pageContentBytes(content)).toBeGreaterThan(JSON.stringify(content).length);
  });
});
