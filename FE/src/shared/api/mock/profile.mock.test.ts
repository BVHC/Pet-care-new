import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it } from 'vitest';
import { MOCK_OTP_CODE, MOCK_PASSWORD } from './identity.mock';
import { installMockApi } from './mock-api';

// Dữ liệu mẫu (customer.mock.ts):
// - khach@petcare.vn: hồ sơ online #1, SĐT 0912345678, đã có thú cưng, 2 địa chỉ.
// - khachmoi@petcare.vn: hồ sơ online #2, SĐT 0905111222, đang chờ quyết định liên kết, 1 địa chỉ.
// - Hồ sơ tại quầy cùng SĐT 0905111222: #3 "Lê Hoàng Long" có email, #4 "Lê Thị Hoa" không có email.
let client: AxiosInstance;

beforeEach(() => {
  client = axios.create();
  installMockApi(client);
});

async function as(email: string) {
  const res = await client.post('/api/auth/login', { email, password: MOCK_PASSWORD });
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}
const customer = () => as('khach@petcare.vn');
const pending = () => as('khachmoi@petcare.vn');
const vet = () => as('doctor@store1.vn');
const desk = () => as('reception@store1.vn');

async function failure(request: Promise<unknown>): Promise<AxiosResponse> {
  try {
    await request;
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return e.response;
    throw e;
  }
  throw new Error('expected the request to fail');
}

const rule = (res: AxiosResponse, message: string) =>
  expect(res.data).toMatchObject({ errorCode: 'BUSINESS_RULE_VIOLATION', statusCode: 400, message });

describe('GET /me (UC06)', () => {
  it('returns the staff profile with the branch of the staff member', async () => {
    const res = await client.get('/api/me', await vet());
    expect(res.data.data).toMatchObject({
      staffProfile: { accountId: 5, fullName: 'BS. Minh Anh', branchId: 1, specialty: 'Nội khoa, da liễu thú y' },
      customerId: null,
      linkDecisionPending: false,
    });
  });

  it('returns the customer profile id and the pending link flag (BR-TK-19)', async () => {
    const login = await client.post('/api/auth/login', { email: 'khachmoi@petcare.vn', password: MOCK_PASSWORD });
    expect(login.data.data.linkDecisionPending).toBe(true);

    const res = await client.get('/api/me', await pending());
    expect(res.data.data).toMatchObject({ staffProfile: null, customerId: 2, linkDecisionPending: true });
  });
});

describe('PATCH /me/staff-profile (UC06, BR-TK-15, BR-TK-20)', () => {
  it('keeps fields left out, clears the ones sent empty and strips the rest', async () => {
    const res = await client.patch('/api/me/staff-profile', { fullName: '  BS. Minh Anh  ', specialty: '', bio: ' Khám chó mèo. ' }, await vet());
    expect(res.data).toMatchObject({ message: 'Đã cập nhật hồ sơ' });
    expect(res.data.data).toMatchObject({ fullName: 'BS. Minh Anh', specialty: null, bio: 'Khám chó mèo.', phone: '0900000005' });

    const me = await client.get('/api/me', await vet());
    expect(me.data.data.staffProfile).toMatchObject({ specialty: null, bio: 'Khám chó mèo.' });
  });

  it('checks the input like the BE DTO', async () => {
    const res = await failure(
      client.patch('/api/me/staff-profile', { phone: '012345', avatarUrl: 'http://anh.vn/a.png', fullName: ' ' }, await vet()),
    );
    expect(res.status).toBe(400);
    expect(res.data.errorCode).toBe('VALIDATION_FAILED');
    expect(res.data.message.split('; ').sort()).toEqual([
      'avatarUrl: Ảnh đại diện phải là đường dẫn https://',
      'fullName: Họ tên không được để trống',
      'phone: Số điện thoại phải có dạng 0xxxxxxxxx',
    ]);
  });

  it('refuses to change the email (BR-TK-15)', async () => {
    rule(
      await failure(client.patch('/api/me/staff-profile', { email: 'moi@petcare.vn' }, await vet())),
      'Không thể tự sửa email; liên hệ cấp trên để được sửa hộ (BR-TK-15)',
    );
  });

  it('keeps the public introduction to vets', async () => {
    const res = await failure(client.patch('/api/me/staff-profile', { bio: 'Lễ tân thân thiện' }, await desk()));
    expect(res.data).toMatchObject({ errorCode: 'ACCESS_DENIED_SCOPE_MISMATCH', statusCode: 403, message: 'Không có quyền truy cập dữ liệu này' });
  });

  it('limits the bio to 500 characters (BR-TK-20)', async () => {
    await client.patch('/api/me/staff-profile', { bio: 'a'.repeat(500) }, await vet());
    rule(
      await failure(client.patch('/api/me/staff-profile', { bio: 'a'.repeat(501) }, await vet())),
      'Mô tả ngắn tối đa 500 ký tự (BR-TK-20)',
    );
  });

  it('is for staff only', async () => {
    const res = await failure(client.patch('/api/me/staff-profile', { fullName: 'Khách' }, await customer()));
    expect(res.data).toMatchObject({ errorCode: 'ACCESS_DENIED', statusCode: 403, message: 'Không có quyền thực hiện thao tác này' });
  });
});

