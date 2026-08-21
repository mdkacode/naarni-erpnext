"""Shared vocabulary for the process engine.

Every literal the engine branches on lives here so the doctype JSON, the Python
controllers, the whitelisted API and the app renderer cannot drift apart.

`STEP_TYPE_CAPABILITY` is the one that matters operationally: each response type
declares the minimum app capability level that can render it. Publishing a
process computes the maximum across its steps into
`Process Definition.min_app_step_types`, and an older install that reports a
lower level renders those steps read-only instead of crashing. Without this the
first new step type breaks every phone in the plant on a Monday morning.
"""

from __future__ import annotations

# ---------------------------------------------------------------- response types

CHOICE = "Choice"
CHOICE_MULTI = "Choice Multi"
NUMBER = "Number"
NUMBER_IN_RANGE = "Number in Range"
NUMBER_WITH_TOLERANCE = "Number with Tolerance"
COMPUTED = "Computed"
TEXT_SHORT = "Text Short"
TEXT_LONG = "Text Long"
YES_NO = "Yes No"
DATE = "Date"
DATETIME = "Datetime"
PHOTO_ONLY = "Photo Only"
SCAN = "Scan"
SIGNATURE = "Signature"
LINK = "Link"
SECTION_NOTE = "Section Note"
#: A weight typed in KG next to a photograph of the scale. The app reads the
#: number off the photo with on-device OCR and pre-fills the box; the operator
#: confirms or corrects it. Capability 2 — see STEP_TYPE_CAPABILITY below.
WEIGHT_PHOTO = "Weight from Photo"
#: The last screen of a run: everything answered so far, listed back with its
#: photographs, and one button that says it is right. Records nothing new — it
#: exists so the person who captured the evidence is the person who checks it,
#: while the item is still in front of them. Capability 3.
REVIEW = "Review & Confirm"

RESPONSE_TYPES = (
	CHOICE,
	CHOICE_MULTI,
	NUMBER,
	NUMBER_IN_RANGE,
	NUMBER_WITH_TOLERANCE,
	COMPUTED,
	TEXT_SHORT,
	TEXT_LONG,
	YES_NO,
	DATE,
	DATETIME,
	PHOTO_ONLY,
	SCAN,
	SIGNATURE,
	LINK,
	SECTION_NOTE,
	WEIGHT_PHOTO,
	REVIEW,
)

#: Types whose answer is a number held in `value_numeric`.
NUMERIC_TYPES = (NUMBER, NUMBER_IN_RANGE, NUMBER_WITH_TOLERANCE, COMPUTED, WEIGHT_PHOTO)

#: Types that carry a genuine pass/fail judgement and therefore score.
JUDGED_TYPES = (CHOICE, CHOICE_MULTI, YES_NO, *NUMERIC_TYPES)

#: Types that never score, whatever weight is set on the step.
UNSCORED_TYPES = (SECTION_NOTE, REVIEW)

#: Minimum app capability level per response type. Bump the level (never renumber
#: an existing one) when adding a type, and raise
#: `Process Engine Settings.app_step_type_capability` once the app ships it.
STEP_TYPE_CAPABILITY = {
	CHOICE: 1,
	CHOICE_MULTI: 1,
	NUMBER: 1,
	NUMBER_IN_RANGE: 1,
	NUMBER_WITH_TOLERANCE: 1,
	TEXT_SHORT: 1,
	TEXT_LONG: 1,
	YES_NO: 1,
	PHOTO_ONLY: 1,
	SCAN: 1,
	SECTION_NOTE: 1,
	DATE: 1,
	DATETIME: 1,
	SIGNATURE: 1,
	LINK: 1,
	COMPUTED: 1,
	# Level 2: needs an app that bundles the on-device OCR model. An older build
	# reports capability 1, so the server marks this step unsupported and the
	# runner shows a read-only card with an update prompt rather than crashing.
	WEIGHT_PHOTO: 2,
	# Level 3: the run's own summary screen. An app that cannot draw it would
	# otherwise fall through to a text box asking the operator to type the word
	# "confirmed", which is worse than telling them to update.
	REVIEW: 3,
}

# ---------------------------------------------------------------- pass conditions

