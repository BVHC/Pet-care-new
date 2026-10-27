// Mock module catalog (docs/api/catalog-v1.md #1–18): danh mục sản phẩm, sản phẩm, dịch vụ (gồm loại chuồng),
// loại vaccine, phác đồ tiêm. Luật và câu báo lỗi chép từ BE (module/catalog). Đọc: nhân viên A04–A08; ghi: SUPER_MANAGER.
import type { Role } from '../../types/auth';
import type {
  KennelTypeSpec,
  MedicalType,
  Product,
  ProductCategory,
  ProductType,
  Service,
  ServiceGroup,
  Species,
  VaccinationProtocol,
  VaccineType,
} from '../../types/catalog';
import { denied, fail, invalid, isText, noContent, notFound, ok, ruleFail, type Check, type MockHandler, type MockReply } from './envelope';

interface State {
  seq: number;
  categories: ProductCategory[];
  products: Product[];
  services: Service[];
  vaccineTypes: VaccineType[];
  protocols: VaccinationProtocol[];
}

function seed(): State {
  const product = (p: Partial<Product> & Pick<Product, 'productId' | 'categoryId' | 'sku' | 'name' | 'unit' | 'price'>): Product => ({
    productType: 'GOODS',
    isPrescription: false,
    tracksExpiry: false,
    vaccineTypeId: null,
    description: null,
    imageUrl: null,
    isActive: true,
    ...p,
  });
  const service = (s: Partial<Service> & Pick<Service, 'serviceId' | 'name' | 'group' | 'price'>): Service => ({
    medicalType: null,
    priceIsFrom: false,
    description: null,
    isActive: true,
    kennelType: null,
    ...s,
  });
  return {
    seq: 100,
    categories: [
      { categoryId: 1, name: 'Thức ăn', isActive: true },
      { categoryId: 2, name: 'Thuốc', isActive: true },
      { categoryId: 3, name: 'Vaccine', isActive: true },
      { categoryId: 4, name: 'Phụ kiện', isActive: true },
      { categoryId: 5, name: 'Đồ chơi', isActive: false },
    ],
    vaccineTypes: [
      { vaccineTypeId: 1, name: 'Dại', species: 'DOG', isActive: true },
      { vaccineTypeId: 2, name: '5 bệnh', species: 'DOG', isActive: true },
      { vaccineTypeId: 3, name: 'Dại', species: 'CAT', isActive: true },
      { vaccineTypeId: 4, name: '4 bệnh', species: 'CAT', isActive: true },
      { vaccineTypeId: 5, name: 'Ho cũi', species: 'DOG', isActive: true },
    ],
    products: [
      product({ productId: 1, categoryId: 1, sku: 'RC-MINI-2KG', name: 'Hạt Royal Canin Mini Adult 2kg', unit: 'túi', price: 450_000, imageUrl: '/imgs/prod1.jpg' }),
      product({ productId: 2, categoryId: 1, sku: 'WK-PATE-85', name: 'Pate Whiskas 85g', unit: 'gói', price: 18_000, imageUrl: '/imgs/prod6.jpg' }),
      product({ productId: 3, categoryId: 2, sku: 'AMOX-250', name: 'Amoxicillin 250mg', productType: 'DRUG', isPrescription: true, tracksExpiry: true, unit: 'viên', price: 8_000 }),
      product({ productId: 4, categoryId: 2, sku: 'VITA-PET', name: 'Vitamin tổng hợp PetCare', productType: 'DRUG', tracksExpiry: true, unit: 'lọ', price: 120_000 }),
      product({ productId: 5, categoryId: 3, sku: 'VAC-RABISIN', name: 'Vaccine Dại Rabisin', productType: 'VACCINE', tracksExpiry: true, vaccineTypeId: 1, unit: 'liều', price: 150_000 }),
      product({ productId: 6, categoryId: 3, sku: 'VAC-DHPPI', name: 'Vaccine 5 bệnh Vanguard', productType: 'VACCINE', tracksExpiry: true, vaccineTypeId: 2, unit: 'liều', price: 180_000 }),
      product({ productId: 7, categoryId: 4, sku: 'VONG-CO-S', name: 'Vòng cổ size S', unit: 'cái', price: 65_000, isActive: false }),
    ],
    services: [
      service({ serviceId: 1, name: 'Khám tổng quát', group: 'MEDICAL', medicalType: 'EXAM', price: 150_000 }),
      service({ serviceId: 2, name: 'Tiêm phòng', group: 'MEDICAL', medicalType: 'VACCINE', price: 50_000, priceIsFrom: true }),
      service({ serviceId: 3, name: 'Tắm & sấy', group: 'GROOMING', price: 120_000, priceIsFrom: true }),
      service({ serviceId: 4, name: 'Cắt tỉa lông', group: 'GROOMING', price: 250_000, priceIsFrom: true }),
      service({ serviceId: 5, name: 'Chuồng chó nhỏ', group: 'BOARDING', price: 200_000, kennelType: { species: 'DOG', maxWeightKg: 10 } }),
      service({ serviceId: 6, name: 'Chuồng mèo', group: 'BOARDING', price: 180_000, kennelType: { species: 'CAT', maxWeightKg: 8 } }),
    ],
    protocols: [
      { protocolId: 1, species: 'DOG', vaccineTypeId: 2, doseNumber: 1, intervalDays: 21, minAgeWeeks: 6, requiredForBoarding: true, isActive: true },
      { protocolId: 2, species: 'DOG', vaccineTypeId: 2, doseNumber: 2, intervalDays: 21, minAgeWeeks: 9, requiredForBoarding: true, isActive: true },
      { protocolId: 3, species: 'DOG', vaccineTypeId: 1, doseNumber: 1, intervalDays: 365, minAgeWeeks: 12, requiredForBoarding: true, isActive: true },
      { protocolId: 4, species: 'CAT', vaccineTypeId: 4, doseNumber: 1, intervalDays: 21, minAgeWeeks: 8, requiredForBoarding: true, isActive: true },
      { protocolId: 5, species: 'CAT', vaccineTypeId: 3, doseNumber: 1, intervalDays: 365, minAgeWeeks: 12, requiredForBoarding: false, isActive: true },
    ],
  };
}

