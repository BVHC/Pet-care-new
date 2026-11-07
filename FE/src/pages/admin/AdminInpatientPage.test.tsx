import { screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../shared/api/axios';
import { catalogApi } from '../../shared/api/catalog.api';
import { inpatientApi } from '../../shared/api/inpatient.api';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { visitApi } from '../../shared/api/visit.api';
import type { Service } from '../../shared/types/catalog';
import { renderDesk, signInAs, startDesk } from '../../test/visit-harness';
import { AdminInpatientPage } from './AdminInpatientPage';
import { inpatientPermissions } from './inpatient/permissions';

// Mock nội trú lúc 10:40 thứ Năm 22/10/2026, chi nhánh 1: không có ca ADMITTED; lịch sử #1 DISCHARGED (Mochi, lượt 61, 4 y lệnh, 3 lần theo dõi).
// VET #5 (doctor@store1.vn) đang khám Lu (lượt 2002); đặt chỗ lưu trú #2 Cốm, #3 Mực đang CHECKED_IN. Catalog chưa có dịch vụ INPATIENT (luồng chính nối sau): test tiêm 7101 / 7102.

beforeAll(() => installMockApi(apiClient));
afterEach(() => vi.restoreAllMocks());

const inpatientService = (serviceId: number, name: string): Service =>
  ({ serviceId, name, group: 'MEDICAL', medicalType: 'INPATIENT', price: 350_000, priceIsFrom: false, isActive: true }) as unknown as Service;
const spyServices = () => vi.spyOn(catalogApi, 'listServices').mockResolvedValue([inpatientService(7101, 'Nội trú điều trị (theo ngày)')]);

async function renderPage(email: string) {
  await startDesk(email);
  return renderDesk(<AdminInpatientPage />);
}

describe('danh sách và chi tiết (inpatient-v1 #2, #3, #6, #10)', () => {
  it('mặc định là ca đang điều trị: chi nhánh chưa có ca nào thì báo trống; tab Đã xuất viện có ca mẫu, chọn thì thấy y lệnh và chỉ số theo dõi', async () => {
    const { user } = await renderPage('doctor@store1.vn');
    expect(await screen.findByText('Chưa có thú nào đang nội trú')).toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Đã xuất viện' }));
    const row = await screen.findByRole('button', { name: /^Xem ca NT-/ });
    expect(row).toHaveTextContent('Mochi');
    await user.click(row);

    const detail = await screen.findByRole('region', { name: /^Ca nội trú NT-/ });
    expect(await within(detail).findByText(/Điều trị viêm đường hô hấp/)).toBeInTheDocument();
    const orders = await within(detail).findByRole('table', { name: 'Y lệnh nội trú' });
    expect(within(orders).getAllByRole('row')).toHaveLength(1 + 4);
    expect(within(orders).getByText('Đo nhiệt độ mỗi 4 giờ')).toBeInTheDocument();
    expect(within(orders).getByText(/Lý do hủy: Thú ổn định/)).toBeInTheDocument();
    const observations = await within(detail).findByRole('table', { name: 'Chỉ số theo dõi' });
    expect(within(observations).getAllByRole('row')).toHaveLength(1 + 3);
    expect(within(observations).getByText('Bất thường')).toBeInTheDocument();
    expect(within(detail).getByRole('img', { name: /Biểu đồ nhiệt độ/ })).toBeInTheDocument();
    // Ca đã xuất viện: không còn nút ghi nào, kể cả với VET.
    for (const name of [/Xuất viện/, /Ra y lệnh/, /Ghi chỉ số/, /Đã thực hiện/]) expect(within(detail).queryByRole('button', { name })).not.toBeInTheDocument();
  });
});

describe('VET: nhập viện, y lệnh, theo dõi, xuất viện', () => {
  it('nhập viện Lu từ lượt khám đang mở: ca mới hiện trong danh sách và mở sẵn chi tiết', async () => {
    spyServices();
    const { user } = await renderPage('doctor@store1.vn');
    await screen.findByText('Chưa có thú nào đang nội trú');

    await user.click(screen.getByRole('button', { name: 'Nhập viện' }));
    const dialog = await screen.findByRole('dialog', { name: 'Nhập viện' });
    await user.click(await within(dialog).findByRole('radio', { name: /Lu/ }));
    await user.selectOptions(within(dialog).getByLabelText('Dịch vụ nội trú'), '7101');
    await user.click(within(dialog).getByRole('button', { name: 'Nhập viện' }));

    expect(await screen.findByText(/Đã nhập viện Lu \(NT-/)).toBeInTheDocument();
    expect(await screen.findByRole('region', { name: /^Ca nội trú NT-/ })).toHaveTextContent('Nội trú điều trị (theo ngày)');
    expect(screen.getByRole('button', { name: /^Xem ca NT-/ })).toHaveTextContent('Lu');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('nhập viện từ đặt chỗ lưu trú đang CHECKED_IN (Cốm): chuyển sang tab đặt chỗ, ca mới giữ chuồng của đặt chỗ', async () => {
    spyServices();
    const { user } = await renderPage('doctor@store1.vn');
    await user.click(await screen.findByRole('button', { name: 'Nhập viện' }));
    const dialog = await screen.findByRole('dialog', { name: 'Nhập viện' });
    await user.click(within(dialog).getByRole('button', { name: 'Từ đặt chỗ lưu trú' }));
    await user.click(await within(dialog).findByRole('radio', { name: /Cốm/ }));
    await user.selectOptions(within(dialog).getByLabelText('Dịch vụ nội trú'), '7101');
    await user.click(within(dialog).getByRole('button', { name: 'Nhập viện' }));

    expect(await screen.findByText(/Đã nhập viện Cốm/)).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: /^Xem ca NT-/ })).toHaveTextContent('chuồng C-01');
  });

  it('danh mục thật (đã nối 7101–7104): chỉ liệt kê dịch vụ nội trú đang kinh doanh', async () => {
    const { user } = await renderPage('doctor@store1.vn');
    await user.click(await screen.findByRole('button', { name: 'Nhập viện' }));
    const dialog = await screen.findByRole('dialog', { name: 'Nhập viện' });
    await waitFor(() => expect(within(dialog.querySelector('select') as HTMLElement).getAllByRole('option').length).toBeGreaterThan(1));
    const names = within(dialog.querySelector('select') as HTMLElement).getAllByRole('option').map((o) => o.textContent);
    expect(names).toEqual(['Chọn dịch vụ', 'Nội trú chăm sóc đặc biệt (theo ngày)', 'Nội trú điều trị (theo ngày)', 'Nội trú theo dõi (chi nhánh 2)']);
  });

  it('danh mục chưa có dịch vụ nội trú: nói rõ và không cho gửi', async () => {
    vi.spyOn(catalogApi, 'listServices').mockResolvedValue([]);
    const { user } = await renderPage('doctor@store1.vn');
    await user.click(await screen.findByRole('button', { name: 'Nhập viện' }));
    const dialog = await screen.findByRole('dialog', { name: 'Nhập viện' });
    expect(await within(dialog).findByText(/chưa có dịch vụ nội trú đang kinh doanh/)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Nhập viện' })).toBeDisabled();
  });

  it('ra y lệnh, ghi chỉ số bất thường, rồi xuất viện: còn y lệnh chờ thì BE từ chối (BR-LT-14) và hộp thoại giữ lỗi; làm xong y lệnh thì xuất viện được', async () => {
    spyServices();
    await startDesk('doctor@store1.vn');
    const admission = await inpatientApi.admit({ visitId: 2002, serviceId: 7101 });
    const { user } = renderDesk(<AdminInpatientPage />);
    await user.click(await screen.findByRole('button', { name: `Xem ca ${admission.code}` }));

    await user.click(await screen.findByRole('button', { name: 'Ra y lệnh' }));
    const orderDialog = await screen.findByRole('dialog', { name: 'Ra y lệnh' });
    await user.type(within(orderDialog).getByLabelText('Nội dung'), 'Cho ăn pate mềm 3 bữa');
    await user.click(within(orderDialog).getByRole('button', { name: 'Ra y lệnh' }));
    expect(await screen.findByText('Đã ra y lệnh cho Lu.')).toBeInTheDocument();
    expect(await screen.findByText('Cho ăn pate mềm 3 bữa')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Ghi chỉ số' }));
    const obsDialog = await screen.findByRole('dialog', { name: 'Ghi chỉ số theo dõi' });
    await user.click(within(obsDialog).getByRole('button', { name: 'Lưu chỉ số' }));
    expect(await within(obsDialog).findByText(/Nhập ít nhất một chỉ số/)).toBeInTheDocument();
    await user.type(within(obsDialog).getByLabelText('Nhiệt độ (°C)'), '39,6');
    await user.click(within(obsDialog).getByRole('checkbox', { name: /Bất thường/ }));
    await user.click(within(obsDialog).getByRole('button', { name: 'Lưu chỉ số' }));
    expect(await screen.findByText(/Đã ghi chỉ số bất thường của Lu/)).toBeInTheDocument();
    const table = await screen.findByRole('table', { name: 'Chỉ số theo dõi' });
    expect(await within(table).findByText('39.6')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Xuất viện' }));
    const dischargeDialog = await screen.findByRole('dialog', { name: 'Xuất viện' });
    await user.type(within(dischargeDialog).getByLabelText('Tóm tắt điều trị'), 'Ăn uống tốt, về nhà theo dõi');
    await user.click(within(dischargeDialog).getByRole('button', { name: 'Xuất viện' }));
    expect(await within(dischargeDialog).findByText('Còn 1 y lệnh chưa thực hiện hoặc chưa hủy')).toBeInTheDocument();

    await user.click(within(dischargeDialog).getByRole('button', { name: 'Quay lại' }));
    await user.click(screen.getByRole('button', { name: /^Đã thực hiện: Cho ăn pate mềm/ }));
    const doneDialog = await screen.findByRole('dialog', { name: 'Đã thực hiện y lệnh' });
    await user.type(within(doneDialog).getByLabelText('Ghi chú'), 'Ăn hết');
    await user.click(within(doneDialog).getByRole('button', { name: 'Lưu' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: /^Đã thực hiện: / })).not.toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Xuất viện' }));
    const again = await screen.findByRole('dialog', { name: 'Xuất viện' });
    await user.type(within(again).getByLabelText('Tóm tắt điều trị'), 'Ăn uống tốt, về nhà theo dõi');
    await user.click(within(again).getByRole('button', { name: 'Xuất viện' }));
    expect(await screen.findByText(/Đã xuất viện Lu/)).toBeInTheDocument();
    expect(await screen.findByText('Đã xuất viện', { selector: 'span' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Xuất viện' })).not.toBeInTheDocument();
  });

  it('hủy y lệnh phải có lý do; y lệnh đã hủy ở nơi khác thì BE trả 409 và hộp thoại báo lại', async () => {
    spyServices();
    await startDesk('doctor@store1.vn');
    const admission = await inpatientApi.admit({ visitId: 2002, serviceId: 7101 });
    const order = await inpatientApi.createOrder(admission.admissionId, { orderType: 'PROCEDURE', content: 'Truyền dịch', scheduledAt: new Date(Date.now() + 3_600_000).toISOString() });
    const { user } = renderDesk(<AdminInpatientPage />);
    await user.click(await screen.findByRole('button', { name: `Xem ca ${admission.code}` }));

    await user.click(await screen.findByRole('button', { name: 'Hủy y lệnh: Truyền dịch' }));
    const dialog = await screen.findByRole('dialog', { name: 'Hủy y lệnh' });
    await user.click(within(dialog).getByRole('button', { name: 'Hủy y lệnh' }));
    expect(await within(dialog).findByText('Nhập lý do hủy.')).toBeInTheDocument();

    await inpatientApi.cancelOrder(order.admissionOrderId, { reason: 'Hủy từ máy khác' });
    await user.type(within(dialog).getByLabelText('Lý do hủy'), 'Thú ổn định');
    await user.click(within(dialog).getByRole('button', { name: 'Hủy y lệnh' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/ORDERED|CANCELLED|trạng thái/i);
  });
});

describe('VET: y lệnh thuốc phải gắn dòng đơn của lượt (BR-LT-15)', () => {
  const openOrderDialog = async (prescribe: boolean) => {
    spyServices();
    await startDesk('doctor@store1.vn');
    if (prescribe) await visitApi.addPrescription(2002, { productId: 3, quantity: 10, dosageInstructions: 'Ngày 2 lần, mỗi lần 1 viên sau ăn' });
    const admission = await inpatientApi.admit({ visitId: 2002, serviceId: 7101 });
    const { user } = renderDesk(<AdminInpatientPage />);
    await user.click(await screen.findByRole('button', { name: `Xem ca ${admission.code}` }));
    await user.click(await screen.findByRole('button', { name: 'Ra y lệnh' }));
    const dialog = await screen.findByRole('dialog', { name: 'Ra y lệnh' });
    await user.selectOptions(within(dialog).getByLabelText('Loại y lệnh'), 'MEDICATION');
    return { user, dialog };
  };

  it('lượt chưa có đơn thuốc: nói rõ, không chọn được dòng đơn và BE/FE không cho ra y lệnh thuốc', async () => {
    const { user, dialog } = await openOrderDialog(false);
    expect(await within(dialog).findByText(/Lượt này chưa có đơn thuốc/)).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Nội dung'), 'Amoxicillin sáng chiều');
    await user.click(within(dialog).getByRole('button', { name: 'Ra y lệnh' }));
    expect(await within(dialog).findByText('Chọn dòng đơn thuốc của lượt khám.')).toBeInTheDocument();
  });

  it('có đơn thuốc: chọn dòng đơn rồi ra y lệnh thuốc; y lệnh hiện trong bảng', async () => {
    const { user, dialog } = await openOrderDialog(true);
    await user.selectOptions(await within(dialog).findByLabelText('Dòng đơn thuốc'), await within(dialog).findByRole('option', { name: /Amoxicillin/ }));
    await user.type(within(dialog).getByLabelText('Nội dung'), 'Amoxicillin sáng chiều');
    await user.click(within(dialog).getByRole('button', { name: 'Ra y lệnh' }));
    expect(await screen.findByText('Amoxicillin sáng chiều')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Đã thực hiện: Amoxicillin sáng chiều' })).toBeInTheDocument();
  });
});

describe('quyền theo vai trò: nút ngoài vai trò bị ẩn', () => {
  it('lễ tân chỉ xem: thấy ca, y lệnh, chỉ số nhưng không có nút Nhập viện / Ra y lệnh / Ghi chỉ số / Xuất viện / Đã thực hiện / Hủy', async () => {
    spyServices();
    await startDesk('doctor@store1.vn');
    const admission = await inpatientApi.admit({ visitId: 2002, serviceId: 7101 });
    await inpatientApi.createOrder(admission.admissionId, { orderType: 'FEEDING', content: 'Pate mềm', scheduledAt: new Date(Date.now() + 3_600_000).toISOString() });
    await signInAs('reception@store1.vn');
    const { user } = renderDesk(<AdminInpatientPage />);

    await user.click(await screen.findByRole('button', { name: `Xem ca ${admission.code}` }));
    expect(await screen.findByText('Pate mềm')).toBeInTheDocument();
    for (const name of [/^Nhập viện$/, /Ra y lệnh/, /Ghi chỉ số/, /Xuất viện/, /Đã thực hiện/, /^Hủy/]) expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
  });

  it('chăm sóc thú: thực hiện y lệnh không phải thuốc và ghi chỉ số được, nhưng không nhập viện / ra y lệnh / hủy / xuất viện', async () => {
    spyServices();
    await startDesk('doctor@store1.vn');
    const admission = await inpatientApi.admit({ visitId: 2002, serviceId: 7101 });
    await inpatientApi.createOrder(admission.admissionId, { orderType: 'FEEDING', content: 'Pate mềm', scheduledAt: new Date(Date.now() + 3_600_000).toISOString() });
    await signInAs('caretaker@store1.vn');
    const { user } = renderDesk(<AdminInpatientPage />);

    await user.click(await screen.findByRole('button', { name: `Xem ca ${admission.code}` }));
    expect(await screen.findByRole('button', { name: 'Đã thực hiện: Pate mềm' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Ghi chỉ số' })).toBeInTheDocument();
    for (const name of [/^Nhập viện$/, /Ra y lệnh/, /Xuất viện/, /^Hủy/]) expect(screen.queryByRole('button', { name })).not.toBeInTheDocument();
  });

  it('ma trận quyền: y lệnh thuốc chỉ VET thực hiện được', () => {
    expect(inpatientPermissions('VET').completeOrder('MEDICATION')).toBe(true);
    expect(inpatientPermissions('CARETAKER').completeOrder('MEDICATION')).toBe(false);
    expect(inpatientPermissions('CARETAKER').completeOrder('FEEDING')).toBe(true);
    expect(inpatientPermissions('RECEPTIONIST')).toMatchObject({ admit: false, observe: false });
  });
});
