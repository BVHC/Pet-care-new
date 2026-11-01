// Test seed dev giai đoạn 2 (FE/scripts/seed-dev/seed-phase2.ts). Chạy trên BE GIẢ (seed-dev-phase2.fake-be.ts), KHÔNG phải BE thật: chứng minh
// thứ tự gọi, vai trò gọi từng bước, từ chối lần hai / ngoài giờ không ghi gì, báo lỗi giữa chừng, và kiểm giờ mở cửa theo giờ VN.
// Dữ liệu (nội dung trang, bài viết...) được kiểm lại bằng schema của chính FE (PAGE_SCHEMAS). Đã chạy thật với BE: xem README-phase2.md.
import axios from 'axios';
import { describe, expect, it } from 'vitest';
import { PAGE_CONTENT_MAX_BYTES, PAGE_SCHEMAS, pageContentBytes } from '../shared/constants/page-content';
import { DEV_PASSWORD, SEED } from '../../scripts/seed-dev/data';
import { PHASE2, STAFF_KEYS } from '../../scripts/seed-dev/data-phase2';
import { parseOtp, StubOtpSource } from '../../scripts/seed-dev/otp';
import {
  addDays,
  assessOpeningWindow,
  isoWeekday,
  Phase2AlreadySeededError,
  Phase2ClosedError,
  Phase2Error,
  runSeedPhase2,
  vnDate,
  vnMinutes,
  type OpeningVersion,
  type Phase2Options,
  type StageName,
} from '../../scripts/seed-dev/seed-phase2';
import { installFakeBe, type FakeBeOptions } from './seed-dev-phase2.fake-be';

/** 10:00 thứ Sáu 2026-10-30 giờ VN. */
const FRIDAY_10 = new Date('2026-10-30T03:00:00Z');
/** Giờ VN → Date, dùng ngày 2026-10-30 (Thứ Sáu) hoặc ngày khác. */
const vn = (date: string, time: string) => new Date(`${date}T${time}:00+07:00`);

const VERSIONS: OpeningVersion[] = [{ effectiveFrom: '2026-10-24', days: SEED.branch.openingHours }];
const assess = (now: Date, extra: Partial<Parameters<typeof assessOpeningWindow>[0]> = {}) =>
  assessOpeningWindow({ now, versions: VERSIONS, holidays: [], marginMinutes: 10, ...extra });

