import type { AxiosInstance } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ACCOUNTS, authAs, failure, makeClient, pinClock } from '../../../test/boarding-harness';
import { registerAdmissionSource } from './boarding.mock';
import { hasActiveAdmission } from './inpatient.mock';
import { recordDoseForMock } from './visit-clinical.mock';

// Nhận thú (#13, #14), hủy vì hết chuồng (#15), trả thú (#16–18) và hủy phiên trả thú (sales #9) — mock chép BE (BoardingCheckInService, BoardingCheckoutService, BoardingRules, PetWeightApiService).
// Giờ ghim: thứ Sáu 23/10/2026 09:00 (giờ VN). Xem dữ liệu mẫu ở boarding.mock.test.ts: Cốm (#2) đã nhận 21/10, trả dự kiến 31/10, chuồng C-01;
// Bim (#4) BOOKED 26/10→28/10 và chưa tiêm 5 bệnh; Bơ (#1) BOOKED 30/10→01/11 (thú / khách không còn trong mock thú nên không có hồ sơ tiêm → không thiếu mũi).
// Bơ ở 23/10 là nhận sớm 7 ngày và đêm 26/10, 27/10 đã kín (Cốm + Mực + Bim): muốn thử nhận ĐÚNG ngày thì seed rồi `at('2026-10-30T09:00:00+07:00')`.

let client: AxiosInstance;

beforeEach(() => {
  pinClock();
  client = makeClient();
});
afterEach(() => vi.useRealTimers());

const as = (email: string) => authAs(client, email);
const desk = () => as(ACCOUNTS.desk);
const manager = () => as(ACCOUNTS.manager);
const at = (iso: string) => vi.setSystemTime(new Date(iso));

type Booking = { bookingId: number; status: string; checkInDate: string; checkOutDate: string; kennelId: number | null; kennelCode: string | null; activeOrderId: number | null; [k: string]: unknown };
const get = async (id: number, auth?: object): Promise<Booking> => (await client.get(`/api/boarding-bookings/${id}`, auth ?? (await desk()))).data.data;
const kennelOf = async (id: number) => ((await client.get('/api/kennels', await desk())).data.data as { kennelId: number; status: string; currentBookingId: number | null }[]).find((k) => k.kennelId === id)!;

const body = (extra: object = {}) => ({ kennelId: 3, weightKg: 6.2, healthCondition: 'Khỏe, hơi nhút nhát', emergencyPhone: '0987000111', ...extra });
const readiness = async (id: number, auth?: object) => client.get(`/api/boarding-bookings/${id}/check-in-readiness`, auth ?? (await desk()));
const checkIn = async (id: number, extra: object = {}, auth?: object) => client.post(`/api/boarding-bookings/${id}/check-in`, body(extra), auth ?? (await desk()));
const checkInFail = async (id: number, extra: object = {}, auth?: object) => failure(checkIn(id, extra, auth));
const post = async (path: string, data?: object, auth?: object) => client.post(path, data, auth ?? (await desk()));
const startCheckout = (id: number, auth?: object) => post(`/api/boarding-bookings/${id}/start-checkout`, undefined, auth);
const handOver = (id: number, auth?: object) => post(`/api/boarding-bookings/${id}/hand-over`, undefined, auth);
const order = async (id: number) => (await client.get(`/api/orders/${id}`, await desk())).data.data;

/** Rex (chó của khách #1, không có lượt khám nào) đã tiêm đủ, đặt hôm nay và đã nhận vào C-03. Bim không dùng được cho các ca khóa thú: mock lượt khám mẫu cho Bim một lượt thẩm mỹ đang mở. */
async function rexInStay(): Promise<{ petId: number; bookingId: number }> {
  const petId = (await client.post('/api/customers/1/pets', { name: 'Rex', species: 'DOG', sex: 'MALE' }, await desk())).data.data.pet.petId;
  recordDoseForMock(petId, 1, '2026-10-10');
  recordDoseForMock(petId, 2, '2026-10-10');
  const booked = await client.post('/api/boarding-bookings', { petId, branchId: 1, kennelTypeId: 5, checkInDate: '2026-10-23', checkOutDate: '2026-10-24' }, await desk());
  const bookingId = booked.data.data.booking.bookingId;
  expect((await checkIn(bookingId)).data.data.status).toBe('CHECKED_IN');
  return { petId, bookingId };
}

/** Rex tiêm đủ với hai đặt chỗ liền nhau: A hôm nay→24/10, B 24/10→25/10 (cùng BOOKED; nhận B sớm thì chồng A). */
async function rexBackToBack(): Promise<{ petId: number; a: number; b: number }> {
  const petId = (await client.post('/api/customers/1/pets', { name: 'Rex', species: 'DOG', sex: 'MALE' }, await desk())).data.data.pet.petId;
  recordDoseForMock(petId, 1, '2026-10-10');
  recordDoseForMock(petId, 2, '2026-10-10');
  const book = async (checkInDate: string, checkOutDate: string): Promise<number> =>
    (await client.post('/api/boarding-bookings', { petId, branchId: 1, kennelTypeId: 5, checkInDate, checkOutDate }, await desk())).data.data.booking.bookingId;
  return { petId, a: await book('2026-10-23', '2026-10-24'), b: await book('2026-10-24', '2026-10-25') };
}

