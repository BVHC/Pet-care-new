// Envelope trả về của mock, đúng 00-method §3.4 (thành công) và §3.5 (lỗi 6 trường).
import { newTraceId, type ErrorCode, type ErrorResponse } from '../api-error';
import type { AccountSummary } from '../../types/auth';

export interface MockRequest {
  body: unknown;
  /** Người gọi; luôn có ở path cần đăng nhập. */
  account: AccountSummary | null;
}
export interface MockReply {
  status: number;
  body?: unknown;
}
export type MockHandler = (req: MockRequest) => MockReply;

export const ok = (data: unknown, status = 200, message = 'success'): MockReply => ({
  status,
  body: { data, message, code: status },
});

export const noContent = (): MockReply => ({ status: 204 });

export function fail(errorCode: ErrorCode, message: string, status: number): MockReply {
  const body: ErrorResponse = {
    success: false,
    errorCode,
    message,
    statusCode: status,
    timestamp: new Date().toISOString(),
    traceId: newTraceId(),
  };
  return { status, body };
}

/** Như BE: mã rule nối vào cuối message. */
export const ruleFail = (ruleId: string, message: string) => fail('BUSINESS_RULE_VIOLATION', `${message} (${ruleId})`, 400);
