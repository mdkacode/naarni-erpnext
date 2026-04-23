<template>
  <div class="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
    <div class="mb-6">
      <router-link to="/service-portal" class="text-sm text-brand-600 hover:underline">&larr; Back to list</router-link>
      <h1 class="text-2xl font-bold text-gray-900 mt-2">New Job Card</h1>
    </div>

    <Wizard :steps="steps" v-model="formData" submit-label="Create Job Card" @complete="handleSubmit">

      <!-- ━━━ Step 1: Job Card Type ━━━ -->
      <template #step-type="{ data, updateField }">
        <div class="space-y-5">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-2">What type of service?</label>
            <div class="grid grid-cols-2 gap-3">
              <button v-for="t in jobCardTypes" :key="t.value" type="button"
                @click="selectJobType(t.value, updateField)"
                class="flex items-center gap-3 p-4 rounded-xl border-2 text-left transition-all"
                :class="data.job_card_type === t.value ? 'border-brand-500 bg-brand-50 ring-1 ring-brand-500' : 'border-gray-200 hover:border-gray-300'">
                <span class="text-2xl">{{ t.icon }}</span>
                <div>
                  <span class="text-sm font-semibold text-gray-800">{{ t.label }}</span>
                  <p class="text-xs text-gray-500 mt-0.5">{{ t.desc }}</p>
                </div>
              </button>
            </div>
          </div>
        </div>
      </template>

      <!-- ━━━ Step 2: Vehicle (searchable dropdown) + Odometer ━━━ -->
      <template #step-vehicle="{ data, updateField }">
        <div class="space-y-5">
          <!-- Vehicle search -->
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Vehicle Number</label>
            <div class="relative">
              <input
                v-model="vehicleSearch"
                @input="searchVehicles"
                @focus="showVehicleDropdown = true"
                type="text"
                placeholder="Type to search... e.g. DL-01 or Naarni 12M"
                class="input-field uppercase"
                autocomplete="off"
              />
              <!-- Dropdown results -->
              <div v-if="showVehicleDropdown && vehicleResults.length"
                class="absolute z-50 w-full mt-1 bg-white border border-gray-200 rounded-xl shadow-lg max-h-60 overflow-y-auto">
                <button v-for="v in vehicleResults" :key="v.name" type="button"
                  @click="selectVehicle(v, updateField)"
                  class="w-full px-4 py-3 text-left hover:bg-brand-50 border-b border-gray-50 last:border-0 transition-colors">
                  <div class="flex items-center justify-between">
                    <div>
                      <span class="font-bold text-gray-900">{{ formatVehicle(v.registration_number) }}</span>
                      <span class="text-sm text-gray-500 ml-2">{{ v.make_model }}</span>
                    </div>
                    <span class="text-xs text-gray-400">{{ v.customer }}</span>
                  </div>
                </button>
              </div>
              <div v-if="showVehicleDropdown && vehicleSearch.length >= 2 && !vehicleResults.length && !searchingVehicle"
                class="absolute z-50 w-full mt-1 bg-white border border-gray-200 rounded-xl shadow-lg p-4 text-sm text-gray-400 text-center">
                No vehicles found
              </div>
            </div>
            <!-- Selected vehicle card -->
            <div v-if="data.vehicle" class="mt-3 p-3 bg-green-50 border border-green-200 rounded-xl flex items-center justify-between">
              <div>
                <span class="font-bold text-green-800 text-lg tracking-wide">{{ formatVehicle(data.vehicle_number) }}</span>
                <span class="text-sm text-green-700 ml-2">{{ data.vehicle_make_model }}</span>
              </div>
              <button type="button" @click="clearVehicle(updateField)" class="text-green-600 hover:text-red-500 text-sm">Change</button>
            </div>
          </div>

          <!-- Odometer -->
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Odometer Reading</label>
            <div class="relative">
              <input :value="data.odometer_reading"
                @input="updateField('odometer_reading', Number($event.target.value))"
                type="number" inputmode="numeric" min="0" placeholder="e.g. 145000"
                class="input-field pr-12" />
              <span class="absolute right-3 top-1/2 -translate-y-1/2 text-sm text-gray-400">km</span>
            </div>
          </div>

          <!-- Auto-filled info -->
          <div v-if="data.customer_name" class="p-3 bg-gray-50 rounded-xl text-sm space-y-1">
            <div class="flex justify-between"><span class="text-gray-500">Customer</span><span class="font-medium">{{ data.customer_name }}</span></div>
            <div v-if="data.auto_check_sheet" class="flex justify-between"><span class="text-gray-500">Check Sheet</span><span class="font-medium">{{ data.auto_check_sheet }}</span></div>
          </div>
        </div>
      </template>

      <!-- ━━━ Step 3: Depot + VOC ━━━ -->
      <template #step-details="{ data, updateField }">
        <div class="space-y-5">
          <!-- Depot search -->
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Depot / Workshop</label>
            <div class="relative">
              <input v-model="depotSearch" @input="searchDepots" @focus="showDepotDropdown = true"
                type="text" placeholder="Search depot..." class="input-field" autocomplete="off" />
              <div v-if="showDepotDropdown && depotResults.length"
                class="absolute z-50 w-full mt-1 bg-white border border-gray-200 rounded-xl shadow-lg max-h-48 overflow-y-auto">
                <button v-for="d in depotResults" :key="d.name" type="button"
                  @click="selectDepot(d, updateField)"
                  class="w-full px-4 py-3 text-left hover:bg-brand-50 border-b border-gray-50 last:border-0">
                  <span class="font-medium text-gray-800">{{ d.depot_name }}</span>
                  <span class="text-xs text-gray-400 ml-2">{{ d.city }}</span>
                </button>
              </div>
            </div>
            <div v-if="data.depot" class="mt-2 text-sm text-green-700 font-medium">Selected: {{ data.depot }}</div>
          </div>

          <!-- Driver complaints -->
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Driver / Customer Complaints</label>
            <textarea :value="data.complaint_description"
              @input="updateField('complaint_description', $event.target.value)"
              rows="3" placeholder="What did the driver or customer report? List all issues..."
              class="input-field" />
          </div>

          <!-- SE observations -->
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Your Observations (SE)</label>
            <textarea :value="data.se_observations"
              @input="updateField('se_observations', $event.target.value)"
              rows="3" placeholder="Issues you found during initial inspection..."
              class="input-field" />
          </div>
        </div>
      </template>

      <!-- ━━━ Step 4: Review ━━━ -->
      <template #step-review="{ data }">
        <div class="space-y-4">
          <h3 class="text-sm font-semibold text-gray-500 uppercase tracking-wider">Review & Submit</h3>
          <div class="bg-gray-50 rounded-xl p-5 space-y-3 text-sm">
            <div class="flex justify-between"><span class="text-gray-500">Type</span><span class="font-bold text-gray-900">{{ data.job_card_type }}</span></div>
            <div class="flex justify-between"><span class="text-gray-500">Vehicle</span><span class="font-bold text-gray-900 tracking-wide">{{ formatVehicle(data.vehicle_number) }}</span></div>
            <div class="flex justify-between"><span class="text-gray-500">Model</span><span class="font-medium">{{ data.vehicle_make_model }}</span></div>
            <div class="flex justify-between"><span class="text-gray-500">Odometer</span><span class="font-medium">{{ Number(data.odometer_reading || 0).toLocaleString('en-IN') }} km</span></div>
            <div class="flex justify-between"><span class="text-gray-500">Customer</span><span class="font-medium">{{ data.customer_name }}</span></div>
            <div class="flex justify-between"><span class="text-gray-500">Depot</span><span class="font-medium">{{ data.depot }}</span></div>
            <div class="flex justify-between"><span class="text-gray-500">Priority</span>
              <span class="font-semibold" :class="data.priority === 'Urgent' ? 'text-red-600' : ''">{{ data.priority }}</span>
            </div>
          </div>
          <div v-if="data.complaint_description" class="bg-amber-50 rounded-xl p-4 text-sm">
            <p class="text-xs font-semibold text-amber-700 mb-1">Driver Complaints</p>
            <p class="text-gray-800">{{ data.complaint_description }}</p>
          </div>
          <div v-if="data.se_observations" class="bg-blue-50 rounded-xl p-4 text-sm">
            <p class="text-xs font-semibold text-blue-700 mb-1">SE Observations</p>
            <p class="text-gray-800">{{ data.se_observations }}</p>
          </div>
        </div>
      </template>
    </Wizard>

    <div v-if="submitError" class="mt-4 p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-700">{{ submitError }}</div>
  </div>
