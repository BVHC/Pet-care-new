import type { AxiosInstance } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ACCOUNTS, authAs, failure, makeClient, pinClock } from '../../../test/boarding-harness';
import { ruleIdOf } from '../api-error';

// Chuồng (#1–6) và còn chỗ theo đêm (#7). Giờ ghim: thứ Sáu 23/10/2026 09:00 (giờ VN). Dữ liệu mẫu (boarding.seed.ts), chi nhánh 1:
// loại #5 "Chuồng chó nhỏ" 200.000 đ/đêm (chó ≤ 10 kg, bật tại CN1); loại #6 "Chuồng mèo" CHƯA bật tại CN1.
// Chuồng: #1 C-01 OCCUPIED (Cốm, đặt chỗ #2), #2 C-02 OCCUPIED (Mực, #3), #3 C-03 AVAILABLE (đã có lịch sử lưu trú), #4 C-04 MAINTENANCE.
// Sức chứa loại #5 = 3 (không tính chuồng bảo trì). Đêm 26/10, 27/10 (Cốm + Mực + Bim) và 30/10 (Cốm + Mực + Bơ) đã kín.

let client: AxiosInstance;

beforeEach(() => {
  pinClock();
  client = makeClient();
});
afterEach(() => vi.useRealTimers());

const as = (email: string) => authAs(client, email);
const manager = () => as(ACCOUNTS.manager);
const desk = () => as(ACCOUNTS.desk);

type Kennel = { kennelId: number; code: string; status: string; note: string | null; currentBookingId: number | null; kennelTypeName: string };
const kennels = async (params: Record<string, unknown> = {}, auth?: object): Promise<Kennel[]> =>
  (await client.get('/api/kennels', { params, ...(auth ?? (await desk())) })).data.data;
const codes = (list: Kennel[]) => list.map((k) => k.code);

describe('GET /kennels (#1)', () => {
  it('chi nhánh người gọi, sắp theo mã, kèm tên loại và đặt chỗ đang giữ chuồng', async () => {
    const res = await client.get('/api/kennels', await desk());
    expect(res.data).toMatchObject({ message: 'Danh sách chuồng', code: 200 });
    expect(res.data.data).toEqual([
      { kennelId: 1, branchId: 1, kennelTypeId: 5, kennelTypeName: 'Chuồng chó nhỏ', code: 'C-01', status: 'OCCUPIED', note: null, currentBookingId: 2 },
      { kennelId: 2, branchId: 1, kennelTypeId: 5, kennelTypeName: 'Chuồng chó nhỏ', code: 'C-02', status: 'OCCUPIED', note: null, currentBookingId: 3 },
      { kennelId: 3, branchId: 1, kennelTypeId: 5, kennelTypeName: 'Chuồng chó nhỏ', code: 'C-03', status: 'AVAILABLE', note: null, currentBookingId: null },
      { kennelId: 4, branchId: 1, kennelTypeId: 5, kennelTypeName: 'Chuồng chó nhỏ', code: 'C-04', status: 'MAINTENANCE', note: 'Hỏng khóa cửa, chờ thợ thay', currentBookingId: null },
    ]);
  });

  it('lọc theo loại chuồng và trạng thái', async () => {
    expect(codes(await kennels({ status: 'MAINTENANCE' }))).toEqual(['C-04']);
    expect(codes(await kennels({ kennelTypeId: 6 }))).toEqual([]);
    expect(codes(await kennels({ kennelTypeId: 5, status: 'AVAILABLE' }))).toEqual(['C-03']);
  });

  it('sắp theo mã tăng dần theo từng ký tự, không natural sort', async () => {
    const auth = await manager();
    for (const code of ['C-2', 'C-10']) await client.post('/api/kennels', { kennelTypeId: 5, code }, auth);
    expect(codes(await kennels({}, auth))).toEqual(['C-01', 'C-02', 'C-03', 'C-04', 'C-10', 'C-2']);
  });

  it('BRANCH_MANAGER, RECEPTIONIST, CARETAKER xem được; VET, khách, ADMIN, SUPER_MANAGER bị 403', async () => {
    for (const email of [ACCOUNTS.manager, ACCOUNTS.desk, ACCOUNTS.caretaker]) {
      expect((await client.get('/api/kennels', await as(email))).status).toBe(200);
    }
    for (const email of [ACCOUNTS.vet, ACCOUNTS.customer, ACCOUNTS.admin, ACCOUNTS.chain]) {
      const res = await failure(client.get('/api/kennels', await as(email)));
      expect(res.data).toMatchObject({ errorCode: 'ACCESS_DENIED', message: 'Không có quyền thực hiện thao tác này', statusCode: 403 });
    }
  });

  it('chỉ thấy chuồng chi nhánh của mình', async () => {
    expect(await kennels({}, await as(ACCOUNTS.manager2))).toEqual([]);
  });

  it('tham số sai kiểu → 400 trước cả kiểm vai trò', async () => {
    const vet = await as(ACCOUNTS.vet);
    const type = await failure(client.get('/api/kennels', { params: { kennelTypeId: 'abc' }, ...vet }));
    expect(type.data).toMatchObject({ errorCode: 'MALFORMED_REQUEST', message: "Tham số 'kennelTypeId' sai kiểu dữ liệu" });
    const status = await failure(client.get('/api/kennels', { params: { status: 'FULL' }, ...(await desk()) }));
    expect(status.data.message).toBe("Tham số 'status' sai kiểu dữ liệu");
  });

  it('chưa đăng nhập → 401', async () => {
    const res = await failure(client.get('/api/kennels'));
    expect(res.data).toMatchObject({ errorCode: 'UNAUTHENTICATED', message: 'Chưa đăng nhập hoặc phiên đăng nhập đã hết hạn' });
  });
});

