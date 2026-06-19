var C = (N, k, m) =>
	new Promise((x, b) => {
		var d = (l) => {
				try {
					u(m.next(l));
				} catch (n) {
					b(n);
				}
			},
			c = (l) => {
				try {
					u(m.throw(l));
				} catch (n) {
					b(n);
				}
			},
			u = (l) => (l.done ? x(l.value) : Promise.resolve(l.value).then(d, c));
		u((m = m.apply(N, k)).next());
	});
import {
	i as j,
	o as r,
	a as i,
	b as e,
	g as B,
	f as E,
	q as y,
	z as f,
	F as _,
	r as h,
	u as L,
	v as F,
	j as D,
	t as o,
	d as R,
	B as z,
	h as g,
	p as H,
	m as O,
	n as q,
} from "./main-CCKo9z3F.js";
import { u as G, a as J } from "./useCrmDropdowns-HGNnfTWS.js";
import { _ as K } from "./_plugin-vue_export-helper-DlAUqK2U.js";
import "./api-BQ4HYjks.js";
const Q = { class: "max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6" },
	W = { class: "flex items-center justify-between mb-6" },
	X = {
		class: "bg-white border border-gray-200 rounded-xl p-4 mb-4 grid grid-cols-1 md:grid-cols-5 gap-3",
	},
	Y = ["value"],
	Z = ["value"],
	ee = ["value"],
	te = { class: "bg-white border border-gray-200 rounded-xl overflow-hidden" },
	se = { class: "min-w-full text-sm" },
	ae = { class: "divide-y divide-gray-100" },
	oe = { key: 0 },
	le = { key: 1 },
	ne = ["onClick"],
	re = { class: "px-4 py-3" },
	ie = { class: "font-medium text-gray-900" },
	de = { class: "text-xs text-gray-500" },
	ue = { class: "px-4 py-3 text-gray-700" },
	pe = { class: "px-4 py-3 text-gray-700" },
	me = { class: "px-4 py-3" },
	xe = { class: "inline-flex px-2 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-700" },
	ce = { class: "px-4 py-3" },
	ye = { class: "px-4 py-3 text-gray-700" },
	ge = { class: "px-4 py-3 text-gray-700" },
	ve = { class: "px-4 py-3 text-xs text-gray-500" },
	be = {
		key: 0,
		class: "flex items-center justify-between px-4 py-3 border-t border-gray-100 bg-gray-50 text-xs text-gray-600",
	},
	fe = { class: "flex gap-2" },
	_e = ["disabled"],
	he = ["disabled"],
	v = 20,
	ke = {
		__name: "LeadsList",
		setup(N) {
			const k = H(),
				m = J(),
				{ dropdowns: x, load: b } = G(),
				d = z({ status: "", lead_source: "", assigned_to: "", priority: "" }),
				c = g(""),
				u = g([]),
				l = g(0),
				n = g(1),
				w = g(!1);
			let S = null;
			function p() {
				return C(this, null, function* () {
					w.value = !0;
					try {
						const a = yield m.list({
							filters: U(d),
							page: n.value,
							pageSize: v,
							search: c.value.trim(),
						});
						(u.value = a.rows || []), (l.value = a.total || 0);
					} finally {
						w.value = !1;
					}
				});
			}
			function $() {
				clearTimeout(S),
					(S = setTimeout(() => {
						(n.value = 1), p();
					}, 300));
			}
			function V(a) {
				(n.value = a), p();
			}
			function I(a) {
				k.push(`/service-portal/crm/leads/${encodeURIComponent(a)}`);
			}
			function U(a) {
				return Object.fromEntries(Object.entries(a).filter(([, t]) => t));
			}
			function A(a) {
				const t = "inline-flex px-2 py-0.5 rounded-full text-xs font-medium";
				return a === "High"
					? `${t} bg-red-50 text-red-700`
					: a === "Medium"
					? `${t} bg-amber-50 text-amber-700`
					: a === "Low"
					? `${t} bg-gray-100 text-gray-600`
					: `${t} bg-gray-100 text-gray-600`;
			}
			function M(a) {
				return new Intl.NumberFormat("en-IN", {
					style: "currency",
					currency: "INR",
					maximumFractionDigits: 0,
				}).format(a);
			}
			function P(a) {
				return a
					? new Date(a).toLocaleDateString("en-IN", {
							day: "2-digit",
							month: "short",
							year: "2-digit",
					  })
					: "";
			}
			return (
				j(() =>
					C(this, null, function* () {
						yield b(), yield p();
					})
				),
				(a, t) => {
					const T = R("router-link");
					return (
						r(),
						i("div", Q, [
							e("div", W, [
								t[8] ||
									(t[8] = e(
										"div",
										null,
										[
											e("h1", { class: "text-2xl font-bold text-gray-900" }, "Leads"),
											e(
												"p",
												{ class: "text-sm text-gray-500 mt-0.5" },
												"Prospects captured during sales pitches."
											),
										],
										-1
									)),
								B(
									T,
									{
										to: "/service-portal/crm/leads/new",
										class: "inline-flex items-center gap-1.5 px-4 py-2 text-sm font-medium text-white bg-brand-600 rounded-lg hover:bg-brand-700",
									},
									{
										default: E(() => [...(t[7] || (t[7] = [O(" + New Lead ", -1)]))]),
										_: 1,
									}
								),
							]),
							e("div", X, [
								e("div", null, [
									t[10] ||
										(t[10] = e(
											"label",
											{ class: "block text-xs font-medium text-gray-500 mb-1" },
											"Status",
											-1
										)),
									y(
										e(
											"select",
											{
												"onUpdate:modelValue": t[0] || (t[0] = (s) => (d.status = s)),
												onChange: p,
												class: "select-field",
											},
											[
												t[9] || (t[9] = e("option", { value: "" }, "All", -1)),
												(r(!0),
												i(
													_,
													null,
													h(
														L(x).statuses,
														(s) => (
															r(),
															i(
																"option",
																{ key: s.name, value: s.name },
																o(s.status_name),
																9,
																Y
															)
														)
													),
													128
												)),
											],
											544
										),
										[[f, d.status]]
									),
								]),
								e("div", null, [
									t[12] ||
										(t[12] = e(
											"label",
											{ class: "block text-xs font-medium text-gray-500 mb-1" },
											"Source",
											-1
										)),
									y(
										e(
											"select",
											{
												"onUpdate:modelValue":
													t[1] || (t[1] = (s) => (d.lead_source = s)),
												onChange: p,
												class: "select-field",
											},
											[
												t[11] || (t[11] = e("option", { value: "" }, "All", -1)),
												(r(!0),
												i(
													_,
													null,
													h(
														L(x).sources,
														(s) => (
															r(),
															i(
																"option",
																{ key: s.name, value: s.name },
																o(s.source_name),
																9,
																Z
															)
														)
													),
													128
												)),
											],
											544
										),
										[[f, d.lead_source]]
									),
								]),
								e("div", null, [
									t[14] ||
										(t[14] = e(
											"label",
											{ class: "block text-xs font-medium text-gray-500 mb-1" },
											"Assigned To",
											-1
										)),
									y(
										e(
											"select",
											{
												"onUpdate:modelValue":
													t[2] || (t[2] = (s) => (d.assigned_to = s)),
												onChange: p,
												class: "select-field",
											},
											[
												t[13] || (t[13] = e("option", { value: "" }, "All", -1)),
												(r(!0),
												i(
													_,
													null,
													h(
														L(x).sales_users,
														(s) => (
															r(),
															i(
																"option",
																{ key: s.name, value: s.name },
																o(s.full_name || s.name),
																9,
																ee
															)
														)
													),
													128
												)),
											],
											544
										),
										[[f, d.assigned_to]]
									),
								]),
								e("div", null, [
									t[16] ||
										(t[16] = e(
											"label",
											{ class: "block text-xs font-medium text-gray-500 mb-1" },
											"Priority",
											-1
										)),
									y(
										e(
											"select",
											{
												"onUpdate:modelValue":
													t[3] || (t[3] = (s) => (d.priority = s)),
												onChange: p,
												class: "select-field",
											},
											[
												...(t[15] ||
													(t[15] = [
														e("option", { value: "" }, "All", -1),
														e("option", null, "Low", -1),
														e("option", null, "Medium", -1),
														e("option", null, "High", -1),
													])),
											],
											544
										),
										[[f, d.priority]]
									),
								]),
								e("div", null, [
									t[17] ||
										(t[17] = e(
											"label",
											{ class: "block text-xs font-medium text-gray-500 mb-1" },
											"Search",
											-1
										)),
									y(
										e(
											"input",
											{
												"onUpdate:modelValue": t[4] || (t[4] = (s) => (c.value = s)),
												onInput: $,
												type: "text",
												placeholder: "Name, phone, company",
												class: "input-field",
											},
											null,
											544
										),
										[[F, c.value]]
									),
								]),
							]),
							e("div", te, [
								e("table", se, [
									t[20] ||
										(t[20] = e(
											"thead",
											{ class: "bg-gray-50" },
											[
												e(
													"tr",
													{
														class: "text-left text-xs font-semibold text-gray-500 uppercase tracking-wide",
													},
													[
														e("th", { class: "px-4 py-3" }, "Lead"),
														e("th", { class: "px-4 py-3" }, "Phone"),
														e("th", { class: "px-4 py-3" }, "Source"),
														e("th", { class: "px-4 py-3" }, "Status"),
														e("th", { class: "px-4 py-3" }, "Priority"),
														e("th", { class: "px-4 py-3" }, "Est. Value"),
														e("th", { class: "px-4 py-3" }, "Assigned"),
														e("th", { class: "px-4 py-3" }, "Updated"),
													]
												),
											],
											-1
										)),
									e("tbody", ae, [
										w.value
											? (r(),
											  i("tr", oe, [
													...(t[18] ||
														(t[18] = [
															e(
																"td",
																{
																	colspan: "8",
																	class: "px-4 py-8 text-center text-gray-400",
																},
																"Loading…",
																-1
															),
														])),
											  ]))
											: u.value.length
											? D("", !0)
											: (r(),
											  i("tr", le, [
													...(t[19] ||
														(t[19] = [
															e(
																"td",
																{
																	colspan: "8",
																	class: "px-4 py-8 text-center text-gray-400",
																},
																" No leads match the filters. ",
																-1
															),
														])),
											  ])),
										(r(!0),
										i(
											_,
											null,
											h(
												u.value,
												(s) => (
													r(),
													i(
														"tr",
														{
															key: s.name,
															class: "hover:bg-gray-50 cursor-pointer",
															onClick: (we) => I(s.name),
														},
														[
															e("td", re, [
																e("div", ie, o(s.lead_name), 1),
																e("div", de, o(s.company_name || "—"), 1),
															]),
															e("td", ue, o(s.phone), 1),
															e("td", pe, o(s.lead_source || "—"), 1),
															e("td", me, [
																e("span", xe, o(s.status || "—"), 1),
															]),
															e("td", ce, [
																e(
																	"span",
																	{ class: q(A(s.priority)) },
																	o(s.priority || "—"),
																	3
																),
															]),
															e(
																"td",
																ye,
																o(
																	s.estimated_value
																		? M(s.estimated_value)
																		: "—"
																),
																1
															),
															e("td", ge, o(s.assigned_to || "—"), 1),
															e("td", ve, o(P(s.modified)), 1),
														],
														8,
														ne
													)
												)
											),
											128
										)),
									]),
								]),
								l.value > v
									? (r(),
									  i("div", be, [
											e(
												"span",
												null,
												" Showing " +
													o((n.value - 1) * v + 1) +
													"–" +
													o(Math.min(n.value * v, l.value)) +
													" of " +
													o(l.value),
												1
											),
											e("div", fe, [
												e(
													"button",
													{
														class: "px-3 py-1 bg-white border border-gray-200 rounded disabled:opacity-40",
														disabled: n.value <= 1,
														onClick: t[5] || (t[5] = (s) => V(n.value - 1)),
													},
													" Prev ",
													8,
													_e
												),
												e(
													"button",
													{
														class: "px-3 py-1 bg-white border border-gray-200 rounded disabled:opacity-40",
														disabled: n.value * v >= l.value,
														onClick: t[6] || (t[6] = (s) => V(n.value + 1)),
													},
													" Next ",
													8,
													he
												),
											]),
									  ]))
									: D("", !0),
							]),
						])
					);
				}
			);
		},
	},
	De = K(ke, [["__scopeId", "data-v-60d0c3d8"]]);
export { De as default };
//# sourceMappingURL=LeadsList-DuwekaiL.js.map
