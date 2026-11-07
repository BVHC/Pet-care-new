/**
 * Đặt lịch hẹn online (UC39, A02) — appointment-v1 #1, #2, #7; chi nhánh / dịch vụ công khai (branch-v1 #20, catalog-v1 #19).
 * Luồng: thú cưng (còn sống) → chi nhánh → dịch vụ Khám/Tiêm hoặc Thẩm mỹ (BR-LH-01) → ngày (hôm nay … +30 ngày [CFG])
 * → khung giờ còn chỗ → ghi chú → đặt. Lịch được giữ ngay (BOOKED), không có bước chờ xác nhận.
 * Route nằm sau RequireLinkDecision (BR-TK-19). Khách đang bị hạn chế (BR-LH-09): ẩn form, hiện lý do và thời điểm hết hạn.
 * Tùy chọn "Khám tại nhà" (UC43, homevisit-v1 #1): thêm địa chỉ trong sổ + số liên hệ + phí của chi nhánh (fees-v1 #3), đặt qua POST /home-visit-appointments;
 * chỉ gọi hook tại nhà khi khách chọn tùy chọn này (lần gọi đầu nạp dữ liệu mẫu lịch tại nhà của mock).
 */
import { useState, type FormEvent, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Calendar, CalendarCheck, Cat, ChevronRight, ClipboardCheck, Clock, Dog, Home, MapPin, PawPrint, RefreshCw, Scissors, Stethoscope } from 'lucide-react';
import { RestrictionNotice } from '@/components/appointments/RestrictionNotice';
import { SlotPicker } from '@/components/appointments/SlotPicker';
import { customerMaxDate, customerMinDate, formatSlot } from '@/components/appointments/format';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { appointmentApi } from '@/shared/api/appointment.api';
import { useMyPets } from '@/domains/pets/hooks/useCustomerPet';
import { useHomeVisitFee } from '@/shared/hooks/useFees';
import { useCreateHomeVisit } from '@/shared/hooks/useHomeVisit';
import {
  useAppointmentMutation,
  useMyBookingRestriction,
  usePublicBranches,
  usePublicServices,
  useRefreshAppointments,
} from '@/shared/hooks/useAppointments';
import { APPOINTMENT_CFG } from '@/shared/types/appointment';
import { SERVICE_GROUP_LABELS } from '@/shared/types/catalog';
import { HOME_VISIT_PHONE_PATTERN } from '@/shared/types/homevisit';
import { SuccessModal, type Booked } from './booking/BookingSuccess';
import { HomeVisitStep } from './booking/HomeVisitStep';
import styles from './BookingPage.module.css';

const NOTE_MAX = 500;

const vnd = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 });

const PET_ICON = { DOG: Dog, CAT: Cat, OTHER: PawPrint } as const;

/* ================================================================
   Form.
   ================================================================ */

const rowsOf = (a: { code: string; serviceName: string; petName: string; slotDate: string; slotStart: string }, branchName: string): [string, string][] => [
  ['Mã lịch hẹn', a.code],
  ['Dịch vụ', a.serviceName],
  ['Thú cưng', a.petName],
  ['Chi nhánh', branchName],
  ['Thời gian', formatSlot(a)],
];

function Step({ n, title, children }: { n: number; title: string; children: ReactNode }) {
  return (
    <fieldset className={styles.step}>
      <legend className={styles.stepLegend}>
        <span className={styles.stepNumber}>{n}</span>
        {title}
      </legend>
      {children}
    </fieldset>
  );
}

