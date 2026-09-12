const fs = require('fs');

const all25Modules = [
  {
    id: 'auth',
    num: '01',
    icon: '🔐',
    name: '01. Authentication & OTP',
    items: [
      { key: 'auth_customer_registration', eyebrow: 'Use Case · Authentication & OTP', title: 'UC1a - Customer Self-Registration & OTP', name: 'Self-Registration', tag: '5 UC' },
      { key: 'auth_customer_login', eyebrow: 'Use Case · Authentication & OTP', title: 'UC1b - Customer Login & Session', name: 'Login & Session', tag: '4 UC' },
      { key: 'auth_staff_onboarding', eyebrow: 'Use Case · Authentication & OTP', title: 'UC1c - Staff Direct Onboarding (D-04)', name: 'Staff Onboarding', tag: '4 UC' },
      { key: 'auth_account_management', eyebrow: 'Use Case · Authentication & OTP', title: 'UC1d - Account Lifecycle & Lockout', name: 'Account Lifecycle', tag: '4 UC' }
    ]
  },
  {
    id: 'iam',
    num: '02',
    icon: '🛡️',
    name: '02. Identity & Access (IAM)',
    items: [
      { key: 'iam_permission', eyebrow: 'Use Case · IAM & Permissions', title: 'UC2a - Permission Matrix & Scopes', name: 'Permission Matrix', tag: '5 UC' },
      { key: 'iam_role', eyebrow: 'Use Case · IAM & Permissions', title: 'UC2b - Role Assignment & Store Scopes', name: 'Role Assignment', tag: '5 UC' }
    ]
  },
  {
    id: 'store',
    num: '03',
    icon: '🏢',
    name: '03. Organization & Store',
    items: [
      { key: 'store_store_mgmt', eyebrow: 'Use Case · Organization & Store', title: 'UC3a - Store Provisioning & Policies', name: 'Store Lifecycle', tag: '5 UC' },
      { key: 'store_store_archive', eyebrow: 'Use Case · Organization & Store', title: 'UC3b - Store Suspension & Archive', name: 'Suspend & Archive', tag: '4 UC' }
    ]
  },
  {
    id: 'caregiver',
    num: '04',
    icon: '🐾',
    name: '04. Customer & Pet Caregiver',
    items: [
      { key: 'caregiver_delegation', eyebrow: 'Use Case · Customer & Pet', title: 'UC4a - Pet Profile & Delegation Invite', name: 'Delegation Invite', tag: '5 UC' },
      { key: 'caregiver_access', eyebrow: 'Use Case · Customer & Pet', title: 'UC4b - Caregiver Delegated Actions', name: 'Delegated Actions', tag: '4 UC' }
    ]
  },
  {
    id: 'catalog',
    num: '05',
    icon: '📚',
    name: '05. Service & Product Catalog',
    items: [
      { key: 'catalog_master_product', eyebrow: 'Use Case · Catalog Management', title: 'UC5a - Master Catalog & Store Pricing', name: 'Master Catalog', tag: '5 UC' }
    ]
  },
  {
    id: 'booking',
    num: '06',
    icon: '📅',
    name: '06. Appointment & Scheduling',
    items: [
      { key: 'booking_slot_reservation', eyebrow: 'Use Case · Appointment & Scheduling', title: 'UC6a - Pre-booking Slot Reservation 15m', name: 'Slot Hold 15m', tag: '5 UC' },
      { key: 'booking_appointment_booking', eyebrow: 'Use Case · Appointment & Scheduling', title: 'UC6b - Appointment Confirmation Flow', name: 'Booking Flow', tag: '6 UC' },
      { key: 'booking_appointment_checkin', eyebrow: 'Use Case · Appointment & Scheduling', title: 'UC6c - Check-in & Service Start', name: 'Check-in & Service', tag: '5 UC' },
      { key: 'booking_reschedule_cancel', eyebrow: 'Use Case · Appointment & Scheduling', title: 'UC6d - Reschedule & Cancellation', name: 'Reschedule & Cancel', tag: '5 UC' }
    ]
  },
  {
    id: 'queue',
    num: '07',
    icon: '🚶',
    name: '07. Walk-in & Queue Management',
    items: [
      { key: 'queue_register_queue', eyebrow: 'Use Case · Walk-in & Queue', title: 'UC7a - Queue Ticket Registration (FIFO)', name: 'Ticket Register', tag: '5 UC' },
      { key: 'queue_queue_management', eyebrow: 'Use Case · Walk-in & Queue', title: 'UC7b - Calling, No-Show & Emergency', name: 'Queue Calling', tag: '5 UC' },
      { key: 'queue_walkin_bridge', eyebrow: 'Use Case · Walk-in & Queue', title: 'UC7c - Walk-in to Appointment Bridge', name: 'Lifecycle Bridge', tag: '5 UC' }
    ]
  },
  {
    id: 'workforce',
    num: '08',
    icon: '👥',
    name: '08. Workforce Management',
    items: [
      { key: 'workforce_schedule', eyebrow: 'Use Case · Workforce Management', title: 'UC8a - Shift Scheduling & Assignment', name: 'Shift Scheduling', tag: '4 UC' },
      { key: 'workforce_absence', eyebrow: 'Use Case · Workforce Management', title: 'UC8b - Absence Handling & Replacement', name: 'Absence & Swap', tag: '4 UC' }
    ]
  },
  {
    id: 'clinical',
    num: '09',
    icon: '🩺',
    name: '09. Veterinary & Clinical EMR',
    items: [
      { key: 'clinical_clinical_examination', eyebrow: 'Use Case · Veterinary Clinical', title: 'UC9a - Examination & Diagnosis', name: 'Clinical Exam', tag: '5 UC' },
      { key: 'clinical_prescription', eyebrow: 'Use Case · Veterinary Clinical', title: 'UC9b - Prescription & Treatment', name: 'Prescription Flow', tag: '5 UC' },
      { key: 'clinical_cross_store_emr', eyebrow: 'Use Case · Veterinary Clinical', title: 'UC9c - Cross-Store EMR Consent (24h)', name: 'Cross-Store Consent', tag: '5 UC' },
      { key: 'clinical_emergency_override', eyebrow: 'Use Case · Veterinary Clinical', title: 'UC9d - Emergency Access Override', name: 'Emergency Override', tag: '5 UC' }
    ]
  },
  {
    id: 'vaccination',
    num: '10',
    icon: '💉',
    name: '10. Vaccination Management',
    items: [
      { key: 'vaccination_routine', eyebrow: 'Use Case · Vaccination Management', title: 'UC10a - Routine Vaccine Screening', name: 'Routine Screening', tag: '5 UC' },
      { key: 'vaccination_therapeutic', eyebrow: 'Use Case · Vaccination Management', title: 'UC10b - Therapeutic Treatment Injection', name: 'Therapeutic Injection', tag: '5 UC' },
      { key: 'vaccination_schedule', eyebrow: 'Use Case · Vaccination Management', title: 'UC10c - Auto-Recall Schedule', name: 'Recall Schedule', tag: '5 UC' }
    ]
  },
  {
    id: 'grooming',
    num: '11',
    icon: '✂️',
    name: '11. Grooming Management',
    items: [
      { key: 'grooming_grooming_session', eyebrow: 'Use Case · Grooming Service', title: 'UC11a - Grooming Intake & Inspection', name: 'Intake Inspection', tag: '5 UC' },
      { key: 'grooming_grooming_surcharge', eyebrow: 'Use Case · Grooming Service', title: 'UC11b - Add-on Surcharge Workflow (D-02)', name: 'Add-on Surcharge', tag: '5 UC' },
      { key: 'grooming_grooming_emergency', eyebrow: 'Use Case · Grooming Service', title: 'UC11c - Grooming Emergency Abort', name: 'Emergency Abort', tag: '4 UC' }
    ]
  },
  {
    id: 'inventory',
    num: '12',
    icon: '📦',
    name: '12. Inventory & Warehouse',
    items: [
      { key: 'inventory_receive', eyebrow: 'Use Case · Inventory & Warehouse', title: 'UC12a - Receiving & Stock Count', name: 'Stock Receiving', tag: '6 UC' },
      { key: 'inventory_transfer', eyebrow: 'Use Case · Inventory & Warehouse', title: 'UC12b - Store-to-Store Stock Transfer', name: 'Transfer Workflow', tag: '6 UC' },
      { key: 'inventory_alerts', eyebrow: 'Use Case · Inventory & Warehouse', title: 'UC12c - Inventory Alerts & FEFO Picking', name: 'FEFO & Low Stock', tag: '5 UC' }
    ]
  },
  {
    id: 'procurement',
    num: '13',
    icon: '📑',
    name: '13. Procurement Management',
    items: [
      { key: 'procurement_pr', eyebrow: 'Use Case · Procurement', title: 'UC13a - Purchase Request (PR) Approval', name: 'Purchase Request', tag: '5 UC' },
      { key: 'procurement_po', eyebrow: 'Use Case · Procurement', title: 'UC13b - Purchase Order (PO) & Receiving', name: 'Purchase Order', tag: '6 UC' }
    ]
  },
  {
    id: 'commerce',
    num: '14',
    icon: '🛒',
    name: '14. Order Management (Commerce & POS)',
    items: [
      { key: 'commerce_cart', eyebrow: 'Use Case · Order Management', title: 'UC14a - Cart & Stock Hold 15m', name: 'Cart Hold 15m', tag: '5 UC' },
      { key: 'commerce_checkout', eyebrow: 'Use Case · Order Management', title: 'UC14b - Store Fulfillment & Pickup', name: 'Store Pickup Order', tag: '5 UC' },
      { key: 'commerce_fulfillment', eyebrow: 'Use Case · Order Management', title: 'UC14c - POS In-store Instant Checkout', name: 'POS Instant Checkout', tag: '5 UC' }
    ]
  },
  {
    id: 'billing',
    num: '15',
    icon: '🧾',
    name: '15. Billing & Invoicing',
    items: [
      { key: 'billing_invoice_creation', eyebrow: 'Use Case · Billing & Settlement', title: 'UC15a - Invoice Generation & Discounts', name: 'Invoice Drafting', tag: '5 UC' },
      { key: 'billing_invoice_settlement', eyebrow: 'Use Case · Billing & Settlement', title: 'UC15b - Invoice Paid & Immutability (D-01)', name: 'Invoice Settlement', tag: '4 UC' },
      { key: 'billing_invoice_void', eyebrow: 'Use Case · Billing & Settlement', title: 'UC15c - Invoice Discard & Voiding', name: 'Void Invoice', tag: '4 UC' }
    ]
  },
  {
    id: 'payment',
    num: '16',
    icon: '💳',
    name: '16. Payment Management',
    items: [
      { key: 'payment_online', eyebrow: 'Use Case · Payment Management', title: 'UC16a - Online Gateway & Webhook', name: 'Online Gateway', tag: '5 UC' },
      { key: 'payment_cash', eyebrow: 'Use Case · Payment Management', title: 'UC16b - Cash Payment At Counter', name: 'Cash POS Payment', tag: '4 UC' }
    ]
  },
  {
    id: 'refund',
    num: '17',
    icon: '🔄',
    name: '17. Refund Management',
    items: [
      { key: 'payment_refund', eyebrow: 'Use Case · Refund Management', title: 'UC17a - Maker-Checker Refund Workflow', name: 'Maker-Checker Refund', tag: '5 UC' }
    ]
  },
  {
    id: 'promotion',
    num: '18',
    icon: '🏷️',
    name: '18. Promotion & Voucher',
    items: [
      { key: 'promotion_voucher', eyebrow: 'Use Case · Promotion & Voucher', title: 'UC18 - Promotion Campaign & Voucher Validation', name: 'Voucher Campaigns', tag: '6 UC' }
    ]
  },
  {
    id: 'membership',
    num: '19',
    icon: '👑',
    name: '19. Membership & Loyalty',
    items: [
      { key: 'membership_loyalty', eyebrow: 'Use Case · Membership & Loyalty', title: 'UC19 - Loyalty Points & Tier Progression', name: 'Membership Tiers', tag: '6 UC' }
    ]
  },
  {
    id: 'package',
    num: '20',
    icon: '🎟️',
    name: '20. Service Packages',
    items: [
      { key: 'package_management', eyebrow: 'Use Case · Service Packages', title: 'UC20 - Prepaid Bundles & Usage Deduction', name: 'Prepaid Bundles', tag: '6 UC' }
    ]
  },
  {
    id: 'incident',
    num: '21',
    icon: '⚠️',
    name: '21. Incident Management',
    items: [
      { key: 'incident_management', eyebrow: 'Use Case · Incident Management', title: 'UC21 - Clinic & Grooming Incident Escalation', name: 'Incident Escalation', tag: '6 UC' }
    ]
  },
  {
    id: 'consent',
    num: '22',
    icon: '📝',
    name: '22. Consent & Privacy',
    items: [
      { key: 'consent_privacy', eyebrow: 'Use Case · Consent & Privacy', title: 'UC22 - Cross-Store EMR Consent & GDPR', name: 'Medical Consent', tag: '6 UC' }
    ]
  },
  {
    id: 'notification',
    num: '23',
    icon: '🔔',
    name: '23. Notification System',
    items: [
      { key: 'notification_dispatch', eyebrow: 'Use Case · Notification System', title: 'UC23a - Multi-channel Notification Dispatch', name: 'Dispatch Engine', tag: '5 UC' },
      { key: 'notification_reminder', eyebrow: 'Use Case · Notification System', title: 'UC23b - Appointment & Vaccine Auto-reminder', name: 'Auto Reminders', tag: '4 UC' }
    ]
  },
  {
    id: 'reporting',
    num: '24',
    icon: '📊',
    name: '24. Reporting & Analytics',
    items: [
      { key: 'reporting_analytics', eyebrow: 'Use Case · Reporting & Analytics', title: 'UC24 - Revenue, Inventory & Doctor KPIs', name: 'Store Analytics', tag: '6 UC' }
    ]
  },
  {
    id: 'audit',
    num: '25',
    icon: '🔍',
    name: '25. Audit Management',
    items: [
      { key: 'audit_management', eyebrow: 'Use Case · Audit Management', title: 'UC25 - Immutable Audit Trail & Access Logs', name: 'Audit Logs', tag: '6 UC' }
    ]
  }
];

