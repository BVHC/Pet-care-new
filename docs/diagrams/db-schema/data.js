const MASTER_TABLES = {
      accounts: { id: 'accounts', name: 'public.accounts', w: 260, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'phone', t: 'varchar', uq: true },
        { n: 'email', t: 'varchar', uq: true },
        { n: 'password_hash', t: 'varchar' },
        { n: 'status', t: 'account_status' },
        { n: 'role', t: 'user_role' },
        { n: 'refresh_token', t: 'varchar' },
        { n: 'refresh_token_expiry', t: 'timestamp' },
        { n: 'created_at', t: 'timestamp' },
        { n: 'updated_at', t: 'timestamp' }
      ]},
      users: { id: 'users', name: 'public.users', w: 260, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'account_id', t: 'bigint', fk: true, uq: true },
        { n: 'name', t: 'varchar' },
        { n: 'avatar', t: 'varchar' },
        { n: 'organization_id', t: 'bigint', fk: true },
        { n: 'store_id', t: 'bigint', fk: true },
        { n: 'created_at', t: 'timestamp' },
        { n: 'updated_at', t: 'timestamp' }
      ]},
      otps: { id: 'otps', name: 'public.otps', w: 250, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'account_id', t: 'bigint', fk: true },
        { n: 'code', t: 'varchar' },
        { n: 'type', t: 'varchar' },
        { n: 'expires_at', t: 'timestamp' },
        { n: 'used_at', t: 'timestamp' },
        { n: 'attempts', t: 'int' },
        { n: 'created_at', t: 'timestamp' }
      ]},
      organizations: { id: 'organizations', name: 'public.organizations', w: 260, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'name', t: 'varchar' },
        { n: 'code', t: 'varchar', uq: true },
        { n: 'description', t: 'text' },
        { n: 'created_at', t: 'timestamp' },
        { n: 'updated_at', t: 'timestamp' }
      ]},
      stores: { id: 'stores', name: 'public.stores', w: 260, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'organization_id', t: 'bigint', fk: true },
        { n: 'name', t: 'varchar' },
        { n: 'code', t: 'varchar', uq: true },
        { n: 'address', t: 'varchar' },
        { n: 'phone', t: 'varchar' },
        { n: 'status', t: 'store_status' },
        { n: 'created_at', t: 'timestamp' },
        { n: 'updated_at', t: 'timestamp' }
      ]},
      operating_hours: { id: 'operating_hours', name: 'public.operating_hours', w: 250, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'store_id', t: 'bigint', fk: true },
        { n: 'day_of_week', t: 'smallint' },
        { n: 'open_time', t: 'time' },
        { n: 'close_time', t: 'time' },
        { n: 'is_closed', t: 'boolean' }
      ]},
      store_services: { id: 'store_services', name: 'public.store_services', w: 250, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'store_id', t: 'bigint', fk: true },
        { n: 'service_id', t: 'bigint', fk: true },
        { n: 'price', t: 'decimal' },
        { n: 'is_available', t: 'boolean' }
      ]},
      pets: { id: 'pets', name: 'public.pets', w: 260, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'owner_id', t: 'bigint', fk: true },
        { n: 'name', t: 'varchar' },
        { n: 'species', t: 'varchar' },
        { n: 'breed', t: 'varchar' },
        { n: 'birth_date', t: 'date' },
        { n: 'weight', t: 'decimal' },
        { n: 'image_url', t: 'varchar' },
        { n: 'notes', t: 'text' },
        { n: 'created_at', t: 'timestamp' },
        { n: 'updated_at', t: 'timestamp' }
      ]},
      services: { id: 'services', name: 'public.services', w: 250, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'name', t: 'varchar' },
        { n: 'description', t: 'text' },
        { n: 'category', t: 'varchar' },
        { n: 'duration_minutes', t: 'int' },
        { n: 'price', t: 'decimal' },
        { n: 'is_active', t: 'boolean' },
        { n: 'created_at', t: 'timestamp' }
      ]},
      products: { id: 'products', name: 'public.products', w: 260, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'name', t: 'varchar' },
        { n: 'description', t: 'text' },
        { n: 'category', t: 'varchar' },
        { n: 'price', t: 'decimal' },
        { n: 'discount_percent', t: 'int' },
        { n: 'image_url', t: 'varchar' },
        { n: 'is_active', t: 'boolean' },
        { n: 'created_at', t: 'timestamp' },
        { n: 'updated_at', t: 'timestamp' }
      ]},
      inventory: { id: 'inventory', name: 'public.inventory', w: 250, focal: true, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'store_id', t: 'bigint', fk: true },
        { n: 'product_id', t: 'bigint', fk: true },
        { n: 'quantity', t: 'int' },
        { n: 'min_threshold', t: 'int' },
        { n: 'updated_at', t: 'timestamp' }
      ]},
      store_resources: { id: 'store_resources', name: 'public.store_resources', w: 250, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'store_id', t: 'bigint', fk: true },
        { n: 'resource_type', t: 'varchar' },
        { n: 'name', t: 'varchar' },
        { n: 'capacity', t: 'int' },
        { n: 'is_active', t: 'boolean' },
        { n: 'created_at', t: 'timestamp' }
      ]},
      service_required_resources: { id: 'service_required_resources', name: 'public.service_required_resources', w: 260, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'service_id', t: 'bigint', fk: true },
        { n: 'resource_type', t: 'varchar' },
        { n: 'quantity_required', t: 'int' }
      ]},
      carts: { id: 'carts', name: 'public.carts', w: 250, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'customer_id', t: 'bigint', fk: true },
        { n: 'store_id', t: 'bigint', fk: true },
        { n: 'created_at', t: 'timestamp' },
        { n: 'updated_at', t: 'timestamp' }
      ]},
      cart_items: { id: 'cart_items', name: 'public.cart_items', w: 250, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'cart_id', t: 'bigint', fk: true },
        { n: 'product_id', t: 'bigint', fk: true },
        { n: 'quantity', t: 'int' },
        { n: 'unit_price', t: 'decimal' },
        { n: 'created_at', t: 'timestamp' }
      ]},
      orders: { id: 'orders', name: 'public.orders', w: 270, focal: true, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'order_number', t: 'varchar', uq: true },
        { n: 'customer_id', t: 'bigint', fk: true },
        { n: 'store_id', t: 'bigint', fk: true },
        { n: 'status', t: 'order_status' },
        { n: 'subtotal', t: 'decimal' },
        { n: 'discount', t: 'decimal' },
        { n: 'total', t: 'decimal' },
        { n: 'voucher_code', t: 'varchar' },
        { n: 'notes', t: 'text' },
        { n: 'created_at', t: 'timestamp' },
        { n: 'updated_at', t: 'timestamp' }
      ]},
      order_items: { id: 'order_items', name: 'public.order_items', w: 260, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'order_id', t: 'bigint', fk: true },
        { n: 'product_id', t: 'bigint', fk: true },
        { n: 'quantity', t: 'int' },
        { n: 'unit_price', t: 'decimal' },
        { n: 'subtotal', t: 'decimal' },
        { n: 'created_at', t: 'timestamp' }
      ]},
      payments: { id: 'payments', name: 'public.payments', w: 260, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'order_id', t: 'bigint', fk: true },
        { n: 'amount', t: 'decimal' },
        { n: 'method', t: 'varchar' },
        { n: 'status', t: 'payment_status' },
        { n: 'transaction_id', t: 'varchar' },
        { n: 'gateway_response', t: 'jsonb' },
        { n: 'paid_at', t: 'timestamp' },
        { n: 'created_at', t: 'timestamp' },
        { n: 'updated_at', t: 'timestamp' }
      ]},
      refunds: { id: 'refunds', name: 'public.refunds', w: 260, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'payment_id', t: 'bigint', fk: true },
        { n: 'order_id', t: 'bigint', fk: true },
        { n: 'amount', t: 'decimal' },
        { n: 'reason', t: 'text' },
        { n: 'status', t: 'refund_status' },
        { n: 'approved_by', t: 'bigint', fk: true },
        { n: 'approved_at', t: 'timestamp' },
        { n: 'rejection_reason', t: 'text' },
        { n: 'processed_at', t: 'timestamp' },
        { n: 'completed_at', t: 'timestamp' },
        { n: 'created_at', t: 'timestamp' }
      ]},
      invoices: { id: 'invoices', name: 'billing.invoices', w: 260, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'invoice_number', t: 'varchar', uq: true },
        { n: 'order_id', t: 'bigint', fk: true },
        { n: 'appointment_id', t: 'bigint', fk: true },
        { n: 'customer_id', t: 'bigint', fk: true },
        { n: 'store_id', t: 'bigint', fk: true },
        { n: 'status', t: 'invoice_status' },
        { n: 'subtotal', t: 'decimal' },
        { n: 'tax', t: 'decimal' },
        { n: 'discount', t: 'decimal' },
        { n: 'total', t: 'decimal' },
        { n: 'paid_amount', t: 'decimal' },
        { n: 'issued_at', t: 'timestamp' }
      ]},
      invoice_items: { id: 'invoice_items', name: 'billing.invoice_items', w: 250, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'invoice_id', t: 'bigint', fk: true },
        { n: 'type', t: 'varchar' },
        { n: 'reference_id', t: 'bigint' },
        { n: 'name', t: 'varchar' },
        { n: 'quantity', t: 'int' },
        { n: 'unit_price', t: 'decimal' },
        { n: 'subtotal', t: 'decimal' }
      ]},
      vouchers: { id: 'vouchers', name: 'public.vouchers', w: 250, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'code', t: 'varchar', uq: true },
        { n: 'description', t: 'text' },
        { n: 'discount_type', t: 'varchar' },
        { n: 'discount_value', t: 'decimal' },
        { n: 'min_order_value', t: 'decimal' },
        { n: 'max_usage', t: 'int' },
        { n: 'current_usage', t: 'int' },
        { n: 'max_per_user', t: 'int' },
        { n: 'valid_from', t: 'timestamp' },
        { n: 'valid_to', t: 'timestamp' },
        { n: 'is_active', t: 'boolean' },
        { n: 'created_at', t: 'timestamp' }
      ]},
      voucher_usages: { id: 'voucher_usages', name: 'public.voucher_usages', w: 250, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'voucher_id', t: 'bigint', fk: true },
        { n: 'customer_id', t: 'bigint', fk: true },
        { n: 'order_id', t: 'bigint', fk: true },
        { n: 'discount_amount', t: 'decimal' },
        { n: 'used_at', t: 'timestamp' }
      ]},
      appointments: { id: 'appointments', name: 'public.appointments', w: 280, focal: true, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'pet_id', t: 'bigint', fk: true },
        { n: 'store_id', t: 'bigint', fk: true },
        { n: 'service_id', t: 'bigint', fk: true },
        { n: 'staff_id', t: 'bigint', fk: true },
        { n: 'customer_id', t: 'bigint', fk: true },
        { n: 'scheduled_at', t: 'timestamp' },
        { n: 'status', t: 'appointment_status' },
        { n: 'notes', t: 'text' },
        { n: 'checked_in_at', t: 'timestamp' },
        { n: 'started_at', t: 'timestamp' },
        { n: 'completed_at', t: 'timestamp' },
        { n: 'cancelled_at', t: 'timestamp' },
        { n: 'cancellation_reason', t: 'text' },
        { n: 'created_at', t: 'timestamp' },
        { n: 'updated_at', t: 'timestamp' }
      ]},
      queues: { id: 'queues', name: 'public.queues', w: 250, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'store_id', t: 'bigint', fk: true },
        { n: 'date', t: 'date' },
        { n: 'current_position', t: 'int' },
        { n: 'status', t: 'varchar' },
        { n: 'created_at', t: 'timestamp' }
      ]},
      queue_entries: { id: 'queue_entries', name: 'public.queue_entries', w: 250, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'queue_id', t: 'bigint', fk: true },
        { n: 'walkin_id', t: 'bigint', fk: true },
        { n: 'position', t: 'int' },
        { n: 'status', t: 'queue_entry_status' },
        { n: 'called_at', t: 'timestamp' },
        { n: 'service_started_at', t: 'timestamp' },
        { n: 'completed_at', t: 'timestamp' },
        { n: 'created_at', t: 'timestamp' }
      ]},
      walkins: { id: 'walkins', name: 'public.walkins', w: 260, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'store_id', t: 'bigint', fk: true },
        { n: 'pet_id', t: 'bigint', fk: true },
        { n: 'customer_id', t: 'bigint', fk: true },
        { n: 'service_id', t: 'bigint', fk: true },
        { n: 'queue_number', t: 'int' },
        { n: 'status', t: 'varchar' },
        { n: 'called_at', t: 'timestamp' },
        { n: 'served_at', t: 'timestamp' },
        { n: 'estimated_wait_minutes', t: 'int' },
        { n: 'appointment_id', t: 'bigint', fk: true },
        { n: 'created_at', t: 'timestamp' }
      ]},
      grooming_sessions: { id: 'grooming_sessions', name: 'public.grooming_sessions', w: 270, focal: true, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'appointment_id', t: 'bigint', fk: true },
        { n: 'pet_id', t: 'bigint', fk: true },
        { n: 'store_id', t: 'bigint', fk: true },
        { n: 'groomer_id', t: 'bigint', fk: true },
        { n: 'status', t: 'grooming_status' },
        { n: 'scheduled_at', t: 'timestamp' },
        { n: 'started_at', t: 'timestamp' },
        { n: 'completed_at', t: 'timestamp' },
        { n: 'notes', t: 'text' },
        { n: 'created_at', t: 'timestamp' },
        { n: 'updated_at', t: 'timestamp' }
      ]},
      grooming_services: { id: 'grooming_services', name: 'public.grooming_services', w: 260, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'session_id', t: 'bigint', fk: true },
        { n: 'service_type', t: 'varchar' },
        { n: 'price', t: 'decimal' },
        { n: 'is_additional', t: 'boolean' },
        { n: 'customer_confirmed', t: 'boolean' },
        { n: 'created_at', t: 'timestamp' }
      ]},
      medical_records: { id: 'medical_records', name: 'public.medical_records', w: 280, focal: true, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'pet_id', t: 'bigint', fk: true },
        { n: 'appointment_id', t: 'bigint', fk: true },
        { n: 'veterinarian_id', t: 'bigint', fk: true },
        { n: 'store_id', t: 'bigint', fk: true },
        { n: 'examination_date', t: 'timestamp' },
        { n: 'chief_complaint', t: 'text' },
        { n: 'symptoms', t: 'text[]' },
        { n: 'examination_results', t: 'jsonb' },
        { n: 'diagnosis', t: 'text' },
        { n: 'treatment_plan', t: 'text' },
        { n: 'status', t: 'varchar' },
        { n: 'created_at', t: 'timestamp' },
        { n: 'updated_at', t: 'timestamp' }
      ]},
      diagnoses: { id: 'diagnoses', name: 'public.diagnoses', w: 260, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'medical_record_id', t: 'bigint', fk: true },
        { n: 'diagnosis_code', t: 'varchar' },
        { n: 'diagnosis_name', t: 'varchar' },
        { n: 'severity', t: 'varchar' },
        { n: 'notes', t: 'text' },
        { n: 'created_at', t: 'timestamp' }
      ]},
      prescriptions: { id: 'prescriptions', name: 'public.prescriptions', w: 260, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'medical_record_id', t: 'bigint', fk: true },
        { n: 'veterinarian_id', t: 'bigint', fk: true },
        { n: 'prescription_date', t: 'timestamp' },
        { n: 'instructions', t: 'text' },
        { n: 'notes', t: 'text' },
        { n: 'status', t: 'varchar' },
        { n: 'created_at', t: 'timestamp' }
      ]},
      prescription_items: { id: 'prescription_items', name: 'public.prescription_items', w: 260, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'prescription_id', t: 'bigint', fk: true },
        { n: 'medication_name', t: 'varchar' },
        { n: 'dosage', t: 'varchar' },
        { n: 'frequency', t: 'varchar' },
        { n: 'duration', t: 'varchar' },
        { n: 'quantity', t: 'varchar' },
        { n: 'instructions', t: 'text' },
        { n: 'price', t: 'decimal' },
        { n: 'created_at', t: 'timestamp' }
      ]},
      follow_ups: { id: 'follow_ups', name: 'public.follow_ups', w: 260, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'medical_record_id', t: 'bigint', fk: true },
        { n: 'pet_id', t: 'bigint', fk: true },
        { n: 'scheduled_date', t: 'timestamp' },
        { n: 'purpose', t: 'varchar' },
        { n: 'notes', t: 'text' },
        { n: 'status', t: 'varchar' },
        { n: 'created_at', t: 'timestamp' }
      ]},
      vaccines: { id: 'vaccines', name: 'public.vaccines', w: 250, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'name', t: 'varchar' },
        { n: 'description', t: 'text' },
        { n: 'manufacturer', t: 'varchar' },
        { n: 'valid_months', t: 'int' },
        { n: 'is_active', t: 'boolean' },
        { n: 'created_at', t: 'timestamp' }
      ]},
      vaccine_batches: { id: 'vaccine_batches', name: 'public.vaccine_batches', w: 260, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'vaccine_id', t: 'bigint', fk: true },
        { n: 'store_id', t: 'bigint', fk: true },
        { n: 'batch_number', t: 'varchar' },
        { n: 'barcode', t: 'varchar' },
        { n: 'expiry_date', t: 'date' },
        { n: 'quantity', t: 'int' },
        { n: 'available_quantity', t: 'int' },
        { n: 'price', t: 'decimal' },
        { n: 'status', t: 'varchar' },
        { n: 'manufacturer', t: 'varchar' },
        { n: 'created_at', t: 'timestamp' }
      ]},
      vaccinations: { id: 'vaccinations', name: 'public.vaccinations', w: 260, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'pet_id', t: 'bigint', fk: true },
        { n: 'vaccine_id', t: 'bigint', fk: true },
        { n: 'batch_id', t: 'bigint', fk: true },
        { n: 'store_id', t: 'bigint', fk: true },
        { n: 'veterinarian_id', t: 'bigint', fk: true },
        { n: 'administered_at', t: 'timestamp' },
        { n: 'next_due_date', t: 'date' },
        { n: 'site', t: 'varchar' },
        { n: 'batch_number', t: 'varchar' },
        { n: 'notes', t: 'text' },
        { n: 'created_at', t: 'timestamp' }
      ]},
      vaccination_schedules: { id: 'vaccination_schedules', name: 'public.vaccination_schedules', w: 260, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'pet_id', t: 'bigint', fk: true },
        { n: 'vaccine_id', t: 'bigint', fk: true },
        { n: 'due_date', t: 'date' },
        { n: 'reminder_sent', t: 'boolean' },
        { n: 'status', t: 'varchar' },
        { n: 'created_at', t: 'timestamp' }
      ]},
      work_schedules: { id: 'work_schedules', name: 'public.work_schedules', w: 250, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'user_id', t: 'bigint', fk: true },
        { n: 'store_id', t: 'bigint', fk: true },
        { n: 'day_of_week', t: 'smallint' },
        { n: 'start_time', t: 'time' },
        { n: 'end_time', t: 'time' },
        { n: 'is_active', t: 'boolean' },
        { n: 'created_at', t: 'timestamp' }
      ]},
      staff_absences: { id: 'staff_absences', name: 'public.staff_absences', w: 250, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'user_id', t: 'bigint', fk: true },
        { n: 'store_id', t: 'bigint', fk: true },
        { n: 'start_date', t: 'date' },
        { n: 'end_date', t: 'date' },
        { n: 'reason', t: 'varchar' },
        { n: 'status', t: 'varchar' },
        { n: 'approved_by', t: 'bigint', fk: true },
        { n: 'created_at', t: 'timestamp' }
      ]},
      notifications: { id: 'notifications', name: 'public.notifications', w: 260, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'user_id', t: 'bigint', fk: true },
        { n: 'type', t: 'varchar' },
        { n: 'title', t: 'varchar' },
        { n: 'message', t: 'text' },
        { n: 'reference_type', t: 'varchar' },
        { n: 'reference_id', t: 'bigint' },
        { n: 'is_read', t: 'boolean' },
        { n: 'read_at', t: 'timestamp' },
        { n: 'created_at', t: 'timestamp' }
      ]},
      audit_logs: { id: 'audit_logs', name: 'public.audit_logs', w: 260, focal: false, columns: [
        { n: 'id', t: 'bigint', pk: true },
        { n: 'user_id', t: 'bigint', fk: true },
        { n: 'action', t: 'varchar' },
        { n: 'entity_type', t: 'varchar' },
        { n: 'entity_id', t: 'bigint' },
        { n: 'changes', t: 'jsonb' },
        { n: 'ip_address', t: 'varchar' },
        { n: 'user_agent', t: 'text' },
        { n: 'created_at', t: 'timestamp' }
      ]}
    };

    function createTableInstance(key, x, y) {
      const template = MASTER_TABLES[key];
      return {
        ...template,
        x: x,
        y: y
      };
    }

    // ============================================================
    // DIAGRAM TYPE RENDERERS
    // ============================================================

