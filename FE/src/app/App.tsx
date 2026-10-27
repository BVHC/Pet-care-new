import { lazy } from 'react';
import { Routes, Route, Navigate, useLocation } from 'react-router-dom';
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
import PetsPage from '../pages/customer/PetsPage';
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
import { BoardingBookingsPage } from '../pages/customer/BoardingBookingsPage';
import { BoardingNewPage } from '../pages/customer/BoardingNewPage';
import { BoardingDetailPage } from '../pages/customer/BoardingDetailPage';
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
import { ArticleDetailPage } from '../pages/news/ArticleDetailPage';
import { ServicesPage } from '../pages/services/ServicesPage';
import { BranchesPage } from '../pages/branches/BranchesPage';
import { DoctorsPage } from '../pages/doctors/DoctorsPage';
import { RequireLinkDecision } from '../shared/components/auth/RequireLinkDecision';
import { RequireRole } from '../shared/components/auth/RequireRole';
import { ROUTES } from '../shared/constants/routes';
import { useAccount, useSession } from '../shared/stores/session.store';
import { ALL_ROLES, STAFF_ROLES, type Role } from '../shared/types/auth';

// Staff area: one frame (StaffShell) for the workspaces and the back office; workspaces are lazy chunks
import { StaffShell } from '../components/staff/StaffShell';
import { rolesForPath } from '../shared/constants/staff-menu';
import { WORKSPACE_PATHS, WORKSPACE_ROLES, homePathFor, type Workspace } from '../shared/constants/workspaces';
const ReceptionWorkspacePage = lazy(() => import('../pages/staff/ReceptionWorkspacePage').then((m) => ({ default: m.ReceptionWorkspacePage })));
const DoctorWorkspacePage = lazy(() => import('../pages/staff/DoctorWorkspacePage').then((m) => ({ default: m.DoctorWorkspacePage })));
const GroomingWorkspacePage = lazy(() => import('../pages/staff/GroomingWorkspacePage').then((m) => ({ default: m.GroomingWorkspacePage })));

// Admin imports
import { AdminLoginPage } from '../pages/admin/AdminLoginPage';
import { AdminDashboardPage } from '../pages/admin/AdminDashboardPage';
import { AdminPOSPage } from '../pages/admin/AdminPOSPage';
import { AdminAppointmentsPage } from '../pages/admin/AdminAppointmentsPage';
import { AdminPaymentsPage } from '../pages/admin/AdminPaymentsPage';
import { AdminRefundsPage } from '../pages/admin/AdminRefundsPage';
import { AdminPromotionsPage } from '../pages/admin/AdminPromotionsPage';
import { AdminMembershipPage } from '../pages/admin/AdminMembershipPage';
import { AdminWarehousePage } from '../pages/admin/AdminWarehousePage';
import { AdminWorkforcePage } from '../pages/admin/AdminWorkforcePage';
import { AdminIncidentsPage } from '../pages/admin/AdminIncidentsPage';
import { AdminReportsPage } from '../pages/admin/AdminReportsPage';
import { AdminAuditPage } from '../pages/admin/AdminAuditPage';
import { AdminUsersPage } from '../pages/admin/AdminUsersPage';
import { AdminAccountsPage } from '../pages/admin/AdminAccountsPage';
import { AdminSystemConfigsPage } from '../pages/admin/AdminSystemConfigsPage';
import { AdminNotificationTemplatesPage } from '../pages/admin/AdminNotificationTemplatesPage';
import { AdminOrdersPage } from '../pages/admin/AdminOrdersPage';
import { AdminAIPage } from '../pages/admin/AdminAIPage';
import { AdminCatalogPage } from '../pages/admin/AdminCatalogPage';
import { AdminArticlesPage } from '../pages/admin/AdminArticlesPage';
import { AdminCareTasksPage } from '../pages/admin/AdminCareTasksPage';
import { AdminBoardingPage } from '../pages/admin/AdminBoardingPage';
import { AdminKennelsPage } from '../pages/admin/AdminKennelsPage';
import { AdminFeedbacksPage } from '../pages/admin/AdminFeedbacksPage';
import { AdminPageContentsPage } from '../pages/admin/AdminPageContentsPage';
import { AdminCustomersPage } from '../pages/admin/AdminCustomersPage';
import { AdminBranchesPage } from '../pages/admin/AdminBranchesPage';
import { StaffProfilePage } from '../pages/admin/StaffProfilePage';
import { StaffNotificationsPage } from '../pages/admin/StaffNotificationsPage';
import { AdminBranchDetailPage } from '../pages/admin/AdminBranchDetailPage';

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 60_000, refetchOnWindowFocus: false } },
});

