import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it } from 'vitest';
import { ruleIdOf } from '../api-error';
import type { HomeVisitFee, MaterialNorm, ShippingFeeConfig } from '../../types/fees';
import { homeVisitFeeOf, materialNormsOf, shippingFeeConfig } from './fees.mock';
import { MOCK_PASSWORD } from './identity.mock';
import { installMockApi } from './mock-api';

// Dữ liệu mẫu (fees.mock.ts): phí vận chuyển 30.000 / miễn phí từ 500.000; khám tại nhà: chi nhánh 1 bật 100.000, chi nhánh 2 tắt (lưu 80.000), chi nhánh 3 chưa cấu hình;
// định mức dịch vụ 1: Amoxicillin (#3) × 2, Vitamin (#4) × 1; dịch vụ 4: Pate (#2) × 1.

let client: AxiosInstance;

beforeEach(() => {
  client = axios.create();
  installMockApi(client);
});

const EMAILS = {
  admin: 'admin@petcare.vn',
  chuoi: 'chuoi@petcare.vn',
  manager: 'manager@store1.vn',
  manager2: 'manager2@store2.vn',
  reception: 'reception@store1.vn',
  doctor: 'doctor@store1.vn',
  caretaker: 'caretaker@store1.vn',
  khach: 'khach@petcare.vn',
} as const;
type Who = keyof typeof EMAILS;

async function as(who: Who) {
  const res = await client.post('/api/auth/login', { email: EMAILS[who], password: MOCK_PASSWORD });
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}

async function status(request: Promise<AxiosResponse>): Promise<number> {
  try {
    return (await request).status;
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return e.response.status;
    throw e;
  }
}
async function failure(request: Promise<unknown>): Promise<AxiosResponse> {
  try {
    await request;
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return e.response;
    throw e;
  }
  throw new Error('expected the request to fail');
}

type Call = (auth: object) => Promise<AxiosResponse>;
const CALLS: Record<string, Call> = {
  'GET /shipping-fee-config': (a) => client.get('/api/shipping-fee-config', a),
  'PUT /shipping-fee-config': (a) => client.put('/api/shipping-fee-config', { baseFee: 25_000, freeThreshold: 300_000 }, a),
  'GET /branches/1/home-visit-fee': (a) => client.get('/api/branches/1/home-visit-fee', a),
  'PUT /branches/1/home-visit-fee': (a) => client.put('/api/branches/1/home-visit-fee', { enabled: true, fee: 120_000 }, a),
  'GET /services/1/material-norms': (a) => client.get('/api/services/1/material-norms', a),
  'PUT /services/1/material-norms': (a) => client.put('/api/services/1/material-norms', { items: [{ productId: 3, quantity: 3 }] }, a),
};
const SUPER_ONLY: Who[] = ['chuoi'];
/** fees-v1 #3 "Quyền:": SUPER_MANAGER, CUSTOMER, A06 (lễ tân) và A07 (bác sĩ) của chính chi nhánh 1. */
const HOME_VISIT_READERS: Who[] = ['chuoi', 'khach', 'reception', 'doctor'];
const ALLOWED: Record<string, Who[]> = {
  'GET /shipping-fee-config': SUPER_ONLY,
  'PUT /shipping-fee-config': SUPER_ONLY,
  'GET /branches/1/home-visit-fee': HOME_VISIT_READERS,
  'PUT /branches/1/home-visit-fee': SUPER_ONLY,
  'GET /services/1/material-norms': SUPER_ONLY,
  'PUT /services/1/material-norms': SUPER_ONLY,
};

