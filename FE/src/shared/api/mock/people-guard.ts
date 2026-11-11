// Cửa vào chung của mock workforce (T57 bước A, nhóm PEOPLE). Thứ tự kiểm theo brief (00-method §3.5 + bài học report A1):
//   401 (adapter) → 400 MALFORMED_REQUEST (path / query sai kiểu hoặc thiếu, body thiếu / không phải JSON object / sai kiểu / ngoài enum)
//   → 403 ACCESS_DENIED (vai trò) → 400 VALIDATION_FAILED (Bean Validation, SAU vai trò) → handler (403 phạm vi → 404 → 400 BR-… → 409).
// BE tầng 3 chưa cài nên thứ tự này là theo quy ước chung, chưa đo trên BE thật.
import type { Role } from '../../types/auth';
import { denied, fail, invalid, isText, tooLong, type Check, type MockHandler, type MockReply, type MockRequest } from './envelope';
import { typeError } from './public.mock';

export type Body = Record<string, unknown>;
/** Kiểu của một trường body JSON; mảng chuỗi là enum. */
export type Field = 'text' | 'int' | 'bool' | 'date' | readonly string[];
type QueryKind = 'int' | 'bool' | 'date' | readonly string[];
/** [tên, kiểu, bắt buộc]; kiểm theo đúng thứ tự khai báo như Spring. */
export type QuerySpec = readonly (readonly [name: string, kind: QueryKind, required?: boolean])[];

export const PAGING: QuerySpec = [['page', 'int'], ['size', 'int']];

export const malformed = (message = 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu') => fail('MALFORMED_REQUEST', message, 400);

/** `yyyy-MM-dd` là ngày có thật (LocalDate của BE từ chối 2026-02-30). */
export const isDay = (v: string) => {
  const ms = /^\d{4}-\d{2}-\d{2}$/.test(v) ? Date.parse(`${v}T00:00:00Z`) : NaN;
  return !Number.isNaN(ms) && new Date(ms).toISOString().slice(0, 10) === v;
};

// Spring StringToBooleanConverter.
const BOOLEANS: Record<string, boolean> = { true: true, on: true, yes: true, '1': true, false: false, off: false, no: false, '0': false };
const isBoolean = (v: string) => Object.prototype.hasOwnProperty.call(BOOLEANS, v.toLowerCase());
/** Giá trị boolean của một tham số đã qua `queryError`; vắng → undefined. */
export const flagOf = (v: string | undefined): boolean | undefined => (v === undefined ? undefined : BOOLEANS[v.toLowerCase()]);

function queryError(query: Record<string, string>, spec: QuerySpec): MockReply | null {
  for (const [name, kind, required] of spec) {
    const v = query[name];
    if (v === undefined) {
      if (required) return malformed(`Thiếu tham số '${name}'`);
      continue;
    }
    const good = kind === 'int' ? /^[+-]?\d+$/.test(v) : kind === 'bool' ? isBoolean(v) : kind === 'date' ? isDay(v) : kind.includes(v);
    if (!good) return malformed(`Tham số '${name}' sai kiểu dữ liệu`);
  }
  return null;
}

/** Trường có giá trị nhưng sai kiểu (null và vắng là hợp lệ ở bước này; thiếu bắt buộc là VALIDATION_FAILED, bước sau). */
function wrongType(value: unknown, kind: Field): boolean {
  if (value === undefined || value === null) return false;
  if (kind === 'text') return typeof value !== 'string';
  if (kind === 'int') return !Number.isInteger(value);
  if (kind === 'bool') return typeof value !== 'boolean';
  if (kind === 'date') return typeof value !== 'string' || !isDay(value);
  return typeof value !== 'string' || !kind.includes(value);
}

/** null = body vắng, không phải JSON object hoặc có trường sai kiểu (HttpMessageNotReadableException). */
function bodyOf(req: MockRequest, spec: Record<string, Field>): Body | null {
  const raw = req.body;
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return null;
  const body = raw as Body;
  return Object.entries(spec).some(([name, kind]) => wrongType(body[name], kind)) ? null : body;
}

export interface GuardSpec {
  roles: readonly Role[];
  /** Tham số path kiểu số nguyên. */
  params?: readonly string[];
  query?: QuerySpec;
  /** Có khóa này = request bắt buộc có body JSON object; giá trị là kiểu từng trường. */
  body?: Record<string, Field>;
  /** Bean Validation (@NotBlank, @Size, @Pattern…): chạy sau kiểm vai trò. */
  checks?: (body: Body) => Check[];
}

export function guarded(spec: GuardSpec, handle: (req: MockRequest, body: Body) => MockReply): MockHandler {
  return (req) => {
    const bad = (spec.params && typeError(req.params, [...spec.params])) || (spec.query && queryError(req.query, spec.query));
    if (bad) return bad;
    let body: Body = {};
    if (spec.body) {
      const parsed = bodyOf(req, spec.body);
      if (!parsed) return malformed();
      body = parsed;
    }
    if (!spec.roles.includes(req.account!.role)) return denied();
    const failed = spec.checks && invalid(spec.checks(body));
    return failed || handle(req, body);
  };
}

/** `@NotBlank` + `@Size(max)`. */
export const textChecks = (field: string, label: string, value: unknown, max: number): Check[] => [
  [field, `${label} không được để trống`, !isText(value)],
  [field, `${label} tối đa ${max} ký tự`, tooLong(value, max)],
];
