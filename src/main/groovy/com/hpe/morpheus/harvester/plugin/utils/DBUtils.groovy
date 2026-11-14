// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.utils
import com.hpe.morpheus.harvester.plugin.HpeHarvesterClusterProvider
import com.hpe.morpheus.harvester.plugin.utils.MorpheusException
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.*
import groovy.util.logging.Slf4j

@Slf4j
class DBUtils {

  static CloudPool getDefaultNamespace(MorpheusContext morpheusContext, ComputeServerGroup serverGroup) {
        try {
            CloudPool namespace = morpheusContext.services.cloud.pool.find(new DataQuery().withFilters(
                    new DataFilter('serverGroup',  serverGroup),
                    new DataFilter('name',  'default')
            ))
            log.info("Found default namespace:${namespace}")
            return namespace
        } catch(e) {
            log.error("Harvester: get default namespace error: ${e}", e)
        }
  }

  static CloudPool getClusterResourcePool(MorpheusContext morpheusContext, ComputeServerGroup serverGroup) {
        try {
            CloudPool resourcePool = morpheusContext.services.cloud.pool.find(new DataQuery().withFilters(
                    new DataFilter('serverGroup',  serverGroup),
                    new DataFilter('name',  serverGroup.name),
                    new DataFilter('type',  HarvesterConstants.RESOURCE_POOL_TYPE_DEFAULT),
                    new DataFilter('category',  "${HarvesterConstants.CATEGORY_DEFAULT_RESOURCE_POOL_PREFIX}${serverGroup.id}".toString())
            ))
            log.info("Found harvester resource pool:${resourcePool} for cluster:${serverGroup.name}")
            return resourcePool
        } catch(e) {
            log.error("Harvester: get harvester resource pool error: ${e}", e)
        }
    }

    static Map<String, StorageVolumeType> getStorageVolumeTypeMap(MorpheusContext morpheusContext) {
        Map<String, StorageVolumeType> storageVolumeTypeMap = [:]
        try {
            HarvesterConstants.STORAGE_VOLUME_TYPES?.each { svt ->
                storageVolumeTypeMap[svt] = morpheusContext.services.storage.volume.storageVolumeType.find(
                        new DataQuery().withFilter('code', svt)
                )
            }
        } catch(e) {
            log.error("Harvester: get storage volume type map error: ${e}", e)
        }
        return storageVolumeTypeMap
    }

    static Map<String, StorageControllerType> getStorageControllerTypeMap(MorpheusContext morpheusContext) {
        Map<String, StorageControllerType> storageControllerTypeMap = [:]
        try {
            HarvesterConstants.STORAGE_CONTROLLERS_TYPES?.each { sct ->
                storageControllerTypeMap[sct] = morpheusContext.async.storage.controller.storageControllerType.find(
                        new DataQuery().withFilter('code', sct)
                ).blockingGet()
            }
        } catch(e) {
            log.error("Harvester: get storage controller type map error: ${e}", e)
        }
        return storageControllerTypeMap
    }

    static findStorageControllerByType(ComputeServer computeServer, StorageControllerType storageControllerType) {
        try {
            StorageController storageController = null
            storageController = computeServer.controllers?.find { sc ->
                sc.type?.code == storageControllerType.code
            }
            if (!storageController) {
                log.warn("Harvester: No storage controller found for type: ${storageControllerType.code} on computeServer: ${computeServer.name}")
            }
            return storageController
        } catch(e) {
            log.error("Harvester: get storage controller error: ${e}", e)
        }
    }

