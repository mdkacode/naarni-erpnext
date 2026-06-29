"""Dashboard APIs for Depot Manager, Central Ops, and Customer views."""

import frappe
from frappe import _


@frappe.whitelist()
def get_depot_manager_dashboard(depot: str = "") -> dict:
	"""Depot Manager: summary of their depot — open cards, SLA breaches, fleet status."""
	frappe.only_for(["Depot Manager", "System Manager", "Administrator"])

	filters = {}
	if depot:
		filters["depot"] = depot

	# Job card counts by state
	states = frappe.db.sql(
		"""
        SELECT workflow_state, COUNT(*) as count
        FROM `tabJob Card`
        {where}
        GROUP BY workflow_state
        ORDER BY count DESC
    """.format(where="WHERE depot = %(depot)s" if depot else ""),
		{"depot": depot} if depot else {},
		as_dict=True,
	)

	# SLA breaches
	breached = frappe.db.count(
		"Job Card", filters={**filters, "sla_breached": 1, "workflow_state": ["!=", "Closed"]}
	)

	# Priority breakdown
	priorities = frappe.db.sql(
		"""
        SELECT priority, COUNT(*) as count
        FROM `tabJob Card`
        WHERE workflow_state != 'Closed'
        {depot_filter}
        GROUP BY priority
    """.format(depot_filter="AND depot = %(depot)s" if depot else ""),
		{"depot": depot} if depot else {},
		as_dict=True,
	)

	# Recent job cards
	recent = frappe.get_list(
		"Job Card",
		filters={**filters, "workflow_state": ["!=", "Closed"]},
		fields=[
			"name",
			"vehicle_number",
			"vehicle_make_model",
			"job_card_type",
			"priority",
			"workflow_state",
			"sla_breached",
			"depot",
			"customer_name",
			"modified",
		],
		order_by="modified desc",
		limit_page_length=20,
	)

	# Fleet count per depot
	fleet = frappe.db.sql(
		"""
        SELECT v.make_model, COUNT(*) as count
        FROM tabVehicle v
        {join}
        GROUP BY v.make_model
        ORDER BY count DESC
    """.format(join="INNER JOIN tabDepot d ON 1=1" if not depot else ""),
		as_dict=True,
	)

	total_open = sum(s["count"] for s in states if s["workflow_state"] != "Closed")
	total_closed = sum(s["count"] for s in states if s["workflow_state"] == "Closed")

	return {
		"success": True,
		"data": {
			"states": states,
			"total_open": total_open,
			"total_closed": total_closed,
			"sla_breached": breached,
			"priorities": priorities,
			"recent_cards": recent,
			"fleet": fleet,
		},
	}


@frappe.whitelist()
def get_central_ops_dashboard() -> dict:
	"""Central Ops: cross-depot analytics."""
	frappe.only_for(["Central Ops", "Depot Manager", "System Manager", "Administrator"])

	# Cards by depot
	by_depot = frappe.db.sql(
		"""
        SELECT depot, workflow_state, COUNT(*) as count
        FROM `tabJob Card`
        GROUP BY depot, workflow_state
        ORDER BY depot, workflow_state
    """,
		as_dict=True,
	)

	# SLA breaches by depot
	breaches_by_depot = frappe.db.sql(
		"""
        SELECT depot, COUNT(*) as count
        FROM `tabJob Card`
        WHERE sla_breached = 1 AND workflow_state != 'Closed'
        GROUP BY depot
    """,
		as_dict=True,
	)

	# Job card type distribution
	by_type = frappe.db.sql(
		"""
        SELECT job_card_type, COUNT(*) as count
        FROM `tabJob Card`
        GROUP BY job_card_type
        ORDER BY count DESC
    """,
		as_dict=True,
	)

	# TAT: avg time to close (hours) per depot
	tat_by_depot = frappe.db.sql(
		"""
        SELECT depot,
            ROUND(AVG(TIMESTAMPDIFF(MINUTE, opened_at, closed_at) / 60.0), 1) as avg_hours,
            COUNT(*) as closed_count
        FROM `tabJob Card`
        WHERE workflow_state = 'Closed' AND opened_at IS NOT NULL AND closed_at IS NOT NULL
        GROUP BY depot
    """,
		as_dict=True,
	)

	# Fleet health: buses with active job cards
	buses_in_service = frappe.db.sql(
		"""
        SELECT COUNT(DISTINCT vehicle) as count
        FROM `tabJob Card`
        WHERE workflow_state NOT IN ('Closed')
    """,
		as_dict=True,
	)[0]["count"]

	total_buses = frappe.db.count("Vehicle")

	# Cost summary
	cost_summary = frappe.db.sql(
		"""
        SELECT
            SUM(estimated_cost) as total_estimated,
            SUM(actual_cost) as total_actual,
            COUNT(*) as total_cards
        FROM `tabJob Card`
    """,
		as_dict=True,
	)[0]

	return {
		"success": True,
		"data": {
			"by_depot": by_depot,
			"breaches_by_depot": breaches_by_depot,
			"by_type": by_type,
			"tat_by_depot": tat_by_depot,
			"total_buses": total_buses,
			"buses_in_service": buses_in_service,
			"cost_summary": cost_summary,
		},
	}


