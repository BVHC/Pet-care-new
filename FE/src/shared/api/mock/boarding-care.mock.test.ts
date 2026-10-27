import type { AxiosInstance } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ACCOUNTS, authAs, failure, makeClient, pinClock } from '../../../test/boarding-harness';
import { recordDoseForMock } from './visit-clinical.mock';

// Nhật ký chăm sóc (#19–22; UC57, UC60) — BE chưa có, mock theo ASSUMPTION A7 của T48 (hợp đồng + BR-LT-11, BR-LT-12).
// Giờ ghim: thứ Sáu 23/10/2026 09:00 (giờ VN). Dữ liệu mẫu: Cốm (#2, CHECKED_IN) có nhật ký #1 (21/10), #2 (22/10, bất thường, có 1 bản bổ sung của VET),
// chưa có nhật ký hôm nay; Mực (#3) có #3 (22/10) và #4 hôm nay (ghi 30 phút trước, còn sửa được); Bim (#5, CHECKED_OUT) có #5–7.

let client: AxiosInstance;

beforeEach(() => {
  pinClock();
  client = makeClient();
});
afterEach(() => vi.useRealTimers());

const as = (email: string) => authAs(client, email);
const caretaker = () => as(ACCOUNTS.caretaker);
const vet = () => as(ACCOUNTS.vet);
const at = (iso: string) => vi.setSystemTime(new Date(iso));
const HTTPS = 'https://images.example.com/cot-1.jpg';

type Log = {
  careLogId: number;
  bookingId: number;
  logDate: string;
  eating: string | null;
  drinking: string | null;
  hygiene: string | null;
  activity: string | null;
  note: string | null;
  photoUrls: string[];
  isAbnormal: boolean;
  recordedBy: number;
  recordedByName: string;
  createdAt: string;
  editableUntil: string;
  addenda: { addendumId: number; content: string; createdBy: number; createdAt: string }[];
};
const logsOf = async (bookingId: number, auth?: object): Promise<Log[]> => (await client.get(`/api/boarding-bookings/${bookingId}/care-logs`, auth ?? (await vet()))).data.data;
const write = async (bookingId: number, extra: object = {}, auth?: object) =>
  client.post(`/api/boarding-bookings/${bookingId}/care-logs`, { photoUrls: [], eating: 'Ăn hết khẩu phần', ...extra }, auth ?? (await caretaker()));
const writeFail = async (bookingId: number, extra: object = {}, auth?: object) => failure(write(bookingId, extra, auth));
const edit = async (careLogId: number, body: object, auth?: object) => client.patch(`/api/care-logs/${careLogId}`, body, auth ?? (await caretaker()));
const editFail = async (careLogId: number, body: object, auth?: object) => failure(edit(careLogId, body, auth));
const addend = async (careLogId: number, content: unknown, auth?: object) => client.post(`/api/care-logs/${careLogId}/addenda`, { content }, auth ?? (await vet()));
const booking = async (id: number): Promise<{ missingCareLogToday: boolean }> => (await client.get(`/api/boarding-bookings/${id}`, await as(ACCOUNTS.desk))).data.data;
const notificationsOf = async (email: string) => (await client.get('/api/me/notifications', { params: { size: 100 }, ...(await as(email)) })).data.data.content as { type: string; title: string; body: string; linkUrl: string | null }[];

