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
import { TermsPage, PrivacyPage } from '../pages/legal/LegalPage';
import { NewsPage } from '../pages/news/NewsPage';

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

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 60_000, refetchOnWindowFocus: false } },
});

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <Routes>
        {/* Public Layout */}
        <Route element={<PublicLayout />}>
          <Route path="/" element={<HomePage />} />
          <Route path="/shop" element={<ShopPage />} />
          <Route path="/shop/:id" element={<ProductDetailPage />} />
          <Route path="/cart" element={<CartPage />} />
          <Route path="/checkout" element={<CheckoutPage />} />
          <Route path="/order/:id" element={<OrderConfirmationPage />} />
          <Route path="/booking" element={<BookingPage />} />
          <Route path="/pets" element={<PetsPage />} />
          <Route path="/account" element={<AccountPage />} />
          <Route path="/orders" element={<OrderHistoryPage />} />
          <Route path="/favorites" element={<FavoritesPage />} />
          <Route path="/notifications" element={<NotificationsPage />} />
          <Route path="/payment" element={<PaymentPage />} />
          <Route path="/security" element={<SecurityPage />} />
          <Route path="/settings" element={<SettingsPage />} />
          <Route path="/help" element={<HelpPage />} />
          <Route path="/membership" element={<MembershipPage />} />
          <Route path="/caregivers" element={<CaregiversPage />} />
          <Route path="/vouchers" element={<VouchersPage />} />
          <Route path="/appointments" element={<AppointmentsPage />} />
          <Route path="/packages" element={<PackagesPage />} />
          <Route path="/review" element={<ReviewsPage />} />
          <Route path="/about" element={<AboutPage />} />
          <Route path="/recommend" element={<RecommendPage />} />
          <Route path="/hotel" element={<HotelPage />} />
          <Route path="/news" element={<NewsPage />} />
          <Route path="/terms" element={<TermsPage />} />
          <Route path="/privacy" element={<PrivacyPage />} />
        </Route>

        {/* Auth Routes (without header/footer layout) */}
        <Route path="/auth/login" element={<LoginPage />} />
        <Route path="/auth/register" element={<RegisterPage />} />
        <Route path="/auth/verify-otp" element={<VerifyOtpPage />} />
        <Route path="/auth/forgot" element={<ForgotPasswordPage />} />

        {/* Admin Routes */}
        <Route path="/admin" element={<Navigate to="/admin/dashboard" replace />} />
        <Route path="/staff" element={<Navigate to="/admin/dashboard" replace />} />
        <Route path="/admin/login" element={<AdminLoginPage />} />
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
        </Route>
      </Routes>
      <GlobalModal />
      <Toaster position="top-right" richColors />
    </QueryClientProvider>
  );
}

export default App;