const STATE_KEY = 'petcare-mock-catalog';

function load(): State {
  try {
    const raw = localStorage.getItem(STATE_KEY);
    return raw ? (JSON.parse(raw) as State) : seed();
  } catch {
    return seed();
  }
}

/** Dịch vụ của danh mục, cho mock module khác đọc chéo (như CatalogQueryApi). */
export const catalogServices = (): Service[] => load().services;

/** Sản phẩm của danh mục, cho mock kho đọc chéo (tên, SKU, đơn vị, quản lý hạn dùng). */
export const catalogProducts = (): Product[] => load().products;
/** Danh mục sản phẩm, cho mock công khai (T46) lọc theo danh mục đang dùng (catalog-v1 A1 của T32). */
export const catalogCategories = (): ProductCategory[] => load().categories;
/** Cho mock lâm sàng (T37): loại vaccine và dòng phác đồ để gợi ý / kiểm mũi tiêm (BR-KB-04, BR-SP-02). */
export const catalogVaccineTypes = (): VaccineType[] => load().vaccineTypes;
export const catalogProtocols = (): VaccinationProtocol[] => load().protocols;

function save(s: State) {
  try {
    localStorage.setItem(STATE_KEY, JSON.stringify(s));
  } catch {
    // Không ghi được (chế độ riêng tư): thay đổi chỉ mất khi tải lại.
  }
}

// ─── Quyền (catalog-v1 §B, BE CatalogAccess) ───

const STAFF_READ: readonly Role[] = ['SUPER_MANAGER', 'BRANCH_MANAGER', 'RECEPTIONIST', 'VET', 'CARETAKER'];
const read = (h: MockHandler): MockHandler => (req) => (STAFF_READ.includes(req.account!.role) ? h(req) : denied());
const write = (h: MockHandler): MockHandler => (req) => (req.account!.role === 'SUPER_MANAGER' ? h(req) : denied());

