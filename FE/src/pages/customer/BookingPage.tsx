/**
 * Đặt lịch hẹn online (UC39, A02) — appointment-v1 #1, #2, #7; chi nhánh / dịch vụ công khai (branch-v1 #20, catalog-v1 #19).
 * Luồng: thú cưng (còn sống) → chi nhánh → dịch vụ Khám/Tiêm hoặc Thẩm mỹ (BR-LH-01) → ngày (hôm nay … +30 ngày [CFG])
 * → khung giờ còn chỗ → ghi chú → đặt. Lịch được giữ ngay (BOOKED), không có bước chờ xác nhận.
 * Route nằm sau RequireLinkDecision (BR-TK-19). Khách đang bị hạn chế (BR-LH-09): ẩn form, hiện lý do và thời điểm hết hạn.
 */
import { useState, type FormEvent, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Calendar, CalendarCheck, Cat, Check, ChevronRight, ClipboardCheck, Clock, Dog, MapPin, PawPrint, RefreshCw, Scissors, Stethoscope, X } from 'lucide-react';
import { RestrictionNotice } from '@/components/appointments/RestrictionNotice';
import { SlotPicker } from '@/components/appointments/SlotPicker';
import { customerMaxDate, customerMinDate, formatSlot } from '@/components/appointments/format';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { appointmentApi } from '@/shared/api/appointment.api';
import { useMyPets } from '@/domains/pets/hooks/useCustomerPet';
import {
  useAppointmentMutation,
  useMyBookingRestriction,
  usePublicBranches,
  usePublicServices,
  useRefreshAppointments,
} from '@/shared/hooks/useAppointments';
import { APPOINTMENT_CFG, type Appointment } from '@/shared/types/appointment';
import { SERVICE_GROUP_LABELS } from '@/shared/types/catalog';
import styles from './BookingPage.module.css';

const NOTE_MAX = 500;

const vnd = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 });

const PET_ICON = { DOG: Dog, CAT: Cat, OTHER: PawPrint } as const;

/* ================================================================
   Success modal.
   ================================================================ */

function SuccessModal({ appointment, onClose }: { appointment: Appointment; onClose: () => void }) {
  const rows: [string, string][] = [
    ['Mã lịch hẹn', appointment.code],
    ['Dịch vụ', appointment.serviceName],
    ['Thú cưng', appointment.petName],
    ['Chi nhánh', appointment.branchName],
    ['Thời gian', formatSlot(appointment)],
  ];
  return (
    <div className={styles.successOverlay} role="dialog" aria-modal="true" aria-label="Đặt lịch thành công">
      <div className={styles.successCard}>
        <div className={styles.successIcon}>
          <Check size={36} strokeWidth={2.5} />
        </div>
        <h2 className={styles.successTitle}>Đặt lịch thành công!</h2>
        <p className={styles.successDesc}>
          Lịch hẹn đã được giữ. Vui lòng đến đúng giờ; bạn có thể đổi giờ hoặc hủy trong mục Lịch hẹn của tôi.
        </p>
        <dl className={styles.successDetails}>
          {rows.map(([label, value]) => (
            <div key={label} className={styles.successDetail}>
              <dt className={styles.successDetailLabel}>{label}</dt>
              <dd className={styles.successDetailValue}>{value}</dd>
            </div>
          ))}
        </dl>
        <div className={styles.successActions}>
          <Link to="/appointments" className={styles.successSecondary}>
            Xem lịch hẹn của tôi
          </Link>
          <button type="button" className={styles.successPrimary} onClick={onClose}>
            Đặt thêm lịch
          </button>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Đóng"
          className="absolute top-4 right-4 rounded-full p-1.5 text-(--color-text-secondary) hover:bg-(--color-surface-2) transition-colors"
        >
          <X size={18} />
        </button>
      </div>
    </div>
  );
}

/* ================================================================
   Form.
   ================================================================ */

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

function BookingForm({ onBooked }: { onBooked: (appointment: Appointment) => void }) {
  const pets = useMyPets();
  const branches = usePublicBranches();
  const [petId, setPetId] = useState<number | null>(null);
  const [pickedBranchId, setBranchId] = useState<number | null>(null);
  const [serviceId, setServiceId] = useState<number | null>(null);
  const [date, setDate] = useState<string | null>(null);
  const [slotStart, setSlotStart] = useState<string | null>(null);
  const [note, setNote] = useState('');
  const [error, setError] = useState('');
  const book = useAppointmentMutation(appointmentApi.book);
  const refreshAppointments = useRefreshAppointments();

  const branchList = branches.data ?? [];
  // Chuỗi chỉ có một chi nhánh nhận đặt lịch thì chọn sẵn.
  const branchId = pickedBranchId ?? (branchList.length === 1 ? branchList[0].branchId : null);
  const services = usePublicServices(branchId);
  // Nhóm Lưu trú đặt ở UC58, không qua lịch hẹn (BR-LH-01).
  const bookable = (services.data ?? []).filter((s) => s.group !== 'BOARDING');
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
    book.mutate(
      { petId, branchId, serviceId, slotDate: date, slotStart, note: note.trim() || undefined },
      {
        onSuccess: (appointment) => {
          setSlotStart(null);
          setNote('');
          onBooked(appointment);
        },
        onError: (err) => {
          const apiError = toApiError(err);
          refreshAppointments();
          // BR-LH-03: khung vừa hết chỗ trong lúc chọn — tải lại khung, bỏ chọn.
          setError(apiError.ruleId === 'BR-LH-03' ? 'Khung giờ bạn chọn vừa hết chỗ. Vui lòng chọn khung khác.' : plainMessage(apiError));
        },
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

        <Step n={5} title="Ghi chú cho phòng khám">
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
        <button type="submit" className={styles.submitBtn} disabled={book.isPending}>
          <Calendar size={18} aria-hidden />
          {book.isPending ? 'Đang đặt lịch…' : 'Xác nhận đặt lịch'}
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
  const [booked, setBooked] = useState<Appointment | null>(null);
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
          appointment={booked}
          onClose={() => {
            setBooked(null);
            setFormKey((k) => k + 1);
          }}
        />
      )}
    </div>
  );
}