describe('/me/customer-profile (UC06)', () => {
  it('shows the account email and lets the customer clear the phone of an online profile', async () => {
    const auth = await customer();
    const before = await client.get('/api/me/customer-profile', auth);
    expect(before.data.data).toEqual({
      customerId: 1,
      fullName: 'Nguyễn Thu Trang',
      phone: '0912345678',
      email: 'khach@petcare.vn',
      avatarUrl: null,
      createdChannel: 'ONLINE',
      hasAccount: true,
      linkDecisionPending: false,
    });

    const res = await client.patch('/api/me/customer-profile', { fullName: ' Nguyễn Thu Trang ', phone: '  ' }, auth);
    expect(res.data).toMatchObject({ message: 'Đã cập nhật hồ sơ', data: { fullName: 'Nguyễn Thu Trang', phone: null } });
  });

  it('checks the input, then the email (BR-TK-15)', async () => {
    const auth = await customer();
    const bad = await failure(client.patch('/api/me/customer-profile', { phone: '091234567', email: 'x@y.vn' }, auth));
    expect(bad.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'phone: Số điện thoại phải có dạng 0xxxxxxxxx' });

    rule(
      await failure(client.patch('/api/me/customer-profile', { email: 'x@y.vn' }, auth)),
      'Không thể tự sửa email; liên hệ lễ tân để được sửa hộ (BR-TK-15)',
    );
  });

  it('is for customers only', async () => {
    const res = await failure(client.get('/api/me/customer-profile', await vet()));
    expect(res.data).toMatchObject({ errorCode: 'ACCESS_DENIED', statusCode: 403 });
  });
});

