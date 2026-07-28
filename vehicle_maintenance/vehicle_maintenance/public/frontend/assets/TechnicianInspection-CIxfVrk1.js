var te = Object.defineProperty,
	se = Object.defineProperties;
var ne = Object.getOwnPropertyDescriptors;
var G = Object.getOwnPropertySymbols;
var re = Object.prototype.hasOwnProperty,
	ie = Object.prototype.propertyIsEnumerable;
var W = (l, c, u) =>
		c in l ? te(l, c, { enumerable: !0, configurable: !0, writable: !0, value: u }) : (l[c] = u),
	A = (l, c) => {
		for (var u in c || (c = {})) re.call(c, u) && W(l, u, c[u]);
		if (G) for (var u of G(c)) ie.call(c, u) && W(l, u, c[u]);
		return l;
	},
	F = (l, c) => se(l, ne(c));
var U = (l, c, u) =>
	new Promise((k, S) => {
		var h = (o) => {
				try {
					_(u.next(o));
				} catch (v) {
					S(v);
				}
			},
			j = (o) => {
				try {
					_(u.throw(o));
				} catch (v) {
					S(v);
				}
			},
			_ = (o) => (o.done ? k(o.value) : Promise.resolve(o.value).then(h, j));
		_((u = u.apply(l, c)).next());
	});
