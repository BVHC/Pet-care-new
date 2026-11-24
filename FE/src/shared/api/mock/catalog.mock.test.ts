import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it } from 'vitest';
import { ruleIdOf } from '../api-error';
import { CLINICAL_SEED_SERVICES } from './clinical-shared';
import { MOCK_PASSWORD } from './identity.mock';
import { installMockApi } from './mock-api';

// Dữ liệu mẫu (catalog.mock.ts): loại vaccine #1 "Dại" (chó) đã gắn sản phẩm và phác đồ, #5 "Ho cũi" chưa dùng.
let client: AxiosInstance;

beforeEach(() => {
  client = axios.create();
  installMockApi(client);
});

async function as(email: string) {
  const res = await client.post('/api/auth/login', { email, password: MOCK_PASSWORD });
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}
const manager = () => as('chuoi@petcare.vn');

async function failure(request: Promise<unknown>): Promise<AxiosResponse> {
  try {
    await request;
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return e.response;
    throw e;
  }
  throw new Error('expected the request to fail');
}

const NEW_PRODUCT = {
  categoryId: 1,
  sku: 'TEST-001',
  name: 'Sữa tắm thử',
  productType: 'GOODS',
  isPrescription: false,
  tracksExpiry: false,
  unit: 'chai',
  price: 99_000,
};

describe('products', () => {
  it('lists products as a page and filters them', async () => {
    const res = await client.get('/api/products', { params: { productType: 'VACCINE', q: 'dại' }, ...(await manager()) });
    expect(res.data.data).toMatchObject({ page: 0, size: 20, totalElements: 1, totalPages: 1 });
    expect(res.data.data.content).toEqual([expect.objectContaining({ productType: 'VACCINE', vaccineTypeId: 1 })]);
  });

  it('leaves prescription drugs out of a retailOnly list (A4, BR-SP-01)', async () => {
    const res = await client.get('/api/products', { params: { retailOnly: true, size: 100 }, ...(await manager()) });
    expect(res.data.data.content.length).toBeGreaterThan(0);
    expect(res.data.data.content.every((p: { isPrescription: boolean }) => !p.isPrescription)).toBe(true);
  });

  it('creates a product that then shows in the list', async () => {
    const auth = await manager();
    const created = await client.post('/api/products', NEW_PRODUCT, auth);
    expect(created.status).toBe(201);
    expect(created.data.data).toMatchObject({ ...NEW_PRODUCT, productId: expect.any(Number), isActive: true });

    const list = await client.get('/api/products', { params: { q: 'TEST-001' }, ...auth });
    expect(list.data.data.content).toHaveLength(1);
  });

  it.each([
    ['a vaccine without a vaccine type', { productType: 'VACCINE', tracksExpiry: true }, 'BR-SP-07'],
    ['goods flagged as prescription', { isPrescription: true }, 'BR-SP-01'],
    ['a prescription drug without expiry tracking', { productType: 'DRUG', isPrescription: true }, 'BR-SP-05'],
  ])('refuses %s', async (_case, patch, rule) => {
    const res = await failure(client.post('/api/products', { ...NEW_PRODUCT, ...patch }, await manager()));
    expect(ruleIdOf(res.data.message)).toBe(rule);
  });

  it('refuses a duplicate SKU as a validation error without a rule code', async () => {
    const res = await failure(client.post('/api/products', { ...NEW_PRODUCT, sku: 'AMOX-250' }, await manager()));
    expect(res.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'SKU đã tồn tại' });
  });

  // BE ProductService.update: trường vắng hoặc null = giữ nguyên; "" được ghi như "" (T49 nhóm 2, hàng 4).
  it('PATCH keeps the description when it is absent or null and clears it only with ""', async () => {
    const auth = await manager();
    const id = (await client.post('/api/products', { ...NEW_PRODUCT, description: 'Mô tả cũ', imageUrl: '/imgs/a.jpg' }, auth)).data.data.productId;
    const read = async () => (await client.get(`/api/products/${id}`, auth)).data.data;

    await client.patch(`/api/products/${id}`, { name: 'Sữa tắm thử 2' }, auth);
    expect(await read()).toMatchObject({ name: 'Sữa tắm thử 2', description: 'Mô tả cũ', imageUrl: '/imgs/a.jpg' });
    await client.patch(`/api/products/${id}`, { description: null, imageUrl: null }, auth);
    expect(await read()).toMatchObject({ description: 'Mô tả cũ', imageUrl: '/imgs/a.jpg' });
    const cleared = await client.patch(`/api/products/${id}`, { description: '' }, auth);
    expect(cleared.data.data.description).toBe('');
    expect(await read()).toMatchObject({ description: '', imageUrl: '/imgs/a.jpg' });
  });

  it('answers 404 for an unknown product', async () => {
    const res = await failure(client.get('/api/products/9999', await manager()));
    expect(res.data).toMatchObject({ statusCode: 404, message: 'Không tìm thấy Sản phẩm #9999' });
  });
});

