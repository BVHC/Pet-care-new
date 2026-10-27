/**
 * Lưu trú của chi nhánh (UC57–59) — boarding-v1 #9 (danh sách) cho RECEPTIONIST, BRANCH_MANAGER, CARETAKER, VET.
 * Ba tab: Sắp nhận (BOOKED), Đang lưu trú (inStay: CHECKED_IN + OVERDUE), Lịch sử (CHECKED_OUT / CANCELLED / NO_SHOW, một trạng thái mỗi lần
 * vì API chỉ nhận một status). Nút theo vai trò × trạng thái ở boarding/rules.ts; dialog nhận / trả / nhật ký do WS khác viết, trang chỉ mount.
 * Chi nhánh lấy từ phiên (account.branchId); BE tự giới hạn phạm vi, trang không có ô chọn chi nhánh.
 */
import { useState } from 'react';
import { Plus } from 'lucide-react';
import { todayVn } from '@/components/appointments/format';
import { Field, FormError, selectClass } from '@/components/admin/form-ui';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { cn } from '@/lib/utils';
import { plainMessage, toApiError } from '@/shared/api/api-error';
import { useBookingList } from '@/shared/hooks/useBoarding';
import { useSession } from '@/shared/stores/session.store';
import { BOOKING_STATUS_LABELS, type Booking } from '@/shared/types/boarding';
import { BookingDetailDialog } from './boarding/BookingDetailDialog';
import { BookingTable, type RowAction } from './boarding/BookingTable';
import { CancelBookingDialog } from './boarding/CancelBookingDialog';
import { CareLogDialog } from './boarding/care-logs/CareLogDialog';
import { CounterBookingDialog } from './boarding/CounterBookingDialog';
import { ExtendBookingDialog } from './boarding/ExtendBookingDialog';
import { CheckInDialog } from './boarding/stay/CheckInDialog';
import { CheckoutDialog } from './boarding/stay/CheckoutDialog';
import { DeceasedDialog } from './boarding/stay/DeceasedDialog';
import { NoKennelDialog } from './boarding/stay/NoKennelDialog';
import { bookingQueryOf, canWriteCareLog, HISTORY_STATUSES, TAB_LABELS, type BoardingFilters, type BoardingTab, type HistoryStatus } from './boarding/rules';

const PAGE_SIZE = 20;
const TABS = Object.keys(TAB_LABELS) as BoardingTab[];
const EMPTY_TEXT: Record<BoardingTab, string> = {
  upcoming: 'Không có đặt chỗ sắp nhận nào.',
  inStay: 'Không có thú nào đang lưu trú.',
  history: 'Không có đặt chỗ nào trong lịch sử.',
};

interface ActiveDialog {
  action: RowAction;
  booking: Booking;
}

