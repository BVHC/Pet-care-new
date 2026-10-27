// Dòng thời gian trạng thái theo SM #6; mốc giờ theo giờ Việt Nam (`formatVnDateTime`).
import { useId } from 'react';
import { Check } from 'lucide-react';
import { cn } from '@/lib/utils';
import type { Booking, BookingStatus } from '@/shared/types/boarding';
import { timelineSteps, type StepState } from './model';
import { CARD, H2, STATUS_ICON } from './styles';

const STATE_WORD: Record<StepState, string> = { done: 'đã xong', current: 'trạng thái hiện tại', upcoming: 'chưa tới' };

function dotClass(state: StepState, key: BookingStatus): string {
  if (state === 'upcoming') return 'border border-(--color-border-default) bg-white text-(--color-text-secondary)';
  if (state === 'done') return 'bg-(--color-accent-soft) text-accent';
  if (key === 'OVERDUE' || key === 'NO_SHOW') return 'bg-red-600 text-white';
  if (key === 'CANCELLED') return 'bg-gray-500 text-white';
  return 'bg-accent text-white';
}

export function StatusTimeline({ booking }: { booking: Booking }) {
  const titleId = useId();
  const steps = timelineSteps(booking);
  return (
    <section aria-labelledby={titleId} className={cn(CARD, 'p-5 sm:p-6')}>
      <h2 id={titleId} className={H2}>
        Tiến trình
      </h2>
      <ol aria-label="Tiến trình lưu trú" className="mt-4">
        {steps.map((step, i) => {
          const Icon = step.state === 'done' ? Check : STATUS_ICON[step.key];
          const next = steps[i + 1];
          return (
            <li key={step.key} aria-current={step.state === 'current' ? 'step' : undefined} className="relative flex gap-3 pb-5 last:pb-0">
              {next && (
                <span
                  aria-hidden
                  className={cn('absolute left-[13px] top-8 bottom-1 w-px', next.state === 'upcoming' ? 'bg-(--color-border-default)' : 'bg-accent/40')}
                />
              )}
              <span className={cn('relative z-[1] flex h-7 w-7 shrink-0 items-center justify-center rounded-full', dotClass(step.state, step.key))}>
                {step.state !== 'upcoming' && <Icon size={14} aria-hidden />}
              </span>
              <div className="min-w-0 pt-0.5">
                <p className={cn('text-[14px] leading-tight', step.state === 'upcoming' ? 'font-semibold text-(--color-text-secondary)' : 'font-bold text-(--color-text-primary)')}>
                  {step.label}
                  <span className="sr-only">, {STATE_WORD[step.state]}</span>
                </p>
                {step.detail && <p className="mt-0.5 break-words text-[12.5px] text-(--color-text-secondary)">{step.detail}</p>}
              </div>
            </li>
          );
        })}
      </ol>
      <p className="mt-4 text-[12px] text-(--color-text-secondary)">Giờ hiển thị theo giờ Việt Nam.</p>
    </section>
  );
}
