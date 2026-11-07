import { AxiosError, type AxiosResponse } from 'axios';
import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/api-error';
import { errorMessage } from './error-message';

const axiosError = (status: number, data: unknown) =>
  new AxiosError(`Request failed with status code ${status}`, 'ERR_BAD_REQUEST', undefined, {}, { status, data } as AxiosResponse);

describe('errorMessage', () => {
  it('lấy message của envelope lỗi từ AxiosError thô và bỏ mã rule ở cuối', () => {
    const raw = axiosError(400, { success: false, errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Ca đã tồn tại (BR-NS-01)', statusCode: 400, timestamp: '', traceId: 'x' });
    expect(errorMessage(raw)).toBe('Ca đã tồn tại');
  });

  it('AxiosError không có phản hồi: báo mất kết nối, không lộ "Request failed"', () => {
    expect(errorMessage(new AxiosError('Network Error', 'ERR_NETWORK'))).toBe('Không kết nối được máy chủ. Vui lòng thử lại.');
  });

  it('ApiError giữ nguyên; Error thường dùng message của nó; giá trị lạ dùng câu chung', () => {
    expect(errorMessage(new ApiError('ACCESS_DENIED', 'Không có quyền', 403))).toBe('Không có quyền');
    expect(errorMessage(new Error('boom'))).toBe('boom');
    expect(errorMessage(undefined)).toBe('Không thực hiện được. Thử lại sau ít phút.');
  });
});
