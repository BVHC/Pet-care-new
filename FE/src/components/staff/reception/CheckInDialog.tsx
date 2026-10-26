// Tiếp nhận (UC44, UC47; visit-v1 #1): check-in lịch hẹn, khách vãng lai, cấp cứu. Lượt mới vào hàng đợi chưa có người phụ trách: request tiếp nhận không có
// trường gán nhân viên, nên gán là bước riêng ở tab Hàng chờ (#5, BR-TN-05).
import { useState } from 'react';
import { toast } from 'sonner';
import { cn } from '../../../lib/utils';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '../../ui/dialog';
import { useCustomerDetail } from '../../../domains/pets/hooks/useCustomerPet';
import { SPECIES_LABEL } from '../../../domains/pets/labels';
import { useBranchServices } from '../../../shared/hooks/useBranch';
import { useStaffBranchId } from '../../../shared/hooks/useStaffBranch';
import { useNow } from '../../../shared/hooks/useNow';
import { useCheckIn, useVisitQueue, visitErrorMessage } from '../../../shared/hooks/useVisits';
import { useAccount } from '../../../shared/stores/session.store';
import type { Appointment } from '../../../shared/types/appointment';
import { QUOTA_GROUP_LABELS } from '../../../shared/types/branch';
import { visitPermissions, VISIT_STATUS_LABELS, type CheckInRequest } from '../../../shared/types/visit';
import { QueryError, RowsSkeleton, WsButton } from '../ui';
import { arrivalWindow } from './arrival';
import { CustomerLookup } from './CustomerLookup';

export type CheckInTarget = { mode: 'walk-in' } | { mode: 'appointment'; arrival: Appointment };

const COPY = {
  label: 'Tìm khách tiếp nhận',
  placeholder: 'Tìm khách: SĐT 10 số, họ tên hoặc email',
  emptyTitle: 'Chọn khách đến quầy',
  emptyBody: 'Tìm khách theo SĐT hoặc họ tên rồi chọn thú cưng. Khách mới thì lập hồ sơ ở màn Khách hàng.',
  listLabel: 'Hồ sơ khách tiếp nhận',
};

export function CheckInDialog({ target, onClose }: { target: CheckInTarget | null; onClose: () => void }) {
  return (
    <Dialog open={!!target} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="ws-portal max-h-[90dvh] overflow-y-auto border-(--ws-line) bg-(--ws-surface) text-(--ws-ink) sm:max-w-xl">
        {target && <CheckInForm key={target.mode === 'appointment' ? target.arrival.appointmentId : 'walk-in'} target={target} onDone={onClose} />}
      </DialogContent>
    </Dialog>
  );
}

/** Thú đang có lượt chờ / đã gọi trong hàng đợi. BE không chặn tiếp nhận lần hai (ra thêm lượt và Order), nên UI chỉ cảnh báo. */
function OpenVisitWarning({ petId, petName }: { petId: number | null; petName: string }) {
  const perms = visitPermissions(useAccount()?.role);
  const open = useVisitQueue({}, perms.queue).data?.find((v) => v.petId === petId);
  if (petId === null || !open) return null;
  return (
    <p role="status" className="rounded-lg bg-(--ws-wait-bg) px-3 py-2 text-sm text-(--ws-wait-ink)">
      {petName} đang có lượt {open.code} ({VISIT_STATUS_LABELS[open.status].toLowerCase()}) trong hàng đợi. Tiếp nhận thêm sẽ tạo lượt và Order thứ hai.
    </p>
  );
}

