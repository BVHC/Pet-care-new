/**
 * Form đặt chỗ lưu trú (UC58) — boarding-v1 #7, #8; chi nhánh và loại chuồng công khai (branch-v1 #20, catalog-v1 #19).
 * Luồng: thú cưng còn sống → chi nhánh có nhận lưu trú → loại chuồng PHÙ HỢP thú (BR-LT-02: loại không phù hợp không hiện)
 * → ngày nhận (từ ngày mai tới +60 ngày) và ngày trả (1 đến 30 đêm) theo giờ Việt Nam → chỗ còn lại từng đêm → tóm tắt + chính sách → đặt.
 * Hết chỗ đêm nào thì khóa nút và nêu đêm đó; lỗi BE hiện nguyên câu rồi làm mới lưới đêm. Cảnh báo tiêm phòng nằm ở màn xác nhận (không chặn).
 */
import { useState, type FormEvent, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { BedDouble, CalendarX, Cat, Clock, Dog, MapPin, PawPrint, RefreshCw, Syringe } from 'lucide-react';
import { formatShortDate, todayVn } from '@/components/appointments/format';
import { DatePicker } from '@/components/customer/DatePicker';
import { SPECIES_LABEL, type PetResponse, type Species } from '@/domains/pets/types';
import { useMyPets } from '@/domains/pets/hooks/useCustomerPet';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { boardingApi } from '@/shared/api/boarding.api';
import { QUERY_KEYS } from '@/shared/constants/queryKeys';
import { useRefreshAppointments, usePublicBranches } from '@/shared/hooks/useAppointments';
import { useBoardingAvailability, useBoardingMutation, useRefreshBoarding } from '@/shared/hooks/useBoarding';
import { BOARDING_CFG, type BookingResult } from '@/shared/types/boarding';
import { nightsBetween, shiftDate, stayAmount } from '@/shared/utils/boarding';
import { formatWeight, nightsLabel, vnd } from './format';
import { usePublicKennelTypes } from './hooks';
import { NightStrip, NightStripSkeleton } from './NightStrip';
import { bookingDateError, branchIdsWithBoarding, checkInWindow, checkOutWindow, kennelsFor, soldOutMessage, soldOutNights } from './rules';
import base from '../BookingPage.module.css';
import styles from './Boarding.module.css';

const PET_ICON = { DOG: Dog, CAT: Cat, OTHER: PawPrint } as const;
const speciesName = (s: Species) => SPECIES_LABEL[s].toLowerCase();
const weightText = (pet: Pick<PetResponse, 'currentWeightKg'>) => (pet.currentWeightKg != null ? formatWeight(pet.currentWeightKg) : 'chưa cân');

function Step({ n, title, children }: { n: number; title: string; children: ReactNode }) {
  return (
    <fieldset className={base.step}>
      <legend className={base.stepLegend}>
        <span className={base.stepNumber}>{n}</span>
        {title}
      </legend>
      {children}
    </fieldset>
  );
}

function SummaryRow({ label, value }: { label: string; value?: string | null }) {
  return (
    <div className={styles.row}>
      <dt className={styles.rowLabel}>{label}</dt>
      <dd className={`${styles.rowValue} ${value ? '' : styles.rowEmpty}`}>{value || 'Chưa chọn'}</dd>
    </div>
  );
}

export interface BookingFormProps {
  onBooked: (result: BookingResult, branchName: string) => void;
  /** Hạn chế đặt online (BR-LH-09) có thể phát sinh ngay lúc gửi: trang cha giữ câu của BE khi ẩn form. */
  onRestricted: (message: string) => void;
}

export function BookingForm({ onBooked, onRestricted }: BookingFormProps) {
  const pets = useMyPets();
  const branches = usePublicBranches();
  const kennelTypes = usePublicKennelTypes();
  const [petId, setPetId] = useState<number | null>(null);
  const [pickedBranchId, setPickedBranchId] = useState<number | null>(null);
  const [pickedKennelId, setPickedKennelId] = useState<number | null>(null);
  const [checkIn, setCheckIn] = useState<string | null>(null);
  const [checkOut, setCheckOut] = useState<string | null>(null);
  const [error, setError] = useState('');
  const book = useBoardingMutation(boardingApi.book);
  const refreshBoarding = useRefreshBoarding();
  const refreshAppointments = useRefreshAppointments();
  const queryClient = useQueryClient();

  // Ngày hôm nay tính lại mỗi lần vẽ, theo giờ Việt Nam: qua nửa đêm thì khoảng đặt được dịch theo.
  const today = todayVn();
  const inRange = checkInWindow(today);
  const outWindow = checkIn ? checkOutWindow(checkIn) : { min: shiftDate(inRange.min, BOARDING_CFG.minNights), max: undefined };

  const myPets = pets.data ?? [];
  const pet = myPets.find((p) => p.petId === petId && !p.deceasedOn) ?? null;
  const options = kennelTypes.data ?? [];
  const offered = branchIdsWithBoarding(options);
  const branchList = (branches.data ?? []).filter((b) => offered.includes(b.branchId));
  // Chỉ một chi nhánh nhận lưu trú thì chọn sẵn.
  const branchId = branchList.some((b) => b.branchId === pickedBranchId) ? pickedBranchId : branchList.length === 1 ? branchList[0].branchId : null;
  const branch = branchList.find((b) => b.branchId === branchId) ?? null;
  const choices = pet && branchId != null ? kennelsFor(options, branchId, pet) : [];
  const kennel = choices.find((k) => k.serviceId === pickedKennelId) ?? (choices.length === 1 ? choices[0] : null);

  const dateError = bookingDateError(checkIn, checkOut, today);
  const nights = checkIn && checkOut && !dateError ? nightsBetween(checkIn, checkOut) : 0;
  const ready = !!(pet && branch && kennel && checkIn && checkOut && !dateError);
  const availability = useBoardingAvailability(
    branchId,
    kennel && ready ? { kennelTypeId: kennel.serviceId, checkInDate: checkIn!, checkOutDate: checkOut! } : {},
  );
  const soldOut = availability.data ? soldOutNights(availability.data.nights) : [];
  const nightlyPrice = availability.data?.nightlyPrice ?? kennel?.price;

  // Lý do nút đặt đang khóa. Trùng với câu lỗi BE vừa hiện thì không lặp lại.
  let lockHint = '';
  if (!ready) lockHint = 'Chọn đủ thú cưng, chi nhánh, loại chuồng, ngày nhận và ngày trả để đặt chỗ.';
  else if (availability.isError) lockHint = 'Chưa kiểm tra được chỗ trống. Hãy thử lại ở mục chỗ còn lại theo đêm.';
  else if (!availability.data) lockHint = 'Đang kiểm tra chỗ trống…';
  else if (!availability.data.available) lockHint = soldOutMessage(soldOut);
  const canSubmit = ready && availability.data?.available === true && !book.isPending;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!canSubmit || !pet || !branch || !kennel || !checkIn || !checkOut) return;
    setError('');
    book.mutate(
      { petId: pet.petId, branchId: branch.branchId, kennelTypeId: kennel.serviceId, checkInDate: checkIn, checkOutDate: checkOut },
      {
        onSuccess: (result) => onBooked(result, branch.name),
        onError: (err) => {
          const apiError = toApiError(err);
          const message = plainMessage(apiError);
          setError(message);
          // Hết chỗ / 409: lưới đêm đọc lại để khách thấy đêm nào vừa kín. Thú đã mất / không còn: danh sách thú đọc lại.
          refreshBoarding();
          if (apiError.ruleId === 'BR-KH-05' || apiError.statusCode === 404) queryClient.invalidateQueries({ queryKey: QUERY_KEYS.pets.all });
          if (apiError.ruleId === 'BR-LH-09') {
            refreshAppointments();
            onRestricted(message);
          }
        },
      },
    );
  };

  const firstError = [pets, branches, kennelTypes].find((q) => q.isError);
  const loading = pets.isPending || branches.isPending || kennelTypes.isPending;

  if (pets.isSuccess && myPets.length === 0) {
    return (
      <div className={base.emptyState}>
        <PawPrint size={40} className="opacity-40" aria-hidden />
        <p className="font-bold text-(--color-text-primary)">Bạn chưa có thú cưng nào để đặt chỗ lưu trú</p>
        <p>Thêm hồ sơ thú cưng trước, rồi quay lại đặt chỗ.</p>
        <Link to="/pets" className={base.emptyAction}>
          Thêm thú cưng
        </Link>
      </div>
    );
  }

  return (
    <form onSubmit={submit} noValidate aria-label="Thông tin đặt chỗ lưu trú" className={styles.layout}>
      <div className={`${base.formCard} min-w-0`}>
        <div className={base.formHeader}>
          <BedDouble size={20} className="text-accent" aria-hidden />
          <h2 className={base.formTitle}>Thông tin lưu trú</h2>
        </div>

        <div className={base.formBody}>
          {loading && (
            <p role="status" className={base.muted}>
              Đang tải thú cưng và chi nhánh…
            </p>
          )}
          {firstError && (
            <div role="alert" className={`${base.formError} flex flex-wrap items-center justify-between gap-3`}>
              <span className="min-w-0">{plainMessage(toApiError(firstError.error))}</span>
              <button
                type="button"
                onClick={() => [pets, branches, kennelTypes].filter((q) => q.isError).forEach((q) => q.refetch())}
                className="inline-flex min-h-10 items-center gap-1.5 rounded-xl border border-red-200 bg-white px-4 text-[12.5px] font-bold text-red-700"
              >
                <RefreshCw size={13} aria-hidden /> Thử lại
              </button>
            </div>
          )}

          <Step n={1} title="Thú cưng">
            <div className={base.optionGrid} role="group" aria-label="Chọn thú cưng">
              {myPets.map((p) => {
                const Icon = PET_ICON[p.species];
                const gone = !!p.deceasedOn;
                return (
                  <button
                    key={p.petId}
                    type="button"
                    disabled={gone}
                    aria-pressed={pet?.petId === p.petId}
                    onClick={() => setPetId(p.petId)}
                    className={`${base.optionCard} ${pet?.petId === p.petId ? base.optionCardActive : ''} ${gone ? styles.optionDisabled : ''}`}
                  >
                    <span className={`${base.optionTitle} flex items-center gap-2 break-words`}>
                      <Icon size={16} className="shrink-0" aria-hidden />
                      <span className="min-w-0 break-words">{p.name}</span>
                    </span>
                    <span className={base.optionMeta}>
                      {SPECIES_LABEL[p.species]}, {gone ? 'đã mất' : weightText(p)}
                    </span>
                  </button>
                );
              })}
            </div>
            {myPets.some((p) => p.deceasedOn) && <p className={base.hint}>Thú đã mất không đặt chỗ được.</p>}
          </Step>

          <Step n={2} title="Chi nhánh">
            {kennelTypes.isSuccess && branchList.length === 0 && <p className={base.muted}>Hiện chưa có chi nhánh nào nhận lưu trú.</p>}
            <div className={base.optionGrid} role="group" aria-label="Chọn chi nhánh">
              {branchList.map((b) => (
                <button
                  key={b.branchId}
                  type="button"
                  aria-pressed={branchId === b.branchId}
                  onClick={() => setPickedBranchId(b.branchId)}
                  className={`${base.optionCard} ${branchId === b.branchId ? base.optionCardActive : ''}`}
                >
                  <span className={base.optionTitle}>{b.name}</span>
                  <span className={base.optionMeta}>
                    <MapPin size={13} className="shrink-0" aria-hidden /> <span className="min-w-0 break-words">{b.address}</span>
                  </span>
                  <span className={base.optionMeta}>Điện thoại {b.phone}</span>
                </button>
              ))}
            </div>
          </Step>

          <Step n={3} title="Loại chuồng">
            {(!pet || branchId == null) && <p className={base.muted}>Chọn thú cưng và chi nhánh để xem loại chuồng phù hợp.</p>}
            {pet && branchId != null && choices.length === 0 && kennelTypes.isSuccess && (
              <p role="status" className={base.muted}>
                {(() => {
                  const elsewhere = branchList.filter((b) => b.branchId !== branchId && kennelsFor(options, b.branchId, pet).length > 0);
                  return elsewhere.length > 0
                    ? `Chi nhánh này chưa có loại chuồng phù hợp với ${pet.name}. Chi nhánh có chuồng phù hợp: ${elsewhere.map((b) => b.name).join(', ')}.`
                    : `Hiện chưa có loại chuồng nào phù hợp với ${pet.name} (${speciesName(pet.species)}, ${weightText(pet)}). Vui lòng liên hệ chi nhánh để được tư vấn.`;
                })()}
              </p>
            )}
            <div className={base.optionGrid} role="group" aria-label="Chọn loại chuồng">
              {choices.map((k) => (
                <button
                  key={k.serviceId}
                  type="button"
                  aria-pressed={kennel?.serviceId === k.serviceId}
                  onClick={() => setPickedKennelId(k.serviceId)}
                  className={`${base.optionCard} ${kennel?.serviceId === k.serviceId ? base.optionCardActive : ''}`}
                >
                  <span className={base.optionTitle}>{k.name}</span>
                  <span className={base.optionMeta}>
                    {SPECIES_LABEL[k.kennelType.species]}, tối đa {formatWeight(k.kennelType.maxWeightKg)}
                  </span>
                  {k.description && <span className={`${base.optionMeta} break-words`}>{k.description}</span>}
                  <span className={styles.price}>{vnd.format(k.price)}/đêm</span>
                </button>
              ))}
            </div>
          </Step>

          <Step n={4} title="Ngày nhận và ngày trả">
            <div className={styles.twoCols}>
              <div className="min-w-0">
                <span className={styles.fieldLabel}>Ngày nhận</span>
                <DatePicker
                  id="boarding-check-in"
                  value={checkIn}
                  onChange={(iso) => setCheckIn(iso || null)}
                  minDate={inRange.min}
                  maxDate={inRange.max}
                  ariaLabel="Ngày nhận"
                  placeholder="Chọn ngày nhận"
                />
              </div>
              <div className="min-w-0">
                <span className={styles.fieldLabel}>Ngày trả</span>
                <DatePicker
                  id="boarding-check-out"
                  value={checkOut}
                  onChange={(iso) => setCheckOut(iso || null)}
                  minDate={outWindow.min}
                  maxDate={outWindow.max}
                  ariaLabel="Ngày trả"
                  placeholder="Chọn ngày trả"
                />
              </div>
            </div>
            <p className={base.hint}>
              Nhận từ {formatShortDate(inRange.min)} đến {formatShortDate(inRange.max)} (đặt trước ít nhất {BOARDING_CFG.minLeadDays} ngày, xa nhất {BOARDING_CFG.maxAdvanceDays} ngày).
              Lưu trú {BOARDING_CFG.minNights} đến {BOARDING_CFG.maxNights} đêm.
              {nights > 0 && <strong className="text-(--color-text-primary)"> Bạn chọn {nightsLabel(nights)}.</strong>}
            </p>
            {dateError && (
              <p role="alert" className={base.formError}>
                {dateError}
              </p>
            )}
          </Step>

          <Step n={5} title="Chỗ còn lại theo đêm">
            {!ready && <p className={base.muted}>Chọn đủ thú cưng, chi nhánh, loại chuồng và ngày để xem từng đêm còn chỗ không.</p>}
            {ready && availability.isPending && <NightStripSkeleton count={nights} />}
            {availability.isError && (
              <div role="alert" className={`${base.formError} flex flex-wrap items-center justify-between gap-3`}>
                <span className="min-w-0">{plainMessage(toApiError(availability.error))}</span>
                <button
                  type="button"
                  onClick={() => availability.refetch()}
                  className="inline-flex min-h-10 items-center gap-1.5 rounded-xl border border-red-200 bg-white px-4 text-[12.5px] font-bold text-red-700"
                >
                  <RefreshCw size={13} aria-hidden /> Thử lại
                </button>
              </div>
            )}
            {ready && availability.data && <NightStrip nights={availability.data.nights} />}
          </Step>
        </div>
      </div>

      <aside aria-label="Tóm tắt đặt chỗ" className={`${base.formCard} ${styles.summary} min-w-0`}>
        <div className={base.formHeader}>
          <h2 className={base.formTitle}>Tóm tắt đặt chỗ</h2>
        </div>
        <div className={styles.summaryBody}>
          <dl className={styles.rows}>
            <SummaryRow label="Thú cưng" value={pet?.name} />
            <SummaryRow label="Chi nhánh" value={branch?.name} />
            <SummaryRow label="Loại chuồng" value={kennel?.name} />
            <SummaryRow label="Nhận" value={checkIn && !dateError ? formatShortDate(checkIn) : null} />
            <SummaryRow label="Trả" value={checkOut && !dateError ? `${formatShortDate(checkOut)}, trước ${BOARDING_CFG.checkoutTime}` : null} />
          </dl>

          <hr className={styles.tear} />
          <div className={styles.total}>
            {nights > 0 && nightlyPrice != null ? (
              <>
                <span className={styles.totalCalc}>
                  {nightsLabel(nights)} × {vnd.format(nightlyPrice)}
                </span>
                <span className={styles.totalValue}>{vnd.format(stayAmount(nights, nightlyPrice))}</span>
                <span className={styles.totalCalc}>Tạm tính. Tiền chốt khi trả thú, theo số đêm thực tế.</span>
              </>
            ) : (
              <span className={styles.totalCalc}>Chọn loại chuồng và ngày để xem tiền tạm tính.</span>
            )}
          </div>
          <hr className={styles.tear} />

          <ul className={styles.policies}>
            <li className={styles.policy}>
              <Syringe size={15} className={styles.policyIcon} aria-hidden />
              <span>Thú cần tiêm đủ các mũi bắt buộc khi nhận. Mũi tiêm ở nơi khác không được tính; thiếu mũi thì phòng khám không nhận thú.</span>
            </li>
            <li className={styles.policy}>
              <CalendarX size={15} className={styles.policyIcon} aria-hidden />
              <span>
                Hủy khi còn dưới {BOARDING_CFG.lateCancelHours} giờ trước 00:00 ngày nhận là hủy muộn, tính vào hạn chế đặt online.
              </span>
            </li>
            <li className={styles.policy}>
              <Clock size={15} className={styles.policyIcon} aria-hidden />
              <span>Giờ trả thú là {BOARDING_CFG.checkoutTime}. Trả sau giờ này tính thêm 1 đêm.</span>
            </li>
          </ul>

          {error && (
            <p role="alert" className={base.formError}>
              {error}
            </p>
          )}
          {lockHint && lockHint !== error && <p className={styles.lockHint}>{lockHint}</p>}
        </div>

        <div className={base.submitWrap}>
          <button type="submit" className={base.submitBtn} disabled={!canSubmit}>
            <BedDouble size={18} aria-hidden />
            {book.isPending ? 'Đang đặt chỗ…' : 'Xác nhận đặt chỗ'}
          </button>
        </div>
      </aside>
    </form>
  );
}
