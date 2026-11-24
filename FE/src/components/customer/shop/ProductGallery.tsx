import { useRef, useState, type KeyboardEvent } from 'react'
import { ChevronLeft, ChevronRight, PawPrint } from 'lucide-react'
import { uniqueUrls } from '@/shared/utils/gallery'

const FOCUS = 'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent'
const FRAME = 'relative aspect-square overflow-hidden rounded-2xl border border-(--color-border-default) bg-(--color-surface-1)'
const ARROW = `absolute top-1/2 grid h-10 w-10 -translate-y-1/2 place-items-center rounded-full bg-white/90 text-(--color-surface-dark) shadow-md transition-colors hover:bg-white ${FOCUS}`

/**
 * Ảnh sản phẩm: ảnh lớn, nút Trước / Sau và dải ảnh nhỏ khi có từ hai ảnh tốt trở lên. `urls` là [imageUrl, ...galleryUrls];
 * rỗng và trùng bị bỏ. Ảnh tải lỗi bị ẩn khỏi dải và bị bỏ qua khi chuyển; hết ảnh tốt thì hiện khung dự phòng.
 * Chuyển ảnh vòng lại ở hai đầu (nút và phím mũi tên trái / phải trên ảnh nhỏ).
 */
export function ProductGallery({ name, urls }: { name: string; urls: readonly (string | null | undefined)[] }) {
  const all = uniqueUrls(urls)
  const [broken, setBroken] = useState<ReadonlySet<string>>(new Set())
  const [selected, setSelected] = useState<string | null>(null)
  const thumbs = useRef(new Map<string, HTMLButtonElement>())

  const good = all.filter((u) => !broken.has(u))
  // Ảnh đang chọn mà hỏng thì lấy ảnh tốt kế tiếp theo thứ tự gốc; không còn thì lùi về ảnh tốt cuối.
  const from = Math.max(all.indexOf(selected ?? ''), 0)
  const current = good.find((u) => all.indexOf(u) >= from) ?? good[good.length - 1]
  const markBroken = (url: string) => setBroken((prev) => new Set(prev).add(url))

  if (!current) {
    return (
      <div aria-hidden className={`${FRAME} grid place-items-center`}>
        <PawPrint size={88} strokeWidth={1.25} className="text-accent/25" />
      </div>
    )
  }

  const step = (origin: string, delta: number, focus = false) => {
    const next = good[(good.indexOf(origin) + delta + good.length) % good.length]
    setSelected(next)
    if (focus) thumbs.current.get(next)?.focus()
  }
  const onThumbKey = (e: KeyboardEvent, url: string) => {
    if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return
    e.preventDefault()
    step(url, e.key === 'ArrowRight' ? 1 : -1, true)
  }

  const many = good.length > 1
  return (
    <div>
      <div className={FRAME}>
        <img
          key={current}
          src={current}
          alt={name}
          onError={() => markBroken(current)}
          className="h-full w-full object-contain p-4 mix-blend-multiply"
        />
        {many && (
          <>
            <button type="button" aria-label="Ảnh trước" onClick={() => step(current, -1)} className={`${ARROW} left-3`}>
              <ChevronLeft size={20} aria-hidden />
            </button>
            <button type="button" aria-label="Ảnh sau" onClick={() => step(current, 1)} className={`${ARROW} right-3`}>
              <ChevronRight size={20} aria-hidden />
            </button>
          </>
        )}
      </div>

      {many && (
        <div role="group" aria-label="Ảnh sản phẩm" className="mt-3 flex flex-wrap gap-2">
          {good.map((url, i) => (
            <button
              key={url}
              ref={(el) => {
                if (el) thumbs.current.set(url, el)
                else thumbs.current.delete(url)
              }}
              type="button"
              aria-label={`Xem ảnh ${i + 1} trên ${good.length}`}
              aria-current={url === current ? 'true' : undefined}
              onClick={() => setSelected(url)}
              onKeyDown={(e) => onThumbKey(e, url)}
              className={`h-16 w-16 overflow-hidden rounded-xl bg-(--color-surface-1) transition-colors ${FOCUS} ${
                url === current ? 'border-2 border-accent' : 'border border-(--color-border-default) hover:border-accent'
              }`}
            >
              <img src={url} alt="" onError={() => markBroken(url)} className="h-full w-full object-cover" />
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
