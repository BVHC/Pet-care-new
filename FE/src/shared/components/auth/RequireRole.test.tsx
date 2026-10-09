import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import { useSession } from '../../stores/session.store';
import type { AccountSummary, Role } from '../../types/auth';
import { RequireRole } from './RequireRole';

const account = (role: Role, mustChangePassword = false): AccountSummary => ({
  id: 5,
  email: 'u@petcare.vn',
  role,
  status: 'ACTIVE',
  isLocked: false,
  mustChangePassword,
});

const IN_ONE_HOUR = () => new Date(Date.now() + 3_600_000).toISOString();

function Probe({ name }: { name: string }) {
  const { state } = useLocation();
  return <p>{`${name} from=${(state as { from?: string } | null)?.from ?? '-'}`}</p>;
}

function renderGuarded(path: string, roles: readonly Role[], loginPath?: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/auth/login" element={<Probe name="login" />} />
        <Route path="/admin/login" element={<Probe name="staff-login" />} />
        <Route path="/auth/change-password" element={<Probe name="change-password" />} />
        <Route element={<RequireRole roles={roles} loginPath={loginPath} />}>
          <Route path="/pets" element={<p>Thú cưng của tôi</p>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

const signIn = (a: AccountSummary | null, expiresAt = IN_ONE_HOUR()) =>
  useSession.setState({ token: a && 'token', account: a, expiresAt: a && expiresAt, fullName: null });

beforeEach(() => signIn(null));

describe('RequireRole', () => {
  it('sends a visitor who is not logged in to the login page and remembers where they were going', () => {
    renderGuarded('/pets?tab=2', ['CUSTOMER']);
    expect(screen.getByText('login from=/pets?tab=2')).toBeInTheDocument();
  });

  it('uses the login page given for the zone', () => {
    renderGuarded('/pets', ['VET'], '/admin/login');
    expect(screen.getByText('staff-login from=/pets')).toBeInTheDocument();
  });

  it('treats an expired session as not logged in', () => {
    signIn(account('CUSTOMER'), new Date(Date.now() - 1000).toISOString());
    renderGuarded('/pets', ['CUSTOMER']);
    expect(screen.getByText('login from=/pets')).toBeInTheDocument();
  });

  it('forces the change-password screen while mustChangePassword is set (BR-TK-17)', () => {
    signIn(account('CUSTOMER', true));
    renderGuarded('/pets', ['CUSTOMER']);
    expect(screen.getByText('change-password from=/pets')).toBeInTheDocument();
  });

  it('shows 403 to a role that is not allowed, with a way back to their own home', () => {
    signIn(account('VET'));
    renderGuarded('/pets', ['CUSTOMER']);
    expect(screen.getByRole('heading', { name: /không có quyền/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Về trang của tôi' })).toHaveAttribute('href', '/staff/doctor');
    expect(screen.queryByText('Thú cưng của tôi')).not.toBeInTheDocument();
  });

  it('renders the page for an allowed role', () => {
    signIn(account('CUSTOMER'));
    renderGuarded('/pets', ['CUSTOMER']);
    expect(screen.getByText('Thú cưng của tôi')).toBeInTheDocument();
  });
});
