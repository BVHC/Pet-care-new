import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import { loadDb, resetClinicDb, seedClinicDb } from '../../shared/api/clinic-db';
import { useAdminSession } from '../../shared/stores/admin-session.store';
import { useConsultationStore } from '../../shared/stores/consultation.store';
import { DoctorWorkspacePage } from './DoctorWorkspacePage';

// Seed: vet '5' is examining Mochi (visit-5, skin exam, no diagnosis yet) and has Miu waiting.
const VET = { id: '5', email: 'doctor@store1.vn', name: 'BS. Minh Anh', role: 'VETERINARIAN' as const, organizationId: 'org-1' };

function renderExamRoom() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <DoctorWorkspacePage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  resetClinicDb(seedClinicDb());
  useAdminSession.setState({ user: VET, isAuthenticated: true });
  useConsultationStore.setState({ drafts: {}, activeVisitId: null });
});

describe('exam room', () => {
  it('opens on the patient the vet is examining', async () => {
    renderExamRoom();
    expect(await screen.findByRole('heading', { level: 2, name: 'Mochi' })).toBeInTheDocument();
  });

  it('explains why an exam without a diagnosis cannot be completed (BR-KB-02)', async () => {
    const user = userEvent.setup();
    renderExamRoom();

    await user.click(await screen.findByRole('button', { name: /Hoàn tất/ }));

    expect(await screen.findByText('Ghi chẩn đoán trước khi hoàn tất lượt khám.')).toBeInTheDocument();
    expect(loadDb().visits.find((v) => v.id === 'visit-5')?.status).toBe('IN_PROGRESS');
  });

  it('hands a diagnosed exam over to the cashier', async () => {
    const user = userEvent.setup();
    renderExamRoom();

    await user.type(await screen.findByLabelText(/Chẩn đoán/), 'Viêm da dị ứng');
    await user.click(screen.getByRole('button', { name: /Hoàn tất/ }));

    await waitFor(() => expect(loadDb().orders.find((o) => o.id === 'order-5')?.status).toBe('PENDING'));
    expect(loadDb().visits.find((v) => v.id === 'visit-5')?.record?.assessment).toBe('Viêm da dị ứng');
  });
});
