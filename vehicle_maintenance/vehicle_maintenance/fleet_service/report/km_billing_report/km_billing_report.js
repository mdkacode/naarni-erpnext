// KM Billing Report — Desk filters. The report is monthly: the "Billing Month"
// filter is a calendar date picker, and the WHOLE calendar month of the picked
// date is billed (the day itself is not significant). The backend slices the
// value to YYYY-MM, so the aggregation helpers and the public report still
// receive the same YYYY-MM they always did.
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
			fieldtype: "Date",
			reqd: 1,
			// Default to the 1st of the current month so the value reads as a
			// month at a glance. Any day may be picked — the backend bills the
			// whole calendar month of the chosen date.
			default: frappe.datetime.get_today().slice(0, 8) + "01",
		},
	],
};
