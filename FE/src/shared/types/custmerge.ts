// Gộp hồ sơ khách trùng (UC27) — docs/api/custmerge-v1.md, openapi/custmerge-v1.yaml. Tầng 3, BE chưa cài; kiểu bám schema yaml.

/** #1: số bản ghi sẽ chuyển từ hồ sơ nguồn sang hồ sơ đích. `blockers` rỗng mới gộp được (BR-KH-11). */
export interface MergePreview {
  targetCustomerId: number;
  sourceCustomerId: number;
  petCount: number;
  addressCount: number;
  /** Địa chỉ trùng sẽ bỏ khi gộp. */
  duplicateAddressCount: number;
  appointmentCount: number;
  boardingCount: number;
  orderCount: number;
  onlineOrderCount: number;
  feedbackCount: number;
  careTaskCount: number;
  warnings: string[];
  blockers: string[];
}

/** `confirm` phải là true sau khi đã xem #1 (BR-KH-14). */
export interface CustomerMergeRequest {
  sourceCustomerId: number;
  targetCustomerId: number;
  confirm: boolean;
}

/** `moved`: số bản ghi đã chuyển theo loại (khóa tự do trong yaml). */
export interface CustomerMergeResult {
  mergeId: number;
  sourceCustomerId: number;
  targetCustomerId: number;
  moved: Record<string, number>;
  createdAt: string;
}
