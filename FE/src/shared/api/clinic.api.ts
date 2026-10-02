// Visit / order / cashier-shift API for the staff workspaces.
// The BE ships only auth, iam, notification and pet so far, so this module answers in the
// browser (clinic-db.ts). It keeps the BE error envelope (errorCode + statusCode, rule ids
// from docs v13) so swapping each function for an axios call later changes nothing upstream.
import { formatCurrency } from '../../lib/utils';
import type {
  ArrivalView,
  CashierShift,
  Catalog,
  Customer,
  CustomerWithPets,
  DashboardSummary,
  MedicalRecord,
  Order,
  OrderLine,
  PaymentMethod,
  PendingOrderView,
  ServiceKind,
  Staff,
  Visit,
  VisitDetail,
  VisitView,
} from '../types/clinic';
import {
  CLINIC_CFG,
  arrivalState,
  callViolation,
  canTransitionVisit,
  completeViolation,
  lineTotal,
  paymentViolation,
  positionFor,
  queuePriority,
  retailViolation,
  sortQueue,
  type RuleViolation,
} from '../utils/clinic-rules';
import { foldVi } from '../utils/text';
import { dayKey, loadDb, resetClinicDb, saveDb, seedClinicDb, type ClinicDb } from './clinic-db';

export class ApiError extends Error {
  constructor(
    readonly errorCode: string,
    message: string,
    readonly statusCode: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export interface CheckInInput {
  appointmentId?: string;
  petId?: string;
  serviceId?: string;
  emergency?: boolean;
  assigneeId?: string;
}

export interface AddLineInput {
  kind: 'SERVICE' | 'PRODUCT';
  refId: string;
  qty: number;
}

export interface CollectPaymentInput {
  /** PENDING orders handed over by the exam room / grooming. */
  orderIds: string[];
  /** Counter sale lines, booked as a new RETAIL order. */
  retailLines: { productId: string; qty: number }[];
  customerId?: string;
  method: PaymentMethod;
  amount: number;
}

export interface PaymentReceipt {
  orderIds: string[];
  total: number;
  method: PaymentMethod;
  paidAt: string;
  customerName: string;
}

export const WALK_IN_CUSTOMER: Customer = { id: 'walk-in', name: 'Khách lẻ', phone: '' };

const LATENCY_MS = import.meta.env.MODE === 'test' ? 0 : 220;
const pause = () => new Promise<void>((resolve) => setTimeout(resolve, LATENCY_MS));

function reject(v: RuleViolation): never {
  throw new ApiError(v.code, v.message, v.code === 'INVALID_STATE_TRANSITION' ? 409 : 400);
}
const rule = (code: string, message: string): never => reject({ code, message });
const conflict = (message: string): never => reject({ code: 'INVALID_STATE_TRANSITION', message });
const denied = (message: string): never => {
  throw new ApiError('ACCESS_DENIED_SCOPE', message, 403);
};

function must<T>(value: T | undefined, what: string): T {
  if (value === undefined) throw new ApiError('RESOURCE_NOT_FOUND', `Không tìm thấy ${what}.`, 404);
  return value;
}

async function read<T>(fn: (db: ClinicDb, now: number) => T): Promise<T> {
  await pause();
  const now = Date.now();
  return fn(loadDb(now), now);
}

/** One use case = one load/save; a thrown guard leaves storage untouched. */
async function write<T>(fn: (db: ClinicDb, now: number) => T): Promise<T> {
  await pause();
  const now = Date.now();
  const db = loadDb(now);
  const result = fn(db, now);
  saveDb(db);
  return result;
}

const iso = (time: number) => new Date(time).toISOString();
const isToday = (db: ClinicDb, at: string) => dayKey(Date.parse(at)) === db.day;
const nextId = (db: ClinicDb, prefix: string) => `${prefix}-${++db.seq}`;

function log(db: ClinicDb, now: number, kind: ClinicDb['activity'][number]['kind'], text: string) {
  db.activity.unshift({ id: nextId(db, 'act'), at: iso(now), kind, text });
  db.activity.splice(50);
}

const customerOf = (db: ClinicDb, id: string): Customer =>
  db.customers.find((c) => c.id === id) ?? WALK_IN_CUSTOMER;
const staffName = (db: ClinicDb, id: string) => db.staff.find((s) => s.id === id)?.name ?? 'Nhân viên';
const findVisit = (db: ClinicDb, id: string) => must(db.visits.find((v) => v.id === id), 'lượt tiếp nhận');
const orderOf = (db: ClinicDb, visit: Visit) => must(db.orders.find((o) => o.id === visit.orderId), 'đơn hàng');
const petName = (db: ClinicDb, visit: Visit) => db.pets.find((p) => p.id === visit.petId)?.name ?? 'thú cưng';

/** Visit services = the service lines on its order (check-in line, switched or added ones). */
function syncServiceIds(db: ClinicDb, visit: Visit) {
  visit.serviceIds = [...new Set(orderOf(db, visit).lines.filter((l) => l.kind === 'SERVICE').map((l) => l.refId))];
}

/** Kinds of every service on the visit's order — the check-in line plus any the vet added. */
function visitKinds(db: ClinicDb, visit: Visit): ServiceKind[] {
  const ids = orderOf(db, visit).lines.filter((l) => l.kind === 'SERVICE').map((l) => l.refId);
  return [...new Set(ids.map((id) => must(db.services.find((s) => s.id === id), 'dịch vụ').kind))];
}

/**
 * Tồn khả dụng: on hand minus what open/pending orders already hold (vaccines are deducted on
 * injection). The same number gates the counter, prescriptions and payment (BR-BH-04).
 */
function availableStock(db: ClinicDb, productId: string, excludeOrderIds: string[] = []): number {
  const product = must(db.products.find((p) => p.id === productId), 'sản phẩm');
  if (product.vaccineType) return product.stock;
  const held = db.orders
    .filter((o) => (o.status === 'OPEN' || o.status === 'PENDING') && !excludeOrderIds.includes(o.id))
    .flatMap((o) => o.lines)
    .filter((l) => l.kind === 'PRODUCT' && l.refId === productId)
    .reduce((sum, l) => sum + l.qty, 0);
  return product.stock - held;
}

function assertPosition(db: ClinicDb, staffId: string, kind: ServiceKind) {
  const staff = must(db.staff.find((s) => s.id === staffId), 'nhân viên');
  const needed = positionFor(kind);
  if (staff.position !== needed) {
    rule('BR-TN-05', needed === 'VET' ? 'Lượt khám, tiêm chỉ gán cho bác sĩ.' : 'Lượt grooming chỉ gán cho nhân viên chăm sóc.');
  }
}

function assertRecordWriter(db: ClinicDb, visit: Visit, staffId: string) {
  if (!visitKinds(db, visit).some((k) => k !== 'GROOMING')) rule('BR-KB-01', 'Lượt grooming không có bệnh án.');
  if (visit.status === 'COMPLETED') conflict('Bệnh án đã khóa sau khi hoàn tất lượt.');
  if (visit.status !== 'IN_PROGRESS') conflict('Gọi lượt trước khi ghi bệnh án.');
  if (visit.assigneeId !== staffId) rule('BR-KB-01', 'Chỉ bác sĩ được gán lượt mới ghi được bệnh án.');
}

function assertLineEditor(visit: Visit, staffId: string) {
  if (visit.status !== 'IN_PROGRESS') conflict('Chỉ sửa chỉ định khi lượt đang làm.');
  if (visit.assigneeId !== staffId) denied('Chỉ nhân viên phụ trách lượt được sửa chỉ định.');
}

// ---------- view builders ----------

function visitView(db: ClinicDb, visit: Visit): VisitView {
  const pet = must(db.pets.find((p) => p.id === visit.petId), 'thú cưng');
  const assignee = visit.assigneeId ? db.staff.find((s) => s.id === visit.assigneeId) : undefined;
  return {
    id: visit.id,
    queueNo: visit.queueNo,
    status: visit.status,
    priority: visit.priority,
    emergency: visit.emergency,
    pet: { id: pet.id, name: pet.name, species: pet.species, breed: pet.breed, alert: pet.alert },
    customer: { ...customerOf(db, visit.customerId) },
    services: visit.serviceIds.map((id) => {
      const s = must(db.services.find((x) => x.id === id), 'dịch vụ');
      return { id: s.id, name: s.name, kind: s.kind };
    }),
    assignee: assignee ? { id: assignee.id, name: assignee.name } : undefined,
    scheduledAt: visit.scheduledAt,
    checkedInAt: visit.checkedInAt,
    calledAt: visit.calledAt,
    completedAt: visit.completedAt,
    orderId: visit.orderId,
    orderStatus: orderOf(db, visit).status,
    orderTotal: lineTotal(orderOf(db, visit).lines),
  };
}

function visitDetail(db: ClinicDb, visit: Visit): VisitDetail {
  const pet = must(db.pets.find((p) => p.id === visit.petId), 'thú cưng');
  const history = db.visits
    .filter((h) => h.petId === visit.petId && h.id !== visit.id && h.status === 'COMPLETED')
    .sort((a, b) => Date.parse(b.checkedInAt) - Date.parse(a.checkedInAt))
    .map((h) => ({
      visitId: h.id,
      date: h.checkedInAt,
      services: h.serviceIds.map((id) => db.services.find((s) => s.id === id)?.name ?? id),
      assessment: h.record?.assessment,
      followUpDate: h.record?.followUpDate,
    }));
  return {
    ...visitView(db, visit),
    petProfile: { ...pet },
    record: visit.record ? structuredClone(visit.record) : null,
    lines: orderOf(db, visit).lines.map((l) => ({ ...l })),
    history,
  };
}

function pendingView(db: ClinicDb, order: Order): PendingOrderView {
  const visit = order.visitId ? db.visits.find((v) => v.id === order.visitId) : undefined;
  return {
    id: order.id,
    source: order.source,
    customer: { ...customerOf(db, order.customerId) },
    queueNo: visit?.queueNo,
    petName: visit ? petName(db, visit) : undefined,
    lines: order.lines.map((l) => ({ ...l })),
    total: lineTotal(order.lines),
    createdAt: visit?.completedAt ?? order.createdAt,
  };
}

// ---------- reads ----------

function listTodayVisits() {
  return read((db) => sortQueue(db.visits.filter((v) => isToday(db, v.checkedInAt)).map((v) => visitView(db, v))));
}

/** BOOKED appointments inside the check-in window (BR-TN-02). Also plays the ST05 no-show job. */
function listArrivals() {
  return read((db, now): ArrivalView[] => {
    const todays = db.appointments.filter((a) => a.status === 'BOOKED' && isToday(db, a.startsAt));
    const missed = todays.filter((a) => arrivalState(a.startsAt, now) === 'NO_SHOW');
    if (missed.length) {
      missed.forEach((a) => (a.status = 'NO_SHOW'));
      saveDb(db);
    }
    return todays
      .filter((a) => arrivalState(a.startsAt, now) === 'OPEN')
      .sort((a, b) => Date.parse(a.startsAt) - Date.parse(b.startsAt))
      .map((a) => {
        const pet = must(db.pets.find((p) => p.id === a.petId), 'thú cưng');
        const service = must(db.services.find((s) => s.id === a.serviceId), 'dịch vụ');
        return {
          appointmentId: a.id,
          startsAt: a.startsAt,
          pet: { id: pet.id, name: pet.name, species: pet.species, breed: pet.breed },
          customer: { ...customerOf(db, a.customerId) },
          service: { id: service.id, name: service.name, kind: service.kind },
        };
      });
  });
}

function listPendingOrders() {
  return read((db) =>
    db.orders
      .filter((o) => o.status === 'PENDING')
      .map((o) => pendingView(db, o))
      .sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt)),
  );
}

