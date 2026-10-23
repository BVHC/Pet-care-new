// Mock module KH: docs/api/customer-v1.md #8–21 (tra cứu, hồ sơ tại quầy, thú cưng, cân nặng, chuyển chủ)
// và hồ sơ sức khỏe docs/api/visit-v1.md #21. Hồ sơ khách nằm ở customer.mock.ts (chung với UC06/UC07);
// file này giữ thú cưng, cân nặng và hồ sơ sức khỏe mẫu.
// Thứ tự kiểm như BE (00-method §3.5): dữ liệu vào → quyền → tồn tại → rule (400) → trạng thái (409).
import type { Role } from '../../types/auth';
import type { CustomerProfile } from '../../types/profile';
import { foldVi } from '../../utils/text';
import type { CustomerSearchItem, HealthRecord, PetResponse, Sex, Species, WeightRecord } from '../../../domains/pets/types';
import {
  allCustomers,
  createCounterProfile,
  customerOfAccount,
  findCustomer,
  markHasData,
  updateCustomerFields,
  type MockCustomer,
} from './customer.mock';
import {
  blank,
  denied,
  fail,
  has,
  invalid,
  isText,
  noContent,
  notFound,
  ok,
  optional,
  ruleFail,
  scopeDenied,
  tooLong,
  type Check,
  type MockHandler,
  type MockReply,
  type MockRequest,
} from './envelope';
import { accountEmailOf, maskEmail } from './identity.mock';

/** Tra cứu và xem hồ sơ khách, thú cưng: A06–A08 (customer-v1 mục B). */
const LOOKUP: readonly Role[] = ['RECEPTIONIST', 'VET', 'CARETAKER'];
const canLookUp = (req: MockRequest) => LOOKUP.includes(req.account!.role);
const isDesk = (req: MockRequest) => req.account!.role === 'RECEPTIONIST';
const isCustomer = (req: MockRequest) => req.account!.role === 'CUSTOMER';

const SPECIES: readonly Species[] = ['DOG', 'CAT', 'OTHER'];
const SEXES: readonly Sex[] = ['MALE', 'FEMALE', 'UNKNOWN'];
const PHONE = /^0\d{9}$/;
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const todayVn = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Ho_Chi_Minh' }).format(new Date());

// ─── Dữ liệu ───

/** Việc đang dở của thú: chặn đánh dấu đã mất (BR-KH-05) và chuyển chủ (BR-KH-08). */
type Busy = 'OPEN_VISIT' | 'BOARDING' | 'PENDING_ORDER';

interface PetRow extends Omit<PetResponse, 'currentWeightKg'> {
  busy: Busy | null;
}

interface WeightRow extends WeightRecord {
  petId: number;
}

interface State {
  nextPetId: number;
  nextWeightId: number;
  pets: PetRow[];
  weights: WeightRow[];
}

