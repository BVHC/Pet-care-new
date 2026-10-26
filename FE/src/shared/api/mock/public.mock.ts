// Mock phần công khai của catalog và identity (T46, UC15, UC16): danh mục sản phẩm, sản phẩm (+ chi tiết), đội ngũ bác sĩ
// (catalog-v1 #20–22, identity-v1 #37). Chi nhánh và dịch vụ công khai ở branch.mock.ts; bài viết ở content.mock.ts.
// Không có state riêng: mọi thứ đọc chéo từ mock catalog / kho / chi nhánh / identity như BE đọc qua QueryApi, nên đổi dữ
// liệu ở màn nhân viên (ngừng kinh doanh, tồn kho, khóa tài khoản…) hiện ngay ở trang công khai. Luật và câu báo lỗi chép từ
// BE (PublicCatalogService, PublicVetService). Path công khai: token gửi kèm bị bỏ qua (ADR-0005).
//
// Các điểm hợp đồng im lặng (ASSUMPTION):
//  - Vaccine vẫn hiện ở danh sách (BR-CK-01 chỉ loại thuốc kê đơn, BE chưa loại vaccine).
//  - Tìm `q` theo tên, không phân biệt hoa thường nhưng có phân biệt dấu (như BE `lower(name) LIKE`); không tìm theo SKU.
//  - Khóa tạm do đăng nhập sai (locked_until) không có trong mock identity nên VET không bao giờ bị ẩn vì nó; VET bị khóa
//    (isLocked) hoặc không ACTIVE thì ẩn, đúng BE.
import type { PublicBranch } from '../../types/branch';
import type { Product } from '../../types/catalog';
import type { PublicProduct, PublicVet } from '../../types/public-site';
import { availableQuantity } from './inventory.mock';
import { branchRoutes } from './branch.mock';
import { catalogCategories, catalogProducts } from './catalog.mock';
import { fail, notFound, ok, type MockHandler, type MockReply } from './envelope';
import { assignableStaffOf } from './identity-admin.mock';
import { STAFF_SEED, load as loadIdentity } from './identity.mock';

// ─── Tham số vào, dùng chung với content.mock.ts ───

const INT = /^-?\d+$/;

/** Sai kiểu dữ liệu (Spring: MethodArgumentTypeMismatchException) đến trước lỗi ràng buộc. */
export function typeError(values: Record<string, string>, names: string[]): MockReply | null {
  const bad = names.find((n) => values[n] !== undefined && !INT.test(values[n]));
  return bad ? fail('MALFORMED_REQUEST', `Tham số '${bad}' sai kiểu dữ liệu`, 400) : null;
}

/** `@Positive` (HandlerMethodValidationException): ≤ 0 là 400 VALIDATION_FAILED. */
export function notPositive(values: Record<string, string>, names: string[]): MockReply | null {
  const bad = names.find((n) => values[n] !== undefined && Number(values[n]) <= 0);
  return bad ? fail('VALIDATION_FAILED', `${bad}: ${bad} phải là số dương`, 400) : null;
}

const MAX_PAGE_SIZE = 100;

/** Như BE: `page` ≥ 0, `size` trong 1..100 (mặc định 20); ngoài khoảng thì kẹp lại, không báo lỗi. Gọi sau `typeError`. */
export function clampPaging(query: Record<string, string>): { page: number; size: number } {
  return {
    page: Math.max(Number(query.page ?? 0), 0),
    size: Math.min(Math.max(Number(query.size ?? 20), 1), MAX_PAGE_SIZE),
  };
}

/** Trang như `PageResponse` của BE; trang rỗng có `totalPages: 0`. */
export function pageOf<T>(items: T[], { page, size }: { page: number; size: number }) {
  return {
    content: items.slice(page * size, page * size + size),
    page,
    size,
    totalElements: items.length,
    totalPages: Math.ceil(items.length / size),
  };
}

const byName = (a: { name: string }, b: { name: string }) => a.name.localeCompare(b.name, 'vi');

/** `BranchQueryApi.listActiveBranches`: đi qua route công khai của branch.mock nên cùng một định nghĩa "chi nhánh ACTIVE". */
function activeBranches(): PublicBranch[] {
  const reply = branchRoutes['GET /api/public/branches']({ body: null, account: null, params: {}, query: {} });
  return (reply.body as { data: PublicBranch[] }).data;
}

// ─── Danh mục sản phẩm và sản phẩm (catalog-v1 #20–22) ───

const listPublicProductCategories: MockHandler = () =>
  ok(
    catalogCategories()
      .filter((c) => c.isActive)
      .sort(byName)
      .map(({ categoryId, name, isActive }) => ({ categoryId, name, isActive })),
  );

