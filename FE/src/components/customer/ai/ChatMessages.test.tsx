import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import type { ChatResponse } from '../../../shared/types/ai';
import { ChatEntryView } from './ChatMessages';

// Nhánh kết quả khó dựng bằng mock (UNAVAILABLE phụ thuộc hạn mức 20 lệnh/ngày): dựng ChatResponse theo hợp đồng và kiểm cách hiện.

const base: ChatResponse = { result: 'ANSWERED', answer: '', sources: [], bookingHint: false, emergencyBranches: [], pets: [] };

function show(response: Partial<ChatResponse>, onChoosePet = vi.fn()) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <ChatEntryView entry={{ id: 'x', from: 'bot', response: { ...base, ...response } }} onChoosePet={onChoosePet} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return onChoosePet;
}

describe('ChatEntryView theo result', () => {
  it('UNAVAILABLE: báo trợ lý bận, vẫn có nút đặt lịch, không phải khối cảnh báo', () => {
    show({ result: 'UNAVAILABLE', answer: 'Trợ lý đang bận, bạn thử lại sau nhé.', bookingHint: true });
    expect(screen.getByText('Trợ lý đang bận')).toBeInTheDocument();
    expect(screen.getByText(/thử lại sau nhé/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Đặt lịch khám/ })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('PERSONAL: giữ nguyên xuống dòng của câu mẫu, không nguồn, không nút', () => {
    show({ result: 'PERSONAL', answer: 'Bạn có 2 lịch hẹn sắp tới:\n1) Mochi — Tiêm phòng\n2) Bim — Tắm' });
    expect(screen.getByText(/Bạn có 2 lịch hẹn sắp tới/).closest('.whitespace-pre-line')).not.toBeNull();
    expect(screen.queryByText(/^Nguồn:/)).not.toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });

  it('NEED_PET_CHOICE: mỗi thú một nút, bấm trả đúng petId', async () => {
    const onChoose = show({ result: 'NEED_PET_CHOICE', answer: 'Bạn muốn xem lịch tiêm của bé nào?', pets: [{ petId: 12, name: 'Mochi' }, { petId: 15, name: 'Kitty' }] });
    await userEvent.click(screen.getByRole('button', { name: 'Kitty' }));
    expect(onChoose).toHaveBeenCalledWith({ petId: 15, name: 'Kitty' });
  });

  it('EMERGENCY: là khối alert; số điện thoại gọi được', () => {
    show({
      result: 'EMERGENCY',
      answer: 'Hãy đưa thú tới ngay chi nhánh dưới đây.',
      emergencyBranches: [{ branchId: 2, name: 'Pet Care Cầu Giấy', address: '1 Cầu Giấy', phone: '0901234567' }],
    });
    expect(screen.getByRole('alert')).toHaveTextContent('Pet Care Cầu Giấy');
    expect(screen.getByRole('link', { name: '0901234567' })).toHaveAttribute('href', 'tel:0901234567');
  });

  it('ANSWERED: nguồn nối bằng dấu chấm phẩy và lời nhắc', () => {
    show({ result: 'ANSWERED', answer: 'Mũi dại từ 12 tuần.', sources: [{ documentId: 1, title: 'Lịch tiêm cho chó con' }, { documentId: 2, title: 'Vaccine dại' }], disclaimer: 'Thông tin tham khảo.' });
    expect(screen.getByText('Nguồn: Lịch tiêm cho chó con; Vaccine dại')).toBeInTheDocument();
    expect(screen.getByText('Thông tin tham khảo.')).toBeInTheDocument();
  });
});
