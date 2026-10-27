/**
 * Đặt chỗ lưu trú online (UC58) — boarding-v1 #7, #8. Route nằm trong RequireLinkDecision (BR-TK-19): khách còn chờ liên kết hồ sơ không vào được.
 * Khách đang bị hạn chế đặt online (BR-LH-09, dùng chung với lịch hẹn): ẩn form, hiện lý do và thời điểm hết hạn chế. Nếu hạn chế phát sinh
 * đúng lúc gửi, BE từ chối bằng chính câu BR-LH-09 và trang giữ câu đó trên thông báo hạn chế.
 */
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { RestrictionNotice } from '@/components/appointments/RestrictionNotice';
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero';
import { useMyBookingRestriction } from '@/shared/hooks/useAppointments';
import type { BookingResult } from '@/shared/types/boarding';
import { BookingConfirmation } from './boarding/BookingConfirmation';
import { BookingForm } from './boarding/BookingForm';
import base from './BookingPage.module.css';

export function BoardingNewPage() {
  const restriction = useMyBookingRestriction();
  const [done, setDone] = useState<{ result: BookingResult; branchName: string } | null>(null);
  const [formKey, setFormKey] = useState(0);
  const [rejection, setRejection] = useState('');
  const blocked = restriction.data?.restricted ? restriction.data.restriction : null;

  return (
    <div className="bg-(--color-surface-page) pb-24">
      <CommonPageHero
        eyebrow="Khách sạn thú cưng"
        title="Đặt chỗ lưu trú"
        subtitle="Chọn thú cưng, loại chuồng và ngày. Hệ thống kiểm tra chỗ trống từng đêm trước khi bạn xác nhận."
        breadcrumbs={[{ label: 'Lưu trú', href: '/boarding-bookings' }, { label: 'Đặt chỗ' }]}
      />

      <div className="mx-auto max-w-[1280px] px-5 sm:px-8">
        {done ? (
          <BookingConfirmation
            result={done.result}
            branchName={done.branchName}
            onAnother={() => {
              setDone(null);
              setFormKey((k) => k + 1);
            }}
          />
        ) : (
          <>
            {restriction.isPending && <p className={`${base.muted} pt-8`}>Đang kiểm tra quyền đặt chỗ…</p>}
            {blocked && (
              <div className="space-y-4 pt-8">
                {rejection && (
                  <p role="alert" className={base.formError}>
                    {rejection}
                  </p>
                )}
                <RestrictionNotice restriction={blocked} />
                <Link to="/boarding-bookings" className={base.emptyAction}>
                  Xem lưu trú của tôi
                </Link>
              </div>
            )}
            {!restriction.isPending && !blocked && <BookingForm key={formKey} onBooked={(result, branchName) => setDone({ result, branchName })} onRestricted={setRejection} />}
          </>
        )}
      </div>
    </div>
  );
}
