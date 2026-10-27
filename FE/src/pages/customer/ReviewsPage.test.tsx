import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { feedbackApi } from '../../shared/api/feedback.api';
import { FEEDBACK_STATE_KEY } from '../../shared/api/mock/feedback.mock';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { SiteHeader } from '../../shared/components/layout/SiteHeader';
import { useSession } from '../../shared/stores/session.store';
import { ReviewsPage } from './ReviewsPage';

// Dữ liệu mẫu ở shared/api/mock/feedback.mock.ts, đồng hồ ghim ở thứ Ba 27/10/2026 10:00 giờ Việt Nam. Của khach@petcare.vn (khách 1):
// #3 NEW (chi nhánh 1, 3 sao, hôm qua 16:20) · #2 SEEN (WEBSITE, 4 sao, 24/10 20:40) · #1 RESOLVED (chi nhánh 1, 5 sao, 18/10 10:15,
// có ghi chú nội bộ "… (ghi chú nội bộ)" mà khách KHÔNG được thấy). Feedback #4, #5, #6 là của khách khác.
beforeAll(() => installMockApi(apiClient));

beforeEach(async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-10-27T10:00:00+07:00'));
  useSession.getState().clear();
  await signInAs('khach@petcare.vn');
});

afterEach(() => vi.useRealTimers());

const signInAs = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/review']}>
        <ReviewsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const historyList = () => screen.findByRole('list', { name: 'Góp ý đã gửi' });
const items = async () => within(await historyList()).getAllByRole('listitem');
const content = () => screen.getByLabelText('Nội dung góp ý') as HTMLTextAreaElement;
const send = (user: ReturnType<typeof userEvent.setup>) => user.click(screen.getByRole('button', { name: 'Gửi góp ý' }));
const validText = 'Nhân viên tư vấn nhiệt tình, phòng khám sạch sẽ.';
const validLength = [...validText.normalize('NFC')].length;

