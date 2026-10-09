// In-browser data store behind clinic.api.ts. Persisted in localStorage so several tabs
// (reception + exam room + grooming) share one clinic, and reseeded every new day.
import type {
  ActivityEntry,
  Appointment,
  CashierShift,
  Customer,
  Order,
  OrderLine,
  Pet,
  Product,
  Service,
  Staff,
  Visit,
} from '../types/clinic';

export interface ClinicDb {
  day: string;
  seq: number;
  lastQueueNo: number;
  customers: Customer[];
  pets: Pet[];
  staff: Staff[];
  services: Service[];
  products: Product[];
  appointments: Appointment[];
  visits: Visit[];
  orders: Order[];
  shifts: CashierShift[];
  activity: ActivityEntry[];
}

export const CLINIC_DB_KEY = 'petcare-clinic-db-v1';

/** Local calendar day, yyyy-mm-dd. */
export const dayKey = (time: number) => new Date(time).toLocaleDateString('sv-SE');

const SERVICES: Service[] = [
  { id: 'svc-exam', name: 'Khám tổng quát', kind: 'EXAM', price: 150_000, durationMin: 20 },
  { id: 'svc-exam-skin', name: 'Khám da liễu', kind: 'EXAM', price: 200_000, durationMin: 30 },
  { id: 'svc-blood-test', name: 'Xét nghiệm máu tổng quát', kind: 'EXAM', price: 250_000, durationMin: 15 },
  { id: 'svc-vac-rabies', name: 'Tiêm phòng dại', kind: 'VACCINATION', price: 60_000, durationMin: 10 },
  { id: 'svc-vac-combo', name: 'Tiêm vaccine tổng hợp', kind: 'VACCINATION', price: 60_000, durationMin: 10 },
  { id: 'svc-groom-bath', name: 'Tắm sấy', kind: 'GROOMING', price: 180_000, durationMin: 45 },
  { id: 'svc-groom-full', name: 'Tắm và cắt tỉa trọn gói', kind: 'GROOMING', price: 350_000, durationMin: 90 },
  { id: 'svc-groom-nail', name: 'Cắt móng, vệ sinh tai', kind: 'GROOMING', price: 80_000, durationMin: 15 },
];

const PRODUCTS: Product[] = [
  { id: 'p-rc-indoor', name: 'Royal Canin Indoor 2kg', category: 'FOOD', price: 450_000, stock: 25, unit: 'túi', prescriptionOnly: false },
  { id: 'p-pedigree', name: 'Pedigree Adult 3kg', category: 'FOOD', price: 280_000, stock: 30, unit: 'túi', prescriptionOnly: false },
  { id: 'p-pate', name: 'Pate Whiskas 85g', category: 'FOOD', price: 18_000, stock: 120, unit: 'gói', prescriptionOnly: false },
  { id: 'p-shampoo', name: 'Sữa tắm trị ve rận 250ml', category: 'HYGIENE', price: 120_000, stock: 15, unit: 'chai', prescriptionOnly: false },
  { id: 'p-litter', name: 'Cát đậu nành 6L', category: 'HYGIENE', price: 135_000, stock: 40, unit: 'túi', prescriptionOnly: false },
  { id: 'p-collar', name: 'Vòng cổ chống ve', category: 'ACCESSORY', price: 95_000, stock: 18, unit: 'cái', prescriptionOnly: false },
  { id: 'p-bowl', name: 'Bát inox chống trượt', category: 'ACCESSORY', price: 65_000, stock: 22, unit: 'cái', prescriptionOnly: false },
  { id: 'p-vitamin', name: 'Vitamin tổng hợp Nutri-Plus', category: 'MEDICINE', price: 280_000, stock: 20, unit: 'hộp', prescriptionOnly: false },
  { id: 'p-amox', name: 'Amoxicillin 250mg', category: 'MEDICINE', price: 8_000, stock: 200, unit: 'viên', prescriptionOnly: true },
  { id: 'p-predni', name: 'Prednisolone 5mg', category: 'MEDICINE', price: 5_000, stock: 150, unit: 'viên', prescriptionOnly: true },
  { id: 'p-apoquel', name: 'Apoquel 16mg', category: 'MEDICINE', price: 35_000, stock: 60, unit: 'viên', prescriptionOnly: true },
  { id: 'p-vac-rabisin', name: 'Vaccine dại Rabisin', category: 'MEDICINE', price: 90_000, stock: 30, unit: 'liều', prescriptionOnly: true, vaccineType: 'Dại' },
  { id: 'p-vac-dog5', name: 'Vaccine 5 bệnh chó Vanguard', category: 'MEDICINE', price: 180_000, stock: 25, unit: 'liều', prescriptionOnly: true, vaccineType: '5 bệnh chó' },
  { id: 'p-vac-cat4', name: 'Vaccine 4 bệnh mèo Felocell', category: 'MEDICINE', price: 200_000, stock: 20, unit: 'liều', prescriptionOnly: true, vaccineType: '4 bệnh mèo' },
];

