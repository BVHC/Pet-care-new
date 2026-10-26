import { describe, expect, it } from 'vitest';
import { isMockedRequest, parseMockOnly } from './mock-scope';

// VITE_API_MOCK_ONLY: danh sách đường dẫn (cách nhau bằng dấu phẩy) mà chế độ hỗn hợp vẫn trả bằng mock,
// còn mọi đường dẫn khác đi tới BE thật. Mỗi mục: `[METHOD ]/duong/dan`, `*` thay đúng một đoạn.

const mocked = (raw: string, method: string, pathname: string) => isMockedRequest(method, pathname, parseMockOnly(raw));

describe('parseMockOnly', () => {
  it('không đặt, rỗng hoặc chỉ khoảng trắng: không có luật nào', () => {
    expect(parseMockOnly(undefined)).toEqual([]);
    expect(parseMockOnly('')).toEqual([]);
    expect(parseMockOnly('   ,  , ')).toEqual([]);
  });

  it('tách theo dấu phẩy, bỏ khoảng trắng thừa', () => {
    const rules = parseMockOnly(' /api/public/branches , /api/public/services ');
    expect(rules).toEqual([
      { segments: ['api', 'public', 'branches'] },
      { segments: ['api', 'public', 'services'] },
    ]);
  });

  it('nhận phương thức đứng trước đường dẫn, viết hoa hóa', () => {
    expect(parseMockOnly('get /api/pets/*/health-record')).toEqual([
      { method: 'GET', segments: ['api', 'pets', '*', 'health-record'] },
    ]);
  });

  it('bỏ mục không bắt đầu bằng "/" (gõ nhầm thì không vô tình mock cả hệ thống)', () => {
    expect(parseMockOnly('api/public, GET public, /api/ok')).toEqual([{ segments: ['api', 'ok'] }]);
  });
});

describe('isMockedRequest', () => {
  it('không có luật thì không mock gì', () => {
    expect(mocked('', 'GET', '/api/public/branches')).toBe(false);
  });

  it('khớp theo tiền tố ở ranh giới đoạn: /api/public khớp /api/public/branches/1 nhưng không khớp /api/publicity', () => {
    expect(mocked('/api/public', 'GET', '/api/public')).toBe(true);
    expect(mocked('/api/public', 'GET', '/api/public/branches/1')).toBe(true);
    expect(mocked('/api/public', 'GET', '/api/publicity')).toBe(false);
    expect(mocked('/api/public/branches', 'GET', '/api/public')).toBe(false);
  });

  it('"*" thay đúng một đoạn', () => {
    const raw = '/api/pets/*/health-record';
    expect(mocked(raw, 'GET', '/api/pets/7/health-record')).toBe(true);
    expect(mocked(raw, 'GET', '/api/pets/health-record')).toBe(false);
    expect(mocked(raw, 'GET', '/api/pets/7/8/health-record')).toBe(false);
    expect(mocked(raw, 'GET', '/api/pets/7')).toBe(false);
  });

  it('có phương thức thì chỉ khớp đúng phương thức đó (không phân biệt hoa thường)', () => {
    expect(mocked('GET /api/me/notification-settings', 'get', '/api/me/notification-settings')).toBe(true);
    expect(mocked('GET /api/me/notification-settings', 'PUT', '/api/me/notification-settings')).toBe(false);
  });

  it('không có phương thức thì khớp mọi phương thức', () => {
    expect(mocked('/api/care-tasks', 'POST', '/api/care-tasks/3/complete')).toBe(true);
  });

  it('đường dẫn phân biệt hoa thường và bỏ dấu "/" cuối', () => {
    expect(mocked('/api/public/branches', 'GET', '/api/Public/branches')).toBe(false);
    expect(mocked('/api/public/branches', 'GET', '/api/public/branches/')).toBe(true);
  });

  it('chỉ cần một luật khớp', () => {
    const raw = '/api/public/branches, POST /api/visits/*/vaccinations';
    expect(mocked(raw, 'POST', '/api/visits/9/vaccinations')).toBe(true);
    expect(mocked(raw, 'GET', '/api/visits/9/vaccinations')).toBe(false);
    expect(mocked(raw, 'GET', '/api/public/branches')).toBe(true);
    expect(mocked(raw, 'GET', '/api/orders')).toBe(false);
  });
});
