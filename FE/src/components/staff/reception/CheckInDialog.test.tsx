import { screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../../shared/api/api-error';
import { apiClient } from '../../../shared/api/axios';
import { branchApi } from '../../../shared/api/branch.api';
import { customerPetApi } from '../../../domains/pets/customer-pet.api';
import { installMockApi } from '../../../shared/api/mock/mock-api';
import { profileApi } from '../../../shared/api/profile.api';
import { useSession } from '../../../shared/stores/session.store';
import { visitApi } from '../../../shared/api/visit.api';
import { asAccount } from '../../../test/cashier-harness';
import { at, renderDesk, startDesk } from '../../../test/visit-harness';
import { CheckInDialog, type CheckInTarget } from './CheckInDialog';

const toast = vi.hoisted(() => Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
vi.mock('sonner', () => ({ toast }));

// Đồng hồ ghim thứ Năm 22/10/2026 10:40 (harness). Lịch hẹn mẫu #1: Mochi (thú #1) của Nguyễn Thu Trang, Khám tổng quát 11:00, BOOKED.
// Khách Lê Hoàng Long (#3) có thú Lu (#4) đang được khám (lượt 2002). Giờ mở cửa chi nhánh 1: 08:00–12:00 và 13:30–19:00, cờ cấp cứu ngoài giờ BẬT.

beforeAll(() => installMockApi(apiClient));
afterEach(() => vi.useRealTimers());

async function openAppointment() {
  const [arrival] = (await visitApi.listArrivals()).content;
  return mount({ mode: 'appointment', arrival });
}

async function openWalkIn() {
  return mount({ mode: 'walk-in' });
}

async function mount(target: CheckInTarget) {
  const onClose = vi.fn();
  const view = renderDesk(<CheckInDialog target={target} onClose={onClose} />);
  const name = target.mode === 'walk-in' ? 'Tiếp nhận khách vãng lai' : 'Tiếp nhận lịch hẹn';
  return { ...view, onClose, dialog: within(await screen.findByRole('dialog', { name })) };
}

type Mounted = Awaited<ReturnType<typeof mount>>;

// Hai hồ sơ cùng tên và cùng SĐT (BR-KH-10): #2 (đăng ký online, chưa có thú) và #3 (lập tại quầy, có Lu); phân biệt bằng email đã che.
const LONG_WITH_LU = /lo\*\*\*@gmail\.com/;

/** Tìm khách theo tên rồi chọn thú. */
async function pickPet({ user, dialog }: Mounted, term: string, customer: RegExp, pet: RegExp) {
  await user.type(dialog.getByLabelText('Tìm khách tiếp nhận'), term);
  await user.click(await dialog.findByRole('button', { name: customer }));
  await user.click(await dialog.findByRole('button', { name: pet }));
}

const queue = async () => (await visitApi.listQueue()).map((v) => ({ id: v.visitId, pet: v.petName, priority: v.priorityClass, appointmentId: v.appointmentId, group: v.serviceGroup }));
const newest = async () => (await visitApi.listQueue()).find((v) => v.visitId > 3000);

describe('CheckInDialog — lịch hẹn (UC44; BR-TN-02, BR-TN-03)', () => {
  it('điền sẵn thông tin lịch hẹn: giờ hẹn, thú, chủ nuôi, dịch vụ và gợi ý giờ đến', async () => {
    await startDesk('reception@store1.vn');
    const { dialog } = await openAppointment();

    expect(dialog.getByText('11:00')).toBeInTheDocument();
    expect(dialog.getByText('Mochi')).toBeInTheDocument();
    expect(dialog.getByText('Nguyễn Thu Trang')).toBeInTheDocument();
    expect(dialog.getByText('Khám tổng quát')).toBeInTheDocument();
    expect(dialog.getByText('Đến sớm 20 phút')).toBeInTheDocument();
  });

  it('gửi {appointmentId, petId} (thú của lịch, không serviceId, không isEmergency), báo mã lượt, hàng đợi có lượt ưu tiên lịch hẹn', async () => {
    await startDesk('reception@store1.vn');
    const checkIn = vi.spyOn(visitApi, 'checkIn');
    const { user, dialog, onClose } = await openAppointment();

    await user.click(dialog.getByRole('button', { name: 'Tiếp nhận' }));

    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
    expect(checkIn).toHaveBeenCalledWith({ appointmentId: 1, petId: 1 });
    const created = await newest();
    expect(created).toMatchObject({ petName: 'Mochi', priorityClass: 'APPOINTMENT', appointmentId: 1, status: 'WAITING' });
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining(created!.code), expect.objectContaining({ description: expect.stringContaining('tab Hàng chờ') }));
  });

  it('khách trễ 20 phút: BE xếp như khách vãng lai nhưng lượt vẫn gắn lịch hẹn (BR-TN-03)', async () => {
    await startDesk('reception@store1.vn', at('11:20'));
    const { user, dialog, onClose } = await openAppointment();
    expect(dialog.getByText('Trễ 20 phút: xếp như khách vãng lai')).toBeInTheDocument();

    await user.click(dialog.getByRole('button', { name: 'Tiếp nhận' }));

    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
    expect(await newest()).toMatchObject({ priorityClass: 'WALK_IN', appointmentId: 1 });
  });

  it('cấp cứu: gửi đúng tên trường isEmergency (BE bỏ qua trường lạ như "emergency") và lượt thành EMERGENCY', async () => {
    await startDesk('reception@store1.vn');
    const checkIn = vi.spyOn(visitApi, 'checkIn');
    const { user, dialog, onClose } = await openAppointment();

    await user.click(dialog.getByRole('checkbox', { name: /Cấp cứu/ }));
    await user.click(dialog.getByRole('button', { name: 'Tiếp nhận' }));

    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
    expect(checkIn).toHaveBeenCalledWith({ appointmentId: 1, petId: 1, isEmergency: true });
    expect(checkIn.mock.calls[0][0]).not.toHaveProperty('emergency');
    expect(await newest()).toMatchObject({ priorityClass: 'EMERGENCY' });
  });

  it('thú của lịch đang có lượt mở: cảnh báo sẽ tạo lượt và Order thứ hai nhưng không chặn (BE không chặn)', async () => {
    await startDesk('reception@store1.vn');
    const { user, dialog, onClose } = await openAppointment();

    // Mochi đang có lượt grooming 2104 (TN-2610-00006) trong hàng đợi.
    expect(await dialog.findByRole('status')).toHaveTextContent('Mochi đang có lượt TN-2610-00006');
    expect(dialog.getByRole('status')).toHaveTextContent('Order thứ hai');
    await user.click(dialog.getByRole('button', { name: 'Tiếp nhận' }));
    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
  });

  describe('lỗi của BE hiện ngay trong hộp thoại và hộp thoại vẫn mở', () => {
    it('ngoài cửa sổ (đến quá sớm: 10:29 so với giờ hẹn 11:00, BR-TN-02): nêu cả hai mốc của cửa sổ', async () => {
      await startDesk('reception@store1.vn');
      const { user, dialog, onClose } = await openAppointment();
      vi.setSystemTime(new Date(at('10:29')));

      await user.click(dialog.getByRole('button', { name: 'Tiếp nhận' }));

      expect(await dialog.findByRole('alert')).toHaveTextContent(
        'Chỉ tiếp nhận lịch hẹn của hôm nay, sớm nhất 30 phút trước giờ hẹn và trễ nhất 30 phút sau giờ hẹn',
      );
      expect(onClose).not.toHaveBeenCalled();
    });

    it('quá 30 phút (11:31): lịch đã NO_SHOW (409), báo tiếp nhận như khách vãng lai', async () => {
      await startDesk('reception@store1.vn');
      const { user, dialog, onClose } = await openAppointment();
      vi.setSystemTime(new Date(at('11:31')));

      await user.click(dialog.getByRole('button', { name: 'Tiếp nhận' }));

      expect(await dialog.findByRole('alert')).toHaveTextContent('Lịch hẹn đã quá giờ nên tính là không đến. Nếu khách vẫn đến, hãy tiếp nhận như khách vãng lai');
      expect(onClose).not.toHaveBeenCalled();
    });

    it('lịch đã được tiếp nhận ở quầy khác (409 CHECKED_IN): bảo xem hàng đợi, không khuyên tiếp nhận thêm', async () => {
      await startDesk('reception@store1.vn');
      const { user, dialog } = await openAppointment();
      await visitApi.checkIn({ appointmentId: 1, petId: 1 });

      await user.click(dialog.getByRole('button', { name: 'Tiếp nhận' }));

      expect(await dialog.findByRole('alert')).toHaveTextContent('Lịch hẹn này đã được tiếp nhận rồi');
    });
  });
});

describe('CheckInDialog — khách vãng lai và cấp cứu (BR-TN-01, BR-CN-05)', () => {
  it('tìm khách, chọn thú và dịch vụ → gửi {petId, serviceId}; lượt là vãng lai; cảnh báo vì Lu đang được khám', async () => {
    await startDesk('reception@store1.vn');
    const checkIn = vi.spyOn(visitApi, 'checkIn');
    const view = await openWalkIn();
    const { user, dialog, onClose } = view;

    await pickPet(view, 'Long', LONG_WITH_LU, /^Lu/);
    expect(await dialog.findByRole('status')).toHaveTextContent('Lu đang có lượt TN-2610-00005');
    await user.click(dialog.getByRole('radio', { name: /Khám tổng quát/ }));
    await user.click(dialog.getByRole('button', { name: 'Tiếp nhận' }));

    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
    expect(checkIn).toHaveBeenCalledWith({ petId: 4, serviceId: 1 });
    expect(await newest()).toMatchObject({ petName: 'Lu', priorityClass: 'WALK_IN', appointmentId: null, serviceGroup: 'MEDICAL' });
    expect(toast.success).toHaveBeenCalledWith(expect.stringMatching(/Lu.*TN-2610-\d{5}/), expect.anything());
  });

  it('chỉ liệt kê dịch vụ Khám/Tiêm và Thẩm mỹ đang bật tại chi nhánh, không có Lưu trú', async () => {
    await startDesk('reception@store1.vn');
    const { dialog } = await openWalkIn();

    expect(await dialog.findByRole('radio', { name: /Khám tổng quát/ })).toBeInTheDocument();
    expect(dialog.getAllByRole('radio').map((r) => r.closest('label')?.textContent)).toEqual([
      expect.stringContaining('Cắt tỉa lông'),
      expect.stringContaining('Khám tổng quát'),
      expect.stringContaining('Tắm & sấy'),
      expect.stringContaining('Tiêm phòng'),
    ]);
  });

  it('thiếu thú hoặc dịch vụ: nói thiếu gì, không gửi request', async () => {
    await startDesk('reception@store1.vn');
    const checkIn = vi.spyOn(visitApi, 'checkIn');
    const { user, dialog } = await openWalkIn();

    await user.click(dialog.getByRole('button', { name: 'Tiếp nhận' }));

    expect(await dialog.findByRole('alert')).toHaveTextContent('Chọn thú cưng và dịch vụ trước khi tiếp nhận.');
    expect(checkIn).not.toHaveBeenCalled();
  });

  it('cấp cứu không hẹn trước: {petId, serviceId, isEmergency: true} và lượt lên đầu hàng đợi', async () => {
    await startDesk('reception@store1.vn');
    const checkIn = vi.spyOn(visitApi, 'checkIn');
    const view = await openWalkIn();
    const { user, dialog, onClose } = view;

    await pickPet(view, 'Long', LONG_WITH_LU, /^Lu/);
    await user.click(dialog.getByRole('radio', { name: /Khám tổng quát/ }));
    await user.click(dialog.getByRole('checkbox', { name: /Cấp cứu/ }));
    await user.click(dialog.getByRole('button', { name: 'Tiếp nhận' }));

    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
    expect(checkIn).toHaveBeenCalledWith({ petId: 4, serviceId: 1, isEmergency: true });
    const list = await queue();
    expect(list.filter((v) => v.priority === 'EMERGENCY').map((v) => v.pet)).toEqual(['Miu', 'Lu']);
  });

  it('Ctrl+Enter tiếp nhận như bấm nút', async () => {
    await startDesk('reception@store1.vn');
    const checkIn = vi.spyOn(visitApi, 'checkIn');
    const view = await openWalkIn();
    const { user, dialog, onClose } = view;

    await pickPet(view, 'Long', LONG_WITH_LU, /^Lu/);
    await user.click(dialog.getByRole('radio', { name: /Khám tổng quát/ }));
    await user.keyboard('{Control>}{Enter}{/Control}');

    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
    expect(checkIn).toHaveBeenCalledTimes(1);
  });

  it('thú không có lượt mở thì không cảnh báo', async () => {
    await startDesk('reception@store1.vn');
    vi.spyOn(visitApi, 'listQueue').mockResolvedValue([]);
    const view = await openWalkIn();

    await pickPet(view, 'Long', LONG_WITH_LU, /^Lu/);

    expect(view.dialog.queryByRole('status')).not.toBeInTheDocument();
  });

  it('thú đã mất không chọn được (BR-KH-05, BR-TN-01)', async () => {
    await startDesk('reception@store1.vn');
    const pet = await customerPetApi.addPetForCustomer(3, { name: 'Cún cũ', species: 'DOG', sex: 'MALE' });
    await customerPetApi.markDeceased(pet.pet.petId, { deceasedOn: '2026-10-20', confirmed: true });
    const view = await openWalkIn();

    await view.user.type(view.dialog.getByLabelText('Tìm khách tiếp nhận'), 'Long');
    await view.user.click(await view.dialog.findByRole('button', { name: LONG_WITH_LU }));

    expect(await view.dialog.findByRole('button', { name: /Cún cũ/ })).toBeDisabled();
    expect(view.dialog.getByRole('button', { name: /^Lu/ })).toBeEnabled();
  });

  it('BE vẫn từ chối thú đã mất (400 BR-TN-01, chặn bởi BE chứ không phải BR-KH-05): hiện thông điệp của BE', async () => {
    await startDesk('reception@store1.vn');
    vi.spyOn(visitApi, 'checkIn').mockRejectedValue(new ApiError('BUSINESS_RULE_VIOLATION', 'Thú cưng đã mất, không tiếp nhận được (BR-TN-01)', 400));
    const view = await openAppointment();

    await view.user.click(view.dialog.getByRole('button', { name: 'Tiếp nhận' }));

    expect(await view.dialog.findByRole('alert')).toHaveTextContent('Thú cưng đã mất, không tiếp nhận được');
    expect(view.dialog.getByRole('alert')).not.toHaveTextContent('BR-TN-01');
  });

  describe('ngoài giờ mở cửa (BR-CN-05): 12:30 là giờ nghỉ trưa, chi nhánh 1 đóng cửa', () => {
    it('không đánh dấu cấp cứu: BE chỉ nhận ca cấp cứu; đánh dấu cấp cứu thì tiếp nhận được', async () => {
      await startDesk('reception@store1.vn', at('12:30'));
      const view = await openWalkIn();
      const { user, dialog, onClose } = view;
      await pickPet(view, 'Long', LONG_WITH_LU, /^Lu/);
      await user.click(dialog.getByRole('radio', { name: /Khám tổng quát/ }));

      await user.click(dialog.getByRole('button', { name: 'Tiếp nhận' }));
      expect(await dialog.findByRole('alert')).toHaveTextContent('Ngoài giờ mở cửa chỉ tiếp nhận ca cấp cứu');
      expect(onClose).not.toHaveBeenCalled();

      await user.click(dialog.getByRole('checkbox', { name: /Cấp cứu/ }));
      await user.click(dialog.getByRole('button', { name: 'Tiếp nhận' }));
      await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
      expect(await newest()).toMatchObject({ petName: 'Lu', priorityClass: 'EMERGENCY' });
    });

    it('chi nhánh tắt cờ cấp cứu ngoài giờ: cả ca cấp cứu cũng bị từ chối, hiện thông điệp của BE', async () => {
      await startDesk('reception@store1.vn', at('12:30'));
      await asAccount('chuoi@petcare.vn', 'reception@store1.vn', () => branchApi.updateBranch(1, { acceptsAfterHoursEmergency: false }));
      const view = await openWalkIn();
      const { user, dialog } = view;
      await pickPet(view, 'Long', LONG_WITH_LU, /^Lu/);
      await user.click(dialog.getByRole('radio', { name: /Khám tổng quát/ }));
      await user.click(dialog.getByRole('checkbox', { name: /Cấp cứu/ }));

      await user.click(dialog.getByRole('button', { name: 'Tiếp nhận' }));

      expect(await dialog.findByRole('alert')).toHaveTextContent('Ngoài giờ mở cửa, chi nhánh không nhận cấp cứu ngoài giờ');
    });
  });
});

describe('CheckInDialog — chi nhánh của nhân viên (đăng nhập thật không trả branchId)', () => {
  const withoutBranch = () => useSession.setState((state) => ({ account: { ...state.account!, branchId: undefined } }));
  const me = (branchId: number | null) => ({
    account: useSession.getState().account!,
    staffProfile: { accountId: 4, fullName: 'Lan Chi', phone: '0900000000', branchId },
    linkDecisionPending: false,
  });

  it('chưa biết chi nhánh thì không hỏi dịch vụ (không gọi chi nhánh 0) và nói rõ vì sao chưa chọn được dịch vụ', async () => {
    await startDesk('reception@store1.vn');
    withoutBranch();
    vi.spyOn(profileApi, 'me').mockResolvedValue(me(null));
    const services = vi.spyOn(branchApi, 'listServices');
    const { dialog } = await openWalkIn();

    expect(await dialog.findByText('Chưa xác định được chi nhánh của bạn nên chưa liệt kê được dịch vụ.')).toBeInTheDocument();
    await new Promise((r) => setTimeout(r, 50));
    expect(services).not.toHaveBeenCalled();
  });

  it('phiên không có branchId thì lấy từ GET /me rồi hỏi đúng chi nhánh đó', async () => {
    await startDesk('reception@store1.vn');
    withoutBranch();
    vi.spyOn(profileApi, 'me').mockResolvedValue(me(1));
    const services = vi.spyOn(branchApi, 'listServices');
    const { dialog } = await openWalkIn();

    expect(await dialog.findByRole('radio', { name: /Khám tổng quát/ })).toBeInTheDocument();
    expect(services).toHaveBeenCalledWith(1);
    expect(services).not.toHaveBeenCalledWith(0);
  });

  it('tải dịch vụ lỗi: báo lỗi và có nút thử lại, không để lưới trống', async () => {
    await startDesk('reception@store1.vn');
    vi.spyOn(branchApi, 'listServices').mockRejectedValueOnce(new ApiError('INTERNAL_ERROR', 'Máy chủ bận', 500));
    const { user, dialog } = await openWalkIn();

    expect(await dialog.findByText('Không tải được dịch vụ')).toBeInTheDocument();
    await user.click(dialog.getByRole('button', { name: 'Thử lại' }));
    expect(await dialog.findByRole('radio', { name: /Khám tổng quát/ })).toBeInTheDocument();
  });
});
