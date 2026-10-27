// Lễ tân đặt lưu trú tại quầy (UC58, boarding-v1 #7 + #8, kênh COUNTER): khách → thú → loại chuồng phù hợp → ngày → còn chỗ từng đêm → đặt.
// Loại chuồng lấy từ dữ liệu công khai (dịch vụ BOARDING bật tại chi nhánh của lễ tân), ẩn loại sai loài / quá cân (BR-LT-02); không gọi GET /kennels.
// Lễ tân đặt được cả trong ngày (BR-LT-04). Thiếu mũi tiêm chỉ cảnh báo, không chặn (BR-LT-05). Lỗi BR-* của BE hiện nguyên câu.
import { useState } from 'react';
import { toast } from 'sonner';
import { Field, FormError } from '@/components/admin/form-ui';
import { formatShortDate, todayVn } from '@/components/appointments/format';
import { CustomerLookup } from '@/components/staff/reception/CustomerLookup';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { useCustomerDetail } from '@/domains/pets/hooks/useCustomerPet';
import { SPECIES_LABEL } from '@/domains/pets/types';
import { cn, formatCurrency } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { boardingApi } from '@/shared/api/boarding.api';
import { useBoardingAvailability, useBoardingMutation, useRefreshBoarding } from '@/shared/hooks/useBoarding';
import { usePublicServiceList } from '@/shared/hooks/usePublicSite';
import type { BookingResult, CreateBookingRequest } from '@/shared/types/boarding';
import { shiftDate, stayAmount } from '@/shared/utils/boarding';
import { kennelFits, shortDay, vaccineGapText } from './rules';

const LOOKUP_COPY = {
  label: 'Tìm khách theo SĐT, họ tên hoặc email',
  placeholder: 'SĐT 10 số, họ tên hoặc email',
  emptyTitle: 'Chọn khách đặt chỗ',
  emptyBody: 'Tìm khách theo SĐT, họ tên hoặc email. Hồ sơ mới tạo ở màn Khách hàng.',
  listLabel: 'Kết quả tìm khách',
};

const weightText = (kg?: number | null) => (kg == null ? 'chưa cân' : `${new Intl.NumberFormat('vi-VN').format(kg)} kg`);

export function CounterBookingDialog({ branchId, onClose }: { branchId: number; onClose: () => void }) {
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto bg-white sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Đặt chỗ lưu trú tại quầy</DialogTitle>
          <DialogDescription>Đặt hộ khách tại chi nhánh của bạn (kênh đặt tại quầy); đặt được cả ngày nhận là hôm nay.</DialogDescription>
        </DialogHeader>
        <CounterBookingForm branchId={branchId} onClose={onClose} />
      </DialogContent>
    </Dialog>
  );
}

