/**
 * Khách sạn thú cưng (trang công khai) — UC58 phía công khai. Loại chuồng = dịch vụ nhóm BOARDING (catalog-v1 #19) kèm loài, cân nặng tối đa,
 * giá đêm và các chi nhánh đang bật; chi nhánh từ branch-v1 #20. Không có số liệu cứng và không gọi `boarding-availability` (cần đăng nhập):
 * xem chỗ trống từng đêm ở bước đặt chỗ. Khách chưa đăng nhập bấm "Đặt chỗ lưu trú" sẽ được đưa tới đăng nhập rồi quay lại.
 */
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Bed, Bell, CalendarRange, CalendarX, ClipboardList, Clock, MapPin, Phone, RefreshCw, Stethoscope, Syringe } from 'lucide-react';
import { SPECIES_LABEL } from '@/domains/pets/types';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { usePublicBranches } from '@/shared/hooks/useAppointments';
import { BOARDING_CFG } from '@/shared/types/boarding';
import { formatWeight, vnd } from '@/pages/customer/boarding/format';
import { usePublicKennelTypes } from '@/pages/customer/boarding/hooks';
import { branchIdsWithBoarding, type KennelOption } from '@/pages/customer/boarding/rules';
import { StayCalculator } from './StayCalculator';
import styles from './HotelPage.module.css';

const IMAGE_BY_SPECIES = { DOG: '/imgs/hero-dog.png', CAT: '/imgs/cat-boarding.png', OTHER: '/imgs/hero-dog-clean.png' } as const;

function KennelCard({ kennel, branchNames, active, onToggle }: { kennel: KennelOption; branchNames: string[]; active: boolean; onToggle: () => void }) {
  const { species, maxWeightKg } = kennel.kennelType;
  return (
    <article aria-label={kennel.name} className={`${styles.roomCard} ${active ? styles.roomCardActive : ''}`}>
      <div className={styles.roomCardMedia}>
        <img src={IMAGE_BY_SPECIES[species]} alt={kennel.name} loading="lazy" />
      </div>
      <div className={styles.roomCardBody}>
        <h3 className={styles.roomName}>{kennel.name}</h3>
        <p className={styles.roomDesc}>{kennel.description || `Chuồng riêng cho ${SPECIES_LABEL[species].toLowerCase()} từ nhỏ đến ${formatWeight(maxWeightKg)}.`}</p>
        <div className={styles.roomFeatures}>
          <span className={styles.roomFeature}>{`${SPECIES_LABEL[species]}, tối đa ${formatWeight(maxWeightKg)}`}</span>
          {branchNames.length > 0 && <span className={styles.roomFeature}>{`Có tại ${branchNames.join(', ')}`}</span>}
        </div>
        <div className={styles.roomFooter}>
          <p className={styles.roomPrice}>
            {kennel.priceIsFrom ? 'Từ ' : ''}
            {vnd.format(kennel.price)}
            <span className={styles.roomPriceNight}>/đêm</span>
          </p>
          <button type="button" aria-pressed={active} onClick={onToggle} className={`${styles.roomSelectBtn} ${active ? styles.roomSelectBtnActive : ''}`}>
            {active ? 'Đang chọn' : 'Chọn loại này'}
          </button>
        </div>
      </div>
    </article>
  );
}

