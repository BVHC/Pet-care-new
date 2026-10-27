// UC59 "Nhận thú" (A1, BR-LT-03/04/05/08): lễ tân hoặc CARETAKER kiểm tra điều kiện (mũi tiêm, giờ mở cửa, chuồng trống đúng loại) rồi nhận thú vào chuồng.
// Chữ ký và tên truy cập của role="dialog" ("Nhận thú") là hợp đồng với AdminBoardingPage: không đổi.
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { toast } from 'sonner';
import { Field, FormError, selectClass } from '@/components/admin/form-ui';
import { formatShortDate, todayVn } from '@/components/appointments/format';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { fieldErrorsOf, plainMessage, toApiError } from '@/shared/api/api-error';
import { boardingApi } from '@/shared/api/boarding.api';
import { useBoardingMutation, useCheckInReadiness, useRefreshBoarding } from '@/shared/hooks/useBoarding';
import type { Booking, CheckInReadiness, CheckInRequest, VaccineGap } from '@/shared/types/boarding';
import { useAllowed } from './hooks';
import { NoKennelDialog } from './NoKennelDialog';
import { BookingSummary, Callout, StayError, StayFooter, StayForbidden, StayLoading, StayModal } from './shared';

const textareaClass =
  'w-full rounded-lg border border-(--color-border-default) px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-accent';
const PHONE = /^0[0-9]{9,10}$/;

type FieldName = 'kennelId' | 'weightKg' | 'healthCondition' | 'belongings' | 'dietInstructions' | 'emergencyPhone';
type Errors = Partial<Record<FieldName, string>>;
const FIELD_NAMES: readonly string[] = ['kennelId', 'weightKg', 'healthCondition', 'belongings', 'dietInstructions', 'emergencyPhone'];

/** Cân nặng nhập có thể dùng dấu phẩy; > 0, tối đa 9999,99 và 2 chữ số thập phân (A1, cùng câu với BE). */
function parseWeight(raw: string): { value?: number; error?: string } {
  const text = raw.trim().replace(',', '.');
  if (!text) return { error: 'Vui lòng nhập cân nặng' };
  if (!/^-?\d+(\.\d+)?$/.test(text)) return { error: 'Cân nặng phải là số, ví dụ 6,5' };
  const value = Number(text);
  if (value <= 0) return { error: 'Cân nặng phải lớn hơn 0' };
  if (!/^\d{1,4}(\.\d{1,2})?$/.test(text)) return { error: 'Cân nặng tối đa 9999.99 kg và 2 chữ số thập phân' };
  return { value };
}

export function CheckInDialog({ booking, onClose }: { booking: Booking; onClose: () => void }) {
  const allowed = useAllowed(['RECEPTIONIST', 'CARETAKER']);
  const isDesk = useAllowed(['RECEPTIONIST']);
  const [noKennel, setNoKennel] = useState(false);
  return (
    <>
      <StayModal title="Nhận thú" onClose={onClose}>
        {allowed ? (
          <CheckInBody booking={booking} isDesk={isDesk} onClose={onClose} onNoKennel={() => setNoKennel(true)} />
        ) : (
          <StayForbidden who="Chỉ lễ tân và nhân viên chăm sóc được nhận thú." onClose={onClose} />
        )}
      </StayModal>
      {noKennel && <NoKennelDialog booking={booking} onClose={() => setNoKennel(false)} onDone={onClose} />}
    </>
  );
}

