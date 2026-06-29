"""Job Card DocType Controller.

Per PRD: Manages lifecycle, SLA, health score, QR code, and audit trail.
"""

import io
import json as _json

import frappe
from frappe import _
from frappe.model.document import Document
from frappe.model.naming import make_autoname
from frappe.utils import add_to_date, get_datetime, now_datetime

from vehicle_maintenance.fleet_service import notifications

# ── SLA targets per PRD TAT table (hours) ──
SLA_TARGETS_BY_TYPE: dict[str, float] = {
	"PMS + Repair": 4.0,
	"Only Repair": 4.0,  # Regular sub-type
	"Only Repair Accidental Major": 24.0,
	"Software Update": 3.0,
	"Breakdown": 0.5,  # Remote-resolution phase
	"Breakdown On-site": 4.0,  # From SE arrival to handover (PRD "XX hrs")
}

INVENTORY_APPROVAL_THRESHOLD = 1000.0

# ── Force-close severity → authority role mapping (PRD p.6) ──
FORCE_CLOSE_AUTHORITY: dict[str, list[str]] = {
	"Minor": ["Service Engineer", "Depot Manager", "Aftersales Eng", "N. Maintenance Head"],
	"Major": ["Depot Manager", "Aftersales Eng", "N. Maintenance Head"],
	"Critical": ["N. Maintenance Head"],
}

# Header fields Technicians may NOT edit when state is WIP (PRD p.3)
TECH_LOCKED_HEADER_FIELDS: tuple[str, ...] = (
	"job_card_type",
	"repair_subtype",
	"vehicle",
	"customer",
	"depot",
	"priority",
	"service_contract",
	"assigned_service_engineer",
	"assigned_technician",
)

# Job Card Type → Service Type mapping (service_type is a derived display field)
SERVICE_TYPE_BY_JOB_CARD_TYPE: dict[str, str] = {
	"PMS + Repair": "Scheduled Maintenance",
	"Only Repair": "Other",
	"Software Update": "Software Update",
	"Breakdown": "Breakdown Repair",
}

# ── Workflow transitions ──
# Regular closure path: WIP/Parts Fitted -> Closure from Technician -> Verification Pending -> Closed
# Reopen path: Closed -> Reopened -> WIP (allowed for Customer & Central Ops)
VALID_TRANSITIONS: dict[tuple[str, str], list[str]] = {
	("Open", "WIP"): ["Depot Manager", "Service Engineer"],
	("WIP", "Awaiting Customer Approval"): ["Service Engineer"],
	("Awaiting Customer Approval", "WIP"): ["Service Engineer"],
	("Awaiting Customer Approval", "Awaiting Parts"): ["Service Engineer"],
	("WIP", "Awaiting Parts"): ["Service Engineer", "Technician"],
	("Awaiting Parts", "Parts Fitted"): ["Technician", "Service Engineer"],
	("Parts Fitted", "WIP"): ["Technician", "Service Engineer"],
	("WIP", "Closure from Technician"): ["Technician"],
	("Parts Fitted", "Closure from Technician"): ["Technician"],
	("Closure from Technician", "Verification Pending"): ["Technician", "Service Engineer"],
	("Closure from Technician", "WIP"): ["Technician", "Service Engineer"],
	("Verification Pending", "WIP"): ["Service Engineer"],
	("Verification Pending", "Closed"): ["Service Engineer", "Aftersales Eng"],
	# Reopen loop (PRD p.3): Customer & Central Ops can reopen closed cards.
	("Closed", "Reopened"): ["Customer", "Central Ops"],
	("Reopened", "WIP"): ["Service Engineer", "Depot Manager"],
}

STATE_TIMESTAMP_MAP: dict[str, str] = {
	"Open": "opened_at",
	"WIP": "work_started_at",
	"Closure from Technician": "completed_at",
	"Verification Pending": "completed_at",
	"Closed": "closed_at",
}

# ── Health Score values per PRD ──
HEALTH_SCORE_MAP = {
	"Good": 10,
	"Repair/Replace Recommended": 5,
	"Repair/Replace Immediately": 0,
}


