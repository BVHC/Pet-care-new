// Dry run của seed dev (FE/scripts/seed-dev) trên lớp mock `installMockApi`.
//
// CHỈ chứng minh thứ tự gọi API, role gọi từng bước và dữ liệu gửi đi hợp lệ THEO LUẬT CỦA MOCK. Đây KHÔNG phải BE thật:
// mock chép BE nhưng có chỗ lỏng hơn / chặt hơn (xem README.md mục "Chưa kiểm với BE thật").
//
// Hai chỗ test phải bù cho mock (không sửa file mock nào, không nới luật của mock):
//  1. Mock bắt đầu bằng dữ liệu mẫu (cùng tên danh mục, SKU, dịch vụ… với data.ts) nên test ghi trạng thái RỖNG vào
//     localStorage của 5 mock để giả lập DB trống. Hình dạng state chép từ `interface State` của từng mock.
//  2. `identity.mock.ts` chỉ cho tài khoản trong MOCK_ACCOUNTS (+ khách tự đăng ký) đăng nhập, còn nhân viên tạo lúc chạy
//     (`POST /api/staff` → `extraStaff`) thì không đăng nhập được, không có token, không tính vào `branchIdOf` /
//     `countBranchManagers`. Test chèn một adapter bọc ngoài: SAU KHI mock tự trả 201 cho `POST /api/staff` (mọi kiểm tra
//     BR-QT-01, BR-QT-03, BR-TK-01 của mock vẫn chạy), adapter thêm id / email / role / chi nhánh / họ tên của tài khoản đó
//     vào MOCK_ACCOUNTS. Mọi lần đăng nhập và đổi mật khẩu sau đó vẫn đi qua handler thật của mock.
import axios, { getAdapter, type AxiosAdapter, type AxiosInstance } from 'axios';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { installMockApi } from '../shared/api/mock/mock-api';
import { MOCK_ACCOUNTS, MOCK_PASSWORD, type MockAccount } from '../shared/api/mock/identity.mock';
import { DEV_PASSWORD, SEED } from '../../scripts/seed-dev/data';
import { StubTempPasswordSource, parseTempPassword } from '../../scripts/seed-dev/mailbox';
import { AlreadySeededError, runSeed, type SeedSummary } from '../../scripts/seed-dev/seed';

/** 10:00 thứ Sáu 2026-10-23 giờ VN: ngày làm việc, trong khung 08:00–12:00 của giờ mở cửa. */
const PINNED_NOW = new Date('2026-10-23T03:00:00Z');
const TODAY = '2026-10-23';
/** ADMIN của mock (seed của identity.mock). Trên DB thật ADMIN do create-admin.sql tạo. */
const MOCK_ADMIN = { email: 'admin@petcare.vn', password: MOCK_PASSWORD };

const EMPTY_MOCK_STORES: Record<string, unknown> = {
  'petcare-mock-catalog': { seq: 100, categories: [], products: [], services: [], vaccineTypes: [], protocols: [] },
  'petcare-mock-branch': { seq: 100, branches: [], hours: {}, holidays: [], services: {}, quotaDefaults: {}, slotQuotas: [], appointments: [], boardings: [] },
  'petcare-mock-inventory': { seq: 100, suppliers: [], items: [], lots: [], receipts: [], adjustments: [] },
  'petcare-mock-customer': { customers: [], addresses: [], nextAddressId: 1 },
  'petcare-mock-pets': { nextPetId: 1, nextWeightId: 1, pets: [], weights: [] },
};

/** Thêm tài khoản nhân viên mới tạo vào danh bạ đăng nhập của mock (xem đầu file, mục 2). Trả hàm gỡ ra. */
function installAccountDirectoryShim(client: AxiosInstance): () => void {
  const mock = getAdapter(client.defaults.adapter) as AxiosAdapter;
  const directory = MOCK_ACCOUNTS as MockAccount[];
  const start = directory.length;
  client.defaults.adapter = async (config) => {
    const response = await mock(config);
    const path = new URL(config.url ?? '', 'http://mock').pathname;
    if (config.method?.toLowerCase() === 'post' && path === '/api/staff' && response.status === 201) {
      const staff = response.data.data as { accountId: number; email: string; role: MockAccount['role']; fullName: string; branchId: number | null };
      directory.push({ id: staff.accountId, email: staff.email, role: staff.role, fullName: staff.fullName, branchId: staff.branchId ?? undefined });
    }
    return response;
  };
  return () => directory.splice(start);
}

