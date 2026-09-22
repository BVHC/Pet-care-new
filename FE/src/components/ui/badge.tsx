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
        variant === 'default' && "bg-(--color-primary) text-white",
        variant === 'secondary' && "bg-(--bg-tertiary) text-(--text-secondary)",
        variant === 'success' && "bg-(--color-success-light) text-(--color-success)",
        variant === 'warning' && "bg-(--color-warning-light) text-(--color-warning)",
        variant === 'destructive' && "bg-(--color-error-light) text-(--color-error)",
        variant === 'outline' && "border border-(--border-color) text-(--text-secondary)",
        className
      )}
      {...props}
    />
  )
}

export { Badge }
