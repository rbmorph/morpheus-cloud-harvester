// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.apihelper

import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Unroll

class StorageCapacityApiHelperSpec extends Specification {

    static final long GI = 1024L * 1024L * 1024L

    static Map node(String name, Map disks, Map diskStatus, List tags = [], boolean allowScheduling = true) {
        [metadata: [name: name], spec: [allowScheduling: allowScheduling, tags: tags, disks: disks],
         status: [diskStatus: diskStatus]]
    }

    static Map disk(List tags = [], long reserved = 0L, boolean allow = true) {
        [allowScheduling: allow, tags: tags, storageReserved: reserved, diskType: 'filesystem']
    }

    static Map status(long max, long available, long scheduled) {
        [storageMaximum: max, storageAvailable: available, storageScheduled: scheduled]
    }

    @Unroll
    def "parseQuantity #q = #bytes"() {
        expect:
        StorageCapacityApiHelper.parseQuantity(q) == bytes

        where:
        q            || bytes
        '100Gi'      || 100L * GI
        '1.5Ti'      || (long) (1.5 * 1024 * GI)
        '500M'       || 500_000_000L
        '1073741824' || GI
        42           || 42L
        'junk'       || 0L
        null         || 0L
    }

    def "longhorn capacity divides usable space by the replica count"() {
        given: 'three nodes with a 1000 GiB disk each, 100 GiB reserved, 200 GiB scheduled'
        def nodes = (1..3).collect { i ->
            node("n${i}", [d1: disk([], 100L * GI)], [d1: status(1000L * GI, 800L * GI, 200L * GI)])
        }

        when:
        def cap = StorageCapacityApiHelper.longhornCapacity(nodes, [numberOfReplicas: '3'], 100, 25)

        then: 'size = 3 x 900 / 3; free per disk = min(900 - 200, 800 - 250) = 550'
        cap.storageSize == 900L * GI
        cap.freeSpace == 550L * GI
    }

    def "longhorn capacity honours disk and node selectors and unschedulable disks"() {
        given:
        def nodes = [
                node('n1', [ssd: disk(['ssd']), hdd: disk(['hdd'])],
                        [ssd: status(100L * GI, 100L * GI, 0L), hdd: status(500L * GI, 500L * GI, 0L)], ['fast']),
                node('n2', [ssd: disk(['ssd'])], [ssd: status(100L * GI, 100L * GI, 0L)], []),
                node('n3', [ssd: disk(['ssd'], 0L, false)], [ssd: status(100L * GI, 100L * GI, 0L)], ['fast'])
        ]

        when:
        def cap = StorageCapacityApiHelper.longhornCapacity(nodes, [numberOfReplicas: '1', diskSelector: 'ssd', nodeSelector: 'fast'], 100, 0)

        then: 'only n1/ssd matches'
        cap.storageSize == 100L * GI
        cap.freeSpace == 100L * GI
    }

    def "longhorn capacity uses the node count when there are fewer nodes than replicas"() {
        given:
        def nodes = [node('n1', [d: disk()], [d: status(100L * GI, 100L * GI, 0L)])]

        expect:
        StorageCapacityApiHelper.longhornCapacity(nodes, [numberOfReplicas: '3'], 100, 0).storageSize == 100L * GI
    }

    def "longhorn capacity is null when no disk matches and free space never goes negative"() {
        given:
        def full = [node('n1', [d: disk()], [d: status(100L * GI, 10L * GI, 150L * GI)])]

        expect:
        StorageCapacityApiHelper.longhornCapacity(full, [diskSelector: 'nvme']) == null
        StorageCapacityApiHelper.longhornCapacity(full, [numberOfReplicas: '1']).freeSpace == 0L
    }

    def "csi capacities are summed per storage class"() {
        expect:
        StorageCapacityApiHelper.csiCapacities([
                [storageClassName: 'nfs', capacity: '10Gi'],
                [storageClassName: 'nfs', capacity: '5Gi'],
                [storageClassName: 'lvm', capacity: '1Gi'],
                [storageClassName: 'x']
        ]) == [nfs: 15L * GI, lvm: GI]
    }

    def "getCapacities reads Longhorn nodes once and skips classes with unknown capacity"() {
        given:
        def client = Mock(HarvesterApiClient)
        def helper = new StorageCapacityApiHelper(client)
        def nodes = [node('n1', [d: disk()], [d: status(100L * GI, 100L * GI, 0L)])]

        when:
        def result = helper.getCapacities([
                [name: 'longhorn', provisioner: 'driver.longhorn.io', parameters: [numberOfReplicas: '1']],
                [name: 'longhorn-1r', provisioner: 'driver.longhorn.io', parameters: [numberOfReplicas: '1']],
                [name: 'nfs', provisioner: 'nfs.csi.k8s.io', parameters: [:]]
        ])

        then:
        1 * client.get(StorageCapacityApiHelper.LONGHORN_NODES_PATH) >> ServiceResponse.success([items: nodes])
        1 * client.get(StorageCapacityApiHelper.LONGHORN_SETTINGS_PATH + 'storage-over-provisioning-percentage') >> ServiceResponse.success([value: '100'])
        1 * client.get(StorageCapacityApiHelper.LONGHORN_SETTINGS_PATH + 'storage-minimal-available-percentage') >> ServiceResponse.success([value: '0'])
        1 * client.get(StorageCapacityApiHelper.CSI_STORAGE_CAPACITY_PATH) >> ServiceResponse.error('not found')
        result.keySet() == ['longhorn', 'longhorn-1r'] as Set
        result.longhorn.freeSpace == 100L * GI
    }
}
