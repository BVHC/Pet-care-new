import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './app/App'
import './index.css'

// QueryClient do App tạo (staleTime, retry: shouldRetry); trước đây ở đây còn một QueryClient `retry: 1` bị App đè nên vô dụng (L-15).
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </React.StrictMode>,
)