</template>

<script setup>
import { ref } from "vue";
import { useRouter } from "vue-router";
import { call } from "frappe-ui";
import Wizard from "../components/Wizard.vue";

const router = useRouter();
const submitError = ref("");

// ── Job Card Types per PRD ──
const jobCardTypes = [
  { value: "PMS + Repair", label: "PMS + Repair", icon: "\u{1F527}", desc: "Scheduled maintenance + any repairs" },
  { value: "Only Repair", label: "Only Repair", icon: "\u{1F6E0}", desc: "Specific repair, vehicle operational" },
  { value: "Software Update", label: "Software Update", icon: "\u{1F4BB}", desc: "Firmware / software update" },
  { value: "Breakdown", label: "Breakdown", icon: "\u{1F6A8}", desc: "Vehicle non-operational" },
];

// ── Form State ──
const formData = ref({
  job_card_type: "",
  vehicle: "",
  vehicle_number: "",
  vehicle_make_model: "",
  odometer_reading: null,
  customer: "",
  customer_name: "",
  depot: "",
  priority: "Medium",
  service_type: "",
  complaint_description: "",
  se_observations: "",
  auto_check_sheet: "",
});

// ── Vehicle Search ──
const vehicleSearch = ref("");
const vehicleResults = ref([]);
const showVehicleDropdown = ref(false);
const searchingVehicle = ref(false);
let vehicleTimer = null;

