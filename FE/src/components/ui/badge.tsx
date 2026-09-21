import * as React from "react"
import { cn } from "../../lib/utils"

interface BadgeProps extends React.HTMLAttributes<HTMLDivElement> {
  variant?: 'default' | 'secondary' | 'success' | 'warning' | 'destructive' | 'outline'
}

function Badge({ className, variant = 'default', ...props }: BadgeProps) {
  return (
    <div
      className={cn(
        "badge",
        variant === 'default' && "bg-[var(--color-primary)] text-white",
        variant === 'secondary' && "bg-[var(--bg-tertiary)] text-[var(--text-secondary)]",
        variant === 'success' && "bg-[var(--color-success-light)] text-[var(--color-success)]",
        variant === 'warning' && "bg-[var(--color-warning-light)] text-[var(--color-warning)]",
        variant === 'destructive' && "bg-[var(--color-error-light)] text-[var(--color-error)]",
        variant === 'outline' && "border border-[var(--border-color)] text-[var(--text-secondary)]",
        className
      )}
      {...props}
    />
  )
}

export { Badge }
