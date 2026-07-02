"""White-label branding for the shared email template (co-brand + default path).

Run:
    bench --site <site> run-tests --module vehicle_maintenance.fleet_service.test_email_templates
"""

from frappe.tests.utils import FrappeTestCase

from vehicle_maintenance.fleet_service import email_templates as et


class TestBrandedEmail(FrappeTestCase):
	def test_default_header_unchanged(self):
		# Regression: existing callers (no white-label args) still get the NaArNi header.
		html = et.render_branded_email(heading="Alert", body_html="<p>hi</p>")
		self.assertIn("NaArNi", html)
		self.assertIn("Notification", html)
		self.assertNotIn("powered by", html)

	def test_white_label_shows_customer_and_powered_by(self):
		html = et.render_branded_email(
			heading="Your Monthly KM Report",
			body_html="<p>June figures</p>",
			customer_logo_url="https://cdn.example.com/acme.png",
			customer_name="Acme Transit",
			cta_label="View KM Report",
			cta_url="https://fleet.example.com/km-report/abc123",
		)
		self.assertIn("Acme Transit", html)
		self.assertIn("acme.png", html)
		self.assertIn("powered by", html)
		self.assertIn("NaArNi", html)  # NaArNi still present (lockup + footer)
		self.assertIn("/km-report/abc123", html)  # CTA points at the public link
		# customer name is escaped, not injected raw
		self.assertNotIn("<script", html)

	def test_white_label_name_only(self):
		html = et.render_branded_email(heading="Report", body_html="<p>x</p>", customer_name="Beta Fleet")
		self.assertIn("Beta Fleet", html)
		self.assertIn("powered by", html)

	def test_logo_url_is_attribute_escaped(self):
		html = et.render_branded_email(
			heading="Report",
			body_html="<p>x</p>",
			customer_logo_url='https://x/a.png" onerror="alert(1)',
			customer_name="Q",
		)
		# the quote that would break out of the src attribute must be escaped
		self.assertNotIn('src="https://x/a.png" onerror=', html)
		self.assertIn("&quot;", html)

	def test_plain_text_includes_customer(self):
		txt = et.to_plain_text("Report", "body", customer_name="Acme Transit")
		self.assertIn("Acme Transit", txt)
		self.assertIn("powered by NaArNi", txt)
