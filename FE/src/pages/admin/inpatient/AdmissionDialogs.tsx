// Nhập viện (inpatient-v1 #1) và xuất viện (#4). Lỗi BR-LT-13 / BR-LT-14 / 409 của BE hiện ngay trong hộp thoại, không đóng hộp.
import { useState } from 'react';
import { Field } from '@/components/admin/form-ui';
import { ClinicalDialog, inputClass, textareaClass } from '@/components/clinical/ClinicalDialog';
import { useClinicalServices } from '@/components/clinical/useClinicalServices';
import { cn } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { useBookingList } from '@/shared/hooks/useBoarding';
import { useAdmit, useDischarge } from '@/shared/hooks/useInpatient';
import { useMyQueue } from '@/shared/hooks/useVisits';
import { DISCHARGE_OUTCOME_LABELS, DISCHARGE_OUTCOMES, type Admission, type DischargeOutcome } from '@/shared/types/inpatient';

type Source = 'visit' | 'booking';
const SOURCES: { id: Source; label: string }[] = [
  { id: 'visit', label: 'Từ lượt khám đang mở' },
  { id: 'booking', label: 'Từ đặt chỗ lưu trú' },
];

const choice = (active: boolean) =>
  cn('flex cursor-pointer items-center gap-2 rounded-lg border px-3 py-2.5 text-sm', active ? 'border-(--ws-accent) bg-(--ws-accent-soft)' : 'border-(--color-border-default) hover:bg-(--bg-secondary)');

