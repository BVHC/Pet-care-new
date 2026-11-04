import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function formatCurrency(amount: number): string {
  return new Intl.NumberFormat('vi-VN', {
    style: 'currency',
    currency: 'VND',
  }).format(amount);
}

/** Ngày thuần "yyyy-MM-dd" (LocalDate của BE) dựng theo giờ máy: `new Date("2026-10-28")` là 00:00 UTC nên máy phía tây UTC in lùi 1 ngày. */
const LOCAL_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

export function formatDate(date: Date | string): string {
  const plain = typeof date === 'string' ? LOCAL_DATE.exec(date) : null;
  const d = plain ? new Date(Number(plain[1]), Number(plain[2]) - 1, Number(plain[3])) : new Date(date);
  return new Intl.DateTimeFormat('vi-VN', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  }).format(d);
}

export function formatDateTime(date: Date | string): string {
  const d = typeof date === 'string' ? new Date(date) : date;
  return new Intl.DateTimeFormat('vi-VN', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(d);
}