function buildSubAccordionHtml(mod) {
  const totalUC = mod.items.reduce((acc, it) => acc + parseInt(it.tag) || 4, 0);
  let html = `          <!-- Sub-group: ${mod.name} -->\n`;
  html += `          <div class="sub-nav-group" id="sub-uc-${mod.id}">\n`;
  html += `            <div class="sub-nav-header" onclick="toggleGroup('sub-uc-${mod.id}')">\n`;
  html += `              <div class="sub-nav-title">\n`;
  html += `                <span class="nav-icon">${mod.icon}</span> ${mod.name}\n`;
  html += `              </div>\n`;
  html += `              <div class="sub-nav-meta">\n`;
  html += `                <span class="table-count-pill">${mod.items.length} UC</span>\n`;
  html += `                <span class="chevron-sub">▶</span>\n`;
  html += `              </div>\n`;
  html += `            </div>\n`;
  html += `            <div class="sub-nav-content">\n`;
  mod.items.forEach(it => {
    html += `              <div class="menu-item" onclick="loadDiagram('${it.key}', '${it.eyebrow}', '${it.title}')">\n`;
    html += `                <div class="menu-item-left"><span class="badge">UC</span> ${it.name}</div>\n`;
    html += `                <span class="table-count-pill">${it.tag}</span>\n`;
    html += `              </div>\n`;
  });
  html += `            </div>\n`;
  html += `          </div>\n`;
  return html;
}

