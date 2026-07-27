import { _ as v } from "./StatusBadge-DZuo5DLS.js";
import {
	c as b,
	w as f,
	o as s,
	a,
	b as t,
	F as m,
	r as _,
	u as n,
	d as y,
	n as p,
	t as o,
	e as h,
	f as w,
	g as k,
	h as C,
} from "./main-C3kezhEI.js";
import "./api-Dt1AOn__.js";
const j = { class: "max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8" },
	A = { class: "flex flex-wrap gap-2 mb-6" },
	P = ["onClick"],
	B = { key: 0, class: "text-center py-12 text-gray-400" },
	V = { key: 1, class: "text-center py-12 text-gray-400" },
	F = { key: 2, class: "grid gap-4 sm:grid-cols-2 lg:grid-cols-3" },
	N = { class: "flex items-start justify-between mb-3" },
	$ = { class: "text-sm font-mono text-gray-500" },
	D = { class: "font-semibold text-gray-900" },
	I = { class: "space-y-1 text-sm text-gray-600" },
	L = { class: "mt-3 flex items-center justify-between text-xs text-gray-400" },
	R = {
		__name: "Dashboard",
		setup(O) {
			const g = [
					{ label: "All", value: "" },
					{ label: "Open", value: "Open" },
					{ label: "In Progress", value: "WIP" },
					{ label: "Awaiting Approval", value: "Awaiting Customer Approval" },
					{ label: "Awaiting Parts", value: "Awaiting Parts" },
					{ label: "Verification", value: "Verification Pending" },
					{ label: "Closed", value: "Closed" },
				],
				r = C(""),
				l = b({
					url: "vehicle_maintenance.api.job_card.get_my_job_cards",
					params: { status: "", limit: 50, offset: 0 },
					auto: !0,
				});
			return (
				f(r, (i) => {
					l.update({ params: { status: i, limit: 50, offset: 0 } }), l.fetch();
				}),
				(i, c) => {
					var d, u;
					const x = y("router-link");
					return (
						s(),
						a("div", j, [
							c[0] ||
								(c[0] = t(
									"h1",
									{ class: "text-2xl font-bold text-gray-900 mb-6" },
									"My Job Cards",
									-1
								)),
							t("div", A, [
								(s(),
								a(
									m,
									null,
									_(g, (e) =>
										t(
											"button",
											{
												key: e.value,
												onClick: (z) => (r.value = e.value),
												class: p([
													"px-3 py-1.5 text-sm font-medium rounded-full transition-colors",
													r.value === e.value
														? "bg-brand-600 text-white"
														: "bg-gray-100 text-gray-600 hover:bg-gray-200",
												]),
											},
											o(e.label),
											11,
											P
										)
									),
									64
								)),
							]),
							n(l).loading
								? (s(), a("div", B, " Loading... "))
								: (u = (d = n(l).data) == null ? void 0 : d.data) != null && u.length
								? (s(),
								  a("div", F, [
										(s(!0),
										a(
											m,
											null,
											_(
												n(l).data.data,
												(e) => (
													s(),
													h(
														x,
														{
															key: e.name,
															to: `/service-portal/job-card/${e.name}`,
															class: "bg-white border border-gray-200 rounded-xl p-4 hover:shadow-md transition-shadow",
														},
														{
															default: w(() => [
																t("div", N, [
																	t("div", null, [
																		t("p", $, o(e.name), 1),
																		t("p", D, o(e.vehicle_number), 1),
																	]),
																	k(
																		v,
																		{ state: e.workflow_state },
																		null,
																		8,
																		["state"]
																	),
																]),
																t("div", I, [
																	t("p", null, o(e.customer_name), 1),
																	t("p", null, o(e.service_type), 1),
																]),
																t("div", L, [
																	t(
																		"span",
																		{
																			class: p(
																				e.priority === "Urgent"
																					? "text-red-500 font-medium"
																					: ""
																			),
																		},
																		o(e.priority),
																		3
																	),
																]),
															]),
															_: 2,
														},
														1032,
														["to"]
													)
												)
											),
											128
										)),
								  ]))
								: (s(), a("div", V, " No job cards found. ")),
						])
					);
				}
			);
		},
	};
export { R as default };
//# sourceMappingURL=Dashboard-Dftz56ZX.js.map
