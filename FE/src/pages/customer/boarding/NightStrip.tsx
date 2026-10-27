/**
 * Dải chỗ còn lại từng đêm (boarding-v1 #7): đêm = ngày nhận … ngày trả − 1. Đêm hết chỗ (remaining = 0) có chữ "Hết chỗ" và sọc chéo,
 * đêm còn đúng 1 chỗ được nhấn nhẹ. Ô xếp lưới tự xuống dòng nên tối đa 30 đêm vẫn nằm gọn trong khung, kể cả màn 360 px.
 */
import { cn } from '@/lib/utils';
import type { NightAvailability } from '@/shared/types/boarding';
import { ddMM, weekdayShort } from './format';
import styles from './Boarding.module.css';

export function NightStrip({ nights }: { nights: readonly NightAvailability[] }) {
  return (
    <ul aria-label="Chỗ còn lại theo đêm" className={styles.nights}>
      {nights.map((n) => {
        const full = n.remaining <= 0;
        return (
          <li key={n.date} className={cn(styles.night, full && styles.nightFull, !full && n.remaining === 1 && styles.nightLow)}>
            <span className={styles.nightDow}>{weekdayShort(n.date)}</span>
            <span className={styles.nightDate}>{ddMM(n.date)}</span>
            <span className={styles.nightLeft}>{full ? 'Hết chỗ' : `Còn ${n.remaining} chỗ`}</span>
          </li>
        );
      })}
    </ul>
  );
}

/** Khung chờ cùng hình dạng với dải đêm thật. */
export function NightStripSkeleton({ count }: { count: number }) {
  return (
    <div role="status" aria-label="Đang kiểm tra chỗ trống" className={styles.nights}>
      {Array.from({ length: count }, (_, i) => (
        <span key={i} className={styles.nightSkeleton} />
      ))}
    </div>
  );
}
