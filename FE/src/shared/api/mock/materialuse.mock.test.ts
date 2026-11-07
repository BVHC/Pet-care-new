import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it } from 'vitest';
import { ruleIdOf } from '../api-error';
import type { PageResponse } from '../../types';
import type { AppNotification } from '../../types/care';
import type { MaterialUsage } from '../../types/materialuse';
import { MOCK_PASSWORD } from './identity.mock';
import { allLots, lotMovements } from './inventory.mock';
import { recordMaterialUsage } from './materialuse.mock';
import { visitRowOf } from './visit.mock';
import { installMockApi } from './mock-api';

// Dữ liệu mẫu (materialuse.mock.ts), chi nhánh 1: lượt 61 (Amoxicillin 2/2, Vitamin 1/1) · lượt 58 (Amoxicillin thiếu 2, CHƯA xử lý = dòng 3; Vitamin 1/1) ·
// lượt 40 (Amoxicillin thiếu 1, ĐÃ xử lý = dòng 5). Chi nhánh 2: dòng 6 (lượt 7001, không có trong mock lượt khám). Bác sĩ phụ trách lượt 61, 58, 40, 2102: doctor@store1.vn (#5).

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
  doctor2: 'doctor2@store1.vn',
  caretaker: 'caretaker@store1.vn',
  khach: 'khach@petcare.vn',
} as const;
type Who = keyof typeof EMAILS;
const WHO = Object.keys(EMAILS) as Who[];

