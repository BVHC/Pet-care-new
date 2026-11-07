import { useState } from 'react'
import { EyeOff, Pencil, Trash2 } from 'lucide-react'
import { ARTICLEX_LIMITS, type PublicComment } from '@/shared/types/articlex'
import { formatPublishedDate } from '@/shared/utils/public-format'

// UC82: một bình luận và ô soạn bình luận (dùng chung cho đăng mới và sửa).

export const FOCUS = 'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent'
const BTN = `inline-flex min-h-9 items-center justify-center gap-1.5 rounded-full px-4 text-[13px] font-bold transition-colors disabled:opacity-60 ${FOCUS}`
export const PRIMARY = `${BTN} bg-accent text-white hover:bg-accent-hover`
export const GHOST = `${BTN} border border-(--color-border-default) bg-white text-(--color-text-primary) hover:bg-(--color-surface-1)`
const TEXTAREA =
  'w-full rounded-xl border border-(--color-border-default) bg-white p-3 text-[14px] leading-relaxed text-(--color-text-primary) focus:outline-2 focus:outline-accent'

/** Ô nhập; đếm ký tự theo giới hạn BR-BV-08. */
export function CommentEditor({
  label,
  initial = '',
  submitText,
  busy,
  onSubmit,
  onCancel,
}: {
  label: string
  initial?: string
  submitText: string
  busy: boolean
  /** Trả true khi đã lưu: ô nhập mới (không có `initial`) được xóa trắng. */
  onSubmit: (content: string) => Promise<unknown>
  onCancel?: () => void
}) {
  const [text, setText] = useState(initial)
  return (
    <form
      className="space-y-2"
      onSubmit={async (e) => {
        e.preventDefault()
        if ((await onSubmit(text.trim())) === true && !initial) setText('')
      }}
    >
      <textarea aria-label={label} className={TEXTAREA} rows={3} maxLength={ARTICLEX_LIMITS.comment} value={text} onChange={(e) => setText(e.target.value)} />
      <div className="flex items-center justify-between gap-3">
        <span className="text-[12px] text-(--color-text-secondary)">
          {text.length}/{ARTICLEX_LIMITS.comment}
        </span>
        <div className="flex gap-2">
          {onCancel && (
            <button type="button" className={GHOST} onClick={onCancel} disabled={busy}>
              Hủy
            </button>
          )}
          <button type="submit" className={PRIMARY} disabled={busy || !text.trim()}>
            {submitText}
          </button>
        </div>
      </div>
    </form>
  )
}

/** `mine`: bình luận do chính khách đang đăng nhập đăng (sửa / xóa); `moderator`: SUPER_MANAGER (ẩn, kèm lý do bắt buộc, BR-BV-08). */
export function CommentItem({
  comment,
  mine,
  moderator,
  busy,
  onEdit,
  onDelete,
  onHide,
}: {
  comment: PublicComment
  mine: boolean
  moderator: boolean
  busy: boolean
  onEdit: (id: number, content: string) => Promise<boolean>
  onDelete: (id: number) => void
  onHide: (id: number, reason: string) => Promise<boolean>
}) {
  const [mode, setMode] = useState<'view' | 'edit' | 'delete' | 'hide'>('view')
  const [reason, setReason] = useState('')
  return (
    <li className="rounded-2xl border border-(--color-border-default) bg-white p-4">
      <div className="flex flex-wrap items-center gap-x-3 text-[13px]">
        <span className="font-bold text-(--color-text-primary)">{comment.authorName}</span>
        <time dateTime={comment.createdAt} className="text-(--color-text-secondary)">
          {formatPublishedDate(comment.createdAt)}
        </time>
        {comment.editedAt && <span className="text-(--color-text-secondary)">(đã sửa)</span>}
        {moderator && <span className="text-(--color-text-secondary)">Mã #{comment.commentId}</span>}
      </div>

      {mode === 'edit' ? (
        <div className="mt-2">
          <CommentEditor
            label="Sửa bình luận"
            initial={comment.content}
            submitText="Lưu"
            busy={busy}
            onSubmit={async (content) => (await onEdit(comment.commentId, content)) && setMode('view')}
            onCancel={() => setMode('view')}
          />
        </div>
      ) : (
        <p className="mt-2 whitespace-pre-line text-[14px] leading-relaxed text-(--color-text-primary)">{comment.content}</p>
      )}

      {mode === 'view' && (mine || moderator) && (
        <div className="mt-3 flex flex-wrap gap-2">
          {mine && (
            <>
              <button type="button" className={GHOST} onClick={() => setMode('edit')} disabled={busy}>
                <Pencil size={13} aria-hidden /> Sửa
              </button>
              <button type="button" className={GHOST} onClick={() => setMode('delete')} disabled={busy}>
                <Trash2 size={13} aria-hidden /> Xóa
              </button>
            </>
          )}
          {moderator && (
            <button type="button" className={GHOST} onClick={() => setMode('hide')} disabled={busy}>
              <EyeOff size={13} aria-hidden /> Ẩn
            </button>
          )}
        </div>
      )}

      {mode === 'delete' && (
        <div className="mt-3 flex flex-wrap items-center gap-2 text-[13px]">
          <span>Xóa bình luận này?</span>
          <button type="button" className={PRIMARY} onClick={() => onDelete(comment.commentId)} disabled={busy}>
            Xác nhận xóa
          </button>
          <button type="button" className={GHOST} onClick={() => setMode('view')}>
            Giữ lại
          </button>
        </div>
      )}

      {mode === 'hide' && (
        <form
          className="mt-3 space-y-2"
          onSubmit={async (e) => {
            e.preventDefault()
            if (await onHide(comment.commentId, reason.trim())) setMode('view')
          }}
        >
          <input
            aria-label="Lý do ẩn"
            className={TEXTAREA}
            maxLength={ARTICLEX_LIMITS.hideReason}
            placeholder="Lý do ẩn bình luận (bắt buộc)"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
          <div className="flex gap-2">
            <button type="submit" className={PRIMARY} disabled={busy || !reason.trim()}>
              Xác nhận ẩn
            </button>
            <button type="button" className={GHOST} onClick={() => setMode('view')}>
              Hủy
            </button>
          </div>
        </form>
      )}
    </li>
  )
}
