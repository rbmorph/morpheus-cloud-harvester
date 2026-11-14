// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.sync

import com.hpe.morpheus.harvester.plugin.HpeHarvesterPlugin
import com.hpe.morpheus.harvester.plugin.apihelper.VirtualMachineApiHelper
import com.hpe.morpheus.harvester.plugin.utils.CommonUtils
import com.hpe.morpheus.harvester.plugin.utils.DBUtils
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.*
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class VMsSync {

    private ComputeServerGroup cluster
    private MorpheusContext morpheusContext
    private HpeHarvesterPlugin plugin
    private Boolean importExisting

    public VMsSync(HpeHarvesterPlugin plugin, ComputeServerGroup cluster, Boolean importExisting) {
        this.plugin = plugin
        this.cluster = cluster
        this.morpheusContext = plugin.morpheusContext
        this.importExisting = importExisting
    }

    def execute() {
        log.debug "Harvester: execute VMSync: ${cluster}"
        try {
            VirtualMachineApiHelper vmApiHelper = new VirtualMachineApiHelper(cluster)

            Map<String, StorageVolumeType> storageVolumeTypeMap = DBUtils.getStorageVolumeTypeMap(morpheusContext)
            Map<String, StorageControllerType> storageControllerTypeMap = DBUtils.getStorageControllerTypeMap(morpheusContext)

            def cloudVms = vmApiHelper.getVMs(storageVolumeTypeMap, storageControllerTypeMap)
            Cloud cloud = cluster.cloud
            log.info("Harvester: VMSync cloudVms# ${cloudVms.vms.size()}")
            if(cloudVms.success == true) {
                Observable domainRecords = morpheusContext.async.computeServer.listIdentityProjections(
                        new DataQuery().withFilters(
                                        new DataFilter('cloud', cloud),
                                        new DataFilter('category', "${HarvesterConstants.CATEGORY_HARVESTER_VM_PREFIX}${cluster.id}"),
                                        new DataFilter('serverType', HarvesterConstants.SERVER_TYPE_VM)
                        )
                )

                ComputeServerType serverType = morpheusContext.async.cloud.
                        findComputeServerTypeByCode(HarvesterConstants.SERVER_TYPE_HARVESTER_UNMANAGED).blockingGet()

                ServicePlan fallbackServicePlan = morpheusContext.services.servicePlan.find(new DataQuery().withFilters(
                        new DataFilter('code',  HarvesterConstants.SERVICE_PLAN_CUSTOM_HARVESTER),
                ))

                List<ServicePlan> availablePlans = morpheusContext.services.servicePlan.list(new DataQuery().withFilters(
                        new DataFilter('active',  true),
                        new DataFilter('deleted',  false),
                        new DataFilter('provisionTypeCode', 'in', [HarvesterConstants.PROVISION_TYPE_CODE, HarvesterConstants.LEGACY_PROVISION_TYPE_CODE]),
                ))

                Collection<ResourcePermission> availablePlanPermissions = []
                if(availablePlans) {
                    availablePlanPermissions = morpheusContext.services.resourcePermission.list(new DataQuery().withFilters(
                            new DataFilter('morpheusResourceType',  HarvesterConstants.RESOURCE_TYPE_SERVICE_PLAN),
                            new DataFilter('morpheusResourceId',  'in' , availablePlans.collect{pl -> pl.id}),
                    ))
                }
                log.debug("Harvester: availablePlanPermissions: ${availablePlanPermissions?.size()} - ${availablePlanPermissions?.collect{it.morpheusResourceId}}")

                SyncTask<ComputeServerIdentityProjection, Map, ComputeServer> syncTask = new SyncTask<>(domainRecords, cloudVms.vms ?: [])
                syncTask.addMatchFunction { ComputeServerIdentityProjection domainObject, Map apiItem ->
                    domainObject.externalId == apiItem.uid
                }.onDelete { removeItems ->
                    removeVMs(removeItems)
                }.onUpdate { List<SyncTask.UpdateItem<ComputeServer, Map>> updateItems ->
                    updateVMs(updateItems, serverType, storageVolumeTypeMap, storageControllerTypeMap, fallbackServicePlan, availablePlans, availablePlanPermissions)
                }.onAdd { itemsToAdd ->
                    if (importExisting){
                        addVMs(itemsToAdd, serverType, storageVolumeTypeMap, storageControllerTypeMap, fallbackServicePlan, availablePlans, availablePlanPermissions)
                    } else {
                        log.info("Harvester: Skipping addVMs as importExisting passed as ${importExisting}")
                    }
                }.withLoadObjectDetailsFromFinder { updateItems ->
                    return morpheusContext.async.computeServer.list(
                            new DataQuery().withFilters(
                                    new DataOrFilter(updateItems.collect {new DataFilter('id', it.existingItem.id) }                                       ),
                            ))
                }.start()


            } else {
                log.error("Harvester: Error retrieving VMs, error ${cloudVms.message}")
                throw new Exception("Error retrieving VMs for ${this.cluster.name}")
            }

        } catch(e) {
            log.error("Harvester: cacheVM error: ${e}", e)
        }
    }

    private addVMs(addList, ComputeServerType serverType,
                   Map<String, StorageVolumeType> storageVolumeTypeMap,
                   Map<String, StorageControllerType> storageControllerTypeMap, ServicePlan fallbackServicePlan, List<ServicePlan> availablePlans, Collection<ResourcePermission> availablePlanPermissions) {
        log.info("Harvester: VMs addList# ${addList?.size()}")
        def itemsToAdd = []
        Cloud cloud = cluster.cloud
        def networkMap = fetchNetworkForVMInterfaces(addList)

        def interfaceTypes = DBUtils.getInterfaceTypeFromInterfaceModel(morpheusContext) ?: []
        Map<Object,Object> interfaceTypeMap = interfaceTypes?.collectEntries { [it.code, it] } ?: [:]

        addList?.each { Map vmToAdd ->
            try {
                def license = true
                if(license) {
                    def namespace = vmToAdd.namespace
                    CloudPool pool = morpheusContext.services.cloud.pool.find(new DataQuery().withFilters(
                            new DataFilter('cloud',  cloud),
                            new DataFilter('name',  namespace),
                            new DataFilter('type', 'in', HarvesterConstants.NAMESPACE_TYPES),
                            new DataFilter('category', "${HarvesterConstants.CATEGORY_NAMESPACE_CLUSTER_PREFIX}${cluster.id}".toString()),
                    ))
                    // Extract all available IP addresses from interfaces
                    def availableIps = vmToAdd.interfaces?.collect { it.ipAddress }?.findAll { it } ?: []
                    // Use first available IP or default
                    def ipAddress = availableIps ? availableIps.first() : HarvesterConstants.DEFAULT_IP

                    def parentServer = null
                    if (vmToAdd.nodeDetails){
                        parentServer = morpheusContext.services.computeServer.find(new DataQuery().withFilters(
                                new DataFilter('cloud',  cloud),
                                new DataFilter('externalId',  vmToAdd.nodeDetails.uid),
                                new DataFilter('category', "${HarvesterConstants.CATEGORY_HARVESTER_NODE_PREFIX}${cluster.id}".toString()),
                        ))
                    }
                    log.debug("Harvester: vmToAdd ${vmToAdd}, parentServer ${parentServer}")
                    def vmConfig = [
                            account      : cluster.owner,
                            externalId   : vmToAdd.uid,
                            uniqueId     : vmToAdd.uid,
                            name         : vmToAdd.name,
                            externalIp   : ipAddress,
                            internalIp   : ipAddress,
                            sshHost      : ipAddress,
                            sshUsername  : HarvesterConstants.SSH_USER_ROOT,
                            hostname     : null,
                            provision    : false,
                            computeServerType: serverType,
                            singleTenant : true,
                            cloud        : cloud,
                            category     : "${HarvesterConstants.CATEGORY_HARVESTER_VM_PREFIX}${cluster.id}",
                            lvmEnabled   : false,
                            managed      : false,
                            discovered   : true,
                            serverType   : HarvesterConstants.SERVER_TYPE_VM,
                            status       : HarvesterConstants.STATUS_PROVISIONED
                    ]
                    vmConfig.apiKey = java.util.UUID.randomUUID()

                    log.debug("Harvester: Adding VM with config ${vmConfig}, vmDetails: ${vmToAdd}")
                    ComputeServer computeServer = new ComputeServer(vmConfig)
                    computeServer.powerState = determinePowerState(vmToAdd.status?.toString())
                    // The VM's resource pool is its namespace; storage classes and networks are assigned to every
                    // namespace pool, so reconfigure still finds them. Fall back to the cluster pool.
                    CloudPool clusterPool = pool ? null : DBUtils.getClusterResourcePool(morpheusContext, cluster)
                    computeServer.resourcePool = pool ?: clusterPool

                    def maxStorage = 0
                    def maxMemory = 0
                    def usedStorage = 0
                    def maxCores = 0
                    def coresPerSocket = vmToAdd.cpu.cores?.toInteger() ?: 1  // Cores per socket from Harvester
                    def sockets = vmToAdd.cpu.sockets?.toInteger() ?: 1
                    maxStorage = vmToAdd.volumes?.size() > 0 ? vmToAdd.volumes*.sizeBytes.sum() : 0
                    maxCores = coresPerSocket * sockets  // total cores is cores per socket × number of sockets
                    maxMemory = vmToAdd.memory ?: 0

                    computeServer.plan = findServicePlanBySizing(
                            availablePlans, maxMemory.toLong(), maxCores.toLong(),
                            coresPerSocket?.toLong(), fallbackServicePlan,
                            null,computeServer.account, availablePlanPermissions)

                    computeServer.maxMemory = maxMemory
                    computeServer.maxCores = maxCores
                    // the Harvester plan's CPU value is the VM's vCPU count
                    computeServer.maxCpu = maxCores?.toLong()
                    computeServer.coresPerSocket = coresPerSocket?.toLong()
                    computeServer.maxStorage = maxStorage
                    // CPU and memory hot add (Harvester hotplug annotation)
                    computeServer.hotResize = vmToAdd.hotplug == true
                    computeServer.cpuHotResize = vmToAdd.hotplug == true

                    def osTypeCode = CommonUtils.getMorpheusOsTypeCode(vmToAdd.osType)
                    OsType osType = morpheusContext.services.osType.find(
                            new DataQuery().withFilter('code', osTypeCode ?: 'unknown'))
                    computeServer.serverOs = osType
                    computeServer.osType = osType?.platform?.toString() ?: 'unknown'
                    log.debug("Harvester: Updating VM with osTypeCode: ${osTypeCode}, osType: ${computeServer.osType}")

                    if (computeServer.platform == 'windows')
                        computeServer.sshUsername = HarvesterConstants.SSH_USER_ADMIN

                    computeServer.capacityInfo = new ComputeCapacityInfo(
                            maxCores: maxCores, maxMemory: maxMemory,
                            maxStorage: maxStorage, usedStorage: usedStorage)
                    computeServer.guestConsoleType = CommonUtils.guestConsoleTypeFor(computeServer.osType)

                    // set the project (namespace) the VM belongs to
                    log.debug "Add VM: setting project for VM ${computeServer.name} to ${namespace}"
                    computeServer.setConfigProperty("project", ["name": namespace])
                    computeServer = morpheusContext.async.computeServer.create(computeServer).blockingGet()

                    // sync storage controllers
                     syncStorageControllers(vmToAdd.volumes, storageControllerTypeMap, computeServer)

                    // sync storage volumes
                    syncStorageVolumes(computeServer, vmToAdd.volumes, storageVolumeTypeMap, storageControllerTypeMap, cloud)

                    createComputeServerInterface(computeServer, vmToAdd.interfaces as List, namespace as String, networkMap, interfaceTypeMap)

                    // update parent server if available, some issue is there in updating parent server while adding
                    if (parentServer){
                        computeServer = morpheusContext.services.computeServer.get(computeServer.id) // refresh to get latest
                        computeServer.parentServer = parentServer
                        morpheusContext.async.computeServer.bulkSave([computeServer]).blockingGet()
                    }
                }
            }
            catch (t) {
                log.error(t.message, t)
            }

        }
    }

    private updateVMs(
            updateList, ComputeServerType serverType,
            Map<String, StorageVolumeType> storageVolumeTypeMap,
            Map<String, StorageControllerType> storageControllerTypeMap,
            ServicePlan fallbackServicePlan,
            List<ServicePlan> availablePlans,
            Collection<ResourcePermission> availablePlanPermissions) {
        log.info("Harvester: VM matchList# ${updateList?.size()}")
        Cloud cloud = cluster.cloud
        // use update list's master items(cloud items) to fetch networks for interfaces
        def networkMap = fetchNetworkForVMInterfaces(updateList.collect { it.masterItem })
        Map<String, CloudPool> namespacePoolsByName = DBUtils.getNamespacePools(morpheusContext, cluster)
                .collectEntries { [(it.name): it] }
        def interfaceTypes = DBUtils.getInterfaceTypeFromInterfaceModel(morpheusContext) ?: []
        Map<Object,Object> interfaceTypeMap = interfaceTypes?.collectEntries { [it.code, it] } ?: [:]
        updateList?.each { updateMap ->
            try {
                ComputeServer server = updateMap.existingItem
                Map matchedVm = updateMap.masterItem // cloudItem
                log.debug("Harvester: Checking state of matched vm ${matchedVm.name} - ${server}")

                if (server && server.status != 'provisioning') {
                    try {
                        // Extract all available IP addresses from interfaces
                        def availableIps = matchedVm.interfaces?.collect { it.ipAddress }?.findAll { it } ?: []
                        // Use first available IP or default
                        def ipAddress = availableIps ? availableIps.first() : HarvesterConstants.DEFAULT_IP

                        def privateIp = ipAddress
                        def publicIp = ipAddress
                        ComputeServer.PowerState powerState = determinePowerState(matchedVm.status?.toString())
                        def usedStorage = 0
                        def totalStorage = 0
                        def maxStorage = 0

                        if (matchedVm.volumes?.size() > 0) {
                            totalStorage = matchedVm.volumes*.sizeBytes.sum()
                            def nodeType = server.computeServerType?.nodeType
                            if (nodeType == 'morpheus-node') {
                                maxStorage = matchedVm.volumes[1..matchedVm.volumes.size() - 1]*.sizeBytes.sum()
                            }
                            else {
                                maxStorage = totalStorage
                            }
                        }

                        if (maxStorage == 0){
                            log.warn("VM: ${server.name} has maxStorage 0," +
                                    " volumes: ${matchedVm.volumes}," +
                                    " matchedVm: ${matchedVm}," +
                                    " nodeType: ${server.computeServerType?.nodeType}," +
                                    " status: ${server.status}")
                        }

                        def maxCores = matchedVm.cpu.cores ?: 0
                        def maxMemory = matchedVm.memory ?: 0
                        def coresPerSocket = matchedVm.cpu.cores?.toInteger() ?: 1  // Cores per socket from Harvester

                        def sockets = matchedVm.cpu.sockets?.toInteger() ?: 1
                        maxCores = coresPerSocket * sockets  // total cores is cores per socket × number of sockets

                        def correctPlan = findServicePlanBySizing(
                                availablePlans, maxMemory.toLong(), maxCores.toLong(), coresPerSocket.toLong(),
                                fallbackServicePlan, server.plan,server.account,availablePlanPermissions
                        )
                        def planInfoChanged = false

                        // Try to determine os type
                        def osTypeCode = CommonUtils.getMorpheusOsTypeCode(matchedVm.osType)
                        log.debug("Harvester: Updating VM: ${server.name} with osTypeCode: ${osTypeCode}")
                        OsType osType = morpheusContext.services.osType.find(
                                new DataQuery().withFilter('code', osTypeCode ?: 'unknown'))

                        def save = false
                        if (!server.sourceImage && server.serverOs?.code != osType.code) {
                            server.serverOs = osType
                            server.osType = osType?.platform?.toString()
                            save = true
                        }

                        if (server.computeServerType?.code == HarvesterConstants.SERVER_TYPE_UNMANAGED) {
                            server.computeServerType = serverType
                            save = true
                        }

                        if (server.name != matchedVm.name) {
                            server.name = matchedVm.name
                            save = true
                        }

                        // check for internal and external IP update
                        if (server.internalIp != privateIp) {
                            server.internalIp = privateIp
                            save = true
                        }

                        if (server.externalIp != publicIp) {
                            server.externalIp = publicIp
                            save = true
                        }

                        if (server.powerState != powerState) {
                            server.powerState = powerState
                            save = true
                        }

                        def capacityInfo = server.capacityInfo
                        if(!capacityInfo) {
                            server.capacityInfo = new ComputeCapacityInfo(
                                    maxCores:maxCores, maxMemory:maxMemory,
                                    maxStorage: maxStorage, usedStorage: usedStorage)
                            capacityInfo = server.capacityInfo
                            save = true
                        }

                        if (server.maxMemory != maxMemory) {
                            server.maxMemory = maxMemory
                            capacityInfo.maxMemory = maxMemory
                            planInfoChanged = true
                            save = true
                        }

                        if (server.maxStorage != maxStorage) {
                            server.maxStorage = maxStorage
                            capacityInfo.maxStorage = maxStorage
                            planInfoChanged = true
                            save = true
                        }

                        if (server.maxCores != maxCores) {
                            server.maxCores = maxCores
                            capacityInfo.maxCores = maxCores
                            planInfoChanged = true
                            save = true
                        }

                        if (server.maxCpu != (maxCores as Long)) {
                            // the Harvester plan's CPU value is the VM's vCPU count
                            server.maxCpu = maxCores as Long
                            save = true
                        }
                        if (server.coresPerSocket != coresPerSocket) {
                            server.coresPerSocket = coresPerSocket
                            planInfoChanged = true
                            save = true
                        }

                        boolean hotplug = matchedVm.hotplug == true
                        if ((server.hotResize == true) != hotplug || (server.cpuHotResize == true) != hotplug) {
                            server.hotResize = hotplug
                            server.cpuHotResize = hotplug
                            save = true
                        }

                        if (server.computeServerType == null) {
                            server.computeServerType = serverType
                            save = true
                        }

                        if (!server.uniqueId) {
                            server.uniqueId = matchedVm.uid
                            save = true
                        }

                        if (server.plan && server.plan?.id != correctPlan?.id) {
                            server.plan = correctPlan
                            planInfoChanged = true
                            save = true
                        }

                        // update the project (namespace) the VM belongs to
                        def currentProjectName = server.getConfigProperty("project")?.get("name")
                        if (currentProjectName != matchedVm.namespace) {
                            log.debug "Update VM: updating project for VM ${server.name} from ${currentProjectName} to ${matchedVm.namespace}"
                            server.setConfigProperty("project", ["name": matchedVm.namespace])
                            save = true
                        }

                        // The VM's resource pool is the pool of its namespace (VMs synced by earlier versions
                        // used the cluster-wide pool)
                        CloudPool namespacePool = namespacePoolsByName[matchedVm.namespace]
                        CloudPool targetPool = namespacePool ?: DBUtils.getClusterResourcePool(morpheusContext, cluster)
                        if (targetPool && server.resourcePool?.id != targetPool.id) {
                            log.debug "Updating resource pool for VM ${server.name} to ${targetPool.name}"
                            server.resourcePool = targetPool
                            save = true
                        }
                        // handling parent server update
                        def parentServer = null
                        if (matchedVm.nodeDetails){
                            parentServer = morpheusContext.services.computeServer.find(new DataQuery().withFilters(
                                    new DataFilter('cloud',  cloud),
                                    new DataFilter('externalId',  matchedVm.nodeDetails.uid),
                                    new DataFilter('category', "${HarvesterConstants.CATEGORY_HARVESTER_NODE_PREFIX}${cluster.id}".toString()),
                            ))
                        }
                        if (!parentServer && server.parentServer) {
                            server.parentServer = null
                            save = true
                        } else if (parentServer && !server.parentServer) {
                            server.parentServer = parentServer
                            save = true
                        } else if (server.parentServer?.id != parentServer?.id) {
                            server.parentServer = parentServer
                            save = true
                        }

                        // Earlier versions switched the guest console off on every VM: turn it back on (SSH/RDP).
                        // A type set any other way (e.g. by the user) is kept.
                        if (server.guestConsoleType == null || server.guestConsoleType == ComputeServer.GuestConsoleType.disabled) {
                            server.guestConsoleType = CommonUtils.guestConsoleTypeFor(server.osType ?: server.platform)
                            save = true
                        }

                        if (save) {
                            morpheusContext.async.computeServer.bulkSave([server]).blockingGet()
                        }

                        // sync storage controllers
                        syncStorageControllers(matchedVm.volumes, storageControllerTypeMap, server)

                        // Sync storage volumes
                        syncStorageVolumes(
                                server, matchedVm.volumes, storageVolumeTypeMap, storageControllerTypeMap, cloud)

                        // Sync interfaces
                        syncComputeServerInterface(server, matchedVm.interfaces as List, matchedVm.namespace as String, networkMap, interfaceTypeMap)

                        // Harvester labels <-> Morpheus tags
                        try {
                            new VmTagsSync(morpheusContext, cluster).sync(server, matchedVm)
                        } catch (Throwable tagEx) {
                            log.warn("Harvester: tag sync failed for ${server?.name}: ${tagEx.message}", tagEx)
                        }

                        // todo update server containers and instances if needed
//                        if (planInfoChanged && server.computeServerType?.guestVm) {
//                            updateServerContainersAndInstances(server, correctPlan)
//                        }

                    } catch (ex) {
                        log.warn("Error Updating Virtual Machine ${server?.name} - ${server.externalId} - ${ex}", ex)
                    }
                }
            }
            catch (notFoundEx) {
                //VM Has dissappeared in the middle of a sync, ignore and move on
                log.warn(notFoundEx.message, notFoundEx)
            }
        }
    }

    private removeVMs(removeList) {
        log.info("Harvester: VMs removeList# ${removeList?.size()}")
        // filter vs having state provisioning?
        def vms = []
        removeList.each { item ->
            if (item.status == 'provisioning') {
                log.info("Harvester: Skipping removal of VM ${item.name} as it is in provisioning state")
            } else {
                vms << item
            }
        }
        morpheusContext.async.computeServer.bulkRemove(vms).blockingGet()
    }

    /**
     * Determines the power state of a VM based on its status
     * @param vmStatus The status string from the VM
     * @return The corresponding ComputeServer.PowerState
     */
    private ComputeServer.PowerState determinePowerState(String vmStatus) {
        if (!vmStatus) {
            return ComputeServer.PowerState.unknown
        }

        switch (vmStatus) {
            case HarvesterConstants.STATUS_RUNNING:
                return ComputeServer.PowerState.on
            case HarvesterConstants.STATUS_STOPPED:
                return ComputeServer.PowerState.off
            case HarvesterConstants.STATUS_PAUSED:
                return ComputeServer.PowerState.paused
            default:
                return ComputeServer.PowerState.unknown
        }
    }

    private ServicePlan findServicePlanBySizing(Collection<ServicePlan> allPlans, Long maxMemory, Long maxCores, Long coresPerSocket=null, ServicePlan fallbackPlan=null, ServicePlan existingPlan = null, Account account = null, Collection<ResourcePermission> resourcePermissions = null) {
        Collection<ServicePlan> availablePlans = allPlans
        if(existingPlan?.deleted) {
            existingPlan = null
        }
        if(existingPlan?.code == 'container-unmanaged') {
            //this is a special discovery plan we use in tf to match, its temporary and should not stay
            existingPlan = null
        }
        if(account) {
            availablePlans = allPlans?.findAll { pl -> pl.visibility == 'public' || pl.account?.id == account?.id || pl.owner?.id == account?.id || (pl.account == null && pl.visibility == 'public') || resourcePermissions?.find{ rp -> rp.morpheusResourceId == pl.id && rp.account?.id == account?.id}  }
            if(existingPlan) {
                if(existingPlan.visibility == 'public' || existingPlan.account?.id == account?.id || existingPlan.owner?.id == account?.id || (existingPlan.account == null && existingPlan.visibility == 'public') || resourcePermissions?.find{ rp -> rp.morpheusResourceId == existingPlan.id && rp.account?.id == account?.id} ) {
                    existingPlan = existingPlan
                } else {
                    existingPlan = null //we have to correct a plan discrepency due to permissions on the vm
                }
            }
        }

        //first lets try to find a match by zone and an exact match at that
        if(existingPlan && existingPlan != fallbackPlan) {
            if((existingPlan.maxMemory == maxMemory || existingPlan.customMaxMemory) && ((existingPlan.maxCores == 0 && maxCores == 1) || existingPlan.maxCores == maxCores || (existingPlan.customCores || existingPlan.customCpu)) && (!coresPerSocket || (existingPlan.coresPerSocket == coresPerSocket || (existingPlan.customCores || existingPlan.customCpu)))) {
                return existingPlan //existingPlan is still sufficient
            }
        }
        Collection<ServicePlan> matchedPlans
        if(!coresPerSocket || coresPerSocket == 1) {

            matchedPlans = availablePlans.findAll{it.maxMemory == maxMemory && it.customMaxMemory != true && !(it.customCores || it.customCpu) && ((maxCores == 1 && (it.maxCores == null || it.maxCores == 0)) || it.maxCores == maxCores) && (it.coresPerSocket == null || it.coresPerSocket == 1)}
        } else {
            matchedPlans = availablePlans.findAll{it.maxMemory == maxMemory && ((maxCores == 1 && (it.maxCores == null || it.maxCores == 0)) || it.maxCores == maxCores) && it.customMaxMemory != true && !(it.customCores || it.customCpu) && it.coresPerSocket == coresPerSocket}
        }

        if(!matchedPlans) {
            matchedPlans = availablePlans.findAll { it.maxMemory == maxMemory && (it.customCores || it.customCpu) }
        }

        //check globals
        if(!matchedPlans) {
            //we need to look by custom
            if(!coresPerSocket || coresPerSocket == 1) {
                matchedPlans = availablePlans.findAll { ((maxCores == 1 && (it.maxCores == null || it.maxCores == 0)) || it.maxCores == maxCores) && (it.coresPerSocket == null || it.coresPerSocket == 1) && it.customMaxMemory }
            } else {
                matchedPlans = availablePlans.findAll { ((maxCores == 1 && (it.maxCores == null || it.maxCores == 0)) || it.maxCores == maxCores) && it.coresPerSocket == coresPerSocket && it.customMaxMemory }
            }

        }

        if(!matchedPlans) {
            matchedPlans = availablePlans.findAll { it.customMaxMemory && (it.customCores || it.customCpu) }
        }

        if(matchedPlans) {
            return matchedPlans.first()
        } else {
            return fallbackPlan
        }
    }

    /**
     * Creates a new StorageVolume instance from volume data
     * @param volumeData The volume data from the VM
     * @param volType The storage volume type
     * @param displayOrder The display order index
     * @param cloud The cloud instance
     * @return A new StorageVolume instance
     */
    private StorageVolume createStorageVolume(
            ComputeServer server, volumeData, StorageVolumeType volType,
            StorageControllerType storageControllerType,
            displayOrder, Cloud cloud) {
        Datastore datastore = findDatastoreForVolume(volumeData, cloud)
        StorageVolume sourceVolume = DBUtils.getSourceStorageVolume(morpheusContext, cluster, volumeData)
        String sourceId = sourceVolume ? sourceVolume.id.toString() : null

        def newVolume = new StorageVolume(
                rootVolume: displayOrder == 1,
                type: volType,
                volumeType : volType.code,
                name: volumeData.name,
                maxStorage: volumeData.sizeBytes,
                deviceName: volumeData.deviceName,
                deviceDisplayName: volumeData.deviceName,
                displayOrder: displayOrder,
                category    : DBUtils.buildVMStorageVolumeCategory(server, volType, cluster.id),
                claimName   : volumeData.claimName,
                poolName    : volumeData.namespace,
                sourceId    : sourceId,
                externalId  : sourceId,
                cloudId     : cloud.id,
                account     : cluster.owner,
        )
        server = morpheusContext.services.computeServer.get(server.id) // refresh to get controllers
        StorageController storageController = DBUtils.findStorageControllerByType(server, storageControllerType)
        newVolume.controller = storageController
        newVolume.datastore = datastore
        return newVolume
    }

    /**
     * Finds the appropriate datastore for a volume
     * @param volumeData The volume data containing storage class information
     * @param cloud The cloud instance
     * @return The matching Datastore or null if not found
     */
    private Datastore findDatastoreForVolume(Map volumeData, Cloud cloud) {
        return DBUtils.findDatastoreByStorageClass(morpheusContext, cluster, volumeData.storageClass as String, cloud)
    }

    private  syncStorageControllers(
            List vmVolumes, Map<String,
            StorageControllerType> storageControllerTypeMap,
            ComputeServer computeServer) {
        def result = ["removeList": []]

        if (!vmVolumes) return
        log.debug("vmVolumes: ${vmVolumes}, server: ${computeServer.name}")

        // Get existing storage controllers for this compute server to avoid duplicates
        List<StorageController> existingControllers = computeServer.controllers ?: []
        List<String> existingControllerTypes = existingControllers.collect { it.type?.code }.findAll { it }

        List<StorageController> controllersToAdd = []

        // Extract unique controller types from volumes based on their bus information
        Set<String> requiredControllerTypes = new HashSet<String>()

        vmVolumes.each { vol ->
            def storageControllerValue = vol.storageController

            if (storageControllerValue) {
                String storageController = storageControllerValue.toString()

                if (storageControllerTypeMap.containsKey(storageController)) {
                    requiredControllerTypes.add(storageController)
                    log.debug("Added required controller type: ${storageController}")
                } else {
                    log.warn("Storage controller type '${storageController}' not found in available types: ${storageControllerTypeMap.keySet()}")
                }
            }
        }

        // Create controllers for types that don't already exist
        requiredControllerTypes.each { requiredController ->
            if (!existingControllerTypes.contains(requiredController)) {
                StorageControllerType controllerType = storageControllerTypeMap[requiredController]
                if (controllerType) {
                    StorageController newController = new StorageController(
                        type: controllerType,
                        name: controllerType.name,
                        active: true,
                        busNumber: '0',
                        unitNumber: '0',
                        displayOrder: controllerType.displayOrder,
                        description: controllerType.description,
                        uniqueId: "${requiredController}.vm.${computeServer.id}",
                    )
                    controllersToAdd << newController
                    log.debug("Adding storage controller: ${requiredController} for server: ${computeServer.name}")
                } else {
                    log.warn("Storage controller type not found for code: ${requiredController}, server: ${computeServer.name}")
                }
            } else {
                log.debug("Storage controller type ${requiredController} already exists for server: ${computeServer.name}")
            }
        }

        // controllers to remove
        def controllersToRemove = existingControllers.findAll { controller ->
            !requiredControllerTypes.contains(controller.type?.code)
        }
        result.removeList = controllersToRemove

        // Add new controllers to the compute server if any
        if (controllersToAdd) {
            morpheusContext.async.storage.controller.create(controllersToAdd, computeServer).blockingGet()
        }

        // remove the controllers
        if (controllersToRemove) {
            log.info("controllersToRemove: ${controllersToRemove}, server: ${computeServer.name}")
            morpheusContext.async.storage.controller.bulkRemove(controllersToRemove)
        }

        return result
    }

     /**
     * Creates volumes for a new VM and associates them with the compute server
     * @param vmVolumes List of volume data from the VM
     * @param volType The storage volume type
     * @param computeServer The compute server to associate volumes with
     * @param cloud The cloud instance
     */
    private createNewStorageVolumes(List vmVolumes,
            Map<String, StorageVolumeType> storageVolTypeMap,
            Map<String, StorageControllerType> storageControllerTypeMap,
            ComputeServer computeServer, Cloud cloud) {
        if (!vmVolumes) return

        def volumes = []
        vmVolumes.eachWithIndex { vol, i ->
            def volType = storageVolTypeMap[vol.type]
            def storageControllerType = storageControllerTypeMap[vol.storageController]
            def newVolume =  createStorageVolume(
                    computeServer, vol, volType, storageControllerType,
                    vol.bootOrder, cloud)
            volumes << newVolume
        }

        if (volumes) {
            morpheusContext.async.storage.volume.create(volumes, computeServer).blockingGet()
        }
        return volumes
    }

    /**
     * Updates externalID and category for old provisioned VM's volumes,
     * and create the new volume with same externalID
     * @param vmVolumes List of VM's volume
     * @param server The compute server associated with the VM
     * @param cloud The cloud instance
     */
    private updateExistingProvisionedVMsVolume(List<StorageVolume> vmVolumes, ComputeServer server, Cloud cloud)
    {
        if (!vmVolumes || vmVolumes.size() == 0) return

        def vmName = server.name
        def vmId = server.id
        vmVolumes.each { StorageVolume vmVol ->
            // old Provisioned VM's volume will have the category like PVC/dataVolume.cluster.id
            // in next call it will not come here as we will be updating the category like PVC/dataVolume.cluster.id.vm.id
            def category = DBUtils.buildStorageVolumeCategory(vmVol.type, cluster.id)
            if (vmVol.category == category) {
                def vmVolId = vmVol.id
                def vmVolName = vmVol.name
                def externalId = vmVol.externalId
                log.info("Harvester: Found a volume: ${vmVolName}(${vmVolId}) with externalID: ${externalId}" +
                        " for VM: ${vmName}(${vmId}), need to update externalId, and category and clone it with same externalId")
                def internalId = vmVol.internalId

                def cloneVolConfig = [
                        externalId : externalId,
                        uniqueId   : externalId,
                        name       : vmVol.name,
                        maxStorage : vmVol.maxStorage,
                        datastore  : vmVol.datastore,
                        type       : vmVol.type,
                        volumeType : vmVol.volumeType,
                        config     : vmVol.config,
                        claimName  : vmVol.claimName,
                        account    : cluster.owner,
                        poolName   : vmVol.poolName,
                        refType    : HarvesterConstants.COMPUTE_SERVER_GROUP,
                        refId      : cluster.id,
                        cloudId    : cloud.id,
                        category   : category,
                        internalId : internalId,
                ]

                // first will update the existing provisioned VM's volume's category to remove externalId so that it does not get sync through by DV/PVC sync
                vmVol.externalId = null // and later will update it with sourceID from update flow, just after this
                vmVol.uniqueId = null
                vmVol.internalId = null
                vmVol.category = DBUtils.buildVMStorageVolumeCategory(server, vmVol.type, cluster.id)
                StorageVolume updatedVmVol = morpheusContext.services.storage.volume.save(vmVol)
                if (updatedVmVol){
                   // now check if no volume exists with the same externalId then we will create it
                    StorageVolume existingVol = morpheusContext.services.storage.volume.find(new DataQuery().withFilters(
                            new DataFilter('name', vmVol.name),
                            new DataFilter('volumeType', vmVol.volumeType),
                            new DataFilter('externalId', externalId),
                            new DataFilter('category', category),
                    ))
                    if (existingVol) {
                        log.info("Harvester: volume with name: ${vmVolName}(${vmVolId}) and" +
                                " externalId: ${externalId}, category: ${category} alreday exists," +
                                " VM name: ${vmName}(${vmId}), skipping clone")
                    } else {
                        log.info("Harvester: cloning volume: ${vmVolName}(${vmVolId})" +
                                " of VM: ${vmName}(${vmId}) with config: ${cloneVolConfig}")
                        def cloneVol = new StorageVolume(cloneVolConfig)
                        cloneVol = morpheusContext.services.storage.volume.create(cloneVol)
                        if(cloneVol) {
                            log.info("Harvester: successfully cloned volume: ${vmVolName}(${vmVolId}) of VM: ${vmName}(${vmId})")
                        } else {
                            // no worries here, if cloning fails then it will be created by PVC/DV sync and source id will get updated in next sync
                            log.error("Harvester: failed to clone volume: ${vmVolName}(${vmVolId}) of VM: ${vmName}(${vmId}) with config: ${cloneVolConfig}")
                        }
                     }
                } else {
                    // if in case somehow it comes here(ideally should not),
                    // it will be updated as part of update volume and the cloning will be done by PVC/DV sync
                    log.error("Harvester: failed to update external id for volume: ${vmVolName}(${vmVolId}) of VM: ${vmName}(${vmId})")
                }
            }
        }
    }

    /**
     * Synchronizes volumes for an existing VM, handling additions, updates, and removals
     * @param server The existing compute server
     * @param vmVolumes List of volume data from the VM
     * @param volType The storage volume type
     * @param cloud The cloud instance
     * @return A map containing flags for save and planInfoChanged
     */
    private Map syncStorageVolumes(
            ComputeServer currentServer, List vmVolumes,
            Map<String, StorageVolumeType> storageVolumeTypeMap,
            Map<String, StorageControllerType> storageControllerTypeMap, Cloud cloud) {
        def result = [save: false, planInfoChanged: false]

        // refresh the server to get any controller changes
        def server = morpheusContext.services.computeServer.get(currentServer.id)
        log.debug("Syncing volumes for VM: ${server.name} - Current Volumes: ${server.volumes?.size() ?: 0},  vmVolumes: ${vmVolumes}")

        if (server.volumes?.size()==0) {
            // Server has no volumes, create them
            createNewStorageVolumes(vmVolumes, storageVolumeTypeMap,
                    storageControllerTypeMap, server, cloud)
            result.save = true
            result.planInfoChanged = true
            return result
        } else {
            List<StorageVolume> cleanupVolumes = []
            def newVolumes = []

            // handle the existing provisioning VM's volume having the externalID
            // todo remove after few version
            updateExistingProvisionedVMsVolume(server.volumes, server, cloud)

            // Handle volume removals
            def removeVolumes = server.volumes.findAll { vol ->
                !vmVolumes*.name.contains(vol.name)
            }
            if (removeVolumes) {
                log.debug("Removing volumes  ${removeVolumes?.size()} from server: ${server.name}")
                removeVolumes.each { vol ->
                    server.getVolumes().remove(vol)
                    result.save = true
                    result.planInfoChanged = true
                    cleanupVolumes << vol
                }
            }

            // Handle volume additions and updates
            vmVolumes.eachWithIndex { Map volume, i ->
                log.debug("volume: ${volume.name}  VM: ${server.name}")

                StorageVolumeType volType = storageVolumeTypeMap[volume.type]
                StorageControllerType storageControllerType = storageControllerTypeMap[volume.storageController]

                def existingVol = server.volumes.find { vol -> vol.name == volume.name }
                if (existingVol) {
                    log.debug("Found existing volume: ${volume} on server: ${server.name}")
                    // Update existing volume
                    if (updateExistingVolume(existingVol, volume, volume.bootOrder, cloud, server)) {
                        result.save = true
                        result.planInfoChanged = true
                    }
                } else {
                    // Create new volume
                    log.debug("found new volume: ${volume.name} for  VM: ${server.name}")
                    newVolumes << createStorageVolume(
                            server, volume, volType,
                            storageControllerType, volume.bootOrder, cloud)
                    result.save = true
                    result.planInfoChanged = true
                }
            }

            if (newVolumes) {
                morpheusContext.async.storage.volume.create(newVolumes, server).blockingGet()
                log.debug("Creating new volumes: ${newVolumes.size()} for VM: ${server.name}")
            }

            // Cleanup removed volumes
            if (cleanupVolumes) {
                morpheusContext.async.storage.volume.remove(cleanupVolumes, server, false).blockingGet()
            }

            if (cleanupVolumes || newVolumes) {
                server = morpheusContext.services.computeServer.get(currentServer.id) // refresh the server to get any controller changes
                log.debug("Updated Volumes: ${server.volumes?.size()} for VM: ${server.name}")
            }
        }

        return result
    }

    /**
     * Updates an existing volume with new data
     * @param existingVol The existing storage volume
     * @param volumeData The new volume data
     * @param displayOrder The new display order
     * @param cloud The cloud instance
     * @return true if the volume was modified and needs saving
     */
    private boolean updateExistingVolume(StorageVolume existingVol, Map volumeData, displayOrder, Cloud cloud, ComputeServer server) {
        boolean saveVol = false
        Datastore datastore = findDatastoreForVolume(volumeData, cloud)
        if (existingVol.maxStorage != volumeData.sizeBytes) {
            existingVol.maxStorage = volumeData.sizeBytes
            saveVol = true
        }

        if (existingVol.datastore?.id != datastore?.id) {
            existingVol.datastore = datastore
            saveVol = true
        }

        // convert displayOrder to integer and the compare
        if (existingVol.displayOrder != displayOrder as Integer) {
            existingVol.displayOrder = displayOrder
            saveVol = true
        }

        if (existingVol.deviceName != volumeData.deviceName) {
            existingVol.deviceName = volumeData.deviceName
            saveVol = true
        }

        if (existingVol.deviceDisplayName != volumeData.deviceName) {
            existingVol.deviceDisplayName = volumeData.deviceName
            saveVol = true
        }

        if ( existingVol.displayOrder ==1){
            if (!existingVol.rootVolume) {
                existingVol.rootVolume = true
                saveVol = true
            }
        } else {
            if (existingVol.rootVolume) {
                existingVol.rootVolume = false
                saveVol = true
            }
        }

        // update claim name for pvc based volumes
        if (existingVol.claimName != volumeData.claimName) {
            existingVol.claimName = volumeData.claimName
            saveVol = true
        }

        // update pool name (namespace) for volumes
        if (existingVol.poolName != volumeData.namespace) {
            existingVol.poolName = volumeData.namespace
            saveVol = true
        }

        // update sourceId for volumes(this will update for all VM's(discovered as well provisioned ones)
        StorageVolume sourceVolume = DBUtils.getSourceStorageVolume(morpheusContext, cluster, volumeData)
        if (sourceVolume && existingVol.sourceId != sourceVolume.id.toString()) {
            existingVol.sourceId = sourceVolume.id.toString()
            saveVol = true
        }

        // update category ( this we could have already updated(for provisioned's VM),
        // but safe side check and if not same update it
        String category = DBUtils.buildVMStorageVolumeCategory(server, existingVol.type, cluster.id)
        if (existingVol.category != category) {
            existingVol.category = category
            saveVol = true
        }

        // will make sure VM's vol will have external id as sourceID
        // this will update for all VM's(discovered as well provisioned ones)
        if (sourceVolume && existingVol.externalId != sourceVolume.id.toString()) {
            existingVol.externalId = sourceVolume.id.toString()
            existingVol.uniqueId = null
            saveVol = true
        }

        if (saveVol) {
            log.info("Updating volume ${existingVol.id}: ${existingVol.name}, volumeData: ${volumeData}")
            morpheusContext.services.storage.volume.save(existingVol)
        }

        return saveVol
    }

    /**
     * Creates network interfaces for a compute server based on VM interface data
     * @param server The compute server to associate interfaces with
     * @param networkInterface List of interface data from the VM
     * @param namespace The namespace of the VM
     * @param networkMap A map of "namespace/networkName" to Network objects
     * @param interfaceTypeMap A map of interface type codes to ComputeServerInterfaceType objects
     */
    private void createComputeServerInterface(ComputeServer computeServer, List networkInterface, String namespace, Map networkMap, Map interfaceTypeMap) {
        if (!networkInterface || networkInterface.isEmpty()) return
        List<ComputeServerInterface> newInterfaces = []
        ComputeServer server = morpheusContext.services.computeServer.get(computeServer.id) // refresh to get latest
        networkInterface.eachWithIndex { iface, index ->
            def ifaceCode = CommonUtils.getInterfaceTypeCode(iface.model)
            // def uniqueId = "${server.id}-${iface.name}"
            def network = networkMap["${namespace}/${iface.networkName}"]
            // handle networks from namespace different than VM namespace of format {namespace}/{networkname}
            if (iface.networkName?.contains('/')){
                network = networkMap["${iface.networkName}"]
            }
            def podNetwork = networkMap["${HarvesterConstants.POD_NETWORK_NAME}"]
            def newInterface = new ComputeServerInterface(
                    // uniqueId: uniqueId,
                    type: interfaceTypeMap[ifaceCode] as ComputeServerInterfaceType,
                    macAddress: iface.macAddress,
                    name: iface.name,
                    displayOrder: index + 1,
                    network: (iface.defaultPodNetwork ? podNetwork as Network : network as Network),
                    // set it to true conditionally later
                    dhcp: false,
                    primaryInterface: false,

            )
            iface.ipAddresses?.each { ipEntry ->
                NetAddress.AddressType ipType = CommonUtils.getIpType(ipEntry as String)
                if (ipEntry==server.externalIp) {
                    log.debug("Found primary interface: ${ipEntry} == ${server.externalIp}")
                    newInterface.primaryInterface=true
                }
                if ((ipType == NetAddress.AddressType.IPV6 && newInterface.network?.dhcpServerIPv6) ||
                        (ipType == NetAddress.AddressType.IPV4 && newInterface.network?.dhcpServer)) {
                    newInterface.dhcp = true
                }
                newInterface.addresses.add(new NetAddress(type: ipType, address: ipEntry))
            }
            newInterfaces << newInterface
        }
        if (newInterfaces) {
            log.debug("Creating interfaces: ${newInterfaces} for server: ${server.name}")
            morpheusContext.async.computeServer.computeServerInterface.create(newInterfaces,server).blockingGet()
        }
//        if (saveServer){
//            server.interfaces = newInterfaces
//            morpheusContext.services.computeServer.save(server)
//        }
    }


    /**
     * Updates an existing compute server interface with new data
     * @param server The compute server the interface belongs to
     * @param matchedInterface The existing compute server interface to update
     * @param networkInterface The new interface data
     * @param index The display order index
     * @param namespace The namespace of the VM
     * @param networkMap A map of "namespace/networkName" to Network objects
     * @param interfaceTypeMap A map of interface type codes to ComputeServerInterfaceType objects
     * @return true if the interface was modified
     */
    private Boolean updateComputeServerInterface(ComputeServer server,ComputeServerInterface matchedInterface, Object networkInterface,
                                                 Integer index, String namespace, Map networkMap,Map interfaceTypeMap) {
        def interfaceTypeCode = CommonUtils.getInterfaceTypeCode(networkInterface.model)
        def network = networkMap["${namespace}/${networkInterface.networkName}"]
        // handle networks from namespace different than VM namespace
        if (networkInterface.networkName?.contains('/')){
            network = networkMap["${networkInterface.networkName}"]
        }
        def podNetwork = networkMap["${HarvesterConstants.POD_NETWORK_NAME}"]
        networkInterface.network = (networkInterface.defaultPodNetwork ? podNetwork as Network : network as Network)
        def serverIPAddress = server.externalIp
        Boolean save = false
        Boolean foundPrimary = false
        Boolean isDHCP = false
        if(matchedInterface.network?.code != networkInterface.network?.code) {
            matchedInterface.network = networkInterface.network
            save = true
        }
        if(matchedInterface.macAddress != networkInterface.macAddress) {
            matchedInterface.macAddress = networkInterface.macAddress
            save = true
        }
        if(matchedInterface.type?.code != interfaceTypeMap[interfaceTypeCode]?.code) {
            matchedInterface.type = interfaceTypeMap[interfaceTypeCode] as ComputeServerInterfaceType
            save = true
        }
        if(matchedInterface.displayOrder != index + 1) {
            matchedInterface.displayOrder = index + 1
            save = true
        }

        // iterate through the ip addresses and add any new ones, delete old ones
        networkInterface.ipAddresses?.each { ipEntry ->
            if (ipEntry==serverIPAddress) {
                log.debug("Found primary interface: ${ipEntry} == ${serverIPAddress}")
                foundPrimary = true
            }
            NetAddress.AddressType ipType = CommonUtils.getIpType(ipEntry as String)
            def existingAddress = matchedInterface.addresses?.find { it.address == ipEntry }
            if (!existingAddress) {
                matchedInterface.addresses.add(new NetAddress(type: ipType, address: ipEntry))
                save = true
            }

            if ((ipType == NetAddress.AddressType.IPV6 && networkInterface.network?.dhcpServerIPv6) ||
                    (ipType == NetAddress.AddressType.IPV4 && networkInterface.network?.dhcpServer))
                isDHCP = true
        }
        // remove any old addresses that are not in the new list
        matchedInterface.addresses?.toList()?.each { addr ->
            def found = networkInterface.ipAddresses?.find { it == addr.address }
            if (!found) {
                matchedInterface.addresses.remove(addr)
                save = true
            }
        }

        if (matchedInterface.primaryInterface != foundPrimary) {
            matchedInterface.primaryInterface = foundPrimary
            save = true
        }

        if (matchedInterface.dhcp != isDHCP) {
            matchedInterface.dhcp = isDHCP
            save = true
        }

        if (save) {
            log.info("Saving updated interface: ${matchedInterface}")
            morpheusContext.async.computeServer.computeServerInterface.save([matchedInterface]).blockingGet()
        }
        return save
    }

    /**
     * Synchronizes the compute server interfaces with the provided network interface data
     * @param server The compute server to synchronize interfaces for
     * @param networkInterface List of interface data from the VM
     * @param namespace The namespace of the VM
     * @param networkMap A map of "namespace/networkName" to Network objects
     * @param interfaceTypeMap A map of interface type codes to ComputeServerInterfaceType objects
     * @return A map containing flags for save and planInfoChanged
     */
    private Map syncComputeServerInterface(ComputeServer currentServer, List networkInterface, String namespace, Map networkMap, Map interfaceTypeMap) {
        def result = [save: false, planInfoChanged: false]
        def server = morpheusContext.services.computeServer.get(currentServer.id) // refresh the server
        def serverInterfaces = server.interfaces ?: []
        if (!serverInterfaces || serverInterfaces?.size() == 0) {
            // Server has no interfaces, create them
            createComputeServerInterface(server, networkInterface as List, namespace, networkMap, interfaceTypeMap)
            result.save = true
            result.planInfoChanged = true
            return result
        }

        def newInterfaces = []
        networkInterface?.eachWithIndex { iface, index ->
            def matchedInterface = serverInterfaces?.find{ it.name == "${iface.name}"}
            log.debug("matchedInterface: ${matchedInterface?.name} of server ${server.name}")
            if (!matchedInterface) {
                newInterfaces << iface
            }
            else {
                // Update existing interface if needed
                Boolean saved = updateComputeServerInterface(server,matchedInterface, iface , index, namespace, networkMap, interfaceTypeMap)
                if (saved) {
                    result.save = true
                    result.planInfoChanged = true
                }

            }
        }

        if (newInterfaces) {
            createComputeServerInterface(server, newInterfaces as List, namespace, networkMap, interfaceTypeMap)
            result.save = true
            result.planInfoChanged = true
        }

        // Handle interface removals
        def missingInterfaces = []
        server.interfaces?.each { iface ->
            def found = networkInterface.find{"${it.name}" == iface.name}
            if(!found) {
                missingInterfaces << iface
            }
        }
        if (missingInterfaces) {
            log.info("Removing interfaces from server: ${server.id} - ${server.name}")
            morpheusContext.async.computeServer.computeServerInterface.remove(missingInterfaces,server).blockingGet()
        }
        return result
    }

    /**
     * Builds a lookup map keyed by "namespace/networkName" for network object.
     * @param vmList List of VMs, each with namespace and interfaces
     * @return Map of "namespace/networkName" -> Network object
     */
    private fetchNetworkForVMInterfaces(vmList) {
        def networkMap = [:]
        def poolMap = [:]
        def namespaces = vmList?.collect { it.namespace }?.findAll()?.unique() ?: []
        log.debug("Fetching networks for namespaces: ${namespaces}")

        if (namespaces) {
            def poolList = morpheusContext.services.cloud.pool.list(new DataQuery().withFilters(
                    new DataFilter('cloud', cluster.cloud),
                    new DataFilter('type', 'in', HarvesterConstants.NAMESPACE_TYPES),
                    new DataFilter('category', "${HarvesterConstants.CATEGORY_NAMESPACE_CLUSTER_PREFIX}${cluster.id}"),
                    new DataFilter('name', 'in', namespaces)
            ))
            poolList?.each { p -> poolMap[p.name] = p.id }
        }
        log.debug("Fetching networks poolMap: ${poolMap}")
        def zonePoolNetworkPairs = [] as Set
        def networkNames = [] as Set
        def poolIds = [] as Set

        vmList?.each { vm ->
            vm.interfaces?.each { iface ->
                def namespaceName = vm.namespace
                def namespacePoolId = poolMap[vm.namespace]
                def name = iface.networkName?.toString()
                def networkName = name?.contains('/') ? (name.split('/').size() >= 2 ? name.split('/')[1] : name) : name
                // if VM has interfaces with nads of different namespace and networkName is of the format {namespace}/{networkName},
                // fetch that namespace id here
                if (name?.contains('/') && (name.split('/').size() >= 2)) {
                    def nameList = name.split('/')
                    if (nameList[0] != vm.namespace){
                        CloudPool ifaceNetworkNamespace = morpheusContext.services.cloud.pool.find(new DataQuery().withFilters(
                                new DataFilter('cloud', cluster.cloud),
                                new DataFilter('type', 'in', HarvesterConstants.NAMESPACE_TYPES),
                                new DataFilter('category', "${HarvesterConstants.CATEGORY_NAMESPACE_CLUSTER_PREFIX}${cluster.id}"),
                                new DataFilter('name', nameList[0])
                        ))
                        namespacePoolId = ifaceNetworkNamespace?.id
                        namespaceName = nameList[0]
                        networkName = nameList[1]
                    }

                }

                if (namespacePoolId && networkName) {
                    log.debug("Adding zonePoolNetworkPair: ${namespaceName}/${networkName} with poolId: ${namespacePoolId}")
                    zonePoolNetworkPairs << [zonePoolName: namespaceName, zonepoolId: namespacePoolId, networkName: networkName]
                    networkNames << networkName
                    poolIds << namespacePoolId
                }
            }
        }
        def allNetworks = []
        if (networkNames && poolIds) {
            allNetworks = morpheusContext.services.network.list(new DataQuery().withFilters(
                    new DataFilter('refType', HarvesterConstants.COMPUTE_ZONE),
                    new DataFilter('refId', cluster.cloud.id),
                    new DataFilter('category', "${HarvesterConstants.CATEGORY_NETWORK_CLUSTER_PREFIX}${cluster.id}"),
                    new DataFilter('name', 'in', networkNames as List),
                    new DataFilter('assignedZonePools.id', 'in', poolIds as List)
            ))
        }
        zonePoolNetworkPairs?.each { pair ->
            def foundNet = allNetworks?.find { n ->
                n.name == pair.networkName && n.assignedZonePools?.any { it.id == pair.zonepoolId }
            }
            if (foundNet) {
                networkMap["${pair.zonePoolName}/${pair.networkName}"] = foundNet
                log.debug("foundNet: ${foundNet} ${foundNet.name}")
            } else {
                log.warn("No network found for namespace: ${pair.zonePoolName}, networkName: ${pair.networkName}, poolId: ${pair.zonepoolId}")
            }
        }
        // get default pod network
        def podNetwork = morpheusContext.services.network.find(new DataQuery().withFilters(
                new DataFilter('category', "${HarvesterConstants.CATEGORY_NETWORK_CLUSTER_PREFIX}${cluster.id}"),
                new DataFilter('name',"${HarvesterConstants.POD_NETWORK_NAME}"),
        ))
        networkMap["${HarvesterConstants.POD_NETWORK_NAME}"] = podNetwork
        log.debug("Final networkMap: ${networkMap}")
        return networkMap
    }
}