async function as(who: Who) {
  const res = await client.post('/api/auth/login', { email: EMAILS[who], password: MOCK_PASSWORD });
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}
async function send(who: Who | null, method: 'get' | 'post' | 'put', url: string, body?: unknown, params?: object) {
  return client.request({ method, url, data: body, params, ...(who ? await as(who) : {}) });
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
const data = async <T>(who: Who, method: 'get' | 'post' | 'put', url: string, body?: unknown, params?: object) => (await send(who, method, url, body, params)).data.data as T;
const bad = (who: Who | null, method: 'get' | 'post' | 'put', url: string, body?: unknown, params?: object) => failure(send(who, method, url, body, params));
const outcome = async (request: Promise<AxiosResponse>) => {
  try {
    return String((await request).status);
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return `${e.response.status} ${e.response.data.errorCode}`;
    throw e;
  }
};
const usages = (who: Who, visitId: number) => data<MaterialUsage[]>(who, 'get', `/api/visits/${visitId}/material-usages`);
const shortages = (who: Who, params: object = {}) => data<PageResponse<MaterialUsage>>(who, 'get', '/api/material-shortages', undefined, params);
const ids = (rows: MaterialUsage[]) => rows.map((r) => r.usageId);

function expectLedgerBalanced() {
  for (const lot of allLots()) {
    expect(lotMovements(lot.lotId).reduce((sum, m) => sum + m.quantity, 0), `sổ lô ${lot.lotId}`).toBe(lot.quantity);
  }
}

const DENIED = '403 ACCESS_DENIED';
const SCOPE = '403 ACCESS_DENIED_SCOPE_MISMATCH';
interface Case {
  name: string;
  call: (who: Who | null) => Promise<AxiosResponse>;
  expect: Partial<Record<Who, string>>;
}
const CASES: Case[] = [
  { name: '#1 GET /visits/61/material-usages', call: (w) => send(w, 'get', '/api/visits/61/material-usages'), expect: { manager: '200', doctor: '200', manager2: SCOPE, doctor2: SCOPE } },
  { name: '#2 GET /material-shortages', call: (w) => send(w, 'get', '/api/material-shortages'), expect: { manager: '200', manager2: '200' } },
  { name: '#3 POST /material-shortages/3/resolve', call: (w) => send(w, 'post', '/api/material-shortages/3/resolve', { note: 'Đã nhập thêm' }), expect: { manager: '200', manager2: SCOPE } },
];

describe('phân quyền (dòng "Quyền:" của yaml): mỗi endpoint × 9 tài khoản', () => {
  it.each(CASES.flatMap((c) => WHO.map((who) => [c.name, who, c.expect[who] ?? DENIED] as const)))('%s với %s → %s', async (name, who, expected) => {
    expect(await outcome(CASES.find((c) => c.name === name)!.call(who))).toBe(expected);
  });

  it.each(CASES.map((c) => c.name))('%s không token → 401', async (name) => {
    const res = await failure(CASES.find((c) => c.name === name)!.call(null));
    expect(res.status).toBe(401);
    expect(res.data.errorCode).toBe('UNAUTHENTICATED');
  });
});

describe('vật tư tiêu hao của một lượt (#1)', () => {
  it('trả các dòng theo định mức kèm tên dịch vụ, sản phẩm, đơn vị; normQty = issuedQty + shortageQty', async () => {
    const rows = await usages('manager', 61);
    expect(rows).toEqual([
      { usageId: 1, visitId: 61, serviceId: 1, serviceName: 'Khám tổng quát', productId: 3, productName: 'Amoxicillin 250mg', unit: 'viên', normQty: 2, issuedQty: 2, shortageQty: 0, resolvedAt: null, resolvedNote: null },
      { usageId: 2, visitId: 61, serviceId: 1, serviceName: 'Khám tổng quát', productId: 4, productName: 'Vitamin tổng hợp PetCare', unit: 'lọ', normQty: 1, issuedQty: 1, shortageQty: 0, resolvedAt: null, resolvedNote: null },
    ]);
    for (const id of [61, 58, 40]) for (const r of await usages('doctor', id)) expect(r.normQty).toBe(r.issuedQty + r.shortageQty);
    const resolved = (await usages('manager', 40))[0];
    expect(resolved).toMatchObject({ shortageQty: 1, resolvedNote: 'Đã nhập thêm Amoxicillin về kho' });
    expect(resolved.resolvedAt).toEqual(expect.any(String));
  });

  it('lượt chưa hoàn tất hoặc chưa có dòng nào → mảng rỗng; lượt không có → 404; id sai kiểu → 400', async () => {
    expect(await usages('doctor', 2102)).toEqual([]);
    expect((await bad('doctor', 'get', '/api/visits/999999/material-usages')).status).toBe(404);
    expect((await bad('manager', 'get', '/api/visits/7001/material-usages')).status).toBe(404);
    const res = await bad('khach', 'get', '/api/visits/abc/material-usages');
    expect(res.status).toBe(400);
    expect(res.data.errorCode).toBe('MALFORMED_REQUEST');
  });

  it('bác sĩ khác (không phụ trách lượt) → 403 phạm vi, câu chung không có mã rule', async () => {
    const res = await bad('doctor2', 'get', '/api/visits/61/material-usages');
    expect(res.data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect(ruleIdOf(res.data.message)).toBeNull();
  });
});

describe('thiếu hụt chưa xử lý (#2)', () => {
  it('mặc định chỉ dòng thiếu chưa xử lý của chi nhánh mình; resolved=true là các dòng đã xử lý', async () => {
    expect(ids((await shortages('manager')).content)).toEqual([3]);
    expect(ids((await shortages('manager', { resolved: false })).content)).toEqual([3]);
    expect(ids((await shortages('manager', { resolved: true })).content)).toEqual([5]);
    expect(ids((await shortages('manager2')).content)).toEqual([6]);
    expect((await shortages('manager')).content[0]).toMatchObject({ visitId: 58, productName: 'Amoxicillin 250mg', normQty: 2, issuedQty: 0, shortageQty: 2, resolvedAt: null });
  });

  it('cũ nhất trước; phân trang đúng', async () => {
    await send('manager', 'post', '/api/material-shortages/3/resolve', { note: 'ok' });
    recordMaterialUsage({ visitId: 2102, branchId: 1, serviceIds: [1] });
    const page = await shortages('manager', { resolved: true });
    expect(ids(page.content)).toEqual([5, 3]);
    expect(await shortages('manager', { resolved: true, size: 1 })).toMatchObject({ page: 0, size: 1, totalElements: 2, totalPages: 2 });
    expect(ids((await shortages('manager', { resolved: true, size: 1, page: 1 })).content)).toEqual([3]);
  });

  it('resolved sai kiểu → 400 MALFORMED_REQUEST, đến trước 403 vai trò', async () => {
    for (const who of ['manager', 'khach'] as const) {
      const res = await bad(who, 'get', '/api/material-shortages', undefined, { resolved: 'yes' });
      expect(res.data.errorCode).toBe('MALFORMED_REQUEST');
    }
    expect((await bad('manager', 'get', '/api/material-shortages', undefined, { size: 'x' })).status).toBe(400);
  });
});

describe('đánh dấu đã xử lý (#3, BR-KB-13)', () => {
  const resolve = (who: Who, usageId: number | string, body: unknown) => send(who, 'post', `/api/material-shortages/${usageId}/resolve`, body);

  it('ghi người xử lý, giờ và ghi chú đã cắt khoảng trắng; dòng rời danh sách chưa xử lý', async () => {
    const res = await resolve('manager', 3, { note: '  Đã điều chỉnh tồn  ' });
    expect(res.status).toBe(200);
    expect(res.data.data).toMatchObject({ usageId: 3, resolvedNote: 'Đã điều chỉnh tồn' });
    expect(Date.parse(res.data.data.resolvedAt)).toBeGreaterThan(Date.now() - 5000);
    expect((await shortages('manager')).content).toEqual([]);
  });

  it('xử lý lần hai → 409 INVALID_STATE_TRANSITION và giữ ghi chú đầu tiên', async () => {
    await resolve('manager', 3, { note: 'Lần một' });
    for (const id of [3, 5]) {
      const again = await failure(resolve('manager', id, { note: 'Lần hai' }));
      expect(again.status).toBe(409);
      expect(again.data.errorCode).toBe('INVALID_STATE_TRANSITION');
    }
    expect((await shortages('manager', { resolved: true })).content.find((r) => r.usageId === 3)?.resolvedNote).toBe('Lần một');
  });

  it('dòng không có thiếu hụt → 400 BR-KB-13', async () => {
    const res = await failure(resolve('manager', 1, { note: 'x' }));
    expect(res.status).toBe(400);
    expect(res.data.errorCode).toBe('BUSINESS_RULE_VIOLATION');
    expect(ruleIdOf(res.data.message)).toBe('BR-KB-13');
  });

  it('ghi chú trống / thiếu / quá 300 ký tự → VALIDATION_FAILED (trước vai trò); sai kiểu → MALFORMED_REQUEST', async () => {
    for (const body of [{}, { note: '' }, { note: '   ' }, { note: 'x'.repeat(301) }, { note: null }]) {
      const res = await failure(resolve('khach', 3, body));
      expect(res.data.errorCode).toBe('VALIDATION_FAILED');
      expect(res.data.message).toContain('note:');
    }
    for (const body of [{ note: 5 }, null, 'x']) expect((await failure(resolve('manager', 3, body))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await resolve('manager', 3, { note: 'x'.repeat(300) })).status).toBe(200);
  });

  it('dòng không có → 404; id sai kiểu → 400; dòng của chi nhánh khác → 403 phạm vi', async () => {
    expect((await failure(resolve('manager', 999, { note: 'x' }))).status).toBe(404);
    expect((await failure(resolve('manager', 'abc', { note: 'x' }))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(resolve('manager', 6, { note: 'x' }))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await resolve('manager2', 6, { note: 'Đã nhập thêm' })).status).toBe(200);
  });
});

describe('recordMaterialUsage: ST07 cho mock lượt khám gọi khi hoàn tất lượt (BR-KB-12…14)', () => {
  it('xuất kho FEFO theo định mức, đủ thì không thiếu; thiếu thì xuất phần có, ghi thiếu hụt và báo quản lý; không chặn', async () => {
    const created = recordMaterialUsage({ visitId: 2102, branchId: 1, serviceIds: [1] });
    expect(created.map((u) => [u.productId, u.normQty, u.issuedQty, u.shortageQty])).toEqual([[3, 2, 2, 0], [4, 1, 0, 1]]); // chi nhánh 1 chưa có tồn Vitamin
    expect(created.every((u) => u.visitId === 2102 && u.serviceName === 'Khám tổng quát')).toBe(true);
    expect(await usages('doctor', 2102)).toEqual(created);
    expect(allLots().find((l) => l.lotId === 13)?.quantity).toBe(198);
    expect(ids((await shortages('manager')).content)).toEqual([3, created[1].usageId]);

    const notices = (await data<PageResponse<AppNotification>>('manager', 'get', '/api/me/notifications')).content;
    const notice = notices.find((n) => n.type === 'MATERIAL_SHORTAGE')!;
    expect(notice.body).toContain('Vitamin tổng hợp PetCare');
    expect(notice.readAt ?? null).toBeNull();
    expect((await data<PageResponse<AppNotification>>('manager2', 'get', '/api/me/notifications')).content.some((n) => n.type === 'MATERIAL_SHORTAGE')).toBe(false);
    expectLedgerBalanced();
  });

  it('mỗi lượt chỉ trừ một lần: gọi lại không tạo dòng, không trừ thêm kho', async () => {
    recordMaterialUsage({ visitId: 2102, branchId: 1, serviceIds: [1] });
    expect(recordMaterialUsage({ visitId: 2102, branchId: 1, serviceIds: [1, 1] })).toEqual([]);
    expect(allLots().find((l) => l.lotId === 13)?.quantity).toBe(198);
    expect(await usages('manager', 2102)).toHaveLength(2);
  });

  it('dịch vụ không có định mức → không có dòng; lượt ghi sổ lô kèm mã lượt', async () => {
    expect(recordMaterialUsage({ visitId: 2103, branchId: 1, serviceIds: [3] })).toEqual([]);
    recordMaterialUsage({ visitId: 2102, branchId: 1, serviceIds: [1] });
    const last = lotMovements(13).at(-1)!;
    expect(last).toMatchObject({ kind: 'ORDER_ISSUE', quantity: -2, documentCode: visitRowOf(2102)!.code });
  });

  it('cộng dồn mọi dịch vụ của lượt theo sản phẩm; thiếu thì phần xuất chia theo thứ tự dịch vụ', async () => {
    const auth = await as('chuoi');
    await client.put('/api/services/4/material-norms', { items: [{ productId: 3, quantity: 5 }] }, auth);
    const manager = await as('manager');
    await client.post('/api/stock-adjustments', { reason: 'DAMAGED', lines: [{ stockLotId: 13, quantityDelta: -200 }, { stockLotId: 14, quantityDelta: -496 }] }, manager);

    const created = recordMaterialUsage({ visitId: 2102, branchId: 1, serviceIds: [1, 4] });
    const amox = created.filter((u) => u.productId === 3);
    expect(amox.map((u) => [u.serviceId, u.normQty, u.issuedQty, u.shortageQty])).toEqual([[1, 2, 2, 0], [4, 5, 2, 3]]); // chỉ còn 4 viên
    expect(allLots().filter((l) => l.productId === 3).reduce((sum, l) => sum + l.quantity, 0)).toBe(0);
    expectLedgerBalanced();
  });

  it('sửa định mức chỉ ảnh hưởng các lần thực hiện sau: dòng đã ghi giữ định mức cũ (BR-SP-10)', async () => {
    recordMaterialUsage({ visitId: 2102, branchId: 1, serviceIds: [1] });
    await client.put('/api/services/1/material-norms', { items: [{ productId: 3, quantity: 9 }] }, await as('chuoi'));
    expect((await usages('doctor', 2102)).map((u) => u.normQty)).toEqual([2, 1]);
    const later = recordMaterialUsage({ visitId: 2103, branchId: 1, serviceIds: [1] });
    expect(later.map((u) => [u.productId, u.normQty])).toEqual([[3, 9]]);
  });
});

describe('state lưu dạng cũ / thiếu bảng', () => {
  it('{} → dùng dữ liệu mẫu và route chính không nổ', async () => {
    localStorage.setItem('petcare-mock-materialuse', '{}');
    expect(ids((await shortages('manager')).content)).toEqual([3]);
    expect(await usages('manager', 61)).toHaveLength(2);
  });

  it('dữ liệu hỏng (không phải JSON) → dùng lại dữ liệu mẫu', async () => {
    localStorage.setItem('petcare-mock-materialuse', '[hỏng');
    expect(ids((await shortages('manager')).content)).toEqual([3]);
  });
});