function CheckInForm({ target, onDone }: { target: CheckInTarget; onDone: () => void }) {
  const checkIn = useCheckIn();
  const walkIn = target.mode === 'walk-in';
  const [customerId, setCustomerId] = useState<number | null>(null);
  const [petId, setPetId] = useState<number | null>(null);
  const [serviceId, setServiceId] = useState<number | null>(null);
  const [emergency, setEmergency] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const submit = () => {
    if (checkIn.isPending) return;
    let body: CheckInRequest;
    if (target.mode === 'appointment') {
      // Dịch vụ lấy từ lịch hẹn (A11); petId bắt buộc và phải là thú của lịch (BR-TN-01).
      body = { appointmentId: target.arrival.appointmentId, petId: target.arrival.petId, ...(emergency && { isEmergency: true }) };
    } else {
      if (petId === null || serviceId === null) {
        setProblem('Chọn thú cưng và dịch vụ trước khi tiếp nhận.');
        return;
      }
      body = { petId, serviceId, ...(emergency && { isEmergency: true }) };
    }
    setProblem(null);
    checkIn.mutate(body, {
      onSuccess: (visit) => {
        toast.success(`Đã tiếp nhận ${visit.petName}, mã ${visit.code}`, { description: 'Gán người phụ trách ở tab Hàng chờ.' });
        onDone();
      },
      onError: (e) => setProblem(visitErrorMessage(e, 'checkIn')),
    });
  };

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
          Lượt mới vào hàng chờ chưa có người phụ trách; gán người ở tab Hàng chờ.
        </DialogDescription>
      </div>

      {target.mode === 'appointment' ? (
        <AppointmentSummary arrival={target.arrival} />
      ) : (
        <WalkInPicker
          customerId={customerId}
          onCustomer={(id) => {
            setCustomerId(id);
            setPetId(null);
            setProblem(null);
          }}
          petId={petId}
          onPet={(id) => {
            setPetId(id);
            setProblem(null);
          }}
          serviceId={serviceId}
          onService={(id) => {
            setServiceId(id);
            setProblem(null);
          }}
        />
      )}

      {target.mode === 'appointment' && <OpenVisitWarning petId={target.arrival.petId} petName={target.arrival.petName} />}

      <label className="flex items-center gap-2 rounded-lg border border-(--ws-line) px-3 py-2.5 text-sm has-[:checked]:border-(--ws-urgent-ink) has-[:checked]:bg-(--ws-urgent-bg)">
        <input type="checkbox" checked={emergency} onChange={(e) => setEmergency(e.target.checked)} className="h-4 w-4 accent-(--ws-urgent-ink)" />
        <span>
          <span className="font-semibold">Cấp cứu</span>
          <span className="block text-xs text-(--ws-ink-2)">Lên đầu hàng chờ. Ngoài giờ mở cửa chỉ tiếp nhận ca cấp cứu (BR-CN-05).</span>
        </span>
      </label>

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

function AppointmentSummary({ arrival }: { arrival: Appointment }) {
  const now = useNow();
  const window = arrivalWindow(Date.parse(`${arrival.slotDate}T${arrival.slotStart}:00+07:00`), now);
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 rounded-lg bg-(--ws-raised) p-4 text-sm">
      <dt className="text-(--ws-ink-2)">Giờ hẹn</dt>
      <dd className="ws-num font-semibold">{arrival.slotStart}</dd>
      <dt className="text-(--ws-ink-2)">Thú cưng</dt>
      <dd className="font-semibold">{arrival.petName}</dd>
      <dt className="text-(--ws-ink-2)">Chủ nuôi</dt>
      <dd>{arrival.customerName}</dd>
      <dt className="text-(--ws-ink-2)">Dịch vụ</dt>
      <dd>{arrival.serviceName}</dd>
      <dt className="text-(--ws-ink-2)">Giờ đến</dt>
      <dd>
        <span className="ws-chip" data-tone={window.kind === 'ON_TIME' ? undefined : 'wait'}>
          {window.label}
        </span>
      </dd>
    </dl>
  );
}

