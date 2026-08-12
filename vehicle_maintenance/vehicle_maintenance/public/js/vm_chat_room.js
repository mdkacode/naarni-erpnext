// Copyright (c) 2026, Naarni and contributors
// For license information, please see license.txt

/**
 * Desk helpers for VM Chat Room.
 *
 * This is where an admin builds an alert channel: create a room, tick "Post
 * Alerts Here", set the depot, and pull that depot's engineers in as members in
 * one click rather than typing twenty rows into a grid.
 *
 * The banner is the important part. "Post Alerts Here" quietly turns a room
 * into a firehose, and the difference between a channel people read and one
 * they mute is whether whoever created it understood the scope they had chosen
 * at the moment they chose it.
 */
frappe.ui.form.on("VM Chat Room", {
	refresh(frm) {
		render_scope_banner(frm);

		if (!frm.is_new() && frm.doc.depot) {
			frm.add_custom_button(__("Add Depot Engineers"), () => add_depot_engineers(frm), __("Members"));
		}

		if (!frm.is_new()) {
			frm.add_custom_button(
				__("Open Thread"),
				() => frappe.set_route("List", "VM Chat Message", { room: frm.doc.name }),
				__("Members")
			);
		}
	},

	broadcast_alerts: render_scope_banner,
	depot: render_scope_banner,
	vehicle: render_scope_banner,
	alert_min_severity: render_scope_banner,
});

function render_scope_banner(frm) {
	frm.dashboard.clear_headline();
	if (!frm.doc.broadcast_alerts) return;

	const severity = frm.doc.alert_min_severity || "warning";
	let scope;
	if (frm.doc.vehicle) {
		scope = __("only vehicle {0}", [frm.doc.vehicle]);
	} else if (frm.doc.depot) {
		scope = __("every vehicle at {0}", [frm.doc.depot]);
	} else {
		scope = __("<b>the entire fleet</b>");
	}

	frm.dashboard.set_headline(
		__("Alerts of severity <b>{0}</b> and above for {1} will be posted into this room.", [
			severity,
			scope,
		]),
		frm.doc.depot || frm.doc.vehicle ? "blue" : "orange"
	);
}

function add_depot_engineers(frm) {
	frappe.call({
		method: "vehicle_maintenance.api.chat.add_depot_members",
		args: { room: frm.doc.name },
		freeze: true,
		freeze_message: __("Adding depot engineers…"),
		callback(r) {
			const added = (r.message && r.message.data && r.message.data.added) || [];
			if (!added.length) {
				frappe.show_alert({
					message: __("Everyone at this depot is already a member."),
					indicator: "blue",
				});
				return;
			}
			frappe.show_alert({
				message: __("Added {0} member(s).", [added.length]),
				indicator: "green",
			});
			frm.reload_doc();
		},
	});
}