// catalog-v1 galleryUrls (ảnh phụ có thứ tự). PATCH: vắng / null = giữ nguyên, [] = xóa hết, mảng khác = THAY cả danh sách.
// Bean Validation chạy trên thân thô (trước khi cắt khoảng trắng và bỏ trùng); BE rồi mới chuẩn hóa: trim, giữ lần xuất hiện đầu.
describe('product gallery (galleryUrls)', () => {
  const create = async (galleryUrls?: unknown) => {
    const auth = await manager();
    const sku = `GAL-${Math.random().toString(36).slice(2, 8)}`;
    const res = await client.post('/api/products', { ...NEW_PRODUCT, sku, ...(galleryUrls === undefined ? {} : { galleryUrls }) }, auth);
    return { auth, id: res.data.data.productId as number, product: res.data.data };
  };
  const readGallery = async (auth: Awaited<ReturnType<typeof manager>>, id: number) => (await client.get(`/api/products/${id}`, auth)).data.data.galleryUrls;
  const message = (res: AxiosResponse) => res.data.message as string;
  const LONG = (n: number) => `/${'a'.repeat(n - 1)}`;

  it('dữ liệu mẫu: sản phẩm 1 có 2 ảnh phụ, sản phẩm 3 không có; danh sách và chi tiết đều có trường', async () => {
    const auth = await manager();
    const one = (await client.get('/api/products/1', auth)).data.data;
    expect(one.galleryUrls).toEqual(['/imgs/prod2.jpg', '/imgs/prod3.jpg']);
    expect((await client.get('/api/products/3', auth)).data.data.galleryUrls).toEqual([]);
    const list = (await client.get('/api/products', { params: { size: 100 }, ...auth })).data.data.content as { galleryUrls: string[] }[];
    expect(list.every((p) => Array.isArray(p.galleryUrls))).toBe(true);
  });

  it('POST không gửi galleryUrls thì lưu []; gửi mảng thì lưu đã cắt khoảng trắng, bỏ trùng, giữ thứ tự', async () => {
    expect((await create()).product.galleryUrls).toEqual([]);
    const { product } = await create([' /b.jpg ', '/a.jpg', '/b.jpg', '/c.jpg']);
    expect(product.galleryUrls).toEqual(['/b.jpg', '/a.jpg', '/c.jpg']);
  });

  it('PATCH: vắng hoặc null giữ nguyên; [] xóa hết; mảng khác THAY cả danh sách (không cộng dồn) theo thứ tự gửi', async () => {
    const { auth, id } = await create(['/a.jpg', '/b.jpg']);
    await client.patch(`/api/products/${id}`, { name: 'Đổi tên' }, auth);
    expect(await readGallery(auth, id)).toEqual(['/a.jpg', '/b.jpg']);
    await client.patch(`/api/products/${id}`, { galleryUrls: null }, auth);
    expect(await readGallery(auth, id)).toEqual(['/a.jpg', '/b.jpg']);
    const replaced = await client.patch(`/api/products/${id}`, { galleryUrls: ['/c.jpg', '/b.jpg'] }, auth);
    expect(replaced.data.data.galleryUrls).toEqual(['/c.jpg', '/b.jpg']);
    expect(await readGallery(auth, id)).toEqual(['/c.jpg', '/b.jpg']);
    await client.patch(`/api/products/${id}`, { galleryUrls: [] }, auth);
    expect(await readGallery(auth, id)).toEqual([]);
  });

  it('đủ 8 ảnh được; 9 ảnh bị 400 VALIDATION_FAILED "Tối đa 8 ảnh phụ"; sản phẩm không đổi', async () => {
    const eight = Array.from({ length: 8 }, (_, i) => `/p${i}.jpg`);
    const { auth, id } = await create(eight);
    expect(await readGallery(auth, id)).toEqual(eight);
    const res = await failure(client.patch(`/api/products/${id}`, { galleryUrls: [...eight, '/p8.jpg'] }, auth));
    expect(res.status).toBe(400);
    expect(res.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'galleryUrls: Tối đa 8 ảnh phụ' });
    expect(await readGallery(auth, id)).toEqual(eight);
  });

  it('giới hạn 8 tính trên thân thô: 9 phần tử trùng nhau (chuẩn hóa còn 1) vẫn bị từ chối, còn 8 phần tử trùng nhau thì được', async () => {
    const { auth, id } = await create();
    const nine = await failure(client.patch(`/api/products/${id}`, { galleryUrls: Array(9).fill('/same.jpg') }, auth));
    expect(message(nine)).toBe('galleryUrls: Tối đa 8 ảnh phụ');
    await client.patch(`/api/products/${id}`, { galleryUrls: Array(8).fill('/same.jpg') }, auth);
    expect(await readGallery(auth, id)).toEqual(['/same.jpg']);
  });

  it('phần tử rỗng, chỉ khoảng trắng hoặc null bị 400 kèm đường dẫn trường galleryUrls[i]; không được bỏ im lặng', async () => {
    const { auth, id } = await create(['/a.jpg']);
    for (const [bad, index] of [
      [['/x.jpg', ''], 1],
      [['   '], 0],
      [[null, '/x.jpg'], 0],
    ] as const) {
      const res = await failure(client.patch(`/api/products/${id}`, { galleryUrls: bad }, auth));
      expect(res.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: `galleryUrls[${index}]: Đường dẫn ảnh phụ không được để trống` });
    }
    const post = await failure(client.post('/api/products', { ...NEW_PRODUCT, sku: 'GAL-BLANK', galleryUrls: [' '] }, auth));
    expect(message(post)).toBe('galleryUrls[0]: Đường dẫn ảnh phụ không được để trống');
    expect(await readGallery(auth, id)).toEqual(['/a.jpg']);
  });

  it('mỗi phần tử tối đa 500 ký tự (tính trên chuỗi thô, trước khi cắt); 500 được, 501 bị 400', async () => {
    const { auth, id } = await create();
    await client.patch(`/api/products/${id}`, { galleryUrls: [LONG(500)] }, auth);
    const res = await failure(client.patch(`/api/products/${id}`, { galleryUrls: ['/ok.jpg', LONG(501)] }, auth));
    expect(res.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', message: 'galleryUrls[1]: Đường dẫn ảnh phụ tối đa 500 ký tự' });
    expect(await readGallery(auth, id)).toEqual([LONG(500)]);
  });

  // BE trả các vi phạm cùng lúc thành MỘT chuỗi nối bằng "; " nhưng THỨ TỰ KHÔNG ỔN ĐỊNH (Bean Validation trả tập hợp; đo 24/11/2026
  // trên BE thật: 4 thứ tự khác nhau trong 5 lần). Nên chỉ so tập hợp các đoạn; mock giữ một thứ tự cố định (số lượng trước, rồi theo chỉ số).
  it('nhiều lỗi cùng lúc được nối bằng "; " trong MỘT thông báo (so theo tập hợp, không so thứ tự)', async () => {
    const { auth, id } = await create();
    const list = ['', null, LONG(501), ...Array(6).fill('/x.jpg')];
    const res = await failure(client.patch(`/api/products/${id}`, { galleryUrls: list }, auth));
    expect(res.data.errorCode).toBe('VALIDATION_FAILED');
    expect(new Set(message(res).split('; '))).toEqual(
      new Set([
        'galleryUrls: Tối đa 8 ảnh phụ',
        'galleryUrls[0]: Đường dẫn ảnh phụ không được để trống',
        'galleryUrls[1]: Đường dẫn ảnh phụ không được để trống',
        'galleryUrls[2]: Đường dẫn ảnh phụ tối đa 500 ký tự',
      ]),
    );
  });

  it('lỗi gallery đi cùng lỗi trường khác trong cùng một thông báo (tên sản phẩm rỗng, giá âm, 9 ảnh)', async () => {
    const { auth, id } = await create();
    const res = await failure(client.patch(`/api/products/${id}`, { name: ' ', price: -1, galleryUrls: Array.from({ length: 9 }, (_, i) => `/p${i}.jpg`) }, auth));
    expect(new Set(message(res).split('; '))).toEqual(
      new Set(['name: Tên sản phẩm không được để trống', 'price: Giá không được âm', 'galleryUrls: Tối đa 8 ảnh phụ']),
    );
  });

  // Đo trên BE thật (24/11/2026): 401 trước, rồi 400 (thân sai) TRƯỚC 403 (sai vai) TRƯỚC 404 (không có sản phẩm); POST cũng vậy.
  it('thứ tự lỗi như BE thật: 400 (thân sai) trước 403 (không phải SUPER_MANAGER) trước 404; gallery hợp lệ bởi bác sĩ vẫn 403', async () => {
    const { id } = await create();
    const vet = await as('doctor@store1.vn');
    const nine = Array.from({ length: 9 }, (_, i) => `/p${i}.jpg`);
    const badPatch = await failure(client.patch(`/api/products/${id}`, { galleryUrls: nine }, vet));
    expect(badPatch.data).toMatchObject({ statusCode: 400, errorCode: 'VALIDATION_FAILED', message: 'galleryUrls: Tối đa 8 ảnh phụ' });
    const badPost = await failure(client.post('/api/products', { ...NEW_PRODUCT, sku: 'GAL-VET', galleryUrls: nine }, vet));
    expect(badPost.data).toMatchObject({ statusCode: 400, errorCode: 'VALIDATION_FAILED' });
    const okPatch = await failure(client.patch(`/api/products/${id}`, { galleryUrls: ['/a.jpg'] }, vet));
    expect(okPatch.data).toMatchObject({ statusCode: 403, errorCode: 'ACCESS_DENIED', message: 'Không có quyền thực hiện thao tác này' });

    const auth = await manager();
    const missingBad = await failure(client.patch('/api/products/999999999', { galleryUrls: nine }, auth));
    expect(missingBad.data).toMatchObject({ statusCode: 400, errorCode: 'VALIDATION_FAILED' });
    const missing = await failure(client.patch('/api/products/999999999', { galleryUrls: ['/a.jpg'] }, auth));
    expect(missing.data).toMatchObject({ statusCode: 404, errorCode: 'RESOURCE_NOT_FOUND', message: 'Không tìm thấy Sản phẩm #999999999' });
  });

  it('không đăng nhập thì 401 dù thân sai', async () => {
    const res = await failure(client.patch('/api/products/1', { galleryUrls: Array(9).fill('/a.jpg') }));
    expect(res.status).toBe(401);
  });

  it.each([
    ['một chuỗi', '/a.jpg'],
    ['một đối tượng', { url: '/a.jpg' }],
    ['mảng có phần tử là đối tượng', ['/a.jpg', { url: '/b.jpg' }]],
  ])('galleryUrls là %s: 400 MALFORMED_REQUEST (sai kiểu JSON, không phải VALIDATION_FAILED), cả POST lẫn PATCH', async (_case, galleryUrls) => {
    const { auth, id } = await create();
    const expected = { statusCode: 400, errorCode: 'MALFORMED_REQUEST', message: 'Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu' };
    expect((await failure(client.patch(`/api/products/${id}`, { galleryUrls }, auth))).data).toMatchObject(expected);
    expect((await failure(client.post('/api/products', { ...NEW_PRODUCT, sku: 'GAL-BAD-TYPE', galleryUrls }, auth))).data).toMatchObject(expected);
  });

  it('PATCH sản phẩm trả message "Đã cập nhật sản phẩm" như BE thật', async () => {
    const { auth, id } = await create();
    expect((await client.patch(`/api/products/${id}`, { galleryUrls: ['/a.jpg'] }, auth)).data.message).toBe('Đã cập nhật sản phẩm');
  });

  it('state cũ lưu từ trước (chưa có galleryUrls) vẫn đọc ra []', async () => {
    const { auth, id } = await create();
    const stored = JSON.parse(localStorage.getItem('petcare-mock-catalog')!);
    for (const p of stored.products) delete p.galleryUrls;
    localStorage.setItem('petcare-mock-catalog', JSON.stringify(stored));
    expect(await readGallery(auth, id)).toEqual([]);
    expect((await client.get('/api/public/products/2')).data.data.galleryUrls).toEqual([]);
  });
});

