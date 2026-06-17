// Alert Type form helper. Two jobs:
//  1) Adapt the value input + conditions to the selected Telemetry Parameter:
//       Number       -> "…this number" (Float), all conditions
//       Category     -> "…this value" becomes a dropdown of allowed values, equality only
//       Yes-No       -> "…this value" dropdown of Yes / No, equality only
//  2) Show a live plain-English preview of when the alert fires, so a non-technical
//     user can read back exactly what they configured.

const NUMERIC_OPS = [
	"is greater than (>)",
	"is greater than or equal to (≥)",
	"is less than (<)",
	"is less than or equal to (≤)",
	"is exactly (=)",
	"is not (≠)",
];
const EQUALITY_OPS = ["is exactly (=)", "is not (≠)"];

frappe.ui.form.on("Alert Type", {
	parameter(frm) {
		_apply_param_type(frm);
	},
	op: _render_preview,
	default_threshold: _render_preview,
	match_value: _render_preview,
	unit: _render_preview,
	alert_name: _render_preview,
	icon: _render_preview,
	severity: _render_preview,
	refresh(frm) {
		_apply_param_type(frm);
	},
});

function _apply_param_type(frm) {
	const param = frm.doc.parameter;
	if (!param) {
		_render_preview(frm);
		return;
	}
	frappe.db.get_value("Telemetry Parameter", param, ["data_type", "allowed_values", "unit"]).then((r) => {
		const d = (r && r.message) || {};
		frm.set_value("param_type", d.data_type || "Numeric");
		if (d.unit && !frm.doc.unit) {
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
			frm.set_df_property("op", "options", EQUALITY_OPS.join("\n"));
			if (!EQUALITY_OPS.includes(frm.doc.op)) {
				frm.set_value("op", "is exactly (=)");
			}
		} else {
			frm.set_df_property("match_value", "fieldtype", "Data");
			frm.set_df_property("op", "options", NUMERIC_OPS.join("\n"));
		}
		frm.refresh_field("match_value");
		frm.refresh_field("op");
		_render_preview(frm);
	});
}

function _render_preview(frm) {
	const wrapper = frm.get_field("rule_preview") && frm.get_field("rule_preview").$wrapper;
	if (!wrapper) {
		return;
	}
	const reading = frm.doc.parameter;
	const cond = frm.doc.op;
	const categorical = frm.doc.param_type === "Categorical" || frm.doc.param_type === "Boolean";
	const value = categorical ? frm.doc.match_value : frm.doc.default_threshold;
	const unit = !categorical && frm.doc.unit ? frm.doc.unit : "";

	if (!reading || !cond || value === undefined || value === null || value === "") {
		wrapper.html(
			"<div style='color:#888;font-size:13px'>Fill in the steps above to see a preview of when this alert fires.</div>"
		);
		return;
	}
	const icon = frm.doc.icon || (frm.doc.severity === "critical" ? "🔴" : "🟠");
	// "is greater than (>)" -> "is greater than"
	const condWords = String(cond).replace(/\s*\([^)]*\)\s*$/, "");
	const html = `
		<div style="border:1px solid #cfe3d0;background:#f3faf4;border-radius:10px;padding:12px 14px">
			<div style="font-size:12px;text-transform:uppercase;letter-spacing:.04em;color:#5b8a63;margin-bottom:4px">Preview · this alert fires when</div>
			<div style="font-size:15px;color:#1f3d24;line-height:1.5">
				${frappe.utils.escape_html(icon)}
				<b>${frappe.utils.escape_html(reading)}</b>
				${frappe.utils.escape_html(condWords)}
				<b>${frappe.utils.escape_html(String(value))}${frappe.utils.escape_html(unit)}</b>
			</div>
			<div style="font-size:12px;color:#6b7d70;margin-top:6px">The notification always shows the vehicle number and live position 📍</div>
		</div>`;
	wrapper.html(html);
}
