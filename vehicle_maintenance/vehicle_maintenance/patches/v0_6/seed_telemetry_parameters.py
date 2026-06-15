"""Seed the Telemetry Parameter catalog from the vehicle_recent_info_silver schema.

Idempotent. Numeric columns get type Numeric; the derived varchar columns get type
Categorical with their known value sets; booleans get Boolean (true/false). Identity
and timestamp columns are skipped (not alertable). Re-run safe via `bench migrate`.
"""

import frappe

# Categorical varchar columns + their value sets (derived in the silver runner —
# data-naarni/.../derived_classifiers.py). Refine via Trino DISTINCT if needed.
CATEGORICAL_VALUES = {
	"connectivity_status": ["ONLINE", "OFFLINE"],
	"activity": ["CHARGING", "DISCHARGING", "NA"],
	"gun_thermal_status": ["NORMAL", "WARN", "CRIT", "NA"],
	"pack_thermal_status": ["NORMAL", "WARN", "CRIT", "NA"],
}

# Other varchar columns that are categorical-ish but without a fixed code set —
# seeded as Categorical with no preset values (admin can add values later).
FREE_CATEGORICAL = {"route_name", "operator", "model", "make", "last_msg_rcvd_interval", "gun_hottest_pin"}

# Identity / timestamp columns — not alert conditions.
SKIP = {"device_id", "registration_number", "timestamp", "ts_ist", "updated_at", "date"}

# Optional display units for common numeric params.
UNITS = {
	"vehicle_speed_vcu": "km/h",
	"ground_speed_kmph": "km/h",
	"bat_soc": "%",
	"soh": "%",
	"non_null_pct": "%",
	"bat_voltage": "V",
	"hv_voltage": "V",
	"cell_max_voltage": "V",
	"cell_min_voltage": "V",
	"pack1_cellmax_temperature": "°C",
	"pack1_cell_min_temperature": "°C",
	"motor_temperature": "°C",
	"igbt_temperature": "°C",
	"cellmax_c": "°C",
	"coolant_c": "°C",
	"outside_temp": "°C",
	"cabin_temp": "°C",
	"total_battery_current": "A",
	"charger_current": "A",
	"motor_rpm": "rpm",
	"motor_torque": "Nm",
	"odometerreading": "km",
	"distancetoempty": "km",
	"last_msg_interval_mins": "min",
}

