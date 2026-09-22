"use client"

import * as React from "react"
import { X, Check, AlertTriangle, Info, Trash2, Edit, Eye, Plus } from "lucide-react"
import { cn } from "@/lib/utils"
import { Button } from "./button"

/* ================================================================
   Base Modal Components (wrappers around Radix Dialog)
   ================================================================ */

interface ModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  children: React.ReactNode
  className?: string
}

export function Modal({ open, onOpenChange, children, className }: ModalProps) {
  return (
    <div
      data-state={open ? "open" : "closed"}
      className={cn(
        "fixed inset-0 z-[100] flex items-center justify-center bg-black/50 p-4 transition-all duration-200",
        open ? "opacity-100 pointer-events-auto" : "opacity-0 pointer-events-none"
      )}
      onClick={(e) => e.target === e.currentTarget && onOpenChange(false)}
    >
      <div
        className={cn(
          "relative w-full max-h-[90vh] overflow-auto rounded-2xl border border-(--color-border-default) bg-white shadow-2xl transition-all duration-200",
          open ? "scale-100 translate-y-0" : "scale-95 translate-y-4",
          className
        )}
        onClick={(e) => e.stopPropagation()}
      >
        {children}
      </div>
    </div>
  )
}

export function ModalHeader({
  children,
  onClose,
  className
}: {
  children: React.ReactNode
  onClose?: () => void
  className?: string
}) {
  return (
    <div className={cn("flex items-center justify-between border-b border-(--color-border-default) px-6 py-4", className)}>
      {children}
      {onClose && (
        <button
          onClick={onClose}
          className="rounded-lg p-1.5 text-(--color-text-secondary) hover:bg-(--color-surface-2) hover:text-(--color-text-primary) transition-colors"
        >
          <X size={18} />
        </button>
      )}
    </div>
  )
}

export function ModalTitle({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <h2 className={cn("font-friendly text-lg font-extrabold text-(--color-text-primary)", className)}>
      {children}
    </h2>
  )
}

export function ModalDescription({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <p className={cn("text-sm text-(--color-text-secondary)", className)}>
      {children}
    </p>
  )
}

export function ModalBody({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <div className={cn("p-6", className)}>
      {children}
    </div>
  )
}

export function ModalFooter({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <div className={cn("flex items-center justify-end gap-3 border-t border-(--color-border-default) px-6 py-4", className)}>
      {children}
    </div>
  )
}

/* ================================================================
   Confirm Modal - Xác nhận hành động
   ================================================================ */

interface ConfirmModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  type?: "danger" | "warning" | "info" | "success"
  title: string
  description?: string
  confirmText?: string
  cancelText?: string
  onConfirm: () => void
  loading?: boolean
}

const typeConfig = {
  danger: {
    icon: Trash2,
    iconClass: "text-red-500 bg-red-50",
    buttonClass: "bg-red-500 hover:bg-red-600 text-white",
  },
  warning: {
    icon: AlertTriangle,
    iconClass: "text-amber-500 bg-amber-50",
    buttonClass: "bg-amber-500 hover:bg-amber-600 text-white",
  },
  info: {
    icon: Info,
    iconClass: "text-blue-500 bg-blue-50",
    buttonClass: "bg-blue-500 hover:bg-blue-600 text-white",
  },
  success: {
    icon: Check,
    iconClass: "text-green-500 bg-green-50",
    buttonClass: "bg-green-500 hover:bg-green-600 text-white",
  },
}

export function ConfirmModal({
  open,
  onOpenChange,
  type = "info",
  title,
  description,
  confirmText = "Xác nhận",
  cancelText = "Hủy",
  onConfirm,
  loading = false,
}: ConfirmModalProps) {
  const config = typeConfig[type]
  const Icon = config.icon

  return (
    <Modal open={open} onOpenChange={onOpenChange} className="max-w-md">
      <ModalHeader onClose={() => onOpenChange(false)} className="pb-0">
        <ModalTitle>{title}</ModalTitle>
      </ModalHeader>
      <ModalBody>
        <div className="flex items-start gap-4">
          <div className={cn("shrink-0 flex h-12 w-12 items-center justify-center rounded-full", config.iconClass)}>
            <Icon size={24} />
          </div>
          <div>
            {description && (
              <p className="text-sm text-(--color-text-secondary)">{description}</p>
            )}
          </div>
        </div>
      </ModalBody>
      <ModalFooter>
        <Button
          variant="outline"
          onClick={() => onOpenChange(false)}
          disabled={loading}
        >
          {cancelText}
        </Button>
        <Button
          className={config.buttonClass}
          onClick={onConfirm}
          disabled={loading}
        >
          {loading ? "Đang xử lý..." : confirmText}
        </Button>
      </ModalFooter>
    </Modal>
  )
}