function BookingForm({ onBooked }: { onBooked: (booked: Booked) => void }) {
  const pets = useMyPets();
  const branches = usePublicBranches();
  const [petId, setPetId] = useState<number | null>(null);
  const [pickedBranchId, setBranchId] = useState<number | null>(null);
  const [serviceId, setServiceId] = useState<number | null>(null);
  const [date, setDate] = useState<string | null>(null);
  const [slotStart, setSlotStart] = useState<string | null>(null);
  const [note, setNote] = useState('');
  const [error, setError] = useState('');
  const [home, setHome] = useState(false);
  const [addressId, setAddressId] = useState<number | null>(null);
  const [phone, setPhone] = useState('');
  const book = useAppointmentMutation(appointmentApi.book);
  const bookHome = useCreateHomeVisit();
  const refreshAppointments = useRefreshAppointments();

  const branchList = branches.data ?? [];
  // Chuỗi chỉ có một chi nhánh nhận đặt lịch thì chọn sẵn.
  const branchId = pickedBranchId ?? (branchList.length === 1 ? branchList[0].branchId : null);
  const services = usePublicServices(branchId);
  const fee = useHomeVisitFee(home ? branchId : null);
  // Nhóm Lưu trú đặt ở UC58, không qua lịch hẹn (BR-LH-01). Khám tại nhà chỉ cho dịch vụ khám: danh sách công khai không nêu loại (PublicService), nên giữ cả nhóm Khám/Tiêm và để BE (BR-LH-13) từ chối dịch vụ không phải khám.
  const bookable = (services.data ?? []).filter((s) => (home ? s.group === 'MEDICAL' : s.group !== 'BOARDING'));
  const alivePets = (pets.data ?? []).filter((p) => !p.deceasedOn);

  const pickBranch = (id: number) => {
    setBranchId(id);
    setServiceId(null);
    setSlotStart(null);
  };
  const pickService = (id: number) => {
    setServiceId(id);
    setSlotStart(null);
  };
  const pickMode = (next: boolean) => {
    setHome(next);
    setServiceId(null);
    setSlotStart(null);
    setError('');
  };

  const onFailed = (err: unknown) => {
    const apiError = toApiError(err);
    refreshAppointments();
    // BR-LH-03: khung vừa hết chỗ trong lúc chọn (lịch tại nhà: 409 CONCURRENCY_CONFLICT) — tải lại khung, bỏ chọn.
    setError(apiError.ruleId === 'BR-LH-03' || apiError.errorCode === 'CONCURRENCY_CONFLICT' ? 'Khung giờ bạn chọn vừa hết chỗ. Vui lòng chọn khung khác.' : plainMessage(apiError));
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError('');
    if (petId == null || branchId == null || serviceId == null || !date || !slotStart) {
      setError('Vui lòng chọn đủ thú cưng, chi nhánh, dịch vụ, ngày và giờ hẹn.');
      return;
    }
    if (note.trim().length > NOTE_MAX) {
      setError(`Ghi chú tối đa ${NOTE_MAX} ký tự.`);
      return;
    }
    const branchName = branchList.find((b) => b.branchId === branchId)?.name ?? '';
    if (home) {
      if (addressId == null) return setError('Vui lòng chọn địa chỉ khám tại nhà.');
      if (!HOME_VISIT_PHONE_PATTERN.test(phone.trim())) return setError('Số điện thoại liên hệ gồm 10–11 chữ số, bắt đầu bằng 0.');
      bookHome.mutate(
        { petId, branchId, serviceId, slotDate: date, slotStart, addressId, contactPhone: phone.trim(), note: note.trim() || undefined },
        {
          onSuccess: (a) => {
            setSlotStart(null);
            setNote('');
            onBooked({ rows: [...rowsOf(a, branchName), ['Địa chỉ', a.homeAddress], ['Phí khám tại nhà', vnd.format(a.homeFee)]], home: true });
          },
          onError: onFailed,
        },
      );
      return;
    }
    book.mutate(
      { petId, branchId, serviceId, slotDate: date, slotStart, note: note.trim() || undefined },
      {
        onSuccess: (appointment) => {
          setSlotStart(null);
          setNote('');
          onBooked({ rows: rowsOf(appointment, appointment.branchName), home: false });
        },
        onError: onFailed,
      },
    );
  };

  if (pets.isSuccess && alivePets.length === 0) {
    return (
      <div className={styles.emptyState}>
        <PawPrint size={40} className="opacity-40" aria-hidden />
        <p className="font-bold text-(--color-text-primary)">Bạn chưa có thú cưng nào để đặt lịch</p>
        <p>Thêm hồ sơ thú cưng trước, rồi quay lại đặt lịch.</p>
        <Link to="/pets" className={styles.emptyAction}>
          Thêm thú cưng
        </Link>
      </div>
    );
  }

  return (
    <form onSubmit={submit} noValidate className={styles.formCard} aria-label="Thông tin đặt lịch">
      <div className={styles.formHeader}>
        <ClipboardCheck size={20} className="text-accent" />
        <h2 className={styles.formTitle}>Thông tin đặt lịch</h2>
      </div>

      <div className={styles.formBody}>
        <div className={styles.pills} role="group" aria-label="Hình thức khám">
          {[
            { id: false, label: 'Khám tại chi nhánh', icon: Stethoscope },
            { id: true, label: 'Khám tại nhà', icon: Home },
          ].map(({ id, label, icon: Icon }) => (
            <button key={label} type="button" aria-pressed={home === id} onClick={() => pickMode(id)} className={`${styles.pill} ${home === id ? styles.pillActive : ''}`}>
              <Icon size={16} aria-hidden />
              {label}
            </button>
          ))}
        </div>

        <Step n={1} title="Thú cưng">
          {pets.isPending && <p className={styles.muted}>Đang tải thú cưng…</p>}
          <div className={styles.pills} role="group" aria-label="Chọn thú cưng">
            {alivePets.map((pet) => {
              const Icon = PET_ICON[pet.species];
              return (
                <button
                  key={pet.petId}
                  type="button"
                  aria-pressed={petId === pet.petId}
                  onClick={() => setPetId(pet.petId)}
                  className={`${styles.pill} ${petId === pet.petId ? styles.pillActive : ''}`}
                >
                  <Icon size={16} aria-hidden />
                  {pet.name}
                </button>
              );
            })}
          </div>
        </Step>

        <Step n={2} title="Chi nhánh">
          {branches.isPending && <p className={styles.muted}>Đang tải chi nhánh…</p>}
          {branches.isSuccess && branchList.length === 0 && <p className={styles.muted}>Hiện chưa có chi nhánh nhận đặt lịch.</p>}
          <div className={styles.optionGrid} role="group" aria-label="Chọn chi nhánh">
            {branchList.map((b) => (
              <button
                key={b.branchId}
                type="button"
                aria-pressed={branchId === b.branchId}
                onClick={() => pickBranch(b.branchId)}
                className={`${styles.optionCard} ${branchId === b.branchId ? styles.optionCardActive : ''}`}
              >
                <span className={styles.optionTitle}>{b.name}</span>
                <span className={styles.optionMeta}>
                  <MapPin size={13} aria-hidden /> {b.address}
                </span>
                <span className={styles.optionMeta}>Điện thoại {b.phone}</span>
              </button>
            ))}
          </div>
        </Step>

        <Step n={3} title="Dịch vụ">
          {branchId == null && <p className={styles.muted}>Chọn chi nhánh để xem dịch vụ đang nhận đặt lịch.</p>}
          {services.isSuccess && bookable.length === 0 && <p className={styles.muted}>Chi nhánh chưa mở dịch vụ nào để đặt lịch.</p>}
          <div className={styles.servicesGrid} role="group" aria-label="Chọn dịch vụ">
            {bookable.map((s) => {
              const Icon = s.group === 'GROOMING' ? Scissors : Stethoscope;
              const active = serviceId === s.serviceId;
              return (
                <button
                  key={s.serviceId}
                  type="button"
                  aria-pressed={active}
                  onClick={() => pickService(s.serviceId)}
                  className={`${styles.serviceCard} ${active ? styles.serviceCardActive : ''}`}
                >
                  <span className={styles.serviceIcon}>
                    <Icon size={24} strokeWidth={1.75} aria-hidden />
                  </span>
                  <span className={styles.serviceName}>{s.name}</span>
                  <span className={styles.serviceDesc}>{s.description || SERVICE_GROUP_LABELS[s.group]}</span>
                  <span className={styles.servicePrice}>
                    {s.priceIsFrom ? 'Từ ' : ''}
                    {vnd.format(s.price)}
                  </span>
                </button>
              );
            })}
          </div>
          {bookable.length > 0 && (
            <p className={styles.hint}>Giá tham khảo; chi phí thực tế phụ thuộc chỉ định của bác sĩ tại buổi hẹn.</p>
          )}
        </Step>

        <Step n={4} title="Ngày và giờ hẹn">
          <SlotPicker
            branchId={branchId}
            serviceId={serviceId}
            date={date}
            onDateChange={setDate}
            value={slotStart}
            onChange={setSlotStart}
            minDate={customerMinDate()}
            maxDate={customerMaxDate()}
            hint={`Đặt trước ít nhất ${APPOINTMENT_CFG.minLeadHours} giờ, xa nhất ${APPOINTMENT_CFG.maxAdvanceDays} ngày tới.`}
          />
        </Step>

        {home && (
          <Step n={5} title="Địa chỉ và phí khám tại nhà">
            <HomeVisitStep
              fee={fee.data}
              addressId={addressId}
              phone={phone}
              onAddress={(a) => {
                setAddressId(a.addressId);
                setPhone((p) => p || a.receiverPhone);
              }}
              onPhone={setPhone}
            />
          </Step>
        )}

        <Step n={home ? 6 : 5} title="Ghi chú cho phòng khám">
          <label htmlFor="booking-note" className="sr-only">
            Ghi chú cho phòng khám
          </label>
          <textarea
            id="booking-note"
            value={note}
            onChange={(e) => setNote(e.target.value)}
            placeholder="Triệu chứng, yêu cầu đặc biệt, dị ứng thức ăn…"
            rows={3}
            className={`${styles.fieldInput} ${styles.fieldTextarea}`}
          />
          <p className={styles.hint}>
            {note.trim().length}/{NOTE_MAX} ký tự
          </p>
        </Step>

        {error && (
          <p role="alert" className={styles.formError}>
            {error}
          </p>
        )}
      </div>

      <div className={styles.submitWrap}>
        <button type="submit" className={styles.submitBtn} disabled={book.isPending || bookHome.isPending || (home && fee.data?.enabled === false)}>
          <Calendar size={18} aria-hidden />
          {book.isPending || bookHome.isPending ? 'Đang đặt lịch…' : 'Xác nhận đặt lịch'}
          <ChevronRight size={18} aria-hidden />
        </button>
      </div>
    </form>
  );
}

