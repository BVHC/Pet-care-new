import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { CustomerMergeResult, MergePreview } from '../../types/custmerge';
import { registerMergeSources, type MergeKind } from './custmerge.mock';
import { findCustomer, linkCandidates, mergeCustomerRecords } from './customer.mock';
import { FEEDBACK_STATE_KEY, feedbackRecords, type FeedbackRecord } from './feedback.mock';
import { MOCK_PASSWORD } from './identity.mock';
import { allOrderRows } from './sales.mock';
import { installMockApi } from './mock-api';

// Dữ liệu mẫu: khách 1 (khach@, có tài khoản, 3 thú, 2 địa chỉ), 2 (khachmoi@, có tài khoản, chờ quyết định liên kết), 3 và 4 (hồ sơ tại quầy cùng SĐT 0905111222;
// 3 có thú Lu đang khám, 4 có thú Miu). Mọi khách mẫu đều có Order OPEN / PENDING trong mock bán hàng nên đều bị chặn làm hồ sơ NGUỒN (BR-KH-11); các ca gộp thành công
// dùng hồ sơ tại quầy lễ tân tạo thêm (id 5, 6, …) — đúng cách tạo hồ sơ trùng ngoài đời.
let client: AxiosInstance;
let restore: (() => void) | undefined;

beforeEach(() => {
  client = axios.create();
  installMockApi(client);
});
afterEach(() => {
  restore?.();
  restore = undefined;
});

async function as(email: string) {
  const res = await client.post('/api/auth/login', { email, password: MOCK_PASSWORD });
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}
const manager = () => as('manager@store1.vn');
const desk = () => as('reception@store1.vn');

async function failure(request: Promise<unknown>): Promise<AxiosResponse> {
  try {
    await request;
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return e.response;
    throw e;
  }
  throw new Error('expected the request to fail');
}

const preview = async (target: number, source: number, auth?: object) =>
  (await client.get(`/api/customers/${target}/merge-preview`, { ...(auth ?? (await manager())), params: { sourceCustomerId: source } })).data.data as MergePreview;
const merge = (body: object, auth: object) => client.post('/api/customer-merges', body, auth);
const result = (res: AxiosResponse) => res.data.data as CustomerMergeResult;
const confirmed = (sourceCustomerId: number, targetCustomerId: number) => ({ sourceCustomerId, targetCustomerId, confirm: true });

/** Hồ sơ tại quầy mới (lễ tân tạo, UC22). */
async function newProfile(fullName: string, phone: string, pets: [name: string, species: string][] = []) {
  const auth = await desk();
  const id = (await client.post('/api/customers', { fullName, phone }, auth)).data.data.customer.customerId as number;
  for (const [name, species] of pets) await client.post(`/api/customers/${id}/pets`, { name, species, sex: 'MALE' }, auth);
  return id;
}
const petsOf = async (customerId: number) =>
  ((await client.get(`/api/customers/${customerId}`, await desk())).data.data.pets as { name: string }[]).map((p) => p.name).sort();

const CALLS: [string, (auth: object) => Promise<unknown>][] = [
  ['merge-preview (#1)', (auth) => client.get('/api/customers/4/merge-preview', { ...auth, params: { sourceCustomerId: 3 } })],
  ['customer-merges (#2)', (auth) => merge(confirmed(3, 4), auth)],
];
const ROLES_DENIED = ['admin@petcare.vn', 'chuoi@petcare.vn', 'reception@store1.vn', 'doctor@store1.vn', 'caretaker@store1.vn', 'khach@petcare.vn'];

describe('quyền: chỉ BRANCH_MANAGER', () => {
  describe.each(CALLS)('%s', (_name, call) => {
    it.each(ROLES_DENIED)('%s → 403 ACCESS_DENIED', async (email) => {
      const res = await failure(call(await as(email)));
      expect(res.status).toBe(403);
      expect(res.data).toMatchObject({ errorCode: 'ACCESS_DENIED', statusCode: 403 });
    });
    it('không token → 401', async () => {
      expect((await failure(call({}))).status).toBe(401);
    });
    it('quản lý chi nhánh khác cũng qua cổng quyền (hồ sơ khách dùng chung toàn chuỗi, A1)', async () => {
      // Qua cổng quyền: xem trước trả 200, gộp dừng ở điều kiện nghiệp vụ (nguồn 3 còn lượt khám mở).
      const res = await call(await as('manager2@store2.vn')).then((r) => r as AxiosResponse, (e: { response: AxiosResponse }) => e.response);
      expect(res.status).not.toBe(403);
    });
  });
});

