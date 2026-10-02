import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import { clinicApi } from '../../shared/api/clinic.api';
import { loadDb, resetClinicDb, seedClinicDb } from '../../shared/api/clinic-db';
import { useAdminSession } from '../../shared/stores/admin-session.store';
import { usePosStore } from '../../shared/stores/pos.store';
import { ReceptionWorkspacePage } from './ReceptionWorkspacePage';

// Seed: order-2 is Tiêu's exam (customer c5), PENDING.
const DESK = { id: '4', email: 'reception@store1.vn', name: 'Lan Chi', role: 'RECEPTIONIST' as const, organizationId: 'org-1' };

function renderDesk() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ReceptionWorkspacePage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  resetClinicDb(seedClinicDb());
  useAdminSession.setState({ user: DESK, isAuthenticated: true });
  usePosStore.getState().clear();
});

describe('reception desk', () => {
  it('never takes payment while the check-in dialog is open', async () => {
    await clinicApi.openShift('4');
    usePosStore.getState().attachOrder('order-2', 'c5');
    const user = userEvent.setup();
    renderDesk();
    await screen.findByText(/Ca mở lúc/);

    await user.click(screen.getByRole('button', { name: /Tiếp nhận khách/ }));
    await screen.findByRole('dialog');
    await user.keyboard('{Control>}{Enter}{/Control}');

    expect(loadDb().orders.find((o) => o.id === 'order-2')?.status).toBe('PENDING');
  });

  it('adds nothing when Enter is pressed in an empty product search', async () => {
    const user = userEvent.setup();
    renderDesk();
    const search = await screen.findByPlaceholderText('Tìm sản phẩm, Enter để thêm');
    await screen.findByRole('button', { name: /Royal Canin/ });

    await user.click(search);
    await user.keyboard('{Enter}');

    expect(usePosStore.getState().retail).toEqual([]);
  });
});
