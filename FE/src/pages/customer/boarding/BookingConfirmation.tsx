/**
 * Màn xác nhận sau khi đặt chỗ lưu trú thành công (boarding-v1 #8). `vaccineWarnings` là cảnh báo, KHÔNG chặn (BR-LT-05):
 * chỗ vẫn được giữ, nhưng nếu tới ngày nhận chưa đủ mũi bắt buộc tại hệ thống thì phòng khám không nhận thú. Gợi ý đặt lịch tiêm trước ngày nhận.
 */
import { Link } from 'react-router-dom';
import { BedDouble, Check, ShieldAlert } from 'lucide-react';
import { formatShortDate } from '@/components/appointments/format';
import type { BookingResult, VaccineGap } from '@/shared/types/boarding';
import { nightsBetween } from '@/shared/utils/boarding';
import { nightsLabel } from './format';
import base from '../BookingPage.module.css';
import styles from './Boarding.module.css';

/** "Chưa có mũi nào tại PetCare" hoặc "Mũi gần nhất 10/10/2026, đến hạn tiêm nhắc lại 31/10/2026". */
function gapDetail(gap: VaccineGap): string {
  if (!gap.lastAdministeredOn) return 'Chưa có mũi nào ghi nhận tại PetCare';
  const last = `Mũi gần nhất ${formatShortDate(gap.lastAdministeredOn)}`;
  return gap.nextDueDate ? `${last}, đến hạn tiêm nhắc lại ${formatShortDate(gap.nextDueDate)}` : last;
}

export function BookingConfirmation({ result, branchName, onAnother }: { result: BookingResult; branchName: string; onAnother: () => void }) {
  const { booking: b, vaccineWarnings } = result;
  const rows: [string, string][] = [
    ['Thú cưng', b.petName],
    ['Chi nhánh', branchName],
    ['Loại chuồng', b.kennelTypeName],
    ['Nhận', formatShortDate(b.checkInDate)],
    ['Trả', `${formatShortDate(b.checkOutDate)}, trước 12:00`],
    ['Số đêm', nightsLabel(nightsBetween(b.checkInDate, b.checkOutDate))],
  ];

  return (
    <section aria-label="Đặt chỗ lưu trú thành công" className={styles.done}>
      <div className={styles.doneHead}>
        <span className={styles.doneIcon}>
          <Check size={28} strokeWidth={2.5} aria-hidden />
        </span>
        <div className="min-w-0">
          <h2 className={styles.doneTitle}>Đã giữ chỗ cho {b.petName}</h2>
          <p className={styles.doneCode}>
            Mã đặt chỗ <strong>{b.code}</strong>
          </p>
        </div>
      </div>

      <dl className={styles.doneDetails}>
        {rows.map(([label, value]) => (
          <div key={label} className={styles.row}>
            <dt className={styles.rowLabel}>{label}</dt>
            <dd className={styles.rowValue}>{value}</dd>
          </div>
        ))}
      </dl>

      {vaccineWarnings.length > 0 && (
        <div role="alert" aria-label="Cảnh báo tiêm phòng" className={styles.warn}>
          <h3 className={styles.warnTitle}>
            <ShieldAlert size={18} aria-hidden /> Cần tiêm phòng trước ngày nhận
          </h3>
          <p className={styles.warnText}>
            Khi nhận thú, PetCare chỉ tính các mũi đã ghi nhận tại hệ thống; mũi tiêm ở nơi khác không được tính. Chỗ của bạn vẫn được giữ, nhưng nếu tới ngày nhận
            chưa đủ các mũi dưới đây thì phòng khám sẽ không nhận thú.
          </p>
          <ul className={styles.warnList}>
            {vaccineWarnings.map((g) => (
              <li key={g.vaccineTypeId}>
                <strong>{g.vaccineTypeName}</strong>
                {gapDetail(g)}
              </li>
            ))}
          </ul>
          <Link to="/booking" className={styles.warnAction}>
            Đặt lịch tiêm trước ngày nhận
          </Link>
        </div>
      )}

      <div className={styles.doneActions}>
        <Link to={`/boarding-bookings/${b.bookingId}`} className={`${base.successPrimary} flex items-center justify-center`}>
          <BedDouble size={16} className="mr-2" aria-hidden /> Xem chi tiết lưu trú
        </Link>
        <button type="button" className={base.successSecondary} onClick={onAnother}>
          Đặt thêm chỗ
        </button>
      </div>
    </section>
  );
}
