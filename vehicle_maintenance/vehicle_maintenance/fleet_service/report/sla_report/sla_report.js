// SLA Report — Desk filters. Any date range works (day / week / month view).
frappe.query_reports["SLA Report"] = {
	filters: [
		{
			fieldname: "customer",
			label: __("Customer"),
			fieldtype: "Link",
			options: "Customer",
			reqd: 1,
		},
		{
			fieldname: "from_date",
			label: __("From"),
			fieldtype: "Date",
			reqd: 1,
			default: frappe.datetime.month_start(),
		},
		{
			fieldname: "to_date",
			label: __("To"),
			fieldtype: "Date",
			reqd: 1,
			default: frappe.datetime.month_end(),
		},
	],
	formatter: function (value, row, column, data, default_formatter) {
		value = default_formatter(value, row, column, data);
		if (column.fieldname === "uptime_pct" && data && data.uptime_pct != null) {
			const color = data.uptime_pct >= data.target ? "#10B981" : "#EF4444";
			value = `<span style="font-weight:700;color:${color}">${value}</span>`;
		}
		return value;
	},
};
