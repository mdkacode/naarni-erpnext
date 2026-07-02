"""NaArNi-branded HTML email templates for Fleet Service notifications.

All notification emails go through `render_branded_email()` so the look-and-feel
stays consistent and any rebrand is a one-file change. Inline CSS only —
Gmail/Outlook strip <style> blocks and external stylesheets.

Brand palette (derived from the NaArNi Vue SPA tailwind config):
  • Brand primary  #0F4C81 (deep navy)   — header / primary CTA
  • Brand accent   #F6A623 (signal amber) — highlights, alerts
  • Neutral 50     #F7F9FC                — page background
  • Text primary   #1F2937                — body copy
  • Text muted     #6B7280                — meta/footer
"""

from __future__ import annotations

from html import escape

BRAND_NAME = "NaArNi"
BRAND_TAGLINE = "Fleet Service Platform"
BRAND_PRIMARY = "#0F4C81"
BRAND_ACCENT = "#F6A623"
BRAND_BG = "#F7F9FC"
TEXT_PRIMARY = "#1F2937"
TEXT_MUTED = "#6B7280"
BORDER = "#E5E7EB"

# Pill colours per priority/severity level — inline CSS below.
PRIORITY_STYLES = {
	"High": ("#DC2626", "#FEE2E2"),  # red 600 on red 100
	"Medium": ("#B45309", "#FEF3C7"),  # amber 700 on amber 100
	"Low": ("#1E40AF", "#DBEAFE"),  # blue 800 on blue 100
}


def _brand_bar(customer_logo_url: str | None, customer_name: str | None) -> tuple[str, str]:
	"""Return (left_cell_html, right_cell_html) for the header brand bar.

	Co-brands with the customer (logo and/or name) and a "powered by NaArNi"
	lockup when white-label details are given; otherwise the default NaArNi header.
	"""
	if not (customer_logo_url or customer_name):
		left = (
			f"{BRAND_NAME}"
			f'<span style="font-weight:400;color:#CBD5E1;font-size:13px;'
			f'margin-left:10px;letter-spacing:0.2px;">{BRAND_TAGLINE}</span>'
		)
		return left, "Notification"

	logo_html = ""
	if customer_logo_url:
		logo_html = (
			f'<img src="{escape(customer_logo_url, quote=True)}" '
			f'alt="{escape(customer_name or "")}" '
			f'style="max-height:34px;max-width:170px;vertical-align:middle;'
			f'background:#FFFFFF;border-radius:4px;padding:2px 6px;" />'
		)
	name_html = ""
	if customer_name:
		name_html = (
			f'<span style="color:#FFFFFF;font-size:18px;font-weight:700;'
			f'letter-spacing:0.3px;vertical-align:middle;'
			f'{"margin-left:10px;" if logo_html else ""}">{escape(customer_name)}</span>'
		)
	right = (
		f'<span style="color:#CBD5E1;font-size:11px;font-weight:500;'
		f'text-transform:none;letter-spacing:0.2px;">powered by </span>'
		f'<span style="color:{BRAND_ACCENT};font-size:13px;font-weight:700;'
		f'letter-spacing:0.5px;">{BRAND_NAME}</span>'
	)
	return logo_html + name_html, right


