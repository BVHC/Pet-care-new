import type { ComponentProps } from 'react';
import { act, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../../shared/api/api-error';
import { apiClient } from '../../../shared/api/axios';
import { identityApi } from '../../../shared/api/identity.api';
import { setStaffOnline } from '../../../shared/api/mock/identity-admin.mock';
import { installMockApi } from '../../../shared/api/mock/mock-api';
import { salesApi } from '../../../shared/api/sales.api';
import { visitApi } from '../../../shared/api/visit.api';
import { VISIT_REFRESH_MS } from '../../../shared/hooks/useVisits';
import { useSession } from '../../../shared/stores/session.store';
import { usePosStore } from '../../../shared/stores/pos.store';
import { at, makeStaff, makeVisit, pageOf, renderDesk, startDesk } from '../../../test/visit-harness';
import { FrontDeskPanel } from './FrontDeskPanel';

const toast = vi.hoisted(() => Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
vi.mock('sonner', () => ({ toast }));

// Đồng hồ ghim thứ Năm 22/10/2026 10:40 (test-harness). Lịch hẹn mẫu còn BOOKED: #1 Mochi (Nguyễn Thu Trang) Khám tổng quát 11:00.
// visit.mock, chi nhánh 1, hàng đợi theo thứ tự BE: 2101 Miu cấp cứu chờ gán · 2002 Lu có lịch hẹn đang khám (VET #5) · 2104 Mochi grooming đã gọi, người phụ trách #12
// không còn trong danh bạ (cần gán lại) · 2102 Tôm vãng lai chờ (VET #5) · 2103 Bim grooming chờ (CARETAKER #6).
// sales.mock, chi nhánh 1, chờ thu (PENDING): #2 Visit của khách #1 Nguyễn Thu Trang 266.000 · #3 Visit của khách #4 Lê Thị Hoa 200.000 · #4 bán lẻ của khách #2 Lê Hoàng Long 450.000.

beforeAll(async () => {
  installMockApi(apiClient);
  // Hộp thoại thao tác tải lười: nạp trước để test đầu tiên không phải chờ bước biên dịch lần đầu (giới hạn findBy mặc định 1 giây).
  await import('./VisitActionDialogs');
});
afterEach(() => vi.useRealTimers());

function renderPanel(props: Partial<ComponentProps<typeof FrontDeskPanel>> = {}) {
  const onCheckIn = vi.fn();
  const onWalkIn = vi.fn();
  const onCollect = vi.fn();
  const { user } = renderDesk(<FrontDeskPanel onCheckIn={onCheckIn} onWalkIn={onWalkIn} onCollect={onCollect} {...props} />);
  return { user, onCheckIn, onWalkIn, onCollect };
}

const rowOf = async (pet: string) => within((await screen.findByText(pet)).closest('li')!);
const queueTab = async (user: ReturnType<typeof renderPanel>['user']) => {
  await user.click(await screen.findByRole('tab', { name: /Hàng chờ/ }));
  await screen.findByRole('list', { name: 'Hàng chờ theo thứ tự ưu tiên' });
};
const petsInQueue = () => within(screen.getByRole('list', { name: 'Hàng chờ theo thứ tự ưu tiên' })).getAllByRole('listitem').map((li) => within(li).getByTestId('visit-pet').textContent);

describe('FrontDeskPanel — tab Chờ thu đọc Order PENDING từ sales-v1 (T35)', () => {
  async function renderPending() {
    const view = renderPanel();
    await view.user.click(await screen.findByRole('tab', { name: /Chờ thu/ }));
    return view;
  }

  it('lễ tân: liệt kê Order chờ thu (mã, khách, nguồn, tổng tiền); số trên tab là số Order của API', async () => {
    await startDesk('reception@store1.vn');
    await renderPending();

    const trang = await rowOf('DH-2610-00002');
    expect(trang.getByText('Nguyễn Thu Trang')).toBeInTheDocument();
    expect(trang.getByText('Lượt tiếp nhận')).toBeInTheDocument();
    expect(trang.getByText(/266\.000\s*₫/)).toBeInTheDocument();
    const retail = await rowOf('DH-2610-00004');
    expect(retail.getByText('Lê Hoàng Long')).toBeInTheDocument();
    expect(retail.getByText('Bán lẻ')).toBeInTheDocument();
    expect(retail.getByText(/450\.000\s*₫/)).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /Chờ thu/ })).toHaveTextContent('3');
  });

  it('"Thu tiền" đưa Order vào phiếu thu của đúng khách rồi báo bàn để chuyển sang phiếu', async () => {
    await startDesk('reception@store1.vn');
    usePosStore.getState().clear();
    const { user, onCollect } = await renderPending();

    const trang = await rowOf('DH-2610-00002');
    await user.click(trang.getByRole('button', { name: 'Thu tiền' }));

    expect(usePosStore.getState()).toMatchObject({ customerId: 1, orderIds: [2] });
    expect(onCollect).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(trang.getByRole('button', { name: 'Trong phiếu' })).toBeDisabled());
  });

  it('quản lý chi nhánh xem được danh sách nhưng không có nút thu tiền (thu tiền là việc của lễ tân)', async () => {
    await startDesk('manager@store1.vn');
    usePosStore.getState().clear();
    await renderPending();

    await rowOf('DH-2610-00002');
    expect(screen.queryByRole('button', { name: 'Thu tiền' })).not.toBeInTheDocument();
  });
});

