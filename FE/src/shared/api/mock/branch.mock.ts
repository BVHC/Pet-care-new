// Mock module branch (docs/api/branch-v1.md #1–19): chi nhánh, giờ mở cửa, ngày nghỉ (kèm hủy hàng loạt),
// dịch vụ tại chi nhánh, quota. Quyền, thứ tự kiểm tra và câu báo lỗi chép từ BE (module/branch).
// Lịch hẹn / đặt chỗ lưu trú BOOKED do mock tự giữ, thay cho AppointmentQueryApi / BoardingQueryApi;
// trên BE thật hai API này còn là placeholder nên đổi lịch của chi nhánh ACTIVE trả 500 (time.md, T10).
import type { Role } from '../../types/auth';
import type {
  AffectedAppointment,
  AffectedBoarding,
  Branch,
  DayHours,
  Holiday,
  OpeningHoursVersion,
  QuotaGroup,
  ScheduleImpact,
  SlotQuota,
  TimeRange,
} from '../../types/branch';
import {
  addDays,
  isEmptyImpact,
  localIsoDate,
  nextWeekday,
  rangesOn,
  slotFits,
  slotsOn,
} from '../../utils/branch-schedule';
import { catalogServices } from './catalog.mock';
import {
  denied,
  fail,
  invalid,
  isText,
  noContent,
  notFound,
  ok,
  ruleFail,
  scopeDenied,
  type Check,
  type MockHandler,
  type MockReply,
  type MockRequest,
} from './envelope';
import { branchIdOf, countBranchManagers } from './identity.mock';

type Booked<T> = T & { branchId: number };

interface State {
  seq: number;
  branches: Branch[];
  /** Các bản giờ mở cửa theo chi nhánh. */
  hours: Record<number, OpeningHoursVersion[]>;
  holidays: Booked<Holiday>[];
  /** branchId → serviceId → bật. Chưa có dòng = tắt. */
  services: Record<number, Record<number, boolean>>;
  quotaDefaults: Record<number, Partial<Record<QuotaGroup, number>>>;
  slotQuotas: Booked<SlotQuota>[];
  /** Lịch hẹn BOOKED (thay module appointment). */
  appointments: Booked<AffectedAppointment>[];
  /** Đặt chỗ lưu trú BOOKED (thay module boarding). */
  boardings: Booked<AffectedBoarding>[];
}

const MORNING: TimeRange = { open: '08:00', close: '12:00' };
const AFTERNOON: TimeRange = { open: '13:30', close: '19:00' };
const code = (prefix: string, date: string, n: number) => `${prefix}-${date.slice(2).replace(/-/g, '')}-${String(n).padStart(4, '0')}`;

