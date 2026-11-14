// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.apihelper

import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification

class ClusterHealthApiHelperSpec extends Specification {

    private static Map node(String name, String ready, Map extra = [:]) {
        return [metadata: [name: name, annotations: extra.annotations ?: [:]],
                spec: [unschedulable: extra.unschedulable ?: false],
                status: [conditions: [[type: 'Ready', status: ready]] + (extra.conditions ?: [])]]
    }

    def "nodeHealth reports not ready, pressure, maintenance and cordoned nodes"() {
        when:
        def result = ClusterHealthApiHelper.nodeHealth([
                node('n1', 'True'),
                node('n2', 'False'),
                node('n3', 'True', [conditions: [[type: 'DiskPressure', status: 'True']]]),
                node('n4', 'True', [annotations: ['harvesterhci.io/maintain-status': 'running'], unschedulable: true]),
                node('n5', 'True', [unschedulable: true])])

        then:
        result.total == 5
        result.ready == 4
        result.issues == ['1 of 5 nodes not ready (n2)', 'node pressure: n3 DiskPressure',
                          'in maintenance: n4', 'cordoned: n5']
    }

    def "nodeHealth with all nodes ready has no issues, and no data means unavailable"() {
        expect:
        ClusterHealthApiHelper.nodeHealth([node('n1', 'True')]).issues.isEmpty()
        !ClusterHealthApiHelper.nodeHealth(null).available
    }

    def "volumeHealth counts degraded and faulted Longhorn volumes"() {
        when:
        def result = ClusterHealthApiHelper.volumeHealth([
                [status: [robustness: 'healthy']], [status: [robustness: 'degraded']],
                [status: [robustness: 'degraded']], [status: [robustness: 'faulted']], [status: [robustness: 'unknown']]])

        then:
        result.degraded == 2
        result.faulted == 1
        result.issues == ['1 Longhorn volume faulted', '2 Longhorn volumes degraded']
    }

    def "alertHealth counts firing critical and warning alerts and ignores the Watchdog"() {
        when:
        def result = ClusterHealthApiHelper.alertHealth([
                [labels: [alertname: 'Watchdog', severity: 'none'], status: [state: 'active']],
                [labels: [alertname: 'KubeNodeNotReady', severity: 'critical'], status: [state: 'active']],
                [labels: [alertname: 'LonghornVolumeStatusWarning', severity: 'warning'], status: [state: 'active']],
                [labels: [alertname: 'Suppressed', severity: 'critical'], status: [state: 'suppressed']],
                [labels: [alertname: 'InfoAlert', severity: 'info'], status: [state: 'active']]])

        then:
        result.critical == 1
        result.warning == 1
        result.issues == ['1 critical alert (KubeNodeNotReady)', '1 warning alert (LonghornVolumeStatusWarning)']
    }

    def "message joins the issues and stays short"() {
        expect:
        ClusterHealthApiHelper.message(['a', 'b']) == 'Harvester health: a; b'
        ClusterHealthApiHelper.message(['x' * 400]).length() == ClusterHealthApiHelper.MAX_MESSAGE
    }

    def "check skips APIs that aren't available"() {
        given:
        def client = Mock(HarvesterApiClient)
        client.get(ClusterHealthApiHelper.NODES_PATH) >> ServiceResponse.success([items: [node('n1', 'True')]])
        client.get(ClusterHealthApiHelper.LONGHORN_VOLUMES_PATH) >> ServiceResponse.error('404')
        client.get(ClusterHealthApiHelper.ALERTS_PATH, _ as Map) >> ServiceResponse.error('503')

        when:
        def result = new ClusterHealthApiHelper(client).check()

        then:
        result.healthy
        result.nodes.available
        !result.volumes.available
        !result.alerts.available
    }
}
