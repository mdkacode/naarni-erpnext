/**
 * Inspection Review — every operator's inspections, for the people who oversee them.
 *
 * The operator's own record lives in the app (`my_history`). This is the other
 * side of that wall: it reads across operators, so it is gated to the review
 * roles both here (Page.roles) and again server-side in `_assert_inspection_admin`.
 * The page gate only hides the menu item; the server gate is the one that counts.
 *
 * Two screens in one: a filtered list, and the full report for one inspection —
 * every answer as the operator gave it, with the photos taken at each step.
 * Reading a report is the whole job, so the list is a means to it, not the point.
 */

frappe.pages["inspection-review"].on_page_load = function (wrapper) {
	const page = frappe.ui.make_app_page({
		parent: wrapper,
		title: __("Inspection Review"),
		single_column: true,
	});
	new InspectionReview(page);
};

const STATUS_TONE = {
	Passed: "pass",
	Quarantined: "fail",
	"Awaiting Verification": "warn",
	"In Progress": "info",
	"In Rework": "warn",
	Draft: "info",
	Cancelled: "muted",
};

class InspectionReview {
	constructor(page) {
		this.page = page;
		this.filters = {};
		this.offset = 0;
		this.limit = 50;
		this.render_shell();
		this.build_filters();
		this.load();
	}

	// ------------------------------------------------------------------ shell

	render_shell() {
		this.page.main.html(`
			<style>
				.ir-stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
					gap: 12px; margin-bottom: 18px; }
				.ir-stat { border: 1px solid var(--border-color); border-radius: 10px;
					padding: 14px 16px; background: var(--card-bg); }
				.ir-stat .n { font-size: 26px; font-weight: 700; line-height: 1.1;
					font-variant-numeric: tabular-nums; }
				.ir-stat .l { font-size: 11px; text-transform: uppercase; letter-spacing: .06em;
					color: var(--text-muted); margin-top: 2px; }
				.ir-n-pass { color: var(--green-600); }
				.ir-n-fail { color: var(--red-600); }
				.ir-n-warn { color: var(--orange-600); }

				.ir-table { width: 100%; border-collapse: collapse; }
				.ir-table th { text-align: left; font-size: 11px; text-transform: uppercase;
					letter-spacing: .06em; color: var(--text-muted); font-weight: 600;
					padding: 8px 10px; border-bottom: 1px solid var(--border-color); white-space: nowrap; }
				.ir-table td { padding: 10px; border-bottom: 1px solid var(--border-color);
					vertical-align: middle; }
				.ir-table tbody tr { cursor: pointer; }
				.ir-table tbody tr:hover { background: var(--bg-light-gray); }
				.ir-thumb { width: 44px; height: 44px; border-radius: 8px; object-fit: cover;
					background: var(--bg-light-gray); display: block; }
				.ir-thumb-empty { width: 44px; height: 44px; border-radius: 8px;
					background: var(--bg-light-gray); }
				.ir-id { font-weight: 600; }
				.ir-sub { color: var(--text-muted); font-size: 12px; }
				.ir-num { font-variant-numeric: tabular-nums; }

				.ir-pill { display: inline-block; padding: 2px 8px; border-radius: 999px;
					font-size: 11px; font-weight: 600; white-space: nowrap; }
				.ir-pill.pass { background: var(--green-100); color: var(--green-700); }
				.ir-pill.fail { background: var(--red-100); color: var(--red-700); }
				.ir-pill.warn { background: var(--orange-100); color: var(--orange-700); }
				.ir-pill.info { background: var(--blue-100); color: var(--blue-700); }
				.ir-pill.muted { background: var(--bg-light-gray); color: var(--text-muted); }

				.ir-empty { padding: 48px 16px; text-align: center; color: var(--text-muted); }
				.ir-scroll { overflow-x: auto; }

				/* report */
				.ir-rep-head { display: flex; flex-wrap: wrap; gap: 18px; align-items: baseline;
					padding-bottom: 12px; margin-bottom: 12px;
					border-bottom: 1px solid var(--border-color); }
				.ir-rep-mini { font-size: 12px; color: var(--text-muted); }
				.ir-rep-mini b { font-size: 15px; color: var(--text-color);
					font-variant-numeric: tabular-nums; }
				.ir-stage { font-size: 11px; text-transform: uppercase; letter-spacing: .06em;
					color: var(--text-muted); font-weight: 600; margin: 18px 0 8px; }
				.ir-step { border: 1px solid var(--border-color); border-radius: 10px;
					padding: 12px 14px; margin-bottom: 8px; }
				.ir-step-q { display: flex; gap: 8px; align-items: baseline; }
				.ir-step-no { color: var(--text-muted); font-size: 12px; min-width: 22px; }
				.ir-ans { margin-top: 6px; font-weight: 600; }
				.ir-ans.pass { color: var(--green-600); }
				.ir-ans.fail { color: var(--red-600); }
				.ir-ans.warn { color: var(--orange-600); }
				.ir-ans.muted { color: var(--text-muted); }
				.ir-spec { font-weight: 400; color: var(--text-muted); font-size: 12px;
					margin-left: 8px; }
				.ir-remark { margin-top: 4px; color: var(--text-muted); font-style: italic; }
				.ir-shots { display: flex; gap: 8px; margin-top: 10px; flex-wrap: wrap; }
				.ir-shot { width: 92px; }
				.ir-shot img { width: 92px; height: 92px; object-fit: cover; border-radius: 8px;
					display: block; cursor: zoom-in; }
				.ir-shot .meta { font-size: 10px; color: var(--text-muted); margin-top: 3px;
					line-height: 1.3; }
			</style>
			<div class="ir-stats"></div>
			<div class="ir-scroll"><table class="ir-table">
				<thead><tr>
					<th></th><th>${__("Identifier")}</th><th>${__("Process")}</th>
					<th>${__("Operator")}</th><th>${__("Status")}</th>
					<th class="text-right">${__("Score")}</th>
					<th class="text-right">${__("Pass")}</th>
					<th class="text-right">${__("Fail")}</th>
					<th class="text-right">${__("Photos")}</th>
					<th>${__("When")}</th>
				</tr></thead>
				<tbody></tbody>
			</table></div>
			<div class="ir-empty" style="display:none"></div>
			<div class="text-center" style="margin:16px 0">
				<button class="btn btn-default btn-sm ir-more" style="display:none">${__("Load more")}</button>
			</div>
		`);

		this.$stats = this.page.main.find(".ir-stats");
		this.$rows = this.page.main.find(".ir-table tbody");
		this.$empty = this.page.main.find(".ir-empty");
		this.$more = this.page.main.find(".ir-more");
		this.$more.on("click", () => {
			this.offset += this.limit;
			this.load({ append: true });
		});
	}