describe('GET /boarding-bookings/:id/care-logs (#19)', () => {
  it('mới nhất trước (ngày nhật ký giảm dần, rồi id giảm dần), kèm bản bổ sung, người ghi và hạn sửa', async () => {
    const res = await client.get('/api/boarding-bookings/2/care-logs', await vet());
    expect(res.data).toMatchObject({ message: 'Nhật ký chăm sóc' });
    const logs: Log[] = res.data.data;
    expect(logs.map((l) => l.careLogId)).toEqual([2, 1]);
    expect(logs[0]).toMatchObject({
      careLogId: 2,
      bookingId: 2,
      logDate: '2026-10-22',
      isAbnormal: true,
      recordedBy: 5,
      recordedByName: 'BS. Minh Anh',
      note: expect.stringContaining('Bỏ ăn'),
    });
    expect(logs[0].addenda).toEqual([{ addendumId: 1, content: expect.stringContaining('men tiêu hóa'), createdBy: 5, createdAt: expect.any(String) }]);
    expect(logs[1]).toMatchObject({ careLogId: 1, logDate: '2026-10-21', recordedBy: 6, recordedByName: 'Thu Hà', isAbnormal: false, addenda: [] });
    expect(logs[1].photoUrls.length).toBeGreaterThan(0);
    for (const l of logs) expect(Date.parse(l.editableUntil) - Date.parse(l.createdAt)).toBe(60 * 60_000);
  });

  it('trường trống trả null, danh sách ảnh luôn là mảng', async () => {
    await write(2, { eating: 'Ăn ít', drinking: undefined });
    const latest = (await logsOf(2))[0];
    expect(latest).toMatchObject({ eating: 'Ăn ít', drinking: null, hygiene: null, activity: null, note: null, photoUrls: [] });
  });

  it('khách xem nhật ký của thú mình ngay cả khi đã trả thú; của người khác → 404', async () => {
    const mine = await logsOf(5, await as(ACCOUNTS.customer));
    expect(mine.map((l) => l.careLogId)).toEqual([7, 6, 5]);
    expect((await failure(client.get('/api/boarding-bookings/2/care-logs', await as(ACCOUNTS.customer)))).data.message).toBe('Không tìm thấy đặt chỗ lưu trú #2');
    expect(await logsOf(4, await as(ACCOUNTS.customer))).toEqual([]);
  });

  it('nhân viên xem được nhật ký của chi nhánh mình, chi nhánh khác → 403 phạm vi sau 404; ADMIN / SUPER_MANAGER 403', async () => {
    for (const email of [ACCOUNTS.vet, ACCOUNTS.caretaker, ACCOUNTS.desk, ACCOUNTS.manager]) expect((await logsOf(2, await as(email))).length).toBe(2);
    expect((await failure(client.get('/api/boarding-bookings/2/care-logs', await as(ACCOUNTS.manager2)))).data.errorCode).toBe('ACCESS_DENIED_SCOPE_MISMATCH');
    expect((await failure(client.get('/api/boarding-bookings/999/care-logs', await as(ACCOUNTS.manager2)))).status).toBe(404);
    for (const email of [ACCOUNTS.admin, ACCOUNTS.chain]) expect((await failure(client.get('/api/boarding-bookings/2/care-logs', await as(email)))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(client.get('/api/boarding-bookings/abc/care-logs', await vet()))).data.message).toBe("Tham số 'bookingId' sai kiểu dữ liệu");
  });
});