/** Khách vãng lai: tìm khách (cùng ô tra cứu với màn thu tiền) → chọn thú còn sống → chọn dịch vụ Khám/Tiêm hoặc Thẩm mỹ đang bật tại chi nhánh. */
function WalkInPicker({
  customerId,
  onCustomer,
  petId,
  onPet,
  serviceId,
  onService,
}: {
  customerId: number | null;
  onCustomer: (id: number | null) => void;
  petId: number | null;
  onPet: (id: number) => void;
  serviceId: number | null;
  onService: (id: number) => void;
}) {
  const detail = useCustomerDetail(customerId);
  const pet = detail.data?.pets.find((p) => p.petId === petId);

  return (
    <>
      <div className="flex flex-col gap-2">
        <p className="text-[13px] font-semibold">Khách hàng và thú cưng</p>
        {customerId === null ? (
          <div className="rounded-lg border border-(--ws-line)">
            <CustomerLookup onPick={onCustomer} copy={COPY} />
          </div>
        ) : (
          <div className="flex flex-col gap-2 rounded-lg border border-(--ws-line) p-3">
            <div className="flex items-center justify-between gap-3 text-sm">
              <span>
                Khách: <strong>{detail.data?.customer.fullName ?? 'Đang tải…'}</strong>
                {detail.data?.customer.phone && <span className="ws-num text-(--ws-ink-3)"> {detail.data.customer.phone}</span>}
              </span>
              <WsButton size="sm" variant="ghost" onClick={() => onCustomer(null)}>
                Đổi khách
              </WsButton>
            </div>
            {detail.isLoading ? (
              <RowsSkeleton rows={1} />
            ) : detail.data && detail.data.pets.length === 0 ? (
              <p className="text-sm text-(--ws-ink-2)">Khách chưa có thú cưng. Thêm thú ở màn Khách hàng.</p>
            ) : (
              <div role="group" aria-label="Chọn thú cưng" className="flex flex-wrap gap-1.5">
                {detail.data?.pets.map((p) => (
                  <button
                    key={p.petId}
                    type="button"
                    disabled={!!p.deceasedOn}
                    aria-pressed={petId === p.petId}
                    onClick={() => onPet(p.petId)}
                    className={cn(
                      'h-8 rounded-full border border-(--ws-line-strong) px-3 text-[13px] hover:border-(--ws-accent) aria-pressed:border-(--ws-accent) aria-pressed:bg-(--ws-accent-soft) aria-pressed:font-semibold aria-pressed:text-(--ws-accent-ink)',
                      p.deceasedOn && 'cursor-not-allowed opacity-50',
                    )}
                  >
                    {p.name} <span className="opacity-70">({SPECIES_LABEL[p.species]}{p.deceasedOn ? ', đã mất' : ''})</span>
                  </button>
                ))}
              </div>
            )}
          </div>
        )}
        {pet && <OpenVisitWarning petId={pet.petId} petName={pet.name} />}
      </div>

      <fieldset className="flex flex-col gap-2">
        <legend className="mb-2 text-[13px] font-semibold">Dịch vụ</legend>
        <ServiceChoice serviceId={serviceId} onService={onService} />
      </fieldset>
    </>
  );
}

/** Dịch vụ Khám/Tiêm hoặc Thẩm mỹ đang bật tại chi nhánh của nhân viên (BR-LH-01). Chưa biết chi nhánh thì không hỏi (không gọi `/branches/0`). */
function ServiceChoice({ serviceId, onService }: { serviceId: number | null; onService: (id: number) => void }) {
  const branchId = useStaffBranchId();
  if (branchId === null) {
    return <p className="text-sm text-(--ws-ink-2)">Chưa xác định được chi nhánh của bạn nên chưa liệt kê được dịch vụ.</p>;
  }
  return <BranchServiceList branchId={branchId} serviceId={serviceId} onService={onService} />;
}

function BranchServiceList({ branchId, serviceId, onService }: { branchId: number; serviceId: number | null; onService: (id: number) => void }) {
  const services = useBranchServices(branchId);
  if (services.isLoading) return <RowsSkeleton rows={2} />;
  if (services.isError) return <QueryError error={services.error} onRetry={() => services.refetch()} what="dịch vụ" />;
  return (
    <div className="grid gap-1.5 sm:grid-cols-2">
      {(services.data ?? [])
        .filter((s) => s.enabled && s.group !== 'BOARDING')
        .map((s) => (
          <label
            key={s.serviceId}
            className="flex cursor-pointer items-center gap-2 rounded-lg border border-(--ws-line) px-3 py-2 text-sm has-[:checked]:border-(--ws-accent) has-[:checked]:bg-(--ws-accent-soft)"
          >
            <input
              type="radio"
              name="service"
              value={s.serviceId}
              checked={serviceId === s.serviceId}
              onChange={() => onService(s.serviceId)}
              className="accent-(--ws-accent)"
            />
            <span className="min-w-0 flex-1">
              <span className="block truncate font-medium">{s.name}</span>
              <span className="text-xs text-(--ws-ink-3)">{QUOTA_GROUP_LABELS[s.group as 'MEDICAL' | 'GROOMING']}</span>
            </span>
          </label>
        ))}
    </div>
  );
}
