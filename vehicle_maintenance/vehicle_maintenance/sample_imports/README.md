# Sample Imports (CSV/XLSX mass upload)

Ready-to-use templates for Frappe's **Data Import** tool (Desk → search "Data Import" →
New → pick the DocType → upload the CSV → Map → Start Import). All masters are import-enabled.

## Files
- `part_group.csv` — 15 commercial-EV-bus part groups
- `part.csv` — 30 starter parts (links to the part groups above)

> A starter catalog of these same Part Groups + Parts is also auto-seeded on deploy
> (idempotent — skips anything that already exists), so you'll see data immediately.
> Use these CSVs as the template for loading your **real** catalog.

## Dependency-safe import order
Link fields must resolve, so import parents first:

```
OEM → Part Group → Depot → Customer → Subsystem → Telemetry Parameter
     → Vehicle (needs OEM, Depot, Customer)
     → Part (needs Part Group)
     → Complaint Catalog / Fault Code / Observation Template (need Subsystem)
     → Telemetry Code (needs Telemetry Parameter)
     → Service Contract (needs Customer)
```

## Tips
- Keep the header row exactly as the doctype fieldnames (these templates already do).
- `is_active` / check fields: use `1` / `0`.
- Link columns (e.g. Part.part_group) must match an existing record's name.
- For images, import the row first, then attach the photo (or use the app's stamped camera).
