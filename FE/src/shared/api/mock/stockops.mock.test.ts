import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it } from 'vitest';
import { ruleIdOf } from '../api-error';
import type { PageResponse } from '../../types';
import type { StockAdjustment } from '../../types/inventory';
import type { LotSummary, LotTrace, StockCount, StockCountSummary, StockTransfer, StockTransferSummary } from '../../types/stockops';
import { addDays } from '../../utils/branch-schedule';
import { vnDate } from '../../utils/vn-time';
import { MOCK_PASSWORD } from './identity.mock';
import { allLots, issueFefo, issueOneFefo, lotMovements } from './inventory.mock';
import { inTransitTransfersOf, openStockCountsOf } from './stockops.mock';
import { installMockApi } from './mock-api';

// Dữ liệu mẫu (stockops.mock.ts + SEED_TRANSFERS / sổ lô của inventory.mock.ts), ngày tương đối với hôm nay:
//  phiếu #1 CANCELLED 2→1 (8 ngày trước, lô 18 ×2) · #2 RECEIVED 1→2 (6 ngày, lô 11 ×5 → lô 18) · #3 IN_TRANSIT 1→2 (2 ngày, lô 13 AMX2405 ×30) · #4 IN_TRANSIT 2→1 (1 ngày, lô 18 ×3).
//  phiên #1 CONFIRMED CH1 (vaccine, đếm khớp) · #2 CANCELLED CH1 · #3 OPEN CH2 (lô 18).
//  Tồn mẫu CH1: lô 11 = 12, 12 = 3, 13 = 200, 14 = 500, 15 = 15, 16 = 2 (hết hạn), 17 = 4; CH2: lô 18 = 7. Chỉ CH1 ACTIVE (branch.mock).

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
const ruleOf = (res: AxiosResponse) => ruleIdOf(res.data.message);
const outcome = async (request: Promise<AxiosResponse>) => {
  try {
    return String((await request).status);
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return `${e.response.status} ${e.response.data.errorCode}`;
    throw e;
  }
};

const today = vnDate();
const dayCode = (iso: string) => iso.slice(2).replace(/-/g, '');
const idsOf = (rows: { transferId?: number; countId?: number; lotId?: number }[]) => rows.map((r) => r.transferId ?? r.countId ?? r.lotId);
const lotsOf = async (who: Who, productId: number) => (await data<PageResponse<LotSummary>>(who, 'get', '/api/stock-lots', undefined, { productId })).content;
const qty = async (productId: number, lotId: number) => (await lotsOf('manager', productId)).find((l) => l.lotId === lotId)?.quantity;
const transfersOf = async (who: Who, params: object = {}) => data<PageResponse<StockTransferSummary>>(who, 'get', '/api/stock-transfers', undefined, params);
const countsOf = async (who: Who, params: object = {}) => data<PageResponse<StockCountSummary>>(who, 'get', '/api/stock-counts', undefined, params);

/** Kích hoạt chi nhánh 2 (branch.mock chỉ có chi nhánh 1 ACTIVE) qua API: quản lý CH2 đặt giờ mở cửa, SUPER_MANAGER kích hoạt (cần có quản lý và giờ mở cửa). */
async function activateBranch2() {
  const days = [1, 2, 3, 4, 5, 6, 7].map((dayOfWeek) => ({ dayOfWeek, ranges: [{ open: '08:00', close: '17:00' }] }));
  await send('manager2', 'put', '/api/branches/2/opening-hours', { effectiveFrom: today, days });
  await send('chuoi', 'post', '/api/branches/2/activate');
}

/** Tổng sổ của mỗi lô phải bằng tồn của lô (ghi sổ cùng chỗ đổi tồn). */
function expectLedgerBalanced() {
  for (const lot of allLots()) {
    expect(lotMovements(lot.lotId).reduce((sum, m) => sum + m.quantity, 0), `sổ lô ${lot.lotId}`).toBe(lot.quantity);
  }
}

// ─── Phân quyền: 13 endpoint × 8 tài khoản + 401 ───

const DENIED = '403 ACCESS_DENIED';
const SCOPE = '403 ACCESS_DENIED_SCOPE_MISMATCH';
interface Case {
  name: string;
  call: (who: Who | null) => Promise<AxiosResponse>;
  /** Kết quả riêng của từng tài khoản; còn lại 403 ACCESS_DENIED. */
  expect: Partial<Record<Who, string>>;
}
const CASES: Case[] = [
  // CH2 → CH1 (CH1 ACTIVE): manager2 tạo được; manager (CH1 → CH1) bị BR-KO-08 vì đích trùng nguồn, nghĩa là qua cổng vai trò.
  { name: '#1 POST /stock-transfers', call: (w) => send(w, 'post', '/api/stock-transfers', { destBranchId: 1, lines: [{ productId: 1, quantity: 1 }] }), expect: { manager: '400 BUSINESS_RULE_VIOLATION', manager2: '201' } },
  { name: '#2 GET /stock-transfers', call: (w) => send(w, 'get', '/api/stock-transfers'), expect: { manager: '200', manager2: '200' } },
  { name: '#3 GET /stock-transfers/3', call: (w) => send(w, 'get', '/api/stock-transfers/3'), expect: { manager: '200', manager2: '200' } },
  { name: '#4 POST /stock-transfers/3/receive', call: (w) => send(w, 'post', '/api/stock-transfers/3/receive'), expect: { manager: SCOPE, manager2: '200' } },
  { name: '#5 POST /stock-transfers/3/cancel', call: (w) => send(w, 'post', '/api/stock-transfers/3/cancel', { reason: 'Nhầm lô' }), expect: { manager: '200', manager2: '200' } },
  { name: '#6 POST /stock-counts', call: (w) => send(w, 'post', '/api/stock-counts', { scope: 'ALL' }), expect: { manager: '201', manager2: '409 INVALID_STATE_TRANSITION' } },
  { name: '#7 GET /stock-counts', call: (w) => send(w, 'get', '/api/stock-counts'), expect: { manager: '200', manager2: '200' } },
  { name: '#8 GET /stock-counts/1', call: (w) => send(w, 'get', '/api/stock-counts/1'), expect: { manager: '200', manager2: SCOPE } },
  { name: '#9 PUT /stock-counts/3/lines', call: (w) => send(w, 'put', '/api/stock-counts/3/lines', { lines: [{ stockLotId: 18, countedQty: 5 }] }), expect: { manager: SCOPE, manager2: '200' } },
  { name: '#10 POST /stock-counts/3/confirm', call: (w) => send(w, 'post', '/api/stock-counts/3/confirm'), expect: { manager: SCOPE, manager2: '400 BUSINESS_RULE_VIOLATION' } },
  { name: '#11 POST /stock-counts/3/cancel', call: (w) => send(w, 'post', '/api/stock-counts/3/cancel', { reason: 'Đếm lại' }), expect: { manager: SCOPE, manager2: '200' } },
  { name: '#12 GET /stock-lots', call: (w) => send(w, 'get', '/api/stock-lots', undefined, { productId: 3 }), expect: { manager: '200', manager2: '200', doctor: '200' } },
  { name: '#13 GET /stock-lots/15/trace', call: (w) => send(w, 'get', '/api/stock-lots/15/trace'), expect: { manager: '200', manager2: SCOPE, doctor: '200' } },
];

