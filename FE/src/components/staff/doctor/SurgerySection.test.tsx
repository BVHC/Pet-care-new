import { screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { apiClient } from '../../../shared/api/axios';
import { catalogApi } from '../../../shared/api/catalog.api';
import { installMockApi } from '../../../shared/api/mock/mock-api';
import { surgeryApi } from '../../../shared/api/surgery.api';
import { visitApi } from '../../../shared/api/visit.api';
import type { Service } from '../../../shared/types/catalog';
import { renderDesk, signInAs, startDesk } from '../../../test/visit-harness';
import { SurgerySection } from './SurgerySection';

const toast = vi.hoisted(() => Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn(), warning: vi.fn() }));
vi.mock('sonner', () => ({ toast }));

// surgery.mock lúc 10:40 thứ Năm 22/10/2026: lượt 2002 Lu (VET #5 đang khám) chưa có phẫu thuật; lượt 2001 có 1 ca COMPLETED, lượt 61 có 1 ca CANCELLED (lịch sử).
// Catalog mock chưa có dịch vụ SURGERY (luồng chính nối sau) nên test tiêm 7001 / 7002 vào danh mục.

beforeAll(() => installMockApi(apiClient));
afterEach(() => vi.restoreAllMocks());

const surgeryService = (serviceId: number, name: string) =>
  ({ serviceId, name, group: 'MEDICAL', medicalType: 'SURGERY', price: 1_800_000, priceIsFrom: false, isActive: true }) as unknown as Service;
const spyServices = () => vi.spyOn(catalogApi, 'listServices').mockResolvedValue([surgeryService(7001, 'Triệt sản chó mèo'), surgeryService(7002, 'Phẫu thuật mô mềm')]);

async function renderSection(visitId: number, canManage: boolean) {
  await startDesk('doctor@store1.vn');
  const visit = await visitApi.getVisit(visitId);
  return renderDesk(<SurgerySection visit={visit} canManage={canManage} />);
}

/** Lễ tân lập cam kết bằng API rồi đăng nhập lại bác sĩ, để màn của bác sĩ hỏi lại từ máy chủ. */
async function consentAsReception(surgeryId: number) {
  await signInAs('reception@store1.vn');
  await surgeryApi.consent(surgeryId, { consentText: 'Rủi ro gây mê, chi phí dự kiến 1.800.000 ₫', signerName: 'Lê Hoàng Long', signerRelation: 'Chủ thú' });
  await signInAs('doctor@store1.vn');
}

