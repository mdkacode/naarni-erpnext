<template>
  <!--
    SubsystemPicker — multi-select for the Subsystem master.

    Used by Only Repair / Software Update / Breakdown job cards per PRD
    ("Selects Subsystem" step). Renders as a chip input that loads options
    lazily and groups them by the master's Category column for orientation.
  -->
  <div>
    <!-- Selected chips -->
    <div v-if="selected.length" class="flex flex-wrap gap-1.5 mb-2">
      <span
        v-for="name in selected"
        :key="name"
        class="inline-flex items-center gap-1 px-2.5 py-1 text-xs font-medium rounded-full bg-brand-50 text-brand-700"
      >
        {{ name }}
        <button
          v-if="!disabled"
          type="button"
          @click="toggle(name)"
          class="text-brand-400 hover:text-red-500"
          aria-label="Remove"
        >
          &times;
        </button>
      </span>
    </div>

    <!-- Add button / dropdown -->
    <div v-if="!disabled" class="relative">
      <button
        type="button"
        @click="open = !open"
        class="w-full flex items-center justify-between px-3 py-2 text-sm text-gray-600 bg-white border border-gray-300 rounded-lg hover:border-gray-400"
      >
        <span>{{ open ? "Close" : "Add subsystems" }}</span>
        <span class="text-xs text-gray-400">{{ selected.length }} selected</span>
      </button>

      <div
        v-if="open"
        class="absolute z-10 mt-1 w-full bg-white border border-gray-200 rounded-lg shadow-lg max-h-64 overflow-y-auto p-2"
      >
        <input
          v-model="search"
          type="text"
          placeholder="Search…"
          class="w-full px-2 py-1.5 text-sm border border-gray-200 rounded mb-2 focus:outline-none focus:border-brand-500"
        />
        <div v-if="loading" class="text-center text-xs text-gray-400 py-2">
          Loading…
        </div>
        <div v-else-if="!filteredGrouped.size" class="text-center text-xs text-gray-400 py-2">
          No subsystems match "{{ search }}".
        </div>
        <template v-for="[category, items] in filteredGrouped" :key="category">
          <div class="text-[10px] uppercase tracking-wide text-gray-400 font-semibold mt-2 mb-1 px-1">
            {{ category || "Uncategorized" }}
          </div>
          <button
            v-for="item in items"
            :key="item.name"
            type="button"
            @click="toggle(item.name)"
            class="w-full text-left px-2 py-1.5 text-sm rounded hover:bg-brand-50 flex items-center justify-between"
          >
            <span>{{ item.subsystem_name }}</span>
            <span v-if="selected.includes(item.name)" class="text-brand-600 font-bold">
              &#10003;
            </span>
          </button>
        </template>
      </div>
    </div>

    <div v-if="!disabled && !loading && !options.length" class="text-xs text-amber-700 mt-2">
      No subsystems configured yet — add them under Setup › Fleet Service › Subsystem.
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from "vue";
import { call } from "frappe-ui";

const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  disabled: { type: Boolean, default: false },
});
const emit = defineEmits(["update:modelValue"]);

const options = ref([]);
const loading = ref(false);
const open = ref(false);
const search = ref("");

const selected = computed(() => props.modelValue || []);

const filteredGrouped = computed(() => {
  const q = search.value.trim().toLowerCase();
  const map = new Map();
  for (const opt of options.value) {
    if (q && !opt.subsystem_name.toLowerCase().includes(q)) continue;
    const cat = opt.category || "Other";
    if (!map.has(cat)) map.set(cat, []);
    map.get(cat).push(opt);
  }
  return map;
});

async function load() {
  loading.value = true;
  try {
    const res = await call("vehicle_maintenance.api.job_card.list_subsystems");
    options.value = res?.data || [];
  } catch {
    options.value = [];
  } finally {
    loading.value = false;
  }
}

function toggle(name) {
  const next = selected.value.includes(name)
    ? selected.value.filter((n) => n !== name)
    : [...selected.value, name];
  emit("update:modelValue", next);
}

onMounted(load);

// Close dropdown when user clicks outside
watch(open, (val) => {
  if (!val) search.value = "";
});
</script>
