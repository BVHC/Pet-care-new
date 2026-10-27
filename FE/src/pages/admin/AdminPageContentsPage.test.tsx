import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../shared/api/api-error';
import { apiClient } from '../../shared/api/axios';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { PAGES_STATE_KEY } from '../../shared/api/mock/page-content.mock';
import { pageContentApi } from '../../shared/api/page-content.api';
import {
  PAGE_CONTENT_MAX_BYTES,
  PAGE_DEFAULTS,
  PAGE_LIMITS,
  pageContentBytes,
  parsePageContent,
} from '../../shared/constants/page-content';
import { useSession } from '../../shared/stores/session.store';
import type { PageKey } from '../../shared/types/page-content';
import { AdminPageContentsPage } from './AdminPageContentsPage';

// Dữ liệu mẫu ở shared/api/mock/page-content.mock.ts: cả 4 trang bắt đầu bằng `{}` (đang dùng nội dung mặc định trong mã, PAGE_DEFAULTS).
// Mặc định: HOME_BANNER ctaLabel "Đặt Lịch Ngay", FAQ 4 câu hỏi, POLICY mỗi văn bản 7 mục.
// Ca "tối đa 20 mục" bấm thêm 20 lần (~3,7 giây một mình): chạy cả bộ song song thì vượt 5 giây mặc định và đỏ chập chờn (T49 gặp ở lần chạy thứ 4).
vi.setConfig({ testTimeout: 20_000 });
beforeAll(() => installMockApi(apiClient));

beforeEach(async () => {
  useSession.getState().clear();
  await useSession.getState().login({ email: 'chuoi@petcare.vn', password: MOCK_PASSWORD });
});

afterEach(() => vi.restoreAllMocks());

const DEFAULT_STRIP = 'Trang đang dùng nội dung mặc định trong mã; sửa rồi Lưu để ghi vào hệ thống.';
const MAX_KB = PAGE_CONTENT_MAX_BYTES / 1024;

function Where() {
  return <p data-testid="where">{useLocation().search}</p>;
}

