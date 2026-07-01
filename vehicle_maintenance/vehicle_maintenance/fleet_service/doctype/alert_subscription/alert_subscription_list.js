// List view for Alert Subscription — colour each row by how serious the alert is
// (fetched from the linked Alert Type) so criticality is readable at a glance.
frappe.listview_settings["Alert Subscription"] = {
	add_fields: ["severity", "enabled"],
	get_indicator(doc) {
		if (doc.severity === "critical") {
			return [__("Critical"), "red", "severity,=,critical"];
		}
		if (doc.severity === "warning") {
			return [__("Warning"), "orange", "severity,=,warning"];
		}
		return [__("—"), "gray", "severity,=,"];
	},
};
