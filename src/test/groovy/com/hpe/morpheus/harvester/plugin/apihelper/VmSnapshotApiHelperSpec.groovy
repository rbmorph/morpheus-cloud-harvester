// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.apihelper

import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification

class VmSnapshotApiHelperSpec extends Specification {

    def "paths"() {
        expect:
        VmSnapshotApiHelper.backupsPath('ns') == '/apis/harvesterhci.io/v1beta1/namespaces/ns/virtualmachinebackups'
        VmSnapshotApiHelper.backupsPath('ns', 's') == '/apis/harvesterhci.io/v1beta1/namespaces/ns/virtualmachinebackups/s'
        VmSnapshotApiHelper.restoresPath('ns', 'r') == '/apis/harvesterhci.io/v1beta1/namespaces/ns/virtualmachinerestores/r'
        VmSnapshotApiHelper.vmSubresourcePath('ns', 'vm', 'stop') == '/apis/subresources.kubevirt.io/v1/namespaces/ns/virtualmachines/vm/stop'
    }

    def "listSnapshots keeps only VM snapshots, not backups"() {
        given:
        def client = Mock(HarvesterApiClient)
        client.get(VmSnapshotApiHelper.ALL_BACKUPS_PATH) >> ServiceResponse.success([items: [
                [metadata: [name: 'a'], spec: [type: 'snapshot', source: [kind: 'VirtualMachine', name: 'vm']]],
                [metadata: [name: 'b'], spec: [type: 'backup', source: [kind: 'VirtualMachine', name: 'vm']]],
                [metadata: [name: 'c'], spec: [source: [kind: 'VirtualMachine', name: 'vm']]]
        ]])

        expect:
        new VmSnapshotApiHelper(client).listSnapshots().data*.metadata*.name == ['a']
    }

    def "listSnapshots passes on an API error"() {
        given:
        def client = Mock(HarvesterApiClient)
        client.get(_) >> ServiceResponse.error('404')

        expect:
        !new VmSnapshotApiHelper(client).listSnapshots().success
    }

    def "status helpers"() {
        expect:
        VmSnapshotApiHelper.isReady([status: [readyToUse: true]])
        !VmSnapshotApiHelper.isReady([status: [:]])
        VmSnapshotApiHelper.errorOf([status: [error: [message: 'boom']]]) == 'boom'
        VmSnapshotApiHelper.sizeOf([status: [volumeBackups: [[volumeSize: 5L], [volumeSize: 7L]]]]) == 12L
        VmSnapshotApiHelper.sizeOf([status: [:]]) == null
        VmSnapshotApiHelper.isRestoreComplete([status: [complete: true]])
        VmSnapshotApiHelper.restoreError([status: [conditions: [[type: 'Ready', status: 'False', reason: 'Failed', message: 'pvc missing']]]]) == 'pvc missing'
        VmSnapshotApiHelper.restoreError([status: [conditions: [[type: 'Ready', status: 'False', reason: 'InProgress']]]]) == null
    }
}
