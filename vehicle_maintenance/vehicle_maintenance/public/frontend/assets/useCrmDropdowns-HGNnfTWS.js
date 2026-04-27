var a = (c, t, e) =>
	new Promise((r, u) => {
		var i = (l) => {
				try {
					m(e.next(l));
				} catch (_) {
					u(_);
				}
			},
			v = (l) => {
				try {
					m(e.throw(l));
				} catch (_) {
					u(_);
				}
			},
			m = (l) => (l.done ? r(l.value) : Promise.resolve(l.value).then(i, v));
		m((e = e.apply(c, t)).next());
	});
import { c as s } from "./api-BQ4HYjks.js";
import { h as w } from "./main-CCKo9z3F.js";
function n(c) {
	var t;
	return (t = c == null ? void 0 : c.data) != null ? t : c;
}
function R() {
	return {
		list() {
			return a(
				this,
				arguments,
				function* ({ filters: t = {}, page: e = 1, pageSize: r = 20, search: u = "" } = {}) {
					const i = yield s("crm.get_lead_list", { filters: t, page: e, page_size: r, search: u });
					return n(i);
				}
			);
		},
		get(t) {
			return a(this, null, function* () {
				const e = yield s("crm.get_lead", { name: t });
				return n(e);
			});
		},
		create(t) {
			return a(this, null, function* () {
				const e = yield s("crm.create_lead", { payload: t });
				return n(e);
			});
		},
		updateStatus(t, e, r = "") {
			return a(this, null, function* () {
				const u = yield s("crm.update_lead_status", { name: t, new_status: e, note: r });
				return n(u);
			});
		},
		addActivity(t, e) {
			return a(this, null, function* () {
				const r = yield s("crm.add_activity", { lead: t, payload: e });
				return n(r);
			});
		},
		scheduleReminder(t) {
			return a(this, null, function* () {
				const e = yield s("crm.schedule_reminder", { payload: t });
				return n(e);
			});
		},
		cancelReminder(t) {
			return a(this, null, function* () {
				const e = yield s("crm.cancel_reminder", { name: t });
				return n(e);
			});
		},
		convert(t) {
			return a(this, null, function* () {
				const e = yield s("crm.convert_lead_to_customer", { name: t });
				return n(e);
			});
		},
		uploadAttachment(t, e, r = "") {
			return a(this, null, function* () {
				const u = yield s("crm.upload_lead_attachment", { lead: t, file_url: e, caption: r });
				return n(u);
			});
		},
	};
}
const d = w({ sources: [], statuses: [], sales_users: [], depots: [], email_templates: [] }),
	p = w(!1),
	f = w(!1);
let o = null;
function y(c = !1) {
	return a(this, null, function* () {
		return p.value && !c
			? d.value
			: o ||
					((f.value = !0),
					(o = a(this, null, function* () {
						try {
							const t = yield s("crm.get_lead_dropdowns"),
								e = (t == null ? void 0 : t.data) || t;
							return (
								(d.value = {
									sources: e.sources || [],
									statuses: e.statuses || [],
									sales_users: e.sales_users || [],
									depots: e.depots || [],
									email_templates: e.email_templates || [],
								}),
								(p.value = !0),
								d.value
							);
						} finally {
							(f.value = !1), (o = null);
						}
					})),
					o);
	});
}
function x() {
	return { dropdowns: d, loaded: p, loading: f, load: y, refresh: () => y(!0) };
}
export { R as a, x as u };
//# sourceMappingURL=useCrmDropdowns-HGNnfTWS.js.map