describe('seed giai đoạn 2: giờ VN và giờ mở cửa', () => {
  it('ngày và phút tính theo giờ VN dù Date là UTC', () => {
    expect(vnDate(new Date('2026-10-30T17:30:00Z'))).toBe('2026-10-31');
    expect(vnMinutes(new Date('2026-10-30T17:30:00Z'))).toBe(30);
    expect(vnDate(FRIDAY_10)).toBe('2026-10-30');
    expect(vnMinutes(FRIDAY_10)).toBe(600);
    expect(isoWeekday('2026-10-30')).toBe(5);
    expect(isoWeekday('2026-11-01')).toBe(7);
    expect(addDays('2026-10-30', 3)).toBe('2026-11-02');
    expect(addDays('2026-10-30', -30)).toBe('2026-09-30');
  });

  it('trong giờ mở cửa và còn đủ thời gian thì đạt', () => {
    expect(assess(vn('2026-10-30', '10:00'))).toMatchObject({ ok: true, minutesLeft: 120 });
    expect(assess(vn('2026-10-30', '08:00')).ok).toBe(true);
    expect(assess(vn('2026-10-30', '13:30')).ok).toBe(true);
    expect(assess(vn('2026-10-30', '18:49'))).toMatchObject({ ok: true, minutesLeft: 11 });
    expect(assess(vn('2026-11-01', '11:00')).ok).toBe(true);
  });

  it('từ chối giữa trưa 12:00–13:30 (nghỉ trưa) và nói rõ giờ mở cửa', () => {
    for (const time of ['12:00', '12:30', '13:29']) {
      const verdict = assess(vn('2026-10-30', time));
      expect(verdict.ok, time).toBe(false);
      expect(verdict.reason).toContain('Ngoài giờ mở cửa');
      expect(verdict.reason).toContain('08:00–12:00 và 13:30–19:00');
    }
  });

  it('từ chối khi sắp đóng cửa (dưới 10 phút), trước giờ mở và sau giờ đóng', () => {
    expect(assess(vn('2026-10-30', '11:55'))).toMatchObject({ ok: false, minutesLeft: 5 });
    expect(assess(vn('2026-10-30', '18:55'))).toMatchObject({ ok: false, minutesLeft: 5 });
    expect(assess(vn('2026-10-30', '18:50')).ok).toBe(true);
    expect(assess(vn('2026-10-30', '07:59')).ok).toBe(false);
    expect(assess(vn('2026-10-30', '19:00')).ok).toBe(false);
    expect(assess(vn('2026-10-30', '23:30')).ok).toBe(false);
    expect(assess(vn('2026-10-30', '18:55'), { marginMinutes: 3 }).ok).toBe(true);
  });

  it('Chủ nhật chỉ mở buổi sáng: 13:00 Chủ nhật bị từ chối', () => {
    const verdict = assess(vn('2026-11-01', '13:00'));
    expect(verdict.ok).toBe(false);
    expect(verdict.reason).toContain('hôm nay chi nhánh mở 08:00–12:00');
  });

  it('ngày nghỉ, chi nhánh không mở thứ đó, chưa có bản giờ hiệu lực', () => {
    expect(assess(vn('2026-10-30', '10:00'), { holidays: ['2026-10-30'] }).reason).toContain('ngày nghỉ');
    const noSaturday: OpeningVersion[] = [{ effectiveFrom: '2026-10-24', days: SEED.branch.openingHours.filter((d) => d.dayOfWeek !== 6) }];
    expect(assess(vn('2026-10-31', '10:00'), { versions: noSaturday })).toMatchObject({ ok: false });
    expect(assess(vn('2026-10-31', '10:00'), { versions: noSaturday }).reason).toContain('Thứ Bảy');
    expect(assess(vn('2026-10-30', '10:00'), { versions: [{ effectiveFrom: '2026-11-05', days: SEED.branch.openingHours }] }).reason).toContain('chưa có giờ mở cửa hiệu lực');
  });

  it('chọn bản giờ hiệu lực mới nhất không sau hôm nay, bỏ qua bản tương lai', () => {
    const shortDays = SEED.branch.openingHours.map((d) => ({ dayOfWeek: d.dayOfWeek, ranges: [{ open: '09:00', close: '10:00' }] }));
    const versions: OpeningVersion[] = [
      { effectiveFrom: '2026-10-24', days: SEED.branch.openingHours },
      { effectiveFrom: '2026-10-30', days: shortDays },
      { effectiveFrom: '2026-12-01', days: [] },
    ];
    expect(assess(vn('2026-10-30', '15:00'), { versions }).ok).toBe(false);
    expect(assess(vn('2026-10-29', '15:00'), { versions }).ok).toBe(true);
  });
});

