/**
 * Job Card Client Script
 * Per PRD: Maximum auto-fill, minimal manual input.
 *
 * Flow when SE creates a Job Card:
 *   1. Select Job Card Type → service_type + priority auto-set
 *   2. Select Vehicle (searchable dropdown) → customer, OEM, model auto-fill
 *   3. Enter/capture Odometer → check_sheet auto-selected (PMS only)
 *   4. Customer auto-fills service_contract
 *   5. SE records VOC (driver complaints) + own observations
 *   6. Depot Manager assigns SE + Technician (permlevel 1)
 */

frappe.ui.form.on("Job Card", {
	setup(frm) {
		// ── Link field filters ──

		// Vehicle: searchable by reg number or model
		frm.set_query("vehicle", function () {
			return {
				filters: { fuel_type: "Electric" }, // NaArNi fleet is all-electric
			};
		});

		// SE: only users with Service Engineer role
		frm.set_query("assigned_service_engineer", function () {
			return {
				query: "vehicle_maintenance.api.job_card.get_users_by_role",
				filters: { role: "Service Engineer" },
			};
		});

		// Technician: only users with Technician role
		frm.set_query("assigned_technician", function () {
			return {
				query: "vehicle_maintenance.api.job_card.get_users_by_role",
				filters: { role: "Technician" },
			};
		});

		// Service Contract: filtered to selected customer
		frm.set_query("service_contract", function () {
			return {
				filters: { customer: frm.doc.customer || "" },
			};
		});

		// Part Group on child tables
		frm.set_query("part_group", "repair_items", function () {
			return {}; // all part groups available
		});
	},

	refresh(frm) {
		// ── Onboarding / save-bar guidance banner ──
		// Frappe replaces "Save" with workflow action buttons whenever a workflow
		// is attached. New users mistake this for "no save button". Show an
		// intro_msg explaining what to click + (when Open) a deep link into the
		// Vue onboarding wizard which is where most edits happen anyway.
		if (!frm.is_new()) {
			const state = frm.doc.workflow_state;
			if (state === "Open" || state === "Reopened") {
				const url = `/service-portal/job-card/${encodeURIComponent(frm.doc.name)}/onboarding`;
				frm.dashboard.set_headline_alert(
					__("Onboarding pending: assign, fill the checklist, and upload Before-Images. ") +
						`<a href="${url}" target="_blank" style="font-weight:600;">Open onboarding wizard →</a>`,
					"orange"
				);
			} else if (state) {
				frm.set_intro(
					__(
						"Tip: workflow action buttons (top-right of the form) save the doc and advance the state. " +
							"Field edits without a state change save with the standard Save shortcut (Ctrl/Cmd+S)."
					),
					"blue"
				);
			}
		}

		// Format vehicle number display in Indian format (XX-00-XX-0000)
		if (frm.doc.vehicle_number) {
			let formatted = formatIndianVehicleNumber(frm.doc.vehicle_number);
			frm.fields_dict.vehicle_number.$wrapper
				.find(".like-disabled-input, .control-value")
				.css({ "font-weight": "bold", "font-size": "16px", "letter-spacing": "1px" })
				.text(formatted);
		}

		// Show QR code prominently if it exists
		if (frm.doc.qr_code_image && !frm.is_new()) {
			frm.sidebar &&
				frm.sidebar.add_user_action &&
				frm.sidebar.add_user_action(__("Print QR Code")).on("click", function () {
					let w = window.open("");
					w.document.write(
						'<img src="' +
							frm.doc.qr_code_image +
							'" style="width:300px;margin:40px auto;display:block;" />' +
							'<p style="text-align:center;font-size:18px;font-weight:bold;">' +
							frm.doc.name +
							"</p>"
					);
					w.print();
				});
		}
	},

	// ── Job Card Type → auto-set service_type + priority ──
	job_card_type(frm) {
		const typeMap = {
			"PMS + Repair": { service_type: "Scheduled Maintenance", priority: "Medium" },
			"Only Repair": { service_type: "General Inspection", priority: "Medium" },
			"Software Update": { service_type: "Software Update", priority: "Low" },
			Breakdown: { service_type: "Breakdown Repair", priority: "Urgent" },
		};
		let config = typeMap[frm.doc.job_card_type];
		if (config) {
			frm.set_value("service_type", config.service_type);
			frm.set_value("priority", config.priority);
		}
		autoSelectCheckSheet(frm);
	},

	// ── Vehicle selected → auto-fill customer, model, OEM, service contract ──
	vehicle(frm) {
		if (!frm.doc.vehicle) return;
		frappe.db
			.get_value("Vehicle", frm.doc.vehicle, ["customer", "make_model", "registration_number"])
			.then(function (r) {
				if (!r.message) return;
				if (r.message.customer) {
					frm.set_value("customer", r.message.customer);
				}
			});
	},

	// ── Customer selected → auto-fill service contract ──
	customer(frm) {
		if (!frm.doc.customer) return;
		frappe.call({
			method: "frappe.client.get_list",
			args: {
				doctype: "Service Contract",
				filters: { customer: frm.doc.customer },
				fields: ["name"],
				limit_page_length: 1,
				order_by: "end_date desc",
			},
			callback: function (r) {
				if (r.message && r.message.length) {
					frm.set_value("service_contract", r.message[0].name);
				}
			},
		});
	},

	// ── Odometer → auto-select check sheet ──
	odometer_reading(frm) {
		autoSelectCheckSheet(frm);
	},
});

// ── Repair Items child table: auto-compute amount ──
frappe.ui.form.on("Job Card Repair Item", {
	qty(frm, cdt, cdn) {
		computeItemAmount(frm, cdt, cdn);
	},
	rate(frm, cdt, cdn) {
		computeItemAmount(frm, cdt, cdn);
	},
});

frappe.ui.form.on("Job Card Maintenance Item", {
	qty(frm, cdt, cdn) {
		computeItemAmount(frm, cdt, cdn);
	},
	rate(frm, cdt, cdn) {
		computeItemAmount(frm, cdt, cdn);
	},
});

// ── Helper: auto-compute estimated_amount = qty * rate ──
function computeItemAmount(frm, cdt, cdn) {
	let row = locals[cdt][cdn];
	let amount = (row.qty || 0) * (row.rate || 0);
	frappe.model.set_value(cdt, cdn, "estimated_amount", amount);
}

// ── Helper: auto-select PMS check sheet from odometer ──
function autoSelectCheckSheet(frm) {
	if (frm.doc.job_card_type !== "PMS + Repair") {
		frm.set_value("check_sheet", "");
		return;
	}
	let km = frm.doc.odometer_reading || 0;
	if (km <= 15000) {
		frm.set_value("check_sheet", "Sheet A (0-15,000 km)");
	} else if (km <= 60000) {
		frm.set_value("check_sheet", "Sheet B (15,001-60,000 km)");
	} else {
		frm.set_value("check_sheet", "Sheet C (60,001+ km)");
	}
}

// ── Helper: format Indian vehicle registration number ──
// Input: DL01EB1001 or DL-01-EB-1001 → Output: DL 01 EB 1001
function formatIndianVehicleNumber(num) {
	if (!num) return "";
	// Remove existing separators
	let clean = num.replace(/[-\s]/g, "").toUpperCase();
	// Match Indian format: XX 00 XX 0000
	let match = clean.match(/^([A-Z]{2})(\d{1,2})([A-Z]{1,3})(\d{1,4})$/);
	if (match) {
		return match[1] + " " + match[2].padStart(2, "0") + " " + match[3] + " " + match[4].padStart(4, "0");
	}
	return num;
}