describe('phân quyền (dòng "Quyền:" của yaml): mỗi endpoint × 8 tài khoản', () => {
  it.each(CASES.flatMap((c) => WHO.map((who) => [c.name, who, c.expect[who] ?? DENIED] as const)))('%s với %s → %s', async (name, who, expected) => {
    const found = CASES.find((c) => c.name === name)!;
    expect(await outcome(found.call(who))).toBe(expected);
  });

  it.each(CASES.map((c) => c.name))('%s không token → 401', async (name) => {
    const res = await failure(CASES.find((c) => c.name === name)!.call(null));
    expect(res.status).toBe(401);
    expect(res.data.errorCode).toBe('UNAUTHENTICATED');
  });

  it('403 phạm vi dùng câu chung, không lộ mã rule', async () => {
    const res = await bad('manager', 'get', '/api/stock-counts/3');
    expect(res.data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect(ruleOf(res)).toBeNull();
  });
});

// ─── Chuyển kho ───

describe('chuyển kho: xem (#2, #3, BR-KO-11)', () => {
  it('mới nhất trước; lọc theo chiều và trạng thái; hai quản lý cùng thấy phiếu liên quan chi nhánh mình', async () => {
    expect(idsOf((await transfersOf('manager')).content)).toEqual([4, 3, 2, 1]);
    expect(idsOf((await transfersOf('manager', { direction: 'OUTGOING' })).content)).toEqual([3, 2]);
    expect(idsOf((await transfersOf('manager', { direction: 'INCOMING' })).content)).toEqual([4, 1]);
    expect(idsOf((await transfersOf('manager2', { direction: 'OUTGOING' })).content)).toEqual([4, 1]);
    expect(idsOf((await transfersOf('manager', { status: 'IN_TRANSIT' })).content)).toEqual([4, 3]);
    expect(idsOf((await transfersOf('manager', { status: 'RECEIVED', direction: 'INCOMING' })).content)).toEqual([]);
    const first = (await transfersOf('manager')).content.at(-1)!;
    expect(first).toEqual({ transferId: 1, code: `CK-${dayCode(addDays(today, -8))}-0001`, sourceBranchId: 2, destBranchId: 1, status: 'CANCELLED', lineCount: 1, createdAt: expect.any(String) });
  });

  it('phân trang: size 2 → 2 trang; page vượt quá → rỗng; size ngoài khoảng bị kẹp', async () => {
    expect(await transfersOf('manager', { size: 2 })).toMatchObject({ page: 0, size: 2, totalElements: 4, totalPages: 2 });
    expect(idsOf((await transfersOf('manager', { size: 2, page: 1 })).content)).toEqual([2, 1]);
    expect((await transfersOf('manager', { page: 9 })).content).toEqual([]);
    expect(await transfersOf('manager', { size: 500 })).toMatchObject({ size: 100 });
  });

  it('tham số sai kiểu → 400 MALFORMED_REQUEST, đến trước cả 403 vai trò', async () => {
    for (const params of [{ direction: 'SIDEWAYS' }, { status: 'DONE' }, { page: 'x' }, { size: '1.5' }]) {
      const res = await bad('khach', 'get', '/api/stock-transfers', undefined, params);
      expect(res.status).toBe(400);
      expect(res.data.errorCode).toBe('MALFORMED_REQUEST');
    }
  });

  it('chi tiết: tên chi nhánh, dòng kèm lô và hạn; id không có → 404; id sai kiểu → 400; phiếu của hai chi nhánh khác → 403 phạm vi', async () => {
    const t = await data<StockTransfer>('manager', 'get', '/api/stock-transfers/3');
    expect(t).toMatchObject({ transferId: 3, status: 'IN_TRANSIT', sourceBranchName: 'PetCare Cầu Giấy', destBranchName: 'PetCare Hà Đông', receivedAt: null, cancelledAt: null, cancelReason: null });
    expect(t.lines).toEqual([{ productId: 3, productName: 'Amoxicillin 250mg', quantity: 30, lotNumber: 'AMX2405', expiryDate: addDays(today, 20) }]);
    expect((await data<StockTransfer>('manager', 'get', '/api/stock-transfers/1')).cancelReason).toBe('Chi nhánh đích đã đủ hàng');
    expect((await bad('manager', 'get', '/api/stock-transfers/999')).status).toBe(404);
    expect((await bad('manager', 'get', '/api/stock-transfers/abc')).data.errorCode).toBe('MALFORMED_REQUEST');

    const stored = JSON.parse(localStorage.getItem('petcare-mock-stockops')!);
    stored.transfers.push({ ...stored.transfers[0], transferId: 9, sourceBranchId: 2, destBranchId: 3 });
    localStorage.setItem('petcare-mock-stockops', JSON.stringify(stored));
    expect((await bad('manager', 'get', '/api/stock-transfers/9')).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect(idsOf((await transfersOf('manager')).content)).not.toContain(9);
    expect(idsOf((await transfersOf('manager2')).content)).toContain(9);
  });
});

describe('chuyển kho: tạo (#1, BR-KO-08)', () => {
  it('chi nhánh 2 → 1: trừ lô nguồn ngay, phiếu IN_TRANSIT mã CK-yyMMdd-0005, dòng ghi lô; chi nhánh 1 thấy phiếu đến', async () => {
    expect(await qty(1, 18)).toBeUndefined(); // CH1 không thấy lô của CH2
    const created = await send('manager2', 'post', '/api/stock-transfers', { destBranchId: 1, lines: [{ productId: 1, quantity: 4 }] });
    expect(created.status).toBe(201);
    const t = created.data.data as StockTransfer;
    expect(t).toMatchObject({ transferId: 5, code: `CK-${dayCode(today)}-0005`, status: 'IN_TRANSIT', sourceBranchId: 2, destBranchId: 1, sourceBranchName: 'PetCare Hà Đông', destBranchName: 'PetCare Cầu Giấy' });
    expect(t.lines).toEqual([{ productId: 1, productName: 'Hạt Royal Canin Mini Adult 2kg', quantity: 4, lotNumber: null, expiryDate: null }]);
    expect((await lotsOf('manager2', 1))[0]).toMatchObject({ lotId: 18, quantity: 3 });
    expect(idsOf((await transfersOf('manager', { direction: 'INCOMING' })).content)).toEqual([5, 4, 1]);
    expectLedgerBalanced();
  });

  it('FEFO: sản phẩm trải qua nhiều lô thì phiếu có nhiều dòng, mỗi dòng một lô; lô hết hạn không bị lấy', async () => {
    await activateBranch2();
    const t = await data<StockTransfer>('manager', 'post', '/api/stock-transfers', { destBranchId: 2, lines: [{ productId: 3, quantity: 250 }, { productId: 5, quantity: 15 }] });
    expect(t.lines.map((l) => [l.productId, l.lotNumber, l.quantity])).toEqual([
      [3, 'AMX2405', 200],
      [3, 'AMX2501', 50],
      [5, 'RB2401', 15],
    ]);
    expect((await transfersOf('manager')).content[0].lineCount).toBe(3);
    expect(await qty(3, 13)).toBe(0);
    expect(await qty(3, 14)).toBe(450);
    expect(await qty(5, 16)).toBe(2); // RB2309 đã hết hạn: nằm yên
    expectLedgerBalanced();
  });

  it('thiếu tồn khả dụng (không tính lô hết hạn) → 400 BR-KO-08 và không trừ gì ở dòng nào', async () => {
    await activateBranch2();
    const res = await bad('manager', 'post', '/api/stock-transfers', { destBranchId: 2, lines: [{ productId: 3, quantity: 10 }, { productId: 5, quantity: 16 }] });
    expect(res.status).toBe(400);
    expect(res.data.errorCode).toBe('BUSINESS_RULE_VIOLATION');
    expect(ruleOf(res)).toBe('BR-KO-08');
    expect(res.data.message).toContain('Rabisin');
    expect(res.data.message).toContain('tồn khả dụng 15');
    expect(await qty(3, 13)).toBe(200);
    expect((await transfersOf('manager')).totalElements).toBe(4);
  });

  it('đích trùng nguồn / không ACTIVE / trùng sản phẩm → 400 BR-KO-08', async () => {
    const lines = [{ productId: 1, quantity: 1 }];
    expect(ruleOf(await bad('manager', 'post', '/api/stock-transfers', { destBranchId: 1, lines }))).toBe('BR-KO-08');
    const draft = await bad('manager', 'post', '/api/stock-transfers', { destBranchId: 2, lines });
    expect(ruleOf(draft)).toBe('BR-KO-08');
    expect(draft.data.message).toContain('chưa hoạt động');
    await activateBranch2();
    const twice = await bad('manager', 'post', '/api/stock-transfers', { destBranchId: 2, lines: [{ productId: 1, quantity: 1 }, { productId: 1, quantity: 2 }] });
    expect(ruleOf(twice)).toBe('BR-KO-08');
    expect(twice.data.message).toContain('nhiều dòng');
  });

  it('404 chi nhánh đích / sản phẩm đến trước BR-KO-08', async () => {
    expect((await bad('manager', 'post', '/api/stock-transfers', { destBranchId: 99, lines: [{ productId: 1, quantity: 1 }] })).status).toBe(404);
    expect((await bad('manager', 'post', '/api/stock-transfers', { destBranchId: 1, lines: [{ productId: 999, quantity: 1 }] })).status).toBe(404);
  });

  it('thiếu / sai trường → VALIDATION_FAILED theo trường (trước vai trò); sai kiểu JSON → MALFORMED_REQUEST', async () => {
    for (const body of [{}, { destBranchId: 1 }, { destBranchId: 1, lines: [] }, { destBranchId: 1, lines: [{ productId: 1, quantity: 0 }] }, { destBranchId: 1, lines: [{ quantity: 1 }] }]) {
      const res = await bad('khach', 'post', '/api/stock-transfers', body);
      expect(res.data.errorCode).toBe('VALIDATION_FAILED');
    }
    expect((await bad('manager2', 'post', '/api/stock-transfers', { destBranchId: 1, lines: [{ productId: 1, quantity: 0 }] })).data.message).toContain('lines[0].quantity:');
    for (const body of [{ destBranchId: 'a', lines: [] }, { destBranchId: 1, lines: 'x' }, { destBranchId: 1, lines: [{ productId: '1', quantity: 1 }] }, null]) {
      expect((await bad('manager2', 'post', '/api/stock-transfers', body)).data.errorCode).toBe('MALFORMED_REQUEST');
    }
  });
});

describe('chuyển kho: nhận hàng và hủy (#4, #5, BR-KO-09…11)', () => {
  it('nhận: chỉ chi nhánh đích; lô đích có đúng số lô, hạn dùng, số lượng; chi nhánh nguồn không nhận được', async () => {
    expect(await outcome(send('manager', 'post', '/api/stock-transfers/3/receive'))).toBe(SCOPE);
    const t = await data<StockTransfer>('manager2', 'post', '/api/stock-transfers/3/receive');
    expect(t).toMatchObject({ status: 'RECEIVED', cancelledAt: null });
    expect(Date.parse(t.receivedAt!)).toBeGreaterThan(Date.now() - 5000);
    const lots = (await data<PageResponse<LotSummary>>('manager2', 'get', '/api/stock-lots', undefined, { lotNumber: 'amx2405' })).content;
    expect(lots).toHaveLength(1);
    expect(lots[0]).toMatchObject({ productId: 3, lotNumber: 'AMX2405', expiryDate: addDays(today, 20), branchId: 2, quantity: 30 });
    expectLedgerBalanced();
  });

  it('nhận hàng không quản lý hạn dùng cộng vào lô mặc định có sẵn của chi nhánh đích', async () => {
    const t = await data<StockTransfer>('manager', 'post', '/api/stock-transfers/4/receive');
    expect(t.status).toBe('RECEIVED');
    expect(await qty(1, 11)).toBe(15);
    expect((await lotsOf('manager', 1)).filter((l) => l.lotNumber === null)).toHaveLength(1);
    expectLedgerBalanced();
  });

  it('nhận lần hai, nhận phiếu đã hủy → 409 INVALID_STATE_TRANSITION', async () => {
    for (const id of [2, 1]) {
      const res = await bad(id === 2 ? 'manager2' : 'manager', 'post', `/api/stock-transfers/${id}/receive`);
      expect(res.status).toBe(409);
      expect(res.data.errorCode).toBe('INVALID_STATE_TRANSITION');
    }
  });

  it('hủy bởi chi nhánh nguồn: hoàn đúng lô nguồn, ghi lý do', async () => {
    const t = await data<StockTransfer>('manager', 'post', '/api/stock-transfers/3/cancel', { reason: '  Hàng chưa xuất kho  ' });
    expect(t).toMatchObject({ status: 'CANCELLED', cancelReason: 'Hàng chưa xuất kho' });
    expect(await qty(3, 13)).toBe(230);
    expectLedgerBalanced();
  });

  it('hủy bởi chi nhánh đích cũng được; hoàn kể cả khi lô nguồn đã hết hạn lúc hoàn', async () => {
    const t = await data<StockTransfer>('manager', 'post', '/api/stock-transfers/4/cancel', { reason: 'Không cần nữa' });
    expect(t.status).toBe('CANCELLED');
    expect((await lotsOf('manager2', 1)).find((l) => l.lotId === 18)?.quantity).toBe(10);
    expectLedgerBalanced();
  });

  it('thiếu lý do (trống, toàn khoảng trắng, không gửi) → 400 BR-KO-10; quá 300 ký tự → VALIDATION_FAILED; body sai kiểu → MALFORMED_REQUEST', async () => {
    for (const body of [{ reason: '' }, { reason: '   ' }, {}, { reason: null }]) {
      const res = await bad('manager', 'post', '/api/stock-transfers/3/cancel', body);
      expect(res.status).toBe(400);
      expect(ruleOf(res)).toBe('BR-KO-10');
    }
    const long = await bad('manager', 'post', '/api/stock-transfers/3/cancel', { reason: 'x'.repeat(301) });
    expect(long.data.errorCode).toBe('VALIDATION_FAILED');
    expect((await data<StockTransfer>('manager', 'post', '/api/stock-transfers/3/cancel', { reason: 'x'.repeat(300) })).status).toBe('CANCELLED');
    expect((await bad('manager', 'post', '/api/stock-transfers/4/cancel', { reason: 5 })).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await bad('manager', 'post', '/api/stock-transfers/4/cancel')).data.errorCode).toBe('MALFORMED_REQUEST');
  });

  it('không hủy sau RECEIVED hoặc hủy lần hai → 409; 404 đến trước BR-KO-10', async () => {
    expect((await bad('manager', 'post', '/api/stock-transfers/2/cancel', { reason: 'Nhầm' })).data.errorCode).toBe('INVALID_STATE_TRANSITION');
    expect((await bad('manager', 'post', '/api/stock-transfers/1/cancel', { reason: 'Nhầm' })).status).toBe(409);
    expect((await bad('manager', 'post', '/api/stock-transfers/999/cancel', { reason: '' })).status).toBe(404);
  });
});

// ─── Kiểm kê ───

describe('kiểm kê: xem và mở phiên (#6, #7, #8, BR-KO-12)', () => {
  it('danh sách của chi nhánh mình, mới nhất trước, lọc trạng thái', async () => {
    expect(idsOf((await countsOf('manager')).content)).toEqual([2, 1]);
    expect(idsOf((await countsOf('manager', { status: 'CONFIRMED' })).content)).toEqual([1]);
    expect(idsOf((await countsOf('manager', { status: 'OPEN' })).content)).toEqual([]);
    expect(idsOf((await countsOf('manager2')).content)).toEqual([3]);
    expect((await bad('manager', 'get', '/api/stock-counts', undefined, { status: 'DONE' })).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await countsOf('manager')).content[1]).toEqual({ countId: 1, code: `KK-${dayCode(addDays(today, -14))}-0001`, status: 'CONFIRMED', scope: 'CATEGORY', createdAt: expect.any(String), confirmedAt: expect.any(String) });
  });

  it('phiên đã xác nhận đóng băng tồn hệ thống; phiên đã hủy hiện tồn hiện tại và lý do', async () => {
    const confirmed = await data<StockCount>('manager', 'get', '/api/stock-counts/1');
    expect(confirmed).toMatchObject({ branchId: 1, status: 'CONFIRMED', scope: 'CATEGORY', categoryId: 3 });
    expect(confirmed.lines.map((l) => [l.stockLotId, l.lotNumber, l.countedQty, l.systemQty])).toEqual([[15, 'RB2401', 17, 17], [16, 'RB2309', 2, 2], [17, 'VG2402', 5, 5]]);
    const cancelled = await data<StockCount>('manager', 'get', '/api/stock-counts/2');
    expect(cancelled.cancelReason).toBe('Mở nhầm phạm vi, đếm lại theo toàn kho');
    expect(cancelled.lines.map((l) => [l.stockLotId, l.countedQty, l.systemQty])).toEqual([[11, 12, 12], [12, null, 3]]);
    expect((await bad('manager', 'get', '/api/stock-counts/999')).status).toBe(404);
    expect((await bad('manager', 'get', '/api/stock-counts/x')).data.errorCode).toBe('MALFORMED_REQUEST');
  });

  it('mở toàn kho: mọi lô còn tồn của chi nhánh (kể cả hết hạn), theo sản phẩm rồi hạn dùng; chưa đếm', async () => {
    const res = await send('manager', 'post', '/api/stock-counts', { scope: 'ALL' });
    expect(res.status).toBe(201);
    const c = res.data.data as StockCount;
    expect(c).toMatchObject({ countId: 4, code: `KK-${dayCode(today)}-0004`, branchId: 1, status: 'OPEN', scope: 'ALL', categoryId: null, confirmedAt: null });
    expect(c.lines.map((l) => l.stockLotId)).toEqual([11, 12, 13, 14, 16, 15, 17]);
    expect(c.lines.every((l) => l.countedQty === null)).toBe(true);
    expect(c.lines.find((l) => l.stockLotId === 13)).toMatchObject({ productName: 'Amoxicillin 250mg', lotNumber: 'AMX2405', expiryDate: addDays(today, 20), systemQty: 200 });
  });

  it('mở theo danh mục: chỉ lô của sản phẩm thuộc danh mục; scope ALL bỏ qua categoryId', async () => {
    const c = await data<StockCount>('manager', 'post', '/api/stock-counts', { scope: 'CATEGORY', categoryId: 3 });
    expect(c).toMatchObject({ scope: 'CATEGORY', categoryId: 3 });
    expect(c.lines.map((l) => l.stockLotId)).toEqual([16, 15, 17]);
    await data<StockCount>('manager', 'post', '/api/stock-counts/4/cancel', { reason: 'Mở lại' });
    expect((await data<StockCount>('manager', 'post', '/api/stock-counts', { scope: 'ALL', categoryId: 3 })).categoryId).toBeNull();
  });

  it('thiếu / không có danh mục → 400 BR-KO-12; scope lạ → MALFORMED_REQUEST; thiếu scope → VALIDATION_FAILED (trước vai trò)', async () => {
    expect(ruleOf(await bad('manager', 'post', '/api/stock-counts', { scope: 'CATEGORY' }))).toBe('BR-KO-12');
    expect(ruleOf(await bad('manager', 'post', '/api/stock-counts', { scope: 'CATEGORY', categoryId: 99 }))).toBe('BR-KO-12');
    expect((await bad('manager', 'post', '/api/stock-counts', { scope: 'SOME' })).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await bad('manager', 'post', '/api/stock-counts', { scope: 'CATEGORY', categoryId: 'x' })).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await bad('khach', 'post', '/api/stock-counts', {})).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await bad('manager', 'post', '/api/stock-counts', null)).data.errorCode).toBe('MALFORMED_REQUEST');
  });

  it('mỗi chi nhánh tối đa một phiên OPEN → 409; hủy hoặc xác nhận xong thì mở lại được', async () => {
    await send('manager', 'post', '/api/stock-counts', { scope: 'ALL' });
    const again = await bad('manager', 'post', '/api/stock-counts', { scope: 'ALL' });
    expect(again.status).toBe(409);
    expect(again.data.errorCode).toBe('INVALID_STATE_TRANSITION');
    await send('manager', 'post', '/api/stock-counts/4/cancel', { reason: 'Mở nhầm' });
    expect((await send('manager', 'post', '/api/stock-counts', { scope: 'ALL' })).status).toBe(201);
  });
});