describe('services', () => {
  it('requires EXAM or VACCINE for a MEDICAL service (BR-SP-06)', async () => {
    const res = await failure(client.post('/api/services', { name: 'Siêu âm', group: 'MEDICAL', price: 300_000 }, await manager()));
    expect(ruleIdOf(res.data.message)).toBe('BR-SP-06');
  });

  it('requires a species and a positive nightly price for a kennel type (BR-SP-04)', async () => {
    const auth = await manager();
    const noSpecies = await failure(
      client.post('/api/services', { name: 'Chuồng lớn', group: 'BOARDING', price: 300_000, kennelType: { maxWeightKg: 40 } }, auth),
    );
    const free = await failure(
      client.post('/api/services', { name: 'Chuồng lớn', group: 'BOARDING', price: 0, kennelType: { species: 'DOG', maxWeightKg: 40 } }, auth),
    );
    expect([ruleIdOf(noSpecies.data.message), ruleIdOf(free.data.message)]).toEqual(['BR-SP-04', 'BR-SP-04']);
  });

  it('PATCH keeps the description and the medical type when they are absent or null; "" clears the description', async () => {
    const auth = await manager();
    const created = await client.post('/api/services', { name: 'Siêu âm bụng', group: 'MEDICAL', medicalType: 'EXAM', price: 300_000, description: 'Mô tả cũ' }, auth);
    const id = created.data.data.serviceId;

    const same = await client.patch(`/api/services/${id}`, { price: 310_000, description: null, medicalType: null }, auth);
    expect(same.data.data).toMatchObject({ price: 310_000, description: 'Mô tả cũ', medicalType: 'EXAM' });
    const cleared = await client.patch(`/api/services/${id}`, { description: '' }, auth);
    expect(cleared.data.data).toMatchObject({ description: '', medicalType: 'EXAM' });
  });

  it('creates a kennel type and changes its price with PATCH /services/{serviceId}', async () => {
    const auth = await manager();
    const created = await client.post(
      '/api/services',
      { name: 'Chuồng chó lớn', group: 'BOARDING', price: 300_000, kennelType: { species: 'DOG', maxWeightKg: 40 } },
      auth,
    );
    const id = created.data.data.serviceId;
    const updated = await client.patch(`/api/services/${id}`, { price: 320_000 }, auth);
    expect(updated.data.data).toMatchObject({ serviceId: id, price: 320_000, kennelType: { species: 'DOG', maxWeightKg: 40 } });
  });
});

