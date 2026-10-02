import { useEffect, useMemo } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { CLINIC_DB_KEY } from '../api/clinic-db';
import { QUERY_KEYS } from '../constants/queryKeys';
import type { Workspace } from '../constants/workspaces';
import { announce } from '../utils/announce';
import { usePendingOrders, useTodayVisits } from './useClinic';
import { useOnNewItems } from './useOnNewItems';

/** Another tab changed the clinic store: refresh now instead of waiting for the next poll. */
export function useClinicSync(): void {
  const queryClient = useQueryClient();
  useEffect(() => {
    const onStorage = (e: StorageEvent) => {
      if (e.key !== CLINIC_DB_KEY) return;
      Object.values(QUERY_KEYS).forEach((family) => queryClient.invalidateQueries({ queryKey: family.all }));
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, [queryClient]);
}

/**
 * Cross-workspace handoffs (plan: toast now, WebSocket later):
 * reception hears about finished visits, vets and groomers about work assigned to them.
 */
export function useHandoffNotices(workspace: Workspace | null, userId: string): void {
  const visits = useTodayVisits();
  const pending = usePendingOrders();

  const myNewWork = useMemo(() => {
    if (workspace !== 'doctor' && workspace !== 'grooming') return undefined;
    const wantsGrooming = workspace === 'grooming';
    return visits.data?.filter(
      (v) =>
        v.status === 'WAITING' &&
        v.assignee?.id === userId &&
        v.services.some((s) => (s.kind === 'GROOMING') === wantsGrooming),
    );
  }, [visits.data, workspace, userId]);

  useOnNewItems(
    workspace === 'reception' ? pending.data : undefined,
    (order) => order.id,
    (orders) =>
      orders.forEach((o) => {
        const title = o.queueNo ? `Số ${o.queueNo}: ${o.petName} đã xong` : `Đơn mới chờ thu của ${o.customer.name}`;
        toast(title, { description: `Mời ${o.customer.name} đến quầy thu tiền và đón bé.` });
        announce(title);
      }),
  );

  useOnNewItems(
    myNewWork,
    (visit) => visit.id,
    (arrived) =>
      arrived.forEach((v) => {
        const title = `Số ${v.queueNo}: ${v.pet.name} vào hàng chờ của bạn`;
        toast(title, { description: v.services.map((s) => s.name).join(', ') });
        announce(title);
      }),
  );
}
