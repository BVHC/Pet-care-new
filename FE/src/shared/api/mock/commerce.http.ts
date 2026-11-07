// Phần HTTP dùng chung của ba mock thương mại tầng 3 (shop, payment, returns): đọc tham số / body, vai trò, phân trang, lỗi chung.
// Thứ tự kiểm (xem khối ASSUMPTION ở đầu từng mock): 401 (adapter) → 400 path / query / body sai kiểu (MALFORMED_REQUEST) → 400 VALIDATION_FAILED của body
// (@Valid chạy TRƯỚC @PreAuthorize, như care.mock / cashier.mock đã đo trên BE thật) → 403 vai trò → 404 → 403 phạm vi → 400 BR-… → 409.
import type { Role } from '../../types/auth';
import { fail, ok, type MockReply, type MockRequest } from './envelope';
import { branchIdOf } from './identity.mock';
import { clampPaging, pageOf, typeError } from './public.mock';

export const malformed = () => fail('MALFORMED_REQUEST', 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu', 400);
export const badParam = (name: string) => fail('MALFORMED_REQUEST', `Tham số '${name}' sai kiểu dữ liệu`, 400);
export const missingParam = (name: string) => fail('MALFORMED_REQUEST', `Thiếu tham số '${name}'`, 400);
export const stateError = (what: string, from: string, to: string) =>
  fail('INVALID_STATE_TRANSITION', `Không thể chuyển trạng thái ${what} từ ${from} sang ${to}`, 409);

export const isReply = (x: unknown): x is MockReply => typeof x === 'object' && x !== null && 'status' in x && typeof (x as MockReply).status === 'number';
export const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

/** Body là object JSON (hoặc rỗng khi endpoint không có body); khác thế là 400 MALFORMED_REQUEST. */
export const bodyOf = (req: MockRequest): Record<string, unknown> | null => (isObject(req.body) ? req.body : null);

/** Nhân viên / khách đang gọi. */
export const me = (req: MockRequest) => req.account!;
/** Chi nhánh của nhân viên A05–A08 (identity.mock `branchIdOf`, không đọc `account.branchId`). */
export const myBranch = (req: MockRequest) => branchIdOf(me(req).id);

/** Vai trò có nằm trong danh sách được phép (dòng "Quyền:" của yaml)? Gọi SAU các kiểm tra path / query / body. */
export const hasRole = (req: MockRequest, roles: readonly Role[]) => roles.includes(me(req).role);

/** Tham số path số nguyên: sai kiểu → 400 trước cả vai trò (MethodArgumentTypeMismatchException). */
export const pathIds = (req: MockRequest, names: string[]): MockReply | null => typeError(req.params, names);

/** Query enum: giá trị lạ → 400 MALFORMED_REQUEST (Spring không ép được thành enum). */
export function enumQuery<T extends string>(req: MockRequest, name: string, allowed: readonly T[]): T | undefined | MockReply {
  const v = req.query[name];
  if (v === undefined) return undefined;
  return allowed.includes(v as T) ? (v as T) : badParam(name);
}

/** Query boolean (`true` / `false`). */
export function boolQuery(req: MockRequest, name: string): boolean | undefined | MockReply {
  const v = req.query[name];
  if (v === undefined) return undefined;
  return v === 'true' ? true : v === 'false' ? false : badParam(name);
}

/** `page` / `size`: sai kiểu → 400; ngoài khoảng thì kẹp (page ≥ 0, size 1–100, mặc định 20), không báo lỗi. */
export function paging(req: MockRequest): MockReply | { page: number; size: number } {
  return typeError(req.query, ['page', 'size']) ?? clampPaging(req.query);
}

export const pageReply = <T>(items: T[], p: { page: number; size: number }) => ok(pageOf(items, p));

/** Mới nhất trước (`createdAt` giảm dần, trùng thì id giảm dần). */
export const newestFirst = <T extends { createdAt: string }>(id: (x: T) => number) => (a: T, b: T) => Date.parse(b.createdAt) - Date.parse(a.createdAt) || id(b) - id(a);
/** Cũ nhất trước (`createdAt` tăng dần, trùng thì id tăng dần). */
export const oldestFirst = <T extends { createdAt: string }>(id: (x: T) => number) => (a: T, b: T) => Date.parse(a.createdAt) - Date.parse(b.createdAt) || id(a) - id(b);