describe('POST /boarding-bookings/:id/care-logs (#20)', () => {
  it('CARETAKER ghi: ngày mặc định hôm nay (giờ VN), hạn sửa +60 phút, không bất thường, người ghi là người gọi; Cốm hết cờ "chưa có nhật ký hôm nay"', async () => {
    expect((await booking(2)).missingCareLogToday).toBe(true);
    const res = await write(2, { drinking: ' Uống đủ ', hygiene: '', activity: '   ', note: ' Ngoan ', photoUrls: [HTTPS] });
    expect(res.status).toBe(201);
    expect(res.data).toMatchObject({ message: 'Đã ghi nhật ký chăm sóc', code: 201 });
    expect(res.data.data).toMatchObject({
      bookingId: 2,
      logDate: '2026-10-23',
      eating: 'Ăn hết khẩu phần',
      drinking: 'Uống đủ',
      hygiene: null,
      activity: null,
      note: 'Ngoan',
      photoUrls: [HTTPS],
      isAbnormal: false,
      recordedBy: 6,
      recordedByName: 'Thu Hà',
      createdAt: '2026-10-23T02:00:00.000Z',
      editableUntil: '2026-10-23T03:00:00.000Z',
      addenda: [],
    });
    expect((await booking(2)).missingCareLogToday).toBe(false);
    const listed = (await logsOf(2)).map((l) => l.careLogId);
    expect(listed[0]).toBe(res.data.data.careLogId);
  });

  it('VET cũng ghi được; nhiều mục trong ngày được, mục sau đứng trước', async () => {
    const first = await write(2, {}, await vet());
    expect(first.data.data).toMatchObject({ recordedBy: 5, recordedByName: 'BS. Minh Anh' });
    const second = await write(2, { eating: 'Bữa tối ăn ít' });
    expect((await logsOf(2)).slice(0, 2).map((l) => l.careLogId)).toEqual([second.data.data.careLogId, first.data.data.careLogId]);
  });

  it('cờ "chưa có nhật ký hôm nay" chỉ tính nhật ký có ngày = hôm nay (giờ VN)', async () => {
    await write(2, { logDate: '2026-10-22' });
    expect((await booking(2)).missingCareLogToday).toBe(true);
    await write(2, { logDate: '2026-10-23' });
    expect((await booking(2)).missingCareLogToday).toBe(false);
    // Sang ngày mới (00:00 giờ VN) thì lại thiếu.
    at('2026-10-23T23:59:59+07:00');
    expect((await booking(2)).missingCareLogToday).toBe(false);
    at('2026-10-24T00:00:00+07:00');
    expect((await booking(2)).missingCareLogToday).toBe(true);
    const rows = (await client.get('/api/boarding-bookings', { params: { missingCareLogToday: true }, ...(await as(ACCOUNTS.desk)) })).data.data.content.map((b: { bookingId: number }) => b.bookingId);
    expect(rows).toEqual([3, 2]);
  });

  it('ngày nhật ký không ở tương lai (giờ VN): hôm nay và quá khứ qua, ngày mai bị chặn — kiểm trước vai trò', async () => {
    expect((await write(2, { logDate: '2026-10-23' })).status).toBe(201);
    expect((await write(2, { logDate: '2026-10-20' })).status).toBe(201);
    const future = await writeFail(2, { logDate: '2026-10-24' });
    expect(future.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'logDate: Ngày nhật ký không được ở tương lai' });
    at('2026-10-23T23:59:59+07:00');
    expect((await writeFail(2, { logDate: '2026-10-24' })).status).toBe(400);
    expect((await write(2, { logDate: '2026-10-23' })).status).toBe(201);
    at('2026-10-24T00:00:00+07:00');
    expect((await write(2, { logDate: '2026-10-24' })).status).toBe(201);
    const desk = await writeFail(2, { logDate: '2099-01-01' }, await as(ACCOUNTS.desk));
    expect(desk.data.errorCode).toBe('VALIDATION_FAILED');
  });

  it('chữ ≤ 300 ký tự mỗi ô; ảnh: tối đa 5, chỉ https; thiếu danh sách ảnh bị từ chối', async () => {
    for (const field of ['eating', 'drinking', 'hygiene', 'activity']) {
      const res = await writeFail(2, { [field]: 'a'.repeat(301) });
      expect(res.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: `${field}: Tối đa 300 ký tự` });
      expect((await write(2, { [field]: 'a'.repeat(300) })).status).toBe(201);
    }
    expect((await write(2, { note: 'n'.repeat(2000) })).status).toBe(201);
    const six = await writeFail(2, { photoUrls: Array.from({ length: 6 }, (_, i) => `https://img.example.com/${i}.jpg`) });
    expect(six.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'photoUrls: Tối đa 5 ảnh' });
    expect((await write(2, { photoUrls: Array.from({ length: 5 }, (_, i) => `https://img.example.com/${i}.jpg`) })).data.data.photoUrls).toHaveLength(5);
    expect((await writeFail(2, { photoUrls: ['http://img.example.com/a.jpg'] })).data.message).toBe('photoUrls: Ảnh phải là đường dẫn https');
    expect((await writeFail(2, { photoUrls: ['ftp://x/y.jpg', 'https://ok.example.com/a.jpg'] })).data.message).toBe('photoUrls: Ảnh phải là đường dẫn https');
    expect((await writeFail(2, { photoUrls: [5] })).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await writeFail(2, { photoUrls: 'https://a' })).data.errorCode).toBe('MALFORMED_REQUEST');
    const missing = await failure(client.post('/api/boarding-bookings/2/care-logs', { eating: 'x' }, await caretaker()));
    expect(missing.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'photoUrls: Vui lòng gửi danh sách ảnh (có thể để trống)' });
    expect((await writeFail(2, { isAbnormal: 'yes' })).data.errorCode).toBe('MALFORMED_REQUEST');
  });

  it('chỉ ghi khi thú đang lưu trú: BOOKED, CHECKED_OUT, CANCELLED → 409; OVERDUE vẫn ghi được', async () => {
    for (const id of [4, 5, 6]) {
      const res = await writeFail(id);
      expect(res.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409, message: 'Thú cưng không đang lưu trú' });
    }
    await booking(2);
    at('2026-10-31T13:00:00+07:00');
    expect((await client.get('/api/boarding-bookings/2', await as(ACCOUNTS.desk))).data.data.status).toBe('OVERDUE');
    expect((await write(2)).data.data.logDate).toBe('2026-10-31');
  });

  it('chỉ VET và CARETAKER; 404 đặt chỗ', async () => {
    for (const email of [ACCOUNTS.desk, ACCOUNTS.manager, ACCOUNTS.customer, ACCOUNTS.admin]) expect((await writeFail(2, {}, await as(email))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await writeFail(999)).data.message).toBe('Không tìm thấy đặt chỗ lưu trú #999');
  });

  it('đánh dấu bất thường: báo khách (link tới đặt chỗ) và mọi lễ tân chi nhánh (link /admin/boarding); mục bình thường không báo gì', async () => {
    await booking(4);
    recordDoseForMock(2, 2, '2026-10-10');
    await client.post('/api/boarding-bookings/4/check-in', { kennelId: 3, weightKg: 6.2, healthCondition: 'Khỏe', emergencyPhone: '0987000111' }, await as(ACCOUNTS.desk));
    const before = { customer: (await notificationsOf(ACCOUNTS.customer)).length, desk: (await notificationsOf(ACCOUNTS.desk)).length };
    await write(4, { note: 'Bé ăn tốt' });
    expect((await notificationsOf(ACCOUNTS.customer)).length).toBe(before.customer);
    const res = await write(4, { isAbnormal: true, note: 'Bỏ ăn, nôn hai lần' });
    expect(res.data.data.isAbnormal).toBe(true);
    const mine = await notificationsOf(ACCOUNTS.customer);
    expect(mine).toHaveLength(before.customer + 1);
    expect(mine[0]).toMatchObject({ type: 'CARE_LOG_ABNORMAL', title: 'Nhật ký chăm sóc bất thường: Bim', linkUrl: '/boarding-bookings/4' });
    expect(mine[0].body).toContain('Bỏ ăn, nôn hai lần');
    const front = await notificationsOf(ACCOUNTS.desk);
    expect(front).toHaveLength(before.desk + 1);
    expect(front[0]).toMatchObject({ type: 'CARE_LOG_ABNORMAL', title: 'Nhật ký chăm sóc bất thường: Bim', linkUrl: '/admin/boarding' });
    // Thú không có tài khoản khách (Cốm): chỉ lễ tân được báo.
    const deskBefore = (await notificationsOf(ACCOUNTS.desk)).length;
    await write(2, { isAbnormal: true });
    expect((await notificationsOf(ACCOUNTS.desk)).length).toBe(deskBefore + 1);
  });

  it('khách thấy nhật ký ngay khi được ghi', async () => {
    await booking(4);
    recordDoseForMock(2, 2, '2026-10-10');
    await client.post('/api/boarding-bookings/4/check-in', { kennelId: 3, weightKg: 6.2, healthCondition: 'Khỏe', emergencyPhone: '0987000111' }, await as(ACCOUNTS.desk));
    expect(await logsOf(4, await as(ACCOUNTS.customer))).toEqual([]);
    await write(4, { eating: 'Ăn ngon' });
    expect((await logsOf(4, await as(ACCOUNTS.customer))).map((l) => l.eating)).toEqual(['Ăn ngon']);
  });
});

