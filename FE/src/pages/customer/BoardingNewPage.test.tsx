import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import axios from 'axios';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { customerPetApi } from '../../domains/pets/customer-pet.api';
import { apiClient } from '../../shared/api/axios';
import { boardingApi } from '../../shared/api/boarding.api';
import { branchApi } from '../../shared/api/branch.api';
import { catalogApi } from '../../shared/api/catalog.api';
import { MOCK_PASSWORD } from '../../shared/api/mock/identity.mock';
import { installMockApi } from '../../shared/api/mock/mock-api';
import { recordDoseForMock } from '../../shared/api/mock/visit-clinical.mock';
import { useSession } from '../../shared/stores/session.store';
import type { PublicBranch } from '../../shared/types/branch';
import type { BookingResult } from '../../shared/types/boarding';
import type { PublicService } from '../../shared/types/catalog';
import { pinClock } from '../../test/boarding-harness';
import { BoardingNewPage } from './BoardingNewPage';
import { apiFailure } from './boarding/testing';

// Giờ ghim: thứ Sáu 23/10/2026 09:00 (giờ VN). khach@petcare.vn: Mochi (mèo 4,2 kg), Bim (chó 6,5 kg, đã có đặt chỗ #4 26/10→28/10, #6 hủy 01/11→03/11), Tôm (hamster, chưa cân).
// Chi nhánh 1 là chi nhánh ACTIVE duy nhất, loại chuồng #5 "Chuồng chó nhỏ" (chó ≤ 10 kg, 200.000 đ/đêm, sức chứa 3). Đêm 26/10 và 27/10 đã kín
// (Cốm + Mực + Bim #4) vì C-04 đang bảo trì; đêm 24/10 còn 1 chỗ. Loại #6 "Chuồng mèo" chưa bật ở chi nhánh nào. 12/11 là ngày nghỉ của chi nhánh.

const raw = axios.create();
beforeAll(() => {
  installMockApi(apiClient);
  installMockApi(raw);
});
beforeEach(async () => {
  pinClock();
  useSession.getState().clear();
  await useSession.getState().login({ email: 'khach@petcare.vn', password: MOCK_PASSWORD });
});
afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

const asStaff = async (email: string) => ({
  headers: { Authorization: `Bearer ${(await raw.post('/api/auth/login', { email, password: MOCK_PASSWORD })).data.data.accessToken}` },
});

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/boarding-bookings/new']}>
        <BoardingNewPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { user: userEvent.setup(), client };
}
type User = ReturnType<typeof userEvent.setup>;

const form = () => screen.findByRole('form', { name: 'Thông tin đặt chỗ lưu trú' });
const petButton = async (name: RegExp) => within(await screen.findByRole('group', { name: 'Chọn thú cưng' })).findByRole('button', { name });
const kennelGroup = () => screen.getByRole('group', { name: 'Chọn loại chuồng' });
const submitButton = () => screen.getByRole('button', { name: /Xác nhận đặt chỗ|Đang đặt chỗ/ });
const summary = () => screen.getByRole('complementary', { name: 'Tóm tắt đặt chỗ' });

/** Mở lịch của một ô ngày, sang `months` tháng sau, chọn ngày có tên `day` (dd/mm/yyyy). */
async function pickDate(user: User, field: 'Ngày nhận' | 'Ngày trả', day: string, months = 0) {
  await user.click(screen.getByRole('button', { name: field }));
  for (let i = 0; i < months; i++) await user.click(screen.getByRole('button', { name: 'Tháng sau' }));
  await user.click(screen.getByRole('button', { name: day }));
}

interface Fill {
  pet?: RegExp;
  checkIn?: [string, number];
  checkOut?: [string, number];
}
/** Chọn thú, ngày nhận và ngày trả (chi nhánh và loại chuồng duy nhất được chọn sẵn). */
async function fill(user: User, { pet = /^Bim/, checkIn = ['24/10/2026', 0], checkOut = ['25/10/2026', 0] }: Fill = {}) {
  await user.click(await petButton(pet));
  await pickDate(user, 'Ngày nhận', checkIn[0], checkIn[1]);
  await pickDate(user, 'Ngày trả', checkOut[0], checkOut[1]);
}
const strip = () => screen.findByRole('list', { name: 'Chỗ còn lại theo đêm' });

/** Lấy lại danh sách thú sau khi thêm thú mới qua API. */
async function addDog(name: string, weightKg?: number) {
  const { pet } = await customerPetApi.addMyPet({ name, species: 'DOG', sex: 'MALE' });
  if (weightKg) await customerPetApi.addWeight(pet.petId, { weightKg });
  return pet.petId;
}

