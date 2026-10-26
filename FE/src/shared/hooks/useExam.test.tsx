import type { ReactNode } from 'react';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/api-error';
import { visitApi } from '../api/visit.api';
import type { MedicalRecord } from '../types/medical-record';
import type { Visit } from '../types/visit';
import {
  useAddAddendum,
  useCompleteVisit,
  useDeleteVaccination,
  useMedicalRecord,
  useRecordVaccination,
  useRecordWeight,
  useSaveMedicalRecord,
  useVaccinationOptions,
} from './useExam';

const record = (visitId: number, over: Partial<MedicalRecord> = {}): MedicalRecord => ({
  visitId,
  examination: null,
  diagnosis: null,
  treatmentPlan: null,
  internalNote: null,
  followUpDate: null,
  locked: false,
  lastEditedBy: null,
  prescriptions: [],
  vaccinations: [],
  addenda: [],
  ...over,
});

const doneVisit = { visitId: 7, status: 'COMPLETED' } as Visit;

let client: QueryClient;
const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;

beforeEach(() => {
  client = new QueryClient({ defaultOptions: { queries: { retry: false, retryDelay: 0 } } });
});
afterEach(() => vi.restoreAllMocks());

describe('hook đọc bệnh án và gợi ý tiêm: chỉ gọi API khi có lượt và được phép', () => {
  it('useMedicalRecord: chưa chọn lượt hoặc enabled = false thì không có request; chọn lượt thì đọc đúng lượt', async () => {
    const read = vi.spyOn(visitApi, 'getMedicalRecord').mockResolvedValue(record(7));
    renderHook(() => useMedicalRecord(null), { wrapper });
    renderHook(() => useMedicalRecord(7, false), { wrapper });
    await new Promise((r) => setTimeout(r, 20));
    expect(read).not.toHaveBeenCalled();

    const { result } = renderHook(() => useMedicalRecord(7), { wrapper });
    await waitFor(() => expect(result.current.data?.visitId).toBe(7));
    expect(read).toHaveBeenCalledWith(7);
  });

  it('bệnh án nằm dưới gốc [visits]: gọi lượt, hoàn tất hay hủy (làm mới [visits]) thì bệnh án đang mở được tải lại', async () => {
    const read = vi.spyOn(visitApi, 'getMedicalRecord').mockResolvedValue(record(7));
    const { result } = renderHook(() => useMedicalRecord(7), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    await act(async () => {
      await client.invalidateQueries({ queryKey: ['visits'] });
    });
    expect(read).toHaveBeenCalledTimes(2);
  });

  it('useVaccinationOptions: chỉ hỏi khi đã chọn loại vaccine và được bật', async () => {
    const options = vi.spyOn(visitApi, 'getVaccinationOptions').mockResolvedValue({ vaccineTypeId: 2, protocol: [], products: [], underMinAge: false });
    renderHook(() => useVaccinationOptions(7, null), { wrapper });
    renderHook(() => useVaccinationOptions(7, 2, false), { wrapper });
    await new Promise((r) => setTimeout(r, 20));
    expect(options).not.toHaveBeenCalled();

    const { result } = renderHook(() => useVaccinationOptions(7, 2), { wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(options).toHaveBeenCalledWith(7, 2);
  });
});

describe('lệnh ghi khám bệnh: làm mới các gốc bị ảnh hưởng, kể cả khi lỗi (409 = lượt đã đổi ở nơi khác)', () => {
  type Mutation = { mutateAsync: (a: never) => Promise<unknown> };

  async function run(hook: () => Mutation, args: unknown) {
    const spy = vi.spyOn(client, 'invalidateQueries');
    const { result } = renderHook(hook, { wrapper });
    await act(async () => {
      await result.current.mutateAsync(args as never).catch(() => undefined);
    });
    return spy.mock.calls.map((c) => (c[0] as { queryKey: unknown[] }).queryKey);
  }

  it('hoàn tất lượt (#10): làm mới [visits], [appointments], [sales]; lỗi 400 BR-KB-02 cũng làm mới', async () => {
    vi.spyOn(visitApi, 'complete').mockResolvedValue(doneVisit);
    for (const root of [['visits'], ['appointments'], ['sales']]) expect(await run(useCompleteVisit as never, 7)).toContainEqual(root);
    vi.spyOn(visitApi, 'complete').mockRejectedValue(new ApiError('BUSINESS_RULE_VIOLATION', 'x (BR-KB-02)', 400));
    expect(await run(useCompleteVisit as never, 7)).toContainEqual(['visits']);
  });

  it('ghi bệnh án (#13): chuyển đúng đối số, đặt bệnh án trả về vào cache và làm mới [visits]', async () => {
    const save = vi.spyOn(visitApi, 'saveMedicalRecord').mockResolvedValue(record(7, { diagnosis: 'Viêm da' }));
    const keys = await run(useSaveMedicalRecord as never, { visitId: 7, diagnosis: 'Viêm da', followUpDate: null });
    expect(save).toHaveBeenCalledWith(7, { diagnosis: 'Viêm da', followUpDate: null });
    expect(client.getQueryData<MedicalRecord>(['visits', 'record', 7])?.diagnosis).toBe('Viêm da');
    expect(keys).toContainEqual(['visits']);
  });

  it('cân nặng (#14) làm mới [pets] (cân nặng hiện tại, lịch sử cân); bản bổ sung (#15) làm mới [visits]', async () => {
    const weigh = vi.spyOn(visitApi, 'recordWeight').mockResolvedValue({ weightRecordId: 1, weightKg: 4.2, measuredAt: '2026-10-23T03:40:00Z' });
    expect(await run(useRecordWeight as never, { visitId: 7, weightKg: 4.2 })).toContainEqual(['pets']);
    expect(weigh).toHaveBeenCalledWith(7, { weightKg: 4.2 });
    const add = vi.spyOn(visitApi, 'addAddendum').mockResolvedValue({ addendumId: 1, content: 'x', isInternal: true, createdBy: 5, createdByName: 'BS', createdAt: '2026-10-23T03:40:00Z' });
    expect(await run(useAddAddendum as never, { visitId: 7, content: 'x', isInternal: true })).toContainEqual(['visits']);
    expect(add).toHaveBeenCalledWith(7, { content: 'x', isInternal: true });
  });

  it('ghi / xóa mũi tiêm (#19, #20): đổi kho, dòng Order và hồ sơ sức khỏe nên làm mới [inventory], [sales], [pets], [visits]; lỗi cũng làm mới', async () => {
    vi.spyOn(visitApi, 'recordVaccination').mockResolvedValue({ vaccination: {} as never, warnings: [] });
    const del = vi.spyOn(visitApi, 'deleteVaccination').mockResolvedValue(undefined);
    for (const root of [['inventory'], ['sales'], ['pets'], ['visits']]) {
      expect(await run(useRecordVaccination as never, { visitId: 7, vaccineTypeId: 2, protocolId: 1, productId: 6 })).toContainEqual(root);
      expect(await run(useDeleteVaccination as never, { visitId: 7, vaccinationId: 3 })).toContainEqual(root);
    }
    expect(del).toHaveBeenCalledWith(7, 3);
    vi.spyOn(visitApi, 'deleteVaccination').mockRejectedValue(new ApiError('INVALID_STATE_TRANSITION', 'x', 409));
    expect(await run(useDeleteVaccination as never, { visitId: 7, vaccinationId: 3 })).toContainEqual(['inventory']);
  });
});