describe('/me/addresses (BR-TK-18)', () => {
  const address = (n: number) => ({ receiverName: `Người nhận ${n}`, receiverPhone: '0987000111', addressLine: `${n} Kim Mã`, province: 'Hà Nội' });

  it('lists the default first, then in the order added', async () => {
    const res = await client.get('/api/me/addresses', await customer());
    expect(res.data.data.map((a: { addressId: number; isDefault: boolean }) => [a.addressId, a.isDefault])).toEqual([
      [1, true],
      [2, false],
    ]);
  });

  it('stops at 5 addresses', async () => {
    const auth = await customer();
    for (const n of [3, 4, 5]) {
      const res = await client.post('/api/me/addresses', address(n), auth);
      expect(res.status).toBe(201);
      expect(res.data.message).toBe('Đã thêm địa chỉ');
    }
    rule(await failure(client.post('/api/me/addresses', address(6), auth)), 'Sổ địa chỉ đã đủ 5 địa chỉ (BR-TK-18)');
  });

  it('moves the default flag when a new address asks for it', async () => {
    const auth = await customer();
    const added = await client.post('/api/me/addresses', { ...address(3), ward: '  ', isDefault: true }, auth);
    expect(added.data.data).toMatchObject({ ward: null, isDefault: true });

    const list = await client.get('/api/me/addresses', auth);
    expect(list.data.data.filter((a: { isDefault: boolean }) => a.isDefault)).toHaveLength(1);
    expect(list.data.data[0].addressId).toBe(added.data.data.addressId);
  });

  it('checks a new address like the BE DTO', async () => {
    const res = await failure(client.post('/api/me/addresses', { receiverName: ' ', receiverPhone: '', province: 'Hà Nội' }, await customer()));
    expect(res.data.message.split('; ').sort()).toEqual([
      'addressLine: Vui lòng nhập địa chỉ',
      'receiverName: Vui lòng nhập tên người nhận',
      'receiverPhone: Số điện thoại người nhận phải có dạng 0xxxxxxxxx',
    ]);
  });

  it('edits without touching the default flag, and sets the default only through set-default', async () => {
    const auth = await customer();
    const edited = await client.patch('/api/me/addresses/2', { addressLine: '45 Lê Văn Lương', isDefault: true }, auth);
    expect(edited.data).toMatchObject({ message: 'Đã cập nhật địa chỉ', data: { addressLine: '45 Lê Văn Lương', isDefault: false } });

    const res = await client.post('/api/me/addresses/2/set-default', undefined, auth);
    expect(res.data).toMatchObject({ message: 'Đã đặt địa chỉ mặc định', data: { addressId: 2, isDefault: true } });
    const list = await client.get('/api/me/addresses', auth);
    expect(list.data.data.map((a: { addressId: number }) => a.addressId)).toEqual([2, 1]);
  });

  it('keeps the default while other addresses remain, and deletes the last one', async () => {
    const auth = await customer();
    rule(
      await failure(client.delete('/api/me/addresses/1', auth)),
      'Không thể xóa địa chỉ mặc định khi còn địa chỉ khác; hãy đặt địa chỉ khác làm mặc định trước (BR-TK-18)',
    );
    expect((await client.delete('/api/me/addresses/2', auth)).status).toBe(204);
    expect((await client.delete('/api/me/addresses/1', auth)).status).toBe(204);
    expect((await client.get('/api/me/addresses', auth)).data.data).toEqual([]);

    const first = await client.post('/api/me/addresses', { ...address(1), isDefault: false }, auth);
    expect(first.data.data.isDefault).toBe(true);
  });

  it("hides another customer's address behind 404", async () => {
    const res = await failure(client.patch('/api/me/addresses/3', { province: 'Huế' }, await customer()));
    expect(res.data).toMatchObject({ errorCode: 'RESOURCE_NOT_FOUND', statusCode: 404, message: 'Không tìm thấy Địa chỉ #3' });
  });
});