// Build the complete Use Cases Nav Group
let navGroupHtml = `      <!-- 2. Use Case Diagrams Group (25 Modules + 9 Overviews) -->\n`;
navGroupHtml += `      <div class="nav-group" id="group-usecases">\n`;
navGroupHtml += `        <div class="nav-group-header" onclick="toggleGroup('group-usecases')">\n`;
navGroupHtml += `          <div class="nav-group-title">\n`;
navGroupHtml += `            <span class="nav-icon">🎯</span> Use Cases (25 Modules · 62 Diagrams)\n`;
navGroupHtml += `          </div>\n`;
navGroupHtml += `          <span class="chevron">▶</span>\n`;
navGroupHtml += `        </div>\n`;
navGroupHtml += `        <div class="nav-group-content">\n`;
navGroupHtml += `          <!-- Sub-group: Overview Domains -->\n`;
navGroupHtml += `          <div class="sub-nav-group open" id="sub-uc-overview">\n`;
navGroupHtml += `            <div class="sub-nav-header" onclick="toggleGroup('sub-uc-overview')">\n`;
navGroupHtml += `              <div class="sub-nav-title">\n`;
navGroupHtml += `                <span class="nav-icon">⭐</span> Overview Domains\n`;
navGroupHtml += `              </div>\n`;
navGroupHtml += `              <div class="sub-nav-meta">\n`;
navGroupHtml += `                <span class="table-count-pill">9 Core</span>\n`;
navGroupHtml += `                <span class="chevron-sub">▶</span>\n`;
navGroupHtml += `              </div>\n`;
navGroupHtml += `            </div>\n`;
navGroupHtml += `            <div class="sub-nav-content">\n`;
navGroupHtml += `              <div class="menu-item" onclick="loadDiagram('uc1', 'Use Case · Auth & Identity', 'UC1 · Authentication & Identity Management')"><div class="menu-item-left"><span class="badge">UC1</span> Auth & Identity</div><span class="table-count-pill">Core</span></div>\n`;
navGroupHtml += `              <div class="menu-item" onclick="loadDiagram('uc2', 'Use Case · Appointment & Scheduling', 'UC2 · Appointment Booking & Scheduling')"><div class="menu-item-left"><span class="badge">UC2</span> Appointment Booking</div><span class="table-count-pill">Core</span></div>\n`;
navGroupHtml += `              <div class="menu-item" onclick="loadDiagram('uc3', 'Use Case · Walk-in & Queue', 'UC3 · Walk-in Queue & Emergency Triage')"><div class="menu-item-left"><span class="badge">UC3</span> Walk-in Queue</div><span class="table-count-pill">Core</span></div>\n`;
navGroupHtml += `              <div class="menu-item" onclick="loadDiagram('uc4', 'Use Case · Commerce & Order', 'UC4 · Commerce & Order Management')"><div class="menu-item-left"><span class="badge">UC4</span> Commerce & Order</div><span class="table-count-pill">Core</span></div>\n`;
navGroupHtml += `              <div class="menu-item" onclick="loadDiagram('uc5', 'Use Case · Clinical & EMR', 'UC5 · Clinical Examination & EMR')"><div class="menu-item-left"><span class="badge">UC5</span> Clinical & EMR</div><span class="table-count-pill">Core</span></div>\n`;
navGroupHtml += `              <div class="menu-item" onclick="loadDiagram('uc6', 'Use Case · Grooming Service', 'UC6 · Grooming & Surcharge Workflow')"><div class="menu-item-left"><span class="badge">UC6</span> Grooming Service</div><span class="table-count-pill">Core</span></div>\n`;
navGroupHtml += `              <div class="menu-item" onclick="loadDiagram('uc7', 'Use Case · Inventory & Stock', 'UC7 · Inventory Management & Warehouse')"><div class="menu-item-left"><span class="badge">UC7</span> Inventory & Stock</div><span class="table-count-pill">Core</span></div>\n`;
navGroupHtml += `              <div class="menu-item" onclick="loadDiagram('uc8', 'Use Case · Procurement', 'UC8 · Procurement & Purchase Orders')"><div class="menu-item-left"><span class="badge">UC8</span> Procurement</div><span class="table-count-pill">Core</span></div>\n`;
navGroupHtml += `              <div class="menu-item" onclick="loadDiagram('uc9', 'Use Case · Billing & Settlement', 'UC9 · Billing, Payment & Invoicing')"><div class="menu-item-left"><span class="badge">UC9</span> Billing & Settlement</div><span class="table-count-pill">Core</span></div>\n`;
navGroupHtml += `            </div>\n`;
navGroupHtml += `          </div>\n`;