const branchFixture = (over: Partial<PublicBranch>): PublicBranch => ({
  branchId: 1,
  name: 'PetCare Cầu Giấy',
  address: '1 Cầu Giấy, Hà Nội',
  phone: '0241111111',
  latitude: 21,
  longitude: 105,
  acceptsAfterHoursEmergency: false,
  weeklyHours: [],
  ...over,
});
const kennelFixture = (over: Partial<PublicService>): PublicService => ({
  serviceId: 5,
  name: 'Chuồng chó nhỏ',
  group: 'BOARDING',
  price: 200_000,
  priceIsFrom: false,
  description: null,
  kennelType: { species: 'DOG', maxWeightKg: 10 },
  branchIds: [1],
  ...over,
});

describe('BoardingNewPage (UC58): bước 1-3, thú cưng, chi nhánh, loại chuồng phù hợp', () => {
  it('một <h1> "Đặt chỗ lưu trú"; liệt kê thú cưng kèm loài và cân nặng hiện tại (chưa cân thì ghi rõ)', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { level: 1, name: 'Đặt chỗ lưu trú' })).toBeInTheDocument();
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(await petButton(/^Bim/)).toHaveTextContent('Chó, 6,5 kg');
    // Khách được phép vào trang: tải thú, chi nhánh, loại chuồng không phát sinh lỗi (không 401 / 403).
    expect(await screen.findByRole('button', { name: /PetCare Cầu Giấy/ })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(await petButton(/^Mochi/)).toHaveTextContent('Mèo, 4,2 kg');
    expect(await petButton(/^Tôm/)).toHaveTextContent('Khác, chưa cân');
  });

  it('thú đã mất (BR-KH-05) hiện nhưng không chọn được', async () => {
    await customerPetApi.markDeceased(3, { deceasedOn: '2026-10-22', confirmed: true });
    const { user } = renderPage();
    const tom = await petButton(/^Tôm/);
    expect(tom).toBeDisabled();
    expect(tom).toHaveTextContent('đã mất');
    await user.click(tom);
    expect(tom).toHaveAttribute('aria-pressed', 'false');
    expect(await petButton(/^Bim/)).toBeEnabled();
  });

  it('chỉ có một chi nhánh nhận lưu trú thì chọn sẵn', async () => {
    renderPage();
    expect(await screen.findByRole('button', { name: /PetCare Cầu Giấy/ })).toHaveAttribute('aria-pressed', 'true');
  });

  it('chó 6,5 kg: chỉ thấy "Chuồng chó nhỏ" kèm loài, cân nặng tối đa và giá đêm, được chọn sẵn vì là loại duy nhất', async () => {
    const { user } = renderPage();
    await user.click(await petButton(/^Bim/));
    const small = await within(kennelGroup()).findByRole('button', { name: /Chuồng chó nhỏ/ });
    expect(small).toHaveTextContent('Chó, tối đa 10 kg');
    expect(small).toHaveTextContent(/200\.000.*\/đêm/);
    expect(small).toHaveAttribute('aria-pressed', 'true');
    expect(within(kennelGroup()).getAllByRole('button')).toHaveLength(1);
  });

  it('chó 18,3 kg quá cân (BR-LT-02): không hiện loại nào, giải thích rõ và không có nút chọn chuồng chó nhỏ', async () => {
    await addDog('Lu', 18.3);
    const { user } = renderPage();
    await user.click(await petButton(/^Lu/));
    expect(await screen.findByText(/Hiện chưa có loại chuồng nào phù hợp với Lu \(chó, 18,3 kg\)/)).toBeInTheDocument();
    expect(within(kennelGroup()).queryAllByRole('button')).toHaveLength(0);
  });

  it('mèo: chưa chi nhánh nào bật loại chuồng mèo nên không có loại nào và có lời giải thích', async () => {
    const { user } = renderPage();
    await user.click(await petButton(/^Mochi/));
    expect(await screen.findByText(/Hiện chưa có loại chuồng nào phù hợp với Mochi \(mèo, 4,2 kg\)/)).toBeInTheDocument();
    expect(within(kennelGroup()).queryAllByRole('button')).toHaveLength(0);
  });

  it('bật loại chuồng mèo ở chi nhánh 1 thì mèo thấy "Chuồng mèo" còn chó vẫn chỉ thấy chuồng chó', async () => {
    const manager = await asStaff('manager@store1.vn');
    await raw.put('/api/branches/1/services/6', { enabled: true }, manager);
    const { user } = renderPage();
    await user.click(await petButton(/^Mochi/));
    const cat = await within(kennelGroup()).findByRole('button', { name: /Chuồng mèo/ });
    expect(cat).toHaveTextContent('Mèo, tối đa 8 kg');
    expect(cat).toHaveTextContent(/180\.000/);
    expect(within(kennelGroup()).queryByRole('button', { name: /Chuồng chó nhỏ/ })).not.toBeInTheDocument();
    await user.click(await petButton(/^Bim/));
    expect(within(kennelGroup()).queryByRole('button', { name: /Chuồng mèo/ })).not.toBeInTheDocument();
    expect(within(kennelGroup()).getByRole('button', { name: /Chuồng chó nhỏ/ })).toBeInTheDocument();
  });

  it('giá lấy từ dữ liệu công khai: đổi giá loại chuồng thì form hiện giá mới', async () => {
    await raw.patch('/api/services/5', { price: 250_000 }, await asStaff('chuoi@petcare.vn'));
    const { user } = renderPage();
    await user.click(await petButton(/^Bim/));
    expect(await within(kennelGroup()).findByRole('button', { name: /Chuồng chó nhỏ/ })).toHaveTextContent(/250\.000/);
  });

  it('nhiều chi nhánh: chỉ liệt kê chi nhánh có nhận lưu trú, không chọn sẵn; chi nhánh thiếu loại phù hợp thì nêu chi nhánh có', async () => {
    vi.spyOn(branchApi, 'listPublicBranches').mockResolvedValue([
      branchFixture({}),
      branchFixture({ branchId: 2, name: 'PetCare Quận 7', address: '9 Nguyễn Thị Thập, TP.HCM' }),
      branchFixture({ branchId: 3, name: 'PetCare Thủ Đức', address: '5 Võ Văn Ngân, TP.HCM' }),
    ]);
    vi.spyOn(catalogApi, 'listPublicServices').mockResolvedValue([
      kennelFixture({ branchIds: [1] }),
      kennelFixture({ serviceId: 6, name: 'Chuồng mèo', price: 180_000, kennelType: { species: 'CAT', maxWeightKg: 8 }, branchIds: [2] }),
    ]);
    const { user } = renderPage();
    const group = await screen.findByRole('group', { name: 'Chọn chi nhánh' });
    await waitFor(() => expect(within(group).getAllByRole('button')).toHaveLength(2));
    expect(within(group).queryByRole('button', { name: /Thủ Đức/ })).not.toBeInTheDocument();
    expect(within(group).getByRole('button', { name: /Cầu Giấy/ })).toHaveAttribute('aria-pressed', 'false');
    await user.click(await petButton(/^Mochi/));
    expect(screen.getByText('Chọn thú cưng và chi nhánh để xem loại chuồng phù hợp.')).toBeInTheDocument();
    await user.click(within(group).getByRole('button', { name: /Cầu Giấy/ }));
    expect(await screen.findByText(/Chi nhánh này chưa có loại chuồng phù hợp với Mochi\. Chi nhánh có chuồng phù hợp: PetCare Quận 7\./)).toBeInTheDocument();
    await user.click(within(group).getByRole('button', { name: /Quận 7/ }));
    expect(await within(kennelGroup()).findByRole('button', { name: /Chuồng mèo/ })).toBeInTheDocument();
  });

  it('khách chưa có thú cưng: hướng dẫn thêm thú ở /pets, không có form', async () => {
    vi.spyOn(customerPetApi, 'listMyPets').mockResolvedValue([]);
    renderPage();
    expect(await screen.findByText('Bạn chưa có thú cưng nào để đặt chỗ lưu trú')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Thêm thú cưng' })).toHaveAttribute('href', '/pets');
    expect(screen.queryByRole('form', { name: 'Thông tin đặt chỗ lưu trú' })).not.toBeInTheDocument();
  });

  it('lỗi tải dữ liệu công khai: hiện câu của BE và nút Thử lại', async () => {
    const spy = vi.spyOn(catalogApi, 'listPublicServices').mockRejectedValueOnce(apiFailure(403, 'ACCESS_DENIED', 'Không có quyền thực hiện thao tác này'));
    const { user } = renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent('Không có quyền thực hiện thao tác này');
    spy.mockRestore();
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
    await form();
  });
});

