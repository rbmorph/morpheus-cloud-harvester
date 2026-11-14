// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.sync

import com.hpe.morpheus.harvester.plugin.HpeHarvesterClusterProvider
import com.hpe.morpheus.harvester.plugin.HpeHarvesterPlugin
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.DatastoreType
import com.morpheusdata.model.projection.DatastoreIdentity
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable
import com.hpe.morpheus.harvester.plugin.apihelper.StorageClassApiHelper
import com.hpe.morpheus.harvester.plugin.apihelper.StorageCapacityApiHelper
import groovy.json.JsonOutput
import com.hpe.morpheus.harvester.plugin.utils.DBUtils


@Slf4j
class StorageClassesSync {

    private ComputeServerGroup cluster
    private MorpheusContext morpheusContext
    private HpeHarvesterPlugin plugin

    StorageClassesSync(HpeHarvesterPlugin plugin, ComputeServerGroup cluster) {
        this.plugin = plugin
        this.cluster = cluster
        this.morpheusContext = plugin.morpheusContext
    }

    def execute() {
        log.debug "Harvester: execute StorageClass Sync: ${cluster}"
        try {

            def storageClassHelper = new StorageClassApiHelper(cluster)
            def storageClassesResponse = storageClassHelper.getStorageClasses()

            if (storageClassesResponse.success) {
                def storageClasses = storageClassesResponse.data?.storageClasses
                log.debug "Harvester: Found ${storageClasses?.size()} Storage Classes for cluster harvester ${cluster.name}"

                // Only storage classes VMs can be deployed to become datastores
                Set<String> vmProvisioners = storageClassHelper.getVmStorageProvisioners()
                boolean allFilteredOut = false
                if (vmProvisioners != null && storageClasses) {
                    def skipped = storageClasses.findAll { !StorageClassApiHelper.isVmStorageClass(it as Map, vmProvisioners) }
                    if (skipped) {
                        log.info "Harvester: not syncing storage classes VMs can't use: ${skipped*.name}"
                    }
                    storageClasses = storageClasses - skipped
                    allFilteredOut = storageClasses.isEmpty()
                }

                // When every class was filtered out the sync still runs, so their old datastores are removed
                if (storageClasses || allFilteredOut) {
                    def stgCls = storageClasses
                    Long storageVolumeTypeId = null

                    // Get storage volume type id to fill in datastore entry
                    def storage = morpheusContext.services.getStorage()

                    // A Harvester cloud's datastores carry the disk type VMs use (Harvester Volume), so they match the
                    // disks in the provisioning wizard. Clusters added with earlier versions keep 'kubernetesVolume'.
                    String volumeTypeCode = datastoreVolumeTypeCode()
                    def storageVolumeType = storage.volume.storageVolumeType.list(
                            new DataQuery().withFilters(
                                    new DataFilter('code', volumeTypeCode),
                            )
                    )

                    if (!storageVolumeType || storageVolumeType.isEmpty()) {
                        log.error "Harvester: No storage volume type found with code '${volumeTypeCode}'. Please ensure the storage volume type is created in Morpheus."
                        return
                    }

                    // Size and free space of each storage class (from Longhorn disks, or CSIStorageCapacity)
                    Map<String, Map> capacities = [:]
                    try {
                        capacities = new StorageCapacityApiHelper(cluster).getCapacities(stgCls as List)
                        log.debug "Harvester: storage class capacities: ${capacities}"
                    } catch (Exception capEx) {
                        log.warn "Harvester: unable to read storage class capacity: ${capEx.message}"
                    }

                    // update storageClasses to include the storage volume type id
                    stgCls = stgCls.collect { Map storageClass ->
                        storageClass.storageVolumeType = storageVolumeType[0]
                        storageClass.capacity = capacities[storageClass.name as String]
                        return storageClass
                    }

                    CloudPool resourcePool = DBUtils.getClusterResourcePool(morpheusContext, cluster)

                    log.debug "Harvester: Storage Classes with volume type id: ${stgCls}"

                    def datastoreList
                    try {

                        datastoreList = morpheusContext.services.cloud.datastore.listIdentityProjections(
                                new DataQuery().withFilters(
                                        // by category: matches datastores of a Harvester cloud and of older clusters
                                        DBUtils.datastoreFilter(cluster),
                                )
                        )
                        log.debug("Harvester: Found ${datastoreList.size()} existing datastores in Morpheus")
                    } catch(e) {
                        log.error("Harvester: Error retrieving existing datastores: ${e.message}", e)
                        throw e
                    }

                    // Create an Observable from the list
                    Observable<DatastoreIdentity> domainRecords = Observable.fromIterable(datastoreList)

                    SyncTask<DatastoreIdentity, Map, Datastore> syncTask = new SyncTask<>(domainRecords, stgCls ?: [])
                    syncTask.addMatchFunction { DatastoreIdentity domainObject, Map apiItem ->
                        domainObject.externalId == apiItem.uid
                    }.onDelete { removeItems ->
                        removeStorageClasses(removeItems)
                    }.onUpdate { List<SyncTask.UpdateItem<Datastore, Map>> updateItems ->
                        updateStorageClasses(updateItems)
                    }.onAdd { itemsToAdd ->
                        addStorageClasses(itemsToAdd, resourcePool)
                    }.withLoadObjectDetailsFromFinder { updateItems ->
                        def fullDatastoreList = morpheusContext.services.cloud.datastore.list(
                                new DataQuery().withFilters(
                                        new DataOrFilter(updateItems.collect {new DataFilter('id', it.existingItem.id) }),
                                ))
                        return Observable.fromIterable(fullDatastoreList)
                    }.start()
                }
            } else {
                log.warn "Harvester: No Storage Classes found for cluster ${cluster.name}"
            }
        } catch(e) {
            log.error("Harvester: StorageClassesSync error: ${e}", e)
        }
    }

