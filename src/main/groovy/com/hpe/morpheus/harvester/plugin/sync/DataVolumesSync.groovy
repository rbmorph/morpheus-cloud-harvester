// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.sync

import com.hpe.morpheus.harvester.plugin.HpeHarvesterPlugin
import com.hpe.morpheus.harvester.plugin.apihelper.DataVolumeApiHelper
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
class DataVolumesSync {

    private ComputeServerGroup cluster
    private MorpheusContext morpheusContext
    private HpeHarvesterPlugin plugin

    DataVolumesSync(HpeHarvesterPlugin plugin, ComputeServerGroup cluster) {
        this.plugin = plugin
        this.cluster = cluster
        this.morpheusContext = plugin.morpheusContext
    }

    def execute() {
        log.info "Harvester: execute DataVolumeSync: ${cluster}"
        try {
            DataVolumeApiHelper dataVolumeApiHelper = new DataVolumeApiHelper(cluster)
            def result = dataVolumeApiHelper.getDataVolumes()
            log.info("Harvester: DataVolumes# ${result.datavolumes.size()}")
            if(result.success == true) {

                StorageVolumeType volType = morpheusContext.services.storage.volume.storageVolumeType.find(
                        new DataQuery().withFilter('code', HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME))

                Observable<StorageVolumeIdentityProjection> domainRecords = morpheusContext.async.storage.volume.listIdentityProjections(
                        new DataQuery().withFilters(
                                new DataFilter('type', volType),
                                new DataFilter('volumeType', HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME),
                                new DataFilter('category', "${HarvesterConstants.CATEGORY_DATAVOLUME_CLUSTER_PREFIX}${cluster.id}".toString()),
                        )
                )

                SyncTask syncTask = new SyncTask<StorageVolumeIdentityProjection, Map, StorageVolume>(domainRecords, result.datavolumes ?: [])
                syncTask.addMatchFunction { StorageVolumeIdentityProjection domainObject, Map apiItem ->
                    domainObject.externalId == apiItem.uid
                }.onDelete { removeItems ->
                    removeDataVolumes(removeItems)
                }.onUpdate { List<SyncTask.UpdateItem<StorageVolume, Map>> updateItems ->
                    updateDataVolumes(updateItems)
                }.onAdd { itemsToAdd ->
                    addDataVolumes(itemsToAdd, volType)
                }.withLoadObjectDetailsFromFinder { updateItems ->
                    return morpheusContext.async.storage.volume.list(
                            new DataQuery().withFilters(
                                    new DataOrFilter(updateItems.collect {new DataFilter('id', it.existingItem.id) }                                       ),
                            ))

                }.start()

            } else {
                log.error("Error retrieving DataVolumes ${result.error}")
                throw new Exception("Error retrieving DataVolumes for cluster ${cluster.name}")
            }

        } catch(e) {
            log.error("Harvester: cacheDataVolumes error: ${e}", e)
        }
    }

    private addDataVolumes(addList, volType) {
        log.info("Harvester: DataVolumes addList# ${addList?.size()}")
        Cloud cloud = cluster.cloud
        def itemsToAdd = []
        addList?.each { Map cloudItem ->
            def namespace = cloudItem.namespace
            if (!cloudItem.storageClass){
                log.warn("Harvester: skipping DataVolume:${cloudItem.name} with no storageClass - ${cloudItem.storageClass}")
                return
            }
            Datastore datastore = morpheusContext.services.cloud.datastore.find(new DataQuery().withFilters(
                    new DataFilter('name',  cloudItem.storageClass),
                    DBUtils.datastoreFilter(cluster),
                    new DataFilter('cloud',  cloud),
            ))
            def dataVolumeConfig =  JsonOutput.toJson([
                    ownerReferences: cloudItem.ownerReferences,
                    dataSource: cloudItem.dataSource,
            ])

            def addConfig = [
                    externalId : cloudItem.uid,
                    uniqueId   : cloudItem.uid,
                    name       : cloudItem.name,
                    maxStorage : cloudItem.sizeBytes ?: 0L,
                    datastore  : datastore,
                    type       : volType,
                    volumeType : HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME,
                    config     : dataVolumeConfig,
                    claimName  : cloudItem.claimName,
                    account    : cluster.owner,
                    poolName   : namespace,
                    refType    : HarvesterConstants.COMPUTE_SERVER_GROUP,
                    refId      : cluster.id,
                    cloudId    : cloud.id,
                    category   : "${HarvesterConstants.CATEGORY_DATAVOLUME_CLUSTER_PREFIX}${cluster.id}".toString(),
                    internalId : "${cloudItem.storageClass}:${namespace}:${cloudItem.name}",
            ]

            log.debug "Harvester: adding DataVolume: ${addConfig}"

            def add = new StorageVolume(addConfig)
            add.datastore = datastore
            itemsToAdd << add
        }

        if(itemsToAdd) {
            morpheusContext.services.storage.volume.bulkCreate(itemsToAdd)
            log.info "Harvester: successfully added DataVolumes# ${itemsToAdd.size()}"
        }
    }

    private updateDataVolumes(updateList) {
        log.info("Harvester: DataVolumes matchList# ${updateList?.size()}")
        def itemsToUpdate = []
        updateList?.each { updateMap ->
            def localItem = updateMap.existingItem
            def cloudItem = updateMap.masterItem
            def doSave = false

            def dataVolumeConfig =  JsonOutput.toJson([
                    ownerReferences: cloudItem.ownerReferences,
                    dataSource: cloudItem.dataSource
            ])

            if(localItem.config != dataVolumeConfig) {
                localItem.config = dataVolumeConfig
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
            log.info("Harvester: updating DataVolumes # ${itemsToUpdate?.size()}")
            morpheusContext.services.storage.volume.bulkSave(itemsToUpdate)
        }

    }

    private removeDataVolumes(removeList) {
        log.info("Harvester: DataVolumes removeList# ${removeList?.size()}")
        morpheusContext.services.storage.volume.bulkRemove(removeList)
    }
}
