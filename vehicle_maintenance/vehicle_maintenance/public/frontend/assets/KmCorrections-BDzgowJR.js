var h = (D, p, d) =>
	new Promise((x, g) => {
		var i = (a) => {
				try {
					m(d.next(a));
				} catch (v) {
					g(v);
				}
			},
			b = (a) => {
				try {
					m(d.throw(a));
				} catch (v) {
					g(v);
				}
			},
			m = (a) => (a.done ? x(a.value) : Promise.resolve(a.value).then(i, b));
		m((d = d.apply(D, p)).next());
	});
import {
	h as c,
	i as V,
	o as n,
	a as r,
	g as F,
	f as C,
	_ as $,
	b as t,
	m as B,
	F as f,
	r as k,
	t as o,
	v as K,
	j as w,
} from "./main-C3kezhEI.js";
import { c as M } from "./api-Dt1AOn__.js";
const N = { class: "max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-6" },
	E = { class: "flex flex-wrap items-end gap-3 mb-6" },
	L = { class: "block" },
	T = ["value"],
	P = { class: "block" },
	U = ["value"],
	Y = { key: 0, class: "text-center py-16 text-gray-400" },
	j = { class: "flex flex-wrap items-stretch gap-2 mb-6" },
	A = { class: "flex-1 min-w-[120px] bg-white rounded-xl border p-4 text-center" },
	I = { class: "text-2xl font-extrabold text-gray-800" },
	O = { class: "flex-1 min-w-[120px] bg-white rounded-xl border p-4 text-center" },
	q = { class: "text-2xl font-extrabold text-amber-600" },
	z = { class: "flex-1 min-w-[120px] bg-white rounded-xl border p-4 text-center" },
	G = { class: "text-2xl font-extrabold text-red-600" },
	H = { class: "flex-1 min-w-[120px] bg-brand-50 rounded-xl border border-brand-200 p-4 text-center" },
	J = { class: "text-2xl font-extrabold text-brand-700" },
	Q = { class: "bg-white rounded-xl border overflow-hidden" },
	R = { class: "w-full text-sm" },
	W = { class: "divide-y" },
	X = { class: "px-4 py-3 font-medium text-gray-900" },
	Z = { class: "px-3 py-3 text-right tabular-nums" },
	tt = { class: "px-3 py-3 text-right tabular-nums text-amber-600 hidden sm:table-cell" },
	et = { class: "px-3 py-3 text-right tabular-nums text-gray-400 hidden sm:table-cell" },
	st = { class: "px-3 py-3 text-right" },
	at = ["value", "disabled", "onChange"],
	lt = { class: "px-4 py-3 text-right font-bold text-brand-700 tabular-nums" },
	ot = { key: 0 },
	nt = { key: 0, class: "text-xs text-green-600 mt-3" },
	xt = {
		__name: "KmCorrections",
		setup(D) {
			const p = c([]),
				d = c(""),
				x = c(""),
				g = c(v(18)),
				i = c(null),
				b = c(!1),
				m = c(""),
				a = c("");
			function v(l) {
				const e = [],
					s = new Date();
				s.setDate(1);
				for (let y = 0; y < l; y++)
					e.push(`${s.getFullYear()}-${String(s.getMonth() + 1).padStart(2, "0")}`),
						s.setMonth(s.getMonth() - 1);
				return e;
			}
			function u(l) {
				return Number(l || 0).toLocaleString(void 0, {
					minimumFractionDigits: 1,
					maximumFractionDigits: 1,
				});
			}
			function _() {
				return h(this, null, function* () {
					if (!(!d.value || !x.value)) {
						(b.value = !0), (a.value = "");
						try {
							const l = yield M("km_reports.get_km_correction_board", {
								customer: d.value,
								year_month: x.value,
							});
							i.value = l.data;
						} finally {
							b.value = !1;
						}
					}
				});
			}
			function S(l, e) {
				return h(this, null, function* () {
					const s = Math.max(parseFloat(e) || 0, 0);
					(m.value = l.vehicle), (a.value = "");
					try {
						yield M("km_reports.set_monthly_dead_km", {
							vehicle: l.vehicle,
							year_month: x.value,
							dead_km: s,
						}),
							yield _(),
							(a.value = `Saved ${l.registration}: ${u(s)} km dead for ${x.value}.`);
					} finally {
						m.value = "";
					}
				});
			}
			return (
				V(() =>
					h(this, null, function* () {
						x.value = g.value[0];
						const l = yield M("km_reports.list_km_customers");
						(p.value = l.data || []), p.value.length && ((d.value = p.value[0].name), yield _());
					})
				),
				(l, e) => (
					n(),
					r("div", N, [
						F(
							$,
							{ roles: ["Central Ops", "Depot Manager", "System Manager"] },
							{
								fallback: C(() => [
									...(e[13] ||
										(e[13] = [
											t(
												"div",
												{ class: "text-center py-20 text-gray-400" },
												"You don't have access to KM corrections.",
												-1
											),
										])),
								]),
								default: C(() => [
									e[14] ||
										(e[14] = t(
											"h1",
											{ class: "text-2xl font-bold text-gray-900 mb-1" },
											"KM Corrections",
											-1
										)),
									e[15] ||
										(e[15] = t(
											"p",
											{ class: "text-sm text-gray-500 mb-6" },
											" Deduct dead (non-billable) kilometres. Billable = Total − Excluded − Dead. ",
											-1
										)),
									t("div", E, [
										t("label", L, [
											e[2] ||
												(e[2] = t(
													"span",
													{
														class: "text-xs font-semibold text-gray-500 uppercase",
													},
													"Customer",
													-1
												)),
											B(
												t(
													"select",
													{
														"onUpdate:modelValue":
															e[0] || (e[0] = (s) => (d.value = s)),
														onChange: _,
														class: "mt-1 block w-56 rounded-lg border-gray-300 text-sm",
													},
													[
														(n(!0),
														r(
															f,
															null,
															k(
																p.value,
																(s) => (
																	n(),
																	r(
																		"option",
																		{ key: s.name, value: s.name },
																		o(s.customer_name || s.name),
																		9,
																		T
																	)
																)
															),
															128
														)),
													],
													544
												),
												[[K, d.value]]
											),
										]),
										t("label", P, [
											e[3] ||
												(e[3] = t(
													"span",
													{
														class: "text-xs font-semibold text-gray-500 uppercase",
													},
													"Billing Month",
													-1
												)),
											B(
												t(
													"select",
													{
														"onUpdate:modelValue":
															e[1] || (e[1] = (s) => (x.value = s)),
														onChange: _,
														class: "mt-1 block w-40 rounded-lg border-gray-300 text-sm",
													},
													[
														(n(!0),
														r(
															f,
															null,
															k(
																g.value,
																(s) => (
																	n(),
																	r(
																		"option",
																		{ key: s, value: s },
																		o(s),
																		9,
																		U
																	)
																)
															),
															128
														)),
													],
													544
												),
												[[K, x.value]]
											),
										]),
									]),
									b.value
										? (n(), r("div", Y, "Loading…"))
										: i.value
										? (n(),
										  r(
												f,
												{ key: 1 },
												[
													t("div", j, [
														t("div", A, [
															t(
																"div",
																I,
																o(u(i.value.totals.total_distance_km)),
																1
															),
															e[4] ||
																(e[4] = t(
																	"div",
																	{
																		class: "text-2xs text-gray-500 uppercase mt-1",
																	},
																	"Total KM",
																	-1
																)),
														]),
														e[8] ||
															(e[8] = t(
																"div",
																{
																	class: "self-center text-2xl font-bold text-gray-300",
																},
																"−",
																-1
															)),
														t("div", O, [
															t("div", q, o(u(i.value.totals.excluded_km)), 1),
															e[5] ||
																(e[5] = t(
																	"div",
																	{
																		class: "text-2xs text-gray-500 uppercase mt-1",
																	},
																	"Excluded",
																	-1
																)),
														]),
														e[9] ||
															(e[9] = t(
																"div",
																{
																	class: "self-center text-2xl font-bold text-gray-300",
																},
																"−",
																-1
															)),
														t("div", z, [
															t("div", G, o(u(i.value.totals.dead_km)), 1),
															e[6] ||
																(e[6] = t(
																	"div",
																	{
																		class: "text-2xs text-gray-500 uppercase mt-1",
																	},
																	"Dead KM",
																	-1
																)),
														]),
														e[10] ||
															(e[10] = t(
																"div",
																{
																	class: "self-center text-2xl font-bold text-gray-300",
																},
																"=",
																-1
															)),
														t("div", H, [
															t("div", J, o(u(i.value.totals.billable_km)), 1),
															e[7] ||
																(e[7] = t(
																	"div",
																	{
																		class: "text-2xs text-brand-600 uppercase mt-1 font-semibold",
																	},
																	"Billable",
																	-1
																)),
														]),
													]),
													t("div", Q, [
														t("table", R, [
															e[12] ||
																(e[12] = t(
																	"thead",
																	{
																		class: "bg-gray-50 text-gray-500 text-xs uppercase",
																	},
																	[
																		t("tr", null, [
																			t(
																				"th",
																				{
																					class: "text-left px-4 py-3",
																				},
																				"Vehicle"
																			),
																			t(
																				"th",
																				{
																					class: "text-right px-3 py-3",
																				},
																				"Total KM"
																			),
																			t(
																				"th",
																				{
																					class: "text-right px-3 py-3 hidden sm:table-cell",
																				},
																				"Excluded"
																			),
																			t(
																				"th",
																				{
																					class: "text-right px-3 py-3 hidden sm:table-cell",
																					title: "Dead KM logged on individual days",
																				},
																				"Per-day Dead"
																			),
																			t(
																				"th",
																				{
																					class: "text-right px-3 py-3",
																				},
																				"Monthly Dead"
																			),
																			t(
																				"th",
																				{
																					class: "text-right px-4 py-3",
																				},
																				"Billable"
																			),
																		]),
																	],
																	-1
																)),
															t("tbody", W, [
																(n(!0),
																r(
																	f,
																	null,
																	k(
																		i.value.vehicles,
																		(s) => (
																			n(),
																			r(
																				"tr",
																				{
																					key: s.vehicle,
																					class: "hover:bg-gray-50",
																				},
																				[
																					t(
																						"td",
																						X,
																						o(s.registration),
																						1
																					),
																					t(
																						"td",
																						Z,
																						o(
																							u(
																								s.total_distance_km
																							)
																						),
																						1
																					),
																					t(
																						"td",
																						tt,
																						o(
																							s.excluded_km
																								? "− " +
																										u(
																											s.excluded_km
																										)
																								: "0.0"
																						),
																						1
																					),
																					t(
																						"td",
																						et,
																						o(
																							s.per_day_dead_km
																								? "− " +
																										u(
																											s.per_day_dead_km
																										)
																								: "0.0"
																						),
																						1
																					),
																					t("td", st, [
																						t(
																							"input",
																							{
																								type: "number",
																								min: "0",
																								step: "0.1",
																								class: "w-24 text-right rounded-lg border-gray-300 text-sm tabular-nums",
																								value: s.monthly_dead_km,
																								disabled:
																									m.value ===
																									s.vehicle,
																								onChange: (
																									y
																								) =>
																									S(
																										s,
																										y
																											.target
																											.value
																									),
																							},
																							null,
																							40,
																							at
																						),
																					]),
																					t(
																						"td",
																						lt,
																						o(u(s.billable_km)),
																						1
																					),
																				]
																			)
																		)
																	),
																	128
																)),
																i.value.vehicles.length
																	? w("", !0)
																	: (n(),
																	  r("tr", ot, [
																			...(e[11] ||
																				(e[11] = [
																					t(
																						"td",
																						{
																							colspan: "6",
																							class: "px-4 py-10 text-center text-gray-400",
																						},
																						"No vehicles for this customer.",
																						-1
																					),
																				])),
																	  ])),
															]),
														]),
													]),
													a.value ? (n(), r("p", nt, o(a.value), 1)) : w("", !0),
												],
												64
										  ))
										: w("", !0),
								]),
								_: 1,
							}
						),
					])
				)
			);
		},
	};
export { xt as default };
//# sourceMappingURL=KmCorrections-BDzgowJR.js.map