interface Probe<T> {
  value?: T;
  error?: unknown;
}
async function probe<T>(read: () => Promise<T>): Promise<Probe<T>> {
  try {
    return { value: await read() };
  } catch (error) {
    return { error };
  }
}
/** Đọc kết quả của một lần thăm dò; nếu lần đó lỗi thì ném lỗi gốc để assertion đỏ đúng chỗ. */
function got<T>(p: Probe<T>): T {
  if (p.error !== undefined) throw p.error;
  return p.value as T;
}

interface Session {
  account: { id: number; email: string; role: string; mustChangePassword: boolean };
  get: <T = any>(path: string, params?: Record<string, unknown>) => Promise<T>; // eslint-disable-line @typescript-eslint/no-explicit-any
}

describe('seed dev — dry run trên lớp mock (KHÔNG phải BE thật)', () => {
  const client = axios.create();
  const logs: string[] = [];
  let removeShim = () => {};

  async function login(email: string, password: string): Promise<Session> {
    const res = await client.post('/api/auth/login', { email, password });
    const { accessToken, account } = res.data.data;
    return {
      account,
      get: async (path, params) => (await client.get(path, { params, headers: { Authorization: `Bearer ${accessToken}` } })).data.data,
    };
  }

  const world = {
    run: {} as Probe<SeedSummary>,
    rerun: {} as Probe<unknown>,
    tempPasswordRejected: {} as Probe<unknown>,
    accounts: {} as Probe<{ email: string; role: string; mustChangePassword: boolean; branchId: number | null }[]>,
    branch: {} as Probe<{
      list: { branchId: number; name: string; status: string; acceptsAfterHoursEmergency: boolean; activatedAt: string | null }[];
      hours: { effectiveFrom: string; days: { dayOfWeek: number; ranges: { open: string; close: string }[] }[] }[];
      services: { serviceId: number; name: string; group: string; enabled: boolean }[];
      quotaDefaults: { serviceGroup: string; defaultQuota: number | null }[];
      publicBranches: { branchId: number; weeklyHours: unknown[] }[];
    }>,
    catalog: {} as Probe<{
      categories: { name: string }[];
      vaccineTypes: { vaccineTypeId: number; name: string; species: string }[];
      products: { sku: string; productType: string; isPrescription: boolean; tracksExpiry: boolean; vaccineTypeId: number | null; isActive: boolean }[];
      services: { name: string; group: string; medicalType: string | null; kennelType: { species: string; maxWeightKg: number } | null }[];
      protocols: { species: string; vaccineTypeId: number; doseNumber: number }[];
    }>,
    inventory: {} as Probe<{
      suppliers: { name: string }[];
      receipts: { content: { receiptId: number; status: string; lineCount: number }[] };
      receipt: { status: string; receiptDate: string; lines: { stockLotId: number | null }[] };
      items: { productId: number; sku: string; totalQuantity: number; availableQuantity: number; expiredQuantity: number; nearestExpiryDate: string | null }[];
      lots: Record<string, { lotNumber: string | null; expiryDate: string | null; quantity: number; expired: boolean }[]>;
    }>,
    customers: {} as Probe<{ fullName: string; phone: string; pets: { name: string; species: string }[] }[]>,
  };

  beforeAll(async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(PINNED_NOW);
    localStorage.clear();
    for (const [key, state] of Object.entries(EMPTY_MOCK_STORES)) localStorage.setItem(key, JSON.stringify(state));
    installMockApi(client);
    removeShim = installAccountDirectoryShim(client);

    const options = () => ({
      client,
      mailbox: new StubTempPasswordSource(MOCK_PASSWORD),
      log: (line: string) => void logs.push(line),
      now: new Date(),
      adminEmail: MOCK_ADMIN.email,
      adminPassword: MOCK_ADMIN.password,
    });
    world.run = await probe(() => runSeed(options()));
    world.rerun = await probe(() => runSeed(options()));

    const smEmail = SEED.superManager.email;
    const bmEmail = SEED.branchManager.email;
    const receptionist = SEED.branchStaff.find((s) => s.role === 'RECEPTIONIST')!;
    world.tempPasswordRejected = await probe(() => login(smEmail, MOCK_PASSWORD));

    world.accounts = await probe(async () => {
      const everyone = [SEED.superManager, SEED.branchManager, ...SEED.branchStaff];
      return Promise.all(
        everyone.map(async (s) => {
          const session = await login(s.email, DEV_PASSWORD);
          const me = await session.get<{ staffProfile: { branchId: number | null } }>('/api/me');
          return { email: s.email, role: session.account.role, mustChangePassword: session.account.mustChangePassword, branchId: me.staffProfile.branchId };
        }),
      );
    });

    world.branch = await probe(async () => {
      const sm = await login(smEmail, DEV_PASSWORD);
      const bm = await login(bmEmail, DEV_PASSWORD);
      const list = await sm.get<{ branchId: number; name: string; status: string; acceptsAfterHoursEmergency: boolean; activatedAt: string | null }[]>('/api/branches');
      const id = list[0].branchId;
      return {
        list,
        hours: await bm.get(`/api/branches/${id}/opening-hours`),
        services: await bm.get(`/api/branches/${id}/services`),
        quotaDefaults: await bm.get(`/api/branches/${id}/quota-defaults`),
        publicBranches: (await client.get('/api/public/branches')).data.data,
      };
    });

    world.catalog = await probe(async () => {
      const sm = await login(smEmail, DEV_PASSWORD);
      return {
        categories: await sm.get('/api/product-categories'),
        vaccineTypes: await sm.get('/api/vaccine-types'),
        products: (await sm.get<{ content: never[] }>('/api/products', { size: 100 })).content,
        services: await sm.get('/api/services'),
        protocols: await sm.get('/api/vaccination-protocols'),
      };
    });

    world.inventory = await probe(async () => {
      const bm = await login(bmEmail, DEV_PASSWORD);
      const receipts = await bm.get<NonNullable<typeof world.inventory.value>['receipts']>('/api/stock-receipts');
      const items = (await bm.get<{ content: NonNullable<typeof world.inventory.value>['items'] }>('/api/inventory', { size: 100 })).content;
      const lots: NonNullable<typeof world.inventory.value>['lots'] = {};
      for (const item of items) lots[item.sku] = await bm.get(`/api/inventory/${item.productId}/lots`);
      return {
        suppliers: await bm.get('/api/suppliers'),
        receipts,
        receipt: await bm.get(`/api/stock-receipts/${receipts.content[0].receiptId}`),
        items,
        lots,
      };
    });

    world.customers = await probe(async () => {
      const desk = await login(receptionist.email, DEV_PASSWORD);
      return Promise.all(
        SEED.customers.map(async (c) => {
          const found = await desk.get<{ content: { customerId: number }[] }>('/api/customers', { phone: c.phone, name: c.fullName });
          const detail = await desk.get<{ customer: { fullName: string; phone: string }; pets: { name: string; species: string }[] }>(`/api/customers/${found.content[0].customerId}`);
          return { fullName: detail.customer.fullName, phone: detail.customer.phone, pets: detail.pets };
        }),
      );
    });
  }, 60_000);

  afterAll(() => {
    removeShim();
    vi.useRealTimers();
  });

  it('chạy trọn bộ không lỗi và trả tóm tắt 8 tài khoản (1 SUPER_MANAGER, 1 BRANCH_MANAGER, 2 lễ tân, 2 bác sĩ, 2 chăm sóc)', () => {
    const summary = got(world.run);
    expect(summary.accounts.map((a) => a.role).sort()).toEqual(
      ['BRANCH_MANAGER', 'CARETAKER', 'CARETAKER', 'RECEPTIONIST', 'RECEPTIONIST', 'SUPER_MANAGER', 'VET', 'VET'],
    );
    expect(summary.accounts.map((a) => a.email).sort()).toEqual([SEED.superManager, SEED.branchManager, ...SEED.branchStaff].map((s) => s.email).sort());
    expect(summary.accounts.filter((a) => a.branchName === SEED.branch.name)).toHaveLength(7);
    expect(logs.length).toBeGreaterThan(8);
  });

  it('DB đã có dữ liệu seed thì dừng ngay bằng AlreadySeededError, không tạo thêm gì', () => {
    const error = world.rerun.error;
    expect(error).toBeInstanceOf(AlreadySeededError);
    expect(String((error as Error).message)).toContain(SEED.superManager.email);
  });

  it('chi nhánh ACTIVE, có cờ cấp cứu ngoài giờ, giờ mở cửa 2 khoảng T2–T7 và 1 khoảng Chủ nhật từ hôm nay (giờ VN)', () => {
    const { list, hours } = got(world.branch);
    expect(list).toHaveLength(1);
    expect(list[0]).toMatchObject({ name: SEED.branch.name, status: 'ACTIVE', acceptsAfterHoursEmergency: true });
    expect(list[0].activatedAt).toBeTruthy();
    expect(hours).toHaveLength(1);
    expect(hours[0].effectiveFrom).toBe(TODAY);
    expect(hours[0].days.map((d) => d.dayOfWeek)).toEqual([1, 2, 3, 4, 5, 6, 7]);
    for (const day of hours[0].days) {
      expect(day.ranges).toEqual(day.dayOfWeek === 7 ? [{ open: '08:00', close: '12:00' }] : [{ open: '08:00', close: '12:00' }, { open: '13:30', close: '19:00' }]);
    }
  });

  it('mọi dịch vụ được bật tại chi nhánh, quota mặc định MEDICAL / GROOMING đã đặt, chi nhánh hiện ở trang công khai', () => {
    const { services, quotaDefaults, publicBranches, list } = got(world.branch);
    expect(services).toHaveLength(SEED.services.length);
    expect(services.every((s) => s.enabled)).toBe(true);
    expect(quotaDefaults).toEqual([
      { serviceGroup: 'MEDICAL', defaultQuota: SEED.branch.quotaDefaults.MEDICAL },
      { serviceGroup: 'GROOMING', defaultQuota: SEED.branch.quotaDefaults.GROOMING },
    ]);
    expect(publicBranches.map((b) => b.branchId)).toContain(list[0].branchId);
    expect(publicBranches.find((b) => b.branchId === list[0].branchId)!.weeklyHours).toHaveLength(7);
  });

  it('cả 8 tài khoản đăng nhập được bằng mật khẩu dev, không còn mustChangePassword, nhân viên đúng chi nhánh', () => {
    const accounts = got(world.accounts);
    expect(accounts).toHaveLength(8);
    const branchId = got(world.branch).list[0].branchId;
    for (const a of accounts) {
      expect(a.mustChangePassword, a.email).toBe(false);
      expect(a.branchId, a.email).toBe(a.role === 'SUPER_MANAGER' ? null : branchId);
    }
  });

  it('mật khẩu tạm đã bị thay: đăng nhập bằng mật khẩu tạm bị từ chối 401', () => {
    expect((world.tempPasswordRejected.error as { response?: { status: number } }).response?.status).toBe(401);
  });

  it('danh mục: số lượng đúng data.ts, vaccine gắn loại vaccine, thuốc kê đơn quản lý hạn dùng, loại chuồng có thông số', () => {
    const { categories, vaccineTypes, products, services, protocols } = got(world.catalog);
    expect(categories.map((c) => c.name).sort()).toEqual(SEED.categories.map((c) => c).sort());
    expect(vaccineTypes).toHaveLength(SEED.vaccineTypes.length);
    expect(products).toHaveLength(SEED.products.length);
    expect(products.every((p) => p.isActive)).toBe(true);
    const vaccines = products.filter((p) => p.productType === 'VACCINE');
    expect(vaccines.length).toBeGreaterThanOrEqual(3);
    expect(vaccines.every((p) => p.tracksExpiry && vaccineTypes.some((t) => t.vaccineTypeId === p.vaccineTypeId))).toBe(true);
    const prescription = products.filter((p) => p.isPrescription);
    expect(prescription.length).toBeGreaterThanOrEqual(1);
    expect(prescription.every((p) => p.productType === 'DRUG' && p.tracksExpiry)).toBe(true);
    expect(services).toHaveLength(SEED.services.length);
    expect(services.filter((s) => s.medicalType === 'EXAM')).toHaveLength(1);
    expect(services.filter((s) => s.medicalType === 'VACCINE')).toHaveLength(1);
    expect(services.filter((s) => s.group === 'GROOMING').length).toBeGreaterThanOrEqual(1);
    const kennels = services.filter((s) => s.group === 'BOARDING');
    expect(kennels.length).toBeGreaterThanOrEqual(1);
    expect(kennels.every((s) => s.kennelType && s.kennelType.maxWeightKg > 0)).toBe(true);
    expect(protocols).toHaveLength(SEED.protocols.length);
  });

  it('kho: 2 nhà cung cấp, 1 phiếu nhập CONFIRMED có lô, mọi sản phẩm còn tồn khả dụng', () => {
    const { suppliers, receipts, receipt, items } = got(world.inventory);
    expect(suppliers.map((s) => s.name).sort()).toEqual(SEED.suppliers.map((s) => s.name).sort());
    expect(receipts.content).toHaveLength(1);
    expect(receipts.content[0].status).toBe('CONFIRMED');
    expect(receipt.status).toBe('CONFIRMED');
    expect(receipt.receiptDate).toBe('2026-09-23');
    expect(receipt.lines).toHaveLength(SEED.receipt.lines.length);
    expect(receipt.lines.every((l) => l.stockLotId !== null)).toBe(true);
    expect(items).toHaveLength(SEED.products.length);
    expect(items.every((i) => i.availableQuantity > 0)).toBe(true);
  });

  it('kho: có lô sắp hết hạn (< 30 ngày), có lô đã hết hạn không tính vào tồn khả dụng, lô sắp theo hạn dùng (FEFO)', () => {
    const { items, lots } = got(world.inventory);
    const bySku = (sku: string) => items.find((i) => i.sku === sku)!;
    expect(bySku('AMX-250').nearestExpiryDate).toBe('2026-11-12');
    const vitamin = bySku('VITA-PET');
    expect(vitamin.expiredQuantity).toBe(2);
    expect(vitamin.availableQuantity).toBe(vitamin.totalQuantity - 2);
    expect(lots['VITA-PET'].filter((l) => l.expired)).toHaveLength(1);
    const dated = lots['AMX-250'].map((l) => l.expiryDate!);
    expect(dated).toEqual([...dated].sort());
    expect(dated).toHaveLength(2);
  });

  it('khách tại quầy: 3 hồ sơ, 4 thú cưng đúng chủ', () => {
    const customers = got(world.customers);
    expect(customers).toHaveLength(3);
    for (const [i, seeded] of SEED.customers.entries()) {
      expect(customers[i].fullName).toBe(seeded.fullName);
      expect(customers[i].phone).toBe(seeded.phone);
      expect(customers[i].pets.map((p) => `${p.name}/${p.species}`).sort()).toEqual(seeded.pets.map((p) => `${p.name}/${p.species}`).sort());
    }
    expect(customers.flatMap((c) => c.pets)).toHaveLength(4);
  });
});

