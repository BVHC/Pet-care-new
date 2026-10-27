// Định dạng hiển thị cho màn hình lưu trú của khách. Ngày là `YYYY-MM-DD` giờ Việt Nam: chỉ cắt chuỗi / dùng UTC, không qua múi giờ máy.
export const vnd = new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 });

const WEIGHT = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 2 });
/** 6.5 -> "6,5 kg" */
export const formatWeight = (kg: number) => `${WEIGHT.format(kg)} kg`;

export const nightsLabel = (nights: number) => `${nights} đêm`;

const WEEKDAYS = ['CN', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7'] as const;
/** "2026-10-26" -> "T2" (thứ của ngày lịch, không phụ thuộc múi giờ máy). */
export const weekdayShort = (iso: string) => WEEKDAYS[new Date(Date.UTC(Number(iso.slice(0, 4)), Number(iso.slice(5, 7)) - 1, Number(iso.slice(8, 10)))).getUTCDay()];

/** "2026-10-26" -> "26/10" */
export const ddMM = (iso: string) => `${iso.slice(8, 10)}/${iso.slice(5, 7)}`;
