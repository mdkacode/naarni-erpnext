// Alert Event list view: encode severity by row colour (so the Severity column can
// be hidden). The indicator pill shows the Status text, coloured by severity —
// red = critical, orange = warning.
frappe.listview_settings["Alert Event"] = {
	add_fields: ["severity", "status"],
	get_indicator(doc) {
		const color = doc.severity === "critical" ? "red" : "orange";
		const label = doc.status || "Open";
		return [label, color, "status,=," + (doc.status || "Open")];
	},
};
