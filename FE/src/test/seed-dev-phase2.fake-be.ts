// BE giả tối thiểu cho test seed giai đoạn 2 (scripts/seed-dev/seed-phase2.ts): adapter axios trả lời các endpoint mà script gọi, có trạng thái
// trong bộ nhớ và vài luật có thật của BE đủ để bắt lỗi thứ tự gọi / sai vai trò (gán trước khi gọi, thu tiền cần ca mở của chính người thu,
// giao thú cần Order lưu trú đã PAID, thu gộp cùng khách). KHÔNG phải BE thật: không có giờ mở cửa, hạn mức, tồn kho... Lớp mock của FE
// (shared/api/mock) không dùng ở đây vì dữ liệu mẫu của nó va vào dấu hiệu "đã có dữ liệu" của script và hình dạng state của 10 mock khác nhau.
import type { AxiosInstance, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { DEV_PASSWORD, SEED } from '../../scripts/seed-dev/data';
import { PHASE2, STAFF_KEYS } from '../../scripts/seed-dev/data-phase2';

type Json = Record<string, unknown>;
interface Ctx {
  who: string | null;
  body: Json;
  params: string[];
  query: Record<string, unknown>;
}
type Reply = { status: number; data?: unknown; message?: string };
type Handler = (ctx: Ctx) => Reply;

export interface FakeCall {
  method: string;
  path: string;
  who: string | null;
  body?: unknown;
}

interface FakePet {
  petId: number;
  name: string;
  species: string;
  customerId: number;
}
interface FakeOrder {
  orderId: number;
  code: string;
  customerId: number;
  source: 'VISIT' | 'RETAIL' | 'BOARDING';
  status: 'OPEN' | 'PENDING' | 'PAID';
  lines: { description: string; amount: number }[];
}
interface FakeVisit {
  visitId: number;
  code: string;
  petId: number;
  orderId: number;
  serviceId: number;
  status: 'WAITING' | 'IN_PROGRESS' | 'COMPLETED';
  assigneeEmail: string | null;
  diagnosis?: string;
  vaccinations: number;
}
interface FakeBooking {
  bookingId: number;
  code: string;
  petId: number;
  kennelTypeId: number;
  kennelId?: number;
  status: 'BOOKED' | 'CHECKED_IN' | 'CHECKED_OUT';
  checkInDate: string;
  orderId?: number;
}
interface FakeShift {
  shiftId: number;
  cashierEmail: string;
  status: 'OPEN' | 'CLOSED' | 'RECONCILED';
  cash: number;
}

const ROLE_OF_KEY: Record<string, string> = {
  'ha.nguyen': 'SUPER_MANAGER',
  'hung.tran': 'BRANCH_MANAGER',
  'mai.le': 'RECEPTIONIST',
  'anh.pham': 'RECEPTIONIST',
  'khoi.vo': 'VET',
  'hang.dang': 'VET',
  'tai.bui': 'CARETAKER',
  'lan.ngo': 'CARETAKER',
};
const slotTimes = [...'8,9,10,11'.split(','), ...'13,14,15,16,17,18'.split(',')].flatMap((h) => [`${h.padStart(2, '0')}:00`, `${h.padStart(2, '0')}:30`]);

export interface FakeBeOptions {
  /** Đường dẫn (không query) mà mọi lời gọi tới sẽ trả 500 để thử lỗi giữa chừng, ví dụ `POST /api/payments`. */
  failOn?: string;
  /** Ngày không có khung trống (ngày nghỉ). */
  closedDates?: string[];
  /** Ngày nghỉ của chi nhánh (trả ở /holidays). */
  holidays?: string[];
}

export function installFakeBe(client: AxiosInstance, options: FakeBeOptions = {}) {
  const calls: FakeCall[] = [];
  let seq = 100;
  const next = () => ++seq;

  const services = SEED.services.map((s, i) => ({ serviceId: i + 1, ...s, medicalType: 'medicalType' in s ? s.medicalType : null, kennelType: 'kennelType' in s ? s.kennelType : null, isActive: true }));
  const products = SEED.products.map((p, i) => ({ productId: i + 1, ...p, isActive: true }));
  const vaccineTypes = SEED.vaccineTypes.map((t, i) => ({ vaccineTypeId: i + 1, ...t }));
  const protocols: { protocolId: number; species: string; vaccineTypeId: number; doseNumber: number; requiredForBoarding: boolean }[] = SEED.protocols.map((p, i) => ({
    protocolId: i + 1,
    species: p.species as string,
    vaccineTypeId: vaccineTypes.find((t) => t.key === p.vaccineTypeKey)?.vaccineTypeId ?? 0,
    doseNumber: p.doseNumber,
    requiredForBoarding: p.requiredForBoarding,
  }));
  const customers: { customerId: number; fullName: string; phone: string; email?: string; accountId?: number; pets: FakePet[] }[] = [];
  const addCustomer = (fullName: string, phone: string, email?: string) => {
    const c = { customerId: next(), fullName, phone, email, pets: [] as FakePet[] };
    customers.push(c);
    return c;
  };
  for (const c of SEED.customers) {
    const created = addCustomer(c.fullName, c.phone, 'email' in c ? c.email : undefined);
    for (const p of c.pets) created.pets.push({ petId: next(), name: p.name, species: p.species, customerId: created.customerId });
  }
  const allPets = () => customers.flatMap((c) => c.pets);
  const petById = (id: unknown) => allPets().find((p) => p.petId === id);

  const kennels: { kennelId: number; code: string; kennelTypeId: number; status: string }[] = [];
  const categories: { categoryId: number; name: string }[] = [];
  const articles: { articleId: number; status: string; title: string }[] = [];
  const pages: Record<string, unknown> = {};
  const appointments: { appointmentId: number; code: string; petId: number; slotDate: string; slotStart: string }[] = [];
  const bookings: FakeBooking[] = [];
  const visits: FakeVisit[] = [];
  const orders: FakeOrder[] = [];
  const payments: { code: string; amount: number; method: string; orderIds: number[] }[] = [];
  const shifts: FakeShift[] = [];
  const careLogs: { careLogId: number; bookingId: number; abnormal: boolean; addenda: number }[] = [];
  const accounts = new Map<string, { id: number; password: string; role: string; verified: boolean }>();
  const feedbacks: { feedbackId: number; email: string; status: string }[] = [];
  STAFF_KEYS.forEach((key, i) => accounts.set(`${key}@petcare.local`, { id: i + 2, password: DEV_PASSWORD, role: ROLE_OF_KEY[key], verified: true }));

  const ok = (data: unknown): Reply => ({ status: 200, data });
  const created = (data: unknown): Reply => ({ status: 201, data });
  const bad = (message: string, status = 400): Reply => ({ status, message });
  const page = (items: unknown[]): Reply => ok({ content: items, page: 0, size: 20, totalElements: items.length, totalPages: 1 });
  const roleOf = (who: string | null) => (who ? (accounts.get(who)?.role ?? null) : null);
  const only = (who: string | null, ...roles: string[]): Reply | null => (roles.includes(roleOf(who) ?? '') ? null : bad('Không đủ quyền', 403));
  const orderTotal = (o: FakeOrder) => o.lines.reduce((sum, l) => sum + l.amount, 0);
  const orderById = (id: unknown) => orders.find((o) => o.orderId === id);
  const orderBody = (o: FakeOrder) => ({ orderId: o.orderId, code: o.code, status: o.status, totalAmount: orderTotal(o) });
  const visitById = (id: string) => visits.find((v) => v.visitId === Number(id));
  const serviceById = (id: unknown) => services.find((s) => s.serviceId === id);
  const productById = (id: unknown) => products.find((p) => p.productId === id);
  const bookingById = (id: string) => bookings.find((b) => b.bookingId === Number(id));
  const code = (prefix: string, n: number) => `${prefix}-2610-${String(n).padStart(5, '0')}`;
  const customerOfOrder = (o: FakeOrder) => o.customerId;
  const customerOfPet = (petId: number) => petById(petId)?.customerId ?? 0;
  const nightlyPrice = (b: FakeBooking) => serviceById(b.kennelTypeId)?.price ?? 0;

  const routes: [string, string, Handler][] = [];
  const route = (method: string, pattern: string, handler: Handler) => routes.push([method, pattern, handler]);

  // identity, branch, catalog
  route('POST', '/api/auth/login', ({ body }) => {
    const account = accounts.get(String(body.email));
    if (!account || account.password !== body.password || !account.verified) return bad('Email hoặc mật khẩu không đúng', 401);
    return ok({ accessToken: `t:${String(body.email)}`, account: { id: account.id, email: body.email, role: account.role, mustChangePassword: false } });
  });
  route('GET', '/api/me', ({ who }) => ok({ staffProfile: { branchId: roleOf(who) === 'SUPER_MANAGER' ? null : 1 } }));
  route('GET', '/api/branches/1/opening-hours', () => ok([{ effectiveFrom: '2026-10-24', days: SEED.branch.openingHours }]));
  route('GET', '/api/branches/1/holidays', () => ok((options.holidays ?? []).map((holidayDate) => ({ holidayDate }))));
  route('GET', '/api/services', () => ok(services));
  route('GET', '/api/products', () => page(products));
  route('GET', '/api/vaccine-types', () => ok(vaccineTypes));
  route('GET', '/api/vaccination-protocols', () => ok(protocols));
  route('POST', '/api/vaccination-protocols', ({ who, body }) => {
    const denied = only(who, 'SUPER_MANAGER');
    if (denied) return denied;
    if (protocols.some((p) => p.species === body.species && p.vaccineTypeId === body.vaccineTypeId && p.doseNumber === body.doseNumber)) return bad('Phác đồ đã có mũi này');
    protocols.push({ protocolId: next(), species: String(body.species), vaccineTypeId: Number(body.vaccineTypeId), doseNumber: Number(body.doseNumber), requiredForBoarding: Boolean(body.requiredForBoarding) });
    return created({});
  });

  // customers
  route('GET', '/api/customers', ({ query }) => {
    const found = customers.filter((c) => (!query.phone || c.phone === query.phone) && (!query.name || c.fullName === query.name));
    return page(found.map((c) => ({ customerId: c.customerId, fullName: c.fullName, phone: c.phone })));
  });
  route('GET', '/api/customers/:id', ({ params }) => {
    const c = customers.find((x) => x.customerId === Number(params[0]));
    return c ? ok({ customer: { customerId: c.customerId, fullName: c.fullName }, pets: c.pets }) : bad('Không có khách', 404);
  });
  route('POST', '/api/customers', ({ who, body }) => {
    const denied = only(who, 'RECEPTIONIST');
    if (denied) return denied;
    const c = addCustomer(String(body.fullName), String(body.phone), body.email === undefined ? undefined : String(body.email));
    return created({ customer: { customerId: c.customerId }, duplicatePhoneProfiles: [] });
  });
  route('POST', '/api/customers/:id/pets', ({ params, body }) => {
    const c = customers.find((x) => x.customerId === Number(params[0]));
    if (!c) return bad('Không có khách', 404);
    const pet = { petId: next(), name: String(body.name), species: String(body.species), customerId: c.customerId };
    c.pets.push(pet);
    return created({ pet: { petId: pet.petId } });
  });

  // kennels, content
  route('GET', '/api/kennels', () => ok(kennels));
  route('POST', '/api/kennels', ({ who, body }) => {
    const denied = only(who, 'BRANCH_MANAGER');
    if (denied) return denied;
    if (!serviceById(body.kennelTypeId) || serviceById(body.kennelTypeId)?.group !== 'BOARDING') return bad('Loại chuồng không hợp lệ');
    const k = { kennelId: next(), code: String(body.code), kennelTypeId: Number(body.kennelTypeId), status: 'AVAILABLE' };
    kennels.push(k);
    return created(k);
  });
  route('POST', '/api/kennels/:id/start-maintenance', ({ params }) => {
    const k = kennels.find((x) => x.kennelId === Number(params[0]));
    if (!k || k.status !== 'AVAILABLE') return bad('Chuồng không AVAILABLE', 409);
    k.status = 'MAINTENANCE';
    return ok({ kennel: k, shortageBookings: [] });
  });
  route('GET', '/api/article-categories', () => ok(categories));
  route('POST', '/api/article-categories', ({ who, body }) => {
    const denied = only(who, 'SUPER_MANAGER');
    if (denied) return denied;
    const c = { categoryId: next(), name: String(body.name) };
    categories.push(c);
    return created(c);
  });
  route('GET', '/api/articles', () => page(articles));
  route('POST', '/api/articles', ({ who, body }) => {
    const denied = only(who, 'SUPER_MANAGER');
    if (denied) return denied;
    if (!categories.some((c) => c.categoryId === body.categoryId)) return bad('Chuyên mục không có', 404);
    const a = { articleId: next(), status: 'DRAFT', title: String(body.title) };
    articles.push(a);
    return created(a);
  });
  route('POST', '/api/articles/:id/publish', ({ params }) => {
    const a = articles.find((x) => x.articleId === Number(params[0]));
    if (!a) return bad('Không có bài', 404);
    a.status = 'PUBLISHED';
    return ok(a);
  });
  route('PUT', '/api/page-contents/:key', ({ who, params, body }) => {
    const denied = only(who, 'SUPER_MANAGER');
    if (denied) return denied;
    pages[params[0]] = body.content;
    return ok({ key: params[0] });
  });

  // appointments, boarding bookings
  route('GET', '/api/branches/1/available-slots', ({ query }) =>
    ok(options.closedDates?.includes(String(query.date)) ? [] : slotTimes.map((slotStart) => ({ slotStart, slotEnd: slotStart, quota: 3, booked: 0, status: 'AVAILABLE' }))),
  );
  route('GET', '/api/appointments', () => page(appointments));
  route('POST', '/api/appointments', ({ who, body }) => {
    const denied = only(who, 'RECEPTIONIST');
    if (denied) return denied;
    const a = { appointmentId: next(), code: `LH-${appointments.length + 1}`, petId: Number(body.petId), slotDate: String(body.slotDate), slotStart: String(body.slotStart) };
    appointments.push(a);
    return created(a);
  });
  const hasVaccine = (petId: number) => visits.some((v) => v.petId === petId && v.vaccinations > 0);
  const vaccineGaps = (petId: number) => (petById(petId)?.species === 'DOG' && !hasVaccine(petId) ? [{ vaccineTypeName: '5 bệnh' }] : []);
  route('GET', '/api/boarding-bookings', ({ query }) => page(bookings.filter((b) => !query.status || b.status === query.status).map((b) => ({ bookingId: b.bookingId, status: b.status }))));
  route('POST', '/api/boarding-bookings', ({ who, body }) => {
    const denied = only(who, 'RECEPTIONIST');
    if (denied) return denied;
    if (!kennels.some((k) => k.kennelTypeId === body.kennelTypeId)) return bad('BR-LT-03: hết chỗ');
    const b: FakeBooking = {
      bookingId: next(),
      code: code('LT', bookings.length + 1),
      petId: Number(body.petId),
      kennelTypeId: Number(body.kennelTypeId),
      status: 'BOOKED',
      checkInDate: String(body.checkInDate),
    };
    bookings.push(b);
    return created({ booking: { bookingId: b.bookingId, code: b.code, status: b.status }, vaccineWarnings: vaccineGaps(b.petId) });
  });
  route('GET', '/api/boarding-bookings/:id/check-in-readiness', ({ params }) => {
    const b = bookingById(params[0]);
    if (!b) return bad('Không có đặt chỗ', 404);
    const free = kennels.filter((k) => k.kennelTypeId === b.kennelTypeId && k.status === 'AVAILABLE');
    const gaps = vaccineGaps(b.petId);
    return ok({ canCheckIn: gaps.length === 0 && free.length > 0, vaccineGaps: gaps, availableKennels: free, withinOpeningHours: true });
  });
  route('POST', '/api/boarding-bookings/:id/check-in', ({ params, body }) => {
    const b = bookingById(params[0]);
    const k = kennels.find((x) => x.kennelId === body.kennelId);
    if (!b || b.status !== 'BOOKED') return bad('Đặt chỗ không còn BOOKED', 409);
    if (!k || k.status !== 'AVAILABLE' || k.kennelTypeId !== b.kennelTypeId) return bad('BR-LT-08: chuồng không hợp lệ');
    if (typeof body.weightKg !== 'number' || !body.healthCondition || !body.emergencyPhone) return bad('Thiếu thông tin nhận thú');
    k.status = 'OCCUPIED';
    b.status = 'CHECKED_IN';
    b.kennelId = k.kennelId;
    return ok({ bookingId: b.bookingId });
  });
  route('POST', '/api/boarding-bookings/:id/start-checkout', ({ who, params }) => {
    const denied = only(who, 'RECEPTIONIST');
    if (denied) return denied;
    const b = bookingById(params[0]);
    if (!b || b.status !== 'CHECKED_IN') return bad('Thú không còn trong chuồng', 409);
    const order: FakeOrder = { orderId: next(), code: code('DH', orders.length + 1), customerId: customerOfPet(b.petId), source: 'BOARDING', status: 'PENDING', lines: [{ description: 'Lưu trú', amount: nightlyPrice(b) }] };
    orders.push(order);
    b.orderId = order.orderId;
    return ok({ booking: { bookingId: b.bookingId }, orderId: order.orderId, nights: 1, amount: orderTotal(order) });
  });
  route('POST', '/api/boarding-bookings/:id/hand-over', ({ who, params }) => {
    const denied = only(who, 'RECEPTIONIST');
    if (denied) return denied;
    const b = bookingById(params[0]);
    if (!b || b.status !== 'CHECKED_IN') return bad('Thú không còn trong chuồng', 409);
    if (orderById(b.orderId)?.status !== 'PAID') return bad('BR-LT-09: Order lưu trú chưa PAID');
    b.status = 'CHECKED_OUT';
    const k = kennels.find((x) => x.kennelId === b.kennelId);
    if (k) k.status = 'AVAILABLE';
    return ok({ bookingId: b.bookingId });
  });
  route('POST', '/api/boarding-bookings/:id/care-logs', ({ who, params, body }) => {
    const denied = only(who, 'CARETAKER', 'VET');
    if (denied) return denied;
    const b = bookingById(params[0]);
    if (!b || b.status !== 'CHECKED_IN') return bad('Thú không đang lưu trú', 409);
    if (!Array.isArray(body.photoUrls)) return bad('Vui lòng gửi danh sách ảnh (có thể rỗng)');
    const log = { careLogId: next(), bookingId: b.bookingId, abnormal: body.isAbnormal === true, addenda: 0 };
    careLogs.push(log);
    return created({ careLogId: log.careLogId });
  });
  route('POST', '/api/care-logs/:id/addenda', ({ params }) => {
    const log = careLogs.find((c) => c.careLogId === Number(params[0]));
    if (!log) return bad('Không có nhật ký', 404);
    log.addenda++;
    return created({});
  });

  // visits
  route('POST', '/api/visits', ({ who, body }) => {
    const denied = only(who, 'RECEPTIONIST');
    if (denied) return denied;
    const pet = petById(body.petId);
    const service = serviceById(body.serviceId);
    if (!pet || !service) return bad('BR-TN-01: thiếu thú hoặc dịch vụ');
    const order: FakeOrder = { orderId: next(), code: code('DH', orders.length + 1), customerId: pet.customerId, source: 'VISIT', status: 'OPEN', lines: [{ description: service.name, amount: service.price }] };
    orders.push(order);
    const v: FakeVisit = { visitId: next(), code: code('TN', visits.length + 1), petId: pet.petId, orderId: order.orderId, serviceId: service.serviceId, status: 'WAITING', assigneeEmail: null, vaccinations: 0 };
    visits.push(v);
    return created({ visitId: v.visitId, code: v.code, orderId: v.orderId, status: v.status });
  });
  route('GET', '/api/visits', () => ok(visits));
  route('POST', '/api/visits/:id/assign', ({ who, params, body }) => {
    const denied = only(who, 'RECEPTIONIST');
    if (denied) return denied;
    const v = visitById(params[0]);
    const email = [...accounts.entries()].find(([, a]) => a.id === body.assigneeId)?.[0];
    const wanted = serviceById(v?.serviceId)?.group === 'GROOMING' ? 'CARETAKER' : 'VET';
    if (!v || !email || roleOf(email) !== wanted) return bad('BR-TN-05: sai chức vụ');
    v.assigneeEmail = email;
    return ok({ visit: { visitId: v.visitId }, assigneeOffline: false });
  });
  const mine = (ctx: Ctx, needStatus: FakeVisit['status'] = 'IN_PROGRESS'): FakeVisit | Reply => {
    const v = visitById(ctx.params[0]);
    if (!v) return bad('Không có lượt', 404);
    if (v.assigneeEmail !== ctx.who) return bad('Không phải người phụ trách', 403);
    if (v.status !== needStatus) return bad('Lượt không đúng trạng thái', 409);
    return v;
  };
  const isReply = (x: FakeVisit | Reply): x is Reply => 'status' in x && !('visitId' in x);
  route('POST', '/api/visits/:id/call', (ctx) => {
    const v = mine(ctx, 'WAITING');
    if (isReply(v)) return v;
    v.status = 'IN_PROGRESS';
    return ok({ visitId: v.visitId });
  });
  route('POST', '/api/visits/:id/weight', (ctx) => {
    const v = mine(ctx);
    if (isReply(v)) return v;
    return serviceById(v.serviceId)?.group === 'GROOMING' ? bad('Lượt thẩm mỹ không có bệnh án', 404) : created({});
  });
  route('PUT', '/api/visits/:id/medical-record', (ctx) => {
    const v = mine(ctx);
    if (isReply(v)) return v;
    v.diagnosis = String(ctx.body.diagnosis ?? '');
    return ok({});
  });
  route('GET', '/api/visits/:id/vaccination-options', (ctx) => {
    const v = mine(ctx);
    if (isReply(v)) return v;
    const species = petById(v.petId)?.species;
    const dose = protocols.filter((p) => p.species === species && p.vaccineTypeId === Number(ctx.query.vaccineTypeId));
    const type = vaccineTypes.find((t) => t.vaccineTypeId === Number(ctx.query.vaccineTypeId));
    return ok({
      protocol: dose.map((p) => ({ protocolId: p.protocolId, doseNumber: p.doseNumber })),
      suggestedProtocolId: dose[0]?.protocolId ?? null,
      products: products.filter((p) => p.productType === 'VACCINE' && 'vaccineTypeKey' in p && p.vaccineTypeKey === type?.key).map((p) => ({ productId: p.productId, availableQuantity: 10 })),
    });
  });
  route('POST', '/api/visits/:id/vaccinations', (ctx) => {
    const v = mine(ctx);
    if (isReply(v)) return v;
    if (!protocols.some((p) => p.protocolId === ctx.body.protocolId)) return bad('BR-KB-04: mũi không thuộc phác đồ');
    const product = productById(ctx.body.productId);
    if (!product) return bad('BR-KB-04: sản phẩm không hợp lệ');
    v.vaccinations++;
    orderById(v.orderId)?.lines.push({ description: product.name, amount: product.price });
    return created({ vaccination: { doseNumber: 1, nextDueDate: '2026-11-20' }, warnings: [] });
  });
  route('POST', '/api/visits/:id/prescriptions', (ctx) => {
    const v = mine(ctx);
    if (isReply(v)) return v;
    const product = productById(ctx.body.productId);
    if (!product || !product.isPrescription || !ctx.body.dosageInstructions) return bad('BR-KB-03: thuốc không hợp lệ');
    orderById(v.orderId)?.lines.push({ description: product.name, amount: product.price * Number(ctx.body.quantity) });
    return created({ itemId: next() });
  });
  route('POST', '/api/visits/:id/complete', (ctx) => {
    const v = mine(ctx);
    if (isReply(v)) return v;
    const kind = serviceById(v.serviceId)?.medicalType;
    if (kind === 'EXAM' && !v.diagnosis) return bad('BR-KB-02: chưa có chẩn đoán');
    if (kind === 'VACCINE' && v.vaccinations === 0) return bad('BR-KB-02: chưa ghi mũi tiêm');
    v.status = 'COMPLETED';
    const order = orderById(v.orderId);
    if (order) order.status = 'PENDING';
    return ok({ visitId: v.visitId });
  });

  // orders, payments, shifts
  route('POST', '/api/orders', ({ who, body }) => {
    const denied = only(who, 'RECEPTIONIST');
    if (denied) return denied;
    const o: FakeOrder = { orderId: next(), code: code('DH', orders.length + 1), customerId: Number(body.customerId), source: 'RETAIL', status: 'OPEN', lines: [] };
    orders.push(o);
    return created(orderBody(o));
  });
  route('POST', '/api/orders/:id/lines', ({ params, body }) => {
    const o = orderById(Number(params[0]));
    const p = productById(body.productId);
    if (!o || o.status !== 'OPEN' || !p || p.isPrescription || body.lineType !== 'GOODS') return bad('Không thêm được dòng');
    o.lines.push({ description: p.name, amount: p.price * Number(body.quantity) });
    return created({ order: orderBody(o), lineId: next(), stockWarning: null });
  });
  route('POST', '/api/orders/:id/close', ({ params }) => {
    const o = orderById(Number(params[0]));
    if (!o || o.source !== 'RETAIL' || o.status !== 'OPEN') return bad('Không phải Order bán lẻ OPEN', 409);
    if (o.lines.length === 0) return bad('BR-BH-01: Order chưa có dòng nào');
    o.status = 'PENDING';
    return ok(orderBody(o));
  });
  route('GET', '/api/orders', ({ query }) => page(orders.filter((o) => !query.status || o.status === query.status).map(orderBody)));
  const resolveOrders = (ids: unknown): FakeOrder[] | Reply => {
    const list = Array.isArray(ids) ? ids.map((id) => orderById(id)) : [];
    if (list.length === 0 || list.some((o) => !o)) return bad('Order không có', 404);
    const found = list as FakeOrder[];
    if (found.some((o) => o.status !== 'PENDING')) return bad('BR-TG-01: có Order không PENDING', 409);
    if (new Set(found.map(customerOfOrder)).size > 1) return bad('BR-TG-03: Order khác khách');
    return found;
  };
  route('POST', '/api/payments/preview', ({ body }) => {
    const found = resolveOrders(body.orderIds);
    return Array.isArray(found) ? ok({ totalAmount: found.reduce((s, o) => s + orderTotal(o), 0), shortages: [] }) : found;
  });
  route('POST', '/api/payments', ({ who, body }) => {
    const denied = only(who, 'RECEPTIONIST');
    if (denied) return denied;
    const shift = shifts.find((s) => s.cashierEmail === who && s.status === 'OPEN');
    if (!shift) return bad('BR-TG-01: chưa mở ca');
    const found = resolveOrders(body.orderIds);
    if (!Array.isArray(found)) return found;
    const total = found.reduce((s, o) => s + orderTotal(o), 0);
    if (body.amount !== total) return bad('BR-TG-02: số tiền khác tổng');
    found.forEach((o) => (o.status = 'PAID'));
    const p = { code: code('PT', payments.length + 1), amount: total, method: String(body.method), orderIds: found.map((o) => o.orderId) };
    payments.push(p);
    if (p.method === 'CASH') shift.cash += total;
    return created({ paymentId: next(), code: p.code, amount: total });
  });
  route('GET', '/api/me/cashier-shift', ({ who }) => {
    const shift = shifts.find((s) => s.cashierEmail === who && s.status === 'OPEN');
    return ok(shift ? { open: true, shift: { shiftId: shift.shiftId, status: shift.status } } : { open: false });
  });
  route('POST', '/api/cashier-shifts', ({ who }) => {
    const denied = only(who, 'RECEPTIONIST');
    if (denied) return denied;
    if (shifts.some((s) => s.cashierEmail === who && s.status === 'OPEN')) return bad('Đã có ca OPEN', 409);
    const s: FakeShift = { shiftId: next(), cashierEmail: String(who), status: 'OPEN', cash: 0 };
    shifts.push(s);
    return created({ shiftId: s.shiftId, status: s.status });
  });
  route('GET', '/api/cashier-shifts', () => page(shifts));
  route('GET', '/api/cashier-shifts/:id', ({ params }) => {
    const s = shifts.find((x) => x.shiftId === Number(params[0]));
    return s ? ok({ shiftId: s.shiftId, status: s.status, expectedCash: null }) : bad('Không có ca', 404);
  });
  route('POST', '/api/cashier-shifts/:id/close', ({ who, params, body }) => {
    const s = shifts.find((x) => x.shiftId === Number(params[0]));
    if (!s || s.cashierEmail !== who || s.status !== 'OPEN') return bad('Không chốt được ca', 409);
    if (body.countedCash !== s.cash) return bad('Số tiền thực đếm lệch (fake yêu cầu khớp)');
    s.status = 'CLOSED';
    return ok({ shiftId: s.shiftId, status: s.status });
  });
  route('POST', '/api/cashier-shifts/:id/reconcile', ({ who, params, body }) => {
    const denied = only(who, 'BRANCH_MANAGER');
    if (denied) return denied;
    const s = shifts.find((x) => x.shiftId === Number(params[0]));
    if (!s || s.status !== 'CLOSED' || !body.note) return bad('Không đối soát được', 409);
    s.status = 'RECONCILED';
    return ok({ shiftId: s.shiftId, status: s.status });
  });

  // registration + feedback
  route('POST', '/api/auth/register', ({ body }) => {
    if (accounts.has(String(body.email))) return bad('BR-TK-01: email đã dùng');
    accounts.set(String(body.email), { id: next(), password: String(body.password), role: 'CUSTOMER', verified: false });
    return created({});
  });
  route('POST', '/api/auth/register/verify', ({ body }) => {
    const account = accounts.get(String(body.email));
    if (!account || body.code !== '123456') return bad('BR-TK-05: mã sai', 400);
    account.verified = true;
    return ok({ accountId: account.id, status: 'ACTIVE', linkDecisionPending: false });
  });
  route('POST', '/api/me/feedbacks', ({ who, body }) => {
    const denied = only(who, 'CUSTOMER');
    if (denied) return denied;
    if (body.topic === 'BRANCH_SERVICE' && !body.branchId) return bad('BR-DG-01: thiếu chi nhánh');
    const f = { feedbackId: next(), email: String(who), status: 'NEW' };
    feedbacks.push(f);
    return created({ feedbackId: f.feedbackId });
  });
  route('GET', '/api/feedbacks', () => page(feedbacks));
  route('GET', '/api/feedbacks/:id', ({ params }) => {
    const f = feedbacks.find((x) => x.feedbackId === Number(params[0]));
    if (!f) return bad('Không có feedback', 404);
    if (f.status === 'NEW') f.status = 'SEEN';
    return ok(f);
  });
  route('POST', '/api/feedbacks/:id/resolve', ({ params, body }) => {
    const f = feedbacks.find((x) => x.feedbackId === Number(params[0]));
    if (!f || f.status !== 'SEEN' || !body.resolutionNote) return bad('BR-DG-04: không xử lý được', 409);
    f.status = 'RESOLVED';
    return ok(f);
  });

  const compiled = routes.map(([method, pattern, handler]) => ({ method, regex: new RegExp(`^${pattern.replace(/:\w+/g, '([^/]+)')}$`), handler }));

  client.defaults.validateStatus = () => true;
  client.defaults.adapter = async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    const method = (config.method ?? 'get').toUpperCase();
    const path = String(config.url ?? '');
    const auth = String(config.headers?.Authorization ?? config.headers?.get?.('Authorization') ?? '');
    const who = auth.startsWith('Bearer t:') ? auth.slice('Bearer t:'.length) : null;
    const body = config.data ? (JSON.parse(String(config.data)) as Json) : {};
    calls.push({ method, path, who, body: config.data ? body : undefined });
    const respond = (status: number, payload: unknown): AxiosResponse => ({ status, statusText: String(status), headers: {}, config, data: payload });
    if (options.failOn === `${method} ${path}`) return respond(500, { success: false, errorCode: 'INTERNAL_ERROR', message: 'Lỗi giả lập của BE', statusCode: 500 });
    const hit = compiled.map((r) => ({ r, m: r.method === method ? r.regex.exec(path) : null })).find((x) => x.m);
    if (!hit?.m) return respond(404, { success: false, errorCode: 'RESOURCE_NOT_FOUND', message: `Fake BE chưa có ${method} ${path}`, statusCode: 404 });
    const reply = hit.r.handler({ who, body, params: hit.m.slice(1), query: (config.params ?? {}) as Record<string, unknown> });
    if (reply.status >= 400) return respond(reply.status, { success: false, errorCode: 'BUSINESS_RULE_VIOLATION', message: reply.message ?? 'Lỗi', statusCode: reply.status });
    return respond(reply.status, { data: reply.data, message: 'success', code: reply.status });
  };

  return {
    calls,
    state: { customers, kennels, categories, articles, pages, appointments, bookings, visits, orders, payments, shifts, careLogs, feedbacks, protocols },
    /** Lời gọi ghi (không GET, không đăng nhập, không xem trước thu). */
    writes: () => calls.filter((c) => c.method !== 'GET' && !c.path.endsWith('/auth/login') && !c.path.endsWith('/payments/preview')),
    phase2: PHASE2,
  };
}
