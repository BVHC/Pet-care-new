// Thông báo theo trạng thái ngay dưới đầu trang: quá hạn đón, hủy / không đến (kèm hệ quả hạn chế đặt online, BR-LH-09),
// kết thúc do thú mất (nhẹ nhàng, không đổ lỗi), và lý do vì sao không còn nút gia hạn / hủy.
import { useId, type ReactNode } from 'react';
import { Ban, Heart, Info, TriangleAlert, type LucideIcon } from 'lucide-react';
import { formatVnDateTime } from '@/components/appointments/format';
import { cn } from '@/lib/utils';
import { BOARDING_CFG, BOOKING_CANCEL_SOURCE_LABELS, type Booking } from '@/shared/types/boarding';
import { canCancel, canExtend } from '@/shared/utils/boarding';
import { PhoneLink, type BranchContact } from './BookingInfo';
import { isDeceasedEnd, RESTRICTION_RULE } from './model';
import { CARD } from './styles';

const TONE = {
  warning: 'border-amber-300 bg-amber-50 text-amber-950',
  danger: 'border-red-200 bg-red-50 text-red-950',
  calm: 'border-(--color-border-default) bg-(--color-surface-2) text-(--color-text-primary)',
} as const;

function Notice({
  tone,
  icon: Icon,
  title,
  alert,
  children,
}: {
  tone: keyof typeof TONE;
  icon: LucideIcon;
  title: string;
  alert?: boolean;
  children: ReactNode;
}) {
  const titleId = useId();
  return (
    <section
      role={alert ? 'alert' : undefined}
      aria-labelledby={titleId}
      className={cn('flex gap-3 rounded-2xl border p-4 sm:gap-4 sm:p-5', TONE[tone])}
    >
      <Icon size={20} className="mt-0.5 shrink-0" aria-hidden />
      <div className="min-w-0 space-y-1.5 text-[13.5px] leading-relaxed">
        <h2 id={titleId} className="text-[15px] font-bold">
          {title}
        </h2>
        {children}
      </div>
    </section>
  );
}

/** "chi nhánh PetCare Cầu Giấy, gọi 0901234567" (chưa tải được chi nhánh thì chỉ nói "chi nhánh"). */
function ContactBranch({ branch }: { branch: BranchContact }) {
  return (
    <>
      chi nhánh{branch ? ` ${branch.name}` : ''}
      {branch?.phone && (
        <>
          , gọi <PhoneLink phone={branch.phone} />
        </>
      )}
    </>
  );
}

export function StatusNotices({ booking: b, branch }: { booking: Booking; branch: BranchContact }) {
  return (
    <>
      {b.status === 'OVERDUE' && (
        <Notice tone="danger" icon={TriangleAlert} title="Đã quá hạn đón" alert>
          <p>
            {b.petName} chưa được đón{b.overdueSince ? ` từ ${formatVnDateTime(b.overdueSince)}` : ' sau giờ trả'}. Đặt chỗ này không gia hạn được nữa,
            vui lòng liên hệ <ContactBranch branch={branch} /> để sắp xếp đón thú cưng.
          </p>
        </Notice>
      )}

      {b.status === 'CANCELLED' && (
        <Notice tone={b.lateCancel ? 'warning' : 'calm'} icon={Ban} title="Thông tin hủy">
          <p>
            <span className="text-(--color-text-secondary)">Nguồn hủy:</span> <strong>{b.cancelSource ? BOOKING_CANCEL_SOURCE_LABELS[b.cancelSource] : 'Đã hủy'}</strong>
          </p>
          {b.cancelReason && (
            <p className="break-words">
              <span className="text-(--color-text-secondary)">Lý do:</span> {b.cancelReason}
            </p>
          )}
          {b.lateCancel && (
            <p>
              <strong>Hủy muộn.</strong> Hủy trong vòng {BOARDING_CFG.lateCancelHours} giờ trước ngày nhận, tính vào hạn chế đặt online: {RESTRICTION_RULE}.
            </p>
          )}
          {!b.lateCancel && b.cancelSource === 'CLINIC' && <p>Do phòng khám chủ động hủy nên không tính vào hạn chế đặt online.</p>}
        </Notice>
      )}

      {b.status === 'NO_SHOW' && (
        <Notice tone="danger" icon={Ban} title="Không đến nhận thú">
          <p>
            Thú cưng chưa được đưa đến trong ngày nhận nên đặt chỗ đã tự đóng. Lần này tính vào hạn chế đặt online: {RESTRICTION_RULE}.
          </p>
        </Notice>
      )}

      {isDeceasedEnd(b) && (
        <Notice tone="calm" icon={Heart} title="Lưu trú đã kết thúc">
          <p>
            Chúng tôi rất tiếc: {b.petName} đã ra đi trong thời gian lưu trú. Toàn thể nhân viên xin gửi lời chia buồn đến bạn. Nếu cần thêm thông tin hoặc
            hỗ trợ, vui lòng liên hệ <ContactBranch branch={branch} />.
          </p>
        </Notice>
      )}

      {b.status === 'BOOKED' && !canCancel(b) && (
        <HintLine>
          Đã tới ngày nhận, không hủy online được. Cần thay đổi xin liên hệ <ContactBranch branch={branch} />.
        </HintLine>
      )}
      {(b.status === 'BOOKED' || b.status === 'CHECKED_IN') && !canExtend(b) && (
        <HintLine>
          Đã tới ngày trả dự kiến, không gia hạn online được. Cần ở thêm xin liên hệ <ContactBranch branch={branch} />.
        </HintLine>
      )}
    </>
  );
}

function HintLine({ children }: { children: ReactNode }) {
  return (
    <p className={cn(CARD, 'flex items-start gap-2.5 px-4 py-3 text-[13px] text-(--color-text-secondary)')}>
      <Info size={16} className="mt-0.5 shrink-0" aria-hidden />
      <span>{children}</span>
    </p>
  );
}