describe('BoardingNewPage: bước 4, ngày nhận / trả theo giờ Việt Nam (BR-LT-02, BR-LT-04)', () => {
  it('ngày nhận: hôm nay không chọn được, ngày mai được; ngày thứ 60 được, ngày thứ 61 thì không', async () => {
    const { user } = renderPage();
    await form();
    await user.click(screen.getByRole('button', { name: 'Ngày nhận' }));
    expect(screen.getByRole('button', { name: '23/10/2026' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '24/10/2026' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: 'Tháng sau' }));
    await user.click(screen.getByRole('button', { name: 'Tháng sau' }));
    expect(screen.getByRole('button', { name: '22/12/2026' })).toBeEnabled();
    expect(screen.getByRole('button', { name: '23/12/2026' })).toBeDisabled();
  });

  it('ngày trả: 1 đến 30 đêm tính từ ngày nhận; đêm thứ 30 được, đêm thứ 31 thì không; ngày nhận thì không', async () => {
    const { user } = renderPage();
    await form();
    await pickDate(user, 'Ngày nhận', '24/10/2026');
    await user.click(screen.getByRole('button', { name: 'Ngày trả' }));
    expect(screen.getByRole('button', { name: '24/10/2026' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '25/10/2026' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: 'Tháng sau' }));
    expect(screen.getByRole('button', { name: '23/11/2026' })).toBeEnabled();
    expect(screen.getByRole('button', { name: '24/11/2026' })).toBeDisabled();
  });

  it('nút "Hôm nay" của bộ chọn bỏ qua giới hạn: form vẫn báo lỗi, khóa nút đặt và không hỏi chỗ trống', async () => {
    const spy = vi.spyOn(boardingApi, 'availability');
    const { user } = renderPage();
    await form();
    await user.click(await petButton(/^Bim/));
    await user.click(screen.getByRole('button', { name: 'Ngày nhận' }));
    await user.click(screen.getByRole('button', { name: 'Hôm nay' }));
    expect(await screen.findByText(/Ngày nhận phải từ ngày mai/)).toBeInTheDocument();
    expect(submitButton()).toBeDisabled();
    await pickDate(user, 'Ngày trả', '25/10/2026');
    expect(submitButton()).toBeDisabled();
    expect(spy).not.toHaveBeenCalled();
  });

  it('ranh giới nửa đêm giờ Việt Nam: 23:59 ngày 23/10 thì sớm nhất 24/10, 00:01 ngày 24/10 thì sớm nhất 25/10', async () => {
    pinClock('2026-10-23T23:59:00+07:00');
    const first = renderPage();
    await form();
    await first.user.click(screen.getByRole('button', { name: 'Ngày nhận' }));
    expect(screen.getByRole('button', { name: '24/10/2026' })).toBeEnabled();
    expect(screen.getByText(/Nhận từ 24\/10\/2026 đến 22\/12\/2026/)).toBeInTheDocument();
    cleanup();
    vi.setSystemTime(new Date('2026-10-24T00:01:00+07:00'));
    const second = renderPage();
    await form();
    await second.user.click(screen.getByRole('button', { name: 'Ngày nhận' }));
    expect(screen.getByRole('button', { name: '24/10/2026' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '25/10/2026' })).toBeEnabled();
    expect(screen.getByText(/Nhận từ 25\/10\/2026 đến 23\/12\/2026/)).toBeInTheDocument();
  });

  it('chọn ngày trả trước rồi mới chọn ngày nhận làm quá 30 đêm: báo lỗi, khóa nút và không hỏi chỗ trống', async () => {
    const spy = vi.spyOn(boardingApi, 'availability');
    const { user } = renderPage();
    await form();
    await user.click(await petButton(/^Bim/));
    await pickDate(user, 'Ngày trả', '24/11/2026', 1);
    await pickDate(user, 'Ngày nhận', '24/10/2026');
    expect(await screen.findByRole('alert')).toHaveTextContent('Lưu trú tối đa 30 đêm.');
    expect(submitButton()).toBeDisabled();
    expect(spy).not.toHaveBeenCalled();
  });

  it('ngày trả không sau ngày nhận: báo lỗi', async () => {
    const { user } = renderPage();
    await form();
    await pickDate(user, 'Ngày trả', '25/10/2026');
    await pickDate(user, 'Ngày nhận', '28/10/2026');
    expect(await screen.findByRole('alert')).toHaveTextContent('Ngày trả phải sau ngày nhận.');
  });
});

