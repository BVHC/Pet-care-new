// Cửa vào chung của mock workforce (T57 bước A, nhóm PEOPLE). Thứ tự kiểm theo brief (00-method §3.5 + bài học report A1):
//   401 (adapter) → 400 MALFORMED_REQUEST (path / query sai kiểu hoặc thiếu, body thiếu / không phải JSON object / sai kiểu / ngoài enum)
//   → 403 ACCESS_DENIED (vai trò) → 400 VALIDATION_FAILED (Bean Validation, SAU vai trò) → handler (403 phạm vi → 404 → 400 BR-… → 409).
// Riêng workforce đã ĐO trên BE thật (T66, 23/11/2026): VALIDATION_FAILED đến TRƯỚC vai trò (`validateFirst`), vì @Valid chạy khi nạp tham số còn @PreAuthorize chạy sau.
import type { Role } from '../../types/auth';
import { denied, fail, invalid, isText, tooLong, type Check, type MockHandler, type MockReply, type MockRequest } from './envelope';
import { typeError } from './public.mock';

export type Body = Record<string, unknown>;
/** Kiểu của một trường body JSON; mảng chuỗi là enum; hàm nhận giá trị có mặt (khác null) và trả true khi SAI kiểu (trường lồng như `items`). */
export type Field = 'text' | 'int' | 'bool' | 'date' | 'time' | readonly string[] | ((value: unknown) => boolean);
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
export function wrongType(value: unknown, kind: Field): boolean {
  if (value === undefined || value === null) return false;
  if (typeof kind === 'function') return kind(value);
  if (kind === 'text') return typeof value !== 'string';
  if (kind === 'int') return !Number.isInteger(value);
  if (kind === 'bool') return typeof value !== 'boolean';
  if (kind === 'date') return typeof value !== 'string' || !isDay(value);
  // LocalTime `HH:mm` (BE đo: `9:00`, `25:00`, `08:00:00` → MALFORMED_REQUEST; `24:00` được nhận; chuỗi trống hoặc toàn khoảng trắng đọc như vắng → VALIDATION_FAILED).
  if (kind === 'time') return typeof value !== 'string' || (value.trim() !== '' && !/^(?:([01]\d|2[0-3]):[0-5]\d|24:00)$/.test(value));
  return typeof value !== 'string' || !kind.includes(value);
}

function dateParamError(params: Record<string, string>, names: readonly string[]): MockReply | null {
  const bad = names.find((n) => params[n] !== undefined && !isDay(params[n]));
  return bad ? fail('MALFORMED_REQUEST', `Tham số '${bad}' sai kiểu dữ liệu`, 400) : null;
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
  /** Tham số path kiểu ngày `yyyy-MM-dd` (sai → 400 MALFORMED_REQUEST, như `params`). */
  dateParams?: readonly string[];
  query?: QuerySpec;
  /** Có khóa này = request bắt buộc có body JSON object; giá trị là kiểu từng trường. */
  body?: Record<string, Field>;
  /** Bean Validation (@NotBlank, @Size, @Pattern…): chạy sau kiểm vai trò. */
  checks?: (body: Body) => Check[];
  /** Bean Validation chạy TRƯỚC kiểm vai trò (như workforce trên BE thật). */
  validateFirst?: boolean;
}

export function guarded(spec: GuardSpec, handle: (req: MockRequest, body: Body) => MockReply): MockHandler {
  return (req) => {
    const bad =
      (spec.params && typeError(req.params, [...spec.params])) ||
      (spec.dateParams && dateParamError(req.params, spec.dateParams)) ||
      (spec.query && queryError(req.query, spec.query));
    if (bad) return bad;
    let body: Body = {};
    if (spec.body) {
      const parsed = bodyOf(req, spec.body);
      if (!parsed) return malformed();
      body = parsed;
    }
    const check = () => spec.checks && invalid(spec.checks(body));
    if (spec.validateFirst) {
      const failed = check();
      if (failed) return failed;
    }
    if (!spec.roles.includes(req.account!.role)) return denied();
    const failed = spec.validateFirst ? null : check();
    return failed || handle(req, body);
  };
}

/** `@NotBlank` + `@Size(max)`. */
export const textChecks = (field: string, label: string, value: unknown, max: number): Check[] => [
  [field, `${label} không được để trống`, !isText(value)],
  [field, `${label} tối đa ${max} ký tự`, tooLong(value, max)],
];
