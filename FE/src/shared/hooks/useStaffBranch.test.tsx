// Chi nhánh của nhân viên khi BE thật KHÔNG trả `branchId` ở login (chỉ `GET /me` → staffProfile.branchId). Mock login có sẵn
// `branchId` nên che lỗi này (T49): các ca dưới đây bỏ `branchId` khỏi phiên để dựng đúng cái BE trả.
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../api/axios';
import { authApi } from '../api/auth.api';
import { MOCK_PASSWORD } from '../api/mock/identity.mock';
import { installMockApi } from '../api/mock/mock-api';
import { profileApi } from '../api/profile.api';
import { useSession } from '../stores/session.store';
import { useStaffBranch, useStaffBranchId } from './useStaffBranch';

beforeAll(() => installMockApi(apiClient));
beforeEach(() => useSession.getState().clear());
afterEach(() => vi.restoreAllMocks());

const wrapper = ({ children }: { children: ReactNode }) => (
  <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{children}</QueryClientProvider>
);

/** Phiên như BE thật trả: có tài khoản nhưng không có `branchId` (phiên lưu từ bản cũ). */
async function loginWithoutBranch(email: string) {
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
  useSession.setState((s) => ({ account: { ...s.account!, branchId: null } }));
}

describe('useStaffBranch', () => {
  it('phiên đã có branchId thì không hỏi GET /me', async () => {
    await useSession.getState().login({ email: 'reception@store1.vn', password: MOCK_PASSWORD });
    const me = vi.spyOn(profileApi, 'me');

    const { result } = renderHook(() => useStaffBranch(), { wrapper });

    expect(result.current).toEqual({ branchId: 1, isPending: false });
    expect(me).not.toHaveBeenCalled();
  });

  it('phiên thiếu branchId thì lấy từ GET /me, trong lúc chờ báo isPending', async () => {
    await loginWithoutBranch('reception@store1.vn');

    const { result } = renderHook(() => useStaffBranch(), { wrapper });

    expect(result.current).toEqual({ branchId: null, isPending: true });
    await waitFor(() => expect(result.current).toEqual({ branchId: 1, isPending: false }));
  });

  it('quản lý chi nhánh 2 nhận chi nhánh 2 từ GET /me', async () => {
    await loginWithoutBranch('manager2@store2.vn');

    const { result } = renderHook(() => useStaffBranchId(), { wrapper });

    await waitFor(() => expect(result.current).toBe(2));
  });

  it('chưa đăng nhập thì không hỏi gì và không chờ', () => {
    const me = vi.spyOn(profileApi, 'me');

    const { result } = renderHook(() => useStaffBranch(), { wrapper });

    expect(result.current).toEqual({ branchId: null, isPending: false });
    expect(me).not.toHaveBeenCalled();
  });

  it('khách (không có hồ sơ nhân viên) không bao giờ có chi nhánh và hết chờ', async () => {
    await loginWithoutBranch('khach@petcare.vn');

    const { result } = renderHook(() => useStaffBranch(), { wrapper });

    await waitFor(() => expect(result.current.isPending).toBe(false));
    expect(result.current.branchId).toBeNull();
  });
});

describe('login gộp branchId từ GET /me (BE không trả ở login)', () => {
  /** Giả BE thật: `POST /auth/login` trả tài khoản KHÔNG có `branchId`. */
  function stripBranchFromLogin() {
    const real = authApi.login;
    vi.spyOn(authApi, 'login').mockImplementation(async (req) => {
      const res = await real(req);
      return { ...res, account: { ...res.account, branchId: undefined } };
    });
  }

  it('nhân viên: branchId vào phiên ngay sau login, không cần hỏi lại', async () => {
    stripBranchFromLogin();

    await useSession.getState().login({ email: 'manager@store1.vn', password: MOCK_PASSWORD });

    expect(useSession.getState().account).toMatchObject({ id: 3, role: 'BRANCH_MANAGER', branchId: 1 });
  });

  it('khách: không thêm branchId', async () => {
    stripBranchFromLogin();

    await useSession.getState().login({ email: 'khach@petcare.vn', password: MOCK_PASSWORD });

    expect(useSession.getState().account?.branchId ?? null).toBeNull();
  });
});
