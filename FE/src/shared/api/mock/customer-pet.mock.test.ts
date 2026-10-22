import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it } from 'vitest';
import { ruleIdOf } from '../api-error';
import { MOCK_PASSWORD } from './identity.mock';
import { installMockApi } from './mock-api';

// Dữ liệu mẫu: khach@petcare.vn = hồ sơ #1 (Mochi #1 có bệnh án, Bim #2 có mũi tiêm, Tôm #3 chưa phát sinh gì).
// Ba hồ sơ cùng SĐT 0905111222: #2 online của khachmoi@ (chờ quyết định liên kết), #3 tại quầy
// (Lu #4 đang có lượt khám mở), #4 tại quầy (Miu #5).

let client: AxiosInstance;

beforeEach(() => {
  client = axios.create();
  installMockApi(client);
});

async function as(email: string) {
  const res = await client.post('/api/auth/login', { email, password: MOCK_PASSWORD });
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}
const desk = () => as('reception@store1.vn');
const vet = () => as('doctor@store1.vn');
const owner = () => as('khach@petcare.vn');

async function failure(request: Promise<unknown>): Promise<AxiosResponse> {
  try {
    await request;
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return e.response;
    throw e;
  }
  throw new Error('expected the request to fail');
}
const ruleOf = (res: AxiosResponse) => ruleIdOf(res.data.message);
const ids = (items: { customerId: number }[]) => items.map((c) => c.customerId);

describe('tra cứu khách & thú (UC23)', () => {
  it('một SĐT ra nhiều hồ sơ, email đã che, kèm thú cưng (BR-KH-10)', async () => {
    const res = await client.get('/api/customers', { params: { phone: '0905111222' }, ...(await vet()) });
    const items = res.data.data.content;
    expect(ids(items)).toEqual([2, 3, 4]);
    expect(items[0].maskedEmail).toBe('kh***@petcare.vn'); // hồ sơ online: email của tài khoản
    expect(items[1]).toMatchObject({
      fullName: 'Lê Hoàng Long',
      maskedEmail: 'lo***@gmail.com',
      createdChannel: 'COUNTER',
      hasAccount: false,
      pets: [{ petId: 4, name: 'Lu', species: 'DOG', deceased: false }],
    });
  });

  it('tìm theo họ tên không dấu và theo tên thú', async () => {
    const byName = await client.get('/api/customers', { params: { name: 'hoang long' }, ...(await desk()) });
    expect(ids(byName.data.data.content)).toEqual([2, 3]);
    const byPet = await client.get('/api/customers', { params: { petName: 'mochi' }, ...(await desk()) });
    expect(ids(byPet.data.data.content)).toEqual([1]);
  });

  it('cần ít nhất một điều kiện; khách hàng không tra cứu được', async () => {
    expect((await failure(client.get('/api/customers', await desk()))).data).toMatchObject({ errorCode: 'VALIDATION_FAILED', statusCode: 400 });
    const res = await failure(client.get('/api/customers', { params: { phone: '0905111222' }, ...(await owner()) }));
    expect(res.status).toBe(403);
  });
});

describe('hồ sơ khách tại quầy (UC22)', () => {
  it('SĐT trùng vẫn tạo, trả danh sách nghi trùng và tra cứu được ngay (BR-KH-10)', async () => {
    const res = await client.post('/api/customers', { fullName: 'Trần Văn Nam', phone: '0905111222' }, await desk());
    expect(res.status).toBe(201);
    expect(res.data.data.customer).toMatchObject({ fullName: 'Trần Văn Nam', createdChannel: 'COUNTER', hasAccount: false });
    expect(ids(res.data.data.duplicatePhoneProfiles)).toEqual([2, 3, 4]);

    const found = await client.get('/api/customers', { params: { name: 'tran van nam' }, ...(await desk()) });
    expect(ids(found.data.data.content)).toEqual([res.data.data.customer.customerId]);
  });

  it('thiếu SĐT → 400 theo trường; bác sĩ không được tạo → 403', async () => {
    const missing = await failure(client.post('/api/customers', { fullName: 'Ngô Bảo' }, await desk()));
    expect(missing.data.errorCode).toBe('VALIDATION_FAILED');
    expect(missing.data.message).toContain('phone:');
    const res = await failure(client.post('/api/customers', { fullName: 'Ngô Bảo', phone: '0911000111' }, await vet()));
    expect(res.status).toBe(403);
  });

  it('hồ sơ tại quầy không xóa được SĐT (BR-KH-01); hồ sơ có tài khoản không sửa email ở đây', async () => {
    expect(ruleOf(await failure(client.patch('/api/customers/3', { phone: '' }, await desk())))).toBe('BR-KH-01');
    expect(ruleOf(await failure(client.patch('/api/customers/1', { email: 'moi@petcare.vn' }, await desk())))).toBe('BR-TK-16');
  });

  it('đổi SĐT sang số đã có thì cảnh báo nghi trùng, đổi tên thì không', async () => {
    const phone = await client.patch('/api/customers/1', { phone: '0905111222' }, await desk());
    expect(ids(phone.data.data.duplicatePhoneProfiles)).toEqual([2, 3, 4]);
    const name = await client.patch('/api/customers/1', { fullName: 'Nguyễn Thu Trang' }, await desk());
    expect(name.data.data.duplicatePhoneProfiles).toEqual([]);
  });
});

