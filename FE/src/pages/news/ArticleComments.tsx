import { useReducer, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { MessageCircle } from 'lucide-react'
import { plainMessage, toApiError } from '@/shared/api/api-error'
import { articlexApi } from '@/shared/api/articlex.api'
import { useArticlexMutation, usePublicComments } from '@/shared/hooks/useArticlex'
import { useAccount } from '@/shared/stores/session.store'
import { CommentEditor, CommentItem, FOCUS, GHOST } from './CommentItem'

// UC82: khối bình luận dưới bài viết. Khách (CUSTOMER) đăng / sửa / xóa bình luận của mình; SUPER_MANAGER ẩn và bỏ ẩn (UC81 mở rộng); ẩn danh chỉ đọc.

const PAGE_SIZE = 10

// PublicComment không có cờ "của tôi" (hợp đồng articlex-v1 #9): nhớ các commentId đã đăng ở trình duyệt này, theo từng tài khoản.
const mineKey = (accountId: number) => `petcare-my-comments:${accountId}`
function readMine(accountId: number | null): number[] {
  if (accountId == null) return []
  try {
    const ids = JSON.parse(localStorage.getItem(mineKey(accountId)) ?? '[]') as unknown
    return Array.isArray(ids) ? ids.filter(Number.isInteger) : []
  } catch {
    return []
  }
}
function writeMine(accountId: number, ids: number[]) {
  try {
    localStorage.setItem(mineKey(accountId), JSON.stringify(ids))
  } catch {
    // Không ghi được (chế độ riêng tư): chỉ mất nút sửa / xóa sau khi tải lại.
  }
}

/** `unhideId`: bình luận vừa ẩn, cho SUPER_MANAGER hoàn tác ngay (#9 không liệt kê bình luận đã ẩn nên đây là đường bỏ ẩn duy nhất trên trang này). */
type Notice = { kind: 'success' | 'error'; text: string; unhideId?: number } | null

export function ArticleComments({ articleId, slug }: { articleId: number; slug: string }) {
  const account = useAccount()
  const { pathname } = useLocation()
  const [page, setPage] = useState(0)
  const [notice, setNotice] = useState<Notice>(null)
  const [, refreshMine] = useReducer((n: number) => n + 1, 0)
  const list = usePublicComments(slug, { page, size: PAGE_SIZE })
  const create = useArticlexMutation((content: string) => articlexApi.createComment(articleId, content))
  const update = useArticlexMutation((v: { id: number; content: string }) => articlexApi.updateComment(v.id, v.content))
  const remove = useArticlexMutation((id: number) => articlexApi.deleteComment(id))
  const hide = useArticlexMutation((v: { id: number; reason: string }) => articlexApi.hideComment(v.id, v.reason))
  const unhide = useArticlexMutation((id: number) => articlexApi.unhideComment(id))
  const busy = create.isPending || update.isPending || remove.isPending || hide.isPending || unhide.isPending

  const isCustomer = account?.role === 'CUSTOMER'
  const moderator = account?.role === 'SUPER_MANAGER'
  const mineIds = readMine(isCustomer ? account.id : null)

  /** Chạy một lệnh ghi, hiện kết quả ngay trong khối; trả true khi thành công. */
  const run = async (action: () => Promise<unknown>, success: string, unhideId?: number): Promise<boolean> => {
    setNotice(null)
    try {
      await action()
      setNotice({ kind: 'success', text: success, unhideId })
      return true
    } catch (e) {
      setNotice({ kind: 'error', text: plainMessage(toApiError(e)) })
      return false
    }
  }

  const post = async (content: string) => {
    const total = list.data?.totalElements ?? 0
    const ok = await run(async () => {
      const saved = await create.mutateAsync(content)
      writeMine(account!.id, [...mineIds, saved.commentId])
      refreshMine()
    }, 'Đã đăng bình luận.')
    if (ok) setPage(Math.floor(total / PAGE_SIZE)) // bình luận mới nằm cuối (cũ nhất trước)
    return ok
  }

  const data = list.data
  const notAvailable = list.isError && toApiError(list.error).statusCode === 404

  return (
    <section aria-labelledby="comments-heading" className="mt-12 border-t border-(--color-border-default) pt-8">
      <h2 id="comments-heading" className="flex items-center gap-2 font-friendly text-[22px] font-extrabold text-(--color-surface-dark)">
        <MessageCircle size={20} aria-hidden /> Bình luận{data ? ` (${data.totalElements})` : ''}
      </h2>

      {isCustomer ? (
        <div className="mt-4">
          <CommentEditor label="Viết bình luận" submitText="Đăng bình luận" busy={busy} onSubmit={post} />
        </div>
      ) : !account ? (
        <p className="mt-4 rounded-xl bg-(--color-surface-1) px-4 py-3 text-[14px] text-(--color-text-secondary)">
          <Link to="/auth/login" state={{ from: pathname }} className={`font-bold text-accent hover:text-accent-hover ${FOCUS}`}>
            Đăng nhập
          </Link>{' '}
          để bình luận.
        </p>
      ) : null}

      {notice && (
        <div
          role={notice.kind === 'error' ? 'alert' : 'status'}
          className={`mt-4 flex flex-wrap items-center gap-3 rounded-xl px-4 py-2.5 text-[13.5px] font-medium ${notice.kind === 'error' ? 'bg-red-50 text-red-700' : 'bg-green-50 text-green-700'}`}
        >
          <span>{notice.text}</span>
          {notice.unhideId !== undefined && moderator && (
            <button
              type="button"
              className={GHOST}
              disabled={busy}
              onClick={() => void run(() => unhide.mutateAsync(notice.unhideId!), `Đã bỏ ẩn bình luận #${notice.unhideId}.`)}
            >
              Bỏ ẩn
            </button>
          )}
        </div>
      )}

      {notAvailable ? (
        <p className="mt-6 text-[14px] text-(--color-text-secondary)">Bài viết này chưa mở bình luận.</p>
      ) : list.isError ? (
        <div role="alert" className="mt-6 flex items-center gap-3 text-[14px] text-red-700">
          {plainMessage(toApiError(list.error))}
          <button type="button" className={GHOST} onClick={() => void list.refetch()}>
            Thử lại
          </button>
        </div>
      ) : !data ? (
        <p role="status" className="mt-6 text-[14px] text-(--color-text-secondary)">
          Đang tải bình luận…
        </p>
      ) : data.content.length === 0 ? (
        <p className="mt-6 text-[14px] text-(--color-text-secondary)">Chưa có bình luận nào.</p>
      ) : (
        <ul className="mt-6 space-y-3">
          {data.content.map((c) => (
            <CommentItem
              key={c.commentId}
              comment={c}
              mine={mineIds.includes(c.commentId)}
              moderator={moderator}
              busy={busy}
              onEdit={(id, content) => run(() => update.mutateAsync({ id, content }), 'Đã lưu bình luận.')}
              onDelete={(id) =>
                void run(async () => {
                  await remove.mutateAsync(id)
                  writeMine(account!.id, mineIds.filter((x) => x !== id))
                  refreshMine()
                }, 'Đã xóa bình luận.')
              }
              onHide={(id, reason) => run(() => hide.mutateAsync({ id, reason }), `Đã ẩn bình luận #${id}. Cần bỏ ẩn sau này: tab Chờ duyệt ở Quản lý bài viết (nhập mã bình luận).`, id)}
            />
          ))}
        </ul>
      )}

      {data && data.totalPages > 1 && (
        <div className="mt-4 flex items-center justify-end gap-3 text-[13px] text-(--color-text-secondary)">
          <span>
            Trang {data.page + 1}/{data.totalPages}
          </span>
          <button type="button" className={GHOST} disabled={data.page === 0} onClick={() => setPage(data.page - 1)}>
            Trước
          </button>
          <button type="button" className={GHOST} disabled={data.page + 1 >= data.totalPages} onClick={() => setPage(data.page + 1)}>
            Sau
          </button>
        </div>
      )}
    </section>
  )
}