/* ================================================================
   Main page.
   ================================================================ */

export function BookingPage() {
  const restriction = useMyBookingRestriction();
  const branches = usePublicBranches();
  const [booked, setBooked] = useState<Booked | null>(null);
  const [formKey, setFormKey] = useState(0);
  const blocked = restriction.data?.restricted ? restriction.data.restriction : null;

  const facts = [
    { icon: Clock, text: <>Đặt trước ít nhất <span className={styles.trustStrong}>{APPOINTMENT_CFG.minLeadHours} giờ</span></> },
    { icon: CalendarCheck, text: <>Chọn lịch trong <span className={styles.trustStrong}>{APPOINTMENT_CFG.maxAdvanceDays} ngày</span> tới</> },
    {
      icon: RefreshCw,
      text: (
        <>
          Đổi giờ online đến <span className={styles.trustStrong}>{APPOINTMENT_CFG.rescheduleMinLeadHours} giờ</span> trước hẹn
        </>
      ),
    },
    {
      icon: MapPin,
      text: branches.data ? (
        <>
          <span className={styles.trustStrong}>{branches.data.length}</span> chi nhánh nhận đặt lịch
        </>
      ) : (
        'Chi nhánh nhận đặt lịch'
      ),
    },
  ];

  return (
    <div className="bg-(--color-surface-page) pb-24">
      {/* ====== Hero ====== */}
      <section className={styles.hero}>
        <img src="/imgs/cta-banner.jpg" alt="" className={styles.heroImg} aria-hidden loading="eager" />
        <div className={styles.heroOverlay} aria-hidden />
        <div className={styles.heroNoise} aria-hidden />

        <div className={`${styles.heroContent} mx-auto max-w-[1280px] px-5 py-10 sm:px-8 sm:py-14`}>
          <nav aria-label="Đường dẫn" className="mb-5 text-[12.5px] text-white/50">
            <Link to="/" className="underline-offset-4 transition-colors hover:text-white hover:underline">
              Trang chủ
            </Link>
            <span className="mx-1.5">/</span>
            <span className="font-semibold text-white/85">Đặt lịch khám</span>
          </nav>

          <div className="max-w-[680px]">
            <h1 className="font-friendly font-extrabold text-[clamp(28px,4vw,52px)] leading-[1.05] text-white">Đặt lịch khám cho boss</h1>
            <p className="mt-4 max-w-[50ch] text-[15px] leading-relaxed text-white/70">
              Chọn thú cưng, chi nhánh, dịch vụ và khung giờ còn trống. Lịch được giữ ngay khi bạn xác nhận.
            </p>
          </div>
        </div>
      </section>

      {/* ====== Quy định đặt lịch ====== */}
      <div className="mx-auto max-w-[1280px] px-5 sm:px-8">
        <div className={styles.trustStrip}>
          {facts.map(({ icon: Icon, text }, i) => (
            <div key={i} className={styles.trustItem}>
              <span className={styles.trustIcon}>
                <Icon size={16} aria-hidden />
              </span>
              <span>{text}</span>
            </div>
          ))}
        </div>
      </div>

      <div className="mx-auto max-w-[1280px] px-5 sm:px-8">
        <section className={styles.formSection}>
          {restriction.isPending && <p className={styles.muted}>Đang kiểm tra quyền đặt lịch…</p>}
          {restriction.isError && (
            <p role="alert" className={styles.formError}>
              {plainMessage(toApiError(restriction.error))}
            </p>
          )}
          {blocked && (
            <div className="space-y-4">
              <RestrictionNotice restriction={blocked} />
              <Link to="/appointments" className={styles.emptyAction}>
                Xem lịch hẹn của tôi
              </Link>
            </div>
          )}
          {restriction.isSuccess && !blocked && <BookingForm key={formKey} onBooked={setBooked} />}
        </section>
      </div>

      {booked && (
        <SuccessModal
          booked={booked}
          onClose={() => {
            setBooked(null);
            setFormKey((k) => k + 1);
          }}
        />
      )}
    </div>
  );
}
