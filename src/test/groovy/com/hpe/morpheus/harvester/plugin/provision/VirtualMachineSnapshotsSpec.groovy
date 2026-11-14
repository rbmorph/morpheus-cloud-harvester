// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.provision

import com.hpe.morpheus.harvester.plugin.apihelper.VmSnapshotApiHelper
import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusSnapshotService
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Snapshot
import com.morpheusdata.response.ServiceResponse
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import spock.lang.Unroll

class VirtualMachineSnapshotsSpec extends Specification {

    MorpheusContext ctx = Mock(MorpheusContext)
    MorpheusAsyncServices async = Mock(MorpheusAsyncServices)
    MorpheusSnapshotService snapshotService = Mock(MorpheusSnapshotService)
    VmSnapshotApiHelper api = Mock(VmSnapshotApiHelper)
    VirtualMachineSnapshots snapshots

    def setup() {
        ctx.getAsync() >> async
        ctx.async >> async
        async.getSnapshot() >> snapshotService
        async.snapshot >> snapshotService
        snapshots = new VirtualMachineSnapshots(ctx, api)
        snapshots.pollIntervalMs = 0L
    }

    private static ComputeServer server() {
        def s = new ComputeServer(id: 9L, name: 'vm1', externalId: 'uid-1', cloud: new Cloud(id: 5L))
        s.setConfigMap([project: [name: 'ns1'], vmname: 'vm1'])
        return s
    }

    def "createSnapshot takes a Harvester snapshot, waits until ready and records it"() {
        given:
        def saved = new Snapshot(id: 77L)

        when:
        def result = snapshots.createSnapshot(server(), [snapshotName: 'Before Upgrade!', description: 'pre-patch'])

        then:
        1 * api.get(VmSnapshotApiHelper.vmPath('ns1', 'vm1')) >> ServiceResponse.success([metadata: [uid: 'uid-1'], status: [printableStatus: 'Running']])
        1 * api.create(VmSnapshotApiHelper.backupsPath('ns1'), { Map b ->
            b.kind == 'VirtualMachineBackup' && b.metadata.name == 'before-upgrade' && b.spec.type == 'snapshot' &&
                    b.spec.source == [apiGroup: 'kubevirt.io', kind: 'VirtualMachine', name: 'vm1']
        }) >> ServiceResponse.success([:])
        2 * api.get(VmSnapshotApiHelper.backupsPath('ns1', 'before-upgrade')) >>>
                [ServiceResponse.success([status: [readyToUse: false]]),
                 ServiceResponse.success([metadata: [name: 'before-upgrade', namespace: 'ns1'],
                                          spec: [source: [name: 'vm1']],
                                          status: [readyToUse: true, creationTime: '2026-11-13T15:00:00Z',
                                                   volumeBackups: [[volumeSize: 1000L], [volumeSize: 24L]]]])]
        1 * snapshotService.listIdentityProjections(5L, null) >> Observable.empty()
        1 * snapshotService.create({ Snapshot s ->
            s.name == 'before-upgrade' && s.externalId == 'ns1/before-upgrade' && s.maxStorage == 1024L &&
                    s.description == 'pre-patch' && s.snapshotCreated != null
        }) >> Single.just(saved)
        1 * snapshotService.addSnapshot(saved, _ as ComputeServer) >> Single.just(true)
        result.success
        result.data.id == 77L
    }

    def "createSnapshot reports Harvester's error"() {
        when:
        def result = snapshots.createSnapshot(server(), [:])

        then:
        1 * api.get(VmSnapshotApiHelper.vmPath('ns1', 'vm1')) >> ServiceResponse.success([metadata: [uid: 'uid-1']])
        1 * api.create(_, _) >> ServiceResponse.success([:])
        1 * api.get({ it.contains('virtualmachinebackups/') }) >> ServiceResponse.success([status: [error: [message: 'volume snapshot class not found']]])
        0 * snapshotService.create(_)
        !result.success
        result.msg.contains('volume snapshot class not found')
    }

    def "createSnapshot refuses when the VM in Harvester is a different VM"() {
        when:
        def result = snapshots.createSnapshot(server(), [:])

        then:
        1 * api.get(_) >> ServiceResponse.success([metadata: [uid: 'other-uid']])
        0 * api.create(_, _)
        !result.success
        result.msg.contains('UID')
    }

