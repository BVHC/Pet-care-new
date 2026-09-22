import React, { useState } from 'react';
import { Star } from 'lucide-react';

export interface StarRatingInputProps {
  value: number;
  onChange?: (rating: number) => void;
  label?: string;
  readOnly?: boolean;
  size?: 'small' | 'medium' | 'large';
  showTextLabel?: boolean;
}

const RATING_LABELS: Record<number, string> = {
  1: 'Rất thất vọng',
  2: 'Chưa hài lòng',
  3: 'Bình thường',
  4: 'Hài lòng',
  5: 'Rất hài lòng',
};

export const StarRatingInput: React.FC<StarRatingInputProps> = ({
  value,
  onChange,
  label,
  readOnly = false,
  size = 'medium',
  showTextLabel = true,
}) => {
  const [hoverRating, setHoverRating] = useState<number | null>(null);

  const starSizeMap = {
    small: 16,
    medium: 22,
    large: 32,
  };

  const currentDisplayRating = hoverRating !== null ? hoverRating : value;

  return (
    <div className="flex flex-col gap-1.5">
      {label && (
        <span className="text-sm font-semibold text-(--color-text-primary)">
          {label}
        </span>
      )}
      <div className="flex items-center gap-2">
        <div className="flex items-center gap-1">
          {[1, 2, 3, 4, 5].map((starIndex) => {
            const isFilled = starIndex <= currentDisplayRating;
            return (
              <button
                key={starIndex}
                type="button"
                disabled={readOnly}
                onClick={() => !readOnly && onChange?.(starIndex)}
                onMouseEnter={() => !readOnly && setHoverRating(starIndex)}
                onMouseLeave={() => !readOnly && setHoverRating(null)}
                className={`transition-all duration-150 ${
                  readOnly
                    ? 'cursor-default'
                    : 'cursor-pointer hover:scale-110 active:scale-95'
                }`}
                aria-label={`Rating ${starIndex} stars`}
              >
                <Star
                  size={starSizeMap[size]}
                  className={`transition-colors duration-150 ${
                    isFilled
                      ? 'fill-[var(--color-accent-amber,#f59e0b)] stroke-[var(--color-accent-amber,#f59e0b)] drop-shadow-sm'
                      : 'fill-[var(--color-surface-sunken,#e2e8f0)] stroke-[var(--color-text-tertiary,#cbd5e1)]'
                  }`}
                />
              </button>
            );
          })}
        </div>

        {showTextLabel && currentDisplayRating > 0 && (
          <span className="ml-1 text-sm font-medium text-[var(--color-brand-primary-active,#d97706)]">
            {RATING_LABELS[currentDisplayRating]}
          </span>
        )}
      </div>
    </div>
  );
};
