import { useMemo, useState } from 'react';
import { Search } from 'lucide-react';
import { cn, formatCurrency } from '../../../lib/utils';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '../../ui/dialog';
import { KIND_LABEL, SPECIES_LABEL } from '../../../shared/constants/clinic-labels';
import { useDebounce } from '../../../shared/hooks/useDebounce';
import { useCatalog, useCheckIn, useCustomerSearch, useStaffList, useTodayVisits } from '../../../shared/hooks/useClinic';
import type { ArrivalView, ServiceKind } from '../../../shared/types/clinic';
import { formatClock, petKind } from '../../../shared/utils/clinic-format';
import { positionFor } from '../../../shared/utils/clinic-rules';
import { RowsSkeleton, WsButton } from '../ui';
import { UNASSIGNED, resolveAssignee } from './assignee';

export type CheckInTarget = { mode: 'walk-in' } | { mode: 'appointment'; arrival: ArrivalView };

export function CheckInDialog({
  target,
  onClose,
  actorId,
}: {
  target: CheckInTarget | null;
  onClose: () => void;
  actorId: string;
}) {
  return (
    <Dialog open={!!target} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="ws-portal max-h-[90dvh] overflow-y-auto border-(--ws-line) bg-(--ws-surface) text-(--ws-ink) sm:max-w-xl">
        {target && (
          <CheckInForm
            key={target.mode === 'appointment' ? target.arrival.appointmentId : 'walk-in'}
            target={target}
            actorId={actorId}
            onDone={onClose}
          />
        )}
      </DialogContent>
    </Dialog>
  );
}

const field = 'h-10 w-full rounded-lg border border-(--ws-line-strong) bg-(--ws-surface) px-3 text-sm text-(--ws-ink)';