function getVisitDetail(visitId: string) {
  return read((db) => visitDetail(db, findVisit(db, visitId)));
}

function getCatalog() {
  return read((db): Catalog => ({
    services: db.services.map((s) => ({ ...s })),
    products: db.products.map((p) => ({ ...p, available: availableStock(db, p.id) })),
  }));
}

/** Diacritic-insensitive name / pet name match, or phone digits (≥ 3). */
function searchCustomers(query: string) {
  return read((db): CustomerWithPets[] => {
    const q = foldVi(query.trim());
    const digits = query.replace(/\D/g, '');
    const withPets = db.customers.map((c) => ({ ...c, pets: db.pets.filter((p) => p.customerId === c.id).map((p) => ({ ...p })) }));
    if (!q) return withPets.slice(0, 8);
    return withPets
      .filter(
        (c) =>
          foldVi(c.name).includes(q) ||
          c.pets.some((p) => foldVi(p.name).includes(q)) ||
          (digits.length >= 3 && c.phone.replace(/\D/g, '').includes(digits)),
      )
      .slice(0, 8);
  });
}

function listStaff() {
  return read((db): Staff[] => db.staff.map((s) => ({ ...s })));
}

function getOpenShift(receptionistId: string) {
  return read((db): CashierShift | null => {
    const shift = db.shifts.find((s) => s.receptionistId === receptionistId && s.status === 'OPEN');
    return shift ? { ...shift } : null;
  });
}