describe('FrontDeskPanel — tab Đón tiếp (UC44; BR-TN-02, BR-TN-03)', () => {
  it('lễ tân: liệt kê lịch hẹn đã vào cửa sổ check-in với thú, khách, dịch vụ, giờ hẹn và gợi ý giờ đến; số trên tab là số lịch hẹn', async () => {
    await startDesk('reception@store1.vn');
    renderPanel();

    const mochi = await rowOf('Mochi');
    expect(mochi.getByText('Nguyễn Thu Trang')).toBeInTheDocument();
    expect(mochi.getByText('Khám tổng quát')).toBeInTheDocument();
    expect(mochi.getByText('11:00')).toBeInTheDocument();
    expect(mochi.getByText('Đến sớm 20 phút')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: /Đón tiếp/ })).toHaveTextContent('1');
  });

  it('"Tiếp nhận" đưa đúng lịch hẹn cho hộp thoại tiếp nhận', async () => {
    await startDesk('reception@store1.vn');
    const { user, onCheckIn } = renderPanel();

    await user.click((await rowOf('Mochi')).getByRole('button', { name: 'Tiếp nhận' }));

    expect(onCheckIn).toHaveBeenCalledTimes(1);
    expect(onCheckIn.mock.calls[0][0]).toMatchObject({ appointmentId: 1, petId: 1, petName: 'Mochi' });
  });

  it('khách trễ 15–30 phút: nói rõ sẽ xếp như khách vãng lai (BR-TN-03) nhưng vẫn tiếp nhận được', async () => {
    await startDesk('reception@store1.vn', at('11:20'));
    renderPanel();

    const mochi = await rowOf('Mochi');
    expect(mochi.getByText('Trễ 20 phút: xếp như khách vãng lai')).toBeInTheDocument();
    expect(mochi.getByRole('button', { name: 'Tiếp nhận' })).toBeEnabled();
  });

  it('chưa có lịch hẹn trong cửa sổ: báo trống, nút Khách vãng lai vẫn dùng được', async () => {
    await startDesk('reception@store1.vn', at('09:00'));
    const { user, onWalkIn } = renderPanel();

    expect(await screen.findByText('Chưa có lịch hẹn sắp đến')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Khách vãng lai/ }));
    expect(onWalkIn).toHaveBeenCalledTimes(1);
  });
});

