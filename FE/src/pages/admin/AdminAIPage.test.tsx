import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { aiApi } from '../../shared/api/ai.api';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AdminAIPage } from './AdminAIPage';

// UC93, UC94 (SUPER_MANAGER chuoi@petcare.vn). Dữ liệu mẫu ai.seed.ts: 16 tài liệu — #9 và #12 DRAFT, #11 ARCHIVED, #8 PUBLISHED nhưng chỉ mục FAILED, #13–16 CATALOG_PROTOCOL (chỉ đọc),
// còn lại PUBLISHED. Hồ sơ gợi ý: 4 dịch vụ / sản phẩm, trong đó 3 đã có hồ sơ; dịch vụ "Tắm & sấy" (#3) chưa có.

beforeAll(() => installMockApi(apiClient));
const login = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};
beforeEach(() => login('chuoi@petcare.vn'));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AdminAIPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return userEvent.setup();
}

const T = { draft: 'Chó sủa nhiều và lo âu khi xa chủ', failed: 'Chuẩn bị đưa thú cưng đi lưu trú', archived: 'Chăm sóc mèo lớn tuổi', published: 'Chăm sóc chó con trong những tuần đầu' };
const docRow = async (title: string) => (await screen.findByText(title)).closest('tr')!;
const buttonNames = (row: HTMLElement) => within(row).getAllByRole('button').map((b) => b.getAttribute('aria-label'));