    String datastoreVolumeTypeCode() {
        return HpeHarvesterClusterProvider.isCloudManaged(cluster) ? HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC : 'kubernetesVolume'
    }

    /**
     * Datastores of a Harvester cloud aren't tied to one pool (they are assigned to every namespace pool), like other
     * clouds' datastores. Clusters added with earlier versions keep the cluster pool.
     */
    CloudPool datastoreZonePool(CloudPool resourcePool) {
        return HpeHarvesterClusterProvider.isCloudManaged(cluster) ? null : resourcePool
    }

    private addStorageClasses(addList, CloudPool resourcePool) {
        log.info("Harvester: Adding storage class: ${addList?.size()}")
        def itemsToAdd = []
        List<CloudPool> namespacePools = DBUtils.getNamespacePools(morpheusContext, cluster)

        // Search for datastore type using the constant code
        DatastoreType datastoreType = morpheusContext.services.cloud.datastore.datastoreType.find(new DataQuery().withFilters(
                new DataFilter('code', HarvesterConstants.DATASTORE_PROVISION_PROVIDER_CODE),
        ))

        log.info "Datastore type found for code ${HarvesterConstants.DATASTORE_PROVISION_PROVIDER_CODE}: ${datastoreType}"

        addList?.each { Map cloudItem ->
            def storageClass = cloudItem.name ?: "Storage Class ${cloudItem.uid}"
            def storeConfig = [
                    // a Harvester cloud lists datastores that belong to it (refType ComputeZone)
                    refType:  DBUtils.datastoreRefType(cluster),
                    refId: DBUtils.datastoreRefId(cluster),
                    category: "harvester.storageClass.cluster.${cluster.id}",
                    type: cloudItem.provisioner,
                    owner: cluster.owner,
                    storageSize: cloudItem.capacity?.storageSize as Long, // null when the capacity isn't known
                    freeSpace: cloudItem.capacity?.freeSpace as Long,
                    externalId: cloudItem.uid,
                    uniqueId: cloudItem.uid,
                    name: storageClass,
                    code: "harvester.storageClass.cluster.${cluster.id}.${cloudItem.name}",
                    active: cluster.cloud.getConfigProperty('defaultDatastoreSyncActive') ?: true,
                    cloud: cluster.cloud,
                    // storage classes are cluster-wide: a cloud's datastores aren't tied to one pool
                    zonePool: datastoreZonePool(resourcePool),
                    // storage classes are cluster-wide, so every namespace pool can use them
                    assignedZonePools: ([resourcePool] + namespacePools).findAll { it }.unique { it.id },
                    volumeType: cloudItem.storageVolumeType ?: null,
                    config: JsonOutput.toJson([
                            provisioner: cloudItem.provisioner,
                            parameters: cloudItem.parameters ?: [:],
                            allowVolumeExpansion: cloudItem.allowVolumeExpansion ?: false,
                            reclaimPolicy: cloudItem.reclaimPolicy ?: 'Delete',
                            volumeBindingMode: cloudItem.volumeBindingMode ?: 'Immediate',
                            clusterId: cluster.id
                    ])
            ]

            // Add datastore type ID if found
            if (datastoreType) {
                storeConfig.datastoreType = datastoreType
                log.debug "Assigned datastore type ID: ${datastoreType.id} for code: ${HarvesterConstants.DATASTORE_PROVISION_PROVIDER_CODE}"
            } else {
                log.warn "Datastore type not found for code: ${HarvesterConstants.DATASTORE_PROVISION_PROVIDER_CODE}"
            }

            log.debug "storage config: ${storeConfig}"
            def store = new Datastore(storeConfig)
            itemsToAdd << store
        }

        if(itemsToAdd) {
            morpheusContext.services.cloud.datastore.bulkCreate(itemsToAdd)
            log.info("Harvester: Successfully added storage classes: ${itemsToAdd.size()}")
        }
    }