# column -> sql type (from vehicle_recent_info_silver). Tab-separated.
SCHEMA = """device_id\tvarchar
registration_number\tvarchar
route_name\tvarchar
operator\tvarchar
activity\tvarchar
date\tdate
dt_sec\tdouble
timestamp\ttimestamp
ts_ist\ttimestamp
last_msg_rcvd_interval\tvarchar
last_msg_interval_mins\tdouble
non_null_pct\tdouble
recent\tbigint
connectivity_status\tvarchar
is_registered\tboolean
ac_msg_interval_mins\tdouble
gps_msg_interval_mins\tdouble
imu_msg_interval_mins\tdouble
gun_thermal_status\tvarchar
gun_hottest_pin\tvarchar
gun_temp_raw_max_c\tdouble
gun_rate_max_cpm\tdouble
gun_temp_clipped\tboolean
pack_thermal_status\tvarchar
cellmax_c\tdouble
coolant_c\tdouble
delta_cm_co\tdouble
bms_alarms\treal
vehiclereadycondition\tdouble
gun_connection_status\tdouble
ignitionstatus\tdouble
dcdc_enable_command\tdouble
steering_pump_enable_command\tdouble
steering_pump_rpm\tdouble
motor_rpm\tdouble
motor_torque\tdouble
motor_temperature\tdouble
igbt_temperature\tdouble
distancetoempty\tdouble
odometerreading\tdouble
vehicle_speed_vcu\tdouble
gear_position\tdouble
vehicle_operation_mode\tdouble
bat_soc\tdouble
soh\tdouble
total_battery_current\tdouble
bat_voltage\tdouble
bmslifesignal\tdouble
batterycoolingstate\tdouble
batterycoolanttemperature\tdouble
bms_fault_code\tdouble
insulation__value\tdouble
dcdc_out_put_currant\tdouble
maxavailableshorttermcharging\tdouble
maxavailableshorttimedischarge\tdouble
vcuversioninformation\tdouble
charger_current\tdouble
charger_voltage\tdouble
chrargecurrent_request\tdouble
evcc_error\tdouble
chargingerror_bms\tdouble
pack_negative_contactors_status\tdouble
batterytotalnegativecontactor\tdouble
chargingcontactor1positive\tdouble
chargingcontactor1negative\tdouble
chargingcontactor2positive\tdouble
chargingcontactor2negative\tdouble
charging_contactor_3_positive\tdouble
charging_contactor_3_negative\tdouble
accessorycontactorstatus\tdouble
guna_dcm_temperature\tdouble
guna_dcp_temperature\tdouble
gunb_dcm_temperature\tdouble
gunb_dcp_temperature\tdouble
gunc_dcm_temperature\tdouble
gunc_dcp_temperature\tdouble
parking_brake_status\tdouble
brakingsystemmallfunction_alarm\tdouble
brake_pad_worn_out_alarm\tdouble
side_door_panel_open_alarm\tdouble
autoholdunenable\tdouble
brakepedalpos\tdouble
brake_pedal\tdouble
accelarationpedal\tdouble
air_compressor_enable_command\tdouble
front_air_pressure\tdouble
rear_air_pressure\tdouble
air_compreesor_temperature\tdouble
pack1_cellmax_temperature\tdouble
pack1_cell_min_temperature\tdouble
pack1_maxtemperature_cell_number\tdouble
pack1_celltemperature_cellnumber\tdouble
cellmax_voltagecellnumber\tdouble
cellminvoltagecellnumber\tdouble
cell_max_voltage\tdouble
cell_min_voltage\tdouble
dcdcbusvoltage\tdouble
dcdc_out_put_volatge\tdouble
kneeling_request\tdouble
lift_lower_normal_request\tdouble
pre_charge_status\tdouble
insulation_status\tdouble
lowvoltagedcaclifesignal\tdouble
lowpressureoilpumpfaultcode\tdouble
output_phase_currant\tdouble
vcu_fault_code\tdouble
fiveinone_faultcode\tdouble
ebsredwarningsignal\tdouble
abs_ebsamberwarningsignal\tdouble
dcdc_statusandfailure\tdouble
systeminterlockstate\tdouble
oilpumpcondition\tdouble
airpumpcondition\tdouble
vcuself_teststatus\tdouble
mcuself_teststatus\tdouble
fourinoneself_teststatus\tdouble
polehightemperaturealarm\tdouble
batteryhightemperaturealarm\tdouble
battery_function_alarm\tdouble
hightempalarmofcharginggun\tdouble
temperaturedifferencealarm\tdouble
singlevoltagedifferencealarm\tdouble
chargingcurrentalarm\tdouble
dischargecurrentalarm\tdouble
batterypackovervoltagealarm\tdouble
batterypackundervoltagealarm\tdouble
monomerovervoltagealarm\tdouble
monomerundervoltagealarm\tdouble
soclowalarm\tdouble
sochighalarm\tdouble
powertrain_failure_alarm\tdouble
steeringcontroller_failurealarm\tdouble
fourinonelifesignal\tdouble
airpumpdcaclifesignal\tdouble
dcdclifesignal\tdouble
lifesignalofinsulation\tdouble
vehiclecontrollerlife\tdouble
guna_dcm_heating_rate_cpm\tdouble
guna_dcp_heating_rate_cpm\tdouble
gunb_dcm_heating_rate_cpm\tdouble
gunb_dcp_heating_rate_cpm\tdouble
gunc_dcm_heating_rate_cpm\tdouble
gunc_dcp_heating_rate_cpm\tdouble
batterycoolanttemperature_rate_cpm\tdouble
pack1_cellmax_temperature_rate_cpm\tdouble
motor_temperature_rate_cpm\tdouble
igbt_temperature_rate_cpm\tdouble
air_compreesor_temperature_rate_cpm\treal
b2t_tms_control_cmd\tdouble
tms_working_mode\tdouble
ac_operating_mode\tdouble
b2t_set_water_out_temp\tdouble
b2t_battery_min_temp\tdouble
b2t_battery_max_temp\tdouble
coolant_out_temp\tdouble
coolant_in_temp\tdouble
ac_set_temp\tdouble
ac_system_ipm_module_temp\tdouble
ac_system_eva_temp\tdouble
outside_temp\tdouble
cabin_temp\tdouble
ac_status\tdouble
comp_status\tdouble
blower_speed\tdouble
comp_target_hz\tdouble
comp_running_frequency\tdouble
comp_current\tdouble
hv_voltage\tdouble
tms_fault_code\tdouble
ac_fault_code\tdouble
v2t_vehicle_coolant_low\tdouble
latitude\tdouble
longitude\tdouble
altitude\tdouble
fix\tdouble
satellites_used\tdouble
satellites_in_view\tdouble
hdop\tdouble
vdop\tdouble
pdop\tdouble
ground_speed_kmph\tdouble
course_over_ground\tdouble
accel_x\tdouble
accel_y\tdouble
accel_z\tdouble
gyro_x\tdouble
gyro_y\tdouble
gyro_z\tdouble
model\tvarchar
make\tvarchar
year\tdouble"""


def _classify(col: str, sqltype: str) -> str:
	if col in CATEGORICAL_VALUES or col in FREE_CATEGORICAL:
		return "Categorical"
	if sqltype == "boolean":
		return "Boolean"
	return "Numeric"


def execute() -> None:
	for line in SCHEMA.strip().splitlines():
		col, sqltype = line.split("\t")
		if col in SKIP or frappe.db.exists("Telemetry Parameter", col):
			continue
		data_type = _classify(col, sqltype)
		if data_type == "Boolean":
			allowed = ["true", "false"]
		else:
			allowed = CATEGORICAL_VALUES.get(col, [])
		frappe.get_doc(
			{
				"doctype": "Telemetry Parameter",
				"parameter_name": col,
				"label": col.replace("_", " ").title(),
				"data_type": data_type,
				"unit": UNITS.get(col, ""),
				"allowed_values": "\n".join(allowed),
			}
		).insert(ignore_permissions=True)