/* ================================================================
   Form Modal - Modal có form nhập liệu
   ================================================================ */

interface FormModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: string
  children: React.ReactNode
  onSubmit?: () => void
  submitText?: string
  cancelText?: string
  loading?: boolean
  size?: "sm" | "md" | "lg" | "xl"
}

const sizeClasses = {
  sm: "max-w-sm",
  md: "max-w-md",
  lg: "max-w-lg",
  xl: "max-w-2xl",
}

export function FormModal({
  open,
  onOpenChange,
  title,
  description,
  children,
  onSubmit,
  submitText = "Lưu",
  cancelText = "Hủy",
  loading = false,
  size = "md",
}: FormModalProps) {
  return (
    <Modal open={open} onOpenChange={onOpenChange} className={sizeClasses[size]}>
      <ModalHeader onClose={() => onOpenChange(false)}>
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-full bg-(--color-accent-soft)">
            <Plus size={20} className="text-accent" />
          </div>
          <div>
            <ModalTitle>{title}</ModalTitle>
            {description && <ModalDescription>{description}</ModalDescription>}
          </div>
        </div>
      </ModalHeader>
      <form onSubmit={(e) => { e.preventDefault(); onSubmit?.() }}>
        <ModalBody className="space-y-4">
          {children}
        </ModalBody>
        <ModalFooter>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={loading}>
            {cancelText}
          </Button>
          <Button type="submit" disabled={loading}>
            {loading ? "Đang lưu..." : submitText}
          </Button>
        </ModalFooter>
      </form>
    </Modal>
  )
}

/* ================================================================
   Detail Modal - Xem chi tiết (View/Edit/Read-only)
   ================================================================ */

interface DetailModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  children: React.ReactNode
  onEdit?: () => void
  onDelete?: () => void
  editText?: string
  deleteText?: string
  size?: "sm" | "md" | "lg" | "xl"
}

export function DetailModal({
  open,
  onOpenChange,
  title,
  children,
  onEdit,
  onDelete,
  editText = "Sửa",
  deleteText = "Xóa",
  size = "md",
}: DetailModalProps) {
  return (
    <Modal open={open} onOpenChange={onOpenChange} className={sizeClasses[size]}>
      <ModalHeader onClose={() => onOpenChange(false)}>
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-full bg-blue-50">
            <Eye size={20} className="text-blue-500" />
          </div>
          <ModalTitle>{title}</ModalTitle>
        </div>
      </ModalHeader>
      <ModalBody>{children}</ModalBody>
      {(onEdit || onDelete) && (
        <ModalFooter>
          {onDelete && (
            <Button variant="outline" className="text-red-500 border-red-200 hover:bg-red-50" onClick={onDelete}>
              <Trash2 size={16} className="mr-2" />
              {deleteText}
            </Button>
          )}
          <div className="flex-1" />
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Đóng
          </Button>
          {onEdit && (
            <Button onClick={onEdit}>
              <Edit size={16} className="mr-2" />
              {editText}
            </Button>
          )}
        </ModalFooter>
      )}
    </Modal>
  )
}

/* ================================================================
   Info Row - Dùng trong Detail Modal
   ================================================================ */

export function InfoRow({ label, value, className }: { label: string; value: React.ReactNode; className?: string }) {
  return (
    <div className={cn("flex justify-between py-2 border-b border-(--color-border-light) last:border-0", className)}>
      <span className="text-sm text-(--color-text-secondary)">{label}</span>
      <span className="text-sm font-medium text-(--color-text-primary)">{value}</span>
    </div>
  )
}

/* ================================================================
   Status Badge - Dùng trong Detail Modal
   ================================================================ */

interface StatusBadgeProps {
  status: string
  variant?: "success" | "warning" | "danger" | "info" | "neutral"
}

const statusVariants = {
  success: "bg-green-100 text-green-700",
  warning: "bg-amber-100 text-amber-700",
  danger: "bg-red-100 text-red-700",
  info: "bg-blue-100 text-blue-700",
  neutral: "bg-gray-100 text-gray-700",
}

export function StatusBadge({ status, variant = "neutral" }: StatusBadgeProps) {
  return (
    <span className={cn("inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium", statusVariants[variant])}>
      {status}
    </span>
  )
}
