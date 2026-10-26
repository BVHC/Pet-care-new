import { useState } from 'react';
import * as Tabs from '@radix-ui/react-tabs';
import type { UseQueryResult } from '@tanstack/react-query';
import { CalendarClock, UserPlus, Wallet } from 'lucide-react';
import { cn, formatCurrency } from '../../../lib/utils';
import { useFreshIds } from '../../../shared/hooks/useFreshIds';
import { useNow } from '../../../shared/hooks/useNow';
import { usePendingSalesOrders } from '../../../shared/hooks/usePendingSalesOrders';
import { useArrivals, useVisitQueue } from '../../../shared/hooks/useVisits';
import { usePOS } from '../../../shared/stores/pos.store';
import { useAccount } from '../../../shared/stores/session.store';
import type { Appointment } from '../../../shared/types/appointment';
import { QUOTA_GROUP_LABELS } from '../../../shared/types/branch';
import { ORDER_SOURCE_LABELS, type OrderSummary } from '../../../shared/types/sales';
import { visitPermissions } from '../../../shared/types/visit';
import { formatClock } from '../../../shared/utils/clinic-format';
import { Kbd, Panel, QueryError, RowsSkeleton, StatePanel, WsButton } from '../ui';
import { arrivalWindow } from './arrival';
import { VisitQueueList } from './VisitQueueList';

type DeskTab = 'arrivals' | 'queue' | 'pending';

/** Giờ hẹn của lịch: `slotDate` + `slotStart` là giờ Việt Nam (docs/time.md). */
const slotMs = (a: Appointment) => Date.parse(`${a.slotDate}T${a.slotStart}:00+07:00`);

export function FrontDeskPanel({
  className,
  onCheckIn,
  onWalkIn,
  onCollect,
}: {
  className?: string;
  onCheckIn: (arrival: Appointment) => void;
  onWalkIn: () => void;
  onCollect: () => void;
}) {
  const perms = visitPermissions(useAccount()?.role);
  const [tab, setTab] = useState<DeskTab>('arrivals');
  // Mỗi vai trò chỉ hỏi những gì BE cho phép (VISIT_ROLES): BRANCH_MANAGER xem hàng đợi, VET / CARETAKER không hỏi lịch hẹn chờ check-in.
  const arrivals = useArrivals(perms.arrivals);
  const queue = useVisitQueue({}, perms.queue);
  const pending = usePendingSalesOrders();

  const tabs: { id: DeskTab; label: string; count?: number }[] = [
    { id: 'arrivals', label: 'Đón tiếp', count: arrivals.data?.length },
    { id: 'queue', label: 'Hàng chờ', count: queue.data?.length },
    { id: 'pending', label: 'Chờ thu', count: pending.data?.length },
  ];

  return (
    <Panel label="Đón tiếp và hàng chờ" className={className}>
      <Tabs.Root value={tab} onValueChange={(v) => setTab(v as DeskTab)} className="flex min-h-0 flex-1 flex-col">
        <Tabs.List aria-label="Danh sách ở quầy" className="grid grid-cols-3 gap-1 border-b border-(--ws-line) p-1.5">
          {tabs.map((t) => (
            <Tabs.Trigger
              key={t.id}
              value={t.id}
              className="flex h-9 items-center justify-center gap-1.5 rounded-md text-[13px] font-medium text-(--ws-ink-2) hover:text-(--ws-ink) data-[state=active]:bg-(--ws-raised) data-[state=active]:font-semibold data-[state=active]:text-(--ws-ink) data-[state=active]:shadow-[inset_0_0_0_1px_var(--ws-line)]"
            >
              {t.label}
              <span className="ws-num rounded-full bg-(--ws-accent-soft) px-1.5 text-[11px] font-semibold text-(--ws-accent-ink)">
                {t.count ?? '–'}
              </span>
            </Tabs.Trigger>
          ))}
        </Tabs.List>
        <Tabs.Content value="arrivals" className="min-h-0 flex-1 overflow-y-auto">
          <ArrivalsList query={arrivals} canCheckIn={perms.checkIn} onCheckIn={onCheckIn} onWalkIn={onWalkIn} />
        </Tabs.Content>
        <Tabs.Content value="queue" className="min-h-0 flex-1 overflow-y-auto">
          <VisitQueueList query={queue} />
        </Tabs.Content>
        <Tabs.Content value="pending" className="min-h-0 flex-1 overflow-y-auto">
          <PendingList query={pending} onCollect={onCollect} />
        </Tabs.Content>
      </Tabs.Root>
    </Panel>
  );
}

