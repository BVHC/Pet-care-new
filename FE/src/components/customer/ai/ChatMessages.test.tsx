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
    expect(screen.queryByText(/^Tài liệu liên quan:/)).not.toBeInTheDocument();
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

  it('ANSWERED: tài liệu liên quan nối bằng dấu chấm phẩy và lời nhắc; không còn nhãn "Nguồn:"', () => {
    show({ result: 'ANSWERED', answer: 'Mũi dại từ 12 tuần.', sources: [{ documentId: 1, title: 'Lịch tiêm cho chó con' }, { documentId: 2, title: 'Vaccine dại' }], disclaimer: 'Thông tin tham khảo.' });
    expect(screen.getByText('Tài liệu liên quan: Lịch tiêm cho chó con; Vaccine dại')).toBeInTheDocument();
    // T92b, BR-AI-05: danh sách này là 3 tài liệu truy hồi gần câu hỏi nhất, không phải "tài liệu mô hình dùng"; nhãn "Nguồn" nói quá.
    expect(screen.queryByText(/^Nguồn:/)).not.toBeInTheDocument();
    expect(screen.getByText('Thông tin tham khảo.')).toBeInTheDocument();
  });
});

// T93, BR-AI-34: nhãn nguồn dưới câu trả lời. Người rà soát của kho demo là giả định (Q2) nên không nhãn nào được ngụ ý "đã được bác sĩ duyệt".
describe('Nhãn nguồn của câu trả lời (sourceType)', () => {
  const LABELS = {
    DATABASE: 'Từ dữ liệu của phòng khám',
    KNOWLEDGE_BASE: 'Theo tài liệu của phòng khám',
    GENERAL_KNOWLEDGE: 'Kiến thức tham khảo chung, không phải tài liệu của phòng khám',
    KNOWLEDGE_BASE_AND_GENERAL: 'Theo tài liệu của phòng khám, có bổ sung kiến thức chung',
  } as const;
  const ANY_LABEL = /Từ dữ liệu của phòng khám|Theo tài liệu của phòng khám|Kiến thức tham khảo chung/;

  it.each(Object.entries(LABELS))('ANSWERED + %s: hiện đúng nhãn', (sourceType, label) => {
    show({ result: 'ANSWERED', answer: 'Câu trả lời.', sourceType: sourceType as ChatResponse['sourceType'] });
    expect(screen.getByText(label)).toBeInTheDocument();
    expect(screen.getByText('Câu trả lời.')).toBeInTheDocument();
  });

  it('PERSONAL + DATABASE: có nhãn dữ liệu của phòng khám (không nằm trong khối ANSWERED)', () => {
    show({ result: 'PERSONAL', answer: 'Bạn có 1 lịch hẹn sắp tới.', sourceType: 'DATABASE' });
    expect(screen.getByText(LABELS.DATABASE)).toBeInTheDocument();
  });

  it.each([
    ['NEED_LOGIN', 'DATABASE'],
    ['NEED_PET_CHOICE', 'DATABASE'],
    ['OUT_OF_SCOPE', null],
    ['UNAVAILABLE', null],
    ['MEDICAL_REFUSAL', 'SAFETY'],
    ['EMERGENCY', 'SAFETY'],
  ] as const)('%s: không nhãn dù sourceType = %s', (result, sourceType) => {
    show({ result, answer: 'Câu mẫu.', sourceType, pets: result === 'NEED_PET_CHOICE' ? [{ petId: 1, name: 'Mochi' }] : [] });
    expect(screen.getByText(/Câu mẫu\./)).toBeInTheDocument();
    expect(screen.queryByText(ANY_LABEL)).not.toBeInTheDocument();
  });

  it.each([['SAFETY'], [null], [undefined], ['GIA_TRI_MOI_CUA_BE'], ['constructor']])('ANSWERED + sourceType %s: không nhãn, không nổ', (sourceType) => {
    show({ result: 'ANSWERED', answer: 'Câu trả lời.', sourceType: sourceType as ChatResponse['sourceType'] });
    expect(screen.getByText('Câu trả lời.')).toBeInTheDocument();
    expect(screen.queryByText(ANY_LABEL)).not.toBeInTheDocument();
  });

  it('không nhãn nào ngụ ý đã được bác sĩ duyệt và không có emoji', () => {
    for (const label of Object.values(LABELS)) {
      expect(label).not.toMatch(/duyệt|bác sĩ|xác nhận/i);
    }
    for (const sourceType of Object.keys(LABELS)) {
      const { container, unmount } = render(
        <QueryClientProvider client={new QueryClient()}>
          <MemoryRouter>
            <ChatEntryView
              entry={{ id: 'x', from: 'bot', response: { ...base, answer: 'Câu trả lời.', sourceType: sourceType as ChatResponse['sourceType'] } }}
              onChoosePet={vi.fn()}
            />
          </MemoryRouter>
        </QueryClientProvider>,
      );
      expect(container.textContent).not.toMatch(/\p{Extended_Pictographic}/u);
      unmount();
    }
  });

  it('nhãn, nguồn và lời nhắc gộp một khối chân (một đường kẻ), nhãn là chữ thường không role', () => {
    const { container } = render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter>
          <ChatEntryView
            entry={{
              id: 'x',
              from: 'bot',
              response: { ...base, answer: 'Mũi dại từ 12 tuần.', sources: [{ documentId: 1, title: 'Vaccine dại' }], disclaimer: 'Thông tin tham khảo.', sourceType: 'KNOWLEDGE_BASE' },
            }}
            onChoosePet={vi.fn()}
          />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    expect(container.querySelectorAll('.border-t')).toHaveLength(1);
    const badge = screen.getByText(LABELS.KNOWLEDGE_BASE);
    expect(badge.tagName).toBe('P');
    expect(badge).not.toHaveAttribute('role');
    expect(screen.getByText('Tài liệu liên quan: Vaccine dại')).toBeInTheDocument();
  });

  it('kiến thức chung có màu nhấn khác nhãn tài liệu', () => {
    show({ result: 'ANSWERED', answer: 'Thông tin tham khảo chung: ...', sourceType: 'GENERAL_KNOWLEDGE' });
    expect(screen.getByText(LABELS.GENERAL_KNOWLEDGE).className).toMatch(/amber/);
  });
});
