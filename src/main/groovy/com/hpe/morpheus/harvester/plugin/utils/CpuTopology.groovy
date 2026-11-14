// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.utils

import groovy.util.logging.Slf4j

/**
 * CPU topology and CPU/memory hot add for Harvester VMs.
 *
 * Morpheus sizes a VM by its core count (total vCPUs) and cores per socket. Harvester (KubeVirt) takes
 * {@code domain.cpu.cores} (cores per socket) and {@code domain.cpu.sockets}, so total vCPUs = cores × sockets.
 *
 * Hot add uses Harvester's CPU and memory hotplug (Harvester 1.6+): the VM carries the annotation
 * {@code harvesterhci.io/enableCPUAndMemoryHotplug: "true"}, CPUs are added a socket at a time up to
 * {@code cpu.maxSockets}, and memory up to {@code memory.maxGuest}. Both maximums are the starting size times
 * Harvester's {@code max-hotplug-ratio} setting (default 4), as in the Harvester UI, and the resource limits hold the
 * maximums.
 */
@Slf4j
class CpuTopology {

    static final String ANNOTATION_HOTPLUG = 'harvesterhci.io/enableCPUAndMemoryHotplug'
    static final String SETTING_MAX_HOTPLUG_RATIO = 'max-hotplug-ratio'
    static final long DEFAULT_HOTPLUG_RATIO = 4L
    /** KubeVirt needs at least 1 GiB of guest memory for memory hotplug */
    static final long MIN_HOTPLUG_MEMORY_MI = 1024L

    /** First positive value among the candidates (e.g. the wizard's choice, the workload's, the plan's). */
    static Long firstPositive(Object... candidates) {
        for (def c : candidates) {
            Long v = CommonUtils.toPositiveLong(c)
            if (v) return v
        }
        return null
    }

    /**
     * Splits vCPUs into Harvester's cores (per socket) and sockets.
     * No cores-per-socket value means one socket with all the cores.
     * @return [cores: x, sockets: y], or null when vCPUs isn't a whole number of sockets
     */
    static Map<String, Long> split(Long vcpus, Long coresPerSocket) {
        long total = vcpus && vcpus > 0 ? vcpus : 1L
        long perSocket = coresPerSocket && coresPerSocket > 0 ? coresPerSocket : total
        if (perSocket > total || total % perSocket != 0) {
            return null
        }
        return [cores: perSocket, sockets: (total / perSocket) as Long]
    }

    static String splitError(Long vcpus, Long coresPerSocket) {
        return "The core count (${vcpus}) must be a whole multiple of the cores per socket (${coresPerSocket}).".toString()
    }

    /** Hot add is on for a VM when it carries Harvester's hotplug annotation set to true. */
    static boolean isHotplugEnabled(Map vm) {
        def value = vm?.metadata?.annotations?.get(ANNOTATION_HOTPLUG)
        return value != null && value.toString().equalsIgnoreCase('true')
    }

    /** Checkbox values from the provisioning form or the workload config. */
    static boolean isOn(def value) {
        return value == true || value?.toString()?.toLowerCase() in ['on', 'true', '1', 'yes']
    }

    /** Harvester's max-hotplug-ratio setting value (1–20), or the default 4. */
    static long parseRatio(Map setting) {
        def raw = setting?.value ?: setting?.default
        try {
            long ratio = raw?.toString()?.trim() ? new BigDecimal(raw.toString().trim()).longValue() : DEFAULT_HOTPLUG_RATIO
            return ratio >= 1L && ratio <= 20L ? ratio : DEFAULT_HOTPLUG_RATIO
        } catch (Exception ignored) {
            return DEFAULT_HOTPLUG_RATIO
        }
    }

    static String settingPath() {
        return "/apis/harvesterhci.io/v1beta1/settings/${SETTING_MAX_HOTPLUG_RATIO}".toString()
    }

