import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { boardingApi } from '@/shared/api/boarding.api';
import type { Booking } from '@/shared/types/boarding';
import { CareLogDialog } from './CareLogDialog';
import { ACCOUNTS, bookingOf, clearRequests, installCareMock, renderDialog, signIn, startDay } from './care-harness';
import { emptyDraft, stayStartOf, validateDraft } from './rules';

// Ba lỗi giao diện tìm thấy khi chạy trên BE thật: F1 cận dưới ngày nhật ký, F2 chữ cảnh báo thiếu email, F3 nút trùng tên truy cập.
// Đồng hồ ghim thứ Sáu 23/10/2026 09:00 giờ VN; dữ liệu mẫu: xem đầu care-harness.ts (Cốm #2 nhận 21/10, Mực #3 nhận 22/10).

beforeAll(installCareMock);
beforeEach(startDay);
afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

const TOO_EARLY = 'Ngày nhật ký phải từ ngày nhận thú tới hôm nay';
/** Ngày giờ VN của một thời điểm, tính độc lập với mã sản phẩm (không dùng múi giờ máy). */
const vnDay = (iso: string) => new Date(Date.parse(iso) + 7 * 3_600_000).toISOString().slice(0, 10);

async function openWith(email: string, bookingId: number, patch: Partial<Booking> = {}) {
  await signIn(email);
  const booking = { ...(await bookingOf(bookingId)), ...patch };
  clearRequests();
  const view = renderDialog(<CareLogDialog booking={booking} canWrite onClose={vi.fn()} />);
  await screen.findByRole('dialog', { name: 'Nhật ký chăm sóc' });
  return { ...view, booking };
}

const openNewForm = async (user: ReturnType<typeof renderDialog>['user']) => {
  await user.click(await screen.findByRole('button', { name: 'Ghi nhật ký mới' }));
  return screen.findByRole('form', { name: 'Ghi nhật ký mới' });
};
const save = (form: HTMLElement) => within(form).getByRole('button', { name: 'Lưu nhật ký' });