/** Đang kinh doanh, không phải thuốc kê đơn, thuộc danh mục đang dùng (BR-CK-01, BR-SP-01; giả định A1 của T32). */
function visibleProducts(): { product: Product; categoryName: string }[] {
  const categories = new Map(catalogCategories().filter((c) => c.isActive).map((c) => [c.categoryId, c.name]));
  return catalogProducts()
    .filter((p) => p.isActive && !p.isPrescription && categories.has(p.categoryId))
    .map((product) => ({ product, categoryName: categories.get(product.categoryId)! }));
}

/** Chín trường của hợp đồng, dựng tường minh (không trải `Product` để khỏi lộ SKU, cờ kê đơn…). Không bao giờ có số lượng (BR-CK-03). */
function toPublicProduct({ product: p, categoryName }: { product: Product; categoryName: string }, branches: PublicBranch[]): PublicProduct {
  return {
    productId: p.productId,
    name: p.name,
    categoryId: p.categoryId,
    categoryName,
    price: p.price,
    unit: p.unit,
    description: p.description ?? null,
    imageUrl: p.imageUrl ?? null,
    inStockBranchIds: branches.filter((b) => availableQuantity(b.branchId, p.productId) > 0).map((b) => b.branchId),
  };
}

const listPublicProducts: MockHandler = ({ query }) => {
  const bad =
    typeError(query, ['categoryId', 'branchId', 'page', 'size']) ??
    notPositive(query, ['categoryId', 'branchId']) ??
    (query.q !== undefined && query.q.length > 100 ? fail('VALIDATION_FAILED', 'q: Từ khóa tối đa 100 ký tự', 400) : null);
  if (bad) return bad;
  const q = query.q?.trim().toLowerCase();
  const branches = activeBranches();
  const paging = clampPaging(query);
  // Chi nhánh không ACTIVE (DRAFT, không có) thì vô hình: trang rỗng, không 404.
  const here = query.branchId === undefined ? undefined : branches.find((b) => b.branchId === Number(query.branchId));
  if (query.branchId !== undefined && !here) return ok(pageOf([], paging));
  const found = visibleProducts()
    .filter(
      ({ product }) =>
        (!q || product.name.toLowerCase().includes(q)) &&
        (query.categoryId === undefined || product.categoryId === Number(query.categoryId)) &&
        (!here || availableQuantity(here.branchId, product.productId) > 0),
    )
    .sort((a, b) => byName(a.product, b.product) || a.product.productId - b.product.productId);
  return ok(pageOf(found.map((p) => toPublicProduct(p, branches)), paging));
};

const getPublicProduct: MockHandler = ({ params }) => {
  const bad = typeError(params, ['productId']) ?? notPositive(params, ['productId']);
  if (bad) return bad;
  // Thuốc kê đơn, ngừng kinh doanh, danh mục ngừng và id lạ cùng một 404.
  const found = visibleProducts().find(({ product }) => product.productId === Number(params.productId));
  return found ? ok(toPublicProduct(found, activeBranches())) : notFound('sản phẩm', params.productId);
};

// ─── Đội ngũ bác sĩ (identity-v1 #37; BR-TK-20, BR-CK-01) ───

/**
 * VET ACTIVE không bị khóa (`assignableStaffOf` = StaffDirectoryApi) ở chi nhánh ACTIVE; họ tên, ảnh, chuyên môn, mô tả lấy từ
 * hồ sơ nhân viên (seed cộng các lần tự sửa). Sắp theo họ tên rồi id. `branchId` không ACTIVE thì mảng rỗng.
 */
const listPublicVets: MockHandler = ({ query }) => {
  const bad = typeError(query, ['branchId']) ?? notPositive(query, ['branchId']);
  if (bad) return bad;
  const profiles = loadIdentity().profiles;
  const vets: PublicVet[] = activeBranches()
    .filter((b) => query.branchId === undefined || b.branchId === Number(query.branchId))
    .flatMap((branch) =>
      assignableStaffOf(branch.branchId, 'VET').map((vet) => {
        const profile = { ...STAFF_SEED[vet.accountId], ...profiles[vet.accountId] };
        return {
          accountId: vet.accountId,
          fullName: profile.fullName ?? vet.fullName,
          avatarUrl: profile.avatarUrl ?? null,
          specialty: profile.specialty ?? null,
          bio: profile.bio ?? null,
          branchId: branch.branchId,
          branchName: branch.name,
        };
      }),
    );
  return ok(vets.sort((a, b) => a.fullName.localeCompare(b.fullName, 'vi') || a.accountId - b.accountId));
};

export const publicRoutes: Record<string, MockHandler> = {
  'GET /api/public/product-categories': listPublicProductCategories,
  'GET /api/public/products': listPublicProducts,
  'GET /api/public/products/:productId': getPublicProduct,
  'GET /api/public/vets': listPublicVets,
};
