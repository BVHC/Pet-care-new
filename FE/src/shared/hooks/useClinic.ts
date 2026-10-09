// React Query bindings for the clinic API: one hook per read, one per command.
// Commands invalidate through INVALIDATE so every workspace sees the change.
import { keepPreviousData, useMutation, useQuery, useQueryClient, type QueryKey } from '@tanstack/react-query';
import { toast } from 'sonner';
import { formatCurrency } from '../../lib/utils';
import { ApiError } from '../api/api-error';
import {
  clinicApi,
  type AddLineInput,
  type CheckInInput,
  type CollectPaymentInput,
} from '../api/clinic.api';
import { INVALIDATE, QUERY_KEYS } from '../constants/queryKeys';
import { liveQuery, retryDelay, shouldRetry } from '../constants/realtime';
import type { MedicalRecord, VisitDetail, VisitView } from '../types/clinic';
import { startTimer, track } from '../utils/telemetry';

// ---------- reads ----------

export const useTodayVisits = () =>
  useQuery({ queryKey: QUERY_KEYS.visits.today(), queryFn: clinicApi.listTodayVisits, ...liveQuery });

export const useArrivals = () =>
  useQuery({ queryKey: QUERY_KEYS.appointments.arrivals(), queryFn: clinicApi.listArrivals, ...liveQuery });

export const usePendingOrders = () =>
  useQuery({ queryKey: QUERY_KEYS.orders.pending(), queryFn: clinicApi.listPendingOrders, ...liveQuery });

export const useDashboardSummary = () =>
  useQuery({ queryKey: QUERY_KEYS.dashboard.summary(), queryFn: clinicApi.getDashboardSummary, ...liveQuery });

export function useVisitDetail(visitId: string | null) {
  return useQuery({
    queryKey: QUERY_KEYS.visits.detail(visitId ?? 'none'),
    queryFn: () => clinicApi.getVisitDetail(visitId as string),
    enabled: !!visitId,
    retry: shouldRetry,
    retryDelay,
  });
}

export function useCustomerSearch(query: string, enabled = true) {
  return useQuery({
    queryKey: QUERY_KEYS.customers.search(query.trim()),
    queryFn: () => clinicApi.searchCustomers(query),
    placeholderData: keepPreviousData,
    staleTime: 30_000,
    enabled,
  });
}

export const useCatalog = () =>
  useQuery({ queryKey: QUERY_KEYS.catalog.all, queryFn: clinicApi.getCatalog, staleTime: 60_000 });

export const useStaffList = () =>
  useQuery({ queryKey: QUERY_KEYS.staff.all, queryFn: clinicApi.listStaff, staleTime: Infinity });

export const useCurrentShift = (receptionistId: string) =>
  useQuery({
    queryKey: QUERY_KEYS.shift.current(receptionistId),
    queryFn: () => clinicApi.getOpenShift(receptionistId),
    staleTime: 30_000,
  });

// ---------- commands ----------

/** Message hiển thị; mã rule ở cuối message (00-method §3.5) tách ra, xem `notifyError`. */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError && error.ruleId) return error.message.replace(` (${error.ruleId})`, '');
  return error instanceof Error && error.message ? error.message : 'Không thực hiện được. Thử lại sau ít phút.';
}

export function notifyError(error: unknown) {
  const rule = error instanceof ApiError ? error.ruleId : null;
  toast.error(errorMessage(error), rule ? { description: `Quy tắc ${rule}` } : undefined);
}

function useInvalidate() {
  const queryClient = useQueryClient();
  return (keys: readonly QueryKey[]) => Promise.all(keys.map((queryKey) => queryClient.invalidateQueries({ queryKey })));
}

export function useCheckIn(actorId: string) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: (input: CheckInInput) => clinicApi.checkIn(input, actorId),
    onSuccess: (visit) => {
      track('check_in', { queueNo: visit.queueNo, priority: visit.priority });
      toast.success(`Đã tiếp nhận ${visit.pet.name}, số ${visit.queueNo}`);
    },
    onError: notifyError,
    onSettled: () => invalidate(INVALIDATE.checkIn),
  });
}

export function useAssignVisit(actorId: string) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: ({ visitId, staffId }: { visitId: string; staffId: string }) =>
      clinicApi.assignVisit(visitId, staffId, actorId),
    onSuccess: (visit) => toast.success(`Đã gán số ${visit.queueNo} cho ${visit.assignee?.name ?? 'nhân viên'}`),
    onError: notifyError,
    onSettled: () => invalidate(INVALIDATE.assignVisit),
  });
}

export function useCancelVisit(actorId: string) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: (visitId: string) => clinicApi.cancelVisit(visitId, actorId),
    onSuccess: (visit) => toast.success(`Đã hủy lượt số ${visit.queueNo}`),
    onError: notifyError,
    onSettled: () => invalidate(INVALIDATE.cancelVisit),
  });
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

export function useOpenShift(receptionistId: string) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: () => clinicApi.openShift(receptionistId),
    onSuccess: () => toast.success('Đã mở ca thu ngân'),
    onError: notifyError,
    onSettled: () => invalidate(INVALIDATE.openShift),
  });
}

export function useCollectPayment(receptionistId: string) {
  const invalidate = useInvalidate();
  return useMutation({
    mutationFn: (input: CollectPaymentInput) => clinicApi.collectPayment(input, receptionistId),
    onSuccess: (receipt) => {
      track('payment', { total: receipt.total, method: receipt.method, orders: receipt.orderIds.length });
      toast.success(`Đã thu ${formatCurrency(receipt.total)}`, { description: receipt.customerName });
    },
    onError: notifyError,
    onSettled: () => invalidate(INVALIDATE.collectPayment),
  });
}

export function useResetDemo() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: clinicApi.resetDemo,
    onSuccess: () => {
      toast.success('Đã đặt lại dữ liệu demo của hôm nay');
      return queryClient.invalidateQueries();
    },
    onError: notifyError,
  });
}
