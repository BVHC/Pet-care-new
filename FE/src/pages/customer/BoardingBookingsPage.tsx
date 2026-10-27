/**
 * Lưu trú của tôi (UC60) — boarding-v1 #9. Lấy một trang tối đa (100) rồi chia tab ở client, như Lịch hẹn của tôi.
 * "Đang & sắp tới" = BOOKED / CHECKED_IN / OVERDUE; "Đã qua" = CHECKED_OUT / CANCELLED / NO_SHOW. Route nằm ngoài RequireLinkDecision:
 * khách còn chờ quyết định liên kết hồ sơ (BR-TK-19) vẫn xem được, chỉ đặt chỗ mới (/boarding-bookings/new) bị chặn.
 * Hạn chế đặt online (BR-LH-09) dùng chung với lịch hẹn: hiện thông báo, ẩn nút đặt chỗ mới, danh sách vẫn xem được.
 */
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { BedDouble, RefreshCw } from 'lucide-react';
import { AccountSidebar } from '@/components/customer/AccountSidebar';
import { RestrictionNotice } from '@/components/appointments/RestrictionNotice';
import { CommonPageHero } from '@/shared/components/layout/CommonPageHero';
import { cn } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { useMyBookingRestriction, usePublicBranches } from '@/shared/hooks/useAppointments';
import { useBookingList } from '@/shared/hooks/useBoarding';
import type { BookingStatus } from '@/shared/types/boarding';
import { isActiveBooking } from '@/shared/utils/boarding';
import { BookingCard } from './boarding/BookingCard';

type Tab = 'upcoming' | 'past';

/** Cần chú ý nhất lên đầu: quá hạn đón, đang ở, rồi tới các đặt chỗ sắp nhận theo ngày nhận gần nhất. */
const ACTIVE_RANK: Partial<Record<BookingStatus, number>> = { OVERDUE: 0, CHECKED_IN: 1, BOOKED: 2 };

function ListSkeleton() {
  return (
    <div role="status" aria-label="Đang tải lưu trú" className="space-y-4">
      {[0, 1].map((i) => (
        <div key={i} className="h-44 rounded-2xl border border-(--color-border-default) bg-white p-5 motion-safe:animate-pulse">
          <div className="h-4 w-24 rounded-full bg-(--color-surface-2)" />
          <div className="mt-5 h-5 w-40 rounded bg-(--color-surface-2)" />
          <div className="mt-6 h-10 w-full max-w-sm rounded bg-(--color-surface-2)" />
        </div>
      ))}
    </div>
  );
}