function getDashboardSummary() {
  return read((db): DashboardSummary => {
    const today = db.visits.filter((v) => isToday(db, v.checkedInAt));
    const byKind: Record<ServiceKind, number> = { EXAM: 0, VACCINATION: 0, GROOMING: 0 };
    today
      .filter((v) => v.status !== 'CANCELLED')
      .forEach((v) => v.serviceIds.forEach((id) => {
        const kind = db.services.find((s) => s.id === id)?.kind;
        if (kind) byKind[kind] += 1;
      }));
    return {
      visitsToday: today.length,
      waiting: today.filter((v) => v.status === 'WAITING').length,
      inProgress: today.filter((v) => v.status === 'IN_PROGRESS').length,
      pendingPayment: db.orders.filter((o) => o.status === 'PENDING').length,
      revenueToday: db.orders
        .filter((o) => o.status === 'PAID' && o.paidAt && isToday(db, o.paidAt))
        .reduce((sum, o) => sum + lineTotal(o.lines), 0),
      byKind,
      activity: db.activity.slice(0, 8).map((a) => ({ ...a })),
    };
  });
}

// ---------- commands ----------

/** Tiếp nhận (Visit#1): Visit WAITING + Order OPEN with the service line; appointment → CHECKED_IN. */
function checkIn(input: CheckInInput, actorId: string) {
  return write((db, now) => {
    let petId = input.petId;
    let serviceId = input.serviceId;
    let scheduledAt: string | undefined;
    const appointment = input.appointmentId
      ? must(db.appointments.find((a) => a.id === input.appointmentId), 'lịch hẹn')
      : undefined;

    if (appointment) {
      if (appointment.status !== 'BOOKED') conflict('Lịch hẹn không còn chờ tiếp nhận.');
      if (!isToday(db, appointment.startsAt) || arrivalState(appointment.startsAt, now) !== 'OPEN') {
        rule('BR-TN-02', `Chỉ tiếp nhận lịch hẹn trong ngày, sớm nhất ${CLINIC_CFG.checkInEarlyMin} phút trước giờ hẹn.`);
      }
      petId = appointment.petId;
      serviceId = appointment.serviceId;
      scheduledAt = appointment.startsAt;
    }
    if (!petId || !serviceId) return rule('VALIDATION_ERROR', 'Chọn thú cưng và dịch vụ.');

    const pet = must(db.pets.find((p) => p.id === petId), 'thú cưng');
    const service = must(db.services.find((s) => s.id === serviceId), 'dịch vụ');
    if (input.assigneeId) assertPosition(db, input.assigneeId, service.kind);

    const checkedInAt = iso(now);
    const emergency = !!input.emergency;
    const visit: Visit = {
      id: nextId(db, 'visit'),
      queueNo: ++db.lastQueueNo,
      petId: pet.id,
      customerId: pet.customerId,
      appointmentId: appointment?.id,
      serviceIds: [service.id],
      emergency,
      priority: queuePriority({ emergency, scheduledAt, checkedInAt }),
      scheduledAt,
      assigneeId: input.assigneeId,
      status: 'WAITING',
      checkedInAt,
      orderId: nextId(db, 'order'),
    };
    const line: OrderLine = {
      id: nextId(db, 'line'), kind: 'SERVICE', refId: service.id, name: service.name,
      unitPrice: service.price, qty: 1, addedBy: actorId, autoGenerated: true,
    };
    db.visits.push(visit);
    db.orders.push({ id: visit.orderId, source: 'VISIT', customerId: pet.customerId, visitId: visit.id, status: 'OPEN', lines: [line], createdAt: checkedInAt });
    if (appointment) appointment.status = 'CHECKED_IN';
    log(db, now, 'CHECK_IN', `Tiếp nhận ${pet.name}, số ${visit.queueNo}`);
    return visitView(db, visit);
  });
}

