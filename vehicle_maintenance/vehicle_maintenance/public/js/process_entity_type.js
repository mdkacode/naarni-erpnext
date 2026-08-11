// Test a QR pattern against a real payload before it reaches the station.
//
// Label formats change without warning, so patterns live in config rather than
// code. This button closes the loop: paste a scan, see exactly what the parser
// would extract, fix the regex, repeat.

frappe.ui.form.on("Process Entity Type", {
	refresh(frm) {
		frm.add_custom_button(__("Test Pattern"), () => test_pattern(frm));

		frm.dashboard.clear_headline();
		if (!frm.doc.qr_pattern) {
			frm.dashboard.set_headline(
				__(
					"No pattern set. Scans will still be saved with their raw payload as the serial — nothing is ever rejected."
				)
			);
		}
	},
});

function test_pattern(frm) {
	const dialog = new frappe.ui.Dialog({
		title: __("Test QR pattern"),
		fields: [
			{
				fieldname: "payload",
				label: __("Scanned payload"),
				fieldtype: "Data",
				reqd: 1,
				default: frm.doc.sample_payload,
				description: __("Paste a real label value from the line."),
			},
			{ fieldname: "out", fieldtype: "HTML" },
		],
		primary_action_label: __("Parse"),
		primary_action(values) {
			frappe.call({
				method: "vehicle_maintenance.api.process.test_qr_pattern",
				args: { entity_type: frm.doc.name, payload: values.payload },
				callback(r) {
					const d = (r.message && r.message.data) || {};
					const esc = frappe.utils.escape_html;
					let html;
					if (d.parse_failed) {
						html = `<div class="alert alert-warning">
							${__("No pattern matched.")}<br>
							<span class="small">${__(
								"The scan would still be saved, with the raw payload as its serial, and flagged for you to add a pattern later."
							)}</span></div>`;
					} else {
						const rows = ["serial_no", "mfg_date", "module_number", "batch_ref", "revision"]
							.filter((k) => d[k])
							.map(
								(k) => `<tr><td class="text-muted">${k}</td><td><b>${esc(d[k])}</b></td></tr>`
							)
							.join("");
						html = `<div class="alert alert-success">${__("Matched.")}</div>
							<table class="table table-bordered">${rows}</table>`;
					}
					dialog.fields_dict.out.$wrapper.html(html);
				},
			});
		},
	});
	dialog.show();
}
