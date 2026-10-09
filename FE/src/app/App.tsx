import { lazy } from 'react';
import { Routes, Route, Navigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Toaster } from 'sonner';
import { GlobalModal } from './GlobalModal';
import { PublicLayout } from '../shared/components/layout/PublicLayout';
import { HomePage } from '../pages/customer/HomePage';
import { ShopPage } from '../pages/customer/ShopPage';
import { ProductDetailPage } from '../pages/customer/ProductDetailPage';
import { CartPage } from '../pages/customer/CartPage';
import { CheckoutPage } from '../pages/customer/CheckoutPage';
import { OrderConfirmationPage } from '../pages/customer/OrderConfirmationPage';
import { BookingPage } from '../pages/customer/BookingPage';
import { PetsPage } from '../pages/customer/PetsPage';
import { AccountPage } from '../pages/customer/AccountPage';
import { OrderHistoryPage } from '../pages/customer/OrderHistoryPage';
import { FavoritesPage } from '../pages/customer/FavoritesPage';
import { NotificationsPage } from '../pages/customer/NotificationsPage';
import { PaymentPage } from '../pages/customer/PaymentPage';
import { SecurityPage } from '../pages/customer/SecurityPage';
import { SettingsPage } from '../pages/customer/SettingsPage';
import { HelpPage } from '../pages/customer/HelpPage';
import { MembershipPage } from '../pages/customer/MembershipPage';
import { CaregiversPage } from '../pages/customer/CaregiversPage';
import { VouchersPage } from '../pages/customer/VouchersPage';
import { AppointmentsPage } from '../pages/customer/AppointmentsPage';
import { PackagesPage } from '../pages/customer/PackagesPage';
import { ReviewsPage } from '../pages/customer/ReviewsPage';
import { AboutPage } from '../pages/about/AboutPage';
import { RecommendPage } from '../pages/recommend/RecommendPage';
import { HotelPage } from '../pages/hotel/HotelPage';
import { LoginPage } from '../pages/auth/LoginPage';
import { RegisterPage } from '../pages/auth/RegisterPage';
import { VerifyOtpPage } from '../pages/auth/VerifyOtpPage';
import { ForgotPasswordPage } from '../pages/auth/ForgotPasswordPage';
import { ChangePasswordPage } from '../pages/auth/ChangePasswordPage';
import { TermsPage, PrivacyPage } from '../pages/legal/LegalPage';
import { NewsPage } from '../pages/news/NewsPage';
import { RequireRole } from '../shared/components/auth/RequireRole';
import { ROUTES } from '../shared/constants/routes';
import { useAccount } from '../shared/stores/session.store';
import { ALL_ROLES, STAFF_ROLES } from '../shared/types/auth';

// Staff workspaces (lazy: each workspace is its own chunk)
import { WorkspaceLayout } from '../components/staff/WorkspaceLayout';
import { WORKSPACE_PATHS, WORKSPACE_ROLES, homePathFor } from '../shared/constants/workspaces';
const ReceptionWorkspacePage = lazy(() => import('../pages/staff/ReceptionWorkspacePage').then((m) => ({ default: m.ReceptionWorkspacePage })));
const DoctorWorkspacePage = lazy(() => import('../pages/staff/DoctorWorkspacePage').then((m) => ({ default: m.DoctorWorkspacePage })));
const GroomingWorkspacePage = lazy(() => import('../pages/staff/GroomingWorkspacePage').then((m) => ({ default: m.GroomingWorkspacePage })));