describe('FrontDeskPanel — tab Hàng chờ (UC45; BR-TN-04)', () => {
  it('hiện hàng đợi chi nhánh đúng thứ tự BE trả, số trên tab là số lượt đang chờ hoặc đã gọi', async () => {
    await startDesk('reception@store1.vn');
    const { user } = renderPanel();
    await queueTab(user);

    await waitFor(() => expect(petsInQueue()).toEqual(['Miu', 'Lu', 'Mochi', 'Tôm', 'Bim']));
    expect(screen.getByRole('tab', { name: /Hàng chờ/ })).toHaveTextContent('5');
  });

  it('không tự sắp lại: mảng BE trả thế nào hiện thế ấy (khóa sắp xếp chỉ khác nhau 1 µs thì Date.parse không phân biệt được)', async () => {
    await startDesk('reception@store1.vn');
    vi.spyOn(visitApi, 'listQueue').mockResolvedValue([
      makeVisit(31, { petName: 'Vãng lai trước', queueSortKey: '2026-10-22T03:00:00.000001Z' }),
      makeVisit(32, { petName: 'Cấp cứu sau', priorityClass: 'EMERGENCY', queueSortKey: '2026-10-22T03:30:00Z' }),
      makeVisit(33, { petName: 'Cùng mili giây', queueSortKey: '2026-10-22T03:00:00.000000Z' }),
    ]);
    const { user } = renderPanel();
    await queueTab(user);

    await waitFor(() => expect(petsInQueue()).toEqual(['Vãng lai trước', 'Cấp cứu sau', 'Cùng mili giây']));
  });

  it('mỗi lượt: mã, thú, khách, trạng thái, nhãn ưu tiên và nguồn, người phụ trách kèm chấm trực tuyến, cờ cần gán lại', async () => {
    await startDesk('reception@store1.vn');
    const { user } = renderPanel();
    await queueTab(user);

    const miu = await rowOf('Miu');
    expect(miu.getByText('TN-2610-00008')).toBeInTheDocument();
    expect(miu.getByText('Lê Thị Hoa')).toBeInTheDocument();
    expect(miu.getByText('Đang chờ')).toBeInTheDocument();
    expect(miu.getByText('Cấp cứu')).toBeInTheDocument();
    expect(miu.getByText('Vãng lai')).toBeInTheDocument();
    expect(miu.getByText('Chưa gán')).toBeInTheDocument();

    const lu = await rowOf('Lu');
    expect(lu.getByText('Đã gọi')).toBeInTheDocument();
    expect(lu.getByText('Lịch hẹn')).toBeInTheDocument();
    expect(lu.getByText('BS. Minh Anh')).toBeInTheDocument();
    expect(await lu.findByRole('img', { name: 'Trực tuyến' })).toBeInTheDocument();

    const mochi = await rowOf('Mochi');
    expect(mochi.getByText('Phạm Quốc Việt')).toBeInTheDocument();
    expect(mochi.getByText('Cần gán lại')).toBeInTheDocument();
    expect(mochi.queryByRole('img', { name: /tuyến/ })).not.toBeInTheDocument();
  });

  it('người phụ trách ngoại tuyến hiện chấm "Ngoại tuyến" (BR-TN-06)', async () => {
    await startDesk('reception@store1.vn');
    setStaffOnline(5, false);
    const { user } = renderPanel();
    await queueTab(user);

    expect(await (await rowOf('Lu')).findByRole('img', { name: 'Ngoại tuyến' })).toBeInTheDocument();
  });

  it('lượt đã gọi chỉ để xem: không có nút thao tác nào', async () => {
    await startDesk('reception@store1.vn');
    const { user } = renderPanel();
    await queueTab(user);

    expect((await rowOf('Lu')).queryAllByRole('button')).toHaveLength(0);
    expect((await rowOf('Mochi')).queryAllByRole('button')).toHaveLength(0);
  });

  it('nút theo từng lượt chờ: ca cấp cứu không còn "Cấp cứu", lượt đứng đầu không có "Đưa lên trước"', async () => {
    await startDesk('reception@store1.vn');
    const { user } = renderPanel();
    await queueTab(user);

    const names = async (pet: string) => (await rowOf(pet)).getAllByRole('button').map((b) => b.textContent);
    expect(await names('Miu')).toEqual(['Gán người', 'Hủy lượt']);
    // Tôm đứng sau hai lượt đã gọi và một ca cấp cứu (phía bên kia ranh giới): không có đích nào để đưa lên trước.
    expect(await names('Tôm')).toEqual(['Đổi người', 'Cấp cứu', 'Hủy lượt']);
    expect(await names('Bim')).toEqual(['Đổi người', 'Đưa lên trước', 'Cấp cứu', 'Hủy lượt']);
  });

  it('hàng đợi tự hỏi lại sau 10 giây: lượt mới tiếp nhận ở quầy khác làm số trên tab tăng, không cần tải lại trang', async () => {
    // Timer giả cho riêng test này (mock có độ trễ bằng setTimeout nên spy API trả ngay); không dùng userEvent.
    useSession.setState({
      token: 't',
      expiresAt: null,
      account: { id: 4, email: 'reception@store1.vn', role: 'RECEPTIONIST', status: 'ACTIVE', isLocked: false, mustChangePassword: false, branchId: 1 },
      fullName: null,
    });
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] });
    vi.setSystemTime(new Date(at('10:40')));
    vi.spyOn(visitApi, 'listArrivals').mockResolvedValue(pageOf([]));
    vi.spyOn(salesApi, 'listOrders').mockResolvedValue(pageOf([]));
    vi.spyOn(identityApi, 'listStaff').mockResolvedValue(pageOf([]));
    const list = vi
      .spyOn(visitApi, 'listQueue')
      .mockResolvedValueOnce([makeVisit(41, { petName: 'Có sẵn' })])
      .mockResolvedValue([makeVisit(41, { petName: 'Có sẵn' }), makeVisit(42, { petName: 'Mới đến' })]);
    renderPanel();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(screen.getByRole('tab', { name: /Hàng chờ/ })).toHaveTextContent('1');

    await act(async () => {
      await vi.advanceTimersByTimeAsync(VISIT_REFRESH_MS);
      await vi.advanceTimersByTimeAsync(50);
    });

    expect(list).toHaveBeenCalledTimes(2);
    expect(screen.getByRole('tab', { name: /Hàng chờ/ })).toHaveTextContent('2');
  });
});

