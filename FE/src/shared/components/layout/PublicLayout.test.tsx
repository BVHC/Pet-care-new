import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { loginAs } from '@/components/customer/shop/testing';
import { apiClient } from '../../api/axios';
import { installMockApi } from '../../api/mock/mock-api';
import { shopApi } from '../../api/shop.api';
import { useSession } from '../../stores/session.store';
import { PublicLayout } from './PublicLayout';

// Huy hiệu giỏ lấy từ GET /me/cart (shop-v1 #1): giỏ mẫu khach@ = 1 túi hạt + 2 gói pate = 3 sản phẩm. Khách vãng lai và nhân viên không có giỏ nên không được gọi API.

beforeAll(() => installMockApi(apiClient));
beforeEach(() => useSession.getState().clear());
afterEach(() => vi.restoreAllMocks());

function renderLayout() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route element={<PublicLayout />}>
            <Route path="/" element={<p>Nội dung trang</p>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** Huy hiệu đỏ bên cạnh biểu tượng giỏ ở header (có thể có hai bản cho hai hàng của header). */
const badges = () => Array.from(document.querySelectorAll('span.bg-red-600')).map((el) => el.textContent);

describe('PublicLayout: huy hiệu giỏ hàng', () => {
  it('khách hàng: hiện tổng số lượng trong giỏ trên BE', async () => {
    await loginAs();
    renderLayout();
    await waitFor(() => expect(badges().length).toBeGreaterThan(0));
    expect(new Set(badges())).toEqual(new Set(['3']));
  });

  it('khách vãng lai: không có huy hiệu và không gọi API giỏ', async () => {
    const getCart = vi.spyOn(shopApi, 'getCart');
    renderLayout();
    expect(await screen.findByText('Nội dung trang')).toBeInTheDocument();
    expect(badges()).toEqual([]);
    expect(getCart).not.toHaveBeenCalled();
  });

  it('nhân viên (lễ tân): không có huy hiệu và không gọi API giỏ', async () => {
    await loginAs('reception@store1.vn');
    const getCart = vi.spyOn(shopApi, 'getCart');
    renderLayout();
    expect(await screen.findByText('Nội dung trang')).toBeInTheDocument();
    expect(badges()).toEqual([]);
    expect(getCart).not.toHaveBeenCalled();
  });

  it('giỏ rỗng (khachmoi@): không hiện số 0', async () => {
    await loginAs('khachmoi@petcare.vn');
    const getCart = vi.spyOn(shopApi, 'getCart');
    renderLayout();
    await waitFor(() => expect(getCart).toHaveBeenCalled());
    expect(await screen.findByText('Nội dung trang')).toBeInTheDocument();
    expect(badges()).toEqual([]);
  });
});