const SCHEMAS = {
      overview: {
        description: "Toàn bộ 38 bảng của hệ thống được hiển thị đầy đủ 100% cột và quan hệ. Sử dụng Pan / Zoom để khám phá mọi phân hệ.",
        tables: [
          createTableInstance('accounts', 40, 50),
          createTableInstance('users', 40, 370),
          createTableInstance('otps', 40, 650),
          createTableInstance('organizations', 40, 930),
          createTableInstance('stores', 40, 1180),
          createTableInstance('operating_hours', 40, 1470),
          createTableInstance('store_services', 40, 1690),

          createTableInstance('carts', 350, 50),
          createTableInstance('cart_items', 350, 240),
          createTableInstance('orders', 350, 470),
          createTableInstance('order_items', 350, 850),
          createTableInstance('payments', 350, 1100),
          createTableInstance('refunds', 350, 1420),
          createTableInstance('invoices', 350, 1780),
          createTableInstance('invoice_items', 350, 2180),
          createTableInstance('vouchers', 350, 2460),
          createTableInstance('voucher_usages', 350, 2850),

          createTableInstance('services', 660, 50),
          createTableInstance('appointments', 660, 310),
          createTableInstance('queues', 660, 780),
          createTableInstance('queue_entries', 660, 1000),
          createTableInstance('walkins', 660, 1310),
          createTableInstance('grooming_sessions', 660, 1690),
          createTableInstance('grooming_services', 660, 2060),

          createTableInstance('pets', 980, 50),
          createTableInstance('medical_records', 980, 380),
          createTableInstance('diagnoses', 980, 790),
          createTableInstance('prescriptions', 980, 1040),
          createTableInstance('prescription_items', 980, 1310),
          createTableInstance('follow_ups', 980, 1630),

          createTableInstance('vaccines', 1300, 50),
          createTableInstance('vaccine_batches', 1300, 280),
          createTableInstance('vaccinations', 1300, 640),
          createTableInstance('vaccination_schedules', 1300, 1000),
          createTableInstance('products', 1300, 1240),
          createTableInstance('inventory', 1300, 1570),
          createTableInstance('store_resources', 1300, 1790),
          createTableInstance('service_required_resources', 1300, 2040),
          createTableInstance('work_schedules', 1300, 2220),
          createTableInstance('staff_absences', 1300, 2490),
          createTableInstance('notifications', 1300, 2770),
          createTableInstance('audit_logs', 1300, 3080)
        ],
        relations: [
          { from: 'users', fromCol: 'account_id', to: 'accounts', toCol: 'id', type: 'RESTRICT' },
          { from: 'users', fromCol: 'organization_id', to: 'organizations', toCol: 'id', type: 'RESTRICT' },
          { from: 'users', fromCol: 'store_id', to: 'stores', toCol: 'id', type: 'RESTRICT' },
          { from: 'otps', fromCol: 'account_id', to: 'accounts', toCol: 'id', type: 'CASCADE' },
          { from: 'stores', fromCol: 'organization_id', to: 'organizations', toCol: 'id', type: 'RESTRICT' },
          { from: 'operating_hours', fromCol: 'store_id', to: 'stores', toCol: 'id', type: 'CASCADE' },
          { from: 'store_services', fromCol: 'store_id', to: 'stores', toCol: 'id', type: 'CASCADE' },
          { from: 'pets', fromCol: 'owner_id', to: 'users', toCol: 'id', type: 'RESTRICT' },
          { from: 'cart_items', fromCol: 'cart_id', to: 'carts', toCol: 'id', type: 'CASCADE' },
          { from: 'order_items', fromCol: 'order_id', to: 'orders', toCol: 'id', type: 'CASCADE' },
          { from: 'payments', fromCol: 'order_id', to: 'orders', toCol: 'id', type: 'CASCADE' },
          { from: 'refunds', fromCol: 'payment_id', to: 'payments', toCol: 'id', type: 'CASCADE' },
          { from: 'invoices', fromCol: 'order_id', to: 'orders', toCol: 'id', type: 'RESTRICT' },
          { from: 'invoice_items', fromCol: 'invoice_id', to: 'invoices', toCol: 'id', type: 'CASCADE' },
          { from: 'voucher_usages', fromCol: 'voucher_id', to: 'vouchers', toCol: 'id', type: 'CASCADE' },
          { from: 'appointments', fromCol: 'pet_id', to: 'pets', toCol: 'id', type: 'RESTRICT' },
          { from: 'appointments', fromCol: 'store_id', to: 'stores', toCol: 'id', type: 'RESTRICT' },
          { from: 'appointments', fromCol: 'service_id', to: 'services', toCol: 'id', type: 'RESTRICT' },
          { from: 'queue_entries', fromCol: 'queue_id', to: 'queues', toCol: 'id', type: 'CASCADE' },
          { from: 'walkins', fromCol: 'appointment_id', to: 'appointments', toCol: 'id', type: 'RESTRICT' },
          { from: 'grooming_sessions', fromCol: 'appointment_id', to: 'appointments', toCol: 'id', type: 'RESTRICT' },
          { from: 'grooming_services', fromCol: 'session_id', to: 'grooming_sessions', toCol: 'id', type: 'CASCADE' },
          { from: 'medical_records', fromCol: 'pet_id', to: 'pets', toCol: 'id', type: 'RESTRICT' },
          { from: 'diagnoses', fromCol: 'medical_record_id', to: 'medical_records', toCol: 'id', type: 'CASCADE' },
          { from: 'prescriptions', fromCol: 'medical_record_id', to: 'medical_records', toCol: 'id', type: 'CASCADE' },
          { from: 'prescription_items', fromCol: 'prescription_id', to: 'prescriptions', toCol: 'id', type: 'CASCADE' },
          { from: 'follow_ups', fromCol: 'medical_record_id', to: 'medical_records', toCol: 'id', type: 'CASCADE' },
          { from: 'vaccine_batches', fromCol: 'vaccine_id', to: 'vaccines', toCol: 'id', type: 'CASCADE' },
          { from: 'vaccinations', fromCol: 'batch_id', to: 'vaccine_batches', toCol: 'id', type: 'RESTRICT' },
          { from: 'vaccinations', fromCol: 'pet_id', to: 'pets', toCol: 'id', type: 'RESTRICT' },
          { from: 'vaccination_schedules', fromCol: 'pet_id', to: 'pets', toCol: 'id', type: 'CASCADE' },
          { from: 'inventory', fromCol: 'product_id', to: 'products', toCol: 'id', type: 'RESTRICT' },
          { from: 'inventory', fromCol: 'store_id', to: 'stores', toCol: 'id', type: 'RESTRICT' },
          { from: 'work_schedules', fromCol: 'user_id', to: 'users', toCol: 'id', type: 'CASCADE' },
          { from: 'staff_absences', fromCol: 'user_id', to: 'users', toCol: 'id', type: 'CASCADE' },
          { from: 'notifications', fromCol: 'user_id', to: 'users', toCol: 'id', type: 'CASCADE' },
          { from: 'audit_logs', fromCol: 'user_id', to: 'users', toCol: 'id', type: 'CASCADE' }
        ]
      },

      commerce: {
        description: "11 bảng trong Commerce & Billing: Carts, Cart Items, Orders, Order Items, Payments, Refunds, Invoices, Invoice Items, Vouchers, Voucher Usages, Products.",
        tables: [
          createTableInstance('carts', 40, 50),
          createTableInstance('cart_items', 40, 240),
          createTableInstance('orders', 340, 50),
          createTableInstance('order_items', 340, 420),
          createTableInstance('payments', 660, 50),
          createTableInstance('refunds', 660, 360),
          createTableInstance('invoices', 970, 50),
          createTableInstance('invoice_items', 970, 440),
          createTableInstance('vouchers', 1280, 50),
          createTableInstance('voucher_usages', 1280, 430),
          createTableInstance('products', 1280, 660)
        ],
        relations: [
          { from: 'cart_items', fromCol: 'cart_id', to: 'carts', toCol: 'id', type: 'CASCADE' },
          { from: 'order_items', fromCol: 'order_id', to: 'orders', toCol: 'id', type: 'CASCADE' },
          { from: 'payments', fromCol: 'order_id', to: 'orders', toCol: 'id', type: 'CASCADE' },
          { from: 'refunds', fromCol: 'payment_id', to: 'payments', toCol: 'id', type: 'CASCADE' },
          { from: 'invoices', fromCol: 'order_id', to: 'orders', toCol: 'id', type: 'RESTRICT' },
          { from: 'invoice_items', fromCol: 'invoice_id', to: 'invoices', toCol: 'id', type: 'CASCADE' },
          { from: 'voucher_usages', fromCol: 'voucher_id', to: 'vouchers', toCol: 'id', type: 'CASCADE' },
          { from: 'voucher_usages', fromCol: 'order_id', to: 'orders', toCol: 'id', type: 'CASCADE' }
        ]
      },

      clinical: {
        description: "12 bảng trong Clinical & Vaccines: Pets, Medical Records, Diagnoses, Prescriptions, Prescription Items, Follow-ups, Vaccines, Batches, Vaccinations, Schedules, Appointments, Users.",
        tables: [
          createTableInstance('pets', 40, 50),
          createTableInstance('appointments', 40, 380),
          createTableInstance('users', 40, 830),
          createTableInstance('medical_records', 340, 50),
          createTableInstance('diagnoses', 340, 460),
          createTableInstance('follow_ups', 340, 710),
          createTableInstance('prescriptions', 670, 50),
          createTableInstance('prescription_items', 670, 330),
          createTableInstance('vaccines', 980, 50),
          createTableInstance('vaccine_batches', 980, 270),
          createTableInstance('vaccinations', 980, 640),
          createTableInstance('vaccination_schedules', 980, 1000)
        ],
        relations: [
          { from: 'medical_records', fromCol: 'pet_id', to: 'pets', toCol: 'id', type: 'RESTRICT' },
          { from: 'medical_records', fromCol: 'appointment_id', to: 'appointments', toCol: 'id', type: 'RESTRICT' },
          { from: 'diagnoses', fromCol: 'medical_record_id', to: 'medical_records', toCol: 'id', type: 'CASCADE' },
          { from: 'prescriptions', fromCol: 'medical_record_id', to: 'medical_records', toCol: 'id', type: 'CASCADE' },
          { from: 'prescription_items', fromCol: 'prescription_id', to: 'prescriptions', toCol: 'id', type: 'CASCADE' },
          { from: 'follow_ups', fromCol: 'medical_record_id', to: 'medical_records', toCol: 'id', type: 'CASCADE' },
          { from: 'vaccine_batches', fromCol: 'vaccine_id', to: 'vaccines', toCol: 'id', type: 'CASCADE' },
          { from: 'vaccinations', fromCol: 'batch_id', to: 'vaccine_batches', toCol: 'id', type: 'RESTRICT' },
          { from: 'vaccinations', fromCol: 'pet_id', to: 'pets', toCol: 'id', type: 'RESTRICT' },
          { from: 'vaccination_schedules', fromCol: 'pet_id', to: 'pets', toCol: 'id', type: 'CASCADE' }
        ]
      },

      appointments: {
        description: "10 bảng Appointments, Queues & Grooming: Stores, Services, Pets, Appointments, Queues, Queue Entries, Walk-ins, Grooming Sessions, Grooming Services, Invoices.",
        tables: [
          createTableInstance('stores', 40, 50),
          createTableInstance('services', 40, 330),
          createTableInstance('pets', 40, 600),
          createTableInstance('appointments', 340, 50),
          createTableInstance('queues', 340, 520),
          createTableInstance('queue_entries', 340, 750),
          createTableInstance('walkins', 670, 50),
          createTableInstance('grooming_sessions', 980, 50),
          createTableInstance('grooming_services', 980, 420),
          createTableInstance('invoices', 980, 680)
        ],
        relations: [
          { from: 'appointments', fromCol: 'store_id', to: 'stores', toCol: 'id', type: 'RESTRICT' },
          { from: 'appointments', fromCol: 'service_id', to: 'services', toCol: 'id', type: 'RESTRICT' },
          { from: 'appointments', fromCol: 'pet_id', to: 'pets', toCol: 'id', type: 'RESTRICT' },
          { from: 'queues', fromCol: 'store_id', to: 'stores', toCol: 'id', type: 'CASCADE' },
          { from: 'queue_entries', fromCol: 'queue_id', to: 'queues', toCol: 'id', type: 'CASCADE' },
          { from: 'walkins', fromCol: 'appointment_id', to: 'appointments', toCol: 'id', type: 'RESTRICT' },
          { from: 'grooming_sessions', fromCol: 'appointment_id', to: 'appointments', toCol: 'id', type: 'RESTRICT' },
          { from: 'grooming_services', fromCol: 'session_id', to: 'grooming_sessions', toCol: 'id', type: 'CASCADE' },
          { from: 'invoices', fromCol: 'appointment_id', to: 'appointments', toCol: 'id', type: 'RESTRICT' }
        ]
      },

      operations: {
        description: "10 bảng Operations, Inventory & Workforce: Stores, Users, Products, Inventory, Store Resources, Service Required Resources, Work Schedules, Staff Absences, Notifications, Audit Logs.",
        tables: [
          createTableInstance('stores', 40, 50),
          createTableInstance('users', 40, 320),
          createTableInstance('products', 340, 50),
          createTableInstance('inventory', 340, 380),
          createTableInstance('store_resources', 340, 610),
          createTableInstance('service_required_resources', 340, 850),
          createTableInstance('work_schedules', 670, 50),
          createTableInstance('staff_absences', 670, 310),
          createTableInstance('notifications', 980, 50),
          createTableInstance('audit_logs', 980, 380)
        ],
        relations: [
          { from: 'inventory', fromCol: 'product_id', to: 'products', toCol: 'id', type: 'RESTRICT' },
          { from: 'inventory', fromCol: 'store_id', to: 'stores', toCol: 'id', type: 'RESTRICT' },
          { from: 'store_resources', fromCol: 'store_id', to: 'stores', toCol: 'id', type: 'CASCADE' },
          { from: 'work_schedules', fromCol: 'user_id', to: 'users', toCol: 'id', type: 'CASCADE' },
          { from: 'staff_absences', fromCol: 'user_id', to: 'users', toCol: 'id', type: 'CASCADE' },
          { from: 'notifications', fromCol: 'user_id', to: 'users', toCol: 'id', type: 'CASCADE' },
          { from: 'audit_logs', fromCol: 'user_id', to: 'users', toCol: 'id', type: 'CASCADE' }
        ]
      }
    };

    // ============================================================
    // USE CASE DIAGRAMS
    // ============================================================