export function AdminBoardingPage() {
  const account = useSession((s) => s.account);
  const role = account?.role;
  const branchId = account?.branchId ?? null;
  const [filters, setFilters] = useState<BoardingFilters>({ tab: 'upcoming', historyStatus: 'CHECKED_OUT', from: '', to: '', missingCareLogToday: false });
  const [page, setPage] = useState(0);
  const [active, setActive] = useState<ActiveDialog | null>(null);
  const [counter, setCounter] = useState(false);
  const list = useBookingList(bookingQueryOf(filters, page, PAGE_SIZE));
  const data = list.data;
  const today = todayVn();
  const close = () => setActive(null);
  /** Đổi tab / bộ lọc luôn quay về trang đầu. */
  const change = (patch: Partial<BoardingFilters>) => {
    setFilters((f) => ({ ...f, ...patch }));
    setPage(0);
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-(--text-primary)">Lưu trú</h1>
          <p className="text-(--text-secondary)">Đặt chỗ lưu trú của chi nhánh: nhận thú, theo dõi nhật ký chăm sóc, trả thú.</p>
        </div>
        {role === 'RECEPTIONIST' && branchId != null && (
          <Button className="min-h-10" onClick={() => setCounter(true)}>
            <Plus className="h-4 w-4" aria-hidden /> Đặt tại quầy
          </Button>
        )}
      </div>

      <Tabs value={filters.tab} onValueChange={(tab) => change({ tab: tab as BoardingTab })}>
        <TabsList className="h-auto w-full sm:w-auto">
          {TABS.map((tab) => (
            <TabsTrigger key={tab} value={tab} className="min-h-10 flex-1 sm:flex-none">
              {TAB_LABELS[tab]}
            </TabsTrigger>
          ))}
        </TabsList>

        <TabsContent value={filters.tab} className="mt-4 space-y-4">
          <Card className="card">
            <CardContent className="flex flex-wrap items-end gap-3 p-4">
              <div className="w-44">
                <Field label="Từ ngày">{(id) => <Input id={id} type="date" value={filters.from} onChange={(e) => change({ from: e.target.value })} />}</Field>
              </div>
              <div className="w-44">
                <Field label="Đến ngày">{(id) => <Input id={id} type="date" value={filters.to} onChange={(e) => change({ to: e.target.value })} />}</Field>
              </div>
              {(filters.from || filters.to) && (
                <Button size="sm" variant="ghost" className="min-h-10" onClick={() => change({ from: '', to: '' })}>
                  Xóa lọc ngày
                </Button>
              )}
              {filters.tab === 'inStay' && (
                <Button
                  variant={filters.missingCareLogToday ? 'default' : 'outline'}
                  aria-pressed={filters.missingCareLogToday}
                  onClick={() => change({ missingCareLogToday: !filters.missingCareLogToday })}
                >
                  Chưa có nhật ký hôm nay
                </Button>
              )}
              {filters.tab === 'history' && (
                <select
                  aria-label="Trạng thái lịch sử"
                  className={cn(selectClass, 'w-44')}
                  value={filters.historyStatus}
                  onChange={(e) => change({ historyStatus: e.target.value as HistoryStatus })}
                >
                  {HISTORY_STATUSES.map((s) => (
                    <option key={s} value={s}>
                      {BOOKING_STATUS_LABELS[s]}
                    </option>
                  ))}
                </select>
              )}
            </CardContent>
          </Card>

          {list.isError ? (
            <Card className="card">
              <CardContent className="flex flex-wrap items-center gap-3 p-4">
                <div className="min-w-0 flex-1">
                  <FormError message={plainMessage(toApiError(list.error))} />
                </div>
                <Button variant="outline" onClick={() => list.refetch()}>
                  Thử lại
                </Button>
              </CardContent>
            </Card>
          ) : !data ? (
            <Card className="card">
              <CardContent role="status" className="p-8 text-center text-(--text-tertiary)">
                Đang tải danh sách…
              </CardContent>
            </Card>
          ) : data.content.length === 0 ? (
            <Card className="card">
              <CardContent className="p-8 text-center text-(--text-tertiary)">{EMPTY_TEXT[filters.tab]}</CardContent>
            </Card>
          ) : (
            <Card className="card">
              <CardContent className="p-0">
                <BookingTable bookings={data.content} role={role} today={today} stale={list.isPlaceholderData} onAction={(action, booking) => setActive({ action, booking })} />
                {data.totalPages > 1 && (
                  <div className="flex flex-wrap items-center justify-end gap-3 border-t border-(--border-color) px-4 py-3 text-sm text-(--text-secondary)">
                    <span>
                      Trang {data.page + 1}/{data.totalPages} · {data.totalElements} đặt chỗ
                    </span>
                    <Button size="sm" variant="outline" className="min-h-10" disabled={data.page === 0} onClick={() => setPage(data.page - 1)}>
                      Trước
                    </Button>
                    <Button size="sm" variant="outline" className="min-h-10" disabled={data.page + 1 >= data.totalPages} onClick={() => setPage(data.page + 1)}>
                      Sau
                    </Button>
                  </div>
                )}
              </CardContent>
            </Card>
          )}
        </TabsContent>
      </Tabs>

      {counter && branchId != null && <CounterBookingDialog branchId={branchId} onClose={() => setCounter(false)} />}
      {active?.action === 'detail' && <BookingDetailDialog booking={active.booking} onClose={close} />}
      {active?.action === 'extend' && <ExtendBookingDialog booking={active.booking} onClose={close} />}
      {active?.action === 'cancel' && <CancelBookingDialog booking={active.booking} onClose={close} />}
      {active?.action === 'checkIn' && <CheckInDialog booking={active.booking} onClose={close} />}
      {active?.action === 'noKennel' && <NoKennelDialog booking={active.booking} onClose={close} />}
      {active?.action === 'checkout' && <CheckoutDialog booking={active.booking} onClose={close} />}
      {active?.action === 'deceased' && <DeceasedDialog booking={active.booking} onClose={close} />}
      {active?.action === 'careLog' && <CareLogDialog booking={active.booking} canWrite={canWriteCareLog(role)} onClose={close} />}
    </div>
  );
}
