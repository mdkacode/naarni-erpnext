// Authoring UX for a Duty Roster.
//
// A month of duty for eight engineers is ~240 rows. Nobody types that, and a
// roster that is painful to author is a roster that never gets published — at
// which point the app shows no shifts and the whole module is dead weight. So
// the real feature on this form is **Fill Roster**: pick people, pick a shift,
// pick which weekdays, and the grid is generated.
//
// Publish is separate and deliberate: Draft is invisible to engineers, so a
// half-built plan never reaches a phone.

frappe.ui.form.on("Duty Roster", {
	refresh(frm) {
		frm.dashboard.clear_headline();

		if (frm.doc.status === "Published") {
			frm.dashboard.set_headline(
				__("Published — visible to {0} engineer(s) in the app. Edits go live immediately.", [
					new Set((frm.doc.entries || []).map((r) => r.engineer)).size,
				])
			);
		} else if (frm.doc.status === "Draft") {
			frm.dashboard.set_headline(
				__(
					"Draft — engineers cannot see these shifts yet. Use <b>Publish</b> when the plan is ready."
				)
			);
		}

		if (frm.doc.__islocal) return;

		frm.add_custom_button(__("Fill Roster"), () => fill_dialog(frm)).addClass(
			frm.doc.entries && frm.doc.entries.length ? "" : "btn-primary"
		);

		if (frm.doc.entries && frm.doc.entries.length) {
			frm.add_custom_button(__("Coverage"), () => show_coverage(frm));
		}

		if (frm.doc.status === "Draft") {
			frm.add_custom_button(__("Publish"), () => confirm_publish(frm)).addClass("btn-primary");
		}
		if (frm.doc.status === "Published") {
			frm.add_custom_button(__("Archive"), () => set_status(frm, "Archived"), __("Actions"));
		}
	},

	depot(frm) {
		// Engineers are picked from the depot's roll, so switching depot mid-edit
		// would silently leave the wrong people on the grid.
		if ((frm.doc.entries || []).length) {
			frappe.show_alert({
				message: __("Depot changed — check the existing rows still belong here."),
				indicator: "orange",
			});
		}
	},
});

// Weekday numbering matches Python's: Monday = 0.
const WEEKDAYS = [
	{ value: 0, label: __("Mon") },
	{ value: 1, label: __("Tue") },
	{ value: 2, label: __("Wed") },
	{ value: 3, label: __("Thu") },
	{ value: 4, label: __("Fri") },
	{ value: 5, label: __("Sat") },
	{ value: 6, label: __("Sun") },
];

function fill_dialog(frm) {
	if (!frm.doc.depot) {
		frappe.msgprint(__("Pick a depot first — engineers are chosen from its roll."));
		return;
	}

	frappe.call({
		method: "vehicle_maintenance.api.roster.get_depot_engineers",
		args: { depot: frm.doc.depot },
		freeze: true,
		callback(r) {
			const engineers = (r.message && r.message.data && r.message.data.engineers) || [];
			if (!engineers.length) {
				frappe.msgprint({
					title: __("No engineers at this depot"),
					message: __(
						"Add Service Engineers under <b>Assigned Service Engineers</b> on the {0} depot record first.",
						[frappe.utils.escape_html(frm.doc.depot)]
					),
					indicator: "orange",
				});
				return;
			}
			open_fill(frm, engineers);
		},
	});
}

