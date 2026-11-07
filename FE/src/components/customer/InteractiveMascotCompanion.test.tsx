import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { aiApi } from '../../shared/api/ai.api';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AI_PROVIDER_NOTICE } from '../../shared/types/ai';
import { InteractiveMascotCompanion } from './InteractiveMascotCompanion';

// UC90, UC91: khung chat của mascot. Mock ai-v1 trả lời bằng độ phủ từ khóa trên tài liệu PUBLISHED; bộ đếm giới hạn nằm trong localStorage nên mỗi ca bắt đầu sạch.

beforeAll(() => installMockApi(apiClient));
beforeEach(() => useSession.getState().clear());

const login = (email: string) => useSession.getState().login({ email, password: MOCK_PASSWORD });

function renderMascot() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <InteractiveMascotCompanion />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

async function openChat(user: ReturnType<typeof renderMascot>) {
  await user.click(screen.getByRole('button', { name: /Trợ lý AI PetCare/ }));
  return screen.findByRole('region', { name: 'Trò chuyện với trợ lý AI' });
}

async function ask(user: ReturnType<typeof renderMascot>, chat: HTMLElement, question: string) {
  await user.type(within(chat).getByRole('textbox', { name: 'Câu hỏi cho trợ lý AI' }), question);
  await user.click(within(chat).getByRole('button', { name: 'Gửi câu hỏi' }));
}

describe('mở khung chat', () => {
  it('chưa mở thì không có khung; mở bằng nút mascot; luôn hiện thông báo gửi tới Google (BR-AI-25); chưa gọi API nào', async () => {
    const publicAsk = vi.spyOn(aiApi, 'ask');
    const meAsk = vi.spyOn(aiApi, 'askAsCustomer');
    const user = renderMascot();
    expect(screen.queryByRole('region', { name: 'Trò chuyện với trợ lý AI' })).not.toBeInTheDocument();
    const chat = await openChat(user);
    expect(within(chat).getByText(AI_PROVIDER_NOTICE)).toBeInTheDocument();
    expect(AI_PROVIDER_NOTICE).toMatch(/Google/);
    expect(within(chat).getByText(/Mình không chẩn đoán bệnh hay kê thuốc/)).toBeInTheDocument();
    expect(publicAsk).not.toHaveBeenCalled();
    expect(meAsk).not.toHaveBeenCalled();

    await user.click(within(chat).getByRole('button', { name: 'Thu nhỏ khung chat' }));
    expect(screen.queryByRole('region', { name: 'Trò chuyện với trợ lý AI' })).not.toBeInTheDocument();
  });
});

