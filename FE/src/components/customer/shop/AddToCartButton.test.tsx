import { screen, waitFor } from '@testing-library/react';
import { Route, useLocation } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '@/shared/api/axios';
import { installMockApi } from '@/shared/api/mock/mock-api';
import { shopApi } from '@/shared/api/shop.api';
import { useSession } from '@/shared/stores/session.store';
import { AddToCartButton } from './AddToCartButton';
import { loginAs, renderRoutes } from './testing';

// UC61, shop-v1 #1–2. Sản phẩm: 1 Hạt Royal Canin (GOODS), 5 Vaccine Dại (VACCINE, không bán online, BR-BH-07). Giỏ khach@: hạt × 1 + pate × 2; khachmoi@ giỏ rỗng.

beforeAll(() => installMockApi(apiClient));
beforeEach(() => useSession.getState().clear());
afterEach(() => vi.restoreAllMocks());

function LoginProbe() {
  const state = useLocation().state as { from?: string } | null;
  return <p>Trang đăng nhập, quay lại {state?.from}</p>;
}

function renderButton(props: { productId: number; quantity?: number }) {
  return renderRoutes(
    <>
      <Route path="/shop" element={<AddToCartButton {...props} />} />
      <Route path="/auth/login" element={<LoginProbe />} />
      <Route path="/cart" element={<p>Trang giỏ hàng</p>} />
    </>,
    '/shop?q=hat',
  );
}

const quantityOf = async (productId: number) => (await shopApi.getCart()).items.find((i) => i.productId === productId)?.quantity;

describe('AddToCartButton (UC61)', () => {
  it('khách vãng lai: chuyển sang đăng nhập, nhớ trang để quay lại, không gọi API giỏ', async () => {
    const getCart = vi.spyOn(shopApi, 'getCart');
    const setItem = vi.spyOn(shopApi, 'setCartItem');
    const { user } = renderButton({ productId: 1 });
    await user.click(screen.getByRole('button', { name: 'Thêm vào giỏ' }));
    expect(await screen.findByText('Trang đăng nhập, quay lại /shop?q=hat')).toBeInTheDocument();
    expect(getCart).not.toHaveBeenCalled();
    expect(setItem).not.toHaveBeenCalled();
  });

  it('khách hàng: cộng dồn vào dòng có sẵn (PUT đặt số lượng = hiện có + thêm), báo thành công, có nút xem giỏ', async () => {
    await loginAs();
    const { user } = renderButton({ productId: 1, quantity: 3 });
    await user.click(screen.getByRole('button', { name: 'Thêm vào giỏ' }));
    expect(await screen.findByText('Đã thêm vào giỏ hàng')).toBeInTheDocument();
    expect(await quantityOf(1)).toBe(4);
    await user.click(screen.getByRole('button', { name: 'Xem giỏ hàng' }));
    expect(await screen.findByText('Trang giỏ hàng')).toBeInTheDocument();
  });

  it('sản phẩm chưa có trong giỏ: thêm mới với số lượng 1', async () => {
    await loginAs('khachmoi@petcare.vn');
    const { user } = renderButton({ productId: 2 });
    await user.click(screen.getByRole('button', { name: 'Thêm vào giỏ' }));
    await waitFor(async () => expect(await quantityOf(2)).toBe(1));
  });

  it('vaccine / thuốc không bán online: lỗi 400 BR-BH-07 của BE hiện thành thông báo, giỏ không đổi', async () => {
    await loginAs();
    const { user } = renderButton({ productId: 5 });
    await user.click(screen.getByRole('button', { name: 'Thêm vào giỏ' }));
    expect(await screen.findByText(/Thuốc và vaccine không bán online/)).toBeInTheDocument();
    expect(await quantityOf(5)).toBeUndefined();
  });

  it('dòng đã đủ 99: báo rõ, không gửi lệnh ghi vô nghĩa', async () => {
    await loginAs();
    await shopApi.setCartItem(1, { quantity: 99 });
    const setItem = vi.spyOn(shopApi, 'setCartItem');
    const { user } = renderButton({ productId: 1 });
    await user.click(screen.getByRole('button', { name: 'Thêm vào giỏ' }));
    expect(await screen.findByText('Giỏ hàng đã có tối đa 99 sản phẩm này')).toBeInTheDocument();
    expect(setItem).not.toHaveBeenCalled();
  });

  it('nhân viên (lễ tân) không thấy nút', async () => {
    await loginAs('reception@store1.vn');
    renderButton({ productId: 1 });
    expect(screen.queryByRole('button', { name: 'Thêm vào giỏ' })).not.toBeInTheDocument();
  });
});