/** Gán / gán lại lượt chưa gọi (Visit#2, BR-TN-05, BR-TN-08). */
function assignVisit(visitId: string, staffId: string, _actorId: string) {
  return write((db, now) => {
    const visit = findVisit(db, visitId);
    if (visit.status !== 'WAITING') conflict('Chỉ gán lại được lượt chưa gọi.');
    assertPosition(db, staffId, visitKinds(db, visit)[0] ?? 'EXAM');
    visit.assigneeId = staffId;
    log(db, now, 'ASSIGN', `Gán ${petName(db, visit)}, số ${visit.queueNo} cho ${staffName(db, staffId)}`);
    return visitView(db, visit);
  });
}

/** Gọi lượt (Visit#3). */
function callVisit(visitId: string, staffId: string) {
  return write((db, now) => {
    const visit = findVisit(db, visitId);
    const violation = callViolation({ status: visit.status, assigneeId: visit.assigneeId }, staffId);
    if (violation) reject(violation);
    visit.status = 'IN_PROGRESS';
    visit.calledAt = iso(now);
    log(db, now, 'CALL', `${staffName(db, staffId)} gọi ${petName(db, visit)}, số ${visit.queueNo}`);
    return visitView(db, visit);
  });
}

function saveRecord(visitId: string, record: MedicalRecord, staffId: string) {
  return write((db, now) => {
    const visit = findVisit(db, visitId);
    assertRecordWriter(db, visit, staffId);
    if (record.followUpDate) {
      const latest = dayKey(now + CLINIC_CFG.followUpMaxDays * 86_400_000);
      if (record.followUpDate <= db.day || record.followUpDate > latest) {
        rule('BR-KB-06', `Ngày tái khám phải sau hôm nay và không quá ${CLINIC_CFG.followUpMaxDays} ngày.`);
      }
    }
    visit.record = { ...structuredClone(record), updatedAt: iso(now) };
    return visitDetail(db, visit);
  });
}

