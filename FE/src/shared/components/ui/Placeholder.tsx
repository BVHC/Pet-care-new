import { Hammer } from 'lucide-react';

// Khung tạm cho trang chưa dựng. Thay bằng nội dung thật khi làm tính năng.
export function Placeholder({ title }: { title: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 py-24 text-center">
      <Hammer size={28} className="opacity-40" />
      <h1 className="font-friendly text-xl font-bold text-(--color-text-primary)">{title}</h1>
      <p className="text-sm text-(--color-text-secondary)">Trang đang được xây dựng.</p>
    </div>
  );
}
