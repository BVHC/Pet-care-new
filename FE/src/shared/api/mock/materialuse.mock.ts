// Mock materialuse-v1 (ST07; docs/api/materialuse-v1.md #1–3): vật tư tiêu hao đã trừ theo định mức khi hoàn tất lượt, và thiếu hụt chưa xử lý.
// ST07 là tác vụ hệ thống (không endpoint): `recordMaterialUsage` bên dưới là chỗ để mock lâm sàng gọi khi hoàn tất lượt (luồng chính nối, KHÔNG sửa visit mock ở bước này).
// Quyền chép dòng "Quyền:" của yaml: #1 BRANCH_MANAGER của chi nhánh, VET phụ trách lượt; #2, #3 BRANCH_MANAGER. Chi nhánh người gọi = `branchIdOf(account.id)`.
// BE tầng 3 CHƯA cài, nên mọi điểm dưới đây là ASSUMPTION của T57 (không có số đo thật):
//  A1  Thứ tự kiểm (Spring): 401 → 400 MALFORMED_REQUEST (path / query sai kiểu; body sai kiểu JSON) → 400 VALIDATION_FAILED của BODY (`@Valid @RequestBody`, trước vai trò như
//      inventory / care mock đã đo trên BE thật) → 403 vai trò → 404 → 403 phạm vi (message chung) → 400 `BR-KB-13` → 409. #3: body → vai trò → 404 → phạm vi → 400 → 409.
//  A2  #1: lượt do `visitRowOf` của mock lượt khám; BRANCH_MANAGER phải cùng chi nhánh với lượt, VET phải là người được giao lượt (`assigneeId`), ngược lại 403 phạm vi (BR-QT-03).
//      Lượt không có trong mock lượt khám → 404. Mảng sắp theo `usageId` tăng dần; rỗng khi lượt chưa hoàn tất hoặc dịch vụ không có định mức vì khi đó chưa có dòng nào.
//  A3  Dòng vật tư chỉ sinh ra từ dữ liệu mẫu và `recordMaterialUsage` (mock lượt khám CHƯA gọi hàm này nên hoàn tất lượt trong mock không tạo dòng). Trừ kho dùng `issueMaterials` của
//      mock kho (xuất FEFO phần có, thiếu thì ghi `shortageQty`, không chặn); cộng dồn mọi dịch vụ của lượt theo sản phẩm rồi chia phần xuất cho các dòng theo thứ tự dịch vụ. Mỗi
//      (lượt, dịch vụ, sản phẩm) một dòng, gọi lại không trừ lần hai (BR-KB-14). Thiếu hụt → thông báo `MATERIAL_SHORTAGE` cho BRANCH_MANAGER của chi nhánh (quyết định T54 Q6), không có linkUrl.
//  A4  #2: `resolved` vắng = false; chỉ dòng `shortageQty > 0`; cũ nhất trước (`createdAt` rồi `usageId`); `resolved=true` liệt kê các thiếu hụt đã xử lý. Giá trị khác true / false → 400 MALFORMED_REQUEST.
//  A5  #3: `note` bắt buộc, không toàn khoảng trắng, ≤ 300 ký tự (yaml chỉ ghi maxLength) → 400 VALIDATION_FAILED. Dòng không có thiếu hụt → 400 `BR-KB-13` (yaml không ghi mã rule). Xử lý lần hai → 409
//      INVALID_STATE_TRANSITION theo yaml, dù ma trận md ghi "Idempotent: Có" (dòng đã xử lý không đổi: giữ `resolvedAt`, `resolvedNote` đầu tiên). Chỉ ghi nhận, không điều chỉnh tồn.
//  A6  Dữ liệu mẫu: lượt 61, 58, 40 (đã hoàn tất, bác sĩ #5) của chi nhánh 1 có dòng vật tư của dịch vụ 1; lượt 58 thiếu 2 Amoxicillin chưa xử lý, lượt 40 thiếu 1 đã xử lý. Chi nhánh 2 có một thiếu
//      hụt ở lượt 7001, lượt này KHÔNG có trong mock lượt khám nên #1 của nó trả 404 (chỉ để quản lý chi nhánh 2 có dòng để thử #2, #3).
//  A7  Không mô phỏng audit và transaction chung với hoàn tất lượt.
import type { Role } from '../../types/auth';
import type { MaterialUsage } from '../../types/materialuse';
import { MATERIAL_USE_LIMITS } from '../../types/materialuse';
import { catalogProducts, catalogServices } from './catalog.mock';
import { pushNotification } from './care.mock';
import { denied, fail, has, invalid, isText, notFound, ok, ruleFail, scopeDenied, tooLong, type Check, type MockHandler, type MockRequest } from './envelope';
import { materialNormsOf } from './fees.mock';
import { MOCK_ACCOUNTS, branchIdOf } from './identity.mock';
import { issueMaterials } from './inventory.mock';
import { clampPaging, pageOf, typeError } from './public.mock';
import { visitRowOf } from './visit.mock';