describe('thú cưng (UC24)', () => {
  it('khách chỉ thấy thú của mình, cân nặng hiện tại là lần đo mới nhất; hồ sơ chờ liên kết bị chặn (BR-TK-19)', async () => {
    const mine = await client.get('/api/me/pets', await owner());
    expect(mine.data.data.map((p: { name: string }) => p.name)).toEqual(['Mochi', 'Bim', 'Tôm']);
    expect(mine.data.data[0].currentWeightKg).toBe(4.2);
    expect((await failure(client.get('/api/pets/4', await owner()))).status).toBe(404);
    expect(ruleOf(await failure(client.get('/api/me/pets', await as('khachmoi@petcare.vn'))))).toBe('BR-TK-19');
  });

  it('trùng tên + loài chỉ cảnh báo (BR-KH-02); ngày sinh ở tương lai → 400', async () => {
    const twin = await client.post('/api/me/pets', { name: 'mochi', species: 'CAT', sex: 'FEMALE' }, await owner());
    expect(twin.status).toBe(201);
    expect(twin.data.data.duplicateNameWarning).toBe(true);
    const otherSpecies = await client.post('/api/me/pets', { name: 'Mochi', species: 'DOG', sex: 'MALE' }, await owner());
    expect(otherSpecies.data.data.duplicateNameWarning).toBe(false);
    const future = await failure(client.post('/api/me/pets', { name: 'Na', species: 'DOG', sex: 'MALE', birthDate: '2999-01-01' }, await owner()));
    expect(future.data.message).toContain('birthDate:');
  });

  it('lễ tân thêm thú cho hồ sơ tại quầy; hồ sơ không có → 404', async () => {
    const res = await client.post('/api/customers/4/pets', { name: 'Miu', species: 'CAT', sex: 'FEMALE' }, await desk());
    expect(res.data.data).toMatchObject({ pet: { customerId: 4, name: 'Miu' }, duplicateNameWarning: true });
    expect((await failure(client.post('/api/customers/999/pets', { name: 'X', species: 'CAT', sex: 'MALE' }, await desk()))).status).toBe(404);
  });

  it('khóa loài khi đã có bệnh án / mũi tiêm (BR-KH-03)', async () => {
    expect(ruleOf(await failure(client.patch('/api/pets/1', { species: 'DOG' }, await owner())))).toBe('BR-KH-03');
    const res = await client.patch('/api/pets/3', { species: 'CAT' }, await owner());
    expect(res.data.data.pet.species).toBe('CAT');
  });

  it('đánh dấu đã mất: phải xác nhận, chặn khi có lượt khám mở, không hoàn tác, sau đó chỉ đọc (BR-KH-05)', async () => {
    const unconfirmed = await failure(client.post('/api/pets/3/mark-deceased', { deceasedOn: '2026-10-01', confirmed: false }, await owner()));
    expect(unconfirmed.data.errorCode).toBe('VALIDATION_FAILED');
    expect(ruleOf(await failure(client.post('/api/pets/4/mark-deceased', { deceasedOn: '2026-10-01', confirmed: true }, await desk())))).toBe('BR-KH-05');

    const res = await client.post('/api/pets/3/mark-deceased', { deceasedOn: '2026-10-01', confirmed: true }, await owner());
    expect(res.data.data).toMatchObject({ deceasedOn: '2026-10-01', deletable: false });
    const again = await failure(client.post('/api/pets/3/mark-deceased', { deceasedOn: '2026-10-01', confirmed: true }, await owner()));
    expect(again.data).toMatchObject({ errorCode: 'INVALID_STATE_TRANSITION', statusCode: 409 });
    expect(ruleOf(await failure(client.patch('/api/pets/3', { name: 'Tôm Tôm' }, await owner())))).toBe('BR-KH-05');
    expect(ruleOf(await failure(client.post('/api/pets/3/weights', { weightKg: 0.2 }, await owner())))).toBe('BR-KH-05');
  });

  it('chỉ xóa được thú chưa phát sinh giao dịch (BR-KH-06)', async () => {
    expect(ruleOf(await failure(client.delete('/api/pets/1', await owner())))).toBe('BR-KH-06');
    expect((await client.delete('/api/pets/3', await owner())).status).toBe(204);
    expect((await failure(client.get('/api/pets/3', await owner()))).status).toBe(404);
  });
});

