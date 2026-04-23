# PENDING WORK — Implementation Queue
> Last Updated: 2026-04-17

## Immediate Next: Batch 1 — Master DocTypes & Fixtures

### Files to Create (in order):
1. `fleet_service/doctype/oem/oem.json` + `oem.py`
2. `fleet_service/doctype/bus_system/bus_system.json` + `bus_system.py`
3. `fleet_service/doctype/concern_code/concern_code.json`
4. `fleet_service/doctype/labor_code/labor_code.json`
5. `fixtures/bus_system.json` (62 entries: 17 PMS + 45 Repair)
6. `fixtures/oem.json` (NaArNi, Azad, BharatBenz)

### Then Batch 2: PMS Checksheet DocTypes
7. `fleet_service/doctype/pms_checksheet_item/pms_checksheet_item.json`
8. `fleet_service/doctype/pms_checksheet/pms_checksheet.json` + `.py`
9. Fixtures for 20K (84 items), 40K (100+ items), 80K (110+ items)

### Full 16-Batch sequence in IMPLEMENTATION_PLAN.md Section 16

## Total Files to Create/Modify: ~91
## Estimated Implementation Time: 8 weeks (Phase 1)

## Phase 2 Items (Out of Scope for Now):
- AI-based damage detection
- Fault code LLM suggestions
- OTA software updates
- WhatsApp job card creation
- Exploded diagram interactive parts selection
- Warranty claim automation
- 3rd party vendor management
- DIY repair guides
- CAN bus data integration