describe('kiểm kê: nhập số đếm, xác nhận, hủy (#9, #10, #11, BR-KO-12)', () => {
  const open = async (body: object = { scope: 'ALL' }) => (await data<StockCount>('manager', 'post', '/api/stock-counts', body)).countId;
  const lineOf = (c: StockCount, lotId: number) => c.lines.find((l) => l.stockLotId === lotId)!;
  const save = (countId: number, lines: object[]) => data<StockCount>('manager', 'put', `/api/stock-counts/${countId}/lines`, { lines });

  it('ghi đè số đếm của lô gửi lên, lô không gửi giữ nguyên; gửi lại y hệt cho kết quả y hệt', async () => {
    const id = await open();
    const first = await save(id, [{ stockLotId: 11, countedQty: 10 }, { stockLotId: 12, countedQty: 0 }]);
    expect(lineOf(first, 11).countedQty).toBe(10);
    expect(lineOf(first, 12).countedQty).toBe(0);
    expect(lineOf(first, 13).countedQty).toBeNull();
    const second = await save(id, [{ stockLotId: 11, countedQty: 10 }, { stockLotId: 12, countedQty: 0 }]);
    expect(second).toEqual(first);
    const third = await save(id, [{ stockLotId: 11, countedQty: 9 }]);
    expect(lineOf(third, 11).countedQty).toBe(9);
    expect(lineOf(third, 12).countedQty).toBe(0);
    expect(await save(id, [])).toEqual(third);
  });

  it('lô ngoài phạm vi phiên → 400 BR-KO-12; số đếm âm / thập phân hoặc trùng lô → VALIDATION_FAILED; sai kiểu → MALFORMED_REQUEST', async () => {
    const id = await open({ scope: 'CATEGORY', categoryId: 3 });
    const outside = await bad('manager', 'put', `/api/stock-counts/${id}/lines`, { lines: [{ stockLotId: 11, countedQty: 1 }] });
    expect(ruleOf(outside)).toBe('BR-KO-12');
    expect((await bad('manager', 'put', `/api/stock-counts/${id}/lines`, { lines: [{ stockLotId: 15, countedQty: -1 }] })).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await bad('manager', 'put', `/api/stock-counts/${id}/lines`, { lines: [{ stockLotId: 15, countedQty: 1.5 }] })).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await bad('manager', 'put', `/api/stock-counts/${id}/lines`, { lines: [{ stockLotId: 15, countedQty: 1 }, { stockLotId: 15, countedQty: 2 }] })).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await bad('manager', 'put', `/api/stock-counts/${id}/lines`, {})).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await bad('manager', 'put', `/api/stock-counts/${id}/lines`, { lines: [{ stockLotId: 15, countedQty: '3' }] })).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await bad('manager', 'put', `/api/stock-counts/${id}/lines`, { lines: 'x' })).data.errorCode).toBe('MALFORMED_REQUEST');
    expect(lineOf(await data<StockCount>('manager', 'get', `/api/stock-counts/${id}`), 15).countedQty).toBeNull();
  });

  it('phiên không còn OPEN → 409; 404 phiên không có', async () => {
    const res = await bad('manager', 'put', '/api/stock-counts/1/lines', { lines: [{ stockLotId: 15, countedQty: 1 }] });
    expect(res.status).toBe(409);
    expect(res.data.errorCode).toBe('INVALID_STATE_TRANSITION');
    expect((await bad('manager', 'put', '/api/stock-counts/999/lines', { lines: [] })).status).toBe(404);
  });

  it('xác nhận: một phiếu điều chỉnh COUNT_DIFF cho lô lệch (số đếm − tồn), lô khớp và lô chưa đếm không đụng tới', async () => {
    const id = await open({ scope: 'CATEGORY', categoryId: 3 });
    await save(id, [{ stockLotId: 15, countedQty: 14 }, { stockLotId: 16, countedQty: 2 }, { stockLotId: 17, countedQty: 7 }]);
    const confirmed = await data<StockCount>('manager', 'post', `/api/stock-counts/${id}/confirm`);
    expect(confirmed.status).toBe('CONFIRMED');
    expect(Date.parse(confirmed.confirmedAt!)).toBeGreaterThan(Date.now() - 5000);
    expect(confirmed.lines.map((l) => [l.stockLotId, l.countedQty, l.systemQty])).toEqual([[16, 2, 2], [15, 14, 15], [17, 7, 4]]);

    const adjustments = (await data<PageResponse<StockAdjustment>>('manager', 'get', '/api/stock-adjustments')).content;
    expect(adjustments).toHaveLength(2);
    const diff = adjustments[0];
    expect(diff.reason).toBe('COUNT_DIFF');
    expect(diff.note).toBe(`Kiểm kê ${confirmed.code}`);
    expect(diff.lines.map((l) => [l.stockLotId, l.quantityDelta, l.balanceAfter])).toEqual([[15, -1, 14], [17, 3, 7]]);
    expect(await qty(5, 15)).toBe(14);
    expect(await qty(6, 17)).toBe(7);
    const trace = await data<LotTrace>('manager', 'get', '/api/stock-lots/15/trace');
    expect(trace.events.at(-1)).toMatchObject({ kind: 'ADJUSTMENT', quantity: -1, documentCode: diff.code });
    expectLedgerBalanced();
  });

  it('lệch tính theo tồn hệ thống LÚC XÁC NHẬN, không phải lúc mở: tồn đổi giữa chừng mà khớp số đếm thì không điều chỉnh', async () => {
    const id = await open();
    await save(id, [{ stockLotId: 11, countedQty: 10 }]);
    await data<StockAdjustment>('manager', 'post', '/api/stock-adjustments', { reason: 'DAMAGED', lines: [{ stockLotId: 11, quantityDelta: -2 }] });
    const confirmed = await data<StockCount>('manager', 'post', `/api/stock-counts/${id}/confirm`);
    expect(lineOf(confirmed, 11)).toMatchObject({ countedQty: 10, systemQty: 10 });
    expect((await data<PageResponse<StockAdjustment>>('manager', 'get', '/api/stock-adjustments')).totalElements).toBe(2);
    // Sau xác nhận, tồn đổi tiếp nhưng phiên giữ tồn đã đóng băng.
    await data<StockAdjustment>('manager', 'post', '/api/stock-adjustments', { reason: 'DAMAGED', lines: [{ stockLotId: 11, quantityDelta: -1 }] });
    expect(lineOf(await data<StockCount>('manager', 'get', `/api/stock-counts/${id}`), 11).systemQty).toBe(10);
  });

  it('chưa nhập số đếm cho lô nào → 400 BR-KO-12; xác nhận lần hai → 409', async () => {
    const id = await open();
    expect(ruleOf(await bad('manager', 'post', `/api/stock-counts/${id}/confirm`))).toBe('BR-KO-12');
    await save(id, [{ stockLotId: 12, countedQty: 3 }]);
    expect((await data<StockCount>('manager', 'post', `/api/stock-counts/${id}/confirm`)).status).toBe('CONFIRMED');
    expect((await data<PageResponse<StockAdjustment>>('manager', 'get', '/api/stock-adjustments')).totalElements).toBe(1);
    expect((await bad('manager', 'post', `/api/stock-counts/${id}/confirm`)).data.errorCode).toBe('INVALID_STATE_TRANSITION');
    expect((await bad('manager', 'post', '/api/stock-counts/999/confirm')).status).toBe(404);
  });

  it('hủy: cần lý do (BR-KO-12), không điều chỉnh gì; hủy xong hoặc phiên đã xác nhận → 409', async () => {
    const id = await open();
    await save(id, [{ stockLotId: 11, countedQty: 1 }]);
    for (const body of [{ reason: ' ' }, {}]) expect(ruleOf(await bad('manager', 'post', `/api/stock-counts/${id}/cancel`, body))).toBe('BR-KO-12');
    expect((await bad('manager', 'post', `/api/stock-counts/${id}/cancel`, { reason: 'x'.repeat(301) })).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await bad('manager', 'post', `/api/stock-counts/${id}/cancel`)).data.errorCode).toBe('MALFORMED_REQUEST');
    const cancelled = await data<StockCount>('manager', 'post', `/api/stock-counts/${id}/cancel`, { reason: ' Đếm nhầm ' });
    expect(cancelled).toMatchObject({ status: 'CANCELLED', cancelReason: 'Đếm nhầm' });
    expect(await qty(1, 11)).toBe(12);
    expect((await data<PageResponse<StockAdjustment>>('manager', 'get', '/api/stock-adjustments')).totalElements).toBe(1);
    expect((await bad('manager', 'post', `/api/stock-counts/${id}/cancel`, { reason: 'Lại' })).data.errorCode).toBe('INVALID_STATE_TRANSITION');
    expect((await bad('manager', 'post', '/api/stock-counts/1/cancel', { reason: 'Lại' })).status).toBe(409);
  });
});