describe('POST /kennels (#2)', () => {
  const create = async (body: object, auth?: object) => client.post('/api/kennels', body, auth ?? (await manager()));

  it('tạo chuồng AVAILABLE: bỏ khoảng trắng hai đầu của mã và ghi chú, ghi chú trống thành null', async () => {
    const res = await create({ kennelTypeId: 5, code: '  C-05  ', note: '  Gần cửa sổ ' });
    expect(res.status).toBe(201);
    expect(res.data).toMatchObject({ message: 'Đã tạo chuồng', code: 201 });
    expect(res.data.data).toMatchObject({ branchId: 1, kennelTypeId: 5, kennelTypeName: 'Chuồng chó nhỏ', code: 'C-05', status: 'AVAILABLE', note: 'Gần cửa sổ', currentBookingId: null });
    const blank = await create({ kennelTypeId: 5, code: 'C-06', note: '   ' });
    expect(blank.data.data.note).toBeNull();
    expect(codes(await kennels())).toContain('C-06');
  });

  it('kiểm dữ liệu vào từng trường theo thứ tự, gộp bằng "; "', async () => {
    const empty = await failure(create({}));
    expect(empty.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'kennelTypeId: Vui lòng chọn loại chuồng; code: Vui lòng nhập mã chuồng' });
    expect((await failure(create({ kennelTypeId: 5, code: '   ' }))).data.message).toBe('code: Vui lòng nhập mã chuồng');
    expect((await failure(create({ kennelTypeId: 5, code: 'x'.repeat(21) }))).data.message).toBe('code: Mã chuồng tối đa 20 ký tự');
    expect((await failure(create({ kennelTypeId: 5, code: 'x'.repeat(20), note: 'n'.repeat(301) }))).data.message).toBe('note: Ghi chú tối đa 300 ký tự');
    expect((await create({ kennelTypeId: 5, code: 'x'.repeat(20), note: 'n'.repeat(300) })).status).toBe(201);
  });

  it('kiểu dữ liệu sai (số thực vào trường số nguyên, mã không phải chuỗi) → 400 MALFORMED_REQUEST', async () => {
    const message = 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu';
    expect((await failure(create({ kennelTypeId: 5.5, code: 'C-07' }))).data).toMatchObject({ errorCode: 'MALFORMED_REQUEST', message });
    expect((await failure(create({ kennelTypeId: 5, code: 7 }))).data.message).toBe(message);
  });

  it('400 của body chạy trước 403 vai trò; sau đó mới đến vai trò', async () => {
    const desk1 = await desk();
    expect((await failure(client.post('/api/kennels', {}, desk1))).data.errorCode).toBe('VALIDATION_FAILED');
    expect((await failure(client.post('/api/kennels', { kennelTypeId: 5, code: 'C-08' }, desk1))).data.errorCode).toBe('ACCESS_DENIED');
    for (const email of [ACCOUNTS.vet, ACCOUNTS.caretaker, ACCOUNTS.chain]) {
      expect((await failure(client.post('/api/kennels', { kennelTypeId: 5, code: 'C-08' }, await as(email)))).status).toBe(403);
    }
  });

  it('loại chuồng không có, hoặc không thuộc nhóm lưu trú → 404 loại chuồng (hợp đồng nói 400, BE thật trả 404)', async () => {
    const missing = await failure(create({ kennelTypeId: 9999, code: 'C-09' }));
    expect(missing.data).toMatchObject({ errorCode: 'RESOURCE_NOT_FOUND', message: 'Không tìm thấy loại chuồng #9999' });
    const notBoarding = await failure(create({ kennelTypeId: 1, code: 'C-09' }));
    expect(notBoarding.data.message).toBe('Không tìm thấy loại chuồng #1');
  });

  it('loại chuồng chưa bật tại chi nhánh → 400 BR-LT-01', async () => {
    const res = await failure(create({ kennelTypeId: 6, code: 'M-01' }));
    expect(res.data.message).toBe('Loại chuồng không phải dịch vụ lưu trú đang bật tại chi nhánh (BR-LT-01)');
    expect(ruleIdOf(res.data.message)).toBe('BR-LT-01');
  });

  it('mã trùng trong chi nhánh (sau khi bỏ khoảng trắng, phân biệt hoa thường) → 400 BR-LT-01', async () => {
    const dup = await failure(create({ kennelTypeId: 5, code: ' C-01 ' }));
    expect(dup.data.message).toBe('Mã chuồng đã tồn tại trong chi nhánh (BR-LT-01)');
    expect((await create({ kennelTypeId: 5, code: 'c-01' })).status).toBe(201);
  });

  it('thứ tự: 404 loại chuồng trước BR-LT-01 loại chưa bật, trước mã trùng', async () => {
    expect((await failure(create({ kennelTypeId: 9999, code: 'C-01' }))).status).toBe(404);
    expect((await failure(create({ kennelTypeId: 6, code: 'C-01' }))).data.message).toContain('đang bật tại chi nhánh');
  });
});

