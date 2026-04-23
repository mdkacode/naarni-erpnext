# DATA MODEL QUICK REFERENCE
> Last Updated: 2026-04-17

## Existing DocTypes (10)
| DocType | Naming | Key Fields |
|---------|--------|------------|
| Job Card | JC-.YYYY.-.##### (TO CHANGE) | job_card_type, vehicle, customer, depot, workflow_state, pre/post_pms_score |
| Job Card Repair Item | (child) | part_group, activity_type, component_status, qty, rate, estimated/actual_amount |
| Job Card Maintenance Item | (child) | maintenance_type, action, qty, unit |
| Vehicle | registration_number | make_model, vin, customer, fuel_type |
| Customer | customer_name | customer_type, mobile_no, email_id |
| Depot | depot_name | city, state, address |
| Service Contract | contract_name | customer, start/end_date, pms/repair/breakdown/software_tat_hours |
| Part Group | part_group_name | bus_system, description |
| Service Estimate | SE-.YYYY.-.##### | job_card, customer, status, total_amount |
| Service Estimate Item | (child) | description, item_type, qty, rate, amount |

## New DocTypes to Create (17)
| DocType | Type | Purpose |
|---------|------|---------|
| OEM | Master | Azad, NaArNi, BharatBenz |
| Bus System | Master | 17 PMS categories + 45 repair groups |
| Concern Code | Master | GC codes from service history |
| Labor Code | Master | Labor operation codes |
| PMS Checksheet | Master | 20K/40K/80K sheet definitions |
| PMS Checksheet Item | Child | Individual check parameters |
| PMS Inspection Result | Document | Per-job-card inspection with scores |
| PMS Inspection Result Item | Child | Individual item results |
| PMS Category Score | Child | Category-level score rollup |
| Software Update Item | Child (JC) | Component version tracking |
| Post Update Validation | Child (JC) | Post-SW validation checks |
| VOC Entry | Child (JC) | Voice of Customer at subsystem level |
| Force Close Log | Child (JC) | Severity, authority, follow-up |
| Pending PMS Item | Child | Items deferred to next PMS |
| Labour Job Entry | Child | Labour activities performed |
| Inventory Request | Document | Parts request with full lifecycle |
| Inventory Request Item | Child | Individual part line items |

## Job Card Field Additions: ~50+ new fields
See IMPLEMENTATION_PLAN.md Section 3.1 for complete list.