describe('xem trước (#1)', () => {
  it('đếm thú, địa chỉ, feedback của nguồn; cảnh báo khác họ tên; cùng SĐT thì không cảnh báo SĐT', async () => {
    const p = await preview(3, 4);
    expect(p).toMatchObject({ targetCustomerId: 3, sourceCustomerId: 4, petCount: 1, addressCount: 0, duplicateAddressCount: 0, feedbackCount: 2 });
    expect(p).toMatchObject({ appointmentCount: 0, boardingCount: 0, orderCount: 0, onlineOrderCount: 0, careTaskCount: 0 });
    expect(p.warnings).toHaveLength(1);
    expect(p.warnings[0]).toContain('Họ tên');
    // Khách mẫu nào cũng còn Order OPEN / PENDING nên bị chặn làm nguồn.
    expect(p.blockers.join('|')).toContain('Order chưa thanh toán');
  });

  it('điều kiện chặn BR-KH-11 liệt kê đủ: lượt khám mở, Order, tài khoản', async () => {
    const lu = await preview(4, 3);
    expect(lu.blockers.join('|')).toMatch(/lượt khám.*Order chưa thanh toán/);
    const both = await preview(2, 1); // khach@ và khachmoi@ đều có tài khoản
    expect(both.blockers.join('|')).toContain('cả hai hồ sơ đã liên kết tài khoản');
    const wrongSide = await preview(3, 1); // nguồn có tài khoản mà đích không
    expect(wrongSide.blockers.join('|')).toContain('hồ sơ đích phải là bên có tài khoản');
    expect(wrongSide.warnings.some((w) => w.includes('Số điện thoại'))).toBe(true);
    expect((await preview(1, 4)).blockers.join('|')).not.toContain('tài khoản'); // đích có tài khoản, nguồn không: đúng chiều
  });

  it('hồ sơ mới tạo, sạch: blockers rỗng; thú nghi trùng và đích chờ liên kết vào warnings', async () => {
    const a = await newProfile('Lê Hoàng Long', '0905111222', [['Lu', 'DOG'], ['Mực', 'CAT']]);
    const b = await newProfile('Lê Hoàng Long', '0905111222', [['lu', 'DOG']]);
    const p = await preview(b, a);
    expect(p).toMatchObject({ petCount: 2, blockers: [] });
    expect(p.warnings).toHaveLength(1);
    expect(p.warnings[0]).toContain('nghi trùng');
    expect((await preview(2, a)).warnings.some((w) => w.includes('chờ khách quyết định liên kết'))).toBe(true); // khachmoi@ chờ liên kết
  });

  it('địa chỉ trùng sẽ bỏ (so theo đường, phường, tỉnh, không phân biệt hoa thường)', async () => {
    const a = await newProfile('Trần Văn A', '0911000111');
    const b = await newProfile('Trần Văn A', '0911000111');
    await seedAddresses(a, b);
    expect(await preview(b, a)).toMatchObject({ addressCount: 2, duplicateAddressCount: 1 });
  });

  it('thứ tự lỗi: id sai kiểu / thiếu query (400) → 403 vai trò → VALIDATION_FAILED → 404', async () => {
    const auth = await manager();
    const get = (path: string, params?: object, a: object = auth) => client.get(path, { ...a, params });
    expect((await failure(get('/api/customers/abc/merge-preview', { sourceCustomerId: 3 }, await desk()))).data).toMatchObject({ errorCode: 'MALFORMED_REQUEST' });
    const missing = await failure(get('/api/customers/4/merge-preview', undefined, await desk()));
    expect(missing.data).toMatchObject({ errorCode: 'MALFORMED_REQUEST', message: "Thiếu tham số 'sourceCustomerId'" });
    expect((await failure(get('/api/customers/4/merge-preview', { sourceCustomerId: 'x' }))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(get('/api/customers/4/merge-preview', { sourceCustomerId: 3 }, await desk()))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(get('/api/customers/4/merge-preview', { sourceCustomerId: 4 }))).data).toMatchObject({ errorCode: 'VALIDATION_FAILED', statusCode: 400 });
    expect((await failure(get('/api/customers/0/merge-preview', { sourceCustomerId: 3 }))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(get('/api/customers/4/merge-preview', { sourceCustomerId: 999 }))).data).toMatchObject({ errorCode: 'RESOURCE_NOT_FOUND', statusCode: 404 });
    expect((await failure(get('/api/customers/999/merge-preview', { sourceCustomerId: 3 }))).status).toBe(404);
  });

  it('không ghi gì: xem trước không đổi hồ sơ, thú, log', async () => {
    await preview(3, 4); // lần đầu có thể ghi dữ liệu mẫu của mock khác (lưu trú tự lưu khi nạp); so sánh từ lần sau
    const snapshot = () => ['customer', 'pets', 'feedback', 'custmerge'].map((k) => localStorage.getItem(`petcare-mock-${k}`));
    const before = snapshot();
    await preview(3, 4);
    await preview(4, 3);
    expect(snapshot().every((v, i) => v === before[i])).toBe(true);
  });
});