describe('FrontDeskPanel — gán người phụ trách (BR-TN-05, BR-TN-06)', () => {
  const openAssign = async (pet: string) => {
    const view = renderPanel();
    await queueTab(view.user);
    await view.user.click((await rowOf(pet)).getByRole('button', { name: /Gán người|Đổi người/ }));
    return { ...view, dialog: within(await screen.findByRole('dialog', { name: 'Gán người phụ trách' })) };
  };

  it('lượt khám: chỉ liệt kê bác sĩ thú y (VET), gán xong hàng đợi hiện tên người phụ trách', async () => {
    await startDesk('reception@store1.vn');
    const list = vi.spyOn(identityApi, 'listStaff');
    const assign = vi.spyOn(visitApi, 'assign');
    const { user, dialog } = await openAssign('Miu');

    await user.selectOptions(await dialog.findByLabelText(/Người phụ trách/), '5');
    await user.click(dialog.getByRole('button', { name: 'Gán' }));

    await waitFor(() => expect(assign).toHaveBeenCalledWith(2101, { assigneeId: 5 }));
    expect(list).toHaveBeenCalledWith(expect.objectContaining({ role: 'VET', status: 'ACTIVE' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await (await rowOf('Miu')).findByText('BS. Minh Anh')).toBeInTheDocument();
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('TN-2610-00008'));
  });

  it('lượt grooming: chỉ liệt kê nhân viên chăm sóc (CARETAKER)', async () => {
    await startDesk('reception@store1.vn');
    const list = vi.spyOn(identityApi, 'listStaff');
    const { dialog } = await openAssign('Bim');

    expect(await dialog.findByRole('option', { name: /Thu Hà/ })).toBeInTheDocument();
    expect(dialog.queryByRole('option', { name: /Minh Anh/ })).not.toBeInTheDocument();
    expect(list).toHaveBeenCalledWith(expect.objectContaining({ role: 'CARETAKER' }));
  });

  it('người online xếp trước; chọn người ngoại tuyến chỉ cảnh báo, không chặn gán (BR-TN-06)', async () => {
    await startDesk('reception@store1.vn');
    vi.spyOn(identityApi, 'listStaff').mockResolvedValue(
      pageOf([makeStaff(7, { fullName: 'Bác sĩ Offline', online: false }), makeStaff(8, { fullName: 'Bác sĩ Online', online: true })]),
    );
    const assign = vi.spyOn(visitApi, 'assign').mockResolvedValue({ visit: makeVisit(2101, { assigneeId: 7, assigneeName: 'Bác sĩ Offline' }), assigneeOffline: true });
    const { user, dialog } = await openAssign('Miu');

    const options = (await dialog.findAllByRole('option')).map((o) => o.textContent);
    expect(options).toEqual(['Chọn nhân viên', 'Bác sĩ Online · trực tuyến', 'Bác sĩ Offline · ngoại tuyến']);
    await user.selectOptions(dialog.getByLabelText(/Người phụ trách/), '7');
    expect(dialog.getByText(/Bác sĩ Offline đang ngoại tuyến/)).toBeInTheDocument();
    expect(dialog.getByRole('button', { name: 'Gán' })).toBeEnabled();

    await user.click(dialog.getByRole('button', { name: 'Gán' }));
    await waitFor(() => expect(assign).toHaveBeenCalledWith(2101, { assigneeId: 7 }));
    await waitFor(() => expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining('ngoại tuyến')));
  });

  it('BE chưa mở danh sách nhân viên cho lễ tân (403): báo rõ chờ BE-1, không để trống, nút Gán tắt', async () => {
    await startDesk('reception@store1.vn');
    vi.spyOn(identityApi, 'listStaff').mockRejectedValue(new ApiError('ACCESS_DENIED', 'Bạn không có quyền', 403));
    const { dialog } = await openAssign('Miu');

    expect(await dialog.findByText('Danh sách nhân viên chờ BE-1 mở GET /api/staff cho lễ tân')).toBeInTheDocument();
    expect(dialog.getByRole('button', { name: 'Gán' })).toBeDisabled();
    expect(dialog.queryByLabelText(/Người phụ trách/)).not.toBeInTheDocument();
  });

  it('BE từ chối (400 BR-TN-05): thông điệp của BE hiện trong hộp thoại, hộp thoại vẫn mở để chọn người khác', async () => {
    await startDesk('reception@store1.vn');
    vi.spyOn(visitApi, 'assign').mockRejectedValue(new ApiError('BUSINESS_RULE_VIOLATION', 'Lượt Khám/Tiêm phải gán bác sĩ thú y (BR-TN-05)', 400));
    const { user, dialog } = await openAssign('Miu');

    await user.selectOptions(await dialog.findByLabelText(/Người phụ trách/), '5');
    await user.click(dialog.getByRole('button', { name: 'Gán' }));

    expect(await dialog.findByRole('alert')).toHaveTextContent('Lượt Khám/Tiêm phải gán bác sĩ thú y');
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});

describe('FrontDeskPanel — sắp lại, cấp cứu, hủy lượt (BR-TN-04, BR-TN-07)', () => {
  it('"Đưa lên trước": chọn lượt đích trong hàng đợi, gửi placeBeforeVisitId, hàng đợi đổi thứ tự theo BE', async () => {
    await startDesk('reception@store1.vn');
    const move = vi.spyOn(visitApi, 'move');
    const { user } = renderPanel();
    await queueTab(user);

    await user.click((await rowOf('Bim')).getByRole('button', { name: 'Đưa lên trước' }));
    const dialog = within(await screen.findByRole('dialog', { name: 'Đưa lượt lên trước' }));
    expect(await dialog.findByRole('option', { name: 'TN-2610-00007 · Tôm' })).toBeInTheDocument();
    await user.click(dialog.getByRole('button', { name: 'Xác nhận đưa lên trước' }));

    await waitFor(() => expect(move).toHaveBeenCalledWith(2103, { placeBeforeVisitId: 2102 }));
    await waitFor(() => expect(petsInQueue()).toEqual(['Miu', 'Lu', 'Mochi', 'Bim', 'Tôm']));
  });

  it('"Cấp cứu": lượt lên nhóm cấp cứu, nút biến mất, thứ tự theo BE', async () => {
    await startDesk('reception@store1.vn');
    const mark = vi.spyOn(visitApi, 'markEmergency');
    const { user } = renderPanel();
    await queueTab(user);

    await user.click((await rowOf('Tôm')).getByRole('button', { name: 'Cấp cứu' }));

    await waitFor(() => expect(mark).toHaveBeenCalledWith(2102));
    await waitFor(() => expect(petsInQueue()).toEqual(['Miu', 'Tôm', 'Lu', 'Mochi', 'Bim']));
    expect((await rowOf('Tôm')).queryByRole('button', { name: 'Cấp cứu' })).not.toBeInTheDocument();
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('Tôm'));
  });

  it('"Cấp cứu" bị 409 (đã là cấp cứu ở quầy khác): báo bằng thông điệp của quầy, không im lặng', async () => {
    await startDesk('reception@store1.vn');
    vi.spyOn(visitApi, 'markEmergency').mockRejectedValue(
      new ApiError('INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái hạng ưu tiên của lượt từ EMERGENCY sang EMERGENCY', 409),
    );
    const { user } = renderPanel();
    await queueTab(user);

    await user.click((await rowOf('Tôm')).getByRole('button', { name: 'Cấp cứu' }));

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Lượt này đã là ca cấp cứu.'));
  });

  describe('hủy lượt khách bỏ về', () => {
    const openCancel = async (pet: string) => {
      const view = renderPanel();
      await queueTab(view.user);
      await view.user.click((await rowOf(pet)).getByRole('button', { name: 'Hủy lượt' }));
      return { ...view, dialog: within(await screen.findByRole('dialog', { name: 'Hủy lượt khách bỏ về' })) };
    };

    it('hộp thoại nói rõ Order và lịch hẹn bị hủy, không tính hủy muộn; không nhập lý do thì không gửi body (A4)', async () => {
      await startDesk('reception@store1.vn');
      const cancel = vi.spyOn(visitApi, 'cancel');
      const { user, dialog } = await openCancel('Bim');

      expect(dialog.getByText(/Order của lượt .* lịch hẹn .* bị hủy/)).toBeInTheDocument();
      expect(dialog.getByText(/không tính là hủy muộn/)).toBeInTheDocument();
      expect(dialog.getByLabelText(/Lý do/)).toHaveAttribute('maxlength', '300');
      await user.click(dialog.getByRole('button', { name: 'Xác nhận hủy lượt' }));

      await waitFor(() => expect(cancel).toHaveBeenCalledWith(2103, undefined));
      await waitFor(() => expect(screen.queryByText('Bim')).not.toBeInTheDocument());
      expect(toast.success).toHaveBeenCalled();
    });

    it('có lý do thì gửi {reason}', async () => {
      await startDesk('reception@store1.vn');
      const cancel = vi.spyOn(visitApi, 'cancel');
      const { user, dialog } = await openCancel('Bim');

      await user.type(dialog.getByLabelText(/Lý do/), 'Khách bỏ về');
      await user.click(dialog.getByRole('button', { name: 'Xác nhận hủy lượt' }));

      await waitFor(() => expect(cancel).toHaveBeenCalledWith(2103, { reason: 'Khách bỏ về' }));
    });

    it('lý do toàn khoảng trắng coi như không nhập: không gửi body', async () => {
      await startDesk('reception@store1.vn');
      const cancel = vi.spyOn(visitApi, 'cancel');
      const { user, dialog } = await openCancel('Bim');

      await user.type(dialog.getByLabelText(/Lý do/), '   ');
      await user.click(dialog.getByRole('button', { name: 'Xác nhận hủy lượt' }));

      await waitFor(() => expect(cancel).toHaveBeenCalledWith(2103, undefined));
    });

    it('"Giữ lại" đóng hộp thoại, không gửi gì', async () => {
      await startDesk('reception@store1.vn');
      const cancel = vi.spyOn(visitApi, 'cancel');
      const { user, dialog } = await openCancel('Bim');

      await user.click(dialog.getByRole('button', { name: 'Giữ lại' }));

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(cancel).not.toHaveBeenCalled();
      expect(screen.getByText('Bim')).toBeInTheDocument();
    });

    it('lượt vừa được gọi ở nơi khác (409 IN_PROGRESS → CANCELLED, BR-TN-07): báo lượt đã gọi nên không hủy được', async () => {
      await startDesk('reception@store1.vn');
      vi.spyOn(visitApi, 'cancel').mockRejectedValue(
        new ApiError('INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái lượt tiếp nhận từ IN_PROGRESS sang CANCELLED', 409),
      );
      const { user, dialog } = await openCancel('Bim');

      await user.click(dialog.getByRole('button', { name: 'Xác nhận hủy lượt' }));

      expect(await dialog.findByRole('alert')).toHaveTextContent('Lượt đã được gọi nên không hủy được');
    });
  });
});

describe('FrontDeskPanel — vai trò không gán được (VET) chỉ xem, không hỏi danh sách nhân viên', () => {
  it('bác sĩ thấy hàng đợi nhưng không có nút nào, không hỏi GET /api/staff (BE 403) và không có chấm trực tuyến', async () => {
    await startDesk('doctor@store1.vn');
    const staff = vi.spyOn(identityApi, 'listStaff');
    const arrivals = vi.spyOn(visitApi, 'listArrivals');
    const { user } = renderPanel();
    await queueTab(user);
    await waitFor(() => expect(petsInQueue()).toEqual(['Miu', 'Lu', 'Mochi', 'Tôm', 'Bim']));

    expect((await rowOf('Miu')).queryAllByRole('button')).toHaveLength(0);
    expect(screen.queryByRole('img', { name: /tuyến/ })).not.toBeInTheDocument();
    expect(staff).not.toHaveBeenCalled();
    expect(arrivals).not.toHaveBeenCalled();
  });
});

describe('FrontDeskPanel — quản lý chi nhánh (A05): xem hàng đợi, chỉ gán lại lượt đã gọi (BR-TN-08)', () => {
  const writes = () => ({
    checkIn: vi.spyOn(visitApi, 'checkIn'),
    assign: vi.spyOn(visitApi, 'assign'),
    move: vi.spyOn(visitApi, 'move'),
    markEmergency: vi.spyOn(visitApi, 'markEmergency'),
    cancel: vi.spyOn(visitApi, 'cancel'),
    call: vi.spyOn(visitApi, 'call'),
    myQueue: vi.spyOn(visitApi, 'getMyQueue'),
  });

  it('thấy cả hàng đợi nhưng không có nút gán / sắp lại / cấp cứu / hủy, và không gửi lệnh ghi hay hàng đợi của tôi nào (không 403)', async () => {
    await startDesk('manager@store1.vn');
    const spies = writes();
    const { user } = renderPanel();
    await queueTab(user);
    await waitFor(() => expect(petsInQueue()).toEqual(['Miu', 'Lu', 'Mochi', 'Tôm', 'Bim']));

    for (const name of [/^Gán người$/, /^Đổi người$/, /Đưa lên trước/, /^Cấp cứu$/, /Hủy lượt/]) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
    }
    for (const spy of Object.values(spies)) expect(spy).not.toHaveBeenCalled();
  });

  it('tab Đón tiếp chỉ để xem: có danh sách nhưng không có nút Tiếp nhận hay Khách vãng lai', async () => {
    await startDesk('manager@store1.vn');
    renderPanel();

    await rowOf('Mochi');
    expect(screen.queryByRole('button', { name: 'Tiếp nhận' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Khách vãng lai/ })).not.toBeInTheDocument();
  });

  it('lượt đã gọi có cờ cần gán lại: "Gán lại" cần lý do, chọn người cùng chức vụ, rồi cờ biến mất', async () => {
    await startDesk('manager@store1.vn');
    const reassign = vi.spyOn(visitApi, 'reassign');
    const { user } = renderPanel();
    await queueTab(user);

    await user.click((await rowOf('Mochi')).getByRole('button', { name: 'Gán lại' }));
    const dialog = within(await screen.findByRole('dialog', { name: 'Gán lại lượt đã gọi' }));
    expect(await dialog.findByRole('option', { name: /Thu Hà/ })).toBeInTheDocument();
    expect(dialog.queryByRole('option', { name: /Minh Anh/ })).not.toBeInTheDocument();
    const confirm = dialog.getByRole('button', { name: 'Xác nhận gán lại' });
    expect(confirm).toBeDisabled();

    await user.selectOptions(dialog.getByLabelText(/Người phụ trách/), '6');
    expect(confirm).toBeDisabled();
    await user.type(dialog.getByLabelText(/Lý do/), '  Người cũ bị khóa tài khoản  ');
    expect(confirm).toBeEnabled();
    await user.click(confirm);

    await waitFor(() => expect(reassign).toHaveBeenCalledWith(2104, { assigneeId: 6, reason: 'Người cũ bị khóa tài khoản' }));
    const mochi = await rowOf('Mochi');
    expect(await mochi.findByText('Thu Hà')).toBeInTheDocument();
    expect(mochi.queryByText('Cần gán lại')).not.toBeInTheDocument();
    expect(mochi.queryByRole('button', { name: 'Gán lại' })).not.toBeInTheDocument();
  });

  it('người phụ trách còn đủ điều kiện thì ẩn "Gán lại" (BR-TN-08)', async () => {
    await startDesk('manager@store1.vn');
    const { user } = renderPanel();
    await queueTab(user);

    expect((await rowOf('Lu')).queryByRole('button', { name: 'Gán lại' })).not.toBeInTheDocument();
  });

  it('BE từ chối gán lại (400 BR-TN-08): thông điệp viết lại hiện trong hộp thoại', async () => {
    await startDesk('manager@store1.vn');
    vi.spyOn(visitApi, 'reassign').mockRejectedValue(new ApiError('BUSINESS_RULE_VIOLATION', 'Người phụ trách vẫn đang hoạt động (BR-TN-08)', 400));
    const { user } = renderPanel();
    await queueTab(user);

    await user.click((await rowOf('Mochi')).getByRole('button', { name: 'Gán lại' }));
    const dialog = within(await screen.findByRole('dialog', { name: 'Gán lại lượt đã gọi' }));
    await user.selectOptions(await dialog.findByLabelText(/Người phụ trách/), '6');
    await user.type(dialog.getByLabelText(/Lý do/), 'Nhầm');
    await user.click(dialog.getByRole('button', { name: 'Xác nhận gán lại' }));

    expect(await dialog.findByRole('alert')).toHaveTextContent('Người phụ trách hiện tại không còn bị khóa');
  });
});
