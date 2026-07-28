var A = Object.defineProperty;
var w = Object.getOwnPropertySymbols;
var B = Object.prototype.hasOwnProperty,
	R = Object.prototype.propertyIsEnumerable;
var S = (d, o, n) =>
		o in d ? A(d, o, { enumerable: !0, configurable: !0, writable: !0, value: n }) : (d[o] = n),
	N = (d, o) => {
		for (var n in o || (o = {})) B.call(o, n) && S(d, n, o[n]);
		if (w) for (var n of w(o)) R.call(o, n) && S(d, n, o[n]);
		return d;
	};
var L = (d, o, n) =>
	new Promise((b, h) => {
		var I = (u) => {
				try {
					_(n.next(u));
				} catch (m) {
					h(m);
				}
			},
			k = (u) => {
				try {
					_(n.throw(u));
				} catch (m) {
					h(m);
				}
			},
			_ = (u) => (u.done ? b(u.value) : Promise.resolve(u.value).then(I, k));
		_((n = n.apply(d, o)).next());
	});
import {
	i as V,
	o as i,
	a as r,
	b as t,
	g as D,
	f as y,
	D as z,
	u as C,
	t as v,
	j as U,
	d as K,
	B as W,
	h as j,
	q as H,
	p as O,
	F as c,
	r as g,
	n as M,
} from "./main-C3kezhEI.js";
import { _ as $ } from "./Wizard-BzvB1Oht.js";
import { u as q, a as G } from "./useCrmDropdowns-DSh1DGGD.js";
import { _ as J } from "./_plugin-vue_export-helper-DlAUqK2U.js";
import "./api-Dt1AOn__.js";
const Q = { class: "max-w-3xl mx-auto px-4 sm:px-6 lg:px-8 py-8" },
	X = { class: "mb-6" },
	Y = { class: "space-y-4" },
	Z = ["value", "onInput"],
	F = ["value", "onInput"],
	ee = ["value", "onInput"],
	te = ["value", "onInput"],
	le = { class: "space-y-4" },
	ae = ["value", "onChange"],
	se = ["value", "onChange"],
	ne = { class: "grid grid-cols-5 gap-2" },
	oe = ["onClick"],
	ie = ["value", "onInput"],
	re = { class: "space-y-4" },
	de = ["value", "onChange"],
	ue = ["value", "onInput"],
	pe = ["value", "onChange"],
	ve = ["value"],
	me = ["value", "onInput"],
	ce = { class: "space-y-4" },
	ge = ["value", "onChange"],
	be = ["value"],
	_e = ["value", "onChange"],
	ye = ["value"],
	he = { class: "grid grid-cols-3 gap-2" },
	fe = ["onClick"],
	xe = ["value", "onInput"],
	Ce = ["value", "onInput"],
	Ie = { key: 0, class: "mt-4 p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700" },
	ke = {
		__name: "LeadNew",
		setup(d) {
			const o = H(),
				n = G(),
				{ dropdowns: b, load: h } = q(),
				I = [
					"Logistics",
					"Mining",
					"Construction",
					"Municipal",
					"Agriculture",
					"Private Fleet",
					"Other",
				],
				k = ["AMC", "Spot Repair", "Full Maintenance Contract", "Parts Only", "Inspection Only"],
				_ = ["1-5", "6-20", "21-50", "51-100", "100+"],
				u = [
					"Andhra Pradesh",
					"Arunachal Pradesh",
					"Assam",
					"Bihar",
					"Chhattisgarh",
					"Goa",
					"Gujarat",
					"Haryana",
					"Himachal Pradesh",
					"Jharkhand",
					"Karnataka",
					"Kerala",
					"Madhya Pradesh",
					"Maharashtra",
					"Manipur",
					"Meghalaya",
					"Mizoram",
					"Nagaland",
					"Odisha",
					"Punjab",
					"Rajasthan",
					"Sikkim",
					"Tamil Nadu",
					"Telangana",
					"Tripura",
					"Uttar Pradesh",
					"Uttarakhand",
					"West Bengal",
					"Andaman and Nicobar Islands",
					"Chandigarh",
					"Dadra and Nagar Haveli and Daman and Diu",
					"Delhi",
					"Jammu and Kashmir",
					"Ladakh",
					"Lakshadweep",
					"Puducherry",
				];
			let m = W({
				lead_name: "",
				phone: "",
				email: "",
				company_name: "",
				industry: "",
				interested_in: "",
				fleet_size_bucket: "",
				estimated_value: "",
				state: "",
				city: "",
				depot: "",
				address_line: "",
				lead_source: "",
				assigned_to: "",
				priority: "Medium",
				expected_close_date: "",
				notes: "",
			});
			const f = j(""),
				P = [
					{
						id: "who",
						title: "Who is this lead?",
						description: "Name and primary contact.",
						validate: (p) => {
							var a;
							const e = [];
							return (
								((a = p.lead_name) != null && a.trim()) || e.push("Lead name is required."),
								(p.phone || "").replace(/\D+/g, "").length < 10 &&
									e.push("Phone must contain at least 10 digits."),
								p.email &&
									!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(p.email) &&
									e.push("Enter a valid email or leave it blank."),
								e
							);
						},
					},
					{
						id: "what",
						title: "What do they need?",
						description: "Qualification snapshot.",
						validate: () => [],
					},
					{
						id: "where",
						title: "Where are they?",
						description: "Location and depot.",
						validate: () => [],
					},
					{
						id: "assign",
						title: "Who owns it?",
						description: "Source, owner, priority.",
						validate: (p) => {
							const e = [];
							return p.priority || e.push("Pick a priority."), e;
						},
					},
				];
			function E(p) {
				return L(this, null, function* () {
					f.value = "";
					try {
						const e = N({}, p);
						e.estimated_value === "" && delete e.estimated_value,
							e.expected_close_date || delete e.expected_close_date;
						const x = yield n.create(e);
						o.push(`/service-portal/crm/leads/${encodeURIComponent(x.name)}`);
					} catch (e) {
						f.value = (e == null ? void 0 : e.message) || "Failed to create lead.";
					}
				});
			}
			return (
				V(() => h()),
				(p, e) => {
					const x = K("router-link");
					return (
						i(),
						r("div", Q, [
							t("div", X, [
								D(
									x,
									{
										to: "/service-portal/crm/leads",
										class: "text-sm text-brand-600 hover:underline",
									},
									{
										default: y(() => [...(e[1] || (e[1] = [O("← Back to leads", -1)]))]),
										_: 1,
									}
								),
								e[2] ||
									(e[2] = t(
										"h1",
										{ class: "text-2xl font-bold text-gray-900 mt-2" },
										"New Lead",
										-1
									)),
								e[3] ||
									(e[3] = t(
										"p",
										{ class: "text-sm text-gray-500 mt-1" },
										" Capture the essentials now — you can log calls and visits from the detail page. ",
										-1
									)),
							]),
							D(
								$,
								{
									steps: P,
									modelValue: C(m),
									"onUpdate:modelValue":
										e[0] || (e[0] = (a) => (z(m) ? (m.value = a) : (m = a))),
									"submit-label": "Create Lead",
									onComplete: E,
								},
								{
									"step-who": y(({ data: a, updateField: s }) => [
										t("div", Y, [
											t("div", null, [
												e[4] ||
													(e[4] = t(
														"label",
														{ class: "label" },
														"Lead Name *",
														-1
													)),
												t(
													"input",
													{
														value: a.lead_name,
														onInput: (l) => s("lead_name", l.target.value),
														type: "text",
														class: "input-field",
														placeholder: "e.g. Ravi Kumar",
													},
													null,
													40,
													Z
												),
											]),
											t("div", null, [
												e[5] ||
													(e[5] = t("label", { class: "label" }, "Phone *", -1)),
												t(
													"input",
													{
														value: a.phone,
														onInput: (l) => s("phone", l.target.value),
														type: "tel",
														inputmode: "numeric",
														class: "input-field",
														placeholder: "10-digit mobile",
													},
													null,
													40,
													F
												),
											]),
											t("div", null, [
												e[6] || (e[6] = t("label", { class: "label" }, "Email", -1)),
												t(
													"input",
													{
														value: a.email,
														onInput: (l) => s("email", l.target.value),
														type: "email",
														class: "input-field",
														placeholder: "optional",
													},
													null,
													40,
													ee
												),
											]),
											t("div", null, [
												e[7] ||
													(e[7] = t("label", { class: "label" }, "Company", -1)),
												t(
													"input",
													{
														value: a.company_name,
														onInput: (l) => s("company_name", l.target.value),
														type: "text",
														class: "input-field",
														placeholder: "optional",
													},
													null,
													40,
													te
												),
											]),
										]),
									]),
									"step-what": y(({ data: a, updateField: s }) => [
										t("div", le, [
											t("div", null, [
												e[9] ||
													(e[9] = t("label", { class: "label" }, "Industry", -1)),
												t(
													"select",
													{
														value: a.industry,
														onChange: (l) => s("industry", l.target.value),
														class: "select-field",
													},
													[
														e[8] ||
															(e[8] = t(
																"option",
																{ value: "" },
																"— Select —",
																-1
															)),
														(i(),
														r(
															c,
															null,
															g(I, (l) => t("option", { key: l }, v(l), 1)),
															64
														)),
													],
													40,
													ae
												),
											]),
											t("div", null, [
												e[11] ||
													(e[11] = t(
														"label",
														{ class: "label" },
														"Interested In",
														-1
													)),
												t(
													"select",
													{
														value: a.interested_in,
														onChange: (l) => s("interested_in", l.target.value),
														class: "select-field",
													},
													[
														e[10] ||
															(e[10] = t(
																"option",
																{ value: "" },
																"— Select —",
																-1
															)),
														(i(),
														r(
															c,
															null,
															g(k, (l) => t("option", { key: l }, v(l), 1)),
															64
														)),
													],
													40,
													se
												),
											]),
											t("div", null, [
												e[12] ||
													(e[12] = t(
														"label",
														{ class: "label" },
														"Fleet Size",
														-1
													)),
												t("div", ne, [
													(i(),
													r(
														c,
														null,
														g(_, (l) =>
															t(
																"button",
																{
																	type: "button",
																	key: l,
																	onClick: (T) => s("fleet_size_bucket", l),
																	class: M([
																		"px-3 py-2 rounded-lg border text-sm transition",
																		a.fleet_size_bucket === l
																			? "border-brand-500 bg-brand-50 text-brand-700 font-semibold"
																			: "border-gray-200 hover:border-gray-300",
																	]),
																},
																v(l),
																11,
																oe
															)
														),
														64
													)),
												]),
											]),
											t("div", null, [
												e[13] ||
													(e[13] = t(
														"label",
														{ class: "label" },
														"Estimated Value (₹)",
														-1
													)),
												t(
													"input",
													{
														value: a.estimated_value,
														onInput: (l) => s("estimated_value", l.target.value),
														type: "number",
														class: "input-field",
														placeholder: "optional",
														min: "0",
													},
													null,
													40,
													ie
												),
											]),
										]),
									]),
									"step-where": y(({ data: a, updateField: s }) => [
										t("div", re, [
											t("div", null, [
												e[15] ||
													(e[15] = t("label", { class: "label" }, "State", -1)),
												t(
													"select",
													{
														value: a.state,
														onChange: (l) => s("state", l.target.value),
														class: "select-field",
													},
													[
														e[14] ||
															(e[14] = t(
																"option",
																{ value: "" },
																"— Select —",
																-1
															)),
														(i(),
														r(
															c,
															null,
															g(u, (l) => t("option", { key: l }, v(l), 1)),
															64
														)),
													],
													40,
													de
												),
											]),
											t("div", null, [
												e[16] || (e[16] = t("label", { class: "label" }, "City", -1)),
												t(
													"input",
													{
														value: a.city,
														onInput: (l) => s("city", l.target.value),
														type: "text",
														class: "input-field",
														placeholder: "City",
													},
													null,
													40,
													ue
												),
											]),
											t("div", null, [
												e[18] ||
													(e[18] = t("label", { class: "label" }, "Depot", -1)),
												t(
													"select",
													{
														value: a.depot,
														onChange: (l) => s("depot", l.target.value),
														class: "select-field",
													},
													[
														e[17] ||
															(e[17] = t(
																"option",
																{ value: "" },
																"— Select —",
																-1
															)),
														(i(!0),
														r(
															c,
															null,
															g(
																C(b).depots,
																(l) => (
																	i(),
																	r(
																		"option",
																		{ key: l.name, value: l.name },
																		v(l.depot_name || l.name),
																		9,
																		ve
																	)
																)
															),
															128
														)),
													],
													40,
													pe
												),
											]),
											t("div", null, [
												e[19] ||
													(e[19] = t("label", { class: "label" }, "Address", -1)),
												t(
													"textarea",
													{
														value: a.address_line,
														onInput: (l) => s("address_line", l.target.value),
														rows: "2",
														class: "input-field",
														placeholder: "optional",
													},
													null,
													40,
													me
												),
											]),
										]),
									]),
									"step-assign": y(({ data: a, updateField: s }) => [
										t("div", ce, [
											t("div", null, [
												e[21] ||
													(e[21] = t("label", { class: "label" }, "Source", -1)),
												t(
													"select",
													{
														value: a.lead_source,
														onChange: (l) => s("lead_source", l.target.value),
														class: "select-field",
													},
													[
														e[20] ||
															(e[20] = t(
																"option",
																{ value: "" },
																"— Select —",
																-1
															)),
														(i(!0),
														r(
															c,
															null,
															g(
																C(b).sources,
																(l) => (
																	i(),
																	r(
																		"option",
																		{ key: l.name, value: l.name },
																		v(l.source_name),
																		9,
																		be
																	)
																)
															),
															128
														)),
													],
													40,
													ge
												),
											]),
											t("div", null, [
												e[23] ||
													(e[23] = t(
														"label",
														{ class: "label" },
														"Assigned To",
														-1
													)),
												t(
													"select",
													{
														value: a.assigned_to,
														onChange: (l) => s("assigned_to", l.target.value),
														class: "select-field",
													},
													[
														e[22] ||
															(e[22] = t(
																"option",
																{ value: "" },
																"— Select —",
																-1
															)),
														(i(!0),
														r(
															c,
															null,
															g(
																C(b).sales_users,
																(l) => (
																	i(),
																	r(
																		"option",
																		{ key: l.name, value: l.name },
																		v(l.full_name || l.name),
																		9,
																		ye
																	)
																)
															),
															128
														)),
													],
													40,
													_e
												),
											]),
											t("div", null, [
												e[24] ||
													(e[24] = t("label", { class: "label" }, "Priority", -1)),
												t("div", he, [
													(i(),
													r(
														c,
														null,
														g(["Low", "Medium", "High"], (l) =>
															t(
																"button",
																{
																	type: "button",
																	key: l,
																	onClick: (T) => s("priority", l),
																	class: M([
																		"px-3 py-2 rounded-lg border text-sm transition",
																		a.priority === l
																			? "border-brand-500 bg-brand-50 text-brand-700 font-semibold"
																			: "border-gray-200 hover:border-gray-300",
																	]),
																},
																v(l),
																11,
																fe
															)
														),
														64
													)),
												]),
											]),
											t("div", null, [
												e[25] ||
													(e[25] = t(
														"label",
														{ class: "label" },
														"Expected Close Date",
														-1
													)),
												t(
													"input",
													{
														value: a.expected_close_date,
														onInput: (l) =>
															s("expected_close_date", l.target.value),
														type: "date",
														class: "input-field",
													},
													null,
													40,
													xe
												),
											]),
											t("div", null, [
												e[26] ||
													(e[26] = t("label", { class: "label" }, "Notes", -1)),
												t(
													"textarea",
													{
														value: a.notes,
														onInput: (l) => s("notes", l.target.value),
														rows: "3",
														class: "input-field",
														placeholder: "Context for next steps",
													},
													null,
													40,
													Ce
												),
											]),
										]),
									]),
									_: 1,
								},
								8,
								["modelValue"]
							),
							f.value ? (i(), r("div", Ie, v(f.value), 1)) : U("", !0),
						])
					);
				}
			);
		},
	},
	Pe = J(ke, [["__scopeId", "data-v-5aeb1007"]]);
export { Pe as default };
//# sourceMappingURL=LeadNew-R010WMLG.js.map
