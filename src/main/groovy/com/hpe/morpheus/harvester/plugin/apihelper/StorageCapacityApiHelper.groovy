// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.apihelper

import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.model.ComputeServerGroup
import groovy.util.logging.Slf4j

/**
 * Works out how much space each storage class has, for the datastores' size and free space in Morpheus.
 *
 * Longhorn storage classes: from the Longhorn disks the class can place replicas on (node and disk tags from the
 * class's nodeSelector/diskSelector, scheduling enabled), using Longhorn's own scheduling limits
 * (storage-over-provisioning-percentage and storage-minimal-available-percentage), divided by the class's replica count.
 * Other CSI drivers: the CSIStorageCapacity objects the driver publishes, if any. Otherwise the capacity is unknown (null).
 */
@Slf4j
class StorageCapacityApiHelper {

    static final String LONGHORN_NODES_PATH = '/apis/longhorn.io/v1beta2/namespaces/longhorn-system/nodes'
    static final String LONGHORN_SETTINGS_PATH = '/apis/longhorn.io/v1beta2/namespaces/longhorn-system/settings/'
    static final String CSI_STORAGE_CAPACITY_PATH = '/apis/storage.k8s.io/v1/csistoragecapacities'

    private HarvesterApiClient apiClient

    StorageCapacityApiHelper(ComputeServerGroup serverGroup) {
        this(new HarvesterApiClient(serverGroup))
    }

    StorageCapacityApiHelper(HarvesterApiClient apiClient) {
        this.apiClient = apiClient
    }

    /**
     * @param storageClasses storage class maps (name, provisioner, parameters) as returned by StorageClassApiHelper
     * @return storage class name -> [storageSize: Long bytes, freeSpace: Long bytes]; classes with unknown capacity are left out
     */
    Map<String, Map> getCapacities(List storageClasses) {
        Map<String, Map> result = [:]
        if (!storageClasses) return result
        List longhornNodes = null
        Map<String, Long> csiCapacity = null
        Integer overProvisioning = null
        Integer minimalAvailable = null

        storageClasses.each { sc ->
            try {
                String name = sc?.name ?: sc?.metadata?.name
                if (!name) return
                if (sc.provisioner == HarvesterConstants.LONGHORN_PROVISIONER) {
                    if (longhornNodes == null) {
                        longhornNodes = listItems(LONGHORN_NODES_PATH)
                        overProvisioning = intSetting('storage-over-provisioning-percentage', 100)
                        minimalAvailable = intSetting('storage-minimal-available-percentage', 25)
                    }
                    Map cap = longhornCapacity(longhornNodes, sc.parameters as Map, overProvisioning, minimalAvailable)
                    if (cap) result[name] = cap
                } else {
                    if (csiCapacity == null) {
                        csiCapacity = csiCapacities(listItems(CSI_STORAGE_CAPACITY_PATH))
                    }
                    Long free = csiCapacity[name]
                    if (free != null) result[name] = [storageSize: null, freeSpace: free]
                }
            } catch (Exception e) {
                log.warn "StorageCapacityApiHelper: capacity for storage class ${sc?.name} unavailable: ${e.message}"
            }
        }
        return result
    }

