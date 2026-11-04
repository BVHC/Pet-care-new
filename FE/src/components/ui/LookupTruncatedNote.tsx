/** Tra cứu khách xin tối đa 100 dòng (BE kẹp 100): còn nhiều hơn thì nhắc thu hẹp từ khóa thay vì cắt im lặng (L-15). */
export function LookupTruncatedNote({ shown, total, className }: { shown: number; total: number; className?: string }) {
  if (total <= shown) return null;
  return <p role="note" className={className ?? 'text-xs text-(--text-tertiary)'}>{`Đang hiển thị ${shown} / ${total} kết quả, hãy thu hẹp từ khóa.`}</p>;
}
