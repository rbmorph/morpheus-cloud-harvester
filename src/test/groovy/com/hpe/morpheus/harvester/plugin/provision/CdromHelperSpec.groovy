// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.provision

import com.hpe.morpheus.harvester.plugin.sync.VirtualImagesSync
import com.morpheusdata.model.VirtualImage
import spock.lang.Specification
import spock.lang.Unroll

class CdromHelperSpec extends Specification {

    static Map vm() {
        [metadata: [name: 'vm1', namespace: 'ns1', resourceVersion: '42'],
         spec    : [template: [spec: [
                 domain : [devices: [disks: [
                         [name: 'rootdisk', bootOrder: 1, disk: [bus: 'virtio']],
                         [name: 'cdrom-1', cdrom: [bus: 'sata']],
                         [name: 'data', bootOrder: 2, disk: [bus: 'virtio']]]]],
                 volumes: [
                         [name: 'rootdisk', persistentVolumeClaim: [claimName: 'pvc-root']],
                         [name: 'cdrom-1', persistentVolumeClaim: [claimName: 'vm1-cdrom-1']],
                         [name: 'data', persistentVolumeClaim: [claimName: 'pvc-data']]]]]]]
    }

    def "isoPvcBody uses the image's storage class, the image annotation and the VM label, rounded up to GiB"() {
        when:
        def body = CdromHelper.isoPvcBody('ns1', 'vm1-cdrom-1', 'vm1', 'default/ubuntu-iso', 'longhorn-ubuntu-iso', 5L * 1024 * 1024 * 1024 + 1)

        then:
        body.kind == 'PersistentVolumeClaim'
        body.metadata.annotations == ['harvesterhci.io/imageId': 'default/ubuntu-iso']
        body.metadata.labels == ['morpheus-label.cdrom-vm': 'vm1']
        body.spec.storageClassName == 'longhorn-ubuntu-iso'
        body.spec.volumeMode == 'Block'
        body.spec.accessModes == ['ReadWriteMany']
        body.spec.resources.requests.storage == '6Gi'
        CdromHelper.isoPvcBody('n', 'c', 'v', 'i', 's', null).spec.resources.requests.storage == '1Gi'
    }

    def "cdroms lists only CD-ROM disks with their volume"() {
        expect:
        CdromHelper.cdroms(vm()) == [[name: 'cdrom-1', bus: 'sata', bootOrder: null, claimName: 'vm1-cdrom-1']]
        CdromHelper.cdroms([:]) == []
    }

    def "nextCdromName skips names in use"() {
        expect:
        CdromHelper.nextCdromName(vm()) == 'cdrom-2'
        CdromHelper.nextCdromName([spec: [template: [spec: [domain: [devices: [disks: []]], volumes: []]]]]) == 'cdrom-1'
    }

    @Unroll
    def "claimName('#vmName', '#disk') = #expected"() {
        expect:
        CdromHelper.claimName(vmName, disk) == expected

        where:
        vmName      | disk      || expected
        'vm1'       | 'cdrom-1' || 'vm1-cdrom-1'
        'My_VM'     | 'cdrom-2' || 'my-vm-cdrom-2'
        'a' * 70    | 'cdrom-1' || ('a' * 70 + '-cdrom-1').substring(70 + 8 - 63)
    }

    def "addCdrom puts a boot-first CD-ROM ahead of the disks"() {
        given:
        Map body = [spec: [template: [spec: [domain: [devices: [disks: [
                [name: 'root', bootOrder: 1, disk: [bus: 'virtio']],
                [name: 'cloudinitdisk', disk: [bus: 'scsi']]]]],
                volumes: [[name: 'root', persistentVolumeClaim: [claimName: 'pvc-root']]]]]]]

        when:
        CdromHelper.addCdrom(body, 'cdrom-1', 'vm1-cdrom-1', true)

        then:
        def disks = body.spec.template.spec.domain.devices.disks
        disks[0] == [name: 'cdrom-1', cdrom: [bus: 'sata'], bootOrder: 1]
        disks[1].bootOrder == 2
        disks[2].bootOrder == null
        body.spec.template.spec.volumes[-1] == [name: 'cdrom-1', persistentVolumeClaim: [claimName: 'vm1-cdrom-1']]
    }

    def "attachPatch adds the disk and volume, guarded by resourceVersion"() {
        expect:
        CdromHelper.attachPatch(vm(), 'cdrom-2', 'vm1-cdrom-2', false) == [
                [op: 'test', path: '/metadata/resourceVersion', value: '42'],
                [op: 'add', path: '/spec/template/spec/domain/devices/disks/-', value: [name: 'cdrom-2', cdrom: [bus: 'sata']]],
                [op: 'add', path: '/spec/template/spec/volumes/-', value: [name: 'cdrom-2', persistentVolumeClaim: [claimName: 'vm1-cdrom-2']]]
        ]
    }

    def "attachPatch with bootFirst moves the other boot orders down"() {
        when:
        def ops = CdromHelper.attachPatch(vm(), 'cdrom-2', 'vm1-cdrom-2', true)

        then:
        ops.findAll { it.op == 'replace' } == [
                [op: 'replace', path: '/spec/template/spec/domain/devices/disks/0/bootOrder', value: 2],
                [op: 'replace', path: '/spec/template/spec/domain/devices/disks/2/bootOrder', value: 3]
        ]
        ops.find { it.op == 'add' && it.path.contains('disks') }.value.bootOrder == 1
    }

    def "ejectPatch removes the CD-ROM disk and its volume"() {
        expect:
        CdromHelper.ejectPatch(vm(), 'cdrom-1') == [
                [op: 'test', path: '/metadata/resourceVersion', value: '42'],
                [op: 'remove', path: '/spec/template/spec/domain/devices/disks/1'],
                [op: 'remove', path: '/spec/template/spec/volumes/1']
        ]
        CdromHelper.ejectPatch(vm(), 'rootdisk') == null
        CdromHelper.ejectPatch(vm(), 'nope') == null
    }

    def "isIsoImage"() {
        expect:
        CdromHelper.isIsoImage(new VirtualImage(imageType: 'iso'))
        !CdromHelper.isIsoImage(new VirtualImage(imageType: 'raw'))
        !CdromHelper.isIsoImage(null)
    }

    @Unroll
    def "VirtualImagesSync.isIsoImage #desc"() {
        expect:
        VirtualImagesSync.isIsoImage(image) == expected

        where:
        desc                         | image                                                                                         || expected
        'label iso'                  | [metadata: [name: 'x', labels: ['harvesterhci.io/image-type': 'iso']]]                         || true
        'label raw_qcow2 wins'       | [metadata: [name: 'x.iso', labels: ['harvesterhci.io/image-type': 'raw_qcow2']]]              || false
        'name ends in .iso'          | [metadata: [name: 'ubuntu.iso']]                                                                || true
        'display name ends in .iso'  | [metadata: [name: 'image-abc'], spec: [displayName: 'Win2022.ISO']]                           || true
        'url ends in .iso'           | [metadata: [name: 'i'], spec: [url: 'https://x/rocky-9.iso?token=1']]                          || true
        'qcow2'                      | [metadata: [name: 'i'], spec: [displayName: 'jammy.img', url: 'https://x/jammy.qcow2']]        || false
    }
}
