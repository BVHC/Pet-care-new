import { QueryClientProvider, useQuery, type QueryClient } from '@tanstack/react-query';
import { render, renderHook, waitFor } from '@testing-library/react';
import { AxiosError, type AxiosResponse } from 'axios';
import { createElement, type ReactNode } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../shared/api/api-error';
import { apiClient } from '../shared/api/axios';
import { installMockApi } from '../shared/api/mock/mock-api';
import { App } from './App';

// Chính sách thử lại mặc định của QueryClient mà App dựng (hook không đặt `retry` riêng dùng nó):
// lỗi mạng và 5xx thử lại tối đa 3 lần, 4xx thì không.

// GlobalModal nằm trong QueryClientProvider của App nên lấy được đúng client đó (App không xuất client).
const app = vi.hoisted(() => ({ client: undefined as QueryClient | undefined }));
vi.mock('./GlobalModal', async () => {
  const { useQueryClient } = await import('@tanstack/react-query');
  return {
    GlobalModal: () => {
      app.client = useQueryClient();
      return null;
    },
  };
});

beforeAll(() => {
  installMockApi(apiClient);
  render(
    <MemoryRouter initialEntries={['/help']}>
      <App />
    </MemoryRouter>,
  );
});

/** Lỗi axios có phản hồi của BE (4xx / 5xx), như hook không qua toApiError nhận được. */
function axiosError(status: number): AxiosError {
  const response = { status, statusText: '', data: {}, headers: {}, config: {} } as AxiosResponse;
  return new AxiosError(`Request failed with status code ${status}`, 'ERR_BAD_REQUEST', undefined, undefined, response);
}

let probe = 0;

/** Chạy một useQuery không đặt `retry` trên QueryClient của App; chỉ rút `retryDelay` để khỏi chờ. Trả số lần gọi queryFn. */
async function callsUntilFailure(error: unknown): Promise<number> {
  const queryFn = vi.fn().mockRejectedValue(error);
  const queryKey = ['retry-probe', probe++];
  const wrapper = ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client: app.client! }, children);
  const { result } = renderHook(() => useQuery({ queryKey, queryFn, retryDelay: 0 }), { wrapper });
  await waitFor(() => expect(result.current.isError).toBe(true), { timeout: 3000 });
  return queryFn.mock.calls.length;
}

describe('QueryClient của App', () => {
  it('không thử lại lỗi 4xx: đúng 1 lần gọi', async () => {
    expect(await callsUntilFailure(new ApiError('RESOURCE_NOT_FOUND', 'Không tìm thấy', 404))).toBe(1);
    expect(await callsUntilFailure(axiosError(404))).toBe(1);
  });

  it('thử lại lỗi 5xx và lỗi mạng tối đa 3 lần: 4 lần gọi', async () => {
    expect(await callsUntilFailure(new ApiError('INTERNAL_ERROR', 'Lỗi máy chủ', 503))).toBe(4);
    expect(await callsUntilFailure(new AxiosError('Network Error', 'ERR_NETWORK'))).toBe(4);
    expect(await callsUntilFailure(new ApiError('INTERNAL_ERROR', 'Không kết nối được máy chủ. Vui lòng thử lại.', 0))).toBe(4);
  });
});
