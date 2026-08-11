// Authoring UX for a Process Definition.
//
// The whole admin surface is standard Frappe Desk plus these four buttons —
// deliberately, because Desk already gives us versioning, audit trail, search,
// permissions and import/export for free. Building a custom process builder
// would mean rebuilding all of that.
//
// Check → Publish is the intended flow: the linter catches the mistakes an
// authoring UI produces constantly (a condition pointing at a step answered
// later, a Choice with no options), and publishing refuses on any Error.

frappe.ui.form.on("Process Definition", {
	refresh(frm) {
		frm.dashboard.clear_headline();

		if (frm.doc.status === "Published") {
			frm.dashboard.set_headline(
				__("Published v{0} — frozen. Use <b>Clone to New Version</b> to make changes.", [
					frm.doc.version,
				])
			);
		} else if (frm.doc.status === "Retired") {
			frm.dashboard.set_headline(__("Retired. Kept so existing runs stay reproducible."));
		}

		if (frm.doc.__islocal) return;

		frm.add_custom_button(__("Check for Problems"), () => run_lint(frm));

		if (frm.doc.status === "Draft") {
			frm.add_custom_button(__("Publish"), () => confirm_publish(frm)).addClass("btn-primary");
			frm.add_custom_button(__("Preview as Operator"), () => preview(frm));
		}

		if (frm.doc.status === "Published") {
			frm.add_custom_button(__("Clone to New Version"), () => clone(frm)).addClass("btn-primary");
			frm.add_custom_button(__("Preview as Operator"), () => preview(frm));
		}

		if (frm.doc.status !== "Retired" && frappe.user.has_role("System Manager")) {
			frm.add_custom_button(__("Retire"), () => retire(frm), __("Actions"));
		}
	},

	scoring_enabled(frm) {
		if (frm.doc.scoring_enabled && !frm.doc.pass_threshold_pct) {
			frm.set_value("pass_threshold_pct", 90);
		}
	},
});

function run_lint(frm) {
	frappe.call({
		method: "vehicle_maintenance.api.process.lint_definition",
		args: { process: frm.doc.name },
		freeze: true,
		freeze_message: __("Checking…"),
		callback(r) {
			const issues = (r.message && r.message.data && r.message.data.issues) || [];
			if (!issues.length) {
				frappe.msgprint({
					title: __("No problems found"),
					message: __("This process is ready to publish."),
					indicator: "green",
				});
				return;
			}
			const rows = issues
				.map(
					(i) =>
						`<li><span class="indicator ${i.severity === "Error" ? "red" : "orange"}">${
							i.severity
						}</span> ${frappe.utils.escape_html(i.message)}</li>`
				)
				.join("");
			frappe.msgprint({
				title: __("{0} issue(s) found", [issues.length]),
				message: `<ul style="padding-left:1.2em">${rows}</ul>
					<p class="text-muted small">${__("Errors block publishing. Warnings do not.")}</p>`,
				indicator: issues.some((i) => i.severity === "Error") ? "red" : "orange",
			});
		},
	});
}

function confirm_publish(frm) {
	frappe.confirm(
		__(
			"Publish v{0}? It becomes the live version for every operator, and can no longer be edited — future changes go into a new version.",
			[frm.doc.version]
		),
		() => {
			frappe.call({
				method: "vehicle_maintenance.api.process.publish_definition",
				args: { process: frm.doc.name },
				freeze: true,
				freeze_message: __("Publishing…"),
				callback(r) {
					if (r.message) {
						frappe.show_alert({ message: r.message.message, indicator: "green" });
						frm.reload_doc();
					}
				},
			});
		}
	);
}

function clone(frm) {
	frappe.call({
		method: "vehicle_maintenance.api.process.clone_definition",
		args: { process: frm.doc.name },
		freeze: true,
		callback(r) {
			const name = r.message && r.message.data && r.message.data.name;
			if (name) frappe.set_route("Form", "Process Definition", name);
		},
	});
}

function retire(frm) {
	frappe.confirm(__("Retire this process? Operators will no longer see it."), () => {
		frappe.call({
			method: "frappe.client.set_value",
			args: {
				doctype: "Process Definition",
				name: frm.doc.name,
				fieldname: "status",
				value: "Retired",
			},
			callback: () => frm.reload_doc(),
		});
	});
}

// Renders the operator's view read-only, grouped exactly as the app will group
// it — by stage, then by section. An author should never have to guess what
// their change looks like on the floor.
function preview(frm) {
	const stages = (frm.doc.stages || []).slice().sort((a, b) => (a.sequence || 0) - (b.sequence || 0));
	const steps = (frm.doc.steps || []).slice().sort((a, b) => (a.sequence || 0) - (b.sequence || 0));
	const word = frm.doc.stage_label || __("Stage");
	const esc = frappe.utils.escape_html;

	let html = "";
	stages.forEach((stage) => {
		const mine = steps.filter((s) => s.stage === stage.stage_code && s.is_active !== 0);
		html += `<h5 style="margin-top:18px">${esc(word)}: ${esc(stage.label || stage.stage_code)}
			<span class="text-muted small">(${mine.length} ${__("checks")})</span></h5>`;

		const sections = [];
		mine.forEach((s) => {
			const key = s.section || __("Checks");
			let bucket = sections.find((x) => x.key === key);
			if (!bucket) sections.push((bucket = { key, items: [] }));
			bucket.items.push(s);
		});

		sections.forEach((section, idx) => {
			html += `<div style="border:1px solid var(--border-color);border-radius:8px;padding:10px 12px;margin:8px 0">
				<div class="text-muted small" style="letter-spacing:.08em;text-transform:uppercase">
					${__("Screen")} ${idx + 1} — ${esc(section.key)}</div>`;
			section.items.forEach((s) => {
				const badges = [];
				if (s.is_critical) badges.push(`<span class="indicator red">${__("Critical")}</span>`);
				if (s.requires_photo) badges.push(`<span class="indicator blue">${__("Photo")}</span>`);
				if (s.requires_scan) badges.push(`<span class="indicator purple">${__("Scan")}</span>`);
				if (s.is_mandatory) badges.push(`<span class="indicator orange">${__("Mandatory")}</span>`);
				const opts = (s.options || []).map((o) => esc(o.label || o.value)).join(" · ");
				html += `<div style="padding:6px 0;border-top:1px solid var(--border-color)">
					<b>${esc(s.display_no || "")}</b> ${esc(s.label || "")}
					${s.label_alt ? `<div class="text-muted small">${esc(s.label_alt)}</div>` : ""}
					<div class="small text-muted">${esc(s.response_type)}${opts ? " — " + opts : ""}</div>
					<div>${badges.join(" ")}</div>
				</div>`;
			});
			html += "</div>";
		});
	});

	new frappe.ui.Dialog({
		title: __("Operator preview — {0}", [frm.doc.process_name]),
		size: "large",
		fields: [{ fieldtype: "HTML", options: html || __("No steps yet.") }],
	}).show();
}
