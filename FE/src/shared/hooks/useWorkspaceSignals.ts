import { useMemo } from 'react';
import { toast } from 'sonner';
import type { Workspace } from '../constants/workspaces';
import { useAccount } from '../stores/session.store';
import { VISIT_GROUP_LABELS, visitPermissions } from '../types/visit';
import { announce } from '../utils/announce';
import { usePendingSalesOrders } from './usePendingSalesOrders';
import { useOnNewItems } from './useOnNewItems';
import { useMyQueue } from './useVisits';

/**
 * Cross-workspace handoffs (plan: toast now, WebSocket later):
 * reception hears about orders that just became payable (sales-v1 #2, polled), vets and groomers about visits newly waiting in their own queue (visit-v1 #3, polled).
 */
export function useHandoffNotices(workspace: Workspace | null): void {
  const desk = workspace === 'doctor' || workspace === 'grooming';
  const role = useAccount()?.role;
  // Chỉ VET / CARETAKER có hàng đợi của tôi; vai trò khác hỏi sẽ bị 403.
  const myQueue = useMyQueue(desk && visitPermissions(role).myQueue);
  // Only the reception desk reacts to new payable orders, so other desks (and roles that may not list orders) do not fetch them.
  const pending = usePendingSalesOrders(workspace === 'reception');

  const myNewWork = useMemo(() => (desk ? myQueue.data?.filter((v) => v.status === 'WAITING') : undefined), [myQueue.data, desk]);

  useOnNewItems(
    workspace === 'reception' ? pending.data : undefined,
    (order) => String(order.orderId),
    (orders) =>
      orders.forEach((o) => {
        const title = `Đơn mới chờ thu của ${o.customerName}`;
        toast(title, { description: `Mời ${o.customerName} đến quầy thu tiền (${o.code}).` });
        announce(title);
      }),
  );

  useOnNewItems(
    myNewWork,
    (visit) => String(visit.visitId),
    (arrived) =>
      arrived.forEach((v) => {
        const title = `${v.code}: ${v.petName} vào hàng chờ của bạn`;
        toast(title, { description: VISIT_GROUP_LABELS[v.serviceGroup] });
        announce(title);
      }),
  );
}
