// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.apihelper

import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

/**
 * Harvester VM snapshots: harvesterhci.io/v1beta1 VirtualMachineBackup with spec.type "snapshot" (what Harvester's UI
 * "Take Snapshot" creates, stored in the cluster), restored with a VirtualMachineRestore.
 */
@Slf4j
class VmSnapshotApiHelper {

    static final String API = '/apis/harvesterhci.io/v1beta1'
    static final String ALL_BACKUPS_PATH = "${API}/virtualmachinebackups"
    static final String TYPE_SNAPSHOT = 'snapshot'

    private HarvesterApiClient apiClient

    VmSnapshotApiHelper(HarvesterApiClient apiClient) {
        this.apiClient = apiClient
    }

    static String backupsPath(String namespace, String name = null) {
        return "${API}/namespaces/${namespace}/virtualmachinebackups${name ? '/' + name : ''}".toString()
    }

    static String restoresPath(String namespace, String name = null) {
        return "${API}/namespaces/${namespace}/virtualmachinerestores${name ? '/' + name : ''}".toString()
    }

    static String vmPath(String namespace, String name) {
        return "/apis/kubevirt.io/v1/namespaces/${namespace}/virtualmachines/${name}".toString()
    }

    static String vmSubresourcePath(String namespace, String name, String action) {
        return "/apis/subresources.kubevirt.io/v1/namespaces/${namespace}/virtualmachines/${name}/${action}".toString()
    }

    /** The VirtualMachineBackup Harvester creates for "Take Snapshot" */
    static Map snapshotBody(String namespace, String name, String vmName, Map labels = [:]) {
        return [
                apiVersion: 'harvesterhci.io/v1beta1',
                kind      : 'VirtualMachineBackup',
                metadata  : [name: name, namespace: namespace] + (labels ? [labels: labels] : [:]),
                spec      : [
                        source: [apiGroup: 'kubevirt.io', kind: 'VirtualMachine', name: vmName],
                        type  : TYPE_SNAPSHOT
                ]
        ]
    }

    /**
     * Restore onto the same VM. Harvester only allows "retain" for snapshots replacing a VM, and requires the VM to be
     * stopped and the snapshot to be in the VM's namespace.
     */
    static Map restoreBody(String namespace, String name, String vmName, String snapshotName) {
        return [
                apiVersion: 'harvesterhci.io/v1beta1',
                kind      : 'VirtualMachineRestore',
                metadata  : [name: name, namespace: namespace],
                spec      : [
                        target                       : [apiGroup: 'kubevirt.io', kind: 'VirtualMachine', name: vmName],
                        virtualMachineBackupName     : snapshotName,
                        virtualMachineBackupNamespace: namespace,
                        newVM                        : false,
                        deletionPolicy               : 'retain'
                ]
        ]
    }

    /** @return snapshots (type snapshot) across all namespaces; success false if the API can't be read */
    ServiceResponse listSnapshots() {
        ServiceResponse resp = apiClient.get(ALL_BACKUPS_PATH)
        if (!resp?.success) {
            return resp ?: ServiceResponse.error('No response listing VM snapshots')
        }
        List items = ((resp.data?.items ?: []) as List).findAll { isSnapshot(it as Map) }
        return ServiceResponse.success(items)
    }

    static boolean isSnapshot(Map item) {
        return item?.spec?.type == TYPE_SNAPSHOT && item?.spec?.source?.kind == 'VirtualMachine'
    }

    static boolean isReady(Map item) {
        return item?.status?.readyToUse == true
    }

    static String errorOf(Map item) {
        return item?.status?.error?.message?.toString()
    }

    /** Total size of the snapshot's volumes, in bytes */
    static Long sizeOf(Map item) {
        def sizes = (item?.status?.volumeBackups ?: []).collect { it?.volumeSize }.findAll { it != null }
        return sizes ? (sizes.sum { it as Long } as Long) : null
    }

    /** Restore finished: status.complete true. Failed: a condition (Ready) with status False and a message. */
    static boolean isRestoreComplete(Map restore) {
        return restore?.status?.complete == true
    }

    static String restoreError(Map restore) {
        def failed = (restore?.status?.conditions ?: []).find { c ->
            c?.status?.toString() == 'False' && c?.reason?.toString()?.toLowerCase()?.contains('fail')
        }
        return failed ? (failed.message ?: failed.reason)?.toString() : null
    }

    ServiceResponse get(String path) {
        return apiClient.get(path)
    }

    ServiceResponse create(String path, Map body) {
        return apiClient.post(path, body)
    }

    ServiceResponse delete(String path) {
        return apiClient.delete(path)
    }

    ServiceResponse put(String path, def body) {
        return apiClient.put(path, body)
    }

    static boolean isNotFound(ServiceResponse resp) {
        return resp?.errorCode?.toString() == '404' || resp?.msg?.toString()?.contains('404') ||
                (resp?.data instanceof Map && (resp.data as Map).code?.toString() == '404')
    }
}
