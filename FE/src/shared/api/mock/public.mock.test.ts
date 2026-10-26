import axios, { type AxiosInstance, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it } from 'vitest';
import type { PublicProduct, PublicVet } from '../../types/public-site';
import { issueFefo } from './inventory.mock';
import { MOCK_PASSWORD } from './identity.mock';
import { installMockApi } from './mock-api';

// Dữ liệu mẫu: catalog.mock.ts (sản phẩm 1, 2 hàng thường; 3 thuốc kê đơn; 4 vitamin chưa có dòng tồn; 5, 6 vaccine;
// 7 ngừng kinh doanh; danh mục 5 "Đồ chơi" ngừng), inventory.mock.ts (chi nhánh 1 còn hàng 1, 2, 3, 5, 6; chi nhánh 2 DRAFT
// còn hàng sản phẩm 1), branch.mock.ts (chi nhánh 1 ACTIVE; 2, 3 DRAFT), identity.mock.ts (VET duy nhất: #5 "BS. Minh Anh", chi nhánh 1).
let client: AxiosInstance;

beforeEach(() => {
  client = axios.create();
  installMockApi(client);
});

async function as(email: string) {
  const res = await client.post('/api/auth/login', { email, password: MOCK_PASSWORD });
  return { headers: { Authorization: `Bearer ${res.data.data.accessToken}` } };
}
const chain = () => as('chuoi@petcare.vn');
const admin = () => as('admin@petcare.vn');

async function failure(request: Promise<unknown>): Promise<AxiosResponse> {
  try {
    await request;
  } catch (e) {
    if (axios.isAxiosError(e) && e.response) return e.response;
    throw e;
  }
  throw new Error('expected the request to fail');
}

type Page<T> = { content: T[]; page: number; size: number; totalElements: number; totalPages: number };
const products = async (params: Record<string, unknown> = {}) =>
  (await client.get('/api/public/products', { params })).data.data as Page<PublicProduct>;
const ids = (list: { productId: number }[]) => list.map((p) => p.productId);

// Theo tên (vi): Hạt Royal Canin (1), Pate Whiskas (2), Vaccine 5 bệnh (6), Vaccine Dại (5), Vitamin (4).
const SELLABLE = [1, 2, 6, 5, 4];