describe('PATCH /kennels/:id (#3)', () => {
  const patch = async (id: number, body: object, auth?: object) => client.patch(`/api/kennels/${id}`, body, auth ?? (await manager()));

  it('đổi mã và ghi chú; vắng hoặc null thì giữ; chuỗi trống / toàn khoảng trắng xóa ghi chú', async () => {
    const res = await patch(3, { code: ' C-33 ', note: ' Cửa phải ' });
    expect(res.data).toMatchObject({ message: 'Đã cập nhật chuồng' });
    expect(res.data.data).toMatchObject({ kennelId: 3, code: 'C-33', note: 'Cửa phải' });
    expect((await patch(3, {})).data.data).toMatchObject({ code: 'C-33', note: 'Cửa phải' });
    expect((await patch(3, { code: null, note: null })).data.data).toMatchObject({ code: 'C-33', note: 'Cửa phải' });
    expect((await patch(3, { note: '' })).data.data.note).toBeNull();
    await patch(3, { note: 'Có ghi chú' });
    expect((await patch(3, { note: '    ' })).data.data.note).toBeNull();
  });

  it('đặt lại đúng mã của chính nó thì không báo trùng; trùng mã chuồng khác → BR-LT-01', async () => {
    expect((await patch(3, { code: 'C-03' })).status).toBe(200);
    const dup = await failure(patch(3, { code: 'C-01' }));
    expect(dup.data.message).toBe('Mã chuồng đã tồn tại trong chi nhánh (BR-LT-01)');
  });

  it('kiểm dữ liệu vào', async () => {
    expect((await failure(patch(3, { code: '   ' }))).data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'code: Mã chuồng không được để trống' });
    expect((await failure(patch(3, { code: 'x'.repeat(21) }))).data.message).toBe('code: Mã chuồng tối đa 20 ký tự');
    expect((await failure(patch(3, { note: 'n'.repeat(301) }))).data.message).toBe('note: Ghi chú tối đa 300 ký tự');
    expect((await failure(patch(3, { code: 5 }))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.patch('/api/kennels/abc', {}, await manager()))).data.message).toBe("Tham số 'kennelId' sai kiểu dữ liệu");
  });

  it('403 vai trò (kể cả lễ tân), 404 chuồng, 403 phạm vi chi nhánh khác sau 404', async () => {
    expect((await failure(patch(3, { note: 'x' }, await desk()))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(patch(999, { note: 'x' }))).data).toMatchObject({ errorCode: 'RESOURCE_NOT_FOUND', message: 'Không tìm thấy chuồng #999' });
    const other = await failure(patch(3, { note: 'x' }, await as(ACCOUNTS.manager2)));
    expect(other.data).toMatchObject({ errorCode: 'ACCESS_DENIED_SCOPE_MISMATCH', message: 'Không có quyền truy cập dữ liệu này' });
  });
});

