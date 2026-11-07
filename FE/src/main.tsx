import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './app/App'
import './index.css'
import { API_MODE } from './shared/api/axios'

// Chỉ lúc dev: CSS ở index.css hiện nhãn BE THẬT / MOCK / HỖN HỢP theo thuộc tính này. Build thật không có nhãn.
if (import.meta.env.DEV) document.documentElement.dataset.api = API_MODE

// QueryClient do App tạo (staleTime, retry: shouldRetry); trước đây ở đây còn một QueryClient `retry: 1` bị App đè nên vô dụng (L-15).
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </React.StrictMode>,
)
