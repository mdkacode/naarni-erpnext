// KM Billing Report — Desk filters. The report is monthly, so the "Billing Month"
// filter is a month dropdown (Select of "YYYY-MM" values), not a day picker.
// The backend slices the value to YYYY-MM, so the aggregation helpers and the
// public report still receive the same YYYY-MM they always did.

// Recent months as "YYYY-MM", newest first — the Select options. Plain Date math
// (setMonth handles year rollover), so no moment/timezone dependency.
function km_billing_month_options(count) {
	const out = [];
	const d = new Date();
	d.setDate(1);
	for (let i = 0; i < count; i++) {
		const y = d.getFullYear();
		const m = String(d.getMonth() + 1).padStart(2, "0");
		out.push(`${y}-${m}`);
		d.setMonth(d.getMonth() - 1);
	}
	return out.join("\n");
}

frappe.query_reports["KM Billing Report"] = {
	filters: [
		{
			fieldname: "customer",
			label: __("Customer"),
			fieldtype: "Link",
			options: "Customer",
			reqd: 1,
		},
		{
			fieldname: "month",
			label: __("Billing Month"),
			fieldtype: "Select",
			// Last 24 months to pick from; extend if older billing needs re-running.
			options: km_billing_month_options(24),
			reqd: 1,
			default: frappe.datetime.get_today().slice(0, 7),
		},
	],
};
