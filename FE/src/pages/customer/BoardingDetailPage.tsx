/**
 * Chi tiết lưu trú của khách (UC58 gia hạn / hủy, UC60 xem lưu trú và nhật ký chăm sóc): boarding-v1 #10, #11, #12, #19.
 * Đặt chỗ không phải của khách → BE 404 → trang "Không tìm thấy lưu trú" (không lộ gì thêm); id không phải số thì không gọi API.
 * Gia hạn / hủy không bị chặn khi hồ sơ còn chờ liên kết (BR-TK-19 chỉ chặn đặt mới, #8). Giữ nguyên <h1> "Chi tiết lưu trú".
 */
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { useBooking } from '@/shared/hooks/useBoarding';
import { usePublicBranches } from '@/shared/hooks/useAppointments';
import { BookingHeader } from './boarding-detail/BookingHeader';
import { BookingInfo } from './boarding-detail/BookingInfo';
import { CancelDialog } from './boarding-detail/CancelDialog';
import { CareLogSection } from './boarding-detail/CareLogSection';
import { ExtendDialog } from './boarding-detail/ExtendDialog';
import { parseBookingId } from './boarding-detail/model';
import { LoadError, LoadingState, NotFoundState } from './boarding-detail/States';
import { StatusNotices } from './boarding-detail/StatusNotices';
import { StatusTimeline } from './boarding-detail/StatusTimeline';

type DialogName = 'extend' | 'cancel' | null;

export function BoardingDetailPage() {
  const id = parseBookingId(useParams().bookingId);
  return (
    <div className="bg-(--color-surface-page) pb-24">
      <CommonPageHero
        eyebrow="Lưu trú của tôi"
        title="Chi tiết lưu trú"
        subtitle="Theo dõi đặt chỗ, gia hạn hoặc hủy, và xem nhật ký chăm sóc của thú cưng."
        breadcrumbs={[{ label: 'Lưu trú của tôi', href: '/boarding-bookings' }, { label: 'Chi tiết' }]}
      />
      <div className="mx-auto max-w-[1180px] px-5 pt-8 sm:px-8">{id === null ? <NotFoundState /> : <BookingDetail bookingId={id} />}</div>
    </div>
  );
}

function BookingDetail({ bookingId }: { bookingId: number }) {
  const booking = useBooking(bookingId);
  const branches = usePublicBranches();
  const [dialog, setDialog] = useState<DialogName>(null);

  const b = booking.data;
  if (!b) {
    if (!booking.isError) return <LoadingState />;
    const error = toApiError(booking.error);
    return error.statusCode === 404 ? <NotFoundState /> : <LoadError message={plainMessage(error)} onRetry={() => void booking.refetch()} />;
  }

  const branch = branches.data?.find((x) => x.branchId === b.branchId);
  return (
    <div className="space-y-6">
      <BookingHeader booking={b} onExtend={() => setDialog('extend')} onCancel={() => setDialog('cancel')} />
      <StatusNotices booking={b} branch={branch} />
      <div className="grid gap-6 lg:grid-cols-[minmax(0,380px)_minmax(0,1fr)] lg:items-start">
        <div className="space-y-6">
          <StatusTimeline booking={b} />
          <BookingInfo booking={b} branch={branch} branchLoading={branches.isPending} />
        </div>
        <CareLogSection booking={b} />
      </div>
      {/* Luôn gắn (không phụ thuộc canExtend / canCancel) để câu lỗi 409 / 400 của BE không biến mất khi trang làm mới. */}
      <ExtendDialog booking={b} open={dialog === 'extend'} onClose={() => setDialog(null)} />
      <CancelDialog booking={b} open={dialog === 'cancel'} onClose={() => setDialog(null)} />
    </div>
  );
}
