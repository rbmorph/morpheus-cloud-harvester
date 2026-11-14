// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.apihelper

import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

/**
 * Harvester cluster health for the Morpheus cloud status.
 *
 * Checks, each skipped when its API isn't available:
 *  - nodes: Ready condition, memory / disk / PID pressure, cordoned or in maintenance mode
 *  - Longhorn volumes: degraded or faulted
 *  - Alertmanager (Harvester's rancher-monitoring add-on): firing critical and warning alerts
 *
 * The result is a list of problems ({@code issues}) plus counts; no problems means healthy.
 */
@Slf4j
class ClusterHealthApiHelper {

    static final String NODES_PATH = '/api/v1/nodes'
    static final String LONGHORN_VOLUMES_PATH = '/apis/longhorn.io/v1beta2/namespaces/longhorn-system/volumes'
    static final String ALERTS_PATH = '/api/v1/namespaces/cattle-monitoring-system/services/' +
            'http:rancher-monitoring-alertmanager:9093/proxy/api/v2/alerts'
    static final Map ALERT_QUERY = [active: 'true', silenced: 'false', inhibited: 'false']
    static final String ANNOTATION_MAINTENANCE = 'harvesterhci.io/maintain-status'
    /** Alerts that always fire by design (heartbeats) */
    static final List<String> IGNORED_ALERTS = ['Watchdog', 'InfoInhibitor']
    /** Cloud status messages are kept short */
    static final int MAX_MESSAGE = 250

    private final HarvesterApiClient client

    ClusterHealthApiHelper(HarvesterApiClient client) {
        this.client = client
    }

    /**
     * @return [healthy: boolean, issues: [String], nodes: [...], volumes: [...], alerts: [...]]
     */
    Map check() {
        Map nodes = nodeHealth(fetchItems(NODES_PATH))
        Map volumes = volumeHealth(fetchItems(LONGHORN_VOLUMES_PATH))
        Map alerts = alertHealth(fetchAlerts())
        List<String> issues = (nodes.issues + volumes.issues + alerts.issues) as List<String>
        return [healthy: issues.isEmpty(), issues: issues, nodes: nodes, volumes: volumes, alerts: alerts]
    }

    /** Items of a Kubernetes list, or null when the API isn't there or can't be read. */
    private List<Map> fetchItems(String path) {
        try {
            ServiceResponse resp = client.get(path)
            if (resp?.success && resp.data instanceof Map) {
                return ((resp.data as Map).items ?: []) as List<Map>
            }
            log.debug("Harvester health: ${path} unavailable: ${resp?.msg}")
        } catch (Exception e) {
            log.debug("Harvester health: ${path} failed: ${e.message}")
        }
        return null
    }

    /** Active alerts from Alertmanager, or null when monitoring isn't enabled. */
    private List<Map> fetchAlerts() {
        try {
            ServiceResponse resp = client.get(ALERTS_PATH, ALERT_QUERY)
            if (resp?.success && resp.data instanceof List) {
                return resp.data as List<Map>
            }
            log.debug("Harvester health: Alertmanager unavailable (is the rancher-monitoring add-on on?): ${resp?.msg}")
        } catch (Exception e) {
            log.debug("Harvester health: Alertmanager failed: ${e.message}")
        }
        return null
    }

    static Map nodeHealth(List<Map> items) {
        if (items == null) return [available: false, total: 0, ready: 0, issues: []]
        List<String> notReady = []
        List<String> pressure = []
        List<String> maintenance = []
        List<String> cordoned = []
        items.each { Map node ->
            String name = node?.metadata?.name
            List conditions = (node?.status?.conditions ?: []) as List
            Map readyCondition = conditions.find { it?.type == 'Ready' } as Map
            if (readyCondition?.status != 'True') {
                notReady << name
            }
            conditions.findAll { it?.type in ['MemoryPressure', 'DiskPressure', 'PIDPressure'] && it?.status == 'True' }.each {
                pressure << "${name} ${it.type}".toString()
            }
            def maintain = node?.metadata?.annotations?.get(ANNOTATION_MAINTENANCE)
            if (maintain) {
                maintenance << name
            } else if (node?.spec?.unschedulable == true) {
                cordoned << name
            }
        }
        List<String> issues = []
        if (notReady) issues << "${notReady.size()} of ${items.size()} nodes not ready (${notReady.join(', ')})".toString()
        if (pressure) issues << "node pressure: ${pressure.join(', ')}".toString()
        if (maintenance) issues << "in maintenance: ${maintenance.join(', ')}".toString()
        if (cordoned) issues << "cordoned: ${cordoned.join(', ')}".toString()
        return [available: true, total: items.size(), ready: items.size() - notReady.size(), issues: issues]
    }

    static Map volumeHealth(List<Map> items) {
        if (items == null) return [available: false, total: 0, degraded: 0, faulted: 0, issues: []]
        int degraded = items.count { it?.status?.robustness == 'degraded' } as int
        int faulted = items.count { it?.status?.robustness == 'faulted' } as int
        List<String> issues = []
        if (faulted) issues << "${faulted} Longhorn volume${faulted == 1 ? '' : 's'} faulted".toString()
        if (degraded) issues << "${degraded} Longhorn volume${degraded == 1 ? '' : 's'} degraded".toString()
        return [available: true, total: items.size(), degraded: degraded, faulted: faulted, issues: issues]
    }

    static Map alertHealth(List<Map> alerts) {
        if (alerts == null) return [available: false, critical: 0, warning: 0, issues: []]
        List<Map> firing = alerts.findAll { Map a ->
            String name = a?.labels?.alertname
            String state = a?.status?.state
            name && !(name in IGNORED_ALERTS) && (state == null || state == 'active')
        }
        List<Map> critical = firing.findAll { it?.labels?.severity == 'critical' }
        List<Map> warning = firing.findAll { it?.labels?.severity == 'warning' }
        List<String> issues = []
        if (critical) issues << "${critical.size()} critical alert${critical.size() == 1 ? '' : 's'} (${alertNames(critical)})".toString()
        if (warning) issues << "${warning.size()} warning alert${warning.size() == 1 ? '' : 's'} (${alertNames(warning)})".toString()
        return [available: true, critical: critical.size(), warning: warning.size(), issues: issues]
    }

    private static String alertNames(List<Map> alerts) {
        List<String> names = alerts.collect { it.labels.alertname as String }.unique()
        return names.size() > 3 ? (names.take(3) + ["+${names.size() - 3} more".toString()]).join(', ') : names.join(', ')
    }

    /** One-line status message for the cloud, e.g. "Harvester health: 1 of 3 nodes not ready (n2); 2 Longhorn volumes degraded" */
    static String message(List<String> issues) {
        String text = 'Harvester health: ' + issues.join('; ')
        return text.length() > MAX_MESSAGE ? text.substring(0, MAX_MESSAGE - 1) + '…' : text
    }
}
