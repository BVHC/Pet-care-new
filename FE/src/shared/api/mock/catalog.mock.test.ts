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

describe('surgery / inpatient services (T57, 09 §B–C ASSUMPTION A1)', () => {
  type Row = { serviceId: number; name: string; group: string; medicalType: string | null; price: number; isActive: boolean };
  const listAll = async () => (await client.get('/api/services', await manager())).data.data as Row[];

  it('seeds 4 SURGERY and 4 INPATIENT services in the MEDICAL group, same data as CLINICAL_SEED_SERVICES', async () => {
    const tier3 = (await listAll()).filter((s) => s.serviceId >= 7000).sort((a, b) => a.serviceId - b.serviceId);
    expect(tier3.map(({ serviceId, name, price, isActive, medicalType }) => ({ serviceId, name, price, isActive, medicalType }))).toEqual(
      CLINICAL_SEED_SERVICES.map(({ serviceId, name, price, isActive, medicalType }) => ({ serviceId, name, price, isActive, medicalType })),
    );
    expect(tier3.every((s) => s.group === 'MEDICAL')).toBe(true);
    expect(tier3.filter((s) => s.medicalType === 'SURGERY')).toHaveLength(4);
    expect(tier3.filter((s) => s.medicalType === 'INPATIENT')).toHaveLength(4);
  });

  it('adds the missing ones to a state saved before T57 and keeps what was already saved', async () => {
    const auth = await manager();
    await client.patch('/api/services/1', { price: 160_000 }, auth);
    const stored = JSON.parse(localStorage.getItem('petcare-mock-catalog')!);
    stored.services = stored.services.filter((s: Row) => s.serviceId < 7000);
    localStorage.setItem('petcare-mock-catalog', JSON.stringify(stored));

    const after = await listAll();
    expect(after.filter((s) => s.serviceId >= 7000)).toHaveLength(8);
    expect(after.find((s) => s.serviceId === 1)?.price).toBe(160_000);
  });

  it('does not throw on an empty or broken saved state', async () => {
    for (const raw of ['{}', 'khong phai json']) {
      localStorage.setItem('petcare-mock-catalog', raw);
      expect((await listAll()).filter((s) => s.serviceId >= 7000)).toHaveLength(8);
    }
  });

  it('lets the chain manager create and price them like any MEDICAL service; other groups still have no type (BR-SP-06)', async () => {
    const auth = await manager();
    const created = await client.post('/api/services', { name: 'Cắt u mới', group: 'MEDICAL', medicalType: 'SURGERY', price: 2_100_000 }, auth);
    expect(created.status).toBe(201);
    expect(created.data.data).toMatchObject({ group: 'MEDICAL', medicalType: 'SURGERY', price: 2_100_000 });
    const repriced = await client.patch('/api/services/7101', { price: 380_000 }, auth);
    expect(repriced.data.data).toMatchObject({ serviceId: 7101, medicalType: 'INPATIENT', price: 380_000 });
    const grooming = await failure(client.post('/api/services', { name: 'Spa', group: 'GROOMING', medicalType: 'SURGERY', price: 100_000 }, auth));
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