describe('tab Tài liệu tri thức', () => {
  it('liệt kê 16 tài liệu, mới sửa trước, kèm trạng thái và chỉ mục', async () => {
    renderPage();
    await docRow(T.draft);
    expect(within(screen.getByRole('table', { name: 'Tài liệu tri thức' })).getAllByRole('row')).toHaveLength(17);
    expect(within(await docRow(T.draft)).getByText('Nháp')).toBeInTheDocument();
    expect(within(await docRow(T.failed)).getByText('Nạp lỗi')).toBeInTheDocument();
    expect(within(await docRow(T.archived)).getByText('Đã lưu trữ')).toBeInTheDocument();
  });

  it('lọc theo trạng thái và tìm theo tiêu đề', async () => {
    const user = renderPage();
    await docRow(T.draft);
    await user.selectOptions(screen.getByLabelText('Lọc trạng thái'), 'DRAFT');
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Tài liệu tri thức' })).getAllByRole('row')).toHaveLength(3));
    await user.selectOptions(screen.getByLabelText('Lọc trạng thái'), '');
    await user.type(screen.getByLabelText('Tìm theo tiêu đề'), 'lưu trú');
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Tài liệu tri thức' })).getAllByRole('row')).toHaveLength(2));
    expect(screen.getByText(T.failed)).toBeInTheDocument();
  });

  it('nút theo trạng thái: nháp xuất bản / lưu trữ; đã xuất bản lưu trữ; lỗi chỉ mục có Nạp lại; lưu trữ xuất bản lại; phác đồ chỉ xem', async () => {
    renderPage();
    expect(buttonNames(await docRow(T.draft))).toEqual([`Sửa ${T.draft}`, `Xuất bản ${T.draft}`, `Lưu trữ ${T.draft}`]);
    expect(buttonNames(await docRow(T.published))).toEqual([`Sửa ${T.published}`, `Lưu trữ ${T.published}`]);
    expect(buttonNames(await docRow(T.failed))).toEqual([`Sửa ${T.failed}`, `Lưu trữ ${T.failed}`, `Nạp lại chỉ mục ${T.failed}`]);
    expect(buttonNames(await docRow(T.archived))).toEqual([`Xem ${T.archived}`, `Xuất bản ${T.archived}`]);
    const catalog = (await screen.findAllByText(/^Lịch tiêm .* cho /))[0].closest('tr')!;
    expect(within(catalog).getAllByRole('button')).toHaveLength(1);
    expect(within(catalog).getByRole('button', { name: /^Xem / })).toBeInTheDocument();
  });

  it('tạo nháp: thiếu trường thì báo ngay; đủ thì có bản nháp mới trong danh sách', async () => {
    const user = renderPage();
    await docRow(T.draft);
    await user.click(screen.getByRole('button', { name: /Tạo tài liệu/ }));
    const form = await screen.findByRole('dialog', { name: 'Tạo tài liệu nháp' });
    await user.click(within(form).getByRole('button', { name: 'Lưu nháp' }));
    expect(within(form).getByText('Nhập tiêu đề.')).toBeInTheDocument();
    expect(within(form).getByText('Chọn chủ đề.')).toBeInTheDocument();

    await user.type(within(form).getByLabelText('Tiêu đề'), 'Cách cho mèo uống thuốc');
    await user.selectOptions(within(form).getByLabelText('Chủ đề'), 'CARE');
    await user.selectOptions(within(form).getByLabelText('Loài áp dụng'), 'CAT');
    await user.type(within(form).getByLabelText('Nội dung (Markdown)'), '## Mẹo');
    await user.click(within(form).getByRole('button', { name: 'Lưu nháp' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Đã lưu bản nháp “Cách cho mèo uống thuốc”');
    expect(within(await docRow('Cách cho mèo uống thuốc')).getByText('Nháp')).toBeInTheDocument();
  });

  it('xuất bản nháp thiếu nguồn và người rà soát: BE chặn (BR-AI-14), thông báo liệt kê phần thiếu ngay trên trang', async () => {
    const user = renderPage();
    await user.click(within(await docRow(T.draft)).getByRole('button', { name: /^Xuất bản/ }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('ghi chú nguồn');
    expect(alert).toHaveTextContent('người rà soát');
    expect(within(await docRow(T.draft)).getByText('Nháp')).toBeInTheDocument();
  });

  it('điền nguồn + người + ngày rà soát rồi xuất bản: tài liệu thành Đã xuất bản', async () => {
    const user = renderPage();
    await user.click(within(await docRow(T.draft)).getByRole('button', { name: /^Sửa/ }));
    const form = await screen.findByRole('dialog', { name: 'Sửa tài liệu' });
    await user.type(await within(form).findByLabelText('Ghi chú nguồn (lớp 1)'), 'AAHA Canine Behavior Guidelines');
    await user.type(within(form).getByLabelText('Bác sĩ thú y rà soát (lớp 2)'), 'BS. Minh Anh');
    await user.type(within(form).getByLabelText('Ngày rà soát'), '2026-11-05');
    await user.click(within(form).getByRole('button', { name: 'Lưu thay đổi' }));
    await screen.findByText(/Đã lưu “Chó sủa nhiều/);

    await user.click(within(await docRow(T.draft)).getByRole('button', { name: /^Xuất bản/ }));
    expect(await screen.findByText(/Đã xuất bản “Chó sủa nhiều/)).toBeInTheDocument();
    await waitFor(() => expect(within(screen.getByText(T.draft).closest('tr')!).getByText('Đã xuất bản')).toBeInTheDocument());
  });

  it('sửa nội dung tài liệu đã xuất bản: bắt buộc nhập lại người và ngày rà soát, lưu thì tăng phiên bản', async () => {
    const user = renderPage();
    await user.click(within(await docRow(T.published)).getByRole('button', { name: /^Sửa/ }));
    const form = await screen.findByRole('dialog', { name: 'Sửa tài liệu' });
    const content = await within(form).findByLabelText('Nội dung (Markdown)');
    await user.type(content, '\nBổ sung một dòng.');
    const reviewer = within(form).getByLabelText('Bác sĩ thú y rà soát (lớp 2)');
    await user.clear(reviewer);
    await user.click(within(form).getByRole('button', { name: 'Lưu thay đổi' }));
    expect(within(form).getByText('Nhập tên bác sĩ thú y rà soát.')).toBeInTheDocument();

    await user.type(reviewer, 'BS. Minh Anh');
    await user.click(within(form).getByRole('button', { name: 'Lưu thay đổi' }));
    expect(await screen.findByRole('status')).toHaveTextContent(/phiên bản 2/);
  });

  it('lưu trữ tài liệu đang xuất bản: chatbot ngừng dùng ngay; chuyển sang Đã lưu trữ', async () => {
    const user = renderPage();
    await user.click(within(await docRow(T.published)).getByRole('button', { name: /^Lưu trữ/ }));
    expect(await screen.findByRole('status')).toHaveTextContent('Chatbot ngừng dùng tài liệu này ngay');
    await waitFor(() => expect(within(screen.getByText(T.published).closest('tr')!).getByText('Đã lưu trữ')).toBeInTheDocument());
  });

  it('tài liệu đã bị người khác lưu trữ sau khi mở trang: bấm Lưu trữ nhận 409 ngay trên trang', async () => {
    const user = renderPage();
    const row = await docRow(T.published);
    const id = Number(/#(\d+)/.exec(row.textContent!)![1]);
    await aiApi.archiveDocument(id);
    await user.click(within(row).getByRole('button', { name: /^Lưu trữ/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/ARCHIVED/);
  });

  it('nạp lại chỉ mục khi FAILED: nút biến mất sau khi mock nạp xong', async () => {
    const user = renderPage();
    await user.click(within(await docRow(T.failed)).getByRole('button', { name: /^Nạp lại chỉ mục/ }));
    expect(await screen.findByRole('status')).toHaveTextContent('Đã yêu cầu nạp lại chỉ mục');
    await waitFor(() => expect(within(screen.getByText(T.failed).closest('tr')!).queryByRole('button', { name: /^Nạp lại chỉ mục/ })).not.toBeInTheDocument());
  });

  it('tài liệu phác đồ tiêm: mở xem được, mọi ô khóa, không có nút lưu', async () => {
    const user = renderPage();
    const row = (await screen.findAllByText(/^Lịch tiêm .* cho /))[0].closest('tr')!;
    await user.click(within(row).getByRole('button', { name: /^Xem / }));
    const dialog = await screen.findByRole('dialog', { name: 'Xem tài liệu' });
    expect(await within(dialog).findByText(/chỉ xem \(BR-AI-13\)/)).toBeInTheDocument();
    expect(within(dialog).getByLabelText('Tiêu đề')).toBeDisabled();
    expect(within(dialog).queryByRole('button', { name: /Lưu/ })).not.toBeInTheDocument();
  });
});

describe('tab Hồ sơ gợi ý', () => {
  const openTab = async (user: ReturnType<typeof renderPage>) => {
    await user.click(screen.getByRole('tab', { name: 'Hồ sơ gợi ý' }));
    return screen.findByRole('table', { name: 'Hồ sơ gợi ý' });
  };
  const rowOf = async (name: string) => (await screen.findByText(name)).closest('tr')!;

  it('liệt kê dịch vụ và sản phẩm kèm tóm tắt hồ sơ; lọc "chỉ mục chưa có hồ sơ" còn đúng mục chưa lập', async () => {
    const user = renderPage();
    const table = await openTab(user);
    await within(table).findByText('Cắt tỉa lông');
    expect(within(await rowOf('Cắt tỉa lông')).getByText('Đã có hồ sơ')).toBeInTheDocument();
    expect(within(await rowOf('Cắt tỉa lông')).getByText(/Chó · từ 12 tuần · từ 5 kg · poodle, phoc soc, shih tzu · dùng lại sau 45 ngày/)).toBeInTheDocument();
    expect(within(await rowOf('Tắm & sấy')).getByText('Chưa có hồ sơ')).toBeInTheDocument();

    await user.click(screen.getByLabelText('Chỉ mục chưa có hồ sơ'));
    await waitFor(() => expect(screen.queryByText('Cắt tỉa lông')).not.toBeInTheDocument());
    expect(screen.getByText('Tắm & sấy')).toBeInTheDocument();
  });

  it('lập hồ sơ cho mục chưa có: lưu thì thành "Đã có hồ sơ"; từ khóa giống chuẩn hóa thường không dấu', async () => {
    const user = renderPage();
    await openTab(user);
    await user.click(within(await rowOf('Tắm & sấy')).getByRole('button', { name: /Lập hồ sơ/ }));
    const form = await screen.findByRole('dialog', { name: /Hồ sơ gợi ý: Tắm & sấy/ });
    await user.selectOptions(within(form).getByLabelText('Loài'), 'CAT');
    await user.type(within(form).getByLabelText('Tuổi tối thiểu (tuần)'), '8');
    await user.type(within(form).getByLabelText('Từ khóa giống'), 'Anh Lông Ngắn, Ba Tư');
    await user.click(within(form).getByRole('button', { name: 'Lưu hồ sơ' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Đã lưu hồ sơ gợi ý của “Tắm & sấy”');
    const row = await rowOf('Tắm & sấy');
    await waitFor(() => expect(within(row).getByText('Đã có hồ sơ')).toBeInTheDocument());
    expect(within(row).getByText(/Mèo · từ 8 tuần · anh long ngan, ba tu/)).toBeInTheDocument();
  });

  it('tuổi tối thiểu lớn hơn tối đa: BE từ chối, lỗi hiện ngay dưới ô và form vẫn mở', async () => {
    const user = renderPage();
    await openTab(user);
    await user.click(within(await rowOf('Tắm & sấy')).getByRole('button', { name: /Lập hồ sơ/ }));
    const form = await screen.findByRole('dialog', { name: /Hồ sơ gợi ý/ });
    await user.type(within(form).getByLabelText('Tuổi tối thiểu (tuần)'), '30');
    await user.type(within(form).getByLabelText('Tuổi tối đa (tuần)'), '10');
    await user.click(within(form).getByRole('button', { name: 'Lưu hồ sơ' }));
    expect(await within(form).findByText(/không được lớn hơn tuổi tối đa/)).toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: /Hồ sơ gợi ý/ })).toBeInTheDocument();
  });

  it('xóa hồ sơ đã có: gửi mọi trường rỗng, mục trở lại "Chưa có hồ sơ"', async () => {
    const user = renderPage();
    await openTab(user);
    await user.click(within(await rowOf('Cắt tỉa lông')).getByRole('button', { name: /Sửa hồ sơ/ }));
    const form = await screen.findByRole('dialog', { name: /Hồ sơ gợi ý/ });
    await user.click(within(form).getByRole('button', { name: 'Xóa hồ sơ' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Đã xóa hồ sơ gợi ý của “Cắt tỉa lông”');
    await waitFor(() => expect(within(screen.getByText('Cắt tỉa lông').closest('tr')!).getByText('Chưa có hồ sơ')).toBeInTheDocument());
  });
});

describe('vai trò', () => {
  it('không phải SUPER_MANAGER (lễ tân): BE từ chối 403, danh sách báo lỗi, không có nút xuất bản / lưu trữ / sửa nào', async () => {
    await login('reception@store1.vn');
    renderPage();
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Xuất bản|^Lưu trữ|^Sửa/ })).not.toBeInTheDocument();
  });
});
