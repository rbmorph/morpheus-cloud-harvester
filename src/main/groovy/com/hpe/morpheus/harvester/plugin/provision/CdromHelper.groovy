// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.provision

import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import groovy.util.logging.Slf4j

/**
 * CD-ROMs on Harvester VMs, the way Harvester's UI adds them: a disk with "cdrom: {bus: sata}" whose volume is a PVC
 * created from the ISO image (the image's storage class + the harvesterhci.io/imageId annotation).
 * The plugin's ISO PVCs are labelled morpheus-label.cdrom-vm=<vm>, so they're deleted with the VM.
 */
@Slf4j
class CdromHelper {

    static final String CDROM_BUS = 'sata'
    static final long GIB = 1024L * 1024L * 1024L

    /** A Morpheus virtual image synced from a Harvester ISO */
    static boolean isIsoImage(def virtualImage) {
        if (!virtualImage) return false
        if (virtualImage.imageType?.toString() == 'iso') return true
        try {
            return virtualImage.getConfigMap()?.iso == true
        } catch (Exception ignored) {
            return false
        }
    }

    /** PVC holding the ISO for a CD-ROM */
    static Map isoPvcBody(String namespace, String claimName, String vmName, String imageId, String storageClass, Long sizeBytes) {
        long gib = Math.max(1L, (long) Math.ceil(((sizeBytes ?: GIB) as double) / GIB))
        return [
                apiVersion: 'v1',
                kind      : 'PersistentVolumeClaim',
                metadata  : [
                        name       : claimName,
                        namespace  : namespace,
                        annotations: [(HarvesterConstants.ANNOTATION_IMAGE_ID): imageId],
                        labels     : [(HarvesterConstants.LABEL_CDROM_VM): vmName]
                ],
                spec      : [
                        accessModes     : ['ReadWriteMany'],
                        volumeMode      : 'Block',
                        storageClassName: storageClass,
                        resources       : [requests: [storage: "${gib}Gi".toString()]]
                ]
        ]
    }

    /** PVC name for a VM's CD-ROM disk: "<vm>-<disk>", a valid name of at most 63 characters */
    static String claimName(String vmName, String diskName) {
        String name = "${vmName}-${diskName}".toLowerCase().replaceAll(/[^a-z0-9-]/, '-').replaceAll(/-{2,}/, '-')
        if (name.length() > 63) {
            name = name.substring(name.length() - 63)
        }
        return name.replaceAll(/^-+|-+$/, '')
    }

    /** The VM's CD-ROM drives: [name, bus, bootOrder, claimName] */
    static List<Map> cdroms(Map vm) {
        Map spec = vm?.spec?.template?.spec as Map
        List disks = (spec?.domain?.devices?.disks ?: []) as List
        List volumes = (spec?.volumes ?: []) as List
        return disks.findAll { it?.cdrom != null }.collect { disk ->
            def volume = volumes.find { it?.name == disk.name }
            [name     : disk.name,
             bus      : disk.cdrom?.bus ?: CDROM_BUS,
             bootOrder: disk.bootOrder,
             claimName: volume?.persistentVolumeClaim?.claimName ?: volume?.dataVolume?.name]
        }
    }

    /** First free name "cdrom-1", "cdrom-2", … among the VM's disks and volumes */
    static String nextCdromName(Map vm) {
        Map spec = vm?.spec?.template?.spec as Map
        Set used = ((spec?.domain?.devices?.disks ?: []).collect { it?.name } + (spec?.volumes ?: []).collect { it?.name }) as Set
        int i = 1
        while (used.contains("cdrom-${i}".toString())) i++
        return "cdrom-${i}".toString()
    }

    /**
     * Adds a CD-ROM to a VM body that is about to be created. bootFirst: boot order 1, other disks move down by one.
     */
    static void addCdrom(Map vmBody, String diskName, String claimName, boolean bootFirst) {
        Map spec = vmBody.spec.template.spec as Map
        List disks = spec.domain.devices.disks as List
        Map disk = [name: diskName, cdrom: [bus: CDROM_BUS]]
        if (bootFirst) {
            disks.each { if (it.bootOrder != null) it.bootOrder = (it.bootOrder as Integer) + 1 }
            disk.bootOrder = 1
        }
        disks.add(0, disk)
        spec.volumes = ((spec.volumes ?: []) as List) + [[name: diskName, persistentVolumeClaim: [claimName: claimName]]]
    }

    /**
     * JSON patch adding a CD-ROM to an existing VM. Refused by Harvester if the VM changed since it was read
     * (resourceVersion test). bootFirst: boot order 1, the VM's other boot orders move down by one.
     */
    static List attachPatch(Map vm, String diskName, String claimName, boolean bootFirst) {
        List ops = []
        if (vm?.metadata?.resourceVersion) {
            ops << [op: 'test', path: '/metadata/resourceVersion', value: vm.metadata.resourceVersion]
        }
        Map disk = [name: diskName, cdrom: [bus: CDROM_BUS]]
        if (bootFirst) {
            List disks = (vm?.spec?.template?.spec?.domain?.devices?.disks ?: []) as List
            disks.eachWithIndex { d, i ->
                if (d?.bootOrder != null) {
                    ops << [op: 'replace', path: "/spec/template/spec/domain/devices/disks/${i}/bootOrder".toString(),
                            value: (d.bootOrder as Integer) + 1]
                }
            }
            disk.bootOrder = 1
        }
        ops << [op: 'add', path: '/spec/template/spec/domain/devices/disks/-', value: disk]
        ops << [op: 'add', path: '/spec/template/spec/volumes/-',
                value: [name: diskName, persistentVolumeClaim: [claimName: claimName]]]
        return ops
    }

    /** JSON patch removing a CD-ROM disk and its volume; null when the VM has no such CD-ROM */
    static List ejectPatch(Map vm, String diskName) {
        List disks = (vm?.spec?.template?.spec?.domain?.devices?.disks ?: []) as List
        List volumes = (vm?.spec?.template?.spec?.volumes ?: []) as List
        int diskIndex = disks.findIndexOf { it?.name == diskName && it?.cdrom != null }
        if (diskIndex < 0) return null
        int volumeIndex = volumes.findIndexOf { it?.name == diskName }
        List ops = []
        if (vm?.metadata?.resourceVersion) {
            ops << [op: 'test', path: '/metadata/resourceVersion', value: vm.metadata.resourceVersion]
        }
        ops << [op: 'remove', path: "/spec/template/spec/domain/devices/disks/${diskIndex}".toString()]
        if (volumeIndex >= 0) {
            ops << [op: 'remove', path: "/spec/template/spec/volumes/${volumeIndex}".toString()]
        }
        return ops
    }

    static String pvcPath(String namespace, String name = null) {
        return "/api/v1/namespaces/${namespace}/persistentvolumeclaims${name ? '/' + name : ''}".toString()
    }

    static String vmPath(String namespace, String name) {
        return "/apis/kubevirt.io/v1/namespaces/${namespace}/virtualmachines/${name}".toString()
    }
}