/** Chỉ định: extra service, prescription (BR-KB-03) or injection (BR-KB-04, deducted at once). */
function addVisitLine(visitId: string, input: AddLineInput, staffId: string) {
  return write((db) => {
    const visit = findVisit(db, visitId);
    assertLineEditor(visit, staffId);
    if (!Number.isInteger(input.qty) || input.qty < 1) rule('VALIDATION_ERROR', 'Số lượng phải từ 1 trở lên.');
    const order = orderOf(db, visit);

    let line: Omit<OrderLine, 'id' | 'qty'>;
    if (input.kind === 'SERVICE') {
      const service = must(db.services.find((s) => s.id === input.refId), 'dịch vụ');
      if (positionFor(service.kind) !== positionFor(visitKinds(db, visit)[0] ?? service.kind)) {
        rule('BR-TN-05', 'Dịch vụ khác nhóm cần một lượt tiếp nhận riêng.');
      }
      line = { kind: 'SERVICE', refId: service.id, name: service.name, unitPrice: service.price, addedBy: staffId, autoGenerated: false };
    } else {
      const product = must(db.products.find((p) => p.id === input.refId), 'sản phẩm');
      const available = availableStock(db, product.id);
      if (input.qty > available) {
        rule('BR-KB-03', `${product.name} chỉ còn ${available} ${product.unit}. Giảm số lượng hoặc ghi "mua ngoài" trong kế hoạch.`);
      }
      if (product.vaccineType) product.stock -= input.qty;
      line = {
        kind: 'PRODUCT', refId: product.id, name: product.name, unitPrice: product.price, addedBy: staffId, autoGenerated: false,
        ...(product.vaccineType ? { vaccine: true } : {}),
      };
    }

    const same = order.lines.find((l) => l.refId === line.refId && l.addedBy === staffId && !l.autoGenerated);
    if (same) same.qty += input.qty;
    else order.lines.push({ ...line, id: nextId(db, 'line'), qty: input.qty });
    syncServiceIds(db, visit);
    return visitDetail(db, visit);
  });
}

