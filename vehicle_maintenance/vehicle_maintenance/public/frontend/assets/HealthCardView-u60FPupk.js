var h = (g, v, n) =>
	new Promise((u, i) => {
		var s = (o) => {
				try {
					m(n.next(o));
				} catch (_) {
					i(_);
				}
			},
			x = (o) => {
				try {
					m(n.throw(o));
				} catch (_) {
					i(_);
				}
			},
			m = (o) => (o.done ? u(o.value) : Promise.resolve(o.value).then(s, x));
		m((n = n.apply(g, v)).next());
	});
import {
	w as j,
	o as a,
	a as d,
	b as t,
	g as y,
	f as V,
	t as r,
	F as H,
	r as N,
	j as b,
	k as f,
	d as D,
	h as p,
	y as L,
	p as w,
	n as S,
} from "./main-C3kezhEI.js";
import { c as A } from "./api-Dt1AOn__.js";
import { _ as B } from "./HealthScoreCard-Dv8NSqQs.js";
const F = { class: "max-w-3xl mx-auto px-4 sm:px-6 lg:px-8 py-8" },
	R = { class: "mb-6" },
	I = { key: 0, class: "text-center py-12 text-gray-500" },
	J = { key: 1, class: "p-4 bg-red-50 border border-red-200 rounded-xl text-sm text-red-700" },
	$ = {
		key: 2,
		class: "p-6 text-center text-sm text-gray-500 bg-gray-50 border border-gray-200 rounded-xl",
	},
	E = { key: 3, class: "space-y-4" },
	P = { class: "bg-brand-50 border border-brand-200 rounded-xl p-4" },
	U = { class: "flex justify-between items-start" },
	z = { class: "text-lg font-bold text-brand-900 mt-1" },
	G = { class: "text-right text-xs text-brand-700" },
	O = { class: "font-medium text-brand-900" },
	T = { class: "grid grid-cols-3 gap-3" },
	q = { class: "bg-white border border-gray-200 rounded-xl p-3" },
	K = { class: "font-semibold text-gray-900 mt-1" },
	M = { class: "bg-white border border-gray-200 rounded-xl p-3" },
	Q = { class: "font-semibold text-gray-900 mt-1" },
	W = { class: "bg-white border border-gray-200 rounded-xl p-3" },
	X = { class: "font-semibold text-gray-900 mt-1" },
	Y = { key: 0, class: "bg-white border border-gray-200 rounded-xl p-4" },
	Z = { class: "space-y-2" },
	ee = { class: "min-w-0" },
	te = { class: "text-sm font-medium text-gray-900 truncate" },
	se = { class: "text-xs text-gray-500" },
	re = { key: 0 },
	oe = { class: "shrink-0 text-right" },
	ae = { class: "text-xs text-gray-400 mt-0.5" },
	de = { key: 1, class: "bg-white border border-gray-200 rounded-xl p-4" },
	le = { class: "text-sm text-gray-700 whitespace-pre-line" },
	ne = { class: "text-right" },
	ie = ["href"],
	ve = {
		__name: "HealthCardView",
		setup(g) {
			const v = L(),
				n = f(() => v.params.jobCardName),
				u = p(!0),
				i = p(""),
				s = p(null),
				x = f(() =>
					s.value
						? `/api/method/frappe.utils.print_format.download_pdf?doctype=Vehicle+Health+Card&name=${encodeURIComponent(
								s.value.name
						  )}&format=Vehicle+Health+Card+Report&no_letterhead=1`
						: "#"
				);
			function m() {
				return h(this, null, function* () {
					var l;
					(u.value = !0), (i.value = ""), (s.value = null);
					try {
						const e = yield A(
							"vehicle_maintenance.fleet_service.doctype.vehicle_health_card.vehicle_health_card.get_for_job_card",
							{ job_card_name: n.value }
						);
						s.value = (e == null ? void 0 : e.data) || null;
					} catch (e) {
						i.value =
							((l = e == null ? void 0 : e.messages) == null ? void 0 : l[0]) ||
							(e == null ? void 0 : e.message) ||
							"Failed to load health card.";
					} finally {
						u.value = !1;
					}
				});
			}
			function o(l) {
				if (!l) return "—";
				try {
					return new Date(l).toLocaleString();
				} catch (e) {
					return l;
				}
			}
			function _(l) {
				const e = (l || "").toLowerCase();
				return e === "critical"
					? "bg-red-100 text-red-700"
					: e === "high"
					? "bg-orange-100 text-orange-700"
					: e === "medium"
					? "bg-amber-100 text-amber-700"
					: e === "low"
					? "bg-green-100 text-green-700"
					: "bg-gray-100 text-gray-600";
			}
			return (
				j(n, m, { immediate: !0 }),
				(l, e) => {
					const k = D("router-link");
					return (
						a(),
						d("div", F, [
							t("div", R, [
								y(
									k,
									{
										to: `/service-portal/job-card/${n.value}`,
										class: "text-sm text-brand-600 hover:underline",
									},
									{
										default: V(() => [
											...(e[0] || (e[0] = [w(" ← Back to Job Card ", -1)])),
										]),
										_: 1,
									},
									8,
									["to"]
								),
								e[1] ||
									(e[1] = t(
										"h1",
										{ class: "text-2xl font-bold text-gray-900 mt-2" },
										"Vehicle Health Card",
										-1
									)),
							]),
							u.value
								? (a(), d("div", I, "Loading…"))
								: i.value
								? (a(), d("div", J, r(i.value), 1))
								: s.value
								? (a(),
								  d("div", E, [
										t("div", P, [
											t("div", U, [
												t("div", null, [
													e[2] ||
														(e[2] = t(
															"div",
															{
																class: "text-xs text-brand-700 uppercase tracking-wide font-medium",
															},
															" Health Card ",
															-1
														)),
													t("div", z, r(s.value.name), 1),
												]),
												t("div", G, [
													e[3] || (e[3] = t("div", null, "Generated", -1)),
													t("div", O, r(o(s.value.generated_on)), 1),
												]),
											]),
										]),
										t("div", T, [
											t("div", q, [
												e[4] ||
													(e[4] = t(
														"div",
														{ class: "text-xs text-gray-500 uppercase" },
														"Vehicle",
														-1
													)),
												t("div", K, r(s.value.vehicle_number || s.value.vehicle), 1),
											]),
											t("div", M, [
												e[5] ||
													(e[5] = t(
														"div",
														{ class: "text-xs text-gray-500 uppercase" },
														"Odometer",
														-1
													)),
												t(
													"div",
													Q,
													r((s.value.odometer_reading || 0).toLocaleString()) +
														" km ",
													1
												),
											]),
											t("div", W, [
												e[6] ||
													(e[6] = t(
														"div",
														{ class: "text-xs text-gray-500 uppercase" },
														"Job Card",
														-1
													)),
												t("div", X, r(s.value.job_card), 1),
											]),
										]),
										y(
											B,
											{
												"pre-score": s.value.overall_pre_score,
												"post-score": s.value.overall_post_score,
												improvement: s.value.improvement,
												"category-scores": s.value.category_scores || [],
											},
											null,
											8,
											["pre-score", "post-score", "improvement", "category-scores"]
										),
										s.value.resolved_alerts && s.value.resolved_alerts.length
											? (a(),
											  d("div", Y, [
													e[7] ||
														(e[7] = t(
															"h3",
															{
																class: "text-sm font-semibold text-gray-800 mb-3",
															},
															"Alerts Resolved",
															-1
														)),
													t("div", Z, [
														(a(!0),
														d(
															H,
															null,
															N(
																s.value.resolved_alerts,
																(c, C) => (
																	a(),
																	d(
																		"div",
																		{
																			key: C,
																			class: "flex items-start justify-between gap-3 border-b border-gray-100 last:border-0 pb-2 last:pb-0",
																		},
																		[
																			t("div", ee, [
																				t(
																					"p",
																					te,
																					r(
																						c.alert_name ||
																							"Alert"
																					),
																					1
																				),
																				t("p", se, [
																					w(
																						r(
																							c.resolved_by ||
																								"—"
																						),
																						1
																					),
																					c.response
																						? (a(),
																						  d(
																								"span",
																								re,
																								" · " +
																									r(
																										c.response
																									),
																								1
																						  ))
																						: b("", !0),
																				]),
																			]),
																			t("div", oe, [
																				t(
																					"span",
																					{
																						class: S([
																							"inline-block px-2 py-0.5 rounded-full text-xs font-semibold",
																							_(c.severity),
																						]),
																					},
																					r(c.severity || "—"),
																					3
																				),
																				t(
																					"p",
																					ae,
																					r(o(c.resolved_on)),
																					1
																				),
																			]),
																		]
																	)
																)
															),
															128
														)),
													]),
											  ]))
											: b("", !0),
										s.value.summary_notes
											? (a(),
											  d("div", de, [
													e[8] ||
														(e[8] = t(
															"h3",
															{
																class: "text-sm font-semibold text-gray-800 mb-2",
															},
															"Summary",
															-1
														)),
													t("p", le, r(s.value.summary_notes), 1),
											  ]))
											: b("", !0),
										t("div", ne, [
											t(
												"a",
												{
													href: x.value,
													target: "_blank",
													rel: "noopener",
													class: "inline-block px-4 py-2 text-sm font-medium text-brand-700 bg-brand-50 hover:bg-brand-100 rounded-lg",
												},
												" Download PDF ",
												8,
												ie
											),
										]),
								  ]))
								: (a(),
								  d(
										"div",
										$,
										" A Health Card has not yet been generated for this Job Card. It will be created automatically once the Service Engineer closes the job. "
								  )),
						])
					);
				}
			);
		},
	};
export { ve as default };
//# sourceMappingURL=HealthCardView-u60FPupk.js.map