// ─── Đọc dữ liệu vào ───

type Body = Record<string, unknown>;
const bodyOf = (b: unknown) => (b ?? {}) as Body;
const flag = (v: string | undefined) => (v === undefined ? undefined : v === 'true');
const isInt = (v: unknown): v is number => typeof v === 'number' && Number.isInteger(v);
const isBool = (v: unknown): v is boolean => typeof v === 'boolean';
const same = (a: string, b: string) => a.trim().toLowerCase() === b.trim().toLowerCase();
const byName = <T extends { name: string }>(a: T, b: T) => a.name.localeCompare(b.name, 'vi');

const priceChecks = (price: unknown, required: boolean): Check[] => [
  ['price', 'Thiếu giá', required && !isInt(price)],
  ['price', 'Giá không được âm', isInt(price) && price < 0],
];

// ─── Danh mục sản phẩm (#1–3) ───

const listCategories: MockHandler = ({ query }) => {
  const active = flag(query.isActive);
  return ok(load().categories.filter((c) => active === undefined || c.isActive === active).sort(byName));
};

function checkCategoryName(s: State, name: string, exceptId?: number): MockReply | null {
  return s.categories.some((c) => c.categoryId !== exceptId && same(c.name, name))
    ? fail('VALIDATION_FAILED', 'Tên danh mục đã tồn tại', 400)
    : null;
}

const createCategory: MockHandler = ({ body }) => {
  const b = bodyOf(body);
  const bad = invalid([
    ['name', 'Tên danh mục không được để trống', !isText(b.name)],
    ['name', 'Tên danh mục tối đa 100 ký tự', isText(b.name) && b.name.length > 100],
  ]);
  if (bad) return bad;
  const s = load();
  const dup = checkCategoryName(s, b.name as string);
  if (dup) return dup;
  const category: ProductCategory = { categoryId: ++s.seq, name: (b.name as string).trim(), isActive: true };
  s.categories.push(category);
  save(s);
  return ok(category, 201, 'Đã tạo danh mục');
};

const updateCategory: MockHandler = ({ body, params }) => {
  const b = bodyOf(body);
  const s = load();
  const category = s.categories.find((c) => c.categoryId === Number(params.categoryId));
  if (!category) return notFound('Danh mục sản phẩm', params.categoryId);
  const bad = invalid([
    ['name', 'Tên danh mục không được để trống', b.name !== undefined && !isText(b.name)],
    ['name', 'Tên danh mục tối đa 100 ký tự', isText(b.name) && b.name.length > 100],
  ]);
  if (bad) return bad;
  if (isText(b.name)) {
    const dup = checkCategoryName(s, b.name, category.categoryId);
    if (dup) return dup;
    category.name = b.name.trim();
  }
  if (isBool(b.isActive)) category.isActive = b.isActive;
  save(s);
  return ok(category);
};

// ─── Sản phẩm (#4–7) ───

/** Thứ tự như BE: BR-SP-01, BR-SP-05, BR-SP-07. */
function productRule(s: State, type: ProductType, prescription: boolean, tracksExpiry: boolean, vaccineTypeId: number | null | undefined): MockReply | null {
  if (prescription && type !== 'DRUG') return ruleFail('BR-SP-01', 'Chỉ thuốc mới được đánh dấu thuốc kê đơn');
  if ((prescription || type === 'VACCINE') && !tracksExpiry) {
    return ruleFail('BR-SP-05', 'Thuốc kê đơn và vaccine bắt buộc quản lý hạn dùng');
  }
  if (type === 'VACCINE' && vaccineTypeId == null) return ruleFail('BR-SP-07', 'Sản phẩm vaccine phải gắn loại vaccine');
  if (type !== 'VACCINE' && vaccineTypeId != null) return ruleFail('BR-SP-07', 'Chỉ sản phẩm vaccine mới gắn loại vaccine');
  if (vaccineTypeId != null && !s.vaccineTypes.some((v) => v.vaccineTypeId === vaccineTypeId)) {
    return ruleFail('BR-SP-07', 'Loại vaccine không tồn tại');
  }
  return null;
}