export function BoardingBookingsPage() {
  const list = useBookingList({ size: 100 });
  const restriction = useMyBookingRestriction();
  const branches = usePublicBranches();
  const [tab, setTab] = useState<Tab>('upcoming');

  const all = list.data?.content ?? [];
  const upcoming = all
    .filter((b) => isActiveBooking(b.status))
    .sort((a, b) => (ACTIVE_RANK[a.status]! - ACTIVE_RANK[b.status]!) || a.checkInDate.localeCompare(b.checkInDate));
  // API trả mới nhất trước (ngày nhận giảm dần): giữ nguyên thứ tự đó cho lưu trú đã qua.
  const past = all.filter((b) => !isActiveBooking(b.status));
  const shown = tab === 'upcoming' ? upcoming : past;
  const blocked = restriction.data?.restricted ? restriction.data.restriction : null;
  const branchOf = (branchId: number) => branches.data?.find((b) => b.branchId === branchId);

  const tabs: { id: Tab; label: string; count: number }[] = [
    { id: 'upcoming', label: 'Đang & sắp tới', count: upcoming.length },
    { id: 'past', label: 'Đã qua', count: past.length },
  ];

  return (
    <div className="bg-(--color-surface-page) pb-24">
      <CommonPageHero
        eyebrow="Khách sạn thú cưng"
        title="Lưu trú của tôi"
        subtitle="Theo dõi các lượt gửi thú cưng: đã đặt chỗ, đang lưu trú và đã qua."
        breadcrumbs={[{ label: 'Lưu trú' }]}
      />

      <div className="mx-auto max-w-[1280px] px-5 pt-8 sm:px-8">
        <div className="grid gap-8 lg:grid-cols-[260px_minmax(0,1fr)] xl:grid-cols-[280px_minmax(0,1fr)]">
          {/* Trên màn hẹp, danh sách lên trước menu tài khoản để khỏi phải cuộn qua cả menu. */}
          <div className="order-2 min-w-0 lg:order-1">
            <AccountSidebar active="boarding" />
          </div>

          <div className="order-1 min-w-0 space-y-6 lg:order-2">
            {blocked && <RestrictionNotice restriction={blocked} />}

            <div className="flex flex-wrap items-center justify-between gap-3">
              <div
                role="tablist"
                aria-label="Nhóm lưu trú"
                className="flex max-w-full items-center gap-1 overflow-x-auto rounded-2xl border border-(--color-border-default) bg-white p-1.5 shadow-[0_1px_2px_rgba(56,36,23,0.06)]"
              >
                {tabs.map((t) => {
                  const active = tab === t.id;
                  return (
                    <button
                      key={t.id}
                      type="button"
                      role="tab"
                      aria-selected={active}
                      onClick={() => setTab(t.id)}
                      className={cn(
                        'flex min-h-10 shrink-0 items-center gap-2 rounded-xl px-4 py-2.5 text-[13px] font-bold transition-all',
                        active
                          ? 'bg-accent text-white shadow-[0_4px_12px_-6px_rgba(164,51,36,0.7)]'
                          : 'text-(--color-text-secondary) hover:bg-(--color-surface-2) hover:text-(--color-text-primary)',
                      )}
                    >
                      {t.label}
                      <span className={cn('rounded-full px-2 py-0.5 text-[10.5px] font-bold', active ? 'bg-white/20 text-white' : 'bg-(--color-surface-2)')}>
                        {t.count}
                      </span>
                    </button>
                  );
                })}
              </div>
              {!blocked && (
                <Link
                  to="/boarding-bookings/new"
                  className="inline-flex min-h-10 items-center gap-1.5 rounded-full bg-accent px-5 py-2.5 text-[13px] font-bold whitespace-nowrap text-white transition-colors hover:bg-(--color-accent-hover)"
                >
                  <BedDouble size={14} aria-hidden /> Đặt chỗ lưu trú
                </Link>
              )}
            </div>

            {list.isError && (
              <div role="alert" className="flex flex-wrap items-center justify-between gap-3 rounded-xl bg-red-50 px-4 py-3 text-sm text-red-700">
                <span className="min-w-0 break-words">{plainMessage(toApiError(list.error))}</span>
                <button
                  type="button"
                  onClick={() => list.refetch()}
                  className="inline-flex min-h-10 items-center gap-1.5 rounded-xl border border-red-200 bg-white px-4 text-[12.5px] font-bold text-red-700 hover:bg-red-100"
                >
                  <RefreshCw size={13} aria-hidden /> Thử lại
                </button>
              </div>
            )}
            {list.isPending && <ListSkeleton />}

            {list.isSuccess && shown.length === 0 && (
              <div className="rounded-3xl border border-dashed border-(--color-border-default) bg-white p-12 text-center sm:p-16">
                <BedDouble size={48} className="mx-auto mb-4 text-(--color-text-secondary) opacity-30" aria-hidden />
                <p className="text-[15px] font-bold text-(--color-text-secondary)">
                  {tab === 'upcoming' ? 'Chưa có lưu trú sắp tới' : 'Chưa có lưu trú đã qua'}
                </p>
                {tab === 'upcoming' && !blocked && (
                  <p className="mt-1.5 text-[13px] text-(--color-text-secondary)">Chọn Đặt chỗ lưu trú để giữ chỗ cho thú cưng của bạn.</p>
                )}
              </div>
            )}

            {shown.length > 0 && (
              <div className="space-y-4">
                {shown.map((b) => (
                  <BookingCard key={b.bookingId} booking={b} branch={branchOf(b.branchId)} />
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