    static String buildVMStorageVolumeCategory(ComputeServer server, StorageVolumeType volType, clusterId) {
        if (volType.code == HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)
            return "${HarvesterConstants.CATEGORY_PVC_CLUSTER_PREFIX}${clusterId}.vm.${server.id}".toString()
        else if (volType.code == HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME)
            return "${HarvesterConstants.CATEGORY_DATAVOLUME_CLUSTER_PREFIX}${clusterId}.vm.${server.id}".toString()
        else if (volType.code == HarvesterConstants.VOLUME_TYPE_HARVESTER_DATASOURCE)
            return "${HarvesterConstants.CATEGORY_DATAVOLUME_CLUSTER_PREFIX}${clusterId}.vm.${server.id}".toString()
        else
            log.error("Harvester: unsupported volume type: ${volType.code}, VM: ${server?.name}(${server?.id}), clusterId: ${clusterId}")
     }

    static String buildStorageVolumeCategory(StorageVolumeType volType, clusterId) {
        if (volType.code == HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)
            return "${HarvesterConstants.CATEGORY_PVC_CLUSTER_PREFIX}${clusterId}".toString()
        else if (volType.code == HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME)
            return "${HarvesterConstants.CATEGORY_DATAVOLUME_CLUSTER_PREFIX}${clusterId}".toString()
        else if (volType.code == HarvesterConstants.VOLUME_TYPE_HARVESTER_DATASOURCE)
            return "${HarvesterConstants.CATEGORY_DATAVOLUME_CLUSTER_PREFIX}${clusterId}".toString()
        else
            log.error("Harvester: unsupported volume type: ${volType.code}, clusterId: ${clusterId}")
    }

    static StorageVolume getSourceStorageVolume(MorpheusContext context, ComputeServerGroup cluster, volumeData) {
        try {
            def sourceVolumeType = volumeData.sourceInfo.type
            def sourceVolumeName = volumeData.sourceInfo.name
            def poolName = volumeData.namespace
            // find a volume with above fields
            StorageVolume sourceVolume = context.services.storage.volume.find(new DataQuery().withFilters(
                    new DataFilter('name', sourceVolumeName),
                    new DataFilter('volumeType', sourceVolumeType),
                    new DataFilter('poolName', poolName),
                    new DataFilter('cloud', cluster.cloud),
                    new DataFilter('refType', HarvesterConstants.COMPUTE_SERVER_GROUP),
                    new DataFilter('refId', cluster.id),
            ))
            if (!sourceVolume) {
                log.warn("Harvester: No source storage volume found for volume: ${volumeData}, cluster: ${cluster?.name}(${cluster?.id})")
            }
            return sourceVolume
        } catch(e) {
            log.error("Harvester: get source storage volume error: ${e}", e)
        }
    }

    static CloudPool createDefaultResourcePool(MorpheusContext morpheusContext, ComputeServerGroup serverGroup) {
        try {
            CloudPool resourcePool = getClusterResourcePool(morpheusContext, serverGroup)
            if(!resourcePool) {
                def addConfig = [
                        active     : true,
                        name       : serverGroup.name,
                        type       : HarvesterConstants.RESOURCE_POOL_TYPE_DEFAULT,
                        cloud      : serverGroup.cloud,
                        owner      : serverGroup.owner,
                        refType    : HarvesterConstants.COMPUTE_SERVER_GROUP,
                        refId     :  serverGroup.id,
                        serverGroup: serverGroup,
                        code       : "${HarvesterConstants.CATEGORY_DEFAULT_RESOURCE_POOL_PREFIX}${serverGroup.id}",
                        category   : "${HarvesterConstants.CATEGORY_DEFAULT_RESOURCE_POOL_PREFIX}${serverGroup.id}",
                ]
                resourcePool = new CloudPool(addConfig)
                morpheusContext.services.cloud.pool.create(resourcePool)
                log.info("Harvester: Created resource pool for cluster:${serverGroup.name}")
            }
            return resourcePool
        } catch(e) {
            log.error("Harvester: create harvester resource pool error: ${e}", e)
            throw new MorpheusException("Error creating resource pool for cluster:${serverGroup.name} - ${e.message}" )
        }
    }

