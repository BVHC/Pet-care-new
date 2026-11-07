// Mock module customer, phần hồ sơ khách của UC06 (docs/api/customer-v1.md #1–7): hồ sơ khách của tôi, sổ địa chỉ.
// Kèm các hàm identity.mock gọi như CustomerApi / CustomerQueryApi của BE (đăng ký, GET /me, liên kết UC07).
// Câu báo lỗi và thứ tự kiểm chép từ BE (MyCustomerProfileService, AddressBookService).
import type { Address, CustomerProfile } from '../../types/profile';
import {
  HTTPS_URL,
  blank,
  denied,
  has,
  invalid,
  noContent,
  notFound,
  ok,
  optional,
  ruleFail,
  tooLong,
  type Check,
  type MockHandler,
  type MockRequest,
} from './envelope';

/** Mặc định của [CFG] `address.max_per_customer` (BR-TK-18). */
const MAX_ADDRESSES = 5;

export interface MockCustomer {
  customerId: number;
  accountId: number | null;
  fullName: string;
  phone: string | null;
  /** Email ghi trong hồ sơ (hồ sơ tại quầy: nơi nhận mã liên kết). */
  email: string | null;
  avatarUrl: string | null;
  createdChannel: 'ONLINE' | 'COUNTER';
  linkDecisionPending: boolean;
  /** Hồ sơ online đã có thú cưng, lịch hẹn, đặt chỗ, Order hoặc feedback: không liên kết được (BR-TK-19). */
  hasData: boolean;
  /**
   * UC27 (custmerge-v1, BR-KH-12): hồ sơ này đã gộp vào hồ sơ có id này, tức `status = MERGED`. Vắng hoặc null = còn dùng. Hồ sơ MERGED vẫn nằm trong state
   * (để audit, `findCustomer` vẫn thấy) nhưng không liên kết được (`linkCandidates`, `linkAccount`) và customer-pet.mock ẩn khỏi tra cứu.
   */
  mergedIntoId?: number | null;
}

interface MockAddress extends Address {
  customerId: number;
}

interface State {
  customers: MockCustomer[];
  addresses: MockAddress[];
  nextAddressId: number;
}

function seed(): State {
  const customer = (c: Partial<MockCustomer> & Pick<MockCustomer, 'customerId' | 'fullName' | 'phone'>): MockCustomer => ({
    accountId: null,
    email: null,
    avatarUrl: null,
    createdChannel: 'COUNTER',
    linkDecisionPending: false,
    hasData: false,
    ...c,
  });
  const address = (a: Omit<MockAddress, 'ward'> & { ward?: string }): MockAddress => ({ ward: null, ...a });
  return {
    customers: [
      // khach@petcare.vn: chủ của Mochi trong mock chi nhánh.
      customer({ customerId: 1, accountId: 7, fullName: 'Nguyễn Thu Trang', phone: '0912345678', createdChannel: 'ONLINE', hasData: true }),
      // khachmoi@petcare.vn: SĐT trùng hai hồ sơ tại quầy chưa liên kết → chờ quyết định liên kết.
      customer({ customerId: 2, accountId: 10, fullName: 'Lê Hoàng Long', phone: '0905111222', createdChannel: 'ONLINE', linkDecisionPending: true }),
      customer({ customerId: 3, fullName: 'Lê Hoàng Long', phone: '0905111222', email: 'long.lh@gmail.com' }),
      customer({ customerId: 4, fullName: 'Lê Thị Hoa', phone: '0905111222' }),
    ],
    addresses: [
      address({ addressId: 1, customerId: 1, receiverName: 'Nguyễn Thu Trang', receiverPhone: '0912345678', addressLine: '12 Trần Thái Tông', ward: 'Dịch Vọng Hậu', province: 'Hà Nội', isDefault: true }),
      address({ addressId: 2, customerId: 1, receiverName: 'Nguyễn Văn Bình', receiverPhone: '0987000111', addressLine: '45 Láng Hạ', ward: 'Láng Hạ', province: 'Hà Nội', isDefault: false }),
      address({ addressId: 3, customerId: 2, receiverName: 'Lê Hoàng Long', receiverPhone: '0905111222', addressLine: '8 Nguyễn Chí Thanh', ward: 'Láng Thượng', province: 'Hà Nội', isDefault: true }),
    ],
    nextAddressId: 4,
  };
}

const STATE_KEY = 'petcare-mock-customer';