    def "revertSnapshot stops a running VM, restores the snapshot keeping old volumes, then starts it"() {
        when:
        def result = snapshots.revertSnapshot(server(), new Snapshot(name: 'snap1', externalId: 'ns1/snap1'), [:])

        then:
        3 * api.get(VmSnapshotApiHelper.vmPath('ns1', 'vm1')) >>>
                [ServiceResponse.success([metadata: [uid: 'uid-1'], status: [printableStatus: 'Running']]),
                 ServiceResponse.success([status: [printableStatus: 'Stopping']]),
                 ServiceResponse.success([status: [printableStatus: 'Stopped']])]
        1 * api.get(VmSnapshotApiHelper.backupsPath('ns1', 'snap1')) >> ServiceResponse.success([status: [readyToUse: true]])
        1 * api.put(VmSnapshotApiHelper.vmSubresourcePath('ns1', 'vm1', 'stop'), [:]) >> ServiceResponse.success([:])
        1 * api.create(VmSnapshotApiHelper.restoresPath('ns1'), { Map b ->
            b.kind == 'VirtualMachineRestore' && b.spec.virtualMachineBackupName == 'snap1' &&
                    b.spec.virtualMachineBackupNamespace == 'ns1' && b.spec.newVM == false &&
                    b.spec.deletionPolicy == 'retain' && b.spec.target.name == 'vm1'
        }) >> ServiceResponse.success([:])
        1 * api.get({ it.contains('virtualmachinerestores/') }) >> ServiceResponse.success([status: [complete: true]])
        1 * api.put(VmSnapshotApiHelper.vmSubresourcePath('ns1', 'vm1', 'start'), [:]) >> ServiceResponse.success([:])
        result.success
    }

    def "revertSnapshot leaves a stopped VM stopped"() {
        when:
        def result = snapshots.revertSnapshot(server(), new Snapshot(name: 'snap1', externalId: 'ns1/snap1'), [:])

        then:
        1 * api.get(VmSnapshotApiHelper.vmPath('ns1', 'vm1')) >> ServiceResponse.success([metadata: [uid: 'uid-1'], status: [printableStatus: 'Stopped']])
        1 * api.get(VmSnapshotApiHelper.backupsPath('ns1', 'snap1')) >> ServiceResponse.success([status: [readyToUse: true]])
        1 * api.create(_, _) >> ServiceResponse.success([:])
        1 * api.get({ it.contains('virtualmachinerestores/') }) >> ServiceResponse.success([status: [complete: true]])
        0 * api.put(_, _)
        result.success
    }

    def "revertSnapshot refuses a snapshot from another namespace"() {
        when:
        def result = snapshots.revertSnapshot(server(), new Snapshot(name: 'snap1', externalId: 'other/snap1'), [:])

        then:
        0 * api.create(_, _)
        !result.success
        result.msg.contains('own namespace')
    }

    def "deleteSnapshot deletes in Harvester and accepts one that is already gone"() {
        when:
        def ok = snapshots.deleteSnapshot(new Snapshot(name: 's', externalId: 'ns1/s'), [:])
        def gone = snapshots.deleteSnapshot(new Snapshot(name: 'g', externalId: 'ns1/g'), [:])

        then:
        1 * api.delete(VmSnapshotApiHelper.backupsPath('ns1', 's')) >> ServiceResponse.success([:])
        1 * api.delete(VmSnapshotApiHelper.backupsPath('ns1', 'g')) >> new ServiceResponse(success: false, errorCode: '404')
        ok.success
        gone.success
    }

    @Unroll
    def "snapshotName('#requested') -> #expected"() {
        expect:
        VirtualMachineSnapshots.snapshotName(requested, 'vm1') == expected

        where:
        requested            || expected
        'Before Upgrade!'    || 'before-upgrade'
        'nightly_2026.11'    || 'nightly-2026.11'
        '--x--'              || 'x'
        'a' * 70             || 'a' * 63
    }

    def "snapshotName defaults to the VM name and a UTC timestamp"() {
        expect:
        VirtualMachineSnapshots.snapshotName(null, 'vm1') ==~ /vm1-\d{8}-\d{6}/
        VirtualMachineSnapshots.snapshotName('  ', 'vm1') ==~ /vm1-\d{8}-\d{6}/
    }

    def "externalId round trip"() {
        expect:
        VirtualMachineSnapshots.externalId('ns1', 'snap') == 'ns1/snap'
        VirtualMachineSnapshots.parseExternalId('ns1/snap') == [namespace: 'ns1', name: 'snap']
        VirtualMachineSnapshots.parseExternalId('nosep') == null
        VirtualMachineSnapshots.parseExternalId('/x') == null
        VirtualMachineSnapshots.parseExternalId(null) == null
    }

    def "restoreName is a valid Kubernetes name of at most 63 characters"() {
        expect:
        VirtualMachineSnapshots.restoreName('vm1') ==~ /restore-vm1-\d+/
        VirtualMachineSnapshots.restoreName('v' * 80).length() <= 63
    }
}