  def static updateZoneStatus(MorpheusContext morpheusContext, Cloud zone, Cloud.Status status, String message, Date syncDate = null) {
        log.debug("Harvester: update cloud status: ${zone.status} new status: ${status}")
        try {
            morpheusContext.async.cloud.updateCloudStatus(zone, status, message, syncDate)
        } catch(e) {
            log.error("Harvester: update cloud status error: ${e}", e)
        }
    }

    def static getAuthConfig(MorpheusContext morpheusContext, Cloud cloud) {
        def rtn = [:]
        if(cloud.id && !cloud.accountCredentialLoaded) {
            AccountCredential accountCredential
            try {
                accountCredential = morpheusContext.async.cloud.loadCredentials(cloud.id).blockingGet()
            } catch(e) {
                // If there is no credential on the cloud, then this will error
                // TODO: Change to using 'maybe' rather than 'blockingGet'?
            }
            cloud.accountCredentialLoaded = true
            cloud.accountCredentialData = accountCredential?.data
        }

        def username
        def password
        if(cloud.accountCredentialData && cloud.accountCredentialData.containsKey('username')) {
            username = cloud.accountCredentialData['username']
        } else {
            username = cloud.serviceUsername
        }
        if(cloud.accountCredentialData && cloud.accountCredentialData.containsKey('password')) {
            password = cloud.accountCredentialData['password']
        } else {
            password = cloud.servicePassword
        }

        def apiUrl = cloud.serviceUrl
        // HACK: Fixing the api url if its stored incorrectly
        if (apiUrl != null) {
            if (apiUrl.trim().endsWith('/'))
                apiUrl = apiUrl[0..-2]
            if (apiUrl.trim().endsWith('/api')) {
                apiUrl = apiUrl - '/api'
            }
        }

        rtn.apiUrl = apiUrl
        rtn.apiUsername = username
        rtn.apiPassword = password
        rtn.apiVersion = cloud.getConfigProperty('serviceVersion')
        return rtn
    }

    def static updateTenantPermissions(MorpheusContext morpheusContext, String resourceType, Long resourceId, accountIds, defaultStoreAccounts = [], defaultTargetAccounts = [], deleteExisting = true, canManageAccountIds = []) {
        List<ResourcePermission> resourcePermissions =  morpheusContext.services.resourcePermission.list(new DataQuery().withFilters(
                new DataFilter('morpheusResourceId',  resourceId),
                new DataFilter('morpheusResourceType',  resourceType)
        ))
        log.debug("Harvester: updateTenantPermissions for resourceType: ${resourceType}" +
                " resourceId: ${resourceId} accountIds: ${accountIds}" +
                " resourcePermissions: ${resourcePermissions}")

        accountIds = accountIds ?: []
        resourcePermissions?.each { resourcePermission ->
            def match = accountIds?.find{accountId -> accountId.toLong() == resourcePermission.account.id}
            if(!match && deleteExisting) {
                morpheusContext.async.resourcePermission.remove(resourcePermission).blockingGet()
            }
        }
        accountIds?.each { accountId ->
            def canManage = canManageAccountIds?.find { it.toLong() == accountId.toLong()} != null ? true : false
            def match = resourcePermissions?.find{resourcePermission -> resourcePermission.account.id == accountId.toLong()}
            if(!match) {
                log.debug('adding new tenant permission')
                def resourcePermission = new ResourcePermission(
                        account:[id: accountId.toLong()],
                        morpheusResourceType: resourceType,
                        morpheusResourceId: resourceId,
                        defaultStore: defaultStoreAccounts?.any { it == accountId.toLong() }, defaultTarget: defaultTargetAccounts?.any { it == accountId.toLong() },
                        canManage: canManage,
                        uuid: UUID.randomUUID().toString()
                )
                morpheusContext.async.resourcePermission.create(resourcePermission).blockingGet()
            } else {
                match.defaultStore = defaultStoreAccounts?.any { it == accountId.toLong() }
                match.defaultTarget = defaultTargetAccounts?.any { it == accountId.toLong() }
                match.canManage = canManage
                log.debug('updating existing permission')
                morpheusContext.async.resourcePermission.save(match).blockingGet()
            }
        }
    }