describe('SurgerySection — VET phụ trách (UC50)', () => {
  it('lượt chưa có phẫu thuật: báo trống; lập kế hoạch (#1) chọn dịch vụ SURGERY thì ca hiện ở trạng thái chờ cam kết, chưa có nút Bắt đầu', async () => {
    spyServices();
    const { user } = await renderSection(2002, true);
    expect(await screen.findByText('Chưa có phẫu thuật nào trong lượt này.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Lập kế hoạch phẫu thuật' }));
    const dialog = await screen.findByRole('dialog', { name: 'Lập kế hoạch phẫu thuật' });
    await user.click(within(dialog).getByRole('button', { name: 'Lập kế hoạch' }));
    expect(await within(dialog).findByText('Chọn dịch vụ phẫu thuật.')).toBeInTheDocument();
    await user.selectOptions(within(dialog).getByLabelText('Dịch vụ phẫu thuật'), '7001');
    await user.type(within(dialog).getByLabelText(/Ghi chú kế hoạch/), 'Triệt sản mèo cái');
    await user.click(within(dialog).getByRole('button', { name: 'Lập kế hoạch' }));

    const list = await screen.findByRole('list', { name: 'Phẫu thuật trong lượt' });
    expect(within(list).getByText('Triệt sản chó mèo')).toBeInTheDocument();
    expect(within(list).getByText('Chờ cam kết')).toBeInTheDocument();
    expect(within(list).getByText('Kế hoạch: Triệt sản mèo cái')).toBeInTheDocument();
    expect(within(list).getByText('Chờ lễ tân lập cam kết.')).toBeInTheDocument();
    expect(within(list).queryByRole('button', { name: 'Bắt đầu phẫu thuật' })).not.toBeInTheDocument();
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('Đã lập kế hoạch phẫu thuật'));
  });

  it('có cam kết thì bắt đầu (#6) rồi hoàn tất (#7) với ghi chú kết quả: ca chuyển Đã hoàn tất, hết nút', async () => {
    spyServices();
    const { user, client } = await renderSection(2002, true);
    const planned = await surgeryApi.plan(2002, { serviceId: 7001 });
    await consentAsReception(planned.surgeryId);
    await client.invalidateQueries({ queryKey: ['surgery'] });

    await user.click(await screen.findByRole('button', { name: 'Bắt đầu phẫu thuật' }));
    expect(await screen.findByText('Đang phẫu thuật')).toBeInTheDocument();
    expect(screen.getByText(/Cam kết lúc .*, người ký Lê Hoàng Long \(Chủ thú\)/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Hoàn tất phẫu thuật' }));
    const dialog = await screen.findByRole('dialog', { name: 'Hoàn tất phẫu thuật' });
    await user.click(within(dialog).getByRole('button', { name: 'Hoàn tất' }));
    expect(await within(dialog).findByText('Nhập ghi chú kết quả phẫu thuật.')).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Kết quả phẫu thuật'), 'Thuận lợi, không biến chứng');
    await user.click(within(dialog).getByRole('button', { name: 'Hoàn tất' }));

    expect(await screen.findByText('Đã hoàn tất')).toBeInTheDocument();
    expect(screen.getByText('Kết quả: Thuận lợi, không biến chứng')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Lập kế hoạch phẫu thuật' })).toBeInTheDocument(); // chỉ còn nút lập kế hoạch mới
    expect(screen.queryByRole('button', { name: 'Hoàn tất phẫu thuật' })).not.toBeInTheDocument();
  });

  it('ca đã bị hủy ở nơi khác: bấm Bắt đầu nhận 409 từ BE và câu báo hiện ngay trong khung', async () => {
    spyServices();
    const { user, client } = await renderSection(2002, true);
    const planned = await surgeryApi.plan(2002, { serviceId: 7001 });
    await consentAsReception(planned.surgeryId);
    await client.invalidateQueries({ queryKey: ['surgery'] });
    const start = await screen.findByRole('button', { name: 'Bắt đầu phẫu thuật' });

    await surgeryApi.cancel(planned.surgeryId, { reason: 'Khách đổi ý' }); // máy khác hủy trước khi bấm
    await user.click(start);

    expect(await screen.findByRole('alert')).toHaveTextContent(/CANCELLED|IN_PROGRESS|trạng thái/i);
    await waitFor(() => expect(screen.getByText('Lý do hủy: Khách đổi ý')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: 'Bắt đầu phẫu thuật' })).not.toBeInTheDocument();
  });

  it('hủy phẫu thuật bắt buộc có lý do', async () => {
    spyServices();
    const { user, client } = await renderSection(2002, true);
    await surgeryApi.plan(2002, { serviceId: 7002 });
    await client.invalidateQueries({ queryKey: ['surgery'] });
    await user.click(await screen.findByRole('button', { name: 'Hủy phẫu thuật' }));
    const dialog = await screen.findByRole('dialog', { name: 'Hủy phẫu thuật' });
    await user.click(within(dialog).getByRole('button', { name: 'Hủy phẫu thuật' }));
    expect(await within(dialog).findByText('Nhập lý do hủy hoặc dừng.')).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText(/Lý do/), 'Chủ thú chưa đồng ý');
    await user.click(within(dialog).getByRole('button', { name: 'Hủy phẫu thuật' }));
    expect(await screen.findByText('Lý do hủy: Chủ thú chưa đồng ý')).toBeInTheDocument();
  });

  it('danh mục thật (đã nối 7001–7004): chỉ liệt kê dịch vụ phẫu thuật đang kinh doanh, không lẫn dịch vụ khám hay nội trú', async () => {
    const { user } = await renderSection(2002, true);
    await user.click(await screen.findByRole('button', { name: 'Lập kế hoạch phẫu thuật' }));
    const dialog = await screen.findByRole('dialog', { name: 'Lập kế hoạch phẫu thuật' });
    await waitFor(() => expect(within(dialog).getAllByRole('option').length).toBeGreaterThan(1));
    const names = within(dialog).getAllByRole('option').map((o) => o.textContent);
    expect(names).toEqual(['Chọn dịch vụ', 'Phẫu thuật mô mềm', 'Phẫu thuật nha khoa', 'Triệt sản chó mèo']); // sắp theo tên; 7004 ngừng kinh doanh bị loại
  });

  it('danh mục chưa có dịch vụ phẫu thuật: hộp lập kế hoạch nói rõ và không cho gửi', async () => {
    vi.spyOn(catalogApi, 'listServices').mockResolvedValue([]);
    const { user } = await renderSection(2002, true);
    await user.click(await screen.findByRole('button', { name: 'Lập kế hoạch phẫu thuật' }));
    const dialog = await screen.findByRole('dialog', { name: 'Lập kế hoạch phẫu thuật' });
    expect(await within(dialog).findByText(/chưa có dịch vụ phẫu thuật đang kinh doanh/)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Lập kế hoạch' })).toBeDisabled();
  });
});

describe('SurgerySection — người không phụ trách / lượt đã khóa chỉ xem', () => {
  it('lượt đã hoàn tất có lịch sử phẫu thuật: hiện ca và kết quả nhưng không có nút nào', async () => {
    await renderSection(2001, false);
    expect(await screen.findByText('Triệt sản chó mèo', { exact: false })).toBeInTheDocument();
    expect(screen.getByText('Đã hoàn tất')).toBeInTheDocument();
    expect(screen.getByText(/Kết quả: Phẫu thuật thuận lợi/)).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('không được lập và lượt chưa có phẫu thuật: không vẽ khung', async () => {
    await renderSection(2002, false);
    await waitFor(() => expect(screen.queryByText('Phẫu thuật')).not.toBeInTheDocument());
    expect(screen.queryByRole('button', { name: 'Lập kế hoạch phẫu thuật' })).not.toBeInTheDocument();
  });
});