// Admin imports
import { AdminLayout } from '../components/admin/AdminLayoutWrapper';
import { AdminLoginPage } from '../pages/admin/AdminLoginPage';
import { AdminDashboardPage } from '../pages/admin/AdminDashboardPage';
import { AdminPOSPage } from '../pages/admin/AdminPOSPage';
import { AdminAppointmentsPage } from '../pages/admin/AdminAppointmentsPage';
import { AdminQueuePage } from '../pages/admin/AdminQueuePage';
import { AdminExamPage } from '../pages/admin/AdminExamPage';
import { AdminVaccinationPage } from '../pages/admin/AdminVaccinationPage';
import { AdminGroomingPage } from '../pages/admin/AdminGroomingPage';
import { AdminInvoicesPage } from '../pages/admin/AdminInvoicesPage';
import { AdminPaymentsPage } from '../pages/admin/AdminPaymentsPage';
import { AdminRefundsPage } from '../pages/admin/AdminRefundsPage';
import { AdminPromotionsPage } from '../pages/admin/AdminPromotionsPage';
import { AdminMembershipPage } from '../pages/admin/AdminMembershipPage';
import { AdminWarehousePage } from '../pages/admin/AdminWarehousePage';
import { AdminPurchasingPage } from '../pages/admin/AdminPurchasingPage';
import { AdminVaccinesPage } from '../pages/admin/AdminVaccinesPage';
import { AdminWorkforcePage } from '../pages/admin/AdminWorkforcePage';
import { AdminIncidentsPage } from '../pages/admin/AdminIncidentsPage';
import { AdminReportsPage } from '../pages/admin/AdminReportsPage';
import { AdminAuditPage } from '../pages/admin/AdminAuditPage';
import { AdminUsersPage } from '../pages/admin/AdminUsersPage';
import { AdminTenantsPage } from '../pages/admin/AdminTenantsPage';
import { AdminOrdersPage } from '../pages/admin/AdminOrdersPage';
import { AdminAIPage } from '../pages/admin/AdminAIPage';

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 60_000, refetchOnWindowFocus: false } },
});

/** Nhân viên tuyến đầu: có ít nhất một bàn làm việc. */
const FRONT_LINE_ROLES = [...new Set([...WORKSPACE_ROLES.reception, ...WORKSPACE_ROLES.doctor, ...WORKSPACE_ROLES.grooming])];

/** /staff, /admin → trang đầu của role. */
function StaffHome() {
  const account = useAccount();
  return <Navigate to={account ? homePathFor(account.role) : ROUTES.staffLogin} replace />;
}

