import { Outlet } from 'react-router-dom';
import { SiteHeader } from './SiteHeader';
import { SiteFooter } from './SiteFooter';
import { NAV_TO_PATH } from '../../constants/routes';
import { useCartStore } from '../../stores/cart.store';
import { useNavigate } from 'react-router-dom';

// Layout công khai (Home, Shop, Booking...).
export function PublicLayout() {
  const navigate = useNavigate();
  const count = useCartStore((s) => s.count());

  const onNav = (page: string) => {
    const path = NAV_TO_PATH[page] ?? '/';
    navigate(path);
  };

  return (
    <div className="min-h-screen flex flex-col bg-[var(--color-surface-page)]">
      <SiteHeader currentPage="home" cartCount={count} onNav={onNav} />
      <main className="flex-1 pt-[150px]">
        <Outlet />
      </main>
      <SiteFooter onNav={onNav} />
    </div>
  );
}