describe('chuyển chủ (UC26)', () => {
  it('chặn khi chưa xác nhận chủ cũ, cùng chủ, thú đang có việc dở (BR-KH-08) hoặc không phải lễ tân', async () => {
    const unconfirmed = await failure(client.post('/api/pets/2/transfer-owner', { newCustomerId: 4, previousOwnerConfirmed: false }, await desk()));
    expect(unconfirmed.data.errorCode).toBe('VALIDATION_FAILED');
    const sameOwner = await failure(client.post('/api/pets/2/transfer-owner', { newCustomerId: 1, previousOwnerConfirmed: true }, await desk()));
    expect(sameOwner.status).toBe(400);
    expect(ruleOf(await failure(client.post('/api/pets/4/transfer-owner', { newCustomerId: 4, previousOwnerConfirmed: true }, await desk())))).toBe('BR-KH-08');
    expect((await failure(client.post('/api/pets/2/transfer-owner', { newCustomerId: 4, previousOwnerConfirmed: true }, await vet()))).status).toBe(403);
  });

  it('sau khi chuyển: chủ cũ nhận 404, chủ mới có thú, lịch sử tiêm đi theo thú', async () => {
    await client.post('/api/pets/2/transfer-owner', { newCustomerId: 4, previousOwnerConfirmed: true }, await desk());
    expect((await failure(client.get('/api/pets/2', await owner()))).status).toBe(404);
    const detail = await client.get('/api/customers/4', await desk());
    expect(detail.data.data.pets.map((p: { petId: number }) => p.petId)).toContain(2);
    const health = await client.get('/api/pets/2/health-record', await desk());
    expect(health.data.data.vaccinations).toHaveLength(1);
  });
});

describe('cân nặng chủ tự khai (BR-KH-04)', () => {
  it('phải > 0, mới nhất đứng đầu và thành cân nặng hiện tại; nhân viên không dùng endpoint này', async () => {
    expect(ruleOf(await failure(client.post('/api/pets/2/weights', { weightKg: 0 }, await owner())))).toBe('BR-KH-04');
    const added = await client.post('/api/pets/2/weights', { weightKg: 7.1, measuredAt: '2026-10-15T08:00:00+07:00' }, await owner());
    expect(added.data.data).toMatchObject({ weightKg: 7.1, source: 'OWNER' });
    const list = await client.get('/api/pets/2/weights', await owner());
    expect(list.data.data[0].weightKg).toBe(7.1);
    expect((await client.get('/api/pets/2', await owner())).data.data.currentWeightKg).toBe(7.1);
    expect((await failure(client.post('/api/pets/2/weights', { weightKg: 5 }, await desk()))).status).toBe(403);
  });
});