    // get resourcePool database object from the resourcePoolId string
    /**
     * Finds the datastore (storage class) of a cluster by name. Volumes created from a Harvester image
     * live on a per-image storage class (longhorn-image-*) that is not synced as a datastore; those
     * are attributed to the cluster's default Harvester storage class instead.
     */
    static Datastore findDatastoreByStorageClass(MorpheusContext morpheusContext, ComputeServerGroup cluster,
                                                 String storageClassName, Cloud cloud = null) {
        if (!storageClassName || !cluster) {
            return null
        }
        Datastore datastore = morpheusContext.services.cloud.datastore.find(datastoreQuery(cluster, storageClassName, cloud))
        if (!datastore && storageClassName.startsWith(HarvesterConstants.IMAGE_STORAGE_CLASS_PREFIX)) {
            datastore = morpheusContext.services.cloud.datastore.find(
                    datastoreQuery(cluster, HarvesterConstants.DEFAULT_STORAGE_CLASS, cloud))
        }
        return datastore
    }

    private static DataQuery datastoreQuery(ComputeServerGroup cluster, String name, Cloud cloud) {
        DataQuery query = new DataQuery().withFilters(
                new DataFilter('name', name),
                datastoreFilter(cluster),
        )
        if (cloud) {
            query.withFilter(new DataFilter('cloud', cloud))
        }
        return query
    }

    /**
     * The Harvester connection record a pool belongs to. Namespace pools of a Harvester cloud are plain cloud pools
     * (no serverGroup, like other clouds' pools, so the provisioning wizard offers them); their category carries the
     * connection id. Falls back to the cloud's connection, then to pool.serverGroup (clusters from earlier versions).
     */
    static ComputeServerGroup resolveConnection(MorpheusContext morpheusContext, CloudPool pool) {
        if (!pool) {
            return null
        }
        if (pool.serverGroup?.id) {
            return pool.serverGroup
        }
        String category = pool.category
        if (category?.startsWith(HarvesterConstants.CATEGORY_NAMESPACE_CLUSTER_PREFIX)) {
            String id = category.substring(HarvesterConstants.CATEGORY_NAMESPACE_CLUSTER_PREFIX.length())
            if (id.isLong()) {
                ComputeServerGroup connection = morpheusContext.services.cluster.get(id.toLong())
                if (connection) {
                    return connection
                }
            }
        }
        if (pool.cloud?.id) {
            return morpheusContext.services.cluster.find(new DataQuery().withFilters(
                    new DataFilter('category', "${HarvesterConstants.CATEGORY_CLOUD_CLUSTER_PREFIX}${pool.cloud.id}".toString())
            ))
        }
        return null
    }

    /**
     * Namespace pools of a Harvester cloud are not linked to the connection record; clusters from earlier versions keep the link.
     */
    static ComputeServerGroup namespacePoolServerGroup(ComputeServerGroup connection) {
        return HpeHarvesterClusterProvider.isCloudManaged(connection) ? null : connection
    }

    /**
     * Datastores (storage classes) of a Harvester connection are identified by their category, which stays the same
     * whether they belong to a Harvester cloud (refType ComputeZone) or to a cluster added with earlier versions.
     */
    static String storageClassCategory(ComputeServerGroup cluster) {
        return "harvester.storageClass.cluster.${cluster?.id}".toString()
    }

    static DataFilter datastoreFilter(ComputeServerGroup cluster) {
        return new DataFilter('category', storageClassCategory(cluster))
    }

