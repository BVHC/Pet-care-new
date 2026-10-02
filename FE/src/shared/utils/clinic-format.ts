import { SPECIES_LABEL } from '../constants/clinic-labels';
import type { Species } from '../types/clinic';
import { foldVi } from './text';

const clock = new Intl.DateTimeFormat('vi-VN', { hour: '2-digit', minute: '2-digit', hour12: false });
const weekday = new Intl.DateTimeFormat('vi-VN', { weekday: 'long', day: 'numeric', month: 'numeric' });
const shortDate = new Intl.DateTimeFormat('vi-VN', { day: '2-digit', month: '2-digit', year: 'numeric' });

/** "09:42" */
export const formatClock = (iso: string) => clock.format(new Date(iso));

/** "Thứ Sáu, 2/10" */
export const formatToday = (now: number = Date.now()) => {
  const text = weekday.format(new Date(now));
  return text.charAt(0).toUpperCase() + text.slice(1);
};

/** "02/10/2026" */
export const formatShortDate = (iso: string) => shortDate.format(new Date(iso));

/** Time since an event, for queues: "vừa xong", "12 phút", "1 giờ 5 phút". */
export function formatElapsed(fromIso: string, now: number): string {
  const minutes = Math.floor((now - Date.parse(fromIso)) / 60_000);
  if (minutes < 1) return 'vừa xong';
  if (minutes < 60) return `${minutes} phút`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest ? `${hours} giờ ${rest} phút` : `${hours} giờ`;
}

export function ageLabel(birthYear: number, now: number): string {
  const years = new Date(now).getFullYear() - birthYear;
  return years < 1 ? 'dưới 1 tuổi' : `${years} tuổi`;
}

/** "Chó Golden Retriever", but "Mèo ta" rather than "Mèo Mèo ta". */
export function petKind(species: Species, breed: string): string {
  const label = SPECIES_LABEL[species];
  return foldVi(breed).startsWith(foldVi(label)) ? breed : `${label} ${breed}`;
}
