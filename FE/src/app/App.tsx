import { Routes, Route } from 'react-router-dom';
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
import { AboutPage } from '../pages/about/AboutPage';
import { RecommendPage } from '../pages/recommend/RecommendPage';
import { HotelPage } from '../pages/hotel/HotelPage';
import { LoginPage } from '../pages/auth/LoginPage';
import { RegisterPage } from '../pages/auth/RegisterPage';
import { VerifyOtpPage } from '../pages/auth/VerifyOtpPage';
import { ForgotPasswordPage } from '../pages/auth/ForgotPasswordPage';
import { TermsPage, PrivacyPage } from '../pages/legal/LegalPage';
import { NewsPage } from '../pages/news/NewsPage';
import { ReviewPage } from '../pages/review/ReviewPage';

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
          <Route path="/about" element={<AboutPage />} />
          <Route path="/recommend" element={<RecommendPage />} />
          <Route path="/hotel" element={<HotelPage />} />
          <Route path="/news" element={<NewsPage />} />
          <Route path="/review" element={<ReviewPage />} />
          <Route path="/terms" element={<TermsPage />} />
          <Route path="/privacy" element={<PrivacyPage />} />
        </Route>

        {/* Auth Routes (without header/footer layout) */}
        <Route path="/auth/login" element={<LoginPage />} />
        <Route path="/auth/register" element={<RegisterPage />} />
        <Route path="/auth/verify-otp" element={<VerifyOtpPage />} />
        <Route path="/auth/forgot" element={<ForgotPasswordPage />} />
      </Routes>
      <GlobalModal />
      <Toaster position="top-right" richColors />
    </QueryClientProvider>
  );
}

export default App;
