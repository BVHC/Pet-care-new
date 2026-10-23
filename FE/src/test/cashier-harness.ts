// Dùng chung cho test màn hình thu ngân (T35): đồng hồ ghim ở thứ Năm 22/10/2026 10:00 giờ Việt Nam (chi nhánh 1 mở cửa),
// đăng nhập bằng mock để có `branchId`. Gọi `pinClock` TRƯỚC khi mock nạp dữ liệu lần đầu.
import { vi } from 'vitest';
import { apiClient } from '../shared/api/axios';
import { MOCK_PASSWORD } from '../shared/api/mock/identity.mock';
import { useSession } from '../shared/stores/session.store';

export const THURSDAY_10 = '2026-10-22T10:00:00+07:00';

/** Chỉ giả `Date`: timer thật để userEvent và react-query chạy bình thường. */
export function pinClock(iso: string = THURSDAY_10) {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(iso));
}

export async function signInAs(email: string) {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
}

/** Dựng tình huống bằng API mock dưới một tài khoản khác (vd quản lý thêm ngày nghỉ), rồi đăng nhập lại `restore`. */
export async function asAccount<T>(email: string, restore: string, run: () => Promise<T>): Promise<T> {
  await signInAs(email);
  try {
    return await run();
  } finally {
    await signInAs(restore);
  }
}

export const api = apiClient;