export function HotelPage() {
  const kennelTypes = usePublicKennelTypes();
  const branches = usePublicBranches();
  const [pickedId, setPickedId] = useState<number | null>(null);

  const types = kennelTypes.data ?? [];
  const offered = branchIdsWithBoarding(types);
  const branchList = (branches.data ?? []).filter((b) => offered.includes(b.branchId));
  // Chỉ một loại chuồng thì chọn sẵn cho máy tính tiền.
  const selectedId = types.some((t) => t.serviceId === pickedId) ? pickedId : types.length === 1 ? types[0].serviceId : null;
  const namesOf = (kennel: KennelOption) => branchList.filter((b) => kennel.branchIds.includes(b.branchId)).map((b) => b.name);

  return (
    <div className="bg-(--color-surface-page) pb-24">
      {/* ====== Hero ====== */}
      <section className={styles.hero}>
        <img src="/imgs/cat-boarding.png" alt="" className={styles.heroBg} aria-hidden loading="eager" />
        <div className={styles.heroOverlay} aria-hidden />
        <div className={styles.heroNoise} aria-hidden />

        <div className={`${styles.heroContent} mx-auto max-w-[1280px] px-5 sm:px-8`}>
          <nav aria-label="Đường dẫn" className="mb-5 text-[12.5px] text-white/50">
            <Link to="/" className="underline-offset-4 transition-colors hover:text-white hover:underline">
              Trang chủ
            </Link>
            <span className="mx-1.5">/</span>
            <span className="font-semibold text-white/85">Khách sạn thú cưng</span>
          </nav>

          <div className="flex flex-wrap items-end justify-between gap-6">
            <div className="min-w-0">
              <div className="mb-4 inline-flex items-center gap-2 rounded-full bg-white/10 px-4 py-1.5 text-[12.5px] font-bold text-white/80 backdrop-blur-sm">
                <Bed size={14} aria-hidden />
                Khách sạn thú cưng
              </div>
              <h1 className="font-friendly font-extrabold text-[clamp(30px,4.5vw,58px)] leading-[1.05] text-white">
                Nơi nghỉ ngơi tuyệt vời
                <br />
                cho boss yêu
              </h1>
              <p className="mt-4 max-w-[50ch] text-[15px] leading-relaxed text-white/65">
                Chuồng riêng theo loài và cân nặng, nhật ký chăm sóc mỗi ngày gửi về tài khoản của bạn.
              </p>
            </div>

            <Link
              to="/boarding-bookings/new"
              className="inline-flex min-h-11 items-center gap-2 rounded-full bg-white px-6 py-3 text-[13.5px] font-bold whitespace-nowrap text-accent transition-colors hover:bg-white/90"
            >
              <Bed size={16} aria-hidden />
              Đặt chỗ lưu trú
            </Link>
          </div>
        </div>
      </section>

      {/* ====== Máy tính tiền ====== */}
      <div className="mx-auto max-w-[1280px] px-5 sm:px-8">
        <StayCalculator types={types} selectedId={selectedId} onSelect={setPickedId} loading={kennelTypes.isPending} />
      </div>

      {/* ====== Loại chuồng ====== */}
      <div className="mx-auto max-w-[1280px] px-5 sm:px-8">
        <section className={styles.section}>
          <p className={styles.sectionLabel}>Chọn chuồng</p>
          <h2 className={styles.sectionTitle}>Loại chuồng và giá theo đêm</h2>

          {kennelTypes.isPending && (
            <div role="status" aria-label="Đang tải loại chuồng" className={styles.roomsGrid}>
              {[0, 1, 2].map((i) => (
                <span key={i} className={styles.skeleton} />
              ))}
            </div>
          )}
          {kennelTypes.isError && (
            <div role="alert" className={styles.errorBox}>
              <span className="min-w-0">{plainMessage(toApiError(kennelTypes.error))}</span>
              <button type="button" onClick={() => kennelTypes.refetch()} className={styles.retryBtn}>
                <RefreshCw size={13} className="mr-1.5 inline" aria-hidden /> Thử lại
              </button>
            </div>
          )}
          {kennelTypes.isSuccess && types.length === 0 && <p className={styles.notice}>Hiện chưa có chi nhánh nào mở dịch vụ lưu trú.</p>}
          {types.length > 0 && (
            <div className={styles.roomsGrid}>
              {types.map((kennel) => (
                <KennelCard
                  key={kennel.serviceId}
                  kennel={kennel}
                  branchNames={namesOf(kennel)}
                  active={selectedId === kennel.serviceId}
                  onToggle={() => setPickedId(selectedId === kennel.serviceId && types.length > 1 ? null : kennel.serviceId)}
                />
              ))}
            </div>
          )}
        </section>
      </div>

      {/* ====== Khi gửi thú ====== */}
      <section className={styles.infoSection}>
        <div className="mx-auto max-w-[1280px] px-5 sm:px-8">
          <h2 className={styles.sectionTitle}>Khi gửi thú ở PetCare</h2>
          <div className={styles.infoGrid}>
            <div className={styles.infoCard}>
              <h3 className={styles.infoCardTitle}>Trong thời gian lưu trú</h3>
              <ul className={styles.infoList}>
                <li className={styles.infoItem}>
                  <span className={styles.infoIcon}>
                    <ClipboardList size={15} aria-hidden />
                  </span>
                  <span>Nhật ký chăm sóc mỗi ngày: ăn uống, vệ sinh, vận động, có thể kèm ảnh. Bạn xem trong mục Lưu trú của tôi.</span>
                </li>
                <li className={styles.infoItem}>
                  <span className={styles.infoIcon}>
                    <Bell size={15} aria-hidden />
                  </span>
                  <span>Thú có dấu hiệu bất thường, bạn và lễ tân chi nhánh được báo ngay.</span>
                </li>
                <li className={styles.infoItem}>
                  <span className={styles.infoIcon}>
                    <Stethoscope size={15} aria-hidden />
                  </span>
                  <span>
                    Khám hoặc tắm trong lúc lưu trú là dịch vụ riêng, tính theo bảng giá dịch vụ.{' '}
                    <Link to="/services" className={styles.infoLink}>
                      Xem dịch vụ
                    </Link>
                  </span>
                </li>
              </ul>
            </div>
            <div className={styles.infoCard}>
              <h3 className={styles.infoCardTitle}>Quy định cần biết</h3>
              <ul className={styles.infoList}>
                <li className={styles.infoItem}>
                  <span className={styles.infoIcon}>
                    <CalendarRange size={15} aria-hidden />
                  </span>
                  <span>
                    Đặt online trước ngày nhận ít nhất {BOARDING_CFG.minLeadDays} ngày, xa nhất {BOARDING_CFG.maxAdvanceDays} ngày; mỗi lần lưu trú {BOARDING_CFG.minNights} đến{' '}
                    {BOARDING_CFG.maxNights} đêm.
                  </span>
                </li>
                <li className={styles.infoItem}>
                  <span className={styles.infoIcon}>
                    <Syringe size={15} aria-hidden />
                  </span>
                  <span>Thú cần tiêm đủ các mũi bắt buộc tại PetCare khi nhận. Mũi tiêm ở nơi khác không được tính.</span>
                </li>
                <li className={styles.infoItem}>
                  <span className={styles.infoIcon}>
                    <CalendarX size={15} aria-hidden />
                  </span>
                  <span>Hủy khi còn dưới {BOARDING_CFG.lateCancelHours} giờ trước 00:00 ngày nhận là hủy muộn, tính vào hạn chế đặt online.</span>
                </li>
                <li className={styles.infoItem}>
                  <span className={styles.infoIcon}>
                    <Clock size={15} aria-hidden />
                  </span>
                  <span>Giờ trả thú là {BOARDING_CFG.checkoutTime}. Trả sau giờ này tính thêm 1 đêm.</span>
                </li>
              </ul>
            </div>
          </div>
        </div>
      </section>

      {/* ====== Chi nhánh nhận lưu trú ====== */}
      {kennelTypes.isSuccess && branchList.length > 0 && (
        <div className="mx-auto max-w-[1280px] px-5 sm:px-8">
          <section aria-label="Chi nhánh nhận lưu trú" className={styles.section}>
            <h2 className={styles.sectionTitle}>Nơi nhận lưu trú</h2>
            <ul className={styles.branchGrid}>
              {branchList.map((b) => (
                <li key={b.branchId} className={styles.branchCard}>
                  <h3 className={styles.branchName}>{b.name}</h3>
                  <p className={styles.branchMeta}>
                    <MapPin size={15} className="mt-0.5 shrink-0" aria-hidden /> <span className="min-w-0">{b.address}</span>
                  </p>
                  <p className={styles.branchMeta}>
                    <Phone size={15} className="mt-0.5 shrink-0" aria-hidden /> <span className="min-w-0">{b.phone}</span>
                  </p>
                </li>
              ))}
            </ul>
            <Link to="/branches" className={styles.branchLink}>
              Xem tất cả chi nhánh
            </Link>
          </section>
        </div>
      )}
    </div>
  );
}