// ─── Truy vết lô ───

describe('tìm lô (#12, BR-KO-13)', () => {
  it('theo sản phẩm: mới nhập trước (id lô giảm dần); theo số lô: khớp một phần, không phân biệt hoa thường', async () => {
    expect(idsOf(await lotsOf('manager', 3))).toEqual([14, 13]);
    const byNumber = (n: string) => data<PageResponse<LotSummary>>('manager', 'get', '/api/stock-lots', undefined, { lotNumber: n });
    expect(idsOf((await byNumber('amx')).content)).toEqual([14, 13]);
    expect(idsOf((await byNumber('AMX2405')).content)).toEqual([13]);
    expect(idsOf((await byNumber('2401')).content)).toEqual([15]);
    expect((await byNumber('khong-co')).content).toEqual([]);
    expect((await lotsOf('manager', 3))[1]).toEqual({ lotId: 13, productId: 3, productName: 'Amoxicillin 250mg', lotNumber: 'AMX2405', expiryDate: addDays(today, 20), branchId: 1, quantity: 200 });
  });

  it('lô mặc định tìm được theo sản phẩm; chỉ thấy lô của chi nhánh mình', async () => {
    expect((await lotsOf('manager', 1)).map((l) => [l.lotId, l.lotNumber])).toEqual([[11, null]]);
    expect((await lotsOf('manager2', 1)).map((l) => l.lotId)).toEqual([18]);
    expect(await lotsOf('manager2', 3)).toEqual([]);
  });

  it('lô đã dùng hết (số dư 0) vẫn tìm và truy vết được', async () => {
    await data<StockAdjustment>('manager', 'post', '/api/stock-adjustments', { reason: 'EXPIRED', lines: [{ stockLotId: 16, quantityDelta: -2 }] });
    const lots = await lotsOf('manager', 5);
    expect(lots.find((l) => l.lotId === 16)).toMatchObject({ quantity: 0, lotNumber: 'RB2309' });
    expect((await data<LotTrace>('manager', 'get', '/api/stock-lots/16/trace')).remaining).toBe(0);
  });

  it('thiếu cả số lô lẫn sản phẩm → 400 BR-KO-13; productId sai kiểu → MALFORMED_REQUEST; phân trang', async () => {
    expect(ruleOf(await bad('manager', 'get', '/api/stock-lots'))).toBe('BR-KO-13');
    expect(ruleOf(await bad('manager', 'get', '/api/stock-lots', undefined, { lotNumber: '   ' }))).toBe('BR-KO-13');
    expect((await bad('manager', 'get', '/api/stock-lots', undefined, { productId: 'abc' })).data.errorCode).toBe('MALFORMED_REQUEST');
    const page = await data<PageResponse<LotSummary>>('manager', 'get', '/api/stock-lots', undefined, { productId: 3, size: 1, page: 1 });
    expect(page).toMatchObject({ page: 1, size: 1, totalElements: 2, totalPages: 2 });
    expect(idsOf(page.content)).toEqual([13]);
  });

  it('bác sĩ chỉ thấy lô thuốc, vaccine của chi nhánh mình: lô hàng thường bị lọc bỏ', async () => {
    expect(await lotsOf('doctor', 1)).toEqual([]);
    expect(await lotsOf('doctor', 2)).toEqual([]);
    expect(idsOf(await lotsOf('doctor', 3))).toEqual([14, 13]);
    expect(idsOf((await data<PageResponse<LotSummary>>('doctor', 'get', '/api/stock-lots', undefined, { lotNumber: 'rb' })).content)).toEqual([16, 15]);
  });
});