describe('tài khoản của hồ sơ khách tại quầy (UC22, identity-v1 #25–27)', () => {
  const ID = { verificationMethod: 'ID_CARD_IN_PERSON' } as const;

  it('sửa email tài khoản qua OTP gửi tới email mới; sau đó đăng nhập bằng email mới (BR-TK-16)', async () => {
    const sent = await client.post('/api/customers/1/account-email-change', { newEmail: 'trang.moi@gmail.com', ...ID }, await desk());
    expect(sent.data.data.maskedEmail).toBe('tr***@gmail.com');
    expect(ruleOf(await failure(client.post('/api/customers/1/account-email-change', { newEmail: 'trang.moi@gmail.com', ...ID }, await desk())))).toBe('BR-TK-07');
    expect(ruleOf(await failure(client.post('/api/customers/1/account-email-change/confirm', { code: '000000' }, await desk())))).toBe('BR-TK-05');
    expect((await client.post('/api/customers/1/account-email-change/confirm', { code: '123456' }, await desk())).status).toBe(204);

    expect((await client.get('/api/customers/1', await desk())).data.data.customer.email).toBe('trang.moi@gmail.com');
    expect((await client.post('/api/auth/login', { email: 'trang.moi@gmail.com', password: MOCK_PASSWORD })).status).toBe(200);
    expect((await failure(client.post('/api/auth/login', { email: 'khach@petcare.vn', password: MOCK_PASSWORD }))).status).toBe(401);
  });

  it('từ chối: hồ sơ chưa có tài khoản, email đã dùng, thiếu phương thức xác minh, không phải lễ tân', async () => {
    expect(ruleOf(await failure(client.post('/api/customers/3/account-email-change', { newEmail: 'a@b.vn', ...ID }, await desk())))).toBe('BR-TK-16');
    expect(ruleOf(await failure(client.post('/api/customers/1/account-email-change', { newEmail: 'reception@store1.vn', ...ID }, await desk())))).toBe('BR-TK-01');
    const noMethod = await failure(client.post('/api/customers/1/account-email-change', { newEmail: 'a@b.vn' }, await desk()));
    expect(noMethod.data.message).toContain('verificationMethod:');
    expect((await failure(client.post('/api/customers/1/account-email-change', { newEmail: 'a@b.vn', ...ID }, await vet()))).status).toBe(403);
  });

  it('liên kết hồ sơ tại quầy với tài khoản online: hồ sơ online bị xóa, khách thấy thú của hồ sơ tại quầy (BR-TK-19)', async () => {
    const res = await client.post('/api/customers/4/link-account', { accountEmail: 'khachmoi@petcare.vn', ...ID }, await desk());
    expect(res.data.data).toEqual({ customerId: 4 });
    expect((await client.get('/api/customers/4', await desk())).data.data.customer).toMatchObject({ hasAccount: true, email: 'khachmoi@petcare.vn' });
    const byPhone = await client.get('/api/customers', { params: { phone: '0905111222' }, ...(await desk()) });
    expect(ids(byPhone.data.data.content)).toEqual([3, 4]);
    const pets = await client.get('/api/me/pets', await as('khachmoi@petcare.vn'));
    expect(pets.data.data.map((p: { name: string }) => p.name)).toEqual(['Miu']);
    // Hồ sơ đã liên kết thì không liên kết lần nữa.
    expect(ruleOf(await failure(client.post('/api/customers/4/link-account', { accountEmail: 'khachmoi@petcare.vn', ...ID }, await desk())))).toBe('BR-TK-19');
  });

  it('không liên kết: hồ sơ online, tài khoản đã có dữ liệu, email không có tài khoản', async () => {
    expect(ruleOf(await failure(client.post('/api/customers/2/link-account', { accountEmail: 'khachmoi@petcare.vn', ...ID }, await desk())))).toBe('BR-TK-19');
    expect(ruleOf(await failure(client.post('/api/customers/3/link-account', { accountEmail: 'khach@petcare.vn', ...ID }, await desk())))).toBe('BR-TK-19');
    expect((await failure(client.post('/api/customers/3/link-account', { accountEmail: 'chua-co@gmail.com', ...ID }, await desk()))).status).toBe(404);
  });
});

describe('hồ sơ sức khỏe (UC25)', () => {
  it('khách là chủ nhận đủ mục nhưng không có ghi chú / bản bổ sung nội bộ (BR-KH-07)', async () => {
    const res = await client.get('/api/pets/1/health-record', await owner());
    const [latest] = res.data.data.visits;
    expect(latest).toMatchObject({ diagnosis: 'Viêm da dị ứng — tái khám', followUpDate: '2026-10-24', serviceGroup: 'MEDICAL' });
    expect(latest).not.toHaveProperty('internalNote');
    expect(latest.addenda.map((a: { addendumId: number }) => a.addendumId)).toEqual([1]);
    expect(res.data.data.upcomingFollowUpDate).toBe('2026-10-24');
  });

  it('nhân viên thấy ghi chú nội bộ; khách không phải chủ nhận 403', async () => {
    const res = await client.get('/api/pets/1/health-record', await vet());
    expect(res.data.data.visits[0].internalNote).toBeTruthy();
    expect(res.data.data.visits[0].addenda).toHaveLength(2);
    const other = await failure(client.get('/api/pets/4/health-record', await owner()));
    expect(other.data).toMatchObject({ errorCode: 'ACCESS_DENIED_SCOPE_MISMATCH', statusCode: 403 });
  });
});