const listProducts: MockHandler = ({ query }) => {
  const q = query.q?.trim().toLowerCase();
  const active = flag(query.isActive);
  const page = Math.max(Number(query.page ?? 0) || 0, 0);
  const size = Math.min(Math.max(Number(query.size ?? 20) || 20, 1), 100);
  const found = load()
    .products.filter(
      (p) =>
        (!q || p.name.toLowerCase().includes(q) || p.sku.toLowerCase().includes(q)) &&
        (!query.categoryId || p.categoryId === Number(query.categoryId)) &&
        (!query.productType || p.productType === query.productType) &&
        (!query.vaccineTypeId || p.vaccineTypeId === Number(query.vaccineTypeId)) &&
        (active === undefined || p.isActive === active) &&
        (query.retailOnly !== 'true' || !p.isPrescription),
    )
    .sort((a, b) => byName(a, b) || a.productId - b.productId);
  return ok({
    content: found.slice(page * size, page * size + size),
    page,
    size,
    totalElements: found.length,
    totalPages: Math.ceil(found.length / size),
  });
};

const getProduct: MockHandler = ({ params }) => {
  const product = load().products.find((p) => p.productId === Number(params.productId));
  return product ? ok(product) : notFound('Sản phẩm', params.productId);
};

const PRODUCT_TYPES: readonly ProductType[] = ['GOODS', 'DRUG', 'VACCINE'];

const createProduct: MockHandler = ({ body }) => {
  const b = bodyOf(body);
  const bad = invalid([
    ['categoryId', 'Thiếu danh mục sản phẩm', !isInt(b.categoryId)],
    ['sku', 'SKU không được để trống', !isText(b.sku)],
    ['sku', 'SKU tối đa 50 ký tự', isText(b.sku) && b.sku.length > 50],
    ['name', 'Tên sản phẩm không được để trống', !isText(b.name)],
    ['name', 'Tên sản phẩm tối đa 200 ký tự', isText(b.name) && b.name.length > 200],
    ['productType', 'Thiếu loại sản phẩm', !PRODUCT_TYPES.includes(b.productType as ProductType)],
    ['isPrescription', 'Thiếu cờ thuốc kê đơn', !isBool(b.isPrescription)],
    ['tracksExpiry', 'Thiếu cờ quản lý hạn dùng', !isBool(b.tracksExpiry)],
    ['unit', 'Đơn vị tính không được để trống', !isText(b.unit)],
    ['unit', 'Đơn vị tính tối đa 20 ký tự', isText(b.unit) && b.unit.length > 20],
    ...priceChecks(b.price, true),
  ]);
  if (bad) return bad;
  const s = load();
  const vaccineTypeId = (b.vaccineTypeId as number | null | undefined) ?? null;
  const rule = productRule(s, b.productType as ProductType, b.isPrescription as boolean, b.tracksExpiry as boolean, vaccineTypeId);
  if (rule) return rule;
  if (!s.categories.some((c) => c.categoryId === b.categoryId)) return notFound('Danh mục sản phẩm', b.categoryId as number);
  if (s.products.some((p) => same(p.sku, b.sku as string))) return fail('VALIDATION_FAILED', 'SKU đã tồn tại', 400);
  const product: Product = {
    productId: ++s.seq,
    categoryId: b.categoryId as number,
    sku: (b.sku as string).trim(),
    name: (b.name as string).trim(),
    productType: b.productType as ProductType,
    isPrescription: b.isPrescription as boolean,
    tracksExpiry: b.tracksExpiry as boolean,
    vaccineTypeId,
    unit: (b.unit as string).trim(),
    price: b.price as number,
    description: (b.description as string | undefined) ?? null,
    imageUrl: (b.imageUrl as string | undefined) ?? null,
    isActive: true,
  };
  s.products.push(product);
  save(s);
  return ok(product, 201, 'Đã tạo sản phẩm');
};

