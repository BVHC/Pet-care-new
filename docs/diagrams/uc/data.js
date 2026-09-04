/**
 * Pet Care Ecosystem · Use Case Diagrams Dataset
 * Contains both high-level domain overviews (uc1-uc9) and all 45 granular module diagrams from 16 files
 */
const UC_DIAGRAMS = {
  "uc1": {
    "description": "Use Case Auth & Identity: Register, Login, OTP verification, Staff onboarding (D-04)",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Staff",
        "color": "#eb6c36"
      },
      {
        "name": "Platform Admin",
        "color": "#7b4ac7"
      },
      {
        "name": "System",
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC1",
        "name": "Register Account",
        "focal": true
      },
      {
        "id": "UC2",
        "name": "Verify OTP"
      },
      {
        "id": "UC3",
        "name": "Login"
      },
      {
        "id": "UC4",
        "name": "Logout"
      },
      {
        "id": "UC5",
        "name": "Resend OTP"
      },
      {
        "id": "UC6",
        "name": "Create Staff (D-04)"
      },
      {
        "id": "UC7",
        "name": "Change Password"
      },
      {
        "id": "UC8",
        "name": "Lock Account"
      },
      {
        "id": "UC9",
        "name": "Deactivate Account"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC1"
      },
      {
        "actor": "Customer",
        "uc": "UC2"
      },
      {
        "actor": "Customer",
        "uc": "UC3"
      },
      {
        "actor": "Customer",
        "uc": "UC4"
      },
      {
        "actor": "Customer",
        "uc": "UC5"
      },
      {
        "actor": "Customer",
        "uc": "UC7"
      },
      {
        "actor": "Staff",
        "uc": "UC3"
      },
      {
        "actor": "Staff",
        "uc": "UC4"
      },
      {
        "actor": "Staff",
        "uc": "UC7"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC6"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC8"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC9"
      },
      {
        "actor": "System",
        "uc": "UC2",
        "type": "include"
      },
      {
        "actor": "System",
        "uc": "UC5",
        "type": "include"
      }
    ]
  },
  "uc2": {
    "description": "Use Case Appointment & Scheduling: HoldSlot (15m), Book, Reschedule, Cancel, Check-in",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Caregiver",
        "color": "#5b8c5a"
      },
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "System",
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC10",
        "name": "Hold Slot (15m TTL)",
        "focal": true
      },
      {
        "id": "UC11",
        "name": "Book Appointment"
      },
      {
        "id": "UC12",
        "name": "Confirm Appointment"
      },
      {
        "id": "UC13",
        "name": "Reschedule"
      },
      {
        "id": "UC14",
        "name": "Cancel Appointment"
      },
      {
        "id": "UC15",
        "name": "Check-in"
      },
      {
        "id": "UC16",
        "name": "Mark No-Show"
      },
      {
        "id": "UC17",
        "name": "View Schedule"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC10"
      },
      {
        "actor": "Customer",
        "uc": "UC11"
      },
      {
        "actor": "Customer",
        "uc": "UC14"
      },
      {
        "actor": "Customer",
        "uc": "UC17"
      },
      {
        "actor": "Caregiver",
        "uc": "UC10"
      },
      {
        "actor": "Caregiver",
        "uc": "UC11"
      },
      {
        "actor": "Caregiver",
        "uc": "UC17"
      },
      {
        "actor": "Receptionist",
        "uc": "UC10"
      },
      {
        "actor": "Receptionist",
        "uc": "UC12"
      },
      {
        "actor": "Receptionist",
        "uc": "UC13"
      },
      {
        "actor": "Receptionist",
        "uc": "UC14"
      },
      {
        "actor": "Receptionist",
        "uc": "UC15"
      },
      {
        "actor": "Receptionist",
        "uc": "UC16"
      },
      {
        "actor": "System",
        "uc": "UC11",
        "type": "include"
      },
      {
        "actor": "System",
        "uc": "UC13",
        "type": "include"
      }
    ]
  },
  "uc3": {
    "description": "Use Case Walk-in & Queue: FIFO Queue, Emergency Triage, Walk-in to Appointment Bridge",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "Staff",
        "color": "#7b4ac7"
      },
      {
        "name": "System",
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC20",
        "name": "Register Queue Entry",
        "focal": true
      },
      {
        "id": "UC21",
        "name": "Call Queue Entry"
      },
      {
        "id": "UC22",
        "name": "Start Queue Service"
      },
      {
        "id": "UC23",
        "name": "Complete Queue Entry"
      },
      {
        "id": "UC24",
        "name": "Cancel Queue Entry"
      },
      {
        "id": "UC25",
        "name": "Mark Queue No-Show"
      },
      {
        "id": "UC26",
        "name": "Triage Emergency"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC20"
      },
      {
        "actor": "Customer",
        "uc": "UC24"
      },
      {
        "actor": "Receptionist",
        "uc": "UC20"
      },
      {
        "actor": "Receptionist",
        "uc": "UC21"
      },
      {
        "actor": "Receptionist",
        "uc": "UC24"
      },
      {
        "actor": "Receptionist",
        "uc": "UC25"
      },
      {
        "actor": "Receptionist",
        "uc": "UC26"
      },
      {
        "actor": "Staff",
        "uc": "UC22"
      },
      {
        "actor": "Staff",
        "uc": "UC23"
      },
      {
        "actor": "System",
        "uc": "UC21",
        "type": "include"
      },
      {
        "actor": "System",
        "uc": "UC22",
        "type": "include"
      }
    ]
  },
  "uc4": {
    "description": "Use Case Commerce & Order: Cart, Checkout, POS vs Online fulfillment, Inventory hold",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "Inventory Staff",
        "color": "#7b4ac7"
      },
      {
        "name": "System",
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC30",
        "name": "Add to Cart"
      },
      {
        "id": "UC31",
        "name": "Checkout Order",
        "focal": true
      },
      {
        "id": "UC32",
        "name": "Make Payment"
      },
      {
        "id": "UC33",
        "name": "Process Order"
      },
      {
        "id": "UC34",
        "name": "Complete Order"
      },
      {
        "id": "UC35",
        "name": "Cancel Order"
      },
      {
        "id": "UC36",
        "name": "Apply Voucher"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC30"
      },
      {
        "actor": "Customer",
        "uc": "UC31"
      },
      {
        "actor": "Customer",
        "uc": "UC32"
      },
      {
        "actor": "Customer",
        "uc": "UC35"
      },
      {
        "actor": "Customer",
        "uc": "UC36"
      },
      {
        "actor": "Receptionist",
        "uc": "UC31"
      },
      {
        "actor": "Receptionist",
        "uc": "UC32"
      },
      {
        "actor": "Receptionist",
        "uc": "UC33"
      },
      {
        "actor": "Receptionist",
        "uc": "UC34"
      },
      {
        "actor": "Inventory Staff",
        "uc": "UC33"
      },
      {
        "actor": "System",
        "uc": "UC31",
        "type": "include"
      },
      {
        "actor": "System",
        "uc": "UC36",
        "type": "include"
      }
    ]
  },
  "uc5": {
    "description": "Use Case Clinical & Vaccination: EMR, Prescription, Vaccine administration, Cross-Store consent",
    "actors": [
      {
        "name": "Veterinarian",
        "color": "#eb6c36"
      },
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Caregiver",
        "color": "#5b8c5a"
      },
      {
        "name": "System",
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC40",
        "name": "Examine Pet",
        "focal": true
      },
      {
        "id": "UC41",
        "name": "Create Medical Record"
      },
      {
        "id": "UC42",
        "name": "Diagnose Pet"
      },
      {
        "id": "UC43",
        "name": "Create Prescription"
      },
      {
        "id": "UC44",
        "name": "Administer Vaccine"
      },
      {
        "id": "UC45",
        "name": "View Medical History"
      },
      {
        "id": "UC46",
        "name": "Request Cross-Store Consent"
      },
      {
        "id": "UC47",
        "name": "Emergency Override Access"
      }
    ],
    "relations": [
      {
        "actor": "Veterinarian",
        "uc": "UC40"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC41"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC42"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC43"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC44"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC46"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC47"
      },
      {
        "actor": "Customer",
        "uc": "UC45"
      },
      {
        "actor": "Customer",
        "uc": "UC46"
      },
      {
        "actor": "Caregiver",
        "uc": "UC45"
      },
      {
        "actor": "System",
        "uc": "UC41",
        "type": "include"
      },
      {
        "actor": "System",
        "uc": "UC44",
        "type": "include"
      }
    ]
  },
  "uc6": {
    "description": "Use Case Grooming Service: Grooming session, Surcharge Invoice (D-02), Emergency Abort",
    "actors": [
      {
        "name": "Groomer",
        "color": "#eb6c36"
      },
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Receptionist",
        "color": "#7b4ac7"
      },
      {
        "name": "System",
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC50",
        "name": "Book Grooming"
      },
      {
        "id": "UC51",
        "name": "Inspect Pet",
        "focal": true
      },
      {
        "id": "UC52",
        "name": "Add Grooming Service"
      },
      {
        "id": "UC53",
        "name": "Confirm Surcharge (D-02)"
      },
      {
        "id": "UC54",
        "name": "Complete Grooming"
      },
      {
        "id": "UC55",
        "name": "Abort Grooming"
      },
      {
        "id": "UC56",
        "name": "Check-in Grooming"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC50"
      },
      {
        "actor": "Customer",
        "uc": "UC53"
      },
      {
        "actor": "Groomer",
        "uc": "UC51"
      },
      {
        "actor": "Groomer",
        "uc": "UC52"
      },
      {
        "actor": "Groomer",
        "uc": "UC54"
      },
      {
        "actor": "Groomer",
        "uc": "UC55"
      },
      {
        "actor": "Groomer",
        "uc": "UC56"
      },
      {
        "actor": "Receptionist",
        "uc": "UC50"
      },
      {
        "actor": "Receptionist",
        "uc": "UC56"
      },
      {
        "actor": "System",
        "uc": "UC52",
        "type": "include"
      }
    ]
  },
  "uc7": {
    "description": "Use Case Inventory & Warehouse: Stock management, Transfer, FEFO, Adjustments",
    "actors": [
      {
        "name": "Inventory Staff",
        "color": "#eb6c36"
      },
      {
        "name": "Store Manager",
        "color": "#7b4ac7"
      },
      {
        "name": "System",
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC60",
        "name": "Receive Inventory"
      },
      {
        "id": "UC61",
        "name": "Adjust Inventory"
      },
      {
        "id": "UC62",
        "name": "Transfer Stock",
        "focal": true
      },
      {
        "id": "UC63",
        "name": "Receive Transfer"
      },
      {
        "id": "UC64",
        "name": "Count Inventory"
      },
      {
        "id": "UC65",
        "name": "Trigger Low Stock Alert"
      }
    ],
    "relations": [
      {
        "actor": "Inventory Staff",
        "uc": "UC60"
      },
      {
        "actor": "Inventory Staff",
        "uc": "UC61"
      },
      {
        "actor": "Inventory Staff",
        "uc": "UC62"
      },
      {
        "actor": "Inventory Staff",
        "uc": "UC63"
      },
      {
        "actor": "Inventory Staff",
        "uc": "UC64"
      },
      {
        "actor": "Store Manager",
        "uc": "UC61"
      },
      {
        "actor": "Store Manager",
        "uc": "UC62"
      },
      {
        "actor": "System",
        "uc": "UC65",
        "type": "include"
      }
    ]
  },
  "uc8": {
    "description": "Use Case Procurement: Purchase Request, PO, Supplier, Goods receipt, Maker-Checker",
    "actors": [
      {
        "name": "Inventory Staff",
        "color": "#eb6c36"
      },
      {
        "name": "Store Manager",
        "color": "#7b4ac7"
      },
      {
        "name": "Supplier",
        "color": "#4f5d75"
      },
      {
        "name": "System",
        "color": "#2e5aa8"
      }
    ],
    "useCases": [
      {
        "id": "UC70",
        "name": "Create Purchase Request"
      },
      {
        "id": "UC71",
        "name": "Approve Purchase Request"
      },
      {
        "id": "UC72",
        "name": "Create Purchase Order",
        "focal": true
      },
      {
        "id": "UC73",
        "name": "Receive Goods"
      },
      {
        "id": "UC74",
        "name": "Inspect Goods"
      },
      {
        "id": "UC75",
        "name": "Cancel Purchase Order"
      }
    ],
    "relations": [
      {
        "actor": "Inventory Staff",
        "uc": "UC70"
      },
      {
        "actor": "Inventory Staff",
        "uc": "UC73"
      },
      {
        "actor": "Inventory Staff",
        "uc": "UC74"
      },
      {
        "actor": "Inventory Staff",
        "uc": "UC75"
      },
      {
        "actor": "Store Manager",
        "uc": "UC71"
      },
      {
        "actor": "Store Manager",
        "uc": "UC72"
      },
      {
        "actor": "Supplier",
        "uc": "UC73"
      },
      {
        "actor": "System",
        "uc": "UC72",
        "type": "include"
      }
    ]
  },
  "uc9": {
    "description": "Use Case Billing & Refund: Invoice, Payment, Refund, Settlement Immutability (D-01)",
    "actors": [
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "Finance Staff",
        "color": "#7b4ac7"
      },
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "System",
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC80",
        "name": "Create Invoice"
      },
      {
        "id": "UC81",
        "name": "Issue Invoice"
      },
      {
        "id": "UC82",
        "name": "Make Payment"
      },
      {
        "id": "UC83",
        "name": "Process Refund",
        "focal": true
      },
      {
        "id": "UC84",
        "name": "Void Invoice"
      },
      {
        "id": "UC85",
        "name": "Reconcile Invoice"
      },
      {
        "id": "UC86",
        "name": "View Invoice"
      }
    ],
    "relations": [
      {
        "actor": "Receptionist",
        "uc": "UC80"
      },
      {
        "actor": "Receptionist",
        "uc": "UC81"
      },
      {
        "actor": "Receptionist",
        "uc": "UC82"
      },
      {
        "actor": "Receptionist",
        "uc": "UC84"
      },
      {
        "actor": "Receptionist",
        "uc": "UC86"
      },
      {
        "actor": "Finance Staff",
        "uc": "UC83"
      },
      {
        "actor": "Finance Staff",
        "uc": "UC85"
      },
      {
        "actor": "Customer",
        "uc": "UC82"
      },
      {
        "actor": "Customer",
        "uc": "UC86"
      },
      {
        "actor": "System",
        "uc": "UC81",
        "type": "include"
      }
    ]
  },
  "auth_customer_registration": {
    "description": "UC1a · Customer Registration: Register → Send OTP → Verify → Account Active",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Frontend App",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "OTP Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Database",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC1a-1",
        "name": "Register Account",
        "focal": true
      },
      {
        "id": "UC1a-2",
        "name": "Send OTP Code"
      },
      {
        "id": "UC1a-3",
        "name": "Verify OTP"
      },
      {
        "id": "UC1a-4",
        "name": "Resend OTP"
      },
      {
        "id": "UC1a-5",
        "name": "Activate Account"
      },
      {
        "id": "UC1a-6",
        "name": "Issue JWT Token"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC1a-1"
      },
      {
        "actor": "Frontend App",
        "uc": "UC1a-2",
        "type": "include"
      },
      {
        "actor": "OTP Service",
        "uc": "UC1a-3"
      },
      {
        "actor": "OTP Service",
        "uc": "UC1a-4"
      },
      {
        "actor": "Database",
        "uc": "UC1a-5"
      },
      {
        "actor": "Database",
        "uc": "UC1a-6"
      }
    ],
    "module": "Auth & Identity",
    "moduleId": "auth",
    "moduleIcon": "🔐"
  },
  "customer-registration": {
    "description": "UC1a · Customer Registration: Register → Send OTP → Verify → Account Active",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Frontend App",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "OTP Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Database",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC1a-1",
        "name": "Register Account",
        "focal": true
      },
      {
        "id": "UC1a-2",
        "name": "Send OTP Code"
      },
      {
        "id": "UC1a-3",
        "name": "Verify OTP"
      },
      {
        "id": "UC1a-4",
        "name": "Resend OTP"
      },
      {
        "id": "UC1a-5",
        "name": "Activate Account"
      },
      {
        "id": "UC1a-6",
        "name": "Issue JWT Token"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC1a-1"
      },
      {
        "actor": "Frontend App",
        "uc": "UC1a-2",
        "type": "include"
      },
      {
        "actor": "OTP Service",
        "uc": "UC1a-3"
      },
      {
        "actor": "OTP Service",
        "uc": "UC1a-4"
      },
      {
        "actor": "Database",
        "uc": "UC1a-5"
      },
      {
        "actor": "Database",
        "uc": "UC1a-6"
      }
    ],
    "module": "Auth & Identity",
    "moduleId": "auth",
    "moduleIcon": "🔐"
  },
  "auth_customer_login": {
    "description": "UC1b · Customer Login: Credentials → Validate → JWT Session",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Auth Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Database",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC1b-1",
        "name": "Login",
        "focal": true
      },
      {
        "id": "UC1b-2",
        "name": "Validate Credentials"
      },
      {
        "id": "UC1b-3",
        "name": "Check Account Status"
      },
      {
        "id": "UC1b-4",
        "name": "Issue Access Token"
      },
      {
        "id": "UC1b-5",
        "name": "Issue Refresh Token"
      },
      {
        "id": "UC1b-6",
        "name": "Logout"
      },
      {
        "id": "UC1b-7",
        "name": "Revoke Tokens"
      },
      {
        "id": "UC1b-8",
        "name": "Change Password"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC1b-1"
      },
      {
        "actor": "Customer",
        "uc": "UC1b-6"
      },
      {
        "actor": "Customer",
        "uc": "UC1b-8"
      },
      {
        "actor": "Auth Service",
        "uc": "UC1b-2",
        "type": "include"
      },
      {
        "actor": "Auth Service",
        "uc": "UC1b-3",
        "type": "include"
      },
      {
        "actor": "Database",
        "uc": "UC1b-4"
      },
      {
        "actor": "Database",
        "uc": "UC1b-5"
      },
      {
        "actor": "Database",
        "uc": "UC1b-7"
      }
    ],
    "module": "Auth & Identity",
    "moduleId": "auth",
    "moduleIcon": "🔐"
  },
  "customer-login": {
    "description": "UC1b · Customer Login: Credentials → Validate → JWT Session",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Auth Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Database",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC1b-1",
        "name": "Login",
        "focal": true
      },
      {
        "id": "UC1b-2",
        "name": "Validate Credentials"
      },
      {
        "id": "UC1b-3",
        "name": "Check Account Status"
      },
      {
        "id": "UC1b-4",
        "name": "Issue Access Token"
      },
      {
        "id": "UC1b-5",
        "name": "Issue Refresh Token"
      },
      {
        "id": "UC1b-6",
        "name": "Logout"
      },
      {
        "id": "UC1b-7",
        "name": "Revoke Tokens"
      },
      {
        "id": "UC1b-8",
        "name": "Change Password"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC1b-1"
      },
      {
        "actor": "Customer",
        "uc": "UC1b-6"
      },
      {
        "actor": "Customer",
        "uc": "UC1b-8"
      },
      {
        "actor": "Auth Service",
        "uc": "UC1b-2",
        "type": "include"
      },
      {
        "actor": "Auth Service",
        "uc": "UC1b-3",
        "type": "include"
      },
      {
        "actor": "Database",
        "uc": "UC1b-4"
      },
      {
        "actor": "Database",
        "uc": "UC1b-5"
      },
      {
        "actor": "Database",
        "uc": "UC1b-7"
      }
    ],
    "module": "Auth & Identity",
    "moduleId": "auth",
    "moduleIcon": "🔐"
  },
  "auth_staff_onboarding": {
    "description": "UC1c · Staff Onboarding D-04: CreateStaff → Active (no OTP) → First Login → Force Change Password",
    "actors": [
      {
        "name": "Platform Admin",
        "color": "#7b4ac7"
      },
      {
        "name": "Admin Portal",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Auth Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Staff",
        "color": "#eb6c36"
      }
    ],
    "useCases": [
      {
        "id": "UC1c-1",
        "name": "Create Staff Account",
        "focal": true
      },
      {
        "id": "UC1c-2",
        "name": "Generate Temp Password"
      },
      {
        "id": "UC1c-3",
        "name": "Send Credentials"
      },
      {
        "id": "UC1c-4",
        "name": "Set must_change=true"
      },
      {
        "id": "UC1c-5",
        "name": "First Login"
      },
      {
        "id": "UC1c-6",
        "name": "Force Change Password"
      },
      {
        "id": "UC1c-7",
        "name": "Issue JWT Session"
      },
      {
        "id": "UC1c-8",
        "name": "Assign Role"
      }
    ],
    "relations": [
      {
        "actor": "Platform Admin",
        "uc": "UC1c-1"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC1c-8"
      },
      {
        "actor": "Admin Portal",
        "uc": "UC1c-2",
        "type": "include"
      },
      {
        "actor": "Admin Portal",
        "uc": "UC1c-3",
        "type": "include"
      },
      {
        "actor": "Admin Portal",
        "uc": "UC1c-4",
        "type": "include"
      },
      {
        "actor": "Staff",
        "uc": "UC1c-5"
      },
      {
        "actor": "Auth Service",
        "uc": "UC1c-6",
        "type": "include"
      },
      {
        "actor": "Auth Service",
        "uc": "UC1c-7"
      }
    ],
    "module": "Auth & Identity",
    "moduleId": "auth",
    "moduleIcon": "🔐"
  },
  "staff-onboarding": {
    "description": "UC1c · Staff Onboarding D-04: CreateStaff → Active (no OTP) → First Login → Force Change Password",
    "actors": [
      {
        "name": "Platform Admin",
        "color": "#7b4ac7"
      },
      {
        "name": "Admin Portal",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Auth Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Staff",
        "color": "#eb6c36"
      }
    ],
    "useCases": [
      {
        "id": "UC1c-1",
        "name": "Create Staff Account",
        "focal": true
      },
      {
        "id": "UC1c-2",
        "name": "Generate Temp Password"
      },
      {
        "id": "UC1c-3",
        "name": "Send Credentials"
      },
      {
        "id": "UC1c-4",
        "name": "Set must_change=true"
      },
      {
        "id": "UC1c-5",
        "name": "First Login"
      },
      {
        "id": "UC1c-6",
        "name": "Force Change Password"
      },
      {
        "id": "UC1c-7",
        "name": "Issue JWT Session"
      },
      {
        "id": "UC1c-8",
        "name": "Assign Role"
      }
    ],
    "relations": [
      {
        "actor": "Platform Admin",
        "uc": "UC1c-1"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC1c-8"
      },
      {
        "actor": "Admin Portal",
        "uc": "UC1c-2",
        "type": "include"
      },
      {
        "actor": "Admin Portal",
        "uc": "UC1c-3",
        "type": "include"
      },
      {
        "actor": "Admin Portal",
        "uc": "UC1c-4",
        "type": "include"
      },
      {
        "actor": "Staff",
        "uc": "UC1c-5"
      },
      {
        "actor": "Auth Service",
        "uc": "UC1c-6",
        "type": "include"
      },
      {
        "actor": "Auth Service",
        "uc": "UC1c-7"
      }
    ],
    "module": "Auth & Identity",
    "moduleId": "auth",
    "moduleIcon": "🔐"
  },
  "auth_account_management": {
    "description": "UC1d · Account Management: Lock, Unlock, Deactivate, Reactivate",
    "actors": [
      {
        "name": "Platform Admin",
        "color": "#7b4ac7"
      },
      {
        "name": "Org Admin",
        "color": "#eb6c36"
      },
      {
        "name": "System",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Account",
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC1d-1",
        "name": "Lock Account",
        "focal": true
      },
      {
        "id": "UC1d-2",
        "name": "Unlock Account"
      },
      {
        "id": "UC1d-3",
        "name": "Deactivate Account"
      },
      {
        "id": "UC1d-4",
        "name": "Reactivate Account"
      },
      {
        "id": "UC1d-5",
        "name": "Revoke All Sessions"
      },
      {
        "id": "UC1d-6",
        "name": "Record Audit Log"
      },
      {
        "id": "UC1d-7",
        "name": "Auto Lock (5 failed attempts)"
      }
    ],
    "relations": [
      {
        "actor": "Platform Admin",
        "uc": "UC1d-1"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC1d-2"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC1d-3"
      },
      {
        "actor": "Org Admin",
        "uc": "UC1d-1"
      },
      {
        "actor": "Org Admin",
        "uc": "UC1d-2"
      },
      {
        "actor": "Org Admin",
        "uc": "UC1d-3"
      },
      {
        "actor": "Org Admin",
        "uc": "UC1d-4"
      },
      {
        "actor": "System",
        "uc": "UC1d-5",
        "type": "include"
      },
      {
        "actor": "System",
        "uc": "UC1d-6",
        "type": "include"
      },
      {
        "actor": "System",
        "uc": "UC1d-7"
      }
    ],
    "module": "Auth & Identity",
    "moduleId": "auth",
    "moduleIcon": "🔐"
  },
  "account-management": {
    "description": "UC1d · Account Management: Lock, Unlock, Deactivate, Reactivate",
    "actors": [
      {
        "name": "Platform Admin",
        "color": "#7b4ac7"
      },
      {
        "name": "Org Admin",
        "color": "#eb6c36"
      },
      {
        "name": "System",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Account",
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC1d-1",
        "name": "Lock Account",
        "focal": true
      },
      {
        "id": "UC1d-2",
        "name": "Unlock Account"
      },
      {
        "id": "UC1d-3",
        "name": "Deactivate Account"
      },
      {
        "id": "UC1d-4",
        "name": "Reactivate Account"
      },
      {
        "id": "UC1d-5",
        "name": "Revoke All Sessions"
      },
      {
        "id": "UC1d-6",
        "name": "Record Audit Log"
      },
      {
        "id": "UC1d-7",
        "name": "Auto Lock (5 failed attempts)"
      }
    ],
    "relations": [
      {
        "actor": "Platform Admin",
        "uc": "UC1d-1"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC1d-2"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC1d-3"
      },
      {
        "actor": "Org Admin",
        "uc": "UC1d-1"
      },
      {
        "actor": "Org Admin",
        "uc": "UC1d-2"
      },
      {
        "actor": "Org Admin",
        "uc": "UC1d-3"
      },
      {
        "actor": "Org Admin",
        "uc": "UC1d-4"
      },
      {
        "actor": "System",
        "uc": "UC1d-5",
        "type": "include"
      },
      {
        "actor": "System",
        "uc": "UC1d-6",
        "type": "include"
      },
      {
        "actor": "System",
        "uc": "UC1d-7"
      }
    ],
    "module": "Auth & Identity",
    "moduleId": "auth",
    "moduleIcon": "🔐"
  },
  "booking_slot_reservation": {
    "description": "UC2a · HoldSlot: 15-minute TTL reservation, Release, Expire",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Caregiver",
        "color": "#5b8c5a"
      },
      {
        "name": "Store App",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Slot Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC2a-1",
        "name": "Hold Slot (15m TTL)",
        "focal": true
      },
      {
        "id": "UC2a-2",
        "name": "Release Hold"
      },
      {
        "id": "UC2a-3",
        "name": "Expire Hold"
      },
      {
        "id": "UC2a-4",
        "name": "Check Availability"
      },
      {
        "id": "UC2a-5",
        "name": "Lock Resources"
      },
      {
        "id": "UC2a-6",
        "name": "Send Notification"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC2a-1"
      },
      {
        "actor": "Caregiver",
        "uc": "UC2a-1"
      },
      {
        "actor": "Store App",
        "uc": "UC2a-4",
        "type": "include"
      },
      {
        "actor": "Slot Service",
        "uc": "UC2a-3"
      },
      {
        "actor": "Slot Service",
        "uc": "UC2a-5",
        "type": "include"
      },
      {
        "actor": "Slot Service",
        "uc": "UC2a-6"
      },
      {
        "actor": "Customer",
        "uc": "UC2a-2"
      }
    ],
    "module": "Appointment & Booking",
    "moduleId": "booking",
    "moduleIcon": "📅"
  },
  "slot-reservation": {
    "description": "UC2a · HoldSlot: 15-minute TTL reservation, Release, Expire",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Caregiver",
        "color": "#5b8c5a"
      },
      {
        "name": "Store App",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Slot Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC2a-1",
        "name": "Hold Slot (15m TTL)",
        "focal": true
      },
      {
        "id": "UC2a-2",
        "name": "Release Hold"
      },
      {
        "id": "UC2a-3",
        "name": "Expire Hold"
      },
      {
        "id": "UC2a-4",
        "name": "Check Availability"
      },
      {
        "id": "UC2a-5",
        "name": "Lock Resources"
      },
      {
        "id": "UC2a-6",
        "name": "Send Notification"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC2a-1"
      },
      {
        "actor": "Caregiver",
        "uc": "UC2a-1"
      },
      {
        "actor": "Store App",
        "uc": "UC2a-4",
        "type": "include"
      },
      {
        "actor": "Slot Service",
        "uc": "UC2a-3"
      },
      {
        "actor": "Slot Service",
        "uc": "UC2a-5",
        "type": "include"
      },
      {
        "actor": "Slot Service",
        "uc": "UC2a-6"
      },
      {
        "actor": "Customer",
        "uc": "UC2a-2"
      }
    ],
    "module": "Appointment & Booking",
    "moduleId": "booking",
    "moduleIcon": "📅"
  },
  "booking_appointment_booking": {
    "description": "UC2b · BookAppointment: After HoldSlot → Create Appointment → Resource Lock",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Caregiver",
        "color": "#5b8c5a"
      },
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "Scheduling API",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Inventory",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC2b-1",
        "name": "Book Appointment",
        "focal": true
      },
      {
        "id": "UC2b-2",
        "name": "Validate Pet Ownership"
      },
      {
        "id": "UC2b-3",
        "name": "Check Store Hours"
      },
      {
        "id": "UC2b-4",
        "name": "Check Staff Availability"
      },
      {
        "id": "UC2b-5",
        "name": "Lock Store Resources"
      },
      {
        "id": "UC2b-6",
        "name": "Lock Staff Slot"
      },
      {
        "id": "UC2b-7",
        "name": "Check Pet Schedule Collision"
      },
      {
        "id": "UC2b-8",
        "name": "Create Appointment Record"
      },
      {
        "id": "UC2b-9",
        "name": "Send Confirmation"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC2b-1"
      },
      {
        "actor": "Caregiver",
        "uc": "UC2b-1"
      },
      {
        "actor": "Receptionist",
        "uc": "UC2b-1"
      },
      {
        "actor": "Scheduling API",
        "uc": "UC2b-2",
        "type": "include"
      },
      {
        "actor": "Scheduling API",
        "uc": "UC2b-3",
        "type": "include"
      },
      {
        "actor": "Scheduling API",
        "uc": "UC2b-4",
        "type": "include"
      },
      {
        "actor": "Scheduling API",
        "uc": "UC2b-7",
        "type": "include"
      },
      {
        "actor": "Scheduling API",
        "uc": "UC2b-8",
        "type": "include"
      },
      {
        "actor": "Inventory",
        "uc": "UC2b-5",
        "type": "include"
      },
      {
        "actor": "Inventory",
        "uc": "UC2b-6",
        "type": "include"
      },
      {
        "actor": "Scheduling API",
        "uc": "UC2b-9"
      }
    ],
    "module": "Appointment & Booking",
    "moduleId": "booking",
    "moduleIcon": "📅"
  },
  "appointment-booking": {
    "description": "UC2b · BookAppointment: After HoldSlot → Create Appointment → Resource Lock",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Caregiver",
        "color": "#5b8c5a"
      },
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "Scheduling API",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Inventory",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC2b-1",
        "name": "Book Appointment",
        "focal": true
      },
      {
        "id": "UC2b-2",
        "name": "Validate Pet Ownership"
      },
      {
        "id": "UC2b-3",
        "name": "Check Store Hours"
      },
      {
        "id": "UC2b-4",
        "name": "Check Staff Availability"
      },
      {
        "id": "UC2b-5",
        "name": "Lock Store Resources"
      },
      {
        "id": "UC2b-6",
        "name": "Lock Staff Slot"
      },
      {
        "id": "UC2b-7",
        "name": "Check Pet Schedule Collision"
      },
      {
        "id": "UC2b-8",
        "name": "Create Appointment Record"
      },
      {
        "id": "UC2b-9",
        "name": "Send Confirmation"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC2b-1"
      },
      {
        "actor": "Caregiver",
        "uc": "UC2b-1"
      },
      {
        "actor": "Receptionist",
        "uc": "UC2b-1"
      },
      {
        "actor": "Scheduling API",
        "uc": "UC2b-2",
        "type": "include"
      },
      {
        "actor": "Scheduling API",
        "uc": "UC2b-3",
        "type": "include"
      },
      {
        "actor": "Scheduling API",
        "uc": "UC2b-4",
        "type": "include"
      },
      {
        "actor": "Scheduling API",
        "uc": "UC2b-7",
        "type": "include"
      },
      {
        "actor": "Scheduling API",
        "uc": "UC2b-8",
        "type": "include"
      },
      {
        "actor": "Inventory",
        "uc": "UC2b-5",
        "type": "include"
      },
      {
        "actor": "Inventory",
        "uc": "UC2b-6",
        "type": "include"
      },
      {
        "actor": "Scheduling API",
        "uc": "UC2b-9"
      }
    ],
    "module": "Appointment & Booking",
    "moduleId": "booking",
    "moduleIcon": "📅"
  },
  "booking_appointment_checkin": {
    "description": "UC2c · Confirm, CheckIn, StartService, Complete, MarkNoShow, Abort",
    "actors": [
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "Veterinarian",
        "color": "#7b4ac7"
      },
      {
        "name": "Groomer",
        "color": "#45a58a"
      },
      {
        "name": "System",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC2c-1",
        "name": "Confirm Appointment"
      },
      {
        "id": "UC2c-2",
        "name": "Check In Pet",
        "focal": true
      },
      {
        "id": "UC2c-3",
        "name": "Start Service"
      },
      {
        "id": "UC2c-4",
        "name": "Complete Service"
      },
      {
        "id": "UC2c-5",
        "name": "Check Out"
      },
      {
        "id": "UC2c-6",
        "name": "Mark No-Show"
      },
      {
        "id": "UC2c-7",
        "name": "Abort Service"
      },
      {
        "id": "UC2c-8",
        "name": "Release Resources"
      }
    ],
    "relations": [
      {
        "actor": "Receptionist",
        "uc": "UC2c-1"
      },
      {
        "actor": "Receptionist",
        "uc": "UC2c-2"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC2c-3"
      },
      {
        "actor": "Groomer",
        "uc": "UC2c-3"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC2c-4"
      },
      {
        "actor": "Groomer",
        "uc": "UC2c-4"
      },
      {
        "actor": "Receptionist",
        "uc": "UC2c-5"
      },
      {
        "actor": "Receptionist",
        "uc": "UC2c-6"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC2c-7"
      },
      {
        "actor": "Groomer",
        "uc": "UC2c-7"
      },
      {
        "actor": "System",
        "uc": "UC2c-8",
        "type": "include"
      }
    ],
    "module": "Appointment & Booking",
    "moduleId": "booking",
    "moduleIcon": "📅"
  },
  "appointment-checkin": {
    "description": "UC2c · Confirm, CheckIn, StartService, Complete, MarkNoShow, Abort",
    "actors": [
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "Veterinarian",
        "color": "#7b4ac7"
      },
      {
        "name": "Groomer",
        "color": "#45a58a"
      },
      {
        "name": "System",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC2c-1",
        "name": "Confirm Appointment"
      },
      {
        "id": "UC2c-2",
        "name": "Check In Pet",
        "focal": true
      },
      {
        "id": "UC2c-3",
        "name": "Start Service"
      },
      {
        "id": "UC2c-4",
        "name": "Complete Service"
      },
      {
        "id": "UC2c-5",
        "name": "Check Out"
      },
      {
        "id": "UC2c-6",
        "name": "Mark No-Show"
      },
      {
        "id": "UC2c-7",
        "name": "Abort Service"
      },
      {
        "id": "UC2c-8",
        "name": "Release Resources"
      }
    ],
    "relations": [
      {
        "actor": "Receptionist",
        "uc": "UC2c-1"
      },
      {
        "actor": "Receptionist",
        "uc": "UC2c-2"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC2c-3"
      },
      {
        "actor": "Groomer",
        "uc": "UC2c-3"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC2c-4"
      },
      {
        "actor": "Groomer",
        "uc": "UC2c-4"
      },
      {
        "actor": "Receptionist",
        "uc": "UC2c-5"
      },
      {
        "actor": "Receptionist",
        "uc": "UC2c-6"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC2c-7"
      },
      {
        "actor": "Groomer",
        "uc": "UC2c-7"
      },
      {
        "actor": "System",
        "uc": "UC2c-8",
        "type": "include"
      }
    ],
    "module": "Appointment & Booking",
    "moduleId": "booking",
    "moduleIcon": "📅"
  },
  "booking_reschedule_cancel": {
    "description": "UC2d · Atomic Reschedule (15m lock new slot → release old) & Cancel with Refund",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Caregiver",
        "color": "#5b8c5a"
      },
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "System",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC2d-1",
        "name": "Reschedule Appointment",
        "focal": true
      },
      {
        "id": "UC2d-2",
        "name": "Hold New Slot"
      },
      {
        "id": "UC2d-3",
        "name": "Validate New Slot"
      },
      {
        "id": "UC2d-4",
        "name": "Release Old Slot"
      },
      {
        "id": "UC2d-5",
        "name": "Rollback on Failure"
      },
      {
        "id": "UC2d-6",
        "name": "Cancel Appointment"
      },
      {
        "id": "UC2d-7",
        "name": "Process Deposit Refund"
      },
      {
        "id": "UC2d-8",
        "name": "Release All Resources"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC2d-1"
      },
      {
        "actor": "Caregiver",
        "uc": "UC2d-1"
      },
      {
        "actor": "Receptionist",
        "uc": "UC2d-1"
      },
      {
        "actor": "System",
        "uc": "UC2d-2",
        "type": "include"
      },
      {
        "actor": "System",
        "uc": "UC2d-3",
        "type": "include"
      },
      {
        "actor": "System",
        "uc": "UC2d-4",
        "type": "include"
      },
      {
        "actor": "System",
        "uc": "UC2d-5"
      },
      {
        "actor": "Customer",
        "uc": "UC2d-6"
      },
      {
        "actor": "Caregiver",
        "uc": "UC2d-6"
      },
      {
        "actor": "Receptionist",
        "uc": "UC2d-6"
      },
      {
        "actor": "System",
        "uc": "UC2d-7",
        "type": "include"
      },
      {
        "actor": "System",
        "uc": "UC2d-8",
        "type": "include"
      }
    ],
    "module": "Appointment & Booking",
    "moduleId": "booking",
    "moduleIcon": "📅"
  },
  "reschedule-cancel": {
    "description": "UC2d · Atomic Reschedule (15m lock new slot → release old) & Cancel with Refund",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Caregiver",
        "color": "#5b8c5a"
      },
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "System",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC2d-1",
        "name": "Reschedule Appointment",
        "focal": true
      },
      {
        "id": "UC2d-2",
        "name": "Hold New Slot"
      },
      {
        "id": "UC2d-3",
        "name": "Validate New Slot"
      },
      {
        "id": "UC2d-4",
        "name": "Release Old Slot"
      },
      {
        "id": "UC2d-5",
        "name": "Rollback on Failure"
      },
      {
        "id": "UC2d-6",
        "name": "Cancel Appointment"
      },
      {
        "id": "UC2d-7",
        "name": "Process Deposit Refund"
      },
      {
        "id": "UC2d-8",
        "name": "Release All Resources"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC2d-1"
      },
      {
        "actor": "Caregiver",
        "uc": "UC2d-1"
      },
      {
        "actor": "Receptionist",
        "uc": "UC2d-1"
      },
      {
        "actor": "System",
        "uc": "UC2d-2",
        "type": "include"
      },
      {
        "actor": "System",
        "uc": "UC2d-3",
        "type": "include"
      },
      {
        "actor": "System",
        "uc": "UC2d-4",
        "type": "include"
      },
      {
        "actor": "System",
        "uc": "UC2d-5"
      },
      {
        "actor": "Customer",
        "uc": "UC2d-6"
      },
      {
        "actor": "Caregiver",
        "uc": "UC2d-6"
      },
      {
        "actor": "Receptionist",
        "uc": "UC2d-6"
      },
      {
        "actor": "System",
        "uc": "UC2d-7",
        "type": "include"
      },
      {
        "actor": "System",
        "uc": "UC2d-8",
        "type": "include"
      }
    ],
    "module": "Appointment & Booking",
    "moduleId": "booking",
    "moduleIcon": "📅"
  },
  "queue_register_queue": {
    "description": "UC3a · Register Queue Entry: FIFO, Emergency Triage Bypass",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "Queue Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC3a-1",
        "name": "Register Queue Entry",
        "focal": true
      },
      {
        "id": "UC3a-2",
        "name": "Assign Queue Number"
      },
      {
        "id": "UC3a-3",
        "name": "Select Service Type"
      },
      {
        "id": "UC3a-4",
        "name": "Triage Emergency"
      },
      {
        "id": "UC3a-5",
        "name": "Cancel Queue Entry"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC3a-1"
      },
      {
        "actor": "Receptionist",
        "uc": "UC3a-1"
      },
      {
        "actor": "Queue Service",
        "uc": "UC3a-2",
        "type": "include"
      },
      {
        "actor": "Queue Service",
        "uc": "UC3a-3",
        "type": "include"
      },
      {
        "actor": "Receptionist",
        "uc": "UC3a-4"
      },
      {
        "actor": "Customer",
        "uc": "UC3a-5"
      }
    ],
    "module": "Walk-in & Queue",
    "moduleId": "queue",
    "moduleIcon": "🚶"
  },
  "register-queue": {
    "description": "UC3a · Register Queue Entry: FIFO, Emergency Triage Bypass",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "Queue Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC3a-1",
        "name": "Register Queue Entry",
        "focal": true
      },
      {
        "id": "UC3a-2",
        "name": "Assign Queue Number"
      },
      {
        "id": "UC3a-3",
        "name": "Select Service Type"
      },
      {
        "id": "UC3a-4",
        "name": "Triage Emergency"
      },
      {
        "id": "UC3a-5",
        "name": "Cancel Queue Entry"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC3a-1"
      },
      {
        "actor": "Receptionist",
        "uc": "UC3a-1"
      },
      {
        "actor": "Queue Service",
        "uc": "UC3a-2",
        "type": "include"
      },
      {
        "actor": "Queue Service",
        "uc": "UC3a-3",
        "type": "include"
      },
      {
        "actor": "Receptionist",
        "uc": "UC3a-4"
      },
      {
        "actor": "Customer",
        "uc": "UC3a-5"
      }
    ],
    "module": "Walk-in & Queue",
    "moduleId": "queue",
    "moduleIcon": "🚶"
  },
  "queue_queue_management": {
    "description": "UC3b · Queue Lifecycle: Call → Start Service → Complete / NoShow",
    "actors": [
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "Veterinarian",
        "color": "#7b4ac7"
      },
      {
        "name": "Groomer",
        "color": "#45a58a"
      },
      {
        "name": "System",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC3b-1",
        "name": "Call Queue Entry"
      },
      {
        "id": "UC3b-2",
        "name": "Send Turn Notification"
      },
      {
        "id": "UC3b-3",
        "name": "Start Queue Service"
      },
      {
        "id": "UC3b-4",
        "name": "Complete Queue Entry"
      },
      {
        "id": "UC3b-5",
        "name": "Mark Queue No-Show"
      },
      {
        "id": "UC3b-6",
        "name": "Promote Next Entry"
      }
    ],
    "relations": [
      {
        "actor": "Receptionist",
        "uc": "UC3b-1"
      },
      {
        "actor": "System",
        "uc": "UC3b-2",
        "type": "include"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC3b-3"
      },
      {
        "actor": "Groomer",
        "uc": "UC3b-3"
      },
      {
        "actor": "Receptionist",
        "uc": "UC3b-4"
      },
      {
        "actor": "Receptionist",
        "uc": "UC3b-5"
      },
      {
        "actor": "System",
        "uc": "UC3b-6",
        "type": "include"
      }
    ],
    "module": "Walk-in & Queue",
    "moduleId": "queue",
    "moduleIcon": "🚶"
  },
  "queue-management": {
    "description": "UC3b · Queue Lifecycle: Call → Start Service → Complete / NoShow",
    "actors": [
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "Veterinarian",
        "color": "#7b4ac7"
      },
      {
        "name": "Groomer",
        "color": "#45a58a"
      },
      {
        "name": "System",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC3b-1",
        "name": "Call Queue Entry"
      },
      {
        "id": "UC3b-2",
        "name": "Send Turn Notification"
      },
      {
        "id": "UC3b-3",
        "name": "Start Queue Service"
      },
      {
        "id": "UC3b-4",
        "name": "Complete Queue Entry"
      },
      {
        "id": "UC3b-5",
        "name": "Mark Queue No-Show"
      },
      {
        "id": "UC3b-6",
        "name": "Promote Next Entry"
      }
    ],
    "relations": [
      {
        "actor": "Receptionist",
        "uc": "UC3b-1"
      },
      {
        "actor": "System",
        "uc": "UC3b-2",
        "type": "include"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC3b-3"
      },
      {
        "actor": "Groomer",
        "uc": "UC3b-3"
      },
      {
        "actor": "Receptionist",
        "uc": "UC3b-4"
      },
      {
        "actor": "Receptionist",
        "uc": "UC3b-5"
      },
      {
        "actor": "System",
        "uc": "UC3b-6",
        "type": "include"
      }
    ],
    "module": "Walk-in & Queue",
    "moduleId": "queue",
    "moduleIcon": "🚶"
  },
  "queue_walkin_bridge": {
    "description": "UC3c · Walk-in to Appointment Bridge: StartQueueService → CreateInternalAppointment",
    "actors": [
      {
        "name": "Staff",
        "color": "#7b4ac7"
      },
      {
        "name": "Queue Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Appointment Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC3c-1",
        "name": "Start Queue Service",
        "focal": true
      },
      {
        "id": "UC3c-2",
        "name": "Create Internal Appointment"
      },
      {
        "id": "UC3c-3",
        "name": "Link QueueTicketId"
      },
      {
        "id": "UC3c-4",
        "name": "Lock Resources"
      },
      {
        "id": "UC3c-5",
        "name": "Sync Medical Records"
      }
    ],
    "relations": [
      {
        "actor": "Staff",
        "uc": "UC3c-1"
      },
      {
        "actor": "Queue Service",
        "uc": "UC3c-2",
        "type": "include"
      },
      {
        "actor": "Queue Service",
        "uc": "UC3c-3",
        "type": "include"
      },
      {
        "actor": "Appointment Service",
        "uc": "UC3c-4",
        "type": "include"
      },
      {
        "actor": "Appointment Service",
        "uc": "UC3c-5",
        "type": "include"
      }
    ],
    "module": "Walk-in & Queue",
    "moduleId": "queue",
    "moduleIcon": "🚶"
  },
  "walkin-bridge": {
    "description": "UC3c · Walk-in to Appointment Bridge: StartQueueService → CreateInternalAppointment",
    "actors": [
      {
        "name": "Staff",
        "color": "#7b4ac7"
      },
      {
        "name": "Queue Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Appointment Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC3c-1",
        "name": "Start Queue Service",
        "focal": true
      },
      {
        "id": "UC3c-2",
        "name": "Create Internal Appointment"
      },
      {
        "id": "UC3c-3",
        "name": "Link QueueTicketId"
      },
      {
        "id": "UC3c-4",
        "name": "Lock Resources"
      },
      {
        "id": "UC3c-5",
        "name": "Sync Medical Records"
      }
    ],
    "relations": [
      {
        "actor": "Staff",
        "uc": "UC3c-1"
      },
      {
        "actor": "Queue Service",
        "uc": "UC3c-2",
        "type": "include"
      },
      {
        "actor": "Queue Service",
        "uc": "UC3c-3",
        "type": "include"
      },
      {
        "actor": "Appointment Service",
        "uc": "UC3c-4",
        "type": "include"
      },
      {
        "actor": "Appointment Service",
        "uc": "UC3c-5",
        "type": "include"
      }
    ],
    "module": "Walk-in & Queue",
    "moduleId": "queue",
    "moduleIcon": "🚶"
  },
  "commerce_cart": {
    "description": "UC4a · Cart Management: Add, Update, Remove items, Apply Voucher",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Cart Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Inventory",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC4a-1",
        "name": "Add to Cart",
        "focal": true
      },
      {
        "id": "UC4a-2",
        "name": "Update Quantity"
      },
      {
        "id": "UC4a-3",
        "name": "Remove Item"
      },
      {
        "id": "UC4a-4",
        "name": "Apply Voucher"
      },
      {
        "id": "UC4a-5",
        "name": "Check Stock Availability"
      },
      {
        "id": "UC4a-6",
        "name": "Calculate Total"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC4a-1"
      },
      {
        "actor": "Customer",
        "uc": "UC4a-2"
      },
      {
        "actor": "Customer",
        "uc": "UC4a-3"
      },
      {
        "actor": "Customer",
        "uc": "UC4a-4"
      },
      {
        "actor": "Cart Service",
        "uc": "UC4a-5",
        "type": "include"
      },
      {
        "actor": "Cart Service",
        "uc": "UC4a-6",
        "type": "include"
      }
    ],
    "module": "Commerce & Order",
    "moduleId": "commerce",
    "moduleIcon": "🛒"
  },
  "cart": {
    "description": "UC4a · Cart Management: Add, Update, Remove items, Apply Voucher",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Cart Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Inventory",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC4a-1",
        "name": "Add to Cart",
        "focal": true
      },
      {
        "id": "UC4a-2",
        "name": "Update Quantity"
      },
      {
        "id": "UC4a-3",
        "name": "Remove Item"
      },
      {
        "id": "UC4a-4",
        "name": "Apply Voucher"
      },
      {
        "id": "UC4a-5",
        "name": "Check Stock Availability"
      },
      {
        "id": "UC4a-6",
        "name": "Calculate Total"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC4a-1"
      },
      {
        "actor": "Customer",
        "uc": "UC4a-2"
      },
      {
        "actor": "Customer",
        "uc": "UC4a-3"
      },
      {
        "actor": "Customer",
        "uc": "UC4a-4"
      },
      {
        "actor": "Cart Service",
        "uc": "UC4a-5",
        "type": "include"
      },
      {
        "actor": "Cart Service",
        "uc": "UC4a-6",
        "type": "include"
      }
    ],
    "module": "Commerce & Order",
    "moduleId": "commerce",
    "moduleIcon": "🛒"
  },
  "commerce_checkout": {
    "description": "UC4b · Checkout: Hold Inventory (15m) → Create Order → Payment",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "Order Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Inventory",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC4b-1",
        "name": "Checkout",
        "focal": true
      },
      {
        "id": "UC4b-2",
        "name": "Reserve Inventory"
      },
      {
        "id": "UC4b-3",
        "name": "Set Hold TTL (15m)"
      },
      {
        "id": "UC4b-4",
        "name": "Create Order"
      },
      {
        "id": "UC4b-5",
        "name": "Timeout Cancel"
      },
      {
        "id": "UC4b-6",
        "name": "Release Hold"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC4b-1"
      },
      {
        "actor": "Receptionist",
        "uc": "UC4b-1"
      },
      {
        "actor": "Order Service",
        "uc": "UC4b-2",
        "type": "include"
      },
      {
        "actor": "Inventory",
        "uc": "UC4b-3",
        "type": "include"
      },
      {
        "actor": "Order Service",
        "uc": "UC4b-4",
        "type": "include"
      },
      {
        "actor": "System",
        "uc": "UC4b-5"
      },
      {
        "actor": "Inventory",
        "uc": "UC4b-6",
        "type": "include"
      }
    ],
    "module": "Commerce & Order",
    "moduleId": "commerce",
    "moduleIcon": "🛒"
  },
  "checkout": {
    "description": "UC4b · Checkout: Hold Inventory (15m) → Create Order → Payment",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "Order Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Inventory",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC4b-1",
        "name": "Checkout",
        "focal": true
      },
      {
        "id": "UC4b-2",
        "name": "Reserve Inventory"
      },
      {
        "id": "UC4b-3",
        "name": "Set Hold TTL (15m)"
      },
      {
        "id": "UC4b-4",
        "name": "Create Order"
      },
      {
        "id": "UC4b-5",
        "name": "Timeout Cancel"
      },
      {
        "id": "UC4b-6",
        "name": "Release Hold"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC4b-1"
      },
      {
        "actor": "Receptionist",
        "uc": "UC4b-1"
      },
      {
        "actor": "Order Service",
        "uc": "UC4b-2",
        "type": "include"
      },
      {
        "actor": "Inventory",
        "uc": "UC4b-3",
        "type": "include"
      },
      {
        "actor": "Order Service",
        "uc": "UC4b-4",
        "type": "include"
      },
      {
        "actor": "System",
        "uc": "UC4b-5"
      },
      {
        "actor": "Inventory",
        "uc": "UC4b-6",
        "type": "include"
      }
    ],
    "module": "Commerce & Order",
    "moduleId": "commerce",
    "moduleIcon": "🛒"
  },
  "commerce_fulfillment": {
    "description": "UC4c · Order Fulfillment: POS (Instant) vs Online (Staged)",
    "actors": [
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "Inventory Staff",
        "color": "#7b4ac7"
      },
      {
        "name": "Order Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC4c-1",
        "name": "Process Order (Online)"
      },
      {
        "id": "UC4c-2",
        "name": "Prepare Product"
      },
      {
        "id": "UC4c-3",
        "name": "Mark Ready"
      },
      {
        "id": "UC4c-4",
        "name": "Handover Order"
      },
      {
        "id": "UC4c-5",
        "name": "Complete POS Order"
      },
      {
        "id": "UC4c-6",
        "name": "Deduct Inventory"
      }
    ],
    "relations": [
      {
        "actor": "Inventory Staff",
        "uc": "UC4c-1"
      },
      {
        "actor": "Inventory Staff",
        "uc": "UC4c-2"
      },
      {
        "actor": "Order Service",
        "uc": "UC4c-3",
        "type": "include"
      },
      {
        "actor": "Receptionist",
        "uc": "UC4c-4"
      },
      {
        "actor": "Receptionist",
        "uc": "UC4c-5"
      },
      {
        "actor": "Order Service",
        "uc": "UC4c-6",
        "type": "include"
      }
    ],
    "module": "Commerce & Order",
    "moduleId": "commerce",
    "moduleIcon": "🛒"
  },
  "fulfillment": {
    "description": "UC4c · Order Fulfillment: POS (Instant) vs Online (Staged)",
    "actors": [
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "Inventory Staff",
        "color": "#7b4ac7"
      },
      {
        "name": "Order Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC4c-1",
        "name": "Process Order (Online)"
      },
      {
        "id": "UC4c-2",
        "name": "Prepare Product"
      },
      {
        "id": "UC4c-3",
        "name": "Mark Ready"
      },
      {
        "id": "UC4c-4",
        "name": "Handover Order"
      },
      {
        "id": "UC4c-5",
        "name": "Complete POS Order"
      },
      {
        "id": "UC4c-6",
        "name": "Deduct Inventory"
      }
    ],
    "relations": [
      {
        "actor": "Inventory Staff",
        "uc": "UC4c-1"
      },
      {
        "actor": "Inventory Staff",
        "uc": "UC4c-2"
      },
      {
        "actor": "Order Service",
        "uc": "UC4c-3",
        "type": "include"
      },
      {
        "actor": "Receptionist",
        "uc": "UC4c-4"
      },
      {
        "actor": "Receptionist",
        "uc": "UC4c-5"
      },
      {
        "actor": "Order Service",
        "uc": "UC4c-6",
        "type": "include"
      }
    ],
    "module": "Commerce & Order",
    "moduleId": "commerce",
    "moduleIcon": "🛒"
  },
  "clinical_clinical_examination": {
    "description": "UC5a · Examination Flow: ExaminePet → RecordSymptom → RecordExaminationResult → DiagnosePet",
    "actors": [
      {
        "name": "Veterinarian",
        "color": "#eb6c36"
      },
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "EMR Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC5a-1",
        "name": "Examine Pet",
        "focal": true
      },
      {
        "id": "UC5a-2",
        "name": "Record Symptoms"
      },
      {
        "id": "UC5a-3",
        "name": "Record Exam Results"
      },
      {
        "id": "UC5a-4",
        "name": "Create Medical Record"
      },
      {
        "id": "UC5a-5",
        "name": "Diagnose Pet"
      },
      {
        "id": "UC5a-6",
        "name": "Create Treatment Plan"
      },
      {
        "id": "UC5a-7",
        "name": "View Medical History"
      }
    ],
    "relations": [
      {
        "actor": "Veterinarian",
        "uc": "UC5a-1"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5a-2",
        "type": "include"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5a-3",
        "type": "include"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5a-4",
        "type": "include"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC5a-5"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC5a-6"
      },
      {
        "actor": "Customer",
        "uc": "UC5a-7"
      }
    ],
    "module": "Clinical & EMR",
    "moduleId": "clinical",
    "moduleIcon": "🩺"
  },
  "clinical-examination": {
    "description": "UC5a · Examination Flow: ExaminePet → RecordSymptom → RecordExaminationResult → DiagnosePet",
    "actors": [
      {
        "name": "Veterinarian",
        "color": "#eb6c36"
      },
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "EMR Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC5a-1",
        "name": "Examine Pet",
        "focal": true
      },
      {
        "id": "UC5a-2",
        "name": "Record Symptoms"
      },
      {
        "id": "UC5a-3",
        "name": "Record Exam Results"
      },
      {
        "id": "UC5a-4",
        "name": "Create Medical Record"
      },
      {
        "id": "UC5a-5",
        "name": "Diagnose Pet"
      },
      {
        "id": "UC5a-6",
        "name": "Create Treatment Plan"
      },
      {
        "id": "UC5a-7",
        "name": "View Medical History"
      }
    ],
    "relations": [
      {
        "actor": "Veterinarian",
        "uc": "UC5a-1"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5a-2",
        "type": "include"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5a-3",
        "type": "include"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5a-4",
        "type": "include"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC5a-5"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC5a-6"
      },
      {
        "actor": "Customer",
        "uc": "UC5a-7"
      }
    ],
    "module": "Clinical & EMR",
    "moduleId": "clinical",
    "moduleIcon": "🩺"
  },
  "clinical_prescription": {
    "description": "UC5b · Prescription: CreatePrescription → AddItems → CreateFollowUp",
    "actors": [
      {
        "name": "Veterinarian",
        "color": "#eb6c36"
      },
      {
        "name": "EMR Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Inventory",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC5b-1",
        "name": "Create Prescription",
        "focal": true
      },
      {
        "id": "UC5b-2",
        "name": "Add Prescription Items"
      },
      {
        "id": "UC5b-3",
        "name": "Check Drug Inventory"
      },
      {
        "id": "UC5b-4",
        "name": "Validate Dosage"
      },
      {
        "id": "UC5b-5",
        "name": "Sign Prescription"
      },
      {
        "id": "UC5b-6",
        "name": "Create Follow-up"
      },
      {
        "id": "UC5b-7",
        "name": "Schedule Next Appointment"
      }
    ],
    "relations": [
      {
        "actor": "Veterinarian",
        "uc": "UC5b-1"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC5b-2"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5b-3",
        "type": "include"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5b-4",
        "type": "include"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5b-5",
        "type": "include"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC5b-6"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5b-7",
        "type": "include"
      }
    ],
    "module": "Clinical & EMR",
    "moduleId": "clinical",
    "moduleIcon": "🩺"
  },
  "prescription": {
    "description": "UC5b · Prescription: CreatePrescription → AddItems → CreateFollowUp",
    "actors": [
      {
        "name": "Veterinarian",
        "color": "#eb6c36"
      },
      {
        "name": "EMR Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Inventory",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC5b-1",
        "name": "Create Prescription",
        "focal": true
      },
      {
        "id": "UC5b-2",
        "name": "Add Prescription Items"
      },
      {
        "id": "UC5b-3",
        "name": "Check Drug Inventory"
      },
      {
        "id": "UC5b-4",
        "name": "Validate Dosage"
      },
      {
        "id": "UC5b-5",
        "name": "Sign Prescription"
      },
      {
        "id": "UC5b-6",
        "name": "Create Follow-up"
      },
      {
        "id": "UC5b-7",
        "name": "Schedule Next Appointment"
      }
    ],
    "relations": [
      {
        "actor": "Veterinarian",
        "uc": "UC5b-1"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC5b-2"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5b-3",
        "type": "include"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5b-4",
        "type": "include"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5b-5",
        "type": "include"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC5b-6"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5b-7",
        "type": "include"
      }
    ],
    "module": "Clinical & EMR",
    "moduleId": "clinical",
    "moduleIcon": "🩺"
  },
  "clinical_cross_store_emr": {
    "description": "UC5c · Cross-Store EMR Access: Request → OTP Consent (5m TTL) → 24h Access",
    "actors": [
      {
        "name": "Veterinarian (Other Store)",
        "color": "#eb6c36"
      },
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "EMR Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "OTP Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC5c-1",
        "name": "Request Cross-Store Consent",
        "focal": true
      },
      {
        "id": "UC5c-2",
        "name": "Generate OTP (TTL=5m)"
      },
      {
        "id": "UC5c-3",
        "name": "Send OTP Notification"
      },
      {
        "id": "UC5c-4",
        "name": "Verify OTP"
      },
      {
        "id": "UC5c-5",
        "name": "Grant Access (24h)"
      },
      {
        "id": "UC5c-6",
        "name": "Revoke Consent"
      },
      {
        "id": "UC5c-7",
        "name": "Process Consent Expiry"
      }
    ],
    "relations": [
      {
        "actor": "Veterinarian (Other Store)",
        "uc": "UC5c-1"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5c-2",
        "type": "include"
      },
      {
        "actor": "OTP Service",
        "uc": "UC5c-3",
        "type": "include"
      },
      {
        "actor": "Customer",
        "uc": "UC5c-4"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5c-5"
      },
      {
        "actor": "Customer",
        "uc": "UC5c-6"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5c-7"
      }
    ],
    "module": "Clinical & EMR",
    "moduleId": "clinical",
    "moduleIcon": "🩺"
  },
  "cross-store-emr": {
    "description": "UC5c · Cross-Store EMR Access: Request → OTP Consent (5m TTL) → 24h Access",
    "actors": [
      {
        "name": "Veterinarian (Other Store)",
        "color": "#eb6c36"
      },
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "EMR Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "OTP Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC5c-1",
        "name": "Request Cross-Store Consent",
        "focal": true
      },
      {
        "id": "UC5c-2",
        "name": "Generate OTP (TTL=5m)"
      },
      {
        "id": "UC5c-3",
        "name": "Send OTP Notification"
      },
      {
        "id": "UC5c-4",
        "name": "Verify OTP"
      },
      {
        "id": "UC5c-5",
        "name": "Grant Access (24h)"
      },
      {
        "id": "UC5c-6",
        "name": "Revoke Consent"
      },
      {
        "id": "UC5c-7",
        "name": "Process Consent Expiry"
      }
    ],
    "relations": [
      {
        "actor": "Veterinarian (Other Store)",
        "uc": "UC5c-1"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5c-2",
        "type": "include"
      },
      {
        "actor": "OTP Service",
        "uc": "UC5c-3",
        "type": "include"
      },
      {
        "actor": "Customer",
        "uc": "UC5c-4"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5c-5"
      },
      {
        "actor": "Customer",
        "uc": "UC5c-6"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5c-7"
      }
    ],
    "module": "Clinical & EMR",
    "moduleId": "clinical",
    "moduleIcon": "🩺"
  },
  "clinical_emergency_override": {
    "description": "UC5d · Emergency Override: Break-Glass → Immediate Access → CreateIncident → Notify",
    "actors": [
      {
        "name": "Veterinarian",
        "color": "#eb6c36"
      },
      {
        "name": "EMR Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Incident Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Notification Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC5d-1",
        "name": "Emergency Override Access",
        "focal": true
      },
      {
        "id": "UC5d-2",
        "name": "Record Clinical Reason"
      },
      {
        "id": "UC5d-3",
        "name": "Create Clinical Incident"
      },
      {
        "id": "UC5d-4",
        "name": "Set CRITICAL Severity"
      },
      {
        "id": "UC5d-5",
        "name": "Record Audit Log"
      },
      {
        "id": "UC5d-6",
        "name": "Send Incident Notification"
      },
      {
        "id": "UC5d-7",
        "name": "Alert Customer"
      },
      {
        "id": "UC5d-8",
        "name": "Alert Store Manager"
      }
    ],
    "relations": [
      {
        "actor": "Veterinarian",
        "uc": "UC5d-1"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5d-2",
        "type": "include"
      },
      {
        "actor": "Incident Service",
        "uc": "UC5d-3",
        "type": "include"
      },
      {
        "actor": "Incident Service",
        "uc": "UC5d-4",
        "type": "include"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5d-5",
        "type": "include"
      },
      {
        "actor": "Notification Service",
        "uc": "UC5d-6",
        "type": "include"
      },
      {
        "actor": "Notification Service",
        "uc": "UC5d-7"
      },
      {
        "actor": "Notification Service",
        "uc": "UC5d-8"
      }
    ],
    "module": "Clinical & EMR",
    "moduleId": "clinical",
    "moduleIcon": "🩺"
  },
  "emergency-override": {
    "description": "UC5d · Emergency Override: Break-Glass → Immediate Access → CreateIncident → Notify",
    "actors": [
      {
        "name": "Veterinarian",
        "color": "#eb6c36"
      },
      {
        "name": "EMR Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Incident Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Notification Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC5d-1",
        "name": "Emergency Override Access",
        "focal": true
      },
      {
        "id": "UC5d-2",
        "name": "Record Clinical Reason"
      },
      {
        "id": "UC5d-3",
        "name": "Create Clinical Incident"
      },
      {
        "id": "UC5d-4",
        "name": "Set CRITICAL Severity"
      },
      {
        "id": "UC5d-5",
        "name": "Record Audit Log"
      },
      {
        "id": "UC5d-6",
        "name": "Send Incident Notification"
      },
      {
        "id": "UC5d-7",
        "name": "Alert Customer"
      },
      {
        "id": "UC5d-8",
        "name": "Alert Store Manager"
      }
    ],
    "relations": [
      {
        "actor": "Veterinarian",
        "uc": "UC5d-1"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5d-2",
        "type": "include"
      },
      {
        "actor": "Incident Service",
        "uc": "UC5d-3",
        "type": "include"
      },
      {
        "actor": "Incident Service",
        "uc": "UC5d-4",
        "type": "include"
      },
      {
        "actor": "EMR Service",
        "uc": "UC5d-5",
        "type": "include"
      },
      {
        "actor": "Notification Service",
        "uc": "UC5d-6",
        "type": "include"
      },
      {
        "actor": "Notification Service",
        "uc": "UC5d-7"
      },
      {
        "actor": "Notification Service",
        "uc": "UC5d-8"
      }
    ],
    "module": "Clinical & EMR",
    "moduleId": "clinical",
    "moduleIcon": "🩺"
  },
  "vaccination_routine": {
    "description": "UC6a · Routine Vaccination: Fast-track, no complex EMR, direct to VaccinationRecord",
    "actors": [
      {
        "name": "Veterinarian",
        "color": "#eb6c36"
      },
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Vaccine Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Inventory",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC6a-1",
        "name": "Check Vaccination Schedule",
        "focal": true
      },
      {
        "id": "UC6a-2",
        "name": "Screening Health Status"
      },
      {
        "id": "UC6a-3",
        "name": "Scan Vaccine Barcode"
      },
      {
        "id": "UC6a-4",
        "name": "Verify Batch Expiry"
      },
      {
        "id": "UC6a-5",
        "name": "Administer Vaccine"
      },
      {
        "id": "UC6a-6",
        "name": "Record Vaccination"
      },
      {
        "id": "UC6a-7",
        "name": "Schedule Next Dose"
      }
    ],
    "relations": [
      {
        "actor": "Veterinarian",
        "uc": "UC6a-1"
      },
      {
        "actor": "Vaccine Service",
        "uc": "UC6a-2",
        "type": "include"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC6a-3"
      },
      {
        "actor": "Vaccine Service",
        "uc": "UC6a-4",
        "type": "include"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC6a-5"
      },
      {
        "actor": "Vaccine Service",
        "uc": "UC6a-6",
        "type": "include"
      },
      {
        "actor": "Vaccine Service",
        "uc": "UC6a-7",
        "type": "include"
      }
    ],
    "module": "Vaccination",
    "moduleId": "vaccination",
    "moduleIcon": "💉"
  },
  "routine": {
    "description": "UC6a · Routine Vaccination: Fast-track, no complex EMR, direct to VaccinationRecord",
    "actors": [
      {
        "name": "Veterinarian",
        "color": "#eb6c36"
      },
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Vaccine Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Inventory",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC6a-1",
        "name": "Check Vaccination Schedule",
        "focal": true
      },
      {
        "id": "UC6a-2",
        "name": "Screening Health Status"
      },
      {
        "id": "UC6a-3",
        "name": "Scan Vaccine Barcode"
      },
      {
        "id": "UC6a-4",
        "name": "Verify Batch Expiry"
      },
      {
        "id": "UC6a-5",
        "name": "Administer Vaccine"
      },
      {
        "id": "UC6a-6",
        "name": "Record Vaccination"
      },
      {
        "id": "UC6a-7",
        "name": "Schedule Next Dose"
      }
    ],
    "relations": [
      {
        "actor": "Veterinarian",
        "uc": "UC6a-1"
      },
      {
        "actor": "Vaccine Service",
        "uc": "UC6a-2",
        "type": "include"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC6a-3"
      },
      {
        "actor": "Vaccine Service",
        "uc": "UC6a-4",
        "type": "include"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC6a-5"
      },
      {
        "actor": "Vaccine Service",
        "uc": "UC6a-6",
        "type": "include"
      },
      {
        "actor": "Vaccine Service",
        "uc": "UC6a-7",
        "type": "include"
      }
    ],
    "module": "Vaccination",
    "moduleId": "vaccination",
    "moduleIcon": "💉"
  },
  "vaccination_therapeutic": {
    "description": "UC6b · Therapeutic Vaccination: Must link to MedicalRecord & Treatment",
    "actors": [
      {
        "name": "Veterinarian",
        "color": "#eb6c36"
      },
      {
        "name": "EMR Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Inventory",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC6b-1",
        "name": "Link to Treatment Plan",
        "focal": true
      },
      {
        "id": "UC6b-2",
        "name": "Link to Medical Record"
      },
      {
        "id": "UC6b-3",
        "name": "Scan Vaccine Barcode"
      },
      {
        "id": "UC6b-4",
        "name": "Administer Vaccine"
      },
      {
        "id": "UC6b-5",
        "name": "Record in EMR"
      }
    ],
    "relations": [
      {
        "actor": "Veterinarian",
        "uc": "UC6b-1"
      },
      {
        "actor": "EMR Service",
        "uc": "UC6b-2",
        "type": "include"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC6b-3"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC6b-4"
      },
      {
        "actor": "EMR Service",
        "uc": "UC6b-5",
        "type": "include"
      }
    ],
    "module": "Vaccination",
    "moduleId": "vaccination",
    "moduleIcon": "💉"
  },
  "therapeutic": {
    "description": "UC6b · Therapeutic Vaccination: Must link to MedicalRecord & Treatment",
    "actors": [
      {
        "name": "Veterinarian",
        "color": "#eb6c36"
      },
      {
        "name": "EMR Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Inventory",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC6b-1",
        "name": "Link to Treatment Plan",
        "focal": true
      },
      {
        "id": "UC6b-2",
        "name": "Link to Medical Record"
      },
      {
        "id": "UC6b-3",
        "name": "Scan Vaccine Barcode"
      },
      {
        "id": "UC6b-4",
        "name": "Administer Vaccine"
      },
      {
        "id": "UC6b-5",
        "name": "Record in EMR"
      }
    ],
    "relations": [
      {
        "actor": "Veterinarian",
        "uc": "UC6b-1"
      },
      {
        "actor": "EMR Service",
        "uc": "UC6b-2",
        "type": "include"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC6b-3"
      },
      {
        "actor": "Veterinarian",
        "uc": "UC6b-4"
      },
      {
        "actor": "EMR Service",
        "uc": "UC6b-5",
        "type": "include"
      }
    ],
    "module": "Vaccination",
    "moduleId": "vaccination",
    "moduleIcon": "💉"
  },
  "vaccination_schedule": {
    "description": "UC6c · Vaccination Schedule & Reminder",
    "actors": [
      {
        "name": "System",
        "color": "#4f5d75"
      },
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Notification Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC6c-1",
        "name": "Generate Schedule"
      },
      {
        "id": "UC6c-2",
        "name": "Calculate Next Due Date"
      },
      {
        "id": "UC6c-3",
        "name": "Send Reminder"
      },
      {
        "id": "UC6c-4",
        "name": "Process Expiry Warning"
      },
      {
        "id": "UC6c-5",
        "name": "View Schedule"
      }
    ],
    "relations": [
      {
        "actor": "System",
        "uc": "UC6c-1"
      },
      {
        "actor": "System",
        "uc": "UC6c-2",
        "type": "include"
      },
      {
        "actor": "Notification Service",
        "uc": "UC6c-3"
      },
      {
        "actor": "System",
        "uc": "UC6c-4"
      },
      {
        "actor": "Customer",
        "uc": "UC6c-5"
      }
    ],
    "module": "Vaccination",
    "moduleId": "vaccination",
    "moduleIcon": "💉"
  },
  "schedule": {
    "description": "UC6c · Vaccination Schedule & Reminder",
    "actors": [
      {
        "name": "System",
        "color": "#4f5d75"
      },
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Notification Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC6c-1",
        "name": "Generate Schedule"
      },
      {
        "id": "UC6c-2",
        "name": "Calculate Next Due Date"
      },
      {
        "id": "UC6c-3",
        "name": "Send Reminder"
      },
      {
        "id": "UC6c-4",
        "name": "Process Expiry Warning"
      },
      {
        "id": "UC6c-5",
        "name": "View Schedule"
      }
    ],
    "relations": [
      {
        "actor": "System",
        "uc": "UC6c-1"
      },
      {
        "actor": "System",
        "uc": "UC6c-2",
        "type": "include"
      },
      {
        "actor": "Notification Service",
        "uc": "UC6c-3"
      },
      {
        "actor": "System",
        "uc": "UC6c-4"
      },
      {
        "actor": "Customer",
        "uc": "UC6c-5"
      }
    ],
    "module": "Vaccination",
    "moduleId": "vaccination",
    "moduleIcon": "💉"
  },
  "grooming_grooming_session": {
    "description": "UC7a · Grooming Session: Inspect → Perform → Complete",
    "actors": [
      {
        "name": "Groomer",
        "color": "#45a58a"
      },
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      }
    ],
    "useCases": [
      {
        "id": "UC7a-1",
        "name": "Book Grooming",
        "focal": true
      },
      {
        "id": "UC7a-2",
        "name": "Check-in Grooming"
      },
      {
        "id": "UC7a-3",
        "name": "Inspect Pet"
      },
      {
        "id": "UC7a-4",
        "name": "Perform Grooming"
      },
      {
        "id": "UC7a-5",
        "name": "Update Progress"
      },
      {
        "id": "UC7a-6",
        "name": "Complete Grooming"
      },
      {
        "id": "UC7a-7",
        "name": "Check-out"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC7a-1"
      },
      {
        "actor": "Receptionist",
        "uc": "UC7a-1"
      },
      {
        "actor": "Receptionist",
        "uc": "UC7a-2"
      },
      {
        "actor": "Groomer",
        "uc": "UC7a-3"
      },
      {
        "actor": "Groomer",
        "uc": "UC7a-4"
      },
      {
        "actor": "Groomer",
        "uc": "UC7a-5"
      },
      {
        "actor": "Groomer",
        "uc": "UC7a-6"
      },
      {
        "actor": "Receptionist",
        "uc": "UC7a-7"
      }
    ],
    "module": "Grooming Service",
    "moduleId": "grooming",
    "moduleIcon": "✂️"
  },
  "grooming-session": {
    "description": "UC7a · Grooming Session: Inspect → Perform → Complete",
    "actors": [
      {
        "name": "Groomer",
        "color": "#45a58a"
      },
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      }
    ],
    "useCases": [
      {
        "id": "UC7a-1",
        "name": "Book Grooming",
        "focal": true
      },
      {
        "id": "UC7a-2",
        "name": "Check-in Grooming"
      },
      {
        "id": "UC7a-3",
        "name": "Inspect Pet"
      },
      {
        "id": "UC7a-4",
        "name": "Perform Grooming"
      },
      {
        "id": "UC7a-5",
        "name": "Update Progress"
      },
      {
        "id": "UC7a-6",
        "name": "Complete Grooming"
      },
      {
        "id": "UC7a-7",
        "name": "Check-out"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC7a-1"
      },
      {
        "actor": "Receptionist",
        "uc": "UC7a-1"
      },
      {
        "actor": "Receptionist",
        "uc": "UC7a-2"
      },
      {
        "actor": "Groomer",
        "uc": "UC7a-3"
      },
      {
        "actor": "Groomer",
        "uc": "UC7a-4"
      },
      {
        "actor": "Groomer",
        "uc": "UC7a-5"
      },
      {
        "actor": "Groomer",
        "uc": "UC7a-6"
      },
      {
        "actor": "Receptionist",
        "uc": "UC7a-7"
      }
    ],
    "module": "Grooming Service",
    "moduleId": "grooming",
    "moduleIcon": "✂️"
  },
  "grooming_grooming_surcharge": {
    "description": "UC7b · D-02 Surcharge Invoice: AddService → Await Approval → Create Independent Invoice",
    "actors": [
      {
        "name": "Groomer",
        "color": "#45a58a"
      },
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Grooming Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Invoice Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC7b-1",
        "name": "Add Grooming Service",
        "focal": true
      },
      {
        "id": "UC7b-2",
        "name": "Await Customer Approval"
      },
      {
        "id": "UC7b-3",
        "name": "Confirm Additional Service"
      },
      {
        "id": "UC7b-4",
        "name": "Reject Additional Service"
      },
      {
        "id": "UC7b-5",
        "name": "Create Surcharge Invoice",
        "type": "extend"
      },
      {
        "id": "UC7b-6",
        "name": "Invoice → DRAFT"
      },
      {
        "id": "UC7b-7",
        "name": "Invoice → ISSUED"
      },
      {
        "id": "UC7b-8",
        "name": "Surcharge → PAID"
      },
      {
        "id": "UC7b-9",
        "name": "Resume Grooming"
      }
    ],
    "relations": [
      {
        "actor": "Groomer",
        "uc": "UC7b-1"
      },
      {
        "actor": "Grooming Service",
        "uc": "UC7b-2",
        "type": "include"
      },
      {
        "actor": "Customer",
        "uc": "UC7b-3"
      },
      {
        "actor": "Customer",
        "uc": "UC7b-4"
      },
      {
        "actor": "Groomer",
        "uc": "UC7b-5",
        "type": "extend"
      },
      {
        "actor": "Invoice Service",
        "uc": "UC7b-6",
        "type": "include"
      },
      {
        "actor": "Invoice Service",
        "uc": "UC7b-7",
        "type": "include"
      },
      {
        "actor": "Invoice Service",
        "uc": "UC7b-8",
        "type": "include"
      },
      {
        "actor": "Grooming Service",
        "uc": "UC7b-9"
      }
    ],
    "module": "Grooming Service",
    "moduleId": "grooming",
    "moduleIcon": "✂️"
  },
  "grooming-surcharge": {
    "description": "UC7b · D-02 Surcharge Invoice: AddService → Await Approval → Create Independent Invoice",
    "actors": [
      {
        "name": "Groomer",
        "color": "#45a58a"
      },
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Grooming Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Invoice Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC7b-1",
        "name": "Add Grooming Service",
        "focal": true
      },
      {
        "id": "UC7b-2",
        "name": "Await Customer Approval"
      },
      {
        "id": "UC7b-3",
        "name": "Confirm Additional Service"
      },
      {
        "id": "UC7b-4",
        "name": "Reject Additional Service"
      },
      {
        "id": "UC7b-5",
        "name": "Create Surcharge Invoice",
        "type": "extend"
      },
      {
        "id": "UC7b-6",
        "name": "Invoice → DRAFT"
      },
      {
        "id": "UC7b-7",
        "name": "Invoice → ISSUED"
      },
      {
        "id": "UC7b-8",
        "name": "Surcharge → PAID"
      },
      {
        "id": "UC7b-9",
        "name": "Resume Grooming"
      }
    ],
    "relations": [
      {
        "actor": "Groomer",
        "uc": "UC7b-1"
      },
      {
        "actor": "Grooming Service",
        "uc": "UC7b-2",
        "type": "include"
      },
      {
        "actor": "Customer",
        "uc": "UC7b-3"
      },
      {
        "actor": "Customer",
        "uc": "UC7b-4"
      },
      {
        "actor": "Groomer",
        "uc": "UC7b-5",
        "type": "extend"
      },
      {
        "actor": "Invoice Service",
        "uc": "UC7b-6",
        "type": "include"
      },
      {
        "actor": "Invoice Service",
        "uc": "UC7b-7",
        "type": "include"
      },
      {
        "actor": "Invoice Service",
        "uc": "UC7b-8",
        "type": "include"
      },
      {
        "actor": "Grooming Service",
        "uc": "UC7b-9"
      }
    ],
    "module": "Grooming Service",
    "moduleId": "grooming",
    "moduleIcon": "✂️"
  },
  "grooming_grooming_emergency": {
    "description": "UC7c · Emergency Abort: Safety Issue → Abort → Create Incident → Notify",
    "actors": [
      {
        "name": "Groomer",
        "color": "#45a58a"
      },
      {
        "name": "Store Manager",
        "color": "#7b4ac7"
      },
      {
        "name": "Grooming Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Notification Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC7c-1",
        "name": "Detect Safety Issue",
        "focal": true
      },
      {
        "id": "UC7c-2",
        "name": "Abort Grooming"
      },
      {
        "id": "UC7c-3",
        "name": "Create Grooming Incident"
      },
      {
        "id": "UC7c-4",
        "name": "Release Resources"
      },
      {
        "id": "UC7c-5",
        "name": "Send Incident Notification"
      },
      {
        "id": "UC7c-6",
        "name": "Process Partial Refund"
      }
    ],
    "relations": [
      {
        "actor": "Groomer",
        "uc": "UC7c-1"
      },
      {
        "actor": "Groomer",
        "uc": "UC7c-2"
      },
      {
        "actor": "Grooming Service",
        "uc": "UC7c-3",
        "type": "include"
      },
      {
        "actor": "Grooming Service",
        "uc": "UC7c-4",
        "type": "include"
      },
      {
        "actor": "Notification Service",
        "uc": "UC7c-5",
        "type": "include"
      },
      {
        "actor": "Store Manager",
        "uc": "UC7c-6"
      }
    ],
    "module": "Grooming Service",
    "moduleId": "grooming",
    "moduleIcon": "✂️"
  },
  "grooming-emergency": {
    "description": "UC7c · Emergency Abort: Safety Issue → Abort → Create Incident → Notify",
    "actors": [
      {
        "name": "Groomer",
        "color": "#45a58a"
      },
      {
        "name": "Store Manager",
        "color": "#7b4ac7"
      },
      {
        "name": "Grooming Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Notification Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC7c-1",
        "name": "Detect Safety Issue",
        "focal": true
      },
      {
        "id": "UC7c-2",
        "name": "Abort Grooming"
      },
      {
        "id": "UC7c-3",
        "name": "Create Grooming Incident"
      },
      {
        "id": "UC7c-4",
        "name": "Release Resources"
      },
      {
        "id": "UC7c-5",
        "name": "Send Incident Notification"
      },
      {
        "id": "UC7c-6",
        "name": "Process Partial Refund"
      }
    ],
    "relations": [
      {
        "actor": "Groomer",
        "uc": "UC7c-1"
      },
      {
        "actor": "Groomer",
        "uc": "UC7c-2"
      },
      {
        "actor": "Grooming Service",
        "uc": "UC7c-3",
        "type": "include"
      },
      {
        "actor": "Grooming Service",
        "uc": "UC7c-4",
        "type": "include"
      },
      {
        "actor": "Notification Service",
        "uc": "UC7c-5",
        "type": "include"
      },
      {
        "actor": "Store Manager",
        "uc": "UC7c-6"
      }
    ],
    "module": "Grooming Service",
    "moduleId": "grooming",
    "moduleIcon": "✂️"
  },
  "inventory_receive": {
    "description": "UC8a · Receive Inventory: PO → Inspect → Record Batch → FEFO",
    "actors": [
      {
        "name": "Inventory Staff",
        "color": "#eb6c36"
      },
      {
        "name": "Store Manager",
        "color": "#7b4ac7"
      },
      {
        "name": "Inventory Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC8a-1",
        "name": "Receive Goods",
        "focal": true
      },
      {
        "id": "UC8a-2",
        "name": "Inspect Goods"
      },
      {
        "id": "UC8a-3",
        "name": "Record Batch Info"
      },
      {
        "id": "UC8a-4",
        "name": "Calculate FEFO Date"
      },
      {
        "id": "UC8a-5",
        "name": "Update Stock Level"
      },
      {
        "id": "UC8a-6",
        "name": "Adjust Inventory"
      }
    ],
    "relations": [
      {
        "actor": "Inventory Staff",
        "uc": "UC8a-1"
      },
      {
        "actor": "Inventory Staff",
        "uc": "UC8a-2"
      },
      {
        "actor": "Inventory Service",
        "uc": "UC8a-3",
        "type": "include"
      },
      {
        "actor": "Inventory Service",
        "uc": "UC8a-4",
        "type": "include"
      },
      {
        "actor": "Inventory Service",
        "uc": "UC8a-5",
        "type": "include"
      },
      {
        "actor": "Store Manager",
        "uc": "UC8a-6"
      }
    ],
    "module": "Inventory & Stock",
    "moduleId": "inventory",
    "moduleIcon": "📦"
  },
  "receive": {
    "description": "UC8a · Receive Inventory: PO → Inspect → Record Batch → FEFO",
    "actors": [
      {
        "name": "Inventory Staff",
        "color": "#eb6c36"
      },
      {
        "name": "Store Manager",
        "color": "#7b4ac7"
      },
      {
        "name": "Inventory Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC8a-1",
        "name": "Receive Goods",
        "focal": true
      },
      {
        "id": "UC8a-2",
        "name": "Inspect Goods"
      },
      {
        "id": "UC8a-3",
        "name": "Record Batch Info"
      },
      {
        "id": "UC8a-4",
        "name": "Calculate FEFO Date"
      },
      {
        "id": "UC8a-5",
        "name": "Update Stock Level"
      },
      {
        "id": "UC8a-6",
        "name": "Adjust Inventory"
      }
    ],
    "relations": [
      {
        "actor": "Inventory Staff",
        "uc": "UC8a-1"
      },
      {
        "actor": "Inventory Staff",
        "uc": "UC8a-2"
      },
      {
        "actor": "Inventory Service",
        "uc": "UC8a-3",
        "type": "include"
      },
      {
        "actor": "Inventory Service",
        "uc": "UC8a-4",
        "type": "include"
      },
      {
        "actor": "Inventory Service",
        "uc": "UC8a-5",
        "type": "include"
      },
      {
        "actor": "Store Manager",
        "uc": "UC8a-6"
      }
    ],
    "module": "Inventory & Stock",
    "moduleId": "inventory",
    "moduleIcon": "📦"
  },
  "inventory_transfer": {
    "description": "UC8b · Stock Transfer: REQUESTED → APPROVED → SHIPPED → IN_TRANSIT → RECEIVED",
    "actors": [
      {
        "name": "Inventory Staff",
        "color": "#eb6c36"
      },
      {
        "name": "Store Manager",
        "color": "#7b4ac7"
      },
      {
        "name": "Transfer Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC8b-1",
        "name": "Create Stock Transfer",
        "focal": true
      },
      {
        "id": "UC8b-2",
        "name": "Approve Transfer"
      },
      {
        "id": "UC8b-3",
        "name": "Ship Stock"
      },
      {
        "id": "UC8b-4",
        "name": "Receive Stock"
      },
      {
        "id": "UC8b-5",
        "name": "Handle Discrepancy"
      },
      {
        "id": "UC8b-6",
        "name": "Record Transfer"
      }
    ],
    "relations": [
      {
        "actor": "Inventory Staff",
        "uc": "UC8b-1"
      },
      {
        "actor": "Store Manager",
        "uc": "UC8b-2"
      },
      {
        "actor": "Inventory Staff",
        "uc": "UC8b-3"
      },
      {
        "actor": "Inventory Staff",
        "uc": "UC8b-4"
      },
      {
        "actor": "Transfer Service",
        "uc": "UC8b-5",
        "type": "include"
      },
      {
        "actor": "Transfer Service",
        "uc": "UC8b-6",
        "type": "include"
      }
    ],
    "module": "Inventory & Stock",
    "moduleId": "inventory",
    "moduleIcon": "📦"
  },
  "transfer": {
    "description": "UC8b · Stock Transfer: REQUESTED → APPROVED → SHIPPED → IN_TRANSIT → RECEIVED",
    "actors": [
      {
        "name": "Inventory Staff",
        "color": "#eb6c36"
      },
      {
        "name": "Store Manager",
        "color": "#7b4ac7"
      },
      {
        "name": "Transfer Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC8b-1",
        "name": "Create Stock Transfer",
        "focal": true
      },
      {
        "id": "UC8b-2",
        "name": "Approve Transfer"
      },
      {
        "id": "UC8b-3",
        "name": "Ship Stock"
      },
      {
        "id": "UC8b-4",
        "name": "Receive Stock"
      },
      {
        "id": "UC8b-5",
        "name": "Handle Discrepancy"
      },
      {
        "id": "UC8b-6",
        "name": "Record Transfer"
      }
    ],
    "relations": [
      {
        "actor": "Inventory Staff",
        "uc": "UC8b-1"
      },
      {
        "actor": "Store Manager",
        "uc": "UC8b-2"
      },
      {
        "actor": "Inventory Staff",
        "uc": "UC8b-3"
      },
      {
        "actor": "Inventory Staff",
        "uc": "UC8b-4"
      },
      {
        "actor": "Transfer Service",
        "uc": "UC8b-5",
        "type": "include"
      },
      {
        "actor": "Transfer Service",
        "uc": "UC8b-6",
        "type": "include"
      }
    ],
    "module": "Inventory & Stock",
    "moduleId": "inventory",
    "moduleIcon": "📦"
  },
  "inventory_alerts": {
    "description": "UC8c · Inventory Alerts: Low Stock, Expiry Warning, FEFO Enforcement",
    "actors": [
      {
        "name": "System",
        "color": "#4f5d75"
      },
      {
        "name": "Store Manager",
        "color": "#7b4ac7"
      },
      {
        "name": "Notification Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC8c-1",
        "name": "Monitor Stock Levels"
      },
      {
        "id": "UC8c-2",
        "name": "Trigger Low Stock Alert"
      },
      {
        "id": "UC8c-3",
        "name": "Trigger Expiry Warning"
      },
      {
        "id": "UC8c-4",
        "name": "FEFO Picking Enforcement"
      },
      {
        "id": "UC8c-5",
        "name": "Auto Reorder Suggestion"
      }
    ],
    "relations": [
      {
        "actor": "System",
        "uc": "UC8c-1"
      },
      {
        "actor": "System",
        "uc": "UC8c-2"
      },
      {
        "actor": "System",
        "uc": "UC8c-3"
      },
      {
        "actor": "System",
        "uc": "UC8c-4"
      },
      {
        "actor": "Notification Service",
        "uc": "UC8c-5",
        "type": "include"
      },
      {
        "actor": "Store Manager",
        "uc": "UC8c-5"
      }
    ],
    "module": "Inventory & Stock",
    "moduleId": "inventory",
    "moduleIcon": "📦"
  },
  "alerts": {
    "description": "UC8c · Inventory Alerts: Low Stock, Expiry Warning, FEFO Enforcement",
    "actors": [
      {
        "name": "System",
        "color": "#4f5d75"
      },
      {
        "name": "Store Manager",
        "color": "#7b4ac7"
      },
      {
        "name": "Notification Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC8c-1",
        "name": "Monitor Stock Levels"
      },
      {
        "id": "UC8c-2",
        "name": "Trigger Low Stock Alert"
      },
      {
        "id": "UC8c-3",
        "name": "Trigger Expiry Warning"
      },
      {
        "id": "UC8c-4",
        "name": "FEFO Picking Enforcement"
      },
      {
        "id": "UC8c-5",
        "name": "Auto Reorder Suggestion"
      }
    ],
    "relations": [
      {
        "actor": "System",
        "uc": "UC8c-1"
      },
      {
        "actor": "System",
        "uc": "UC8c-2"
      },
      {
        "actor": "System",
        "uc": "UC8c-3"
      },
      {
        "actor": "System",
        "uc": "UC8c-4"
      },
      {
        "actor": "Notification Service",
        "uc": "UC8c-5",
        "type": "include"
      },
      {
        "actor": "Store Manager",
        "uc": "UC8c-5"
      }
    ],
    "module": "Inventory & Stock",
    "moduleId": "inventory",
    "moduleIcon": "📦"
  },
  "procurement_pr": {
    "description": "UC9a · Purchase Request: Maker-Checker, 4 eyes principle",
    "actors": [
      {
        "name": "Inventory Staff",
        "color": "#eb6c36"
      },
      {
        "name": "Store Manager",
        "color": "#7b4ac7"
      },
      {
        "name": "Procurement Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC9a-1",
        "name": "Create Purchase Request",
        "focal": true
      },
      {
        "id": "UC9a-2",
        "name": "Approve Purchase Request"
      },
      {
        "id": "UC9a-3",
        "name": "Reject Purchase Request"
      },
      {
        "id": "UC9a-4",
        "name": "Cancel Purchase Request"
      },
      {
        "id": "UC9a-5",
        "name": "Auto-generate PO"
      }
    ],
    "relations": [
      {
        "actor": "Inventory Staff",
        "uc": "UC9a-1"
      },
      {
        "actor": "Store Manager",
        "uc": "UC9a-2"
      },
      {
        "actor": "Store Manager",
        "uc": "UC9a-3"
      },
      {
        "actor": "Inventory Staff",
        "uc": "UC9a-4"
      },
      {
        "actor": "Procurement Service",
        "uc": "UC9a-5",
        "type": "include"
      }
    ],
    "module": "Procurement",
    "moduleId": "procurement",
    "moduleIcon": "📋"
  },
  "pr": {
    "description": "UC9a · Purchase Request: Maker-Checker, 4 eyes principle",
    "actors": [
      {
        "name": "Inventory Staff",
        "color": "#eb6c36"
      },
      {
        "name": "Store Manager",
        "color": "#7b4ac7"
      },
      {
        "name": "Procurement Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC9a-1",
        "name": "Create Purchase Request",
        "focal": true
      },
      {
        "id": "UC9a-2",
        "name": "Approve Purchase Request"
      },
      {
        "id": "UC9a-3",
        "name": "Reject Purchase Request"
      },
      {
        "id": "UC9a-4",
        "name": "Cancel Purchase Request"
      },
      {
        "id": "UC9a-5",
        "name": "Auto-generate PO"
      }
    ],
    "relations": [
      {
        "actor": "Inventory Staff",
        "uc": "UC9a-1"
      },
      {
        "actor": "Store Manager",
        "uc": "UC9a-2"
      },
      {
        "actor": "Store Manager",
        "uc": "UC9a-3"
      },
      {
        "actor": "Inventory Staff",
        "uc": "UC9a-4"
      },
      {
        "actor": "Procurement Service",
        "uc": "UC9a-5",
        "type": "include"
      }
    ],
    "module": "Procurement",
    "moduleId": "procurement",
    "moduleIcon": "📋"
  },
  "procurement_po": {
    "description": "UC9b · Purchase Order: Create PO → Receive Goods → Inspect → Update Inventory",
    "actors": [
      {
        "name": "Inventory Staff",
        "color": "#eb6c36"
      },
      {
        "name": "Supplier",
        "color": "#4f5d75"
      },
      {
        "name": "Procurement Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Inventory",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC9b-1",
        "name": "Create Purchase Order",
        "focal": true
      },
      {
        "id": "UC9b-2",
        "name": "Send PO to Supplier"
      },
      {
        "id": "UC9b-3",
        "name": "Receive Goods"
      },
      {
        "id": "UC9b-4",
        "name": "Inspect Goods"
      },
      {
        "id": "UC9b-5",
        "name": "Update Inventory"
      },
      {
        "id": "UC9b-6",
        "name": "Cancel PO"
      }
    ],
    "relations": [
      {
        "actor": "Inventory Staff",
        "uc": "UC9b-1"
      },
      {
        "actor": "Procurement Service",
        "uc": "UC9b-2",
        "type": "include"
      },
      {
        "actor": "Supplier",
        "uc": "UC9b-3"
      },
      {
        "actor": "Inventory Staff",
        "uc": "UC9b-4"
      },
      {
        "actor": "Inventory",
        "uc": "UC9b-5",
        "type": "include"
      },
      {
        "actor": "Inventory Staff",
        "uc": "UC9b-6"
      }
    ],
    "module": "Procurement",
    "moduleId": "procurement",
    "moduleIcon": "📋"
  },
  "po": {
    "description": "UC9b · Purchase Order: Create PO → Receive Goods → Inspect → Update Inventory",
    "actors": [
      {
        "name": "Inventory Staff",
        "color": "#eb6c36"
      },
      {
        "name": "Supplier",
        "color": "#4f5d75"
      },
      {
        "name": "Procurement Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Inventory",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC9b-1",
        "name": "Create Purchase Order",
        "focal": true
      },
      {
        "id": "UC9b-2",
        "name": "Send PO to Supplier"
      },
      {
        "id": "UC9b-3",
        "name": "Receive Goods"
      },
      {
        "id": "UC9b-4",
        "name": "Inspect Goods"
      },
      {
        "id": "UC9b-5",
        "name": "Update Inventory"
      },
      {
        "id": "UC9b-6",
        "name": "Cancel PO"
      }
    ],
    "relations": [
      {
        "actor": "Inventory Staff",
        "uc": "UC9b-1"
      },
      {
        "actor": "Procurement Service",
        "uc": "UC9b-2",
        "type": "include"
      },
      {
        "actor": "Supplier",
        "uc": "UC9b-3"
      },
      {
        "actor": "Inventory Staff",
        "uc": "UC9b-4"
      },
      {
        "actor": "Inventory",
        "uc": "UC9b-5",
        "type": "include"
      },
      {
        "actor": "Inventory Staff",
        "uc": "UC9b-6"
      }
    ],
    "module": "Procurement",
    "moduleId": "procurement",
    "moduleIcon": "📋"
  },
  "billing_invoice_creation": {
    "description": "UC11a · Invoice Creation: Create → Add Items → Issue",
    "actors": [
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "Finance Staff",
        "color": "#7b4ac7"
      },
      {
        "name": "Invoice Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC11a-1",
        "name": "Create Invoice",
        "focal": true
      },
      {
        "id": "UC11a-2",
        "name": "Add Service Items"
      },
      {
        "id": "UC11a-3",
        "name": "Add Product Items"
      },
      {
        "id": "UC11a-4",
        "name": "Apply Discount/Voucher"
      },
      {
        "id": "UC11a-5",
        "name": "Calculate Total"
      },
      {
        "id": "UC11a-6",
        "name": "Issue Invoice"
      }
    ],
    "relations": [
      {
        "actor": "Receptionist",
        "uc": "UC11a-1"
      },
      {
        "actor": "Receptionist",
        "uc": "UC11a-2"
      },
      {
        "actor": "Receptionist",
        "uc": "UC11a-3"
      },
      {
        "actor": "Invoice Service",
        "uc": "UC11a-4",
        "type": "include"
      },
      {
        "actor": "Invoice Service",
        "uc": "UC11a-5",
        "type": "include"
      },
      {
        "actor": "Finance Staff",
        "uc": "UC11a-6"
      }
    ],
    "module": "Billing & Settlement",
    "moduleId": "billing",
    "moduleIcon": "🧾"
  },
  "invoice-creation": {
    "description": "UC11a · Invoice Creation: Create → Add Items → Issue",
    "actors": [
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "Finance Staff",
        "color": "#7b4ac7"
      },
      {
        "name": "Invoice Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC11a-1",
        "name": "Create Invoice",
        "focal": true
      },
      {
        "id": "UC11a-2",
        "name": "Add Service Items"
      },
      {
        "id": "UC11a-3",
        "name": "Add Product Items"
      },
      {
        "id": "UC11a-4",
        "name": "Apply Discount/Voucher"
      },
      {
        "id": "UC11a-5",
        "name": "Calculate Total"
      },
      {
        "id": "UC11a-6",
        "name": "Issue Invoice"
      }
    ],
    "relations": [
      {
        "actor": "Receptionist",
        "uc": "UC11a-1"
      },
      {
        "actor": "Receptionist",
        "uc": "UC11a-2"
      },
      {
        "actor": "Receptionist",
        "uc": "UC11a-3"
      },
      {
        "actor": "Invoice Service",
        "uc": "UC11a-4",
        "type": "include"
      },
      {
        "actor": "Invoice Service",
        "uc": "UC11a-5",
        "type": "include"
      },
      {
        "actor": "Finance Staff",
        "uc": "UC11a-6"
      }
    ],
    "module": "Billing & Settlement",
    "moduleId": "billing",
    "moduleIcon": "🧾"
  },
  "billing_invoice_settlement": {
    "description": "UC11b · D-01 Settlement Immutability: PaymentSucceeded → PAID (NEVER change)",
    "actors": [
      {
        "name": "System",
        "color": "#4f5d75"
      },
      {
        "name": "Invoice Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Payment Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Refund Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC11b-1",
        "name": "Payment Succeeded",
        "focal": true
      },
      {
        "id": "UC11b-2",
        "name": "Invoice → PAID"
      },
      {
        "id": "UC11b-3",
        "name": "LOCK Invoice Status"
      },
      {
        "id": "UC11b-4",
        "name": "Process Full Refund"
      },
      {
        "id": "UC11b-5",
        "name": "Process Partial Refund"
      },
      {
        "id": "UC11b-6",
        "name": "Update total_refunded_amount"
      },
      {
        "id": "UC11b-7",
        "name": "Log Refund Record"
      }
    ],
    "relations": [
      {
        "actor": "Payment Service",
        "uc": "UC11b-1"
      },
      {
        "actor": "Invoice Service",
        "uc": "UC11b-2",
        "type": "include"
      },
      {
        "actor": "System",
        "uc": "UC11b-3"
      },
      {
        "actor": "Refund Service",
        "uc": "UC11b-4"
      },
      {
        "actor": "Refund Service",
        "uc": "UC11b-5"
      },
      {
        "actor": "Invoice Service",
        "uc": "UC11b-6",
        "type": "include"
      },
      {
        "actor": "Refund Service",
        "uc": "UC11b-7",
        "type": "include"
      }
    ],
    "module": "Billing & Settlement",
    "moduleId": "billing",
    "moduleIcon": "🧾"
  },
  "invoice-settlement": {
    "description": "UC11b · D-01 Settlement Immutability: PaymentSucceeded → PAID (NEVER change)",
    "actors": [
      {
        "name": "System",
        "color": "#4f5d75"
      },
      {
        "name": "Invoice Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Payment Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Refund Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC11b-1",
        "name": "Payment Succeeded",
        "focal": true
      },
      {
        "id": "UC11b-2",
        "name": "Invoice → PAID"
      },
      {
        "id": "UC11b-3",
        "name": "LOCK Invoice Status"
      },
      {
        "id": "UC11b-4",
        "name": "Process Full Refund"
      },
      {
        "id": "UC11b-5",
        "name": "Process Partial Refund"
      },
      {
        "id": "UC11b-6",
        "name": "Update total_refunded_amount"
      },
      {
        "id": "UC11b-7",
        "name": "Log Refund Record"
      }
    ],
    "relations": [
      {
        "actor": "Payment Service",
        "uc": "UC11b-1"
      },
      {
        "actor": "Invoice Service",
        "uc": "UC11b-2",
        "type": "include"
      },
      {
        "actor": "System",
        "uc": "UC11b-3"
      },
      {
        "actor": "Refund Service",
        "uc": "UC11b-4"
      },
      {
        "actor": "Refund Service",
        "uc": "UC11b-5"
      },
      {
        "actor": "Invoice Service",
        "uc": "UC11b-6",
        "type": "include"
      },
      {
        "actor": "Refund Service",
        "uc": "UC11b-7",
        "type": "include"
      }
    ],
    "module": "Billing & Settlement",
    "moduleId": "billing",
    "moduleIcon": "🧾"
  },
  "billing_invoice_void": {
    "description": "UC11c · Invoice Void & Discard: DRAFT → Discard, ISSUED → Void, PAID → NEVER",
    "actors": [
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "Finance Staff",
        "color": "#7b4ac7"
      },
      {
        "name": "Invoice Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC11c-1",
        "name": "Discard Draft Invoice"
      },
      {
        "id": "UC11c-2",
        "name": "Void Issued Invoice"
      },
      {
        "id": "UC11c-3",
        "name": "Record Void Reason"
      },
      {
        "id": "UC11c-4",
        "name": "Prevent Void PAID Invoice"
      },
      {
        "id": "UC11c-5",
        "name": "Reconcile Invoices"
      }
    ],
    "relations": [
      {
        "actor": "Receptionist",
        "uc": "UC11c-1"
      },
      {
        "actor": "Finance Staff",
        "uc": "UC11c-2"
      },
      {
        "actor": "Invoice Service",
        "uc": "UC11c-3",
        "type": "include"
      },
      {
        "actor": "Invoice Service",
        "uc": "UC11c-4"
      },
      {
        "actor": "Finance Staff",
        "uc": "UC11c-5"
      }
    ],
    "module": "Billing & Settlement",
    "moduleId": "billing",
    "moduleIcon": "🧾"
  },
  "invoice-void": {
    "description": "UC11c · Invoice Void & Discard: DRAFT → Discard, ISSUED → Void, PAID → NEVER",
    "actors": [
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "Finance Staff",
        "color": "#7b4ac7"
      },
      {
        "name": "Invoice Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC11c-1",
        "name": "Discard Draft Invoice"
      },
      {
        "id": "UC11c-2",
        "name": "Void Issued Invoice"
      },
      {
        "id": "UC11c-3",
        "name": "Record Void Reason"
      },
      {
        "id": "UC11c-4",
        "name": "Prevent Void PAID Invoice"
      },
      {
        "id": "UC11c-5",
        "name": "Reconcile Invoices"
      }
    ],
    "relations": [
      {
        "actor": "Receptionist",
        "uc": "UC11c-1"
      },
      {
        "actor": "Finance Staff",
        "uc": "UC11c-2"
      },
      {
        "actor": "Invoice Service",
        "uc": "UC11c-3",
        "type": "include"
      },
      {
        "actor": "Invoice Service",
        "uc": "UC11c-4"
      },
      {
        "actor": "Finance Staff",
        "uc": "UC11c-5"
      }
    ],
    "module": "Billing & Settlement",
    "moduleId": "billing",
    "moduleIcon": "🧾"
  },
  "payment_online": {
    "description": "UC12a · Online Payment: Checkout → Gateway → Webhook → Payment Success",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Payment Gateway",
        "color": "#4f5d75"
      },
      {
        "name": "Order Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Webhook Handler",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC12a-1",
        "name": "Checkout",
        "focal": true
      },
      {
        "id": "UC12a-2",
        "name": "Create Payment Intent"
      },
      {
        "id": "UC12a-3",
        "name": "Redirect to Gateway"
      },
      {
        "id": "UC12a-4",
        "name": "Process Payment"
      },
      {
        "id": "UC12a-5",
        "name": "Webhook Callback"
      },
      {
        "id": "UC12a-6",
        "name": "Verify Signature"
      },
      {
        "id": "UC12a-7",
        "name": "Update Order Status"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC12a-1"
      },
      {
        "actor": "Order Service",
        "uc": "UC12a-2",
        "type": "include"
      },
      {
        "actor": "Order Service",
        "uc": "UC12a-3",
        "type": "include"
      },
      {
        "actor": "Payment Gateway",
        "uc": "UC12a-4"
      },
      {
        "actor": "Payment Gateway",
        "uc": "UC12a-5"
      },
      {
        "actor": "Webhook Handler",
        "uc": "UC12a-6",
        "type": "include"
      },
      {
        "actor": "Order Service",
        "uc": "UC12a-7",
        "type": "include"
      }
    ],
    "module": "Payment & Refund",
    "moduleId": "payment",
    "moduleIcon": "💳"
  },
  "online": {
    "description": "UC12a · Online Payment: Checkout → Gateway → Webhook → Payment Success",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Payment Gateway",
        "color": "#4f5d75"
      },
      {
        "name": "Order Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Webhook Handler",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC12a-1",
        "name": "Checkout",
        "focal": true
      },
      {
        "id": "UC12a-2",
        "name": "Create Payment Intent"
      },
      {
        "id": "UC12a-3",
        "name": "Redirect to Gateway"
      },
      {
        "id": "UC12a-4",
        "name": "Process Payment"
      },
      {
        "id": "UC12a-5",
        "name": "Webhook Callback"
      },
      {
        "id": "UC12a-6",
        "name": "Verify Signature"
      },
      {
        "id": "UC12a-7",
        "name": "Update Order Status"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC12a-1"
      },
      {
        "actor": "Order Service",
        "uc": "UC12a-2",
        "type": "include"
      },
      {
        "actor": "Order Service",
        "uc": "UC12a-3",
        "type": "include"
      },
      {
        "actor": "Payment Gateway",
        "uc": "UC12a-4"
      },
      {
        "actor": "Payment Gateway",
        "uc": "UC12a-5"
      },
      {
        "actor": "Webhook Handler",
        "uc": "UC12a-6",
        "type": "include"
      },
      {
        "actor": "Order Service",
        "uc": "UC12a-7",
        "type": "include"
      }
    ],
    "module": "Payment & Refund",
    "moduleId": "payment",
    "moduleIcon": "💳"
  },
  "payment_cash": {
    "description": "UC12b · Cash / POS Payment: Record Cash, POS Transaction",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "Payment Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC12b-1",
        "name": "Record Cash Payment",
        "focal": true
      },
      {
        "id": "UC12b-2",
        "name": "Record POS Payment"
      },
      {
        "id": "UC12b-3",
        "name": "Issue Receipt"
      },
      {
        "id": "UC12b-4",
        "name": "Update Invoice Status"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC12b-1"
      },
      {
        "actor": "Receptionist",
        "uc": "UC12b-1"
      },
      {
        "actor": "Receptionist",
        "uc": "UC12b-2"
      },
      {
        "actor": "Payment Service",
        "uc": "UC12b-3",
        "type": "include"
      },
      {
        "actor": "Payment Service",
        "uc": "UC12b-4",
        "type": "include"
      }
    ],
    "module": "Payment & Refund",
    "moduleId": "payment",
    "moduleIcon": "💳"
  },
  "cash": {
    "description": "UC12b · Cash / POS Payment: Record Cash, POS Transaction",
    "actors": [
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Receptionist",
        "color": "#eb6c36"
      },
      {
        "name": "Payment Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC12b-1",
        "name": "Record Cash Payment",
        "focal": true
      },
      {
        "id": "UC12b-2",
        "name": "Record POS Payment"
      },
      {
        "id": "UC12b-3",
        "name": "Issue Receipt"
      },
      {
        "id": "UC12b-4",
        "name": "Update Invoice Status"
      }
    ],
    "relations": [
      {
        "actor": "Customer",
        "uc": "UC12b-1"
      },
      {
        "actor": "Receptionist",
        "uc": "UC12b-1"
      },
      {
        "actor": "Receptionist",
        "uc": "UC12b-2"
      },
      {
        "actor": "Payment Service",
        "uc": "UC12b-3",
        "type": "include"
      },
      {
        "actor": "Payment Service",
        "uc": "UC12b-4",
        "type": "include"
      }
    ],
    "module": "Payment & Refund",
    "moduleId": "payment",
    "moduleIcon": "💳"
  },
  "payment_refund": {
    "description": "UC12c · Refund Processing: Full Refund, Partial Refund (D-01)",
    "actors": [
      {
        "name": "Finance Staff",
        "color": "#7b4ac7"
      },
      {
        "name": "System",
        "color": "#4f5d75"
      },
      {
        "name": "Payment Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Invoice Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC12c-1",
        "name": "Process Full Refund",
        "focal": true
      },
      {
        "id": "UC12c-2",
        "name": "Process Partial Refund"
      },
      {
        "id": "UC12c-3",
        "name": "Update total_refunded_amount"
      },
      {
        "id": "UC12c-4",
        "name": "Create Refund Record"
      },
      {
        "id": "UC12c-5",
        "name": "Send Refund Notification"
      }
    ],
    "relations": [
      {
        "actor": "Finance Staff",
        "uc": "UC12c-1"
      },
      {
        "actor": "Finance Staff",
        "uc": "UC12c-2"
      },
      {
        "actor": "Invoice Service",
        "uc": "UC12c-3",
        "type": "include"
      },
      {
        "actor": "Payment Service",
        "uc": "UC12c-4",
        "type": "include"
      },
      {
        "actor": "System",
        "uc": "UC12c-5",
        "type": "include"
      }
    ],
    "module": "Payment & Refund",
    "moduleId": "payment",
    "moduleIcon": "💳"
  },
  "refund": {
    "description": "UC12c · Refund Processing: Full Refund, Partial Refund (D-01)",
    "actors": [
      {
        "name": "Finance Staff",
        "color": "#7b4ac7"
      },
      {
        "name": "System",
        "color": "#4f5d75"
      },
      {
        "name": "Payment Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "Invoice Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC12c-1",
        "name": "Process Full Refund",
        "focal": true
      },
      {
        "id": "UC12c-2",
        "name": "Process Partial Refund"
      },
      {
        "id": "UC12c-3",
        "name": "Update total_refunded_amount"
      },
      {
        "id": "UC12c-4",
        "name": "Create Refund Record"
      },
      {
        "id": "UC12c-5",
        "name": "Send Refund Notification"
      }
    ],
    "relations": [
      {
        "actor": "Finance Staff",
        "uc": "UC12c-1"
      },
      {
        "actor": "Finance Staff",
        "uc": "UC12c-2"
      },
      {
        "actor": "Invoice Service",
        "uc": "UC12c-3",
        "type": "include"
      },
      {
        "actor": "Payment Service",
        "uc": "UC12c-4",
        "type": "include"
      },
      {
        "actor": "System",
        "uc": "UC12c-5",
        "type": "include"
      }
    ],
    "module": "Payment & Refund",
    "moduleId": "payment",
    "moduleIcon": "💳"
  },
  "store_store_mgmt": {
    "description": "UC13a · Store Management: DRAFT → ACTIVE ↔ SUSPENDED → DEACTIVATED → ARCHIVED",
    "actors": [
      {
        "name": "Org Admin",
        "color": "#eb6c36"
      },
      {
        "name": "Platform Admin",
        "color": "#7b4ac7"
      },
      {
        "name": "Store Manager",
        "color": "#45a58a"
      },
      {
        "name": "System",
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC13a-1",
        "name": "Create Store",
        "focal": true
      },
      {
        "id": "UC13a-2",
        "name": "Activate Store"
      },
      {
        "id": "UC13a-3",
        "name": "Suspend Store"
      },
      {
        "id": "UC13a-4",
        "name": "Reactivate Store"
      },
      {
        "id": "UC13a-5",
        "name": "Deactivate Store"
      },
      {
        "id": "UC13a-6",
        "name": "Update Store Info"
      }
    ],
    "relations": [
      {
        "actor": "Org Admin",
        "uc": "UC13a-1"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC13a-2"
      },
      {
        "actor": "Org Admin",
        "uc": "UC13a-3"
      },
      {
        "actor": "Org Admin",
        "uc": "UC13a-4"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC13a-5"
      },
      {
        "actor": "Store Manager",
        "uc": "UC13a-6"
      }
    ],
    "module": "Store Lifecycle",
    "moduleId": "store",
    "moduleIcon": "🏪"
  },
  "store-mgmt": {
    "description": "UC13a · Store Management: DRAFT → ACTIVE ↔ SUSPENDED → DEACTIVATED → ARCHIVED",
    "actors": [
      {
        "name": "Org Admin",
        "color": "#eb6c36"
      },
      {
        "name": "Platform Admin",
        "color": "#7b4ac7"
      },
      {
        "name": "Store Manager",
        "color": "#45a58a"
      },
      {
        "name": "System",
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC13a-1",
        "name": "Create Store",
        "focal": true
      },
      {
        "id": "UC13a-2",
        "name": "Activate Store"
      },
      {
        "id": "UC13a-3",
        "name": "Suspend Store"
      },
      {
        "id": "UC13a-4",
        "name": "Reactivate Store"
      },
      {
        "id": "UC13a-5",
        "name": "Deactivate Store"
      },
      {
        "id": "UC13a-6",
        "name": "Update Store Info"
      }
    ],
    "relations": [
      {
        "actor": "Org Admin",
        "uc": "UC13a-1"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC13a-2"
      },
      {
        "actor": "Org Admin",
        "uc": "UC13a-3"
      },
      {
        "actor": "Org Admin",
        "uc": "UC13a-4"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC13a-5"
      },
      {
        "actor": "Store Manager",
        "uc": "UC13a-6"
      }
    ],
    "module": "Store Lifecycle",
    "moduleId": "store",
    "moduleIcon": "🏪"
  },
  "store_store_archive": {
    "description": "UC13b · Store Archive: Check Conditions → Archive Store",
    "actors": [
      {
        "name": "Platform Admin",
        "color": "#7b4ac7"
      },
      {
        "name": "System",
        "color": "#4f5d75"
      },
      {
        "name": "Finance Staff",
        "color": "#eb6c36"
      }
    ],
    "useCases": [
      {
        "id": "UC13b-1",
        "name": "Check Archive Conditions"
      },
      {
        "id": "UC13b-2",
        "name": "Verify Zero Balance"
      },
      {
        "id": "UC13b-3",
        "name": "Archive Store"
      },
      {
        "id": "UC13b-4",
        "name": "Export Historical Data"
      }
    ],
    "relations": [
      {
        "actor": "Platform Admin",
        "uc": "UC13b-1"
      },
      {
        "actor": "System",
        "uc": "UC13b-2",
        "type": "include"
      },
      {
        "actor": "Finance Staff",
        "uc": "UC13b-2"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC13b-3"
      },
      {
        "actor": "System",
        "uc": "UC13b-4",
        "type": "include"
      }
    ],
    "module": "Store Lifecycle",
    "moduleId": "store",
    "moduleIcon": "🏪"
  },
  "store-archive": {
    "description": "UC13b · Store Archive: Check Conditions → Archive Store",
    "actors": [
      {
        "name": "Platform Admin",
        "color": "#7b4ac7"
      },
      {
        "name": "System",
        "color": "#4f5d75"
      },
      {
        "name": "Finance Staff",
        "color": "#eb6c36"
      }
    ],
    "useCases": [
      {
        "id": "UC13b-1",
        "name": "Check Archive Conditions"
      },
      {
        "id": "UC13b-2",
        "name": "Verify Zero Balance"
      },
      {
        "id": "UC13b-3",
        "name": "Archive Store"
      },
      {
        "id": "UC13b-4",
        "name": "Export Historical Data"
      }
    ],
    "relations": [
      {
        "actor": "Platform Admin",
        "uc": "UC13b-1"
      },
      {
        "actor": "System",
        "uc": "UC13b-2",
        "type": "include"
      },
      {
        "actor": "Finance Staff",
        "uc": "UC13b-2"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC13b-3"
      },
      {
        "actor": "System",
        "uc": "UC13b-4",
        "type": "include"
      }
    ],
    "module": "Store Lifecycle",
    "moduleId": "store",
    "moduleIcon": "🏪"
  },
  "caregiver_delegation": {
    "description": "UC14a · Delegation Lifecycle: INVITED → ACTIVE ↔ EXPIRED/REVOKED",
    "actors": [
      {
        "name": "Pet Owner",
        "color": "#2e5aa8"
      },
      {
        "name": "Caregiver",
        "color": "#eb6c36"
      },
      {
        "name": "Delegation Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC14a-1",
        "name": "Invite Caregiver",
        "focal": true
      },
      {
        "id": "UC14a-2",
        "name": "Accept Invitation"
      },
      {
        "id": "UC14a-3",
        "name": "Reject Invitation"
      },
      {
        "id": "UC14a-4",
        "name": "Process Expiry"
      },
      {
        "id": "UC14a-5",
        "name": "Revoke Delegation"
      },
      {
        "id": "UC14a-6",
        "name": "Manage Pets"
      }
    ],
    "relations": [
      {
        "actor": "Pet Owner",
        "uc": "UC14a-1"
      },
      {
        "actor": "Caregiver",
        "uc": "UC14a-2"
      },
      {
        "actor": "Caregiver",
        "uc": "UC14a-3"
      },
      {
        "actor": "Delegation Service",
        "uc": "UC14a-4"
      },
      {
        "actor": "Pet Owner",
        "uc": "UC14a-5"
      },
      {
        "actor": "Caregiver",
        "uc": "UC14a-6"
      }
    ],
    "module": "Caregiver Delegation",
    "moduleId": "caregiver",
    "moduleIcon": "🤝"
  },
  "delegation": {
    "description": "UC14a · Delegation Lifecycle: INVITED → ACTIVE ↔ EXPIRED/REVOKED",
    "actors": [
      {
        "name": "Pet Owner",
        "color": "#2e5aa8"
      },
      {
        "name": "Caregiver",
        "color": "#eb6c36"
      },
      {
        "name": "Delegation Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC14a-1",
        "name": "Invite Caregiver",
        "focal": true
      },
      {
        "id": "UC14a-2",
        "name": "Accept Invitation"
      },
      {
        "id": "UC14a-3",
        "name": "Reject Invitation"
      },
      {
        "id": "UC14a-4",
        "name": "Process Expiry"
      },
      {
        "id": "UC14a-5",
        "name": "Revoke Delegation"
      },
      {
        "id": "UC14a-6",
        "name": "Manage Pets"
      }
    ],
    "relations": [
      {
        "actor": "Pet Owner",
        "uc": "UC14a-1"
      },
      {
        "actor": "Caregiver",
        "uc": "UC14a-2"
      },
      {
        "actor": "Caregiver",
        "uc": "UC14a-3"
      },
      {
        "actor": "Delegation Service",
        "uc": "UC14a-4"
      },
      {
        "actor": "Pet Owner",
        "uc": "UC14a-5"
      },
      {
        "actor": "Caregiver",
        "uc": "UC14a-6"
      }
    ],
    "module": "Caregiver Delegation",
    "moduleId": "caregiver",
    "moduleIcon": "🤝"
  },
  "caregiver_access": {
    "description": "UC14b · Caregiver Access: Limited, Read-only EMR, Schedule Management",
    "actors": [
      {
        "name": "Caregiver",
        "color": "#eb6c36"
      },
      {
        "name": "Delegation Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "EMR Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC14b-1",
        "name": "View Pet Information"
      },
      {
        "id": "UC14b-2",
        "name": "View Medical History"
      },
      {
        "id": "UC14b-3",
        "name": "Book Appointment"
      },
      {
        "id": "UC14b-4",
        "name": "Manage Vaccination Schedule"
      },
      {
        "id": "UC14b-5",
        "name": "Receive Notifications"
      }
    ],
    "relations": [
      {
        "actor": "Caregiver",
        "uc": "UC14b-1"
      },
      {
        "actor": "Delegation Service",
        "uc": "UC14b-2",
        "type": "include"
      },
      {
        "actor": "Caregiver",
        "uc": "UC14b-3"
      },
      {
        "actor": "Caregiver",
        "uc": "UC14b-4"
      },
      {
        "actor": "Delegation Service",
        "uc": "UC14b-5",
        "type": "include"
      }
    ],
    "module": "Caregiver Delegation",
    "moduleId": "caregiver",
    "moduleIcon": "🤝"
  },
  "access": {
    "description": "UC14b · Caregiver Access: Limited, Read-only EMR, Schedule Management",
    "actors": [
      {
        "name": "Caregiver",
        "color": "#eb6c36"
      },
      {
        "name": "Delegation Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "EMR Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC14b-1",
        "name": "View Pet Information"
      },
      {
        "id": "UC14b-2",
        "name": "View Medical History"
      },
      {
        "id": "UC14b-3",
        "name": "Book Appointment"
      },
      {
        "id": "UC14b-4",
        "name": "Manage Vaccination Schedule"
      },
      {
        "id": "UC14b-5",
        "name": "Receive Notifications"
      }
    ],
    "relations": [
      {
        "actor": "Caregiver",
        "uc": "UC14b-1"
      },
      {
        "actor": "Delegation Service",
        "uc": "UC14b-2",
        "type": "include"
      },
      {
        "actor": "Caregiver",
        "uc": "UC14b-3"
      },
      {
        "actor": "Caregiver",
        "uc": "UC14b-4"
      },
      {
        "actor": "Delegation Service",
        "uc": "UC14b-5",
        "type": "include"
      }
    ],
    "module": "Caregiver Delegation",
    "moduleId": "caregiver",
    "moduleIcon": "🤝"
  },
  "workforce_schedule": {
    "description": "UC15a · Schedule Management: Assign, View, Modify schedules",
    "actors": [
      {
        "name": "Store Manager",
        "color": "#7b4ac7"
      },
      {
        "name": "Staff",
        "color": "#eb6c36"
      },
      {
        "name": "Scheduling Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC15a-1",
        "name": "Create Work Schedule",
        "focal": true
      },
      {
        "id": "UC15a-2",
        "name": "Assign Staff to Shift"
      },
      {
        "id": "UC15a-3",
        "name": "View Schedule"
      },
      {
        "id": "UC15a-4",
        "name": "Request Shift Change"
      },
      {
        "id": "UC15a-5",
        "name": "Approve Shift Change"
      }
    ],
    "relations": [
      {
        "actor": "Store Manager",
        "uc": "UC15a-1"
      },
      {
        "actor": "Store Manager",
        "uc": "UC15a-2"
      },
      {
        "actor": "Staff",
        "uc": "UC15a-3"
      },
      {
        "actor": "Staff",
        "uc": "UC15a-4"
      },
      {
        "actor": "Store Manager",
        "uc": "UC15a-5"
      }
    ],
    "module": "Workforce Management",
    "moduleId": "workforce",
    "moduleIcon": "👥"
  },
  "workforce_absence": {
    "description": "UC15b · Staff Absence: Report → Approve → Replace → Recalculate",
    "actors": [
      {
        "name": "Staff",
        "color": "#eb6c36"
      },
      {
        "name": "Store Manager",
        "color": "#7b4ac7"
      },
      {
        "name": "Scheduling Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC15b-1",
        "name": "Report Absence",
        "focal": true
      },
      {
        "id": "UC15b-2",
        "name": "Approve Absence"
      },
      {
        "id": "UC15b-3",
        "name": "Find Shift Replacement"
      },
      {
        "id": "UC15b-4",
        "name": "Recalculate Schedule"
      },
      {
        "id": "UC15b-5",
        "name": "Notify Affected Staff"
      }
    ],
    "relations": [
      {
        "actor": "Staff",
        "uc": "UC15b-1"
      },
      {
        "actor": "Store Manager",
        "uc": "UC15b-2"
      },
      {
        "actor": "Scheduling Service",
        "uc": "UC15b-3",
        "type": "include"
      },
      {
        "actor": "Scheduling Service",
        "uc": "UC15b-4",
        "type": "include"
      },
      {
        "actor": "Scheduling Service",
        "uc": "UC15b-5",
        "type": "include"
      }
    ],
    "module": "Workforce Management",
    "moduleId": "workforce",
    "moduleIcon": "👥"
  },
  "absence": {
    "description": "UC15b · Staff Absence: Report → Approve → Replace → Recalculate",
    "actors": [
      {
        "name": "Staff",
        "color": "#eb6c36"
      },
      {
        "name": "Store Manager",
        "color": "#7b4ac7"
      },
      {
        "name": "Scheduling Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC15b-1",
        "name": "Report Absence",
        "focal": true
      },
      {
        "id": "UC15b-2",
        "name": "Approve Absence"
      },
      {
        "id": "UC15b-3",
        "name": "Find Shift Replacement"
      },
      {
        "id": "UC15b-4",
        "name": "Recalculate Schedule"
      },
      {
        "id": "UC15b-5",
        "name": "Notify Affected Staff"
      }
    ],
    "relations": [
      {
        "actor": "Staff",
        "uc": "UC15b-1"
      },
      {
        "actor": "Store Manager",
        "uc": "UC15b-2"
      },
      {
        "actor": "Scheduling Service",
        "uc": "UC15b-3",
        "type": "include"
      },
      {
        "actor": "Scheduling Service",
        "uc": "UC15b-4",
        "type": "include"
      },
      {
        "actor": "Scheduling Service",
        "uc": "UC15b-5",
        "type": "include"
      }
    ],
    "module": "Workforce Management",
    "moduleId": "workforce",
    "moduleIcon": "👥"
  },
  "iam_permission": {
    "description": "UC16a · Permission Management: CRUD Permissions, Scopes",
    "actors": [
      {
        "name": "Platform Admin",
        "color": "#7b4ac7"
      },
      {
        "name": "IAM Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC16a-1",
        "name": "Create Permission",
        "focal": true
      },
      {
        "id": "UC16a-2",
        "name": "Update Permission"
      },
      {
        "id": "UC16a-3",
        "name": "Deactivate Permission"
      },
      {
        "id": "UC16a-4",
        "name": "Assign to Role"
      },
      {
        "id": "UC16a-5",
        "name": "Check Permission"
      }
    ],
    "relations": [
      {
        "actor": "Platform Admin",
        "uc": "UC16a-1"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC16a-2"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC16a-3"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC16a-4"
      },
      {
        "actor": "IAM Service",
        "uc": "UC16a-5"
      }
    ],
    "module": "IAM & Permissions",
    "moduleId": "iam",
    "moduleIcon": "🛡️"
  },
  "permission": {
    "description": "UC16a · Permission Management: CRUD Permissions, Scopes",
    "actors": [
      {
        "name": "Platform Admin",
        "color": "#7b4ac7"
      },
      {
        "name": "IAM Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC16a-1",
        "name": "Create Permission",
        "focal": true
      },
      {
        "id": "UC16a-2",
        "name": "Update Permission"
      },
      {
        "id": "UC16a-3",
        "name": "Deactivate Permission"
      },
      {
        "id": "UC16a-4",
        "name": "Assign to Role"
      },
      {
        "id": "UC16a-5",
        "name": "Check Permission"
      }
    ],
    "relations": [
      {
        "actor": "Platform Admin",
        "uc": "UC16a-1"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC16a-2"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC16a-3"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC16a-4"
      },
      {
        "actor": "IAM Service",
        "uc": "UC16a-5"
      }
    ],
    "module": "IAM & Permissions",
    "moduleId": "iam",
    "moduleIcon": "🛡️"
  },
  "iam_role": {
    "description": "UC16b · Role Assignment: Create, Assign, Validate",
    "actors": [
      {
        "name": "Platform Admin",
        "color": "#7b4ac7"
      },
      {
        "name": "Org Admin",
        "color": "#eb6c36"
      },
      {
        "name": "IAM Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC16b-1",
        "name": "Create Role"
      },
      {
        "id": "UC16b-2",
        "name": "Assign Permissions"
      },
      {
        "id": "UC16b-3",
        "name": "Assign Role to User"
      },
      {
        "id": "UC16b-4",
        "name": "Validate User Permissions"
      },
      {
        "id": "UC16b-5",
        "name": "Remove Role"
      }
    ],
    "relations": [
      {
        "actor": "Platform Admin",
        "uc": "UC16b-1"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC16b-2"
      },
      {
        "actor": "Org Admin",
        "uc": "UC16b-3"
      },
      {
        "actor": "IAM Service",
        "uc": "UC16b-4",
        "type": "include"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC16b-5"
      }
    ],
    "module": "IAM & Permissions",
    "moduleId": "iam",
    "moduleIcon": "🛡️"
  },
  "role": {
    "description": "UC16b · Role Assignment: Create, Assign, Validate",
    "actors": [
      {
        "name": "Platform Admin",
        "color": "#7b4ac7"
      },
      {
        "name": "Org Admin",
        "color": "#eb6c36"
      },
      {
        "name": "IAM Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC16b-1",
        "name": "Create Role"
      },
      {
        "id": "UC16b-2",
        "name": "Assign Permissions"
      },
      {
        "id": "UC16b-3",
        "name": "Assign Role to User"
      },
      {
        "id": "UC16b-4",
        "name": "Validate User Permissions"
      },
      {
        "id": "UC16b-5",
        "name": "Remove Role"
      }
    ],
    "relations": [
      {
        "actor": "Platform Admin",
        "uc": "UC16b-1"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC16b-2"
      },
      {
        "actor": "Org Admin",
        "uc": "UC16b-3"
      },
      {
        "actor": "IAM Service",
        "uc": "UC16b-4",
        "type": "include"
      },
      {
        "actor": "Platform Admin",
        "uc": "UC16b-5"
      }
    ],
    "module": "IAM & Permissions",
    "moduleId": "iam",
    "moduleIcon": "🛡️"
  },
  "notification_dispatch": {
    "description": "UC17a · Notification Dispatch: Template → Channel → Retry → Log",
    "actors": [
      {
        "name": "System",
        "color": "#4f5d75"
      },
      {
        "name": "Notification Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "User",
        "color": "#2e5aa8"
      }
    ],
    "useCases": [
      {
        "id": "UC17a-1",
        "name": "Trigger Notification",
        "focal": true
      },
      {
        "id": "UC17a-2",
        "name": "Select Template"
      },
      {
        "id": "UC17a-3",
        "name": "Send via Channel"
      },
      {
        "id": "UC17a-4",
        "name": "Retry on Failure"
      },
      {
        "id": "UC17a-5",
        "name": "Log Notification"
      }
    ],
    "relations": [
      {
        "actor": "System",
        "uc": "UC17a-1"
      },
      {
        "actor": "Notification Service",
        "uc": "UC17a-2",
        "type": "include"
      },
      {
        "actor": "Notification Service",
        "uc": "UC17a-3",
        "type": "include"
      },
      {
        "actor": "Notification Service",
        "uc": "UC17a-4",
        "type": "include"
      },
      {
        "actor": "Notification Service",
        "uc": "UC17a-5",
        "type": "include"
      }
    ],
    "module": "Notification System",
    "moduleId": "notification",
    "moduleIcon": "🔔"
  },
  "dispatch": {
    "description": "UC17a · Notification Dispatch: Template → Channel → Retry → Log",
    "actors": [
      {
        "name": "System",
        "color": "#4f5d75"
      },
      {
        "name": "Notification Service",
        "system": true,
        "color": "#4f5d75"
      },
      {
        "name": "User",
        "color": "#2e5aa8"
      }
    ],
    "useCases": [
      {
        "id": "UC17a-1",
        "name": "Trigger Notification",
        "focal": true
      },
      {
        "id": "UC17a-2",
        "name": "Select Template"
      },
      {
        "id": "UC17a-3",
        "name": "Send via Channel"
      },
      {
        "id": "UC17a-4",
        "name": "Retry on Failure"
      },
      {
        "id": "UC17a-5",
        "name": "Log Notification"
      }
    ],
    "relations": [
      {
        "actor": "System",
        "uc": "UC17a-1"
      },
      {
        "actor": "Notification Service",
        "uc": "UC17a-2",
        "type": "include"
      },
      {
        "actor": "Notification Service",
        "uc": "UC17a-3",
        "type": "include"
      },
      {
        "actor": "Notification Service",
        "uc": "UC17a-4",
        "type": "include"
      },
      {
        "actor": "Notification Service",
        "uc": "UC17a-5",
        "type": "include"
      }
    ],
    "module": "Notification System",
    "moduleId": "notification",
    "moduleIcon": "🔔"
  },
  "notification_reminder": {
    "description": "UC17b · Appointment Reminder: Queue Reminder → Send → Track Read",
    "actors": [
      {
        "name": "System",
        "color": "#4f5d75"
      },
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Notification Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC17b-1",
        "name": "Queue Reminder"
      },
      {
        "id": "UC17b-2",
        "name": "Send Appointment Reminder"
      },
      {
        "id": "UC17b-3",
        "name": "Track Read Status"
      },
      {
        "id": "UC17b-4",
        "name": "Escalate if Unread"
      }
    ],
    "relations": [
      {
        "actor": "System",
        "uc": "UC17b-1"
      },
      {
        "actor": "Notification Service",
        "uc": "UC17b-2",
        "type": "include"
      },
      {
        "actor": "Notification Service",
        "uc": "UC17b-3",
        "type": "include"
      },
      {
        "actor": "Notification Service",
        "uc": "UC17b-4"
      },
      {
        "actor": "Customer",
        "uc": "UC17b-3"
      }
    ],
    "module": "Notification System",
    "moduleId": "notification",
    "moduleIcon": "🔔"
  },
  "reminder": {
    "description": "UC17b · Appointment Reminder: Queue Reminder → Send → Track Read",
    "actors": [
      {
        "name": "System",
        "color": "#4f5d75"
      },
      {
        "name": "Customer",
        "color": "#2e5aa8"
      },
      {
        "name": "Notification Service",
        "system": true,
        "color": "#4f5d75"
      }
    ],
    "useCases": [
      {
        "id": "UC17b-1",
        "name": "Queue Reminder"
      },
      {
        "id": "UC17b-2",
        "name": "Send Appointment Reminder"
      },
      {
        "id": "UC17b-3",
        "name": "Track Read Status"
      },
      {
        "id": "UC17b-4",
        "name": "Escalate if Unread"
      }
    ],
    "relations": [
      {
        "actor": "System",
        "uc": "UC17b-1"
      },
      {
        "actor": "Notification Service",
        "uc": "UC17b-2",
        "type": "include"
      },
      {
        "actor": "Notification Service",
        "uc": "UC17b-3",
        "type": "include"
      },
      {
        "actor": "Notification Service",
        "uc": "UC17b-4"
      },
      {
        "actor": "Customer",
        "uc": "UC17b-3"
      }
    ],
    "module": "Notification System",
    "moduleId": "notification",
    "moduleIcon": "🔔"
  }
