import type { InputHTMLAttributes } from 'react';

export function TextInput(props: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      {...props}
      className="w-full flex-1 rounded-(--radius-rounded) border border-(--color-border-default) bg-(--color-surface-card) px-3 py-3 font-professional text-base text-(--color-text-primary) outline-none focus:border-(--color-border-focus)"
    />
  );
}
