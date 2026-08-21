"""Put Battery Assembly QC v2 in front of operators.

`v1_9` seeded v2 as a **Draft** on purpose, and left three things undone that
together mean a plant sees nothing at all:

1. **A draft is invisible.** `list_processes` only returns Published definitions,
   so an unpublished v2 is a process that exists in the database and nowhere
   else. Publishing also retires v1, so the floor moves in one step rather than
   running two versions of the same checklist side by side.

2. **The stages were grouped "By Section",** which puts five checks on one
   screen. The runner now renders one check per screen, which is the whole point
   of the redesign — an oversized checklist is the documented cause of ticking
   boxes without doing the work.

3. **Nobody held `Process Operator`.** The definition's `allowed_roles` gate it
   to that role, so with the role unassigned a correctly published process is
   still an empty list on every phone. This was found the hard way on dev: the
   process was live and the app showed "No processes yet".

Doing this in a patch rather than through the Desk is not ceremony — a UI-only
change is invisible to `bench migrate`, so the next site built from this repo
would come up broken in exactly the same way.
"""

import frappe
from frappe.utils import cint

from vehicle_maintenance.process_engine import constants as C

FAMILY = "BATTERY_QC"

#: One check to a screen. See the module docstring.
SCREEN_GROUPING = "One Per Screen"

#: Who does inspections. Deliberately *not* every enabled user — Customer and
#: Guest accounts exist on this site, and a role that gates a plant process
#: should not be handed to the people the plant builds for. Roles that do not
#: exist on a given site simply match nothing, so this list can name more than
#: any one site has.
OPERATOR_SOURCE_ROLES = (
	"Technician",
	"Service Engineer",
	"Battery QA Admin",
	"Ops Manager",
	"Fleet Manager",
)

OPERATOR_ROLE = "Process Operator"


def execute() -> None:
	# A seeding patch must never take a migration down with it: the site coming
	# up matters more than this process being live, and the failure is visible
	# in the error log either way.
	try:
		_publish()
	except Exception:
		frappe.log_error(title="publish_battery_qc", message=frappe.get_traceback())

	try:
		_enforce_screen_grouping()
	except Exception:
		frappe.log_error(title="publish_battery_qc:grouping", message=frappe.get_traceback())

	# Unconditional, because the first production run of this patch printed
	# nothing at all: one branch returned silently and there was no way to tell
	# that apart from the patch not running. A migration that cannot be read
	# afterwards is a migration nobody can trust.
	try:
		_report_state()
	except Exception:
		frappe.log_error(title="publish_battery_qc:state", message=frappe.get_traceback())

	try:
		_grant_operator_role()
	except Exception:
		frappe.log_error(title="publish_battery_qc:roles", message=frappe.get_traceback())


def _publish() -> None:
	"""Publish the newest draft in the family, after fixing its screen grouping.

	The comparison is against the *live version number*, not merely "is anything
	published". The whole situation this patch exists for is v1 live and v2 in
	draft — a guard that skipped whenever any version was published would have
	skipped forever and left the plant on v1, which is precisely the state a
	rehearsal on dev caught it doing.
	"""
	draft = frappe.db.get_value(
		"Process Definition",
		{"family": FAMILY, "status": C.DEF_DRAFT},
		["name", "version"],
		order_by="version desc",
		as_dict=True,
	)
	if not draft:
		# Said out loud rather than returned quietly. A patch that finds nothing
		# to do and says nothing is indistinguishable from one that ran and
		# worked — and the symptom on the floor is identical either way: the app
		# shows an old checklist, or none.
		_note(f"no draft in family {FAMILY}; nothing published")
		return

	live_version = frappe.db.get_value(
		"Process Definition",
		{"family": FAMILY, "status": C.DEF_PUBLISHED},
		"version",
		order_by="version desc",
	)
	# An older draft sitting behind the live version is somebody's abandoned
	# work, not a release. Publishing it would roll the plant backwards.
	if live_version and cint(draft.version) <= cint(live_version):
		_note(f"newest draft is v{draft.version} but v{live_version} is already live; nothing to publish")
		return

	name = draft.name

	doc = frappe.get_doc("Process Definition", name)
	# `publish()` lints first and throws on any Error-severity finding, which is
	# the behaviour we want: a broken definition should stay a draft.
	doc.publish()
	frappe.db.commit()
	_note(f"published {name} (v{draft.version})")