describe('khách vãng lai (endpoint công khai)', () => {
  it('ANSWERED: câu trả lời kèm nguồn trích dẫn và lời nhắc tham khảo', async () => {
    const publicAsk = vi.spyOn(aiApi, 'ask');
    const meAsk = vi.spyOn(aiApi, 'askAsCustomer');
    const user = renderMascot();
    const chat = await openChat(user);
    await ask(user, chat, 'Chó con tiêm phòng dại lúc mấy tuần?');
    expect(await within(chat).findByText(/^Nguồn:/)).toBeInTheDocument();
    expect(within(chat).getByText(/không thay thế khám thú y/i)).toBeInTheDocument();
    expect(publicAsk.mock.calls[0][0]).toEqual({ question: 'Chó con tiêm phòng dại lúc mấy tuần?' });
    expect(meAsk).not.toHaveBeenCalled();
    expect(within(chat).getByRole('textbox')).toHaveValue('');
  });

  it('OUT_OF_SCOPE: câu mẫu kèm nút Đặt lịch khám (bookingHint)', async () => {
    const user = renderMascot();
    const chat = await openChat(user);
    await ask(user, chat, 'Giá vàng hôm nay là bao nhiêu?');
    expect(await within(chat).findByText(/Mình chưa có thông tin cho câu hỏi này/)).toBeInTheDocument();
    expect(within(chat).getByRole('link', { name: /Đặt lịch khám/ })).toHaveAttribute('href', '/booking');
    expect(within(chat).queryByText(/^Nguồn:/)).not.toBeInTheDocument();
  });

  it('MEDICAL_REFUSAL: từ chối liều thuốc, hướng dẫn đặt lịch', async () => {
    const user = renderMascot();
    const chat = await openChat(user);
    await ask(user, chat, 'Chó 10 kg uống bao nhiêu mg paracetamol?');
    expect(await within(chat).findByText('Mình không thể tư vấn chẩn đoán hay thuốc')).toBeInTheDocument();
    expect(within(chat).getByRole('link', { name: /Đặt lịch khám/ })).toBeInTheDocument();
  });

  it('EMERGENCY: khối cảnh báo nổi bật và danh sách chi nhánh có số gọi được', async () => {
    const user = renderMascot();
    const chat = await openChat(user);
    await ask(user, chat, 'Mèo co giật liên tục');
    const alert = await within(chat).findByRole('alert');
    expect(alert).toHaveTextContent('TÌNH HUỐNG KHẨN CẤP');
    const phone = within(alert).getAllByRole('link').find((a) => a.getAttribute('href')?.startsWith('tel:'));
    expect(phone).toBeDefined();
  });

  it('NEED_LOGIN: hỏi lịch của bé nhà mình thì được mời đăng nhập, không lộ dữ liệu', async () => {
    const user = renderMascot();
    const chat = await openChat(user);
    await ask(user, chat, 'Lịch tiêm của bé nhà tôi');
    expect(await within(chat).findByRole('link', { name: 'Đăng nhập' })).toHaveAttribute('href', '/auth/login');
  });

  it('gửi kèm tối đa 3 câu hỏi trước CỦA NGƯỜI DÙNG, không có câu trả lời của trợ lý (BR-AI-08)', async () => {
    const publicAsk = vi.spyOn(aiApi, 'ask');
    const user = renderMascot();
    const chat = await openChat(user);
    for (const q of ['Câu một?', 'Câu hai?', 'Câu ba?', 'Câu bốn?', 'Câu năm?']) {
      await ask(user, chat, q);
      await within(chat).findAllByText(/Mình chưa có thông tin/).then((all) => expect(all.length).toBeGreaterThan(0));
      await vi.waitFor(() => expect(publicAsk).toHaveBeenCalledTimes(['Câu một?', 'Câu hai?', 'Câu ba?', 'Câu bốn?', 'Câu năm?'].indexOf(q) + 1));
    }
    expect(publicAsk.mock.calls[0][0]).toEqual({ question: 'Câu một?' });
    expect(publicAsk.mock.calls[4][0]).toEqual({ question: 'Câu năm?', previousQuestions: ['Câu hai?', 'Câu ba?', 'Câu bốn?'] });
  });

  it('quá hạn mức mỗi phút (429): hiện lỗi của BE ngay trong khung, trang vẫn dùng được', async () => {
    for (let i = 0; i < 10; i++) await aiApi.ask({ question: `Câu hỏi thử ${i + 1}` });
    const user = renderMascot();
    const chat = await openChat(user);
    await ask(user, chat, 'Chó con tiêm phòng dại lúc mấy tuần?');
    expect(await within(chat).findByRole('alert')).toHaveTextContent('quá nhanh');
    expect(within(chat).getByRole('button', { name: 'Gửi câu hỏi' })).toBeDisabled(); // ô nhập đã xóa, chờ nhập mới
  });

  it('làm mới cuộc trò chuyện xóa lịch sử', async () => {
    const user = renderMascot();
    const chat = await openChat(user);
    await ask(user, chat, 'Giá vàng hôm nay là bao nhiêu?');
    await within(chat).findByText(/Mình chưa có thông tin cho câu hỏi này/);
    await user.click(within(chat).getByRole('button', { name: 'Làm mới cuộc trò chuyện' }));
    expect(within(chat).queryByText(/Giá vàng/)).not.toBeInTheDocument();
    expect(within(chat).getByText(/Cuộc trò chuyện đã được làm mới/)).toBeInTheDocument();
  });
});

