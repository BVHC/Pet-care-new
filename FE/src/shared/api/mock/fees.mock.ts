// Mock fees-v1 (UC32; docs/api/fees-v1.md #1–6): phí vận chuyển toàn chuỗi, phí khám tại nhà theo chi nhánh, định mức vật tư theo dịch vụ.
// Quyền chép dòng "Quyền:" của yaml: #1, #2, #4, #5, #6 chỉ SUPER_MANAGER; #3 thêm CUSTOMER, RECEPTIONIST và VET của CHÍNH chi nhánh.
// BE tầng 3 CHƯA cài, nên mọi điểm dưới đây là ASSUMPTION của T57 (không có số đo thật):
//  A1  Thứ tự kiểm (Spring): 401 → 400 MALFORMED_REQUEST (path không phải số nguyên; body sai kiểu JSON) → 400 VALIDATION_FAILED của BODY (`@Valid
//      @RequestBody`: thiếu trường) → 403 vai trò → 404 → 400 `BR-SP-…` → ghi. Cùng thứ tự với inventory / care mock đã đo trên BE thật; ràng buộc
//      @Positive của tham số query / path (report-v1 A1) chạy SAU vai trò nhưng fees không có tham số như vậy. Id ≤ 0 trên path là 404, không phải 400.
//  A2  `PUT /shipping-fee-config`: yaml ghi "BR-SP-08 số âm hoặc sai kiểu". Mock: chuỗi / boolean / đối tượng → MALFORMED_REQUEST (Jackson từ chối
//      trước khi vào service); số âm hoặc số thập phân (1000.5) → 400 BR-SP-08; thiếu / null → VALIDATION_FAILED.
//  A3  `homeVisitFeeOf(branchId)` (cho mock đặt lịch khám tại nhà): phí khi chi nhánh BẬT, `null` khi chi nhánh chưa bật hoặc chưa cấu hình (BR-SP-09:
//      chi nhánh chưa bật không nhận đặt lịch). `materialNormsOf(serviceId)`: định mức hiện hành, `[]` khi dịch vụ không có định mức hoặc không tồn tại.
//      `shippingFeeConfig()`: cấu hình hiện hành (một dòng duy nhất). Giá trị đã chốt vào đơn / lịch (BR-SP-11) là việc của mock đặt đơn / đặt lịch.
//  A4  #3: BRANCH_MANAGER / ADMIN / CARETAKER bị 403 ACCESS_DENIED đúng yaml (A02, A04, A06, A07), dù BRANCH_MANAGER có vẻ cũng nên xem. RECEPTIONIST /
//      VET xem chi chi nhánh mình (chi nhánh khác: 403 phạm vi, sau 404 như branch.mock). CUSTOMER và SUPER_MANAGER xem mọi chi nhánh, kể cả DRAFT.
//  A5  #6: nhận dịch vụ của mọi nhóm (kể cả lưu trú — ST07 chỉ chạy với khám / thẩm mỹ); sản phẩm ngừng kinh doanh vẫn nhận. Thứ tự trả về = thứ tự
//      gửi lên (BE xóa rồi chèn lại). `items: []` xóa hết. Thứ tự kiểm BR-SP-10: từng dòng (sản phẩm không có / là vaccine → số lượng < 1) rồi mới tới trùng sản phẩm.
//  A6  Seed: phí vận chuyển 30.000 đ, miễn phí từ 500.000 đ; chi nhánh 1 bật khám tại nhà 100.000 đ, chi nhánh 2 tắt còn lưu phí 80.000 đ, chi nhánh 3 chưa cấu hình;
//      định mức mẫu cho dịch vụ 1 (Khám tổng quát) và 4 (Cắt tỉa lông) bằng sản phẩm GOODS / DRUG của danh mục mẫu.
//  A7  Không mô phỏng audit (BR-SP-11): không mock nào export hàm ghi audit.
import type { Role } from '../../types/auth';
import type { HomeVisitFee, MaterialNorm, ShippingFeeConfig } from '../../types/fees';
import { branchBooking } from './branch.mock';
import { catalogProducts, catalogServices } from './catalog.mock';
import { denied, fail, has, invalid, notFound, ok, ruleFail, scopeDenied, type MockHandler, type MockRequest } from './envelope';
import { branchIdOf } from './identity.mock';
import { typeError } from './public.mock';

const SUPER: readonly Role[] = ['SUPER_MANAGER'];
/** fees-v1 #3 "Quyền:": SUPER_MANAGER, CUSTOMER (xem phí khi đặt), RECEPTIONIST / VET của chính chi nhánh. */
const HOME_VISIT_READ: readonly Role[] = ['SUPER_MANAGER', 'CUSTOMER', 'RECEPTIONIST', 'VET'];
const BRANCH_STAFF: readonly Role[] = ['RECEPTIONIST', 'VET'];
const DAY = 86_400_000;

interface NormRow {
  productId: number;
  quantity: number;
}
interface State {
  shipping: ShippingFeeConfig;
  /** branchId → cấu hình; vắng = chưa cấu hình. */
  homeVisit: Record<number, { enabled: boolean; fee: number }>;
  /** serviceId → định mức, theo thứ tự lưu. */
  norms: Record<number, NormRow[]>;
}

