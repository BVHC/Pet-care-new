import { Routes, Route, Navigate } from 'react-router-dom'
import { router } from './router'

function App() {
  return (
    <Routes>
      {router.map((route) => (
        <Route key={route.path} {...route} />
      ))}
      <Route path="/" element={<Navigate to="/auth/login" replace />} />
    </Routes>
  )
}

export default App