import {
	q as oe,
	w as ae,
	d as le,
	o as a,
	a as d,
	b as e,
	g as V,
	f,
	p as D,
	t as r,
	n as x,
	j as g,
	F as E,
	r as I,
	u as w,
	k as y,
	h as R,
	e as ce,
	_ as de,
} from "./main-C3kezhEI.js";
import { c as ue } from "./api-Dt1AOn__.js";
import { _ as pe } from "./Wizard-BzvB1Oht.js";
import { _ as me } from "./_plugin-vue_export-helper-DlAUqK2U.js";
const P = "Good",
	N = "Repair/Replace Recommended",
	H = "Repair/Replace Immediately",
	J = [
		{
			id: "a_engine_oil",
			label: "Engine oil level & condition",
			category: "Fluids",
			inputType: "three_tier",
		},
		{ id: "a_coolant", label: "Coolant level", category: "Fluids", inputType: "three_tier" },
		{ id: "a_brake_fluid", label: "Brake fluid level", category: "Fluids", inputType: "three_tier" },
		{
			id: "a_tyre_pressure_fl",
			label: "Tyre pressure — Front Left",
			category: "Tyres",
			inputType: "measurement",
			unit: "psi",
			min: 30,
			max: 35,
		},
		{
			id: "a_tyre_pressure_fr",
			label: "Tyre pressure — Front Right",
			category: "Tyres",
			inputType: "measurement",
			unit: "psi",
			min: 30,
			max: 35,
		},
		{
			id: "a_tyre_pressure_rl",
			label: "Tyre pressure — Rear Left",
			category: "Tyres",
			inputType: "measurement",
			unit: "psi",
			min: 30,
			max: 35,
		},
		{
			id: "a_tyre_pressure_rr",
			label: "Tyre pressure — Rear Right",
			category: "Tyres",
			inputType: "measurement",
			unit: "psi",
			min: 30,
			max: 35,
		},
		{ id: "a_lights", label: "All lights functional", category: "Electrical", inputType: "three_tier" },
		{ id: "a_horn", label: "Horn functional", category: "Electrical", inputType: "three_tier" },
		{ id: "a_wipers", label: "Wipers & washer fluid", category: "Electrical", inputType: "three_tier" },
		{ id: "a_exterior", label: "Body exterior condition", category: "Body", inputType: "text" },
	],
	q = [
		...J.map((l) => F(A({}, l), { id: l.id.replace("a_", "b_") })),
		{
			id: "b_brake_pad_fl",
			label: "Brake pad thickness — Front Left",
			category: "Brakes",
			inputType: "measurement",
			unit: "mm",
			min: 3,
			max: 12,
		},
		{
			id: "b_brake_pad_fr",
			label: "Brake pad thickness — Front Right",
			category: "Brakes",
			inputType: "measurement",
			unit: "mm",
			min: 3,
			max: 12,
		},
		{
			id: "b_brake_pad_rl",
			label: "Brake pad thickness — Rear Left",
			category: "Brakes",
			inputType: "measurement",
			unit: "mm",
			min: 3,
			max: 12,
		},
		{
			id: "b_brake_pad_rr",
			label: "Brake pad thickness — Rear Right",
			category: "Brakes",
			inputType: "measurement",
			unit: "mm",
			min: 3,
			max: 12,
		},
		{
			id: "b_tyre_tread_fl",
			label: "Tyre tread depth — Front Left",
			category: "Tyres",
			inputType: "measurement",
			unit: "mm",
			min: 1.6,
			max: 8,
		},
		{
			id: "b_tyre_tread_fr",
			label: "Tyre tread depth — Front Right",
			category: "Tyres",
			inputType: "measurement",
			unit: "mm",
			min: 1.6,
			max: 8,
		},
		{
			id: "b_suspension",
			label: "Suspension — visual check for leaks/damage",
			category: "Chassis",
			inputType: "three_tier",
		},
		{
			id: "b_exhaust",
			label: "Exhaust system — leaks or damage",
			category: "Chassis",
			inputType: "three_tier",
		},
		{
			id: "b_battery",
			label: "Battery terminal condition",
			category: "Electrical",
			inputType: "three_tier",
		},
		{ id: "b_ac", label: "A/C blows cold", category: "Comfort", inputType: "three_tier" },
		{
			id: "b_belt_condition",
			label: "Drive belt condition",
			category: "Engine",
			inputType: "three_tier",
		},
	],
	Y = [
		...q.map((l) => F(A({}, l), { id: l.id.replace("b_", "c_") })),
		{
			id: "c_transmission_fluid",
			label: "Transmission fluid level & color",
			category: "Fluids",
			inputType: "three_tier",
		},
		{
			id: "c_power_steering",
			label: "Power steering fluid",
			category: "Fluids",
			inputType: "three_tier",
		},
		{
			id: "c_wheel_bearing_fl",
			label: "Wheel bearing play — Front Left",
			category: "Chassis",
			inputType: "three_tier",
		},
		{
			id: "c_wheel_bearing_fr",
			label: "Wheel bearing play — Front Right",
			category: "Chassis",
			inputType: "three_tier",
		},
		{
			id: "c_cv_joints",
			label: "CV joint boots — cracks or leaks",
			category: "Chassis",
			inputType: "three_tier",
		},
		{
			id: "c_radiator",
			label: "Radiator — leaks, fin condition",
			category: "Engine",
			inputType: "three_tier",
		},
		{
			id: "c_timing_belt",
			label: "Timing belt/chain — condition & tension",
			category: "Engine",
			inputType: "three_tier",
		},
		{
			id: "c_undercarriage",
			label: "Undercarriage rust/corrosion",
			category: "Body",
			inputType: "three_tier",
		},
		{
			id: "c_alignment",
			label: "Wheel alignment — visual pull check",
			category: "Chassis",
			inputType: "three_tier",
		},
	],
	_e = [
		...Y.map((l) => F(A({}, l), { id: l.id.replace("c_", "d_") })),
		{
			id: "d_compression",
			label: "Engine compression test notes",
			category: "Engine",
			inputType: "text",
		},
		{
			id: "d_injector",
			label: "Fuel injector spray pattern",
			category: "Engine",
			inputType: "three_tier",
		},
		{ id: "d_egr", label: "EGR valve condition", category: "Engine", inputType: "three_tier" },
		{
			id: "d_gearbox",
			label: "Gearbox oil level & condition",
			category: "Fluids",
			inputType: "three_tier",
		},
		{ id: "d_diff", label: "Differential oil level", category: "Fluids", inputType: "three_tier" },
		{
			id: "d_body_corrosion",
			label: "Structural corrosion (chassis rails)",
			category: "Body",
			inputType: "three_tier",
		},
	],
	ge = 2e4,
	be = 4e4,
	ye = 8e4;
