import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it } from 'vitest';
import { loadDb, resetClinicDb, seedClinicDb } from '../../../shared/api/clinic-db';
import { useVisitDetail } from '../../../shared/hooks/useClinic';
import { useConsultationStore } from '../../../shared/stores/consultation.store';
import { useSession } from '../../../shared/stores/session.store';
import { SoapPanel } from './SoapPanel';

// Chuyển từ DoctorWorkspacePage.test (T36): bàn bác sĩ giờ chạy trên API visit nên không còn đưa SoapPanel ra màn hình, nhưng bệnh án và hoàn tất lượt vẫn chạy
// trên clinic-db cho tới T37. Giữ nguyên hai ca BR-KB-02 để T37 nối lại panel không mất độ phủ. Seed: vet '5' đang khám Mochi (visit-5, chưa có chẩn đoán).
const VET = { id: 5, email: 'doctor@store1.vn', role: 'VET' as const, status: 'ACTIVE' as const, isLocked: false, mustChangePassword: false };

function Harness() {
  const detail = useVisitDetail('visit-5');
  return <SoapPanel selectedId="visit-5" detail={detail} userId="5" onCompleted={() => {}} hotkeysEnabled />;
}

function renderSoap() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <Harness />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  resetClinicDb(seedClinicDb());
  useSession.setState({ token: 'mock-token-5', expiresAt: null, account: VET, fullName: 'BS. Minh Anh' });
  useConsultationStore.setState({ drafts: {}, activeVisitId: null });
});

describe('SoapPanel (chờ T37 nối API)', () => {
  it('explains why an exam without a diagnosis cannot be completed (BR-KB-02)', async () => {
    const user = userEvent.setup();
    renderSoap();

    await user.click(await screen.findByRole('button', { name: /Hoàn tất/ }));

    expect(await screen.findByText('Ghi chẩn đoán trước khi hoàn tất lượt khám.')).toBeInTheDocument();
    expect(loadDb().visits.find((v) => v.id === 'visit-5')?.status).toBe('IN_PROGRESS');
  });

  it('hands a diagnosed exam over to the cashier', async () => {
    const user = userEvent.setup();
    renderSoap();

    await user.type(await screen.findByLabelText(/Chẩn đoán/), 'Viêm da dị ứng');
    await user.click(screen.getByRole('button', { name: /Hoàn tất/ }));

    await waitFor(() => expect(loadDb().orders.find((o) => o.id === 'order-5')?.status).toBe('PENDING'));
    expect(loadDb().visits.find((v) => v.id === 'visit-5')?.record?.assessment).toBe('Viêm da dị ứng');
  });
});
