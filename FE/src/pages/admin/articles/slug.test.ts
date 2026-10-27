import { describe, expect, it } from 'vitest';
import { SLUG_PATTERN, slugify } from './slug';

describe('slugify: gợi ý đường dẫn từ tiêu đề tiếng Việt', () => {
  it('đ / Đ không có dạng tách của NFD nên phải ánh xạ tường minh: "Đường đi" → duong-di', () => {
    expect(slugify('Đường đi')).toBe('duong-di');
    expect(slugify('ĐƯỜNG ĐI')).toBe('duong-di');
    expect(slugify('đau đầu, đau đớn')).toBe('dau-dau-dau-don');
  });

  it.each([
    ['Mèo bỏ ăn 3 ngày liên tiếp — khi nào cần đưa đi cấp cứu?', 'meo-bo-an-3-ngay-lien-tiep-khi-nao-can-dua-di-cap-cuu'],
    ['Royal Canin vs Pedigree: nên chọn hãng nào cho chó cưng?', 'royal-canin-vs-pedigree-nen-chon-hang-nao-cho-cho-cung'],
    ['Vaccine 5 bệnh cho chó con: lịch tiêm', 'vaccine-5-benh-cho-cho-con-lich-tiem'],
    ['Chó sợ pháo hoa: 4 bước giúp bé bình tĩnh', 'cho-so-phao-hoa-4-buoc-giup-be-binh-tinh'],
    ['Thức ăn "hạt" cho mèo ở Hà Nội', 'thuc-an-hat-cho-meo-o-ha-noi'],
    ['Ưu đãi Ơ ờ ở ỡ ợ ừ ử ữ ự', 'uu-dai-o-o-o-o-o-u-u-u-u'],
  ])('%s → %s', (title, slug) => {
    expect(slugify(title)).toBe(slug);
  });

  it('gộp mọi dãy ký tự lạ thành một dấu gạch, bỏ gạch ở hai đầu', () => {
    expect(slugify('  --Xin chào!!  ')).toBe('xin-chao');
    expect(slugify('A  &  B / C')).toBe('a-b-c');
    expect(slugify('Café Zoë')).toBe('cafe-zoe');
  });

  it('chữ ở dạng tổ hợp (NFD) cho cùng kết quả với dạng dựng sẵn (NFC)', () => {
    expect(slugify('Đường đi'.normalize('NFD'))).toBe('duong-di');
    expect(slugify('Mèo bỏ ăn'.normalize('NFD'))).toBe(slugify('Mèo bỏ ăn'.normalize('NFC')));
  });

  it('không còn ký tự hợp lệ thì trả chuỗi rỗng (người dùng tự nhập)', () => {
    expect(slugify('')).toBe('');
    expect(slugify('   ')).toBe('');
    expect(slugify('?!…')).toBe('');
    expect(slugify('日本語')).toBe('');
  });

  it('cắt theo độ dài tối đa, không để lại dấu gạch cuối', () => {
    const long = Array.from({ length: 200 }, () => 'abc').join(' ');
    const slug = slugify(long, 270);
    expect(slug.length).toBeLessThanOrEqual(270);
    expect(slug.endsWith('-')).toBe(false);
    expect(slugify('aaaa bbbb cccc', 5)).toBe('aaaa');
    expect(slugify('aaaa bbbb cccc', 9)).toBe('aaaa-bbbb');
  });

  it('kết quả luôn khớp mẫu của BE (hoặc rỗng)', () => {
    for (const title of ['Đường đi', 'Mèo bỏ ăn 3 ngày', '  ĐƯỜNG  -- ĐI!!', 'a', '1', 'X_Y.Z', 'Ǆ Ǉ ǋ']) {
      const slug = slugify(title);
      expect(slug === '' || SLUG_PATTERN.test(slug), title).toBe(true);
    }
  });
});

describe('SLUG_PATTERN (^[a-z0-9]+(?:-[a-z0-9]+)*$ của BE)', () => {
  it.each(['a', 'duong-di', 'bai-viet-2026', '2026', 'a-b-c'])('nhận %s', (slug) => expect(SLUG_PATTERN.test(slug)).toBe(true));
  it.each(['', 'Duong-di', 'duong di', 'duong--di', '-duong', 'duong-', 'đường-đi', 'a_b', 'a/b'])('từ chối "%s"', (slug) =>
    expect(SLUG_PATTERN.test(slug)).toBe(false),
  );
});