	build_filters() {
		const refresh = () => {
			this.offset = 0;
			this.load();
		};

		this.f_from = this.page.add_field({
			fieldname: "from_date",
			label: __("From"),
			fieldtype: "Date",
			change: refresh,
		});
		this.f_to = this.page.add_field({
			fieldname: "to_date",
			label: __("To"),
			fieldtype: "Date",
			change: refresh,
		});
		this.f_operator = this.page.add_field({
			fieldname: "operator",
			label: __("Operator"),
			fieldtype: "Link",
			options: "User",
			change: refresh,
		});
		this.f_status = this.page.add_field({
			fieldname: "status",
			label: __("Status"),
			fieldtype: "Select",
			options: [
				"",
				"Passed",
				"Quarantined",
				"Awaiting Verification",
				"In Progress",
				"In Rework",
				"Draft",
				"Cancelled",
			].join("\n"),
			change: refresh,
		});
		this.f_search = this.page.add_field({
			fieldname: "search",
			label: __("Serial / Pack"),
			fieldtype: "Data",
			change: refresh,
		});

		this.page.set_primary_action(__("Refresh"), () => {
			this.offset = 0;
			this.load();
		});
	}

	current_filters() {
		return {
			from_date: this.f_from.get_value() || undefined,
			to_date: this.f_to.get_value() || undefined,
			operator: this.f_operator.get_value() || undefined,
			status: this.f_status.get_value() || undefined,
			search: this.f_search.get_value() || undefined,
		};
	}

