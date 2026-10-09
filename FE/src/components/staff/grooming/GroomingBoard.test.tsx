import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../../shared/api/api-error';
import { clinicApi } from '../../../shared/api/clinic.api';
import { resetClinicDb, seedClinicDb } from '../../../shared/api/clinic-db';
import { GroomingBoard } from './GroomingBoard';

// Seed: groomer '6' has Rocky waiting (visit-7) and Bơ in progress (visit-4).
const GROOMER = '6';

function renderBoard() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <GroomingBoard userId={GROOMER} scope="mine" />
    </QueryClientProvider>,
  );
}

const column = (name: RegExp) => screen.getByRole('list', { name });

beforeEach(() => {
  resetClinicDb(seedClinicDb());
});

describe('grooming board (useKanban)', () => {
  it('moves a card to "Đang làm" before the server answers', async () => {
    vi.spyOn(clinicApi, 'callVisit').mockReturnValue(new Promise(() => {}));
    const user = userEvent.setup();
    renderBoard();

    const rocky = await within(await screen.findByRole('list', { name: /Chờ làm/ })).findByRole('article', { name: /Rocky/ });
    await user.click(within(rocky).getByRole('button', { name: 'Bắt đầu' }));

    expect(await within(column(/Đang làm/)).findByRole('article', { name: /Rocky/ })).toBeInTheDocument();
  });

  it('puts the card back when the server refuses the move', async () => {
    vi.spyOn(clinicApi, 'callVisit').mockRejectedValue(new ApiError('BUSINESS_RULE_VIOLATION', 'Lượt này đã gán cho nhân viên khác. (BR-TN-05)', 400));
    const user = userEvent.setup();
    renderBoard();

    const rocky = await within(await screen.findByRole('list', { name: /Chờ làm/ })).findByRole('article', { name: /Rocky/ });
    await user.click(within(rocky).getByRole('button', { name: 'Bắt đầu' }));

    expect(await within(column(/Chờ làm/)).findByRole('article', { name: /Rocky/ })).toBeInTheDocument();
    expect(within(column(/Đang làm/)).queryByRole('article', { name: /Rocky/ })).not.toBeInTheDocument();
  });

  it('finishes a card into "Xong, chờ đón"', async () => {
    const user = userEvent.setup();
    renderBoard();

    const bo = await within(await screen.findByRole('list', { name: /Đang làm/ })).findByRole('article', { name: /Bơ/ });
    await user.click(within(bo).getByRole('button', { name: 'Xong' }));

    expect(await within(column(/chờ đón/)).findByRole('article', { name: /Bơ/ })).toBeInTheDocument();
  });
});
