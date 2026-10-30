import { AxiosError, type AxiosResponse } from 'axios';
import { describe, expect, it } from 'vitest';
import { ApiError, toApiError } from '../api/api-error';
import { liveQuery, retryDelay, shouldRetry } from './realtime';

/** Lỗi thật của axios: có `response` khi BE trả 4xx/5xx, không có khi rớt mạng. */
function axiosError(status?: number): AxiosError {
  if (status === undefined) return new AxiosError('Network Error', 'ERR_NETWORK');
  const response = { status, statusText: '', data: {}, headers: {}, config: {} } as AxiosResponse;
  return new AxiosError(`Request failed with status code ${status}`, 'ERR_BAD_REQUEST', undefined, undefined, response);
}

describe('live query policy', () => {
  it('does not retry a 4xx that reaches the hook as a raw AxiosError', () => {
    expect(shouldRetry(0, axiosError(404))).toBe(false);
    expect(shouldRetry(0, axiosError(400))).toBe(false);
    expect(shouldRetry(0, axiosError(403))).toBe(false);
  });

  it('retries a 5xx AxiosError up to three times', () => {
    expect(shouldRetry(0, axiosError(500))).toBe(true);
    expect(shouldRetry(2, axiosError(503))).toBe(true);
    expect(shouldRetry(3, axiosError(503))).toBe(false);
  });

  it('retries an AxiosError with no response (network down) up to three times', () => {
    expect(shouldRetry(0, axiosError())).toBe(true);
    expect(shouldRetry(2, axiosError())).toBe(true);
    expect(shouldRetry(3, axiosError())).toBe(false);
  });

  it('does not retry business rule errors', () => {
    expect(shouldRetry(0, new ApiError('BUSINESS_RULE_VIOLATION', 'Lượt chưa được gán (BR-TN-05)', 400))).toBe(false);
  });

  it('retries network failures up to three times', () => {
    expect(shouldRetry(2, new TypeError('Failed to fetch'))).toBe(true);
    expect(shouldRetry(3, new TypeError('Failed to fetch'))).toBe(false);
  });

  it('retries server errors', () => {
    expect(shouldRetry(0, new ApiError('INTERNAL_ERROR', 'Lỗi máy chủ', 503))).toBe(true);
  });

  // Các hook gọi shouldRetry(count, toApiError(error)): rớt mạng thành ApiError statusCode 0, không phải 4xx.
  it('retries an ApiError with status 0 (no response) up to three times', () => {
    const offline = new ApiError('INTERNAL_ERROR', 'Không kết nối được máy chủ. Vui lòng thử lại.', 0);
    expect(shouldRetry(0, offline)).toBe(true);
    expect(shouldRetry(2, offline)).toBe(true);
    expect(shouldRetry(3, offline)).toBe(false);
  });

  it('does not retry a 404 ApiError but retries 503 up to three times', () => {
    expect(shouldRetry(0, new ApiError('RESOURCE_NOT_FOUND', 'Không tìm thấy', 404))).toBe(false);
    expect(shouldRetry(0, new ApiError('INTERNAL_ERROR', 'Lỗi máy chủ', 503))).toBe(true);
    expect(shouldRetry(3, new ApiError('INTERNAL_ERROR', 'Lỗi máy chủ', 503))).toBe(false);
  });

  it('keeps the policy after toApiError, the path the query hooks take', () => {
    expect(shouldRetry(0, toApiError(axiosError()))).toBe(true);
    expect(shouldRetry(0, toApiError(axiosError(503)))).toBe(true);
    expect(shouldRetry(0, toApiError(axiosError(404)))).toBe(false);
  });

  it('backs off exponentially up to 15 seconds', () => {
    expect([0, 1, 2, 3, 4, 5].map(retryDelay)).toEqual([1000, 2000, 4000, 8000, 15000, 15000]);
  });

  it('polls every 5 seconds and pauses while the tab is hidden', () => {
    expect(liveQuery).toMatchObject({ refetchInterval: 5000, refetchIntervalInBackground: false });
  });
});