describe('truy vết lô (#13, BR-KO-13)', () => {
  const trace = (who: Who, lotId: number) => data<LotTrace>(who, 'get', `/api/stock-lots/${lotId}/trace`);

  it('đường đi cũ nhất trước, cộng lại đúng bằng tồn còn lại, cho mọi lô mẫu', async () => {
    for (const [who, lotId] of [['manager', 11], ['manager', 12], ['manager', 13], ['manager', 14], ['manager', 15], ['manager', 16], ['manager', 17], ['manager2', 18]] as const) {
      const t = await trace(who, lotId);
      const times = t.events.map((e) => Date.parse(e.occurredAt));
      expect(times, `lô ${lotId} cũ nhất trước`).toEqual([...times].sort((a, b) => a - b));
      expect(t.events.reduce((sum, e) => sum + e.quantity, 0), `lô ${lotId}`).toBe(t.remaining);
      expect(t.remaining).toBe(t.lot.quantity);
      expect(t.events.every((e) => e.branchId === t.lot.branchId)).toBe(true);
    }
  });

  it('lô vaccine: nhập theo phiếu nhập, ba mũi tiêm kèm tên thú và khách', async () => {
    const t = await trace('manager', 15);
    expect(t.lot).toMatchObject({ lotId: 15, lotNumber: 'RB2401', productName: 'Vaccine Dại Rabisin', branchId: 1, quantity: 15 });
    expect(t.events.map((e) => [e.kind, e.quantity])).toEqual([['RECEIPT', 18], ['VACCINATION', -1], ['VACCINATION', -1], ['VACCINATION', -1]]);
    expect(t.events[0].documentCode).toBe(`PN-${dayCode(addDays(today, -30))}-0001`);
    expect(t.events.slice(1).map((e) => [e.petName, e.customerName])).toEqual([['Mochi', expect.any(String)], ['Bim', expect.any(String)], ['Miu', expect.any(String)]]);
    expect(t.events[0].petName).toBeNull();
  });

  it('lô hàng thường: không lộ tên thú, khách kể cả khi mock kho có ghi; lô thuốc có', async () => {
    issueFefo(1, [{ productId: 1, quantity: 1 }], { petId: 1, customerId: 1 });
    issueFefo(1, [{ productId: 3, quantity: 1 }], { petId: 1, customerId: 1, documentCode: 'DH-000001' });
    const goods = await trace('manager', 11);
    expect(goods.events.at(-1)).toMatchObject({ kind: 'ORDER_ISSUE', quantity: -1, petName: null, customerName: null });
    const drug = await trace('manager', 13);
    expect(drug.events.at(-1)).toMatchObject({ kind: 'ORDER_ISSUE', quantity: -1, documentCode: 'DH-000001', petName: 'Mochi' });
    expect(drug.events.at(-1)?.customerName).toEqual(expect.any(String));
    expect(drug.events.find((e) => e.petName === 'Lu')).toBeDefined();
    expectLedgerBalanced();
  });

  it('nối được phiếu nhập, điều chỉnh, chuyển kho và hủy chuyển kho của lô; mã phiếu chuyển kho tra theo phiếu', async () => {
    const t11 = await trace('manager', 11);
    expect(t11.events.map((e) => [e.kind, e.quantity, e.documentCode])).toEqual([
      ['RECEIPT', 18, null],
      ['TRANSFER_OUT', -5, `CK-${dayCode(addDays(today, -6))}-0002`],
      ['ADJUSTMENT', -1, `DC-${dayCode(addDays(today, -5))}-0001`],
    ]);
    const t18 = await trace('manager2', 18);
    expect(t18.events.map((e) => [e.kind, e.quantity])).toEqual([['RECEIPT', 5], ['TRANSFER_OUT', -2], ['TRANSFER_CANCEL', 2], ['TRANSFER_IN', 5], ['TRANSFER_OUT', -3]]);
    await data<StockTransfer>('manager', 'post', '/api/stock-transfers/4/cancel', { reason: 'Không cần nữa' });
    const after = await trace('manager2', 18);
    expect(after.events.at(-1)).toMatchObject({ kind: 'TRANSFER_CANCEL', quantity: 3, documentCode: `CK-${dayCode(addDays(today, -1))}-0004` });
    expect(after.remaining).toBe(10);
  });

  it('nối được phiếu nhập kho và điều chỉnh tạo mới qua API kho', async () => {
    const receipt = await data<{ receiptId: number; code: string; lines: { stockLotId: number | null }[] }>('manager', 'post', '/api/stock-receipts', {
      supplierId: 2,
      receiptDate: today,
      lines: [{ productId: 3, quantity: 40, unitCost: 5000, lotNumber: 'AMX2601', expiryDate: addDays(today, 300) }],
    });
    const confirmed = await data<{ code: string; lines: { stockLotId: number }[] }>('manager', 'post', `/api/stock-receipts/${receipt.receiptId}/confirm`);
    const lotId = confirmed.lines[0].stockLotId;
    const t = await trace('manager', lotId);
    expect(t.events).toEqual([expect.objectContaining({ kind: 'RECEIPT', quantity: 40, documentCode: confirmed.code })]);
    await data('manager', 'post', `/api/stock-receipts/${receipt.receiptId}/cancel`, { reason: 'Nhập nhầm' });
    const cancelled = await trace('manager', lotId);
    expect(cancelled.remaining).toBe(0);
    expect(cancelled.events.map((e) => [e.kind, e.quantity])).toEqual([['RECEIPT', 40], ['ADJUSTMENT', -40]]);
    expectLedgerBalanced();
  });

  it('tiêm một mũi rồi xóa mũi (hoàn kho) đều có trong sổ', async () => {
    const issued = issueOneFefo(1, 5, { petId: 2, customerId: 1, documentCode: 'TN-001' })!;
    expect(issued.lotId).toBe(15);
    const t = await trace('doctor', 15);
    expect(t.events.at(-1)).toMatchObject({ kind: 'VACCINATION', quantity: -1, documentCode: 'TN-001', petName: 'Bim' });
    expect(t.remaining).toBe(14);
    expectLedgerBalanced();
  });

  it('bác sĩ: lô vaccine, thuốc xem được; lô hàng thường hoặc lô chi nhánh khác → 403 phạm vi; quản lý chi nhánh khác → 403 phạm vi; lô không có → 404', async () => {
    expect((await trace('doctor', 13)).events.length).toBeGreaterThan(0);
    for (const [who, lotId] of [['doctor', 11], ['doctor', 18], ['manager', 18], ['manager2', 11]] as const) {
      const res = await bad(who, 'get', `/api/stock-lots/${lotId}/trace`);
      expect(res.data.errorCode, `${who} lô ${lotId}`).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    }
    expect((await bad('manager', 'get', '/api/stock-lots/999/trace')).status).toBe(404);
    expect((await bad('manager', 'get', '/api/stock-lots/abc/trace')).data.errorCode).toBe('MALFORMED_REQUEST');
  });
});