function searchVehicles() {
  clearTimeout(vehicleTimer);
  if (vehicleSearch.value.length < 2) { vehicleResults.value = []; return; }
  searchingVehicle.value = true;
  vehicleTimer = setTimeout(async () => {
    try {
      const res = await call("vehicle_maintenance.api.job_card.search_vehicles", {
        txt: vehicleSearch.value,
        limit: 10,
      });
      vehicleResults.value = res?.data || [];
    } catch { vehicleResults.value = []; }
    finally { searchingVehicle.value = false; }
  }, 300);
}

function selectVehicle(v, updateField) {
  updateField("vehicle", v.name);
  updateField("vehicle_number", v.registration_number);
  updateField("vehicle_make_model", v.make_model);
  updateField("customer", v.customer);
  updateField("customer_name", v.customer || "");
  vehicleSearch.value = v.registration_number;
  showVehicleDropdown.value = false;
  vehicleResults.value = [];
  // Fetch customer name via our whitelisted API
  if (v.customer) {
    call("vehicle_maintenance.api.job_card.get_customer_name", { customer: v.customer })
      .then(r => { if (r?.data?.customer_name) updateField("customer_name", r.data.customer_name); });
  }
}

function clearVehicle(updateField) {
  updateField("vehicle", "");
  updateField("vehicle_number", "");
  updateField("vehicle_make_model", "");
  updateField("customer", "");
  updateField("customer_name", "");
  vehicleSearch.value = "";
}

// ── Depot Search ──
const depotSearch = ref("");
const depotResults = ref([]);
const showDepotDropdown = ref(false);
let depotTimer = null;

