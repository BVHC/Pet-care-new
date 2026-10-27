import type { AxiosInstance } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ACCOUNTS, authAs, failure, makeClient, pinClock } from '../../../test/boarding-harness';
import { recordDoseForMock } from './visit-clinical.mock';

// Đặt chỗ (#8), danh sách / chi tiết (#9, #10), gia hạn (#11), hủy (#12). Giờ ghim: thứ Sáu 23/10/2026 09:00 (giờ VN).
// Dữ liệu mẫu: xem đầu boarding.mock.test.ts và src/test/boarding-harness.ts (Bim BOOKED 26/10→28/10 = đặt chỗ #4, Bơ BOOKED 30/10→01/11 = #1).
// Chi nhánh 1 mở thứ Hai–thứ Bảy 08:00–12:00 và 13:30–19:00, Chủ nhật 08:00–12:00; ngày nghỉ 12/11.

let client: AxiosInstance;

beforeEach(() => {
  pinClock();
  client = makeClient();
});
afterEach(() => vi.useRealTimers());

const as = (email: string) => authAs(client, email);
const customer = () => as(ACCOUNTS.customer);
const desk = () => as(ACCOUNTS.desk);
const at = (iso: string) => vi.setSystemTime(new Date(iso));

type Booking = {
  bookingId: number;
  code: string;
  status: string;
  checkInDate: string;
  checkOutDate: string;
  lateCancel: boolean;
  cancelSource: string | null;
  cancelReason: string | null;
  [k: string]: unknown;
};

const bimBody = (extra: object = {}) => ({ petId: 2, branchId: 1, kennelTypeId: 5, checkInDate: '2026-10-24', checkOutDate: '2026-10-25', ...extra });
const book = async (body: object, auth?: object) => client.post('/api/boarding-bookings', body, auth ?? (await customer()));
const bookOk = async (body: object, auth?: object): Promise<Booking> => (await book(body, auth)).data.data.booking;
const bookFail = async (body: object, auth?: object) => failure(book(body, auth));
const get = async (id: number, auth?: object): Promise<Booking> => (await client.get(`/api/boarding-bookings/${id}`, auth ?? (await desk()))).data.data;
const list = async (params: object = {}, auth?: object) => (await client.get('/api/boarding-bookings', { params, ...(auth ?? (await desk())) })).data.data;
const idsOf = (page: { content: { bookingId: number }[] }) => page.content.map((b) => b.bookingId);
/** Thú thứ hai của khách #1 (chó, chưa cân) để đặt song song với Bim. */
async function addRex(customerId = 1): Promise<number> {
  return (await client.post(`/api/customers/${customerId}/pets`, { name: 'Rex', species: 'DOG', sex: 'MALE' }, await desk())).data.data.pet.petId;
}

describe('POST /boarding-bookings (#8) — đặt chỗ thành công', () => {
  it('khách đặt cho thú của mình: BOOKED, ONLINE, giá đêm snapshot, mã LT-yyMM-nnnnn, cảnh báo thiếu mũi (Bim thiếu 5 bệnh)', async () => {
    const res = await book(bimBody());
    expect(res.status).toBe(201);
    expect(res.data).toMatchObject({ message: 'Đã đặt chỗ lưu trú', code: 201 });
    expect(res.data.data.booking).toEqual({
      bookingId: 12,
      code: expect.stringMatching(/^LT-2610-\d{5}$/),
      customerId: 1,
      customerName: 'Nguyễn Thu Trang',
      petId: 2,
      petName: 'Bim',
      branchId: 1,
      kennelTypeId: 5,
      kennelTypeName: 'Chuồng chó nhỏ',
      kennelId: null,
      kennelCode: null,
      checkInDate: '2026-10-24',
      checkOutDate: '2026-10-25',
      actualCheckInAt: null,
      actualCheckOutAt: null,
      nightlyPrice: 200000,
      status: 'BOOKED',
      endReason: null,
      channel: 'ONLINE',
      lateCancel: false,
      cancelSource: null,
      cancelReason: null,
      overdueSince: null,
      activeOrderId: null,
      missingCareLogToday: false,
    });
    // Dại còn hạn tới 01/08/2027 nên không cảnh báo; 5 bệnh chưa tiêm lần nào.
    expect(res.data.data.vaccineWarnings).toEqual([{ vaccineTypeId: 2, vaccineTypeName: '5 bệnh', lastAdministeredOn: null, nextDueDate: null }]);
  });

  it('cảnh báo mũi tiêm tính tại NGÀY NHẬN: mũi còn hạn hôm nay nhưng hết hạn trước ngày nhận vẫn bị cảnh báo; đủ tới ngày nhận thì không', async () => {
    await get(4);
    recordDoseForMock(2, 2, '2026-10-10'); // hạn tái chủng 31/10
    expect((await book(bimBody())).data.data.vaccineWarnings).toEqual([]);
    const late = await book(bimBody({ checkInDate: '2026-11-03', checkOutDate: '2026-11-04' }));
    expect(late.data.data.vaccineWarnings).toEqual([{ vaccineTypeId: 2, vaccineTypeName: '5 bệnh', lastAdministeredOn: '2026-10-10', nextDueDate: '2026-10-31' }]);
  });

  it('mã tăng liên tục trong tháng và đếm lại từ 00001 khi sang tháng mới', async () => {
    const first = await bookOk(bimBody());
    const rex = await addRex();
    const second = await bookOk(bimBody({ petId: rex, checkInDate: '2026-10-28', checkOutDate: '2026-10-29' }));
    expect(Number(second.code.slice(-5))).toBe(Number(first.code.slice(-5)) + 1);
    at('2026-11-02T09:00:00+07:00');
    const november = await bookOk(bimBody({ checkInDate: '2026-11-03', checkOutDate: '2026-11-04' }), await desk());
    expect(november.code).toBe('LT-2611-00001');
    // 02/11: Cốm và Mực đã OVERDUE, còn giữ chỗ tới 04/11 (hôm nay + 2 đêm) nên đêm 03/11 đã kín bởi Cốm + Mực + Bim.
    expect((await bookOk(bimBody({ petId: rex, checkInDate: '2026-11-05', checkOutDate: '2026-11-06' }), await desk())).code).toBe('LT-2611-00002');
  });

  it('lễ tân đặt tại quầy: COUNTER, nhận được ngay trong ngày (còn trước giờ đóng cửa)', async () => {
    const booking = await bookOk(bimBody({ checkInDate: '2026-10-23', checkOutDate: '2026-10-24' }), await desk());
    expect(booking).toMatchObject({ channel: 'COUNTER', status: 'BOOKED', checkInDate: '2026-10-23', customerName: 'Nguyễn Thu Trang' });
  });

  it('giá đêm chụp lúc đặt: đổi giá loại chuồng sau đó không đổi đặt chỗ cũ', async () => {
    const created = await bookOk(bimBody());
    await client.patch('/api/services/5', { price: 250000 }, await as(ACCOUNTS.chain));
    expect((await get(created.bookingId)).nightlyPrice).toBe(200000);
    const rex = await addRex();
    expect((await bookOk(bimBody({ petId: rex, checkInDate: '2026-11-03', checkOutDate: '2026-11-04' }))).nightlyPrice).toBe(250000);
  });

  it('thú đã đặt chỗ thì không còn xóa cứng được nữa (BR-KH-06)', async () => {
    const rex = await addRex();
    await bookOk(bimBody({ petId: rex }));
    const res = await failure(client.delete(`/api/pets/${rex}`, await customer()));
    expect(res.data.message).toContain('(BR-KH-06)');
  });
});