function seed(): State {
  const pet = (p: Pick<PetRow, 'petId' | 'customerId' | 'name' | 'species' | 'sex'> & Partial<PetRow>): PetRow => ({
    breed: null,
    birthDate: null,
    birthDateEstimated: false,
    color: null,
    isNeutered: null,
    photoUrl: null,
    deceasedOn: null,
    speciesLocked: false,
    deletable: true,
    busy: null,
    ...p,
  });
  const weight = (weightRecordId: number, petId: number, weightKg: number, source: WeightRow['source'], measuredAt: string, visitId: number | null = null): WeightRow => ({
    weightRecordId,
    petId,
    weightKg,
    source,
    visitId,
    boardingBookingId: null,
    measuredAt,
  });
  return {
    nextPetId: 6,
    nextWeightId: 6,
    pets: [
      // khach@petcare.vn (hồ sơ #1): Mochi có bệnh án, Bim có mũi tiêm → khóa loài, không xóa được; Tôm chưa phát sinh gì.
      pet({ petId: 1, customerId: 1, name: 'Mochi', species: 'CAT', sex: 'FEMALE', breed: 'Mèo Anh lông ngắn', birthDate: '2023-04-12', color: 'Xám xanh', isNeutered: true, speciesLocked: true, deletable: false }),
      pet({ petId: 2, customerId: 1, name: 'Bim', species: 'DOG', sex: 'MALE', breed: 'Poodle', birthDate: '2022-08-01', birthDateEstimated: true, color: 'Nâu đỏ', isNeutered: false, speciesLocked: true, deletable: false }),
      pet({ petId: 3, customerId: 1, name: 'Tôm', species: 'OTHER', sex: 'UNKNOWN', breed: 'Hamster' }),
      // Hồ sơ tại quầy #3 (SĐT 0905111222): Lu đang có lượt khám mở → không đánh dấu mất, không chuyển chủ được.
      pet({ petId: 4, customerId: 3, name: 'Lu', species: 'DOG', sex: 'MALE', breed: 'Phú Quốc', birthDate: '2021-02-10', speciesLocked: true, deletable: false, busy: 'OPEN_VISIT' }),
      // Hồ sơ tại quầy #4 (cùng SĐT 0905111222).
      pet({ petId: 5, customerId: 4, name: 'Miu', species: 'CAT', sex: 'FEMALE' }),
    ],
    weights: [
      weight(1, 1, 4.0, 'VET', '2026-08-15T10:30:00+07:00', 1001),
      weight(2, 1, 4.1, 'OWNER', '2026-09-20T20:00:00+07:00'),
      weight(3, 1, 4.2, 'VET', '2026-10-10T15:00:00+07:00', 1002),
      weight(4, 2, 6.5, 'VET', '2026-08-01T14:00:00+07:00', 1003),
      weight(5, 4, 18.3, 'VET', '2026-10-14T09:00:00+07:00', 1004),
    ],
  };
}

/** Hồ sơ sức khỏe mẫu, đủ trường của visit-v1 (module visit ghi, ở đây chỉ đọc). */
const HEALTH: Record<number, Omit<HealthRecord, 'petId'>> = {
  1: {
    visits: [
      {
        visitId: 1002,
        visitDate: '2026-10-10',
        branchName: 'PetCare Cầu Giấy',
        serviceGroup: 'MEDICAL',
        examination: 'Da vùng bụng còn hơi đỏ, hết ngứa',
        diagnosis: 'Viêm da dị ứng — tái khám',
        treatmentPlan: 'Giảm liều thuốc, tắm dầu gội y tế mỗi tuần',
        internalNote: 'Chủ hay quên cho uống thuốc buổi tối',
        followUpDate: '2026-10-24',
        prescriptions: [
          { itemId: 3, productId: 21, productName: 'Cetirizine 10mg', quantity: 5, dosageInstructions: 'Nửa viên mỗi tối sau ăn', isExternalPurchase: false, externalReason: null, orderLineId: 13 },
        ],
        addenda: [
          { addendumId: 1, content: 'Kết quả soi da: không có nấm', isInternal: false, createdBy: 5, createdByName: 'BS. Minh Anh', createdAt: '2026-10-11T09:00:00+07:00' },
          { addendumId: 2, content: 'Cân nhắc đổi thức ăn hạt nếu tái phát', isInternal: true, createdBy: 5, createdByName: 'BS. Minh Anh', createdAt: '2026-10-11T09:05:00+07:00' },
        ],
      },
      {
        visitId: 1001,
        visitDate: '2026-08-15',
        branchName: 'PetCare Cầu Giấy',
        serviceGroup: 'MEDICAL',
        examination: 'Ngứa, rụng lông vùng bụng',
        diagnosis: 'Viêm da dị ứng',
        treatmentPlan: 'Thuốc kháng histamin 7 ngày, tắm dầu gội y tế',
        internalNote: null,
        followUpDate: '2026-09-10',
        prescriptions: [
          { itemId: 1, productId: 21, productName: 'Cetirizine 10mg', quantity: 7, dosageInstructions: 'Một viên mỗi tối sau ăn', isExternalPurchase: false, externalReason: null, orderLineId: 11 },
          { itemId: 2, productId: 22, productName: 'Dầu gội Medicated', quantity: 1, dosageInstructions: 'Tắm 2 lần mỗi tuần', isExternalPurchase: true, externalReason: 'OUT_OF_STOCK_AT_PRESCRIBE', orderLineId: null },
        ],
        addenda: [],
      },
    ],
    vaccinations: [],
    upcomingVaccinations: [],
    upcomingFollowUpDate: '2026-10-24',
  },
  2: {
    visits: [
      { visitId: 1005, visitDate: '2026-09-12', branchName: 'PetCare Cầu Giấy', serviceGroup: 'GROOMING', examination: null, diagnosis: null, treatmentPlan: 'Tắm, cắt tỉa lông', internalNote: null, followUpDate: null, prescriptions: [], addenda: [] },
      { visitId: 1003, visitDate: '2026-08-01', branchName: 'PetCare Cầu Giấy', serviceGroup: 'MEDICAL', examination: 'Khám tổng quát trước tiêm', diagnosis: 'Khỏe mạnh', treatmentPlan: null, internalNote: null, followUpDate: null, prescriptions: [], addenda: [] },
    ],
    vaccinations: [
      { vaccinationId: 501, visitId: 1003, branchId: 1, vaccineTypeId: 1, vaccineTypeName: 'Dại', protocolId: 1, doseNumber: 1, productId: 31, productName: 'Vaccine Dại Rabisin', lotNumber: 'RB2401', expiryDate: '2027-06-30', administeredOn: '2026-08-01', nextDueDate: '2027-08-01', superseded: false, recordedBy: 5, orderLineId: 17 },
    ],
    upcomingVaccinations: [{ vaccineTypeId: 1, vaccineTypeName: 'Dại', nextDueDate: '2027-08-01' }],
    upcomingFollowUpDate: null,
  },
};