describe('PATCH /care-logs/:id (#21)', () => {
  it('người ghi sửa trong hạn: chỉ đổi trường có gửi; chuỗi trống xóa; ảnh thay cả danh sách; trạng thái bất thường không đổi qua đây', async () => {
    const created = (await write(2, { eating: 'Ăn ít', drinking: 'Uống ít', note: 'Ghi chú', photoUrls: [HTTPS], isAbnormal: true })).data.data;
    const res = await edit(created.careLogId, { eating: ' Ăn hết ', drinking: '', hygiene: 'Sạch', photoUrls: [], isAbnormal: false });
    expect(res.data).toMatchObject({ message: 'Đã cập nhật nhật ký chăm sóc' });
    expect(res.data.data).toMatchObject({ eating: 'Ăn hết', drinking: null, hygiene: 'Sạch', note: 'Ghi chú', photoUrls: [], isAbnormal: true, recordedBy: 6, createdAt: created.createdAt, editableUntil: created.editableUntil });
    const same = await edit(created.careLogId, {});
    expect(same.data.data).toMatchObject({ eating: 'Ăn hết', photoUrls: [] });
    expect((await edit(created.careLogId, { eating: null, photoUrls: null })).data.data).toMatchObject({ eating: 'Ăn hết', photoUrls: [] });
  });

  it('mục mẫu của Mực ghi 30 phút trước còn sửa được tới hết giờ', async () => {
    expect((await edit(4, { note: 'Bổ sung nhận xét' })).data.data.note).toBe('Bổ sung nhận xét');
  });

  it('hạn sửa 60 phút: còn 1 ms thì sửa được, đúng mốc editableUntil thì 409 và chỉ còn bản bổ sung (BR-LT-11)', async () => {
    const created = (await write(2)).data.data; // 09:00 → hết hạn 10:00
    at('2026-10-23T09:59:59.999+07:00');
    expect((await edit(created.careLogId, { eating: 'Sửa lúc 09:59' })).status).toBe(200);
    at('2026-10-23T10:00:00.000+07:00');
    const late = await editFail(created.careLogId, { eating: 'Sửa lúc 10:00' });
    expect(late.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409, message: 'Đã quá thời hạn sửa nhật ký, vui lòng thêm bản bổ sung (BR-LT-11)' });
    expect((await logsOf(2)).find((l) => l.careLogId === created.careLogId)?.eating).toBe('Sửa lúc 09:59');
    expect((await addend(created.careLogId, 'Bổ sung sau giờ')).status).toBe(201);
  });

  it('người khác (kể cả VET) sửa mục của người ghi → 403 ACCESS_DENIED, kiểm trước hết hạn sửa', async () => {
    const created = (await write(2)).data.data;
    const res = await editFail(created.careLogId, { eating: 'x' }, await vet());
    expect(res.data).toMatchObject({ errorCode: 'ACCESS_DENIED', message: 'Không có quyền thực hiện thao tác này' });
    at('2026-10-23T12:00:00+07:00');
    expect((await editFail(created.careLogId, { eating: 'x' }, await vet())).data.errorCode).toBe('ACCESS_DENIED');
    expect((await editFail(created.careLogId, { eating: 'x' })).data.errorCode).toBe('INVALID_STATE_TRANSITION');
  });

  it('kiểm dữ liệu vào (trước vai trò); ảnh tối đa 5 và https; 404; vai trò', async () => {
    expect((await editFail(4, { eating: 'a'.repeat(301) }, await as(ACCOUNTS.desk))).data.message).toBe('eating: Tối đa 300 ký tự');
    expect((await editFail(4, { photoUrls: Array.from({ length: 6 }, (_, i) => `https://i.example.com/${i}.jpg`) })).data.message).toBe('photoUrls: Tối đa 5 ảnh');
    expect((await editFail(4, { photoUrls: ['http://i.example.com/a.jpg'] })).data.message).toBe('photoUrls: Ảnh phải là đường dẫn https');
    expect((await editFail(4, { eating: 5 })).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await editFail(999, { eating: 'x' })).data.message).toBe('Không tìm thấy nhật ký chăm sóc #999');
    for (const email of [ACCOUNTS.desk, ACCOUNTS.manager, ACCOUNTS.customer]) expect((await editFail(4, { eating: 'x' }, await as(email))).data.errorCode).toBe('ACCESS_DENIED');
    expect((await failure(client.patch('/api/care-logs/abc', {}, await caretaker()))).data.message).toBe("Tham số 'careLogId' sai kiểu dữ liệu");
  });
});

