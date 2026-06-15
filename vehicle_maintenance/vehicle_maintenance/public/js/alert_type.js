// Alert Type form: adapt the value input to the selected Telemetry Parameter.
//   Numeric      -> show Default Threshold (number), all operators
//   Categorical  -> Match Value becomes a dropdown of the parameter's allowed values, op limited to == / !=
//   Boolean      -> Match Value dropdown of true / false, op limited to == / !=
frappe.ui.form.on("Alert Type", {
	parameter(frm) {
		_apply_param_type(frm);
	},
	refresh(frm) {
		_apply_param_type(frm);
	},
});

function _apply_param_type(frm) {
	const param = frm.doc.parameter;
	if (!param) {
		return;
	}
	frappe.db.get_value("Telemetry Parameter", param, ["data_type", "allowed_values", "unit"]).then((r) => {
		const d = (r && r.message) || {};
		frm.set_value("param_type", d.data_type || "Numeric");
		if (d.unit) {
			frm.set_value("unit", d.unit);
		}
		const categorical = d.data_type === "Categorical" || d.data_type === "Boolean";
		if (categorical) {
			const opts = (d.allowed_values || "")
				.split("\n")
				.map((s) => s.trim())
				.filter(Boolean);
			frm.set_df_property("match_value", "fieldtype", "Select");
			frm.set_df_property("match_value", "options", ["", ...opts].join("\n"));
			frm.set_df_property("op", "options", "==\n!=");
			if (!["==", "!="].includes(frm.doc.op)) {
				frm.set_value("op", "==");
			}
		} else {
			frm.set_df_property("match_value", "fieldtype", "Data");
			frm.set_df_property("op", "options", ">\n>=\n<\n<=\n==\n!=");
		}
		frm.refresh_field("match_value");
		frm.refresh_field("op");
	});
}