describe('seed giai đoạn 2: dữ liệu', () => {
  it('nội dung 4 trang đúng schema FE (PAGE_SCHEMAS) và dưới 64 KB', () => {
    for (const key of ['HOME_BANNER', 'ABOUT', 'POLICY', 'FAQ'] as const) {
      const parsed = PAGE_SCHEMAS[key].safeParse(PHASE2.content.pages[key]);
      expect(parsed.success, `${key}: ${parsed.success ? '' : JSON.stringify(parsed.error.issues.slice(0, 3))}`).toBe(true);
      expect(pageContentBytes(PHASE2.content.pages[key])).toBeLessThan(PAGE_CONTENT_MAX_BYTES);
    }
  });

  it('chuyên mục và bài viết đúng luật của BE: slug, độ dài, chỉ thẻ HTML cho phép, 5 đã xuất bản + 1 nháp', () => {
    const slug = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
    const { categories, articles } = PHASE2.content;
    expect(categories).toHaveLength(3);
    for (const c of categories) {
      expect(c.slug).toMatch(slug);
      expect(c.slug.length).toBeLessThanOrEqual(120);
      expect(c.name.length).toBeLessThanOrEqual(100);
    }
    expect(new Set(articles.map((a) => a.slug)).size).toBe(articles.length);
    for (const a of articles) {
      expect(a.slug).toMatch(slug);
      expect(a.slug.length).toBeLessThanOrEqual(270);
      expect(a.title.length).toBeLessThanOrEqual(250);
      expect(a.summary.length).toBeLessThanOrEqual(500);
      expect(a.authorDisplayName.length).toBeLessThanOrEqual(100);
      expect(categories.map((c) => c.key)).toContain(a.category);
      const tags = [...a.content.matchAll(/<\/?([a-z0-9]+)/g)].map((m) => m[1]);
      expect(tags.every((t) => ['p', 'h2', 'ul', 'ol', 'li', 'strong'].includes(t)), a.slug).toBe(true);
    }
    expect(articles.filter((a) => a.publish)).toHaveLength(5);
    expect(articles.filter((a) => !a.publish)).toHaveLength(1);
  });

  it('không có nhãn [TEST]; khách mới có SĐT hợp lệ, không trùng, thú cưng không trùng tên với giai đoạn 1; có khách không email', () => {
    expect(JSON.stringify(PHASE2)).not.toMatch(/\[TEST|\bTEST\b/);
    const phones = [...SEED.customers, ...PHASE2.customers].map((c) => c.phone);
    for (const c of PHASE2.customers) expect(c.phone).toMatch(/^0\d{9}$/);
    expect(phones.filter((p) => PHASE2.customers.some((c) => c.phone === p))).toHaveLength(PHASE2.customers.length);
    const names = [...SEED.customers, ...PHASE2.customers].flatMap((c) => c.pets.map((p) => p.name));
    expect(new Set(names).size).toBe(names.length);
    expect(PHASE2.customers.some((c) => !('email' in c))).toBe(true);
  });

  it('mọi tên tham chiếu (thú, dịch vụ, SKU, loại vaccine, khóa nhân viên, khóa Order, chuồng) có thật', () => {
    const pets = new Map([...SEED.customers, ...PHASE2.customers].flatMap((c) => c.pets.map((p) => [p.name, p.species] as const)));
    const serviceNames = new Set(SEED.services.map((s) => s.name));
    const skus = new Set(SEED.products.map((p) => p.sku));
    const orderKeys = new Set<string>([...PHASE2.visits.map((v) => v.key), ...PHASE2.retailOrders.map((r) => r.key)]);
    for (const a of PHASE2.appointments) {
      expect(pets.has(a.pet), a.pet).toBe(true);
      expect(serviceNames.has(a.service), a.service).toBe(true);
    }
    for (const b of PHASE2.futureBookings) expect(pets.has(b.pet) && serviceNames.has(b.kennelType)).toBe(true);
    for (const v of PHASE2.visits) {
      expect(pets.has(v.pet) && serviceNames.has(v.service), v.key).toBe(true);
      if (v.assignee) expect(STAFF_KEYS).toContain(v.assignee);
      if (v.vaccine) {
        expect(skus.has(v.vaccine.sku)).toBe(true);
        expect([...SEED.vaccineTypes, ...[]].some((t) => t.name === v.vaccine?.typeName && t.species === v.vaccine.species)).toBe(true);
        expect(pets.get(v.pet)).toBe(v.vaccine.species);
      }
      if (v.prescription) expect(SEED.products.find((p) => p.sku === v.prescription?.sku)?.isPrescription).toBe(true);
      if (v.record?.followUpInDays !== undefined) expect(v.record.followUpInDays).toBeGreaterThanOrEqual(1);
      if (v.record?.followUpInDays !== undefined) expect(v.record.followUpInDays).toBeLessThanOrEqual(3);
    }
    for (const r of PHASE2.retailOrders) {
      expect([...SEED.customers, ...PHASE2.customers].some((c) => c.phone === r.customerPhone && c.fullName === r.customerName)).toBe(true);
      expect(r.lines.every((l) => skus.has(l.sku) && SEED.products.find((p) => p.sku === l.sku)?.productType === 'GOODS')).toBe(true);
    }
    for (const p of PHASE2.payments) expect(p.orders.every((k) => orderKeys.has(k))).toBe(true);
    const codes = PHASE2.kennels.map((k) => k.code);
    expect(new Set(codes).size).toBe(codes.length);
    expect(codes).toContain(PHASE2.maintenanceKennel);
    for (const stay of [PHASE2.stay, PHASE2.checkoutCycle]) {
      const kennel = PHASE2.kennels.find((k) => k.code === stay.kennelCode);
      expect(kennel?.kennelType).toBe(stay.kennelType);
      expect(kennel?.code).not.toBe(PHASE2.maintenanceKennel);
      expect(pets.get(stay.pet)).toBe('CAT');
    }
    for (const k of PHASE2.kennels) expect(SEED.services.find((s) => s.name === k.kennelType)?.group).toBe('BOARDING');
  });

  it('khách có hẹn tái khám không bị đặt lịch Khám / Tiêm sau đó (BR-TB-06 sẽ hủy nhắc); lượt chưa hoàn tất không có đơn thuốc', () => {
    const followed = PHASE2.visits.filter((v) => v.record?.followUpInDays !== undefined).map((v) => v.pet);
    expect(followed).toEqual(['Miu', 'Lu']);
    const medical = new Set(SEED.services.filter((s) => s.group === 'MEDICAL').map((s) => s.name));
    for (const a of PHASE2.appointments) {
      if (medical.has(a.service)) expect(followed).not.toContain(a.pet);
    }
    for (const v of PHASE2.visits) if (v.stopAt !== 'COMPLETED') expect(v.prescription).toBeUndefined();
    // Order chờ thu còn lại sau khi thu: lượt khám của Lu + Order bán lẻ của chị Trang.
    const paid = new Set<string>(PHASE2.payments.flatMap((p) => p.orders));
    const pending = [...PHASE2.visits.filter((v) => v.stopAt === 'COMPLETED').map((v) => v.key), ...PHASE2.retailOrders.map((r) => r.key)].filter((k) => !paid.has(k));
    expect(pending).toEqual(['lu-kham', 'retail-trang']);
  });
});

describe('seed giai đoạn 2: OTP', () => {
  it('đọc mã từ thư OTP_REGISTER, bỏ qua thư không phải OTP', () => {
    expect(parseOtp('Xin chào,\n\nMã xác thực đăng ký tài khoản Pet Care của bạn là: 482913\n\nMã có hiệu lực trong 5 phút')).toBe('482913');
    expect(parseOtp('Mã xác thực đăng ký tài khoản Pet Care của bạn là: 482913'.normalize('NFD'))).toBe('482913');
    expect(parseOtp('Email đăng nhập: a@b.c\nMật khẩu tạm: kP7mQ2xTzW9c')).toBeNull();
  });
});

function setup(beOptions: FakeBeOptions = {}, options: Partial<Phase2Options> = {}) {
  const client = axios.create({ baseURL: 'http://fake-be' });
  const be = installFakeBe(client, beOptions);
  const logs: string[] = [];
  const run = (extra: Partial<Phase2Options> = {}) => runSeedPhase2({ client, log: (l) => void logs.push(l), now: FRIDAY_10, password: DEV_PASSWORD, ...options, ...extra });
  return { be, logs, run };
}

describe('seed giai đoạn 2: chạy trên BE giả', () => {
  it('chạy trọn bộ: số lượng khớp data, thứ tự và vai trò đúng', async () => {
    const { be, run, logs } = setup();
    const summary = await run();
    const s = be.state;

    expect(s.customers).toHaveLength(SEED.customers.length + PHASE2.customers.length);
    expect(s.customers.flatMap((c) => c.pets)).toHaveLength(4 + 5);
    expect(s.protocols.filter((p) => p.species === 'CAT')).toHaveLength(PHASE2.catProtocols.length);
    expect(s.protocols.filter((p) => p.species === 'CAT').every((p) => !p.requiredForBoarding)).toBe(true);
    expect(s.kennels.map((k) => `${k.code}:${k.status}`).sort()).toEqual(['CL-01:AVAILABLE', 'CM-01:OCCUPIED', 'CM-02:AVAILABLE', 'CM-03:AVAILABLE', 'CN-01:AVAILABLE', 'CN-02:MAINTENANCE']);
    expect(s.categories).toHaveLength(3);
    expect(s.articles.filter((a) => a.status === 'PUBLISHED')).toHaveLength(5);
    expect(s.articles.filter((a) => a.status === 'DRAFT')).toHaveLength(1);
    expect(Object.keys(s.pages).sort()).toEqual(['ABOUT', 'FAQ', 'HOME_BANNER', 'POLICY']);
    expect(s.appointments).toHaveLength(6);

    // lượt hôm nay: 5 hoàn tất, 1 đang khám, 1 đang chờ chưa gán
    expect(s.visits.map((v) => v.status).sort()).toEqual(['COMPLETED', 'COMPLETED', 'COMPLETED', 'COMPLETED', 'COMPLETED', 'IN_PROGRESS', 'WAITING']);
    expect(s.visits.filter((v) => v.assigneeEmail === null)).toHaveLength(1);
    expect(s.visits.reduce((n, v) => n + v.vaccinations, 0)).toBe(2);

    // Order: chờ thu = lượt của Lu + Order bán lẻ của chị Trang; 2 lượt chưa hoàn tất còn OPEN
    const pending = s.orders.filter((o) => o.status === 'PENDING');
    expect(pending).toHaveLength(2);
    expect(s.orders.filter((o) => o.status === 'OPEN')).toHaveLength(2);
    expect(s.orders.filter((o) => o.status === 'PAID')).toHaveLength(s.orders.length - 4);
    expect(s.payments.map((p) => p.method)).toEqual(['CASH', 'TRANSFER', 'CASH', 'TRANSFER']);
    expect(s.payments[2].orderIds).toHaveLength(3);

    // ca thu ngân còn mở, tiền mặt = các phiếu CASH
    expect(s.shifts).toHaveLength(1);
    expect(s.shifts[0]).toMatchObject({ cashierEmail: 'anh.pham@petcare.local', status: 'OPEN' });
    expect(summary.cashCollected).toBe(s.payments.filter((p) => p.method === 'CASH').reduce((n, p) => n + p.amount, 0));

    // lưu trú: 2 BOOKED + 1 đang ở + 1 đã trả; nhật ký 3 mục (1 bất thường), 1 bản bổ sung
    expect(s.bookings.map((b) => b.status)).toEqual(['BOOKED', 'BOOKED', 'CHECKED_IN', 'CHECKED_OUT']);
    expect(s.careLogs).toHaveLength(3);
    expect(s.careLogs.filter((c) => c.abnormal)).toHaveLength(1);
    expect(s.careLogs.reduce((n, c) => n + c.addenda, 0)).toBe(1);

    expect(summary.stages).toEqual(['protocols', 'customers', 'kennels', 'content', 'appointments', 'bookings', 'today', 'stay']);
    expect(summary.notes.join(' ')).toContain('FOLLOW_UP_REMINDER_CRON');
    expect(logs.filter((l) => /^\[\d+\]/.test(l)).length).toBeGreaterThan(10);
  });

  it('không ghi gì trước khi kiểm xong: 8 lần đăng nhập và các lời gọi GET đứng trước mọi lời gọi ghi', async () => {
    const { be, run } = setup();
    await run();
    const firstWrite = be.calls.findIndex((c) => c.method !== 'GET' && !c.path.endsWith('/auth/login'));
    const before = be.calls.slice(0, firstWrite);
    expect(before.filter((c) => c.path.endsWith('/auth/login'))).toHaveLength(STAFF_KEYS.length);
    expect(before.some((c) => c.path.endsWith('/opening-hours'))).toBe(true);
    expect(before.some((c) => c.path === '/api/services')).toBe(true);
    expect(before.every((c) => c.method === 'GET' || c.path.endsWith('/auth/login'))).toBe(true);
  });

  it('mỗi bước dùng đúng vai trò: nội dung SUPER_MANAGER, chuồng BRANCH_MANAGER, khám bác sĩ được gán, thu tiền chỉ anh.pham', async () => {
    const { be, run } = setup();
    await run();
    const by = (method: string, path: RegExp) => [...new Set(be.calls.filter((c) => c.method === method && path.test(c.path)).map((c) => c.who))];
    expect(by('POST', /^\/api\/articles/)).toEqual(['ha.nguyen@petcare.local']);
    expect(by('PUT', /^\/api\/page-contents/)).toEqual(['ha.nguyen@petcare.local']);
    expect(by('POST', /^\/api\/kennels/)).toEqual(['hung.tran@petcare.local']);
    expect(by('POST', /^\/api\/visits$/)).toEqual(['mai.le@petcare.local']);
    expect(by('POST', /^\/api\/visits\/\d+\/call$/).sort()).toEqual(['hang.dang@petcare.local', 'khoi.vo@petcare.local', 'tai.bui@petcare.local']);
    expect(by('POST', /^\/api\/payments$/)).toEqual(['anh.pham@petcare.local']);
    expect(by('POST', /\/care-logs$/).sort()).toEqual(['lan.ngo@petcare.local', 'tai.bui@petcare.local']);
  });

  it('Order lưu trú được thu TRƯỚC khi giao thú (BR-LT-09)', async () => {
    const { be, run } = setup();
    await run();
    const idx = (method: string, path: RegExp) => be.calls.findIndex((c) => c.method === method && path.test(c.path));
    const handOver = idx('POST', /hand-over$/);
    const startCheckout = idx('POST', /start-checkout$/);
    const boardingPayment = be.calls.map((c, i) => ({ c, i })).filter(({ c }) => c.method === 'POST' && c.path === '/api/payments').pop()?.i ?? -1;
    expect(startCheckout).toBeGreaterThan(-1);
    expect(boardingPayment).toBeGreaterThan(startCheckout);
    expect(handOver).toBeGreaterThan(boardingPayment);
  });

  it('chạy lần hai bị từ chối, nêu rõ dấu hiệu và KHÔNG ghi gì', async () => {
    const { be, run } = setup();
    await run();
    const writesBefore = be.writes().length;
    const callsBefore = be.calls.length;
    const error = await run().catch((e: unknown) => e);
    expect(error).toBeInstanceOf(Phase2AlreadySeededError);
    const hits = (error as Phase2AlreadySeededError).hits.join(' | ');
    expect(hits).toContain('Phạm Quốc Khánh');
    expect(hits).toMatch(/chuồng/);
    expect(hits).toMatch(/bài viết/);
    expect(hits).toMatch(/lịch hẹn/);
    expect(hits).toMatch(/Order/);
    expect(hits).toMatch(/ca thu ngân/);
    expect(be.writes()).toHaveLength(writesBefore);
    expect(be.calls.length).toBeGreaterThan(callsBefore);
  });

  it('--only chỉ từ chối đúng giai đoạn đã làm: sau khi làm hết, chạy riêng feedback vẫn được', async () => {
    const { be, run } = setup();
    await run();
    await expect(run({ stages: ['content'] })).rejects.toBeInstanceOf(Phase2AlreadySeededError);
    const summary = await run({ stages: ['feedback'], otp: new StubOtpSource('123456') });
    expect(summary.sections.Feedback).toHaveLength(2);
    expect(be.state.feedbacks.map((f) => f.status).sort()).toEqual(['NEW', 'RESOLVED']);
  });

  it('ngoài giờ mở cửa (giữa trưa): từ chối luồng hôm nay, KHÔNG ghi gì', async () => {
    const { be, run } = setup();
    const error = await run({ now: vn('2026-10-30', '12:30') }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(Phase2ClosedError);
    expect((error as Error).message).toContain('chạy lại trong giờ mở cửa');
    expect((error as Error).message).toContain('--only=');
    expect(be.writes()).toHaveLength(0);
  });

  it('ngoài giờ mở cửa: phần không phụ thuộc giờ vẫn dựng được bằng --only', async () => {
    const { be, run } = setup();
    const stages: StageName[] = ['protocols', 'customers', 'kennels', 'content', 'appointments', 'bookings'];
    const summary = await run({ now: vn('2026-11-01', '13:00'), stages });
    expect(summary.stages).toEqual(stages);
    expect(be.state.visits).toHaveLength(0);
    expect(be.state.orders).toHaveLength(0);
    expect(be.state.shifts).toHaveLength(0);
    expect(be.state.bookings.map((b) => b.status)).toEqual(['BOOKED', 'BOOKED']);
  });

  it('lùi sang ngày sau khi ngày muốn đặt không có khung trống (ngày nghỉ)', async () => {
    const { be, run } = setup({ closedDates: ['2026-10-31'] });
    await run({ stages: ['customers', 'appointments'] });
    const susu = be.state.appointments[0];
    expect(susu.slotDate).toBe('2026-11-01');
    expect(be.state.appointments.every((a) => a.slotDate !== '2026-10-31')).toBe(true);
  });

  it('lỗi giữa chừng: dừng, báo bước + HTTP status + message BE, liệt kê những gì đã tạo, không dọn', async () => {
    const { be, run } = setup({ failOn: 'POST /api/payments' });
    const error = await run().catch((e: unknown) => e);
    expect(error).toBeInstanceOf(Phase2Error);
    const failure = error as Phase2Error;
    expect(failure.status).toBe(500);
    expect(failure.errorCode).toBe('INTERNAL_ERROR');
    expect(failure.message).toContain('POST /api/payments');
    expect(failure.message).toContain('Lỗi giả lập của BE');
    expect(failure.step).toContain('Thu ngân');
    expect(failure.partial).toBe(true);
    expect(failure.created['Khách và thú']).toHaveLength(PHASE2.customers.length);
    expect(failure.created['Lượt khám hôm nay']).toHaveLength(PHASE2.visits.length);
    expect(failure.created['Ca thu ngân']).toHaveLength(1);
    expect(be.state.visits).toHaveLength(PHASE2.visits.length);
    expect(be.state.bookings.map((b) => b.status)).toEqual(['BOOKED', 'BOOKED']);
  });

  it('thiếu dữ liệu nền (đăng nhập thất bại) thì dừng ở bước đầu, chưa ghi gì và gợi ý chạy seed nền', async () => {
    const { be, run } = setup();
    const error = await run({ password: 'sai-mat-khau' }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(Phase2Error);
    expect((error as Phase2Error).status).toBe(401);
    expect((error as Phase2Error).message).toContain('seed giai đoạn 1');
    expect((error as Phase2Error).partial).toBe(false);
    expect(be.writes()).toHaveLength(0);
  });

  it('--check chỉ kiểm điều kiện, không ghi gì', async () => {
    const { be, run } = setup();
    const summary = await run({ checkOnly: true });
    expect(be.writes()).toHaveLength(0);
    expect(summary.sections).toEqual({});
    expect(summary.notes.join(' ')).toContain('--check');
  });

  it('--close-shift: thu ngân chốt ca đúng số tiền mặt rồi BRANCH_MANAGER đối soát', async () => {
    const { be, run } = setup();
    const summary = await run({ stages: [...['protocols', 'customers', 'kennels', 'appointments', 'today', 'stay', 'close-shift'] as StageName[]] });
    expect(be.state.shifts[0].status).toBe('RECONCILED');
    expect(summary.sections['Ca thu ngân'].at(-1)).toContain('RECONCILED');
    const reconcile = be.calls.find((c) => c.path.endsWith('/reconcile'));
    expect(reconcile?.who).toBe('hung.tran@petcare.local');
  });

  it('--no-checkout-cycle bỏ chu trình Tom, vẫn giữ Mochi đang lưu trú', async () => {
    const { be, run } = setup();
    await run({ skipCheckoutCycle: true });
    expect(be.state.bookings.map((b) => b.status)).toEqual(['BOOKED', 'BOOKED', 'CHECKED_IN']);
    expect(be.state.payments).toHaveLength(3);
  });
});