// Đổi người dùng trong cùng tab (đăng nhập, đăng xuất, hết phiên) thì bỏ dữ liệu đã tải của người trước,
// nếu không người sau thấy dữ liệu theo quyền của người trước tới khi cache hết hạn.
useSession.subscribe((state, previous) => {
  if (state.account?.id !== previous.account?.id) queryClient.clear();
});

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

/**
 * Trang cũ có dữ liệu cứng (T49): việc đã nằm ở nơi khác nên route chỉ còn chuyển hướng. Mỗi vai trò đi tới đích của nó;
 * vai trò không có đích về trang đầu của chính nó (homePathFor) thay vì vào trang 403 vô nghĩa.
 */
function RedirectByRole({ targets }: { targets: Partial<Record<Role, string>> }) {
  const role = useAccount()?.role;
  return <Navigate to={role ? (targets[role] ?? homePathFor(role)) : ROUTES.staffLogin} replace />;
}

/** Mọi vai trò được vào bàn `workspace` (WORKSPACE_ROLES) đi tới bàn đó. */
const toDesk = (workspace: Exclude<Workspace, 'admin'>): Partial<Record<Role, string>> =>
  Object.fromEntries(WORKSPACE_ROLES[workspace].map((role) => [role, WORKSPACE_PATHS[workspace]]));

const TO_RECEPTION = toDesk('reception');
const TO_DOCTOR = toDesk('doctor');
const TO_GROOMING = toDesk('grooming');
/** UC70 (thu tiền, lễ tân) làm ở bàn Lễ tân; UC72 (đối soát ca, BRANCH_MANAGER) ở trang Ca thu ngân. v16 không có hóa đơn. */
const TO_CASHIER: Partial<Record<Role, string>> = { ...TO_RECEPTION, BRANCH_MANAGER: '/admin/payments' };