describe('BoardingNewPage: bước 5, chỗ còn lại từng đêm và tóm tắt', () => {
  it('chưa đủ thông tin thì không gọi API chỗ trống; đủ rồi gọi một lần với đúng tham số', async () => {
    const spy = vi.spyOn(boardingApi, 'availability');
    const { user } = renderPage();
    await user.click(await petButton(/^Bim/));
    await pickDate(user, 'Ngày nhận', '24/10/2026');
    expect(spy).not.toHaveBeenCalled();
    await pickDate(user, 'Ngày trả', '25/10/2026');
    await strip();
    expect(spy).toHaveBeenCalledTimes(1);
    expect(spy).toHaveBeenCalledWith(1, { kennelTypeId: 5, checkInDate: '2026-10-24', checkOutDate: '2026-10-25' });
  });

  it('dải đêm: mỗi đêm một ô với số chỗ còn lại; đêm cuối là đêm trước ngày trả', async () => {
    const { user } = renderPage();
    await fill(user, { checkIn: ['24/10/2026', 0], checkOut: ['26/10/2026', 0] });
    const list = await strip();
    const nights = within(list).getAllByRole('listitem');
    expect(nights).toHaveLength(2);
    expect(nights[0]).toHaveTextContent('T7');
    expect(nights[0]).toHaveTextContent('24/10');
    expect(nights[0]).toHaveTextContent('Còn 1 chỗ');
    expect(nights[1]).toHaveTextContent('CN');
    expect(nights[1]).toHaveTextContent('25/10');
    expect(nights[1]).toHaveTextContent('Còn 1 chỗ');
    expect(within(list).queryByText('26/10')).not.toBeInTheDocument();
  });

  it('có chỗ: tóm tắt ghi số đêm × giá đêm = tạm tính, giờ trả 12:00 và các chính sách', async () => {
    const { user } = renderPage();
    await fill(user, { checkIn: ['24/10/2026', 0], checkOut: ['26/10/2026', 0] });
    await strip();
    const box = within(summary());
    expect(box.getByText('Bim')).toBeInTheDocument();
    expect(box.getByText('PetCare Cầu Giấy')).toBeInTheDocument();
    expect(box.getByText('Chuồng chó nhỏ')).toBeInTheDocument();
    expect(box.getByText('24/10/2026')).toBeInTheDocument();
    expect(box.getByText('26/10/2026, trước 12:00')).toBeInTheDocument();
    expect(box.getByText(/^2 đêm × 200\.000/)).toBeInTheDocument();
    expect(box.getByText(/^400\.000/)).toBeInTheDocument();
    expect(box.getByText(/Tiền chốt khi trả thú/)).toBeInTheDocument();
    expect(box.getByText(/Mũi tiêm ở nơi khác không được tính/)).toBeInTheDocument();
    expect(box.getByText(/dưới 24 giờ trước 00:00 ngày nhận là hủy muộn, tính vào hạn chế đặt online/)).toBeInTheDocument();
    expect(box.getByText(/Giờ trả thú là 12:00/)).toBeInTheDocument();
    expect(submitButton()).toBeEnabled();
  });

  it('đêm hết chỗ: đánh dấu "Hết chỗ", khóa nút đặt và nêu "Hết chỗ các đêm: 26/10, 27/10"', async () => {
    await addDog('Rex');
    const { user } = renderPage();
    await fill(user, { pet: /^Rex/, checkIn: ['26/10/2026', 0], checkOut: ['28/10/2026', 0] });
    const nights = within(await strip()).getAllByRole('listitem');
    expect(nights.map((n) => n.textContent)).toEqual([expect.stringContaining('Hết chỗ'), expect.stringContaining('Hết chỗ')]);
    expect(await screen.findByText('Hết chỗ các đêm: 26/10, 27/10')).toBeInTheDocument();
    expect(submitButton()).toBeDisabled();
  });

  it('chỉ một số đêm hết chỗ thì liệt kê đúng các đêm đó', async () => {
    await addDog('Rex');
    const { user } = renderPage();
    await fill(user, { pet: /^Rex/, checkIn: ['25/10/2026', 0], checkOut: ['28/10/2026', 0] });
    await strip();
    expect(await screen.findByText('Hết chỗ các đêm: 26/10, 27/10')).toBeInTheDocument();
    const nights = within(await strip()).getAllByRole('listitem');
    expect(nights[0]).toHaveTextContent('Còn 1 chỗ');
  });

  it('lỗi hỏi chỗ trống: nêu câu của BE, khóa nút, Thử lại gọi lại và mở khóa khi thành công', async () => {
    vi.spyOn(boardingApi, 'availability').mockRejectedValueOnce(apiFailure(404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy chi nhánh #1'));
    const { user } = renderPage();
    await fill(user);
    expect(await screen.findByText('Không tìm thấy chi nhánh #1')).toBeInTheDocument();
    expect(submitButton()).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Thử lại' }));
    await strip();
    expect(screen.queryByText('Không tìm thấy chi nhánh #1')).not.toBeInTheDocument();
    expect(submitButton()).toBeEnabled();
  });

  it('chưa đủ thông tin: nút đặt khóa kèm lý do', async () => {
    renderPage();
    await form();
    expect(submitButton()).toBeDisabled();
    expect(screen.getByText('Chọn đủ thú cưng, chi nhánh, loại chuồng, ngày nhận và ngày trả để đặt chỗ.')).toBeInTheDocument();
    expect(screen.getByText('Chọn loại chuồng và ngày để xem tiền tạm tính.')).toBeInTheDocument();
  });
});

describe('BoardingNewPage: đặt chỗ', () => {
  it('thành công: gửi đúng nội dung, màn xác nhận có mã LT-…, thú, chi nhánh, loại chuồng, ngày; cảnh báo thiếu mũi 5 bệnh kèm gợi ý đặt lịch tiêm; nút sang chi tiết', async () => {
    const book = vi.spyOn(boardingApi, 'book');
    const { user, client } = renderPage();
    const invalidate = vi.spyOn(client, 'invalidateQueries');
    await fill(user);
    await strip();
    await user.click(submitButton());

    const done = await screen.findByRole('region', { name: 'Đặt chỗ lưu trú thành công' });
    // React Query thêm tham số ngữ cảnh sau nội dung gửi: chỉ kiểm nội dung.
    expect(book).toHaveBeenCalledTimes(1);
    expect(book.mock.calls[0][0]).toEqual({ petId: 2, branchId: 1, kennelTypeId: 5, checkInDate: '2026-10-24', checkOutDate: '2026-10-25' });
    expect(within(done).getByRole('heading', { level: 2, name: 'Đã giữ chỗ cho Bim' })).toBeInTheDocument();
    expect(within(done).getByText(/^LT-2610-\d{5}$/)).toBeInTheDocument();
    expect(within(done).getByText('PetCare Cầu Giấy')).toBeInTheDocument();
    expect(within(done).getByText('Chuồng chó nhỏ')).toBeInTheDocument();
    expect(within(done).getByText('24/10/2026')).toBeInTheDocument();
    expect(within(done).getByText('25/10/2026, trước 12:00')).toBeInTheDocument();
    expect(within(done).getByText('1 đêm')).toBeInTheDocument();
    expect(within(done).getByRole('link', { name: /Xem chi tiết lưu trú/ })).toHaveAttribute('href', '/boarding-bookings/12');
    // Bim thiếu mũi 5 bệnh (Dại còn hạn): cảnh báo không chặn, kèm gợi ý đặt lịch tiêm.
    const warning = within(done).getByRole('alert', { name: 'Cảnh báo tiêm phòng' });
    expect(within(warning).getByText('5 bệnh')).toBeInTheDocument();
    expect(warning).toHaveTextContent('Chưa có mũi nào ghi nhận tại PetCare');
    expect(warning).toHaveTextContent('mũi tiêm ở nơi khác không được tính');
    expect(within(warning).getByRole('link', { name: 'Đặt lịch tiêm trước ngày nhận' })).toHaveAttribute('href', '/booking');
    // Đặt chỗ làm mới các gốc dữ liệu bị chạm.
    for (const key of [['boarding'], ['sales'], ['pets'], ['notifications'], ['appointments']]) {
      expect(invalidate).toHaveBeenCalledWith({ queryKey: key });
    }
  });

  it('đủ mũi tiêm bắt buộc vào ngày nhận thì không có khối cảnh báo', async () => {
    recordDoseForMock(2, 2, '2026-10-10');
    const { user } = renderPage();
    await fill(user);
    await strip();
    await user.click(submitButton());
    const done = await screen.findByRole('region', { name: 'Đặt chỗ lưu trú thành công' });
    expect(within(done).queryByRole('alert')).not.toBeInTheDocument();
    expect(within(done).queryByText(/Cần tiêm phòng/)).not.toBeInTheDocument();
  });

  it('mũi còn hạn hôm nay nhưng hết hạn trước ngày nhận vẫn bị cảnh báo, nêu mũi gần nhất và hạn tái chủng', async () => {
    recordDoseForMock(2, 2, '2026-10-10');
    const { user } = renderPage();
    await fill(user, { checkIn: ['03/11/2026', 1], checkOut: ['04/11/2026', 1] });
    await strip();
    await user.click(submitButton());
    const warning = await screen.findByRole('alert', { name: 'Cảnh báo tiêm phòng' });
    expect(warning).toHaveTextContent('Mũi gần nhất 10/10/2026, đến hạn tiêm nhắc lại 31/10/2026');
  });

  it('đặt lại cùng thú sau khi đặt chỗ cũ đã hủy (#6, 01/11→03/11) thì được, không bị coi là trùng ngày', async () => {
    const { user } = renderPage();
    await fill(user, { checkIn: ['01/11/2026', 1], checkOut: ['03/11/2026', 1] });
    await strip();
    await user.click(submitButton());
    expect(await screen.findByRole('region', { name: 'Đặt chỗ lưu trú thành công' })).toBeInTheDocument();
  });

  it('"Đặt thêm chỗ" đưa về form trống', async () => {
    const { user } = renderPage();
    await fill(user);
    await strip();
    await user.click(submitButton());
    await screen.findByRole('region', { name: 'Đặt chỗ lưu trú thành công' });
    await user.click(screen.getByRole('button', { name: 'Đặt thêm chỗ' }));
    await form();
    expect(await petButton(/^Bim/)).toHaveAttribute('aria-pressed', 'false');
    expect(submitButton()).toBeDisabled();
  });

  it('đang gửi: nút khóa, đổi chữ, bấm lần hai không gửi thêm', async () => {
    const book = vi.spyOn(boardingApi, 'book').mockReturnValue(new Promise<BookingResult>(() => undefined));
    const { user } = renderPage();
    await fill(user);
    await strip();
    await user.click(submitButton());
    expect(await screen.findByRole('button', { name: 'Đang đặt chỗ…' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Đang đặt chỗ…' }));
    expect(book).toHaveBeenCalledTimes(1);
  });
});

describe('BoardingNewPage: lỗi từ BE hiện nguyên câu', () => {
  it('BR-LT-02 thú đã có đặt chỗ trùng ngày', async () => {
    // Mở thêm chuồng C-04 để đêm 26/10 còn 1 chỗ (không bị khóa vì hết chỗ), rồi đặt 26→27 trùng đặt chỗ #4 của chính Bim.
    await raw.post('/api/kennels/4/end-maintenance', undefined, await asStaff('manager@store1.vn'));
    const { user } = renderPage();
    await fill(user, { checkIn: ['26/10/2026', 0], checkOut: ['27/10/2026', 0] });
    await strip();
    await user.click(submitButton());
    expect(await screen.findByRole('alert')).toHaveTextContent('Thú cưng đã có đặt chỗ lưu trú trùng ngày');
    expect(screen.queryByText(/\(BR-LT-02\)/)).not.toBeInTheDocument();
    expect(submitButton()).toBeEnabled();
  });

  it('BR-LT-04 ngày nhận trùng ngày nghỉ của chi nhánh (12/11): FE không biết trước, BE trả đúng câu', async () => {
    const { user } = renderPage();
    await fill(user, { checkIn: ['12/11/2026', 1], checkOut: ['13/11/2026', 1] });
    await strip();
    await user.click(submitButton());
    expect(await screen.findByRole('alert')).toHaveTextContent('Ngày nhận / trả trùng ngày chi nhánh không làm việc');
  });

  it('BR-LT-03 vừa hết chỗ lúc đang chọn: hiện câu của BE, dải đêm tải lại thành "Hết chỗ", nút khóa, không kẹt trạng thái đang gửi', async () => {
    const rex = await addDog('Rex');
    const { user } = renderPage();
    await fill(user);
    expect(within(await strip()).getByText('Còn 1 chỗ')).toBeInTheDocument();
    // Lễ tân đặt nốt chỗ cuối của đêm 24/10 cho Rex trong lúc khách còn đang xem form.
    await raw.post('/api/boarding-bookings', { petId: rex, branchId: 1, kennelTypeId: 5, checkInDate: '2026-10-24', checkOutDate: '2026-10-25' }, await asStaff('reception@store1.vn'));
    await user.click(submitButton());
    expect(await screen.findByText('Hết chỗ các đêm: 24/10')).toBeInTheDocument();
    await waitFor(() => expect(within(screen.getByRole('list', { name: 'Chỗ còn lại theo đêm' })).getByText('Hết chỗ')).toBeInTheDocument());
    expect(screen.getAllByText('Hết chỗ các đêm: 24/10')).toHaveLength(1);
    expect(submitButton()).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Đang đặt chỗ…' })).not.toBeInTheDocument();
  });

  it('BR-KH-05 thú vừa được đánh dấu đã mất: hiện câu của BE và nút thú đó bị khóa sau khi tải lại', async () => {
    const rex = await addDog('Rex');
    const { user } = renderPage();
    await fill(user, { pet: /^Rex/ });
    await strip();
    await customerPetApi.markDeceased(rex, { deceasedOn: '2026-10-22', confirmed: true });
    await user.click(submitButton());
    expect(await screen.findByRole('alert')).toHaveTextContent('Thú cưng đã mất, không đặt được chỗ lưu trú');
    await waitFor(async () => expect(await petButton(/^Rex/)).toBeDisabled());
    // Thú vừa mất không còn là lựa chọn: bỏ chọn và khóa nút đặt.
    expect(await petButton(/^Rex/)).toHaveAttribute('aria-pressed', 'false');
    expect(submitButton()).toBeDisabled();
  });

  it('404 thú cưng không còn: hiện câu của BE', async () => {
    const rex = await addDog('Rex');
    const { user } = renderPage();
    await fill(user, { pet: /^Rex/ });
    await strip();
    await customerPetApi.deletePet(rex);
    await user.click(submitButton());
    expect(await screen.findByRole('alert')).toHaveTextContent(`Không tìm thấy thú cưng #${rex}`);
  });

  it('409 người khác vừa thao tác: hiện câu của BE, đọc lại dải đêm, nút bật lại để thử tiếp', async () => {
    const message = 'Dữ liệu vừa bị thay đổi bởi thao tác khác hoặc xung đột với dữ liệu hiện có, vui lòng tải lại và thử lại';
    vi.spyOn(boardingApi, 'book').mockRejectedValueOnce(apiFailure(409, 'CONCURRENCY_CONFLICT', message));
    const availability = vi.spyOn(boardingApi, 'availability');
    const { user } = renderPage();
    await fill(user);
    await strip();
    const before = availability.mock.calls.length;
    await user.click(submitButton());
    expect(await screen.findByRole('alert')).toHaveTextContent(message);
    await waitFor(() => expect(availability.mock.calls.length).toBeGreaterThan(before));
    expect(submitButton()).toBeEnabled();
    await user.click(submitButton());
    expect(await screen.findByRole('region', { name: 'Đặt chỗ lưu trú thành công' })).toBeInTheDocument();
  });
});

describe('BoardingNewPage: hạn chế đặt online (BR-LH-09, dùng chung với lịch hẹn)', () => {
  // Mẫu có 1 lần hủy muộn; thêm 2 vi phạm qua API để đủ 3 lần trong cửa sổ, như BookingPage.test.
  async function restrictCustomer() {
    const owner = await asStaff('khach@petcare.vn');
    const desk = await asStaff('reception@store1.vn');
    const today = (await raw.get('/api/appointments', { params: { date: '2026-10-23' }, ...owner })).data.data.content;
    await raw.post(`/api/appointments/${today[0].appointmentId}/cancel`, {}, owner);
    const counter = (await raw.post('/api/appointments', { petId: 2, branchId: 1, serviceId: 1, slotDate: '2026-10-23', slotStart: '13:30' }, desk)).data.data;
    await raw.post(`/api/appointments/${counter.appointmentId}/cancel`, {}, desk);
  }

  it('đang bị hạn chế khi mở trang: thấy lý do và thời điểm hết hạn chế, không có form', async () => {
    await restrictCustomer();
    renderPage();
    const notice = await screen.findByRole('alert', { name: 'Hạn chế đặt lịch online' });
    expect(notice).toHaveTextContent('đến 09:00 22/11/2026');
    expect(screen.queryByRole('form', { name: 'Thông tin đặt chỗ lưu trú' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Xem lưu trú của tôi' })).toHaveAttribute('href', '/boarding-bookings');
  });

  it('hạn chế phát sinh đúng lúc gửi: giữ nguyên câu BR-LH-09 của BE cùng thông báo hạn chế', async () => {
    const { user } = renderPage();
    await fill(user);
    await strip();
    await restrictCustomer();
    await user.click(submitButton());
    expect(await screen.findByRole('alert', { name: 'Hạn chế đặt lịch online' })).toBeInTheDocument();
    expect(screen.getByText('Bạn đang bị hạn chế đặt online đến 09:00 22/11/2026, vui lòng liên hệ chi nhánh để đặt chỗ')).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: 'Thông tin đặt chỗ lưu trú' })).not.toBeInTheDocument();
  });
});

