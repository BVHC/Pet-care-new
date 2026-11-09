import { Link } from 'react-router-dom'
import { PawPrint } from 'lucide-react'
import { homePathFor } from '@/shared/constants/workspaces'
import { useAccount } from '@/shared/stores/session.store'

const CARD =
  'rounded-2xl border border-(--color-border-default) bg-(--color-surface-card) shadow-[0_1px_2px_rgba(56,36,23,0.06),0_8px_18px_-14px_rgba(56,36,23,0.4)]'
const BUTTON =
  'inline-flex min-h-10 items-center justify-center gap-2 rounded-full bg-accent px-5 text-[13.5px] font-bold text-white transition-colors hover:bg-accent-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent'

/** URL không khớp route nào (gồm các trang đã gỡ ở T57 Bước C). Nhân viên về trang đầu của vai trò, còn lại về trang chủ. */
export function NotFoundPage() {
  const account = useAccount()
  return (
    <div className="mx-auto max-w-[620px] px-5 py-20 sm:px-8">
      <div className={`${CARD} px-6 py-12 text-center`}>
        <span className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-(--color-accent-soft) text-accent">
          <PawPrint size={22} aria-hidden />
        </span>
        <h1 className="mt-4 font-friendly text-[26px] font-extrabold text-(--color-surface-dark)">Không tìm thấy trang</h1>
        <p className="mt-2 text-[14px] text-(--color-dark-gray)/70">Địa chỉ này không tồn tại hoặc đã được gỡ.</p>
        <Link to={account ? homePathFor(account.role) : '/'} className={`${BUTTON} mt-6`}>
          Về trang chủ
        </Link>
      </div>
    </div>
  )
}