// ─── Luồng nối chéo ───

describe('luồng nối chéo: chuyển kho, nhận, kiểm kê, đều giữ sổ lô cân với tồn', () => {
  it('tạo → nhận → kiểm kê lệch → hủy phiếu khác, tồn hai chi nhánh đúng từng bước', async () => {
    await activateBranch2();
    const t = await data<StockTransfer>('manager', 'post', '/api/stock-transfers', { destBranchId: 2, lines: [{ productId: 2, quantity: 3 }, { productId: 4, quantity: 1 }] }).catch(() => null);
    expect(t).toBeNull(); // sản phẩm 4 chưa có tồn ở CH1 → vượt tồn khả dụng (400), không trừ gì
    expect(await qty(2, 12)).toBe(3);

    const ok = await data<StockTransfer>('manager', 'post', '/api/stock-transfers', { destBranchId: 2, lines: [{ productId: 2, quantity: 3 }] });
    expect(await qty(2, 12)).toBe(0);
    const received = await data<StockTransfer>('manager2', 'post', `/api/stock-transfers/${ok.transferId}/receive`);
    expect(received.status).toBe('RECEIVED');
    expect((await lotsOf('manager2', 2)).map((l) => l.quantity)).toEqual([3]);

    const count = await data<StockCount>('manager2', 'post', '/api/stock-counts/3/cancel', { reason: 'Mở lại' });
    expect(count.status).toBe('CANCELLED');
    const open = await data<StockCount>('manager2', 'post', '/api/stock-counts', { scope: 'ALL' });
    expect(open.lines.map((l) => [l.stockLotId, l.systemQty])).toEqual(expect.arrayContaining([[18, 7]]));
    const mine = open.lines.find((l) => l.productId === 2)!;
    await data('manager2', 'put', `/api/stock-counts/${open.countId}/lines`, { lines: [{ stockLotId: mine.stockLotId, countedQty: 2 }] });
    await data('manager2', 'post', `/api/stock-counts/${open.countId}/confirm`);
    expect((await lotsOf('manager2', 2))[0].quantity).toBe(2);
    expectLedgerBalanced();
  });
});

