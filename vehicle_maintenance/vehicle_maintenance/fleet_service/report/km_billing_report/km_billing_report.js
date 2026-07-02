// KM Billing Report — Desk filters. Month is a plain YYYY-MM string so it lines up
// with the aggregation helpers and the public report.
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
			label: __("Month (YYYY-MM)"),
			fieldtype: "Data",
			reqd: 1,
			default: frappe.datetime.get_today().slice(0, 7),
		},
	],
};