function CheckInBody({ booking, isDesk, onClose, onNoKennel }: { booking: Booking; isDesk: boolean; onClose: () => void; onNoKennel: () => void }) {
  const readiness = useCheckInReadiness(booking.bookingId);
  const refresh = useRefreshBoarding();
  /** Lỗi của lệnh nhận: giữ ở đây vì làm mới điều kiện có thể đổi form thành màn chặn, câu lỗi vẫn phải đọc được. */
  const [serverError, setServerError] = useState('');
  /** 409: người khác vừa nhận / hủy đặt chỗ này. */
  const [conflict, setConflict] = useState(false);

  if (conflict) {
    return (
      <>
        <FormError message={serverError} />
        <p className="text-sm text-(--text-secondary)">Có người vừa xử lý đặt chỗ này. Đã tải lại danh sách lưu trú.</p>
        <StayFooter>
          <Button onClick={onClose}>Đóng</Button>
        </StayFooter>
      </>
    );
  }
  const r = readiness.data;
  if (!r) {
    if (readiness.isError) return <StayError error={readiness.error} onRetry={() => readiness.refetch()} onClose={onClose} />;
    return <StayLoading what="Đang kiểm tra điều kiện nhận thú…" />;
  }
  const early = booking.checkInDate > todayVn();
  const noKennels = r.availableKennels.length === 0;
  return (
    <div className="space-y-4">
      <BookingSummary booking={booking} />
      {early && (
        <Callout tone="info">
          <p>
            Nhận sớm hơn ngày đặt ({formatShortDate(booking.checkInDate)}): tính đêm theo thực tế, từ hôm nay (BR-LT-08). Chỉ nhận được nếu các đêm còn chỗ.
          </p>
        </Callout>
      )}
      <FormError message={serverError} />
      {!r.withinOpeningHours && (
        <Callout tone="danger">
          <p>Ngoài giờ mở cửa của chi nhánh, chưa nhận thú được (BR-LT-04). Nhận và trả thú chỉ trong giờ mở cửa, không vào ngày nghỉ.</p>
        </Callout>
      )}
      {r.vaccineGaps.length > 0 && <VaccineGaps gaps={r.vaccineGaps} isDesk={isDesk} />}
      {noKennels && (
        <Callout tone="danger">
          <p>
            Không còn chuồng trống đúng loại “{booking.kennelTypeName}” để nhận thú; không gán chuồng loại khác (BR-LT-08).{' '}
            {isDesk
              ? 'Nếu không bố trí được, hủy đặt chỗ như hủy do phòng khám (không tính hủy muộn).'
              : 'Báo lễ tân hoặc quản lý chi nhánh để xử lý.'}
          </p>
          {isDesk && (
            <Button variant="outline" className="mt-2 bg-white" onClick={onNoKennel}>
              Hủy vì hết chuồng
            </Button>
          )}
        </Callout>
      )}
      {r.canCheckIn ? (
        <CheckInForm
          booking={booking}
          readiness={r}
          onClose={onClose}
          onServerError={(message, isConflict) => {
            setServerError(message);
            setConflict(isConflict);
            refresh();
          }}
          onClearServerError={() => setServerError('')}
        />
      ) : (
        <StayFooter>
          <Button variant="outline" onClick={onClose}>
            Đóng
          </Button>
        </StayFooter>
      )}
    </div>
  );
}

