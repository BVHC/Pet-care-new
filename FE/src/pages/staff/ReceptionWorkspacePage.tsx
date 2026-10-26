import { lazy, Suspense, useRef, useState } from 'react';
import { UserPlus, Users } from 'lucide-react';
import { cn } from '../../lib/utils';
import type { CheckInTarget } from '../../components/staff/reception/CheckInDialog';
import { CheckoutPanel } from '../../components/staff/reception/CheckoutPanel';
import { FrontDeskPanel } from '../../components/staff/reception/FrontDeskPanel';
import { RetailOrderPanel } from '../../components/staff/reception/RetailOrderPanel';
import { CloseShiftControl, ShiftBadge } from '../../components/staff/reception/ShiftControls';
import { WorkspaceHeader, WsButton } from '../../components/staff/ui';

// Hồ sơ khách & thú cưng chỉ tải khi lễ tân mở tới: bàn đón khách hiện nhanh như trước.
// Hộp thoại tiếp nhận chỉ tải khi lễ tân mở tới (bàn hiện ra nhanh, không kéo theo tra cứu khách và dịch vụ).
const CheckInDialog = lazy(() => import('../../components/staff/reception/CheckInDialog').then((m) => ({ default: m.CheckInDialog })));
const CustomerSearchPanel = lazy(() =>
  import('../../components/staff/reception/customers/CustomerSearchPanel').then((m) => ({ default: m.CustomerSearchPanel })),
);
const CustomerDetailDialog = lazy(() =>
  import('../../components/staff/reception/customers/CustomerDetailDialog').then((m) => ({ default: m.CustomerDetailDialog })),
);
const CreateCustomerDialog = lazy(() =>
  import('../../components/staff/reception/customers/CreateCustomerDialog').then((m) => ({ default: m.CreateCustomerDialog })),
);
import { useHotkeys } from '../../shared/hooks/useHotkeys';
import { useMediaQuery } from '../../shared/hooks/useMediaQuery';
import { usePOS } from '../../shared/stores/pos.store';
import { useAccount } from '../../shared/stores/session.store';
import { visitPermissions } from '../../shared/types/visit';
import { formatToday } from '../../shared/utils/clinic-format';

type View = 'desk' | 'sell' | 'cart' | 'customers';

const VIEWS: { id: View; label: string; className?: string }[] = [
  { id: 'desk', label: 'Hàng chờ' },
  { id: 'sell', label: 'Bán hàng' },
  { id: 'customers', label: 'Khách hàng' },
  { id: 'cart', label: 'Phiếu thu', className: 'lg:hidden' },
];

/**
 * Quầy lễ tân: đón khách, theo dõi hàng chờ, bán lẻ theo Order (UC66), thu tiền. Mở cho lễ tân và quản lý chi nhánh (A05): quản lý chỉ xem hàng chờ và
 * gán lại lượt đã gọi (BR-TN-08), nên không có nút / phím tiếp nhận.
 * ≥1280px shows all three panels; 1024–1279 keeps the receipt beside one switchable panel;
 * phones switch between the three.
 */
