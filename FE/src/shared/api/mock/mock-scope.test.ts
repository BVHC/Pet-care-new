import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { cancelFailedClosedForRealOrders, isMockedRequest, parseMockOnly, paymentClosedForRealOrders } from './mock-scope';

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

// T67: payment chưa có controller (T65). Đơn đặt qua BE thật có id do BE cấp, còn `pay` mock tra theo id của kho mock (trùng id, hoặc 404), nên chỉ ở chế độ hỗn hợp
// "đặt đơn thật + pay mock" cổng mới đóng; toàn mock giữ cổng giả lập của T57, toàn thật gọi `pay` thật.
describe('paymentClosedForRealOrders', () => {
  const closed = (apiMock: boolean, raw: string) => paymentClosedForRealOrders(apiMock, parseMockOnly(raw));

  it('toàn mock (VITE_API_MOCK=true): cổng giả lập vẫn mở dù danh sách có luật pay', () => {
    expect(closed(true, '')).toBe(false);
    expect(closed(true, 'POST /api/me/online-orders/*/pay')).toBe(false);
  });

  it('toàn thật (không luật nào): pay đi BE thật, không chặn', () => {
    expect(closed(false, '')).toBe(false);
  });

  it('hỗn hợp: đặt đơn đi BE thật nhưng pay còn mock → cổng đóng', () => {
    expect(closed(false, 'POST /api/me/online-orders/*/pay, /api/online-orders')).toBe(true);
  });

  it('hỗn hợp: đơn cũng là mock (luật bao cả đặt đơn lẫn pay) → cổng mở, vì pay mock tra đúng đơn mock', () => {
    expect(closed(false, '/api/me/online-orders, POST /api/me/online-orders/*/pay')).toBe(false);
  });

  it('hỗn hợp: pay không còn trong danh sách (T65 xong) → cổng mở, không phải sửa mã', () => {
    expect(closed(false, '/api/online-orders, /api/refunds')).toBe(false);
  });
});

// T68: BE chưa có `cancel-failed` (shop-v1 #19, task 138). Lễ tân thấy đơn THẬT (id do BE cấp) mà `cancel-failed` mock tra theo id của kho mock
// (trùng id thì hủy nhầm đơn mock và báo thành công giả; không trùng thì 404 / 409 sai), nên chỉ ở chế độ hỗn hợp "danh sách đơn thật + cancel-failed mock" nút hủy mới khóa.
describe('cancelFailedClosedForRealOrders', () => {
  const closed = (apiMock: boolean, raw: string) => cancelFailedClosedForRealOrders(apiMock, parseMockOnly(raw));

  it('toàn mock (VITE_API_MOCK=true): hủy sau giao thất bại vẫn chạy như T57 dù danh sách có luật cancel-failed', () => {
    expect(closed(true, '')).toBe(false);
    expect(closed(true, 'POST /api/online-orders/*/cancel-failed')).toBe(false);
  });

  it('toàn thật (không luật nào): cancel-failed đi BE thật, không chặn', () => {
    expect(closed(false, '')).toBe(false);
  });

  it('hỗn hợp: danh sách đơn đi BE thật nhưng cancel-failed còn mock → khóa', () => {
    expect(closed(false, 'POST /api/online-orders/*/cancel-failed, /api/refunds')).toBe(true);
  });

  it('hỗn hợp: đơn cũng là mock (luật bao cả danh sách lẫn cancel-failed) → mở, vì cancel-failed mock tra đúng đơn mock', () => {
    expect(closed(false, '/api/online-orders')).toBe(false);
  });

  it('hỗn hợp: cancel-failed không còn trong danh sách (task 138 xong) → mở, không phải sửa mã', () => {
    expect(closed(false, '/api/refunds, POST /api/me/online-orders/*/pay')).toBe(false);
  });
});

