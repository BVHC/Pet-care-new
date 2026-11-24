import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { aiApi } from '../../shared/api/ai.api';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { useSession } from '../../shared/stores/session.store';
import { AdminAIPage } from './AdminAIPage';

// UC93, UC94 (SUPER_MANAGER chuoi@petcare.vn). Dữ liệu mẫu ai.seed.ts: 19 tài liệu — #9 và #12 DRAFT, #11 ARCHIVED, #8 PUBLISHED nhưng chỉ mục FAILED, #13–16 CATALOG_PROTOCOL (chỉ đọc),
// #17–19 đồng bộ từ bài viết (ARTICLE-8 DRAFT, ARTICLE-6 PUBLISHED, ARTICLE-10 ARCHIVED), còn lại PUBLISHED. Hồ sơ gợi ý: 4 dịch vụ / sản phẩm, trong đó 3 đã có hồ sơ; dịch vụ "Tắm & sấy" (#3) chưa có.

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
  it('liệt kê 19 tài liệu, mới sửa trước, kèm trạng thái và chỉ mục', async () => {
    renderPage();
    await docRow(T.draft);
    expect(within(screen.getByRole('table', { name: 'Tài liệu tri thức' })).getAllByRole('row')).toHaveLength(20);
    expect(within(await docRow(T.draft)).getByText('Nháp')).toBeInTheDocument();
    expect(within(await docRow(T.failed)).getByText('Nạp lỗi')).toBeInTheDocument();
    expect(within(await docRow(T.archived)).getByText('Đã lưu trữ')).toBeInTheDocument();
  });

  it('lọc theo trạng thái và tìm theo tiêu đề', async () => {
    const user = renderPage();
    await docRow(T.draft);
    await user.selectOptions(screen.getByLabelText('Lọc trạng thái'), 'DRAFT');
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Tài liệu tri thức' })).getAllByRole('row')).toHaveLength(4));
    await user.selectOptions(screen.getByLabelText('Lọc trạng thái'), '');
    await user.type(screen.getByLabelText('Tìm theo tiêu đề'), 'lưu trú');
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Tài liệu tri thức' })).getAllByRole('row')).toHaveLength(2));
    expect(screen.getByText(T.failed)).toBeInTheDocument();
  });

  // T93 (ADR-0134): tài liệu đồng bộ từ bài viết (external_id = ARTICLE-<id>) có huy hiệu và bộ lọc riêng; `false` phải tới được BE (axios giữ false).
  it('lọc nguồn bài viết: Từ bài viết chỉ còn 3 tài liệu ARTICLE-*, Không từ bài viết còn 16, về Tất cả thì đủ 19; huy hiệu chỉ ở tài liệu từ bài viết', async () => {
    const user = renderPage();
    const table = () => within(screen.getByRole('table', { name: 'Tài liệu tri thức' }));
    const rows = () => table().getAllByRole('row');
    await docRow(T.draft);
    expect(table().getAllByText('Từ bài viết')).toHaveLength(3); // phạm vi bảng: <option> cùng tên không tính
    expect(within(await docRow(T.draft)).queryByText('Từ bài viết')).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Lọc nguồn bài viết'), 'true');
    await waitFor(() => expect(rows()).toHaveLength(4));
    expect(table().getAllByText('Từ bài viết')).toHaveLength(3);
    expect(screen.queryByText(T.draft)).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Lọc nguồn bài viết'), 'false');
    await waitFor(() => expect(rows()).toHaveLength(17));
    expect(table().queryByText('Từ bài viết')).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText('Lọc nguồn bài viết'), '');
    await waitFor(() => expect(rows()).toHaveLength(20));
  });

  it('lọc nguồn bài viết ghép với trạng thái; không khớp thì câu trạng thái rỗng nhắc bộ lọc', async () => {
    const user = renderPage();
    await docRow(T.draft);
    await user.selectOptions(screen.getByLabelText('Lọc nguồn bài viết'), 'true');
    await user.selectOptions(screen.getByLabelText('Lọc trạng thái'), 'PUBLISHED');
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Tài liệu tri thức' })).getAllByRole('row')).toHaveLength(2));
    await user.type(screen.getByLabelText('Tìm theo tiêu đề'), 'không có bài nào như vậy');
    expect(await screen.findByText('Không có tài liệu nào khớp bộ lọc.')).toBeInTheDocument();
    await user.clear(screen.getByLabelText('Tìm theo tiêu đề'));
    await user.selectOptions(screen.getByLabelText('Lọc trạng thái'), '');
    await user.selectOptions(screen.getByLabelText('Lọc nguồn bài viết'), 'false');
    await user.type(screen.getByLabelText('Tìm theo tiêu đề'), 'Chuẩn bị đưa thú cưng');
    await waitFor(() => expect(within(screen.getByRole('table', { name: 'Tài liệu tri thức' })).getAllByRole('row')).toHaveLength(2));
  });

  it('bộ lọc nguồn là bộ lọc DUY NHẤT đang bật mà kho không có tài liệu từ bài viết: câu rỗng nhắc bộ lọc, không phải "Chưa có tài liệu nào"', async () => {
    localStorage.setItem('petcare-mock-ai', JSON.stringify({ documents: [{ documentId: 1, title: 'Tài liệu soạn tay duy nhất', status: 'DRAFT' }] }));
    const user = renderPage();
    await docRow('Tài liệu soạn tay duy nhất');
    await user.selectOptions(screen.getByLabelText('Lọc nguồn bài viết'), 'true');
    expect(await screen.findByText('Không có tài liệu nào khớp bộ lọc.')).toBeInTheDocument();
    expect(screen.queryByText('Chưa có tài liệu nào.')).not.toBeInTheDocument();
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

  it('nạp từ file (BR-AI-29): thiếu file / chủ đề / loài thì báo ngay; chọn file .md thì có bản nháp mới, chưa xuất bản', async () => {
    const user = renderPage();
    await docRow(T.draft);
    await user.click(screen.getByRole('button', { name: /Nạp từ file/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Nạp tài liệu từ file' });
    await user.click(within(dialog).getByRole('button', { name: 'Nạp file' }));
    expect(within(dialog).getByText('Chọn một file.')).toBeInTheDocument();
    expect(within(dialog).getByText('Chọn chủ đề.')).toBeInTheDocument();
    expect(within(dialog).getByText('Chọn loài áp dụng.')).toBeInTheDocument();

    const file = new File(['# Chải lông\n\nChải lông mỗi ngày.'], 'chai-long.md', { type: 'text/markdown' });
    await user.upload(within(dialog).getByLabelText('File'), file);
    await user.selectOptions(within(dialog).getByLabelText('Chủ đề'), 'HYGIENE');
    await user.selectOptions(within(dialog).getByLabelText('Loài áp dụng'), 'DOG');
    await user.click(within(dialog).getByRole('button', { name: 'Nạp file' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Đã nạp “chai-long.md” thành 1 bản nháp');
    expect(within(await docRow('chai-long')).getByText('Nháp')).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: 'Nạp tài liệu từ file' })).not.toBeInTheDocument();
  });

  it('nạp từ file: file sai định dạng bị chặn ngay ở giao diện; cỡ file KHÔNG còn kiểm cứng ở giao diện (trần là [CFG] của BE), lỗi 400 của BE hiện trong hộp thoại', async () => {
    const user = renderPage();
    await docRow(T.draft);
    await user.click(screen.getByRole('button', { name: /Nạp từ file/ }));
    const dialog = await screen.findByRole('dialog', { name: 'Nạp tài liệu từ file' });
    expect(within(dialog).getByText(/cỡ tối đa mặc định 2 MB, đổi được ở Cấu hình hệ thống/)).toBeInTheDocument();
    await user.selectOptions(within(dialog).getByLabelText('Chủ đề'), 'CARE');
    await user.selectOptions(within(dialog).getByLabelText('Loài áp dụng'), 'ALL');
    fireEvent.change(within(dialog).getByLabelText('File'), { target: { files: [new File(['x'], 'chay.exe')] } }); // user.upload lọc theo `accept` nên không chọn được .exe
    await user.click(within(dialog).getByRole('button', { name: 'Nạp file' }));
    expect(within(dialog).getByText('Chỉ nhận file .md, .txt, .docx, .pdf.')).toBeInTheDocument();
    const big = new File(['x'.repeat(2 * 1024 * 1024 + 1)], 'lon.txt');
    fireEvent.change(within(dialog).getByLabelText('File'), { target: { files: [big] } });
    await user.click(within(dialog).getByRole('button', { name: 'Nạp file' }));
    expect(await within(dialog).findByText(/File vượt quá tối đa 2048 kB/)).toBeInTheDocument();
    expect(within(dialog).queryByText(/File vượt quá 2 MB\./)).not.toBeInTheDocument();
  });

  it('nạp lại đúng file đã nạp: BE trả 409 BR-AI-30, hộp thoại giữ nguyên và nêu mã tài liệu đã có; không thêm nháp', async () => {
    const user = renderPage();
    await docRow(T.draft);
    const importOnce = async () => {
      await user.click(screen.getByRole('button', { name: /Nạp từ file/ }));
      const dialog = await screen.findByRole('dialog', { name: 'Nạp tài liệu từ file' });
      await user.upload(within(dialog).getByLabelText('File'), new File(['# Chải lông\n\nChải lông mỗi ngày.'], 'chai-long.md', { type: 'text/markdown' }));
      await user.selectOptions(within(dialog).getByLabelText('Chủ đề'), 'HYGIENE');
      await user.selectOptions(within(dialog).getByLabelText('Loài áp dụng'), 'DOG');
      await user.click(within(dialog).getByRole('button', { name: 'Nạp file' }));
      return dialog;
    };
    await importOnce();
    expect(await screen.findByRole('status')).toHaveTextContent('Đã nạp “chai-long.md” thành 1 bản nháp');
    const dialog = await importOnce();
    expect(await within(dialog).findByText(/File này đã được nạp trước đó thành tài liệu #20/)).toBeInTheDocument();
    expect(screen.getAllByText('chai-long')).toHaveLength(1);
  });

  it('tám chủ đề (thêm An toàn và cấp cứu, Lối sống) và mức rủi ro: tạo nháp có mức rủi ro thì danh sách hiện huy hiệu; tài liệu không nêu thì không có huy hiệu', async () => {
    const user = renderPage();
    await docRow(T.draft);
    expect(within(screen.getByLabelText('Lọc chủ đề')).getAllByRole('option').map((o) => o.textContent)).toEqual([
      'Mọi chủ đề', 'Chăm sóc', 'Dinh dưỡng', 'Hành vi', 'Vệ sinh', 'Tiêm phòng', 'Thông tin phòng khám', 'An toàn và cấp cứu', 'Lối sống',
    ]);
    expect(screen.queryByText(/^Rủi ro /)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Tạo tài liệu/ }));
    const form = await screen.findByRole('dialog', { name: 'Tạo tài liệu nháp' });
    await user.type(within(form).getByLabelText('Tiêu đề'), 'Sơ cứu khi chó bị ngộ độc');
    await user.selectOptions(within(form).getByLabelText('Chủ đề'), 'SAFETY');
    await user.selectOptions(within(form).getByLabelText('Loài áp dụng'), 'DOG');
    expect(within(form).getByLabelText('Mức rủi ro (tùy chọn)')).toHaveValue('');
    await user.selectOptions(within(form).getByLabelText('Mức rủi ro (tùy chọn)'), 'HIGH');
    await user.type(within(form).getByLabelText('Nội dung (Markdown)'), '## Việc cần làm ngay');
    await user.click(within(form).getByRole('button', { name: 'Lưu nháp' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Đã lưu bản nháp “Sơ cứu khi chó bị ngộ độc”');
    const row = await docRow('Sơ cứu khi chó bị ngộ độc');
    expect(within(row).getByText('Rủi ro cao')).toBeInTheDocument();
    expect(within(row).getByText(/An toàn và cấp cứu/)).toBeInTheDocument();
    expect(screen.getAllByText(/^Rủi ro /)).toHaveLength(1);
  });

  it('sửa mức rủi ro của tài liệu đã có: PATCH chỉ gửi mức rủi ro; mở lại thấy mức mới', async () => {
    const user = renderPage();
    const row = await docRow(T.draft);
    await user.click(within(row).getByRole('button', { name: /Sửa|Xem/ }));
    const form = await screen.findByRole('dialog', { name: 'Sửa tài liệu' });
    await user.selectOptions(within(form).getByLabelText('Mức rủi ro (tùy chọn)'), 'MODERATE');
    await user.click(within(form).getByRole('button', { name: 'Lưu thay đổi' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Đã lưu');
    expect(within(await docRow(T.draft)).getByText('Rủi ro trung bình')).toBeInTheDocument();
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
