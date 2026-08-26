// ===========================================
// App.tsx - Main Application Entry
// Routes sẽ được implement trong Week 1
// ===========================================

import { Routes, Route, Navigate } from 'react-router-dom'

function App() {
  return (
    <Routes>
      {/* Auth routes - Week 1 */}
      <Route path="/auth/login" element={<div>Login - Week 1</div>} />
      <Route path="/auth/register" element={<div>Register - Week 1</div>} />

      {/* Admin routes - Week 1 */}
      <Route path="/admin" element={<div>Dashboard - Week 1</div>} />

      {/* Staff routes - Week 1 */}
      <Route path="/staff/appointments" element={<div>Appointments - Week 1</div>} />
      <Route path="/staff/clinical" element={<div>Clinical - Week 1</div>} />

      {/* Customer routes - Week 1 */}
      <Route path="/customer/pets" element={<div>Pets - Week 1</div>} />
      <Route path="/customer/shop" element={<div>Shop - Week 1</div>} />
      <Route path="/customer/cart" element={<div>Cart - Week 1</div>} />

      {/* Default redirect */}
      <Route path="/" element={<Navigate to="/auth/login" replace />} />
    </Routes>
  )
}

export default App