,
  "catalog_master_product": {
  "description": "UC5a · Master Product & Catalog: Global SKU, Organization Grant, Approval",
  "actors": [
    {
      "name": "Platform Admin",
      "color": "#7b4ac7"
    },
    {
      "name": "Organization Admin",
      "color": "#2e5aa8"
    },
    {
      "name": "Store Manager",
      "color": "#eb6c36"
    },
    {
      "name": "Customer",
      "color": "#2e5aa8"
    }
  ],
  "useCases": [
    {
      "id": "UC5a-1",
      "name": "Manage Master Catalog",
      "focal": true,
      "extensionPoints": [
        "phê duyệt Catalog mẫu / grant tenant"
      ]
    },
    {
      "id": "UC5a-2",
      "name": "Grant Catalog To Org"
    },
    {
      "id": "UC5a-3",
      "name": "Configure Store Service"
    },
    {
      "id": "UC5a-4",
      "name": "Configure Store Product Price"
    },
    {
      "id": "UC5a-5",
      "name": "View Product & Service Catalog"
    }
  ],
  "relations": [
    {
      "actor": "Platform Admin",
      "uc": "UC5a-1"
    },
    {
      "actor": "Platform Admin",
      "uc": "UC5a-2"
    },
    {
      "actor": "Organization Admin",
      "uc": "UC5a-2"
    },
    {
      "actor": "Store Manager",
      "uc": "UC5a-3"
    },
    {
      "actor": "Store Manager",
      "uc": "UC5a-4"
    },
    {
      "actor": "Customer",
      "uc": "UC5a-5"
    }
  ]
},
  "promotion_voucher": {
  "description": "UC18 · Promotion & Voucher: Campaigns, Store Rules, Discount Validation",
  "actors": [
    {
      "name": "Organization Admin",
      "color": "#7b4ac7"
    },
    {
      "name": "Store Manager",
      "color": "#eb6c36"
    },
    {
      "name": "Customer",
      "color": "#2e5aa8"
    },
    {
      "name": "System",
      "color": "#4f5d75"
    }
  ],
  "useCases": [
    {
      "id": "UC18-1",
      "name": "Create Promotion Campaign",
      "focal": true,
      "extensionPoints": [
        "cấu hình điều kiện áp dụng / quota"
      ]
    },
    {
      "id": "UC18-2",
      "name": "Configure Store Promotion"
    },
    {
      "id": "UC18-3",
      "name": "Create Voucher Code"
    },
    {
      "id": "UC18-4",
      "name": "Apply Voucher At Checkout"
    },
    {
      "id": "UC18-5",
      "name": "Validate Voucher Conditions"
    },
    {
      "id": "UC18-6",
      "name": "Track Voucher Redemption"
    }
  ],
  "relations": [
    {
      "actor": "Organization Admin",
      "uc": "UC18-1"
    },
    {
      "actor": "Store Manager",
      "uc": "UC18-2"
    },
    {
      "actor": "Organization Admin",
      "uc": "UC18-3"
    },
    {
      "actor": "Customer",
      "uc": "UC18-4"
    },
    {
      "actor": "System",
      "uc": "UC18-5",
      "type": "include"
    },
    {
      "actor": "System",
      "uc": "UC18-6",
      "type": "include"
    }
  ]
},
  "membership_loyalty": {
  "description": "UC19 · Membership & Loyalty: Tier Progression, Points Earning & Redemption",
  "actors": [
    {
      "name": "Customer",
      "color": "#2e5aa8"
    },
    {
      "name": "Receptionist",
      "color": "#eb6c36"
    },
    {
      "name": "Store Manager",
      "color": "#7b4ac7"
    },
    {
      "name": "System",
      "color": "#4f5d75"
    }
  ],
  "useCases": [
    {
      "id": "UC19-1",
      "name": "Register Membership Tier",
      "focal": true,
      "extensionPoints": [
        "nâng hạng hội viên / gia hạn kỳ hạn"
      ]
    },
    {
      "id": "UC19-2",
      "name": "View Loyalty Balance"
    },
    {
      "id": "UC19-3",
      "name": "Redeem Points For Reward"
    },
    {
      "id": "UC19-4",
      "name": "Accumulate Points On Payment"
    },
    {
      "id": "UC19-5",
      "name": "Adjust Points Manually"
    },
    {
      "id": "UC19-6",
      "name": "Process Tier Expiry"
    }
  ],
  "relations": [
    {
      "actor": "Customer",
      "uc": "UC19-1"
    },
    {
      "actor": "Customer",
      "uc": "UC19-2"
    },
    {
      "actor": "Customer",
      "uc": "UC19-3"
    },
    {
      "actor": "Receptionist",
      "uc": "UC19-3"
    },
    {
      "actor": "Store Manager",
      "uc": "UC19-5"
    },
    {
      "actor": "System",
      "uc": "UC19-4",
      "type": "include"
    },
    {
      "actor": "System",
      "uc": "UC19-6",
      "type": "include"
    }
  ]
},
  "package_management": {
  "description": "UC20 · Service Packages: Prepaid Bundles, Usage Deduction & Refund Policy",
  "actors": [
    {
      "name": "Customer",
      "color": "#2e5aa8"
    },
    {
      "name": "Receptionist",
      "color": "#eb6c36"
    },
    {
      "name": "Store Manager",
      "color": "#7b4ac7"
    },
    {
      "name": "System",
      "color": "#4f5d75"
    }
  ],
  "useCases": [
    {
      "id": "UC20-1",
      "name": "Purchase Service Package",
      "focal": true,
      "extensionPoints": [
        "gói spa 10 buổi / combo vaccine"
      ]
    },
    {
      "id": "UC20-2",
      "name": "Activate Package Upon Check-in"
    },
    {
      "id": "UC20-3",
      "name": "Deduct Package Usage Session"
    },
    {
      "id": "UC20-4",
      "name": "Cancel Package With Refund"
    },
    {
      "id": "UC20-5",
      "name": "Track Remaining Sessions"
    },
    {
      "id": "UC20-6",
      "name": "Process Package Expiry"
    }
  ],
  "relations": [
    {
      "actor": "Customer",
      "uc": "UC20-1"
    },
    {
      "actor": "Customer",
      "uc": "UC20-5"
    },
    {
      "actor": "Receptionist",
      "uc": "UC20-2"
    },
    {
      "actor": "Receptionist",
      "uc": "UC20-3"
    },
    {
      "actor": "Store Manager",
      "uc": "UC20-4"
    },
    {
      "actor": "System",
      "uc": "UC20-6",
      "type": "include"
    }
  ]
},
  "incident_management": {
  "description": "UC21 · Incident Management: Clinical & Grooming Accidents, Escalation & Remedies",
  "actors": [
    {
      "name": "Veterinarian",
      "color": "#2e5aa8"
    },
    {
      "name": "Groomer",
      "color": "#eb6c36"
    },
    {
      "name": "Store Manager",
      "color": "#7b4ac7"
    },
    {
      "name": "System",
      "color": "#4f5d75"
    }
  ],
  "useCases": [
    {
      "id": "UC21-1",
      "name": "Record Service Incident",
      "focal": true,
      "extensionPoints": [
        "tai nạn spa / phản ứng sốc thuốc"
      ]
    },
    {
      "id": "UC21-2",
      "name": "Classify Severity Level"
    },
    {
      "id": "UC21-3",
      "name": "Investigate Incident Cause"
    },
    {
      "id": "UC21-4",
      "name": "Escalate Critical Incident"
    },
    {
      "id": "UC21-5",
      "name": "Execute Compensation & Remedy"
    },
    {
      "id": "UC21-6",
      "name": "Send Incident Notification"
    }
  ],
  "relations": [
    {
      "actor": "Veterinarian",
      "uc": "UC21-1"
    },
    {
      "actor": "Groomer",
      "uc": "UC21-1"
    },
    {
      "actor": "Store Manager",
      "uc": "UC21-2"
    },
    {
      "actor": "Store Manager",
      "uc": "UC21-3"
    },
    {
      "actor": "Store Manager",
      "uc": "UC21-4"
    },
    {
      "actor": "Store Manager",
      "uc": "UC21-5"
    },
    {
      "actor": "System",
      "uc": "UC21-6",
      "type": "include"
    }
  ]
},
  "consent_privacy": {
  "description": "UC22 · Consent & Privacy: Cross-Store EMR Consent, Emergency Override & GDPR",
  "actors": [
    {
      "name": "Customer",
      "color": "#2e5aa8"
    },
    {
      "name": "Veterinarian",
      "color": "#7b4ac7"
    },
    {
      "name": "Organization Admin",
      "color": "#eb6c36"
    },
    {
      "name": "System",
      "color": "#4f5d75"
    }
  ],
  "useCases": [
    {
      "id": "UC22-1",
      "name": "Grant Medical Consent",
      "focal": true,
      "extensionPoints": [
        "ký cam kết phẫu thuật / gây mê"
      ]
    },
    {
      "id": "UC22-2",
      "name": "Verify Cross-Store Consent OTP"
    },
    {
      "id": "UC22-3",
      "name": "Emergency Override Access"
    },
    {
      "id": "UC22-4",
      "name": "Revoke Medical Consent"
    },
    {
      "id": "UC22-5",
      "name": "Request Data Export / Deletion"
    },
    {
      "id": "UC22-6",
      "name": "Process 24h Consent Expiry"
    }
  ],
  "relations": [
    {
      "actor": "Customer",
      "uc": "UC22-1"
    },
    {
      "actor": "Customer",
      "uc": "UC22-2"
    },
    {
      "actor": "Customer",
      "uc": "UC22-4"
    },
    {
      "actor": "Customer",
      "uc": "UC22-5"
    },
    {
      "actor": "Veterinarian",
      "uc": "UC22-2"
    },
    {
      "actor": "Veterinarian",
      "uc": "UC22-3"
    },
    {
      "actor": "Organization Admin",
      "uc": "UC22-5"
    },
    {
      "actor": "System",
      "uc": "UC22-6",
      "type": "include"
    }
  ]
},
  "reporting_analytics": {
  "description": "UC24 · Reporting & Analytics: Revenue, Capacity, Discrepancies & Doctor KPIs",
  "actors": [
    {
      "name": "Store Manager",
      "color": "#eb6c36"
    },
    {
      "name": "Organization Admin",
      "color": "#7b4ac7"
    },
    {
      "name": "Finance Staff",
      "color": "#2e5aa8"
    },
    {
      "name": "Platform Admin",
      "color": "#4f5d75"
    }
  ],
  "useCases": [
    {
      "id": "UC24-1",
      "name": "View Store Revenue Report",
      "focal": true,
      "extensionPoints": [
        "lọc theo kênh POS / Online / Dịch vụ"
      ]
    },
    {
      "id": "UC24-2",
      "name": "View Appointment & Service KPIs"
    },
    {
      "id": "UC24-3",
      "name": "View Inventory Discrepancy Report"
    },
    {
      "id": "UC24-4",
      "name": "View Staff Commission Report"
    },
    {
      "id": "UC24-5",
      "name": "Compare Cross-Store Performance"
    },
    {
      "id": "UC24-6",
      "name": "Reconcile Financial Settlement"
    }
  ],
  "relations": [
    {
      "actor": "Store Manager",
      "uc": "UC24-1"
    },
    {
      "actor": "Store Manager",
      "uc": "UC24-2"
    },
    {
      "actor": "Store Manager",
      "uc": "UC24-3"
    },
    {
      "actor": "Store Manager",
      "uc": "UC24-4"
    },
    {
      "actor": "Organization Admin",
      "uc": "UC24-5"
    },
    {
      "actor": "Finance Staff",
      "uc": "UC24-6"
    },
    {
      "actor": "Platform Admin",
      "uc": "UC24-5"
    }
  ]
},
  "audit_management": {
  "description": "UC25 · Audit Management: Immutable Logs, EMR Access Tracking & IAM Audits",
  "actors": [
    {
      "name": "Platform Admin",
      "color": "#7b4ac7"
    },
    {
      "name": "Organization Admin",
      "color": "#2e5aa8"
    },
    {
      "name": "Store Manager",
      "color": "#eb6c36"
    },
    {
      "name": "System",
      "color": "#4f5d75"
    }
  ],
  "useCases": [
    {
      "id": "UC25-1",
      "name": "Record Immutable Audit Log"
    },
    {
      "id": "UC25-2",
      "name": "View Platform Audit Trail",
      "focal": true,
      "extensionPoints": [
        "truy vết can thiệp DB / IAM / Tài chính"
      ]
    },
    {
      "id": "UC25-3",
      "name": "Track Permission Changes"
    },
    {
      "id": "UC25-4",
      "name": "Track Medical EMR Access"
    },
    {
      "id": "UC25-5",
      "name": "Track Payment & Refund Audit"
    },
    {
      "id": "UC25-6",
      "name": "Track Inventory Movement Audit"
    }
  ],
  "relations": [
    {
      "actor": "Platform Admin",
      "uc": "UC25-2"
    },
    {
      "actor": "Platform Admin",
      "uc": "UC25-3"
    },
    {
      "actor": "Organization Admin",
      "uc": "UC25-3"
    },
    {
      "actor": "Organization Admin",
      "uc": "UC25-4"
    },
    {
      "actor": "Store Manager",
      "uc": "UC25-6"
    },
    {
      "actor": "System",
      "uc": "UC25-1",
      "type": "include"
    }
  ]
}
};
