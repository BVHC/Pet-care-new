import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../api/axios';
import { MOCK_PASSWORD } from '../../api/mock/identity.mock';
import { installMockApi } from '../../api/mock/mock-api';
import { NAV_TO_PATH } from '../../constants/routes';
import { useSession } from '../../stores/session.store';
import { SiteHeader } from './SiteHeader';

// Menu người dùng ở header: mục "Lưu trú của tôi" (UC60) nằm ngay dưới "Lịch hẹn" và chỉ khi đã đăng nhập.

beforeAll(() => installMockApi(apiClient));
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-10-23T09:00:00+07:00'));
  useSession.getState().clear();
});
afterEach(() => vi.useRealTimers());

function renderHeader(onNav = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <SiteHeader currentPage="home" onNav={onNav} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { onNav, user: userEvent.setup() };
}

describe('SiteHeader: mục "Lưu trú của tôi"', () => {
  it('khách đã đăng nhập: có mục ngay dưới "Lịch hẹn", bấm thì chuyển tới trang lưu trú', async () => {
    await useSession.getState().login({ email: 'khach@petcare.vn', password: MOCK_PASSWORD });
    const { onNav, user } = renderHeader();
    await user.click(await screen.findByRole('button', { name: /Nguyễn Thu Trang/ }));

    const labels = screen
      .getAllByRole('button')
      .map((b) => b.textContent?.trim())
      .filter((t): t is string => !!t);
    expect(labels.indexOf('Lưu trú của tôi')).toBe(labels.indexOf('Lịch hẹn') + 1);
    // Mục cũ vẫn còn nguyên.
    expect(screen.getByRole('button', { name: 'Lịch hẹn' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Lưu trú của tôi' }));
    expect(onNav).toHaveBeenCalledWith('boardingbookings');
  });

  it('chưa đăng nhập: không có menu người dùng nên không có mục này', () => {
    renderHeader();
    expect(screen.queryByRole('button', { name: 'Lưu trú của tôi' })).not.toBeInTheDocument();
    expect(within(screen.getByRole('banner')).getAllByRole('button', { name: 'Đăng nhập' }).length).toBeGreaterThan(0);
  });

  it('khóa điều hướng "boardingbookings" trỏ tới /boarding-bookings', () => {
    expect(NAV_TO_PATH.boardingbookings).toBe('/boarding-bookings');
  });
});

// Hai hàng của header (đầu trang / đã cuộn) đều chứa chuông; hàng đang ẩn chỉ mờ + cắt (opacity-0, w-0/h-0, overflow-hidden) chứ không display:none,
// nên bản trùng vẫn lọt `:visible`, vẫn nhận Tab và làm `getByRole` ra 2 phần tử. Chỉ hàng đang hiện được mount chuông (mục 9 của C6).
describe('SiteHeader: chuông thông báo', () => {
  const bells = () => screen.queryAllByRole('button', { name: /^Thông báo/ });
  const scrollTo = (y: number) =>
    act(() => {
      Object.defineProperty(window, 'scrollY', { value: y, configurable: true });
      window.dispatchEvent(new Event('scroll'));
    });
  afterEach(() => scrollTo(0));

  it('khách đã đăng nhập, đầu trang: đúng một chuông', async () => {
    await useSession.getState().login({ email: 'khach@petcare.vn', password: MOCK_PASSWORD });
    renderHeader();
    await waitFor(() => expect(bells()).toHaveLength(1));
  });

  it('đã cuộn xuống: vẫn đúng một chuông (chuông đổi sang hàng gọn)', async () => {
    await useSession.getState().login({ email: 'khach@petcare.vn', password: MOCK_PASSWORD });
    renderHeader();
    await waitFor(() => expect(bells()).toHaveLength(1));
    scrollTo(300);
    await waitFor(() => expect(bells()).toHaveLength(1));
    scrollTo(0);
    await waitFor(() => expect(bells()).toHaveLength(1));
  });

  it('chưa đăng nhập: không có chuông', () => {
    renderHeader();
    expect(bells()).toHaveLength(0);
  });
});
