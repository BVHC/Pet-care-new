const fs = require('fs');

const missingModules = {
  "catalog_master_product": {
    "description": "UC5a · Master Product & Catalog: Global SKU, Organization Grant, Approval",
    "actors": [
      { "name": "Platform Admin", "color": "#7b4ac7" },
      { "name": "Organization Admin", "color": "#2e5aa8" },
      { "name": "Store Manager", "color": "#eb6c36" },
      { "name": "Customer", "color": "#2e5aa8" }
    ],
    "useCases": [
      { "id": "UC5a-1", "name": "Manage Master Catalog", "focal": true, "extensionPoints": ["phê duyệt Catalog mẫu / grant tenant"] },
      { "id": "UC5a-2", "name": "Grant Catalog To Org" },
      { "id": "UC5a-3", "name": "Configure Store Service" },
      { "id": "UC5a-4", "name": "Configure Store Product Price" },
      { "id": "UC5a-5", "name": "View Product & Service Catalog" }
    ],
    "relations": [
      { "actor": "Platform Admin", "uc": "UC5a-1" },
      { "actor": "Platform Admin", "uc": "UC5a-2" },
      { "actor": "Organization Admin", "uc": "UC5a-2" },
      { "actor": "Store Manager", "uc": "UC5a-3" },
      { "actor": "Store Manager", "uc": "UC5a-4" },
      { "actor": "Customer", "uc": "UC5a-5" }
    ]
  },
  "promotion_voucher": {
    "description": "UC18 · Promotion & Voucher: Campaigns, Store Rules, Discount Validation",
    "actors": [
      { "name": "Organization Admin", "color": "#7b4ac7" },
      { "name": "Store Manager", "color": "#eb6c36" },
      { "name": "Customer", "color": "#2e5aa8" },
      { "name": "System", "color": "#4f5d75" }
    ],
    "useCases": [
      { "id": "UC18-1", "name": "Create Promotion Campaign", "focal": true, "extensionPoints": ["cấu hình điều kiện áp dụng / quota"] },
      { "id": "UC18-2", "name": "Configure Store Promotion" },
      { "id": "UC18-3", "name": "Create Voucher Code" },
      { "id": "UC18-4", "name": "Apply Voucher At Checkout" },
      { "id": "UC18-5", "name": "Validate Voucher Conditions" },
      { "id": "UC18-6", "name": "Track Voucher Redemption" }
    ],
    "relations": [
      { "actor": "Organization Admin", "uc": "UC18-1" },
      { "actor": "Store Manager", "uc": "UC18-2" },
      { "actor": "Organization Admin", "uc": "UC18-3" },
      { "actor": "Customer", "uc": "UC18-4" },
      { "actor": "System", "uc": "UC18-5", "type": "include" },
      { "actor": "System", "uc": "UC18-6", "type": "include" }
    ]
  },
  "membership_loyalty": {
    "description": "UC19 · Membership & Loyalty: Tier Progression, Points Earning & Redemption",
    "actors": [
      { "name": "Customer", "color": "#2e5aa8" },
      { "name": "Receptionist", "color": "#eb6c36" },
      { "name": "Store Manager", "color": "#7b4ac7" },
      { "name": "System", "color": "#4f5d75" }
    ],
    "useCases": [
      { "id": "UC19-1", "name": "Register Membership Tier", "focal": true, "extensionPoints": ["nâng hạng hội viên / gia hạn kỳ hạn"] },
      { "id": "UC19-2", "name": "View Loyalty Balance" },
      { "id": "UC19-3", "name": "Redeem Points For Reward" },
      { "id": "UC19-4", "name": "Accumulate Points On Payment" },
      { "id": "UC19-5", "name": "Adjust Points Manually" },
      { "id": "UC19-6", "name": "Process Tier Expiry" }
    ],
    "relations": [
      { "actor": "Customer", "uc": "UC19-1" },
      { "actor": "Customer", "uc": "UC19-2" },
      { "actor": "Customer", "uc": "UC19-3" },
      { "actor": "Receptionist", "uc": "UC19-3" },
      { "actor": "Store Manager", "uc": "UC19-5" },
      { "actor": "System", "uc": "UC19-4", "type": "include" },
      { "actor": "System", "uc": "UC19-6", "type": "include" }
    ]
  },
  "package_management": {
    "description": "UC20 · Service Packages: Prepaid Bundles, Usage Deduction & Refund Policy",
    "actors": [
      { "name": "Customer", "color": "#2e5aa8" },
      { "name": "Receptionist", "color": "#eb6c36" },
      { "name": "Store Manager", "color": "#7b4ac7" },
      { "name": "System", "color": "#4f5d75" }
    ],
    "useCases": [
      { "id": "UC20-1", "name": "Purchase Service Package", "focal": true, "extensionPoints": ["gói spa 10 buổi / combo vaccine"] },
      { "id": "UC20-2", "name": "Activate Package Upon Check-in" },
      { "id": "UC20-3", "name": "Deduct Package Usage Session" },
      { "id": "UC20-4", "name": "Cancel Package With Refund" },
      { "id": "UC20-5", "name": "Track Remaining Sessions" },
      { "id": "UC20-6", "name": "Process Package Expiry" }
    ],
    "relations": [
      { "actor": "Customer", "uc": "UC20-1" },
      { "actor": "Customer", "uc": "UC20-5" },
      { "actor": "Receptionist", "uc": "UC20-2" },
      { "actor": "Receptionist", "uc": "UC20-3" },
      { "actor": "Store Manager", "uc": "UC20-4" },
      { "actor": "System", "uc": "UC20-6", "type": "include" }
    ]
  },
  "incident_management": {
    "description": "UC21 · Incident Management: Clinical & Grooming Accidents, Escalation & Remedies",
    "actors": [
      { "name": "Veterinarian", "color": "#2e5aa8" },
      { "name": "Groomer", "color": "#eb6c36" },
      { "name": "Store Manager", "color": "#7b4ac7" },
      { "name": "System", "color": "#4f5d75" }
    ],
    "useCases": [
      { "id": "UC21-1", "name": "Record Service Incident", "focal": true, "extensionPoints": ["tai nạn spa / phản ứng sốc thuốc"] },
      { "id": "UC21-2", "name": "Classify Severity Level" },
      { "id": "UC21-3", "name": "Investigate Incident Cause" },
      { "id": "UC21-4", "name": "Escalate Critical Incident" },
      { "id": "UC21-5", "name": "Execute Compensation & Remedy" },
      { "id": "UC21-6", "name": "Send Incident Notification" }
    ],
    "relations": [
      { "actor": "Veterinarian", "uc": "UC21-1" },
      { "actor": "Groomer", "uc": "UC21-1" },
      { "actor": "Store Manager", "uc": "UC21-2" },
      { "actor": "Store Manager", "uc": "UC21-3" },
      { "actor": "Store Manager", "uc": "UC21-4" },
      { "actor": "Store Manager", "uc": "UC21-5" },
      { "actor": "System", "uc": "UC21-6", "type": "include" }
    ]
  },
  "consent_privacy": {
    "description": "UC22 · Consent & Privacy: Cross-Store EMR Consent, Emergency Override & GDPR",
    "actors": [
      { "name": "Customer", "color": "#2e5aa8" },
      { "name": "Veterinarian", "color": "#7b4ac7" },
      { "name": "Organization Admin", "color": "#eb6c36" },
      { "name": "System", "color": "#4f5d75" }
    ],
    "useCases": [
      { "id": "UC22-1", "name": "Grant Medical Consent", "focal": true, "extensionPoints": ["ký cam kết phẫu thuật / gây mê"] },
      { "id": "UC22-2", "name": "Verify Cross-Store Consent OTP" },
      { "id": "UC22-3", "name": "Emergency Override Access" },
      { "id": "UC22-4", "name": "Revoke Medical Consent" },
      { "id": "UC22-5", "name": "Request Data Export / Deletion" },
      { "id": "UC22-6", "name": "Process 24h Consent Expiry" }
    ],
    "relations": [
      { "actor": "Customer", "uc": "UC22-1" },
      { "actor": "Customer", "uc": "UC22-2" },
      { "actor": "Customer", "uc": "UC22-4" },
      { "actor": "Customer", "uc": "UC22-5" },
      { "actor": "Veterinarian", "uc": "UC22-2" },
      { "actor": "Veterinarian", "uc": "UC22-3" },
      { "actor": "Organization Admin", "uc": "UC22-5" },
      { "actor": "System", "uc": "UC22-6", "type": "include" }
    ]
  },
  "reporting_analytics": {
    "description": "UC24 · Reporting & Analytics: Revenue, Capacity, Discrepancies & Doctor KPIs",
    "actors": [
      { "name": "Store Manager", "color": "#eb6c36" },
      { "name": "Organization Admin", "color": "#7b4ac7" },
      { "name": "Finance Staff", "color": "#2e5aa8" },
      { "name": "Platform Admin", "color": "#4f5d75" }
    ],
    "useCases": [
      { "id": "UC24-1", "name": "View Store Revenue Report", "focal": true, "extensionPoints": ["lọc theo kênh POS / Online / Dịch vụ"] },
      { "id": "UC24-2", "name": "View Appointment & Service KPIs" },
      { "id": "UC24-3", "name": "View Inventory Discrepancy Report" },
      { "id": "UC24-4", "name": "View Staff Commission Report" },
      { "id": "UC24-5", "name": "Compare Cross-Store Performance" },
      { "id": "UC24-6", "name": "Reconcile Financial Settlement" }
    ],
    "relations": [
      { "actor": "Store Manager", "uc": "UC24-1" },
      { "actor": "Store Manager", "uc": "UC24-2" },
      { "actor": "Store Manager", "uc": "UC24-3" },
      { "actor": "Store Manager", "uc": "UC24-4" },
      { "actor": "Organization Admin", "uc": "UC24-5" },
      { "actor": "Finance Staff", "uc": "UC24-6" },
      { "actor": "Platform Admin", "uc": "UC24-5" }
    ]
  },
  "audit_management": {
    "description": "UC25 · Audit Management: Immutable Logs, EMR Access Tracking & IAM Audits",
    "actors": [
      { "name": "Platform Admin", "color": "#7b4ac7" },
      { "name": "Organization Admin", "color": "#2e5aa8" },
      { "name": "Store Manager", "color": "#eb6c36" },
      { "name": "System", "color": "#4f5d75" }
    ],
    "useCases": [
      { "id": "UC25-1", "name": "Record Immutable Audit Log" },
      { "id": "UC25-2", "name": "View Platform Audit Trail", "focal": true, "extensionPoints": ["truy vết can thiệp DB / IAM / Tài chính"] },
      { "id": "UC25-3", "name": "Track Permission Changes" },
      { "id": "UC25-4", "name": "Track Medical EMR Access" },
      { "id": "UC25-5", "name": "Track Payment & Refund Audit" },
      { "id": "UC25-6", "name": "Track Inventory Movement Audit" }
    ],
    "relations": [
      { "actor": "Platform Admin", "uc": "UC25-2" },
      { "actor": "Platform Admin", "uc": "UC25-3" },
      { "actor": "Organization Admin", "uc": "UC25-3" },
      { "actor": "Organization Admin", "uc": "UC25-4" },
      { "actor": "Store Manager", "uc": "UC25-6" },
      { "actor": "System", "uc": "UC25-1", "type": "include" }
    ]
  }
};

let content = fs.readFileSync('./docs/diagrams/uc/data.js', 'utf8');

// Insert new diagrams right before the closing "};"
const lastBraceIdx = content.lastIndexOf('};');
if (lastBraceIdx !== -1) {
  let toAppend = ',\n';
  const keys = Object.keys(missingModules);
  keys.forEach((k, i) => {
    toAppend += `  "${k}": ${JSON.stringify(missingModules[k], null, 2)}${i === keys.length - 1 ? '' : ',\n'}`;
  });
  content = content.slice(0, lastBraceIdx) + toAppend + '\n' + content.slice(lastBraceIdx);
  fs.writeFileSync('./docs/diagrams/uc/data.js', content, 'utf8');
  console.log('Successfully appended 8 modules into uc/data.js!');
} else {
  console.error('Closing brace not found in uc/data.js');
}
