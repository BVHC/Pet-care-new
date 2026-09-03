import { Star } from 'lucide-react';

interface StarsProps {
  rating?: number;
  n?: number;
  size?: number;
}

export function Stars({ rating = 5, n, size = 13 }: StarsProps) {
  const count = n ?? rating;
  return (
    <div className="flex gap-0.5">
      {Array.from({ length: count }).map((_, i) => (
        <Star key={i} size={size} fill="var(--color-brand-secondary)" color="var(--color-brand-secondary)" />
      ))}
    </div>
  );
}