function he(l) {
	const c = Number(l) || 0;
	return c > ye
		? { sheetId: "D", sheetLabel: "Major Service (80,001+ km)", items: _e }
		: c > be
		? { sheetId: "C", sheetLabel: "Comprehensive Inspection (40,001–80,000 km)", items: Y }
		: c > ge
		? { sheetId: "B", sheetLabel: "Standard Inspection (20,001–40,000 km)", items: q }
		: { sheetId: "A", sheetLabel: "Basic Inspection (0–20,000 km)", items: J };
}
function O(l) {
	const c = new Map();
	for (const u of l) c.has(u.category) || c.set(u.category, []), c.get(u.category).push(u);
	return c;
}
const ve = { class: "max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-8" },
	xe = { class: "mb-6" },
	fe = { class: "space-y-4" },
	ke = ["value", "onInput"],
	Te = { class: "relative" },
	Ce = ["value", "onInput"],
	Se = { class: "text-xs ml-1" },
	Ee = { class: "space-y-5" },
	Ie = { class: "grid grid-cols-2 gap-3" },
	we = ["onClick"],
	$e = { class: "text-2xl" },
	Re = { class: "text-sm font-medium text-gray-800" },
	Be = ["value", "onInput"],
	Ae = { class: "flex gap-2 overflow-x-auto pb-3 mb-4 -mx-1 px-1" },
	Fe = ["onClick"],
	De = { key: 0, class: "ml-1" },
	je = { class: "space-y-3" },
	Le = { class: "text-sm font-medium text-gray-800 mb-2" },
	Ne = { key: 0, class: "flex gap-2" },
	He = ["onClick"],
	Oe = ["onClick"],
	Ve = ["onClick"],
	Me = { key: 1, class: "flex items-center gap-2" },
	ze = { class: "relative flex-1" },
	Ge = ["value", "onInput", "placeholder"],
	We = { class: "absolute right-3 top-1/2 -translate-y-1/2 text-xs text-gray-400" },
	Ue = { key: 0, class: "text-xs text-red-600 font-medium shrink-0" },
	Pe = { key: 2 },
	Je = ["value", "onInput"],
	qe = { class: "mt-4 text-xs text-gray-400 text-center" },
	Ye = { class: "space-y-5" },
	Ke = { key: 0, class: "space-y-2" },
	Qe = { class: "text-sm font-semibold text-red-700" },
	Xe = { class: "text-gray-800" },
	Ze = { class: "text-xs text-red-500 ml-auto" },
	et = { key: 1, class: "space-y-2" },
	tt = { class: "text-sm font-semibold text-amber-700" },
	st = { class: "text-gray-800" },
	nt = { class: "text-xs text-amber-600 ml-auto" },
	rt = { key: 2, class: "space-y-2" },
	it = { class: "text-sm font-semibold text-amber-700" },
	ot = { class: "text-gray-800" },
	at = { class: "font-mono text-amber-700" },
	lt = { class: "text-xs text-gray-400" },
	ct = { key: 3, class: "text-center py-6" },
	dt = ["value", "onInput"],
	ut = { class: "space-y-4" },
	pt = { class: "bg-gray-50 rounded-xl p-4 space-y-3 text-sm" },
	mt = { class: "flex justify-between" },
	_t = { class: "font-medium text-gray-900" },
	gt = { class: "flex justify-between" },
	bt = { class: "font-medium text-gray-900" },
	yt = { class: "flex justify-between" },
	ht = { class: "font-medium text-gray-900" },
	vt = { class: "flex justify-between" },
	xt = { class: "font-medium text-gray-900" },
	ft = { class: "flex justify-between" },
	kt = { class: "font-medium text-gray-900" },
	Tt = { class: "flex justify-between" },
	Ct = { key: 0, class: "bg-gray-50 rounded-xl p-4 text-sm" },
	St = { class: "text-gray-900 mt-1" },
	Et = { key: 1, class: "p-3 bg-amber-50 border border-amber-200 rounded-lg text-sm text-amber-800" },
	It = { key: 0, class: "mt-4 p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700" },
	wt = {
		key: 1,
		class: "mt-4 p-3 bg-brand-50 border border-brand-200 rounded-lg text-sm text-brand-700 text-center",
	},
	$t = {
		__name: "JobCardCreationWizard",
		setup(l) {
			const c = oe(),
				u = R(null),
				k = R(""),
				S = R(!1),
				h = R(0),
				j = [
					{ value: "Scheduled Maintenance", label: "Routine Service", icon: "🔧" },
					{ value: "Breakdown Repair", label: "Breakdown", icon: "🚨" },
					{ value: "Body & Paint", label: "Body & Paint", icon: "🎨" },
					{ value: "Electrical", label: "Electrical", icon: "⚡" },
					{ value: "Tyre & Alignment", label: "Tyres", icon: "⭕" },
					{ value: "General Inspection", label: "Inspection", icon: "🔍" },
				],
				_ = R({
					vehicle_number: "",
					odometer_reading: null,
					service_type: "",
					complaint_description: "",
					technician_notes: "",
				}),
				o = y(() => he(_.value.odometer_reading)),
				v = y(() => [...O(o.value.items).keys()]),
				K = y(() => {
					const n = O(o.value.items),
						t = v.value[h.value];
					return n.get(t) || [];
				});
			ae(
				() => o.value.sheetId,
				() => {
					h.value = 0;
				}
			);
			const T = y(() =>
					o.value.items.filter(
						(n) => n.inputType === "three_tier" && _.value[`inspection_${n.id}`] === H
					)
				),
				$ = y(() =>
					o.value.items.filter(
						(n) => n.inputType === "three_tier" && _.value[`inspection_${n.id}`] === N
					)
				),
				C = y(() =>
					o.value.items.filter((n) =>
						n.inputType !== "measurement" ? !1 : L(n, _.value[`inspection_${n.id}`])
					)
				),
				B = y(
					() =>
						o.value.items.filter(
							(n) =>
								_.value[`inspection_${n.id}`] != null && _.value[`inspection_${n.id}`] !== ""
						).length
				),
				Q = y(
					() =>
						({
							A: "bg-green-50 border-green-200 text-green-800",
							B: "bg-amber-50 border-amber-200 text-amber-800",
							C: "bg-orange-50 border-orange-200 text-orange-800",
							D: "bg-red-50 border-red-200 text-red-800",
						}[o.value.sheetId])
				);
			function X(n, t) {
				const p = Number(n.target.value);
				t("odometer_reading", p > 0 ? p : null);
			}
			function L(n, t) {
				if (t == null || t === "") return !1;
				const p = Number(t);
				return p < n.min || p > n.max;
			}
			function M(n, t) {
				return (O(o.value.items).get(n) || []).every(
					(m) => t[`inspection_${m.id}`] != null && t[`inspection_${m.id}`] !== ""
				);
			}
			const z = y(() => [
				{
					id: "vehicle",
					title: "Vehicle",
					description: "Scan or enter the vehicle registration.",
					validate: (n) => {
						var p;
						const t = [];
						return (
							((p = n.vehicle_number) != null && p.trim()) ||
								t.push("Vehicle number is required."),
							(!n.odometer_reading || n.odometer_reading <= 0) &&
								t.push("Enter a valid odometer reading."),
							t
						);
					},
				},
				{
					id: "service",
					title: "Service Type",
					description: "Tap the type of work needed.",
					validate: (n) => (n.service_type ? [] : ["Select a service type."]),
				},
				{
					id: "inspection",
					title: `Inspection — Sheet ${o.value.sheetId}`,
					description: `${o.value.items.length} checks across ${v.value.length} categories.`,
				},
				{ id: "findings", title: "Findings", description: "Review what the inspection found." },
				{
					id: "review",
					title: "Review & Submit",
					description: "Confirm everything looks right before submitting.",
				},
			]);
			function Z(n) {
				var t;
				((t = z.value[n]) == null ? void 0 : t.id) === "inspection" && (h.value = 0);
			}
			function ee(n) {
				return U(this, null, function* () {
					var t;
					(k.value = ""), (S.value = !0);
					try {
						const p = {};
						for (const m of o.value.items) {
							const s = `inspection_${m.id}`;
							n[s] != null &&
								n[s] !== "" &&
								(p[m.id] = {
									value: n[s],
									label: m.label,
									category: m.category,
									inputType: m.inputType,
								});
						}
						const i = yield ue("job_card.create_job_card_with_inspection", {
							vehicle_number: n.vehicle_number,
							odometer_reading: n.odometer_reading,
							service_type: n.service_type,
							complaint_description: n.complaint_description || "",
							technician_notes: n.technician_notes || "",
							inspection_sheet_id: o.value.sheetId,
							inspection_results: JSON.stringify(p),
						});
						i.success
							? c.push(`/service-portal/job-card/${i.data.name}`)
							: (k.value = i.message || "Submission failed.");
					} catch (p) {
						k.value =
							(p == null ? void 0 : p.exc_type) === "ValidationError"
								? p.message
								: ((t = p == null ? void 0 : p.messages) == null ? void 0 : t[0]) ||
								  "Failed to create job card. Please try again.";
					} finally {
						S.value = !1;
					}
				});
			}
			return (n, t) => {
				const p = le("router-link");
				return (
					a(),
					d("div", ve, [
						e("div", xe, [
							V(
								p,
								{ to: "/service-portal", class: "text-sm text-brand-600 hover:underline" },
								{
									default: f(() => [
										...(t[1] || (t[1] = [D(" ← Back to dashboard ", -1)])),
									]),
									_: 1,
								}
							),
							t[2] ||
								(t[2] = e(
									"h1",
									{ class: "text-2xl font-bold text-gray-900 mt-2" },
									"New Inspection",
									-1
								)),
						]),
						V(
							pe,
							{
								ref_key: "wizardRef",
								ref: u,
								steps: z.value,
								modelValue: _.value,
								"onUpdate:modelValue": t[0] || (t[0] = (i) => (_.value = i)),
								"submit-label": "Submit Job Card",
								onComplete: ee,
								onStepChange: Z,
							},
							{
								"step-vehicle": f(({ data: i, updateField: m }) => [
									e("div", fe, [
										e("div", null, [
											t[3] ||
												(t[3] = e(
													"label",
													{ class: "block text-sm font-medium text-gray-700 mb-1" },
													" Vehicle Registration Number ",
													-1
												)),
											e(
												"input",
												{
													value: i.vehicle_number,
													onInput: (s) =>
														m("vehicle_number", s.target.value.toUpperCase()),
													type: "text",
													placeholder: "e.g. KA-01-AB-1234",
													class: "input-field uppercase",
													autocapitalize: "characters",
												},
												null,
												40,
												ke
											),
										]),
										e("div", null, [
											t[5] ||
												(t[5] = e(
													"label",
													{ class: "block text-sm font-medium text-gray-700 mb-1" },
													" Current Odometer Reading ",
													-1
												)),
											e("div", Te, [
												e(
													"input",
													{
														value: i.odometer_reading,
														onInput: (s) => X(s, m),
														type: "number",
														inputmode: "numeric",
														min: "0",
														placeholder: "e.g. 45000",
														class: "input-field pr-12",
													},
													null,
													40,
													Ce
												),
												t[4] ||
													(t[4] = e(
														"span",
														{
															class: "absolute right-3 top-1/2 -translate-y-1/2 text-sm text-gray-400",
														},
														"km",
														-1
													)),
											]),
										]),
										i.odometer_reading > 0
											? (a(),
											  d(
													"div",
													{
														key: 0,
														class: x([
															"mt-3 p-3 rounded-lg border text-sm",
															Q.value,
														]),
													},
													[
														t[6] ||
															(t[6] = e(
																"span",
																{ class: "font-medium" },
																"Auto-selected:",
																-1
															)),
														D(" " + r(o.value.sheetLabel) + " ", 1),
														e(
															"span",
															Se,
															"(" + r(o.value.items.length) + " checks)",
															1
														),
													],
													2
											  ))
											: g("", !0),
									]),
								]),
								"step-service": f(({ data: i, updateField: m }) => [
									e("div", Ee, [
										e("div", null, [
											t[7] ||
												(t[7] = e(
													"label",
													{ class: "block text-sm font-medium text-gray-700 mb-2" },
													" What type of service? ",
													-1
												)),
											e("div", Ie, [
												(a(),
												d(
													E,
													null,
													I(j, (s) =>
														e(
															"button",
															{
																key: s.value,
																type: "button",
																onClick: (b) => m("service_type", s.value),
																class: x([
																	"flex items-center gap-3 p-3 rounded-xl border-2 text-left transition-all",
																	i.service_type === s.value
																		? "border-brand-500 bg-brand-50 ring-1 ring-brand-500"
																		: "border-gray-200 hover:border-gray-300",
																]),
															},
															[
																e("span", $e, r(s.icon), 1),
																e("span", Re, r(s.label), 1),
															],
															10,
															we
														)
													),
													64
												)),
											]),
										]),
										e("div", null, [
											t[8] ||
												(t[8] = e(
													"label",
													{ class: "block text-sm font-medium text-gray-700 mb-1" },
													" Describe the issue (optional for routine service) ",
													-1
												)),
											e(
												"textarea",
												{
													value: i.complaint_description,
													onInput: (s) =>
														m("complaint_description", s.target.value),
													rows: "3",
													placeholder: "Any specific complaints or observations...",
													class: "input-field",
												},
												null,
												40,
												Be
											),
										]),
									]),
								]),
								"step-inspection": f(({ data: i, updateField: m }) => [
									e("div", null, [
										e("div", Ae, [
											(a(!0),
											d(
												E,
												null,
												I(
													v.value,
													(s, b) => (
														a(),
														d(
															"button",
															{
																key: s,
																type: "button",
																onClick: (Bt) => (h.value = b),
																class: x([
																	"shrink-0 px-3 py-1.5 text-xs font-medium rounded-full whitespace-nowrap transition-colors",
																	h.value === b
																		? "bg-brand-600 text-white"
																		: M(s, i)
																		? "bg-green-100 text-green-700"
																		: "bg-gray-100 text-gray-600",
																]),
															},
															[
																D(r(s) + " ", 1),
																M(s, i)
																	? (a(), d("span", De, "✓"))
																	: g("", !0),
															],
															10,
															Fe
														)
													)
												),
												128
											)),
										]),
										e("div", je, [
											(a(!0),
											d(
												E,
												null,
												I(
													K.value,
													(s) => (
														a(),
														d(
															"div",
															{
																key: s.id,
																class: "bg-white border border-gray-200 rounded-xl p-4",
															},
															[
																e("p", Le, r(s.label), 1),
																s.inputType === "three_tier"
																	? (a(),
																	  d("div", Ne, [
																			e(
																				"button",
																				{
																					type: "button",
																					onClick: (b) =>
																						m(
																							`inspection_${s.id}`,
																							w(P)
																						),
																					class: x([
																						"flex-1 py-2.5 text-xs font-medium rounded-lg border-2 transition-all",
																						i[
																							`inspection_${s.id}`
																						] === w(P)
																							? "border-green-500 bg-green-50 text-green-700"
																							: "border-gray-200 text-gray-500 hover:border-gray-300",
																					]),
																				},
																				" Good ",
																				10,
																				He
																			),
																			e(
																				"button",
																				{
																					type: "button",
																					onClick: (b) =>
																						m(
																							`inspection_${s.id}`,
																							w(N)
																						),
																					class: x([
																						"flex-1 py-2.5 text-xs font-medium rounded-lg border-2 transition-all",
																						i[
																							`inspection_${s.id}`
																						] === w(N)
																							? "border-amber-500 bg-amber-50 text-amber-700"
																							: "border-gray-200 text-gray-500 hover:border-gray-300",
																					]),
																				},
																				" Recommended ",
																				10,
																				Oe
																			),
																			e(
																				"button",
																				{
																					type: "button",
																					onClick: (b) =>
																						m(
																							`inspection_${s.id}`,
																							w(H)
																						),
																					class: x([
																						"flex-1 py-2.5 text-xs font-medium rounded-lg border-2 transition-all",
																						i[
																							`inspection_${s.id}`
																						] === w(H)
																							? "border-red-500 bg-red-50 text-red-700"
																							: "border-gray-200 text-gray-500 hover:border-gray-300",
																					]),
																				},
																				" Immediate ",
																				10,
																				Ve
																			),
																	  ]))
																	: s.inputType === "measurement"
																	? (a(),
																	  d("div", Me, [
																			e("div", ze, [
																				e(
																					"input",
																					{
																						value: i[
																							`inspection_${s.id}`
																						],
																						onInput: (b) =>
																							m(
																								`inspection_${s.id}`,
																								b.target.value
																							),
																						type: "number",
																						inputmode: "decimal",
																						step: "0.1",
																						placeholder: `${s.min}–${s.max}`,
																						class: x([
																							"input-field pr-12",
																							L(
																								s,
																								i[
																									`inspection_${s.id}`
																								]
																							)
																								? "border-red-400 bg-red-50"
																								: "",
																						]),
																					},
																					null,
																					42,
																					Ge
																				),
																				e("span", We, r(s.unit), 1),
																			]),
																			i[`inspection_${s.id}`] &&
																			L(s, i[`inspection_${s.id}`])
																				? (a(),
																				  d(
																						"div",
																						Ue,
																						" Out of range "
																				  ))
																				: g("", !0),
																	  ]))
																	: s.inputType === "text"
																	? (a(),
																	  d("div", Pe, [
																			e(
																				"textarea",
																				{
																					value: i[
																						`inspection_${s.id}`
																					],
																					onInput: (b) =>
																						m(
																							`inspection_${s.id}`,
																							b.target.value
																						),
																					rows: "2",
																					placeholder: "Notes...",
																					class: "input-field text-sm",
																				},
																				null,
																				40,
																				Je
																			),
																	  ]))
																	: g("", !0),
															]
														)
													)
												),
												128
											)),
										]),
										e(
											"div",
											qe,
											" Category " +
												r(h.value + 1) +
												" of " +
												r(v.value.length) +
												" · " +
												r(B.value) +
												" / " +
												r(o.value.items.length) +
												" checks done ",
											1
										),
									]),
								]),
								"step-findings": f(({ data: i, updateField: m }) => [
									e("div", Ye, [
										T.value.length
											? (a(),
											  d("div", Ke, [
													e(
														"h3",
														Qe,
														r(T.value.length) +
															" item" +
															r(T.value.length > 1 ? "s need" : " needs") +
															" immediate action ",
														1
													),
													(a(!0),
													d(
														E,
														null,
														I(
															T.value,
															(s) => (
																a(),
																d(
																	"div",
																	{
																		key: s.id,
																		class: "flex items-center gap-3 p-3 bg-red-50 border border-red-200 rounded-lg text-sm",
																	},
																	[
																		t[9] ||
																			(t[9] = e(
																				"span",
																				{
																					class: "text-red-500 font-bold shrink-0",
																				},
																				"✗",
																				-1
																			)),
																		e("span", Xe, r(s.label), 1),
																		e("span", Ze, r(s.category), 1),
																	]
																)
															)
														),
														128
													)),
											  ]))
											: g("", !0),
										$.value.length
											? (a(),
											  d("div", et, [
													e(
														"h3",
														tt,
														r($.value.length) +
															" repair" +
															r($.value.length > 1 ? "s" : "") +
															" recommended ",
														1
													),
													(a(!0),
													d(
														E,
														null,
														I(
															$.value,
															(s) => (
																a(),
																d(
																	"div",
																	{
																		key: s.id,
																		class: "flex items-center gap-3 p-3 bg-amber-50 border border-amber-200 rounded-lg text-sm",
																	},
																	[
																		t[10] ||
																			(t[10] = e(
																				"span",
																				{
																					class: "text-amber-500 font-bold shrink-0",
																				},
																				"⚠",
																				-1
																			)),
																		e("span", st, r(s.label), 1),
																		e("span", nt, r(s.category), 1),
																	]
																)
															)
														),
														128
													)),
											  ]))
											: g("", !0),
										C.value.length
											? (a(),
											  d("div", rt, [
													e(
														"h3",
														it,
														r(C.value.length) +
															" measurement" +
															r(C.value.length > 1 ? "s" : "") +
															" out of range ",
														1
													),
													(a(!0),
													d(
														E,
														null,
														I(
															C.value,
															(s) => (
																a(),
																d(
																	"div",
																	{
																		key: s.id,
																		class: "flex items-center justify-between p-3 bg-amber-50 border border-amber-200 rounded-lg text-sm",
																	},
																	[
																		e("span", ot, r(s.label), 1),
																		e("span", at, [
																			D(
																				r(i[`inspection_${s.id}`]) +
																					" " +
																					r(s.unit) +
																					" ",
																				1
																			),
																			e(
																				"span",
																				lt,
																				"(" +
																					r(s.min) +
																					"–" +
																					r(s.max) +
																					")",
																				1
																			),
																		]),
																	]
																)
															)
														),
														128
													)),
											  ]))
											: g("", !0),
										!T.value.length && !$.value.length && !C.value.length
											? (a(),
											  d("div", ct, [
													...(t[11] ||
														(t[11] = [
															e("div", { class: "text-4xl mb-2" }, "✓", -1),
															e(
																"p",
																{ class: "text-green-700 font-medium" },
																"All components rated Good",
																-1
															),
															e(
																"p",
																{ class: "text-sm text-gray-500 mt-1" },
																"No repairs recommended at this time",
																-1
															),
														])),
											  ]))
											: g("", !0),
										e("div", null, [
											t[12] ||
												(t[12] = e(
													"label",
													{ class: "block text-sm font-medium text-gray-700 mb-1" },
													" Additional technician notes ",
													-1
												)),
											e(
												"textarea",
												{
													value: i.technician_notes,
													onInput: (s) => m("technician_notes", s.target.value),
													rows: "3",
													placeholder:
														"Any observations, recommendations, or context...",
													class: "input-field",
												},
												null,
												40,
												dt
											),
										]),
									]),
								]),
								"step-review": f(({ data: i }) => [
									e("div", ut, [
										t[20] ||
											(t[20] = e(
												"h3",
												{
													class: "text-sm font-semibold text-gray-500 uppercase tracking-wider",
												},
												"Summary",
												-1
											)),
										e("div", pt, [
											e("div", mt, [
												t[13] ||
													(t[13] = e(
														"span",
														{ class: "text-gray-500" },
														"Vehicle",
														-1
													)),
												e("span", _t, r(i.vehicle_number), 1),
											]),
											e("div", gt, [
												t[14] ||
													(t[14] = e(
														"span",
														{ class: "text-gray-500" },
														"Odometer",
														-1
													)),
												e(
													"span",
													bt,
													r(Number(i.odometer_reading).toLocaleString()) + " km",
													1
												),
											]),
											e("div", yt, [
												t[15] ||
													(t[15] = e(
														"span",
														{ class: "text-gray-500" },
														"Service Type",
														-1
													)),
												e("span", ht, r(i.service_type), 1),
											]),
											e("div", vt, [
												t[16] ||
													(t[16] = e(
														"span",
														{ class: "text-gray-500" },
														"Inspection Sheet",
														-1
													)),
												e("span", xt, "Sheet " + r(o.value.sheetId), 1),
											]),
											e("div", ft, [
												t[17] ||
													(t[17] = e(
														"span",
														{ class: "text-gray-500" },
														"Checks Completed",
														-1
													)),
												e(
													"span",
													kt,
													r(B.value) + " / " + r(o.value.items.length),
													1
												),
											]),
											e("div", Tt, [
												t[18] ||
													(t[18] = e(
														"span",
														{ class: "text-gray-500" },
														"Issues Found",
														-1
													)),
												e(
													"span",
													{
														class: x([
															"font-medium",
															T.value.length + C.value.length > 0
																? "text-red-600"
																: "text-green-600",
														]),
													},
													r(T.value.length + C.value.length),
													3
												),
											]),
										]),
										i.technician_notes
											? (a(),
											  d("div", Ct, [
													t[19] ||
														(t[19] = e(
															"span",
															{ class: "text-gray-500" },
															"Notes:",
															-1
														)),
													e("p", St, r(i.technician_notes), 1),
											  ]))
											: g("", !0),
										B.value < o.value.items.length
											? (a(),
											  d(
													"div",
													Et,
													r(o.value.items.length - B.value) +
														" check(s) are incomplete. You can still submit, but it will be flagged. ",
													1
											  ))
											: g("", !0),
									]),
								]),
								_: 1,
							},
							8,
							["steps", "modelValue"]
						),
						k.value ? (a(), d("div", It, r(k.value), 1)) : g("", !0),
						S.value ? (a(), d("div", wt, " Submitting inspection... ")) : g("", !0),
					])
				);
			};
		},
	},
	Rt = me($t, [["__scopeId", "data-v-26a73492"]]),
	Nt = {
		__name: "TechnicianInspection",
		setup(l) {
			return (c, u) => (
				a(),
				ce(
					de,
					{ roles: ["Technician", "Service Engineer", "Depot Manager"] },
					{
						fallback: f(() => [
							...(u[0] ||
								(u[0] = [
									e(
										"div",
										{ class: "text-center py-16 text-gray-500" },
										" You do not have permission to perform inspections. ",
										-1
									),
								])),
						]),
						default: f(() => [V(Rt)]),
						_: 1,
					}
				)
			);
		},
	};
export { Nt as default };
//# sourceMappingURL=TechnicianInspection-CIxfVrk1.js.map