@frappe.whitelist()
def get_customer_dashboard() -> dict:
	"""Customer: their fleet health, active cards, PMS history."""
	user = frappe.session.user
	customer = frappe.db.get_value("Customer", {"user": user}, "name")
	if not customer:
		return {"success": True, "data": {"vehicles": [], "active_cards": [], "history": []}}

	# Their vehicles
	vehicles = frappe.get_list(
		"Vehicle",
		filters={"customer": customer},
		fields=["name", "registration_number", "make_model", "fuel_type", "color", "year_of_manufacture"],
		order_by="registration_number asc",
		limit_page_length=100,
	)

	# Active job cards
	active_cards = frappe.get_list(
		"Job Card",
		filters={"customer": customer, "workflow_state": ["!=", "Closed"]},
		fields=[
			"name",
			"vehicle_number",
			"vehicle_make_model",
			"job_card_type",
			"priority",
			"workflow_state",
			"sla_breached",
			"estimated_cost",
			"depot",
			"modified",
		],
		order_by="modified desc",
		limit_page_length=50,
	)

	# Closed cards (history) with health scores
	history = frappe.get_list(
		"Job Card",
		filters={"customer": customer, "workflow_state": "Closed"},
		fields=[
			"name",
			"vehicle_number",
			"job_card_type",
			"pre_pms_score",
			"post_pms_score",
			"score_improvement",
			"estimated_cost",
			"actual_cost",
			"closed_at",
			"depot",
		],
		order_by="closed_at desc",
		limit_page_length=50,
	)

	# Fleet health: latest PMS score per vehicle
	fleet_health = frappe.db.sql(
		"""
        SELECT vehicle_number, vehicle_make_model,
            MAX(post_pms_score) as latest_health_score,
            MAX(closed_at) as last_service_date
        FROM `tabJob Card`
        WHERE customer = %(customer)s AND job_card_type = 'PMS + Repair'
            AND workflow_state = 'Closed' AND post_pms_score > 0
        GROUP BY vehicle_number, vehicle_make_model
        ORDER BY vehicle_number
    """,
		{"customer": customer},
		as_dict=True,
	)

	# Cost totals
	cost_totals = frappe.db.sql(
		"""
        SELECT
            COUNT(*) as total_cards,
            SUM(estimated_cost) as total_estimated,
            SUM(actual_cost) as total_actual
        FROM `tabJob Card`
        WHERE customer = %(customer)s
    """,
		{"customer": customer},
		as_dict=True,
	)[0]

	return {
		"success": True,
		"data": {
			"customer_name": customer,
			"vehicles": vehicles,
			"active_cards": active_cards,
			"history": history,
			"fleet_health": fleet_health,
			"cost_totals": cost_totals,
		},
	}


@frappe.whitelist()
def get_pms_report(job_card_name: str) -> dict:
	"""Detailed PMS report for a single job card — used for PDF generation."""
	frappe.has_permission("Job Card", doc=job_card_name, throw=True)

	doc = frappe.get_doc("Job Card", job_card_name)

	repair_items = []
	for item in doc.get("repair_items", []):
		repair_items.append(
			{
				"part_group": item.part_group,
				"bus_system": item.bus_system,
				"description": item.description,
				"activity_type": item.activity_type,
				"component_status": item.component_status,
				"qty": float(item.qty or 0),
				"rate": float(item.rate or 0),
				"estimated_amount": float(item.estimated_amount or 0),
				"actual_amount": float(item.actual_amount or 0),
				"item_status": item.item_status,
				"pre_repair_photo": item.pre_repair_photo,
				"post_repair_photo": item.post_repair_photo,
			}
		)

	maintenance_items = []
	for item in doc.get("maintenance_items", []):
		maintenance_items.append(
			{
				"maintenance_type": item.maintenance_type,
				"description": item.description,
				"action": item.action,
				"qty": float(item.qty or 0),
				"unit": item.unit,
				"rate": float(item.rate or 0),
				"estimated_amount": float(item.estimated_amount or 0),
				"actual_amount": float(item.actual_amount or 0),
				"item_status": item.item_status,
				"pre_photo": item.pre_photo,
				"post_photo": item.post_photo,
			}
		)

	return {
		"success": True,
		"data": {
			"name": doc.name,
			"job_card_type": doc.job_card_type,
			"vehicle_number": doc.vehicle_number,
			"vehicle_make_model": doc.vehicle_make_model,
			"oem": doc.oem,
			"odometer_reading": doc.odometer_reading,
			"customer_name": doc.customer_name,
			"depot": doc.depot,
			"service_type": doc.service_type,
			"priority": doc.priority,
			"workflow_state": doc.workflow_state,
			"check_sheet": doc.check_sheet,
			"complaint_description": doc.complaint_description,
			"se_observations": doc.se_observations,
			"pre_pms_score": float(doc.pre_pms_score or 0),
			"post_pms_score": float(doc.post_pms_score or 0),
			"score_improvement": float(doc.score_improvement or 0),
			"estimated_cost": float(doc.estimated_cost or 0),
			"actual_cost": float(doc.actual_cost or 0),
			"opened_at": str(doc.opened_at) if doc.opened_at else None,
			"closed_at": str(doc.closed_at) if doc.closed_at else None,
			"sla_target_hours": float(doc.sla_target_hours or 0),
			"sla_breached": doc.sla_breached,
			"repair_items": repair_items,
			"maintenance_items": maintenance_items,
			"qr_code_image": doc.qr_code_image,
			"arrival_photo_front": doc.arrival_photo_front,
			"arrival_photo_rear": doc.arrival_photo_rear,
			"arrival_photo_left": doc.arrival_photo_left,
			"arrival_photo_right": doc.arrival_photo_right,
		},
	}