function searchDepots() {
  clearTimeout(depotTimer);
  if (depotSearch.value.length < 1) { depotResults.value = []; return; }
  depotTimer = setTimeout(async () => {
    try {
      const res = await call("vehicle_maintenance.api.job_card.search_depots", {
        txt: depotSearch.value,
        limit: 10,
      });
      depotResults.value = res?.data || [];
    } catch { depotResults.value = []; }
  }, 200);
}

function selectDepot(d, updateField) {
  updateField("depot", d.name);
  depotSearch.value = d.depot_name;
  showDepotDropdown.value = false;
  depotResults.value = [];
}

// ── Job Type selection → auto-set fields ──
function selectJobType(type, updateField) {
  updateField("job_card_type", type);
  const map = {
    "PMS + Repair": { service_type: "Scheduled Maintenance", priority: "Medium" },
    "Only Repair": { service_type: "General Inspection", priority: "Medium" },
    "Software Update": { service_type: "Software Update", priority: "Low" },
    "Breakdown": { service_type: "Breakdown Repair", priority: "Urgent" },
  };
  const cfg = map[type];
  if (cfg) {
    updateField("service_type", cfg.service_type);
    updateField("priority", cfg.priority);
  }
}

// ── Indian vehicle number format ──
function formatVehicle(num) {
  if (!num) return "";
  let c = num.replace(/[-\s]/g, "").toUpperCase();
  let m = c.match(/^([A-Z]{2})(\d{1,2})([A-Z]{1,3})(\d{1,4})$/);
  return m ? `${m[1]} ${m[2].padStart(2,"0")} ${m[3]} ${m[4]}` : num;
}

// ── Wizard Steps ──
const steps = [
  {
    id: "type",
    title: "Job Card Type",
    description: "Select the type of service needed.",
    validate: (data) => {
      if (!data.job_card_type) return ["Please select a job card type."];
      return [];
    },
  },
  {
    id: "vehicle",
    title: "Vehicle & Odometer",
    description: "Search and select the bus, enter odometer reading.",
    validate: (data) => {
      const errors = [];
      if (!data.vehicle) errors.push("Please search and select a vehicle.");
      if (!data.odometer_reading || data.odometer_reading <= 0) errors.push("Enter a valid odometer reading.");
      return errors;
    },
  },
  {
    id: "details",
    title: "Location & Complaints",
    description: "Select depot and record customer/driver complaints.",
    validate: (data) => {
      const errors = [];
      if (!data.depot) errors.push("Please select a depot.");
      if (!data.complaint_description?.trim()) errors.push("Please record the driver/customer complaints.");
      return errors;
    },
  },
  {
    id: "review",
    title: "Review",
    description: "Confirm everything before creating.",
  },
];

// ── Submit ──
async function handleSubmit(data) {
  submitError.value = "";
  try {
    const result = await call("frappe.client.insert", {
      doc: {
        doctype: "Job Card",
        job_card_type: data.job_card_type,
        vehicle: data.vehicle,
        odometer_reading: data.odometer_reading,
        customer: data.customer,
        service_type: data.service_type,
        priority: data.priority,
        depot: data.depot,
        complaint_description: data.complaint_description,
        se_observations: data.se_observations,
      },
    });
    router.push(`/service-portal/job-card/${result.name}`);
  } catch (e) {
    submitError.value = e?.messages?.[0] || e?.message || "Failed to create job card.";
  }
}

// Close dropdowns on outside click
if (typeof document !== "undefined") {
  document.addEventListener("click", (e) => {
    if (!e.target.closest(".relative")) {
      showVehicleDropdown.value = false;
      showDepotDropdown.value = false;
    }
  });
}
</script>

<style scoped>
.input-field {
  @apply w-full px-3 py-2.5 border border-gray-300 rounded-lg text-sm
         focus:ring-2 focus:ring-brand-500 focus:border-brand-500
         text-gray-900 placeholder-gray-400 transition-colors;
}
</style>