describe('GET /boarding-bookings/:id/check-in-readiness (#13)', () => {
  it('Bim chưa tiêm 5 bệnh: thiếu mũi, còn chuồng C-03, trong giờ mở cửa → không nhận được', async () => {
    const res = await readiness(4);
    expect(res.data).toMatchObject({ message: 'Điều kiện nhận thú' });
    expect(res.data.data).toEqual({
      vaccineGaps: [{ vaccineTypeId: 2, vaccineTypeName: '5 bệnh', lastAdministeredOn: null, nextDueDate: null }],
      availableKennels: [{ kennelId: 3, branchId: 1, kennelTypeId: 5, kennelTypeName: 'Chuồng chó nhỏ', code: 'C-03', status: 'AVAILABLE', note: null, currentBookingId: null }],
      withinOpeningHours: true,
      canCheckIn: false,
    });
  });

  it('tiêm đủ và còn hạn (hạn tái chủng đúng hôm nay vẫn qua) → nhận được; quá hạn một ngày vẫn là thiếu', async () => {
    await readiness(4); // nạp dữ liệu mẫu
    recordDoseForMock(2, 2, '2026-10-02'); // mũi 1: tái chủng sau 21 ngày = 23/10 = hôm nay
    expect((await readiness(4)).data.data).toMatchObject({ vaccineGaps: [], canCheckIn: true });
    recordDoseForMock(2, 2, '2026-10-01'); // mũi mới nhất theo ngày tiêm vẫn là 02/10 → vẫn đủ
    expect((await readiness(4)).data.data.vaccineGaps).toEqual([]);
  });

  it('mũi gần nhất quá ngày tái chủng → thiếu, kèm ngày tiêm và hạn của mũi đó', async () => {
    await readiness(4);
    recordDoseForMock(2, 2, '2026-10-01'); // hạn 22/10 < hôm nay 23/10
    expect((await readiness(4)).data.data.vaccineGaps).toEqual([{ vaccineTypeId: 2, vaccineTypeName: '5 bệnh', lastAdministeredOn: '2026-10-01', nextDueDate: '2026-10-22' }]);
  });

  it('chỉ tính mũi bắt buộc của loài: thú không có phác đồ bắt buộc không thiếu gì', async () => {
    // Bơ là thú mẫu không có hồ sơ trong mock thú → không có phác đồ để so. Đúng ngày nhận (30/10) để không vướng nhận sớm.
    await get(1);
    at('2026-10-30T09:00:00+07:00');
    expect((await readiness(1)).data.data).toMatchObject({ vaccineGaps: [], canCheckIn: true });
  });

  it('ngoài giờ mở cửa (nghỉ trưa, trước 08:00, từ 19:00) → withinOpeningHours false, không nhận được', async () => {
    await get(1);
    for (const [time, open] of [
      ['07:59', false],
      ['08:00', true],
      ['11:59', true],
      ['12:00', false],
      ['13:29', false],
      ['13:30', true],
      ['18:59', true],
      ['19:00', false],
    ] as const) {
      at(`2026-10-29T${time}:00+07:00`); // nhận sớm 1 đêm (còn chỗ); ở ngày nhận 30/10 thì 19:00 đã bị ST05 chuyển NO_SHOW
      const data = (await readiness(1)).data.data;
      expect([time, data.withinOpeningHours, data.canCheckIn]).toEqual([time, open, open]);
    }
  });

  it('không còn chuồng AVAILABLE đúng loại → availableKennels rỗng, không nhận được', async () => {
    await client.post('/api/kennels/3/start-maintenance', undefined, await manager());
    at('2026-10-30T09:00:00+07:00'); // đúng ngày nhận: canCheckIn false chỉ vì hết chuồng
    expect((await readiness(1)).data.data).toMatchObject({ availableKennels: [], withinOpeningHours: true, canCheckIn: false });
  });

  it('nhận sớm mà một đêm đã kín chỗ → canCheckIn false dù đủ mũi, còn chuồng và đang giờ mở cửa (earlyNightsFit)', async () => {
    await get(4);
    recordDoseForMock(2, 2, '2026-10-10');
    expect((await readiness(4)).data.data).toMatchObject({ vaccineGaps: [], withinOpeningHours: true, canCheckIn: true });
    const rex = (await client.post('/api/customers/1/pets', { name: 'Rex', species: 'DOG', sex: 'MALE' }, await desk())).data.data.pet.petId;
    await client.post('/api/boarding-bookings', { petId: rex, branchId: 1, kennelTypeId: 5, checkInDate: '2026-10-24', checkOutDate: '2026-10-25' }, await desk());
    const data = (await readiness(4)).data.data;
    expect(data.vaccineGaps).toEqual([]);
    expect(data.availableKennels).toHaveLength(1);
    expect(data).toMatchObject({ withinOpeningHours: true, canCheckIn: false });
  });

  it('nhận sớm mà thú có đặt chỗ khác chồng ngày [hôm nay, ngày nhận) → canCheckIn false; đặt chỗ A (nhận đúng hôm nay) vẫn nhận được', async () => {
    const { a, b } = await rexBackToBack();
    expect((await readiness(a)).data.data).toMatchObject({ vaccineGaps: [], canCheckIn: true });
    const data = (await readiness(b)).data.data;
    expect(data).toMatchObject({ vaccineGaps: [], withinOpeningHours: true, canCheckIn: false });
    expect(data.availableKennels).toHaveLength(1);
  });

  it('409 khi không còn BOOKED (câu riêng của readiness, không phải câu FSM); 404; CARETAKER được xem, VET / quản lý / khách thì không', async () => {
    const res = await failure(readiness(2));
    expect(res.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409, message: 'Đặt chỗ không còn ở trạng thái BOOKED' });
    expect((await failure(readiness(999))).data.message).toBe('Không tìm thấy đặt chỗ lưu trú #999');
    expect((await readiness(4, await as(ACCOUNTS.caretaker))).status).toBe(200);
    for (const email of [ACCOUNTS.vet, ACCOUNTS.manager, ACCOUNTS.customer]) expect((await failure(readiness(4, await as(email)))).data.errorCode).toBe('ACCESS_DENIED');
  });
});