	// ------------------------------------------------------------------- data

	load({ append = false } = {}) {
		frappe.call({
			method: "vehicle_maintenance.api.process.inspections",
			args: { ...this.current_filters(), limit: this.limit, offset: this.offset },
			freeze: !append,
			freeze_message: __("Loading inspections…"),
			callback: (r) => {
				const data = (r.message || {}).data || {};
				this.render_stats(data.stats || {});
				this.render_rows(data.runs || [], append);
			},
		});
	}

	render_stats(s) {
		const tiles = [
			[__("Inspections"), s.total || 0, ""],
			[__("Passed"), s.passed || 0, "ir-n-pass"],
			[__("Quarantined"), s.quarantined || 0, "ir-n-fail"],
			[__("Awaiting review"), s.awaiting || 0, "ir-n-warn"],
			[__("In progress"), s.in_progress || 0, "ir-n-warn"],
		];
		this.$stats.html(
			tiles
				.map(
					([label, n, cls]) => `
			<div class="ir-stat">
				<div class="n ${cls}">${n}</div>
				<div class="l">${frappe.utils.escape_html(label)}</div>
			</div>`
				)
				.join("")
		);
	}

	render_rows(runs, append) {
		if (!append) this.$rows.empty();

		if (!runs.length && !append) {
			this.$empty.text(__("No inspections match these filters.")).show();
			this.$more.hide();
			return;
		}
		this.$empty.hide();

		runs.forEach((run) => {
			const tone = STATUS_TONE[run.status] || "muted";
			const thumb = run.thumb
				? `<img class="ir-thumb" src="${frappe.utils.escape_html(run.thumb)}" loading="lazy">`
				: `<div class="ir-thumb-empty"></div>`;
			const when = (run.completed_at || run.started_at || "").slice(0, 16).replace("T", " ");

			const $tr = $(`
				<tr>
					<td>${thumb}</td>
					<td>
						<div class="ir-id">${frappe.utils.escape_html(run.run_identifier || run.name)}</div>
						<div class="ir-sub">${frappe.utils.escape_html(run.name)}</div>
					</td>
					<td>${frappe.utils.escape_html(run.process_name || "")}</td>
					<td>${frappe.utils.escape_html(run.started_by_name || run.started_by || "")}</td>
					<td><span class="ir-pill ${tone}">${frappe.utils.escape_html(run.status)}</span></td>
					<td class="text-right ir-num">${Math.round(run.score_pct || 0)}%</td>
					<td class="text-right ir-num">${run.pass_count || 0}</td>
					<td class="text-right ir-num">${run.fail_count || 0}</td>
					<td class="text-right ir-num">${run.photo_count || 0}</td>
					<td class="ir-sub">${frappe.utils.escape_html(when)}</td>
				</tr>
			`);
			$tr.on("click", () => this.open_report(run.name));
			this.$rows.append($tr);
		});

		// Only offer "load more" when the page came back full — a short page is
		// the end of the list, and a button that returns nothing reads as broken.
		this.$more.toggle(runs.length === this.limit);
	}

	// ----------------------------------------------------------------- report

	open_report(name) {
		frappe.call({
			method: "vehicle_maintenance.api.process.run_report",
			args: { name },
			freeze: true,
			freeze_message: __("Opening inspection…"),
			callback: (r) => {
				const rep = (r.message || {}).data;
				if (!rep) return;
				const d = new frappe.ui.Dialog({
					title: rep.run_identifier || rep.name,
					size: "large",
				});
				d.$body.html(this.report_html(rep));
				d.$body.on("click", ".ir-shot img", (e) => {
					// Full size in a new tab rather than a nested dialog: the
					// stamp is small on a 92px thumbnail and the serial is the
					// whole reason the photo is worth opening.
					window.open($(e.currentTarget).data("full"), "_blank", "noopener");
				});
				d.show();
			},
		});
	}

