import { useEffect, useRef, useState } from 'react'

interface Options {
  threshold?: number
  rootMargin?: string
}

/**
 * Báo true khi element lần đầu vào viewport; tự disconnect sau đó.
 * Trả [ref, isVisible]. Gắn ref lên wrapper, toggle class `.isVisible`
 * khi isVisible=true để CSS reveal chạy đúng 1 lần.
 */
export function useInViewOnce<T extends HTMLElement = HTMLDivElement>(
  options: Options = {},
) {
  const { threshold = 0.15, rootMargin = '0px 0px -10% 0px' } = options
  const ref = useRef<T>(null)
  const [isVisible, setIsVisible] = useState(false)

  useEffect(() => {
    const node = ref.current
    if (!node || typeof IntersectionObserver === 'undefined') {
      setIsVisible(true) // graceful fallback SSR
      return
    }
    const io = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            setIsVisible(true)
            io.disconnect()
            break
          }
        }
      },
      { threshold, rootMargin },
    )
    io.observe(node)
    return () => io.disconnect()
  }, [threshold, rootMargin])

  return [ref, isVisible] as const
}