// Staff ids '3'..'6' are the mock login accounts (api/mock/identity.mock.ts) so "assigned to me" works.
const STAFF: Staff[] = [
  { id: '5', name: 'BS. Minh Anh', position: 'VET' },
  { id: 'vet-2', name: 'BS. Quốc Bảo', position: 'VET' },
  { id: '6', name: 'Thu Hà', position: 'CARETAKER' },
  { id: 'care-2', name: 'Gia Huy', position: 'CARETAKER' },
  { id: '4', name: 'Lan Chi', position: 'RECEPTIONIST' },
  { id: '3', name: 'Hoàng Nam', position: 'BRANCH_MANAGER' },
];

const CUSTOMERS: Customer[] = [
  { id: 'c1', name: 'Nguyễn Thu Trang', phone: '0903 112 458' },
  { id: 'c2', name: 'Trần Đức Huy', phone: '0912 554 201' },
  { id: 'c3', name: 'Lê Bảo Ngọc', phone: '0938 667 120' },
  { id: 'c4', name: 'Phạm Gia Khánh', phone: '0977 301 889' },
  { id: 'c5', name: 'Võ Minh Thư', phone: '0868 245 017' },
  { id: 'c6', name: 'Đặng Quốc Việt', phone: '0909 778 342' },
  { id: 'c7', name: 'Hoàng Anh Tuấn', phone: '0981 420 665' },
  { id: 'c8', name: 'Bùi Thanh Hương', phone: '0935 118 904' },
  { id: 'c9', name: 'Ngô Hải Yến', phone: '0906 552 783' },
  { id: 'c10', name: 'Trịnh Gia Bảo', phone: '0919 403 226' },
];

const PETS: Pet[] = [
  { id: 'pet-mochi', customerId: 'c1', name: 'Mochi', species: 'CAT', breed: 'Anh lông ngắn', birthYear: 2022, weightKg: 4.2 },
  { id: 'pet-bo', customerId: 'c1', name: 'Bơ', species: 'DOG', breed: 'Corgi', birthYear: 2021, weightKg: 11.5 },
  { id: 'pet-lucky', customerId: 'c2', name: 'Lucky', species: 'DOG', breed: 'Golden Retriever', birthYear: 2019, weightKg: 31, alert: 'Sợ máy sấy' },
  { id: 'pet-miu', customerId: 'c3', name: 'Miu', species: 'CAT', breed: 'Ba Tư', birthYear: 2020, weightKg: 3.8, alert: 'Cắn khi cắt móng' },
  { id: 'pet-sua', customerId: 'c4', name: 'Sữa', species: 'DOG', breed: 'Poodle', birthYear: 2023, weightKg: 4.9 },
  { id: 'pet-tieu', customerId: 'c5', name: 'Tiêu', species: 'DOG', breed: 'Phốc sóc', birthYear: 2021, weightKg: 2.6 },
  { id: 'pet-mit', customerId: 'c6', name: 'Mít', species: 'CAT', breed: 'Mèo ta', birthYear: 2024, weightKg: 3.1 },
  { id: 'pet-rocky', customerId: 'c7', name: 'Rocky', species: 'DOG', breed: 'Husky', birthYear: 2020, weightKg: 24 },
  { id: 'pet-kem', customerId: 'c8', name: 'Kem', species: 'DOG', breed: 'Shih Tzu', birthYear: 2018, weightKg: 6.3 },
  { id: 'pet-bong', customerId: 'c9', name: 'Bông', species: 'DOG', breed: 'Poodle', birthYear: 2022, weightKg: 5.4 },
  { id: 'pet-dau', customerId: 'c10', name: 'Đậu', species: 'DOG', breed: 'Pug', birthYear: 2021, weightKg: 8.7 },
];