/** Trang /admin/*: role theo ADMIN_MENU (rolesForPath); 403 hiện trong khung, thanh bên vẫn còn. */
function AdminGuard() {
  const { pathname } = useLocation();
  return <RequireRole roles={rolesForPath(pathname)} loginPath={ROUTES.staffLogin} />;
}

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
          {/* UC17: bài viết công khai theo slug (bài DRAFT / HIDDEN / slug lạ cùng một 404, BR-BV-03) */}
          <Route path="/news/:slug" element={<ArticleDetailPage />} />
          {/* UC15: dịch vụ, chi nhánh, bác sĩ công khai, không cần đăng nhập */}
          <Route path="/services" element={<ServicesPage />} />
          <Route path="/branches" element={<BranchesPage />} />
          <Route path="/doctors" element={<DoctorsPage />} />
          <Route path="/help" element={<HelpPage />} />
          <Route path="/terms" element={<TermsPage />} />
          <Route path="/privacy" element={<PrivacyPage />} />

          {/* Mọi role đã đăng nhập: hồ sơ cá nhân (UC06; nhân viên chuyển sang /admin/profile), đổi mật khẩu (UC05) */}
          <Route element={<RequireRole roles={ALL_ROLES} />}>
            <Route path="/account/:section?" element={<AccountPage />} />
            <Route path="/security" element={<SecurityPage />} />
          </Route>

          {/* Khu khách hàng (A02) */}
          <Route element={<RequireRole roles={['CUSTOMER']} />}>
            {/* Còn cờ chờ quyết định liên kết: ẩn thú cưng, đặt lịch / lưu trú, feedback (BR-TK-19) */}
            <Route element={<RequireLinkDecision />}>
              <Route path="/booking" element={<BookingPage />} />
              <Route path="/pets" element={<PetsPage />} />
              <Route path="/review" element={<ReviewsPage />} />
              {/* UC58: đặt chỗ lưu trú cần BR-TK-19; danh sách / chi tiết ở ngoài như /appointments. Đường dẫn là link BE dự kiến trong thông báo (ADR-0065). */}
              <Route path="/boarding-bookings/new" element={<BoardingNewPage />} />
            </Route>
            <Route path="/appointments" element={<AppointmentsPage />} />
            <Route path="/boarding-bookings" element={<BoardingBookingsPage />} />
            <Route path="/boarding-bookings/:bookingId" element={<BoardingDetailPage />} />
            <Route path="/notifications" element={<NotificationsPage />} />
            <Route path="/settings" element={<SettingsPage />} />
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

        {/* Khu nhân viên: một khung StaffShell cho bàn làm việc và trang quản trị */}
        <Route path="/staff" element={staffOnly(STAFF_ROLES, <StaffHome />)} />
        <Route path="/admin" element={staffOnly(STAFF_ROLES, <StaffHome />)} />
        <Route path={ROUTES.staffLogin} element={<AdminLoginPage />} />
        <Route element={staffOnly(STAFF_ROLES, <StaffShell />)}>
          <Route path={WORKSPACE_PATHS.reception} element={staffOnly(WORKSPACE_ROLES.reception, <ReceptionWorkspacePage />)} />
          <Route path={WORKSPACE_PATHS.doctor} element={staffOnly(WORKSPACE_ROLES.doctor, <DoctorWorkspacePage />)} />
          <Route path={WORKSPACE_PATHS.grooming} element={staffOnly(WORKSPACE_ROLES.grooming, <GroomingWorkspacePage />)} />
          <Route element={<AdminGuard />}>
            <Route path="/admin/dashboard" element={<AdminDashboardPage />} />
            <Route path="/admin/profile" element={<StaffProfilePage />} />
            <Route path="/admin/notifications" element={<StaffNotificationsPage />} />
            <Route path="/admin/catalog/:tab?" element={<AdminCatalogPage />} />
            <Route path="/admin/page-contents" element={<AdminPageContentsPage />} />
            <Route path="/admin/articles/:tab?" element={<AdminArticlesPage />} />
            <Route path="/admin/feedbacks" element={<AdminFeedbacksPage />} />
            <Route path="/admin/care-tasks" element={<AdminCareTasksPage />} />
            <Route path="/admin/boarding" element={<AdminBoardingPage />} />
            <Route path="/admin/kennels" element={<AdminKennelsPage />} />
            <Route path="/admin/customers" element={<AdminCustomersPage />} />
            <Route path="/admin/branches" element={<AdminBranchesPage />} />
            <Route path="/admin/branches/:branchId/:tab?" element={<AdminBranchDetailPage />} />
            <Route path="/admin/pos" element={<AdminPOSPage />} />
            <Route path="/admin/appointments" element={<AdminAppointmentsPage />} />
            {/* Hàng chờ, khám, tiêm chủng, grooming cũ: đã có bàn làm việc (T36, T37), gỡ mục menu ẩn. */}
            <Route path="/admin/queue" element={<RedirectByRole targets={TO_RECEPTION} />} />
            <Route path="/admin/exam" element={<RedirectByRole targets={TO_DOCTOR} />} />
            <Route path="/admin/vaccination" element={<RedirectByRole targets={TO_DOCTOR} />} />
            <Route path="/admin/grooming" element={<RedirectByRole targets={TO_GROOMING} />} />
            <Route path="/admin/orders" element={<AdminOrdersPage />} />
            <Route path="/admin/invoices" element={<RedirectByRole targets={TO_CASHIER} />} />
            <Route path="/admin/payments" element={<AdminPaymentsPage />} />
            <Route path="/admin/refunds" element={<AdminRefundsPage />} />
            <Route path="/admin/promotions" element={<AdminPromotionsPage />} />
            <Route path="/admin/membership" element={<AdminMembershipPage />} />
            <Route path="/admin/warehouse/:tab?" element={<AdminWarehousePage />} />
            {/* Trang mua hàng / vaccine cũ (mô hình đơn mua trước v16): chuyển sang tab kho tương ứng, gỡ ở T57. */}
            <Route path="/admin/purchasing" element={<Navigate to="/admin/warehouse/receipts" replace />} />
            <Route path="/admin/vaccines" element={<Navigate to="/admin/warehouse/stock?productType=VACCINE" replace />} />
            <Route path="/admin/workforce" element={<AdminWorkforcePage />} />
            <Route path="/admin/incidents" element={<AdminIncidentsPage />} />
            <Route path="/admin/reports" element={<AdminReportsPage />} />
            <Route path="/admin/audit" element={<AdminAuditPage />} />
            <Route path="/admin/users" element={<AdminUsersPage />} />
            <Route path="/admin/accounts" element={<AdminAccountsPage />} />
            <Route path="/admin/system-configs" element={<AdminSystemConfigsPage />} />
            <Route path="/admin/notification-templates" element={<AdminNotificationTemplatesPage />} />
            <Route path="/admin/ai" element={<AdminAIPage />} />
          </Route>
        </Route>
      </Routes>
      <GlobalModal />
      <Toaster position="top-right" richColors />
    </QueryClientProvider>
  );
}

export default App;