const STATE_KEY = 'petcare-mock-pets';

function load(): State {
  try {
    const raw = localStorage.getItem(STATE_KEY);
    if (raw) return JSON.parse(raw) as State;
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

// ─── Đọc chéo cho mock lịch hẹn (như PetQueryApi của BE) ───

/** Chủ hiện tại, tên, ngày mất của thú; undefined nếu không có. */
export function petSummary(petId: number) {
  const pet = load().pets.find((p) => p.petId === petId);
  return pet && { petId: pet.petId, customerId: pet.customerId, name: pet.name, species: pet.species, deceasedOn: pet.deceasedOn ?? null };
}

/** Thú đã phát sinh lịch hẹn thì không xóa cứng được nữa (BR-KH-06). */
export function markPetHasBooking(petId: number) {
  const s = load();
  const pet = s.pets.find((p) => p.petId === petId);
  if (pet?.deletable) {
    pet.deletable = false;
    save(s);
  }
}

// ─── Chuyển đổi sang schema hợp đồng ───

const byNewest = (a: WeightRow, b: WeightRow) => Date.parse(b.measuredAt) - Date.parse(a.measuredAt);

/** Cân nặng hiện tại là lần đo gần nhất (BR-KH-04). */
const currentWeight = (s: State, petId: number) => s.weights.filter((w) => w.petId === petId).sort(byNewest)[0]?.weightKg ?? null;

const responseOf = (s: State, { busy: _busy, ...pet }: PetRow): PetResponse => ({ ...pet, currentWeightKg: currentWeight(s, pet.petId) });

const weightOf = ({ petId: _petId, ...weight }: WeightRow): WeightRecord => weight;

/** Hồ sơ có tài khoản dùng email của tài khoản, kể cả email lễ tân vừa sửa hộ (customer-v1 A5, A9). */
const emailOf = (c: MockCustomer): string | null => (c.accountId === null ? c.email : (accountEmailOf(c.accountId) ?? c.email));

const profileOf = (c: MockCustomer): CustomerProfile => ({
  customerId: c.customerId,
  fullName: c.fullName,
  phone: c.phone,
  email: emailOf(c),
  avatarUrl: c.avatarUrl,
  createdChannel: c.createdChannel,
  hasAccount: c.accountId !== null,
  linkDecisionPending: c.linkDecisionPending,
});

/** Tra cứu trả email đã che (BR-KH-10). */
function searchItemOf(s: State, c: MockCustomer): CustomerSearchItem {
  const email = emailOf(c);
  return {
    customerId: c.customerId,
    fullName: c.fullName,
    phone: c.phone,
    maskedEmail: email ? maskEmail(email) : null,
    createdChannel: c.createdChannel,
    hasAccount: c.accountId !== null,
    pets: s.pets
      .filter((p) => p.customerId === c.customerId)
      .map((p) => ({ petId: p.petId, name: p.name, species: p.species, deceased: p.deceasedOn !== null })),
  };
}

/** Hồ sơ khác có cùng SĐT: cảnh báo nghi trùng, không chặn (BR-KH-10). */
const duplicatesOf = (s: State, phone: string | null, exceptId: number) =>
  phone ? allCustomers().filter((c) => c.phone === phone && c.customerId !== exceptId).map((c) => searchItemOf(s, c)) : [];

/** BR-KH-02: chủ đã có thú cùng tên, cùng loài → cảnh báo nghi trùng. */
const hasTwin = (s: State, pet: PetRow) =>
  s.pets.some(
    (o) => o.petId !== pet.petId && o.customerId === pet.customerId && o.species === pet.species && foldVi(o.name.trim()) === foldVi(pet.name.trim()),
  );

const bodyOf = (req: MockRequest) => (req.body ?? {}) as Record<string, unknown>;
const isReply = (x: object): x is MockReply => 'status' in x;

/** Khách: hồ sơ của chính mình; còn cờ chờ quyết định liên kết thì mọi thao tác thú cưng bị chặn (customer-v1 D §2). */
function ownProfile(req: MockRequest): MockCustomer | MockReply {
  const own = customerOfAccount(req.account!.id);
  if (!own) return notFound('hồ sơ khách của tài khoản', req.account!.id);
  if (own.linkDecisionPending) {
    return ruleFail('BR-TK-19', 'Hồ sơ đang chờ quyết định liên kết với hồ sơ tại quầy, chưa quản lý được thú cưng');
  }
  return own;
}

/**
 * Thú cưng theo quyền: khách chỉ thấy thú mình đang là chủ (thú của người khác — kể cả chủ cũ sau
 * khi chuyển — trả 404, customer-v1 D §1); nhân viên A06–A08 được đọc, chỉ lễ tân được ghi.
 */
function petFor(req: MockRequest, s: State, access: 'read' | 'write'): PetRow | MockReply {
  const pet = s.pets.find((p) => p.petId === Number(req.params.petId));
  if (isCustomer(req)) {
    const own = ownProfile(req);
    if (isReply(own)) return own;
    return pet && pet.customerId === own.customerId ? pet : notFound('thú cưng', req.params.petId);
  }
  if (access === 'read' ? !canLookUp(req) : !isDesk(req)) return denied();
  return pet ?? notFound('thú cưng', req.params.petId);
}

// ─── Khách hàng (UC22, UC23) ───

const searchCustomers: MockHandler = (req) => {
  if (!canLookUp(req)) return denied();
  const phone = req.query.phone?.trim();
  const name = req.query.name?.trim();
  const email = req.query.email?.trim().toLowerCase();
  const petName = req.query.petName?.trim();
  if (!phone && !name && !email && !petName) {
    return fail('VALIDATION_FAILED', 'Cần ít nhất một điều kiện tìm: số điện thoại, họ tên, email hoặc tên thú cưng', 400);
  }
  const s = load();
  const found = allCustomers().filter(
    (c) =>
      (!phone || c.phone === phone) &&
      (!name || foldVi(c.fullName).includes(foldVi(name))) &&
      (!email || emailOf(c)?.toLowerCase() === email) &&
      (!petName || s.pets.some((p) => p.customerId === c.customerId && foldVi(p.name).includes(foldVi(petName)))),
  );
  const page = Math.max(Number(req.query.page ?? 0) || 0, 0);
  const size = Math.min(Math.max(Number(req.query.size ?? 20) || 20, 1), 100);
  return ok({
    content: found.slice(page * size, page * size + size).map((c) => searchItemOf(s, c)),
    page,
    size,
    totalElements: found.length,
    totalPages: Math.ceil(found.length / size),
  });
};

const getCustomer: MockHandler = (req) => {
  if (!canLookUp(req)) return denied();
  const c = findCustomer(Number(req.params.customerId));
  if (!c) return notFound('khách hàng', req.params.customerId);
  const s = load();
  return ok({ customer: profileOf(c), pets: s.pets.filter((p) => p.customerId === c.customerId).map((p) => responseOf(s, p)) });
};

const customerChecks = (b: Record<string, unknown>, creating: boolean): Check[] => [
  ['fullName', 'Vui lòng nhập họ tên', creating ? !isText(b.fullName) : blank(b.fullName)],
  ['fullName', 'Họ tên tối đa 100 ký tự', tooLong(b.fullName, 100)],
  ['phone', 'Vui lòng nhập số điện thoại', creating && !isText(b.phone)],
  ['phone', 'Số điện thoại phải có dạng 0xxxxxxxxx', isText(b.phone) && !PHONE.test(String(b.phone).trim())],
  ['email', 'Email không hợp lệ', isText(b.email) && (tooLong(b.email, 255) || !EMAIL.test(String(b.email).trim()))],
];

const createCounterCustomer: MockHandler = (req) => {
  const b = bodyOf(req);
  const bad = invalid(customerChecks(b, true));
  if (bad) return bad;
  if (!isDesk(req)) return denied();
  const created = createCounterProfile(String(b.fullName).trim(), String(b.phone).trim(), optional(b.email));
  return ok(
    { customer: profileOf(created), duplicatePhoneProfiles: duplicatesOf(load(), created.phone, created.customerId) },
    201,
    'Đã tạo hồ sơ khách',
  );
};

const updateCustomer: MockHandler = (req) => {
  const b = bodyOf(req);
  const bad = invalid(customerChecks(b, false));
  if (bad) return bad;
  if (!isDesk(req)) return denied();
  const c = findCustomer(Number(req.params.customerId));
  if (!c) return notFound('khách hàng', req.params.customerId);
  if (has(b.email) && c.accountId !== null) {
    return ruleFail('BR-TK-16', 'Hồ sơ đã gắn tài khoản nên email theo tài khoản; dùng chức năng sửa email tài khoản khách');
  }
  const phone = has(b.phone) ? optional(b.phone) : c.phone;
  if (c.createdChannel === 'COUNTER' && phone === null) return ruleFail('BR-KH-01', 'Hồ sơ tại quầy bắt buộc có số điện thoại');
  const phoneChanged = phone !== c.phone;
  const updated = updateCustomerFields(c.customerId, {
    fullName: has(b.fullName) ? String(b.fullName).trim() : undefined,
    phone,
    email: has(b.email) ? optional(b.email) : undefined,
  })!;
  return ok(
    { customer: profileOf(updated), duplicatePhoneProfiles: phoneChanged ? duplicatesOf(load(), phone, c.customerId) : [] },
    200,
    'Đã cập nhật hồ sơ khách',
  );
};

// ─── Thú cưng (UC24) ───

function petChecks(b: Record<string, unknown>, partial: boolean): Check[] {
  const required = (key: string) => !partial || has(b[key]);
  return [
    ['name', 'Vui lòng nhập tên thú cưng', required('name') && !isText(b.name)],
    ['species', 'Loài phải là DOG, CAT hoặc OTHER', required('species') && !SPECIES.includes(b.species as Species)],
    ['sex', 'Giới tính phải là MALE, FEMALE hoặc UNKNOWN', required('sex') && !SEXES.includes(b.sex as Sex)],
    ['birthDate', 'Ngày sinh không hợp lệ', isText(b.birthDate) && !DATE.test(String(b.birthDate))],
    ['birthDate', 'Ngày sinh không được ở tương lai', isText(b.birthDate) && String(b.birthDate) > todayVn()],
    ['breed', 'Giống tối đa 100 ký tự', tooLong(b.breed, 100)],
    ['color', 'Màu lông tối đa 50 ký tự', tooLong(b.color, 50)],
    ['photoUrl', 'Đường dẫn ảnh tối đa 500 ký tự', tooLong(b.photoUrl, 500)],
  ];
}

function newPet(s: State, customerId: number, b: Record<string, unknown>): PetRow {
  const pet: PetRow = {
    petId: s.nextPetId++,
    customerId,
    name: String(b.name).trim(),
    species: b.species as Species,
    sex: b.sex as Sex,
    breed: optional(b.breed),
    birthDate: optional(b.birthDate),
    birthDateEstimated: b.birthDateEstimated === true,
    color: optional(b.color),
    isNeutered: typeof b.isNeutered === 'boolean' ? b.isNeutered : null,
    photoUrl: optional(b.photoUrl),
    deceasedOn: null,
    speciesLocked: false,
    deletable: true,
    busy: null,
  };
  s.pets.push(pet);
  return pet;
}

const addPetForCustomer: MockHandler = (req) => {
  const b = bodyOf(req);
  const bad = invalid(petChecks(b, false));
  if (bad) return bad;
  if (!isDesk(req)) return denied();
  const owner = findCustomer(Number(req.params.customerId));
  if (!owner) return notFound('khách hàng', req.params.customerId);
  const s = load();
  const pet = newPet(s, owner.customerId, b);
  save(s);
  markHasData(owner.customerId);
  return ok({ pet: responseOf(s, pet), duplicateNameWarning: hasTwin(s, pet) }, 201, 'Đã thêm thú cưng');
};

const listMyPets: MockHandler = (req) => {
  if (!isCustomer(req)) return denied();
  const own = ownProfile(req);
  if (isReply(own)) return own;
  const s = load();
  return ok(s.pets.filter((p) => p.customerId === own.customerId).map((p) => responseOf(s, p)));
};

const addMyPet: MockHandler = (req) => {
  const b = bodyOf(req);
  const bad = invalid(petChecks(b, false));
  if (bad) return bad;
  if (!isCustomer(req)) return denied();
  const own = ownProfile(req);
  if (isReply(own)) return own;
  const s = load();
  const pet = newPet(s, own.customerId, b);
  save(s);
  markHasData(own.customerId);
  return ok({ pet: responseOf(s, pet), duplicateNameWarning: hasTwin(s, pet) }, 201, 'Đã thêm thú cưng');
};

const getPet: MockHandler = (req) => {
  const s = load();
  const pet = petFor(req, s, 'read');
  return isReply(pet) ? pet : ok(responseOf(s, pet));
};

const updatePet: MockHandler = (req) => {
  const b = bodyOf(req);
  const bad = invalid(petChecks(b, true));
  if (bad) return bad;
  const s = load();
  const pet = petFor(req, s, 'write');
  if (isReply(pet)) return pet;
  if (pet.deceasedOn) return ruleFail('BR-KH-05', 'Thú đã mất, hồ sơ chỉ đọc');
  if (has(b.species) && b.species !== pet.species && pet.speciesLocked) {
    return ruleFail('BR-KH-03', 'Không đổi được loài của thú đã có bệnh án hoặc mũi tiêm');
  }
  if (has(b.name)) pet.name = String(b.name).trim();
  if (has(b.species)) pet.species = b.species as Species;
  if (has(b.sex)) pet.sex = b.sex as Sex;
  if (has(b.breed)) pet.breed = optional(b.breed);
  if (has(b.birthDate)) pet.birthDate = optional(b.birthDate);
  if (has(b.birthDateEstimated)) pet.birthDateEstimated = b.birthDateEstimated === true;
  if (has(b.color)) pet.color = optional(b.color);
  if (has(b.isNeutered)) pet.isNeutered = b.isNeutered === true;
  if (has(b.photoUrl)) pet.photoUrl = optional(b.photoUrl);
  save(s);
  const renamed = has(b.name) || has(b.species);
  return ok({ pet: responseOf(s, pet), duplicateNameWarning: renamed && hasTwin(s, pet) }, 200, 'Đã cập nhật thú cưng');
};

const markDeceased: MockHandler = (req) => {
  const b = bodyOf(req);
  const bad = invalid([
    ['deceasedOn', 'Vui lòng chọn ngày mất', !isText(b.deceasedOn)],
    ['deceasedOn', 'Ngày mất không hợp lệ', isText(b.deceasedOn) && !DATE.test(String(b.deceasedOn))],
    ['confirmed', 'Cần xác nhận trước khi đánh dấu đã mất', b.confirmed !== true],
  ]);
  if (bad) return bad;
  const s = load();
  const pet = petFor(req, s, 'write');
  if (isReply(pet)) return pet;
  if (pet.busy === 'OPEN_VISIT') return ruleFail('BR-KH-05', 'Thú đang có lượt khám chưa kết thúc');
  if (pet.busy === 'BOARDING') return ruleFail('BR-KH-05', 'Thú đang lưu trú, hãy kết thúc lưu trú trước (BR-LT-12)');
  if (pet.deceasedOn) return fail('INVALID_STATE_TRANSITION', 'Thú đã được đánh dấu đã mất, không hoàn tác được', 409);
  pet.deceasedOn = String(b.deceasedOn);
  pet.deletable = false;
  save(s);
  return ok(responseOf(s, pet), 200, 'Đã đánh dấu thú cưng đã mất');
};

const deletePet: MockHandler = (req) => {
  const s = load();
  const pet = petFor(req, s, 'write');
  if (isReply(pet)) return pet;
  if (pet.deceasedOn) return ruleFail('BR-KH-05', 'Thú đã mất, hồ sơ chỉ đọc');
  if (!pet.deletable) return ruleFail('BR-KH-06', 'Thú đã có lịch hẹn, đặt chỗ, lượt khám hoặc Order — hãy dùng đánh dấu đã mất');
  s.pets = s.pets.filter((p) => p.petId !== pet.petId);
  s.weights = s.weights.filter((w) => w.petId !== pet.petId);
  save(s);
  return noContent();
};

// ─── Chuyển chủ (UC26) ───

const BUSY_REASON: Record<Busy, string> = {
  OPEN_VISIT: 'Thú đang có lượt khám chưa kết thúc',
  BOARDING: 'Thú đang lưu trú',
  PENDING_ORDER: 'Thú còn Order chưa thanh toán',
};

const transferOwner: MockHandler = (req) => {
  const b = bodyOf(req);
  const bad = invalid([
    ['newCustomerId', 'Vui lòng chọn chủ mới', !Number.isInteger(b.newCustomerId)],
    ['previousOwnerConfirmed', 'Cần xác nhận chủ cũ có mặt hoặc đã đồng ý', b.previousOwnerConfirmed !== true],
  ]);
  if (bad) return bad;
  if (!isDesk(req)) return denied();
  const s = load();
  const pet = s.pets.find((p) => p.petId === Number(req.params.petId));
  if (!pet) return notFound('thú cưng', req.params.petId);
  const newOwner = findCustomer(Number(b.newCustomerId));
  if (!newOwner) return notFound('khách hàng', String(b.newCustomerId));
  if (newOwner.customerId === pet.customerId) {
    return fail('VALIDATION_FAILED', 'newCustomerId: Chủ mới phải khác chủ hiện tại', 400);
  }
  if (pet.deceasedOn) return ruleFail('BR-KH-05', 'Thú đã mất, không chuyển chủ được');
  if (pet.busy) return ruleFail('BR-KH-08', `${BUSY_REASON[pet.busy]}, chưa chuyển chủ được`);
  pet.customerId = newOwner.customerId;
  save(s);
  markHasData(newOwner.customerId);
  return ok(responseOf(s, pet), 200, 'Đã chuyển chủ thú cưng');
};

// ─── Cân nặng (BR-KH-04) ───

const listWeights: MockHandler = (req) => {
  const s = load();
  const pet = petFor(req, s, 'read');
  if (isReply(pet)) return pet;
  return ok(
    s.weights
      .filter((w) => w.petId === pet.petId)
      .sort(byNewest)
      .map(weightOf),
  );
};

/** Chỉ khách là chủ hiện tại tự khai; VET / người nhận thú ghi qua module visit, boarding. */
const addWeight: MockHandler = (req) => {
  const b = bodyOf(req);
  const bad = invalid([
    ['weightKg', 'Vui lòng nhập cân nặng', typeof b.weightKg !== 'number' || Number.isNaN(b.weightKg)],
    ['measuredAt', 'Thời điểm đo không hợp lệ', isText(b.measuredAt) && Number.isNaN(Date.parse(String(b.measuredAt)))],
  ]);
  if (bad) return bad;
  if (!isCustomer(req)) return denied();
  const s = load();
  const pet = petFor(req, s, 'write');
  if (isReply(pet)) return pet;
  if (pet.deceasedOn) return ruleFail('BR-KH-05', 'Thú đã mất, hồ sơ chỉ đọc');
  if ((b.weightKg as number) <= 0) return ruleFail('BR-KH-04', 'Cân nặng phải lớn hơn 0');
  const record: WeightRow = {
    weightRecordId: s.nextWeightId++,
    petId: pet.petId,
    weightKg: b.weightKg as number,
    source: 'OWNER',
    visitId: null,
    boardingBookingId: null,
    measuredAt: isText(b.measuredAt) ? String(b.measuredAt) : new Date().toISOString(),
  };
  s.weights.push(record);
  save(s);
  return ok(weightOf(record), 201, 'Đã ghi cân nặng');
};

// ─── Hồ sơ sức khỏe (UC25, visit-v1 #21) ───

const getHealthRecord: MockHandler = (req) => {
  if (!isCustomer(req) && !canLookUp(req)) return denied();
  const s = load();
  const pet = s.pets.find((p) => p.petId === Number(req.params.petId));
  let own: MockCustomer | null = null;
  if (isCustomer(req)) {
    const profile = ownProfile(req);
    if (isReply(profile)) return profile;
    own = profile;
  }
  if (!pet) return notFound('thú cưng', req.params.petId);
  // visit-v1: khách không phải chủ hiện tại → 403.
  if (own && pet.customerId !== own.customerId) return scopeDenied();
  const record = HEALTH[pet.petId] ?? { visits: [], vaccinations: [], upcomingVaccinations: [], upcomingFollowUpDate: null };
  // Khách không nhận ghi chú nội bộ và bản bổ sung nội bộ (BR-KH-07, BR-KB-01).
  const visits = own
    ? record.visits.map(({ internalNote: _note, ...v }) => ({ ...v, addenda: v.addenda.filter((a) => !a.isInternal) }))
    : record.visits;
  return ok({ petId: pet.petId, ...record, visits });
};

export const customerPetRoutes: Record<string, MockHandler> = {
  'GET /api/customers': searchCustomers,
  'POST /api/customers': createCounterCustomer,
  'GET /api/customers/:customerId': getCustomer,
  'PATCH /api/customers/:customerId': updateCustomer,
  'POST /api/customers/:customerId/pets': addPetForCustomer,
  'GET /api/me/pets': listMyPets,
  'POST /api/me/pets': addMyPet,
  'GET /api/pets/:petId': getPet,
  'PATCH /api/pets/:petId': updatePet,
  'DELETE /api/pets/:petId': deletePet,
  'POST /api/pets/:petId/mark-deceased': markDeceased,
  'POST /api/pets/:petId/transfer-owner': transferOwner,
  'GET /api/pets/:petId/weights': listWeights,
  'POST /api/pets/:petId/weights': addWeight,
  'GET /api/pets/:petId/health-record': getHealthRecord,
};