const staffOnly = (roles: typeof STAFF_ROLES, page: React.ReactNode) => (
  <RequireRole roles={roles} loginPath={ROUTES.staffLogin}>
    {page}
  </RequireRole>
);

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <Routes>
        {/* Public: header/footer công khai */}
        <Route element={<PublicLayout />}>
          <Route path="/" element={<HomePage />} />
          <Route path="/about" element={<AboutPage />} />
          <Route path="/hotel" element={<HotelPage />} />
          <Route path="/news" element={<NewsPage />} />
          <Route path="/help" element={<HelpPage />} />
          <Route path="/terms" element={<TermsPage />} />
          <Route path="/privacy" element={<PrivacyPage />} />

          {/* Mọi role đã đăng nhập: hồ sơ cá nhân (UC06), đổi mật khẩu (UC05) */}
          <Route element={<RequireRole roles={ALL_ROLES} />}>
            <Route path="/account" element={<AccountPage />} />
            <Route path="/security" element={<SecurityPage />} />
          </Route>

          {/* Khu khách hàng (A02) */}
          <Route element={<RequireRole roles={['CUSTOMER']} />}>
            <Route path="/booking" element={<BookingPage />} />
            <Route path="/pets" element={<PetsPage />} />
            <Route path="/appointments" element={<AppointmentsPage />} />
            <Route path="/notifications" element={<NotificationsPage />} />
            <Route path="/settings" element={<SettingsPage />} />
            <Route path="/review" element={<ReviewsPage />} />
          </Route>

          {/* Trang của spec cũ, ngoài phạm vi GĐ1 v16 (gỡ ở T57) */}
          <Route path="/shop" element={<ShopPage />} />
          <Route path="/shop/:id" element={<ProductDetailPage />} />
          <Route path="/cart" element={<CartPage />} />
          <Route path="/checkout" element={<CheckoutPage />} />
          <Route path="/order/:id" element={<OrderConfirmationPage />} />
          <Route path="/orders" element={<OrderHistoryPage />} />
          <Route path="/favorites" element={<FavoritesPage />} />
          <Route path="/payment" element={<PaymentPage />} />
          <Route path="/membership" element={<MembershipPage />} />
          <Route path="/caregivers" element={<CaregiversPage />} />
          <Route path="/vouchers" element={<VouchersPage />} />
          <Route path="/packages" element={<PackagesPage />} />
          <Route path="/recommend" element={<RecommendPage />} />
        </Route>

        {/* Auth Routes (without header/footer layout) */}
        <Route path="/auth/login" element={<LoginPage />} />
        <Route path="/auth/register" element={<RegisterPage />} />
        <Route path="/auth/verify-otp" element={<VerifyOtpPage />} />
        <Route path="/auth/forgot" element={<ForgotPasswordPage />} />
        <Route path={ROUTES.changePassword} element={<ChangePasswordPage />} />

        {/* Staff workspaces */}
        <Route path="/staff" element={staffOnly(STAFF_ROLES, <StaffHome />)} />
        <Route element={staffOnly(FRONT_LINE_ROLES, <WorkspaceLayout />)}>
          <Route path={WORKSPACE_PATHS.reception} element={staffOnly(WORKSPACE_ROLES.reception, <ReceptionWorkspacePage />)} />
          <Route path={WORKSPACE_PATHS.doctor} element={staffOnly(WORKSPACE_ROLES.doctor, <DoctorWorkspacePage />)} />
          <Route path={WORKSPACE_PATHS.grooming} element={staffOnly(WORKSPACE_ROLES.grooming, <GroomingWorkspacePage />)} />
        </Route>

        {/* Admin Routes */}
        <Route path="/admin" element={staffOnly(STAFF_ROLES, <StaffHome />)} />
        <Route path={ROUTES.staffLogin} element={<AdminLoginPage />} />
        <Route element={<AdminLayout />}>
          <Route path="/admin/dashboard" element={<AdminDashboardPage />} />
          <Route path="/admin/pos" element={<AdminPOSPage />} />
          <Route path="/admin/appointments" element={<AdminAppointmentsPage />} />
          <Route path="/admin/queue" element={<AdminQueuePage />} />
          <Route path="/admin/exam" element={<AdminExamPage />} />
          <Route path="/admin/vaccination" element={<AdminVaccinationPage />} />
          <Route path="/admin/grooming" element={<AdminGroomingPage />} />
          <Route path="/admin/orders" element={<AdminOrdersPage />} />
          <Route path="/admin/invoices" element={<AdminInvoicesPage />} />
          <Route path="/admin/payments" element={<AdminPaymentsPage />} />
          <Route path="/admin/refunds" element={<AdminRefundsPage />} />
          <Route path="/admin/promotions" element={<AdminPromotionsPage />} />
          <Route path="/admin/membership" element={<AdminMembershipPage />} />
          <Route path="/admin/warehouse" element={<AdminWarehousePage />} />
          <Route path="/admin/purchasing" element={<AdminPurchasingPage />} />
          <Route path="/admin/vaccines" element={<AdminVaccinesPage />} />
          <Route path="/admin/workforce" element={<AdminWorkforcePage />} />
          <Route path="/admin/incidents" element={<AdminIncidentsPage />} />
          <Route path="/admin/reports" element={<AdminReportsPage />} />
          <Route path="/admin/audit" element={<AdminAuditPage />} />
          <Route path="/admin/users" element={<AdminUsersPage />} />
          <Route path="/admin/tenants" element={<AdminTenantsPage />} />
          <Route path="/admin/ai" element={<AdminAIPage />} />
        </Route>
      </Routes>
      <GlobalModal />
      <Toaster position="top-right" richColors />
    </QueryClientProvider>
  );
}

export default App;
