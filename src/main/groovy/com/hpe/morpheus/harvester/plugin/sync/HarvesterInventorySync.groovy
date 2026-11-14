// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.sync

import com.hpe.morpheus.harvester.plugin.HpeHarvesterPlugin
import com.morpheusdata.model.ComputeServerGroup
import groovy.util.logging.Slf4j

/**
 * Runs the full Harvester inventory sync (nodes, namespaces, storage, images, networks, VMs) for one Harvester
 * connection. Used by the Harvester cloud refresh, and by the cluster refresh for clusters added with older versions.
 */
@Slf4j
class HarvesterInventorySync {

    private final HpeHarvesterPlugin plugin
    private final ComputeServerGroup cluster
    private final boolean importExisting

    HarvesterInventorySync(HpeHarvesterPlugin plugin, ComputeServerGroup cluster, boolean importExisting) {
        this.plugin = plugin
        this.cluster = cluster
        this.importExisting = importExisting
    }

    void execute() {
        log.info("Harvester: starting inventory sync for ${cluster.name}, importExisting: ${importExisting}")
        new NodesSync(plugin, cluster).execute()
        new ProjectsSync(plugin, cluster).execute()
        new StorageClassesSync(plugin, cluster).execute()
        new PVCsSync(plugin, cluster).execute()
        new DataVolumesSync(plugin, cluster).execute()
        new VirtualImagesSync(plugin, cluster).execute()
        new NADsSync(plugin, cluster).execute()
        new VMsSync(plugin, cluster, importExisting).execute()
        // after the VMs, so snapshots can be linked to them
        new SnapshotsSync(plugin, cluster).execute()
        log.info("Harvester: completed inventory sync for ${cluster.name}")
    }
}
