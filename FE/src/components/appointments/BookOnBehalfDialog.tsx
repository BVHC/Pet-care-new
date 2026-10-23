/**
 * Lễ tân đặt lịch hộ (UC39, A06) — appointment-v1 #2, kênh COUNTER: tìm khách (UC23) → chọn thú → dịch vụ đang bật tại
 * chi nhánh (Khám/Tiêm, Thẩm mỹ — BR-LH-01) → khung chưa bắt đầu còn chỗ (BR-LH-04) → ghi chú → đặt.
 * Khách của lịch là chủ hiện tại của thú (A2). Giới hạn số lịch của thú vẫn áp dụng (BR-LH-05); hạn chế online không chặn.
 */
import { useState } from 'react';
import { Search } from 'lucide-react';
import { toast } from 'sonner';
import { Field, FormError, selectClass } from '@/components/admin/form-ui';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { appointmentApi } from '@/shared/api/appointment.api';
import { useBranchServices } from '@/shared/hooks/useBranch';
import { useDebounce } from '@/shared/hooks/useDebounce';
import { useAppointmentMutation, useRefreshAppointments } from '@/shared/hooks/useAppointments';
import { useCustomerDetail, useCustomerLookup } from '@/domains/pets/hooks/useCustomerPet';
import { SPECIES_LABEL } from '@/domains/pets/labels';
import type { CustomerSearchQuery } from '@/domains/pets/types';
import { QUOTA_GROUP_LABELS } from '@/shared/types/branch';
import { CustomerRestrictionPanel } from './CustomerRestrictionPanel';
import { formatSlot, todayVn } from './format';
import { SlotPicker } from './SlotPicker';

const NOTE_MAX = 500;

/** SĐT đủ 10 số thì tra theo SĐT, còn lại tra theo họ tên (BR-KH-10). */
const queryOf = (term: string): CustomerSearchQuery => (/^0\d{9}$/.test(term) ? { phone: term } : { name: term.length >= 2 ? term : '' });

export function BookOnBehalfDialog({ open, branchId, onClose }: { open: boolean; branchId: number; onClose: () => void }) {
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto bg-white sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Đặt lịch hộ khách</DialogTitle>
          <DialogDescription>Lịch đặt tại quầy (kênh COUNTER) cho chi nhánh của bạn; đặt được mọi khung chưa bắt đầu còn chỗ.</DialogDescription>
        </DialogHeader>
        {open && <BookForm branchId={branchId} onClose={onClose} />}
      </DialogContent>
    </Dialog>
  );
}