function seed(now = Date.now()): State {
  return {
    shipping: { baseFee: 30_000, freeThreshold: 500_000, updatedAt: new Date(now - 20 * DAY).toISOString() },
    homeVisit: { 1: { enabled: true, fee: 100_000 }, 2: { enabled: false, fee: 80_000 } },
    norms: {
      1: [
        { productId: 3, quantity: 2 },
        { productId: 4, quantity: 1 },
      ],
      4: [{ productId: 2, quantity: 1 }],
    },
  };
}

const STATE_KEY = 'petcare-mock-fees';

/** Gộp giá trị mặc định cho bảng thiếu (state lưu từ bản cũ hơn) rồi ghi lại, nên `updatedAt` mẫu không đổi giữa các lần đọc. */
function load(): State {
  try {
    const raw = localStorage.getItem(STATE_KEY);
    if (raw) {
      const stored = JSON.parse(raw) as Partial<State>;
      const base = seed();
      const merged: State = { shipping: stored.shipping ?? base.shipping, homeVisit: stored.homeVisit ?? base.homeVisit, norms: stored.norms ?? base.norms };
      if (!stored.shipping || !stored.homeVisit || !stored.norms) save(merged);
      return merged;
    }
  } catch {
    // Dữ liệu hỏng: dùng lại dữ liệu mẫu.
  }
  const s = seed();
  save(s);
  return s;
}

