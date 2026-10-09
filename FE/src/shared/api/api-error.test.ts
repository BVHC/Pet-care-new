import { AxiosError, AxiosHeaders, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { describe, expect, it } from 'vitest';
import { ApiError, ruleIdOf, toApiError } from './api-error';

const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig;

function httpError(status: number, data: unknown): AxiosError {
  const response = { status, statusText: '', data, headers: {}, config } as AxiosResponse;
  return new AxiosError('Request failed', AxiosError.ERR_BAD_REQUEST, config, {}, response);
}

describe('ruleIdOf (00-method §3.5: mã rule ở cuối message)', () => {
  it('reads the rule code that ends the message', () => {
    expect(ruleIdOf('Thú cưng đã có 2 lịch hẹn đang chờ (BR-LH-05)')).toBe('BR-LH-05');
  });

  it('ignores a rule code that is not at the end', () => {
    expect(ruleIdOf('Theo (BR-TK-03) thì mật khẩu phải có chữ và số')).toBeNull();
  });

  it('returns null when the message carries no rule code', () => {
    expect(ruleIdOf('Email hoặc mật khẩu không đúng')).toBeNull();
  });
});

describe('toApiError', () => {
  it('keeps all 6 fields of the BE error envelope and exposes the rule code', () => {
    const err = toApiError(
      httpError(400, {
        success: false,
        errorCode: 'BUSINESS_RULE_VIOLATION',
        message: 'Tài khoản chưa xác thực email (BR-TK-08)',
        statusCode: 400,
        timestamp: '2026-10-09T10:00:00Z',
        traceId: 'trace-1',
      }),
    );
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({
      success: false,
      errorCode: 'BUSINESS_RULE_VIOLATION',
      message: 'Tài khoản chưa xác thực email (BR-TK-08)',
      statusCode: 400,
      timestamp: '2026-10-09T10:00:00Z',
      traceId: 'trace-1',
      ruleId: 'BR-TK-08',
    });
  });

  it('turns a response that is not the envelope (proxy error page) into a generic error with its status', () => {
    const err = toApiError(httpError(502, '<html>Bad Gateway</html>'));
    expect(err).toMatchObject({ errorCode: 'INTERNAL_ERROR', statusCode: 502, ruleId: null });
    expect(err.message).not.toBe('');
  });

  it('reports a request that got no response as a connection failure', () => {
    const err = toApiError(new AxiosError('Network Error', AxiosError.ERR_NETWORK, config, {}));
    expect(err).toMatchObject({ errorCode: 'INTERNAL_ERROR', statusCode: 0 });
    expect(err.message).toBe('Không kết nối được máy chủ. Vui lòng thử lại.');
  });

  it('passes an ApiError through untouched', () => {
    const original = new ApiError('RESOURCE_NOT_FOUND', 'Không tìm thấy lượt tiếp nhận.', 404);
    expect(toApiError(original)).toBe(original);
  });
});