const updateProduct: MockHandler = ({ body, params }) => {
  const b = bodyOf(body);
  const s = load();
  const product = s.products.find((p) => p.productId === Number(params.productId));
  if (!product) return notFound('Sản phẩm', params.productId);
  const bad = invalid([
    ['name', 'Tên sản phẩm không được để trống', b.name !== undefined && !isText(b.name)],
    ['unit', 'Đơn vị tính không được để trống', b.unit !== undefined && !isText(b.unit)],
    ...priceChecks(b.price, false),
  ]);
  if (bad) return bad;
  // PATCH như BE (ProductService.update): trường vắng hoặc null = giữ nguyên; mô tả "" = xóa mô tả.
  const next: Product = {
    ...product,
    ...(isInt(b.categoryId) && { categoryId: b.categoryId }),
    ...(isText(b.name) && { name: b.name.trim() }),
    ...(isBool(b.isPrescription) && { isPrescription: b.isPrescription }),
    ...(isBool(b.tracksExpiry) && { tracksExpiry: b.tracksExpiry }),
    ...(b.vaccineTypeId != null && { vaccineTypeId: b.vaccineTypeId as number }),
    ...(isText(b.unit) && { unit: b.unit.trim() }),
    ...(isInt(b.price) && { price: b.price }),
    ...(b.description != null && { description: b.description as string }),
    ...(b.imageUrl != null && { imageUrl: b.imageUrl as string }),
    ...(isBool(b.isActive) && { isActive: b.isActive }),
  };
  const rule = productRule(s, next.productType, next.isPrescription, next.tracksExpiry, next.vaccineTypeId);
  if (rule) return rule;
  if (!s.categories.some((c) => c.categoryId === next.categoryId)) return notFound('Danh mục sản phẩm', next.categoryId);
  Object.assign(product, next);
  save(s);
  return ok(product);
};

// ─── Dịch vụ, gồm loại chuồng (#8–11) ───

const GROUPS: readonly ServiceGroup[] = ['MEDICAL', 'GROOMING', 'BOARDING'];

function serviceRule(group: ServiceGroup, medicalType: MedicalType | null | undefined, price: number, kennel: Partial<KennelTypeSpec> | null | undefined): MockReply | null {
  if (group === 'MEDICAL' && !medicalType) return ruleFail('BR-SP-06', 'Dịch vụ nhóm Khám/Tiêm phải chọn loại Khám hoặc Tiêm');
  if (group !== 'MEDICAL' && medicalType) return ruleFail('BR-SP-06', 'Chỉ dịch vụ nhóm Khám/Tiêm mới có loại');
  if (group !== 'BOARDING' && kennel) return ruleFail('BR-SP-04', 'Chỉ loại chuồng (nhóm Lưu trú) mới có thông số chuồng');
  if (group !== 'BOARDING') return null;
  if (!kennel?.species) return ruleFail('BR-SP-04', 'Loại chuồng phải có loài phù hợp');
  const w = kennel.maxWeightKg;
  if (typeof w !== 'number' || w <= 0 || w > 9999.99 || Math.round(w * 100) !== w * 100) {
    return ruleFail('BR-SP-04', 'Cân nặng tối đa phải lớn hơn 0, không quá 9999,99 kg và có tối đa 2 chữ số thập phân');
  }
  if (price <= 0) return ruleFail('BR-SP-04', 'Giá theo đêm của loại chuồng phải lớn hơn 0');
  return null;
}

const listServices: MockHandler = ({ query }) => {
  const active = flag(query.isActive);
  return ok(
    load()
      .services.filter((x) => (!query.group || x.group === query.group) && (active === undefined || x.isActive === active))
      .sort(byName),
  );
};

const getService: MockHandler = ({ params }) => {
  const service = load().services.find((x) => x.serviceId === Number(params.serviceId));
  return service ? ok(service) : notFound('Dịch vụ', params.serviceId);
};