    private updateStorageClasses(updateList) {
        def itemsToUpdate = []
        CloudPool resourcePool = DBUtils.getClusterResourcePool(morpheusContext, cluster)
        List<CloudPool> namespacePools = DBUtils.getNamespacePools(morpheusContext, cluster)

        // Search for datastore type using the constant code
        DatastoreType datastoreType = morpheusContext.services.cloud.datastore.datastoreType.find(new DataQuery().withFilters(
            new DataFilter('code', HarvesterConstants.DATASTORE_PROVISION_PROVIDER_CODE)
        ))

        updateList?.each { updateMap ->
            Datastore datastore = updateMap.existingItem
            def storageClass = updateMap.masterItem
            def doSave = false

            if (datastore.owner != cluster.owner) {
                datastore.owner = cluster.owner
                doSave = true
            }
            if (datastore.type != storageClass.provisioner) {
                datastore.type = storageClass.provisioner
                doSave = true
            }
            if (datastore.name != storageClass.name) {
                datastore.name = storageClass.name
                doSave = true
            }
            if (datastore.code != "harvester.storageClass.cluster.${cluster.id}.${storageClass.name}") {
                datastore.code = "harvester.storageClass.cluster.${cluster.id}.${storageClass.name}"
                doSave = true
            }
            if (datastore.uniqueId != storageClass.uid) {
                datastore.uniqueId = storageClass.uid
                doSave = true
            }
            if (datastore.externalId != storageClass.uid) {
                datastore.externalId = storageClass.uid
                doSave = true
            }

            if (datastore.refType != DBUtils.datastoreRefType(cluster)) {
                datastore.refType = DBUtils.datastoreRefType(cluster)
                doSave = true
            }

            if (datastore.refId != DBUtils.datastoreRefId(cluster)) {
                datastore.refId = DBUtils.datastoreRefId(cluster)
                doSave = true
            }

            if (datastore.category != "harvester.storageClass.cluster.${cluster.id}") {
                datastore.category = "harvester.storageClass.cluster.${cluster.id}"
                doSave = true
            }
            if (datastore.cloud?.id != cluster.cloud.id) {
                datastore.cloud = cluster.cloud
                doSave = true
            }

            // Update volume type if it has changed
            if (datastore.volumeType?.id != storageClass.storageVolumeType?.id) {
                datastore.volumeType = storageClass.storageVolumeType
                doSave = true
            }

            // Update active status based on cloud configuration
            def expectedActive = cluster.cloud.getConfigProperty('defaultDatastoreSyncActive') ?: true
            if (datastore.active != expectedActive) {
                datastore.active = expectedActive
                doSave = true
            }

            // Update config
            def newConfig = [
                    provisioner: storageClass.provisioner,
                    parameters: storageClass.parameters ?: [:],
                    allowVolumeExpansion: storageClass.allowVolumeExpansion ?: false,
                    reclaimPolicy: storageClass.reclaimPolicy ?: 'Delete',
                    volumeBindingMode: storageClass.volumeBindingMode ?: 'Immediate',
                    clusterId: cluster.id
            ]
            if (datastore.getConfigMap() != newConfig) {
                datastore.setConfigMap(newConfig)
                doSave = true
            }

            // Size and free space (null when the capacity isn't known)
            Long newSize = storageClass.capacity?.storageSize as Long
            Long newFree = storageClass.capacity?.freeSpace as Long
            if (datastore.storageSize != newSize) {
                datastore.storageSize = newSize
                doSave = true
            }
            if (datastore.freeSpace != newFree) {
                datastore.freeSpace = newFree
                doSave = true
            }

            // Update datastore type if it has changed
            if (datastoreType && datastore.datastoreType?.id != datastoreType.id) {
                datastore.datastoreType = datastoreType
                doSave = true
                log.debug "Updated datastore type ID: ${datastoreType.id} for datastore: ${datastore.name}"
            } else if (!datastoreType && datastore.datastoreType) {
                log.warn "Datastore type not found for code: ${HarvesterConstants.DATASTORE_PROVISION_PROVIDER_CODE}, but datastore has one assigned"
            }

            def desiredZonePool = datastoreZonePool(resourcePool)
            if (datastore.zonePool?.id != desiredZonePool?.id) {
                datastore.zonePool = desiredZonePool
                doSave = true
            }
            if (datastore.assignedZonePools == null) {
                datastore.assignedZonePools = []
            }
            if (DBUtils.addMissingPools(datastore.assignedZonePools, [resourcePool] + namespacePools)) {
                doSave = true
            }

            if (doSave) {
                itemsToUpdate << datastore
            }
        }

        if(itemsToUpdate) {
            morpheusContext.services.cloud.datastore.bulkSave(itemsToUpdate)
            log.info("Harvester: Successfully updated storage classes: ${itemsToUpdate.size()}")
        }

    }

    /**
     * Removes datastores whose storage class is gone or can't take VMs. A datastore that still has volumes on it is
     * deactivated instead, so those volumes keep their datastore and it stops being offered for provisioning.
     */
    private removeStorageClasses(removeList) {
        log.info("Harvester: Removing storage classes: ${removeList?.size()}")
        if (!removeList) {
            return
        }
        def toRemove = []
        def toDeactivate = []
        removeList.each { datastore ->
            def inUse = morpheusContext.services.storage.volume.find(new DataQuery().withFilters(
                    new DataFilter('datastore.id', datastore.id)
            ))
            if (inUse) {
                toDeactivate << datastore.id
            } else {
                toRemove << datastore
            }
        }
        if (toRemove) {
            morpheusContext.services.cloud.datastore.bulkRemove(toRemove)
        }
        if (toDeactivate) {
            def stores = morpheusContext.services.cloud.datastore.list(new DataQuery().withFilters(
                    new DataFilter('id', 'in', toDeactivate)
            ))?.findAll { it.active != false }
            stores?.each { it.active = false }
            if (stores) {
                log.info("Harvester: deactivating datastores still holding volumes: ${stores*.name}")
                morpheusContext.services.cloud.datastore.bulkSave(stores)
            }
        }
    }
}