describe('POST /boarding-bookings (#8) — thứ tự lỗi', () => {
  it('thiếu trường: gộp đủ 5 câu theo thứ tự trường; 400 body chạy trước 403 vai trò', async () => {
    const empty = await failure(client.post('/api/boarding-bookings', {}, await as(ACCOUNTS.vet)));
    expect(empty.data).toMatchObject({
      errorCode: 'VALIDATION_FAILED',
      message:
        'petId: Vui lòng chọn thú cưng; branchId: Vui lòng chọn chi nhánh; kennelTypeId: Vui lòng chọn loại chuồng; checkInDate: Vui lòng chọn ngày nhận; checkOutDate: Vui lòng chọn ngày trả',
    });
  });

  it('sai kiểu (số thực vào trường số nguyên, ngày không tồn tại, chuỗi vào trường số) → 400 MALFORMED_REQUEST', async () => {
    const message = 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu';
    for (const bad of [{ petId: 2.5 }, { checkInDate: '2026-02-30' }, { checkOutDate: '25/10/2026' }, { kennelTypeId: '5' }]) {
      expect((await bookFail(bimBody(bad))).data).toMatchObject({ errorCode: 'MALFORMED_REQUEST', message });
    }
  });

  it('chỉ khách và lễ tân đặt được', async () => {
    for (const email of [ACCOUNTS.vet, ACCOUNTS.caretaker, ACCOUNTS.manager, ACCOUNTS.chain]) {
      expect((await bookFail(bimBody(), await as(email))).data.errorCode).toBe('ACCESS_DENIED');
    }
  });

  it('lễ tân đặt cho chi nhánh khác → 403 phạm vi, kiểm trước cả 404 thú', async () => {
    const res = await bookFail(bimBody({ branchId: 2, petId: 999 }), await desk());
    expect(res.data).toMatchObject({ errorCode: 'ACCESS_DENIED_SCOPE_MISMATCH', message: 'Không có quyền truy cập dữ liệu này' });
  });

  it('404 theo thứ tự thú → loại chuồng → chi nhánh; thú của người khác với khách cũng là 404', async () => {
    expect((await bookFail(bimBody({ petId: 4 }))).data).toMatchObject({ errorCode: 'RESOURCE_NOT_FOUND', message: 'Không tìm thấy thú cưng #4' });
    expect((await bookFail(bimBody({ petId: 999, kennelTypeId: 9999 }), await desk())).data.message).toBe('Không tìm thấy thú cưng #999');
    expect((await bookFail(bimBody({ kennelTypeId: 9999, branchId: 999 }))).data.message).toBe('Không tìm thấy loại chuồng #9999');
    expect((await bookFail(bimBody({ kennelTypeId: 1 }))).data.message).toBe('Không tìm thấy loại chuồng #1');
    expect((await bookFail(bimBody({ branchId: 999 }))).data.message).toBe('Không tìm thấy chi nhánh #999');
    // Khách không thấy chi nhánh chưa ACTIVE (chi nhánh 2 là DRAFT).
    expect((await bookFail(bimBody({ branchId: 2 }))).data.message).toBe('Không tìm thấy chi nhánh #2');
    // Lễ tân đọc thú của khách bất kỳ.
    expect((await bookOk(bimBody({ petId: 2 }), await desk())).petName).toBe('Bim');
  });

  it('loại chuồng chưa bật tại chi nhánh → 400 BR-LT-02, kiểm trước loài và cân nặng', async () => {
    const res = await bookFail(bimBody({ kennelTypeId: 6 }));
    expect(res.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Loại chuồng không đặt được tại chi nhánh này (BR-LT-02)' });
  });

  it('hồ sơ chờ liên kết → BR-TK-19 (chỉ với khách, lễ tân đặt hộ vẫn được)', async () => {
    const rex = await addRex(2);
    const res = await bookFail(bimBody({ petId: rex }), await as(ACCOUNTS.pendingCustomer));
    expect(res.data.message).toBe('Hồ sơ của bạn đang chờ quyết định liên kết với hồ sơ tại phòng khám (BR-TK-19)');
    expect((await bookOk(bimBody({ petId: rex }), await desk())).customerId).toBe(2);
  });

  it('thú đã mất → BR-KH-05, kiểm trước loài của thú', async () => {
    await client.post('/api/pets/3/mark-deceased', { deceasedOn: '2026-10-20', confirmed: true }, await customer());
    const res = await bookFail(bimBody({ petId: 3 }));
    expect(res.data.message).toBe('Thú cưng đã mất, không đặt được chỗ lưu trú (BR-KH-05)');
  });

  it('loài và cân nặng: mèo vào chuồng chó, chó quá cân bị từ chối; thú chưa cân và đúng cân tối đa (10 kg) thì qua', async () => {
    expect((await bookFail(bimBody({ petId: 1 }))).data.message).toBe('Loại chuồng không phù hợp loài của thú cưng (BR-LT-02)');
    const heavy = await bookFail(bimBody({ petId: 4 }), await desk());
    expect(heavy.data.message).toBe('Cân nặng hiện tại vượt cân nặng tối đa của loại chuồng (BR-LT-02)');
    const rex = await addRex();
    expect((await bookOk(bimBody({ petId: rex, checkInDate: '2026-11-03', checkOutDate: '2026-11-04' }))).petName).toBe('Rex');
    await client.post('/api/pets/2/weights', { weightKg: 10 }, await customer());
    expect((await bookOk(bimBody())).petName).toBe('Bim');
    await client.post('/api/pets/2/weights', { weightKg: 10.01 }, await customer());
    expect((await bookFail(bimBody({ checkInDate: '2026-11-05', checkOutDate: '2026-11-06' }))).data.message).toContain('Cân nặng hiện tại vượt cân nặng tối đa');
  });

  it('số đêm 1–30: biên 30 qua; 31, 0 và ngày trả trước ngày nhận bị BR-LT-02; kiểm trước chồng ngày và khoảng ngày', async () => {
    expect((await bookOk(bimBody({ checkInDate: '2026-11-03', checkOutDate: '2026-12-03' }), await desk())).checkOutDate).toBe('2026-12-03');
    const rex = await addRex();
    for (const [from, to] of [
      ['2026-11-03', '2026-12-04'],
      ['2026-11-03', '2026-11-03'],
      ['2026-11-04', '2026-11-03'],
    ]) {
      const res = await bookFail(bimBody({ petId: rex, checkInDate: from, checkOutDate: to }));
      expect(res.data.message).toBe('Thời gian lưu trú phải từ 1 đến 30 đêm và ngày trả phải sau ngày nhận (BR-LT-02)');
    }
  });

  it('chồng ngày với đặt chỗ BOOKED của cùng thú → BR-LT-02; kề nhau (ngày trả = ngày nhận) thì không chồng', async () => {
    const overlap = 'Thú cưng đã có đặt chỗ lưu trú trùng ngày (BR-LT-02)';
    expect((await bookFail(bimBody({ checkInDate: '2026-10-27', checkOutDate: '2026-10-29' }))).data.message).toBe(overlap);
    expect((await bookFail(bimBody({ checkInDate: '2026-10-25', checkOutDate: '2026-10-27' }))).data.message).toBe(overlap);
    expect((await bookFail(bimBody({ checkInDate: '2026-10-25', checkOutDate: '2026-10-30' }))).data.message).toBe(overlap);
    expect((await bookOk(bimBody({ checkInDate: '2026-10-24', checkOutDate: '2026-10-26' }))).checkOutDate).toBe('2026-10-26');
    expect((await bookOk(bimBody({ checkInDate: '2026-10-28', checkOutDate: '2026-10-29' }))).checkInDate).toBe('2026-10-28');
  });

  it('chồng ngày chỉ tính BOOKED / CHECKED_IN / OVERDUE: đặt lại sau khi đặt chỗ cũ CANCELLED, NO_SHOW được (Review Focus #1)', async () => {
    // CANCELLED có sẵn: Bim 01/11→03/11 (đặt chỗ #6).
    expect((await bookOk(bimBody({ checkInDate: '2026-11-01', checkOutDate: '2026-11-03' }))).status).toBe('BOOKED');
    // Khách hủy đặt chỗ #4 rồi đặt lại đúng ngày đó.
    await client.post('/api/boarding-bookings/4/cancel', {}, await customer());
    expect((await bookOk(bimBody({ checkInDate: '2026-10-26', checkOutDate: '2026-10-28' }))).status).toBe('BOOKED');
  });

  it('NO_SHOW không chặn đặt lại cùng ngày', async () => {
    await get(4); // nạp dữ liệu mẫu ở 23/10
    at('2026-10-26T19:00:00+07:00'); // hết giờ làm việc ngày nhận → ST05: Bim NO_SHOW
    expect((await get(4)).status).toBe('NO_SHOW');
    const again = await bookOk(bimBody({ checkInDate: '2026-10-27', checkOutDate: '2026-10-29' }), await desk());
    expect(again.status).toBe('BOOKED');
  });
});

describe('POST /boarding-bookings (#8) — khoảng ngày, ngày nghỉ, sức chứa', () => {
  it('khách: ngày nhận từ mai (≥ 1 ngày) tới 60 ngày tới; hôm nay và 61 ngày bị BR-LT-04', async () => {
    const message = 'Chỉ đặt online trước ngày nhận ít nhất 1 ngày và trong vòng 60 ngày tới (BR-LT-04)';
    expect((await bookFail(bimBody({ checkInDate: '2026-10-23', checkOutDate: '2026-10-24' }))).data.message).toBe(message);
    expect((await bookFail(bimBody({ checkInDate: '2026-10-22', checkOutDate: '2026-10-24' }))).data.message).toBe(message);
    expect((await bookOk(bimBody({ checkInDate: '2026-12-22', checkOutDate: '2026-12-23' }))).checkInDate).toBe('2026-12-22');
    const rex = await addRex();
    expect((await bookFail(bimBody({ petId: rex, checkInDate: '2026-12-23', checkOutDate: '2026-12-24' }))).data.message).toBe(message);
  });

  it('khách: mốc "mai" tính theo ngày giờ Việt Nam, không theo múi giờ máy (23:59 vẫn là ngày 23)', async () => {
    await get(4);
    at('2026-10-23T23:59:59+07:00');
    expect((await bookFail(bimBody({ checkInDate: '2026-10-23', checkOutDate: '2026-10-24' }))).data.message).toContain('Chỉ đặt online');
    expect((await bookOk(bimBody({ checkInDate: '2026-10-24', checkOutDate: '2026-10-25' }))).status).toBe('BOOKED');
    at('2026-10-24T00:00:00+07:00');
    const rex = await addRex();
    expect((await bookFail(bimBody({ petId: rex, checkInDate: '2026-10-24', checkOutDate: '2026-10-25' }))).data.message).toContain('Chỉ đặt online');
    expect((await bookOk(bimBody({ petId: rex, checkInDate: '2026-10-25', checkOutDate: '2026-10-26' }))).checkInDate).toBe('2026-10-25');
  });

  it('lễ tân: không đặt ngày nhận đã qua; trong ngày thì đến trước giờ đóng cửa muộn nhất (19:00 ngày thường)', async () => {
    expect((await bookFail(bimBody({ checkInDate: '2026-10-22', checkOutDate: '2026-10-24' }), await desk())).data.message).toBe('Ngày nhận đã qua (BR-LT-04)');
    await get(4);
    at('2026-10-23T18:59:59+07:00');
    expect((await bookOk(bimBody({ checkInDate: '2026-10-23', checkOutDate: '2026-10-24' }), await desk())).status).toBe('BOOKED');
    at('2026-10-23T19:00:00+07:00');
    const rex = await addRex();
    const res = await bookFail(bimBody({ petId: rex, checkInDate: '2026-10-23', checkOutDate: '2026-10-24' }), await desk());
    expect(res.data.message).toBe('Đã hết giờ làm việc hôm nay, không nhận đặt chỗ nhận trong ngày (BR-LT-04)');
  });

  it('giờ đóng cửa muộn nhất của chính ngày đó: Chủ nhật chỉ mở buổi sáng (12:00)', async () => {
    at('2026-10-25T11:59:59+07:00'); // Chủ nhật
    await get(4);
    expect((await bookOk(bimBody({ checkInDate: '2026-10-25', checkOutDate: '2026-10-26' }), await desk())).status).toBe('BOOKED');
    at('2026-10-25T12:00:00+07:00');
    const rex = await addRex();
    const res = await bookFail(bimBody({ petId: rex, checkInDate: '2026-10-25', checkOutDate: '2026-10-26' }), await desk());
    expect(res.data.message).toContain('Đã hết giờ làm việc hôm nay');
  });

  it('ngày nhận hoặc ngày trả là ngày nghỉ của chi nhánh (12/11) → BR-LT-04, cả khách lẫn lễ tân', async () => {
    const message = 'Ngày nhận / trả trùng ngày chi nhánh không làm việc (BR-LT-04)';
    expect((await bookFail(bimBody({ checkInDate: '2026-11-12', checkOutDate: '2026-11-13' }))).data.message).toBe(message);
    expect((await bookFail(bimBody({ checkInDate: '2026-11-11', checkOutDate: '2026-11-12' }), await desk())).data.message).toBe(message);
  });

  it('hết chỗ: báo các đêm thiếu theo dd/MM; đêm còn chỗ không bị liệt kê', async () => {
    const rex = await addRex();
    const full = await bookFail(bimBody({ petId: rex, checkInDate: '2026-10-26', checkOutDate: '2026-10-28' }));
    expect(full.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Hết chỗ các đêm: 26/10, 27/10 (BR-LT-03)' });
    const partial = await bookFail(bimBody({ petId: rex, checkInDate: '2026-10-25', checkOutDate: '2026-10-27' }));
    expect(partial.data.message).toBe('Hết chỗ các đêm: 26/10 (BR-LT-03)');
  });

  it('đặt tới đúng sức chứa thì đêm đó kín: người đặt kế tiếp bị từ chối, bảo trì thêm chuồng mở lại chỗ', async () => {
    const rex = await addRex();
    await bookOk(bimBody()); // đêm 24/10: đã có Cốm + Mực → còn 1 chỗ, đặt xong là kín
    const full = await bookFail(bimBody({ petId: rex }));
    expect(full.data.message).toBe('Hết chỗ các đêm: 24/10 (BR-LT-03)');
    await client.post('/api/kennels/4/end-maintenance', undefined, await as(ACCOUNTS.manager));
    expect((await bookOk(bimBody({ petId: rex }))).petName).toBe('Rex');
  });

  it('BR-LT-04 (ngày nghỉ) được kiểm trước BR-LT-03 (hết chỗ): đêm 12/11 đã kín mà ngày nhận là ngày nghỉ vẫn báo ngày nghỉ', async () => {
    const pets = [2, await addRex(), await addRex()];
    for (const petId of pets) await bookOk(bimBody({ petId, checkInDate: '2026-11-11', checkOutDate: '2026-11-13' }), await desk());
    const rex = await addRex();
    const full = await bookFail(bimBody({ petId: rex, checkInDate: '2026-11-11', checkOutDate: '2026-11-12' }), await desk());
    expect(full.data.message).toBe('Ngày nhận / trả trùng ngày chi nhánh không làm việc (BR-LT-04)');
    const sameNight = await bookFail(bimBody({ petId: rex, checkInDate: '2026-11-12', checkOutDate: '2026-11-13' }), await desk());
    expect(sameNight.data.message).toBe('Ngày nhận / trả trùng ngày chi nhánh không làm việc (BR-LT-04)');
    const free = await bookFail(bimBody({ petId: rex, checkInDate: '2026-11-11', checkOutDate: '2026-11-14' }), await desk());
    expect(free.data.message).toBe('Hết chỗ các đêm: 11/11, 12/11 (BR-LT-03)');
  });
});

describe('POST /boarding-bookings (#8) — hạn chế đặt online (BR-LH-09)', () => {
  it('hai lần hủy muộn của lưu trú cộng với một lần hủy muộn lịch hẹn mẫu = 3 vi phạm: khách bị chặn đặt online, lễ tân vẫn đặt được', async () => {
    const lateCancel = async () => {
      const b = await bookOk(bimBody());
      return (await client.post(`/api/boarding-bookings/${b.bookingId}/cancel`, {}, await customer())).data.data as Booking;
    };
    const first = await lateCancel();
    expect(first).toMatchObject({ status: 'CANCELLED', lateCancel: true });
    expect((await client.get('/api/me/booking-restriction', await customer())).data.data).toEqual({ restricted: false, restriction: null });
    await lateCancel();
    const restriction = (await client.get('/api/me/booking-restriction', await customer())).data.data;
    expect(restriction).toMatchObject({ restricted: true, restriction: { violationCount: 3, startsAt: '2026-10-23T02:00:00.000Z', endsAt: '2026-11-22T02:00:00.000Z' } });
    const blocked = await bookFail(bimBody());
    expect(blocked.data).toMatchObject({
      errorCode: 'BUSINESS_RULE_VIOLATION',
      message: 'Bạn đang bị hạn chế đặt online đến 09:00 22/11/2026, vui lòng liên hệ chi nhánh để đặt chỗ (BR-LH-09)',
    });
    expect((await bookOk(bimBody(), await desk())).channel).toBe('COUNTER');
  });

  it('hủy sớm (không muộn) không tính vi phạm: hủy ba lần vẫn không bị hạn chế', async () => {
    for (let i = 0; i < 3; i += 1) {
      const b = await bookOk(bimBody({ checkInDate: '2026-11-03', checkOutDate: '2026-11-04' }));
      expect((await client.post(`/api/boarding-bookings/${b.bookingId}/cancel`, {}, await customer())).data.data.lateCancel).toBe(false);
    }
    expect((await client.get('/api/me/booking-restriction', await customer())).data.data.restricted).toBe(false);
    expect((await bookOk(bimBody())).status).toBe('BOOKED');
  });

  it('ST05 NO_SHOW của lưu trú cũng tính: vi phạm thứ 3 sinh hạn chế đúng lúc hết giờ làm việc (19:00) ngày nhận', async () => {
    // Khách #1 có sẵn 1 lần hủy muộn lịch hẹn (18/10); lịch Mochi hôm nay 11:00 sẽ NO_SHOW lúc 11:30 → 2 vi phạm.
    await bookOk(bimBody({ checkInDate: '2026-10-23', checkOutDate: '2026-10-24' }), await desk());
    const restricted = async () => (await client.get('/api/customers/1/booking-restriction', await desk())).data.data;
    at('2026-10-23T18:59:59+07:00');
    expect((await restricted()).restricted).toBe(false);
    at('2026-10-23T19:00:00+07:00');
    expect(await restricted()).toMatchObject({ restricted: true, restriction: { violationCount: 3, startsAt: '2026-10-23T12:00:00.000Z' } });
  });
});

describe('GET /boarding-bookings (#9)', () => {
  it('mặc định trang 0, size 20, sắp checkInDate giảm dần rồi id giảm dần; message danh sách', async () => {
    const res = await client.get('/api/boarding-bookings', await desk());
    expect(res.data).toMatchObject({ message: 'Danh sách đặt chỗ lưu trú' });
    expect(res.data.data).toMatchObject({ page: 0, size: 20, totalElements: 11, totalPages: 1 });
    expect(idsOf(res.data.data)).toEqual([6, 1, 8, 4, 3, 10, 2, 9, 11, 7, 5]);
  });

  it('lọc theo trạng thái, thú, khách', async () => {
    expect(idsOf(await list({ status: 'CHECKED_IN' }))).toEqual([3, 2]);
    expect(idsOf(await list({ status: 'BOOKED' }))).toEqual([1, 4]);
    expect(idsOf(await list({ petId: 2 }))).toEqual([6, 4, 5]);
    expect(idsOf(await list({ customerId: 1 }))).toEqual([6, 4, 5]);
    expect(idsOf(await list({ customerId: 1, status: 'CANCELLED' }))).toEqual([6]);
  });

  it('from / to lọc GIAO với [ngày nhận, ngày trả]: trả ≥ from và nhận ≤ to, biên tính cả hai đầu', async () => {
    expect(idsOf(await list({ from: '2026-10-31', to: '2026-10-31' }))).toEqual([1, 3, 2]);
    expect(idsOf(await list({ from: '2026-11-01' }))).toEqual([6, 1, 3]);
    expect(idsOf(await list({ to: '2026-10-21' }))).toEqual([10, 2, 9, 11, 7, 5]);
    expect(idsOf(await list({ from: '2026-11-04' }))).toEqual([]);
  });

  it('from sau to → 400 VALIDATION_FAILED (không kèm tên trường), kiểm trước phạm vi', async () => {
    const res = await failure(client.get('/api/boarding-bookings', { params: { from: '2026-11-02', to: '2026-11-01' }, ...(await desk()) }));
    expect(res.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'Ngày bắt đầu lọc phải trước hoặc bằng ngày kết thúc' });
  });

  it('inStay = CHECKED_IN / OVERDUE; missingCareLogToday = đang lưu trú chưa có nhật ký hôm nay; false không lọc', async () => {
    expect(idsOf(await list({ inStay: true }))).toEqual([3, 2]);
    expect(idsOf(await list({ missingCareLogToday: true }))).toEqual([2]);
    expect((await list({ inStay: false })).totalElements).toBe(11);
    expect((await list({ missingCareLogToday: false })).totalElements).toBe(11);
    const rows = (await list({ inStay: true })).content as { bookingId: number; missingCareLogToday: boolean }[];
    expect(rows.map((r) => [r.bookingId, r.missingCareLogToday])).toEqual([
      [3, false],
      [2, true],
    ]);
  });

  it('page / size: size kẹp [1, 100] im lặng, page âm về 0, trang vượt cuối rỗng', async () => {
    expect((await list({ size: 0 })).size).toBe(1);
    expect((await list({ size: -5 })).size).toBe(1);
    expect((await list({ size: 1000 })).size).toBe(100);
    expect((await list({ page: -3 })).page).toBe(0);
    const second = await list({ page: 1, size: 5 });
    expect(idsOf(second)).toEqual([10, 2, 9, 11, 7]);
    expect(second).toMatchObject({ page: 1, size: 5, totalElements: 11, totalPages: 3 });
    expect(idsOf(await list({ page: 9, size: 5 }))).toEqual([]);
    expect(await list({ status: 'OVERDUE' })).toMatchObject({ content: [], totalElements: 0, totalPages: 0 });
  });

  it('tham số sai kiểu → 400 MALFORMED_REQUEST, kiểm trước vai trò', async () => {
    const vet = await as(ACCOUNTS.vet);
    for (const [name, value] of [['status', 'DONE'], ['petId', 'x'], ['customerId', '1.5'], ['from', '2026-13-01'], ['to', '31/10'], ['inStay', 'maybe'], ['page', 'a'], ['size', 'b'], ['missingCareLogToday', '2']]) {
      const res = await failure(client.get('/api/boarding-bookings', { params: { [name]: value }, ...vet }));
      expect(res.data).toMatchObject({ errorCode: 'MALFORMED_REQUEST', message: `Tham số '${name}' sai kiểu dữ liệu` });
    }
  });

  it('khách chỉ thấy đặt chỗ của mình; customerId / petId của người khác ra trang rỗng, không 404 / 403', async () => {
    expect(idsOf(await list({}, await customer()))).toEqual([6, 4, 5]);
    expect(idsOf(await list({ customerId: 3 }, await customer()))).toEqual([]);
    expect(idsOf(await list({ petId: 4 }, await customer()))).toEqual([]);
    expect(idsOf(await list({ petId: 2 }, await customer()))).toEqual([6, 4, 5]);
  });

  it('nhân viên chỉ thấy chi nhánh của mình; BRANCH_MANAGER, VET, CARETAKER xem được; ADMIN, SUPER_MANAGER bị 403', async () => {
    expect(await list({}, await as(ACCOUNTS.manager2))).toMatchObject({ content: [], totalElements: 0 });
    for (const email of [ACCOUNTS.manager, ACCOUNTS.vet, ACCOUNTS.caretaker]) expect((await list({}, await as(email))).totalElements).toBe(11);
    for (const email of [ACCOUNTS.admin, ACCOUNTS.chain]) {
      expect((await failure(client.get('/api/boarding-bookings', await as(email)))).data.errorCode).toBe('ACCESS_DENIED');
    }
  });

  it('thú / khách không còn trong mock thú vẫn hiện tên đã lưu trong đặt chỗ (đặt chỗ mẫu Bơ)', async () => {
    const bo = (await list({ status: 'BOOKED' })).content.find((b: { bookingId: number }) => b.bookingId === 1);
    expect(bo).toMatchObject({ petName: 'Bơ', customerName: 'Phạm Gia Bảo', checkInDate: '2026-10-30', checkOutDate: '2026-11-01', channel: 'COUNTER' });
  });
});

describe('GET /boarding-bookings/:id (#10)', () => {
  it('trả đủ trường của đặt chỗ đang lưu trú, null giữ là null', async () => {
    const res = await client.get('/api/boarding-bookings/2', await desk());
    expect(res.data).toMatchObject({ message: 'Chi tiết đặt chỗ lưu trú' });
    expect(res.data.data).toEqual({
      bookingId: 2,
      code: expect.stringMatching(/^LT-2610-\d{5}$/),
      customerId: 9002,
      customerName: 'Đỗ Mai Phương',
      petId: 9002,
      petName: 'Cốm',
      branchId: 1,
      kennelTypeId: 5,
      kennelTypeName: 'Chuồng chó nhỏ',
      kennelId: 1,
      kennelCode: 'C-01',
      checkInDate: '2026-10-21',
      checkOutDate: '2026-10-31',
      actualCheckInAt: '2026-10-21T02:20:00.000Z',
      actualCheckOutAt: null,
      nightlyPrice: 200000,
      status: 'CHECKED_IN',
      endReason: null,
      channel: 'ONLINE',
      lateCancel: false,
      cancelSource: null,
      cancelReason: null,
      overdueSince: null,
      activeOrderId: null,
      missingCareLogToday: true,
    });
  });

  it('đặt chỗ đã hủy hiện nguồn hủy và lý do; đã kết thúc hiện endReason', async () => {
    expect(await get(6)).toMatchObject({ status: 'CANCELLED', cancelSource: 'CUSTOMER', cancelReason: 'Đổi kế hoạch công tác', lateCancel: false });
    expect(await get(8)).toMatchObject({ status: 'CANCELLED', cancelSource: 'CLINIC', lateCancel: false });
    expect(await get(10)).toMatchObject({ status: 'CANCELLED', cancelSource: 'STAFF', lateCancel: true });
    expect(await get(7)).toMatchObject({ status: 'CHECKED_OUT', endReason: 'PET_DECEASED' });
    expect(await get(5)).toMatchObject({ status: 'CHECKED_OUT', endReason: 'RETURNED', kennelCode: 'C-03', missingCareLogToday: false });
  });

  it('404 khi không có; khách xem của người khác cũng 404; nhân viên chi nhánh khác → 403 phạm vi (sau 404)', async () => {
    const res = await failure(client.get('/api/boarding-bookings/999', await desk()));
    expect(res.data).toMatchObject({ errorCode: 'RESOURCE_NOT_FOUND', message: 'Không tìm thấy đặt chỗ lưu trú #999' });
    expect((await failure(client.get('/api/boarding-bookings/2', await customer()))).data.message).toBe('Không tìm thấy đặt chỗ lưu trú #2');
    expect((await client.get('/api/boarding-bookings/4', await customer())).status).toBe(200);
    expect((await failure(client.get('/api/boarding-bookings/2', await as(ACCOUNTS.manager2)))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await failure(client.get('/api/boarding-bookings/999', await as(ACCOUNTS.manager2)))).status).toBe(404);
    expect((await failure(client.get('/api/boarding-bookings/abc', await desk()))).data.message).toBe("Tham số 'bookingId' sai kiểu dữ liệu");
    expect((await failure(client.get('/api/boarding-bookings/2', await as(ACCOUNTS.admin)))).data.errorCode).toBe('ACCESS_DENIED');
  });
});

describe('POST /boarding-bookings/:id/extend (#11)', () => {
  const extend = async (id: number, checkOutDate: unknown, auth?: object) => client.post(`/api/boarding-bookings/${id}/extend`, { checkOutDate }, auth ?? (await customer()));
  const extendFail = async (id: number, checkOutDate: unknown, auth?: object) => failure(extend(id, checkOutDate, auth));

  it('gia hạn đặt chỗ BOOKED: ngày trả mới, các đêm thêm còn chỗ', async () => {
    const res = await extend(4, '2026-10-29');
    expect(res.data).toMatchObject({ message: 'Đã gia hạn đặt chỗ lưu trú' });
    expect(res.data.data).toMatchObject({ bookingId: 4, checkInDate: '2026-10-26', checkOutDate: '2026-10-29', status: 'BOOKED' });
    expect((await get(4)).checkOutDate).toBe('2026-10-29');
  });

  it('gia hạn đặt chỗ CHECKED_IN (lễ tân) được, trước ngày trả', async () => {
    const res = await extend(2, '2026-11-01', await desk());
    expect(res.data.data).toMatchObject({ bookingId: 2, status: 'CHECKED_IN', checkOutDate: '2026-11-01' });
  });

  it('thiếu ngày trả mới → 400; sai kiểu → MALFORMED; chỉ khách và lễ tân', async () => {
    expect((await extendFail(4, undefined)).data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'checkOutDate: Vui lòng chọn ngày trả mới' });
    expect((await extendFail(4, '2026-02-30')).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await extendFail(4, '2026-10-29', await as(ACCOUNTS.vet))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await extendFail(4, undefined, await as(ACCOUNTS.vet))).data.errorCode).toBe('VALIDATION_FAILED');
  });

  it('404 / 403 phạm vi: khách khác 404, nhân viên chi nhánh khác không có (lễ tân duy nhất ở chi nhánh 1)', async () => {
    expect((await extendFail(999, '2026-10-29')).data.message).toBe('Không tìm thấy đặt chỗ lưu trú #999');
    expect((await extendFail(2, '2026-11-02')).data.message).toBe('Không tìm thấy đặt chỗ lưu trú #2');
  });

  it('đặt chỗ đã kết thúc / đã hủy → 409 với câu riêng; kiểm trước mọi rule 400', async () => {
    for (const id of [5, 6, 9]) {
      const res = await extendFail(id, '2026-12-01', await desk());
      expect(res.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409, message: 'Đặt chỗ đang quá hạn đón hoặc đã kết thúc, không gia hạn được' });
    }
  });

  it('OVERDUE không gia hạn được → 409 (ST15 chạy lười sau 12:00 ngày trả)', async () => {
    await get(2);
    at('2026-10-31T12:00:00+07:00');
    expect((await get(2)).status).toBe('CHECKED_IN');
    expect((await extendFail(2, '2026-11-05', await desk())).data.message).toContain('Đã tới ngày trả dự kiến');
    at('2026-10-31T12:00:01+07:00');
    const overdue = await get(2);
    expect(overdue).toMatchObject({ status: 'OVERDUE', overdueSince: '2026-10-31T05:00:00.000Z' });
    expect((await extendFail(2, '2026-11-05', await desk())).data.errorCode).toBe('INVALID_STATE_TRANSITION');
  });

  it('đã tới ngày trả dự kiến → 400 BR-LT-06; hôm trước thì còn gia hạn được', async () => {
    await get(2);
    at('2026-10-30T10:00:00+07:00');
    expect((await extend(2, '2026-11-02', await desk())).status).toBe(200);
    at('2026-11-02T08:00:00+07:00');
    const res = await extendFail(2, '2026-11-04', await desk());
    expect(res.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Đã tới ngày trả dự kiến, không gia hạn được (BR-LT-06)' });
  });

  it('ngày trả mới phải sau ngày trả hiện tại → 400 VALIDATION_FAILED (kiểm sau BR-LT-06)', async () => {
    for (const day of ['2026-10-28', '2026-10-27']) {
      const res = await extendFail(4, day);
      expect(res.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'Ngày trả mới phải sau ngày trả hiện tại' });
    }
  });

  it('tổng số đêm tính từ ngày nhận gốc: biên 30 qua, 31 → BR-LT-02', async () => {
    const rex = await addRex();
    const far = await bookOk(bimBody({ petId: rex, checkInDate: '2026-12-01', checkOutDate: '2026-12-02' }));
    const tooLong = await extendFail(far.bookingId, '2027-01-01'); // 31 đêm từ 01/12
    expect(tooLong.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Thời gian lưu trú phải từ 1 đến 30 đêm và ngày trả phải sau ngày nhận (BR-LT-02)' });
    expect((await extend(far.bookingId, '2026-12-31')).data.data.checkOutDate).toBe('2026-12-31'); // đúng 30 đêm
  });

  it('ngày trả mới là ngày nghỉ → BR-LT-04, kiểm trước chồng ngày và hết chỗ', async () => {
    const res = await extendFail(4, '2026-11-12');
    expect(res.data.message).toBe('Ngày trả mới trùng ngày chi nhánh không làm việc (BR-LT-04)');
  });

  it('chồng ngày với đặt chỗ khác của cùng thú ở đoạn gia hạn → BR-LT-02', async () => {
    await bookOk(bimBody({ checkInDate: '2026-10-29', checkOutDate: '2026-10-30' }));
    const res = await extendFail(4, '2026-10-30');
    expect(res.data.message).toBe('Thú cưng đã có đặt chỗ lưu trú trùng ngày (BR-LT-02)');
    expect((await extend(4, '2026-10-29')).status).toBe(200);
  });

  it('hết chỗ các đêm thêm → BR-LT-03 kèm các đêm thiếu', async () => {
    const res = await extendFail(4, '2026-10-31');
    expect(res.data.message).toBe('Hết chỗ các đêm: 30/10 (BR-LT-03)');
  });
});