/** Ngày mẫu tính theo hôm nay lúc tạo dữ liệu. */
function seed(today = localIsoDate()): State {
  const tuesday = nextWeekday(today, 2);
  const thursday = nextWeekday(today, 4);
  const friday = nextWeekday(today, 5);
  const branch = (b: Omit<Branch, 'activatedAt'> & { activatedAt?: string | null }): Branch => ({ activatedAt: null, ...b });
  return {
    seq: 100,
    branches: [
      branch({
        branchId: 1,
        name: 'PetCare Cầu Giấy',
        address: '123 Trần Duy Hưng, Cầu Giấy, Hà Nội',
        phone: '0901234567',
        latitude: 21.0128,
        longitude: 105.8031,
        status: 'ACTIVE',
        acceptsAfterHoursEmergency: true,
        activatedAt: '2026-01-02T01:00:00Z',
      }),
      branch({
        branchId: 2,
        name: 'PetCare Hà Đông',
        address: '45 Quang Trung, Hà Đông, Hà Nội',
        phone: '0902345678',
        latitude: 20.9714,
        longitude: 105.7787,
        status: 'DRAFT',
        acceptsAfterHoursEmergency: false,
      }),
      branch({
        branchId: 3,
        name: 'PetCare Long Biên',
        address: '8 Nguyễn Văn Cừ, Long Biên, Hà Nội',
        phone: '0903456789',
        latitude: 21.0453,
        longitude: 105.8781,
        status: 'DRAFT',
        acceptsAfterHoursEmergency: false,
      }),
    ],
    hours: {
      1: [
        {
          effectiveFrom: '2026-01-01',
          days: [1, 2, 3, 4, 5, 6, 7].map((d) => ({ dayOfWeek: d, ranges: d === 7 ? [MORNING] : [MORNING, AFTERNOON] })),
        },
      ],
    },
    holidays: [
      { holidayId: 1, branchId: 1, holidayDate: addDays(today, -30), reason: 'Nghỉ lễ' },
      { holidayId: 2, branchId: 1, holidayDate: addDays(today, 20), reason: 'Bảo trì hệ thống điện' },
    ],
    services: { 1: { 1: true, 2: true, 3: true, 4: true, 5: true, 6: false } },
    quotaDefaults: { 1: { MEDICAL: 3 } },
    slotQuotas: [{ slotQuotaId: 1, branchId: 1, serviceGroup: 'MEDICAL', slotDate: tuesday, slotStart: '08:00', quota: 0 }],
    appointments: [
      { appointmentId: 1, branchId: 1, code: code('LH', tuesday, 1), slotDate: tuesday, slotStart: '09:00', petName: 'Mochi', customerName: 'Nguyễn Thu Trang', customerPhone: '0912345678' },
      { appointmentId: 2, branchId: 1, code: code('LH', tuesday, 2), slotDate: tuesday, slotStart: '18:30', petName: 'Lucky', customerName: 'Trần Đức Huy', customerPhone: '0912554321' },
      { appointmentId: 3, branchId: 1, code: code('LH', thursday, 1), slotDate: thursday, slotStart: '14:00', petName: 'Miu', customerName: 'Lê Minh Châu', customerPhone: null },
    ],
    boardings: [
      { bookingId: 1, branchId: 1, code: code('LT', friday, 1), checkInDate: friday, checkOutDate: addDays(friday, 2), petName: 'Bơ', customerName: 'Phạm Gia Bảo', customerPhone: '0987654321' },
    ],
  };
}

const STATE_KEY = 'petcare-mock-branch';

function load(): State {
  try {
    const raw = localStorage.getItem(STATE_KEY);
    return raw ? (JSON.parse(raw) as State) : seed();
  } catch {
    return seed();
  }
}