function CheckInForm({ target, actorId, onDone }: { target: CheckInTarget; actorId: string; onDone: () => void }) {
  const catalog = useCatalog();
  const staff = useStaffList();
  const visits = useTodayVisits();
  const checkIn = useCheckIn(actorId);
  const walkIn = target.mode === 'walk-in';

  const [query, setQuery] = useState('');
  const search = useCustomerSearch(useDebounce(query, 200), walkIn);
  const [petId, setPetId] = useState<string | null>(null);
  const [serviceId, setServiceId] = useState<string | null>(walkIn ? null : target.arrival.service.id);
  const [emergency, setEmergency] = useState(false);
  const [pickedStaff, setPickedStaff] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  const kind: ServiceKind | undefined = walkIn
    ? catalog.data?.services.find((s) => s.id === serviceId)?.kind
    : target.arrival.service.kind;

  // Suggest whoever of the right position has the shortest queue right now (BR-TN-05).
  const candidates = useMemo(() => {
    if (!kind) return [];
    const load = (staffId: string) =>
      visits.data?.filter((v) => v.assignee?.id === staffId && (v.status === 'WAITING' || v.status === 'IN_PROGRESS')).length ?? 0;
    return (staff.data ?? [])
      .filter((s) => s.position === positionFor(kind))
      .map((s) => ({ ...s, load: load(s.id) }))
      .sort((a, b) => a.load - b.load);
  }, [kind, staff.data, visits.data]);
  const assigneeId = resolveAssignee(pickedStaff, candidates.map((c) => c.id));

  const submit = () => {
    if (checkIn.isPending) return;
    if (walkIn && (!petId || !serviceId)) {
      setProblem('Chọn thú cưng và dịch vụ trước khi tiếp nhận.');
      return;
    }
    const assignee = assigneeId || undefined;
    const clinical = kind !== 'GROOMING' && emergency;
    checkIn.mutate(
      walkIn
        ? { petId: petId as string, serviceId: serviceId as string, emergency: clinical, assigneeId: assignee }
        : { appointmentId: target.arrival.appointmentId, emergency: clinical, assigneeId: assignee },
      { onSuccess: onDone },
    );
  };

  const services = catalog.data?.services ?? [];

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
      onKeyDown={(e) => {
        // Ctrl+Enter finishes the task in front of you — here, the check-in.
        if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
          e.preventDefault();
          submit();
        }
      }}
      className="flex flex-col gap-5"
    >
      <div>
        <DialogTitle className="text-lg font-bold">{walkIn ? 'Tiếp nhận khách vãng lai' : 'Tiếp nhận lịch hẹn'}</DialogTitle>
        <DialogDescription className="text-sm text-(--ws-ink-2)">
          Lượt mới vào hàng chờ và người phụ trách thấy ngay trên bàn làm việc của mình.
        </DialogDescription>
      </div>

      {walkIn ? (
        <>
          <div className="flex flex-col gap-2">
            <label htmlFor="ci-search" className="text-[13px] font-semibold">
              Khách hàng
            </label>
            <div className="relative">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-(--ws-ink-3)" aria-hidden />
              <input
                id="ci-search"
                autoFocus
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Tên khách, số điện thoại hoặc tên thú cưng"
                className={cn(field, 'pl-9')}
                autoComplete="off"
              />
            </div>
            <div className="max-h-56 overflow-y-auto rounded-lg border border-(--ws-line)">
              {search.isLoading ? (
                <RowsSkeleton rows={2} />
              ) : (search.data ?? []).length === 0 ? (
                <p className="px-3 py-4 text-sm text-(--ws-ink-2)">Không tìm thấy khách này. Kiểm tra lại số điện thoại.</p>
              ) : (
                <ul className="divide-y divide-(--ws-line)">
                  {search.data?.map((c) => (
                    <li key={c.id} className="px-3 py-2.5">
                      <p className="text-sm font-semibold">
                        {c.name} <span className="ws-num font-normal text-(--ws-ink-3)">{c.phone}</span>
                      </p>
                      <div role="group" aria-label={`Thú cưng của ${c.name}`} className="mt-1.5 flex flex-wrap gap-1.5">
                        {c.pets.map((p) => (
                          <button
                            key={p.id}
                            type="button"
                            aria-pressed={petId === p.id}
                            onClick={() => {
                              setPetId(p.id);
                              setProblem(null);
                            }}
                            className="h-8 rounded-full border border-(--ws-line-strong) px-3 text-[13px] hover:border-(--ws-accent) aria-pressed:border-(--ws-accent) aria-pressed:bg-(--ws-accent-soft) aria-pressed:font-semibold aria-pressed:text-(--ws-accent-ink)"
                          >
                            {p.name} <span className="opacity-70">({SPECIES_LABEL[p.species]})</span>
                          </button>
                        ))}
                      </div>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>

          <fieldset className="flex flex-col gap-2">
            <legend className="mb-2 text-[13px] font-semibold">Dịch vụ</legend>
            <div className="grid gap-1.5 sm:grid-cols-2">
              {services.map((s) => (
                <label
                  key={s.id}
                  className="flex cursor-pointer items-center gap-2 rounded-lg border border-(--ws-line) px-3 py-2 text-sm has-[:checked]:border-(--ws-accent) has-[:checked]:bg-(--ws-accent-soft)"
                >
                  <input
                    type="radio"
                    name="service"
                    value={s.id}
                    checked={serviceId === s.id}
                    onChange={() => {
                      setServiceId(s.id);
                      setProblem(null);
                    }}
                    className="accent-(--ws-accent)"
                  />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-medium">{s.name}</span>
                    <span className="text-xs text-(--ws-ink-3)">
                      {KIND_LABEL[s.kind]}, {formatCurrency(s.price)}
                    </span>
                  </span>
                </label>
              ))}
            </div>
          </fieldset>
        </>
      ) : (
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 rounded-lg bg-(--ws-raised) p-4 text-sm">
          <dt className="text-(--ws-ink-2)">Giờ hẹn</dt>
          <dd className="ws-num font-semibold">{formatClock(target.arrival.startsAt)}</dd>
          <dt className="text-(--ws-ink-2)">Thú cưng</dt>
          <dd className="font-semibold">
            {target.arrival.pet.name}{' '}
            <span className="font-normal text-(--ws-ink-3)">
              {petKind(target.arrival.pet.species, target.arrival.pet.breed)}
            </span>
          </dd>
          <dt className="text-(--ws-ink-2)">Chủ nuôi</dt>
          <dd>
            {target.arrival.customer.name}, <span className="ws-num">{target.arrival.customer.phone}</span>
          </dd>
          <dt className="text-(--ws-ink-2)">Dịch vụ</dt>
          <dd>{target.arrival.service.name}</dd>
        </dl>
      )}

      <div className="grid gap-4 sm:grid-cols-2">
        <div className="flex flex-col gap-2">
          <label htmlFor="ci-assignee" className="text-[13px] font-semibold">
            Người phụ trách
          </label>
          <select
            id="ci-assignee"
            value={assigneeId}
            onChange={(e) => setPickedStaff(e.target.value)}
            disabled={!kind}
            className={field}
          >
            {!kind && <option value="">Chọn dịch vụ trước</option>}
            {candidates.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name} ({c.load} lượt đang chờ/làm)
              </option>
            ))}
            {kind && <option value={UNASSIGNED}>Để lễ tân gán sau</option>}
          </select>
        </div>
        {kind && kind !== 'GROOMING' && (
          <label className="flex items-center gap-2 self-end rounded-lg border border-(--ws-line) px-3 py-2.5 text-sm has-[:checked]:border-(--ws-urgent-ink) has-[:checked]:bg-(--ws-urgent-bg)">
            <input
              type="checkbox"
              checked={emergency}
              onChange={(e) => setEmergency(e.target.checked)}
              className="h-4 w-4 accent-(--ws-urgent-ink)"
            />
            <span>
              <span className="font-semibold">Cấp cứu</span>
              <span className="block text-xs text-(--ws-ink-2)">Lên đầu hàng chờ</span>
            </span>
          </label>
        )}
      </div>

      {problem && (
        <p role="alert" className="text-sm font-medium text-(--ws-urgent-ink)">
          {problem}
        </p>
      )}

      <div className="flex justify-end gap-2 border-t border-(--ws-line) pt-4">
        <WsButton variant="ghost" onClick={onDone}>
          Hủy
        </WsButton>
        <WsButton type="submit" variant="primary" disabled={checkIn.isPending}>
          {checkIn.isPending ? 'Đang tiếp nhận…' : 'Tiếp nhận'}
        </WsButton>
      </div>
    </form>
  );
}