describe('trang Góp ý của tôi (UC83; BR-DG-01, 02, 04)', () => {
  it('có <h1> "Góp ý của tôi" và nói rõ góp ý không hiển thị công khai (BR-DG-02)', async () => {
    renderPage();
    expect(screen.getByRole('heading', { level: 1, name: 'Góp ý của tôi' })).toBeInTheDocument();
    expect(screen.getAllByText(/không hiển thị công khai/).length).toBeGreaterThan(0);
    await historyList();
  });

  describe('danh sách góp ý của tôi', () => {
    it('đang tải thì hiện khung chờ, xong thì thay bằng danh sách', async () => {
      renderPage();
      expect(screen.getByRole('status', { name: 'Đang tải góp ý' })).toBeInTheDocument();
      await historyList();
      expect(screen.queryByRole('status', { name: 'Đang tải góp ý' })).not.toBeInTheDocument();
    });

    it('mới nhất trước, kèm chủ đề, chi nhánh, điểm, giờ Việt Nam và huy hiệu "Mới gửi" / "Đã xem" / "Đã xử lý"', async () => {
      renderPage();
      const rows = await items();
      expect(rows).toHaveLength(3);

      expect(rows[0]).toHaveTextContent('Phòng chờ hơi đông vào cuối tuần');
      expect(within(rows[0]).getByText('Dịch vụ tại chi nhánh')).toBeInTheDocument();
      expect(within(rows[0]).getByText('PetCare Cầu Giấy')).toBeInTheDocument();
      expect(within(rows[0]).getByText('Mới gửi')).toBeInTheDocument();
      expect(within(rows[0]).getByRole('img', { name: 'Mức hài lòng 3 trên 5' })).toBeInTheDocument();
      expect(within(rows[0]).getByText('26/10/2026 16:20')).toBeInTheDocument();

      expect(rows[1]).toHaveTextContent('Trang đặt lịch dễ dùng');
      expect(within(rows[1]).getByText('Website')).toBeInTheDocument();
      expect(within(rows[1]).queryByText('PetCare Cầu Giấy')).not.toBeInTheDocument();
      expect(within(rows[1]).getByText('Đã xem')).toBeInTheDocument();
      expect(within(rows[1]).getByText('24/10/2026 20:40')).toBeInTheDocument();

      expect(rows[2]).toHaveTextContent('Bác sĩ khám rất kỹ');
      expect(within(rows[2]).getByText('Đã xử lý')).toBeInTheDocument();
      expect(within(rows[2]).getByRole('img', { name: 'Mức hài lòng 5 trên 5' })).toBeInTheDocument();
    });

    it('không hiện ghi chú nội bộ của feedback đã xử lý, cũng không hiện góp ý của khách khác (BR-DG-04, BR-DG-02)', async () => {
      const { container } = renderPage();
      await historyList();
      expect(container).not.toHaveTextContent('ghi chú nội bộ');
      expect(container).not.toHaveTextContent('Đã gọi lại cảm ơn khách');
      expect(container).not.toHaveTextContent('chờ lấy thuốc'); // feedback #4 của khách khác
      expect(container).not.toHaveTextContent('nhắc lịch tiêm phòng'); // feedback #5 của khách khác
    });

    it('xuống dòng trong nội dung được giữ nguyên khi hiện lại', async () => {
      localStorage.setItem(
        FEEDBACK_STATE_KEY,
        JSON.stringify({ feedbacks: [{ feedbackId: 1, customerId: 1, topic: 'OTHER', content: 'Dòng một của góp ý.\nDòng hai của góp ý.', status: 'NEW', createdAt: '2026-10-26T03:00:00.000Z' }] }),
      );
      renderPage();
      const paragraph = within((await items())[0]).getByText(/Dòng một của góp ý/);
      expect(paragraph.className).toContain('whitespace-pre-line');
    });

    it('chưa có góp ý nào: câu rỗng có nghĩa, không phải bảng trống', async () => {
      localStorage.setItem(FEEDBACK_STATE_KEY, JSON.stringify({ feedbacks: [] }));
      renderPage();
      expect(await screen.findByText('Bạn chưa gửi góp ý nào')).toBeInTheDocument();
      expect(screen.queryByRole('list', { name: 'Góp ý đã gửi' })).not.toBeInTheDocument();
    });

    it('lỗi tải: hiện câu của BE kèm nút "Thử lại", bấm thì tải lại được', async () => {
      vi.spyOn(feedbackApi, 'listMine').mockRejectedValueOnce(new ApiError('ACCESS_DENIED', 'Không có quyền thực hiện thao tác này', 403));
      const user = userEvent.setup();
      renderPage();
      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('Không tải được góp ý');
      expect(alert).toHaveTextContent('Không có quyền thực hiện thao tác này');

      await user.click(within(alert).getByRole('button', { name: 'Thử lại' }));
      expect(await items()).toHaveLength(3);
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });
  });

  describe('gửi góp ý', () => {
    it('hợp lệ: có thông báo thành công, danh sách làm mới (mục mới ở đầu, "Mới gửi"), form được làm sạch', async () => {
      const user = userEvent.setup();
      renderPage();
      await historyList();

      await user.selectOptions(screen.getByLabelText('Chi nhánh'), 'PetCare Cầu Giấy');
      await user.click(screen.getByRole('radio', { name: '4 sao' }));
      await user.type(content(), validText);
      expect(screen.getByText(`${validLength}/2000`)).toBeInTheDocument();
      await send(user);

      expect(await screen.findByText(/Đã gửi góp ý/)).toBeInTheDocument();
      await waitFor(async () => expect(await items()).toHaveLength(4));
      const [first] = await items();
      expect(first).toHaveTextContent(validText);
      expect(within(first).getByText('Mới gửi')).toBeInTheDocument();
      expect(within(first).getByRole('img', { name: 'Mức hài lòng 4 trên 5' })).toBeInTheDocument();
      expect(within(first).getByText('PetCare Cầu Giấy')).toBeInTheDocument();
      expect(within(first).getByText('27/10/2026 10:00')).toBeInTheDocument();

      // Form sạch: nội dung, chi nhánh, điểm, bộ đếm, chủ đề về mặc định.
      expect(content()).toHaveValue('');
      expect(screen.getByLabelText('Chi nhánh')).toHaveValue('');
      expect(screen.getByRole('radio', { name: '4 sao' })).not.toBeChecked();
      expect(screen.getByRole('radio', { name: 'Dịch vụ tại chi nhánh' })).toBeChecked();
      expect(screen.getByText('0/2000')).toBeInTheDocument();
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('chủ đề Website không cần chi nhánh, điểm không bắt buộc (gửi không kèm điểm)', async () => {
      const user = userEvent.setup();
      renderPage();
      await historyList();

      await user.click(screen.getByRole('radio', { name: 'Website' }));
      await user.type(content(), validText);
      await send(user);

      await waitFor(async () => expect(await items()).toHaveLength(4));
      const [first] = await items();
      expect(within(first).getByText('Website')).toBeInTheDocument();
      expect(within(first).queryByRole('img', { name: /Mức hài lòng/ })).not.toBeInTheDocument();
      expect(within(first).queryByText('PetCare Cầu Giấy')).not.toBeInTheDocument();
      // Gửi xong, chủ đề về mặc định (không giữ "Website" của lần trước).
      expect(screen.getByRole('radio', { name: 'Dịch vụ tại chi nhánh' })).toBeChecked();
      expect(screen.getByRole('radio', { name: 'Website' })).not.toBeChecked();
    });

    it('chọn điểm rồi "Bỏ chọn" thì gửi không kèm điểm', async () => {
      const user = userEvent.setup();
      renderPage();
      await historyList();

      await user.click(screen.getByRole('radio', { name: 'Website' }));
      await user.click(screen.getByRole('radio', { name: '2 sao' }));
      await user.click(screen.getByRole('button', { name: 'Bỏ chọn' }));
      expect(screen.getByRole('radio', { name: '2 sao' })).not.toBeChecked();
      expect(screen.queryByRole('button', { name: 'Bỏ chọn' })).not.toBeInTheDocument();
      await user.type(content(), validText);
      await send(user);

      await waitFor(async () => expect(await items()).toHaveLength(4));
      expect(within((await items())[0]).queryByRole('img', { name: /Mức hài lòng/ })).not.toBeInTheDocument();
    });

    it('thông báo thành công mất khi bắt đầu sửa góp ý tiếp theo', async () => {
      const user = userEvent.setup();
      renderPage();
      await historyList();
      await user.click(screen.getByRole('radio', { name: 'Website' }));
      await user.type(content(), validText);
      await send(user);
      expect(await screen.findByText(/Đã gửi góp ý/)).toBeInTheDocument();

      await user.type(content(), 'Góp ý tiếp');
      expect(screen.queryByText(/Đã gửi góp ý/)).not.toBeInTheDocument();
    });

    it('bộ đếm đếm theo code point: 9 emoji là 9, không phải 18; 10 emoji là 10', async () => {
      renderPage();
      await historyList();
      fireEvent.change(content(), { target: { value: '😀'.repeat(9) } });
      expect(screen.getByText('9/2000')).toBeInTheDocument();
      fireEvent.change(content(), { target: { value: '😀'.repeat(10) } });
      expect(screen.getByText('10/2000')).toBeInTheDocument();
    });

    it('bộ đếm bỏ khoảng trắng hai đầu như BE (NFC + strip)', async () => {
      renderPage();
      await historyList();
      fireEvent.change(content(), { target: { value: '   ngắn gọn   ' } });
      expect(screen.getByText('8/2000')).toBeInTheDocument();
    });

    describe('lỗi của BE hiện ngay trên form, nội dung đã nhập được giữ lại', () => {
      it('nội dung quá ngắn (BR-DG-01)', async () => {
        const user = userEvent.setup();
        renderPage();
        await historyList();
        await user.type(content(), 'ngắn');
        await send(user);

        const alert = await screen.findByRole('alert');
        expect(alert).toHaveTextContent('Nội dung feedback phải từ 10 đến 2000 ký tự');
        expect(alert).not.toHaveTextContent('BR-DG-01');
        expect(content()).toHaveValue('ngắn');
        expect(screen.queryByText(/Đã gửi góp ý/)).not.toBeInTheDocument();
        expect(await items()).toHaveLength(3);
      });

      it('chủ đề dịch vụ tại chi nhánh mà chưa chọn chi nhánh (BR-DG-01)', async () => {
        const user = userEvent.setup();
        renderPage();
        await historyList();
        await user.type(content(), validText);
        await send(user);

        expect(await screen.findByRole('alert')).toHaveTextContent('Vui lòng chọn chi nhánh khi góp ý về dịch vụ tại chi nhánh');
        expect(content()).toHaveValue(validText);
      });

      it('chi nhánh vừa ngừng hoạt động: 404 của BE', async () => {
        vi.spyOn(feedbackApi, 'submit').mockRejectedValueOnce(new ApiError('RESOURCE_NOT_FOUND', 'Không tìm thấy chi nhánh #1', 404));
        const user = userEvent.setup();
        renderPage();
        await historyList();
        await user.selectOptions(screen.getByLabelText('Chi nhánh'), 'PetCare Cầu Giấy');
        await user.type(content(), validText);
        await send(user);

        expect(await screen.findByRole('alert')).toHaveTextContent('Không tìm thấy chi nhánh #1');
        expect(screen.getByLabelText('Chi nhánh')).toHaveValue('1');
      });

      it('quá giới hạn trong ngày: câu của BE kèm thời điểm gửi tiếp (BR-DG-01)', async () => {
        for (let i = 0; i < 5; i++) await feedbackApi.submit({ topic: 'WEBSITE', content: `Góp ý số ${i} của khách hàng.` });
        const user = userEvent.setup();
        renderPage();
        await historyList();
        await user.click(screen.getByRole('radio', { name: 'Website' }));
        await user.type(content(), validText);
        await send(user);

        const alert = await screen.findByRole('alert');
        expect(alert).toHaveTextContent('Bạn đã gửi đủ 5 feedback hôm nay, có thể gửi tiếp từ 00:00 ngày 28/10/2026');
        expect(content()).toHaveValue(validText);
        expect(await items()).toHaveLength(8);
      });

      it('hồ sơ còn chờ quyết định liên kết (BR-TK-19)', async () => {
        await signInAs('khachmoi@petcare.vn');
        const user = userEvent.setup();
        renderPage();
        expect(await screen.findByText('Bạn chưa gửi góp ý nào')).toBeInTheDocument();
        await user.click(screen.getByRole('radio', { name: 'Website' }));
        await user.type(content(), validText);
        await send(user);

        expect(await screen.findByRole('alert')).toHaveTextContent('Hồ sơ của bạn đang chờ quyết định liên kết với hồ sơ tại phòng khám');
        expect(content()).toHaveValue(validText);
      });
    });
  });
});

describe('mục "Góp ý của tôi" trong menu người dùng ở header', () => {
  it('đổi nhãn từ "Đánh giá dịch vụ" thành "Góp ý của tôi" và vẫn dẫn tới trang review', async () => {
    const onNav = vi.fn();
    const user = userEvent.setup();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <SiteHeader currentPage="home" onNav={onNav} />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await user.click(await screen.findByRole('button', { name: /Nguyễn Thu Trang/ }));

    expect(screen.queryByRole('button', { name: 'Đánh giá dịch vụ' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Góp ý của tôi' }));
    expect(onNav).toHaveBeenCalledWith('review');
  });
});