function save(s: State) {
  try {
    localStorage.setItem(STATE_KEY, JSON.stringify(s));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}

// ─── Quyền (BE BranchAccess) và phạm vi chi nhánh (BranchScope) ───

const STAFF_READ: readonly Role[] = ['ADMIN', 'SUPER_MANAGER', 'BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER'];
const QUOTA_READ: readonly Role[] = ['BRANCH_MANAGER', 'RECEPTIONIST'];
const SM: readonly Role[] = ['SUPER_MANAGER'];
const BM: readonly Role[] = ['BRANCH_MANAGER'];
/** Phạm vi cả chuỗi; các role khác chỉ chi nhánh của mình. */
const CHAIN: readonly Role[] = ['ADMIN', 'SUPER_MANAGER'];

const allow = (roles: readonly Role[], h: MockHandler): MockHandler => (req) =>
  roles.includes(req.account!.role) ? h(req) : denied();

const inScope = (req: MockRequest, branchId: number) =>
  CHAIN.includes(req.account!.role) || branchIdOf(req.account!.id) === branchId;

const malformed = () => fail('MALFORMED_REQUEST', 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu', 400);
const badParam = (name: string) => fail('MALFORMED_REQUEST', `Tham số '${name}' sai kiểu dữ liệu`, 400);
const missingParam = (name: string) => fail('MALFORMED_REQUEST', `Thiếu tham số '${name}'`, 400);

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
const TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/;
const isDate = (v: unknown): v is string => typeof v === 'string' && DATE_RE.test(v) && !Number.isNaN(Date.parse(v));
const isTime = (v: unknown): v is string => typeof v === 'string' && TIME_RE.test(v);
const isInt = (v: unknown): v is number => typeof v === 'number' && Number.isInteger(v);
const isGroup = (v: unknown): v is QuotaGroup | 'BOARDING' => v === 'MEDICAL' || v === 'GROOMING' || v === 'BOARDING';
const bodyOf = (b: unknown) => (b ?? {}) as Record<string, unknown>;
const byName = (a: { name: string }, b: { name: string }) => a.name.localeCompare(b.name, 'vi');

/** 404 chi nhánh → phạm vi → xử lý; giống thứ tự của BE. */
function onBranch(req: MockRequest, handle: (s: State, branch: Branch) => MockReply): MockReply {
  if (!/^\d+$/.test(req.params.branchId)) return badParam('branchId');
  const s = load();
  const branch = s.branches.find((b) => b.branchId === Number(req.params.branchId));
  if (!branch) return notFound('Chi nhánh', req.params.branchId);
  if (!inScope(req, branch.branchId)) return scopeDenied();
  return handle(s, branch);
}

// ─── Lịch: giờ, ngày nghỉ, lịch bị ảnh hưởng ───

const holidayDates = (s: State, branchId: number) => s.holidays.filter((h) => h.branchId === branchId).map((h) => h.holidayDate);

const strip = <T extends { branchId: number }>({ branchId: _branchId, ...rest }: T) => rest;

/** Lịch bị ảnh hưởng khi áp bản giờ mới (BE ScheduleImpactFinder.forOpeningHours). */
function hoursImpact(s: State, branchId: number, version: OpeningHoursVersion): ScheduleImpact {
  const versions = [...(s.hours[branchId] ?? []).filter((v) => v.effectiveFrom !== version.effectiveFrom), version];
  const off = holidayDates(s, branchId);
  const closed = (date: string) => off.includes(date) || rangesOn(versions, date).length === 0;
  const from = version.effectiveFrom;
  return {
    appointments: s.appointments
      .filter((a) => a.branchId === branchId && a.slotDate >= from)
      .filter((a) => off.includes(a.slotDate) || !slotFits(rangesOn(versions, a.slotDate), a.slotStart))
      .map(strip),
    boardingBookings: s.boardings
      .filter((b) => b.branchId === branchId)
      .filter((b) => (b.checkInDate >= from && closed(b.checkInDate)) || (b.checkOutDate >= from && closed(b.checkOutDate)))
      .map(strip),
  };
}

/** Lịch bị ảnh hưởng của một ngày nghỉ (BE ScheduleImpactFinder.forHoliday). */
function holidayImpact(s: State, branchId: number, date: string): ScheduleImpact {
  return {
    appointments: s.appointments.filter((a) => a.branchId === branchId && a.slotDate === date).map(strip),
    boardingBookings: s.boardings
      .filter((b) => b.branchId === branchId && (b.checkInDate === date || b.checkOutDate === date))
      .map(strip),
  };
}

const EMPTY_IMPACT: ScheduleImpact = { appointments: [], boardingBookings: [] };

/** Thay listener của BranchClinicCancellationEvent: lịch bị ảnh hưởng chuyển CANCELLED. */
function cancel(s: State, impact: ScheduleImpact) {
  const appointmentIds = impact.appointments.map((a) => a.appointmentId);
  const bookingIds = impact.boardingBookings.map((b) => b.bookingId);
  s.appointments = s.appointments.filter((a) => !appointmentIds.includes(a.appointmentId));
  s.boardings = s.boardings.filter((b) => !bookingIds.includes(b.bookingId));
}

// ─── Chi nhánh (#1–5) ───

const decimals = (v: number) => (String(v).split('.')[1] ?? '').length;
const intDigits = (v: number) => String(Math.trunc(Math.abs(v))).length;

function coordinateChecks(field: string, v: unknown, max: number, maxInt: number, label: string, required: boolean): Check[] {
  const n = typeof v === 'number' ? v : null;
  return [
    [field, `Thiếu ${label.toLowerCase()}`, required && v == null],
    [field, `${label} phải từ -${max} đến ${max}`, n !== null && (n < -max || n > max)],
    [field, `${label} tối đa 6 chữ số thập phân`, n !== null && (decimals(n) > 6 || intDigits(n) > maxInt)],
  ];
}

function branchChecks(b: Record<string, unknown>, creating: boolean): Check[] {
  const blank = (v: unknown) => (creating ? !isText(v) : v !== undefined && v !== null && !isText(v));
  const tooLong = (v: unknown, max: number) => isText(v) && v.length > max;
  return [
    ['name', 'Tên chi nhánh không được để trống', blank(b.name)],
    ['name', 'Tên chi nhánh tối đa 150 ký tự', tooLong(b.name, 150)],
    ['address', 'Địa chỉ không được để trống', blank(b.address)],
    ['address', 'Địa chỉ tối đa 300 ký tự', tooLong(b.address, 300)],
    ['phone', 'Số điện thoại không được để trống', blank(b.phone)],
    ['phone', 'Số điện thoại tối đa 15 ký tự', tooLong(b.phone, 15)],
    ...coordinateChecks('latitude', b.latitude, 90, 2, 'Vĩ độ', creating),
    ...coordinateChecks('longitude', b.longitude, 180, 3, 'Kinh độ', creating),
  ];
}

const listBranches: MockHandler = (req) => {
  const status = req.query.status;
  if (status !== undefined && status !== 'DRAFT' && status !== 'ACTIVE') return badParam('status');
  return ok(
    load()
      .branches.filter((b) => inScope(req, b.branchId) && (!status || b.status === status))
      .sort(byName),
  );
};

const getBranch: MockHandler = (req) => onBranch(req, (_s, branch) => ok(branch));

const createBranch: MockHandler = ({ body }) => {
  const b = bodyOf(body);
  const bad = invalid(branchChecks(b, true));
  if (bad) return bad;
  const s = load();
  const name = (b.name as string).trim();
  if (s.branches.some((x) => x.name === name)) return ruleFail('BR-CN-01', 'Tên chi nhánh đã tồn tại');
  const branch: Branch = {
    branchId: ++s.seq,
    name,
    address: (b.address as string).trim(),
    phone: (b.phone as string).trim(),
    latitude: b.latitude as number,
    longitude: b.longitude as number,
    status: 'DRAFT',
    acceptsAfterHoursEmergency: b.acceptsAfterHoursEmergency === true,
    activatedAt: null,
  };
  s.branches.push(branch);
  save(s);
  return ok(branch, 201, 'Đã tạo chi nhánh');
};

const updateBranch: MockHandler = (req) => {
  const b = bodyOf(req.body);
  const bad = invalid(branchChecks(b, false));
  if (bad) return bad;
  return onBranch(req, (s, branch) => {
    if (isText(b.name)) {
      const name = b.name.trim();
      if (s.branches.some((x) => x.branchId !== branch.branchId && x.name === name)) {
        return ruleFail('BR-CN-01', 'Tên chi nhánh đã tồn tại');
      }
      branch.name = name;
    }
    if (isText(b.address)) branch.address = b.address.trim();
    if (isText(b.phone)) branch.phone = b.phone.trim();
    if (typeof b.latitude === 'number') branch.latitude = b.latitude;
    if (typeof b.longitude === 'number') branch.longitude = b.longitude;
    if (typeof b.acceptsAfterHoursEmergency === 'boolean') branch.acceptsAfterHoursEmergency = b.acceptsAfterHoursEmergency;
    save(s);
    return ok(branch, 200, 'Đã cập nhật chi nhánh');
  });
};

const activateBranch: MockHandler = (req) =>
  onBranch(req, (s, branch) => {
    if (branch.status === 'ACTIVE') {
      return fail('INVALID_STATE_TRANSITION', 'Không thể chuyển trạng thái BranchTransitionHandler từ ACTIVE sang ACTIVE', 409);
    }
    const noManager = countBranchManagers(branch.branchId) < 1;
    const noHours = !(s.hours[branch.branchId] ?? []).some((v) => v.days.some((d) => d.ranges.length > 0));
    const missing = [noManager && 'chưa có BRANCH_MANAGER', noHours && 'chưa cấu hình giờ mở cửa'].filter(Boolean);
    if (missing.length) return ruleFail(noManager ? 'BR-QT-04' : 'BR-CN-01', `Không kích hoạt được chi nhánh: ${missing.join(', ')}`);
    branch.status = 'ACTIVE';
    branch.activatedAt = new Date().toISOString();
    save(s);
    return ok(branch, 200, 'Đã kích hoạt chi nhánh');
  });

// ─── Giờ mở cửa (#6–8) ───

/** Bean Validation + đọc JSON của SetOpeningHoursRequest / OpeningHoursImpactRequest. */
function hoursBodyError(b: Record<string, unknown>): MockReply | null {
  if (b.effectiveFrom != null && !isDate(b.effectiveFrom)) return malformed();
  if (b.days != null && !Array.isArray(b.days)) return malformed();
  const days = (b.days ?? []) as unknown[];
  const checks: Check[] = [
    ['effectiveFrom', 'Thiếu ngày hiệu lực', b.effectiveFrom == null],
    ['days', 'Thiếu giờ mở cửa các ngày trong tuần', b.days == null],
  ];
  for (const [i, day] of days.entries()) {
    if (day == null) {
      checks.push([`days[${i}]`, 'Ngày không được để trống', true]);
      continue;
    }
    const d = day as Record<string, unknown>;
    if (d.dayOfWeek != null && !isInt(d.dayOfWeek)) return malformed();
    if (d.ranges != null && !Array.isArray(d.ranges)) return malformed();
    checks.push([`days[${i}].dayOfWeek`, 'Thiếu thứ trong tuần', d.dayOfWeek == null]);
    checks.push([`days[${i}].ranges`, 'Thiếu danh sách khoảng giờ', d.ranges == null]);
    for (const [j, range] of ((d.ranges ?? []) as unknown[]).entries()) {
      if (range == null) {
        checks.push([`days[${i}].ranges[${j}]`, 'Khoảng giờ không được để trống', true]);
        continue;
      }
      const r = range as Record<string, unknown>;
      if ((r.open != null && !isTime(r.open)) || (r.close != null && !isTime(r.close))) return malformed();
      checks.push([`days[${i}].ranges[${j}].open`, 'Thiếu giờ mở cửa', r.open == null]);
      checks.push([`days[${i}].ranges[${j}].close`, 'Thiếu giờ đóng cửa', r.close == null]);
    }
  }
  return invalid(checks);
}

/** BR-CN-02 theo thứ tự của BE OpeningHoursMapping; "Thứ N" là số ISO như câu báo của BE. */
function hoursShapeError(days: DayHours[]): MockReply | null {
  const seen = new Set<number>();
  for (const day of days) {
    if (day.dayOfWeek < 1 || day.dayOfWeek > 7) return ruleFail('BR-CN-02', 'Thứ trong tuần phải từ 1 (thứ Hai) đến 7 (Chủ nhật)');
    if (seen.has(day.dayOfWeek)) return ruleFail('BR-CN-02', `Thứ ${day.dayOfWeek} bị khai báo hai lần`);
    seen.add(day.dayOfWeek);
  }
  if (seen.size !== 7) return ruleFail('BR-CN-02', 'Phải khai báo giờ mở cửa đủ 7 ngày trong tuần');
  for (const day of days) {
    const n = day.dayOfWeek;
    if (day.ranges.length > 2) return ruleFail('BR-CN-02', `Thứ ${n} có quá 2 khoảng giờ mở cửa`);
    let previousClose = '';
    for (const r of [...day.ranges].sort((a, b) => a.open.localeCompare(b.open))) {
      if (r.open >= r.close) return ruleFail('BR-CN-02', `Thứ ${n}: giờ mở cửa phải trước giờ đóng cửa`);
      if (previousClose && r.open < previousClose) return ruleFail('BR-CN-02', `Thứ ${n}: các khoảng giờ mở cửa không được chồng nhau`);
      previousClose = r.close;
    }
  }
  return null;
}

const normalize = (effectiveFrom: string, days: DayHours[]): OpeningHoursVersion => ({
  effectiveFrom,
  days: [...days]
    .sort((a, b) => a.dayOfWeek - b.dayOfWeek)
    .map((d) => ({ dayOfWeek: d.dayOfWeek, ranges: [...d.ranges].sort((a, b) => a.open.localeCompare(b.open)).map(({ open, close }) => ({ open, close })) })),
});

const listHours: MockHandler = (req) =>
  onBranch(req, (s, branch) => {
    const versions = [...(s.hours[branch.branchId] ?? [])].sort((a, b) => a.effectiveFrom.localeCompare(b.effectiveFrom));
    const today = localIsoDate();
    const current = [...versions].reverse().find((v) => v.effectiveFrom <= today);
    return ok(current ? versions.filter((v) => v.effectiveFrom >= current.effectiveFrom) : versions);
  });

const previewHours: MockHandler = (req) => {
  const b = bodyOf(req.body);
  const bad = hoursBodyError(b);
  if (bad) return bad;
  return onBranch(req, (s, branch) => {
    const days = b.days as DayHours[];
    const shape = hoursShapeError(days);
    if (shape) return shape;
    if (branch.status === 'DRAFT') return ok(EMPTY_IMPACT);
    return ok(hoursImpact(s, branch.branchId, normalize(b.effectiveFrom as string, days)));
  });
};

const setHours: MockHandler = (req) => {
  const b = bodyOf(req.body);
  const bad = hoursBodyError(b);
  if (bad) return bad;
  return onBranch(req, (s, branch) => {
    const days = b.days as DayHours[];
    const shape = hoursShapeError(days);
    if (shape) return shape;
    const effectiveFrom = b.effectiveFrom as string;
    const today = localIsoDate();
    if (branch.status === 'DRAFT' && effectiveFrom < today) return ruleFail('BR-CN-04', 'Ngày hiệu lực không được ở quá khứ');
    if (branch.status === 'ACTIVE' && effectiveFrom < addDays(today, 1)) {
      return ruleFail('BR-CN-04', 'Ngày hiệu lực của giờ mở cửa mới sớm nhất là ngày mai');
    }
    const version = normalize(effectiveFrom, days);
    const impact = branch.status === 'DRAFT' ? EMPTY_IMPACT : hoursImpact(s, branch.branchId, version);
    if (!isEmptyImpact(impact) && b.cancelAffected !== true) {
      return ruleFail(
        'BR-CN-04',
        `Giờ mở cửa mới làm ${impact.appointments.length} lịch hẹn và ${impact.boardingBookings.length} đặt chỗ lưu trú rơi ra ngoài giờ mở cửa; xem danh sách và chọn hủy hàng loạt để tiếp tục`,
      );
    }
    s.hours[branch.branchId] = [...(s.hours[branch.branchId] ?? []).filter((v) => v.effectiveFrom !== effectiveFrom), version];
    cancel(s, impact);
    save(s);
    return ok(version, 200, 'Đã cập nhật giờ mở cửa');
  });
};

// ─── Ngày nghỉ (#9–12) ───

const listHolidays: MockHandler = (req) => {
  const { from, to } = req.query;
  if (from !== undefined && !isDate(from)) return badParam('from');
  if (to !== undefined && !isDate(to)) return badParam('to');
  return onBranch(req, (s, branch) =>
    ok(
      s.holidays
        .filter((h) => h.branchId === branch.branchId && (!from || h.holidayDate >= from) && (!to || h.holidayDate <= to))
        .sort((a, b) => a.holidayDate.localeCompare(b.holidayDate))
        .map(strip),
    ),
  );
};

function holidayBodyError(b: Record<string, unknown>): MockReply | null {
  if (b.holidayDate != null && !isDate(b.holidayDate)) return malformed();
  return invalid([
    ['holidayDate', 'Thiếu ngày nghỉ', b.holidayDate == null],
    ['reason', 'Lý do tối đa 200 ký tự', typeof b.reason === 'string' && b.reason.length > 200],
  ]);
}

const previewHoliday: MockHandler = (req) => {
  const b = bodyOf(req.body);
  const bad = holidayBodyError(b);
  if (bad) return bad;
  return onBranch(req, (s, branch) =>
    ok(branch.status === 'DRAFT' ? EMPTY_IMPACT : holidayImpact(s, branch.branchId, b.holidayDate as string)),
  );
};

const addHoliday: MockHandler = (req) => {
  const b = bodyOf(req.body);
  const bad = holidayBodyError(b);
  if (bad) return bad;
  return onBranch(req, (s, branch) => {
    const date = b.holidayDate as string;
    if (date < localIsoDate()) return ruleFail('BR-CN-03', 'Không thêm được ngày nghỉ trong quá khứ');
    if (holidayDates(s, branch.branchId).includes(date)) return ruleFail('BR-CN-03', `Ngày ${date} đã là ngày nghỉ của chi nhánh`);
    const impact = branch.status === 'DRAFT' ? EMPTY_IMPACT : holidayImpact(s, branch.branchId, date);
    if (!isEmptyImpact(impact) && b.cancelAffected !== true) {
      return ruleFail(
        'BR-LH-10',
        `Ngày ${date} còn ${impact.appointments.length} lịch hẹn và ${impact.boardingBookings.length} đặt chỗ lưu trú; xem danh sách và chọn hủy hàng loạt để tiếp tục`,
      );
    }
    const holiday = { holidayId: ++s.seq, branchId: branch.branchId, holidayDate: date, reason: (b.reason as string | undefined) ?? null };
    s.holidays.push(holiday);
    cancel(s, impact);
    save(s);
    return ok(strip(holiday), 201, 'Đã thêm ngày nghỉ');
  });
};

const deleteHoliday: MockHandler = (req) =>
  onBranch(req, (s, branch) => {
    const holiday = s.holidays.find((h) => h.branchId === branch.branchId && h.holidayId === Number(req.params.holidayId));
    if (!holiday) return notFound('Ngày nghỉ', req.params.holidayId);
    if (holiday.holidayDate <= localIsoDate()) return ruleFail('BR-CN-03', 'Chỉ xóa được ngày nghỉ trong tương lai');
    s.holidays = s.holidays.filter((h) => h !== holiday);
    save(s);
    return noContent();
  });

// ─── Dịch vụ tại chi nhánh (#13–14) ───

const listServices: MockHandler = (req) =>
  onBranch(req, (s, branch) =>
    ok(
      catalogServices()
        .filter((x) => x.isActive)
        .sort(byName)
        .map((x) => ({ serviceId: x.serviceId, name: x.name, group: x.group, enabled: s.services[branch.branchId]?.[x.serviceId] ?? false })),
    ),
  );

const setService: MockHandler = (req) => {
  const b = bodyOf(req.body);
  if (b.enabled != null && typeof b.enabled !== 'boolean') return malformed();
  const bad = invalid([['enabled', 'Thiếu cờ bật / tắt dịch vụ', b.enabled == null]]);
  if (bad) return bad;
  return onBranch(req, (s, branch) => {
    const service = catalogServices().find((x) => x.serviceId === Number(req.params.serviceId));
    if (!service) return notFound('Dịch vụ', req.params.serviceId);
    if (b.enabled === true && !service.isActive) {
      return ruleFail('BR-LH-01', 'Dịch vụ đã ngừng kinh doanh, không bật được tại chi nhánh');
    }
    s.services[branch.branchId] = { ...s.services[branch.branchId], [service.serviceId]: b.enabled as boolean };
    save(s);
    return ok({ serviceId: service.serviceId, name: service.name, group: service.group, enabled: b.enabled }, 200, 'Đã cập nhật dịch vụ tại chi nhánh');
  });
};

// ─── Quota (#15–19) ───

const MAX_QUOTA = 32767;
const groupError = (group: string) =>
  group === 'BOARDING' ? ruleFail('BR-LH-03', 'Quota chỉ có với nhóm Khám/Tiêm và Thẩm mỹ, nhóm Lưu trú không có khung giờ') : null;
const quotaError = (quota: number) => (quota < 0 || quota > MAX_QUOTA ? ruleFail('BR-LH-03', `Quota phải từ 0 đến ${MAX_QUOTA}`) : null);

const listQuotaDefaults: MockHandler = (req) =>
  onBranch(req, (s, branch) =>
    ok((['MEDICAL', 'GROOMING'] as const).map((g) => ({ serviceGroup: g, defaultQuota: s.quotaDefaults[branch.branchId]?.[g] ?? null }))),
  );

const setQuotaDefault: MockHandler = (req) => {
  const group = req.params.serviceGroup;
  if (!isGroup(group)) return badParam('serviceGroup');
  const b = bodyOf(req.body);
  if (b.defaultQuota != null && !isInt(b.defaultQuota)) return malformed();
  const bad = invalid([['defaultQuota', 'Thiếu quota mặc định', b.defaultQuota == null]]);
  if (bad) return bad;
  return onBranch(req, (s, branch) => {
    const quota = b.defaultQuota as number;
    const refused = groupError(group) ?? quotaError(quota);
    if (refused) return refused;
    s.quotaDefaults[branch.branchId] = { ...s.quotaDefaults[branch.branchId], [group]: quota };
    save(s);
    return ok({ serviceGroup: group, defaultQuota: quota }, 200, 'Đã cập nhật quota mặc định');
  });
};

const listSlotQuotas: MockHandler = (req) => {
  const { from, to, serviceGroup } = req.query;
  if (from === undefined) return missingParam('from');
  if (!isDate(from)) return badParam('from');
  if (to === undefined) return missingParam('to');
  if (!isDate(to)) return badParam('to');
  if (serviceGroup !== undefined && !isGroup(serviceGroup)) return badParam('serviceGroup');
  return onBranch(req, (s, branch) => {
    if (from > to) return ruleFail('BR-LH-03', 'Ngày bắt đầu phải trước hoặc bằng ngày kết thúc');
    if (serviceGroup) {
      const refused = groupError(serviceGroup);
      if (refused) return refused;
    }
    return ok(
      s.slotQuotas
        .filter((q) => q.branchId === branch.branchId && q.slotDate >= from && q.slotDate <= to && (!serviceGroup || q.serviceGroup === serviceGroup))
        .sort((a, b) => a.slotDate.localeCompare(b.slotDate) || a.slotStart.localeCompare(b.slotStart) || a.serviceGroup.localeCompare(b.serviceGroup))
        .map(strip),
    );
  });
};

const setSlotQuota: MockHandler = (req) => {
  const b = bodyOf(req.body);
  if (b.serviceGroup != null && !isGroup(b.serviceGroup)) return malformed();
  if ((b.slotDate != null && !isDate(b.slotDate)) || (b.slotStart != null && !isTime(b.slotStart))) return malformed();
  if (b.quota != null && !isInt(b.quota)) return malformed();
  const bad = invalid([
    ['serviceGroup', 'Thiếu nhóm dịch vụ', b.serviceGroup == null],
    ['slotDate', 'Thiếu ngày của khung giờ', b.slotDate == null],
    ['slotStart', 'Thiếu giờ bắt đầu của khung', b.slotStart == null],
    ['quota', 'Thiếu quota', b.quota == null],
  ]);
  if (bad) return bad;
  return onBranch(req, (s, branch) => {
    const group = b.serviceGroup as QuotaGroup | 'BOARDING';
    const refused = groupError(group) ?? quotaError(b.quota as number);
    if (refused) return refused;
    const slotDate = b.slotDate as string;
    const slotStart = b.slotStart as string;
    if (!slotsOn(s.hours[branch.branchId] ?? [], holidayDates(s, branch.branchId), slotDate).includes(slotStart)) {
      return ruleFail(
        'BR-LH-02',
        `Khung ${slotStart} ngày ${slotDate} không phải khung đặt lịch của chi nhánh (ngoài giờ mở cửa, ngày nghỉ, hoặc không đúng bước 30 phút từ giờ mở cửa)`,
      );
    }
    const existing = s.slotQuotas.find(
      (q) => q.branchId === branch.branchId && q.serviceGroup === group && q.slotDate === slotDate && q.slotStart === slotStart,
    );
    const quota = existing ?? { slotQuotaId: ++s.seq, branchId: branch.branchId, serviceGroup: group as QuotaGroup, slotDate, slotStart, quota: 0 };
    quota.quota = b.quota as number;
    if (!existing) s.slotQuotas.push(quota);
    save(s);
    return ok(strip(quota), 200, 'Đã cập nhật quota của khung giờ');
  });
};

const deleteSlotQuota: MockHandler = (req) =>
  onBranch(req, (s, branch) => {
    const quota = s.slotQuotas.find((q) => q.branchId === branch.branchId && q.slotQuotaId === Number(req.params.slotQuotaId));
    if (!quota) return notFound('Quota khung giờ', req.params.slotQuotaId);
    s.slotQuotas = s.slotQuotas.filter((q) => q !== quota);
    save(s);
    return noContent();
  });

export const branchRoutes: Record<string, MockHandler> = {
  'GET /api/branches': allow(STAFF_READ, listBranches),
  'POST /api/branches': allow(SM, createBranch),
  'GET /api/branches/:branchId': allow(STAFF_READ, getBranch),
  'PATCH /api/branches/:branchId': allow(SM, updateBranch),
  'POST /api/branches/:branchId/activate': allow(SM, activateBranch),
  'GET /api/branches/:branchId/opening-hours': allow(STAFF_READ, listHours),
  'POST /api/branches/:branchId/opening-hours/impact': allow(BM, previewHours),
  'PUT /api/branches/:branchId/opening-hours': allow(BM, setHours),
  'GET /api/branches/:branchId/holidays': allow(STAFF_READ, listHolidays),
  'POST /api/branches/:branchId/holidays/impact': allow(BM, previewHoliday),
  'POST /api/branches/:branchId/holidays': allow(BM, addHoliday),
  'DELETE /api/branches/:branchId/holidays/:holidayId': allow(BM, deleteHoliday),
  'GET /api/branches/:branchId/services': allow(STAFF_READ, listServices),
  'PUT /api/branches/:branchId/services/:serviceId': allow(BM, setService),
  'GET /api/branches/:branchId/quota-defaults': allow(QUOTA_READ, listQuotaDefaults),
  'PUT /api/branches/:branchId/quota-defaults/:serviceGroup': allow(BM, setQuotaDefault),
  'GET /api/branches/:branchId/slot-quotas': allow(QUOTA_READ, listSlotQuotas),
  'PUT /api/branches/:branchId/slot-quotas': allow(BM, setSlotQuota),
  'DELETE /api/branches/:branchId/slot-quotas/:slotQuotaId': allow(BM, deleteSlotQuota),
};