export function ReceptionWorkspacePage() {
  const canCheckIn = visitPermissions(useAccount()?.role).checkIn;
  const [view, setView] = useState<View>('desk');
  const [checkIn, setCheckIn] = useState<CheckInTarget | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  // Order bán lẻ đang làm: giữ ở trang để mở "Khách hàng" rồi quay lại không mất Order.
  const [retailOrderId, setRetailOrderId] = useState<number | null>(null);
  const receiptCount = usePOS((s) => s.orderIds.length);
  const wide = useMediaQuery('(min-width: 1024px)');
  const receiptVisible = wide || view === 'cart';

  // Hồ sơ khách & thú cưng (UC22–24, UC26)
  const [openCustomerId, setOpenCustomerId] = useState<number | null>(null);
  const [creatingCustomer, setCreatingCustomer] = useState(false);

  const focusSearch = () => {
    setView((v) => (v === 'desk' ? 'sell' : v));
    window.requestAnimationFrame(() => searchRef.current?.focus());
  };

  useHotkeys(
    {
      f1: focusSearch,
      'mod+k': focusSearch,
      ...(canCheckIn ? { n: () => setCheckIn({ mode: 'walk-in' }) } : {}),
      // Never pay from a receipt that is off screen: show it first.
      ...(receiptVisible ? {} : { 'mod+enter': () => setView('cart') }),
    },
    !checkIn,
  );

  const showOnSmall = (id: View) => (view === id ? 'flex' : 'hidden');

  return (
    <div className="flex flex-col lg:h-full">
      <WorkspaceHeader title="Quầy lễ tân" subtitle={formatToday()}>
        <ShiftBadge />
        <CloseShiftControl />
        <WsButton
          variant={view === 'customers' ? 'primary' : 'secondary'}
          onClick={() => setView((v) => (v === 'customers' ? 'desk' : 'customers'))}
          aria-pressed={view === 'customers'}
        >
          <Users className="h-4 w-4" aria-hidden />
          <span className="hidden sm:inline">Khách hàng</span>
        </WsButton>
        {canCheckIn && (
          <WsButton variant="primary" onClick={() => setCheckIn({ mode: 'walk-in' })}>
            <UserPlus className="h-4 w-4" aria-hidden />
            <span className="hidden sm:inline">Tiếp nhận khách</span>
            <span className="hidden text-xs font-medium opacity-75 sm:inline">N</span>
          </WsButton>
        )}
      </WorkspaceHeader>

      <div aria-label="Khu vực quầy" role="group" className="flex gap-1 border-b border-(--ws-line) bg-(--ws-surface) px-3 py-2 lg:hidden">
        {VIEWS.map((v) => (
          <button
            key={v.id}
            type="button"
            aria-pressed={view === v.id}
            onClick={() => setView(v.id)}
            className={cn(
              'flex h-9 flex-1 items-center justify-center gap-1.5 rounded-md text-sm font-medium text-(--ws-ink-2) aria-pressed:bg-(--ws-accent-soft) aria-pressed:font-semibold aria-pressed:text-(--ws-accent-ink)',
              v.className,
            )}
          >
            {v.id === 'customers' && <Users className="h-4 w-4" aria-hidden />}
            {v.label}
            {v.id === 'cart' && receiptCount > 0 && (
              <span className="ws-num rounded-full bg-(--ws-accent) px-1.5 text-[11px] font-bold text-white">{receiptCount}</span>
            )}
          </button>
        ))}
      </div>

      <div className={cn('min-h-0 flex-1 gap-3 p-3', view === 'customers' ? 'block' : 'grid lg:grid-cols-[minmax(0,1fr)_340px] xl:grid-cols-[320px_minmax(0,1fr)_340px]')}>
        {view === 'customers' ? (
          <div className="rounded-xl border border-(--ws-line) bg-(--ws-surface)">
            <Suspense fallback={<p className="p-4 text-sm text-(--ws-ink-2)">Đang tải…</p>}>
              <CustomerSearchPanel onOpenCustomer={setOpenCustomerId} onCreateCustomer={() => setCreatingCustomer(true)} />
            </Suspense>
          </div>
        ) : (
          <>
            <FrontDeskPanel
              className={cn(showOnSmall('desk'), 'min-h-[60dvh] lg:min-h-0 xl:flex')}
              onCheckIn={(arrival) => setCheckIn({ mode: 'appointment', arrival })}
              onWalkIn={() => setCheckIn({ mode: 'walk-in' })}
              onCollect={() => {
                if (!wide) setView('cart');
              }}
            />
            <RetailOrderPanel
              ref={searchRef}
              orderId={retailOrderId}
              onOrderChange={setRetailOrderId}
              className={cn(showOnSmall('sell'), view === 'cart' && 'lg:flex', 'min-h-[60dvh] lg:min-h-0 xl:flex')}
            />
            <CheckoutPanel
              className={cn(showOnSmall('cart'), 'min-h-[60dvh] lg:flex lg:min-h-0')}
              hotkeysEnabled={receiptVisible}
            />
          </>
        )}
      </div>

      <Suspense fallback={null}>
        {checkIn && <CheckInDialog target={checkIn} onClose={() => setCheckIn(null)} />}
      </Suspense>
      <Suspense fallback={null}>
        {openCustomerId != null && <CustomerDetailDialog customerId={openCustomerId} onClose={() => setOpenCustomerId(null)} />}
        {creatingCustomer && <CreateCustomerDialog open onOpenChange={setCreatingCustomer} onOpenCustomer={setOpenCustomerId} />}
      </Suspense>
    </div>
  );
}
