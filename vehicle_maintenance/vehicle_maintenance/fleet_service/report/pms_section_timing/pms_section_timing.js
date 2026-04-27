// PMS Section Timing — Desk filter definitions.
frappe.query_reports["PMS Section Timing"] = {
	filters: [
		{
			fieldname: "from_date",
			label: __("From Date"),
			fieldtype: "Date",
			default: frappe.datetime.add_days(frappe.datetime.get_today(), -30),
		},
		{
			fieldname: "to_date",
			label: __("To Date"),
			fieldtype: "Date",
			default: frappe.datetime.get_today(),
		},
		{
			fieldname: "technician",
			label: __("Technician"),
			fieldtype: "Link",
			options: "User",
		},
		{
			fieldname: "depot",
			label: __("Depot"),
			fieldtype: "Link",
			options: "Depot",
		},
		{
			fieldname: "template_code",
			label: __("Check Sheet"),
			fieldtype: "Link",
			options: "Check Sheet Template",
			default: "SHEET_A",
		},
		{
			fieldname: "section_code",
			label: __("Section Code"),
			fieldtype: "Data",
		},
	],
};