describe('sản phẩm công khai (public, UC16; BR-CK-01, BR-CK-03)', () => {
  it('không cần đăng nhập; chỉ sản phẩm đang kinh doanh, không phải thuốc kê đơn, theo tên', async () => {
    const res = await client.get('/api/public/products');
    expect(res.status).toBe(200);
    expect(res.data.data).toMatchObject({ page: 0, size: 20, totalElements: 5, totalPages: 1 });
    expect(ids(res.data.data.content)).toEqual(SELLABLE);
  });

  it('thuốc kê đơn (BR-SP-01) và sản phẩm ngừng kinh doanh không lọt vào danh sách, kể cả khi tìm đúng tên', async () => {
    expect(ids((await products({ size: 100 })).content)).not.toContain(3);
    expect(ids((await products({ size: 100 })).content)).not.toContain(7);
    expect((await products({ q: 'amoxicillin' })).content).toEqual([]);
    expect((await products({ q: 'vòng cổ' })).content).toEqual([]);
  });

  it('vaccine vẫn hiện (BR-CK-01 chỉ loại thuốc kê đơn; BE chưa loại vaccine)', async () => {
    expect(ids((await products({ categoryId: 3 })).content)).toEqual([6, 5]);
  });

  it('mỗi sản phẩm chỉ có đúng các trường của PublicProduct: không lộ số lượng tồn, SKU, cờ kê đơn (BR-CK-03)', async () => {
    const first = (await products()).content[0];
    expect(Object.keys(first).sort()).toEqual(
      ['categoryId', 'categoryName', 'description', 'imageUrl', 'inStockBranchIds', 'name', 'price', 'productId', 'unit'].sort(),
    );
    expect(first).toMatchObject({ productId: 1, name: 'Hạt Royal Canin Mini Adult 2kg', categoryName: 'Thức ăn', price: 450_000, unit: 'túi' });
    const detail = (await client.get('/api/public/products/1')).data.data;
    expect(JSON.stringify(detail)).not.toMatch(/quantity|sku|isPrescription|isActive|productType/i);
  });

  describe('chi nhánh còn hàng (inStockBranchIds)', () => {
    const inStock = async (productId: number) => (await client.get(`/api/public/products/${productId}`)).data.data.inStockBranchIds as number[];

    it('chỉ chi nhánh ACTIVE: sản phẩm 1 còn hàng ở chi nhánh 2 (DRAFT) nhưng chỉ chi nhánh 1 hiện ra', async () => {
      expect(await inStock(1)).toEqual([1]);
    });

    it('sản phẩm chưa có dòng tồn ở đâu cả thì mảng rỗng', async () => {
      expect(await inStock(4)).toEqual([]);
    });

    it('danh sách và chi tiết cùng một kết quả', async () => {
      const row = (await products()).content.find((p) => p.productId === 5)!;
      expect(row.inStockBranchIds).toEqual(await inStock(5));
      expect(row.inStockBranchIds).toEqual([1]);
    });

    it('tồn khả dụng về 0 thì chi nhánh biến khỏi danh sách (BR-KO-01)', async () => {
      expect(await inStock(2)).toEqual([1]);
      expect(issueFefo(1, [{ productId: 2, quantity: 3 }])).toEqual([]);
      expect(await inStock(2)).toEqual([]);
      expect(ids((await products({ branchId: 1 })).content)).not.toContain(2);
    });
  });

  describe('tìm kiếm q', () => {
    it('không phân biệt hoa thường, tìm theo tên (BE: lower(name) LIKE)', async () => {
      expect(ids((await products({ q: 'royal' })).content)).toEqual([1]);
      expect(ids((await products({ q: 'ROYAL CANIN' })).content)).toEqual([1]);
      expect(ids((await products({ q: '  whiskas  ' })).content)).toEqual([2]);
    });

    it('không tìm theo SKU (BE chỉ so tên)', async () => {
      expect((await products({ q: 'RC-MINI' })).content).toEqual([]);
    });

    it('q chỉ có khoảng trắng thì không lọc', async () => {
      expect(ids((await products({ q: '   ' })).content)).toEqual(SELLABLE);
    });

    it('đúng 100 ký tự thì hợp lệ, 101 ký tự thì 400 VALIDATION_FAILED', async () => {
      expect((await client.get('/api/public/products', { params: { q: 'a'.repeat(100) } })).status).toBe(200);
      const res = await failure(client.get('/api/public/products', { params: { q: 'a'.repeat(101) } }));
      expect(res.status).toBe(400);
      expect(res.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', statusCode: 400 });
      expect(res.data.message).toContain('q:');
    });
  });

  describe('lọc theo danh mục và chi nhánh', () => {
    it('categoryId', async () => {
      expect(ids((await products({ categoryId: 1 })).content)).toEqual([1, 2]);
      expect((await products({ categoryId: 999 })).content).toEqual([]);
    });

    it('danh mục ngừng sử dụng thì không có sản phẩm nào để lọc', async () => {
      expect((await products({ categoryId: 5 })).content).toEqual([]);
    });

    it('branchId của chi nhánh ACTIVE: chỉ sản phẩm còn hàng ở đó', async () => {
      expect(ids((await products({ branchId: 1 })).content)).toEqual([1, 2, 6, 5]);
    });

    it.each([2, 3, 999])('branchId %i không ACTIVE (DRAFT hoặc không có): trang rỗng 200, không phải 404', async (branchId) => {
      const res = await client.get('/api/public/products', { params: { branchId } });
      expect(res.status).toBe(200);
      expect(res.data.data).toMatchObject({ content: [], totalElements: 0, totalPages: 0 });
    });

    it('kết hợp q, categoryId và branchId', async () => {
      expect(ids((await products({ q: 'vaccine', categoryId: 3, branchId: 1 })).content)).toEqual([6, 5]);
      expect((await products({ q: 'pate', categoryId: 3 })).content).toEqual([]);
    });

    it('danh mục ngừng sử dụng thì sản phẩm thuộc nó ẩn khỏi danh sách và chi tiết (ADR-0071 A1)', async () => {
      await client.patch('/api/product-categories/1', { isActive: false }, await chain());
      expect(ids((await products()).content)).toEqual([6, 5, 4]);
      expect((await failure(client.get('/api/public/products/1'))).status).toBe(404);
      const categories = (await client.get('/api/public/product-categories')).data.data as { categoryId: number }[];
      expect(categories.map((c) => c.categoryId)).not.toContain(1);
    });
  });

  describe('phân trang: page từ 0, size 1–100 (mặc định 20), ngoài khoảng thì kẹp, không báo lỗi', () => {
    it('size nhỏ chia trang', async () => {
      const first = await products({ size: 2 });
      expect(first).toMatchObject({ page: 0, size: 2, totalElements: 5, totalPages: 3 });
      expect(ids(first.content)).toEqual([1, 2]);
      expect(ids((await products({ page: 1, size: 2 })).content)).toEqual([6, 5]);
      expect(ids((await products({ page: 2, size: 2 })).content)).toEqual([4]);
    });

    it('page quá cuối thì trang rỗng nhưng vẫn đếm đủ', async () => {
      expect(await products({ page: 9, size: 2 })).toMatchObject({ content: [], page: 9, totalElements: 5, totalPages: 3 });
    });

    it.each([
      [{ page: -1 }, 0, 20],
      [{ page: -100 }, 0, 20],
      [{ size: 0 }, 0, 1],
      [{ size: -5 }, 0, 1],
      [{ size: 101 }, 0, 100],
      [{ size: 100000 }, 0, 100],
    ])('kẹp %j thành page %i, size %i', async (params, page, size) => {
      const res = await products(params);
      expect(res).toMatchObject({ page, size });
      expect(res.content.length).toBe(Math.min(size, 5));
    });

    it('page / size không phải số nguyên thì 400 MALFORMED_REQUEST', async () => {
      expect((await failure(client.get('/api/public/products', { params: { page: 'abc' } }))).data.errorCode).toBe('MALFORMED_REQUEST');
      expect((await failure(client.get('/api/public/products', { params: { size: '1.5' } }))).data.errorCode).toBe('MALFORMED_REQUEST');
    });
  });

  describe('tham số id', () => {
    it.each(['categoryId', 'branchId'])('%s ≤ 0 thì 400 VALIDATION_FAILED, không phải số thì 400 MALFORMED_REQUEST', async (name) => {
      for (const bad of [0, -1]) {
        const res = await failure(client.get('/api/public/products', { params: { [name]: bad } }));
        expect(res.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', statusCode: 400 });
        expect(res.data.message).toContain(name);
      }
      expect((await failure(client.get('/api/public/products', { params: { [name]: 'abc' } }))).data.errorCode).toBe('MALFORMED_REQUEST');
    });
  });
});

describe('chi tiết sản phẩm công khai (public, UC16; BR-CK-01)', () => {
  it('sản phẩm đang kinh doanh, không kê đơn: 200 kèm tên danh mục', async () => {
    const res = await client.get('/api/public/products/6');
    expect(res.data.data).toMatchObject({ productId: 6, name: 'Vaccine 5 bệnh Vanguard', categoryId: 3, categoryName: 'Vaccine', unit: 'liều', price: 180_000 });
  });

  it('thuốc kê đơn (#3), sản phẩm ngừng kinh doanh (#7) và id không có đều là 404', async () => {
    for (const id of [3, 7, 999]) {
      const res = await failure(client.get(`/api/public/products/${id}`));
      expect(res.data).toMatchObject({ errorCode: 'RESOURCE_NOT_FOUND', statusCode: 404 });
    }
  });

  it('thuốc không kê đơn (#4) hiện được dù chưa có chi nhánh nào còn hàng', async () => {
    const res = await client.get('/api/public/products/4');
    expect(res.data.data).toMatchObject({ productId: 4, categoryName: 'Thuốc', inStockBranchIds: [] });
  });

  it('id ≤ 0 thì 400 VALIDATION_FAILED, không phải số thì 400 MALFORMED_REQUEST', async () => {
    for (const bad of ['0', '-1']) {
      const res = await failure(client.get(`/api/public/products/${bad}`));
      expect(res.data).toMatchObject({ errorCode: 'VALIDATION_FAILED', statusCode: 400 });
    }
    expect((await failure(client.get('/api/public/products/abc'))).data.errorCode).toBe('MALFORMED_REQUEST');
  });

  it('thuốc kê đơn vừa bị đổi thành không kê đơn thì hiện ra (đọc theo trạng thái, không theo seed)', async () => {
    await client.patch('/api/products/3', { isPrescription: false }, await chain());
    expect((await client.get('/api/public/products/3')).status).toBe(200);
  });
});

describe('danh mục sản phẩm công khai (public, UC16)', () => {
  it('chỉ danh mục đang sử dụng, đúng ba trường, theo tên', async () => {
    const res = await client.get('/api/public/product-categories');
    const list = res.data.data as { categoryId: number; name: string; isActive: boolean }[];
    expect(list.map((c) => c.categoryId).sort()).toEqual([1, 2, 3, 4]);
    expect(list.every((c) => c.isActive)).toBe(true);
    expect(Object.keys(list[0]).sort()).toEqual(['categoryId', 'isActive', 'name']);
    const names = list.map((c) => c.name);
    expect(names).toEqual([...names].sort((a, b) => a.localeCompare(b, 'vi')));
  });
});

describe('đội ngũ bác sĩ công khai (public, UC15; BR-TK-20, BR-CK-01)', () => {
  const vets = async (params: Record<string, unknown> = {}) => (await client.get('/api/public/vets', { params })).data.data as PublicVet[];
  const newVet = async (fullName: string, branchId = 1) => {
    const res = await client.post(
      '/api/staff',
      { email: `${fullName.replace(/\W/g, '').toLowerCase()}@store1.vn`, phone: '0912345678', fullName, role: 'VET', branchId },
      await chain(),
    );
    return res.data.data.accountId as number;
  };

  it('không cần đăng nhập; VET duy nhất của mẫu kèm hồ sơ công khai và tên chi nhánh', async () => {
    const list = await vets();
    expect(list).toHaveLength(1);
    expect(list[0]).toMatchObject({
      accountId: 5,
      fullName: 'BS. Minh Anh',
      branchId: 1,
      branchName: 'PetCare Cầu Giấy',
      specialty: 'Nội khoa, da liễu thú y',
    });
    expect(list[0].bio).toContain('8 năm');
  });

  it('không lộ email, SĐT, vai trò, trạng thái', async () => {
    const list = await vets();
    expect(Object.keys(list[0]).sort()).toEqual(['accountId', 'avatarUrl', 'bio', 'branchId', 'branchName', 'fullName', 'specialty']);
    expect(JSON.stringify(list)).not.toMatch(/@|phone|role|status/i);
  });

  it('chỉ VET: lễ tân, thu ngân, chăm sóc, quản lý cùng chi nhánh không hiện', async () => {
    expect((await vets()).map((v) => v.accountId)).toEqual([5]);
  });

  it('lọc theo branchId: chi nhánh ACTIVE trả VET của nó, chi nhánh DRAFT hoặc không có trả mảng rỗng', async () => {
    expect((await vets({ branchId: 1 })).map((v) => v.accountId)).toEqual([5]);
    for (const branchId of [2, 3, 999]) expect(await vets({ branchId })).toEqual([]);
  });

  it('branchId ≤ 0 thì 400 VALIDATION_FAILED, không phải số thì 400 MALFORMED_REQUEST', async () => {
    for (const bad of [0, -1]) {
      expect((await failure(client.get('/api/public/vets', { params: { branchId: bad } }))).data.errorCode).toBe('VALIDATION_FAILED');
    }
    expect((await failure(client.get('/api/public/vets', { params: { branchId: 'abc' } }))).data.errorCode).toBe('MALFORMED_REQUEST');
  });

  it('nhiều bác sĩ: sắp theo họ tên', async () => {
    await newVet('Zed Bác sĩ');
    await newVet('An Bình');
    expect((await vets()).map((v) => v.fullName)).toEqual(['An Bình', 'BS. Minh Anh', 'Zed Bác sĩ']);
  });

  it('VET ở chi nhánh DRAFT bị ẩn dù tài khoản ACTIVE (BR-CK-01)', async () => {
    await newVet('Bác sĩ Hà Đông', 2);
    expect((await vets()).map((v) => v.fullName)).toEqual(['BS. Minh Anh']);
    expect(await vets({ branchId: 2 })).toEqual([]);
  });

  it('VET bị khóa (ADMIN khóa) hoặc vô hiệu hóa thì ẩn; mở khóa thì hiện lại (BR-TK-20)', async () => {
    const id = await newVet('Bác sĩ Tạm');
    expect((await vets()).map((v) => v.accountId)).toContain(id);

    await client.post(`/api/accounts/${id}/lock`, { reason: 'Kiểm tra' }, await admin());
    expect((await vets()).map((v) => v.accountId)).not.toContain(id);
    await client.post(`/api/accounts/${id}/unlock`, { reason: 'Xong' }, await admin());
    expect((await vets()).map((v) => v.accountId)).toContain(id);

    await client.post(`/api/staff/${id}/disable`, {}, await chain());
    expect((await vets()).map((v) => v.accountId)).not.toContain(id);
  });

  it('VET tự sửa họ tên, ảnh, chuyên môn thì trang công khai thấy ngay', async () => {
    await client.patch(
      '/api/me/staff-profile',
      { fullName: 'BS. Minh Anh (Trưởng khoa)', avatarUrl: 'https://example.com/minh-anh.jpg', specialty: 'Ngoại khoa' },
      await as('doctor@store1.vn'),
    );
    expect((await vets())[0]).toMatchObject({
      fullName: 'BS. Minh Anh (Trưởng khoa)',
      avatarUrl: 'https://example.com/minh-anh.jpg',
      specialty: 'Ngoại khoa',
    });
  });
});

describe('dịch vụ công khai không kèm branchId (public, UC15; BR-CK-01, BR-CK-02)', () => {
  type Row = { serviceId: number; name: string; group: string; branchIds: number[]; kennelType?: unknown };
  const services = async (params: Record<string, unknown> = {}) => (await client.get('/api/public/services', { params })).data.data as Row[];

  it('không cần đăng nhập; dịch vụ đang kinh doanh bật ở ≥ 1 chi nhánh ACTIVE kèm branchIds (chỉ chi nhánh ACTIVE)', async () => {
    const list = await services();
    // Dịch vụ 6 "Chuồng mèo" chưa bật ở chi nhánh nào → ẩn.
    expect(list.map((s) => s.serviceId).sort()).toEqual([1, 2, 3, 4, 5]);
    expect(list.every((s) => s.branchIds.length > 0 && s.branchIds.every((id) => id === 1))).toBe(true);
  });

  it('loại chuồng kèm kennelType; giá và cờ "Từ" đúng dữ liệu', async () => {
    const boarding = (await services({ group: 'BOARDING' }))[0] as Row & { price: number; priceIsFrom: boolean };
    expect(boarding).toMatchObject({ serviceId: 5, price: 200_000, priceIsFrom: false, kennelType: { species: 'DOG', maxWeightKg: 10 } });
    const grooming = (await services({ group: 'GROOMING' })) as (Row & { priceIsFrom: boolean })[];
    expect(grooming.map((s) => s.name)).toEqual(['Cắt tỉa lông', 'Tắm & sấy']);
    expect(grooming.every((s) => s.priceIsFrom)).toBe(true);
  });

  it.each(['SPA', 'medical', 'Boarding'])('group %s không thuộc enum thì 400 MALFORMED_REQUEST', async (group) => {
    const res = await failure(client.get('/api/public/services', { params: { group } }));
    expect(res.data).toMatchObject({ errorCode: 'MALFORMED_REQUEST', statusCode: 400 });
  });

  it('dịch vụ ngừng kinh doanh thì ẩn', async () => {
    await client.patch('/api/services/1', { isActive: false }, await chain());
    expect((await services()).map((s) => s.serviceId)).not.toContain(1);
  });

  it('dịch vụ chỉ bật ở chi nhánh DRAFT thì ẩn; bật thêm ở chi nhánh ACTIVE thì hiện, branchIds không lộ chi nhánh DRAFT', async () => {
    const manager2 = await as('manager2@store2.vn');
    await client.put('/api/branches/2/services/6', { enabled: true }, manager2);
    expect((await services()).map((s) => s.serviceId)).not.toContain(6);

    await client.put('/api/branches/1/services/6', { enabled: true }, await as('manager@store1.vn'));
    const cat = (await services()).find((s) => s.serviceId === 6)!;
    expect(cat.branchIds).toEqual([1]);
  });

  it('branchId ≤ 0 thì 400 (BE: @Positive), chi nhánh DRAFT hoặc không có thì mảng rỗng', async () => {
    expect((await failure(client.get('/api/public/services', { params: { branchId: 0 } }))).status).toBe(400);
    expect((await failure(client.get('/api/public/services', { params: { branchId: 'abc' } }))).data.errorCode).toBe('MALFORMED_REQUEST');
    expect(await services({ branchId: 2 })).toEqual([]);
    expect(await services({ branchId: 999 })).toEqual([]);
  });
});

describe('chi nhánh công khai theo id (public, UC15; BR-CK-01)', () => {
  it('chi nhánh ACTIVE kèm giờ mở cửa; DRAFT hoặc không có là 404; id ≤ 0 là 400', async () => {
    const res = await client.get('/api/public/branches/1');
    expect(res.data.data).toMatchObject({ branchId: 1, name: 'PetCare Cầu Giấy' });
    expect(res.data.data.weeklyHours).toHaveLength(7);
    expect((await failure(client.get('/api/public/branches/2'))).status).toBe(404);
    expect((await failure(client.get('/api/public/branches/999'))).status).toBe(404);
    expect((await failure(client.get('/api/public/branches/0'))).status).toBe(400);
  });
});