describe('POST /boarding-bookings/:id/cancel (#12)', () => {
  const cancel = async (id: number, body?: object, auth?: object) => client.post(`/api/boarding-bookings/${id}/cancel`, body, auth ?? (await customer()));
  const cancelFail = async (id: number, body?: object, auth?: object) => failure(cancel(id, body, auth));

  it('khách hủy sớm (nhận 26/10, hôm nay 23/10): CANCELLED, nguồn CUSTOMER, không hủy muộn; lý do để trống thành null', async () => {
    const res = await cancel(4);
    expect(res.data).toMatchObject({ message: 'Đã hủy đặt chỗ lưu trú' });
    expect(res.data.data).toMatchObject({ bookingId: 4, status: 'CANCELLED', cancelSource: 'CUSTOMER', lateCancel: false, cancelReason: null });
  });

  it('lý do được bỏ khoảng trắng hai đầu; toàn khoảng trắng thành null; quá 300 ký tự → 400', async () => {
    expect((await cancel(4, { reason: '  Đổi lịch công tác ' })).data.data.cancelReason).toBe('Đổi lịch công tác');
    const rex = await addRex();
    const other = await bookOk(bimBody({ petId: rex, checkInDate: '2026-10-28', checkOutDate: '2026-10-29' }));
    expect((await cancel(other.bookingId, { reason: '   ' })).data.data.cancelReason).toBeNull();
    const second = await bookOk(bimBody({ petId: rex, checkInDate: '2026-10-28', checkOutDate: '2026-10-29' }));
    expect((await cancelFail(second.bookingId, { reason: 'a'.repeat(301) })).data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'reason: Lý do tối đa 300 ký tự' });
    expect((await cancel(second.bookingId, { reason: 'a'.repeat(300) })).status).toBe(200);
  });

  it('lễ tân hủy: nguồn STAFF; hủy muộn vẫn ghi cho đặt chỗ (và tính vi phạm của khách)', async () => {
    await get(4);
    at('2026-10-25T10:00:00+07:00');
    const res = await cancel(4, { reason: 'Khách gọi báo hủy' }, await desk());
    expect(res.data.data).toMatchObject({ status: 'CANCELLED', cancelSource: 'STAFF', lateCancel: true, cancelReason: 'Khách gọi báo hủy' });
  });

  it('hủy muộn = từ 00:00 giờ VN của ngày nhận − 24 giờ: 23:59:59.999 ngày D−2 chưa muộn, 00:00:00.000 ngày D−1 là muộn; sang ngày nhận thì không hủy được', async () => {
    const [r1, r2, r3] = [await addRex(), await addRex(), await addRex()];
    const stay = async (petId: number) => bookOk(bimBody({ petId, checkInDate: '2026-11-20', checkOutDate: '2026-11-21' }), await desk());
    const [early, late, none] = [await stay(r1), await stay(r2), await stay(r3)];
    at('2026-11-18T23:59:59.999+07:00'); // D−2
    expect((await cancel(early.bookingId)).data.data.lateCancel).toBe(false);
    at('2026-11-19T00:00:00.000+07:00'); // D−1
    expect((await cancel(late.bookingId)).data.data.lateCancel).toBe(true);
    at('2026-11-20T00:00:00.000+07:00'); // D
    const res = await cancelFail(none.bookingId);
    expect(res.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Đã tới ngày nhận, không hủy được (BR-LT-06)' });
  });

  it('chỉ BOOKED hủy được: các trạng thái khác → 409 FSM, kiểm trước "Đã tới ngày nhận"', async () => {
    const checkedIn = await cancelFail(2, undefined, await desk());
    expect(checkedIn.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409, message: 'Không thể chuyển trạng thái đặt chỗ lưu trú từ CHECKED_IN sang CANCELLED' });
    expect((await cancelFail(6)).data.message).toBe('Không thể chuyển trạng thái đặt chỗ lưu trú từ CANCELLED sang CANCELLED');
    expect((await cancelFail(5)).data.message).toBe('Không thể chuyển trạng thái đặt chỗ lưu trú từ CHECKED_OUT sang CANCELLED');
    expect((await cancelFail(9, undefined, await desk())).data.message).toBe('Không thể chuyển trạng thái đặt chỗ lưu trú từ NO_SHOW sang CANCELLED');
  });

  it('404 khách khác, 403 vai trò; hủy hai lần → lần hai 409', async () => {
    expect((await cancelFail(999)).data.message).toBe('Không tìm thấy đặt chỗ lưu trú #999');
    expect((await cancelFail(1)).data.message).toBe('Không tìm thấy đặt chỗ lưu trú #1');
    expect((await cancelFail(4, undefined, await as(ACCOUNTS.caretaker))).data.errorCode).toBe('ACCESS_DENIED');
    await cancel(4);
    expect((await cancelFail(4)).data.errorCode).toBe('INVALID_STATE_TRANSITION');
  });

  it('hủy giải phóng chỗ ngay: đêm kín mở lại', async () => {
    await cancel(4);
    const free = await client.get('/api/branches/1/boarding-availability', { params: { kennelTypeId: 5, checkInDate: '2026-10-26', checkOutDate: '2026-10-28' }, ...(await customer()) });
    expect(free.data.data.available).toBe(true);
  });
});
