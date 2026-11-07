import { screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../../shared/api/axios';
import { installMockApi } from '../../../shared/api/mock/mock-api';
import { surgeryApi } from '../../../shared/api/surgery.api';
import { renderDesk, signInAs, startDesk } from '../../../test/visit-harness';
import { FrontDeskPanel } from './FrontDeskPanel';

const toast = vi.hoisted(() => Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
vi.mock('sonner', () => ({ toast }));

// surgery.mock lúc 10:40 thứ Năm 22/10/2026, chi nhánh 1: không có phẫu thuật nào đang mở; chi nhánh 2 có 1 ca PLANNED (không được lọt vào danh sách của lễ tân chi nhánh 1).
// Test tạo ca trên lượt 2002 (Lu) bằng VET #5 rồi chuyển sang lễ tân.

beforeAll(() => installMockApi(apiClient));
afterEach(() => vi.useRealTimers());

const renderPanel = () => renderDesk(<FrontDeskPanel onCheckIn={vi.fn()} onWalkIn={vi.fn()} onCollect={vi.fn()} />);

/** VET lập kế hoạch phẫu thuật cho Lu rồi đăng nhập `email`. */
async function planThenSignIn(email: string) {
  await startDesk('doctor@store1.vn');
  const planned = await surgeryApi.plan(2002, { serviceId: 7001, planNote: 'Triệt sản mèo cái' });
  await signInAs(email);
  return planned;
}

/** VET hủy ca ở máy khác: sửa thẳng state của mock (đổi phiên đăng nhập sẽ gỡ màn đang mở). */
function cancelBehindTheScenes(surgeryId: number) {
  const state = JSON.parse(localStorage.getItem('petcare-mock-surgery')!);
  Object.assign(state.surgeries.find((x: { surgeryId: number }) => x.surgeryId === surgeryId), { status: 'CANCELLED', cancelReason: 'Khách đổi ý' });
  localStorage.setItem('petcare-mock-surgery', JSON.stringify(state));
}

const openSurgeryTab = async (user: ReturnType<typeof renderPanel>['user']) => {
  await user.click(await screen.findByRole('tab', { name: /Phẫu thuật/ }));
  return screen.findByRole('list', { name: 'Phẫu thuật' });
};

describe('lễ tân: phẫu thuật chờ cam kết (UC50)', () => {
  it('tab Phẫu thuật đếm ca chờ cam kết của chi nhánh mình và liệt kê thú, dịch vụ, trạng thái', async () => {
    await planThenSignIn('reception@store1.vn');
    const { user } = renderPanel();
    expect(await screen.findByRole('tab', { name: /Phẫu thuật 1/ })).toBeInTheDocument();

    const list = await openSurgeryTab(user);
    const row = within(list).getAllByRole('listitem');
    expect(row).toHaveLength(1);
    expect(row[0]).toHaveTextContent('Lu');
    expect(row[0]).toHaveTextContent('Triệt sản chó mèo');
    expect(row[0]).toHaveTextContent('Chờ cam kết');
    expect(within(row[0]).getByRole('button', { name: 'Lập cam kết: Lu' })).toBeInTheDocument();
  });

  it('lập cam kết cần nội dung và người ký; xong thì ca rời tab chờ và nằm ở lọc "Đã có cam kết"', async () => {
    await planThenSignIn('reception@store1.vn');
    const { user } = renderPanel();
    await openSurgeryTab(user);

    await user.click(await screen.findByRole('button', { name: 'Lập cam kết: Lu' }));
    const dialog = await screen.findByRole('dialog', { name: 'Lập cam kết phẫu thuật' });
    await user.click(within(dialog).getByRole('button', { name: 'Lưu cam kết' }));
    expect(await within(dialog).findByText(/Nhập đủ nội dung cam kết/)).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText(/Nội dung cam kết/), 'Rủi ro gây mê, chi phí dự kiến 1.800.000 ₫');
    await user.type(within(dialog).getByLabelText('Người ký'), 'Lê Hoàng Long');
    await user.type(within(dialog).getByLabelText('Quan hệ với chủ thú'), 'Chủ thú');
    await user.click(within(dialog).getByRole('button', { name: 'Lưu cam kết' }));

    expect(await screen.findByText('Không có phẫu thuật chờ cam kết')).toBeInTheDocument();
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('Đã lập cam kết'));
    await user.click(screen.getByRole('button', { name: 'Đã có cam kết' }));
    const list = await screen.findByRole('list', { name: 'Phẫu thuật' });
    expect(within(list).getByText('Lu')).toBeInTheDocument();
    expect(within(list).queryByRole('button', { name: /Lập cam kết/ })).not.toBeInTheDocument();
    expect(within(list).getByRole('button', { name: 'Hủy phẫu thuật: Lu' })).toBeInTheDocument();
  });

  it('VET đã hủy ca trước khi lễ tân bấm lưu: BE trả 409 và câu báo hiện trong hộp thoại, hộp vẫn mở', async () => {
    const planned = await planThenSignIn('reception@store1.vn');
    const { user } = renderPanel();
    await openSurgeryTab(user);
    await user.click(await screen.findByRole('button', { name: 'Lập cam kết: Lu' }));
    const dialog = await screen.findByRole('dialog', { name: 'Lập cam kết phẫu thuật' });
    await user.type(within(dialog).getByLabelText(/Nội dung cam kết/), 'Rủi ro gây mê');
    await user.type(within(dialog).getByLabelText('Người ký'), 'Lê Hoàng Long');
    await user.type(within(dialog).getByLabelText('Quan hệ với chủ thú'), 'Chủ thú');

    cancelBehindTheScenes(planned.surgeryId);
    await user.click(within(dialog).getByRole('button', { name: 'Lưu cam kết' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/CANCELLED|PLANNED|trạng thái/i);
    expect(screen.getByRole('dialog', { name: 'Lập cam kết phẫu thuật' })).toBeInTheDocument();
  });

  it('lễ tân hủy được ca chưa bắt đầu và phải ghi lý do', async () => {
    await planThenSignIn('reception@store1.vn');
    const { user } = renderPanel();
    await openSurgeryTab(user);
    await user.click(await screen.findByRole('button', { name: 'Hủy phẫu thuật: Lu' }));
    const dialog = await screen.findByRole('dialog', { name: 'Hủy phẫu thuật' });
    await user.click(within(dialog).getByRole('button', { name: 'Hủy phẫu thuật' }));
    expect(await within(dialog).findByText('Nhập lý do hủy hoặc dừng.')).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText(/Lý do/), 'Khách chưa đồng ý');
    await user.click(within(dialog).getByRole('button', { name: 'Hủy phẫu thuật' }));
    expect(await screen.findByText('Không có phẫu thuật chờ cam kết')).toBeInTheDocument();
  });
});

describe('vai trò khác lễ tân không có tab Phẫu thuật và không hỏi danh sách phẫu thuật', () => {
  it.each([
    ['manager@store1.vn', 'quản lý chi nhánh'],
    ['doctor@store1.vn', 'bác sĩ'],
  ])('%s (%s)', async (email) => {
    await startDesk(email);
    const list = vi.spyOn(surgeryApi, 'list');
    renderPanel();
    expect(await screen.findByRole('tab', { name: /Hàng chờ/ })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('tab', { name: /Chờ thu/ })).toBeInTheDocument());
    expect(screen.queryByRole('tab', { name: /Phẫu thuật/ })).not.toBeInTheDocument();
    expect(list).not.toHaveBeenCalled();
  });
});
