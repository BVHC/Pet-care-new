// Nguồn path duy nhất cho toàn app. Thêm route mới → khai báo ở đây trước.

export const ROUTES = {
  // public
  home: '/',
  login: '/auth/login',
  register: '/auth/register',
  verifyOtp: '/auth/verify-otp',
  forgotPassword: '/auth/forgot',
  shop: '/shop',
  productDetail: '/shop/:productId',
  cart: '/cart',
  checkout: '/checkout',
  booking: '/booking',
  hotel: '/hotel',
  recommend: '/recommend',
  about: '/about',
  news: '/news',
  newsDetail: '/news/:newsId',
  terms: '/terms',
  privacy: '/privacy',
  favorites: '/favorites',
  notifications: '/notifications',
  payment: '/payment',
  security: '/security',
  settings: '/settings',
  help: '/help',

  // customer (cần đăng nhập)
  account: '/account',
  orderConfirm: '/order/:orderId',
  orders: '/orders',
  petProfile: '/pets',
  appointments: '/appointments',
  packages: '/packages',
  review: '/review',
  membership: '/membership',
  caregivers: '/caregivers',
  vouchers: '/vouchers',

  // staff
  reception: '/staff/reception',
  doctor: '/staff/doctor',

  // admin
  admin: '/admin',
} as const;

export type RouteKey = keyof typeof ROUTES;

// Bảng ánh xạ nav-key cũ (SiteHeader/Footer dùng onNav) → path router.
export const NAV_TO_PATH: Record<string, string> = {
  home: ROUTES.home,
  listing: ROUTES.shop,
  recommend: ROUTES.recommend,
  booking: ROUTES.booking,
  hotel: ROUTES.hotel,
  about: ROUTES.about,
  news: ROUTES.news,
  cart: ROUTES.cart,
  login: ROUTES.login,
  signup: ROUTES.register,
  register: ROUTES.register,
  verifyotp: ROUTES.verifyOtp,
  forgot: ROUTES.forgotPassword,
  forgotpassword: ROUTES.forgotPassword,
  terms: ROUTES.terms,
  privacy: ROUTES.privacy,
  account: ROUTES.account,
  orders: ROUTES.orders,
  pets: ROUTES.petProfile,
  petprofile: ROUTES.petProfile,
  appointments: ROUTES.appointments,
  packages: ROUTES.packages,
  review: ROUTES.review,
  notifications: ROUTES.notifications,
  favorites: ROUTES.favorites,
  payment: ROUTES.payment,
  security: ROUTES.security,
  settings: ROUTES.settings,
  help: ROUTES.help,
  membership: ROUTES.membership,
  caregivers: ROUTES.caregivers,
  vouchers: ROUTES.vouchers,
};
