import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../shared/api/axios';
import { MOCK_PASSWORD } from '../shared/api/mock/identity.mock';
import { installMockApi } from '../shared/api/mock/mock-api';
import { useSession } from '../shared/stores/session.store';
import { App } from './App';

// Nút sáng/tối là nguồn duy nhất của theme: class .dark trên <html> + localStorage 'theme'.

beforeAll(() => installMockApi(apiClient));
beforeEach(() => {
  useSession.getState().clear();
  document.documentElement.classList.remove('dark');
});

function open(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

describe('back-office theme', () => {
  it('opens a back-office page in the saved dark theme', async () => {
    localStorage.setItem('theme', 'dark');
    await useSession.getState().login({ email: 'manager@store1.vn', password: MOCK_PASSWORD });
    open('/admin/dashboard');

    expect(await screen.findByRole('heading', { name: 'Bảng điều khiển' })).toBeInTheDocument();
    expect(document.documentElement).toHaveClass('dark');
  });

  it('turns the saved dark theme off from the header and remembers light', async () => {
    localStorage.setItem('theme', 'dark');
    await useSession.getState().login({ email: 'manager@store1.vn', password: MOCK_PASSWORD });
    open('/admin/dashboard');

    await userEvent.click(await screen.findByRole('button', { name: 'Chế độ sáng' }));

    expect(document.documentElement).not.toHaveClass('dark');
    expect(localStorage.getItem('theme')).toBe('light');
    expect(screen.getByRole('button', { name: 'Chế độ tối' })).toBeInTheDocument();
  });
});