describe('profile link (UC07, BR-TK-19)', () => {
  it('lists the counter profiles sharing the phone of the online profile, names masked', async () => {
    const res = await client.get('/api/me/link-candidates', await pending());
    expect(res.data.data).toEqual([
      { customerId: 3, maskedFullName: 'Lê*** H** L*', hasEmail: true },
      { customerId: 4, maskedFullName: 'Lê*** T** H*', hasEmail: false },
    ]);
  });

  it('searches by another phone and checks its format', async () => {
    const auth = await pending();
    expect((await client.get('/api/me/link-candidates', { ...auth, params: { phone: '0977000000' } })).data.data).toEqual([]);
    const bad = await failure(client.get('/api/me/link-candidates', { ...auth, params: { phone: '12345' } }));
    expect(bad.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'phone: Số điện thoại phải gồm 10–11 chữ số, bắt đầu bằng 0' });
  });

  it('refuses a profile that already has data', async () => {
    rule(
      await failure(client.get('/api/me/link-candidates', await customer())),
      'Hồ sơ của bạn đã có thú cưng, lịch hẹn, đặt chỗ, đơn hàng hoặc góp ý nên không thể liên kết với hồ sơ tại quầy. Vui lòng liên hệ quầy để được hỗ trợ (BR-TK-19)',
    );
  });

  it('sends the code only to a candidate that has an email', async () => {
    const auth = await pending();
    rule(
      await failure(client.post('/api/me/link/otp', { customerId: 4 }, auth)),
      'Hồ sơ tại quầy chưa có email nên không thể liên kết online. Vui lòng ra quầy để được hỗ trợ (BR-TK-19)',
    );
    const stranger = await failure(client.post('/api/me/link/otp', { customerId: 1 }, auth));
    expect(stranger.data).toMatchObject({ errorCode: 'RESOURCE_NOT_FOUND', message: 'Không tìm thấy hồ sơ khách #1' });

    const sent = await client.post('/api/me/link/otp', { customerId: 3 }, auth);
    expect(sent.data).toMatchObject({ message: 'Đã gửi mã xác thực tới email của hồ sơ', data: { maskedEmail: 'lo***@gmail.com' } });
    rule(await failure(client.post('/api/me/link/otp', { customerId: 3 }, auth)), 'Vui lòng chờ 60 giây trước khi gửi lại mã OTP (BR-TK-07)');
  });

  it('attaches the account to the counter profile and drops the online one with its addresses', async () => {
    const auth = await pending();
    await client.post('/api/me/link/otp', { customerId: 3, phone: '0905111222' }, auth);
    rule(await failure(client.post('/api/me/link/confirm', { customerId: 3, code: '000000' }, auth)), 'OTP không đúng hoặc đã hết hạn (BR-TK-05)');

    const res = await client.post('/api/me/link/confirm', { customerId: 3, code: MOCK_OTP_CODE }, auth);
    expect(res.data).toMatchObject({ message: 'Liên kết hồ sơ thành công', data: { customerId: 3 } });

    expect((await client.get('/api/me', auth)).data.data).toMatchObject({ customerId: 3, linkDecisionPending: false });
    expect((await client.get('/api/me/customer-profile', auth)).data.data).toMatchObject({
      customerId: 3,
      fullName: 'Lê Hoàng Long',
      email: 'khachmoi@petcare.vn',
      createdChannel: 'COUNTER',
    });
    expect((await client.get('/api/me/addresses', auth)).data.data).toEqual([]);
    rule(
      await failure(client.get('/api/me/link-candidates', auth)),
      'Tài khoản đã được liên kết với hồ sơ khách hàng tại phòng khám (BR-TK-19)',
    );
    rule(await failure(client.patch('/api/me/customer-profile', { phone: '' }, auth)), 'Hồ sơ tại quầy bắt buộc có số điện thoại (BR-KH-01)');
  });

  it('keeps the online profile on "Không phải tôi", once', async () => {
    const auth = await pending();
    expect((await client.post('/api/me/link/decline', undefined, auth)).status).toBe(204);
    expect((await client.get('/api/me', auth)).data.data.linkDecisionPending).toBe(false);

    const again = await failure(client.post('/api/me/link/decline', undefined, auth));
    expect(again.data).toMatchObject({
      errorCode: 'INVALID_STATE_TRANSITION',
      statusCode: 409,
      message: 'Không thể chuyển trạng thái liên kết hồ sơ từ không còn chờ quyết định sang Không phải tôi',
    });
  });

  it('is for customers only', async () => {
    const res = await failure(client.get('/api/me/link-candidates', await vet()));
    expect(res.data).toMatchObject({ errorCode: 'ACCESS_DENIED', statusCode: 403 });
  });

  it('flags a new account whose phone matches a counter profile (UC01)', async () => {
    await client.post('/api/auth/register', {
      email: 'long.moi@gmail.com',
      password: MOCK_PASSWORD,
      fullName: 'Lê Hoàng Long',
      phone: '0905111222',
      isAdult: true,
      termsAccepted: true,
    });
    const res = await client.post('/api/auth/register/verify', { email: 'long.moi@gmail.com', code: MOCK_OTP_CODE });
    expect(res.data.data.linkDecisionPending).toBe(true);
  });
});
