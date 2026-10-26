// React Query cho khám bệnh (T37): bệnh án, cân nặng, bản bổ sung, hoàn tất lượt, tiêm chủng (visit-v1 #10, #12–15, #18–20; UC48, UC49, UC52, UC53) và kê đơn (T46, #16–17).
// Khóa đọc nằm dưới gốc ['visits', …] nên mọi lệnh ghi của lượt (gọi, hoàn tất, hủy) làm tải lại bệnh án đang mở. KHÔNG tự làm mới định kỳ như hàng đợi: bệnh án
// đang soạn là của chính người mở nó. Phân quyền kiểu `useMyShift(enabled)`: UI tính từ `visitPermissions(role)` để vai trò không có quyền không bắn request (BE trả 403).
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { visitApi } from '../api/visit.api';
import type { AddendumRequest, MedicalRecord, RecordVaccinationRequest, RecordWeightRequest, UpdateMedicalRecordRequest } from '../types/medical-record';
import type { AddPrescriptionRequest } from '../types/visit';
import { VISITS_KEY, useVisitMutation, visitQueryRetry } from './useVisits';

const recordKey = (visitId: number | null) => ['visits', 'record', visitId] as const;

/** #12 (VET, CARETAKER chỉ xem). Lượt Thẩm mỹ → 404 (không có bệnh án) nên UI không bật hook cho nhóm đó. */
export const useMedicalRecord = (visitId: number | null, enabled = true) =>
  useQuery({ queryKey: recordKey(visitId), queryFn: () => visitApi.getMedicalRecord(visitId!), enabled: enabled && visitId != null, ...visitQueryRetry });

/** #18 (VET được gán lượt). Chưa chọn loại vaccine (null) thì chưa hỏi. */
export const useVaccinationOptions = (visitId: number | null, vaccineTypeId: number | null, enabled = true) =>
  useQuery({
    queryKey: ['visits', 'vaccination-options', visitId, vaccineTypeId],
    queryFn: () => visitApi.getVaccinationOptions(visitId!, vaccineTypeId!),
    enabled: enabled && visitId != null && vaccineTypeId != null,
    ...visitQueryRetry,
  });

/** Làm mới [visits], [appointments], [sales] và `extra` — kể cả khi lỗi, vì 409 nghĩa là lượt đã đổi ở nơi khác. */
function useExamMutation<A, R>(fn: (args: A) => Promise<R>, extra: readonly (readonly unknown[])[] = [], onSuccess?: (result: R, args: A, client: ReturnType<typeof useQueryClient>) => void) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: (result, args) => onSuccess?.(result, args, queryClient),
    onSettled: () => Promise.all([VISITS_KEY, ['appointments'], ['sales'], ...extra].map((queryKey) => queryClient.invalidateQueries({ queryKey }))),
  });
}

/** #10 (người được gán: VET cho Khám/Tiêm, CARETAKER cho Thẩm mỹ). */
export const useCompleteVisit = () => useVisitMutation((visitId: number) => visitApi.complete(visitId));

/** #13 (VET được gán): PUT thay toàn bộ 5 trường. Bệnh án trả về được đặt vào cache để form không nháy về giá trị cũ trước khi tải lại. */
export const useSaveMedicalRecord = () =>
  useExamMutation(
    ({ visitId, ...body }: { visitId: number } & UpdateMedicalRecordRequest) => visitApi.saveMedicalRecord(visitId, body),
    [],
    (record: MedicalRecord, { visitId }, client) => client.setQueryData(recordKey(visitId), record),
  );

/** #14 (VET được gán): đổi cân nặng hiện tại và lịch sử cân của thú nên làm mới [pets]. */
export const useRecordWeight = () => useExamMutation(({ visitId, weightKg }: { visitId: number } & RecordWeightRequest) => visitApi.recordWeight(visitId, { weightKg }), [['pets']]);

/** #15 (mọi VET của chi nhánh). */
export const useAddAddendum = () => useExamMutation(({ visitId, ...body }: { visitId: number } & AddendumRequest) => visitApi.addAddendum(visitId, body), [['pets']]);

/**
 * #16 (VET được gán): dòng đơn thuốc, kèm dòng Order thuốc khi đủ tồn → làm mới [sales] (và [visits] cho bệnh án). Không đổi kho (kho trừ khi thu tiền, BR-BH-04) và chưa vào hồ sơ
 * sức khỏe (chỉ lượt đã hoàn tất) nên không cần làm mới [inventory], [pets].
 */
export const useAddPrescription = () => useExamMutation(({ visitId, ...body }: { visitId: number } & AddPrescriptionRequest) => visitApi.addPrescription(visitId, body));

/** #17 (VET được gán): xóa dòng đơn, kèm dòng Order thuốc của nó. */
export const useDeletePrescription = () => useExamMutation(({ visitId, itemId }: { visitId: number; itemId: number }) => visitApi.deletePrescription(visitId, itemId));

/** #19 (VET được gán): trừ kho FEFO, thêm dòng Order vaccine, thêm vào hồ sơ sức khỏe → làm mới [inventory], [pets]. */
export const useRecordVaccination = () =>
  useExamMutation(({ visitId, ...body }: { visitId: number } & RecordVaccinationRequest) => visitApi.recordVaccination(visitId, body), [['inventory'], ['pets']]);

/** #20 (VET đã ghi mũi đó): hoàn kho đúng lô, xóa dòng Order vaccine. */
export const useDeleteVaccination = () =>
  useExamMutation(({ visitId, vaccinationId }: { visitId: number; vaccinationId: number }) => visitApi.deleteVaccination(visitId, vaccinationId), [['inventory'], ['pets']]);