class JobCard(Document):
	"""Controller for Job Card DocType."""

	def before_insert(self) -> None:
		# service_type is reqd+read_only; derive it from job_card_type
		# before the framework's mandatory-field validator runs.
		self._auto_set_service_type()
		self._autofill_last_pms_info()

	def autoname(self) -> None:
		"""Name: [CustomerCode]-[LocationCode]-YYYY-##### (per PRD).

		Customer and Depot Link fields are declared mandatory (`reqd: 1`) in the
		DocType JSON, so Frappe's validator rejects the insert before reaching
		here if either is missing. Linked-doc *codes* fall back to safe defaults
		so a misconfigured master never blocks job card creation.
		"""
		if not self.customer:
			frappe.throw(_("Customer is mandatory before inserting a Job Card."))
		if not self.depot:
			frappe.throw(_("Depot is mandatory before inserting a Job Card."))

		customer_code = frappe.db.get_value("Customer", self.customer, "customer_code") or "CUST"
		location_code = frappe.db.get_value("Depot", self.depot, "location_code") or "DEPOT"

		# Normalize: uppercase, strip whitespace, collapse separators that would
		# break the `-` delimited name pattern.
		customer_code = str(customer_code).strip().upper().replace(" ", "").replace("-", "")
		location_code = str(location_code).strip().upper().replace(" ", "").replace("-", "")

		prefix = f"{customer_code}-{location_code}-"
		self.name = make_autoname(prefix + ".YYYY.-.#####")

	def before_save(self) -> None:
		self._auto_set_service_type()
		self._auto_fetch_oem()
		self._auto_select_check_sheet()
		self._compute_sla()
		self._check_sla_breach()
		self._compute_costs()
		self._compute_breakdown_metrics()
		self._compute_approval_threshold()
		self._compute_health_scores()
		self._init_remote_resolution_timer()
		self._stamp_rca_received()

	def _auto_set_service_type(self) -> None:
		"""service_type is a read-only derived display field."""
		if self.job_card_type:
			self.service_type = SERVICE_TYPE_BY_JOB_CARD_TYPE.get(self.job_card_type, "Other")

	def _autofill_last_pms_info(self) -> None:
		"""For a new Breakdown card, prefill Last PMS fields from this vehicle's
		most recent closed PMS + Repair job card (PRD p.15).

		Idempotent — only fills when values are currently blank so manual
		overrides (rare) aren't clobbered.
		"""
		if self.job_card_type != "Breakdown" or not self.vehicle:
			return

		if any(
			[
				self.last_pms_date,
				self.last_pms_odometer,
				self.last_service_tolerance_level,
				self.last_serviced_by,
			]
		):
			return

		prior = frappe.get_all(
			"Job Card",
			filters={
				"vehicle": self.vehicle,
				"job_card_type": "PMS + Repair",
				"workflow_state": "Closed",
				"force_closed": 0,
			},
			fields=[
				"name",
				"closed_at",
				"job_card_date",
				"odometer_reading",
				"pms_tolerance_level",
				"assigned_service_engineer",
				"assigned_technician",
			],
			order_by="closed_at desc",
			limit_page_length=1,
		)
		if not prior:
			return

		row = prior[0]
		self.last_pms_date = row.get("closed_at") or row.get("job_card_date")
		self.last_pms_odometer = row.get("odometer_reading")
		self.last_service_tolerance_level = row.get("pms_tolerance_level")

		names = [row.get("assigned_service_engineer"), row.get("assigned_technician")]
		names = [n for n in names if n]
		if names:
			self.last_serviced_by = " / ".join(names)

	def _auto_fetch_oem(self) -> None:
		"""Populate `oem` from the Vehicle master.

		`fetch_from` on a read-only Link is unreliable during server-side inserts,
		so we do it explicitly. Always refreshes when vehicle is set, even if the
		current `oem` value is stale from a prior vehicle.
		"""
		if not self.vehicle:
			return
		vehicle_oem = frappe.db.get_value("Vehicle", self.vehicle, "oem")
		if vehicle_oem:
			self.oem = vehicle_oem

	def _auto_select_check_sheet(self) -> None:
		"""Auto-select the PMS inspection check sheet from odometer.

		PRD thresholds: Sheet A (≤20K), Sheet B (≤40K), Sheet C (≤80K), Sheet D (>80K).
		"""
		if self.job_card_type != "PMS + Repair" or not self.odometer_reading:
			return
		odo = int(self.odometer_reading or 0)
		if odo <= 20_000:
			self.check_sheet = "Sheet A (0-20,000 km)"
		elif odo <= 40_000:
			self.check_sheet = "Sheet B (20,001-40,000 km)"
		elif odo <= 80_000:
			self.check_sheet = "Sheet C (40,001-80,000 km)"
		else:
			self.check_sheet = "Sheet D (80,001+ km)"

	def _stamp_rca_received(self) -> None:
		"""Stamp `rca_received_at` the first time `rca_notes` is populated
		(PRD p.16 — DM shares RCA with customer once Aftersales Eng fills it).
		"""
		if self.job_card_type != "Breakdown":
			return
		if not (self.rca_notes or "").strip():
			return
		if self.rca_received_at:
			return
		self.rca_received_at = now_datetime()

	def _init_remote_resolution_timer(self) -> None:
		"""Start the 30-min remote-resolution SLA timer for breakdowns.

		On insert of a Breakdown card, stamp `remote_resolution_started_at` so
		the scheduler task can detect the 30-min threshold and escalate.
		"""
		if self.job_card_type != "Breakdown":
			return
		if not self.remote_resolution_started_at:
			self.remote_resolution_started_at = now_datetime()
		if not self.remote_resolution_status:
			self.remote_resolution_status = "In Progress"

	def validate(self) -> None:
		# Stash pre-save workflow state so `on_update` can route state-transition
		# notifications without re-reading from DB (which will already reflect
		# the new value by the time on_update runs).
		self._pre_save_workflow_state = None if self.is_new() else self.get_db_value("workflow_state")
		self._enforce_closed_state_lock()
		self._enforce_in_progress_edit_lock()
		self._apply_breakdown_fast_path()
		self._reset_sla_flags_on_reopen()
		self._stamp_customer_approval_request()
		self._validate_odometer()
		self._validate_workflow_transition()
		self._validate_force_close()

	def on_trash(self) -> None:
		"""Hard delete is forbidden. Use Force Close / Void instead."""
		raise frappe.ValidationError(_("Job Cards cannot be deleted, only Voided (use Force Close)."))

	def after_insert(self) -> None:
		self._generate_qr_code()
		try:
			notifications.notify_job_card_created(self)
		except Exception:
			frappe.log_error(
				title=f"notify_job_card_created failed for {self.name}",
				message=frappe.get_traceback(),
			)

	def on_update(self) -> None:
		self._stamp_timestamps()
		old_state = getattr(self, "_pre_save_workflow_state", None)
		try:
			notifications.notify_state_transition(self, old_state)
		except Exception:
			frappe.log_error(
				title=f"notify_state_transition failed for {self.name}",
				message=frappe.get_traceback(),
			)
		try:
			self._generate_health_card_if_closed(old_state)
		except Exception:
			frappe.log_error(
				title=f"health card generation failed for {self.name}",
				message=frappe.get_traceback(),
			)
		try:
			self._notify_central_ops_on_closed_edit(old_state)
		except Exception:
			frappe.log_error(
				title=f"notify_closed_state_edit failed for {self.name}",
				message=frappe.get_traceback(),
			)
		try:
			self._create_critical_followup_if_needed()
		except Exception:
			frappe.log_error(
				title=f"create_critical_followup failed for {self.name}",
				message=frappe.get_traceback(),
			)
		try:
			self._sync_closure_record(old_state)
		except Exception:
			frappe.log_error(
				title=f"closure record sync failed for {self.name}",
				message=frappe.get_traceback(),
			)
		try:
			self._broadcast_fleet_risk_if_needed(old_state)
		except Exception:
			frappe.log_error(
				title=f"fleet broadcast failed for {self.name}",
				message=frappe.get_traceback(),
			)

	def _broadcast_fleet_risk_if_needed(self, old_state: str | None) -> None:
		"""Phase-2 PRD (Breakdown item 8): when a Breakdown closes with
		`occurrence_risk = High`, ping all Depot Managers so they can check
		similar vehicles at other depots. Fires once per JC, gated by a flag.
		"""
		if self.job_card_type != "Breakdown":
			return
		if self.workflow_state != "Closed" or old_state == "Closed":
			return  # Only on the entering-Closed transition
		if self.occurrence_risk != "High":
			return
		if getattr(self, "fleet_broadcast_sent", 0):
			return
		notifications.notify_depot_broadcast(self)
		frappe.db.set_value(
			"Job Card",
			self.name,
			"fleet_broadcast_sent",
			1,
			update_modified=False,
		)

	def _sync_closure_record(self, old_state: str | None) -> None:
		"""Phase 2: capture a Closure Record on entering Closed, and stamp
		reopen_* on the latest open record when leaving Closed (PRD p.3 audit
		rule: "Previous closure record retained").
		"""
		entering_closed = old_state != "Closed" and self.workflow_state == "Closed"
		leaving_closed = old_state == "Closed" and self.workflow_state != "Closed"

		if entering_closed:
			from vehicle_maintenance.fleet_service.doctype.job_card_closure_record import (
				job_card_closure_record as cr_module,
			)

			cr_module.create_snapshot(self)
		elif leaving_closed:
			from vehicle_maintenance.fleet_service.doctype.job_card_closure_record import (
				job_card_closure_record as cr_module,
			)

			# Surface the reopen reason the user typed into closed_edit_reason
			# (prefixed with "Reopen: " by the reopen API).
			reason = (self.closed_edit_reason or "").strip()
			if reason.lower().startswith("reopen:"):
				reason = reason[len("reopen:") :].strip()
			cr_module.stamp_reopen(self.name, reason)

	def _create_critical_followup_if_needed(self) -> str | None:
		"""PRD p.6 Severity Matrix: Critical force-close → auto-create a new
		Job Card addressed to the Aftersales Engineer within 24 hours.

		Idempotent: only creates one follow-up per source JC. Safe to call
		from a scheduler task — double-check via `followup_job_card` link.
		"""
		if not (self.force_closed and self.force_close_severity == "Critical"):
			return None
		# Read idempotency key from DB rather than self — during consecutive
		# saves the in-memory value may be stale even after a reload() because
		# `frappe.db.set_value` bypasses the ORM snapshot.
		existing = frappe.db.get_value("Job Card", self.name, "followup_job_card")
		if existing:
			return existing
		# Also cover the case where the follow-up row exists but the link
		# didn't persist (crash-midway recovery).
		prior = frappe.db.exists("Job Card", {"source_force_close_job_card": self.name})
		if prior:
			frappe.db.set_value(
				"Job Card",
				self.name,
				"followup_job_card",
				prior,
				update_modified=False,
			)
			return prior

		# Aftersales Eng as responsible SE on the new card when present; else
		# fall back to the source SE so the card isn't orphaned.
		aftersales_user = frappe.db.get_value(
			"Has Role",
			{"role": "Aftersales Eng", "parenttype": "User"},
			"parent",
		)
		assigned_se = aftersales_user or self.assigned_service_engineer

		followup = frappe.new_doc("Job Card")
		followup.update(
			{
				"job_card_type": self.job_card_type,
				"repair_subtype": self.repair_subtype,
				"vehicle": self.vehicle,
				"customer": self.customer,
				"depot": self.depot,
				"priority": "Urgent",
				"odometer_reading": self.odometer_reading,
				"complaint_description": (
					f"Critical follow-up auto-created from {self.name} "
					f"(force-closed: {self.force_close_reason or ''})"
				),
				"assigned_service_engineer": assigned_se,
				"source_force_close_job_card": self.name,
				"workflow_state": "Open",
			}
		)
		followup.insert(ignore_permissions=True)

		frappe.db.set_value(
			"Job Card",
			self.name,
			"followup_job_card",
			followup.name,
			update_modified=False,
		)
		return followup.name

	def _notify_central_ops_on_closed_edit(self, old_state: str | None) -> None:
		"""PRD p.3: notify Central Ops whenever a Closed card is modified.

		Suppressed when the save is actually a workflow transition *out* of
		Closed (e.g., Closed→Reopened) — those carry their own notifications.
		"""
		if old_state != "Closed" or self.workflow_state != "Closed":
			return
		reason = getattr(self, "_pre_save_closed_edit_reason", None) or self.closed_edit_reason
		notifications.notify_closed_state_edit(self, reason=reason)

	# ── Health Card auto-generation (Milestone 2) ──

	def _generate_health_card_if_closed(self, old_state: str | None) -> None:
		"""Auto-generate the Vehicle Health Card on SE closure.

		Fires exactly once when the Job Card transitions into the 'Closed'
		state from any other state, for PMS + Repair job cards only. The
		builder is idempotent (skips if a card already exists), so reopens
		and subsequent closures won't duplicate.
		"""
		if self.workflow_state != "Closed":
			return
		if old_state == "Closed":
			return
		if self.job_card_type != "PMS + Repair":
			return
		if self.health_card:
			return

		# Local import avoids a module-load-time cycle between job_card.py
		# and vehicle_health_card.py (both reference shared helpers).
		from vehicle_maintenance.fleet_service.doctype.vehicle_health_card import (
			vehicle_health_card as health_card_module,
		)

		hc_name = health_card_module.generate_from_job_card(self)
		if hc_name:
			frappe.db.set_value(
				"Job Card",
				self.name,
				"health_card",
				hc_name,
				update_modified=False,
			)

	# ── Validations ──

	def _enforce_closed_state_lock(self) -> None:
		"""Once Closed, only Depot Manager may edit and a reason is mandatory.

		On every Closed-state edit the user must provide `closed_edit_reason`.
		The field is cleared after save so it must be re-supplied each time —
		that keeps an audit trail visible in Version history.
		"""
		if self.is_new():
			return
		old_state = self.get_db_value("workflow_state")
		if old_state != "Closed":
			return
		user_roles = set(frappe.get_roles(frappe.session.user))
		if "Administrator" not in user_roles and "Depot Manager" not in user_roles:
			raise frappe.PermissionError(_("This Job Card is Closed. Only a Depot Manager can modify it."))
		if not (self.closed_edit_reason or "").strip():
			frappe.throw(
				_("A 'Reason for Closed-State Edit' is mandatory when modifying " "a Closed Job Card.")
			)

	def _enforce_in_progress_edit_lock(self) -> None:
		"""Technicians cannot edit header fields while the card is in active
		work states (PRD p.3 'Edit job card (In Progress) — SE only').
		Applies to WIP, Closure from Technician, Awaiting Parts, Parts Fitted.
		"""
		if self.is_new():
			return
		old_state = self.get_db_value("workflow_state")
		locked_states = {"WIP", "Awaiting Parts", "Parts Fitted", "Closure from Technician"}
		if old_state not in locked_states:
			return
		user_roles = set(frappe.get_roles(frappe.session.user))
		# Only enforce when the sole privileged role is Technician.
		if user_roles & {
			"Service Engineer",
			"Depot Manager",
			"Administrator",
			"Aftersales Eng",
			"N. Maintenance Head",
		}:
			return
		if "Technician" not in user_roles:
			return
		for field in TECH_LOCKED_HEADER_FIELDS:
			old_value = self.get_db_value(field)
			if old_value != self.get(field):
				frappe.throw(
					_(
						"Technicians cannot edit header field '{0}' while the "
						"Job Card is in progress. Ask the Service Engineer."
					).format(field)
				)

	def _reset_sla_flags_on_reopen(self) -> None:
		"""When a card is reopened (state transitions INTO WIP), clear SLA flags
		so the next cycle is tracked independently. First Open -> WIP transition
		is a no-op because the flags default to 0.
		"""
		if self.is_new():
			return
		old_state = self.get_db_value("workflow_state")
		if old_state == self.workflow_state:
			return
		if self.workflow_state == "WIP" and old_state != "WIP":
			self.sla_warning_sent = 0
			self.sla_breach_sent = 0

	def _apply_breakdown_fast_path(self) -> None:
		"""Breakdown job cards skip the 'Awaiting Customer Approval' gate.

		Customer sign-off is not required for emergency breakdown work; if
		something tries to route a Breakdown to that state we transparently
		redirect it back to 'WIP' rather than failing the save.
		"""
		if self.job_card_type != "Breakdown":
			return
		if self.workflow_state == "Awaiting Customer Approval":
			self.workflow_state = "WIP"

	def _stamp_customer_approval_request(self) -> None:
		"""When the card moves into 'Awaiting Customer Approval', start the
		30-min approval timer by stamping `customer_approval_requested_at` and
		issuing a random `customer_approval_token` so an external-link view
		can be tokenised (PRD p.11 Only Repair step 9).
		The 30-min escalation is handled by `monitor_customer_approval_sla`.
		"""
		if self.is_new():
			return
		old_state = self._pre_save_workflow_state
		if old_state == self.workflow_state:
			return
		if self.workflow_state == "Awaiting Customer Approval" and not self.customer_approval_requested_at:
			self.customer_approval_requested_at = now_datetime()
			self.customer_approval_escalated = 0
			if not self.customer_approval_token:
				self.customer_approval_token = frappe.generate_hash(length=48)
		# If the approval returned (moved out of the wait), stamp the response time.
		if old_state == "Awaiting Customer Approval" and self.workflow_state != "Awaiting Customer Approval":
			if not self.customer_approval_received_at:
				self.customer_approval_received_at = now_datetime()

	def _validate_odometer(self) -> None:
		if self.odometer_reading and self.odometer_reading < 0:
			frappe.throw(_("Odometer Reading cannot be negative."))

	def _validate_workflow_transition(self) -> None:
		if self.is_new():
			return
		old_state = self.get_db_value("workflow_state")
		new_state = self.workflow_state
		if old_state == new_state:
			return
		user_roles = set(frappe.get_roles(frappe.session.user))
		# Administrator override (consistent with force-close and closed-edit
		# gates): permits both unknown transitions and role-restricted ones,
		# enabling system recovery and test harnesses.
		if "Administrator" in user_roles:
			return
		transition = (old_state, new_state)
		if transition not in VALID_TRANSITIONS:
			frappe.throw(_("Invalid transition from {0} to {1}.").format(old_state, new_state))
		allowed_roles = VALID_TRANSITIONS[transition]
		if not user_roles.intersection(allowed_roles):
			frappe.throw(
				_("You do not have permission to move this Job Card from {0} to {1}.").format(
					old_state, new_state
				)
			)

	def _validate_force_close(self) -> None:
		"""Enforce the PRD Force-Close severity matrix.

		Minor    → SE / DM / Aftersales Eng / N.Maint.Head
		Major    → DM / Aftersales Eng / N.Maint.Head (DM approval mandatory)
		Critical → N.Maint.Head only

		Critical severity additionally flags `critical_followup_required` so a
		downstream task can auto-create the 24h follow-up job card (PRD p.6).
		"""
		if not self.force_closed:
			return
		if not (self.force_close_reason or "").strip():
			frappe.throw(_("Force Close Reason is mandatory."))
		if not self.force_close_severity:
			frappe.throw(_("Force Close Severity is mandatory."))

		allowed_roles = FORCE_CLOSE_AUTHORITY.get(self.force_close_severity, [])
		user_roles = set(frappe.get_roles(frappe.session.user))
		if "Administrator" in user_roles:
			pass  # Admin override permitted for recovery
		elif not user_roles.intersection(allowed_roles):
			frappe.throw(
				_("Severity '{0}' can only be force-closed by: {1}.").format(
					self.force_close_severity, ", ".join(allowed_roles)
				)
			)

		if self.force_close_severity == "Critical":
			self.critical_followup_required = 1

	# ── Timestamps ──

	def _stamp_timestamps(self) -> None:
		state = self.workflow_state
		field = STATE_TIMESTAMP_MAP.get(state)
		if field and not self.get(field):
			frappe.db.set_value(self.doctype, self.name, field, now_datetime(), update_modified=False)

	# ── Cost computation ──

	def _compute_costs(self) -> None:
		estimated = 0.0
		actual = 0.0
		for item in self.get("repair_items", []):
			estimated += float(item.get("estimated_amount") or 0)
			actual += float(item.get("actual_amount") or 0)
		for item in self.get("maintenance_items", []):
			estimated += float(item.get("estimated_amount") or 0)
			actual += float(item.get("actual_amount") or 0)
		self.estimated_cost = estimated
		self.actual_cost = actual

	def _compute_approval_threshold(self) -> None:
		"""PRD p.5: parts replacement > ₹1,000 needs customer approval."""
		total_parts = sum(
			float(i.get("estimated_amount") or 0)
			for i in self.get("repair_items", [])
			if i.get("activity_type") in ("Spare Replacement", "Both")
		)
		self.requires_customer_approval = 1 if total_parts > INVENTORY_APPROVAL_THRESHOLD else 0

	# ── Breakdown travel / trial-trip / downtime auto-calc (PRD p.15-16) ──

	def _compute_breakdown_metrics(self) -> None:
		if self.job_card_type != "Breakdown":
			return
		# Travel duration: SE leaves depot → arrives at BD location
		if self.travel_started_at and self.arrived_at_location:
			start = get_datetime(self.travel_started_at)
			end = get_datetime(self.arrived_at_location)
			self.travel_duration_minutes = round((end - start).total_seconds() / 60, 2)
		# Trial trip distance & duration
		if self.trial_trip_start_km is not None and self.trial_trip_end_km is not None:
			try:
				self.trial_trip_distance_km = max(
					0, int(self.trial_trip_end_km) - int(self.trial_trip_start_km)
				)
			except (TypeError, ValueError):
				pass
		if self.trial_trip_started_at and self.trial_trip_ended_at:
			s = get_datetime(self.trial_trip_started_at)
			e = get_datetime(self.trial_trip_ended_at)
			self.trial_trip_duration_minutes = round((e - s).total_seconds() / 60, 2)
		# Total downtime: info received (opened_at) → handover (closed_at or now)
		if self.opened_at:
			start = get_datetime(self.opened_at)
			end_ref = self.closed_at or now_datetime()
			if isinstance(end_ref, str):
				end_ref = get_datetime(end_ref)
			self.total_downtime_minutes = round((end_ref - start).total_seconds() / 60, 2)

	# ── SLA per PRD TAT table ──

	def _compute_sla(self) -> None:
		"""Compute the SLA deadline for this Job Card.

		For Breakdown cards, the SLA has two phases per PRD p.3:
		  1. Remote phase (default 30 min) — from opened_at to remote resolution
		  2. On-site phase (contract-configurable, PRD "XX hrs") — starts when
		     SE arrives at the BD location (`arrived_at_location`)
		When arrival is stamped we recompute with the on-site anchor+target.
		For all other types the deadline is set once and left alone.
		"""
		if self.sla_deadline and not self._breakdown_onsite_active():
			return

		target_hours = self._resolve_sla_target_hours()

		self.sla_target_hours = target_hours
		anchor = self._resolve_sla_anchor()
		if anchor:
			self.sla_deadline = add_to_date(anchor, hours=target_hours)
			# Fresh phase → clear the breach/warning flags so alerts can fire again.
			if self._breakdown_onsite_active():
				self.sla_breached = 0
				self.sla_breach_sent = 0
				self.sla_warning_sent = 0

	def _breakdown_onsite_active(self) -> bool:
		"""True when the Breakdown card has crossed into on-site phase."""
		return self.job_card_type == "Breakdown" and bool(self.arrived_at_location)

	def _resolve_sla_target_hours(self) -> float:
		# Service Contract override wins when set.
		contract_override = None
		if self.service_contract:
			contract = frappe.get_cached_doc("Service Contract", self.service_contract)
			if self._breakdown_onsite_active():
				field = "breakdown_onsite_tat_hours"
			else:
				field_map = {
					"PMS + Repair": "pms_tat_hours",
					"Only Repair": "repair_tat_hours",
					"Breakdown": "breakdown_tat_hours",
					"Software Update": "software_tat_hours",
				}
				field = field_map.get(self.job_card_type)
			if field and contract.get(field):
				contract_override = float(contract.get(field))
		if contract_override:
			return contract_override

		# Otherwise fall back to the PRD-default lookup.
		if self._breakdown_onsite_active():
			return SLA_TARGETS_BY_TYPE["Breakdown On-site"]
		lookup_key = self.job_card_type
		if self.job_card_type == "Only Repair" and self.repair_subtype == "Accidental Major":
			lookup_key = "Only Repair Accidental Major"
		return SLA_TARGETS_BY_TYPE.get(lookup_key, 4.0)

	def _resolve_sla_anchor(self):
		"""The timestamp from which the deadline is measured."""
		if self._breakdown_onsite_active():
			return self.arrived_at_location
		return self.work_started_at or self.opened_at or self.creation

	def _check_sla_breach(self) -> None:
		if self.sla_breached or not self.sla_deadline:
			return
		if self.workflow_state == "Closed":
			return
		now = now_datetime()
		deadline = self.sla_deadline
		if isinstance(deadline, str):
			deadline = get_datetime(deadline)
		if now > deadline:
			self.sla_breached = 1
			self.sla_breached_at = now

	# ── Health Score per PRD (Pre-PMS + Post-PMS) ──

	def _compute_health_scores(self) -> None:
		"""Health Score per PRD p.7.

		1. Component Score: Good=10, Recommended=5, Immediate=0.
		2. Category Score (%) = sum(components)/max * 100, grouped by bus_system.
		3. Vehicle Health Score (%) = average of all category scores
		   (categories weighted equally, independent of component counts).
		4. Persist per-category rows into `category_scores` child table so
		   reports + Vehicle Health Card (M2) can surface the breakdown.
		"""
		if self.job_card_type != "PMS + Repair":
			return

		repair_items = self.get("repair_items", [])
		inspection_items = self.get("inspection_items", [])
		if not repair_items and not inspection_items:
			return

		# Group component scores by category (bus_system) first. The PMS
		# inspection checklist is the primary source — each three-tier row maps
		# Good/Recommended/Immediate -> 10/5/0. Measurement/text rows carry no
		# status and are skipped. Repair items also contribute (existing
		# behaviour: an unset status counts as Good).
		by_category: dict[str, list[int]] = {}
		for item in inspection_items:
			status = item.get("component_status")
			if status not in HEALTH_SCORE_MAP:
				continue
			category = (item.get("category") or "Uncategorized").strip()
			by_category.setdefault(category, []).append(HEALTH_SCORE_MAP[status])
		for item in repair_items:
			category = (item.get("bus_system") or "Uncategorized").strip()
			status = item.get("component_status") or "Good"
			by_category.setdefault(category, []).append(HEALTH_SCORE_MAP.get(status, 10))

		if not by_category:
			return

		# Build per-category percentages and the overall vehicle score.
		category_percentages: dict[str, float] = {}
		for cat, scores in by_category.items():
			if not scores:
				continue
			pct = round((sum(scores) / (len(scores) * 10)) * 100, 1)
			category_percentages[cat] = pct

		if not category_percentages:
			return

		vehicle_score = round(sum(category_percentages.values()) / len(category_percentages), 1)

		# A brand-new card (no workflow configured yet, or state not set) is, by
		# definition, in the Pre-PMS phase — capture the inspection score now rather
		# than waiting for a workflow to stamp "Open".
		is_pre_phase = not self.workflow_state or self.workflow_state in (
			"Open",
			"WIP",
			"Awaiting Customer Approval",
			"Awaiting Parts",
			"Parts Fitted",
		)
		is_post_phase = self.workflow_state in (
			"Closure from Technician",
			"Verification Pending",
			"Closed",
		)

		if is_pre_phase:
			self.pre_pms_score = vehicle_score
		elif is_post_phase:
			self.post_pms_score = vehicle_score
			if self.pre_pms_score and self.pre_pms_score > 0:
				self.score_improvement = round(
					((self.post_pms_score - self.pre_pms_score) / self.pre_pms_score) * 100, 1
				)

		self._sync_category_score_rows(by_category, category_percentages, is_pre_phase, is_post_phase)

	def _sync_category_score_rows(
		self,
		by_category: dict[str, list[int]],
		percentages: dict[str, float],
		is_pre_phase: bool,
		is_post_phase: bool,
	) -> None:
		"""Write `percentages` into the `category_scores` child table.

		Merges with existing rows so a Post-PMS update doesn't wipe the Pre-PMS
		values captured earlier. Keyed on category name.
		"""
		existing: dict[str, dict] = {}
		for row in self.get("category_scores", []):
			cat = (row.get("category") or "").strip()
			if cat:
				existing[cat] = {
					"component_count": row.get("component_count"),
					"pre_pms_score": row.get("pre_pms_score"),
					"post_pms_score": row.get("post_pms_score"),
				}

		merged_rows: list[dict] = []
		for cat, scores in by_category.items():
			prior = existing.get(cat, {})
			row = {
				"category": cat,
				"component_count": len(scores),
				"pre_pms_score": prior.get("pre_pms_score"),
				"post_pms_score": prior.get("post_pms_score"),
			}
			if is_pre_phase:
				row["pre_pms_score"] = percentages[cat]
			elif is_post_phase:
				row["post_pms_score"] = percentages[cat]

			pre = row.get("pre_pms_score") or 0
			post = row.get("post_pms_score")
			if pre and post is not None:
				row["improvement"] = round(((post - pre) / pre) * 100, 1)
			merged_rows.append(row)

		# Replace the child table with the merged set.
		self.set("category_scores", [])
		for row in merged_rows:
			self.append("category_scores", row)

	# ── QR Code generation ──

	def _generate_qr_code(self) -> None:
		try:
			import qrcode
		except ImportError:
			return  # qrcode not installed, skip silently

		# QR data: URL to view this job card
		site_url = frappe.utils.get_url()
		qr_url = f"{site_url}/app/job-card/{self.name}"

		qr = qrcode.QRCode(version=1, box_size=10, border=4)
		qr.add_data(qr_url)
		qr.make(fit=True)

		img = qr.make_image(fill_color="black", back_color="white")
		buffer = io.BytesIO()
		img.save(buffer, format="PNG")
		buffer.seek(0)

		filename = f"qr_{self.name}.png"
		file_doc = frappe.get_doc(
			{
				"doctype": "File",
				"file_name": filename,
				"attached_to_doctype": "Job Card",
				"attached_to_name": self.name,
				"content": buffer.read(),
				"is_private": 0,
			}
		)
		file_doc.save(ignore_permissions=True)

		frappe.db.set_value("Job Card", self.name, "qr_code_image", file_doc.file_url, update_modified=False)


