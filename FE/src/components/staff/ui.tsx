// Small building blocks shared by the three staff workspaces.
import { forwardRef, type ButtonHTMLAttributes, type ComponentType, type ReactNode } from 'react';
import { AlertTriangle } from 'lucide-react';
import { cn } from '../../lib/utils';
import { PRIORITY_LABEL, VISIT_STATUS_LABEL } from '../../shared/constants/clinic-labels';
import type { QueuePriority, VisitStatus } from '../../shared/types/clinic';
import { errorMessage } from '../../shared/hooks/useClinic';

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger';
type Size = 'sm' | 'md' | 'lg';

const VARIANTS: Record<Variant, string> = {
  primary: 'bg-(--ws-accent) text-white hover:brightness-110 active:brightness-95',
  secondary:
    'border border-(--ws-line-strong) bg-(--ws-surface) text-(--ws-ink) hover:border-(--ws-ink-3) hover:bg-(--ws-raised)',
  ghost: 'text-(--ws-ink-2) hover:bg-(--ws-raised) hover:text-(--ws-ink)',
  danger: 'text-(--ws-urgent-ink) hover:bg-(--ws-urgent-bg)',
};

const SIZES: Record<Size, string> = {
  sm: 'h-8 gap-1.5 px-2.5 text-[13px]',
  md: 'h-10 gap-2 px-3.5 text-sm',
  lg: 'h-12 gap-2 px-5 text-[15px]',
};

export interface WsButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
}

export const WsButton = forwardRef<HTMLButtonElement, WsButtonProps>(function WsButton(
  { variant = 'secondary', size = 'md', className, type = 'button', ...props },
  ref,
) {
  return (
    <button
      ref={ref}
      type={type}
      className={cn(
        'inline-flex shrink-0 select-none items-center justify-center rounded-lg font-semibold transition-[background-color,border-color,filter] disabled:cursor-not-allowed disabled:opacity-45 disabled:hover:brightness-100',
        VARIANTS[variant],
        SIZES[size],
        className,
      )}
      {...props}
    />
  );
});

export function Kbd({ children }: { children: ReactNode }) {
  return <kbd className="ws-kbd">{children}</kbd>;
}

/** The queue number staff call out. */
export function Ticket({ no, size = 'md', tone }: { no: number; size?: 'sm' | 'md' | 'lg'; tone?: 'urgent' | 'muted' }) {
  return (
    <span className="ws-ticket" data-size={size} data-tone={tone} aria-label={`Số ${no}`}>
      {no}
    </span>
  );
}

const STATUS_TONE: Record<VisitStatus, string> = { WAITING: 'wait', IN_PROGRESS: 'busy', COMPLETED: 'ready', CANCELLED: 'done' };

export function VisitStatusChip({ status, label }: { status: VisitStatus; label?: string }) {
  return (
    <span className="ws-chip" data-tone={STATUS_TONE[status]}>
      {label ?? VISIT_STATUS_LABEL[status]}
    </span>
  );
}

export function PriorityChip({ priority }: { priority: QueuePriority }) {
  if (priority === 'WALK_IN') return null;
  return (
    <span className="ws-chip" data-tone={priority === 'EMERGENCY' ? 'urgent' : 'accent'}>
      {priority === 'EMERGENCY' && <AlertTriangle className="h-3 w-3" aria-hidden />}
      {PRIORITY_LABEL[priority]}
    </span>
  );
}

export function Panel({ className, children, label }: { className?: string; children: ReactNode; label: string }) {
  return (
    <section
      aria-label={label}
      className={cn('flex min-h-0 flex-col overflow-hidden rounded-xl border border-(--ws-line) bg-(--ws-surface)', className)}
    >
      {children}
    </section>
  );
}

export function PanelTitle({ children, aside }: { children: ReactNode; aside?: ReactNode }) {
  return (
    <div className="flex min-h-12 items-center gap-2 border-b border-(--ws-line) px-4 py-2">
      <h2 className="mr-auto text-[15px] font-semibold text-(--ws-ink)">{children}</h2>
      {aside}
    </div>
  );
}

export function WorkspaceHeader({ title, subtitle, children }: { title: string; subtitle?: ReactNode; children?: ReactNode }) {
  return (
    <header className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-(--ws-line) bg-(--ws-surface) px-4 py-3 shadow-[inset_0_3px_0_var(--ws-accent)] lg:px-5">
      <div className="mr-auto min-w-0">
        <h1 className="text-lg font-bold tracking-tight text-(--ws-ink)">{title}</h1>
        {subtitle && <p className="text-[13px] text-(--ws-ink-2)">{subtitle}</p>}
      </div>
      {children}
    </header>
  );
}

export function StatePanel({
  icon: Icon,
  title,
  body,
  action,
  tone = 'empty',
}: {
  icon?: ComponentType<{ className?: string }>;
  title: string;
  body?: ReactNode;
  action?: ReactNode;
  tone?: 'empty' | 'error';
}) {
  return (
    <div
      role={tone === 'error' ? 'alert' : undefined}
      className="flex flex-col items-center justify-center gap-2 px-6 py-10 text-center"
    >
      {Icon && (
        <Icon className={cn('h-6 w-6', tone === 'error' ? 'text-(--ws-urgent-ink)' : 'text-(--ws-ink-3)')} aria-hidden />
      )}
      <p className="font-semibold text-(--ws-ink)">{title}</p>
      {body && <p className="max-w-xs text-sm text-(--ws-ink-2)">{body}</p>}
      {action && <div className="mt-1">{action}</div>}
    </div>
  );
}

export function QueryError({ error, onRetry, what }: { error: unknown; onRetry: () => void; what: string }) {
  return (
    <StatePanel
      tone="error"
      icon={AlertTriangle}
      title={`Không tải được ${what}`}
      body={errorMessage(error)}
      action={
        <WsButton size="sm" onClick={onRetry}>
          Thử lại
        </WsButton>
      }
    />
  );
}

export function Skeleton({ className }: { className?: string }) {
  return <span aria-hidden className={cn('ws-skel block', className)} />;
}

export function RowsSkeleton({ rows = 4 }: { rows?: number }) {
  return (
    <div role="status" aria-label="Đang tải" className="flex flex-col gap-3 p-4">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="flex items-center gap-3">
          <Skeleton className="h-10 w-10 rounded-md" />
          <div className="flex flex-1 flex-col gap-1.5">
            <Skeleton className="h-3.5 w-2/3" />
            <Skeleton className="h-3 w-1/3" />
          </div>
        </div>
      ))}
    </div>
  );
}