function removeVisitLine(visitId: string, lineId: string, staffId: string) {
  return write((db) => {
    const visit = findVisit(db, visitId);
    assertLineEditor(visit, staffId);
    const order = orderOf(db, visit);
    const line = must(order.lines.find((l) => l.id === lineId), 'dòng chỉ định');
    // The check-in line belongs to whoever runs the visit (BR-TN-01); other lines to whoever added them (BR-BH-02).
    if (!line.autoGenerated && line.addedBy !== staffId) rule('BR-BH-02', 'Chỉ xóa được dòng do chính bạn thêm.');
    if (line.kind === 'SERVICE' && order.lines.filter((l) => l.kind === 'SERVICE').length === 1) {
      rule('VALIDATION_ERROR', 'Lượt cần ít nhất một dịch vụ. Hãy đổi dịch vụ thay vì xóa.');
    }
    if (line.vaccine) must(db.products.find((p) => p.id === line.refId), 'sản phẩm').stock += line.qty;
    order.lines = order.lines.filter((l) => l.id !== lineId);
    syncServiceIds(db, visit);
    return visitDetail(db, visit);
  });
}

/**
 * Đổi dịch vụ tự sinh lúc tiếp nhận (BR-TN-01), e.g. booked for a vaccination but the vet only
 * examines (BR-KB-02). Same group only: one visit, one kind of staff (BR-TN-05).
 */
function replaceVisitService(visitId: string, serviceId: string, staffId: string) {
  return write((db) => {
    const visit = findVisit(db, visitId);
    assertLineEditor(visit, staffId);
    const service = must(db.services.find((s) => s.id === serviceId), 'dịch vụ');
    if (positionFor(service.kind) !== positionFor(visitKinds(db, visit)[0] ?? service.kind)) {
      rule('BR-TN-05', 'Dịch vụ khác nhóm cần một lượt tiếp nhận riêng.');
    }
    const order = orderOf(db, visit);
    const fields = { kind: 'SERVICE' as const, refId: service.id, name: service.name, unitPrice: service.price, qty: 1, addedBy: staffId, autoGenerated: true };
    const checkInLine = order.lines.find((l) => l.autoGenerated);
    if (checkInLine) Object.assign(checkInLine, fields);
    else order.lines.unshift({ ...fields, id: nextId(db, 'line') });
    syncServiceIds(db, visit);
    return visitDetail(db, visit);
  });
}

/** Hoàn tất lượt (Visit#5): Order → PENDING, appointment → COMPLETED, record locked. */
function completeVisit(visitId: string, staffId: string) {
  return write((db, now) => {
    const visit = findVisit(db, visitId);
    if (visit.assigneeId !== staffId) denied('Chỉ nhân viên phụ trách được hoàn tất lượt.');
    const order = orderOf(db, visit);
    const violation = completeViolation({
      status: visit.status,
      kinds: visitKinds(db, visit),
      assessment: visit.record?.assessment,
      vaccineLines: order.lines.filter((l) => l.vaccine).length,
    });
    if (violation) reject(violation);
    visit.status = 'COMPLETED';
    visit.completedAt = iso(now);
    order.status = 'PENDING';
    const appointment = db.appointments.find((a) => a.id === visit.appointmentId);
    if (appointment) appointment.status = 'COMPLETED';
    log(db, now, 'COMPLETE', `${staffName(db, staffId)} hoàn tất ${petName(db, visit)}, số ${visit.queueNo}`);
    return visitView(db, visit);
  });
}

/** Hủy lượt khách bỏ về (Visit#6, BR-TN-07). */
function cancelVisit(visitId: string, _actorId: string) {
  return write((db, now) => {
    const visit = findVisit(db, visitId);
    if (!canTransitionVisit(visit.status, 'CANCELLED')) conflict('Lượt đã được gọi, không hủy được.');
    visit.status = 'CANCELLED';
    orderOf(db, visit).status = 'CANCELLED';
    const appointment = db.appointments.find((a) => a.id === visit.appointmentId);
    if (appointment) appointment.status = 'CANCELLED';
    log(db, now, 'CANCEL', `Hủy lượt ${petName(db, visit)}, số ${visit.queueNo}`);
    return visitView(db, visit);
  });
}

function openShift(receptionistId: string) {
  return write((db, now): CashierShift => {
    if (db.shifts.some((s) => s.receptionistId === receptionistId && s.status === 'OPEN')) {
      rule('BR-TG-05', 'Bạn đang có một ca thu ngân mở.');
    }
    const shift: CashierShift = {
      id: nextId(db, 'shift'), receptionistId, status: 'OPEN', openedAt: iso(now), cashTotal: 0, transferTotal: 0,
    };
    db.shifts.push(shift);
    log(db, now, 'SHIFT', `${staffName(db, receptionistId)} mở ca thu ngân`);
    return { ...shift };
  });
}