/** Ghi sổ địa chỉ mẫu vào state khách: a có 2 địa chỉ (một trùng với b), b có 1 địa chỉ mặc định. */
async function seedAddresses(a: number, b: number, bHasDefault = true) {
  const line = (addressId: number, customerId: number, addressLine: string, isDefault: boolean) => ({
    addressId,
    customerId,
    receiverName: 'Trần Văn A',
    receiverPhone: '0911000111',
    addressLine,
    ward: 'Láng Thượng',
    province: 'Hà Nội',
    isDefault,
  });
  const stored = JSON.parse(localStorage.getItem('petcare-mock-customer')!) as object;
  localStorage.setItem(
    'petcare-mock-customer',
    JSON.stringify({
      ...stored,
      addresses: [line(21, a, '8 Nguyễn Chí Thanh', true), line(22, a, '99 Cầu Giấy', false), ...(bHasDefault ? [line(23, b, '8 NGUYỄN CHÍ THANH', true)] : [])],
      nextAddressId: 30,
    }),
  );
}

describe('gộp (#2)', () => {
  it('thứ tự lỗi: sai kiểu → 403 vai trò → VALIDATION_FAILED → 404 → BR-KH-11 → BR-KH-14', async () => {
    const mgr = await manager();
    expect((await failure(merge({ sourceCustomerId: '3', targetCustomerId: 4, confirm: true }, await desk()))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(merge({ sourceCustomerId: 3, targetCustomerId: 4, confirm: 'yes' }, mgr))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.post('/api/customer-merges', [], mgr))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(merge({}, await desk()))).data.errorCode).toBe('ACCESS_DENIED');

    const empty = await failure(merge({}, mgr));
    expect(empty.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', statusCode: 400 });
    for (const field of ['sourceCustomerId', 'targetCustomerId', 'confirm']) expect(empty.data.message).toContain(field);
    expect((await failure(merge({ sourceCustomerId: 0, targetCustomerId: 4, confirm: true }, mgr))).data.errorCode).toBe('VALIDATION_FAILED');

    expect((await failure(merge(confirmed(999, 4), mgr))).data.errorCode).toBe('RESOURCE_NOT_FOUND');
    expect((await failure(merge(confirmed(4, 999), mgr))).data.errorCode).toBe('RESOURCE_NOT_FOUND');
    const same = await failure(merge(confirmed(4, 4), mgr));
    expect(same.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION' });
    expect(same.data.message).toMatch(/\(BR-KH-11\)$/);

    const blocked = await failure(merge(confirmed(3, 4), mgr));
    expect(blocked.data.message).toMatch(/lượt khám.*\(BR-KH-11\)$/);
    // BR-KH-11 đến trước BR-KH-14: hồ sơ bị chặn mà chưa xác nhận vẫn báo BR-KH-11.
    expect((await failure(merge({ ...confirmed(3, 4), confirm: false }, mgr))).data.message).toMatch(/\(BR-KH-11\)$/);

    const a = await newProfile('Lê A', '0905000001');
    const b = await newProfile('Lê A', '0905000001');
    const noConfirm = await failure(merge({ ...confirmed(a, b), confirm: false }, mgr));
    expect(noConfirm.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', statusCode: 400 });
    expect(noConfirm.data.message).toMatch(/\(BR-KH-14\)$/);
    expect((await preview(b, a)).blockers).toEqual([]); // chưa đổi gì
  });

  it('thành công: 201, thú sang đích, nguồn MERGED và ẩn khỏi tra cứu, đích có thú của cả hai', async () => {
    const a = await newProfile('Phạm Thu', '0905222333', [['Bơ', 'DOG'], ['Mực', 'CAT']]);
    const b = await newProfile('Phạm Thị Thu', '0905222333', [['Lu Lu', 'DOG']]);
    const res = await merge(confirmed(a, b), await manager());
    expect(res.status).toBe(201);
    expect(res.data.message).toBe('Đã gộp hồ sơ khách');
    expect(result(res)).toMatchObject({ mergeId: 1, sourceCustomerId: a, targetCustomerId: b });
    expect(result(res).moved).toEqual({ pets: 2, addresses: 0, appointments: 0, boardings: 0, orders: 0, onlineOrders: 0, feedbacks: 0, careTasks: 0 });
    expect(Date.parse(result(res).createdAt)).not.toBeNaN();

    expect(await petsOf(b)).toEqual(['Bơ', 'Lu Lu', 'Mực']);
    const found = (await client.get('/api/customers', { ...(await desk()), params: { phone: '0905222333' } })).data.data;
    expect(found.content.map((c: { customerId: number }) => c.customerId)).toEqual([b]);
    // Hồ sơ nguồn giữ để audit nhưng tra theo id, sửa, thêm thú, nhận chuyển chủ đều như không có.
    const auth = await desk();
    expect((await failure(client.get(`/api/customers/${a}`, auth))).status).toBe(404);
    expect((await failure(client.patch(`/api/customers/${a}`, { fullName: 'Khác' }, auth))).status).toBe(404);
    expect((await failure(client.post(`/api/customers/${a}/pets`, { name: 'X', species: 'DOG', sex: 'MALE' }, auth))).status).toBe(404);
    const stored = JSON.parse(localStorage.getItem('petcare-mock-customer')!).customers as { customerId: number; mergedIntoId?: number }[];
    expect(stored.find((c) => c.customerId === a)?.mergedIntoId).toBe(b);
  });

  it('hồ sơ đã gộp không gộp lại được, cũng không làm đích: BR-KH-11; preview báo trong blockers', async () => {
    const a = await newProfile('A', '0905333001');
    const b = await newProfile('A', '0905333001');
    const c = await newProfile('A', '0905333001');
    await merge(confirmed(a, b), await manager());
    expect((await preview(c, a)).blockers.join('|')).toContain(`đã được gộp vào hồ sơ #${b}`);
    expect((await preview(a, c)).blockers.join('|')).toContain('hồ sơ đích');
    expect((await failure(merge(confirmed(a, c), await manager()))).data.message).toMatch(/đã được gộp.*\(BR-KH-11\)$/);
    expect((await failure(merge(confirmed(c, a), await manager()))).data.message).toMatch(/\(BR-KH-11\)$/);
    expect((await merge(confirmed(b, c), await manager())).status).toBe(201); // đích đã nhận gộp vẫn gộp tiếp được (chuỗi gộp)
  });

  it('feedback chuyển sang đích với nguyên trạng thái; số chuyển bằng số đếm của preview', async () => {
    const a = await newProfile('Võ Hải', '0905444001');
    const b = await newProfile('Võ Hải', '0905444001');
    const rec = (feedbackId: number, customerId: number): FeedbackRecord => ({
      feedbackId,
      customerId,
      topic: 'WEBSITE',
      branchId: null,
      rating: 4,
      content: 'Trang web dễ dùng, cần thêm ảnh bác sĩ.',
      status: 'SEEN',
      seenAt: '2026-10-01T01:00:00.000Z',
      resolvedBy: null,
      resolvedAt: null,
      resolutionNote: null,
      createdAt: '2026-10-01T00:00:00.000Z',
    });
    localStorage.setItem(FEEDBACK_STATE_KEY, JSON.stringify({ feedbacks: [rec(1, a), rec(2, a), rec(3, b), rec(4, 1)] }));
    const p = await preview(b, a);
    expect(p.feedbackCount).toBe(2);
    expect(result(await merge(confirmed(a, b), await manager())).moved.feedbacks).toBe(p.feedbackCount);
    expect(feedbackRecords().filter((f) => f.customerId === b).map((f) => f.feedbackId)).toEqual([1, 2, 3]);
    expect(feedbackRecords().find((f) => f.feedbackId === 1)).toMatchObject({ status: 'SEEN', seenAt: '2026-10-01T01:00:00.000Z' });
    expect(feedbackRecords().find((f) => f.feedbackId === 4)?.customerId).toBe(1);
  });

  it('địa chỉ: bỏ địa chỉ trùng, chuyển phần còn lại, đích giữ địa chỉ mặc định của mình; moved = count − duplicate', async () => {
    const a = await newProfile('Trần Văn A', '0911000111');
    const b = await newProfile('Trần Văn A', '0911000111');
    await seedAddresses(a, b);
    const p = await preview(b, a);
    const res = await merge(confirmed(a, b), await manager());
    expect(result(res).moved.addresses).toBe(p.addressCount - p.duplicateAddressCount);
    const rows = JSON.parse(localStorage.getItem('petcare-mock-customer')!).addresses as { addressId: number; customerId: number; isDefault: boolean }[];
    expect(rows.filter((r) => r.customerId === a)).toEqual([]);
    expect(rows.filter((r) => r.customerId === b).map((r) => [r.addressId, r.isDefault]).sort()).toEqual([
      [22, false],
      [23, true],
    ]);
  });

  it('đích chưa có địa chỉ: địa chỉ mặc định của nguồn lên mặc định, chỉ một địa chỉ mặc định', async () => {
    const a = await newProfile('Trần Văn A', '0911000111');
    const b = await newProfile('Trần Văn A', '0911000111');
    await seedAddresses(a, b, false);
    await merge(confirmed(a, b), await manager());
    const rows = (JSON.parse(localStorage.getItem('petcare-mock-customer')!).addresses as { customerId: number; isDefault: boolean; addressId: number }[]).filter((r) => r.customerId === b);
    expect(rows.map((r) => r.addressId).sort()).toEqual([21, 22]);
    expect(rows.filter((r) => r.isDefault).map((r) => r.addressId)).toEqual([21]);
  });

  it('nguồn đăng ký: preview đếm và gộp chuyển đúng cùng một số cho từng loại (moved[k] = count)', async () => {
    const kinds: [MergeKind, string][] = [
      ['appointments', 'appointmentCount'],
      ['boardings', 'boardingCount'],
      ['orders', 'orderCount'],
      ['onlineOrders', 'onlineOrderCount'],
      ['careTasks', 'careTaskCount'],
    ];
    const move = vi.fn((_from: number, _to: number) => 0);
    restore = registerMergeSources(
      Object.fromEntries(
        kinds.map(([kind], i) => [kind, { count: () => i + 2, move: (from: number, to: number) => (move(from, to), i + 2) }]),
      ) as Record<MergeKind, { count: () => number; move: (f: number, t: number) => number }>,
    );
    const a = await newProfile('Đào Lan', '0905555001');
    const b = await newProfile('Đào Lan', '0905555001');
    const p = (await preview(b, a)) as unknown as Record<string, number>;
    const moved = result(await merge(confirmed(a, b), await manager())).moved;
    for (const [kind, previewKey] of kinds) expect(moved[kind]).toBe(p[previewKey]);
    expect(move).toHaveBeenCalledTimes(kinds.length);
    expect(move).toHaveBeenCalledWith(a, b);
  });

  it('ghi log customer_merges: mergeId tăng dần, lưu người làm và số chuyển; state log thiếu vẫn chạy', async () => {
    localStorage.setItem('petcare-mock-custmerge', '{}');
    const [a, b, c] = [await newProfile('K', '0905666001', [['Bé', 'CAT']]), await newProfile('K', '0905666001'), await newProfile('K', '0905666001')];
    expect(result(await merge(confirmed(a, b), await manager())).mergeId).toBe(1);
    expect(result(await merge(confirmed(b, c), await manager())).mergeId).toBe(2);
    const log = JSON.parse(localStorage.getItem('petcare-mock-custmerge')!) as { seq: number; merges: { mergeId: number; executedBy: number; moved: { pets: number } }[] };
    expect(log.seq).toBe(2);
    expect(log.merges.map((m) => [m.mergeId, m.executedBy, m.moved.pets])).toEqual([[1, 3, 1], [2, 3, 1]]);
  });

  it('khách chờ liên kết: hồ sơ quầy đã gộp không còn là ứng viên liên kết (UC07)', async () => {
    const mine = await as('khachmoi@petcare.vn');
    const candidates = async () => ((await client.get('/api/me/link-candidates', mine)).data.data as { customerId: number }[]).map((c) => c.customerId);
    expect(await candidates()).toEqual(expect.arrayContaining([3, 4]));
    // Gộp hồ sơ quầy 4 vào hồ sơ quầy tạo mới (nguồn 4 còn Order PENDING nên coi như đã thu tiền: chỉ `orders` được đọc ở đây).
    localStorage.setItem('petcare-mock-sales', JSON.stringify({ orders: allOrderRows().map((o) => (o.customerId === 4 ? { ...o, status: 'PAID' } : o)) }));
    const fresh = await newProfile('Lê Thị Hoa', '0905111222');
    expect((await merge(confirmed(4, fresh), await manager())).status).toBe(201);
    const after = await candidates();
    expect(after).not.toContain(4);
    expect(after).toContain(fresh);
  });
});

describe('đích đang chờ quyết định liên kết (BR-TK-19)', () => {
  it('gộp hết hồ sơ quầy cùng SĐT vào hồ sơ chờ thì cờ chờ được gỡ; còn ứng viên thì giữ', () => {
    expect(findCustomer(2)?.linkDecisionPending).toBe(true); // khachmoi@: chờ quyết định, ứng viên là hồ sơ quầy 3 và 4
    expect(linkCandidates('0905111222').map((c) => c.customerId)).toEqual([3, 4]);
    expect(mergeCustomerRecords(3, 2)).toEqual({ addressesMoved: 0 });
    expect(findCustomer(2)?.linkDecisionPending).toBe(true);
    expect(linkCandidates('0905111222').map((c) => c.customerId)).toEqual([4]);
    expect(mergeCustomerRecords(4, 2)).toEqual({ addressesMoved: 0 });
    expect(linkCandidates('0905111222')).toEqual([]);
    expect(findCustomer(2)).toMatchObject({ linkDecisionPending: false, hasData: true });
    expect(findCustomer(3)?.mergedIntoId).toBe(2);
    expect(mergeCustomerRecords(99, 2)).toBeNull();
  });
});

describe('state cũ trong localStorage', () => {
  it('state khách / thú chỉ có {} vẫn xem trước và gộp được', async () => {
    const a = await newProfile('Hà', '0905777001', [['Bi', 'DOG']]);
    const b = await newProfile('Hà', '0905777001');
    localStorage.setItem('petcare-mock-pets', '{}');
    localStorage.setItem('petcare-mock-customer', '{}');
    localStorage.setItem('petcare-mock-custmerge', '{}');
    // `{}` cho state khách về dữ liệu mẫu: hồ sơ vừa tạo mất, nhưng mọi route không nổ và hồ sơ mẫu vẫn dùng được.
    const p = await preview(4, 3);
    expect(p.petCount).toBe(1);
    expect((await failure(merge(confirmed(a, b), await manager()))).data.errorCode).toBe('RESOURCE_NOT_FOUND');
    expect((await failure(merge(confirmed(3, 4), await manager()))).data.message).toMatch(/\(BR-KH-11\)$/);
  });

  it('state khách cũ không có mergedIntoId: hồ sơ coi như chưa gộp', async () => {
    const mgr = await manager();
    const customers = [
      { customerId: 1, accountId: 7, fullName: 'Nguyễn Thu Trang', phone: '0912345678', email: null, avatarUrl: null, createdChannel: 'ONLINE', linkDecisionPending: false, hasData: true },
      { customerId: 3, accountId: null, fullName: 'Lê Hoàng Long', phone: '0905111222', email: null, avatarUrl: null, createdChannel: 'COUNTER', linkDecisionPending: false, hasData: false },
      { customerId: 4, accountId: null, fullName: 'Lê Thị Hoa', phone: '0905111222', email: null, avatarUrl: null, createdChannel: 'COUNTER', linkDecisionPending: false, hasData: false },
    ];
    localStorage.setItem('petcare-mock-customer', JSON.stringify({ customers }));
    expect((await preview(3, 4, mgr)).targetCustomerId).toBe(3);
  });
});