const createService: MockHandler = ({ body }) => {
  const b = bodyOf(body);
  const bad = invalid([
    ['name', 'Tên dịch vụ không được để trống', !isText(b.name)],
    ['name', 'Tên dịch vụ tối đa 150 ký tự', isText(b.name) && b.name.length > 150],
    ['group', 'Thiếu nhóm dịch vụ', !GROUPS.includes(b.group as ServiceGroup)],
    ...priceChecks(b.price, true),
  ]);
  if (bad) return bad;
  const group = b.group as ServiceGroup;
  const kennel = (b.kennelType as Partial<KennelTypeSpec> | null | undefined) ?? null;
  const rule = serviceRule(group, b.medicalType as MedicalType | undefined, b.price as number, kennel);
  if (rule) return rule;
  const s = load();
  if (s.services.some((x) => same(x.name, b.name as string))) return fail('VALIDATION_FAILED', 'Tên dịch vụ đã tồn tại', 400);
  const service: Service = {
    serviceId: ++s.seq,
    name: (b.name as string).trim(),
    group,
    medicalType: (b.medicalType as MedicalType | undefined) ?? null,
    price: b.price as number,
    priceIsFrom: isBool(b.priceIsFrom) ? b.priceIsFrom : false,
    description: (b.description as string | undefined) ?? null,
    isActive: true,
    kennelType: group === 'BOARDING' ? (kennel as KennelTypeSpec) : null,
  };
  s.services.push(service);
  save(s);
  return ok(service, 201, 'Đã tạo dịch vụ');
};

const updateService: MockHandler = ({ body, params }) => {
  const b = bodyOf(body);
  const s = load();
  const service = s.services.find((x) => x.serviceId === Number(params.serviceId));
  if (!service) return notFound('Dịch vụ', params.serviceId);
  const bad = invalid([
    ['name', 'Tên dịch vụ không được để trống', b.name !== undefined && !isText(b.name)],
    ...priceChecks(b.price, false),
  ]);
  if (bad) return bad;
  const patch = b.kennelType as Partial<KennelTypeSpec> | undefined;
  // Như BE: trường nào của thông số chuồng không gửi thì giữ giá trị cũ.
  const kennel = patch && service.group === 'BOARDING' ? { ...service.kennelType, ...patch } : patch ?? service.kennelType;
  // PATCH như BE (ServiceCatalogService.update): trường vắng hoặc null = giữ nguyên; mô tả "" = xóa mô tả.
  const next: Service = {
    ...service,
    ...(isText(b.name) && { name: b.name.trim() }),
    ...(b.medicalType != null && { medicalType: b.medicalType as MedicalType }),
    ...(isInt(b.price) && { price: b.price }),
    ...(isBool(b.priceIsFrom) && { priceIsFrom: b.priceIsFrom }),
    ...(b.description != null && { description: b.description as string }),
    ...(isBool(b.isActive) && { isActive: b.isActive }),
    kennelType: (kennel as KennelTypeSpec | null | undefined) ?? null,
  };
  const rule = serviceRule(next.group, next.medicalType, next.price, next.kennelType);
  if (rule) return rule;
  if (s.services.some((x) => x.serviceId !== service.serviceId && same(x.name, next.name))) {
    return fail('VALIDATION_FAILED', 'Tên dịch vụ đã tồn tại', 400);
  }
  Object.assign(service, next);
  save(s);
  return ok(service);
};

// ─── Loại vaccine (#12–15) ───

const SPECIES: readonly Species[] = ['DOG', 'CAT', 'OTHER'];

const listVaccineTypes: MockHandler = ({ query }) => {
  const active = flag(query.isActive);
  return ok(
    load()
      .vaccineTypes.filter((v) => (!query.species || v.species === query.species) && (active === undefined || v.isActive === active))
      .sort((a, b) => a.species.localeCompare(b.species) || byName(a, b)),
  );
};

function checkVaccineTypeName(s: State, name: string, species: Species, exceptId?: number): MockReply | null {
  return s.vaccineTypes.some((v) => v.vaccineTypeId !== exceptId && v.species === species && same(v.name, name))
    ? fail('VALIDATION_FAILED', 'Loại vaccine đã tồn tại trong loài này', 400)
    : null;
}