	report_html(rep) {
		const esc = frappe.utils.escape_html;
		const tone = STATUS_TONE[rep.status] || "muted";

		const head = `
			<div class="ir-rep-head">
				<span class="ir-pill ${tone}">${esc(rep.status)}</span>
				<div class="ir-rep-mini"><b>${Math.round(rep.score_pct || 0)}%</b> ${__("score")}</div>
				<div class="ir-rep-mini"><b>${rep.pass_count || 0}</b> ${__("passed")}</div>
				<div class="ir-rep-mini"><b>${rep.fail_count || 0}</b> ${__("failed")}</div>
				<div class="ir-rep-mini"><b>${rep.skip_count || 0}</b> ${__("skipped")}</div>
				<div class="ir-rep-mini"><b>${rep.photo_count || 0}</b> ${__("photos")}</div>
				<div class="ir-rep-mini" style="flex:1; text-align:right">
					${esc(rep.started_by_name || rep.started_by || "")}
					· ${esc((rep.completed_at || rep.started_at || "").slice(0, 16).replace("T", " "))}
					${rep.station ? "· " + esc(rep.station) : ""}
				</div>
			</div>
			${
				rep.quarantine_reason
					? `<div class="ir-ans fail" style="margin-bottom:12px">${esc(
							rep.quarantine_reason
					  )}</div>`
					: ""
			}
		`;

		const stages = (rep.stages || [])
			.map(
				(st) => `
			<div class="ir-stage">${esc(st.label || st.stage)}</div>
			${(st.steps || []).map((s) => this.step_html(s)).join("")}
		`
			)
			.join("");

		const orphans = (rep.unmatched_photos || []).length
			? `<div class="ir-stage">${__("Other photos")}</div>` +
			  rep.unmatched_photos
					.map(
						(g) => `<div class="ir-step">
							<div class="ir-step-q">${esc(g.label || g.step_code)}</div>
							${this.shots_html(g.photos)}
						</div>`
					)
					.join("")
			: "";

		return head + stages + orphans;
	}

	step_html(s) {
		const esc = frappe.utils.escape_html;
		const answered =
			s.is_skipped ||
			s.response ||
			s.value_text ||
			(s.value_numeric !== null &&
				s.value_numeric !== undefined &&
				["Number", "Number in Range", "Number with Tolerance", "Computed"].includes(s.response_type));

		let tone = "muted";
		let answer = __("Not answered");
		if (s.is_skipped) {
			tone = "warn";
			answer = `${__("Skipped")} — ${s.skip_reason || __("no reason given")}`;
		} else if (answered) {
			tone = s.is_pass ? "pass" : "fail";
			if (s.response) {
				answer = s.response;
			} else if (s.value_text) {
				answer = s.value_text;
			} else {
				const n = s.value_numeric;
				answer = (n % 1 === 0 ? String(n) : String(n)) + (s.unit ? " " + s.unit : "");
			}
		}

		return `
			<div class="ir-step">
				<div class="ir-step-q">
					<span class="ir-step-no">${esc(s.display_no || "")}</span>
					<span>${esc(s.label || s.step_code)}</span>
				</div>
				<div class="ir-ans ${tone}">
					${esc(answer)}
					${s.spec_summary ? `<span class="ir-spec">${esc(s.spec_summary)}</span>` : ""}
					${s.is_critical ? `<span class="ir-pill fail" style="margin-left:8px">${__("Critical")}</span>` : ""}
				</div>
				${s.remark ? `<div class="ir-remark">“${esc(s.remark)}”</div>` : ""}
				${this.shots_html(s.photos)}
			</div>
		`;
	}

	shots_html(photos) {
		if (!photos || !photos.length) return "";
		const esc = frappe.utils.escape_html;
		return `<div class="ir-shots">${photos
			.map((p) => {
				const where =
					p.latitude != null && p.longitude != null
						? `${Number(p.latitude).toFixed(4)}, ${Number(p.longitude).toFixed(4)}`
						: __("no fix");
				const when = (p.captured_at || "").slice(11, 16);
				return `<div class="ir-shot">
					<img src="${esc(p.file_url)}" data-full="${esc(p.file_url)}" loading="lazy">
					<div class="meta">${esc(when)} · ${esc(where)}</div>
				</div>`;
			})
			.join("")}</div>`;
	}
}