    /**
     * A Harvester cloud lists datastores that belong to it (refType ComputeZone, refId cloud id), like other clouds.
     */
    static String datastoreRefType(ComputeServerGroup cluster) {
        return HpeHarvesterClusterProvider.isCloudManaged(cluster) ? HarvesterConstants.COMPUTE_ZONE : HarvesterConstants.COMPUTE_SERVER_GROUP
    }

    static Long datastoreRefId(ComputeServerGroup cluster) {
        return HpeHarvesterClusterProvider.isCloudManaged(cluster) ? cluster?.cloud?.id : cluster?.id
    }

    /**
     * @return the namespace resource pools of a Harvester connection (one per synced namespace)
     */
    static List<CloudPool> getNamespacePools(MorpheusContext morpheusContext, ComputeServerGroup serverGroup) {
        try {
            return morpheusContext.services.cloud.pool.list(new DataQuery().withFilters(
                    new DataFilter('type', 'in', HarvesterConstants.NAMESPACE_TYPES),
                    new DataFilter('category', "${HarvesterConstants.CATEGORY_NAMESPACE_CLUSTER_PREFIX}${serverGroup.id}".toString())
            )) ?: []
        } catch (e) {
            log.error("Harvester: get namespace pools error: ${e}", e)
            return []
        }
    }

    /**
     * Namespace pools are identified by their category: their type is Morpheus's generic 'default'.
     */
    static boolean isNamespacePool(CloudPool pool) {
        return pool?.category?.startsWith(HarvesterConstants.CATEGORY_NAMESPACE_CLUSTER_PREFIX) ?: false
    }

    /**
     * Adds every pool in {@code pools} that {@code assigned} does not hold yet.
     * @return true when something was added
     */
    static boolean addMissingPools(List<CloudPool> assigned, Collection<CloudPool> pools) {
        boolean changed = false
        pools?.findAll { it }?.each { CloudPool pool ->
            if (!assigned.find { it?.id == pool.id }) {
                assigned << pool
                changed = true
            }
        }
        return changed
    }

    /**
     * For VMs: the namespace from the selected resource pool. A namespace pool provisions into that namespace;
     * the cluster-wide pool keeps the namespace chosen in the form, or 'default'. Writes it to config.project.
     * @return the namespace, or null when no pool was selected
     */
    static String applyNamespaceFromPool(MorpheusContext morpheusContext, Map opts) {
        if (opts == null) {
            return null
        }
        if (!(opts.config instanceof Map)) {
            opts.config = [:]
        }
        Map config = (Map) opts.config
        def poolId = config.resourcePoolId ?: config.harvesterResourcePoolId ?: opts.resourcePoolId ?: opts.zonePoolId
        if (!poolId) {
            return null
        }
        String poolIdString = poolId.toString()
        if (!poolIdString.startsWith('pool-') && poolIdString.isLong()) {
            poolIdString = "pool-${poolIdString}".toString()
        }
        CloudPool pool = getZonePoolFromIdString(morpheusContext, poolIdString)
        if (!pool) {
            return null
        }
        String namespace
        if (isNamespacePool(pool)) {
            namespace = pool.name
        } else {
            def current = config.project
            namespace = (current instanceof Map ? current.name : current)?.toString() ?: HarvesterConstants.DEFAULT_NAMESPACE_NAME
        }
        config.project = [name: namespace]
        return namespace
    }

    /**
     * The Harvester namespace of a server: the namespace recorded on it (config.project), otherwise the namespace pool
     * it was provisioned into. Null when neither is known.
     */
    static String namespaceForServer(MorpheusContext morpheusContext, ComputeServer server) {
        if (!server) {
            return null
        }
        def project = server.getConfigProperty('project')
        String name = project instanceof Map ? project.name : project?.toString()
        if (name) {
            return name
        }
        try {
            CloudPool pool = server.resourcePool
            if (pool?.id && !pool.category) {
                pool = morpheusContext.services.cloud.pool.get(pool.id)
            }
            if (!pool && server.id) {
                pool = morpheusContext.services.computeServer.get(server.id)?.resourcePool
                if (pool?.id && !pool.category) {
                    pool = morpheusContext.services.cloud.pool.get(pool.id)
                }
            }
            return isNamespacePool(pool) ? pool.name : null
        } catch (Exception e) {
            log.warn("Harvester: unable to read the resource pool of server ${server.name}: ${e.message}")
            return null
        }
    }