function ArrivalsList({
  query,
  canCheckIn,
  onCheckIn,
  onWalkIn,
}: {
  query: UseQueryResult<Appointment[], unknown>;
  canCheckIn: boolean;
  onCheckIn: (arrival: Appointment) => void;
  onWalkIn: () => void;
}) {
  const now = useNow();
  const fresh = useFreshIds(query.data, (a) => String(a.appointmentId));
  const walkIn = canCheckIn && (
    <div className="border-t border-(--ws-line) p-3">
      <WsButton className="w-full" onClick={onWalkIn}>
        <UserPlus className="h-4 w-4" aria-hidden />
        Khách vãng lai
        <Kbd>N</Kbd>
      </WsButton>
    </div>
  );

  if (query.isLoading) return <RowsSkeleton rows={3} />;
  if (query.isError) return <QueryError error={query.error} onRetry={() => query.refetch()} what="lịch hẹn" />;
  const arrivals = query.data ?? [];

  return (
    <>
      {arrivals.length === 0 ? (
        <StatePanel icon={CalendarClock} title="Chưa có lịch hẹn sắp đến" body="Lịch hẹn hiện ở đây từ 30 phút trước giờ hẹn." />
      ) : (
        <ul className="divide-y divide-(--ws-line)">
          {arrivals.map((a) => {
            const window = arrivalWindow(slotMs(a), now);
            return (
              <li key={a.appointmentId} className={cn('flex items-start gap-3 px-4 py-3', fresh.has(String(a.appointmentId)) && 'ws-fresh')}>
                <time className="ws-num w-11 shrink-0 pt-0.5 text-sm font-bold" dateTime={`${a.slotDate}T${a.slotStart}:00+07:00`}>
                  {a.slotStart}
                </time>
                <div className="min-w-0 flex-1">
                  <p className="truncate font-semibold">
                    <span>{a.petName}</span>
                  </p>
                  <p className="truncate text-[13px] text-(--ws-ink-2)">{a.customerName}</p>
                  <p className="mt-1 flex flex-wrap items-center gap-1.5 text-[13px]">
                    <span className="ws-chip" data-tone="accent">
                      {QUOTA_GROUP_LABELS[a.serviceGroup]}
                    </span>
                    <span>{a.serviceName}</span>
                  </p>
                  <p className="mt-1">
                    <span className="ws-chip" data-tone={window.kind === 'ON_TIME' ? undefined : 'wait'}>
                      {window.label}
                    </span>
                  </p>
                </div>
                {canCheckIn && (
                  <WsButton size="sm" variant="primary" onClick={() => onCheckIn(a)}>
                    Tiếp nhận
                  </WsButton>
                )}
              </li>
            );
          })}
        </ul>
      )}
      {walkIn}
    </>
  );
}

/** Order PENDING của chi nhánh (sales-v1 #2). Thu tiền là việc của lễ tân (UC70, A06): vai trò khác chỉ xem. */
function PendingList({ query, onCollect }: { query: UseQueryResult<OrderSummary[], unknown>; onCollect: () => void }) {
  const orderIds = usePOS((s) => s.orderIds);
  const customerId = usePOS((s) => s.customerId);
  const attachOrder = usePOS((s) => s.attachOrder);
  const canCollect = useAccount()?.role === 'RECEPTIONIST';
  const fresh = useFreshIds(query.data, (o) => String(o.orderId));

  if (query.isLoading) return <RowsSkeleton rows={3} />;
  if (query.isError) return <QueryError error={query.error} onRetry={() => query.refetch()} what="đơn chờ thu" />;
  const orders = query.data ?? [];
  if (orders.length === 0) {
    return (
      <StatePanel
        icon={Wallet}
        title="Không có đơn chờ thu"
        body="Khi bác sĩ hoặc nhân viên grooming hoàn tất lượt, hoặc khi chốt Order bán lẻ, đơn sẽ hiện ở đây."
      />
    );
  }

  return (
    <ul className="divide-y divide-(--ws-line)">
      {orders.map((o) => {
        const inReceipt = orderIds.includes(o.orderId);
        const combine = !inReceipt && orderIds.length > 0 && customerId === o.customerId;
        return (
          <li key={o.orderId} className={cn('flex items-start gap-3 px-4 py-3', fresh.has(String(o.orderId)) && 'ws-fresh')}>
            <div className="min-w-0 flex-1">
              <p className="flex flex-wrap items-center gap-1.5">
                <span className="ws-num truncate font-semibold">{o.code}</span>
                <span className="ws-chip">{ORDER_SOURCE_LABELS[o.source]}</span>
              </p>
              <p className="truncate text-[13px] text-(--ws-ink-2)">{o.customerName}</p>
              <p className="text-xs text-(--ws-ink-3)">{o.pendingAt ? `Chốt lúc ${formatClock(o.pendingAt)}` : 'Chờ thu tiền'}</p>
              <p className="ws-num text-sm font-semibold">{formatCurrency(o.totalAmount)}</p>
            </div>
            {canCollect && (
              <WsButton
                size="sm"
                variant={inReceipt ? 'ghost' : 'primary'}
                disabled={inReceipt}
                onClick={() => {
                  attachOrder(o.orderId, o.customerId);
                  onCollect();
                }}
              >
                {inReceipt ? 'Trong phiếu' : combine ? 'Thu gộp' : 'Thu tiền'}
              </WsButton>
            )}
          </li>
        );
      })}
    </ul>
  );
}
