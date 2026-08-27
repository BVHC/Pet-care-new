import type { InputHTMLAttributes } from 'react';

export function TextInput(props: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      {...props}
      className="w-full flex-1 rounded-[var(--radius-rounded)] border border-[var(--color-border-default)] bg-[var(--color-surface-card)] px-3 py-3 font-[var(--font-professional)] text-base text-[var(--color-text-primary)] outline-none focus:border-[var(--color-border-focus)]"
    />
  );
}