const createVaccineType: MockHandler = ({ body }) => {
  const b = bodyOf(body);
  const bad = invalid([
    ['name', 'Tên loại vaccine không được để trống', !isText(b.name)],
    ['name', 'Tên loại vaccine tối đa 100 ký tự', isText(b.name) && b.name.length > 100],
    ['species', 'Thiếu loài áp dụng', !SPECIES.includes(b.species as Species)],
  ]);
  if (bad) return bad;
  const s = load();
  const dup = checkVaccineTypeName(s, b.name as string, b.species as Species);
  if (dup) return dup;
  const type: VaccineType = { vaccineTypeId: ++s.seq, name: (b.name as string).trim(), species: b.species as Species, isActive: true };
  s.vaccineTypes.push(type);
  save(s);
  return ok(type, 201, 'Đã tạo loại vaccine');
};

const updateVaccineType: MockHandler = ({ body, params }) => {
  const b = bodyOf(body);
  const s = load();
  const type = s.vaccineTypes.find((v) => v.vaccineTypeId === Number(params.vaccineTypeId));
  if (!type) return notFound('Loại vaccine', params.vaccineTypeId);
  const bad = invalid([['name', 'Tên loại vaccine không được để trống', b.name !== undefined && !isText(b.name)]]);
  if (bad) return bad;
  if (isText(b.name)) {
    const dup = checkVaccineTypeName(s, b.name, type.species, type.vaccineTypeId);
    if (dup) return dup;
    type.name = b.name.trim();
  }
  if (isBool(b.isActive)) type.isActive = b.isActive;
  save(s);
  return ok(type);
};

const deleteVaccineType: MockHandler = ({ params }) => {
  const s = load();
  const id = Number(params.vaccineTypeId);
  if (!s.vaccineTypes.some((v) => v.vaccineTypeId === id)) return notFound('Loại vaccine', params.vaccineTypeId);
  if (s.products.some((p) => p.vaccineTypeId === id) || s.protocols.some((p) => p.vaccineTypeId === id)) {
    return ruleFail('BR-SP-07', 'Loại vaccine đã có phác đồ, sản phẩm hoặc mũi tiêm nên không xóa được, chỉ ngừng sử dụng');
  }
  s.vaccineTypes = s.vaccineTypes.filter((v) => v.vaccineTypeId !== id);
  save(s);
  return noContent();
};

// ─── Phác đồ tiêm chủng (#16–18) ───

const MAX_SMALLINT = 32767;

function protocolRule(p: Pick<VaccinationProtocol, 'doseNumber' | 'intervalDays' | 'minAgeWeeks'>): MockReply | null {
  if (p.doseNumber < 1 || p.doseNumber > MAX_SMALLINT) return ruleFail('BR-SP-02', `Mũi thứ mấy phải từ 1 đến ${MAX_SMALLINT}`);
  if (p.intervalDays <= 0) return ruleFail('BR-SP-02', 'Khoảng cách đến mũi kế tiếp phải lớn hơn 0 ngày');
  if (p.minAgeWeeks < 0 || p.minAgeWeeks > MAX_SMALLINT) return ruleFail('BR-SP-02', `Tuổi tối thiểu phải từ 0 đến ${MAX_SMALLINT}`);
  return null;
}

const listProtocols: MockHandler = ({ query }) => {
  const active = flag(query.isActive);
  return ok(
    load()
      .protocols.filter(
        (p) =>
          (!query.species || p.species === query.species) &&
          (!query.vaccineTypeId || p.vaccineTypeId === Number(query.vaccineTypeId)) &&
          (active === undefined || p.isActive === active),
      )
      .sort((a, b) => a.species.localeCompare(b.species) || a.vaccineTypeId - b.vaccineTypeId || a.doseNumber - b.doseNumber),
  );
};

