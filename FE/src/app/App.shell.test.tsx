import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useNavigate, type NavigateFunction } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../shared/api/axios';
import { MOCK_PASSWORD } from '../shared/api/mock/identity.mock';
import { installMockApi } from '../shared/api/mock/mock-api';
import { useSession } from '../shared/stores/session.store';
import { App } from './App';

// Khung chung khu nhân viên — docs/superpowers/specs/2026-10-13-staff-shell-design.md §9.

beforeAll(() => installMockApi(apiClient));
beforeEach(() => {
  useSession.getState().clear();
  document.documentElement.classList.remove('dark');
});

const signInAs = (email: string) => useSession.getState().login({ email, password: MOCK_PASSWORD });

let navigateTo: NavigateFunction;
function Navigator() {
  navigateTo = useNavigate();
  return null;
}

function open(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
      <Navigator />
    </MemoryRouter>,
  );
}

const sidebar = () => screen.getByRole('complementary', { name: 'Thanh bên' });
const forbidden = () => screen.findByRole('heading', { name: 'Bạn không có quyền vào trang này' });

describe('staff shell', () => {
  it('keeps the same sidebar when the desk opens the overview', async () => {
    await signInAs('reception@store1.vn');
    open('/staff/reception');
    await screen.findByRole('heading', { name: 'Quầy lễ tân' });
    const before = sidebar();

    await userEvent.click(within(before).getByRole('link', { name: 'Tổng quan' }));

    expect(await screen.findByRole('heading', { name: 'Bảng điều khiển' })).toBeInTheDocument();
    expect(sidebar()).toBe(before);
  });

  it('still opens a page hidden from the menu by its URL', async () => {
    await signInAs('reception@store1.vn');
    open('/admin/queue');
    expect(await screen.findByRole('heading', { name: 'Hàng chờ walk-in' })).toBeInTheDocument();
  });

  it('shows 403 inside the frame, sidebar included', async () => {
    await signInAs('doctor@store1.vn');
    open('/admin/pos');
    expect(await forbidden()).toBeInTheDocument();
    expect(sidebar()).toBeInTheDocument();
  });

  it('keeps the technical admin out of the dashboard, without a desk bar', async () => {
    await signInAs('admin@petcare.vn');
    open('/admin/dashboard');
    expect(await forbidden()).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Chuyển bàn làm việc' })).not.toBeInTheDocument();
  });

  it('remembers the rail after a reload', async () => {
    await signInAs('doctor@store1.vn');
    const first = open('/staff/doctor');
    await userEvent.click(await screen.findByRole('button', { name: 'Thu gọn thanh bên' }));
    expect(localStorage.getItem('sidebar-collapsed')).toBe('true');

    first.unmount();
    open('/staff/doctor');

    expect(await screen.findByRole('button', { name: 'Mở rộng thanh bên' })).toBeInTheDocument();
  });

  it('keeps the rail usable when storage is blocked', async () => {
    await signInAs('doctor@store1.vn');
    const getItem = Storage.prototype.getItem;
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(function (this: Storage, key: string) {
      if (key === 'sidebar-collapsed') throw new Error('blocked');
      return getItem.call(this, key);
    });
    const setItem = Storage.prototype.setItem;
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (this: Storage, key: string, value: string) {
      if (key === 'sidebar-collapsed') throw new Error('blocked');
      return setItem.call(this, key, value);
    });
    open('/staff/doctor');

    await userEvent.click(await screen.findByRole('button', { name: 'Thu gọn thanh bên' }));

    expect(screen.getByRole('button', { name: 'Mở rộng thanh bên' })).toBeInTheDocument();
  });

  it('does not switch desks while a dialog is open', async () => {
    await signInAs('reception@store1.vn');
    open('/staff/reception');
    await screen.findByRole('heading', { name: 'Quầy lễ tân' });
    await userEvent.click(within(sidebar()).getByRole('button', { name: 'Phím tắt' }));
    await screen.findByRole('dialog');

    await userEvent.keyboard('{Alt>}2{/Alt}');

    expect(screen.getByRole('heading', { name: 'Quầy lễ tân', hidden: true })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Bảng điều khiển', hidden: true })).not.toBeInTheDocument();
  });

  it('closes the phone menu once a page is picked', async () => {
    await signInAs('reception@store1.vn');
    open('/staff/reception');
    await userEvent.click(await screen.findByRole('button', { name: 'Mở menu' }));
    const drawer = await screen.findByRole('dialog');

    await userEvent.click(within(drawer).getByRole('link', { name: 'Lịch hẹn' }));

    expect(await screen.findByRole('heading', { name: 'Quản lý lịch hẹn' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('leaves no staff theme behind on customer pages', async () => {
    localStorage.setItem('theme', 'dark');
    await signInAs('doctor@store1.vn');
    open('/staff/doctor');
    await screen.findByRole('heading', { name: 'Phòng khám' });
    expect(document.documentElement).toHaveClass('dark');
    expect(document.documentElement).toHaveAttribute('data-staff-shell');

    act(() => navigateTo('/help'));

    expect(document.documentElement).not.toHaveClass('dark');
    expect(document.documentElement).not.toHaveAttribute('data-staff-shell');
  });
});