function CounterBookingForm({ branchId, onClose }: { branchId: number; onClose: () => void }) {
  const today = todayVn();
  const [customerId, setCustomerId] = useState<number | null>(null);
  const [petId, setPetId] = useState<number | null>(null);
  const [typeId, setTypeId] = useState<number | null>(null);
  const [checkIn, setCheckIn] = useState(today);
  const [checkOut, setCheckOut] = useState(shiftDate(today, 1));
  const [error, setError] = useState('');
  const [result, setResult] = useState<BookingResult | null>(null);
  const detail = useCustomerDetail(customerId);
  const types = usePublicServiceList({ group: 'BOARDING', branchId });
  const availability = useBoardingAvailability(branchId, { kennelTypeId: typeId ?? undefined, checkInDate: checkIn, checkOutDate: checkOut });
  const book = useBoardingMutation((req: CreateBookingRequest) => boardingApi.book(req));
  const refresh = useRefreshBoarding();

  const customer = detail.data?.customer;
  const pet = detail.data?.pets.find((p) => p.petId === petId) ?? null;
  const allTypes = types.data ?? [];
  const fitting = pet ? allTypes.filter((t) => kennelFits(t, pet)) : [];
  const nights = availability.data?.nights ?? [];
  const fullNights = nights.filter((n) => n.remaining <= 0).map((n) => shortDay(n.date));
  const canBook = petId != null && typeId != null && availability.data?.available === true && !book.isPending;

  const touch = () => setError('');
  const chooseCustomer = (id: number | null) => {
    setCustomerId(id);
    setPetId(null);
    setTypeId(null);
    touch();
  };
  const choosePet = (id: number) => {
    setPetId(id);
    setTypeId(null);
    touch();
  };
  const changeCheckIn = (value: string) => {
    setCheckIn(value);
    if (value >= checkOut) setCheckOut(shiftDate(value, 1));
    touch();
  };

  const submit = () => {
    if (petId == null || typeId == null) return;
    touch();
    book.mutate(
      { petId, branchId, kennelTypeId: typeId, checkInDate: checkIn, checkOutDate: checkOut },
      {
        onSuccess: (res) => {
          if (res.vaccineWarnings.length === 0) {
            toast.success(`Đã đặt chỗ ${res.booking.code}`);
            onClose();
          } else setResult(res);
        },
        onError: (e) => {
          setError(plainMessage(toApiError(e)));
          // Còn chỗ vừa đổi (người khác đặt trước): đọc lại để bảng đêm và nút khớp thực tế.
          refresh();
        },
      },
    );
  };

  if (result) {
    const { booking, vaccineWarnings } = result;
    return (
      <div className="space-y-4">
        <p role="status" className="rounded-lg bg-green-50 px-3 py-2 text-sm font-medium text-green-700">
          {`Đã đặt chỗ ${booking.code}`}
        </p>
        <p className="text-sm">{`${booking.petName} · ${booking.kennelTypeName} · ${formatShortDate(booking.checkInDate)} → ${formatShortDate(booking.checkOutDate)}`}</p>
        <section aria-label="Cảnh báo tiêm phòng" className="space-y-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-900">
          <p className="font-medium">Thú còn thiếu mũi tiêm bắt buộc vào ngày nhận. Vẫn đặt được; lúc nhận thú sẽ kiểm tra lại (BR-LT-05).</p>
          <ul className="list-disc space-y-0.5 pl-5">
            {vaccineWarnings.map((g) => (
              <li key={g.vaccineTypeId}>{`${g.vaccineTypeName}: ${vaccineGapText(g)}`}</li>
            ))}
          </ul>
        </section>
        <div className="flex justify-end">
          <Button className="min-h-10" onClick={onClose}>
            Xong
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <section aria-label="Khách" className="space-y-2">
        <p className="text-sm font-medium">1. Khách</p>
        {customerId == null ? (
          <div className="rounded-lg border border-(--color-border-default)">
            <CustomerLookup onPick={chooseCustomer} copy={LOOKUP_COPY} />
          </div>
        ) : (
          <div className="flex items-center justify-between gap-3 rounded-lg bg-(--bg-tertiary) px-3 py-2 text-sm">
            <span>
              <strong>{customer?.fullName ?? 'Đang tải…'}</strong>
              {customer?.phone && ` · ${customer.phone}`}
            </span>
            <Button size="sm" variant="ghost" className="min-h-10" onClick={() => chooseCustomer(null)}>
              Đổi khách
            </Button>
          </div>
        )}
        {detail.isError && <FormError message={plainMessage(toApiError(detail.error))} />}
      </section>

      {customerId != null && detail.data && (
        <section aria-label="Thú cưng" className="space-y-2">
          <p className="text-sm font-medium">2. Thú cưng</p>
          {detail.data.pets.length === 0 && <p className="text-sm text-(--text-secondary)">Khách chưa có thú cưng.</p>}
          <div role="group" aria-label="Chọn thú cưng" className="flex flex-wrap gap-2">
            {detail.data.pets.map((p) => (
              <button
                key={p.petId}
                type="button"
                disabled={!!p.deceasedOn}
                aria-pressed={petId === p.petId}
                onClick={() => choosePet(p.petId)}
                className={cn(
                  'min-h-10 rounded-full border px-3 py-1.5 text-sm',
                  petId === p.petId ? 'border-accent bg-accent text-white' : 'border-(--color-border-default) bg-white',
                  p.deceasedOn && 'cursor-not-allowed opacity-50',
                )}
              >
                {`${p.name} · ${SPECIES_LABEL[p.species]} · ${weightText(p.currentWeightKg)}${p.deceasedOn ? ' (đã mất)' : ''}`}
              </button>
            ))}
          </div>
        </section>
      )}

      {pet && (
        <section aria-label="Loại chuồng" className="space-y-2">
          <p className="text-sm font-medium">3. Loại chuồng</p>
          {types.isError && <FormError message={plainMessage(toApiError(types.error))} />}
          {types.isLoading && <p className="text-sm text-(--text-secondary)">Đang tải loại chuồng…</p>}
          {types.data && fitting.length === 0 && (
            <p className="text-sm text-(--text-secondary)">{`Chưa có loại chuồng phù hợp với ${pet.name} tại chi nhánh này.`}</p>
          )}
          <div role="group" aria-label="Chọn loại chuồng" className="flex flex-wrap gap-2">
            {fitting.map((t) => (
              <button
                key={t.serviceId}
                type="button"
                aria-pressed={typeId === t.serviceId}
                onClick={() => {
                  setTypeId(t.serviceId);
                  touch();
                }}
                className={cn(
                  'min-h-10 rounded-lg border px-3 py-2 text-left text-sm',
                  typeId === t.serviceId ? 'border-accent bg-accent text-white' : 'border-(--color-border-default) bg-white',
                )}
              >
                <span className="font-medium">{t.name}</span>
                <span className="block text-xs opacity-80">
                  {`${SPECIES_LABEL[t.kennelType!.species]} · tối đa ${t.kennelType!.maxWeightKg} kg · ${formatCurrency(t.price)}/đêm`}
                </span>
              </button>
            ))}
          </div>
          {types.data && allTypes.length > fitting.length && (
            <p className="text-xs text-(--text-tertiary)">{`Đã ẩn ${allTypes.length - fitting.length} loại chuồng sai loài hoặc quá cân nặng.`}</p>
          )}
        </section>
      )}

      {typeId != null && (
        <section aria-label="Ngày lưu trú" className="space-y-3">
          <p className="text-sm font-medium">4. Ngày lưu trú</p>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label="Ngày nhận">
              {(id) => <Input id={id} type="date" min={today} value={checkIn} onChange={(e) => changeCheckIn(e.target.value)} />}
            </Field>
            <Field label="Ngày trả">
              {(id) => (
                <Input
                  id={id}
                  type="date"
                  min={shiftDate(checkIn, 1)}
                  value={checkOut}
                  onChange={(e) => {
                    setCheckOut(e.target.value);
                    touch();
                  }}
                />
              )}
            </Field>
          </div>

          <p className="text-sm font-medium">5. Còn chỗ từng đêm</p>
          {availability.isLoading && <p className="text-sm text-(--text-secondary)">Đang kiểm tra chỗ…</p>}
          {availability.isError && <FormError message={plainMessage(toApiError(availability.error))} />}
          {availability.data && (
            <>
              <ul aria-label="Còn chỗ từng đêm" className="flex flex-wrap gap-2">
                {nights.map((n) => (
                  <li
                    key={n.date}
                    className={cn(
                      'rounded-lg border px-3 py-1.5 text-sm tabular-nums',
                      n.remaining <= 0 ? 'border-red-300 bg-red-50 font-medium text-red-700' : 'border-(--color-border-default) bg-white',
                    )}
                  >
                    {n.remaining <= 0 ? `${shortDay(n.date)} · hết chỗ ${n.remaining}/${n.capacity}` : `${shortDay(n.date)} · còn ${n.remaining}/${n.capacity}`}
                  </li>
                ))}
              </ul>
              {fullNights.length > 0 && <p className="text-sm font-medium text-red-700">{`Hết chỗ các đêm: ${fullNights.join(', ')}. Chọn ngày khác.`}</p>}
              <p className="text-sm font-medium">{`Tạm tính ${formatCurrency(stayAmount(nights.length, availability.data.nightlyPrice))}`}</p>
              <p className="text-xs text-(--text-tertiary)">{`${nights.length} đêm × ${formatCurrency(availability.data.nightlyPrice)}`}</p>
            </>
          )}
        </section>
      )}

      <FormError message={error} />
      <div className="flex justify-end gap-2">
        <Button variant="outline" className="min-h-10" onClick={onClose} disabled={book.isPending}>
          Đóng
        </Button>
        <Button className="min-h-10" onClick={submit} disabled={!canBook}>
          {book.isPending ? 'Đang đặt…' : 'Đặt chỗ'}
        </Button>
      </div>
    </div>
  );
}