describe('POST /boarding-bookings/:id/check-in (#14)', () => {
  it('Bơ nhận đúng ngày: CHECKED_IN, chuồng OCCUPIED, ngày nhận giữ nguyên', async () => {
    await get(1);
    at('2026-10-30T09:00:00+07:00');
    const res = await checkIn(1);
    expect(res.data).toMatchObject({ message: 'Đã nhận thú lưu trú' });
    expect(res.data.data).toMatchObject({
      bookingId: 1,
      status: 'CHECKED_IN',
      kennelId: 3,
      kennelCode: 'C-03',
      checkInDate: '2026-10-30',
      checkOutDate: '2026-11-01',
      actualCheckInAt: '2026-10-30T02:00:00.000Z',
      missingCareLogToday: true,
    });
    expect(await kennelOf(3)).toMatchObject({ status: 'OCCUPIED', currentBookingId: 1 });
  });

  it('Bim nhận sớm 3 ngày sau khi tiêm đủ: ngày nhận về hôm nay, cân nặng INTAKE vào lịch sử, chuồng OCCUPIED, chiếm các đêm thực tế', async () => {
    await get(4);
    recordDoseForMock(2, 2, '2026-10-10');
    const res = await checkIn(4, { weightKg: 6.25, belongings: 'Đệm, 1 gói pate', dietInstructions: 'Ăn hạt mềm' });
    expect(res.data.data).toMatchObject({ status: 'CHECKED_IN', checkInDate: '2026-10-23', checkOutDate: '2026-10-28', kennelCode: 'C-03', actualCheckInAt: '2026-10-23T02:00:00.000Z' });
    const owner = await as(ACCOUNTS.customer);
    const weights = (await client.get('/api/pets/2/weights', owner)).data.data;
    expect(weights[0]).toMatchObject({ weightKg: 6.25, source: 'INTAKE', boardingBookingId: 4 });
    expect((await client.get('/api/pets/2', owner)).data.data.currentWeightKg).toBe(6.25);
    // Đặt chỗ nhận sớm chiếm các đêm thực tế: 23/10 đã có Cốm + Mực + Bim.
    const nights = (await client.get('/api/branches/1/boarding-availability', { params: { kennelTypeId: 5, checkInDate: '2026-10-23', checkOutDate: '2026-10-24' }, ...owner })).data.data.nights;
    expect(nights[0]).toMatchObject({ occupied: 3, remaining: 0 });
  });

  it('thú đang lưu trú thì khóa đánh dấu mất (BR-KH-05), chuyển chủ (BR-KH-08) và xóa (BR-KH-06); rời chuồng thì mở lại', async () => {
    const { petId, bookingId } = await rexInStay();
    const owner = await as(ACCOUNTS.customer);
    const dead = await failure(client.post(`/api/pets/${petId}/mark-deceased`, { deceasedOn: '2026-10-23', confirmed: true }, owner));
    expect(dead.data.message).toBe('Thú đang lưu trú, hãy kết thúc lưu trú trước (BR-LT-12) (BR-KH-05)');
    const moved = await failure(client.post(`/api/pets/${petId}/transfer-owner`, { newCustomerId: 4, previousOwnerConfirmed: true }, await desk()));
    expect(moved.data.message).toBe('Thú đang lưu trú, chưa chuyển chủ được (BR-KH-08)');
    expect((await failure(client.delete(`/api/pets/${petId}`, owner))).data.message).toContain('(BR-KH-06)');
    await post(`/api/boarding-bookings/${bookingId}/end-deceased`, { note: 'Thú mất do bệnh tim' });
    expect((await client.post(`/api/pets/${petId}/transfer-owner`, { newCustomerId: 4, previousOwnerConfirmed: true }, await desk())).status).toBe(200);
  });

  it('body (Bean Validation, chạy trước cả 403): chỉ healthCondition, belongings, dietInstructions, emergencyPhone bị @Valid; chữ đúng CheckInRequest', async () => {
    const empty = await failure(client.post('/api/boarding-bookings/4/check-in', {}, await as(ACCOUNTS.vet)));
    expect(empty.data).toMatchObject({
      errorCode: 'VALIDATION_FAILED',
      message: 'healthCondition: Vui lòng nhập tình trạng sức khỏe lúc nhận; emergencyPhone: Vui lòng nhập số điện thoại liên hệ khẩn',
    });
    expect((await checkInFail(4, { healthCondition: '   ' })).data.message).toBe('healthCondition: Vui lòng nhập tình trạng sức khỏe lúc nhận');
    expect((await checkInFail(4, { healthCondition: 'x'.repeat(1001) })).data.message).toBe('healthCondition: Tình trạng sức khỏe tối đa 1000 ký tự');
    expect((await checkInFail(4, { belongings: 'x'.repeat(1001) })).data.message).toBe('belongings: Đồ gửi kèm tối đa 1000 ký tự');
    expect((await checkInFail(4, { dietInstructions: 'x'.repeat(1001) })).data.message).toBe('dietInstructions: Chế độ ăn riêng tối đa 1000 ký tự');
    for (const phone of ['123456789', '012345678', '012345678901', '09a7000111']) {
      expect((await checkInFail(4, { emergencyPhone: phone })).data.message).toBe('emergencyPhone: Số điện thoại liên hệ khẩn không hợp lệ');
    }
    // Chuỗi rỗng vi phạm cả @NotBlank lẫn @Pattern.
    expect((await checkInFail(4, { emergencyPhone: '' })).data.message).toBe(
      'emergencyPhone: Vui lòng nhập số điện thoại liên hệ khẩn; emergencyPhone: Số điện thoại liên hệ khẩn không hợp lệ',
    );
    expect((await checkInFail(4, { healthCondition: 'x'.repeat(1000), belongings: 'y'.repeat(1000), emergencyPhone: '0987000111' })).data.errorCode).not.toBe('VALIDATION_FAILED'); // đúng 1000 ký tự, 10 số
    expect((await checkInFail(4, { emergencyPhone: '09870001112' })).data.errorCode).not.toBe('VALIDATION_FAILED'); // 11 số hợp lệ
    expect((await checkInFail(4, { kennelId: 'x' })).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await checkInFail(4, { weightKg: '6' })).data.errorCode).toBe('MALFORMED_REQUEST');
  });

  it('thiếu cân nặng / chuồng KHÔNG phải VALIDATION_FAILED: 400 BR-LT-08, cân nặng báo trước, và chỉ sau mũi tiêm', async () => {
    await get(4);
    // Thiếu cả cân lẫn mũi tiêm → mũi tiêm (BR-LT-05) đứng trước.
    const noVaccine = await checkInFail(4, { weightKg: null, kennelId: null });
    expect(noVaccine.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Thiếu hoặc quá hạn mũi tiêm bắt buộc khi lưu trú: 5 bệnh (BR-LT-05)' });
    recordDoseForMock(2, 2, '2026-10-10');
    const noWeight = await checkInFail(4, { weightKg: null, kennelId: null });
    expect(noWeight.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', statusCode: 400, message: 'Vui lòng nhập cân nặng lúc nhận (BR-LT-08)' });
    const noKennel = await checkInFail(4, { kennelId: null });
    expect(noKennel.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', statusCode: 400, message: 'Vui lòng chọn chuồng cho thú (BR-LT-08)' });
    expect((await checkInFail(4, { kennelId: undefined })).data.message).toBe('Vui lòng chọn chuồng cho thú (BR-LT-08)');
    expect((await get(4)).status).toBe('BOOKED');
  });

  it('cân nặng ghi vào lịch sử (BR-KH-04): ≤ 0 → "lớn hơn 0"; làm tròn HALF_UP 2 số rồi ngoài 0.01–9999.99 → "trong khoảng"; lỗi thì không đổi gì', async () => {
    await get(4);
    recordDoseForMock(2, 2, '2026-10-10');
    const range = 'Cân nặng phải trong khoảng 0.01 đến 9999.99 kg (BR-KH-04)';
    for (const weightKg of [0, -1]) {
      expect((await checkInFail(4, { weightKg })).data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Cân nặng phải lớn hơn 0 (BR-KH-04)' });
    }
    for (const weightKg of [10000, 9999.995, 0.001, 0.004]) {
      expect([weightKg, (await checkInFail(4, { weightKg })).data]).toEqual([weightKg, expect.objectContaining({ errorCode: 'BUSINESS_RULE_VIOLATION', message: range })]);
    }
    expect((await get(4)).status).toBe('BOOKED');
    expect((await kennelOf(3)).status).toBe('AVAILABLE');
    const owner = await as(ACCOUNTS.customer);
    expect(((await client.get('/api/pets/2/weights', owner)).data.data as { source: string }[]).some((w) => w.source === 'INTAKE')).toBe(false);
    // Nhiều hơn 2 số lẻ KHÔNG bị từ chối: BE làm tròn (form FE vẫn chặn ở ô nhập).
    expect((await checkIn(4, { weightKg: 4.305 })).data.data.status).toBe('CHECKED_IN');
    expect((await client.get('/api/pets/2/weights', owner)).data.data[0]).toMatchObject({ weightKg: 4.31, source: 'INTAKE', boardingBookingId: 4 });
  });

  it('cạnh dưới: 0.005 làm tròn HALF_UP thành 0.01 nên nhận được, lịch sử ghi 0.01', async () => {
    const { petId, a } = await rexBackToBack();
    expect((await checkIn(a, { weightKg: 0.005 })).data.data.status).toBe('CHECKED_IN');
    expect((await client.get(`/api/pets/${petId}/weights`, await as(ACCOUNTS.customer))).data.data[0]).toMatchObject({ weightKg: 0.01, source: 'INTAKE', boardingBookingId: a });
  });

  it('vai trò: lễ tân và CARETAKER nhận được; quản lý, VET, khách bị 403; 404 đặt chỗ; 409 trạng thái đứng trước 404 chuồng', async () => {
    for (const email of [ACCOUNTS.manager, ACCOUNTS.vet, ACCOUNTS.customer]) expect((await checkInFail(4, {}, await as(email))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await checkInFail(999)).data.message).toBe('Không tìm thấy đặt chỗ lưu trú #999');
    // Đặt chỗ đã CHECKED_IN: 409 FSM, kể cả khi chuồng gửi lên không tồn tại.
    const stale = await checkInFail(2);
    expect(stale.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', message: 'Không thể chuyển trạng thái đặt chỗ lưu trú từ CHECKED_IN sang CHECKED_IN' });
    expect((await checkInFail(2, { kennelId: 999 })).data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409 });
    expect((await checkInFail(5, { kennelId: 999 })).data.message).toBe('Không thể chuyển trạng thái đặt chỗ lưu trú từ CHECKED_OUT sang CHECKED_IN');
    // Chuồng không tồn tại → 404 (chỉ khi thật sự không có): đúng ngày nhận, thú mẫu không thiếu mũi.
    await get(1);
    at('2026-10-30T09:00:00+07:00');
    expect((await checkInFail(1, { kennelId: 999 })).data).toMatchObject({ errorCode: 'RESOURCE_NOT_FOUND', statusCode: 404, message: 'Không tìm thấy chuồng #999' });
    expect((await checkIn(1, {}, await as(ACCOUNTS.caretaker))).data.data.status).toBe('CHECKED_IN');
  });

  it('thứ tự 400: giờ mở cửa (BR-LT-04) → nhận sớm (BR-LT-02/03) → mũi tiêm (BR-LT-05) → cân nặng / chuồng (BR-LT-08)', async () => {
    await get(4);
    at('2026-10-23T12:30:00+07:00');
    const closed = await checkInFail(4, { kennelId: 1 }); // chuồng đang có thú, thiếu mũi — vẫn báo giờ mở cửa trước
    expect(closed.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Chỉ nhận thú trong giờ mở cửa của chi nhánh (BR-LT-04)' });
    at('2026-10-23T09:00:00+07:00');
    // Thiếu mũi (5 bệnh) đứng trước mọi lỗi cân nặng / chuồng; câu không còn đuôi "Hãy tiếp nhận thú như walk-in…".
    expect((await checkInFail(4, { kennelId: 1 })).data.message).toBe('Thiếu hoặc quá hạn mũi tiêm bắt buộc khi lưu trú: 5 bệnh (BR-LT-05)');
    // Đêm 24/10 kín bởi Cốm + Mực + một đặt chỗ khác → nhận sớm 3 ngày bị BR-LT-03, đứng trước cả mũi tiêm.
    const other = (await client.post('/api/customers/1/pets', { name: 'Rex', species: 'DOG', sex: 'MALE' }, await desk())).data.data.pet.petId;
    await client.post('/api/boarding-bookings', { petId: other, branchId: 1, kennelTypeId: 5, checkInDate: '2026-10-24', checkOutDate: '2026-10-25' }, await desk());
    const full = await checkInFail(4);
    expect(full.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Hết chỗ các đêm: 24/10 (BR-LT-03)' });
    expect((await get(4)).status).toBe('BOOKED');
    expect((await kennelOf(3)).status).toBe('AVAILABLE');
  });

  it('chuồng: BR-LT-08 "đúng chi nhánh và đúng loại" (kể cả chuồng chi nhánh khác, không phải 404) → "không còn trống" (chuồng có thú / bảo trì)', async () => {
    await get(4);
    recordDoseForMock(2, 2, '2026-10-10');
    expect((await checkInFail(4, { kennelId: 1 })).data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Chuồng không còn trống (BR-LT-08)' });
    expect((await checkInFail(4, { kennelId: 4 })).data.message).toBe('Chuồng không còn trống (BR-LT-08)');
    expect((await checkInFail(4, { kennelId: 999 })).data.message).toBe('Không tìm thấy chuồng #999');
    // Chuồng của chi nhánh 2: tồn tại nên không phải 404.
    await client.put('/api/branches/2/services/5', { enabled: true }, await as(ACCOUNTS.manager2));
    const far = (await client.post('/api/kennels', { kennelTypeId: 5, code: 'Z-01' }, await as(ACCOUNTS.manager2))).data.data;
    expect(far.branchId).toBe(2);
    expect((await checkInFail(4, { kennelId: far.kennelId })).data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', statusCode: 400, message: 'Chuồng phải đúng chi nhánh và đúng loại đã đặt (BR-LT-08)' });
    expect((await get(4)).status).toBe('BOOKED');
  });

  it('chuồng khác loại với đặt chỗ → BR-LT-08 "đúng chi nhánh và đúng loại đã đặt"', async () => {
    await client.put('/api/branches/1/services/6', { enabled: true }, await manager());
    const cat = (await client.post('/api/kennels', { kennelTypeId: 6, code: 'M-01' }, await manager())).data.data;
    at('2026-10-30T09:00:00+07:00'); // đúng ngày nhận để không vướng nhận sớm
    const res = await checkInFail(1, { kennelId: cat.kennelId });
    expect(res.data.message).toBe('Chuồng phải đúng chi nhánh và đúng loại đã đặt (BR-LT-08)');
  });

  it('nhận sớm: tổng số đêm từ hôm nay tới ngày trả > 30 → BR-LT-02 (đứng trước hết chỗ)', async () => {
    await get(1);
    expect((await post('/api/boarding-bookings/1/extend', { checkOutDate: '2026-11-27' })).status).toBe(200); // 30/10→27/11 = 28 đêm
    const res = await checkInFail(1);
    expect(res.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Thời gian lưu trú phải từ 1 đến 30 đêm và ngày trả phải sau ngày nhận (BR-LT-02)' });
    expect((await readiness(1)).data.data.canCheckIn).toBe(false);
    expect((await get(1)).status).toBe('BOOKED');
  });

  it('nhận sớm mà thú có đặt chỗ khác chồng ngày [hôm nay, ngày nhận) → BR-LT-02 (đứng trước hết chỗ và mũi tiêm)', async () => {
    const { a, b } = await rexBackToBack();
    const res = await checkInFail(b);
    expect(res.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Thú cưng đã có đặt chỗ lưu trú trùng ngày (BR-LT-02)' });
    expect((await get(b)).status).toBe('BOOKED');
    expect((await checkIn(a)).data.data.status).toBe('CHECKED_IN'); // A nhận đúng hôm nay: không phải nhận sớm nên không bị chặn
  });

  it('nhận xong chuồng không còn trống: đặt chỗ BOOKED khác cùng loại hết chuồng để nhận', async () => {
    const rex = (await client.post('/api/customers/1/pets', { name: 'Rex', species: 'DOG', sex: 'MALE' }, await desk())).data.data.pet.petId;
    recordDoseForMock(rex, 1, '2026-10-10');
    recordDoseForMock(rex, 2, '2026-10-10');
    const today = (await client.post('/api/boarding-bookings', { petId: rex, branchId: 1, kennelTypeId: 5, checkInDate: '2026-10-23', checkOutDate: '2026-10-24' }, await desk())).data.data.booking;
    expect((await checkIn(today.bookingId)).data.data).toMatchObject({ status: 'CHECKED_IN', kennelCode: 'C-03' });
    expect((await readiness(4)).data.data).toMatchObject({ availableKennels: [], canCheckIn: false });
  });

  it('thú nhận xong xuất hiện ở danh sách đang lưu trú và đủ cờ chưa có nhật ký hôm nay', async () => {
    await get(1);
    at('2026-10-30T09:00:00+07:00');
    await checkIn(1);
    const rows = (await client.get('/api/boarding-bookings', { params: { missingCareLogToday: true }, ...(await desk()) })).data.data.content as { bookingId: number }[];
    expect(rows.map((r) => r.bookingId)).toContain(1);
  });
});

describe('POST /boarding-bookings/:id/cancel-no-kennel (#15)', () => {
  const cancelNoKennel = (id: number, auth?: object) => post(`/api/boarding-bookings/${id}/cancel-no-kennel`, undefined, auth);
  const notifications = async (email: string) => (await client.get('/api/me/notifications', { params: { size: 100 }, ...(await as(email)) })).data.data.content as { type: string; title: string; body: string; linkUrl: string | null; readAt: string | null }[];

  /** Bim (#4) nhận 26/10: đến thứ Hai 26/10 09:00 mới hủy vì hết chuồng được (BE: `today < checkInDate` → 400). */
  const toCheckInDay = async () => {
    await get(4);
    at('2026-10-26T09:00:00+07:00');
  };

  it('còn chuồng trống đúng loại → 400 BR-LT-08, hãy nhận thú', async () => {
    await toCheckInDay();
    const res = await failure(cancelNoKennel(4));
    expect(res.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Vẫn còn chuồng trống đúng loại, hãy nhận thú (BR-LT-08)' });
    expect((await get(4)).status).toBe('BOOKED');
  });

  it('chưa tới ngày nhận → 400 BR-LT-08 dù đã hết chuồng; kiểm TRƯỚC câu "còn chuồng trống", SAU 409 trạng thái', async () => {
    await client.post('/api/kennels/3/start-maintenance', undefined, await manager()); // hết chuồng nhưng mới 23/10
    const early = await failure(cancelNoKennel(4));
    expect(early.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', statusCode: 400, message: 'Chưa tới ngày nhận, chưa hủy vì hết chuồng được (BR-LT-08)' });
    expect((await get(4)).status).toBe('BOOKED');
    await client.post('/api/kennels/3/end-maintenance', undefined, await manager()); // còn chuồng: vẫn báo chưa tới ngày, không phải "còn chuồng trống"
    expect((await failure(cancelNoKennel(4))).data.message).toBe('Chưa tới ngày nhận, chưa hủy vì hết chuồng được (BR-LT-08)');
    // Đặt chỗ #6 đã CANCELLED (ngày nhận 01/11 còn xa): 409 trạng thái đứng trước kiểm ngày.
    expect((await failure(cancelNoKennel(6))).data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', message: 'Không thể chuyển trạng thái đặt chỗ lưu trú từ CANCELLED sang CANCELLED' });
    at('2026-10-26T09:00:00+07:00'); // đúng ngày nhận: hết kiểm ngày, còn chuồng nên vẫn 400 theo câu "còn chuồng trống"
    expect((await failure(cancelNoKennel(4))).data.message).toBe('Vẫn còn chuồng trống đúng loại, hãy nhận thú (BR-LT-08)');
  });

  it('hết chuồng: phòng khám hủy, không tính hủy muộn, báo khách (link tới đặt chỗ) và quản lý chi nhánh (link tới /admin/boarding)', async () => {
    await toCheckInDay();
    await client.post('/api/kennels/3/start-maintenance', undefined, await manager());
    const customerBefore = (await notifications(ACCOUNTS.customer)).length;
    const managerBefore = (await notifications(ACCOUNTS.manager)).length;
    const res = await cancelNoKennel(4);
    expect(res.data).toMatchObject({ message: 'Đã hủy đặt chỗ vì không còn chuồng trống' });
    expect(res.data.data).toMatchObject({
      status: 'CANCELLED',
      cancelSource: 'CLINIC',
      lateCancel: false,
      cancelReason: 'Chi nhánh không còn chuồng trống đúng loại vào ngày nhận',
    });
    const mine = await notifications(ACCOUNTS.customer);
    expect(mine).toHaveLength(customerBefore + 1);
    expect(mine[0]).toMatchObject({ type: 'BOARDING_CANCELLED_BY_CLINIC', title: 'Phòng khám đã hủy đặt chỗ lưu trú của Bim', linkUrl: '/boarding-bookings/4', readAt: null });
    expect(mine[0].body).toContain('Lần hủy này không tính là hủy muộn.');
    const boss = await notifications(ACCOUNTS.manager);
    expect(boss).toHaveLength(managerBefore + 1);
    expect(boss[0]).toMatchObject({ type: 'BOARDING_CANCELLED_BY_CLINIC', linkUrl: '/admin/boarding' });
    // Không tính hủy muộn nên không thêm vi phạm cho khách.
    expect((await client.get('/api/me/booking-restriction', await as(ACCOUNTS.customer))).data.data.restricted).toBe(false);
  });

  it('409 khi không còn BOOKED; 404; chỉ lễ tân', async () => {
    expect((await failure(cancelNoKennel(2))).data.message).toBe('Không thể chuyển trạng thái đặt chỗ lưu trú từ CHECKED_IN sang CANCELLED');
    expect((await failure(cancelNoKennel(999))).status).toBe(404);
    for (const email of [ACCOUNTS.caretaker, ACCOUNTS.manager, ACCOUNTS.vet, ACCOUNTS.customer]) expect((await failure(cancelNoKennel(4, await as(email)))).data.errorCode).toBe('ACCESS_DENIED');
  });
});

describe('POST /boarding-bookings/:id/start-checkout (#16)', () => {
  it('Cốm ở từ 21/10, hôm nay 23/10 trước 12:00: 2 đêm × 200.000 đ, Order BOARDING PENDING 1 dòng, activeOrderId trỏ tới Order', async () => {
    const res = await startCheckout(2);
    expect(res.data).toMatchObject({ message: 'Đã bắt đầu trả thú' });
    const { booking, orderId, nights, amount } = res.data.data;
    expect([nights, amount]).toEqual([2, 400000]);
    expect(booking).toMatchObject({ bookingId: 2, status: 'CHECKED_IN', activeOrderId: orderId });
    const o = await order(orderId);
    expect(o).toMatchObject({ source: 'BOARDING', status: 'PENDING', boardingBookingId: 2, branchId: 1, customerId: 9002, totalAmount: 400000 });
    expect(o.lines).toEqual([expect.objectContaining({ lineType: 'BOARDING', description: 'Lưu trú Chuồng chó nhỏ × 2 đêm', quantity: 2, unitPrice: 200000, lineTotal: 400000, canEdit: false, canDelete: false })]);
    expect((await get(2)).activeOrderId).toBe(orderId);
  });

  it('trả sau 12:00 (giờ làm việc buổi chiều) tính thêm 1 đêm', async () => {
    await get(2);
    at('2026-10-23T13:30:00+07:00');
    const res = await startCheckout(2);
    expect([res.data.data.nights, res.data.data.amount]).toEqual([3, 600000]);
  });

  it('số đêm tối thiểu 1: nhận và trả trong cùng ngày vẫn tính 1 đêm', async () => {
    await get(1);
    at('2026-10-30T09:00:00+07:00');
    await checkIn(1);
    const res = await startCheckout(1);
    expect([res.data.data.nights, res.data.data.amount]).toEqual([1, 200000]);
  });

  it('nhận và trả cùng ngày lúc 14:00 (sau 12:00) vẫn 1 đêm: đêm trễ cộng TRƯỚC khi lấy tối thiểu 1 (BoardingRules.chargedNights)', async () => {
    await get(1);
    at('2026-10-30T09:00:00+07:00');
    await checkIn(1);
    at('2026-10-30T14:00:00+07:00');
    const res = await startCheckout(1);
    expect([res.data.data.nights, res.data.data.amount]).toEqual([1, 200000]);
  });
  it('đã có Order PENDING thì dùng lại và tính lại số đêm, không sinh Order thứ hai', async () => {
    const first = (await startCheckout(2)).data.data;
    at('2026-10-24T09:00:00+07:00');
    const second = (await startCheckout(2)).data.data;
    expect(second.orderId).toBe(first.orderId);
    expect([second.nights, second.amount]).toEqual([3, 600000]);
    const orders = (await client.get('/api/orders', { params: { boardingBookingId: 2 }, ...(await desk()) })).data.data.content;
    expect(orders).toHaveLength(1);
    expect((await order(first.orderId)).lines).toHaveLength(1);
  });

  it('OVERDUE vẫn trả được và tính tiếp theo đêm', async () => {
    await get(2);
    at('2026-10-31T13:30:00+07:00');
    expect((await get(2)).status).toBe('OVERDUE');
    const res = await startCheckout(2);
    expect([res.data.data.nights, res.data.data.amount]).toEqual([11, 2200000]); // 21/10→31/10 = 10 đêm, quá 12:00 +1
    expect(res.data.data.booking.status).toBe('OVERDUE');
  });

  it('ngoài giờ mở cửa → 400 BR-LT-04; thú không còn trong chuồng → 409 (kiểm trước giờ mở cửa)', async () => {
    await get(2);
    at('2026-10-23T12:30:00+07:00');
    expect((await failure(startCheckout(2))).data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Chỉ trả thú trong giờ mở cửa của chi nhánh (BR-LT-04)' });
    const gone = await failure(startCheckout(4));
    expect(gone.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409, message: 'Thú không còn trong chuồng' });
    expect((await failure(startCheckout(5))).status).toBe(409);
    expect((await failure(startCheckout(6))).status).toBe(409);
  });

  it('chỉ lễ tân; 404', async () => {
    for (const email of [ACCOUNTS.caretaker, ACCOUNTS.manager, ACCOUNTS.vet, ACCOUNTS.customer]) expect((await failure(startCheckout(2, await as(email)))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(startCheckout(999))).data.message).toBe('Không tìm thấy đặt chỗ lưu trú #999');
  });
});

describe('vòng trả thú: bắt đầu → thu tiền → giao thú; hủy phiên (Review Focus #4)', () => {
  const openShift = async () => client.post('/api/cashier-shifts', { afterHours: false }, await desk());
  const pay = async (orderIds: number[], amount: number) => client.post('/api/payments', { orderIds, method: 'CASH', amount }, await desk());
  const abort = (orderId: number, reason?: unknown, auth?: object) => post(`/api/orders/${orderId}/abort-checkout`, reason === undefined ? {} : { reason }, auth);

  const paidOrdersOf = async (bookingId: number) => (await client.get('/api/orders', { params: { boardingBookingId: bookingId, status: 'PAID' }, ...(await desk()) })).data.data.content as { orderId: number }[];
  /** Bắt đầu trả thú Cốm (#2) rồi thu đủ tiền ở máy thu ngân: trả Order lưu trú đã PAID. */
  const startAndPay = async () => {
    const { orderId, amount } = (await startCheckout(2)).data.data;
    await openShift();
    expect((await pay([orderId], amount)).status).toBe(201);
    return { orderId, amount };
  };

  it('giao thú khi Order chưa PAID → 400 BR-LT-09 "…, chưa giao thú được", chưa có Order cũng vậy', async () => {
    const noOrder = await failure(handOver(2));
    expect(noOrder.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', statusCode: 400, message: 'Order lưu trú chưa thanh toán, chưa giao thú được (BR-LT-09)' });
    await startCheckout(2);
    expect((await failure(handOver(2))).data.message).toBe('Order lưu trú chưa thanh toán, chưa giao thú được (BR-LT-09)');
    expect((await get(2)).status).toBe('CHECKED_IN');
    expect((await kennelOf(1)).status).toBe('OCCUPIED');
  });

  it('activeOrderId là null sau khi Order lưu trú đã PAID (A10: chỉ Order OPEN / PENDING), Order PAID vẫn còn đúng 1 dòng', async () => {
    const { orderId, amount } = (await startCheckout(2)).data.data;
    expect((await get(2)).activeOrderId).toBe(orderId);
    await openShift();
    expect((await pay([orderId], amount)).status).toBe(201);
    expect((await order(orderId)).status).toBe('PAID');
    expect((await get(2)).activeOrderId).toBeNull();
    const rows = (await client.get('/api/boarding-bookings', { params: { inStay: true }, ...(await desk()) })).data.data.content as { bookingId: number; activeOrderId: number | null }[];
    expect(rows.find((b) => b.bookingId === 2)).toMatchObject({ status: 'CHECKED_IN', activeOrderId: null });
    expect((await paidOrdersOf(2)).map((o) => o.orderId)).toEqual([orderId]);
  });

  it('start-checkout khi đã PAID → 409 "Đã thu tiền lưu trú, chỉ còn giao thú", không sinh Order thứ hai (cả khi sang ngày mới)', async () => {
    const { orderId } = await startAndPay();
    const again = await failure(startCheckout(2));
    expect(again.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409, message: 'Đã thu tiền lưu trú, chỉ còn giao thú' });
    at('2026-10-24T09:00:00+07:00');
    expect((await failure(startCheckout(2))).data.message).toBe('Đã thu tiền lưu trú, chỉ còn giao thú');
    const all = (await client.get('/api/orders', { params: { boardingBookingId: 2 }, ...(await desk()) })).data.data.content as { orderId: number; status: string }[];
    expect(all.map((o) => [o.orderId, o.status])).toEqual([[orderId, 'PAID']]);
    expect((await get(2)).status).toBe('CHECKED_IN');
  });

  it('start-checkout đã PAID mà ngoài giờ mở cửa → 400 BR-LT-04 (giờ mở cửa kiểm TRƯỚC PAID, như BE); thú đã rời chuồng → 409 trạng thái trước cả hai', async () => {
    await startAndPay();
    at('2026-10-23T12:30:00+07:00');
    expect((await failure(startCheckout(2))).data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', statusCode: 400, message: 'Chỉ trả thú trong giờ mở cửa của chi nhánh (BR-LT-04)' });
    at('2026-10-23T13:30:00+07:00');
    expect((await handOver(2)).data.data.status).toBe('CHECKED_OUT');
    at('2026-10-23T12:30:00+07:00');
    expect((await failure(startCheckout(2))).data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409, message: 'Thú không còn trong chuồng' });
  });

  it('giao thú dựa vào Order PAID chứ không dựa vào activeOrderId (đã null): CHECKED_OUT / RETURNED, chuồng AVAILABLE, giao lần hai → 409', async () => {
    await startAndPay();
    expect((await get(2)).activeOrderId).toBeNull();
    const res = await handOver(2);
    expect(res.data).toMatchObject({ message: 'Đã giao thú' });
    expect(res.data.data).toMatchObject({ status: 'CHECKED_OUT', endReason: 'RETURNED', actualCheckOutAt: '2026-10-23T02:00:00.000Z', activeOrderId: null });
    expect(await kennelOf(1)).toMatchObject({ status: 'AVAILABLE', currentBookingId: null });
    expect((await get(2)).activeOrderId).toBeNull();
    const inStay = (await client.get('/api/boarding-bookings', { params: { inStay: true }, ...(await desk()) })).data.data.content.map((b: { bookingId: number }) => b.bookingId);
    expect(inStay).toEqual([3]);
    expect((await failure(handOver(2))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
  });

  it('khách không trả: hủy phiên (lý do bắt buộc) → Order CANCELLED CHECKOUT_ABORTED, đặt chỗ giữ CHECKED_IN, bắt đầu lại sinh Order mới', async () => {
    const first = (await startCheckout(2)).data.data;
    const noReason = await failure(abort(first.orderId, '   '));
    expect(noReason.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Vui lòng nhập lý do hủy phiên trả thú (BR-LT-09)' });
    expect((await failure(abort(first.orderId))).data.message).toBe('Vui lòng nhập lý do hủy phiên trả thú (BR-LT-09)');
    expect((await failure(abort(first.orderId, 'x'.repeat(301)))).data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'reason: Lý do tối đa 300 ký tự' });

    const res = await abort(first.orderId, '  Khách chưa mang đủ tiền  ');
    expect(res.data).toMatchObject({ message: 'Đã hủy phiên trả thú' });
    expect(res.data.data).toMatchObject({ orderId: first.orderId, status: 'CANCELLED', cancelType: 'CHECKOUT_ABORTED', cancelReason: 'Khách chưa mang đủ tiền', source: 'BOARDING' });
    const booking = await get(2);
    expect(booking).toMatchObject({ status: 'CHECKED_IN', activeOrderId: null });
    expect((await kennelOf(1)).status).toBe('OCCUPIED');

    const second = (await startCheckout(2)).data.data;
    expect(second.orderId).not.toBe(first.orderId);
    expect(second.booking.activeOrderId).toBe(second.orderId);
    expect((await get(2)).activeOrderId).toBe(second.orderId);
    expect((await order(first.orderId)).status).toBe('CANCELLED');
  });

  it('hủy phiên: 409 khi không phải Order BOARDING đang PENDING (đã PAID, đã hủy, Order bán lẻ); kiểm trước lý do', async () => {
    const { orderId, amount } = (await startCheckout(2)).data.data;
    await abort(orderId, 'Khách chưa trả');
    const cancelled = await failure(abort(orderId, 'Lần hai'));
    expect(cancelled.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409 });
    const retail = await failure(abort(4)); // Order bán lẻ PENDING mẫu — không lý do vẫn là 409
    expect(retail.data.errorCode).toBe('INVALID_STATE_TRANSITION');
    const next = (await startCheckout(2)).data.data;
    await openShift();
    await pay([next.orderId], amount);
    expect((await failure(abort(next.orderId, 'Đã trả rồi'))).data.errorCode).toBe('INVALID_STATE_TRANSITION');
  });

  it('hủy phiên: chỉ lễ tân; 404; Order chi nhánh khác → 403 phạm vi (sau 404)', async () => {
    const { orderId } = (await startCheckout(2)).data.data;
    for (const email of [ACCOUNTS.manager, ACCOUNTS.caretaker, ACCOUNTS.vet]) expect((await failure(abort(orderId, 'x', await as(email)))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(abort(999, 'x'))).data.message).toBe('Không tìm thấy đơn hàng #999');
    expect((await failure(abort(8, 'x'))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
  });

  it('thú đã rời chuồng: không hủy phiên được (400 BR-LT-09, dùng hủy Order khách không thanh toán); quản lý hủy Order được', async () => {
    const { orderId } = (await post('/api/boarding-bookings/2/end-deceased', { note: 'Thú lên cơn co giật lúc 8h sáng' })).data.data;
    const res = await failure(abort(orderId, 'Hủy'));
    expect(res.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Thú cưng không còn trong chuồng, hãy hủy Order theo luồng khách không thanh toán (BR-LT-09)' });
    const cancelled = await client.post(`/api/orders/${orderId}/cancel-unpaid`, { reason: 'Khách bỏ về, không liên lạc được' }, await manager());
    expect(cancelled.data.data).toMatchObject({ status: 'CANCELLED', cancelType: 'UNPAID' });
  });

  it('quản lý không hủy được Order lưu trú khi thú còn trong chuồng (xử lý theo BR-LT-09)', async () => {
    const { orderId } = (await startCheckout(2)).data.data;
    const res = await failure(client.post(`/api/orders/${orderId}/cancel-unpaid`, { reason: 'Muốn hủy' }, await manager()));
    expect(res.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', message: 'Order lưu trú khi thú còn trong chuồng phải hủy phiên trả thú (BR-LT-09)' });
    expect((await order(orderId)).status).toBe('PENDING');
  });

  it('Order BOARDING PENDING hiện trong "Order chờ thu" của khách (bàn Lễ tân) và thu được bằng POST /payments (A11)', async () => {
    await get(4);
    recordDoseForMock(2, 2, '2026-10-10');
    await checkIn(4);
    const { orderId, amount } = (await startCheckout(4)).data.data;
    const payable = (await client.get('/api/customers/1/payable-orders', await desk())).data.data as { orderId: number; source: string; totalAmount: number }[];
    expect(payable).toEqual(expect.arrayContaining([expect.objectContaining({ orderId, source: 'BOARDING', totalAmount: amount })]));
    await openShift();
    expect((await pay([orderId], amount)).status).toBe(201);
    expect(((await client.get('/api/customers/1/payable-orders', await desk())).data.data as { orderId: number }[]).map((o) => o.orderId)).not.toContain(orderId);
    expect((await handOver(4)).data.data.status).toBe('CHECKED_OUT');
  });
});

describe('POST /boarding-bookings/:id/end-deceased (#18)', () => {
  const deceased = (id: number, note?: unknown, auth?: object) => post(`/api/boarding-bookings/${id}/end-deceased`, note === undefined ? {} : { note }, auth);

  it('kết thúc do thú mất: CHECKED_OUT / PET_DECEASED, chuồng về AVAILABLE, Order PENDING tính đến hôm nay, trả CheckoutStarted', async () => {
    const res = await deceased(2, '  Thú lên cơn co giật lúc 8h sáng ');
    expect(res.data).toMatchObject({ message: 'Đã kết thúc lưu trú do thú mất' });
    const { booking, orderId, nights, amount } = res.data.data;
    expect([nights, amount]).toEqual([2, 400000]);
    expect(booking).toMatchObject({ status: 'CHECKED_OUT', endReason: 'PET_DECEASED', actualCheckOutAt: '2026-10-23T02:00:00.000Z', activeOrderId: null });
    expect(await kennelOf(1)).toMatchObject({ status: 'AVAILABLE', currentBookingId: null });
    expect(await order(orderId)).toMatchObject({ source: 'BOARDING', status: 'PENDING', boardingBookingId: 2 });
  });

  it('đã bắt đầu trả thú trước đó thì dùng lại Order PENDING (BR-LT-09)', async () => {
    const started = (await startCheckout(2)).data.data;
    const res = await deceased(2, 'Thú mất');
    expect(res.data.data.orderId).toBe(started.orderId);
  });

  it('thú mất KHÔNG BAO GIỜ cộng đêm trễ (BR-LT-12): nhận 21/10, mất 31/10 13:30 (đã OVERDUE) là 10 đêm', async () => {
    await get(2);
    at('2026-10-31T13:30:00+07:00');
    const late = (await deceased(2, 'Thú mất lúc chiều')).data.data;
    expect([late.nights, late.amount]).toEqual([10, 2000000]);
    expect(late.booking).toMatchObject({ status: 'CHECKED_OUT', endReason: 'PET_DECEASED' });
  });

  it('thú mất sau 12:00 của ngày thứ hai vẫn 2 đêm; đã mở Order lúc 13:30 (3 đêm) thì tính lại không đêm trễ: 2 đêm', async () => {
    await get(2);
    at('2026-10-23T13:30:00+07:00');
    const started = (await startCheckout(2)).data.data;
    expect(started.nights).toBe(3); // start-checkout sau 12:00 cộng 1 đêm trễ
    const res = (await deceased(2, 'Thú mất')).data.data;
    expect([res.orderId, res.nights, res.amount]).toEqual([started.orderId, 2, 400000]);
    expect((await order(res.orderId)).lines).toEqual([expect.objectContaining({ quantity: 2, unitPrice: 200000, lineTotal: 400000 })]);
  });

  it('nhận và mất cùng ngày sau 12:00: 1 đêm', async () => {
    const { bookingId } = await rexInStay();
    at('2026-10-23T14:00:00+07:00');
    const res = (await deceased(bookingId, 'Thú mất lúc 14h')).data.data;
    expect([res.nights, res.amount]).toEqual([1, 200000]);
  });

  it('đã thu tiền rồi thú mới mất thì trả lại đúng Order PAID đó (BR-LT-12), không sinh Order thứ hai', async () => {
    const started = (await startCheckout(2)).data.data;
    await client.post('/api/cashier-shifts', { afterHours: false }, await desk());
    expect((await client.post('/api/payments', { orderIds: [started.orderId], method: 'CASH', amount: started.amount }, await desk())).status).toBe(201);
    at('2026-10-24T09:00:00+07:00'); // sang ngày mới: Order PAID không bị tính lại số đêm
    const res = await deceased(2, 'Thú mất');
    expect([res.data.data.orderId, res.data.data.nights, res.data.data.amount]).toEqual([started.orderId, started.nights, started.amount]);
    expect(res.data.data.booking).toMatchObject({ status: 'CHECKED_OUT', endReason: 'PET_DECEASED', activeOrderId: null });
    expect((await order(started.orderId)).lines).toEqual([expect.objectContaining({ quantity: started.nights, unitPrice: 200000 })]);
    const all = (await client.get('/api/orders', { params: { boardingBookingId: 2 }, ...(await desk()) })).data.data.content as { orderId: number; status: string }[];
    expect(all.map((o) => [o.orderId, o.status])).toEqual([[started.orderId, 'PAID']]);
  });

  it('thú mất xong mới đánh dấu đã mất được (BR-LT-12): đang lưu trú thì bị chặn', async () => {
    const { petId, bookingId } = await rexInStay();
    const owner = await as(ACCOUNTS.customer);
    expect((await failure(client.post(`/api/pets/${petId}/mark-deceased`, { deceasedOn: '2026-10-23', confirmed: true }, owner))).data.message).toContain('hãy kết thúc lưu trú trước');
    const res = await deceased(bookingId, 'Thú mất do bệnh tim');
    expect([res.data.data.nights, res.data.data.amount]).toEqual([1, 200000]); // nhận và mất cùng ngày vẫn tính tối thiểu 1 đêm
    expect((await client.post(`/api/pets/${petId}/mark-deceased`, { deceasedOn: '2026-10-23', confirmed: true }, owner)).status).toBe(200);
  });

  it('ghi chú bắt buộc và ≤ 2000 ký tự; sai kiểu; thú không còn trong chuồng → 409 câu FSM; chỉ lễ tân; 404', async () => {
    expect((await failure(deceased(2))).data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'note: Vui lòng ghi rõ diễn biến' });
    expect((await failure(deceased(2, '   '))).data.message).toBe('note: Vui lòng ghi rõ diễn biến');
    expect((await failure(deceased(2, 'x'.repeat(2001)))).data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'note: Diễn biến tối đa 2000 ký tự' });
    expect((await failure(deceased(2, 5))).data.errorCode).toBe('MALFORMED_REQUEST');
    // Đặt chỗ BOOKED (#4) và đã CHECKED_OUT (#5): câu FSM "… từ X sang CHECKED_OUT", không phải câu của start-checkout.
    expect((await failure(deceased(4, 'Không ở trong chuồng'))).data).toMatchObject({
      errorCode: 'INVALID_STATE_TRANSITION',
      statusCode: 409,
      message: 'Không thể chuyển trạng thái đặt chỗ lưu trú từ BOOKED sang CHECKED_OUT',
    });
    expect((await failure(deceased(5, 'Đã trả'))).data.message).toBe('Không thể chuyển trạng thái đặt chỗ lưu trú từ CHECKED_OUT sang CHECKED_OUT');
    expect((await deceased(2, 'x'.repeat(2000))).data.data.booking.status).toBe('CHECKED_OUT'); // đúng 2000 ký tự vẫn được
    expect((await failure(deceased(999, 'x'))).status).toBe(404);
    for (const email of [ACCOUNTS.caretaker, ACCOUNTS.manager, ACCOUNTS.vet]) expect((await failure(deceased(2, 'x', await as(email)))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(deceased(2, undefined, await as(ACCOUNTS.vet)))).data.errorCode).toBe('VALIDATION_FAILED');
  });
});

describe('hand-over (#17) — quyền', () => {
  it('chỉ lễ tân; 404; thú không còn trong chuồng → 409', async () => {
    for (const email of [ACCOUNTS.caretaker, ACCOUNTS.manager, ACCOUNTS.vet, ACCOUNTS.customer]) expect((await failure(handOver(2, await as(email)))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(handOver(999))).status).toBe(404);
    expect((await failure(handOver(4))).data).toMatchObject({
      errorCode: 'INVALID_STATE_TRANSITION',
      statusCode: 409,
      message: 'Không thể chuyển trạng thái đặt chỗ lưu trú từ BOOKED sang CHECKED_OUT',
    });
    expect((await failure(handOver(5))).data.message).toBe('Không thể chuyển trạng thái đặt chỗ lưu trú từ CHECKED_OUT sang CHECKED_OUT');
  });
});

describe('đặt lại sau khi đặt chỗ cũ đã CHECKED_OUT (Review Focus #1)', () => {
  it('đặt chỗ đã CHECKED_OUT không còn chặn đặt chồng ngày của cùng thú; đang CHECKED_IN thì còn chặn', async () => {
    await get(4);
    recordDoseForMock(2, 2, '2026-10-10');
    await checkIn(4); // 23/10→28/10, CHECKED_IN
    const again = { petId: 2, branchId: 1, kennelTypeId: 5, checkInDate: '2026-10-24', checkOutDate: '2026-10-25' };
    const blocked = await failure(client.post('/api/boarding-bookings', again, await desk()));
    expect(blocked.data.message).toBe('Thú cưng đã có đặt chỗ lưu trú trùng ngày (BR-LT-02)');
    const { orderId, amount } = (await startCheckout(4)).data.data;
    await client.post('/api/cashier-shifts', { afterHours: false }, await desk());
    await client.post('/api/payments', { orderIds: [orderId], method: 'CASH', amount }, await desk());
    await handOver(4);
    expect((await client.post('/api/boarding-bookings', again, await desk())).status).toBe(201);
  });
});

describe('guard BR-LT-17 — thú còn ca nội trú ADMITTED không trả được (T57, A13)', () => {
  // Nguồn thật (mock nội trú) do tier3-wiring nối; ở đây thay bằng nguồn giả theo thú, test tự trả lại sau mỗi ca.
  afterEach(() => registerAdmissionSource(hasActiveAdmission));
  const refusedAs = (res: Awaited<ReturnType<typeof failure>>) => [res.status, res.data.errorCode, /\((BR-[A-Z]+-\d+)\)$/.exec(res.data.message)?.[1]];

  it('bắt đầu trả thú và giao thú → 409 INVALID_STATE_TRANSITION "(BR-LT-17)", không sinh Order; hết ADMITTED thì trả được', async () => {
    const { petId, bookingId } = await rexInStay();
    registerAdmissionSource((id) => id === petId);
    expect(refusedAs(await failure(startCheckout(bookingId)))).toEqual([409, 'INVALID_STATE_TRANSITION', 'BR-LT-17']);
    expect(refusedAs(await failure(handOver(bookingId)))).toEqual([409, 'INVALID_STATE_TRANSITION', 'BR-LT-17']);
    expect((await get(bookingId)).activeOrderId).toBeNull();

    registerAdmissionSource(() => false);
    expect((await startCheckout(bookingId)).status).toBe(200);
  });

  it('thứ tự: sau 409 "không còn trong chuồng", trước 400 BR-LT-04 (ngoài giờ mở cửa) và 400 BR-LT-09 (chưa thu tiền)', async () => {
    const { petId, bookingId } = await rexInStay();
    registerAdmissionSource((id) => id === petId);
    at('2026-10-23T20:00:00+07:00');
    expect(refusedAs(await failure(startCheckout(bookingId)))).toEqual([409, 'INVALID_STATE_TRANSITION', 'BR-LT-17']);
    // Đặt chỗ #5 (Bim) đã CHECKED_OUT: câu 409 trạng thái, không phải BR-LT-17 dù nguồn nói thú nào cũng có ca.
    registerAdmissionSource(() => true);
    const gone = await failure(handOver(5));
    expect(refusedAs(gone)).toEqual([409, 'INVALID_STATE_TRANSITION', undefined]);
    expect((await failure(startCheckout(5))).data.message).toBe('Thú không còn trong chuồng');
  });

  it('chỉ chặn thú có ca nội trú: Cốm vẫn trả được; thú mất (end-deceased) không bị chặn', async () => {
    const { petId, bookingId } = await rexInStay();
    registerAdmissionSource((id) => id === petId);
    expect((await startCheckout(2)).status).toBe(200);
    expect((await post(`/api/boarding-bookings/${bookingId}/end-deceased`, { note: 'Thú mất trong lúc điều trị' })).status).toBe(200);
  });
});
