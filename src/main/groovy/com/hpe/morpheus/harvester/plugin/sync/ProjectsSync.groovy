// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.sync

import com.hpe.morpheus.harvester.plugin.HpeHarvesterClusterProvider
import com.hpe.morpheus.harvester.plugin.HpeHarvesterPlugin
import com.hpe.morpheus.harvester.plugin.apihelper.ProjectApiHelper
import com.hpe.morpheus.harvester.plugin.utils.DBUtils
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.projection.CloudPoolIdentity
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class ProjectsSync {

    private ComputeServerGroup cluster
    private MorpheusContext morpheusContext
    private HpeHarvesterPlugin plugin

    public ProjectsSync(HpeHarvesterPlugin plugin, ComputeServerGroup cluster) {
        this.plugin = plugin
        this.cluster = cluster
        this.morpheusContext = plugin.morpheusContext
    }

    def execute() {
        log.debug "Harvester: execute projectsSync: ${cluster}"
        try {
            Cloud cloud = cluster.cloud
            ProjectApiHelper projectApiHelper = new ProjectApiHelper(cluster)
            def result =  projectApiHelper.getProjects()
            log.info("Harvester: projects# ${result.projects?.size()}")
            if(result.success == true) {
                Observable<CloudPool> domainRecords = morpheusContext.async.cloud.pool.listIdentityProjections(
                        new DataQuery().withFilters(
                                new DataFilter('cloud',  cloud),
                                new DataFilter('type', 'in', HarvesterConstants.NAMESPACE_TYPES),
                                new DataFilter('category', "${HarvesterConstants.CATEGORY_NAMESPACE_CLUSTER_PREFIX}${cluster.id}".toString()),
                        )
                )

                SyncTask<CloudPoolIdentity, Map, CloudPool> syncTask = new SyncTask<>(domainRecords, result.projects ?: [])
                syncTask.addMatchFunction { CloudPoolIdentity domainObject, Map apiItem ->
                    domainObject.externalId == apiItem.uid
                }.onDelete { removeItems ->
                    removeProjects(removeItems)
                }.onUpdate { List<SyncTask.UpdateItem<CloudPool, Map>> updateItems ->
                    updateProjects(updateItems)
                }.onAdd { itemsToAdd ->
                    addProjects(itemsToAdd)
                }.withLoadObjectDetailsFromFinder { updateItems ->
                    return morpheusContext.async.cloud.pool.list(
                            new DataQuery().withFilters(
                                    new DataOrFilter(updateItems.collect {new DataFilter('id', it.existingItem.id) }                                       ),
                            ))

                }.start()

            } else {
                log.error("Error retrieving projects ${result.error}")
                throw new Exception("Error retrieving projects for ${cloud.name}")
            }

        } catch(e) {
            log.error("Harvester: cacheProject error: ${e}", e)
        }
    }

    /**
     * Namespace pools of a Harvester cloud belong to the cloud (refType ComputeZone), which is what puts them on the
     * cloud's Resources tab. Clusters added with earlier versions keep them on the cluster.
     */
    String poolRefType() {
        return HpeHarvesterClusterProvider.isCloudManaged(cluster) ? HarvesterConstants.COMPUTE_ZONE : HarvesterConstants.COMPUTE_SERVER_GROUP
    }

    Long poolRefId() {
        return HpeHarvesterClusterProvider.isCloudManaged(cluster) ? cluster.cloud?.id : cluster.id
    }

    def addProjects(addList) {
        log.info("Harvester: projects addList# ${addList?.size()}")
        def itemsToAdd = []
        Cloud cloud = cluster.cloud
        addList = adoptUnlinkedPools(addList)
        addList?.each { Map cloudItem ->
            // Each Harvester namespace is a resource pool of the cloud: VMs provision into the selected namespace
            def addConfig = [
                    active     : true,
                    removable  : true,
                    editable   : false,
                    hidden     : false,
                    displayName: cloudItem.name,
                    externalId : cloudItem.uid,
                    uniqueId   : cloudItem.uid,
                    name       : cloudItem.name,
                    type       : HarvesterConstants.NAMESPACE_TYPE,
                    cloud      : cloud,
                    owner      : cluster.owner,
                    refType    : poolRefType(),
                    refId      : poolRefId(),
                    serverGroup: DBUtils.namespacePoolServerGroup(cluster),
                    code       : "${HarvesterConstants.CATEGORY_NAMESPACE_CLUSTER_PREFIX}${cluster.id}",
                    category   : "${HarvesterConstants.CATEGORY_NAMESPACE_CLUSTER_PREFIX}${cluster.id}",

            ]
            log.debug "Harvester: adding projects: ${addConfig}"

            def add = new CloudPool(addConfig)
            add.rawData = cloudItem
            itemsToAdd << add
        }

        if(itemsToAdd) {
            morpheusContext.services.cloud.pool.bulkCreate(itemsToAdd)
            log.info "Harvester: successfully added projects# ${itemsToAdd.size()}"
        }
    }

    /**
     * A pool added from Morpheus is saved by Morpheus before (or without) the plugin's namespace details. Links such
     * pools (same name, no externalId) to their namespace instead of adding a second pool.
     * @return the namespaces that still need a new pool
     */
    def adoptUnlinkedPools(addList) {
        if (!addList || !HpeHarvesterClusterProvider.isCloudManaged(cluster)) {
            return addList
        }
        def names = addList.collect { it.name }.findAll { it }
        List<CloudPool> unlinked = morpheusContext.services.cloud.pool.list(new DataQuery().withFilters(
                new DataFilter('cloud', cluster.cloud),
                new DataFilter('name', 'in', names)
        ))?.findAll { !it.externalId } ?: []
        if (!unlinked) {
            return addList
        }
        def adopted = []
        def remaining = []
        addList.each { Map cloudItem ->
            CloudPool pool = unlinked.find { it.name == cloudItem.name && !(it in adopted) }
            if (pool) {
                pool.externalId = cloudItem.uid
                pool.uniqueId = cloudItem.uid
                pool.displayName = cloudItem.name
                pool.type = HarvesterConstants.NAMESPACE_TYPE
                pool.cloud = cluster.cloud
                pool.owner = pool.owner ?: cluster.owner
                pool.refType = poolRefType()
                pool.refId = poolRefId()
                pool.serverGroup = DBUtils.namespacePoolServerGroup(cluster)
                pool.code = "${HarvesterConstants.CATEGORY_NAMESPACE_CLUSTER_PREFIX}${cluster.id}".toString()
                pool.category = "${HarvesterConstants.CATEGORY_NAMESPACE_CLUSTER_PREFIX}${cluster.id}".toString()
                pool.active = true
                pool.removable = true
                pool.hidden = false
                pool.rawData = groovy.json.JsonOutput.toJson(cloudItem)
                adopted << pool
            } else {
                remaining << cloudItem
            }
        }
        if (adopted) {
            log.info("Harvester: linking pools added from Morpheus to their namespaces: ${adopted*.name}")
            morpheusContext.services.cloud.pool.bulkSave(adopted)
        }
        return remaining
    }

    def updateProjects(updateList) {
        log.info("Harvester: projects matchList# ${updateList?.size()}")
        def itemsToUpdate = []
        updateList?.each { updateMap ->
            def localItem = updateMap.existingItem
            def cloudItem = updateMap.masterItem
            def doSave = false

            if(localItem.name != cloudItem.name) {
                localItem.name = cloudItem.name
                doSave = true
            }
            // earlier versions stored type 'namespace', which Morpheus keeps out of VM provisioning
            if (localItem.type != HarvesterConstants.NAMESPACE_TYPE) {
                localItem.type = HarvesterConstants.NAMESPACE_TYPE
                doSave = true
            }
            // namespace pools of a Harvester cloud are plain cloud pools (no link to the connection record)
            if (DBUtils.namespacePoolServerGroup(cluster) == null && localItem.serverGroup != null) {
                localItem.serverGroup = null
                doSave = true
            }
            // a Harvester cloud lists its own pools (refType ComputeZone) under Resources; pools synced by
            // earlier versions pointed at the connection record instead and did not show there
            if (localItem.refType != poolRefType() || localItem.refId != poolRefId()) {
                localItem.refType = poolRefType()
                localItem.refId = poolRefId()
                doSave = true
            }
            // pools synced by earlier versions were inactive; namespaces are now provisioning targets
            if (localItem.active != true) {
                localItem.active = true
                doSave = true
            }
            if (localItem.removable != true) {
                localItem.removable = true
                doSave = true
            }
            if (localItem.hidden == true) {
                localItem.hidden = false
                doSave = true
            }

            if(doSave == true) {
                itemsToUpdate << localItem
            }
        }
        if(itemsToUpdate) {
            morpheusContext.services.cloud.pool.bulkSave(itemsToUpdate)
        }

    }

    def removeProjects(removeList) {
        log.info("Harvester: projects removeList# ${removeList?.size()}")
        if (removeList) {
            morpheusContext.services.cloud.pool.bulkRemove(removeList)
        }
    }
}
