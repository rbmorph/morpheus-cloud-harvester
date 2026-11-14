// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.sync

import com.hpe.morpheus.harvester.plugin.HpeHarvesterPlugin
import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.hpe.morpheus.harvester.plugin.apihelper.VmSnapshotApiHelper
import com.hpe.morpheus.harvester.plugin.provision.VirtualMachineSnapshots
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Snapshot
import com.morpheusdata.model.projection.SnapshotIdentityProjection
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

/**
 * Harvester VM snapshots (VirtualMachineBackup type "snapshot") <-> Morpheus snapshots of the cloud's VMs.
 * Snapshots taken in Harvester appear in Morpheus, linked to their VM; snapshots deleted in Harvester are removed.
 * Only ready snapshots of VMs Morpheus knows are added.
 */
@Slf4j
class SnapshotsSync {

    private ComputeServerGroup cluster
    private MorpheusContext morpheusContext
    private VmSnapshotApiHelper api

    SnapshotsSync(HpeHarvesterPlugin plugin, ComputeServerGroup cluster, VmSnapshotApiHelper api = null) {
        this.cluster = cluster
        this.morpheusContext = plugin.morpheusContext
        this.api = api ?: new VmSnapshotApiHelper(new HarvesterApiClient(cluster))
    }

    def execute() {
        try {
            Cloud cloud = cluster.cloud
            ServiceResponse listed = api.listSnapshots()
            if (!listed.success) {
                // Harvester without VM snapshots, or no access: leave Morpheus as it is
                log.info("Harvester: VM snapshots not available for ${cluster.name}: ${listed.msg ?: listed.errorCode}")
                return
            }
            Map<String, ComputeServer> vmsByName = vmsByNamespaceAndName(cloud)
            List<Map> snapshots = ((listed.data ?: []) as List<Map>).findAll { Map item ->
                VmSnapshotApiHelper.isReady(item) &&
                        vmsByName.containsKey(key(item.metadata?.namespace, item.spec?.source?.name))
            }

            Observable<SnapshotIdentityProjection> domainRecords = morpheusContext.async.snapshot
                    .listIdentityProjections(cloud.id, null)
                    .filter { SnapshotIdentityProjection s -> VirtualMachineSnapshots.parseExternalId(s.externalId) != null }

            SyncTask<SnapshotIdentityProjection, Map, Snapshot> syncTask = new SyncTask<>(domainRecords, snapshots)
            syncTask.addMatchFunction { SnapshotIdentityProjection existing, Map item ->
                existing.externalId == VirtualMachineSnapshots.externalId(item.metadata?.namespace as String, item.metadata?.name as String)
            }.onAdd { List<Map> items ->
                items.each { Map item ->
                    ComputeServer vm = vmsByName[key(item.metadata?.namespace, item.spec?.source?.name)]
                    try {
                        Snapshot saved = morpheusContext.async.snapshot.create(VirtualMachineSnapshots.buildRecord(item, vm)).blockingGet()
                        if (saved) {
                            morpheusContext.async.snapshot.addSnapshot(saved, vm).blockingGet()
                        }
                    } catch (Exception e) {
                        log.warn("Harvester: adding snapshot ${item.metadata?.name} failed: ${e.message}")
                    }
                }
            }.onUpdate { updateItems ->
                // name and VM don't change in Harvester; nothing to update
            }.onDelete { List<SnapshotIdentityProjection> removeItems ->
                log.info("Harvester: removing ${removeItems.size()} snapshot(s) deleted in Harvester")
                morpheusContext.async.snapshot.removeSnapshots(removeItems).blockingGet()
            }.withLoadObjectDetailsFromFinder { updateItems ->
                return morpheusContext.async.snapshot.listByIds(updateItems.collect { it.existingItem.id } as List<Long>)
            }.start()
        } catch (Exception e) {
            log.error("Harvester: SnapshotsSync error for ${cluster?.name}: ${e.message}", e)
        }
    }

    /** The cloud's Harvester VMs, keyed "<namespace>/<VM name>" */
    private Map<String, ComputeServer> vmsByNamespaceAndName(Cloud cloud) {
        List<ComputeServer> servers = morpheusContext.services.computeServer.list(new DataQuery().withFilters(
                new DataFilter('cloud.id', cloud.id),
                new DataFilter('category', "${HarvesterConstants.CATEGORY_HARVESTER_VM_PREFIX}${cluster.id}".toString())
        ))
        Map<String, ComputeServer> result = [:]
        servers.each { ComputeServer s ->
            def project = s.getConfigProperty('project')
            String namespace = project instanceof Map ? project.name : project?.toString()
            String vmName = s.getConfigProperty('vmname')?.toString() ?: s.name
            if (namespace && vmName) result[key(namespace, vmName)] = s
        }
        return result
    }

    static String key(def namespace, def name) {
        return "${namespace}/${name}".toString()
    }
}