    static CloudPool getZonePoolFromIdString(MorpheusContext morpheusContext, String resourcePoolId) {

        resourcePoolId = "" + resourcePoolId
        if(resourcePoolId instanceof String && resourcePoolId.startsWith('pool-')) {
            def pool = morpheusContext.async.cloud.pool.get(
                    resourcePoolId.substring(5) as Long).blockingGet()
            if(pool) {
                return pool
            }
        }
        return null
    }

    // get interfaceType database object from the interface model string in Harvester
    static List<ComputeServerInterfaceType> getInterfaceTypeFromInterfaceModel(MorpheusContext morpheusContext, List<String> interfaceTypes = null) {
        Set<String> codes = [] as Set
        if (interfaceTypes){
            interfaceTypes.findAll { it }?.each { model ->
                codes << CommonUtils.getInterfaceTypeCode(model)
            }
        }
        if (codes.isEmpty()){
            codes.addAll(HarvesterConstants.INTERFACE_TYPES)
        }
        def interfaceTypeList = morpheusContext.services.computeServer.computeServerInterface.type.list(
                new DataQuery().withFilters(
                        new DataFilter('code','in', codes)
                )
        )
        if (!interfaceTypeList) {
            log.error "Harvester: No interface type found with code ${codes}."
            return []
        }
        return interfaceTypeList
    }

    // get computeZonePool by filter map
    static Collection<CloudPool> getZonePoolByFilterMap(MorpheusContext morpheusContext,List<Object> filterParams) {
        Collection<DataFilter> filters = []
        filterParams.each { param ->
            filters << new DataFilter(param.key, param.operator, param.value)
        }
        List<CloudPool> poolList = morpheusContext.services.cloud.pool.list(
                new DataQuery().withFilters(filters)
        )
        if (!poolList || poolList.isEmpty()) {
            log.error "Harvester: No cloud pool found with filter ${filterParams}."
            return null
        }
        return poolList
    }

    // get networkType database objects from a list of networkType codes in Harvester model
    static List<NetworkType> getNetworkTypesByCodes(MorpheusContext morpheusContext, List<String> codes) {
        if (!codes || codes.isEmpty()) return []
        def networkTypeList = morpheusContext.services.network.type.list(
                new DataQuery().withFilters(
                        new DataFilter('code', 'in', codes)
                )
        )
        return networkTypeList ?: []
    }

    static def getServerIdByNodeName(MorpheusContext morpheusContext, String nodeName, ComputeServerGroup serverGroup) {
        try {
            log.info ("Searching for server with identifier: ${nodeName} in serverGroup: ${serverGroup.name} with id ${serverGroup.id}")
            def server = morpheusContext.services.computeServer.find(
                    new DataQuery().withFilters(
                            new DataFilter('hostname', nodeName),
                            // hosts are identified by category (hosts of a Harvester cloud aren't linked to the cluster record)
                            new DataFilter('category', "${HarvesterConstants.CATEGORY_HARVESTER_NODE_PREFIX}${serverGroup.id}".toString())
                    )
            )
            if (!server) {
                log.warn("Harvester: No server found with hostname: ${nodeName}")
                return null
            }
            log.info("Harvester: Found server with identifier: ${nodeName}, id: ${server.id}, name: ${server.name}, hostname: ${server.hostname}")
            return [id: server.id, name: server.name]
        } catch(e) {
            log.error("Harvester: get server by node name error: ${e}", e)
            return null
        }
    }
}
