import { useRef, useState } from 'react';
import { UserPlus } from 'lucide-react';
import { cn } from '../../lib/utils';
import { CatalogPanel } from '../../components/staff/reception/CatalogPanel';
import { CheckInDialog, type CheckInTarget } from '../../components/staff/reception/CheckInDialog';
import { CheckoutPanel, ShiftBadge } from '../../components/staff/reception/CheckoutPanel';
import { FrontDeskPanel } from '../../components/staff/reception/FrontDeskPanel';
import { WorkspaceHeader, WsButton } from '../../components/staff/ui';
import { useHotkeys } from '../../shared/hooks/useHotkeys';
import { useMediaQuery } from '../../shared/hooks/useMediaQuery';
import { useStaffUser } from '../../shared/hooks/useStaffUser';
import { usePOS } from '../../shared/stores/pos.store';
import { formatToday } from '../../shared/utils/clinic-format';

type View = 'desk' | 'sell' | 'cart';

const VIEWS: { id: View; label: string; className?: string }[] = [
  { id: 'desk', label: 'Hàng chờ' },
  { id: 'sell', label: 'Bán hàng' },
  { id: 'cart', label: 'Phiếu thu', className: 'lg:hidden' },
];

/**
 * Quầy lễ tân: đón khách, theo dõi hàng chờ, thu tiền.
 * ≥1280px shows all three panels; 1024–1279 keeps the receipt beside one switchable panel;
 * phones switch between the three.
 */
export function ReceptionWorkspacePage() {
  const user = useStaffUser();
  const [view, setView] = useState<View>('desk');
  const [checkIn, setCheckIn] = useState<CheckInTarget | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const receiptCount = usePOS((s) => s.retail.reduce((n, l) => n + l.qty, 0) + s.orderIds.length);
  const wide = useMediaQuery('(min-width: 1024px)');
  const receiptVisible = wide || view === 'cart';

  const focusSearch = () => {
    setView((v) => (v === 'desk' ? 'sell' : v));
    window.requestAnimationFrame(() => searchRef.current?.focus());
  };

  useHotkeys(
    {
      f1: focusSearch,
      'mod+k': focusSearch,
      n: () => setCheckIn({ mode: 'walk-in' }),
      // Never pay from a receipt that is off screen: show it first.
      ...(receiptVisible ? {} : { 'mod+enter': () => setView('cart') }),
    },
    !checkIn,
  );

  const showOnSmall = (id: View) => (view === id ? 'flex' : 'hidden');

  return (
    <div className="flex flex-col lg:h-full">
      <WorkspaceHeader title="Quầy lễ tân" subtitle={formatToday()}>
        <ShiftBadge receptionistId={user.id} />
        <WsButton variant="primary" onClick={() => setCheckIn({ mode: 'walk-in' })}>
          <UserPlus className="h-4 w-4" aria-hidden />
          Tiếp nhận khách
          <span className="text-xs font-medium opacity-75">N</span>
        </WsButton>
      </WorkspaceHeader>

      <div aria-label="Khu vực quầy" role="group" className="flex gap-1 border-b border-(--ws-line) bg-(--ws-surface) px-3 py-2 xl:hidden">
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
            {v.label}
            {v.id === 'cart' && receiptCount > 0 && (
              <span className="ws-num rounded-full bg-(--ws-accent) px-1.5 text-[11px] font-bold text-white">{receiptCount}</span>
            )}
          </button>
        ))}
      </div>

      <div className="grid min-h-0 flex-1 gap-3 p-3 lg:grid-cols-[minmax(0,1fr)_340px] xl:grid-cols-[320px_minmax(0,1fr)_340px]">
        <FrontDeskPanel
          className={cn(showOnSmall('desk'), 'min-h-[60dvh] lg:min-h-0 xl:flex')}
          actorId={user.id}
          onCheckIn={(arrival) => setCheckIn({ mode: 'appointment', arrival })}
          onWalkIn={() => setCheckIn({ mode: 'walk-in' })}
          onCollect={() => {
            if (!wide) setView('cart');
          }}
        />
        <CatalogPanel
          ref={searchRef}
          className={cn(showOnSmall('sell'), view === 'cart' && 'lg:flex', 'min-h-[60dvh] lg:min-h-0 xl:flex')}
        />
        <CheckoutPanel
          className={cn(showOnSmall('cart'), 'min-h-[60dvh] lg:flex lg:min-h-0')}
          receptionistId={user.id}
          hotkeysEnabled={receiptVisible}
        />
      </div>

      <CheckInDialog target={checkIn} onClose={() => setCheckIn(null)} actorId={user.id} />
    </div>
  );
}