describe('seed dev — đọc mật khẩu tạm từ email STAFF_TEMP_PASSWORD', () => {
  // Nội dung chép từ V11__seed_staff_admin_templates.sql, thay {ho_ten} / {email_dang_nhap} / {mat_khau_tam}.
  const body = (email: string, password: string) =>
    `Xin chào Nguyễn Thanh Hà,\n\nTài khoản nhân viên Pet Care của bạn đã sẵn sàng.\n\nEmail đăng nhập: ${email}\nMật khẩu tạm: ${password}\n\nBạn phải đổi mật khẩu ngay ở lần đăng nhập đầu tiên. Nếu bạn nhận được nhiều email như thế này, chỉ mật khẩu trong email mới nhất còn dùng được. Không chia sẻ mật khẩu này với bất kỳ ai.\n\nPet Care`;

  it('lấy đúng mật khẩu, chịu được xuống dòng CRLF và Unicode dạng tổ hợp (NFD)', () => {
    expect(parseTempPassword(body('ha.nguyen@petcare.local', 'kP7mQ2xTzW9c'), 'ha.nguyen@petcare.local')).toBe('kP7mQ2xTzW9c');
    expect(parseTempPassword(body('ha.nguyen@petcare.local', 'kP7mQ2xTzW9c').replace(/\n/g, '\r\n'), 'ha.nguyen@petcare.local')).toBe('kP7mQ2xTzW9c');
    expect(parseTempPassword(body('ha.nguyen@petcare.local', 'kP7mQ2xTzW9c').normalize('NFD'), 'ha.nguyen@petcare.local')).toBe('kP7mQ2xTzW9c');
  });

  it('bỏ qua email gửi cho người khác dù địa chỉ chỉ khớp một phần, và nội dung không phải mẫu mật khẩu tạm', () => {
    expect(parseTempPassword(body('thuan.ha.nguyen@petcare.local', 'kP7mQ2xTzW9c'), 'ha.nguyen@petcare.local')).toBeNull();
    expect(parseTempPassword('Mã xác thực của bạn là: 123456', 'ha.nguyen@petcare.local')).toBeNull();
  });
});