const serviceLine = (id: string, serviceId: string, addedBy: string): OrderLine => {
  const s = SERVICES.find((x) => x.id === serviceId)!;
  return { id, kind: 'SERVICE', refId: s.id, name: s.name, unitPrice: s.price, qty: 1, addedBy, autoGenerated: true };
};

const productLine = (id: string, productId: string, qty: number, addedBy: string): OrderLine => {
  const p = PRODUCTS.find((x) => x.id === productId)!;
  return {
    id, kind: 'PRODUCT', refId: p.id, name: p.name, unitPrice: p.price, qty, addedBy, autoGenerated: false,
    ...(p.vaccineType ? { vaccine: true } : {}),
  };
};

const emptyRecord = () => ({ subjective: '', objective: { findings: '' }, assessment: '', plan: '', internalNote: '' });

export function seedClinicDb(now: number = Date.now()): ClinicDb {
  const startOfDay = new Date(now).setHours(0, 0, 0, 0);
  // Today's past events never slip into yesterday, even for a demo just after midnight.
  const t = (min: number) => new Date(Math.max(now + min * 60_000, startOfDay + 60_000)).toISOString();
  const daysAgo = (d: number) => new Date(now - d * 86_400_000).toISOString();

  type Seed = Omit<Visit, 'orderId' | 'id' | 'queueNo'> & { lines: OrderLine[]; orderStatus: Order['status']; paidAt?: string };
  const today: Seed[] = [
    { petId: 'pet-sua', customerId: 'c4', serviceIds: ['svc-groom-nail'], emergency: false, priority: 'WALK_IN', assigneeId: '6', status: 'COMPLETED', checkedInAt: t(-110), calledAt: t(-100), completedAt: t(-85), lines: [serviceLine('l-1', 'svc-groom-nail', '4')], orderStatus: 'PAID', paidAt: t(-80) },
    { petId: 'pet-tieu', customerId: 'c5', serviceIds: ['svc-exam'], emergency: false, priority: 'WALK_IN', assigneeId: '5', status: 'COMPLETED', checkedInAt: t(-70), calledAt: t(-50), completedAt: t(-20), lines: [serviceLine('l-2', 'svc-exam', '4'), productLine('l-3', 'p-amox', 10, '5')], orderStatus: 'PENDING',
      record: { subjective: 'Tiêu chảy 2 ngày, bỏ ăn sáng nay', objective: { temperatureC: 39.1, weightKg: 2.6, heartRate: 128, findings: 'Bụng chướng nhẹ, niêm mạc hồng' }, assessment: 'Viêm ruột nhẹ', plan: 'Amoxicillin 2 viên/ngày trong 5 ngày, ăn cháo loãng', internalNote: '', followUpDate: new Date(now + 5 * 86_400_000).toLocaleDateString('sv-SE') } },
    { petId: 'pet-bong', customerId: 'c9', serviceIds: ['svc-groom-bath'], emergency: false, priority: 'WALK_IN', assigneeId: 'care-2', status: 'COMPLETED', checkedInAt: t(-65), calledAt: t(-55), completedAt: t(-10), lines: [serviceLine('l-4', 'svc-groom-bath', '4')], orderStatus: 'PENDING' },
    { petId: 'pet-bo', customerId: 'c1', serviceIds: ['svc-groom-full'], emergency: false, priority: 'WALK_IN', assigneeId: '6', status: 'IN_PROGRESS', checkedInAt: t(-40), calledAt: t(-25), lines: [serviceLine('l-5', 'svc-groom-full', '4')], orderStatus: 'OPEN' },
    { petId: 'pet-mochi', customerId: 'c1', serviceIds: ['svc-exam-skin'], emergency: false, priority: 'WALK_IN', assigneeId: '5', status: 'IN_PROGRESS', checkedInAt: t(-30), calledAt: t(-12), lines: [serviceLine('l-6', 'svc-exam-skin', '4')], orderStatus: 'OPEN',
      record: { ...emptyRecord(), subjective: 'Gãi nhiều vùng cổ khoảng 1 tuần nay', objective: { temperatureC: 38.6, weightKg: 4.2, findings: '' } } },
    { petId: 'pet-miu', customerId: 'c3', appointmentId: 'appt-miu', serviceIds: ['svc-exam'], emergency: false, priority: 'APPOINTMENT', scheduledAt: t(-10), assigneeId: '5', status: 'WAITING', checkedInAt: t(-8), lines: [serviceLine('l-7', 'svc-exam', '4')], orderStatus: 'OPEN' },
    { petId: 'pet-rocky', customerId: 'c7', serviceIds: ['svc-groom-full'], emergency: false, priority: 'WALK_IN', assigneeId: '6', status: 'WAITING', checkedInAt: t(-6), lines: [serviceLine('l-8', 'svc-groom-full', '4')], orderStatus: 'OPEN' },
    { petId: 'pet-kem', customerId: 'c8', serviceIds: ['svc-groom-nail'], emergency: false, priority: 'WALK_IN', status: 'WAITING', checkedInAt: t(-3), lines: [serviceLine('l-9', 'svc-groom-nail', '4')], orderStatus: 'OPEN' },
  ];

  const visits: Visit[] = [];
  const orders: Order[] = [];
  today.forEach(({ lines, orderStatus, paidAt, ...v }, i) => {
    const n = i + 1;
    visits.push({ ...v, id: `visit-${n}`, queueNo: n, orderId: `order-${n}` });
    orders.push({
      id: `order-${n}`, source: 'VISIT', customerId: v.customerId, visitId: `visit-${n}`, status: orderStatus, lines, createdAt: v.checkedInAt,
      ...(paidAt ? { paidAt, paymentMethod: 'CASH' as const } : {}),
    });
  });

  // Earlier days: pet history for the exam room.
  const past = [
    { petId: 'pet-mochi', customerId: 'c1', serviceIds: ['svc-vac-combo'], at: daysAgo(92), assessment: '', followUp: undefined },
    { petId: 'pet-mochi', customerId: 'c1', serviceIds: ['svc-exam'], at: daysAgo(180), assessment: 'Viêm tai ngoài', followUp: undefined },
    { petId: 'pet-miu', customerId: 'c3', serviceIds: ['svc-exam'], at: daysAgo(41), assessment: 'Rụng lông do nấm, đã điều trị', followUp: undefined },
    { petId: 'pet-lucky', customerId: 'c2', serviceIds: ['svc-exam'], at: daysAgo(60), assessment: 'Viêm khớp háng giai đoạn đầu', followUp: undefined },
  ];
  past.forEach((p, i) => {
    const id = `visit-past-${i + 1}`;
    visits.push({
      id, queueNo: 0, petId: p.petId, customerId: p.customerId, serviceIds: p.serviceIds, emergency: false, priority: 'WALK_IN',
      assigneeId: '5', status: 'COMPLETED', checkedInAt: p.at, calledAt: p.at, completedAt: p.at, orderId: `order-past-${i + 1}`,
      record: { ...emptyRecord(), assessment: p.assessment },
    });
    orders.push({ id: `order-past-${i + 1}`, source: 'VISIT', customerId: p.customerId, visitId: id, status: 'PAID', lines: [serviceLine(`l-past-${i + 1}`, p.serviceIds[0], '4')], createdAt: p.at, paidAt: p.at, paymentMethod: 'CASH' });
  });

  const appointments: Appointment[] = [
    { id: 'appt-miu', customerId: 'c3', petId: 'pet-miu', serviceId: 'svc-exam', startsAt: t(-10), status: 'CHECKED_IN' },
    { id: 'appt-lucky', customerId: 'c2', petId: 'pet-lucky', serviceId: 'svc-exam', startsAt: new Date(now + 10 * 60_000).toISOString(), status: 'BOOKED' },
    { id: 'appt-mit', customerId: 'c6', petId: 'pet-mit', serviceId: 'svc-vac-combo', startsAt: t(-5), status: 'BOOKED' },
    { id: 'appt-dau', customerId: 'c10', petId: 'pet-dau', serviceId: 'svc-groom-bath', startsAt: new Date(now + 180 * 60_000).toISOString(), status: 'BOOKED' },
  ];

  const activity: ActivityEntry[] = [
    { id: 'act-1', at: t(-80), kind: 'PAY', text: 'Thu 80.000 ₫ của Phạm Gia Khánh (tiền mặt)' },
    { id: 'act-2', at: t(-20), kind: 'COMPLETE', text: 'BS. Minh Anh hoàn tất khám Tiêu, số 2' },
    { id: 'act-3', at: t(-12), kind: 'CALL', text: 'BS. Minh Anh gọi Mochi, số 5' },
    { id: 'act-4', at: t(-10), kind: 'COMPLETE', text: 'Gia Huy xong grooming Bông, số 3' },
    { id: 'act-5', at: t(-3), kind: 'CHECK_IN', text: 'Tiếp nhận Kem, số 8' },
  ];

  return {
    day: dayKey(now),
    // Time-based so ids of a reseeded day never collide with earlier ones (seen-sets, drafts).
    seq: Math.floor(now / 1000),
    lastQueueNo: today.length,
    customers: CUSTOMERS.map((c) => ({ ...c })),
    pets: PETS.map((p) => ({ ...p })),
    staff: STAFF.map((s) => ({ ...s })),
    services: SERVICES.map((s) => ({ ...s })),
    products: PRODUCTS.map((p) => ({ ...p })),
    appointments,
    visits,
    orders,
    shifts: [],
    activity,
  };
}

/** Reads today's clinic; a missing, corrupt or stale (previous day) store is reseeded. */
export function loadDb(now: number = Date.now()): ClinicDb {
  let stored: ClinicDb | null = null;
  try {
    const raw = localStorage.getItem(CLINIC_DB_KEY);
    stored = raw ? (JSON.parse(raw) as ClinicDb) : null;
  } catch {
    stored = memoryFallback;
  }
  if (stored && stored.day === dayKey(now)) return stored;
  const fresh = seedClinicDb(now);
  saveDb(fresh);
  return fresh;
}

// Used only when localStorage throws (private mode, blocked site data).
let memoryFallback: ClinicDb | null = null;

export function saveDb(db: ClinicDb): void {
  memoryFallback = db;
  try {
    localStorage.setItem(CLINIC_DB_KEY, JSON.stringify(db));
  } catch {
    // Storage blocked or full: the in-memory copy keeps this tab working.
  }
}

/** Replace the whole store (tests, "reset demo data"). */
export function resetClinicDb(db: ClinicDb = seedClinicDb()): void {
  saveDb(db);
}
