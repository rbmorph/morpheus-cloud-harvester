// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.sync

import com.hpe.morpheus.harvester.plugin.HpeHarvesterPlugin
import com.hpe.morpheus.harvester.plugin.apihelper.PVCApiHelper
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.projection.StorageVolumeIdentityProjection
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable
import com.hpe.morpheus.harvester.plugin.utils.DBUtils
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants


@Slf4j
class PVCsSync {

    private ComputeServerGroup cluster
    private MorpheusContext morpheusContext
    private HpeHarvesterPlugin plugin

    public PVCsSync(HpeHarvesterPlugin plugin, ComputeServerGroup cluster) {
        this.plugin = plugin
        this.cluster = cluster
        this.morpheusContext = plugin.morpheusContext
    }

    def execute() {
        log.info "Harvester: execute PVCSync: ${cluster}"
        try {
            PVCApiHelper pvcApiHelper = new PVCApiHelper(cluster)
            def result = pvcApiHelper.getPVCs()
            log.info("Harvester: PVCs# ${result.pvcs.size()}")
            if(result.success == true) {

                StorageVolumeType volType = morpheusContext.services.storage.volume.storageVolumeType.find(
                        new DataQuery().withFilter('code', HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC))

                Observable<StorageVolume> domainRecords = morpheusContext.async.storage.volume.listIdentityProjections(
                        new DataQuery().withFilters(
                                new DataFilter('type', volType),
                                new DataFilter('volumeType', HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC),
                                new DataFilter('category', "${HarvesterConstants.CATEGORY_PVC_CLUSTER_PREFIX}${cluster.id}".toString()),
                        )
                )

                SyncTask<StorageVolumeIdentityProjection, Map, StorageVolume> syncTask = new SyncTask<>(domainRecords, result.pvcs ?: [])
                syncTask.addMatchFunction { StorageVolumeIdentityProjection domainObject, Map apiItem ->
                    domainObject.externalId == apiItem.uid
                }.onDelete { removeItems ->
                    removePVCs(removeItems)
                }.onUpdate { List<SyncTask.UpdateItem<StorageVolume, Map>> updateItems ->
                    updatePVCs(updateItems)
                }.onAdd { itemsToAdd ->
                    addPVCs(itemsToAdd, volType)
                }.withLoadObjectDetailsFromFinder { updateItems ->
                    return morpheusContext.async.storage.volume.list(
                            new DataQuery().withFilters(
                                    new DataOrFilter(updateItems.collect {new DataFilter('id', it.existingItem.id) }                                       ),
                            ))

                }.start()

            } else {
                log.error("Error retrieving PVCs ${result.error}")
                throw new Exception("Error retrieving PVCs for cluster ${cluster.name}")
            }

        } catch(e) {
            log.error("Harvester: cachePVCs error: ${e}", e)
        }
    }

    private addPVCs(addList, volType) {
        log.info("Harvester: PVCs addList# ${addList?.size()}")
        Cloud cloud = cluster.cloud
        def itemsToAdd = []
        addList?.each { Map cloudItem ->
            def namespace = cloudItem.namespace
            Datastore datastore = DBUtils.findDatastoreByStorageClass(morpheusContext, cluster,
                    cloudItem.storageClass as String, cloud)
            def pvcConfig =  JsonOutput.toJson([
                    ownerReferences: cloudItem.ownerReferences,
                    dataSource: cloudItem.dataSource
            ])

            def addConfig = [
                    externalId : cloudItem.uid,
                    uniqueId   : cloudItem.uid,
                    name       : cloudItem.name,
                    maxStorage : cloudItem.sizeBytes ?: 0L,
                    datastore  : datastore,
                    type       : volType,
                    volumeType : HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC,
                    config     : pvcConfig,
                    volumeType : HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC,
                    account    : cluster.owner,
                    poolName   : namespace,
                    refType    : HarvesterConstants.COMPUTE_SERVER_GROUP,
                    refId      : cluster.id,
                    cloudId    : cloud.id,
                    category   : "${HarvesterConstants.CATEGORY_PVC_CLUSTER_PREFIX}${cluster.id}".toString(),
                    internalId : "${cloudItem.storageClass}:${namespace}:${cloudItem.name}",
            ]

            log.debug "Harvester: adding PVC: ${addConfig}"

            def add = new StorageVolume(addConfig)
            add.datastore = datastore
            itemsToAdd << add
        }

        if(itemsToAdd) {
            morpheusContext.services.storage.volume.bulkCreate(itemsToAdd)
            log.info "Harvester: successfully added PVCs# ${itemsToAdd.size()}"
        }
    }

    private updatePVCs(updateList) {
        log.info("Harvester: PVCs matchList# ${updateList?.size()}")
        def itemsToUpdate = []
        updateList?.each { updateMap ->
            def localItem = updateMap.existingItem
            def cloudItem = updateMap.masterItem
            def doSave = false

            def pvcConfig =  JsonOutput.toJson([
                    ownerReferences: cloudItem.ownerReferences,
                    dataSource: cloudItem.dataSource
            ])

            if(localItem.config != pvcConfig) {
                localItem.config = pvcConfig
                doSave = true
            }

            if(localItem.name != cloudItem.name) {
                localItem.name = cloudItem.name
                doSave = true
            }

            if(localItem.maxStorage != cloudItem.sizeBytes) {
                localItem.maxStorage = cloudItem.sizeBytes
                doSave = true
            }

            if(localItem.poolName != cloudItem.namespace) {
                localItem.poolName = cloudItem.namespace
                localItem.internalId = "${cloudItem.storageClass}:${cloudItem.namespace}:${cloudItem.name}"
                doSave = true
            }

            if(doSave == true) {
                itemsToUpdate << localItem
            }
        }

        if(itemsToUpdate) {
            log.info("Harvester: updating PVCs # ${itemsToUpdate?.size()}")
            morpheusContext.services.storage.volume.bulkSave(itemsToUpdate)
        }

    }

    private removePVCs(removeList) {
        log.info("Harvester: PVCs removeList# ${removeList?.size()}")
        morpheusContext.services.storage.volume.bulkRemove(removeList)
    }
}
