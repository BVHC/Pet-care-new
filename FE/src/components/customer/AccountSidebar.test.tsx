import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { AccountSidebar } from './AccountSidebar';

// Menu tài khoản của khách: mục "Lưu trú của tôi" (UC60) nằm ngay dưới "Lịch hẹn".

const renderSidebar = (active?: Parameters<typeof AccountSidebar>[0]['active']) =>
  render(
    <MemoryRouter>
      <AccountSidebar active={active} />
    </MemoryRouter>,
  );

describe('AccountSidebar: mục "Lưu trú của tôi"', () => {
  it('dẫn tới /boarding-bookings và nằm ngay dưới "Lịch hẹn"', () => {
    renderSidebar();
    const links = screen.getAllByRole('link');
    const hrefs = links.map((l) => l.getAttribute('href'));
    expect(screen.getByRole('link', { name: 'Lưu trú của tôi' })).toHaveAttribute('href', '/boarding-bookings');
    expect(hrefs.indexOf('/boarding-bookings')).toBe(hrefs.indexOf('/appointments') + 1);
    expect(screen.getByRole('link', { name: 'Lịch hẹn' })).toHaveAttribute('href', '/appointments');
  });

  it('active="boarding" chỉ tô sáng mục lưu trú, không tô mục lịch hẹn', () => {
    renderSidebar('boarding');
    const boarding = screen.getByRole('link', { name: 'Lưu trú của tôi' });
    const appointments = screen.getByRole('link', { name: 'Lịch hẹn' });
    expect(boarding.className).toMatch(/navItemActive/);
    expect(appointments.className).not.toMatch(/navItemActive/);
  });
});

describe('AccountSidebar: mục "Khiếu nại"', () => {
  it('dẫn tới /complaints và chỉ tô sáng khi active="complaints"', () => {
    renderSidebar('complaints');
    const link = screen.getByRole('link', { name: 'Khiếu nại' });
    expect(link).toHaveAttribute('href', '/complaints');
    expect(link.className).toMatch(/navItemActive/);
    expect(screen.getByRole('link', { name: 'Lịch hẹn' }).className).not.toMatch(/navItemActive/);
  });

  it('không tô sáng khi đang ở mục khác', () => {
    renderSidebar('boarding');
    expect(screen.getByRole('link', { name: 'Khiếu nại' }).className).not.toMatch(/navItemActive/);
  });
});

// L-23: chữ viết tắt trên avatar lấy chữ đầu của họ và của tên cuối ("Nguyễn Văn An" → "NA"), như avatar bác sĩ; trước đây lấy chữ đầu của HAI TỪ CUỐI ("VA").
describe('AccountSidebar: chữ viết tắt trên avatar', () => {
  const avatarOf = (name: string) => {
    const { container, unmount } = render(
      <MemoryRouter>
        <AccountSidebar userName={name} />
      </MemoryRouter>,
    );
    const text = container.querySelector('[class*="userAvatar"]')?.textContent;
    unmount();
    return text;
  };

  it.each([
    ['Nguyễn Văn An', 'NA'],
    ['Nguyễn Thu Trang', 'NT'],
    ['Ánh', 'Á'],
    ['  Lê   Hoa ', 'LH'],
  ])('%s → %s', (name, expected) => {
    expect(avatarOf(name)).toBe(expected);
  });
});