function BookForm({ branchId, onClose }: { branchId: number; onClose: () => void }) {
  const [term, setTerm] = useState('');
  const debounced = useDebounce(term.trim(), 250);
  const lookup = useCustomerLookup(queryOf(debounced));
  const [customerId, setCustomerId] = useState<number | null>(null);
  const detail = useCustomerDetail(customerId);
  const services = useBranchServices(branchId);
  const [petId, setPetId] = useState<number | null>(null);
  const [serviceId, setServiceId] = useState<number | null>(null);
  const [date, setDate] = useState<string | null>(todayVn());
  const [slotStart, setSlotStart] = useState<string | null>(null);
  const [note, setNote] = useState('');
  const [error, setError] = useState('');
  const book = useAppointmentMutation(appointmentApi.book);
  const refreshAppointments = useRefreshAppointments();

  // Chỉ dịch vụ đang bật, nhóm Khám/Tiêm hoặc Thẩm mỹ (BR-LH-01).
  const bookable = (services.data ?? []).filter((s) => s.enabled && s.group !== 'BOARDING');
  const customer = detail.data?.customer;

  const chooseCustomer = (id: number | null) => {
    setCustomerId(id);
    setPetId(null);
    setError('');
  };

  const submit = () => {
    setError('');
    if (customerId == null || petId == null || serviceId == null || !date || !slotStart) {
      setError('Vui lòng chọn đủ khách, thú cưng, dịch vụ, ngày và giờ hẹn.');
      return;
    }
    if (note.trim().length > NOTE_MAX) {
      setError(`Ghi chú tối đa ${NOTE_MAX} ký tự.`);
      return;
    }
    book.mutate(
      { petId, branchId, serviceId, slotDate: date, slotStart, note: note.trim() || undefined },
      {
        onSuccess: (a) => {
          toast.success(`Đã đặt lịch ${a.code} — ${a.petName}, ${formatSlot(a)}`);
          onClose();
        },
        onError: (e) => {
          const apiError = toApiError(e);
          refreshAppointments();
          setError(apiError.ruleId === 'BR-LH-03' ? 'Khung giờ vừa hết chỗ, vui lòng chọn khung khác.' : plainMessage(apiError));
        },
      },
    );
  };

  if (customerId == null) {
    const items = debounced ? (lookup.data?.content ?? []) : [];
    return (
      <div className="space-y-3">
        <div className="relative">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-(--text-tertiary)" aria-hidden />
          <Input
            aria-label="Tìm khách theo SĐT hoặc họ tên"
            placeholder="SĐT 10 số hoặc họ tên"
            className="pl-9"
            value={term}
            onChange={(e) => setTerm(e.target.value)}
            autoFocus
          />
        </div>
        {lookup.isError && <FormError message={plainMessage(toApiError(lookup.error))} />}
        {debounced && lookup.isSuccess && items.length === 0 && (
          <p className="text-sm text-(--text-secondary)">Không tìm thấy khách. Hồ sơ mới tạo ở màn Khách hàng.</p>
        )}
        <ul className="space-y-2" aria-label="Kết quả tìm khách">
          {items.map((c) => (
            <li key={c.customerId} className="flex items-start justify-between gap-3 rounded-lg border border-(--color-border-default) p-3">
              <div className="min-w-0 text-sm">
                <p className="font-semibold">{c.fullName}</p>
                <p className="text-xs text-(--text-secondary)">
                  {c.phone ?? 'Chưa có SĐT'} · {c.maskedEmail ?? 'Chưa có email'}
                </p>
                {c.pets.length > 0 && (
                  <p className="mt-1 text-xs text-(--text-secondary)">Thú cưng: {c.pets.map((p) => p.name + (p.deceased ? ' (đã mất)' : '')).join(', ')}</p>
                )}
              </div>
              <Button size="sm" variant="outline" onClick={() => chooseCustomer(c.customerId)} aria-label={`Chọn khách ${c.fullName}, ${c.maskedEmail ?? c.phone ?? ''}`}>
                Chọn
              </Button>
            </li>
          ))}
        </ul>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3 rounded-lg bg-(--bg-tertiary) px-3 py-2 text-sm">
        <span>
          Khách: <strong>{customer?.fullName ?? 'Đang tải…'}</strong>
          {customer?.phone && ` · ${customer.phone}`}
        </span>
        <Button size="sm" variant="ghost" onClick={() => chooseCustomer(null)}>
          Đổi khách
        </Button>
      </div>
      {customer && <CustomerRestrictionPanel customerId={customer.customerId} customerName={customer.fullName} />}

      <div>
        <p className="mb-1.5 text-sm font-medium">Thú cưng</p>
        {detail.data && detail.data.pets.length === 0 && <p className="text-sm text-(--text-secondary)">Khách chưa có thú cưng.</p>}
        <div role="group" aria-label="Chọn thú cưng" className="flex flex-wrap gap-2">
          {detail.data?.pets.map((p) => (
            <button
              key={p.petId}
              type="button"
              disabled={!!p.deceasedOn}
              aria-pressed={petId === p.petId}
              onClick={() => setPetId(p.petId)}
              className={cn(
                'rounded-full border px-3 py-1.5 text-sm',
                petId === p.petId ? 'border-accent bg-accent text-white' : 'border-(--color-border-default) bg-white',
                p.deceasedOn && 'cursor-not-allowed opacity-50',
              )}
            >
              {p.name} · {SPECIES_LABEL[p.species]}
              {p.deceasedOn && ' (đã mất)'}
            </button>
          ))}
        </div>
      </div>

      <Field label="Dịch vụ">
        {(id) => (
          <select
            id={id}
            className={selectClass}
            value={serviceId ?? ''}
            onChange={(e) => {
              setServiceId(e.target.value ? Number(e.target.value) : null);
              setSlotStart(null);
            }}
          >
            <option value="">Chọn dịch vụ</option>
            {bookable.map((s) => (
              <option key={s.serviceId} value={s.serviceId}>
                {s.name} ({QUOTA_GROUP_LABELS[s.group as 'MEDICAL' | 'GROOMING']})
              </option>
            ))}
          </select>
        )}
      </Field>

      <SlotPicker
        branchId={branchId}
        serviceId={serviceId}
        date={date}
        onDateChange={setDate}
        value={slotStart}
        onChange={setSlotStart}
        minDate={todayVn()}
        hint="Lễ tân đặt được mọi khung chưa bắt đầu còn chỗ."
      />

      <Field label="Ghi chú (không bắt buộc)" hint={`${note.trim().length}/${NOTE_MAX} ký tự`}>
        {(id) => (
          <textarea
            id={id}
            rows={2}
            value={note}
            onChange={(e) => setNote(e.target.value)}
            className="w-full rounded-lg border border-(--color-border-default) bg-white px-3 py-2 text-sm"
          />
        )}
      </Field>

      <FormError message={error} />
      <div className="flex justify-end gap-2">
        <Button variant="outline" onClick={onClose} disabled={book.isPending}>
          Đóng
        </Button>
        <Button onClick={submit} disabled={book.isPending}>
          {book.isPending ? 'Đang đặt…' : 'Đặt lịch'}
        </Button>
      </div>
    </div>
  );
}