/** BR-LT-05: thiếu hoặc quá hạn mũi bắt buộc khi lưu trú thì không nhận; tiêm bù qua đường walk-in của lễ tân. */
function VaccineGaps({ gaps, isDesk }: { gaps: VaccineGap[]; isDesk: boolean }) {
  return (
    <Callout tone="danger" title="Thiếu mũi tiêm bắt buộc">
      <p>
        Thú chưa đủ mũi tiêm bắt buộc khi lưu trú nên chưa nhận được (BR-LT-05). Mũi tiêm ở nơi khác không được tính; chỉ mũi đã ghi tại hệ thống.
      </p>
      <div className="mt-2 overflow-x-auto rounded-md bg-white">
        <table aria-label="Mũi tiêm còn thiếu" className="w-full text-left text-xs text-(--text-primary)">
          <thead>
            <tr className="border-b border-(--color-border-default)">
              <th scope="col" className="px-2 py-1.5 font-semibold">
                Loại vaccine
              </th>
              <th scope="col" className="px-2 py-1.5 font-semibold">
                Lần tiêm cuối
              </th>
              <th scope="col" className="px-2 py-1.5 font-semibold">
                Hạn tái chủng
              </th>
            </tr>
          </thead>
          <tbody>
            {gaps.map((g) => (
              <tr key={g.vaccineTypeId} className="border-b border-(--color-border-default) last:border-0">
                <td className="px-2 py-1.5 font-medium">{g.vaccineTypeName}</td>
                <td className="px-2 py-1.5">{g.lastAdministeredOn ? formatShortDate(g.lastAdministeredOn) : 'Chưa tiêm'}</td>
                <td className="px-2 py-1.5">{g.nextDueDate ? formatShortDate(g.nextDueDate) : '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="mt-2">
        {isDesk ? (
          <>
            <Link to="/staff/reception" className="font-semibold underline">
              Tiếp nhận như walk-in để tiêm trước
            </Link>
            , xong lượt tiêm thì quay lại nhận thú.
          </>
        ) : (
          'Nhờ lễ tân tiếp nhận thú như walk-in để tiêm trước, xong lượt tiêm thì nhận thú.'
        )}
      </p>
    </Callout>
  );
}

function CheckInForm({
  booking,
  readiness,
  onClose,
  onServerError,
  onClearServerError,
}: {
  booking: Booking;
  readiness: CheckInReadiness;
  onClose: () => void;
  onServerError: (message: string, conflict: boolean) => void;
  onClearServerError: () => void;
}) {
  const checkIn = useBoardingMutation((body: CheckInRequest) => boardingApi.checkIn(booking.bookingId, body));
  const kennels = readiness.availableKennels;
  const [kennelId, setKennelId] = useState('');
  const [weight, setWeight] = useState('');
  const [health, setHealth] = useState('');
  const [belongings, setBelongings] = useState('');
  const [diet, setDiet] = useState('');
  const [phone, setPhone] = useState('');
  const [errors, setErrors] = useState<Errors>({});

  // Chuồng đã chọn mà vừa hết (làm mới điều kiện) thì bỏ chọn; chỉ còn một chuồng thì chọn sẵn.
  const chosen = kennels.some((k) => String(k.kennelId) === kennelId) ? kennelId : kennels.length === 1 ? String(kennels[0].kennelId) : '';
  const edit = (field: FieldName, set: (value: string) => void) => (value: string) => {
    set(value);
    setErrors((prev) => ({ ...prev, [field]: undefined }));
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    onClearServerError();
    const found: Errors = {};
    const parsed = parseWeight(weight);
    if (!chosen) found.kennelId = 'Vui lòng chọn chuồng';
    if (parsed.error) found.weightKg = parsed.error;
    if (!health.trim()) found.healthCondition = 'Vui lòng nhập tình trạng sức khỏe';
    if (!phone.trim()) found.emergencyPhone = 'Vui lòng nhập số điện thoại liên hệ khẩn';
    else if (!PHONE.test(phone.trim())) found.emergencyPhone = 'Số điện thoại liên hệ khẩn phải bắt đầu bằng 0 và có 10–11 chữ số';
    setErrors(found);
    if (Object.keys(found).length > 0 || parsed.value === undefined) return;

    const body: CheckInRequest = {
      kennelId: Number(chosen),
      weightKg: parsed.value,
      healthCondition: health.trim(),
      emergencyPhone: phone.trim(),
      ...(belongings.trim() && { belongings: belongings.trim() }),
      ...(diet.trim() && { dietInstructions: diet.trim() }),
    };
    checkIn.mutate(body, {
      onSuccess: (updated) => {
        toast.success(`Đã nhận ${booking.petName} vào chuồng ${updated.kennelCode ?? ''}`.trim() + '.');
        onClose();
      },
      onError: (err) => {
        const apiError = toApiError(err);
        const fields = fieldErrorsOf(apiError);
        const names = Object.keys(fields);
        if (names.length > 0 && names.every((n) => FIELD_NAMES.includes(n))) {
          setErrors(fields as Errors);
          onServerError('', false);
        } else {
          onServerError(plainMessage(apiError), apiError.statusCode === 409);
        }
      },
    });
  };

  return (
    <form onSubmit={submit} className="space-y-4" noValidate>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Chuồng" error={errors.kennelId} hint="Chỉ chuồng trống đúng loại đã đặt.">
          {(id) => (
            <select id={id} className={selectClass} value={chosen} onChange={(e) => edit('kennelId', setKennelId)(e.target.value)}>
              <option value="">Chọn chuồng</option>
              {kennels.map((k) => (
                <option key={k.kennelId} value={k.kennelId}>
                  {k.code} · {k.kennelTypeName}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Cân nặng khi nhận (kg)" error={errors.weightKg} hint="Tối đa 2 số lẻ, dùng dấu phẩy hoặc dấu chấm.">
          {(id) => (
            <Input id={id} inputMode="decimal" value={weight} aria-invalid={errors.weightKg ? true : undefined} onChange={(e) => edit('weightKg', setWeight)(e.target.value)} />
          )}
        </Field>
      </div>
      <Field label="Tình trạng sức khỏe" error={errors.healthCondition} hint="Quan sát được lúc nhận: ăn uống, da lông, vết thương…">
        {(id) => (
          <textarea
            id={id}
            rows={2}
            className={textareaClass}
            value={health}
            aria-invalid={errors.healthCondition ? true : undefined}
            onChange={(e) => edit('healthCondition', setHealth)(e.target.value)}
          />
        )}
      </Field>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Đồ gửi kèm" error={errors.belongings} hint="Không bắt buộc.">
          {(id) => <Input id={id} value={belongings} onChange={(e) => edit('belongings', setBelongings)(e.target.value)} />}
        </Field>
        <Field label="Chế độ ăn riêng" error={errors.dietInstructions} hint="Không bắt buộc.">
          {(id) => <Input id={id} value={diet} onChange={(e) => edit('dietInstructions', setDiet)(e.target.value)} />}
        </Field>
      </div>
      <Field label="SĐT liên hệ khẩn cấp" error={errors.emergencyPhone} hint="Bắt đầu bằng 0, 10–11 chữ số.">
        {(id) => (
          <Input
            id={id}
            type="tel"
            inputMode="tel"
            maxLength={15}
            value={phone}
            aria-invalid={errors.emergencyPhone ? true : undefined}
            onChange={(e) => edit('emergencyPhone', setPhone)(e.target.value)}
          />
        )}
      </Field>
      <StayFooter>
        <Button type="button" variant="outline" onClick={onClose} disabled={checkIn.isPending}>
          Đóng
        </Button>
        <Button type="submit" disabled={checkIn.isPending}>
          {checkIn.isPending ? 'Đang xử lý…' : 'Xác nhận nhận thú'}
        </Button>
      </StayFooter>
    </form>
  );
}