function save(s: State) {
  try {
    localStorage.setItem(STATE_KEY, JSON.stringify(s));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}

const normsView = (rows: NormRow[]): MaterialNorm[] =>
  rows.flatMap(({ productId, quantity }) => {
    const p = catalogProducts().find((x) => x.productId === productId);
    return p ? [{ productId, productName: p.name, unit: p.unit, quantity }] : [];
  });

// ─── Cho mock khác đọc chéo (CatalogQueryApi.findMaterialNorms, nguồn phí vận chuyển / khám tại nhà): chỉ đọc ───

/** Cấu hình phí vận chuyển hiện hành (BR-SP-08), cho mock đơn online (ST09). */
export const shippingFeeConfig = (): ShippingFeeConfig => load().shipping;

/** Phí khám tại nhà của chi nhánh khi đang BẬT; `null` khi chưa bật hoặc chưa cấu hình (BR-SP-09, không nhận đặt lịch khám tại nhà). */
export function homeVisitFeeOf(branchId: number): number | null {
  const row = load().homeVisit[branchId];
  return row?.enabled ? row.fee : null;
}

/** Định mức vật tư hiện hành của dịch vụ (BR-SP-10), cho ST07; `[]` khi dịch vụ không có định mức hoặc không tồn tại. */
export const materialNormsOf = (serviceId: number): MaterialNorm[] => normsView(load().norms[serviceId] ?? []);

// ─── Đọc dữ liệu vào ───

const malformed = () => fail('MALFORMED_REQUEST', 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu', 400);
const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const role = (req: MockRequest) => req.account!.role;

// ─── Phí vận chuyển (#1, #2) ───

const getShippingFeeConfig: MockHandler = (req) => (SUPER.includes(role(req)) ? ok(load().shipping) : denied());

const saveShippingFeeConfig: MockHandler = (req) => {
  if (!isObject(req.body)) return malformed();
  const { baseFee, freeThreshold } = req.body;
  if ((has(baseFee) && typeof baseFee !== 'number') || (has(freeThreshold) && typeof freeThreshold !== 'number')) return malformed();
  const bad = invalid([
    ['baseFee', 'Vui lòng nhập phí cơ bản', !has(baseFee)],
    ['freeThreshold', 'Vui lòng nhập ngưỡng miễn phí', !has(freeThreshold)],
  ]);
  if (bad) return bad;
  if (!SUPER.includes(role(req))) return denied();
  const notAmount = (v: unknown) => !Number.isInteger(v) || (v as number) < 0;
  if (notAmount(baseFee)) return ruleFail('BR-SP-08', 'Phí cơ bản phải là số nguyên từ 0 trở lên');
  if (notAmount(freeThreshold)) return ruleFail('BR-SP-08', 'Ngưỡng miễn phí phải là số nguyên từ 0 trở lên (0 = không miễn phí)');
  const s = load();
  s.shipping = { baseFee: baseFee as number, freeThreshold: freeThreshold as number, updatedAt: new Date().toISOString() };
  save(s);
  return ok(s.shipping, 200, 'Đã lưu cấu hình phí vận chuyển');
};

// ─── Phí khám tại nhà (#3, #4) ───

const getHomeVisitFee: MockHandler = (req) => {
  const badId = typeError(req.params, ['branchId']);
  if (badId) return badId;
  if (!HOME_VISIT_READ.includes(role(req))) return denied();
  const branchId = Number(req.params.branchId);
  if (!branchBooking(branchId)) return notFound('chi nhánh', branchId);
  if (BRANCH_STAFF.includes(role(req)) && branchIdOf(req.account!.id) !== branchId) return scopeDenied();
  const row = load().homeVisit[branchId];
  const fee: HomeVisitFee = { branchId, enabled: row?.enabled ?? false, fee: row?.fee ?? 0 };
  return ok(fee);
};

const saveHomeVisitFee: MockHandler = (req) => {
  const badId = typeError(req.params, ['branchId']);
  if (badId) return badId;
  if (!isObject(req.body)) return malformed();
  const { enabled, fee } = req.body;
  if ((has(enabled) && typeof enabled !== 'boolean') || (has(fee) && typeof fee !== 'number')) return malformed();
  const bad = invalid([
    ['enabled', 'Vui lòng chọn bật hoặc tắt khám tại nhà', !has(enabled)],
    ['fee', 'Vui lòng nhập phí khám tại nhà', !has(fee)],
  ]);
  if (bad) return bad;
  if (!SUPER.includes(role(req))) return denied();
  const branchId = Number(req.params.branchId);
  if (!branchBooking(branchId)) return notFound('chi nhánh', branchId);
  if (!Number.isInteger(fee) || (fee as number) < 0) return ruleFail('BR-SP-09', 'Phí khám tại nhà phải là số nguyên từ 0 trở lên');
  const s = load();
  s.homeVisit[branchId] = { enabled: enabled as boolean, fee: fee as number };
  save(s);
  return ok({ branchId, ...s.homeVisit[branchId] } satisfies HomeVisitFee, 200, 'Đã lưu phí khám tại nhà');
};

// ─── Định mức vật tư (#5, #6) ───

const serviceExists = (serviceId: number) => catalogServices().some((x) => x.serviceId === serviceId);

const listMaterialNorms: MockHandler = (req) => {
  const badId = typeError(req.params, ['serviceId']);
  if (badId) return badId;
  if (!SUPER.includes(role(req))) return denied();
  const serviceId = Number(req.params.serviceId);
  return serviceExists(serviceId) ? ok(normsView(load().norms[serviceId] ?? [])) : notFound('dịch vụ', serviceId);
};

const saveMaterialNorms: MockHandler = (req) => {
  const badId = typeError(req.params, ['serviceId']);
  if (badId) return badId;
  if (!isObject(req.body)) return malformed();
  const { items } = req.body;
  if (has(items) && (!Array.isArray(items) || items.some((x) => !isObject(x) || (has(x.productId) && typeof x.productId !== 'number') || (has(x.quantity) && typeof x.quantity !== 'number')))) {
    return malformed();
  }
  const rows = (Array.isArray(items) ? items : []) as Record<string, unknown>[];
  const bad = invalid([
    ['items', 'Vui lòng gửi danh sách định mức (mảng rỗng để xóa hết)', !has(items)],
    ...rows.flatMap((x, i): [string, string, boolean][] => [
      [`items[${i}].productId`, 'Vui lòng chọn sản phẩm', !has(x.productId)],
      [`items[${i}].quantity`, 'Vui lòng nhập số lượng', !has(x.quantity)],
    ]),
  ]);
  if (bad) return bad;
  if (!SUPER.includes(role(req))) return denied();
  const serviceId = Number(req.params.serviceId);
  if (!serviceExists(serviceId)) return notFound('dịch vụ', serviceId);

  const products = catalogProducts();
  const seen = new Set<number>();
  for (const [i, x] of rows.entries()) {
    const p = products.find((y) => y.productId === x.productId);
    if (!p) return ruleFail('BR-SP-10', `Dòng ${i + 1}: sản phẩm #${x.productId} không tồn tại`);
    if (p.productType === 'VACCINE') return ruleFail('BR-SP-10', `Dòng ${i + 1}: ${p.name} là vaccine, không đưa vào định mức (vaccine đã trừ kho khi tiêm)`);
    if (!Number.isInteger(x.quantity) || (x.quantity as number) < 1) return ruleFail('BR-SP-10', `Dòng ${i + 1}: số lượng ${p.name} phải là số nguyên từ 1 trở lên`);
  }
  for (const [i, x] of rows.entries()) {
    if (seen.has(x.productId as number)) return ruleFail('BR-SP-10', `Dòng ${i + 1}: sản phẩm ${products.find((y) => y.productId === x.productId)!.name} bị trùng trong định mức`);
    seen.add(x.productId as number);
  }
  const s = load();
  s.norms[serviceId] = rows.map((x) => ({ productId: x.productId as number, quantity: x.quantity as number }));
  save(s);
  return ok(normsView(s.norms[serviceId]), 200, 'Đã lưu định mức vật tư');
};

export const feesRoutes: Record<string, MockHandler> = {
  'GET /api/shipping-fee-config': getShippingFeeConfig,
  'PUT /api/shipping-fee-config': saveShippingFeeConfig,
  'GET /api/branches/:branchId/home-visit-fee': getHomeVisitFee,
  'PUT /api/branches/:branchId/home-visit-fee': saveHomeVisitFee,
  'GET /api/services/:serviceId/material-norms': listMaterialNorms,
  'PUT /api/services/:serviceId/material-norms': saveMaterialNorms,
};
