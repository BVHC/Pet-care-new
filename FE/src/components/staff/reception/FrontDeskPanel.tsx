import { useMemo, useState } from 'react';
import * as Tabs from '@radix-ui/react-tabs';
import type { UseQueryResult } from '@tanstack/react-query';
import { CalendarClock, ClipboardList, UserPlus, Wallet } from 'lucide-react';
import { cn, formatCurrency } from '../../../lib/utils';
import { KIND_LABEL } from '../../../shared/constants/clinic-labels';
import {
  useArrivals,
  useAssignVisit,
  useCancelVisit,
  usePendingOrders,
  useStaffList,
  useTodayVisits,
} from '../../../shared/hooks/useClinic';
import { useFreshIds } from '../../../shared/hooks/useFreshIds';
import { useNow } from '../../../shared/hooks/useNow';
import { usePOS } from '../../../shared/stores/pos.store';
import type { ArrivalView, PendingOrderView, VisitView } from '../../../shared/types/clinic';
import { formatClock, formatElapsed, petKind } from '../../../shared/utils/clinic-format';
import { CLINIC_CFG, positionFor } from '../../../shared/utils/clinic-rules';
import { Kbd, Panel, PriorityChip, QueryError, RowsSkeleton, StatePanel, Ticket, VisitStatusChip, WsButton } from '../ui';

type DeskTab = 'arrivals' | 'queue' | 'pending';

export function FrontDeskPanel({
  className,
  actorId,
  onCheckIn,
  onWalkIn,
  onCollect,
}: {
  className?: string;
  actorId: string;
  onCheckIn: (arrival: ArrivalView) => void;
  onWalkIn: () => void;
  onCollect: () => void;
}) {
  const [tab, setTab] = useState<DeskTab>('arrivals');
  const arrivals = useArrivals();
  const visits = useTodayVisits();
  const pending = usePendingOrders();
  const active = useMemo(
    () => visits.data?.filter((v) => v.status === 'WAITING' || v.status === 'IN_PROGRESS'),
    [visits.data],
  );

  const tabs: { id: DeskTab; label: string; count?: number }[] = [
    { id: 'arrivals', label: 'Đón tiếp', count: arrivals.data?.length },
    { id: 'queue', label: 'Hàng chờ', count: active?.length },
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
          <ArrivalsList query={arrivals} onCheckIn={onCheckIn} onWalkIn={onWalkIn} />
        </Tabs.Content>
        <Tabs.Content value="queue" className="min-h-0 flex-1 overflow-y-auto">
          <QueueList query={visits} active={active} actorId={actorId} />
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
  onCheckIn,
  onWalkIn,
}: {
  query: UseQueryResult<ArrivalView[], unknown>;
  onCheckIn: (arrival: ArrivalView) => void;
  onWalkIn: () => void;
}) {
  const now = useNow();
  const walkIn = (
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
        <StatePanel
          icon={CalendarClock}
          title="Chưa có lịch hẹn sắp đến"
          body={`Lịch hẹn hiện ở đây từ ${CLINIC_CFG.checkInEarlyMin} phút trước giờ hẹn.`}
        />
      ) : (
        <ul className="divide-y divide-(--ws-line)">
          {arrivals.map((a) => {
            const lateMin = Math.floor((now - Date.parse(a.startsAt)) / 60_000);
            return (
              <li key={a.appointmentId} className="flex items-start gap-3 px-4 py-3">
                <time className="ws-num w-11 shrink-0 pt-0.5 text-sm font-bold" dateTime={a.startsAt}>
                  {formatClock(a.startsAt)}
                </time>
                <div className="min-w-0 flex-1">
                  <p className="truncate font-semibold">
                    {a.pet.name}{' '}
                    <span className="font-normal text-(--ws-ink-3)">{petKind(a.pet.species, a.pet.breed)}</span>
                  </p>
                  <p className="truncate text-[13px] text-(--ws-ink-2)">{a.customer.name}</p>
                  <p className="ws-num text-[13px] text-(--ws-ink-3)">{a.customer.phone}</p>
                  <p className="mt-1 flex flex-wrap items-center gap-1.5 text-[13px]">
                    <span className="ws-chip" data-tone="accent">
                      {KIND_LABEL[a.service.kind]}
                    </span>
                    {a.service.name}
                    {lateMin > CLINIC_CFG.latePriorityMin && (
                      <span className="ws-chip" data-tone="wait" title="Trễ quá 15 phút nên xếp như khách vãng lai">
                        Trễ {lateMin} phút
                      </span>
                    )}
                  </p>
                </div>
                <WsButton size="sm" variant="primary" onClick={() => onCheckIn(a)}>
                  Tiếp nhận
                </WsButton>
              </li>
            );
          })}
        </ul>
      )}
      {walkIn}
    </>
  );
}

