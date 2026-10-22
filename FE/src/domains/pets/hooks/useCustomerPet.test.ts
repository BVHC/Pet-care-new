import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement, type ReactNode } from 'react';
import { beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { apiClient } from '../../../shared/api/axios';
import { MOCK_PASSWORD } from '../../../shared/api/mock/identity.mock';
import { installMockApi } from '../../../shared/api/mock/mock-api';
import { useSession } from '../../../shared/stores/session.store';
import {
  customerErrors,
  deceasedErrors,
  petErrors,
  todayVn,
  transferErrors,
  useCreateCounterCustomer,
  useCustomerLookup,
  useHealthRecord,
  useMyPets,
  weightErrors,
} from './useCustomerPet';

// Hook gọi customerPetApi thật qua apiClient (mock theo hợp đồng): bắt lỗi đường dẫn và cách đọc envelope.

beforeAll(() => installMockApi(apiClient));

const signIn = async (email: string) => {
  useSession.getState().clear();
  await useSession.getState().login({ email, password: MOCK_PASSWORD });
};

function wrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client }, children);
}

describe('hook tra cứu và hồ sơ (lễ tân)', () => {
  beforeEach(() => signIn('reception@store1.vn'));

  it('tra theo SĐT trả trang kết quả của hợp đồng', async () => {
    const { result } = renderHook(() => useCustomerLookup({ phone: '0905111222' }), { wrapper: wrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.content.map((c) => c.customerId)).toEqual([2, 3, 4]);
    expect(result.current.data?.totalElements).toBe(3);
  });

  it('không gọi API khi chưa có điều kiện nào', () => {
    const { result } = renderHook(() => useCustomerLookup({ phone: '  ', name: '' }), { wrapper: wrapper() });
    expect(result.current.fetchStatus).toBe('idle');
    expect(result.current.data).toBeUndefined();
  });

  it('tạo hồ sơ tại quầy trả hồ sơ mới và danh sách nghi trùng SĐT', async () => {
    const { result } = renderHook(() => useCreateCounterCustomer(), { wrapper: wrapper() });
    let created: Awaited<ReturnType<typeof result.current.mutateAsync>> | undefined;
    await act(async () => {
      created = await result.current.mutateAsync({ fullName: 'Ngô Bảo', phone: '0912345678' });
    });
    expect(created?.customer).toMatchObject({ fullName: 'Ngô Bảo', createdChannel: 'COUNTER' });
    expect(created?.duplicatePhoneProfiles.map((c) => c.customerId)).toEqual([1]);
  });
});

describe('hook thú cưng của khách', () => {
  beforeEach(() => signIn('khach@petcare.vn'));

  it('đọc thú của tôi và hồ sơ sức khỏe theo schema visit-v1', async () => {
    const pets = renderHook(() => useMyPets(), { wrapper: wrapper() });
    await waitFor(() => expect(pets.result.current.isSuccess).toBe(true));
    expect(pets.result.current.data?.map((p) => p.name)).toEqual(['Mochi', 'Bim', 'Tôm']);

    const health = renderHook(() => useHealthRecord(2), { wrapper: wrapper() });
    await waitFor(() => expect(health.result.current.isSuccess).toBe(true));
    expect(health.result.current.data?.vaccinations[0]).toMatchObject({ vaccineTypeName: 'Dại', doseNumber: 1, nextDueDate: '2027-08-01' });
    expect(health.result.current.data?.visits.map((v) => v.serviceGroup)).toEqual(['GROOMING', 'MEDICAL']);
  });
});

describe('kiểm tra phía FE', () => {
  const TODAY = '2026-10-16';

  it('ngày nghiệp vụ theo giờ Việt Nam', () => {
    expect(todayVn(new Date('2026-10-15T18:30:00Z'))).toBe('2026-10-16');
    expect(todayVn(new Date('2026-10-15T16:59:00Z'))).toBe('2026-10-15');
  });

  it('hồ sơ khách: họ tên bắt buộc, SĐT bắt buộc với hồ sơ tại quầy (BR-KH-01)', () => {
    expect(customerErrors({ fullName: 'Ngô Bảo', phone: '0912345678' }, true)).toEqual({});
    expect(customerErrors({ fullName: ' ', phone: '0912345678' }, true)).toHaveProperty('fullName');
    expect(customerErrors({ fullName: 'A'.repeat(101), phone: '0912345678' }, true)).toHaveProperty('fullName');
    expect(customerErrors({ fullName: 'Ngô Bảo', phone: '' }, true)).toHaveProperty('phone');
    expect(customerErrors({ fullName: 'Ngô Bảo', phone: '' }, false)).toEqual({});
    expect(customerErrors({ fullName: 'Ngô Bảo', phone: '912345678' }, false)).toHaveProperty('phone');
    expect(customerErrors({ fullName: 'Ngô Bảo', phone: '0912345678', email: 'khong-hop-le' }, true)).toHaveProperty('email');
  });

  it('thú cưng: tên, loài, giới tính bắt buộc; ngày sinh không ở tương lai (BR-KH-02)', () => {
    expect(petErrors({ name: 'Mochi', species: 'CAT', sex: 'FEMALE' }, TODAY)).toEqual({});
    expect(petErrors({ name: ' ', species: 'CAT', sex: 'FEMALE' }, TODAY)).toHaveProperty('name');
    expect(petErrors({ name: 'Mochi', species: 'CAT', sex: 'FEMALE', birthDate: TODAY }, TODAY)).toEqual({});
    expect(petErrors({ name: 'Mochi', species: 'CAT', sex: 'FEMALE', birthDate: '2026-10-17' }, TODAY)).toHaveProperty('birthDate');
    expect(petErrors({ name: 'Mochi', species: 'CAT', sex: 'FEMALE', breed: 'x'.repeat(101) }, TODAY)).toHaveProperty('breed');
  });

  it('đánh dấu đã mất cần ngày hợp lệ và xác nhận (BR-KH-05)', () => {
    expect(deceasedErrors({ deceasedOn: TODAY, confirmed: true }, TODAY)).toEqual({});
    expect(deceasedErrors({ deceasedOn: TODAY, confirmed: false }, TODAY)).toHaveProperty('confirmed');
    expect(deceasedErrors({ deceasedOn: '2026-10-17', confirmed: true }, TODAY)).toHaveProperty('deceasedOn');
    expect(deceasedErrors({ deceasedOn: '', confirmed: true }, TODAY)).toHaveProperty('deceasedOn');
  });

  it('chuyển chủ cần chủ mới khác chủ cũ và xác nhận chủ cũ (BR-KH-08)', () => {
    expect(transferErrors({ newCustomerId: 4, previousOwnerConfirmed: true }, 1)).toEqual({});
    expect(transferErrors({ newCustomerId: null, previousOwnerConfirmed: true }, 1)).toHaveProperty('newCustomerId');
    expect(transferErrors({ newCustomerId: 1, previousOwnerConfirmed: true }, 1)).toHaveProperty('newCustomerId');
    expect(transferErrors({ newCustomerId: 4, previousOwnerConfirmed: false }, 1)).toHaveProperty('previousOwnerConfirmed');
  });

  it('cân nặng > 0 và vừa cột NUMERIC(6,2) (BR-KH-04)', () => {
    expect(weightErrors(0.1)).toEqual({});
    expect(weightErrors(9999.99)).toEqual({});
    for (const bad of [null, Number.NaN, 0, -1, 10000]) expect(weightErrors(bad)).toHaveProperty('weightKg');
  });
});