describe('khách hàng đã đăng nhập (endpoint /me)', () => {
  beforeEach(() => login('khach@petcare.vn'));

  it('gọi /me/ai/chat; NEED_PET_CHOICE cho chọn thú rồi hiện lịch tiêm của thú đó (#3)', async () => {
    const publicAsk = vi.spyOn(aiApi, 'ask');
    const meAsk = vi.spyOn(aiApi, 'askAsCustomer');
    const summary = vi.spyOn(aiApi, 'getVaccinationSummary');
    const user = renderMascot();
    const chat = await openChat(user);
    await ask(user, chat, 'Lịch tiêm của bé nhà tôi');
    for (const pet of ['Mochi', 'Bim', 'Tôm']) expect(await within(chat).findByRole('button', { name: pet })).toBeInTheDocument();
    expect(meAsk).toHaveBeenCalledTimes(1);
    expect(publicAsk).not.toHaveBeenCalled();
    expect(summary).not.toHaveBeenCalled();

    await user.click(within(chat).getByRole('button', { name: 'Bim' }));
    expect(await within(chat).findByText(/không thay thế/i)).toBeInTheDocument();
    expect(summary).toHaveBeenCalledWith(expect.any(Number));
    expect(within(chat).getByText(/Lịch tiêm của Bim/)).toBeInTheDocument();
  });

  it('PERSONAL: lịch của chính mình trả bằng câu mẫu', async () => {
    const user = renderMascot();
    const chat = await openChat(user);
    await ask(user, chat, 'Sắp tới tôi có lịch hẹn không?');
    expect(await within(chat).findByText(/^Bạn (có|chưa có) [^]*lịch hẹn sắp tới/)).toBeInTheDocument();
    expect(within(chat).queryByRole('link', { name: 'Đăng nhập' })).not.toBeInTheDocument();
  });
});

describe('đổi tài khoản', () => {
  it('khách đăng xuất: câu trả lời cá nhân và câu hỏi trước biến mất, không gửi sang người khác (BR-AI-12)', async () => {
    await login('khach@petcare.vn');
    const publicAsk = vi.spyOn(aiApi, 'ask');
    const user = renderMascot();
    const chat = await openChat(user);
    await ask(user, chat, 'Sắp tới tôi có lịch hẹn không?');
    await within(chat).findByText(/^Bạn (có|chưa có) [^]*lịch hẹn sắp tới/);

    act(() => useSession.getState().clear());
    await waitFor(() => expect(within(chat).queryByText(/^Bạn (có|chưa có) [^]*lịch hẹn sắp tới/)).not.toBeInTheDocument());
    // Gợi ý nhanh cùng chữ vẫn nằm ở dưới khung; chỉ bong bóng tin nhắn của khách phải biến mất.
    expect(within(chat).queryAllByText('Sắp tới tôi có lịch hẹn không?').filter((el) => !el.closest('button'))).toHaveLength(0);

    await ask(user, chat, 'Chó con tiêm phòng dại lúc mấy tuần?');
    await within(chat).findByText(/^Nguồn:/);
    expect(publicAsk.mock.calls[0][0]).toEqual({ question: 'Chó con tiêm phòng dại lúc mấy tuần?' });
  });
});

describe('nhân viên đăng nhập', () => {
  it('bác sĩ dùng endpoint công khai (không bị 403 của /me)', async () => {
    await login('doctor@store1.vn');
    const publicAsk = vi.spyOn(aiApi, 'ask');
    const meAsk = vi.spyOn(aiApi, 'askAsCustomer');
    const user = renderMascot();
    const chat = await openChat(user);
    await ask(user, chat, 'Chó con tiêm phòng dại lúc mấy tuần?');
    expect(await within(chat).findByText(/^Nguồn:/)).toBeInTheDocument();
    expect(publicAsk).toHaveBeenCalledTimes(1);
    expect(meAsk).not.toHaveBeenCalled();
  });
});