/** Chuồng: BE cho vắng `kennelId` (dùng chuồng đang ở của đặt chỗ); VET không có endpoint đọc chuồng trống (GET /kennels 403) nên không chọn chuồng ở đây. */
export function AdmitDialog({ onClose, onDone }: { onClose: () => void; onDone: (admission: Admission) => void }) {
  const [source, setSource] = useState<Source>('visit');
  const [pickedId, setPickedId] = useState<number | null>(null);
  const [serviceId, setServiceId] = useState('');
  const [error, setError] = useState('');
  const queue = useMyQueue();
  const bookings = useBookingList({ status: 'CHECKED_IN', size: 50 });
  const { services, isPending: loadingServices } = useClinicalServices('INPATIENT');
  const admit = useAdmit();
  const visits = (queue.data ?? []).filter((v) => v.status === 'IN_PROGRESS' && v.serviceGroup === 'MEDICAL');
  const stays = bookings.data?.content ?? [];

  const submit = async () => {
    if (pickedId == null) return setError(source === 'visit' ? 'Chọn lượt khám đang mở.' : 'Chọn đặt chỗ lưu trú.');
    if (!serviceId) return setError('Chọn dịch vụ nội trú.');
    try {
      const body = source === 'visit' ? { visitId: pickedId, serviceId: Number(serviceId) } : { boardingBookingId: pickedId, serviceId: Number(serviceId) };
      onDone(await admit.mutateAsync(body));
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };

  const options = source === 'visit' ? visits.map((v) => ({ id: v.visitId, title: v.petName, meta: `${v.code} · ${v.customerName}` })) : stays.map((b) => ({ id: b.bookingId, title: b.petName, meta: `${b.code} · chuồng ${b.kennelCode ?? 'chưa gán'}` }));
  const loading = source === 'visit' ? queue.isPending : bookings.isPending;

  return (
    <ClinicalDialog
      title="Nhập viện"
      description="Chọn thú đang điều trị từ lượt khám của bạn, hoặc chuyển thú đang lưu trú sang điều trị."
      onClose={onClose}
      onSubmit={() => void submit()}
      submitText="Nhập viện"
      pending={admit.isPending}
      submitDisabled={services.length === 0}
      error={error}
      wide
    >
      <div role="group" aria-label="Nguồn nhập viện" className="flex gap-2">
        {SOURCES.map((s) => (
          <button
            key={s.id}
            type="button"
            aria-pressed={source === s.id}
            onClick={() => {
              setSource(s.id);
              setPickedId(null);
              setError('');
            }}
            className={cn('h-9 rounded-md border px-3 text-sm font-medium', source === s.id ? 'border-accent bg-accent text-white' : 'border-(--color-border-default) bg-white')}
          >
            {s.label}
          </button>
        ))}
      </div>
      <fieldset className="space-y-2">
        <legend className="mb-1.5 text-sm font-medium">{source === 'visit' ? 'Lượt khám đang mở' : 'Đặt chỗ đang lưu trú'}</legend>
        {!loading && options.length === 0 && (
          <p className="text-sm text-(--text-secondary)">{source === 'visit' ? 'Bạn chưa có lượt khám nào đang khám.' : 'Chưa có đặt chỗ nào đang ở trong chuồng.'}</p>
        )}
        {options.map((o) => (
          <label key={o.id} className={choice(pickedId === o.id)}>
            <input type="radio" name="admit-source" checked={pickedId === o.id} onChange={() => { setPickedId(o.id); setError(''); }} className="h-4 w-4 accent-[#a43324]" />
            <span>
              <strong>{o.title}</strong> <span className="text-(--text-secondary)">{o.meta}</span>
            </span>
          </label>
        ))}
      </fieldset>
      <Field label="Dịch vụ nội trú" hint={!loadingServices && services.length === 0 ? 'Danh mục chưa có dịch vụ nội trú đang kinh doanh nên chưa nhập viện được.' : 'Giá theo ngày, chốt lúc nhập viện.'}>
        {(id) => (
          <select id={id} className={inputClass} value={serviceId} onChange={(e) => setServiceId(e.target.value)}>
            <option value="">Chọn dịch vụ</option>
            {services.map((s) => (
              <option key={s.serviceId} value={s.serviceId}>
                {s.name}
              </option>
            ))}
          </select>
        )}
      </Field>
    </ClinicalDialog>
  );
}

export function DischargeDialog({ admission, onClose, onDone }: { admission: Admission; onClose: () => void; onDone: (message: string) => void }) {
  const [outcome, setOutcome] = useState<DischargeOutcome>('HOME');
  const [summary, setSummary] = useState('');
  const [error, setError] = useState('');
  const discharge = useDischarge();
  const submit = async () => {
    if (!summary.trim()) return setError('Nhập tóm tắt điều trị.');
    try {
      await discharge.mutateAsync({ admissionId: admission.admissionId, outcome, summary: summary.trim() });
      onDone(`Đã xuất viện ${admission.petName}: dòng dịch vụ nội trú đã vào Order, chuyển thu ngân khi hoàn tất lượt.`);
    } catch (e) {
      setError(plainMessage(toApiError(e)));
    }
  };
  return (
    <ClinicalDialog title="Xuất viện" description={`${admission.petName} · ${admission.code}`} onClose={onClose} onSubmit={() => void submit()} submitText="Xuất viện" cancelText="Quay lại" pending={discharge.isPending} error={error}>
      <fieldset>
        <legend className="mb-1.5 text-sm font-medium">Lý do xuất viện</legend>
        <div className="grid grid-cols-2 gap-2">
          {DISCHARGE_OUTCOMES.map((o) => (
            <label key={o} className={choice(outcome === o)}>
              <input type="radio" name="discharge-outcome" checked={outcome === o} onChange={() => { setOutcome(o); setError(''); }} className="h-4 w-4 accent-[#a43324]" />
              {DISCHARGE_OUTCOME_LABELS[o]}
            </label>
          ))}
        </div>
      </fieldset>
      <Field label="Tóm tắt điều trị">{(id) => <textarea id={id} rows={4} className={textareaClass} value={summary} onChange={(e) => setSummary(e.target.value)} />}</Field>
    </ClinicalDialog>
  );
}