function open_fill(frm, engineers) {
	const dialog = new frappe.ui.Dialog({
		title: __("Fill roster: {0} to {1}", [
			frappe.datetime.str_to_user(frm.doc.from_date),
			frappe.datetime.str_to_user(frm.doc.to_date),
		]),
		size: "large",
		fields: [
			{
				fieldname: "engineers",
				label: __("Engineers"),
				fieldtype: "MultiSelectPills",
				reqd: 1,
				get_data: () => engineers.map((e) => ({ value: e.user, description: e.full_name })),
			},
			{
				fieldname: "shift",
				label: __("Shift"),
				fieldtype: "Link",
				options: "Duty Shift",
				get_query: () => ({ filters: { is_active: 1 } }),
				description: __("Leave blank only if every generated day is a week off."),
			},
			{ fieldname: "cb", fieldtype: "Column Break" },
			{
				fieldname: "weekdays",
				label: __("Duty Days"),
				fieldtype: "MultiSelectPills",
				default: [0, 1, 2, 3, 4, 5],
				get_data: () => WEEKDAYS.map((d) => ({ value: String(d.value), description: d.label })),
				description: __("Mon = 0 … Sun = 6. Leave blank for all seven days."),
			},
			{
				fieldname: "week_off_days",
				label: __("Week Off Days"),
				fieldtype: "MultiSelectPills",
				default: [6],
				get_data: () => WEEKDAYS.map((d) => ({ value: String(d.value), description: d.label })),
				description: __(
					"Generated as a Week Off row rather than left blank, so the engineer sees 'Week off' instead of an empty day."
				),
			},
			{ fieldname: "sb", fieldtype: "Section Break" },
			{
				fieldname: "replace",
				label: __("Replace existing rows"),
				fieldtype: "Check",
				description: __("Off: existing rows are kept and only missing days are added."),
			},
		],
		primary_action_label: __("Generate"),
		primary_action(values) {
			dialog.hide();
			frappe.call({
				method: "vehicle_maintenance.api.roster.fill_roster",
				args: {
					roster: frm.doc.name,
					engineers: values.engineers || [],
					shift: values.shift || null,
					weekdays: (values.weekdays || []).map(Number),
					week_off_days: (values.week_off_days || []).map(Number),
					replace: values.replace ? 1 : 0,
				},
				freeze: true,
				freeze_message: __("Generating duty days…"),
				callback(r) {
					if (!r.message) return;
					frappe.show_alert({ message: r.message.message, indicator: "green" });
					frm.reload_doc();
				},
			});
		},
	});

	// Week-off days must not also be duty days — the server would generate a week
	// off and skip the shift, which reads as a bug rather than a choice.
	dialog.fields_dict.week_off_days.$wrapper.on("change", () => {
		const off = (dialog.get_value("week_off_days") || []).map(String);
		const duty = (dialog.get_value("weekdays") || []).map(String).filter((d) => !off.includes(d));
		dialog.set_value("weekdays", duty);
	});

	dialog.show();
}

function show_coverage(frm) {
	frappe.call({
		method: "vehicle_maintenance.api.roster.get_roster_coverage",
		args: { roster: frm.doc.name },
		freeze: true,
		callback(r) {
			const rows = (r.message && r.message.data && r.message.data.coverage) || [];
			const thin = rows.filter((d) => d.on_duty === 0);
			const body = rows
				.map((d) => {
					const tone = d.on_duty === 0 ? "red" : d.on_duty < 2 ? "orange" : "green";
					return `<tr><td>${frappe.datetime.str_to_user(d.date)}</td>
						<td><span class="indicator ${tone}">${d.on_duty}</span></td></tr>`;
				})
				.join("");

			frappe.msgprint({
				title: __("Coverage"),
				indicator: thin.length ? "orange" : "green",
				message: `${
					thin.length
						? `<p>${__("{0} day(s) have nobody on duty.", [thin.length])}</p>`
						: `<p>${__("Every day has at least one engineer on duty.")}</p>`
				}
				<table class="table table-bordered">
					<thead><tr><th>${__("Date")}</th><th>${__("On duty")}</th></tr></thead>
					<tbody>${body}</tbody>
				</table>`,
			});
		},
	});
}

function confirm_publish(frm) {
	const count = (frm.doc.entries || []).length;
	if (!count) {
		frappe.msgprint(__("Add some duty days first — use <b>Fill Roster</b>."));
		return;
	}
	frappe.confirm(
		__("Publish {0} duty day(s)? They appear in the engineers' app immediately.", [count]),
		() => {
			frappe.call({
				method: "vehicle_maintenance.api.roster.publish_roster",
				args: { roster: frm.doc.name },
				freeze: true,
				freeze_message: __("Publishing…"),
				callback(r) {
					if (!r.message) return;
					frappe.show_alert({ message: r.message.message, indicator: "green" });
					frm.reload_doc();
				},
			});
		}
	);
}

function set_status(frm, status) {
	frappe.confirm(__("Archive this roster? Its shifts stop showing in the app."), () => {
		frappe.call({
			method: "frappe.client.set_value",
			args: { doctype: "Duty Roster", name: frm.doc.name, fieldname: "status", value: status },
			callback: () => frm.reload_doc(),
		});
	});
}