# ── Hook wrappers ──
def validate_job_card(doc: "JobCard", method: str | None = None) -> None:
	doc.validate()


def on_update_job_card(doc: "JobCard", method: str | None = None) -> None:
	doc.on_update()


# ── Whitelisted APIs ──


@frappe.whitelist()
def get_user_roles() -> list[str]:
	"""Return current user's roles. Safe for all logged-in users."""
	return frappe.get_roles(frappe.session.user)


@frappe.whitelist()
def get_job_card_by_qr(job_card_name: str) -> dict:
	"""Return job card summary for QR code scan.

	Args:
	    job_card_name: The Job Card name from QR data.

	Returns:
	    dict with job card summary visible to the scanning user's role.
	"""
	frappe.has_permission("Job Card", doc=job_card_name, throw=True)
	doc = frappe.get_doc("Job Card", job_card_name)

	return {
		"success": True,
		"data": {
			"name": doc.name,
			"job_card_type": doc.job_card_type,
			"vehicle_number": doc.vehicle_number,
			"vehicle_make_model": doc.vehicle_make_model,
			"customer_name": doc.customer_name,
			"service_type": doc.service_type,
			"priority": doc.priority,
			"workflow_state": doc.workflow_state,
			"depot": doc.depot,
			"sla_breached": doc.sla_breached,
			"estimated_cost": float(doc.estimated_cost or 0),
			"pre_pms_score": float(doc.pre_pms_score or 0),
			"post_pms_score": float(doc.post_pms_score or 0),
			"qr_code_image": doc.qr_code_image,
			"start_time": doc.opened_at,
			"end_time": doc.closed_at,
		},
	}