describe('inpatient services (T57, 09 §C ASSUMPTION A1)', () => {
  type Row = { serviceId: number; name: string; group: string; medicalType: string | null; price: number; isActive: boolean };
  const listAll = async () => (await client.get('/api/services', await manager())).data.data as Row[];

  it('seeds 4 INPATIENT services in the MEDICAL group, same data as CLINICAL_SEED_SERVICES, and no SURGERY', async () => {
    const tier3 = (await listAll()).filter((s) => s.serviceId >= 7000).sort((a, b) => a.serviceId - b.serviceId);
    expect(tier3.map(({ serviceId, name, price, isActive, medicalType }) => ({ serviceId, name, price, isActive, medicalType }))).toEqual(
      CLINICAL_SEED_SERVICES.map(({ serviceId, name, price, isActive, medicalType }) => ({ serviceId, name, price, isActive, medicalType })),
    );
    expect(tier3.every((s) => s.group === 'MEDICAL')).toBe(true);
    expect(tier3.filter((s) => s.medicalType === 'SURGERY')).toHaveLength(0);
    expect(tier3.filter((s) => s.medicalType === 'INPATIENT')).toHaveLength(4);
  });

  it('adds the missing ones to a state saved before T57 and keeps what was already saved', async () => {
    const auth = await manager();
    await client.patch('/api/services/1', { price: 160_000 }, auth);
    const stored = JSON.parse(localStorage.getItem('petcare-mock-catalog')!);
    stored.services = stored.services.filter((s: Row) => s.serviceId < 7000);
    localStorage.setItem('petcare-mock-catalog', JSON.stringify(stored));

    const after = await listAll();
    expect(after.filter((s) => s.serviceId >= 7000)).toHaveLength(4);
    expect(after.find((s) => s.serviceId === 1)?.price).toBe(160_000);
  });

  it('does not throw on an empty or broken saved state', async () => {
    for (const raw of ['{}', 'khong phai json']) {
      localStorage.setItem('petcare-mock-catalog', raw);
      expect((await listAll()).filter((s) => s.serviceId >= 7000)).toHaveLength(4);
    }
  });

  it('drops the SURGERY services of a state saved before they were removed', async () => {
    await client.patch('/api/services/1', { price: 160_000 }, await manager());
    const stored = JSON.parse(localStorage.getItem('petcare-mock-catalog')!);
    stored.services.push({ serviceId: 7001, name: 'Triệt sản chó mèo', group: 'MEDICAL', medicalType: 'SURGERY', price: 1_800_000, isActive: true });
    localStorage.setItem('petcare-mock-catalog', JSON.stringify(stored));
    const after = (await listAll()).filter((s) => s.serviceId >= 7000);
    expect(after.map((s) => s.serviceId).sort()).toEqual([7101, 7102, 7103, 7104]);
  });

  it('lets the chain manager create and price them like any MEDICAL service; other groups still have no type (BR-SP-06)', async () => {
    const auth = await manager();
    const created = await client.post('/api/services', { name: 'Nội trú mới', group: 'MEDICAL', medicalType: 'INPATIENT', price: 2_100_000 }, auth);
    expect(created.status).toBe(201);
    expect(created.data.data).toMatchObject({ group: 'MEDICAL', medicalType: 'INPATIENT', price: 2_100_000 });
    const repriced = await client.patch('/api/services/7101', { price: 380_000 }, auth);
    expect(repriced.data.data).toMatchObject({ serviceId: 7101, medicalType: 'INPATIENT', price: 380_000 });
    const grooming = await failure(client.post('/api/services', { name: 'Spa', group: 'GROOMING', medicalType: 'INPATIENT', price: 100_000 }, auth));
    expect(ruleIdOf(grooming.data.message)).toBe('BR-SP-06');
  });
});