function renderAt(search = '') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/admin/page-contents${search}`]}>
        <Routes>
          <Route
            path="/admin/page-contents"
            element={
              <>
                <AdminPageContentsPage />
                <Where />
              </>
            }
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...view, client };
}

/** Ghi sẵn nội dung đã lưu của một số khóa vào state của mock (các khóa còn lại giữ seed `{}`). */
function seedPages(pages: Partial<Record<PageKey, unknown>>) {
  const state = Object.fromEntries(
    Object.entries(pages).map(([key, content]) => [key, { key, title: null, content, updatedAt: '2026-10-20T09:00:00+07:00' }]),
  );
  localStorage.setItem(PAGES_STATE_KEY, JSON.stringify(state));
}

/** Trang công khai đọc qua mock, đúng đường `usePublicPage` dùng. */
async function publicPage<K extends PageKey>(key: K) {
  const res = await apiClient.get(`/api/public/pages/${key}`);
  return { raw: res.data.data.content as Record<string, unknown>, parsed: parsePageContent(key, res.data.data.content) };
}

const saveButton = () => screen.getByRole('button', { name: /^(Lưu thay đổi|Đang lưu…)$/ });
const tab = (name: RegExp | string) => screen.getByRole('tab', { name });
const group = (name: string, parent?: HTMLElement) => (parent ? within(parent) : screen).getByRole('group', { name });
const putBody = (put: { mock: { calls: unknown[][] } }, index = 0) => put.mock.calls[index]![1] as { content: Record<string, unknown> };

/** Chờ form hiện (mặc định tab đầu) rồi trả ô theo nhãn. */
const findField = (label: string, parent?: HTMLElement) => (parent ? within(parent) : screen).findByLabelText(label);

async function replaceText(user: ReturnType<typeof userEvent.setup>, el: HTMLElement, text: string) {
  await user.clear(el);
  if (text) await user.type(el, text);
}

describe('khung trang và tab', () => {
  it('giữ h1, có 4 tab đúng thứ tự, mở ở tab đầu và đổi tab qua URL ?trang=', async () => {
    const user = userEvent.setup();
    renderAt();
    expect(screen.getByRole('heading', { level: 1, name: 'Nội dung trang' })).toBeInTheDocument();
    await findField('Chữ trên nút');
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual([
      'Banner trang chủ',
      'Giới thiệu',
      'Chính sách',
      'Câu hỏi thường gặp',
    ]);
    expect(tab('Banner trang chủ')).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('where').textContent).toBe('');

    await user.click(tab('Câu hỏi thường gặp'));
    expect(screen.getByTestId('where')).toHaveTextContent('?trang=FAQ');
    expect(tab('Câu hỏi thường gặp')).toHaveAttribute('aria-selected', 'true');
    expect(group('Câu hỏi 1')).toBeInTheDocument();
  });

  it.each([
    ['ABOUT', 'Giới thiệu', 'Giá trị cốt lõi'],
    ['POLICY', 'Chính sách', 'Điều khoản sử dụng'],
    ['FAQ', 'Câu hỏi thường gặp', 'Danh sách câu hỏi'],
  ])('mở thẳng tab %s từ URL', async (key, label, section) => {
    renderAt(`?trang=${key}`);
    expect(await screen.findByRole('group', { name: section })).toBeInTheDocument();
    expect(tab(label)).toHaveAttribute('aria-selected', 'true');
  });

  it('khóa sai trên URL thì rơi về tab đầu', async () => {
    renderAt('?trang=KHONG_CO');
    await findField('Chữ trên nút');
    expect(tab('Banner trang chủ')).toHaveAttribute('aria-selected', 'true');
  });

  it('đang tải: có h1 và khung chờ, chưa có tab', async () => {
    renderAt();
    expect(screen.getByRole('heading', { level: 1, name: 'Nội dung trang' })).toBeInTheDocument();
    expect(screen.getByRole('status', { name: 'Đang tải' })).toBeInTheDocument();
    expect(screen.queryByRole('tab')).not.toBeInTheDocument();
    await findField('Chữ trên nút');
    expect(screen.queryByRole('status', { name: 'Đang tải' })).not.toBeInTheDocument();
  });

  it('lỗi tải: báo lỗi, vẫn có h1, bấm Thử lại thì hiện form', async () => {
    const user = userEvent.setup();
    vi.spyOn(pageContentApi, 'list').mockRejectedValueOnce(new ApiError('INTERNAL_ERROR', 'Máy chủ đang gặp sự cố. Vui lòng thử lại sau.', 500));
    renderAt();
    expect(await screen.findByText('Không tải được nội dung trang')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Nội dung trang' })).toBeInTheDocument();
    expect(screen.queryByRole('tab')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    await findField('Chữ trên nút');
    expect(screen.queryByText('Không tải được nội dung trang')).not.toBeInTheDocument();
  });
});

describe('nội dung mặc định', () => {
  it('trang mới (`{}`): form nạp nội dung mặc định, hiện dải thông báo, chưa cho lưu hay hoàn tác', async () => {
    renderAt();
    expect(await findField('Tiêu đề')).toHaveValue(PAGE_DEFAULTS.HOME_BANNER.headline);
    expect(screen.getByLabelText('Chữ trên nút')).toHaveValue('Đặt Lịch Ngay');
    expect(screen.getByText(DEFAULT_STRIP)).toBeInTheDocument();
    expect(screen.getByText('Chỉ nhập văn bản thuần: thẻ HTML sẽ hiện thành chữ.')).toBeInTheDocument();
    expect(saveButton()).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Hoàn tác thay đổi' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Khôi phục nội dung mặc định' })).toBeDisabled();
    expect(tab(/Banner trang chủ/).textContent).not.toContain('Chưa lưu');
  });

  it('nội dung đã lưu hợp lệ: nạp đúng chữ đã lưu và không có dải mặc định', async () => {
    seedPages({ HOME_BANNER: { ...PAGE_DEFAULTS.HOME_BANNER, ctaLabel: 'Hẹn khám ngay' } });
    renderAt();
    expect(await findField('Chữ trên nút')).toHaveValue('Hẹn khám ngay');
    expect(screen.queryByText(DEFAULT_STRIP)).not.toBeInTheDocument();
    // Có nội dung riêng thì cho khôi phục về mặc định.
    expect(screen.getByRole('button', { name: 'Khôi phục nội dung mặc định' })).toBeEnabled();
  });

  it.each([
    ['sai kiểu', { HOME_BANNER: { headline: 123 } }],
    ['thiếu trường', { HOME_BANNER: { headline: 'Chỉ có tiêu đề' } }],
    ['quá giới hạn', { HOME_BANNER: { ...PAGE_DEFAULTS.HOME_BANNER, ctaLabel: 'x'.repeat(PAGE_LIMITS.ctaLabel + 1) } }],
    ['không phải object', { HOME_BANNER: ['a'] }],
  ])('state localStorage hình dạng cũ (%s): trang không vỡ, dùng mặc định kèm dải thông báo', async (_name, pages) => {
    localStorage.setItem(PAGES_STATE_KEY, JSON.stringify(pages));
    renderAt();
    expect(await findField('Chữ trên nút')).toHaveValue('Đặt Lịch Ngay');
    expect(screen.getByText(DEFAULT_STRIP)).toBeInTheDocument();
  });

  it('localStorage hỏng hẳn: trang không vỡ', async () => {
    localStorage.setItem(PAGES_STATE_KEY, '{không phải json');
    renderAt();
    expect(await findField('Chữ trên nút')).toHaveValue('Đặt Lịch Ngay');
  });
});

describe('sửa và lưu', () => {
  it('sửa một trường thì bật Lưu; bộ đếm theo số ký tự tiếng Việt có dấu, bỏ khoảng trắng hai đầu', async () => {
    const user = userEvent.setup();
    renderAt();
    const cta = await findField('Chữ trên nút');
    expect(cta).toHaveAccessibleDescription(/\b13\/24\b/); // "Đặt Lịch Ngay"
    expect(saveButton()).toBeDisabled();

    await replaceText(user, cta, '  Đặt lịch ngay  ');
    expect(cta).toHaveAccessibleDescription(/\b13\/24\b/);
    expect(saveButton()).toBeEnabled();
    expect(screen.getByText('Có thay đổi chưa lưu')).toBeInTheDocument();
  });

  it('lưu thành công: PUT đúng thân (không có title, đã bỏ khoảng trắng), dải mặc định biến mất, trang công khai đọc được chữ mới', async () => {
    const user = userEvent.setup();
    const put = vi.spyOn(apiClient, 'put');
    renderAt();
    const cta = await findField('Chữ trên nút');
    await replaceText(user, cta, '  Đặt lịch ngay  ');
    await user.click(saveButton());

    await waitFor(() => expect(put).toHaveBeenCalledTimes(1));
    expect(put.mock.calls[0]![0]).toBe('/api/page-contents/HOME_BANNER');
    const body = putBody(put);
    expect(Object.keys(body)).toEqual(['content']); // không gửi title
    expect(body).toStrictEqual({ content: { ...PAGE_DEFAULTS.HOME_BANNER, ctaLabel: 'Đặt lịch ngay' } });

    await waitFor(() => expect(screen.queryByText(DEFAULT_STRIP)).not.toBeInTheDocument());
    expect(screen.getByText(/Đã lưu “Banner trang chủ”/)).toBeInTheDocument();
    expect(saveButton()).toBeDisabled();
    expect(cta).toHaveValue('Đặt lịch ngay'); // form nạp lại từ bản đã lưu (đã trim)
    expect(tab(/Banner trang chủ/).textContent).not.toContain('Chưa lưu');

    const pub = await publicPage('HOME_BANNER');
    expect(pub.raw.ctaLabel).toBe('Đặt lịch ngay');
    expect(pub.parsed.ctaLabel).toBe('Đặt lịch ngay'); // đúng dạng, không bị trang công khai thay bằng mặc định
  });

  it('HTML gõ vào được lưu như chữ, không bị lọc và không thành thẻ', async () => {
    const user = userEvent.setup();
    const put = vi.spyOn(apiClient, 'put');
    renderAt('?trang=FAQ');
    const item = await screen.findByRole('group', { name: 'Câu hỏi 1' });
    const html = '<b>đậm</b> <script>alert(1)</script> &amp; <img src=x onerror=1>';
    fireEvent.change(within(item).getByLabelText('Câu trả lời'), { target: { value: html } });
    await user.click(saveButton());

    await waitFor(() => expect(put).toHaveBeenCalledTimes(1));
    const items = putBody(put).content.items as { answer: string }[];
    expect(items[0]!.answer).toBe(html);
    expect((await publicPage('FAQ')).parsed.items[0]!.answer).toBe(html);
    await waitFor(() => expect(saveButton()).toBeDisabled());
    expect(within(group('Câu hỏi 1')).getByLabelText('Câu trả lời')).toHaveValue(html);
    expect(document.querySelector('b, script, img')).toBeNull();
  });

  it('tab Giới thiệu: sửa tên một giá trị và một số liệu, PUT đúng cấu trúc {stats, values}', async () => {
    const user = userEvent.setup();
    const put = vi.spyOn(apiClient, 'put');
    renderAt('?trang=ABOUT');
    const stat2 = await screen.findByRole('group', { name: 'Số liệu 2' });
    await replaceText(user, within(stat2).getByLabelText('Con số'), '20+');
    await replaceText(user, within(group('Giá trị 3')).getByLabelText('Tên giá trị'), 'Tận tâm');
    await user.click(saveButton());

    await waitFor(() => expect(put).toHaveBeenCalledTimes(1));
    expect(put.mock.calls[0]![0]).toBe('/api/page-contents/ABOUT');
    const expected = structuredClone(PAGE_DEFAULTS.ABOUT);
    expected.stats[1]!.value = '20+';
    expected.values[2]!.title = 'Tận tâm';
    expect(putBody(put)).toStrictEqual({ content: expected });
    expect((await publicPage('ABOUT')).parsed.values[2]!.title).toBe('Tận tâm');
  });

  it('tab Banner: sửa số liệu nổi bật và một số trong dải, PUT đúng cấu trúc', async () => {
    const user = userEvent.setup();
    const put = vi.spyOn(apiClient, 'put');
    renderAt();
    const hero = await screen.findByRole('group', { name: 'Số liệu nổi bật trên banner' });
    await replaceText(user, within(hero).getByLabelText('Con số'), '3.000+');
    await replaceText(user, within(group('Số liệu 4')).getByLabelText('Nhãn'), 'Hài lòng');
    await user.click(saveButton());

    await waitFor(() => expect(put).toHaveBeenCalledTimes(1));
    const expected = structuredClone(PAGE_DEFAULTS.HOME_BANNER);
    expected.heroStat.value = '3.000+';
    expected.stats[3]!.label = 'Hài lòng';
    expect(putBody(put)).toStrictEqual({ content: expected });
  });

  it('đang lưu: nút Lưu tắt, ô nhập chỉ đọc (gõ thêm không bị mất khi nháp được xóa)', async () => {
    const user = userEvent.setup();
    let release!: () => void;
    vi.spyOn(pageContentApi, 'save').mockImplementationOnce(
      () => new Promise((resolve) => (release = () => resolve({ key: 'HOME_BANNER', title: null, content: {}, updatedAt: '' }))),
    );
    renderAt();
    const cta = await findField('Chữ trên nút');
    await replaceText(user, cta, 'Đặt lịch');
    await user.click(saveButton());

    expect(await screen.findByRole('button', { name: 'Đang lưu…' })).toBeDisabled();
    expect(cta).toHaveAttribute('readonly');
    expect(screen.getByRole('button', { name: 'Hoàn tác thay đổi' })).toBeDisabled();
    release();
    await waitFor(() => expect(saveButton()).toHaveTextContent('Lưu thay đổi'));
    expect(cta).not.toHaveAttribute('readonly');
  });
});

describe('kiểm tra theo trường', () => {
  it('để trống: lỗi dưới ô, Lưu tắt, bấm cũng không gọi PUT', async () => {
    const user = userEvent.setup();
    const put = vi.spyOn(apiClient, 'put');
    renderAt();
    const cta = await findField('Chữ trên nút');
    await user.clear(cta);

    expect(cta).toHaveAccessibleDescription(/Không được để trống\./);
    expect(cta).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText('Còn 1 trường chưa hợp lệ')).toBeInTheDocument();
    expect(saveButton()).toBeDisabled();
    await user.click(saveButton());
    expect(put).not.toHaveBeenCalled();
  });

  it('chỉ có khoảng trắng cũng là để trống', async () => {
    const user = userEvent.setup();
    renderAt();
    const cta = await findField('Chữ trên nút');
    await replaceText(user, cta, '    ');
    expect(cta).toHaveAccessibleDescription(/Không được để trống\./);
    expect(saveButton()).toBeDisabled();
  });

  it('vượt giới hạn: ô vẫn nhận chữ (không maxLength), lỗi nêu giới hạn và số ký tự hiện có, Lưu tắt', async () => {
    const user = userEvent.setup();
    const put = vi.spyOn(apiClient, 'put');
    renderAt();
    const cta = await findField('Chữ trên nút');
    const tooLong = 'Ă'.repeat(PAGE_LIMITS.ctaLabel + 1);
    await replaceText(user, cta, tooLong);

    expect(cta).not.toHaveAttribute('maxlength');
    expect(cta).toHaveValue(tooLong);
    expect(cta).toHaveAccessibleDescription(/Tối đa 24 ký tự \(đang 25\)\./);
    expect(cta).toHaveAccessibleDescription(/\b25\/24\b/);
    expect(saveButton()).toBeDisabled();
    await user.click(saveButton());
    expect(put).not.toHaveBeenCalled();

    await user.type(cta, '{Backspace}');
    expect(cta).not.toHaveAccessibleDescription(/Tối đa/);
    expect(saveButton()).toBeEnabled();
  });

  it('đếm số trường lỗi và sửa xong thì hết lỗi; ô nhiều dòng cũng kiểm', async () => {
    const user = userEvent.setup();
    renderAt();
    await replaceText(user, await findField('Chữ trên nút'), '');
    fireEvent.change(screen.getByLabelText('Tiêu đề'), { target: { value: 'x'.repeat(PAGE_LIMITS.headline + 1) } });
    expect(screen.getByText('Còn 2 trường chưa hợp lệ')).toBeInTheDocument();
    expect(screen.getByLabelText('Tiêu đề')).toHaveAccessibleDescription(/Tối đa 20 ký tự \(đang 21\)\./);

    await user.type(screen.getByLabelText('Chữ trên nút'), 'Đặt lịch');
    expect(screen.getByText('Còn 1 trường chưa hợp lệ')).toBeInTheDocument();
  });
});

describe('giới hạn 64 KB tính theo byte', () => {
  it('chuỗi tiếng Việt có dấu: ít ký tự nhưng quá 64 KB thì báo trước và không gọi PUT', async () => {
    const user = userEvent.setup();
    const put = vi.spyOn(apiClient, 'put');
    renderAt('?trang=POLICY');
    const sectionBody = String.fromCodePoint(0x1ec7).repeat(PAGE_LIMITS.sectionBody); // "ệ": 1 ký tự, 3 byte UTF-8
    const premise = structuredClone(PAGE_DEFAULTS.POLICY);
    for (const doc of [premise.terms, premise.privacy]) for (const s of doc.sections) s.body = sectionBody;
    // Tiền đề của ca: JSON dưới 64 K ký tự nhưng trên 64 KB byte, nên đếm ký tự sẽ cho qua.
    expect(JSON.stringify(premise).length).toBeLessThan(PAGE_CONTENT_MAX_BYTES);
    expect(pageContentBytes(premise)).toBeGreaterThan(PAGE_CONTENT_MAX_BYTES);

    for (const name of ['Điều khoản sử dụng', 'Chính sách bảo mật']) {
      const doc = await screen.findByRole('group', { name });
      for (const body of within(doc).getAllByLabelText('Nội dung')) fireEvent.change(body, { target: { value: sectionBody } });
    }

    const kb = Math.ceil(pageContentBytes(premise) / 1024);
    expect(await screen.findByText(`Nội dung quá dài (${kb} KB / ${MAX_KB} KB)`)).toBeInTheDocument();
    expect(saveButton()).toBeDisabled();
    await user.click(saveButton());
    expect(put).not.toHaveBeenCalled();
  });

  it('dưới giới hạn byte thì lưu được và không có cảnh báo', async () => {
    const user = userEvent.setup();
    const put = vi.spyOn(apiClient, 'put');
    renderAt('?trang=POLICY');
    const terms = await screen.findByRole('group', { name: 'Điều khoản sử dụng' });
    fireEvent.change(within(terms).getAllByLabelText('Nội dung')[0]!, { target: { value: String.fromCodePoint(0x1ec7).repeat(2000) } });
    expect(screen.queryByText(/Nội dung quá dài/)).not.toBeInTheDocument();
    await user.click(saveButton());
    await waitFor(() => expect(put).toHaveBeenCalledTimes(1));
  });
});

describe('lỗi từ máy chủ', () => {
  it('400 hiện ngay trong trang, nháp còn nguyên và lưu lại được', async () => {
    const user = userEvent.setup();
    vi.spyOn(pageContentApi, 'save').mockRejectedValueOnce(new ApiError('VALIDATION_FAILED', 'Nội dung trang tối đa 64 KB', 400));
    renderAt();
    const cta = await findField('Chữ trên nút');
    await replaceText(user, cta, 'Đặt lịch ngay');
    await user.click(saveButton());

    expect(await screen.findByRole('alert')).toHaveTextContent('Nội dung trang tối đa 64 KB');
    expect(cta).toHaveValue('Đặt lịch ngay');
    expect(saveButton()).toBeEnabled();
    expect(screen.getByText(DEFAULT_STRIP)).toBeInTheDocument(); // chưa ghi được
    expect(tab(/Banner trang chủ/)).toHaveTextContent('Chưa lưu');

    await user.click(saveButton()); // lần sau thành công (spy chỉ chặn một lần)
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
    expect((await publicPage('HOME_BANNER')).parsed.ctaLabel).toBe('Đặt lịch ngay');
  });

  it('sửa tiếp thì thông báo lỗi cũ biến mất', async () => {
    const user = userEvent.setup();
    vi.spyOn(pageContentApi, 'save').mockRejectedValueOnce(new ApiError('INTERNAL_ERROR', 'Máy chủ đang gặp sự cố. Vui lòng thử lại sau.', 500));
    renderAt();
    const cta = await findField('Chữ trên nút');
    await replaceText(user, cta, 'Đặt lịch ngay');
    await user.click(saveButton());
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    await user.type(cta, '!');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('nháp từng tab', () => {
  it('không mất khi chuyển tab và quay lại; lưu tab khác không đụng tới; tab có nháp ghi "Chưa lưu"', async () => {
    const user = userEvent.setup();
    const put = vi.spyOn(apiClient, 'put');
    renderAt();
    await replaceText(user, await findField('Chữ trên nút'), 'Nháp banner');

    await user.click(tab(/Câu hỏi thường gặp/));
    expect(tab(/Banner trang chủ/)).toHaveTextContent('Chưa lưu');
    expect(tab(/Câu hỏi thường gặp/).textContent).not.toContain('Chưa lưu');
    await replaceText(user, within(group('Câu hỏi 1')).getByLabelText('Câu hỏi'), 'Câu hỏi mới?');
    await user.click(saveButton());
    await waitFor(() => expect(put).toHaveBeenCalledTimes(1));
    expect(put.mock.calls[0]![0]).toBe('/api/page-contents/FAQ');
    await waitFor(() => expect(screen.getByText(/Đã lưu “Câu hỏi thường gặp”/)).toBeInTheDocument());

    await user.click(tab(/Banner trang chủ/));
    expect(await findField('Chữ trên nút')).toHaveValue('Nháp banner'); // nháp sống sau khi lưu tab khác và tải lại danh sách
    expect(tab(/Banner trang chủ/)).toHaveTextContent('Chưa lưu');
    expect(tab(/Câu hỏi thường gặp/).textContent).not.toContain('Chưa lưu');
    expect(screen.getByText(DEFAULT_STRIP)).toBeInTheDocument(); // banner vẫn chưa ghi
  });

  it('nháp quay về đúng bản đã lưu thì bỏ dấu "Chưa lưu"; dấu cách cuối vẫn tính là thay đổi', async () => {
    const user = userEvent.setup();
    renderAt();
    const cta = await findField('Chữ trên nút');

    await user.type(cta, ' ');
    expect(cta).toHaveValue('Đặt Lịch Ngay '); // dấu cách cuối không bị nuốt
    expect(saveButton()).toBeEnabled();
    expect(tab(/Banner trang chủ/)).toHaveTextContent('Chưa lưu');

    await user.type(cta, '{Backspace}');
    expect(cta).toHaveValue('Đặt Lịch Ngay');
    expect(saveButton()).toBeDisabled();
    expect(tab(/Banner trang chủ/).textContent).not.toContain('Chưa lưu');
    expect(screen.getByRole('button', { name: 'Hoàn tác thay đổi' })).toBeDisabled();
  });

  it('nháp đã quay về đúng bản lưu thì không giữ chỗ: bản lưu mới từ nơi khác hiện ra khi tải lại', async () => {
    const user = userEvent.setup();
    const { client } = renderAt();
    const cta = await findField('Chữ trên nút');
    await user.type(cta, 'x');
    await user.type(cta, '{Backspace}');

    seedPages({ HOME_BANNER: { ...PAGE_DEFAULTS.HOME_BANNER, ctaLabel: 'Admin khác đã sửa' } });
    await client.invalidateQueries({ queryKey: ['page-contents'] });
    await waitFor(() => expect(cta).toHaveValue('Admin khác đã sửa'));
    expect(screen.queryByText(DEFAULT_STRIP)).not.toBeInTheDocument();
  });

  it('Hoàn tác luôn hỏi xác nhận: Giữ lại thì còn nháp, Hoàn tác thì về bản đã lưu', async () => {
    const user = userEvent.setup();
    renderAt();
    const cta = await findField('Chữ trên nút');
    await replaceText(user, cta, 'Đặt lịch ngay');

    await user.click(screen.getByRole('button', { name: 'Hoàn tác thay đổi' }));
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('Hoàn tác thay đổi?');
    await user.click(within(dialog).getByRole('button', { name: 'Giữ lại' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(cta).toHaveValue('Đặt lịch ngay');

    await user.click(screen.getByRole('button', { name: 'Hoàn tác thay đổi' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Hoàn tác' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(cta).toHaveValue('Đặt Lịch Ngay');
    expect(saveButton()).toBeDisabled();
    expect(tab(/Banner trang chủ/).textContent).not.toContain('Chưa lưu');
  });

  it('Khôi phục mặc định khi chưa có nháp: đổ mặc định vào form ngay (chưa ghi), không hỏi', async () => {
    const user = userEvent.setup();
    const put = vi.spyOn(apiClient, 'put');
    seedPages({ HOME_BANNER: { ...PAGE_DEFAULTS.HOME_BANNER, ctaLabel: 'Hẹn khám ngay' } });
    renderAt();
    const cta = await findField('Chữ trên nút');
    await user.click(screen.getByRole('button', { name: 'Khôi phục nội dung mặc định' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(cta).toHaveValue('Đặt Lịch Ngay');
    expect(saveButton()).toBeEnabled(); // khác bản đã lưu nên cần Lưu mới ghi
    expect(tab(/Banner trang chủ/)).toHaveTextContent('Chưa lưu');
    expect(put).not.toHaveBeenCalled();

    await user.click(saveButton());
    await waitFor(() => expect(put).toHaveBeenCalledTimes(1));
    expect(putBody(put).content).toStrictEqual(PAGE_DEFAULTS.HOME_BANNER);
  });

  it('Khôi phục mặc định khi đang có nháp thì hỏi; Giữ lại thì không đổi', async () => {
    const user = userEvent.setup();
    seedPages({ HOME_BANNER: { ...PAGE_DEFAULTS.HOME_BANNER, ctaLabel: 'Hẹn khám ngay' } });
    renderAt();
    const cta = await findField('Chữ trên nút');
    await replaceText(user, cta, 'Đang soạn dở');

    await user.click(screen.getByRole('button', { name: 'Khôi phục nội dung mặc định' }));
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent('Khôi phục nội dung mặc định?');
    await user.click(within(dialog).getByRole('button', { name: 'Giữ lại' }));
    expect(cta).toHaveValue('Đang soạn dở');

    await user.click(screen.getByRole('button', { name: 'Khôi phục nội dung mặc định' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Khôi phục' }));
    expect(cta).toHaveValue('Đặt Lịch Ngay');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('danh sách có thể thêm, xóa, chuyển (FAQ, Chính sách)', () => {
  const questions = () => screen.getAllByLabelText('Câu hỏi').map((el) => (el as HTMLInputElement).value);

  it('FAQ: thêm câu hỏi mới ở cuối, đưa con trỏ vào ô đầu, lỗi trống tới khi điền; lưu đúng 5 mục', async () => {
    const user = userEvent.setup();
    const put = vi.spyOn(apiClient, 'put');
    renderAt('?trang=FAQ');
    await screen.findByRole('group', { name: 'Câu hỏi 4' });
    expect(screen.queryByRole('group', { name: 'Câu hỏi 5' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Thêm câu hỏi' }));
    const added = group('Câu hỏi 5');
    expect(within(added).getByLabelText('Câu hỏi')).toHaveFocus();
    expect(within(added).getByLabelText('Câu hỏi')).toHaveAccessibleDescription(/Không được để trống\./);
    expect(saveButton()).toBeDisabled();
    expect(screen.getByText('Còn 2 trường chưa hợp lệ')).toBeInTheDocument();

    await user.type(within(added).getByLabelText('Câu hỏi'), 'Có giữ thú cưng qua đêm không?');
    await user.type(within(added).getByLabelText('Câu trả lời'), 'Có, tại khu nghỉ dưỡng.');
    await user.click(saveButton());
    await waitFor(() => expect(put).toHaveBeenCalledTimes(1));
    const items = putBody(put).content.items as { question: string }[];
    expect(items).toHaveLength(5);
    expect(items[4]!.question).toBe('Có giữ thú cưng qua đêm không?');
    expect(items.slice(0, 4)).toStrictEqual(PAGE_DEFAULTS.FAQ.items);
  });

  it('FAQ: xóa một câu hỏi giữ nguyên thứ tự các câu còn lại; còn 1 thì không xóa được nữa', async () => {
    const user = userEvent.setup();
    renderAt('?trang=FAQ');
    await screen.findByRole('group', { name: 'Câu hỏi 4' });
    const original = questions();

    await user.click(screen.getByRole('button', { name: 'Xóa câu hỏi 2' }));
    expect(questions()).toEqual([original[0], original[2], original[3]]);
    expect(screen.queryByRole('group', { name: 'Câu hỏi 4' })).not.toBeInTheDocument();
    expect(saveButton()).toBeEnabled();

    await user.click(screen.getByRole('button', { name: 'Xóa câu hỏi 3' }));
    await user.click(screen.getByRole('button', { name: 'Xóa câu hỏi 2' }));
    expect(questions()).toEqual([original[0]]);
    expect(screen.getByRole('button', { name: 'Xóa câu hỏi 1' })).toBeDisabled();
  });

  it('FAQ: chuyển lên / xuống đổi đúng thứ tự, mục đầu không lên được, mục cuối không xuống được, thứ tự được lưu', async () => {
    const user = userEvent.setup();
    const put = vi.spyOn(apiClient, 'put');
    renderAt('?trang=FAQ');
    await screen.findByRole('group', { name: 'Câu hỏi 4' });
    const [q1, q2, q3, q4] = questions() as [string, string, string, string];
    expect(screen.getByRole('button', { name: 'Chuyển câu hỏi 1 lên' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Chuyển câu hỏi 4 xuống' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: 'Chuyển câu hỏi 2 lên' }));
    expect(questions()).toEqual([q2, q1, q3, q4]);
    await user.click(screen.getByRole('button', { name: 'Chuyển câu hỏi 3 xuống' }));
    expect(questions()).toEqual([q2, q1, q4, q3]);

    await user.click(saveButton());
    await waitFor(() => expect(put).toHaveBeenCalledTimes(1));
    expect((putBody(put).content.items as { question: string }[]).map((i) => i.question)).toEqual([q2, q1, q4, q3]);
  });

  it('FAQ: con trỏ đi theo câu hỏi vừa chuyển', async () => {
    const user = userEvent.setup();
    renderAt('?trang=FAQ');
    await screen.findByRole('group', { name: 'Câu hỏi 4' });
    await user.click(screen.getByRole('button', { name: 'Chuyển câu hỏi 3 lên' }));
    expect(screen.getByRole('button', { name: 'Chuyển câu hỏi 2 lên' })).toHaveFocus();
    await user.click(screen.getByRole('button', { name: 'Chuyển câu hỏi 2 lên' }));
    // Lên tới đầu danh sách: nút "lên" đã tắt nên con trỏ sang nút "xuống" của chính mục đó.
    expect(screen.getByRole('button', { name: 'Chuyển câu hỏi 1 xuống' })).toHaveFocus();
  });

  it('FAQ: tối đa 20 câu hỏi, đủ thì tắt nút Thêm và không thêm được nữa', async () => {
    const user = userEvent.setup();
    renderAt('?trang=FAQ');
    await screen.findByRole('group', { name: 'Câu hỏi 4' });
    const add = () => screen.getByRole('button', { name: 'Thêm câu hỏi' });
    for (let i = 4; i < PAGE_LIMITS.faqItems; i++) await user.click(add());

    expect(screen.getByRole('group', { name: 'Câu hỏi 20' })).toBeInTheDocument();
    expect(add()).toBeDisabled();
    expect(screen.getByText('20/20 câu hỏi')).toBeInTheDocument();
    await user.click(add());
    expect(screen.queryByRole('group', { name: 'Câu hỏi 21' })).not.toBeInTheDocument();
    expect(screen.getAllByLabelText('Câu hỏi')).toHaveLength(20);
  });

  it('Chính sách: thêm, chuyển, xóa chỉ ảnh hưởng đúng văn bản; còn 1 mục thì không xóa được', async () => {
    const user = userEvent.setup();
    const put = vi.spyOn(apiClient, 'put');
    renderAt('?trang=POLICY');
    const terms = await screen.findByRole('group', { name: 'Điều khoản sử dụng' });
    const privacy = group('Chính sách bảo mật');
    const headings = (doc: HTMLElement) => within(doc).getAllByLabelText('Tiêu đề').map((el) => (el as HTMLInputElement).value);
    const termsBefore = headings(terms);
    const privacyBefore = headings(privacy);
    expect(termsBefore).toHaveLength(7);

    await user.click(within(terms).getByRole('button', { name: 'Thêm mục' }));
    expect(headings(terms)).toHaveLength(8);
    expect(headings(privacy)).toEqual(privacyBefore);
    expect(within(within(terms).getByRole('group', { name: 'Mục 8' })).getByLabelText('Tiêu đề')).toHaveFocus();

    await user.click(within(terms).getByRole('button', { name: 'Chuyển mục 2 xuống' }));
    await user.click(within(terms).getByRole('button', { name: 'Xóa mục 8' }));
    const moved = [termsBefore[0], termsBefore[2], termsBefore[1], ...termsBefore.slice(3)];
    expect(headings(terms)).toEqual(moved);
    expect(headings(privacy)).toEqual(privacyBefore);

    await user.click(saveButton());
    await waitFor(() => expect(put).toHaveBeenCalledTimes(1));
    const content = putBody(put).content as { terms: { sections: { heading: string }[] }; privacy: { sections: unknown[] } };
    expect(content.terms.sections.map((s) => s.heading)).toEqual(moved);
    expect(content.privacy.sections).toStrictEqual(PAGE_DEFAULTS.POLICY.privacy.sections);
    await waitFor(() => expect(screen.getByText(/Đã lưu “Chính sách”/)).toBeInTheDocument());

    for (let i = 7; i > 1; i--) await user.click(within(privacy).getByRole('button', { name: `Xóa mục ${i}` }));
    expect(within(privacy).getAllByLabelText('Tiêu đề')).toHaveLength(1);
    expect(within(privacy).getByRole('button', { name: 'Xóa mục 1' })).toBeDisabled();
  });

  it('Chính sách: tối đa 20 mục mỗi văn bản', async () => {
    const user = userEvent.setup();
    renderAt('?trang=POLICY');
    const terms = await screen.findByRole('group', { name: 'Điều khoản sử dụng' });
    const add = () => within(terms).getByRole('button', { name: 'Thêm mục' });
    for (let i = 7; i < PAGE_LIMITS.docSections; i++) await user.click(add());
    expect(within(terms).getAllByLabelText('Tiêu đề')).toHaveLength(20);
    expect(add()).toBeDisabled();
    expect(within(terms).getByText('20/20 mục')).toBeInTheDocument();
    expect(within(group('Chính sách bảo mật')).getByRole('button', { name: 'Thêm mục' })).toBeEnabled();
  });

  it('Chính sách: đoạn dẫn và ngày cập nhật được kiểm và lưu', async () => {
    const user = userEvent.setup();
    const put = vi.spyOn(apiClient, 'put');
    renderAt('?trang=POLICY');
    const privacy = await screen.findByRole('group', { name: 'Chính sách bảo mật' });
    const updated = within(privacy).getByLabelText('Cập nhật ngày');
    await replaceText(user, updated, '');
    expect(updated).toHaveAccessibleDescription(/Không được để trống\./);
    expect(saveButton()).toBeDisabled();
    await user.type(updated, '27/10/2026');
    expect(updated).toHaveAccessibleDescription(/\b10\/20\b/);
    await user.click(saveButton());
    await waitFor(() => expect(put).toHaveBeenCalledTimes(1));
    expect((putBody(put).content.privacy as { updatedOn: string }).updatedOn).toBe('27/10/2026');
  });
});

describe('liên kết xem trang công khai', () => {
  it.each([
    ['', [['Xem trang', '/']]],
    ['?trang=ABOUT', [['Xem trang', '/about']]],
    ['?trang=FAQ', [['Xem trang', '/']]],
    [
      '?trang=POLICY',
      [
        ['Xem Điều khoản sử dụng', '/terms'],
        ['Xem Chính sách bảo mật', '/privacy'],
      ],
    ],
  ])('%s mở trang công khai ở tab mới, có noopener noreferrer', async (search, links) => {
    renderAt(search);
    await screen.findByRole('tab', { selected: true });
    for (const [name, href] of links as [string, string][]) {
      const link = await screen.findByRole('link', { name: new RegExp(`^${name}`) });
      expect(link).toHaveAttribute('href', href);
      expect(link).toHaveAttribute('target', '_blank');
      expect(link.getAttribute('rel')).toMatch(/noopener/);
      expect(link.getAttribute('rel')).toMatch(/noreferrer/);
    }
  });
});
