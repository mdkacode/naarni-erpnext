var f = (m, a, i) =>
	new Promise((p, c) => {
		var _ = (t) => {
				try {
					s(i.next(t));
				} catch (n) {
					c(n);
				}
			},
			b = (t) => {
				try {
					s(i.throw(t));
				} catch (n) {
					c(n);
				}
			},
			s = (t) => (t.done ? p(t.value) : Promise.resolve(t.value).then(_, b));
		s((i = i.apply(m, a)).next());
	});
import { c as w } from "./api-BQ4HYjks.js";
import {
	i as k,
	o as d,
	a as l,
	b as e,
	F as x,
	t as r,
	r as v,
	j as u,
	h,
	k as g,
	l as C,
	n as j,
} from "./main-CCKo9z3F.js";
const T = { class: "max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6" },
	I = { key: 0, class: "text-center py-16 text-gray-400" },
	A = { class: "grid grid-cols-2 md:grid-cols-5 gap-4 mb-8" },
	D = { class: "bg-white rounded-xl border p-4 text-center" },
	F = { class: "text-2xl font-extrabold text-brand-700" },
	N = { class: "bg-white rounded-xl border p-4 text-center" },
	O = { class: "text-2xl font-extrabold text-amber-600" },
	S = { class: "bg-white rounded-xl border p-4 text-center" },
	B = { class: "text-2xl font-extrabold text-green-600" },
	P = { class: "bg-white rounded-xl border p-4 text-center" },
	L = { class: "text-2xl font-extrabold text-red-600" },
	z = { class: "bg-white rounded-xl border p-4 text-center" },
	E = { class: "text-2xl font-extrabold text-gray-800" },
	M = { class: "grid md:grid-cols-2 lg:grid-cols-3 gap-4 mb-8" },
	V = { class: "flex items-center justify-between mb-3" },
	W = { class: "font-bold text-gray-900" },
	J = { key: 0, class: "text-xs font-bold text-red-600 bg-red-50 px-2 py-0.5 rounded-full" },
	R = { class: "grid grid-cols-3 gap-2 text-center text-xs" },
	$ = { class: "text-lg font-bold text-brand-600" },
	q = { class: "text-lg font-bold text-amber-600" },
	G = { class: "text-lg font-bold text-green-600" },
	H = { key: 0, class: "mt-3 pt-3 border-t text-xs text-gray-500 flex justify-between" },
	K = { class: "font-bold text-gray-800" },
	Q = { class: "grid md:grid-cols-2 gap-6 mb-8" },
	U = { class: "bg-white rounded-xl border p-5" },
	X = { class: "space-y-3" },
	Y = { class: "w-32 text-sm font-medium text-gray-700 truncate" },
	Z = { class: "flex-1 bg-gray-100 rounded-full h-5 overflow-hidden" },
	tt = { class: "text-white text-2xs font-bold" },
	et = { class: "bg-white rounded-xl border p-5" },
	st = { class: "space-y-3" },
	ot = { class: "text-sm text-gray-700" },
	at = { class: "flex items-center gap-2" },
	rt = { class: "text-2xs text-gray-400" },
	dt = { key: 0, class: "text-sm text-gray-400" },
	ct = {
		__name: "CentralOpsDashboard",
		setup(m) {
			const a = h(null),
				i = h(!0);
			k(() =>
				f(this, null, function* () {
					try {
						const s = yield w("dashboard.get_central_ops_dashboard");
						a.value = s.data;
					} finally {
						i.value = !1;
					}
				})
			);
			const p = g(() => {
					var s;
					return (((s = a.value) == null ? void 0 : s.breaches_by_depot) || []).reduce(
						(t, n) => t + n.count,
						0
					);
				}),
				c = g(() => {
					var s;
					return Math.max(
						...(((s = a.value) == null ? void 0 : s.by_type) || []).map((t) => t.count),
						1
					);
				}),
				_ = g(() => {
					if (!a.value) return [];
					const s = {};
					for (const t of a.value.by_depot || [])
						s[t.depot] || (s[t.depot] = { name: t.depot, open: 0, wip: 0, closed: 0 }),
							t.workflow_state === "Open"
								? (s[t.depot].open = t.count)
								: t.workflow_state === "WIP"
								? (s[t.depot].wip = t.count)
								: t.workflow_state === "Closed"
								? (s[t.depot].closed = t.count)
								: (s[t.depot].open += t.count);
					for (const t of a.value.breaches_by_depot || [])
						s[t.depot] && (s[t.depot].breaches = t.count);
					for (const t of a.value.tat_by_depot || [])
						s[t.depot] && (s[t.depot].avg_tat = t.avg_hours);
					return Object.values(s);
				});
			function b(s) {
				return new Intl.NumberFormat("en-IN", {
					style: "currency",
					currency: "INR",
					minimumFractionDigits: 0,
					notation: "compact",
				}).format(s || 0);
			}
			return (s, t) => {
				var n, y;
				return (
					d(),
					l("div", T, [
						t[12] ||
							(t[12] = e(
								"h1",
								{ class: "text-2xl font-bold text-gray-900 mb-6" },
								"Central Ops — Fleet Overview",
								-1
							)),
						i.value
							? (d(), l("div", I, "Loading..."))
							: a.value
							? (d(),
							  l(
									x,
									{ key: 1 },
									[
										e("div", A, [
											e("div", D, [
												e("div", F, r(a.value.total_buses), 1),
												t[0] ||
													(t[0] = e(
														"div",
														{ class: "text-2xs text-gray-500 uppercase" },
														"Total Fleet",
														-1
													)),
											]),
											e("div", N, [
												e("div", O, r(a.value.buses_in_service), 1),
												t[1] ||
													(t[1] = e(
														"div",
														{ class: "text-2xs text-gray-500 uppercase" },
														"In Service",
														-1
													)),
											]),
											e("div", S, [
												e(
													"div",
													B,
													r(a.value.total_buses - a.value.buses_in_service),
													1
												),
												t[2] ||
													(t[2] = e(
														"div",
														{ class: "text-2xs text-gray-500 uppercase" },
														"Available",
														-1
													)),
											]),
											e("div", P, [
												e("div", L, r(p.value), 1),
												t[3] ||
													(t[3] = e(
														"div",
														{ class: "text-2xs text-gray-500 uppercase" },
														"SLA Breaches",
														-1
													)),
											]),
											e("div", z, [
												e(
													"div",
													E,
													r(
														b(
															(n = a.value.cost_summary) == null
																? void 0
																: n.total_estimated
														)
													),
													1
												),
												t[4] ||
													(t[4] = e(
														"div",
														{ class: "text-2xs text-gray-500 uppercase" },
														"Total Est. Cost",
														-1
													)),
											]),
										]),
										t[11] ||
											(t[11] = e(
												"h2",
												{
													class: "text-sm font-semibold text-gray-500 uppercase tracking-wider mb-3",
												},
												"Depot-wise Status",
												-1
											)),
										e("div", M, [
											(d(!0),
											l(
												x,
												null,
												v(
													_.value,
													(o) => (
														d(),
														l(
															"div",
															{
																key: o.name,
																class: "bg-white rounded-xl border p-5 hover:shadow-md transition-shadow",
															},
															[
																e("div", V, [
																	e("h3", W, r(o.name), 1),
																	o.breaches > 0
																		? (d(),
																		  l(
																				"span",
																				J,
																				r(o.breaches) +
																					" breach" +
																					r(
																						o.breaches > 1
																							? "es"
																							: ""
																					),
																				1
																		  ))
																		: u("", !0),
																]),
																e("div", R, [
																	e("div", null, [
																		e("div", $, r(o.open), 1),
																		t[5] ||
																			(t[5] = e(
																				"div",
																				{ class: "text-gray-500" },
																				"Open",
																				-1
																			)),
																	]),
																	e("div", null, [
																		e("div", q, r(o.wip), 1),
																		t[6] ||
																			(t[6] = e(
																				"div",
																				{ class: "text-gray-500" },
																				"WIP",
																				-1
																			)),
																	]),
																	e("div", null, [
																		e("div", G, r(o.closed), 1),
																		t[7] ||
																			(t[7] = e(
																				"div",
																				{ class: "text-gray-500" },
																				"Closed",
																				-1
																			)),
																	]),
																]),
																o.avg_tat
																	? (d(),
																	  l("div", H, [
																			t[8] ||
																				(t[8] = e(
																					"span",
																					null,
																					"Avg TAT",
																					-1
																				)),
																			e(
																				"span",
																				K,
																				r(o.avg_tat) + " hrs",
																				1
																			),
																	  ]))
																	: u("", !0),
															]
														)
													)
												),
												128
											)),
										]),
										e("div", Q, [
											e("div", U, [
												t[9] ||
													(t[9] = e(
														"h2",
														{
															class: "text-sm font-semibold text-gray-500 uppercase mb-4",
														},
														"Job Card Types",
														-1
													)),
												e("div", X, [
													(d(!0),
													l(
														x,
														null,
														v(
															a.value.by_type,
															(o) => (
																d(),
																l(
																	"div",
																	{
																		key: o.job_card_type,
																		class: "flex items-center gap-3",
																	},
																	[
																		e("div", Y, r(o.job_card_type), 1),
																		e("div", Z, [
																			e(
																				"div",
																				{
																					class: "h-full bg-brand-500 rounded-full flex items-center justify-end pr-2",
																					style: C({
																						width: `${
																							(o.count /
																								c.value) *
																							100
																						}%`,
																					}),
																				},
																				[
																					e(
																						"span",
																						tt,
																						r(o.count),
																						1
																					),
																				],
																				4
																			),
																		]),
																	]
																)
															)
														),
														128
													)),
												]),
											]),
											e("div", et, [
												t[10] ||
													(t[10] = e(
														"h2",
														{
															class: "text-sm font-semibold text-gray-500 uppercase mb-4",
														},
														"TAT Performance by Depot",
														-1
													)),
												e("div", st, [
													(d(!0),
													l(
														x,
														null,
														v(
															a.value.tat_by_depot,
															(o) => (
																d(),
																l(
																	"div",
																	{
																		key: o.depot,
																		class: "flex items-center justify-between",
																	},
																	[
																		e("span", ot, r(o.depot), 1),
																		e("div", at, [
																			e(
																				"span",
																				{
																					class: j([
																						"text-sm font-bold",
																						o.avg_hours <= 4
																							? "text-green-600"
																							: o.avg_hours <= 8
																							? "text-amber-600"
																							: "text-red-600",
																					]),
																				},
																				r(o.avg_hours) + "h ",
																				3
																			),
																			e(
																				"span",
																				rt,
																				"(" +
																					r(o.closed_count) +
																					" cards)",
																				1
																			),
																		]),
																	]
																)
															)
														),
														128
													)),
													(y = a.value.tat_by_depot) != null && y.length
														? u("", !0)
														: (d(), l("div", dt, "No closed cards yet")),
												]),
											]),
										]),
									],
									64
							  ))
							: u("", !0),
					])
				);
			};
		},
	};
export { ct as default };
//# sourceMappingURL=CentralOpsDashboard-sdDGhZsz.js.map
