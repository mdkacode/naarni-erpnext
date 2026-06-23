// Alert Type form helper:
//  1) Adapt the primary value input + operators to the selected reading.
//  2) Live plain-English preview of the primary condition.
//  3) Live "Fires when ..." expression for compound conditions (AND within a group,
//     OR across groups). Conditions/units auto-fill via the child table's fetch_from.

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
	op: _render_all,
	default_threshold: _render_all,
	match_value: _render_all,
	unit: _render_all,
	alert_name: _render_all,
	icon: _render_all,
	severity: _render_all,
	refresh(frm) {
		_apply_param_type(frm);
	},
});

// Re-render the compound expression whenever a condition row changes.
frappe.ui.form.on("Alert Condition", {
	parameter: _render_expression,
	op: _render_expression,
	value: _render_expression,
	condition_group: _render_expression,
	conditions_add: _render_expression,
	conditions_remove: _render_expression,
});

// Show each display reading's placeholder token live in the grid.
frappe.ui.form.on("Alert Display Parameter", {
	parameter(frm, cdt, cdn) {
		const row = locals[cdt][cdn];
		row.placeholder = row.parameter ? "{p_" + row.parameter + "}" : "";
		frm.refresh_field("display_parameters");
	},
});

function _apply_param_type(frm) {
	const param = frm.doc.parameter;
	if (!param) {
		_render_all(frm);
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
		_render_all(frm);
	});
}

function _render_all(frm) {
	_render_preview(frm);
	_render_expression(frm);
}

function _op_words(op) {
	return String(op || "").replace(/\s*\([^)]*\)\s*$/, "");
}

function _primary_clause(frm) {
	if (!frm.doc.parameter || !frm.doc.op) return null;
	const categorical = frm.doc.param_type === "Categorical" || frm.doc.param_type === "Boolean";
	const value = categorical ? frm.doc.match_value : frm.doc.default_threshold;
	if (value === undefined || value === null || value === "") return null;
	const unit = !categorical && frm.doc.unit ? frm.doc.unit : "";
	return `${frm.doc.parameter} ${_op_words(frm.doc.op)} ${value}${unit}`;
}

function _render_preview(frm) {
	const wrapper = frm.get_field("rule_preview") && frm.get_field("rule_preview").$wrapper;
	if (!wrapper) return;
	const clause = _primary_clause(frm);
	if (!clause) {
		wrapper.html(
			"<div style='color:#888;font-size:13px'>Fill in the steps above to see a preview of when this alert fires.</div>"
		);
		return;
	}
	const icon = frm.doc.icon || (frm.doc.severity === "critical" ? "🔴" : "🟠");
	wrapper.html(`
		<div style="border:1px solid #cfe3d0;background:#f3faf4;border-radius:10px;padding:12px 14px">
			<div style="font-size:12px;text-transform:uppercase;letter-spacing:.04em;color:#5b8a63;margin-bottom:4px">Preview · this alert fires when</div>
			<div style="font-size:15px;color:#1f3d24;line-height:1.5">${frappe.utils.escape_html(
				icon
			)} <b>${frappe.utils.escape_html(clause)}</b></div>
			<div style="font-size:12px;color:#6b7d70;margin-top:6px">The notification always shows the vehicle number and live position 📍</div>
		</div>`);
}

function _render_expression(frm) {
	const wrapper = frm.get_field("expression_preview") && frm.get_field("expression_preview").$wrapper;
	if (!wrapper) return;
	const rows = frm.doc.conditions || [];
	if (!rows.length) {
		wrapper.html(
			"<div style='color:#888;font-size:13px'>Single condition. Add rows above to combine readings with AND / OR.</div>"
		);
		return;
	}
	const groups = {};
	const primary = _primary_clause(frm);
	if (primary) (groups[1] = groups[1] || []).push(primary);
	rows.forEach((r) => {
		if (!r.parameter || !r.op || r.value === undefined || r.value === null || r.value === "") return;
		const g = r.condition_group || 1;
		(groups[g] = groups[g] || []).push(`${r.parameter} ${_op_words(r.op)} ${r.value}${r.unit || ""}`);
	});
	const groupStrs = Object.keys(groups)
		.sort((a, b) => a - b)
		.map((g) => (groups[g].length > 1 ? "(" + groups[g].join(" AND ") + ")" : groups[g][0]));
	if (!groupStrs.length) {
		wrapper.html(
			"<div style='color:#888;font-size:13px'>Add condition values to see the expression.</div>"
		);
		return;
	}
	const expr = groupStrs.join("&nbsp;&nbsp;<b style='color:#b54708'>OR</b>&nbsp;&nbsp;");
	wrapper.html(`
		<div style="border:1px solid #f1d9b5;background:#fffaf2;border-radius:10px;padding:12px 14px">
			<div style="font-size:12px;text-transform:uppercase;letter-spacing:.04em;color:#9a6b22;margin-bottom:4px">Trigger expression · fires when</div>
			<div style="font-size:14px;color:#5b3d12;line-height:1.6">${expr}</div>
			<div style="font-size:12px;color:#9a8060;margin-top:6px">Same group = AND · different groups = OR</div>
		</div>`);
}