    /**
     * Capacity of one Longhorn storage class across the nodes' disks.
     * @return [storageSize, freeSpace] in bytes, or null when no disk matches
     */
    static Map longhornCapacity(List nodes, Map parameters, int overProvisioningPct = 100, int minimalAvailablePct = 25) {
        parameters = parameters ?: [:]
        List<String> nodeSelector = splitTags(parameters.nodeSelector)
        List<String> diskSelector = splitTags(parameters.diskSelector)
        String diskType = (parameters.dataEngine?.toString() == 'v2') ? 'block' : 'filesystem'
        int replicas = 3
        try {
            replicas = Math.max(1, (parameters.numberOfReplicas ?: '3').toString().toInteger())
        } catch (Exception ignored) { }

        long totalCapacity = 0L
        long totalFree = 0L
        int matchingNodes = 0
        nodes?.each { node ->
            def spec = node?.spec ?: [:]
            if (spec.allowScheduling == false) return
            if (!((spec.tags ?: []) as List).containsAll(nodeSelector)) return
            boolean nodeMatched = false
            (spec.disks ?: [:]).each { String diskName, disk ->
                if (disk?.allowScheduling == false) return
                if (disk?.evictionRequested == true) return
                if ((disk?.diskType ?: 'filesystem') != diskType) return
                if (!((disk?.tags ?: []) as List).containsAll(diskSelector)) return
                def status = node?.status?.diskStatus?.get(diskName)
                if (!status) return
                long maximum = toLong(status.storageMaximum)
                long available = toLong(status.storageAvailable)
                long scheduled = toLong(status.storageScheduled)
                long reserved = toLong(disk?.storageReserved)
                long capacity = Math.max(0L, maximum - reserved)
                // Longhorn won't place a replica beyond either limit
                long byOverProvisioning = (long) (capacity * overProvisioningPct / 100) - scheduled
                long byMinimalAvailable = available - (long) (maximum * minimalAvailablePct / 100)
                totalCapacity += capacity
                totalFree += Math.max(0L, Math.min(byOverProvisioning, byMinimalAvailable))
                nodeMatched = true
            }
            if (nodeMatched) matchingNodes++
        }
        if (matchingNodes == 0) return null
        // Each replica goes on a different node; with fewer nodes than replicas, Longhorn places as many as it can
        int effectiveReplicas = Math.max(1, Math.min(replicas, matchingNodes))
        return [storageSize: (long) (totalCapacity / effectiveReplicas), freeSpace: (long) (totalFree / effectiveReplicas)]
    }

    /** storage class name -> free bytes, summed over the CSIStorageCapacity objects (one per topology segment) */
    static Map<String, Long> csiCapacities(List items) {
        Map<String, Long> result = [:]
        items?.each { item ->
            String scName = item?.storageClassName
            if (scName && item.capacity != null) {
                result[scName] = (result[scName] ?: 0L) + parseQuantity(item.capacity)
            }
        }
        return result
    }

    /** Parses a Kubernetes quantity ("100Gi", "500M", "1.5Ti", "1073741824") to bytes */
    static long parseQuantity(def quantity) {
        if (quantity == null) return 0L
        if (quantity instanceof Number) return ((Number) quantity).longValue()
        String q = quantity.toString().trim()
        def m = q =~ /^([0-9.]+)([A-Za-z]*)$/
        if (!m.matches()) return 0L
        BigDecimal number = new BigDecimal(m.group(1))
        String suffix = m.group(2)
        Map<String, Number> multipliers = [
                '': 1G, 'k': 1000G, 'K': 1000G, 'M': 1000G ** 2, 'G': 1000G ** 3, 'T': 1000G ** 4, 'P': 1000G ** 5,
                'Ki': 1024G, 'Mi': 1024G ** 2, 'Gi': 1024G ** 3, 'Ti': 1024G ** 4, 'Pi': 1024G ** 5
        ]
        Number mult = multipliers[suffix]
        return mult == null ? 0L : (number * new BigDecimal(mult.toString())).longValue()
    }

    private static List<String> splitTags(def value) {
        if (!value) return []
        return value.toString().split(',').collect { it.trim() }.findAll { it }
    }

    private static long toLong(def value) {
        if (value == null) return 0L
        if (value instanceof Number) return ((Number) value).longValue()
        try { return Long.parseLong(value.toString()) } catch (Exception ignored) { return parseQuantity(value) }
    }

    private List listItems(String path) {
        def resp = apiClient.get(path)
        if (!resp?.success) {
            log.debug "StorageCapacityApiHelper: ${path} not available: ${resp?.msg ?: resp?.error}"
            return []
        }
        return (resp.data?.items ?: []) as List
    }

    private int intSetting(String name, int defaultValue) {
        try {
            def resp = apiClient.get(LONGHORN_SETTINGS_PATH + name)
            def value = resp?.success ? resp.data?.value : null
            return value != null && value.toString().isInteger() ? value.toString().toInteger() : defaultValue
        } catch (Exception ignored) {
            return defaultValue
        }
    }
}
