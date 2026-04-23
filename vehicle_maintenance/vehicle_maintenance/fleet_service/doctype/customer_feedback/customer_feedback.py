"""Customer Feedback DocType (Milestone 5).

Captures post-closure NPS + rating per Job Card. One feedback record per Job
Card (enforced by `unique: 1` on the job_card field). Only allowed for job
cards whose workflow_state is already 'Closed'.
"""

import frappe
from frappe import _
from frappe.model.document import Document


RATING_MIN = 1
RATING_MAX = 5
NPS_MIN = 0
NPS_MAX = 10


class CustomerFeedback(Document):
    def validate(self) -> None:
        self._validate_rating_range()
        self._validate_nps_range()
        self._validate_job_card_state()

    def _validate_rating_range(self) -> None:
        if self.rating is None:
            return
        if not (RATING_MIN <= int(self.rating) <= RATING_MAX):
            frappe.throw(
                _("Service rating must be between {0} and {1}.").format(RATING_MIN, RATING_MAX)
            )

    def _validate_nps_range(self) -> None:
        if self.nps_score is None or self.nps_score == "":
            return
        if not (NPS_MIN <= int(self.nps_score) <= NPS_MAX):
            frappe.throw(
                _("NPS must be between {0} and {1}.").format(NPS_MIN, NPS_MAX)
            )

    def _validate_job_card_state(self) -> None:
        if not self.job_card:
            return
        state = frappe.db.get_value("Job Card", self.job_card, "workflow_state")
        if state != "Closed":
            frappe.throw(
                _("Feedback can only be submitted for Closed Job Cards (current: {0}).").format(state)
            )
