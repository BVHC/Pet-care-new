// Hộp "đặt thành công" của /booking (UC39, UC43): lịch thường và lịch khám tại nhà dùng chung, khác ở các dòng hiển thị.
import { Link } from 'react-router-dom';
import { Check, X } from 'lucide-react';
import styles from '../BookingPage.module.css';

/** Nội dung hộp "đặt thành công": lịch thường và lịch tại nhà dùng chung, khác ở các dòng hiển thị. */
export interface Booked {
  rows: [string, string][];
  home: boolean;
}

export function SuccessModal({ booked: { rows, home }, onClose }: { booked: Booked; onClose: () => void }) {
  return (
    <div className={styles.successOverlay} role="dialog" aria-modal="true" aria-label="Đặt lịch thành công">
      <div className={styles.successCard}>
        <div className={styles.successIcon}>
          <Check size={36} strokeWidth={2.5} />
        </div>
        <h2 className={styles.successTitle}>Đặt lịch thành công!</h2>
        <p className={styles.successDesc}>
          {home
            ? 'Lịch khám tại nhà đã được giữ. Bác sĩ sẽ đến đúng ngày hẹn; bạn có thể đổi giờ hoặc hủy trong mục Lịch hẹn của tôi.'
            : 'Lịch hẹn đã được giữ. Vui lòng đến đúng giờ; bạn có thể đổi giờ hoặc hủy trong mục Lịch hẹn của tôi.'}
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