const MANAGER: readonly Role[] = ['BRANCH_MANAGER'];
const VISIT_READERS: readonly Role[] = ['BRANCH_MANAGER', 'VET'];
const DAY = 86_400_000;

interface UsageRow {
  usageId: number;
  visitId: number;
  serviceId: number;
  productId: number;
  branchId: number;
  normQty: number;
  issuedQty: number;
  shortageQty: number;
  createdAt: string;
  resolvedBy: number | null;
  resolvedAt: string | null;
  resolvedNote: string | null;
}
interface State {
  usages: UsageRow[];
}

function seed(now = Date.now()): State {
  const ago = (days: number) => new Date(now - days * DAY).toISOString();
  const row = (r: Pick<UsageRow, 'usageId' | 'visitId' | 'productId' | 'normQty' | 'issuedQty' | 'createdAt'> & Partial<UsageRow>): UsageRow => ({
    serviceId: 1,
    branchId: 1,
    shortageQty: r.normQty - r.issuedQty,
    resolvedBy: null,
    resolvedAt: null,
    resolvedNote: null,
    ...r,
  });
  return {
    usages: [
      row({ usageId: 1, visitId: 61, productId: 3, normQty: 2, issuedQty: 2, createdAt: ago(3) }),
      row({ usageId: 2, visitId: 61, productId: 4, normQty: 1, issuedQty: 1, createdAt: ago(3) }),
      row({ usageId: 3, visitId: 58, productId: 3, normQty: 2, issuedQty: 0, createdAt: ago(2) }),
      row({ usageId: 4, visitId: 58, productId: 4, normQty: 1, issuedQty: 1, createdAt: ago(2) }),
      row({ usageId: 5, visitId: 40, productId: 3, normQty: 2, issuedQty: 1, createdAt: ago(5), resolvedBy: 3, resolvedAt: ago(4), resolvedNote: 'Đã nhập thêm Amoxicillin về kho' }),
      row({ usageId: 6, visitId: 7001, productId: 2, normQty: 1, issuedQty: 0, createdAt: ago(1), branchId: 2 }),
    ],
  };
}

const STATE_KEY = 'petcare-mock-materialuse';