describe('phân quyền: 6 endpoint × 8 tài khoản (dòng "Quyền:" của yaml) và 401', () => {
  const ROLES = Object.keys(EMAILS) as Who[];
  const cases = Object.keys(CALLS).flatMap((endpoint) => ROLES.map((who) => [endpoint, who, ALLOWED[endpoint].includes(who) ? 200 : 403] as const));
  it.each(cases)('%s với %s → %i', async (endpoint, who, expected) => {
    expect(await status(CALLS[endpoint](await as(who)))).toBe(expected);
  });

  it.each(Object.keys(CALLS))('%s không token → 401', async (endpoint) => {
    const res = await failure(CALLS[endpoint]({}));
    expect(res.status).toBe(401);
    expect(res.data.errorCode).toBe('UNAUTHENTICATED');
  });

  it('phí khám tại nhà: lễ tân, bác sĩ chỉ xem chi nhánh mình (403 phạm vi); chuỗi và khách xem được mọi chi nhánh', async () => {
    for (const who of ['reception', 'doctor'] as const) {
      const res = await failure(client.get('/api/branches/2/home-visit-fee', await as(who)));
      expect(res.status).toBe(403);
      expect(res.data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
      expect(ruleIdOf(res.data.message)).toBeNull();
    }
    for (const who of ['chuoi', 'khach'] as const) expect(await status(client.get('/api/branches/2/home-visit-fee', await as(who)))).toBe(200);
  });
});

describe('phí vận chuyển (#1, #2, BR-SP-08)', () => {
  it('đọc cấu hình mẫu, lưu rồi đọc lại; hàm export phản chiếu cấu hình hiện hành', async () => {
    const auth = await as('chuoi');
    const before = (await client.get('/api/shipping-fee-config', auth)).data.data as ShippingFeeConfig;
    expect(before).toMatchObject({ baseFee: 30_000, freeThreshold: 500_000 });
    expect(Date.parse(before.updatedAt)).toBeLessThan(Date.now());
    expect(shippingFeeConfig()).toEqual(before);

    const saved = await client.put('/api/shipping-fee-config', { baseFee: 25_000, freeThreshold: 0 }, auth);
    expect(saved.status).toBe(200);
    expect(saved.data.data).toMatchObject({ baseFee: 25_000, freeThreshold: 0 });
    expect(Date.parse(saved.data.data.updatedAt)).toBeGreaterThanOrEqual(Date.parse(before.updatedAt));
    expect((await client.get('/api/shipping-fee-config', auth)).data.data).toEqual(saved.data.data);
    expect(shippingFeeConfig().baseFee).toBe(25_000);
  });

  it('số âm hoặc số thập phân → 400 BR-SP-08; không ghi gì', async () => {
    const auth = await as('chuoi');
    for (const body of [{ baseFee: -1, freeThreshold: 0 }, { baseFee: 1000, freeThreshold: -5 }, { baseFee: 1000.5, freeThreshold: 0 }]) {
      const res = await failure(client.put('/api/shipping-fee-config', body, auth));
      expect(res.status).toBe(400);
      expect(res.data.errorCode).toBe('BUSINESS_RULE_VIOLATION');
      expect(ruleIdOf(res.data.message)).toBe('BR-SP-08');
    }
    expect(shippingFeeConfig().baseFee).toBe(30_000);
  });

  it('sai kiểu JSON → 400 MALFORMED_REQUEST; thiếu trường → 400 VALIDATION_FAILED theo trường', async () => {
    const auth = await as('chuoi');
    expect((await failure(client.put('/api/shipping-fee-config', { baseFee: 'abc', freeThreshold: 0 }, auth))).data.errorCode).toBe('MALFORMED_REQUEST');
    const missing = await failure(client.put('/api/shipping-fee-config', { baseFee: 1000 }, auth));
    expect(missing.data.errorCode).toBe('VALIDATION_FAILED');
    expect(missing.data.message).toContain('freeThreshold:');
    expect((await failure(client.put('/api/shipping-fee-config', null, auth))).data.errorCode).toBe('MALFORMED_REQUEST');
  });

  it('thứ tự: body thiếu trường → 400 trước 403 vai trò; body đúng mà sai vai trò → 403', async () => {
    const auth = await as('khach');
    expect((await failure(client.put('/api/shipping-fee-config', {}, auth))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(client.put('/api/shipping-fee-config', { baseFee: 1, freeThreshold: 1 }, auth))).data.errorCode).toBe('ACCESS_DENIED');
  });
});

describe('phí khám tại nhà (#3, #4, BR-SP-09)', () => {
  it('chi nhánh chưa cấu hình trả enabled = false, fee = 0; chi nhánh tắt giữ phí đã lưu', async () => {
    const auth = await as('chuoi');
    expect((await client.get('/api/branches/3/home-visit-fee', auth)).data.data).toEqual({ branchId: 3, enabled: false, fee: 0 });
    expect((await client.get('/api/branches/1/home-visit-fee', auth)).data.data).toEqual({ branchId: 1, enabled: true, fee: 100_000 });
    expect((await client.get('/api/branches/2/home-visit-fee', auth)).data.data).toEqual({ branchId: 2, enabled: false, fee: 80_000 });
  });

  it('chi nhánh không có → 404; id sai kiểu → 400 MALFORMED_REQUEST; 404 đến trước BR-SP-09', async () => {
    const auth = await as('chuoi');
    expect(await status(client.get('/api/branches/99/home-visit-fee', auth))).toBe(404);
    expect((await failure(client.get('/api/branches/abc/home-visit-fee', auth))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect(await status(client.put('/api/branches/99/home-visit-fee', { enabled: true, fee: -5 }, auth))).toBe(404);
  });

  it('lưu: bật chi nhánh 3 thì homeVisitFeeOf trả phí; tắt thì trả null (chưa bật không nhận đặt lịch)', async () => {
    const auth = await as('chuoi');
    expect(homeVisitFeeOf(1)).toBe(100_000);
    expect(homeVisitFeeOf(2)).toBeNull();
    expect(homeVisitFeeOf(3)).toBeNull();
    const saved = await client.put('/api/branches/3/home-visit-fee', { enabled: true, fee: 0 }, auth);
    expect(saved.data.data).toEqual({ branchId: 3, enabled: true, fee: 0 });
    expect(homeVisitFeeOf(3)).toBe(0);
    await client.put('/api/branches/3/home-visit-fee', { enabled: false, fee: 70_000 }, auth);
    expect(homeVisitFeeOf(3)).toBeNull();
    expect((await client.get('/api/branches/3/home-visit-fee', auth)).data.data).toEqual({ branchId: 3, enabled: false, fee: 70_000 });
  });

  it('phí âm / thập phân → 400 BR-SP-09; sai kiểu → MALFORMED_REQUEST; thiếu → VALIDATION_FAILED', async () => {
    const auth = await as('chuoi');
    for (const fee of [-1, 10.5]) {
      const res = await failure(client.put('/api/branches/1/home-visit-fee', { enabled: true, fee }, auth));
      expect(ruleIdOf(res.data.message)).toBe('BR-SP-09');
    }
    expect((await failure(client.put('/api/branches/1/home-visit-fee', { enabled: 'yes', fee: 1 }, auth))).data.errorCode).toBe('MALFORMED_REQUEST');
    const missing = await failure(client.put('/api/branches/1/home-visit-fee', { fee: 1 }, auth));
    expect(missing.data.errorCode).toBe('VALIDATION_FAILED');
    expect(missing.data.message).toContain('enabled:');
    expect(homeVisitFeeOf(1)).toBe(100_000);
  });
});

describe('định mức vật tư (#5, #6, BR-SP-10)', () => {
  const norms = async (serviceId: number) => (await client.get(`/api/services/${serviceId}/material-norms`, await as('chuoi'))).data.data as MaterialNorm[];

  it('đọc định mức mẫu kèm tên, đơn vị; dịch vụ không có định mức trả mảng rỗng; dịch vụ không có → 404', async () => {
    expect(await norms(1)).toEqual([
      { productId: 3, productName: 'Amoxicillin 250mg', unit: 'viên', quantity: 2 },
      { productId: 4, productName: 'Vitamin tổng hợp PetCare', unit: 'lọ', quantity: 1 },
    ]);
    expect(await norms(3)).toEqual([]);
    expect(await status(client.get('/api/services/99/material-norms', await as('chuoi')))).toBe(404);
    expect(materialNormsOf(1)).toEqual(await norms(1));
    expect(materialNormsOf(99)).toEqual([]);
  });

  it('ghi đè toàn bộ theo thứ tự gửi; mảng rỗng xóa hết; chỉ dịch vụ đó đổi', async () => {
    const auth = await as('chuoi');
    const saved = await client.put('/api/services/1/material-norms', { items: [{ productId: 2, quantity: 4 }, { productId: 1, quantity: 1 }] }, auth);
    expect(saved.status).toBe(200);
    expect((saved.data.data as MaterialNorm[]).map((n) => [n.productId, n.quantity])).toEqual([[2, 4], [1, 1]]);
    expect(await norms(1)).toEqual(saved.data.data);
    expect((await norms(4)).map((n) => n.productId)).toEqual([2]);
    expect((await client.put('/api/services/1/material-norms', { items: [] }, auth)).data.data).toEqual([]);
    expect(materialNormsOf(1)).toEqual([]);
  });

  it.each([
    ['vaccine (#5)', [{ productId: 5, quantity: 1 }]],
    ['sản phẩm không tồn tại', [{ productId: 999, quantity: 1 }]],
    ['số lượng 0', [{ productId: 3, quantity: 0 }]],
    ['số lượng âm', [{ productId: 3, quantity: -2 }]],
    ['số lượng thập phân', [{ productId: 3, quantity: 1.5 }]],
    ['trùng sản phẩm', [{ productId: 3, quantity: 1 }, { productId: 3, quantity: 2 }]],
  ])('%s → 400 BR-SP-10 và định mức cũ giữ nguyên', async (_name, items) => {
    const res = await failure(client.put('/api/services/1/material-norms', { items }, await as('chuoi')));
    expect(res.status).toBe(400);
    expect(res.data.errorCode).toBe('BUSINESS_RULE_VIOLATION');
    expect(ruleIdOf(res.data.message)).toBe('BR-SP-10');
    expect(materialNormsOf(1)).toHaveLength(2);
  });

  it('sai kiểu → 400 MALFORMED_REQUEST; thiếu items / thiếu trường dòng → 400 VALIDATION_FAILED; 404 dịch vụ đến trước BR-SP-10', async () => {
    const auth = await as('chuoi');
    expect((await failure(client.put('/api/services/1/material-norms', { items: 'x' }, auth))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.put('/api/services/1/material-norms', { items: [{ productId: 3, quantity: '2' }] }, auth))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.put('/api/services/1/material-norms', {}, auth))).data.errorCode).toBe('VALIDATION_FAILED');
    const line = await failure(client.put('/api/services/1/material-norms', { items: [{ productId: 3 }] }, auth));
    expect(line.data.errorCode).toBe('VALIDATION_FAILED');
    expect(line.data.message).toContain('items[0].quantity:');
    expect(await status(client.put('/api/services/99/material-norms', { items: [{ productId: 5, quantity: 1 }] }, auth))).toBe(404);
  });
});

describe('state lưu dạng cũ / thiếu bảng', () => {
  it('{} → dùng dữ liệu mẫu cho mọi bảng và route chính không nổ', async () => {
    localStorage.setItem('petcare-mock-fees', '{}');
    const auth = await as('chuoi');
    expect((await client.get('/api/shipping-fee-config', auth)).data.data.baseFee).toBe(30_000);
    expect((await client.get('/api/branches/1/home-visit-fee', auth)).data.data.fee).toBe(100_000);
    expect(await status(client.get('/api/services/1/material-norms', auth))).toBe(200);
  });

  it('chỉ có shipping → giữ cấu hình đã lưu, bù hai bảng còn lại', async () => {
    localStorage.setItem('petcare-mock-fees', JSON.stringify({ shipping: { baseFee: 11_000, freeThreshold: 0, updatedAt: '2026-01-01T00:00:00.000Z' } }));
    const auth = await as('chuoi');
    expect((await client.get('/api/shipping-fee-config', auth)).data.data.baseFee).toBe(11_000);
    expect(homeVisitFeeOf(1)).toBe(100_000);
    expect(materialNormsOf(1)).toHaveLength(2);
  });

  it('dữ liệu hỏng (không phải JSON) → dùng lại dữ liệu mẫu', async () => {
    localStorage.setItem('petcare-mock-fees', 'không phải json');
    expect(shippingFeeConfig().baseFee).toBe(30_000);
  });

  it('homeVisitFee kiểu HomeVisitFee đúng hình dạng hợp đồng', async () => {
    const fee = (await client.get('/api/branches/1/home-visit-fee', await as('khach'))).data.data as HomeVisitFee;
    expect(Object.keys(fee).sort()).toEqual(['branchId', 'enabled', 'fee']);
  });
});
