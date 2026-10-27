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
