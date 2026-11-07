// Tiện ích chung cho test các trang mua hàng online: đăng nhập tài khoản mẫu, dựng router + react-query, hiện URL hiện tại.
// File .ts không JSX: không vướng quy tắc react-refresh của eslint.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createElement as h, type ReactNode } from 'react';
import { MemoryRouter, Routes, useLocation } from 'react-router-dom';
import { Toaster } from 'sonner';
import { MOCK_PASSWORD } from '@/shared/api/mock/identity.mock';
import { useSession } from '@/shared/stores/session.store';

// jsdom thiếu API con trỏ mà sonner gọi khi bấm nút trong toast.
Element.prototype.setPointerCapture ??= () => {};
Element.prototype.releasePointerCapture ??= () => {};

export async function loginAs(email = 'khach@petcare.vn') {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
}

function UrlProbe() {
  const { pathname, search } = useLocation();
  return h('output', { 'data-testid': 'url' }, pathname + search);
}

/** `routes` là các `<Route>`; `initial` là URL mở đầu. Trả về `user` và `client` để test làm mới / đọc bộ nhớ đệm. */
export function renderRoutes(routes: ReactNode, initial: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(h(QueryClientProvider, { client }, h(MemoryRouter, { initialEntries: [initial] }, h(Routes, null, routes), h(UrlProbe), h(Toaster))));
  return { user: userEvent.setup(), client };
}
