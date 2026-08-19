import { RouteObject } from 'react-router-dom'

// Lazy load pages
const LoginPage = () => import('@/pages/auth/LoginPage')
const RegisterPage = () => import('@/pages/auth/RegisterPage')
const DashboardPage = () => import('@/pages/admin/DashboardPage')
const AppointmentsPage = () => import('@/pages/staff/AppointmentsPage')
const PetsPage = () => import('@/pages/customer/PetsPage')
const ShopPage = () => import('@/pages/customer/ShopPage')
const CartPage = () => import('@/pages/customer/CartPage')
const ClinicalPage = () => import('@/pages/staff/ClinicalPage')

export const router: RouteObject[] = [
  // Auth
  {
    path: '/auth/login',
    element: <LoginPage />,
  },
  {
    path: '/auth/register',
    element: <RegisterPage />,
  },

  // Admin
  {
    path: '/admin',
    element: <DashboardPage />,
  },

  // Staff
  {
    path: '/staff/appointments',
    element: <AppointmentsPage />,
  },
  {
    path: '/staff/clinical',
    element: <ClinicalPage />,
  },

  // Customer
  {
    path: '/customer/pets',
    element: <PetsPage />,
  },
  {
    path: '/customer/shop',
    element: <ShopPage />,
  },
  {
    path: '/customer/cart',
    element: <CartPage />,
  },
]