describe('vaccine types and protocols', () => {
  it('refuses to delete a vaccine type already in use and deletes an unused one (BR-SP-07)', async () => {
    const auth = await manager();
    const used = await failure(client.delete('/api/vaccine-types/1', auth));
    expect(ruleIdOf(used.data.message)).toBe('BR-SP-07');
    expect((await client.delete('/api/vaccine-types/5', auth)).status).toBe(204);
  });

  it('refuses a protocol whose species is not the species of its vaccine type (BR-SP-02)', async () => {
    const res = await failure(
      client.post(
        '/api/vaccination-protocols',
        { species: 'CAT', vaccineTypeId: 1, doseNumber: 1, intervalDays: 365, minAgeWeeks: 12, requiredForBoarding: true },
        await manager(),
      ),
    );
    expect(ruleIdOf(res.data.message)).toBe('BR-SP-02');
  });

  it('filters protocols by vaccine type', async () => {
    const res = await client.get('/api/vaccination-protocols', { params: { vaccineTypeId: 2 }, ...(await manager()) });
    expect(res.data.data.map((p: { doseNumber: number }) => p.doseNumber)).toEqual([1, 2]);
  });
});

describe('who may read and write (catalog-v1 §B)', () => {
  it('lets a vet read the catalog but not change it', async () => {
    const auth = await as('doctor@store1.vn');
    expect((await client.get('/api/services', auth)).status).toBe(200);
    const res = await failure(client.post('/api/product-categories', { name: 'Mới' }, auth));
    expect(res.data).toMatchObject({ errorCode: 'ACCESS_DENIED', statusCode: 403, message: 'Không có quyền thực hiện thao tác này' });
  });

  it('keeps the technical admin out of the catalog', async () => {
    const res = await failure(client.get('/api/product-categories', await as('admin@petcare.vn')));
    expect(res.status).toBe(403);
  });
});