WITHIN_RANGE = "Within Range"
GREATER_THAN_MIN = "Greater Than Min"
LESS_THAN_MAX = "Less Than Max"
EQUALS_NOMINAL = "Equals Nominal"
ANY_VALUE = "Any Value"

# ---------------------------------------------------------------- run status

STATUS_DRAFT = "Draft"
STATUS_IN_PROGRESS = "In Progress"
STATUS_AWAITING_VERIFICATION = "Awaiting Verification"
STATUS_PASSED = "Passed"
STATUS_QUARANTINED = "Quarantined"
STATUS_IN_REWORK = "In Rework"
STATUS_CANCELLED = "Cancelled"

#: Runs in these states accept no further answers.
TERMINAL_STATUSES = (STATUS_PASSED, STATUS_CANCELLED)

# ---------------------------------------------------------------- definition status

DEF_DRAFT = "Draft"
DEF_PUBLISHED = "Published"
DEF_RETIRED = "Retired"

# ---------------------------------------------------------------- action triggers

TRIGGER_ON_ANSWER = "On Answer"
TRIGGER_ON_PASS = "On Pass"
TRIGGER_ON_FAIL = "On Fail"
TRIGGER_ON_CRITICAL = "On Critical"
TRIGGER_ON_SKIP = "On Skip"
TRIGGER_ON_OUT_OF_RANGE = "On Out Of Range"
TRIGGER_ON_OPTION = "On Option"
TRIGGER_ON_STAGE_COMPLETE = "On Stage Complete"
TRIGGER_ON_PROCESS_COMPLETE = "On Process Complete"

# ---------------------------------------------------------------- action types

ACT_NOTIFY_ROLE = "Notify Role"
ACT_NOTIFY_USER = "Notify User"
ACT_RAISE_DEVIATION = "Raise Deviation"
ACT_BLOCK_STAGE = "Block Stage"
ACT_QUARANTINE = "Quarantine Run"
ACT_SET_RUN_STATUS = "Set Run Status"
ACT_REQUIRE_PHOTO = "Require Photo"
ACT_REQUIRE_REMARK = "Require Remark"
ACT_JUMP_TO_STEP = "Jump To Step"
ACT_SET_FIELD_ON_SUBJECT = "Set Field On Subject"
ACT_CREATE_DOCUMENT = "Create Document"
ACT_SEND_EMAIL = "Send Email"
ACT_SEND_SMS = "Send SMS"
ACT_ADD_TAG = "Add Tag"
ACT_RUN_SERVER_SCRIPT = "Run Server Script"

#: Actions the client needs to know about before the operator moves on — the API
#: returns these so the app can prompt in place rather than after the fact.
CLIENT_HINT_ACTIONS = (ACT_REQUIRE_PHOTO, ACT_REQUIRE_REMARK, ACT_JUMP_TO_STEP)

# ---------------------------------------------------------------- roles

ROLE_AUTHOR = "Process Author"
ROLE_OPERATOR = "Process Operator"
ROLE_VERIFIER = "Process Verifier"
ROLE_VIEWER = "Process Viewer"

ENGINE_ROLES = (ROLE_AUTHOR, ROLE_OPERATOR, ROLE_VERIFIER, ROLE_VIEWER)

#: Roles that may read and extend a Link step's pick list without holding an
#: engine role — the gate staff, who run the material process but are not
#: process authors. Kept here so `api.process` does not have to import from a
#: sibling feature module.
MATERIAL_LINK_ROLES = ("Material Gate Operator", "Material Supervisor")

# ---------------------------------------------------------------- scan parsing

#: Named regex groups the scan parser recognises. Anything else in a pattern is
#: matched but discarded, so admins can use groups for structure without harm.
SCAN_GROUPS = ("serial", "mfg", "module", "batch", "rev")

#: Supported `mfg_date_format` values → (day_idx, month_idx, year_idx) slices.
MFG_DATE_FORMATS = {
	"DDMMYY": ((0, 2), (2, 4), (4, 6)),
	"DDMMYYYY": ((0, 2), (2, 4), (4, 8)),
	"YYMMDD": ((4, 6), (2, 4), (0, 2)),
	"YYYYMMDD": ((6, 8), (4, 6), (0, 4)),
}
