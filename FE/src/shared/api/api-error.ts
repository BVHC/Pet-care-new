// Lỗi API theo hợp đồng: docs/api/00-method.md §3.5 (khớp BE ErrorResponse + ErrorCode).
import { isAxiosError } from 'axios';

/** Mã lỗi của BE `platform/exception/ErrorCode`. */
export type ErrorCode =
  | 'BUSINESS_RULE_VIOLATION'
  | 'INVALID_STATE_TRANSITION'
  | 'RESOURCE_NOT_FOUND'
  | 'ACCESS_DENIED_SCOPE_MISMATCH'
  | 'CONCURRENCY_CONFLICT'
  | 'VALIDATION_FAILED'
  | 'MALFORMED_REQUEST'
  | 'METHOD_NOT_ALLOWED'
  | 'UNSUPPORTED_MEDIA_TYPE'
  | 'UNAUTHENTICATED'
  | 'ACCESS_DENIED'
  | 'INTERNAL_ERROR'
  /** BE hoặc phụ thuộc ngoài chưa sẵn sàng; mock dùng để mô phỏng lỗi 500 từ Visit/ca thu ngân (D016). */
  | 'UNAVAILABLE';

/** Envelope lỗi, luôn đủ 6 trường. */
export interface ErrorResponse {
  success: false;
  errorCode: ErrorCode;
  message: string;
  statusCode: number;
  timestamp: string;
  traceId: string;
}

const RULE_AT_END = /\((BR-[A-Z]+-\d+)\)$/;

/** Mã rule ở cuối message, vd `"… (BR-TK-08)"` → `"BR-TK-08"`. */
export function ruleIdOf(message: string): string | null {
  return RULE_AT_END.exec(message.trim())?.[1] ?? null;
}

export const newTraceId = () => Math.random().toString(16).slice(2, 18);

export class ApiError extends Error implements ErrorResponse {
  readonly success = false as const;
  /** Mã rule đọc từ cuối message; null nếu lỗi không phải BUSINESS_RULE_VIOLATION. */
  readonly ruleId: string | null;

  constructor(
    readonly errorCode: ErrorCode,
    message: string,
    readonly statusCode: number,
    readonly timestamp: string = new Date().toISOString(),
    readonly traceId: string = newTraceId(),
  ) {
    super(message);
    this.name = 'ApiError';
    this.ruleId = ruleIdOf(message);
  }
}

/**
 * Message để hiện cho người dùng: bỏ đuôi `(BR-…)` (mã rule vẫn đọc được qua `ruleId`)
 * và bỏ tên trường của lỗi validation.
 */
export function plainMessage(error: ApiError): string {
  if (error.ruleId) return error.message.replace(` (${error.ruleId})`, '');
  const fields = Object.values(fieldErrorsOf(error));
  return fields.length ? fields.join('. ') : error.message;
}

const FIELD_ERROR = /^([A-Za-z][\w.]*): (.+)$/;

/** Lỗi theo trường của VALIDATION_FAILED; BE ghép dạng `"email: …; phone: …"`. */
export function fieldErrorsOf(error: ApiError): Record<string, string> {
  if (error.errorCode !== 'VALIDATION_FAILED') return {};
  const fields: Record<string, string> = {};
  for (const part of error.message.split('; ')) {
    const m = FIELD_ERROR.exec(part.trim());
    if (m) fields[m[1]] = m[2];
  }
  return fields;
}

function isErrorResponse(data: unknown): data is ErrorResponse {
  const d = data as Partial<ErrorResponse> | null;
  return typeof d?.errorCode === 'string' && typeof d.message === 'string';
}

/** Chuẩn hóa mọi lỗi gọi API về `ApiError`. `statusCode` 0 = không nhận được phản hồi. */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) return error;
  if (isAxiosError(error) && error.response) {
    const { status, data } = error.response;
    if (isErrorResponse(data)) {
      return new ApiError(data.errorCode, data.message, data.statusCode ?? status, data.timestamp, data.traceId);
    }
    return new ApiError('INTERNAL_ERROR', 'Máy chủ đang gặp sự cố. Vui lòng thử lại sau.', status);
  }
  if (isAxiosError(error)) {
    return new ApiError('INTERNAL_ERROR', 'Không kết nối được máy chủ. Vui lòng thử lại.', 0);
  }
  return new ApiError('INTERNAL_ERROR', 'Đã có lỗi xảy ra. Vui lòng thử lại.', 0);
}
