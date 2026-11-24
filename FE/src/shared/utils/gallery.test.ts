import { describe, expect, it } from 'vitest';
import { uniqueUrls } from './gallery';

describe('uniqueUrls', () => {
  it('giữ thứ tự, bỏ rỗng, null, undefined và chỉ toàn khoảng trắng', () => {
    expect(uniqueUrls(['/a.jpg', '', '   ', null, undefined, '/b.jpg'])).toEqual(['/a.jpg', '/b.jpg']);
  });

  it('bỏ khoảng trắng hai đầu rồi mới so trùng, giữ lần xuất hiện đầu', () => {
    expect(uniqueUrls(['/a.jpg', ' /b.jpg ', '/a.jpg', '/b.jpg', '/c.jpg'])).toEqual(['/a.jpg', '/b.jpg', '/c.jpg']);
  });

  it('phân biệt hoa thường (URL phân biệt)', () => {
    expect(uniqueUrls(['/A.jpg', '/a.jpg'])).toEqual(['/A.jpg', '/a.jpg']);
  });
});