/** Thu tiền (Order#8): one receipt for one customer's pending orders plus counter items. */
function collectPayment(input: CollectPaymentInput, receptionistId: string) {
  return write((db, now): PaymentReceipt => {
    const shift = db.shifts.find((s) => s.receptionistId === receptionistId && s.status === 'OPEN') ?? null;
    const orders = input.orderIds.map((id) => must(db.orders.find((o) => o.id === id), 'đơn hàng'));

    let retail: Order | undefined;
    if (input.retailLines.length > 0) {
      const lines = input.retailLines.map((rl): OrderLine => {
        const product = must(db.products.find((p) => p.id === rl.productId), 'sản phẩm');
        const violation = retailViolation(product);
        if (violation) reject(violation);
        if (!Number.isInteger(rl.qty) || rl.qty < 1) rule('VALIDATION_ERROR', 'Số lượng phải từ 1 trở lên.');
        return {
          id: nextId(db, 'line'), kind: 'PRODUCT', refId: product.id, name: product.name,
          unitPrice: product.price, qty: rl.qty, addedBy: receptionistId, autoGenerated: false,
        };
      });
      // Bán lẻ: OPEN → PENDING (chốt đơn, ≥ 1 dòng) then paid with the rest.
      retail = {
        id: nextId(db, 'order'), source: 'RETAIL', status: 'PENDING', lines, createdAt: iso(now),
        customerId: input.customerId ?? orders[0]?.customerId ?? WALK_IN_CUSTOMER.id,
      };
      orders.push(retail);
    }

    const violation = paymentViolation({ orders, shift, receptionistId });
    if (violation) reject(violation);
    const openShift = shift as CashierShift;

    const total = orders.reduce((sum, o) => sum + lineTotal(o.lines), 0);
    if (input.amount !== total) rule('BR-TG-02', `Số tiền thu phải đúng tổng ${formatCurrency(total)}.`);

    // BR-BH-04: re-check and deduct goods + medicine; vaccines left stock when injected.
    const needed = new Map<string, number>();
    orders.flatMap((o) => o.lines)
      .filter((l) => l.kind === 'PRODUCT' && !l.vaccine)
      .forEach((l) => needed.set(l.refId, (needed.get(l.refId) ?? 0) + l.qty));
    const paying = orders.map((o) => o.id);
    needed.forEach((qty, productId) => {
      const product = must(db.products.find((p) => p.id === productId), 'sản phẩm');
      const available = availableStock(db, productId, paying);
      if (available < qty) rule('BR-BH-04', `${product.name} chỉ còn ${available} ${product.unit} có thể bán.`);
    });
    needed.forEach((qty, productId) => {
      must(db.products.find((p) => p.id === productId), 'sản phẩm').stock -= qty;
    });

    if (retail) db.orders.push(retail);
    const paidAt = iso(now);
    orders.forEach((o) => {
      o.status = 'PAID';
      o.paidAt = paidAt;
      o.paymentMethod = input.method;
      o.shiftId = openShift.id;
    });
    if (input.method === 'CASH') openShift.cashTotal += total;
    else openShift.transferTotal += total;

    const customer = customerOf(db, orders[0].customerId);
    log(db, now, 'PAY', `Thu ${formatCurrency(total)} của ${customer.name} (${input.method === 'CASH' ? 'tiền mặt' : 'chuyển khoản'})`);
    return { orderIds: orders.map((o) => o.id), total, method: input.method, paidAt, customerName: customer.name };
  });
}

/** Demo helper: back to this morning's seeded clinic. */
async function resetDemo() {
  await pause();
  resetClinicDb(seedClinicDb());
}

export const clinicApi = {
  listTodayVisits,
  listArrivals,
  listPendingOrders,
  getVisitDetail,
  getCatalog,
  searchCustomers,
  listStaff,
  getOpenShift,
  getDashboardSummary,
  checkIn,
  assignVisit,
  callVisit,
  saveRecord,
  addVisitLine,
  removeVisitLine,
  replaceVisitService,
  completeVisit,
  cancelVisit,
  openShift,
  collectPayment,
  resetDemo,
};
