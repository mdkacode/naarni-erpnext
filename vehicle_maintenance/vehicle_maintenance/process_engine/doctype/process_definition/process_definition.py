"""Process Definition — the authored process, and the rules that keep it honest.

Two invariants this controller exists to hold:

**Published is immutable.** Once published, a definition cannot be edited; the
author clones it, which forks ``version + 1`` as a new Draft. Runs record both
the definition and its version, so a certificate printed in two years replays
the process exactly as the operator saw it. Without this, editing a template
silently rewrites history, and no audit of the result is worth anything.

**Publishing is linted, not trusted.** `lint()` catches the mistakes authoring
UIs produce constantly — a step pointing at a stage that does not exist, a
condition referring to a step answered later, a Choice with no options. An
unreachable step is the no-code equivalent of dead code, and only a linter will
ever find it.
"""

from __future__ import annotations

import frappe
from frappe import _
from frappe.model.document import Document
from frappe.utils import cint

from vehicle_maintenance.process_engine import computed, conditions
from vehicle_maintenance.process_engine import constants as C

#: Fields an author may still change on a Published definition. Everything else
#: is frozen — these three affect neither what was asked nor how it was judged.
_MUTABLE_WHEN_PUBLISHED = {"status", "effective_from", "default_brand"}


class ProcessDefinition(Document):
	def validate(self):
		self._guard_published_edit()
		self._default_family()
		self._normalise_sequences()
		self._validate_structure()
		self._compute_capability()

	def on_update(self):
		frappe.cache().delete_key(f"process_engine:def:{self.name}")

	# ------------------------------------------------------------ step expansion

	def expanded_steps(self) -> list[dict]:
		"""Steps with their linked sets and conditions inlined, in sequence order.

		The rest of the engine — evaluation, conditions, the API serialiser —
		works on a step dict carrying its own ``options``, ``actions`` and
		``visibility_conditions``. Those live on shared Outcome Sets, Action Sets
		and a flat condition table on this document (Frappe does not persist a
		child table nested inside another child row), so this is the single place
		that reassembles them. Resolve here and nothing downstream has to care.
		"""
		conditions_by_step: dict[str, list[dict]] = {}
		for row in self.step_conditions or []:
			conditions_by_step.setdefault((row.step_code or "").strip(), []).append(row.as_dict())

		set_cache: dict[str, list[dict]] = {}

		def resolve(doctype: str, name: str, field: str) -> list[dict]:
			if not name:
				return []
			key = f"{doctype}:{name}"
			if key not in set_cache:
				try:
					doc = frappe.get_cached_doc(doctype, name)
					set_cache[key] = [r.as_dict() for r in doc.get(field) or []]
				except frappe.DoesNotExistError:
					# A deleted set must not break every run using it.
					set_cache[key] = []
			return set_cache[key]

		out = []
		for step in sorted(self.steps or [], key=lambda s: cint(s.sequence)):
			data = step.as_dict()
			data["options"] = resolve("Process Outcome Set", step.outcome_set, "options")
			data["actions"] = resolve("Process Action Set", step.action_set, "actions")
			data["visibility_conditions"] = conditions_by_step.get((step.step_code or "").strip(), [])
			out.append(data)
		return out

	def expanded_step(self, step_code: str) -> dict | None:
		return next((s for s in self.expanded_steps() if s["step_code"] == step_code), None)

	# ------------------------------------------------------------- invariants

	def _guard_published_edit(self):
		"""Block edits to a Published definition, except the few safe fields."""
		if self.is_new() or self.status != C.DEF_PUBLISHED:
			return
		before = self.get_doc_before_save()
		if not before:
			return
		changed = {
			field.fieldname
			for field in self.meta.fields
			if field.fieldtype not in ("Section Break", "Column Break", "HTML")
			and self.get(field.fieldname) != before.get(field.fieldname)
		}
		blocked = changed - _MUTABLE_WHEN_PUBLISHED
		if blocked:
			frappe.throw(
				_(
					"{0} is published and cannot be edited. Use <b>Clone to New Version</b> "
					"to make changes — runs reference this version and must stay reproducible.<br><br>"
					"Blocked fields: {1}"
				).format(self.process_name, ", ".join(sorted(blocked)))
			)

	def _default_family(self):
		if not self.family:
			# Family groups every version of a process; strip any -vN suffix so
			# v1 and v2 of the same process land in the same family.
			base = (self.process_code or "").rsplit("-v", 1)[0]
			self.family = base or self.process_code

	def _normalise_sequences(self):
		for idx, stage in enumerate(self.stages or [], start=1):
			if not cint(stage.sequence):
				stage.sequence = idx * 10
		for idx, step in enumerate(self.steps or [], start=1):
			if not cint(step.sequence):
				step.sequence = idx * 10
			if not step.display_no:
				step.display_no = str(idx)
			if step.response_type in C.UNSCORED_TYPES:
				step.weight = 0

	# -------------------------------------------------------------- structure

	def _validate_structure(self):
		stage_codes = [(s.stage_code or "").strip() for s in self.stages or []]
		if len(stage_codes) != len(set(stage_codes)):
			frappe.throw(_("Stage codes must be unique within a process."))

		step_codes = [(s.step_code or "").strip() for s in self.steps or []]
		dupes = {c for c in step_codes if step_codes.count(c) > 1 and c}
		if dupes:
			frappe.throw(
				_(
					"Step codes must be unique — conditions, actions and run history all refer to them. Duplicated: {0}"
				).format(", ".join(sorted(dupes)))
			)

		known = set(stage_codes)
		for step in self.steps or []:
			if (step.stage or "").strip() not in known:
				frappe.throw(
					_("Step {0} refers to stage '{1}', which this process does not define.").format(
						step.step_code, step.stage
					)
				)

		valid_steps = set(step_codes)
		for cond in self.step_conditions or []:
			if (cond.step_code or "").strip() not in valid_steps:
				frappe.throw(
					_("A visibility rule applies to step '{0}', which this process does not define.").format(
						cond.step_code
					)
				)

	def _compute_capability(self):
		"""Highest app capability level any step needs — read at publish time.

		An older install reports its own level; anything above it renders
		read-only with an update prompt instead of crashing the runner.
		"""
		levels = [C.STEP_TYPE_CAPABILITY.get(s.response_type, 1) for s in self.steps or []]
		self.min_app_step_types = max(levels) if levels else 1

	# ------------------------------------------------------------------- lint

	def lint(self) -> list[dict]:
		"""Publish-readiness warnings. Returns ``[{severity, message}]``."""
		issues: list[dict] = []

		def warn(msg, severity="Warning"):
			issues.append({"severity": severity, "message": msg})

		if not self.steps:
			warn(_("This process has no steps."), "Error")
		if not self.stages:
			warn(_("This process has no stages."), "Error")

		steps = self.expanded_steps()
		for bad in conditions.unreachable_steps(steps):
			warn(_("Unreachable step — {0}").format(bad), "Error")

		codes = {s["step_code"] for s in steps}
		for step in steps:
			code = step["step_code"]
			rtype = step.get("response_type")

			if rtype in (C.CHOICE, C.CHOICE_MULTI) and not step.get("options"):
				warn(
					_(
						"Step {0} is a Choice but has no outcome set, so the operator has nothing to pick."
					).format(code),
					"Error",
				)
			elif rtype in (C.CHOICE, C.CHOICE_MULTI, C.YES_NO) and step.get("options"):
				if not any(cint(o.get("is_pass")) for o in step["options"]):
					warn(_("Step {0} has no option marked as Pass — it can never be passed.").format(code))
			if cint(step.get("requires_scan")) and not step.get("scan_entity_type"):
				warn(_("Step {0} requires a scan but names no entity type.").format(code), "Error")
			if rtype == C.LINK and not step.get("link_doctype"):
				warn(_("Step {0} is a Link but names no doctype.").format(code), "Error")
			if rtype == C.NUMBER_WITH_TOLERANCE and not step.get("tolerance"):
				warn(_("Step {0} uses tolerance but the tolerance is zero.").format(code))
			if rtype == C.COMPUTED:
				expression = step.get("computed_expression")
				if not expression:
					warn(_("Step {0} is Computed but has no expression.").format(code), "Error")
				else:
					unknown = [r for r in computed.referenced_steps(expression) if r not in codes]
					if unknown:
						warn(
							_("Step {0} computes from unknown step(s): {1}").format(code, ", ".join(unknown)),
							"Error",
						)
			for action in step.get("actions") or []:
				if action.get("action_type") == C.ACT_NOTIFY_ROLE and not action.get("target"):
					warn(_("Step {0} has a Notify Role action with no role.").format(code), "Error")

		for stage in self.stages or []:
			if cint(stage.requires_second_signoff) and not stage.second_signoff_role:
				warn(
					_("Stage {0} requires a second sign-off but names no role.").format(stage.stage_code),
					"Error",
				)

		if cint(self.scoring_enabled):
			scoreable = [s for s in self.steps or [] if s.response_type in C.JUDGED_TYPES and s.weight]
			if not scoreable:
				warn(_("Scoring is on but no step carries a weight, so every run scores zero."), "Error")

		capability = cint(
			frappe.db.get_single_value("Process Engine Settings", "app_step_type_capability") or 1
		)
		if cint(self.min_app_step_types) > capability:
			warn(
				_(
					"This process uses step types above the current app capability ({0} > {1}). "
					"Older installs will render those steps read-only."
				).format(self.min_app_step_types, capability)
			)

		return issues

	# ------------------------------------------------------------- transitions

	def publish(self):
		"""Freeze this version and make it the live one for its family."""
		if self.status == C.DEF_PUBLISHED:
			frappe.throw(_("Already published."))

		blocking = [i for i in self.lint() if i["severity"] == "Error"]
		if blocking:
			frappe.throw(
				_("Cannot publish — fix these first:<br>{0}").format(
					"<br>".join("• " + i["message"] for i in blocking)
				)
			)

		# Retire the previously live version in this family, so operators only
		# ever see one Published definition per family.
		for other in frappe.get_all(
			"Process Definition",
			filters={"family": self.family, "status": C.DEF_PUBLISHED, "name": ["!=", self.name]},
			pluck="name",
		):
			frappe.db.set_value("Process Definition", other, "status", C.DEF_RETIRED)

		self.db_set(
			{
				"status": C.DEF_PUBLISHED,
				"published_by": frappe.session.user,
				"published_at": frappe.utils.now_datetime(),
			}
		)
		frappe.cache().delete_key(f"process_engine:def:{self.name}")
		return {
			"success": True,
			"message": _("{0} v{1} is now live.").format(self.process_name, self.version),
		}

	def retire(self):
		self.db_set("status", C.DEF_RETIRED)
		return {"success": True, "message": _("{0} retired.").format(self.process_name)}

	def clone_new_version(self):
		"""Fork this definition as ``version + 1`` in Draft.

		The only way to change a published process. Everything is copied,
		including nested options, conditions and actions.
		"""
		latest = (
			frappe.db.get_value(
				"Process Definition",
				{"family": self.family},
				"max(version)",
			)
			or self.version
		)
		new_version = cint(latest) + 1

		clone = frappe.copy_doc(self)
		clone.process_code = f"{self.family}-v{new_version}"
		clone.version = new_version
		clone.status = C.DEF_DRAFT
		clone.family = self.family
		clone.published_by = None
		clone.published_at = None
		clone.supersedes = self.name
		clone.insert()
		return clone.name