describe('nguồn đếm cho branchlife (inTransitTransfers, openStockCounts)', () => {
  it('đếm phiếu đang chuyển ở cả hai đầu và phiên kiểm kê đang mở của đúng chi nhánh', async () => {
    expect(inTransitTransfersOf(1)).toBe(2); // #3 (nguồn) và #4 (đích)
    expect(inTransitTransfersOf(2)).toBe(2);
    expect(inTransitTransfersOf(3)).toBe(0);
    expect(openStockCountsOf(1)).toBe(0);
    expect(openStockCountsOf(2)).toBe(1);
    await data<StockTransfer>('manager', 'post', '/api/stock-transfers/3/cancel', { reason: 'Nhầm' });
    await data<StockTransfer>('manager', 'post', '/api/stock-transfers/4/receive');
    expect(inTransitTransfersOf(1)).toBe(0);
    expect(inTransitTransfersOf(2)).toBe(0);
    await data<StockCount>('manager', 'post', '/api/stock-counts', { scope: 'ALL' });
    expect(openStockCountsOf(1)).toBe(1);
    await data<StockCount>('manager', 'post', '/api/stock-counts/4/cancel', { reason: 'Đóng' });
    expect(openStockCountsOf(1)).toBe(0);
  });
});

describe('state lưu dạng cũ / thiếu bảng', () => {
  it('stockops {} → dùng dữ liệu mẫu cho cả hai bảng và route chính không nổ', async () => {
    localStorage.setItem('petcare-mock-stockops', '{}');
    expect(idsOf((await transfersOf('manager')).content)).toEqual([4, 3, 2, 1]);
    expect(idsOf((await countsOf('manager')).content)).toEqual([2, 1]);
  });

  it('chỉ có transfers → giữ phiếu đã lưu, bù phiên mẫu', async () => {
    localStorage.setItem('petcare-mock-stockops', JSON.stringify({ transfers: [] }));
    expect((await transfersOf('manager')).content).toEqual([]);
    expect(idsOf((await countsOf('manager')).content)).toEqual([2, 1]);
  });

  it('dữ liệu hỏng (không phải JSON) → dùng lại dữ liệu mẫu', async () => {
    localStorage.setItem('petcare-mock-stockops', '{hỏng');
    expect((await transfersOf('manager')).totalElements).toBe(4);
  });

  it('state mock kho dạng rỗng của dry-run seed-dev (không lô, không sổ): tìm lô trả trang rỗng, không nổ', async () => {
    localStorage.setItem('petcare-mock-inventory', JSON.stringify({ seq: 100, suppliers: [], items: [], lots: [], receipts: [], adjustments: [] }));
    expect(await lotsOf('manager', 3)).toEqual([]);
  });

  it('state mock kho lưu từ trước T57 (chưa có sổ lô): truy vết không nổ, mỗi lô còn hàng có một dòng tồn đầu', async () => {
    await data('manager', 'post', '/api/stock-adjustments', { reason: 'DAMAGED', lines: [{ stockLotId: 11, quantityDelta: -1 }] });
    const stored = JSON.parse(localStorage.getItem('petcare-mock-inventory')!);
    delete stored.movements;
    localStorage.setItem('petcare-mock-inventory', JSON.stringify(stored));
    const t = await data<LotTrace>('manager', 'get', '/api/stock-lots/11/trace');
    expect(t.remaining).toBe(11);
    expect(t.events.map((e) => [e.kind, e.quantity])).toEqual([['RECEIPT', 11]]);
    // Thao tác kho tiếp theo ghi sổ bình thường.
    await data('manager', 'post', '/api/stock-adjustments', { reason: 'DAMAGED', lines: [{ stockLotId: 11, quantityDelta: -1 }] });
    expect((await data<LotTrace>('manager', 'get', '/api/stock-lots/11/trace')).events.map((e) => e.quantity)).toEqual([11, -1]);
    expectLedgerBalanced();
  });
});
