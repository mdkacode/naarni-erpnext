var N = (v, n, d) =>
	new Promise((_, x) => {
		var g = (a) => {
				try {
					m(d.next(a));
				} catch (s) {
					x(s);
				}
			},
			h = (a) => {
				try {
					m(d.throw(a));
				} catch (s) {
					x(s);
				}
			},
			m = (a) => (a.done ? _(a.value) : Promise.resolve(a.value).then(g, h));
		m((d = d.apply(v, n)).next());
	});
import { c as F } from "./api-Dt1AOn__.js";
import { _ as A } from "./StatusBadge-DZuo5DLS.js";
import {
	i as D,
	o as r,
	a as l,
	F as p,
	b as t,
	t as o,
	r as u,
	j as c,
	h as $,
	n as y,
	g as I,
	p as P,
	l as M,
} from "./main-C3kezhEI.js";
const j = { class: "max-w-5xl mx-auto px-4 py-6" },
	B = { key: 0, class: "text-center py-16 text-gray-400" },
	V = { class: "mb-8" },
	L = { class: "text-2xl font-bold text-gray-900" },
	R = { class: "grid grid-cols-2 md:grid-cols-4 gap-4 mb-8" },
	E = { class: "bg-gradient-to-br from-brand-500 to-brand-700 text-white rounded-2xl p-5" },
	T = { class: "text-4xl font-extrabold" },
	Y = { class: "bg-gradient-to-br from-amber-400 to-amber-600 text-white rounded-2xl p-5" },
	z = { class: "text-4xl font-extrabold" },
	H = { class: "bg-gradient-to-br from-green-400 to-green-600 text-white rounded-2xl p-5" },
	J = { class: "text-4xl font-extrabold" },
	U = { class: "bg-gradient-to-br from-violet-400 to-violet-600 text-white rounded-2xl p-5" },
	Z = { class: "text-4xl font-extrabold" },
	W = { key: 0, class: "mb-8" },
	q = { class: "grid gap-4 md:grid-cols-2" },
	G = ["onClick"],
	K = { class: "flex items-start justify-between mb-3" },
	O = { class: "text-xs font-mono text-gray-400" },
	Q = { class: "text-lg font-bold text-gray-900" },
	X = { class: "text-sm text-gray-500" },
	tt = { class: "flex items-center justify-between text-sm" },
	et = { class: "text-gray-500" },
	st = { key: 0, class: "mt-2 text-sm text-gray-500" },
	ot = { class: "font-semibold text-gray-800" },
	at = {
		key: 1,
		class: "mt-2 text-xs text-red-600 font-semibold bg-red-50 rounded-lg px-3 py-1 text-center",
	},
	rt = { key: 2, class: "mt-3 p-3 bg-violet-50 rounded-xl text-center" },
	lt = { key: 1, class: "mb-8" },
	nt = { class: "bg-white rounded-2xl border p-5" },
	it = { class: "space-y-4" },
	dt = { class: "w-36 shrink-0" },
	ct = { class: "font-bold text-sm text-gray-900" },
	_t = { class: "text-2xs text-gray-500" },
	xt = { class: "flex-1" },
	mt = { class: "bg-gray-100 rounded-full h-6 overflow-hidden relative" },
	pt = { class: "text-white text-xs font-bold" },
	ht = { class: "text-2xs text-gray-400 w-24 text-right shrink-0" },
	ut = { class: "mb-8" },
	gt = { class: "grid gap-3 md:grid-cols-3" },
	yt = { class: "font-bold text-gray-900" },
	vt = { class: "text-sm text-gray-500" },
	bt = { class: "mt-2 flex items-center gap-2 text-xs text-gray-400" },
	ft = { class: "px-2 py-0.5 bg-green-50 text-green-700 rounded-full font-medium" },
	wt = { key: 2, class: "mb-8" },
	kt = { class: "bg-white rounded-2xl border overflow-x-auto" },
	Ct = { class: "min-w-full text-sm" },
	St = { class: "divide-y" },
	Nt = { class: "px-4 py-3 font-mono text-brand-600" },
	$t = { class: "px-4 py-3 font-semibold" },
	Ft = { class: "px-4 py-3" },
	At = { class: "px-4 py-3 text-right" },
	Dt = { key: 0, class: "font-semibold" },
	It = { key: 1, class: "text-gray-300" },
	Pt = { class: "px-4 py-3 text-right" },
	Mt = { key: 0, class: "font-semibold text-green-600" },
	jt = { key: 1, class: "text-gray-300" },
	Bt = { class: "px-4 py-3 text-right font-medium" },
	Vt = { class: "px-4 py-3 text-gray-500" },
	Lt = { class: "px-4 py-3 text-center" },
	Rt = ["href"],
	Jt = {
		__name: "CustomerDashboard",
		setup(v) {
			const n = $(null),
				d = $(!0);
			D(() =>
				N(this, null, function* () {
					try {
						const a = yield F("dashboard.get_customer_dashboard");
						n.value = a.data;
					} finally {
						d.value = !1;
					}
				})
			);
			function _(a) {
				if (!a) return "";
				let i = a
					.replace(/[-\s]/g, "")
					.toUpperCase()
					.match(/^([A-Z]{2})(\d{1,2})([A-Z]{1,3})(\d{1,4})$/);
				return i ? `${i[1]} ${i[2].padStart(2, "0")} ${i[3]} ${i[4]}` : a;
			}
			function x(a) {
				return new Intl.NumberFormat("en-IN", {
					style: "currency",
					currency: "INR",
					minimumFractionDigits: 0,
				}).format(a || 0);
			}
			function g(a) {
				return new Intl.NumberFormat("en-IN", {
					style: "currency",
					currency: "INR",
					notation: "compact",
					minimumFractionDigits: 0,
				}).format(a || 0);
			}
			function h(a) {
				return a ? new Date(a).toLocaleDateString("en-IN", { day: "numeric", month: "short" }) : "—";
			}
			function m(a) {
				return a >= 80 ? "bg-green-500" : a >= 60 ? "bg-amber-500" : "bg-red-500";
			}
			return (a, s) => {
				var i, b, f, w, k, C, S;
				return (
					r(),
					l("div", j, [
						d.value
							? (r(), l("div", B, "Loading your fleet..."))
							: n.value
							? (r(),
							  l(
									p,
									{ key: 1 },
									[
										t("div", V, [
											t("h1", L, "Welcome, " + o(n.value.customer_name), 1),
											s[0] ||
												(s[0] = t(
													"p",
													{ class: "text-gray-500 mt-1" },
													"Your NaArNi Electric Fleet at a glance",
													-1
												)),
										]),
										t("div", R, [
											t("div", E, [
												t(
													"div",
													T,
													o(
														((i = n.value.vehicles) == null
															? void 0
															: i.length) || 0
													),
													1
												),
												s[1] ||
													(s[1] = t(
														"div",
														{ class: "text-sm opacity-80 mt-1" },
														"Total Buses",
														-1
													)),
											]),
											t("div", Y, [
												t(
													"div",
													z,
													o(
														((b = n.value.active_cards) == null
															? void 0
															: b.length) || 0
													),
													1
												),
												s[2] ||
													(s[2] = t(
														"div",
														{ class: "text-sm opacity-80 mt-1" },
														"Active Service",
														-1
													)),
											]),
											t("div", H, [
												t(
													"div",
													J,
													o(
														((f = n.value.history) == null ? void 0 : f.length) ||
															0
													),
													1
												),
												s[3] ||
													(s[3] = t(
														"div",
														{ class: "text-sm opacity-80 mt-1" },
														"Completed",
														-1
													)),
											]),
											t("div", U, [
												t(
													"div",
													Z,
													o(
														g(
															(w = n.value.cost_totals) == null
																? void 0
																: w.total_actual
														)
													),
													1
												),
												s[4] ||
													(s[4] = t(
														"div",
														{ class: "text-sm opacity-80 mt-1" },
														"Total Spent",
														-1
													)),
											]),
										]),
										(k = n.value.active_cards) != null && k.length
											? (r(),
											  l("div", W, [
													s[7] ||
														(s[7] = t(
															"h2",
															{ class: "text-lg font-bold text-gray-900 mb-4" },
															"Active Service Cards",
															-1
														)),
													t("div", q, [
														(r(!0),
														l(
															p,
															null,
															u(
																n.value.active_cards,
																(e) => (
																	r(),
																	l(
																		"div",
																		{
																			key: e.name,
																			onClick: (Et) =>
																				a.$router.push(
																					`/service-portal/job-card/${e.name}`
																				),
																			class: y([
																				"bg-white rounded-2xl border-2 p-5 cursor-pointer hover:shadow-lg transition-all",
																				e.sla_breached
																					? "border-red-300"
																					: "border-gray-100",
																			]),
																		},
																		[
																			t("div", K, [
																				t("div", null, [
																					t("p", O, o(e.name), 1),
																					t(
																						"p",
																						Q,
																						o(
																							_(
																								e.vehicle_number
																							)
																						),
																						1
																					),
																					t(
																						"p",
																						X,
																						o(
																							e.vehicle_make_model
																						),
																						1
																					),
																				]),
																				I(
																					A,
																					{
																						state: e.workflow_state,
																					},
																					null,
																					8,
																					["state"]
																				),
																			]),
																			t("div", tt, [
																				t(
																					"span",
																					et,
																					o(e.job_card_type),
																					1
																				),
																				t(
																					"span",
																					{
																						class: y([
																							"font-semibold",
																							e.priority ===
																							"Urgent"
																								? "text-red-600"
																								: "text-gray-700",
																						]),
																					},
																					o(e.priority),
																					3
																				),
																			]),
																			e.estimated_cost
																				? (r(),
																				  l("div", st, [
																						s[5] ||
																							(s[5] = P(
																								" Est: ",
																								-1
																							)),
																						t(
																							"span",
																							ot,
																							o(
																								x(
																									e.estimated_cost
																								)
																							),
																							1
																						),
																				  ]))
																				: c("", !0),
																			e.sla_breached
																				? (r(),
																				  l(
																						"div",
																						at,
																						" SLA Breached — Please contact depot "
																				  ))
																				: c("", !0),
																			e.workflow_state ===
																			"Awaiting Customer Approval"
																				? (r(),
																				  l("div", rt, [
																						...(s[6] ||
																							(s[6] = [
																								t(
																									"p",
																									{
																										class: "text-sm font-semibold text-violet-800",
																									},
																									"Your approval is needed",
																									-1
																								),
																								t(
																									"button",
																									{
																										class: "mt-2 px-4 py-1.5 bg-violet-600 text-white text-sm rounded-lg font-medium hover:bg-violet-700",
																									},
																									" Review Estimate ",
																									-1
																								),
																							])),
																				  ]))
																				: c("", !0),
																		],
																		10,
																		G
																	)
																)
															),
															128
														)),
													]),
											  ]))
											: c("", !0),
										(C = n.value.fleet_health) != null && C.length
											? (r(),
											  l("div", lt, [
													s[8] ||
														(s[8] = t(
															"h2",
															{ class: "text-lg font-bold text-gray-900 mb-4" },
															"Fleet Health Scores",
															-1
														)),
													t("div", nt, [
														t("div", it, [
															(r(!0),
															l(
																p,
																null,
																u(
																	n.value.fleet_health,
																	(e) => (
																		r(),
																		l(
																			"div",
																			{
																				key: e.vehicle_number,
																				class: "flex items-center gap-4",
																			},
																			[
																				t("div", dt, [
																					t(
																						"p",
																						ct,
																						o(
																							_(
																								e.vehicle_number
																							)
																						),
																						1
																					),
																					t(
																						"p",
																						_t,
																						o(
																							e.vehicle_make_model
																						),
																						1
																					),
																				]),
																				t("div", xt, [
																					t("div", mt, [
																						t(
																							"div",
																							{
																								class: y([
																									"h-full rounded-full flex items-center pl-3 transition-all",
																									m(
																										e.latest_health_score
																									),
																								]),
																								style: M({
																									width: `${
																										e.latest_health_score ||
																										0
																									}%`,
																								}),
																							},
																							[
																								t(
																									"span",
																									pt,
																									o(
																										Math.round(
																											e.latest_health_score ||
																												0
																										)
																									) + "%",
																									1
																								),
																							],
																							6
																						),
																					]),
																				]),
																				t(
																					"div",
																					ht,
																					" Last: " +
																						o(
																							h(
																								e.last_service_date
																							)
																						),
																					1
																				),
																			]
																		)
																	)
																),
																128
															)),
														]),
													]),
											  ]))
											: c("", !0),
										t("div", ut, [
											s[9] ||
												(s[9] = t(
													"h2",
													{ class: "text-lg font-bold text-gray-900 mb-4" },
													"Your Fleet",
													-1
												)),
											t("div", gt, [
												(r(!0),
												l(
													p,
													null,
													u(
														n.value.vehicles,
														(e) => (
															r(),
															l(
																"div",
																{
																	key: e.name,
																	class: "bg-white rounded-xl border p-4 hover:shadow-md transition-shadow",
																},
																[
																	t(
																		"p",
																		yt,
																		o(_(e.registration_number)),
																		1
																	),
																	t("p", vt, o(e.make_model), 1),
																	t("div", bt, [
																		t("span", ft, o(e.fuel_type), 1),
																		t(
																			"span",
																			null,
																			o(e.year_of_manufacture),
																			1
																		),
																		t("span", null, o(e.color), 1),
																	]),
																]
															)
														)
													),
													128
												)),
											]),
										]),
										(S = n.value.history) != null && S.length
											? (r(),
											  l("div", wt, [
													s[11] ||
														(s[11] = t(
															"h2",
															{ class: "text-lg font-bold text-gray-900 mb-4" },
															"Service History",
															-1
														)),
													t("div", kt, [
														t("table", Ct, [
															s[10] ||
																(s[10] = t(
																	"thead",
																	{ class: "bg-gray-50" },
																	[
																		t("tr", null, [
																			t(
																				"th",
																				{
																					class: "px-4 py-3 text-left text-gray-600",
																				},
																				"Job Card"
																			),
																			t(
																				"th",
																				{
																					class: "px-4 py-3 text-left text-gray-600",
																				},
																				"Vehicle"
																			),
																			t(
																				"th",
																				{
																					class: "px-4 py-3 text-left text-gray-600",
																				},
																				"Type"
																			),
																			t(
																				"th",
																				{
																					class: "px-4 py-3 text-right text-gray-600",
																				},
																				"Pre-PMS"
																			),
																			t(
																				"th",
																				{
																					class: "px-4 py-3 text-right text-gray-600",
																				},
																				"Post-PMS"
																			),
																			t(
																				"th",
																				{
																					class: "px-4 py-3 text-right text-gray-600",
																				},
																				"Cost"
																			),
																			t(
																				"th",
																				{
																					class: "px-4 py-3 text-left text-gray-600",
																				},
																				"Closed"
																			),
																			t(
																				"th",
																				{
																					class: "px-4 py-3 text-center text-gray-600",
																				},
																				"Report"
																			),
																		]),
																	],
																	-1
																)),
															t("tbody", St, [
																(r(!0),
																l(
																	p,
																	null,
																	u(
																		n.value.history,
																		(e) => (
																			r(),
																			l("tr", { key: e.name }, [
																				t("td", Nt, o(e.name), 1),
																				t(
																					"td",
																					$t,
																					o(_(e.vehicle_number)),
																					1
																				),
																				t(
																					"td",
																					Ft,
																					o(e.job_card_type),
																					1
																				),
																				t("td", At, [
																					e.pre_pms_score
																						? (r(),
																						  l(
																								"span",
																								Dt,
																								o(
																									Math.round(
																										e.pre_pms_score
																									)
																								) + "%",
																								1
																						  ))
																						: (r(),
																						  l("span", It, "—")),
																				]),
																				t("td", Pt, [
																					e.post_pms_score
																						? (r(),
																						  l(
																								"span",
																								Mt,
																								o(
																									Math.round(
																										e.post_pms_score
																									)
																								) + "%",
																								1
																						  ))
																						: (r(),
																						  l("span", jt, "—")),
																				]),
																				t(
																					"td",
																					Bt,
																					o(
																						x(
																							e.actual_cost ||
																								e.estimated_cost
																						)
																					),
																					1
																				),
																				t(
																					"td",
																					Vt,
																					o(h(e.closed_at)),
																					1
																				),
																				t("td", Lt, [
																					t(
																						"a",
																						{
																							href: `/printview?doctype=Job Card&name=${e.name}&format=PMS Report`,
																							target: "_blank",
																							class: "text-brand-600 hover:underline text-xs font-medium",
																						},
																						"PDF",
																						8,
																						Rt
																					),
																				]),
																			])
																		)
																	),
																	128
																)),
															]),
														]),
													]),
											  ]))
											: c("", !0),
									],
									64
							  ))
							: c("", !0),
					])
				);
			};
		},
	};
export { Jt as default };
//# sourceMappingURL=CustomerDashboard-CV2yb6zi.js.map