/** Gộp giá trị mặc định cho bảng thiếu (state lưu từ bản cũ hơn) rồi ghi lại ngay: mốc thời gian mẫu tính theo lúc tạo nên đọc lại phải ra cùng giá trị. */
function load(): State {
  try {
    const raw = localStorage.getItem(STATE_KEY);
    if (raw) {
      const stored = JSON.parse(raw) as Partial<State>;
      if (stored.usages) return stored as State;
      const merged: State = { usages: seed().usages };
      save(merged);
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

// ─── Đọc dữ liệu vào ───

const malformed = () => fail('MALFORMED_REQUEST', 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu', 400);
const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const role = (req: MockRequest) => req.account!.role;
const myBranch = (req: MockRequest) => branchIdOf(req.account!.id) ?? null;

function view(u: UsageRow): MaterialUsage {
  const product = catalogProducts().find((p) => p.productId === u.productId);
  return {
    usageId: u.usageId,
    visitId: u.visitId,
    serviceId: u.serviceId,
    serviceName: catalogServices().find((x) => x.serviceId === u.serviceId)?.name ?? `Dịch vụ #${u.serviceId}`,
    productId: u.productId,
    productName: product?.name ?? `Sản phẩm #${u.productId}`,
    unit: product?.unit ?? '',
    normQty: u.normQty,
    issuedQty: u.issuedQty,
    shortageQty: u.shortageQty,
    resolvedAt: u.resolvedAt,
    resolvedNote: u.resolvedNote,
  };
}
const oldestFirst = (a: UsageRow, b: UsageRow) => Date.parse(a.createdAt) - Date.parse(b.createdAt) || a.usageId - b.usageId;

// ─── ST07 cho mock khác gọi ───

/**
 * ST07 (BR-KB-12…14): khi một lượt hoàn tất, đọc định mức hiện hành của từng dịch vụ (fees.mock `materialNormsOf`), xuất kho FEFO tại chi nhánh phần có và ghi thiếu hụt
 * phần thiếu, KHÔNG chặn. Mỗi (lượt, dịch vụ, sản phẩm) chỉ một dòng: dòng đã có thì bỏ qua. Trả các dòng MỚI tạo.
 */
export function recordMaterialUsage(args: { visitId: number; branchId: number; serviceIds: readonly number[]; documentCode?: string | null }): MaterialUsage[] {
  const s = load();
  const fresh: UsageRow[] = [];
  const createdAt = new Date().toISOString();
  for (const serviceId of new Set(args.serviceIds)) {
    for (const norm of materialNormsOf(serviceId)) {
      if (s.usages.some((u) => u.visitId === args.visitId && u.serviceId === serviceId && u.productId === norm.productId)) continue;
      fresh.push({
        usageId: Math.max(0, ...s.usages.map((u) => u.usageId), ...fresh.map((u) => u.usageId)) + 1,
        visitId: args.visitId,
        serviceId,
        productId: norm.productId,
        branchId: args.branchId,
        normQty: norm.quantity,
        issuedQty: 0,
        shortageQty: norm.quantity,
        createdAt,
        resolvedBy: null,
        resolvedAt: null,
        resolvedNote: null,
      });
    }
  }
  const products = [...new Set(fresh.map((u) => u.productId))];
  const issues = issueMaterials(
    args.branchId,
    products.map((productId) => ({ productId, quantity: fresh.filter((u) => u.productId === productId).reduce((sum, u) => sum + u.normQty, 0) })),
    { documentCode: args.documentCode ?? visitRowOf(args.visitId)?.code ?? null },
  );
  for (const { productId, issued } of issues) {
    let left = issued;
    for (const u of fresh.filter((x) => x.productId === productId)) {
      u.issuedQty = Math.min(u.normQty, left);
      u.shortageQty = u.normQty - u.issuedQty;
      left -= u.issuedQty;
    }
  }
  s.usages.push(...fresh);
  save(s);

  const short = fresh.filter((u) => u.shortageQty > 0);
  if (short.length) {
    const lines = short.map((u) => `${view(u).productName}: thiếu ${u.shortageQty} ${view(u).unit}`.trim()).join('; ');
    for (const manager of MOCK_ACCOUNTS.filter((a) => a.role === 'BRANCH_MANAGER' && a.branchId === args.branchId)) {
      pushNotification(manager.id, {
        type: 'MATERIAL_SHORTAGE',
        title: 'Thiếu vật tư tiêu hao khi hoàn tất lượt',
        body: `Lượt khám #${args.visitId} đã hoàn tất nhưng kho không đủ vật tư theo định mức: ${lines}. Hãy điều chỉnh tồn hoặc nhập thêm rồi đánh dấu đã xử lý.`,
        linkUrl: null,
      });
    }
  }
  return fresh.map(view);
}

// ─── Endpoint ───

const listVisitUsages: MockHandler = (req) => {
  const bad = typeError(req.params, ['visitId']);
  if (bad) return bad;
  if (!VISIT_READERS.includes(role(req))) return denied();
  const visit = visitRowOf(Number(req.params.visitId));
  if (!visit) return notFound('lượt', req.params.visitId);
  const allowed = visit.branchId === myBranch(req) && (role(req) === 'BRANCH_MANAGER' || visit.assigneeId === req.account!.id);
  if (!allowed) return scopeDenied();
  return ok(
    load()
      .usages.filter((u) => u.visitId === visit.visitId)
      .sort((a, b) => a.usageId - b.usageId)
      .map(view),
  );
};

const listShortages: MockHandler = (req) => {
  const bad = typeError(req.query, ['page', 'size']);
  if (bad) return bad;
  const { resolved } = req.query;
  if (resolved !== undefined && resolved !== 'true' && resolved !== 'false') return fail('MALFORMED_REQUEST', "Tham số 'resolved' sai kiểu dữ liệu", 400);
  if (!MANAGER.includes(role(req))) return denied();
  const found = load()
    .usages.filter((u) => u.branchId === myBranch(req) && u.shortageQty > 0 && (u.resolvedAt !== null) === (resolved === 'true'))
    .sort(oldestFirst);
  return ok(pageOf(found.map(view), clampPaging(req.query)));
};

const resolveShortage: MockHandler = (req) => {
  const badId = typeError(req.params, ['usageId']);
  if (badId) return badId;
  if (!isObject(req.body)) return malformed();
  const { note } = req.body;
  if (has(note) && typeof note !== 'string') return malformed();
  const checks: Check[] = [
    ['note', 'Vui lòng nhập ghi chú đã xử lý', !isText(note)],
    ['note', `Ghi chú tối đa ${MATERIAL_USE_LIMITS.note} ký tự`, tooLong(note, MATERIAL_USE_LIMITS.note)],
  ];
  const bad = invalid(checks);
  if (bad) return bad;
  if (!MANAGER.includes(role(req))) return denied();

  const s = load();
  const row = s.usages.find((u) => u.usageId === Number(req.params.usageId));
  if (!row) return notFound('dòng vật tư', req.params.usageId);
  if (row.branchId !== myBranch(req)) return scopeDenied();
  if (row.shortageQty <= 0) return ruleFail('BR-KB-13', 'Dòng vật tư này không có thiếu hụt để xử lý');
  if (row.resolvedAt !== null) return fail('INVALID_STATE_TRANSITION', 'Thiếu hụt vật tư này đã được xử lý', 409);
  Object.assign(row, { resolvedBy: req.account!.id, resolvedAt: new Date().toISOString(), resolvedNote: (note as string).trim() });
  save(s);
  return ok(view(row), 200, 'Đã đánh dấu đã xử lý thiếu hụt');
};

export const materialUseRoutes: Record<string, MockHandler> = {
  'GET /api/visits/:visitId/material-usages': listVisitUsages,
  'GET /api/material-shortages': listShortages,
  'POST /api/material-shortages/:usageId/resolve': resolveShortage,
};
