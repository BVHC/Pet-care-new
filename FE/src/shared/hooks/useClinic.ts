// React Query bindings for the clinic API: one hook per read, one per command.
// Commands invalidate through INVALIDATE so every workspace sees the change.
// T49: bảng điều khiển, bàn làm việc và thanh bên đã chạy trên API hợp đồng; các hook còn lại ở đây là mã chết, gỡ cùng clinic.api.ts ở T57.
import { useMutation, useQuery, useQueryClient, type QueryKey } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ApiError } from '../api/api-error';
import { clinicApi, type AddLineInput } from '../api/clinic.api';
import { INVALIDATE, QUERY_KEYS } from '../constants/queryKeys';
import { retryDelay, shouldRetry } from '../constants/realtime';
import type { MedicalRecord, VisitDetail, VisitView } from '../types/clinic';
import { errorMessage } from '../utils/error-message';
import { startTimer, track } from '../utils/telemetry';

// ---------- reads ----------

export function useVisitDetail(visitId: string | null) {
  return useQuery({
    queryKey: QUERY_KEYS.visits.detail(visitId ?? 'none'),
    queryFn: () => clinicApi.getVisitDetail(visitId as string),
    enabled: !!visitId,
    retry: shouldRetry,
    retryDelay,
  });
}

export const useCatalog = () =>
  useQuery({ queryKey: QUERY_KEYS.catalog.all, queryFn: clinicApi.getCatalog, staleTime: 60_000 });

// ---------- commands ----------

export function notifyError(error: unknown) {
  const rule = error instanceof ApiError ? error.ruleId : null;
  toast.error(errorMessage(error), rule ? { description: `Quy tắc ${rule}` } : undefined);
}

function useInvalidate() {
  const queryClient = useQueryClient();
  return (keys: readonly QueryKey[]) => Promise.all(keys.map((queryKey) => queryClient.invalidateQueries({ queryKey })));
}

type MoveTarget = 'IN_PROGRESS' | 'COMPLETED';

/**
 * Call (WAITING → IN_PROGRESS) or complete (IN_PROGRESS → COMPLETED) a visit.
 * The queue updates at once and rolls back if the server refuses.
 */
export function useMoveVisit(staffId: string) {
  const queryClient = useQueryClient();
  const invalidate = useInvalidate();
  const key = QUERY_KEYS.visits.today();

  return useMutation({
    mutationFn: ({ visitId, to }: { visitId: string; to: MoveTarget }) =>
      to === 'IN_PROGRESS' ? clinicApi.callVisit(visitId, staffId) : clinicApi.completeVisit(visitId, staffId),
    onMutate: async ({ visitId, to }) => {
      await queryClient.cancelQueries({ queryKey: key });
      const previous = queryClient.getQueryData<VisitView[]>(key);
      const now = new Date().toISOString();
      queryClient.setQueryData<VisitView[]>(key, (visits) =>
        visits?.map((v) => {
          if (v.id !== visitId) return v;
          return to === 'IN_PROGRESS'
            ? { ...v, status: to, calledAt: now }
            : { ...v, status: to, completedAt: now, orderStatus: 'PENDING' };
        }),
      );
      return { previous };
    },
    onSuccess: (visit, { to }) => track(to === 'IN_PROGRESS' ? 'visit_call' : 'visit_complete', { queueNo: visit.queueNo }),
    onError: (error, _vars, context) => {
      if (context?.previous) queryClient.setQueryData(key, context.previous);
      notifyError(error);
    },
    onSettled: (_data, _error, { to }) => invalidate(to === 'IN_PROGRESS' ? INVALIDATE.callVisit : INVALIDATE.completeVisit),
  });
}

export function useSaveRecord(visitId: string, staffId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ record }: { record: MedicalRecord; rev: number }) => {
      const stop = startTimer('soap_save');
      return clinicApi.saveRecord(visitId, record, staffId).finally(stop);
    },
    onSuccess: (detail) => queryClient.setQueryData(QUERY_KEYS.visits.detail(visitId), detail),
    onError: notifyError,
  });
}

/** Add / remove "chỉ định" lines; the server answer replaces the cached visit detail. */
export function useVisitLines(visitId: string, staffId: string) {
  const queryClient = useQueryClient();
  const invalidate = useInvalidate();
  const apply = (detail: VisitDetail) => {
    queryClient.setQueryData(QUERY_KEYS.visits.detail(visitId), detail);
    return invalidate(INVALIDATE.editVisitLines);
  };
  const add = useMutation({
    mutationFn: (input: AddLineInput) => clinicApi.addVisitLine(visitId, input, staffId),
    onSuccess: apply,
    onError: notifyError,
  });
  const remove = useMutation({
    mutationFn: (lineId: string) => clinicApi.removeVisitLine(visitId, lineId, staffId),
    onSuccess: apply,
    onError: notifyError,
  });
  const replace = useMutation({
    mutationFn: (serviceId: string) => clinicApi.replaceVisitService(visitId, serviceId, staffId),
    onSuccess: apply,
    onError: notifyError,
  });
  return { add, remove, replace };
}
