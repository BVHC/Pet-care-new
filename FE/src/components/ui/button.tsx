import * as React from "react"
import { cn } from "../../lib/utils"

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'default' | 'destructive' | 'outline' | 'secondary' | 'ghost' | 'link'
  size?: 'default' | 'sm' | 'lg' | 'icon'
}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant = 'default', size = 'default', ...props }, ref) => {
    return (
      <button
        className={cn(
          "inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-lg text-sm font-medium transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-(--color-primary) focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50",
          // Variants
          variant === 'default' && "bg-(--color-primary) text-white hover:bg-(--color-primary-hover) shadow-sm",
          variant === 'destructive' && "bg-red-500 text-white hover:bg-red-600 shadow-sm",
          variant === 'outline' && "border border-(--border-color) bg-transparent hover:bg-(--bg-tertiary) text-(--text-primary)",
          variant === 'secondary' && "bg-(--bg-tertiary) text-(--text-primary) hover:bg-(--bg-secondary) border border-(--border-color)",
          variant === 'ghost' && "hover:bg-(--bg-tertiary) text-(--text-secondary) hover:text-(--text-primary)",
          variant === 'link' && "text-(--color-primary) underline-offset-4 hover:underline",
          // Sizes
          size === 'default' && "h-10 px-4 py-2",
          size === 'sm' && "h-9 rounded-md px-3 text-xs",
          size === 'lg' && "h-11 rounded-md px-8",
          size === 'icon' && "h-10 w-10",
          className
        )}
        ref={ref}
        {...props}
      />
    )
  }
)
Button.displayName = "Button"

export { Button }