describe('DELETE /kennels/:id (#4)', () => {
  const remove = async (id: number, auth?: object) => client.delete(`/api/kennels/${id}`, auth ?? (await manager()));

  it('chuồng chưa từng có thú: 204 và biến mất khỏi danh sách', async () => {
    expect((await remove(4)).status).toBe(204);
    expect(codes(await kennels())).not.toContain('C-04');
  });

  it('chuồng đang có thú → 409 "Chuồng đang có thú, không xóa được" (kiểm trước lịch sử)', async () => {
    const res = await failure(remove(1));
    expect(res.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409, message: 'Chuồng đang có thú, không xóa được' });
  });

  it('chuồng đã có lịch sử lưu trú → 409, khuyên chuyển bảo trì', async () => {
    const res = await failure(remove(3));
    expect(res.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', message: 'Chuồng đã có lịch sử lưu trú — chuyển bảo trì thay vì xóa' });
  });

  it('404 → 403 phạm vi; 403 vai trò', async () => {
    expect((await failure(remove(999))).data.message).toBe('Không tìm thấy chuồng #999');
    expect((await failure(remove(4, await as(ACCOUNTS.manager2)))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await failure(remove(4, await desk()))).data.errorCode).toBe('ACCESS_DENIED');
  });
});

describe('start-maintenance / end-maintenance (#5, #6)', () => {
  const start = async (id: number, auth?: object) => client.post(`/api/kennels/${id}/start-maintenance`, undefined, auth ?? (await manager()));
  const end = async (id: number, auth?: object) => client.post(`/api/kennels/${id}/end-maintenance`, undefined, auth ?? (await manager()));

  it('chuyển bảo trì kèm cảnh báo đặt chỗ BOOKED thiếu chỗ, sắp theo (ngày nhận, id), không tự hủy gì', async () => {
    const res = await start(3);
    expect(res.data).toMatchObject({ message: 'Đã chuyển chuồng sang bảo trì' });
    expect(res.data.data.kennel).toMatchObject({ kennelId: 3, status: 'MAINTENANCE' });
    // Sức chứa còn 2 (C-01, C-02): đêm 26/10 và 27/10 có Cốm + Mực + Bim, đêm 30/10 có Cốm + Mực + Bơ.
    expect(res.data.data.shortageBookings).toEqual([
      { bookingId: 4, code: expect.stringMatching(/^LT-2610-\d{5}$/), checkInDate: '2026-10-26', checkOutDate: '2026-10-28', petName: 'Bim' },
      { bookingId: 1, code: expect.any(String), checkInDate: '2026-10-30', checkOutDate: '2026-11-01', petName: 'Bơ' },
    ]);
    const bookings = (await client.get('/api/boarding-bookings', { params: { status: 'BOOKED' }, ...(await desk()) })).data.data;
    expect(bookings.totalElements).toBe(2);
  });

  it('đủ chỗ thì không cảnh báo: số chiếm = sức chứa mới chưa phải thiếu', async () => {
    await end(4); // sức chứa 4
    const res = await start(3); // sức chứa 3, đêm kín nhất có 3 đặt chỗ
    expect(res.data.data.shortageBookings).toEqual([]);
  });

  it('chuồng không AVAILABLE không chuyển được bảo trì → 409 FSM', async () => {
    const occupied = await failure(start(1));
    expect(occupied.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', message: 'Không thể chuyển trạng thái chuồng từ OCCUPIED sang MAINTENANCE' });
    const again = await failure(start(4));
    expect(again.data.message).toBe('Không thể chuyển trạng thái chuồng từ MAINTENANCE sang MAINTENANCE');
  });

  it('hết bảo trì → AVAILABLE; chuồng đang AVAILABLE → 409', async () => {
    const res = await end(4);
    expect(res.data).toMatchObject({ message: 'Đã hết bảo trì' });
    expect(res.data.data).toMatchObject({ kennelId: 4, status: 'AVAILABLE' });
    const again = await failure(end(4));
    expect(again.data.message).toBe('Không thể chuyển trạng thái chuồng từ AVAILABLE sang AVAILABLE');
  });

  it('chuồng OCCUPIED hết bảo trì KHÔNG bị 409 mà về AVAILABLE (BE lệch hợp đồng: FSM cho phép OCCUPIED → AVAILABLE)', async () => {
    const res = await end(1);
    expect(res.status).toBe(200);
    expect(res.data.data.status).toBe('AVAILABLE');
  });

  it('404 → 403 phạm vi → vai trò', async () => {
    expect((await failure(start(999))).data.message).toBe('Không tìm thấy chuồng #999');
    expect((await failure(start(3, await as(ACCOUNTS.manager2)))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await failure(end(4, await desk()))).data.errorCode).toBe('ACCESS_DENIED');
  });
});

describe('GET /branches/:id/boarding-availability (#7)', () => {
  const query = (checkInDate: string, checkOutDate: string, kennelTypeId: number | string = 5) => ({ kennelTypeId, checkInDate, checkOutDate });
  const availability = async (params: object, auth?: object, branchId = 1) =>
    client.get(`/api/branches/${branchId}/boarding-availability`, { params, ...(auth ?? (await as(ACCOUNTS.customer))) });

  it('số chỗ còn lại theo đêm: sức chứa − số đặt chỗ chiếm chỗ; đêm = ngày nhận … ngày trả − 1', async () => {
    const res = await availability(query('2026-10-25', '2026-10-28'));
    expect(res.data).toMatchObject({ message: 'Số chỗ còn lại theo đêm' });
    expect(res.data.data).toEqual({
      kennelTypeId: 5,
      nightlyPrice: 200000,
      available: false,
      nights: [
        { date: '2026-10-25', capacity: 3, occupied: 2, remaining: 1 },
        { date: '2026-10-26', capacity: 3, occupied: 3, remaining: 0 },
        { date: '2026-10-27', capacity: 3, occupied: 3, remaining: 0 },
      ],
    });
  });

  it('còn chỗ mọi đêm → available; chuồng bảo trì không tính vào sức chứa', async () => {
    const free = await availability(query('2026-10-24', '2026-10-26'));
    expect(free.data.data.available).toBe(true);
    expect(free.data.data.nights.map((n: { remaining: number }) => n.remaining)).toEqual([1, 1]);
    await client.post('/api/kennels/4/end-maintenance', undefined, await manager());
    const more = await availability(query('2026-10-24', '2026-10-26'));
    expect(more.data.data.nights[0]).toMatchObject({ capacity: 4, occupied: 2, remaining: 2 });
  });

  it('remaining không âm khi chuồng bị chuyển bảo trì làm số chiếm vượt sức chứa', async () => {
    await client.post('/api/kennels/3/start-maintenance', undefined, await manager());
    const res = await availability(query('2026-10-26', '2026-10-27'));
    expect(res.data.data.nights[0]).toEqual({ date: '2026-10-26', capacity: 2, occupied: 3, remaining: 0 });
  });

  it('đặt chỗ NO_SHOW / CANCELLED / CHECKED_OUT không chiếm chỗ; CHECKED_IN chiếm các đêm tới trước ngày trả', async () => {
    // Cốm trả 31/10: đêm 30/10 còn chiếm, đêm 31/10 thì không (Mực trả 01/11, Bơ 30/10→01/11): chiếm 3, 2, 0.
    const res = await availability(query('2026-10-30', '2026-11-02'));
    expect(res.data.data.nights.map((n: { occupied: number }) => n.occupied)).toEqual([3, 2, 0]);
    // Đêm 01/11: Bim bị hủy (01/11→03/11) không chiếm chỗ.
    expect(res.data.data.nights[2]).toMatchObject({ date: '2026-11-01', occupied: 0, remaining: 3 });
  });

  it('OVERDUE chiếm chỗ từ hôm nay tới hôm nay + 2 đêm [CFG], không hơn', async () => {
    await kennels(); // nạp dữ liệu mẫu ở 23/10 trước khi đồng hồ chạy tiếp
    vi.setSystemTime(new Date('2026-10-31T13:00:00+07:00')); // Cốm quá 12:00 ngày trả → OVERDUE
    const res = await availability(query('2026-11-01', '2026-11-04'));
    expect(res.data.data.nights.map((n: { date: string; occupied: number }) => [n.date, n.occupied])).toEqual([
      ['2026-11-01', 1],
      ['2026-11-02', 1],
      ['2026-11-03', 0],
    ]);
    const booking = (await client.get('/api/boarding-bookings/2', await desk())).data.data;
    expect(booking.status).toBe('OVERDUE');
  });

  it('giới hạn số đêm 1–30: biên 30 qua, 0 / 31 / ngày trả không sau ngày nhận bị BR-LT-02', async () => {
    expect((await availability(query('2026-11-20', '2026-12-20'))).data.data.nights).toHaveLength(30);
    for (const [from, to] of [
      ['2026-11-20', '2026-12-21'],
      ['2026-11-20', '2026-11-20'],
      ['2026-11-21', '2026-11-20'],
    ]) {
      const res = await failure(availability(query(from, to)));
      expect(res.data).toMatchObject({
        errorCode: 'BUSINESS_RULE_VIOLATION',
        message: 'Thời gian lưu trú phải từ 1 đến 30 đêm và ngày trả phải sau ngày nhận (BR-LT-02)',
      });
    }
  });

  it('thiếu hoặc sai kiểu tham số → 400 MALFORMED_REQUEST theo thứ tự tham số, trước cả vai trò', async () => {
    const vet = await as(ACCOUNTS.vet);
    expect((await failure(availability({}, vet))).data.message).toBe("Thiếu tham số 'kennelTypeId'");
    expect((await failure(availability({ kennelTypeId: 5 }, vet))).data.message).toBe("Thiếu tham số 'checkInDate'");
    expect((await failure(availability({ kennelTypeId: 5, checkInDate: '2026-10-25' }, vet))).data.message).toBe("Thiếu tham số 'checkOutDate'");
    expect((await failure(availability(query('2026-10-25', '2026-10-28', 'abc'), vet))).data.message).toBe("Tham số 'kennelTypeId' sai kiểu dữ liệu");
    expect((await failure(availability(query('25/10/2026', '2026-10-28'), vet))).data.message).toBe("Tham số 'checkInDate' sai kiểu dữ liệu");
    expect((await failure(availability(query('2026-02-30', '2026-10-28'), vet))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(client.get('/api/branches/x/boarding-availability', { params: query('2026-10-25', '2026-10-28'), ...vet }))).data.message).toBe(
      "Tham số 'branchId' sai kiểu dữ liệu",
    );
  });

  it('chỉ khách và lễ tân; lễ tân chi nhánh khác → 403 phạm vi; khách + chi nhánh không ACTIVE → 404', async () => {
    for (const email of [ACCOUNTS.vet, ACCOUNTS.manager, ACCOUNTS.caretaker, ACCOUNTS.admin]) {
      expect((await failure(availability(query('2026-10-25', '2026-10-28'), await as(email)))).data.errorCode).toBe('ACCESS_DENIED');
    }
    expect((await availability(query('2026-10-25', '2026-10-28'), await desk())).status).toBe(200);
    const scope = await failure(availability(query('2026-10-25', '2026-10-28'), await desk(), 2));
    expect(scope.data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    const draft = await failure(availability(query('2026-10-25', '2026-10-28'), undefined, 2));
    expect(draft.data).toMatchObject({ errorCode: 'RESOURCE_NOT_FOUND', message: 'Không tìm thấy chi nhánh #2' });
    expect((await failure(availability(query('2026-10-25', '2026-10-28'), undefined, 999))).data.message).toBe('Không tìm thấy chi nhánh #999');
  });

  it('loại chuồng không có → 404; loại chưa bật tại chi nhánh → 400 BR-LT-02, kiểm sau số đêm', async () => {
    expect((await failure(availability(query('2026-10-25', '2026-10-28', 9999)))).data.message).toBe('Không tìm thấy loại chuồng #9999');
    expect((await failure(availability(query('2026-10-25', '2026-10-28', 1)))).data.message).toBe('Không tìm thấy loại chuồng #1');
    const off = await failure(availability(query('2026-10-25', '2026-10-28', 6)));
    expect(off.data.message).toBe('Loại chuồng không đặt được tại chi nhánh này (BR-LT-02)');
    const nightsFirst = await failure(availability(query('2026-10-25', '2026-10-25', 6)));
    expect(nightsFirst.data.message).toContain('Thời gian lưu trú phải từ 1 đến 30 đêm');
  });

  it('giá đêm là giá hiện hành của loại chuồng', async () => {
    await client.patch('/api/services/5', { price: 250000 }, await as(ACCOUNTS.chain));
    expect((await availability(query('2026-10-25', '2026-10-26'))).data.data.nightlyPrice).toBe(250000);
  });
});

describe('state lưu trong localStorage', () => {
  it('state hình dạng cũ (thiếu trường) được gộp mặc định, route đầu tiên không throw', async () => {
    localStorage.setItem(
      'petcare-mock-boarding',
      JSON.stringify({
        kennels: [{ kennelId: 1, branchId: 1, kennelTypeId: 5, code: 'C-01', note: null, status: 'AVAILABLE' }],
        bookings: [],
      }),
    );
    const list = await client.get('/api/kennels', await desk());
    expect(list.data.data).toEqual([expect.objectContaining({ kennelId: 1, code: 'C-01', status: 'AVAILABLE', currentBookingId: null })]);
    const bookings = await client.get('/api/boarding-bookings', await desk());
    expect(bookings.data.data.content).toEqual([]);
    const logs = await failure(client.get('/api/boarding-bookings/1/care-logs', await desk()));
    expect(logs.status).toBe(404);
    const created = await client.post('/api/kennels', { kennelTypeId: 5, code: 'C-09' }, await manager());
    expect(created.data.data.kennelId).toBeGreaterThan(1);
  });

  it('state hỏng (không phải JSON) → dùng lại dữ liệu mẫu', async () => {
    localStorage.setItem('petcare-mock-boarding', '{not json');
    expect(codes(await kennels())).toEqual(['C-01', 'C-02', 'C-03', 'C-04']);
  });

  it('ghi ngay lần nạp đầu: đọc lại ra cùng dữ liệu mẫu dù đồng hồ đã chạy tiếp', async () => {
    await kennels();
    const stored = JSON.parse(localStorage.getItem('petcare-mock-boarding') ?? 'null');
    expect(stored.bookings).toHaveLength(11);
    vi.setSystemTime(new Date('2026-10-24T09:00:00+07:00'));
    const b = (await client.get('/api/boarding-bookings/4', await desk())).data.data;
    expect(b.checkInDate).toBe('2026-10-26');
  });
});