all25Modules.forEach(mod => {
  navGroupHtml += buildSubAccordionHtml(mod);
});

navGroupHtml += `        </div>\n`;
navGroupHtml += `      </div>\n`;

// 1. Update index.html
let indexHtml = fs.readFileSync('./docs/diagrams/index.html', 'utf8');
const ucStartIdx = indexHtml.indexOf('<!-- 2. Use Case Diagrams Group');
const seqStartIdx = indexHtml.indexOf('<!-- 3. Sequence Diagrams Group');
if (ucStartIdx !== -1 && seqStartIdx !== -1) {
  indexHtml = indexHtml.slice(0, ucStartIdx) + navGroupHtml + '\n' + indexHtml.slice(seqStartIdx);
  fs.writeFileSync('./docs/diagrams/index.html', indexHtml, 'utf8');
  console.log('Successfully updated index.html with all 25 modules!');
} else {
  console.error('Could not find markers in index.html');
}

// 2. Update uc/index.html
let ucIndexHtml = fs.readFileSync('./docs/diagrams/uc/index.html', 'utf8');
// In uc/index.html, find sidebar-menu contents
const menuStart = ucIndexHtml.indexOf('<nav class="sidebar-menu">');
const menuEnd = ucIndexHtml.indexOf('</nav>');
if (menuStart !== -1 && menuEnd !== -1) {
  const innerNav = `\n` + navGroupHtml + `    `;
  ucIndexHtml = ucIndexHtml.slice(0, menuStart + '<nav class="sidebar-menu">'.length) + innerNav + ucIndexHtml.slice(menuEnd);
  fs.writeFileSync('./docs/diagrams/uc/index.html', ucIndexHtml, 'utf8');
  console.log('Successfully updated uc/index.html with all 25 modules!');
} else {
  console.error('Could not find sidebar-menu in uc/index.html');
}
