// Duty Attendance Board — who was on duty, when, and where.
//
// Defaults to today at the manager's own depot, because "who is in right now"
// is the question this report exists to answer; the date range is there for the
// end-of-month look-back.

frappe.query_reports["Duty Attendance Board"] = {
	filters: [
		{
			fieldname: "from_date",
			label: __("From Date"),
			fieldtype: "Date",
			default: frappe.datetime.get_today(),
			reqd: 1,
		},
		{
			fieldname: "to_date",
			label: __("To Date"),
			fieldtype: "Date",
			default: frappe.datetime.get_today(),
			reqd: 1,
		},
		{
			fieldname: "depot",
			label: __("Depot"),
			fieldtype: "Link",
			options: "Depot",
		},
		{
			fieldname: "engineer",
			label: __("Engineer"),
			fieldtype: "Link",
			options: "User",
		},
		{
			fieldname: "status",
			label: __("Status"),
			fieldtype: "Select",
			options: ["", "On Duty", "Present", "Half Day", "Absent", "Not Started", "Week Off"].join("\n"),
		},
		{
			fieldname: "only_exceptions",
			label: __("Only Exceptions"),
			fieldtype: "Check",
			description: __("Late, absent, no-show, or punched outside the depot geofence."),
		},
	],

	formatter(value, row, column, data, default_formatter) {
		const formatted = default_formatter(value, row, column, data);
		if (!data) return formatted;

		if (column.fieldname === "status") {
			const tone = {
				"On Duty": "blue",
				Present: "green",
				"Half Day": "orange",
				Absent: "red",
				"Not Started": "grey",
				"Week Off": "light-blue",
			}[value];
			return tone
				? `<span class="indicator ${tone}">${frappe.utils.escape_html(value)}</span>`
				: formatted;
		}

		// Read lateness at a glance — the number alone gets lost in a wide grid.
		if (column.fieldname === "late_by_minutes" && data.late_by_minutes > 0) {
			return `<span style="color:var(--text-on-red);background:var(--red-100);
				padding:1px 6px;border-radius:8px">${data.late_by_minutes}m</span>`;
		}
		if (column.fieldname === "outside_geofence" && data.outside_geofence) {
			return `<span class="indicator orange">${__("Outside")}</span>`;
		}
		return formatted;
	},
};