describe('FE/.env.example: giá trị VITE_API_MOCK_ONLY đã commit (T67)', () => {
  const example = readFileSync(resolve(__dirname, '../../../../.env.example'), 'utf8');
  const line = example.split(/\r?\n/).find((l) => l.startsWith('VITE_API_MOCK_ONLY='));
  const rules = parseMockOnly(line?.slice('VITE_API_MOCK_ONLY='.length));
  const real = (method: string, path: string) => !isMockedRequest(method, path, rules);

  it('có dòng VITE_API_MOCK_ONLY không bị chú thích', () => {
    expect(line).toBeDefined();
    expect(rules.length).toBeGreaterThan(10);
  });

  it('giỏ hàng và đơn của tôi (shop-v1 #1 đến #11) đi tới BE thật', () => {
    expect(real('GET', '/api/me/cart')).toBe(true);
    expect(real('PUT', '/api/me/cart/items/1')).toBe(true);
    expect(real('DELETE', '/api/me/cart/items/1')).toBe(true);
    expect(real('DELETE', '/api/me/cart')).toBe(true);
    expect(real('GET', '/api/me/online-orders/fulfillment-branches')).toBe(true);
    expect(real('GET', '/api/me/online-orders/quote')).toBe(true);
    expect(real('POST', '/api/me/online-orders')).toBe(true);
    expect(real('GET', '/api/me/online-orders')).toBe(true);
    expect(real('GET', '/api/me/online-orders/5')).toBe(true);
    expect(real('POST', '/api/me/online-orders/5/cancel')).toBe(true);
    expect(real('POST', '/api/me/online-orders/5/confirm-received')).toBe(true);
  });

  it('thanh toán còn mock tới T65: pay của đơn, giao dịch, callback, đối soát', () => {
    expect(real('POST', '/api/me/online-orders/5/pay')).toBe(false);
    expect(real('GET', '/api/me/payment-transactions')).toBe(false);
    expect(real('POST', '/api/public/payment-gateway/callback')).toBe(false);
    expect(real('GET', '/api/payment-reconciliations')).toBe(false);
  });

  it('phía lễ tân (T68, shop-v1 #12 đến #18) đi tới BE thật', () => {
    expect(real('GET', '/api/online-orders')).toBe(true);
    expect(real('GET', '/api/online-orders/5')).toBe(true);
    expect(real('POST', '/api/online-orders/5/start-processing')).toBe(true);
    expect(real('POST', '/api/online-orders/5/ship')).toBe(true);
    expect(real('POST', '/api/online-orders/5/mark-delivered')).toBe(true);
    expect(real('POST', '/api/online-orders/5/fail-delivery')).toBe(true);
    expect(real('POST', '/api/online-orders/5/redeliver')).toBe(true);
  });

  it('hủy sau giao thất bại (#19) còn mock tới task 138 (BE chưa có controller)', () => {
    expect(real('POST', '/api/online-orders/5/cancel-failed')).toBe(false);
  });

  it('nhân sự (T66, workforce-v1 #1 đến #17) đi tới BE thật: không còn luật shift-* nào', () => {
    expect(real('GET', '/api/shift-definitions')).toBe(true);
    expect(real('POST', '/api/shift-definitions')).toBe(true);
    expect(real('PATCH', '/api/shift-definitions/5')).toBe(true);
    expect(real('GET', '/api/shift-assignments')).toBe(true);
    expect(real('POST', '/api/shift-assignments')).toBe(true);
    expect(real('POST', '/api/shift-assignments/5/cancel')).toBe(true);
    expect(real('POST', '/api/shift-assignments/5/absent')).toBe(true);
    expect(real('GET', '/api/me/shift-assignments')).toBe(true);
    expect(real('POST', '/api/me/shift-requests')).toBe(true);
    expect(real('GET', '/api/me/shift-requests')).toBe(true);
    expect(real('POST', '/api/me/shift-requests/5/withdraw')).toBe(true);
    expect(real('GET', '/api/shift-requests')).toBe(true);
    expect(real('POST', '/api/shift-requests/5/approve')).toBe(true);
    expect(real('POST', '/api/shift-requests/5/reject')).toBe(true);
    expect(real('PUT', '/api/me/shift-registrations/2026-11-30')).toBe(true);
    expect(real('GET', '/api/me/shift-registrations/2026-11-30')).toBe(true);
    expect(real('GET', '/api/shift-weekly-summaries')).toBe(true);
    expect(line).not.toMatch(/shift-/);
  });

  it('chế độ hỗn hợp của file này đóng cổng thanh toán cho đơn thật, và không đóng khi toàn mock', () => {
    expect(paymentClosedForRealOrders(false, rules)).toBe(true);
    expect(paymentClosedForRealOrders(true, rules)).toBe(false);
  });

  it('chế độ hỗn hợp của file này khóa hủy sau giao thất bại cho đơn thật, và không khóa khi toàn mock', () => {
    expect(cancelFailedClosedForRealOrders(false, rules)).toBe(true);
    expect(cancelFailedClosedForRealOrders(true, rules)).toBe(false);
  });
});