describe('POST /care-logs/:id/addenda (#22)', () => {
  it('VET / CARETAKER bổ sung vào mục của người khác, bất cứ lúc nào, kể cả đặt chỗ đã trả thú; bản bổ sung nằm trong danh sách', async () => {
    const res = await addend(1, '  Đã gọi chủ báo bé ăn tốt  ');
    expect(res.data).toMatchObject({ message: 'Đã thêm bản bổ sung', code: 201 });
    expect(res.data.data).toEqual({ addendumId: expect.any(Number), content: 'Đã gọi chủ báo bé ăn tốt', createdBy: 5, createdAt: '2026-10-23T02:00:00.000Z' });
    expect((await addend(5, 'Bổ sung cho lần lưu trú cũ', await caretaker())).data.data.createdBy).toBe(6);
    const log = (await logsOf(2)).find((l) => l.careLogId === 1)!;
    expect(log.addenda.map((a) => a.content)).toEqual(['Đã gọi chủ báo bé ăn tốt']);
    const second = (await addend(1, 'Bổ sung thứ hai')).data.data;
    expect(second.addendumId).toBeGreaterThan(res.data.data.addendumId);
    expect((await logsOf(2)).find((l) => l.careLogId === 1)!.addenda.map((a) => a.content)).toEqual(['Đã gọi chủ báo bé ăn tốt', 'Bổ sung thứ hai']);
    // Khách xem được bản bổ sung của nhật ký thú mình.
    await addend(5, 'Lời nhắn cho chủ');
    const seen = (await logsOf(5, await as(ACCOUNTS.customer))).find((l) => l.careLogId === 5)!;
    expect(seen.addenda.map((a) => a.content)).toContain('Lời nhắn cho chủ');
  });

  it('nội dung bắt buộc, tối đa 2000 ký tự, phải là chuỗi', async () => {
    expect((await failure(addend(1, undefined))).data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'content: Vui lòng nhập nội dung bổ sung' });
    expect((await failure(addend(1, '   '))).data.message).toBe('content: Vui lòng nhập nội dung bổ sung');
    expect((await failure(addend(1, 'a'.repeat(2001)))).data.message).toBe('content: Nội dung tối đa 2000 ký tự');
    expect((await addend(1, 'a'.repeat(2000))).status).toBe(201);
    expect((await failure(addend(1, 5))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect((await failure(addend(1, undefined, await as(ACCOUNTS.desk)))).data.errorCode).toBe('VALIDATION_FAILED');
  });

  it('404 nhật ký; chỉ VET và CARETAKER', async () => {
    expect((await failure(addend(999, 'x'))).data.message).toBe('Không tìm thấy nhật ký chăm sóc #999');
    for (const email of [ACCOUNTS.desk, ACCOUNTS.manager, ACCOUNTS.customer, ACCOUNTS.admin]) expect((await failure(addend(1, 'x', await as(email)))).data.errorCode).toBe('ACCESS_DENIED');
  });
});