const createProtocol: MockHandler = ({ body }) => {
  const b = bodyOf(body);
  const bad = invalid([
    ['species', 'Thiếu loài', !SPECIES.includes(b.species as Species)],
    ['vaccineTypeId', 'Thiếu loại vaccine', !isInt(b.vaccineTypeId)],
    ['doseNumber', 'Thiếu mũi thứ mấy', !isInt(b.doseNumber)],
    ['intervalDays', 'Thiếu khoảng cách đến mũi kế tiếp', !isInt(b.intervalDays)],
    ['minAgeWeeks', 'Thiếu tuổi tối thiểu', !isInt(b.minAgeWeeks)],
    ['requiredForBoarding', 'Thiếu cờ bắt buộc khi lưu trú', !isBool(b.requiredForBoarding)],
  ]);
  if (bad) return bad;
  const draft = b as unknown as Omit<VaccinationProtocol, 'protocolId' | 'isActive'>;
  const s = load();
  const type = s.vaccineTypes.find((v) => v.vaccineTypeId === draft.vaccineTypeId);
  if (!type) return notFound('Loại vaccine', draft.vaccineTypeId);
  if (type.species !== draft.species) return ruleFail('BR-SP-02', 'Loài của phác đồ phải trùng loài của loại vaccine');
  const rule = protocolRule(draft);
  if (rule) return rule;
  if (s.protocols.some((p) => p.species === draft.species && p.vaccineTypeId === draft.vaccineTypeId && p.doseNumber === draft.doseNumber)) {
    return fail('VALIDATION_FAILED', `Phác đồ đã có mũi thứ ${draft.doseNumber} của loại vaccine này`, 400);
  }
  const protocol: VaccinationProtocol = {
    protocolId: ++s.seq,
    species: draft.species,
    vaccineTypeId: draft.vaccineTypeId,
    doseNumber: draft.doseNumber,
    intervalDays: draft.intervalDays,
    minAgeWeeks: draft.minAgeWeeks,
    requiredForBoarding: draft.requiredForBoarding,
    isActive: true,
  };
  s.protocols.push(protocol);
  save(s);
  return ok(protocol, 201, 'Đã thêm dòng phác đồ');
};

const updateProtocol: MockHandler = ({ body, params }) => {
  const b = bodyOf(body);
  const s = load();
  const protocol = s.protocols.find((p) => p.protocolId === Number(params.protocolId));
  if (!protocol) return notFound('Phác đồ tiêm chủng', params.protocolId);
  const next: VaccinationProtocol = {
    ...protocol,
    ...(isInt(b.intervalDays) && { intervalDays: b.intervalDays }),
    ...(isInt(b.minAgeWeeks) && { minAgeWeeks: b.minAgeWeeks }),
    ...(isBool(b.requiredForBoarding) && { requiredForBoarding: b.requiredForBoarding }),
    ...(isBool(b.isActive) && { isActive: b.isActive }),
  };
  const rule = protocolRule(next);
  if (rule) return rule;
  Object.assign(protocol, next);
  save(s);
  return ok(protocol);
};

export const catalogRoutes: Record<string, MockHandler> = {
  'GET /api/product-categories': read(listCategories),
  'POST /api/product-categories': write(createCategory),
  'PATCH /api/product-categories/:categoryId': write(updateCategory),
  'GET /api/products': read(listProducts),
  'POST /api/products': write(createProduct),
  'GET /api/products/:productId': read(getProduct),
  'PATCH /api/products/:productId': write(updateProduct),
  'GET /api/services': read(listServices),
  'POST /api/services': write(createService),
  'GET /api/services/:serviceId': read(getService),
  'PATCH /api/services/:serviceId': write(updateService),
  'GET /api/vaccine-types': read(listVaccineTypes),
  'POST /api/vaccine-types': write(createVaccineType),
  'PATCH /api/vaccine-types/:vaccineTypeId': write(updateVaccineType),
  'DELETE /api/vaccine-types/:vaccineTypeId': write(deleteVaccineType),
  'GET /api/vaccination-protocols': read(listProtocols),
  'POST /api/vaccination-protocols': write(createProtocol),
  'PATCH /api/vaccination-protocols/:protocolId': write(updateProtocol),
};
