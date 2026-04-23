"""Subsystem master DocType.

Canonical list of vehicle subsystems (Brakes, HVAC, Powertrain, etc.) referenced
by Job Cards as a multi-select. Introduced in Milestone 1 of the PRD alignment
effort to replace the free-text `subsystem_affected` field.
"""

from frappe.model.document import Document


class Subsystem(Document):
    pass