function load(): State {
  try {
    const raw = localStorage.getItem(STATE_KEY);
    // Gộp với dữ liệu mẫu: state ghi bởi bản cũ mà thiếu bảng vẫn đọc được (hồ sơ cũ không có `mergedIntoId`: coi như chưa gộp).
    if (raw) return { ...seed(), ...(JSON.parse(raw) as Partial<State>) };
  } catch {
    // Dữ liệu hỏng: dùng lại dữ liệu mẫu.
  }
  return seed();
}

function save(s: State) {
  try {
    localStorage.setItem(STATE_KEY, JSON.stringify(s));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}

// ─── Như CustomerApi / CustomerQueryApi, cho identity.mock ───

/** Hồ sơ khách gắn với tài khoản. */
export const customerOfAccount = (accountId: number) => load().customers.find((c) => c.accountId === accountId);

/** UC01: đăng ký online luôn tạo hồ sơ online (BR-KH-01). */
export function createOnlineProfile(accountId: number, fullName: string, phone: string | null) {
  const s = load();
  const customerId = Math.max(0, ...s.customers.map((c) => c.customerId)) + 1;
  s.customers.push({
    customerId,
    accountId,
    fullName,
    phone,
    email: null,
    avatarUrl: null,
    createdChannel: 'ONLINE',
    linkDecisionPending: false,
    hasData: false,
  });
  save(s);
}

/** Hồ sơ tại quầy chưa liên kết có cùng SĐT, theo thứ tự id. */
export const linkCandidates = (phone: string) =>
  load().customers.filter((c) => c.createdChannel === 'COUNTER' && c.accountId === null && c.phone === phone && c.mergedIntoId == null);

/** Sau khi xác thực email: đặt cờ chờ quyết định nếu SĐT trùng hồ sơ tại quầy chưa liên kết (BR-TK-19). */
export function flagLinkDecisionIfPhoneMatches(accountId: number): boolean {
  const s = load();
  const own = s.customers.find((c) => c.accountId === accountId);
  if (!own?.phone || linkCandidates(own.phone).length === 0) return false;
  own.linkDecisionPending = true;
  save(s);
  return true;
}

/** Lý do tài khoản không liên kết được hồ sơ tại quầy (BR-TK-19), null nếu được. */
export function linkBlocker(accountId: number): 'ALREADY_LINKED' | 'HAS_DATA' | null {
  const own = customerOfAccount(accountId);
  if (!own) return null;
  if (own.createdChannel === 'COUNTER') return 'ALREADY_LINKED';
  return own.hasData ? 'HAS_DATA' : null;
}

/** Gắn tài khoản vào hồ sơ tại quầy; xóa hồ sơ online cùng sổ địa chỉ của nó. Email hồ sơ theo email tài khoản, SĐT giữ nguyên. */
export function linkAccount(accountId: number, counterId: number, accountEmail: string): boolean {
  const s = load();
  const counter = s.customers.find((c) => c.customerId === counterId);
  if (!counter || counter.createdChannel !== 'COUNTER' || counter.accountId !== null || counter.mergedIntoId != null) return false;
  const online = s.customers.find((c) => c.accountId === accountId && c.createdChannel === 'ONLINE');
  if (online) {
    s.customers = s.customers.filter((c) => c !== online);
    s.addresses = s.addresses.filter((a) => a.customerId !== online.customerId);
  }
  counter.accountId = accountId;
  counter.email = accountEmail;
  save(s);
  return true;
}

/** "Không phải tôi": gỡ cờ; false nếu hồ sơ không còn cờ. */
export function declineLink(accountId: number): boolean {
  const s = load();
  const own = s.customers.find((c) => c.accountId === accountId);
  if (!own?.linkDecisionPending) return false;
  own.linkDecisionPending = false;
  save(s);
  return true;
}

export const findCustomer = (customerId: number) => load().customers.find((c) => c.customerId === customerId);

/**
 * Khách chỉ có tên trong dữ liệu mẫu của module khác (đặt chỗ lưu trú mẫu T48: khách tại quầy chưa có hồ sơ): tra theo id cho
 * bàn thu tiền và chi tiết khách, KHÔNG vào danh sách / tìm kiếm và không đổi `findCustomer` (nơi nó là cổng chặn luật khác).
 */
const guestSources: Array<(customerId: number) => MockCustomer | undefined> = [];
export const registerCounterGuests = (source: (customerId: number) => MockCustomer | undefined) => {
  guestSources.push(source);
};
export const findCustomerOrGuest = (customerId: number) =>
  findCustomer(customerId) ?? guestSources.map((source) => source(customerId)).find((c) => c !== undefined);

// ─── Cho customer-pet.mock (UC22, UC23): tra cứu, tạo và sửa hồ sơ tại quầy ───

export const allCustomers = () => load().customers;

/** UC22: hồ sơ tại quầy chưa gắn tài khoản, bắt buộc SĐT (BR-KH-01). */
export function createCounterProfile(fullName: string, phone: string, email: string | null): MockCustomer {
  const s = load();
  const created: MockCustomer = {
    customerId: Math.max(0, ...s.customers.map((c) => c.customerId)) + 1,
    accountId: null,
    fullName,
    phone,
    email,
    avatarUrl: null,
    createdChannel: 'COUNTER',
    linkDecisionPending: false,
    hasData: false,
  };
  s.customers.push(created);
  save(s);
  return created;
}

/** UC22: trường `undefined` giữ nguyên; trả undefined nếu không có hồ sơ. */
export function updateCustomerFields(
  customerId: number,
  patch: Partial<Pick<MockCustomer, 'fullName' | 'phone' | 'email'>>,
): MockCustomer | undefined {
  const s = load();
  const c = s.customers.find((x) => x.customerId === customerId);
  if (!c) return undefined;
  if (patch.fullName !== undefined) c.fullName = patch.fullName;
  if (patch.phone !== undefined) c.phone = patch.phone;
  if (patch.email !== undefined) c.email = patch.email;
  save(s);
  return c;
}

/** Hồ sơ online đã có thú cưng thì không liên kết được với hồ sơ tại quầy nữa (BR-TK-19). */
export function markHasData(customerId: number) {
  const s = load();
  const c = s.customers.find((x) => x.customerId === customerId);
  if (c && !c.hasData) {
    c.hasData = true;
    save(s);
  }
}

// ─── Cho custmerge.mock (UC27, BR-KH-11…14): gộp hồ sơ khách trùng ───

/** Hồ sơ còn dùng (chưa MERGED); hồ sơ đã gộp thì undefined. `findCustomer` vẫn trả cả hồ sơ MERGED. */
export const findLiveCustomer = (customerId: number) => {
  const c = findCustomer(customerId);
  return c && c.mergedIntoId == null ? c : undefined;
};

const addressKey = (a: MockAddress) => [a.addressLine, a.ward ?? '', a.province].map((x) => x.trim().toLowerCase()).join('|');

/** Sổ địa chỉ của hồ sơ nguồn: tổng số, và số địa chỉ trùng (cùng đường, phường, tỉnh) với địa chỉ đã có ở hồ sơ đích — những địa chỉ này bị bỏ khi gộp. */
export function addressMergeStats(sourceId: number, targetId: number): { count: number; duplicates: number } {
  const { addresses } = load();
  const have = new Set(addresses.filter((a) => a.customerId === targetId).map(addressKey));
  const source = addresses.filter((a) => a.customerId === sourceId);
  return { count: source.length, duplicates: source.filter((a) => have.has(addressKey(a))).length };
}

/**
 * Gộp hồ sơ: nguồn thành MERGED (`mergedIntoId` = đích), địa chỉ không trùng chuyển sang đích (địa chỉ trùng bị bỏ), đích đánh dấu đã có dữ liệu (BR-TK-19).
 * Đích giữ địa chỉ mặc định của mình; đích chưa có địa chỉ nào thì địa chỉ mặc định của nguồn lên mặc định. Không áp trần 5 địa chỉ (BR-TK-18 chỉ áp cho khách tự thêm).
 * Đích đang chờ quyết định liên kết (BR-TK-19) mà không còn hồ sơ tại quầy nào cùng SĐT để liên kết thì cờ chờ được gỡ (khỏi kẹt ở "chờ quyết định" với danh sách ứng viên rỗng).
 * Điều kiện BR-KH-11 đã kiểm ở custmerge.mock.ts. Trả số địa chỉ đã chuyển, null nếu thiếu hồ sơ.
 */
export function mergeCustomerRecords(sourceId: number, targetId: number): { addressesMoved: number } | null {
  const s = load();
  const source = s.customers.find((c) => c.customerId === sourceId);
  const target = s.customers.find((c) => c.customerId === targetId);
  if (!source || !target) return null;
  const have = new Set(s.addresses.filter((a) => a.customerId === targetId).map(addressKey));
  const targetHasDefault = s.addresses.some((a) => a.customerId === targetId && a.isDefault);
  const mine = s.addresses.filter((a) => a.customerId === sourceId);
  const keep = mine.filter((a) => !have.has(addressKey(a)));
  s.addresses = s.addresses.filter((a) => !mine.includes(a) || keep.includes(a));
  const promoted = targetHasDefault ? undefined : (keep.find((a) => a.isDefault) ?? keep[0]);
  for (const a of keep) {
    a.customerId = targetId;
    a.isDefault = a === promoted;
  }
  source.mergedIntoId = targetId;
  target.hasData = true;
  const stillLinkable = s.customers.some(
    (c) => c.createdChannel === 'COUNTER' && c.accountId === null && c.mergedIntoId == null && c.customerId !== targetId && c.phone === target.phone,
  );
  if (target.linkDecisionPending && !stillLinkable) target.linkDecisionPending = false;
  save(s);
  return { addressesMoved: keep.length };
}

// ─── Handlers ───

const isCustomer = (req: MockRequest) => req.account?.role === 'CUSTOMER';

function profileOf(c: MockCustomer, accountEmail: string): CustomerProfile {
  return {
    customerId: c.customerId,
    fullName: c.fullName,
    phone: c.phone,
    email: accountEmail,
    avatarUrl: c.avatarUrl,
    createdChannel: c.createdChannel,
    hasAccount: c.accountId !== null,
    linkDecisionPending: c.linkDecisionPending,
  };
}

const getProfile: MockHandler = (req) => {
  if (!isCustomer(req)) return denied();
  return ok(profileOf(customerOfAccount(req.account!.id)!, req.account!.email));
};

const updateProfile: MockHandler = (req) => {
  const b = (req.body ?? {}) as Record<string, unknown>;
  const bad = invalid([
    ['fullName', 'Họ tên tối đa 100 ký tự', tooLong(b.fullName, 100)],
    ['fullName', 'Họ tên không được để trống', blank(b.fullName)],
    ['phone', 'Số điện thoại phải có dạng 0xxxxxxxxx', has(b.phone) && !/^(\s*|0\d{9})$/.test(String(b.phone))],
    ['avatarUrl', 'Đường dẫn ảnh đại diện tối đa 500 ký tự', tooLong(b.avatarUrl, 500)],
    ['avatarUrl', 'Ảnh đại diện phải là đường dẫn https://', has(b.avatarUrl) && !HTTPS_URL.test(String(b.avatarUrl))],
  ]);
  if (bad) return bad;
  if (!isCustomer(req)) return denied();
  if (has(b.email)) return ruleFail('BR-TK-15', 'Không thể tự sửa email; liên hệ lễ tân để được sửa hộ');
  const s = load();
  const c = s.customers.find((x) => x.accountId === req.account!.id)!;
  const phone = has(b.phone) ? optional(b.phone) : c.phone;
  if (c.createdChannel === 'COUNTER' && phone === null) return ruleFail('BR-KH-01', 'Hồ sơ tại quầy bắt buộc có số điện thoại');
  if (has(b.fullName)) c.fullName = (b.fullName as string).trim();
  c.phone = phone;
  if (has(b.avatarUrl)) c.avatarUrl = optional(b.avatarUrl);
  save(s);
  return ok(profileOf(c, req.account!.email), 200, 'Đã cập nhật hồ sơ');
};

const addressOf = ({ customerId: _owner, ...a }: MockAddress): Address => a;

/** Mặc định trước, rồi theo thứ tự thêm (A7). */
const bookOf = (s: State, customerId: number) =>
  s.addresses
    .filter((a) => a.customerId === customerId)
    .sort((x, y) => Number(y.isDefault) - Number(x.isDefault) || x.addressId - y.addressId);

const listAddresses: MockHandler = (req) => {
  if (!isCustomer(req)) return denied();
  const s = load();
  return ok(bookOf(s, customerOfAccount(req.account!.id)!.customerId).map(addressOf));
};

const RECEIVER_PHONE = 'Số điện thoại người nhận phải có dạng 0xxxxxxxxx';

const lengthChecks = (b: Record<string, unknown>): Check[] => [
  ['receiverName', 'Tên người nhận tối đa 100 ký tự', tooLong(b.receiverName, 100)],
  ['receiverPhone', RECEIVER_PHONE, has(b.receiverPhone) && !/^0\d{9}$/.test(String(b.receiverPhone))],
  ['addressLine', 'Địa chỉ tối đa 300 ký tự', tooLong(b.addressLine, 300)],
  ['ward', 'Phường/xã tối đa 100 ký tự', tooLong(b.ward, 100)],
  ['province', 'Tỉnh/thành phố tối đa 100 ký tự', tooLong(b.province, 100)],
];

const addAddress: MockHandler = (req) => {
  const b = (req.body ?? {}) as Record<string, unknown>;
  const bad = invalid([
    ['receiverName', 'Vui lòng nhập tên người nhận', !has(b.receiverName) || blank(b.receiverName)],
    ['receiverPhone', 'Vui lòng nhập số điện thoại người nhận', !has(b.receiverPhone)],
    ['addressLine', 'Vui lòng nhập địa chỉ', !has(b.addressLine) || blank(b.addressLine)],
    ['province', 'Vui lòng nhập tỉnh/thành phố', !has(b.province) || blank(b.province)],
    ...lengthChecks(b),
  ]);
  if (bad) return bad;
  if (!isCustomer(req)) return denied();
  const s = load();
  const customerId = customerOfAccount(req.account!.id)!.customerId;
  const book = bookOf(s, customerId);
  if (book.length >= MAX_ADDRESSES) return ruleFail('BR-TK-18', `Sổ địa chỉ đã đủ ${MAX_ADDRESSES} địa chỉ`);
  const makeDefault = book.length === 0 || b.isDefault === true;
  if (makeDefault) book.forEach((a) => (a.isDefault = false));
  const added: MockAddress = {
    addressId: s.nextAddressId++,
    customerId,
    receiverName: (b.receiverName as string).trim(),
    receiverPhone: b.receiverPhone as string,
    addressLine: (b.addressLine as string).trim(),
    ward: optional(b.ward),
    province: (b.province as string).trim(),
    isDefault: makeDefault,
  };
  s.addresses.push(added);
  save(s);
  return ok(addressOf(added), 201, 'Đã thêm địa chỉ');
};

/** Địa chỉ của chính khách; của người khác cũng 404 như id không tồn tại (A8). */
function ownAddress(s: State, req: MockRequest): MockAddress | undefined {
  const customerId = customerOfAccount(req.account!.id)!.customerId;
  return s.addresses.find((a) => a.addressId === Number(req.params.addressId) && a.customerId === customerId);
}

const updateAddress: MockHandler = (req) => {
  const b = (req.body ?? {}) as Record<string, unknown>;
  const bad = invalid([
    ...lengthChecks(b),
    ['receiverName', 'Tên người nhận không được để trống', blank(b.receiverName)],
    ['addressLine', 'Địa chỉ không được để trống', blank(b.addressLine)],
    ['province', 'Tỉnh/thành phố không được để trống', blank(b.province)],
  ]);
  if (bad) return bad;
  if (!isCustomer(req)) return denied();
  const s = load();
  const a = ownAddress(s, req);
  if (!a) return notFound('Địa chỉ', req.params.addressId);
  if (has(b.receiverName)) a.receiverName = (b.receiverName as string).trim();
  if (has(b.receiverPhone)) a.receiverPhone = b.receiverPhone as string;
  if (has(b.addressLine)) a.addressLine = (b.addressLine as string).trim();
  if (has(b.ward)) a.ward = optional(b.ward);
  if (has(b.province)) a.province = (b.province as string).trim();
  save(s);
  return ok(addressOf(a), 200, 'Đã cập nhật địa chỉ');
};

const deleteAddress: MockHandler = (req) => {
  if (!isCustomer(req)) return denied();
  const s = load();
  const a = ownAddress(s, req);
  if (!a) return notFound('Địa chỉ', req.params.addressId);
  if (a.isDefault && bookOf(s, a.customerId).length > 1) {
    return ruleFail('BR-TK-18', 'Không thể xóa địa chỉ mặc định khi còn địa chỉ khác; hãy đặt địa chỉ khác làm mặc định trước');
  }
  s.addresses = s.addresses.filter((x) => x !== a);
  save(s);
  return noContent();
};

const setDefaultAddress: MockHandler = (req) => {
  if (!isCustomer(req)) return denied();
  const s = load();
  const a = ownAddress(s, req);
  if (!a) return notFound('Địa chỉ', req.params.addressId);
  bookOf(s, a.customerId).forEach((x) => (x.isDefault = x === a));
  save(s);
  return ok(addressOf(a), 200, 'Đã đặt địa chỉ mặc định');
};

export const customerRoutes: Record<string, MockHandler> = {
  'GET /api/me/customer-profile': getProfile,
  'PATCH /api/me/customer-profile': updateProfile,
  'GET /api/me/addresses': listAddresses,
  'POST /api/me/addresses': addAddress,
  'PATCH /api/me/addresses/:addressId': updateAddress,
  'DELETE /api/me/addresses/:addressId': deleteAddress,
  'POST /api/me/addresses/:addressId/set-default': setDefaultAddress,
};