def render_branded_email(
	*,
	heading: str,
	body_html: str,
	preheader: str | None = None,
	cta_label: str | None = None,
	cta_url: str | None = None,
	priority: str = "Medium",
	meta_rows: list[tuple[str, str]] | None = None,
	customer_logo_url: str | None = None,
	customer_name: str | None = None,
) -> str:
	"""Return a full HTML email wrapped in NaArNi branding.

	Args:
	    heading:    Big h1 at the top of the card (plain text, will be escaped).
	    body_html:  Pre-sanitised HTML for the message body. Callers are
	                responsible for escaping any user-supplied substitutions
	                they include in the body.
	    preheader:  Optional 1-line summary shown in inbox previews. Hidden
	                inline in the rendered email.
	    cta_label:  Optional button text.
	    cta_url:    Optional button destination. Both must be set for the
	                button to render.
	    priority:   Drives the coloured pill next to the heading.
	    meta_rows:  Optional list of (label, value) rows rendered as a neat
	                2-column grid below the heading.
	    customer_logo_url: Optional customer logo (white-label). When set (or
	                customer_name is set), the brand bar co-brands with the
	                customer and shows a "powered by NaArNi" lockup instead of
	                the plain NaArNi/Notification header.
	    customer_name: Optional customer display name for white-labelling.

	Returns:
	    A single-string HTML document ready to pass to `frappe.sendmail` or
	    Microsoft Graph sendMail.
	"""
	safe_heading = escape(heading)
	brand_left_html, brand_right_html = _brand_bar(customer_logo_url, customer_name)
	pill_fg, pill_bg = PRIORITY_STYLES.get(priority, PRIORITY_STYLES["Medium"])
	priority_pill = (
		f'<span style="display:inline-block;font-size:11px;font-weight:600;'
		f"letter-spacing:0.5px;text-transform:uppercase;padding:4px 10px;"
		f"border-radius:999px;color:{pill_fg};background:{pill_bg};"
		f'margin-left:8px;vertical-align:middle;">{escape(priority)}</span>'
	)

	preheader_html = ""
	if preheader:
		preheader_html = (
			f'<div style="display:none;overflow:hidden;line-height:1px;'
			f'opacity:0;max-height:0;max-width:0;">{escape(preheader)}</div>'
		)

	cta_html = ""
	if cta_label and cta_url:
		cta_html = (
			f'<div style="text-align:center;margin:28px 0 12px;">'
			f'<a href="{escape(cta_url, quote=True)}" '
			f'style="display:inline-block;padding:12px 28px;background:{BRAND_PRIMARY};'
			f"color:#FFFFFF;text-decoration:none;border-radius:6px;"
			f'font-weight:600;font-size:14px;letter-spacing:0.3px;">'
			f"{escape(cta_label)}</a></div>"
		)

	meta_html = ""
	if meta_rows:
		rows = "".join(
			f'<tr>'
			f'<td style="padding:6px 12px 6px 0;color:{TEXT_MUTED};'
			f'font-size:13px;white-space:nowrap;width:140px;">{escape(label)}</td>'
			f'<td style="padding:6px 0;color:{TEXT_PRIMARY};font-size:13px;'
			f'font-weight:500;">{escape(str(value) if value is not None else "—")}</td>'
			f'</tr>'
			for label, value in meta_rows
		)
		meta_html = (
			f'<table role="presentation" cellspacing="0" cellpadding="0" '
			f'style="width:100%;margin:16px 0 8px;border-top:1px solid {BORDER};'
			f'border-bottom:1px solid {BORDER};padding:4px 0;">{rows}</table>'
		)

	return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>{safe_heading}</title>
</head>
<body style="margin:0;padding:0;background:{BRAND_BG};
font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;
color:{TEXT_PRIMARY};-webkit-font-smoothing:antialiased;">
{preheader_html}
<table role="presentation" width="100%" cellspacing="0" cellpadding="0"
       style="background:{BRAND_BG};padding:32px 16px;">
  <tr>
    <td align="center">
      <table role="presentation" width="600" cellspacing="0" cellpadding="0"
             style="max-width:600px;width:100%;background:#FFFFFF;
                    border:1px solid {BORDER};border-radius:10px;overflow:hidden;">
        <!-- Brand bar -->
        <tr>
          <td style="background:{BRAND_PRIMARY};padding:20px 28px;">
            <table role="presentation" width="100%" cellspacing="0" cellpadding="0">
              <tr>
                <td style="color:#FFFFFF;font-size:20px;font-weight:700;
                           letter-spacing:0.5px;">
                  {brand_left_html}
                </td>
                <td align="right" style="color:{BRAND_ACCENT};font-size:12px;
                                         font-weight:600;text-transform:uppercase;
                                         letter-spacing:1px;">
                  {brand_right_html}
                </td>
              </tr>
            </table>
          </td>
        </tr>
        <!-- Content -->
        <tr>
          <td style="padding:28px 32px 8px 32px;">
            <h1 style="margin:0 0 8px 0;font-size:22px;line-height:1.3;
                       color:{TEXT_PRIMARY};font-weight:700;">
              {safe_heading}{priority_pill}
            </h1>
            {meta_html}
            <div style="font-size:15px;line-height:1.55;color:{TEXT_PRIMARY};
                        margin-top:12px;">
              {body_html}
            </div>
            {cta_html}
          </td>
        </tr>
        <!-- Footer -->
        <tr>
          <td style="padding:20px 32px 28px 32px;border-top:1px solid {BORDER};">
            <p style="margin:0;font-size:12px;line-height:1.5;color:{TEXT_MUTED};">
              This is an automated message from the {BRAND_NAME} {BRAND_TAGLINE}.
              If this reached you by mistake, please let us know and ignore it —
              no action will be required.
            </p>
            <p style="margin:8px 0 0 0;font-size:12px;color:{TEXT_MUTED};">
              © {BRAND_NAME}. All rights reserved.
            </p>
          </td>
        </tr>
      </table>
    </td>
  </tr>
</table>
</body>
</html>"""


def to_plain_text(
	heading: str,
	body_text: str,
	cta_label: str | None = None,
	cta_url: str | None = None,
	customer_name: str | None = None,
) -> str:
	"""Return a plain-text fallback for the email — sent alongside the HTML
	part so readers without HTML rendering still see something useful.
	"""
	lines = [heading, "=" * len(heading), "", body_text.strip(), ""]
	if cta_label and cta_url:
		lines += [f"{cta_label}: {cta_url}", ""]
	footer = (
		f"{customer_name} · powered by {BRAND_NAME}" if customer_name else f"{BRAND_NAME} {BRAND_TAGLINE}"
	)
	lines += [
		"—",
		footer,
		"Automated message. Please do not reply.",
	]
	return "\n".join(lines)