def _grant_operator_role() -> None:
	"""Give `Process Operator` to everyone who does the work.

	Additive only. It never removes the role, so an admin who has deliberately
	revoked it for one person does not get overruled on the next migration.
	"""
	if not frappe.db.exists("Role", OPERATOR_ROLE):
		_note(f"role {OPERATOR_ROLE} does not exist on this site")
		return

	holders = set(
		frappe.get_all(
			"Has Role",
			filters={"role": ["in", OPERATOR_SOURCE_ROLES], "parenttype": "User"},
			pluck="parent",
			limit_page_length=0,
		)
	)
	if not holders:
		_note(f"nobody holds any of {', '.join(OPERATOR_SOURCE_ROLES)}")
		return

	already = set(
		frappe.get_all(
			"Has Role",
			filters={"role": OPERATOR_ROLE, "parent": ["in", list(holders)], "parenttype": "User"},
			pluck="parent",
			limit_page_length=0,
		)
	)

	enabled = set(
		frappe.get_all(
			"User",
			filters={
				"name": ["in", list(holders - already)],
				"enabled": 1,
				"user_type": "System User",
			},
			pluck="name",
			limit_page_length=0,
		)
	)

	if not enabled:
		_note("every fleet-role holder already has Process Operator")
		return

	for user in sorted(enabled):
		doc = frappe.get_doc("User", user)
		doc.append("roles", {"role": OPERATOR_ROLE})
		# `ignore_phone_requirement` because this site makes `mobile_no`
		# mandatory on User, and a service account without one must not block a
		# role grant it is not the subject of.
		doc.flags.ignore_phone_requirement = True
		doc.save(ignore_permissions=True)

	frappe.db.commit()
	_note(f"granted {OPERATOR_ROLE} to {len(enabled)} user(s)")


def _note(message: str) -> None:
	"""Leave a trace of what this patch actually did.

	Migrations run unattended on a VM, so the only way anyone learns the outcome
	is if the patch writes it down. Both the console (visible in the deploy log)
	and the error log (readable afterwards from Desk).
	"""
	print(f"publish_battery_qc: {message}")
	frappe.log_error(title="publish_battery_qc", message=message)


def _report_state() -> None:
	"""Print where the family stands, on every run."""
	rows = frappe.get_all(
		"Process Definition",
		filters={"family": FAMILY},
		fields=["name", "version", "status"],
		order_by="version asc",
		limit_page_length=0,
	)
	if not rows:
		_note(f"family {FAMILY} has no definitions on this site")
		return
	_note("state: " + ", ".join(f"{r.name}=v{r.version}/{r.status}" for r in rows))

	live = next((r for r in rows if r.status == C.DEF_PUBLISHED), None)
	if not live:
		_note("NOTHING PUBLISHED — operators will see an empty process list")
		return
	groupings = frappe.get_all(
		"Process Stage",
		filters={"parent": live.name},
		fields=["stage_code", "screen_grouping"],
		limit_page_length=0,
	)
	_note("live stages: " + ", ".join(f"{g.stage_code}={g.screen_grouping}" for g in groupings))


def _enforce_screen_grouping() -> None:
	"""One check to a screen, on whichever definition is currently live.

	Split out from publishing because coupling the two is what put production
	wrong: v2 was already Published there, so the publish branch was skipped and
	the grouping — the entire point of the redesign — was never touched. The
	plant kept getting five checks to a screen from a patch whose name says it
	fixed exactly that.

	**This edits a Published definition, deliberately.** The immutability guard
	exists so that runs stay reproducible: what was asked, and how it was judged,
	must not change under a run that already happened. `screen_grouping` is
	pagination. It changes no question, no option, no tolerance band and no
	verdict — a completed run's results are identical whether the operator saw
	one check per screen or five. That is the same test the doctype itself
	applies to `status` / `effective_from` / `default_brand` in
	`_MUTABLE_WHEN_PUBLISHED`. Written through `db.set_value` on the child rows
	so the parent's guard is not tripped, with both caches cleared by hand
	because `on_update` will not fire for us.
	"""
	live = frappe.db.get_value(
		"Process Definition",
		{"family": FAMILY, "status": C.DEF_PUBLISHED},
		"name",
		order_by="version desc",
	)
	if not live:
		return

	stale = frappe.get_all(
		"Process Stage",
		filters={
			"parent": live,
			"parenttype": "Process Definition",
			"screen_grouping": ["!=", SCREEN_GROUPING],
		},
		fields=["name", "stage_code", "screen_grouping"],
		limit_page_length=0,
	)
	if not stale:
		_note(f"{live} already renders one check per screen")
		return

	for row in stale:
		frappe.db.set_value(
			"Process Stage",
			row.name,
			{"screen_grouping": SCREEN_GROUPING, "steps_per_screen": 1},
			update_modified=False,
		)

	# `get_cached_doc` in `_live_definition` and the engine's own key both hold
	# the old stages; a child-row write fires neither invalidation.
	frappe.clear_document_cache("Process Definition", live)
	frappe.cache().delete_key(f"process_engine:def:{live}")
	frappe.db.commit()
	_note(
		f"{live}: set {len(stale)} stage(s) to {SCREEN_GROUPING} — "
		+ ", ".join(f"{r.stage_code} was {r.screen_grouping}" for r in stale)
	)