    /**
     * Applies CPU and memory to a VM template's domain.
     * Without hot add: Harvester UI shape (limits hold the size, guest memory is the limit minus the reserved overhead).
     * With hot add: sockets/maxSockets and guest/maxGuest, limits hold the maximums.
     */
    static void applyToDomain(Map domain, Map<String, Long> topology, long memoryMi, boolean hotplug, long ratio) {
        long cores = topology.cores
        long sockets = topology.sockets
        Map cpu = [cores: cores, sockets: sockets, threads: 1L]
        Map memory
        Map limits
        if (hotplug) {
            long maxSockets = sockets * ratio
            long maxGuestMi = memoryMi * ratio
            cpu.maxSockets = maxSockets
            memory = [guest: "${memoryMi}Mi".toString(), maxGuest: "${maxGuestMi}Mi".toString()]
            limits = [cpu: (maxSockets * cores).toString(), memory: "${maxGuestMi}Mi".toString()]
        } else {
            memory = [guest: "${CommonUtils.harvesterGuestMemoryMi(memoryMi)}Mi".toString()]
            limits = [cpu: (cores * sockets).toString(), memory: "${memoryMi}Mi".toString()]
        }
        domain.cpu = cpu
        domain.memory = memory
        Map resources = (domain.resources instanceof Map) ? domain.resources as Map : [:]
        resources.limits = limits
        domain.resources = resources
    }

    /** Memory quantity string (Ki/Mi/Gi/plain bytes) to MiB, or null. */
    static Long toMi(def quantity) {
        if (quantity == null) return null
        String s = quantity.toString().trim()
        try {
            def m = s =~ /^([0-9.]+)\s*([KMGT]i?)?$/
            if (!m.matches()) return null
            BigDecimal n = new BigDecimal(m.group(1))
            String unit = m.group(2)
            BigDecimal bytes
            switch (unit) {
                case 'Ki': bytes = n * 1024G; break
                case 'Mi': bytes = n * 1024G * 1024G; break
                case 'Gi': bytes = n * 1024G * 1024G * 1024G; break
                case 'Ti': bytes = n * 1024G * 1024G * 1024G * 1024G; break
                case 'K': bytes = n * 1000G; break
                case 'M': bytes = n * 1000G * 1000G; break
                case 'G': bytes = n * 1000G * 1000G * 1000G; break
                case 'T': bytes = n * 1000G * 1000G * 1000G * 1000G; break
                default: bytes = n
            }
            return (bytes / (1024G * 1024G)).setScale(0, java.math.RoundingMode.DOWN).longValue()
        } catch (Exception ignored) {
            return null
        }
    }

    /**
     * Whether a change on a running hot-add VM can be applied live: same cores per socket, sockets and memory only
     * growing and within the VM's maximums.
     * @param domain the VM's current spec.template.spec.domain
     * @return null when it can be hot added, otherwise the reason it can't
     */
    static String hotAddBlocker(Map domain, Long newVcpus, Long newCoresPerSocket, Long newMemoryMi) {
        Map cpu = domain?.cpu instanceof Map ? domain.cpu as Map : [:]
        long cores = CommonUtils.toPositiveLong(cpu.cores) ?: 1L
        long sockets = CommonUtils.toPositiveLong(cpu.sockets) ?: 1L
        Long maxSockets = CommonUtils.toPositiveLong(cpu.maxSockets)
        if (newVcpus && newVcpus != cores * sockets) {
            if (newCoresPerSocket && newCoresPerSocket != cores) {
                return "cores per socket changes from ${cores} to ${newCoresPerSocket}".toString()
            }
            if (newVcpus % cores != 0) {
                return "${newVcpus} cores isn't a whole number of ${cores}-core sockets".toString()
            }
            long newSockets = (newVcpus / cores) as long
            if (newSockets < sockets) {
                return 'CPUs can only be added while the VM runs, not removed'
            }
            if (!maxSockets || newSockets > maxSockets) {
                return "the VM's maximum is ${(maxSockets ?: sockets) * cores} cores".toString()
            }
        } else if (newCoresPerSocket && newCoresPerSocket != cores) {
            return "cores per socket changes from ${cores} to ${newCoresPerSocket}".toString()
        }
        Map memory = domain?.memory instanceof Map ? domain.memory as Map : [:]
        Long guestMi = toMi(memory.guest)
        Long maxGuestMi = toMi(memory.maxGuest)
        if (newMemoryMi && guestMi == null) {
            return "the VM's current memory couldn't be read"
        }
        if (newMemoryMi && newMemoryMi != guestMi) {
            if (newMemoryMi < guestMi) {
                return 'memory can only be added while the VM runs, not removed'
            }
            if (!maxGuestMi || newMemoryMi > maxGuestMi) {
                return "the VM's maximum memory is ${maxGuestMi ?: guestMi} MiB".toString()
            }
        }
        return null
    }
}