describe('F1: ngày nhật ký có cận dưới = ngày nhận thú', () => {
  const CREATE = { creating: true, today: '2026-10-23' };
  const filled = (logDate: string) => ({ ...emptyDraft('2026-10-23'), logDate, eating: 'Ăn' });

  it('stayStartOf: ngày VN của lúc nhận thực tế; chưa nhận → không có', () => {
    expect(stayStartOf({ actualCheckInAt: '2026-10-21T02:20:00.000Z' })).toBe('2026-10-21');
    expect(stayStartOf({ actualCheckInAt: '2026-10-20T18:30:00.000Z' })).toBe('2026-10-21'); // 01:30 ngày 21 giờ VN, còn 20 theo UTC
    expect(stayStartOf({ actualCheckInAt: '2026-10-21T16:59:59.000Z' })).toBe('2026-10-21'); // 23:59:59 ngày 21
    expect(stayStartOf({ actualCheckInAt: '2026-10-21T17:00:00.000Z' })).toBe('2026-10-22'); // 00:00 ngày 22
    expect(stayStartOf({ actualCheckInAt: null })).toBeUndefined();
    expect(stayStartOf({})).toBeUndefined();
  });

  it('validateDraft: trước ngày nhận bị chặn bằng đúng câu; đúng ngày nhận qua; không có ngày nhận thì không chặn; sửa không kiểm', () => {
    const ctx = { ...CREATE, minDate: '2026-10-21' };
    expect(validateDraft(filled('2026-10-20'), ctx).logDate).toBe(TOO_EARLY);
    expect(validateDraft(filled('2026-10-21'), ctx).logDate).toBeUndefined();
    expect(validateDraft(filled('2026-10-23'), ctx).logDate).toBeUndefined();
    expect(validateDraft(filled('2026-10-01'), CREATE).logDate).toBeUndefined();
    expect(validateDraft(filled('2026-10-01'), { ...ctx, creating: false }).logDate).toBeUndefined();
  });

  it('validateDraft: giữ nguyên hai câu cũ (trống, tương lai)', () => {
    const ctx = { ...CREATE, minDate: '2026-10-21' };
    expect(validateDraft(filled(''), ctx).logDate).toBe('Vui lòng chọn ngày nhật ký');
    expect(validateDraft(filled('2026-10-24'), ctx).logDate).toBe('Ngày nhật ký không được ở tương lai');
  });

  it('ô ngày có min = ngày nhận (giờ VN) và max = hôm nay', async () => {
    const { user, booking } = await openWith(ACCOUNTS.caretaker, 2);
    expect(vnDay(booking.actualCheckInAt as string)).toBe('2026-10-21');
    const date = within(await openNewForm(user)).getByLabelText('Ngày nhật ký');
    expect(date).toHaveAttribute('min', '2026-10-21');
    expect(date).toHaveAttribute('max', '2026-10-23');
  });

  it('nhận lúc 01:30 ngày 21 giờ VN (còn là 20 theo UTC): min vẫn là ngày 21', async () => {
    const { user } = await openWith(ACCOUNTS.caretaker, 2, { actualCheckInAt: '2026-10-20T18:30:00.000Z' });
    expect(within(await openNewForm(user)).getByLabelText('Ngày nhật ký')).toHaveAttribute('min', '2026-10-21');
  });

  it('chưa có ngày nhận thực tế: không đặt min', async () => {
    const { user } = await openWith(ACCOUNTS.caretaker, 2, { actualCheckInAt: null });
    expect(within(await openNewForm(user)).getByLabelText('Ngày nhật ký')).not.toHaveAttribute('min');
  });

  it('chọn ngày trước ngày nhận: chặn ngay ở form, báo dưới ô ngày, KHÔNG gọi API (không phải lỗi 400 của BE)', async () => {
    const { user } = await openWith(ACCOUNTS.caretaker, 2);
    const spy = vi.spyOn(boardingApi, 'createCareLog');
    const form = await openNewForm(user);
    await user.type(within(form).getByLabelText('Ăn'), 'Ăn');
    const date = within(form).getByLabelText('Ngày nhật ký');
    fireEvent.change(date, { target: { value: '2026-10-20' } });
    await user.click(save(form));
    expect(await within(date.parentElement as HTMLElement).findByText(TOO_EARLY)).toBeInTheDocument();
    expect(date).toHaveAttribute('aria-invalid', 'true');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(spy).not.toHaveBeenCalled();
  });

  it('chọn đúng ngày nhận: qua kiểm form và gửi logDate đó', async () => {
    const { user } = await openWith(ACCOUNTS.caretaker, 2);
    const spy = vi.spyOn(boardingApi, 'createCareLog');
    const form = await openNewForm(user);
    await user.type(within(form).getByLabelText('Ăn'), 'Ăn');
    fireEvent.change(within(form).getByLabelText('Ngày nhật ký'), { target: { value: '2026-10-21' } });
    await user.click(save(form));
    await waitFor(() => expect(spy).toHaveBeenCalledTimes(1));
    expect(spy.mock.calls[0][1]).toMatchObject({ logDate: '2026-10-21', eating: 'Ăn' });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('F2: cảnh báo bất thường nói rõ có email tới chủ thú', () => {
  it('lời giải thích dưới công tắc và khối xác nhận đều nêu email (nếu có); tên nút / tiêu đề / công tắc giữ nguyên', async () => {
    const { user } = await openWith(ACCOUNTS.caretaker, 2);
    const form = await openNewForm(user);
    const toggle = within(form).getByRole('switch', { name: /Đánh dấu bất thường/ });
    expect(toggle).toHaveAccessibleDescription(/email/i);
    expect(toggle).toHaveAccessibleDescription(/lễ tân/);

    await user.type(within(form).getByLabelText('Ghi chú'), 'Bỏ ăn, nôn hai lần');
    await user.click(toggle);
    await user.click(save(form));
    const block = (await within(form).findByText('Xác nhận đánh dấu bất thường')).parentElement as HTMLElement;
    expect(block).toHaveTextContent(/email/i);
    expect(block).toHaveTextContent(/lễ tân/);
    expect(within(block).getByRole('button', { name: 'Quay lại chỉnh sửa' })).toBeInTheDocument();
    expect(within(block).getByRole('button', { name: 'Xác nhận và gửi thông báo' })).toBeInTheDocument();
  });
});

describe('F3: nút "Sửa" và "Thêm bản bổ sung" phân biệt được theo mục', () => {
  it('mỗi mục có nút bổ sung mang giờ ghi của chính mục đó; không còn nút trùng tên', async () => {
    await openWith(ACCOUNTS.caretaker, 2);
    const first = await screen.findByRole('article', { name: /Thu Hà lúc 17:30 21\/10\/2026/ });
    const second = await screen.findByRole('article', { name: /BS\. Minh Anh lúc 19:40 22\/10\/2026/ });
    expect(within(first).getByRole('button', { name: 'Thêm bản bổ sung cho nhật ký lúc 17:30 21/10/2026' })).toBeInTheDocument();
    expect(within(second).getByRole('button', { name: 'Thêm bản bổ sung cho nhật ký lúc 19:40 22/10/2026' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Thêm bản bổ sung' })).not.toBeInTheDocument();
  });

  it('đã trả thú, ba mục: ba nút bổ sung có ba tên khác nhau', async () => {
    await openWith(ACCOUNTS.caretaker, 5);
    expect(await screen.findAllByRole('article')).toHaveLength(3);
    const names = screen.getAllByRole('button', { name: /^Thêm bản bổ sung/ }).map((b) => b.getAttribute('aria-label'));
    expect(names).toHaveLength(3);
    expect(new Set(names).size).toBe(3);
  });

  it('nút Sửa mang giờ ghi của mục; bấm nút bổ sung theo tên mới vẫn mở đúng biểu mẫu', async () => {
    const { user } = await openWith(ACCOUNTS.caretaker, 3);
    const today = await screen.findByRole('article', { name: /Thu Hà lúc 08:30 23\/10\/2026/ });
    expect(within(today).getByRole('button', { name: 'Sửa nhật ký lúc 08:30 23/10/2026' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sửa' })).not.toBeInTheDocument();
    await user.click(within(today).getByRole('button', { name: 'Thêm bản bổ sung cho nhật ký lúc 08:30 23/10/2026' }));
    expect(await screen.findByRole('form', { name: 'Thêm bản bổ sung' })).toBeInTheDocument();
  });
});