function QueueList({
  query,
  active,
  actorId,
}: {
  query: UseQueryResult<VisitView[], unknown>;
  active: VisitView[] | undefined;
  actorId: string;
}) {
  const staff = useStaffList();
  const assign = useAssignVisit(actorId);
  const cancel = useCancelVisit(actorId);
  const now = useNow();
  const fresh = useFreshIds(active, (v) => v.id);
  const [confirmCancel, setConfirmCancel] = useState<string | null>(null);

  if (query.isLoading) return <RowsSkeleton rows={4} />;
  if (query.isError) return <QueryError error={query.error} onRetry={() => query.refetch()} what="hàng chờ" />;
  if (!active?.length) {
    return <StatePanel icon={ClipboardList} title="Hàng chờ đang trống" body="Khách đến thì bấm Tiếp nhận ở tab Đón tiếp." />;
  }

  return (
    <ol className="divide-y divide-(--ws-line)" aria-label="Hàng chờ theo thứ tự ưu tiên">
      {active.map((v) => {
        const kind = v.services[0]?.kind ?? 'EXAM';
        const options = staff.data?.filter((s) => s.position === positionFor(kind)) ?? [];
        return (
          <li key={v.id} className={cn('px-4 py-3', fresh.has(v.id) && 'ws-fresh')}>
            <div className="flex items-start gap-3">
              <Ticket no={v.queueNo} size="sm" tone={v.emergency ? 'urgent' : undefined} />
              <div className="min-w-0 flex-1">
                <p className="flex flex-wrap items-center gap-1.5">
                  <span className="font-semibold">{v.pet.name}</span>
                  <VisitStatusChip status={v.status} />
                  <PriorityChip priority={v.priority} />
                </p>
                <p className="truncate text-[13px] text-(--ws-ink-2)">
                  {v.services.map((s) => s.name).join(', ')}, {v.customer.name}
                </p>
                <p className="text-xs text-(--ws-ink-3)">
                  {v.status === 'WAITING'
                    ? `Chờ ${formatElapsed(v.checkedInAt, now)}`
                    : `Bắt đầu lúc ${formatClock(v.calledAt ?? v.checkedInAt)}, ${v.assignee?.name ?? ''}`}
                </p>
              </div>
            </div>
            {v.status === 'WAITING' && (
              <div className="mt-2 flex flex-wrap items-center gap-2 pl-[2.85rem]">
                <label className="sr-only" htmlFor={`assign-${v.id}`}>
                  Người phụ trách số {v.queueNo}
                </label>
                <select
                  id={`assign-${v.id}`}
                  value={v.assignee?.id ?? ''}
                  onChange={(e) => e.target.value && assign.mutate({ visitId: v.id, staffId: e.target.value })}
                  className="h-8 min-w-0 flex-1 rounded-md border border-(--ws-line-strong) bg-(--ws-surface) px-2 text-[13px] text-(--ws-ink)"
                >
                  <option value="" disabled>
                    Chưa gán người làm
                  </option>
                  {options.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
                </select>
                {confirmCancel === v.id ? (
                  <>
                    <WsButton
                      size="sm"
                      variant="danger"
                      onClick={() => {
                        cancel.mutate(v.id);
                        setConfirmCancel(null);
                      }}
                    >
                      Xác nhận hủy lượt
                    </WsButton>
                    <WsButton size="sm" variant="ghost" onClick={() => setConfirmCancel(null)}>
                      Giữ lại
                    </WsButton>
                  </>
                ) : (
                  <WsButton size="sm" variant="ghost" onClick={() => setConfirmCancel(v.id)}>
                    Khách về
                  </WsButton>
                )}
              </div>
            )}
          </li>
        );
      })}
    </ol>
  );
}

function PendingList({ query, onCollect }: { query: UseQueryResult<PendingOrderView[], unknown>; onCollect: () => void }) {
  const orderIds = usePOS((s) => s.orderIds);
  const customerId = usePOS((s) => s.customerId);
  const attachOrder = usePOS((s) => s.attachOrder);
  const fresh = useFreshIds(query.data, (o) => o.id);

  if (query.isLoading) return <RowsSkeleton rows={3} />;
  if (query.isError) return <QueryError error={query.error} onRetry={() => query.refetch()} what="đơn chờ thu" />;
  const orders = query.data ?? [];
  if (orders.length === 0) {
    return (
      <StatePanel
        icon={Wallet}
        title="Không có đơn chờ thu"
        body="Khi bác sĩ hoặc nhân viên grooming hoàn tất lượt, đơn sẽ hiện ở đây."
      />
    );
  }

  return (
    <ul className="divide-y divide-(--ws-line)">
      {orders.map((o) => {
        const inReceipt = orderIds.includes(o.id);
        const combine = !inReceipt && orderIds.length > 0 && customerId === o.customer.id;
        return (
          <li key={o.id} className={cn('flex items-start gap-3 px-4 py-3', fresh.has(o.id) && 'ws-fresh')}>
            {o.queueNo ? <Ticket no={o.queueNo} size="sm" /> : <span className="ws-chip">Bán lẻ</span>}
            <div className="min-w-0 flex-1">
              <p className="truncate font-semibold">{o.petName ?? o.customer.name}</p>
              <p className="truncate text-[13px] text-(--ws-ink-2)">
                {o.customer.name}, {o.lines.length} mục
              </p>
              <p className="ws-num text-sm font-semibold">{formatCurrency(o.total)}</p>
            </div>
            <WsButton
              size="sm"
              variant={inReceipt ? 'ghost' : 'primary'}
              disabled={inReceipt}
              onClick={() => {
                attachOrder(o.id, o.customer.id);
                onCollect();
              }}
            >
              {inReceipt ? 'Trong phiếu' : combine ? 'Thu gộp' : 'Thu tiền'}
            </WsButton>
          </li>
        );
      })}
    </ul>
  );
}
