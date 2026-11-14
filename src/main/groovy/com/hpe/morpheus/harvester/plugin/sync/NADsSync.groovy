// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.sync
import com.hpe.morpheus.harvester.plugin.HpeHarvesterPlugin
import com.hpe.morpheus.harvester.plugin.apihelper.NADApiHelper
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.hpe.morpheus.harvester.plugin.utils.DBUtils
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.NetAddress
import com.morpheusdata.model.Network
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.projection.NetworkIdentityProjection
import com.morpheusdata.core.util.SyncTask
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import com.hpe.morpheus.harvester.plugin.utils.CommonUtils
import io.reactivex.rxjava3.core.Observable

@Slf4j
class NADsSync {

    private ComputeServerGroup cluster
    private MorpheusContext morpheusContext
    private HpeHarvesterPlugin plugin

    NADsSync(HpeHarvesterPlugin plugin, ComputeServerGroup cluster) {
        this.plugin = plugin
        this.cluster = cluster
        this.morpheusContext = plugin.morpheusContext
    }

    def execute() {
        log.info "Harvester: execute NADs Sync for: ${cluster}"
        try {
            def nadHelper = new NADApiHelper(cluster)
            // method fetches both-NADs and default cluster network
            def cloudNADs = nadHelper.getNetworkAttachmentDefinitions()
            log.info("Harvester: Found ${cloudNADs.networkAttachmentDefinitions?.size()} NADs for cluster ${cluster.name}")

            def networkList = []
            if (cloudNADs.success && cloudNADs.networkAttachmentDefinitions) {
                networkList = morpheusContext.services.network.listIdentityProjections(
                        new DataQuery().withFilters(
                                new DataFilter('refType', HarvesterConstants.COMPUTE_ZONE),
                                new DataFilter('refId', cluster.cloud.id),
                                new DataFilter('category', "${HarvesterConstants.CATEGORY_NETWORK_CLUSTER_PREFIX}${cluster.id}")
                        )
                )
                log.info("Harvester: Found ${networkList?.size()} existing NAD networks in Morpheus for cluster ${cluster.name}")
                Observable<NetworkIdentityProjection> domainRecords = Observable.fromIterable(networkList)
                SyncTask<NetworkIdentityProjection, Map, Network> syncTask = new SyncTask<>(domainRecords, cloudNADs.networkAttachmentDefinitions ?: [])
                syncTask.addMatchFunction { NetworkIdentityProjection domainObject, Map apiItem ->
                    domainObject.externalId == apiItem.uid
                }.onDelete { removeItems ->
                    removeNADs(removeItems)
                }.onUpdate { List<SyncTask.UpdateItem<Network, Map>> updateItems ->
                    updateNADs(updateItems)
                }.onAdd { itemsToAdd ->
                    addNADs(itemsToAdd)
                }.withLoadObjectDetailsFromFinder { updateItems ->
                    return Observable.fromIterable(
                            morpheusContext.services.network.list(
                                    new DataQuery().withFilters(
                                            new DataOrFilter(updateItems.collect { new DataFilter('id', it.existingItem.id) })
                                    )
                            )
                    )
                }.start()

            } else {
                log.error("Harvester: Error retrieving network-attachment-definitions ${cloudNADs.message} for ${this.cluster.name}")
            }

        } catch (e) {
            log.error("Harvester: network-attachment-definitions sync error: ${e}", e)
        }
    }

    private addNADs(addList) {
        log.info("Harvester: Adding NADs: ${addList?.size()}")
        def itemsToAdd = []
        // get the zone pool to which the NAD must be associated to
        CloudPool resourcePool = DBUtils.getClusterResourcePool(morpheusContext,cluster)
        // Collect all unique namespaces from addList and query relevant zone pools in one go
        def namespacesList = addList?.collect { it.namespace }?.findAll { it }?.unique() ?: []
        log.debug "Harvester: NAD Sync - found namespaces: ${namespacesList} in addList"
        def filterParams = [
                CommonUtils.param('category', "${HarvesterConstants.CATEGORY_NAMESPACE_CLUSTER_PREFIX}${cluster.id}"),
                CommonUtils.param('type', HarvesterConstants.NAMESPACE_TYPES, 'in'),
                CommonUtils.param('name', namespacesList,'in')
        ]
        def namespacePools = DBUtils.getZonePoolByFilterMap(morpheusContext, filterParams)
        List<CloudPool> allNamespacePools = DBUtils.getNamespacePools(morpheusContext, cluster)
        def namespacePoolMap = [:]
        namespacePools?.each { pool ->
            if (pool?.name) {
                namespacePoolMap[pool.name] = pool
            }
        }
        log.debug "Harvester: NAD Sync - namespacePoolMap: ${namespacePoolMap}"

        def networkTypeStrings = addList?.collect { it.networkType as String }?.findAll { it }?.unique() ?: []
        log.debug "Harvester: NAD Sync - found network types: ${networkTypeStrings} in addList"
        def networkTypeCodes = networkTypeStrings.collect { CommonUtils.getNetworkTypeCode(it) }.findAll { it }?.unique() ?: []
        // assign default network code if no network type specified
        if (!networkTypeCodes) {
            networkTypeCodes = [HarvesterConstants.HARVESTER_NETWORK_TYPE]
        }
        // Batch query networkType objects by code
        log.debug "Harvester: NAD Sync - found network types code: ${networkTypeCodes} in addList"
        def networkTypeObjects = DBUtils.getNetworkTypesByCodes(morpheusContext, networkTypeCodes)
        def networkTypeMap = [:]
        networkTypeObjects?.each { ntObj ->
            if (ntObj?.code) {
                networkTypeMap[ntObj.code] = ntObj
            }
        }
        log.debug "Harvester: NAD Sync - networkTypeMap: ${networkTypeMap}"

        addList?.each { Map nadItem ->
            def networkTypeCode = CommonUtils.getNetworkTypeCode(nadItem.networkType as String)
            def networkType = networkTypeMap[networkTypeCode]
            if (!networkType) {
                log.error "Harvester: No network type found for ${nadItem.networkType}."
                return
            }
            // def networkDetails = processNetworkAddresses(nadItem.config as Map)
            def namespacePool = namespacePoolMap[nadItem.namespace]
            def networkConfig = [
                    refType     : HarvesterConstants.COMPUTE_ZONE,
                    refId       : cluster.cloud.id,
                    cloudPool   : resourcePool,
                    cloud       : cluster.cloud,
                    category    : "${HarvesterConstants.CATEGORY_NETWORK_CLUSTER_PREFIX}${cluster.id}",
                    type        : networkType,
                    owner       : cluster.owner,
                    externalId  : nadItem.uid,
                    uniqueId    : nadItem.uid,
                    name        : nadItem.name,
                    displayName : "${nadItem.namespace ? nadItem.namespace + '/' : ''}${nadItem.name}",
                    code        : "${HarvesterConstants.CATEGORY_NETWORK_CLUSTER_PREFIX}${cluster.id}${nadItem.namespace ? '.' + nadItem.namespace : ''}.${nadItem.name}",
                    externalType: nadItem.externalType,
                    active      : true,
                    bridgeName  : nadItem.bridge,
                    vlanId      : nadItem.vlanID,
                    dhcpServer  : nadItem.dhcp,
                    config      : JsonOutput.toJson(nadItem.config),
                    networkSource: "harvester",
//                    cidr        : networkDetails?.cidr,
//                    gateway     : networkDetails?.gateway,
//                    cidrIPv6    : networkDetails?.cidrIPv6,
//                    gatewayIPv6 : networkDetails?.gatewayIPv6,
                    // Harvester VMs can attach to VM networks in any namespace, so every namespace pool gets the network
                    assignedZonePools: ([resourcePool, namespacePool] + allNamespacePools).findAll { it }.unique { it.id },
            ]
            log.debug "network config: ${networkConfig}"
            def network = new Network(networkConfig)
            itemsToAdd << network
        }
        if (itemsToAdd) {
            morpheusContext.services.network.bulkCreate(itemsToAdd)
            log.info("Harvester: Successfully added NADs: ${itemsToAdd.size()}")
        }
    }


    private updateNADs(updateList) {
        def itemsToUpdate = []
        CloudPool resourcePool = DBUtils.getClusterResourcePool(morpheusContext,cluster)
        List<CloudPool> allNamespacePools = DBUtils.getNamespacePools(morpheusContext, cluster)
        updateList?.each { updateMap ->
            Network network = updateMap.existingItem
            def nadItem = updateMap.masterItem
            def networkTypeCode = CommonUtils.getNetworkTypeCode(nadItem.networkType as String)
            // def networkDetails = processNetworkAddresses(nadItem.config as Map)
            def doSave = false

            if (network.refType != HarvesterConstants.COMPUTE_ZONE) {
                network.refType = HarvesterConstants.COMPUTE_ZONE
                doSave = true
            }

            if (network.refId != cluster.cloud.id) {
                network.refId = cluster.cloud.id
                doSave = true
            }
            if (network.cloudPool?.id != resourcePool?.id) {
                network.cloudPool = resourcePool
                doSave = true
            }
            if (network.cloud?.id != cluster?.cloud?.id) {
                network.cloud = cluster.cloud
                doSave = true
            }
            if (network.category != "${HarvesterConstants.CATEGORY_NETWORK_CLUSTER_PREFIX}${cluster.id}") {
                network.category = "${HarvesterConstants.CATEGORY_NETWORK_CLUSTER_PREFIX}${cluster.id}"
                doSave = true
            }
            if (network.type?.code != networkTypeCode) {
                def networkType = DBUtils.getNetworkTypesByCodes(morpheusContext,[networkTypeCode])
                if (!networkType || networkType.isEmpty()) {
                    log.error "Harvester: No network type found for ${nadItem.networkType}."
                    return
                }
                network.type = networkType[0]
                doSave = true
            }

            if (network.owner?.id != cluster.owner?.id) {
                network.owner = cluster.owner
                doSave = true
            }
            if (network.name != nadItem.name) {
                network.name = nadItem.name
                doSave = true
            }
            if (network.displayName != "${nadItem.namespace ? nadItem.namespace + '/' : ''}${nadItem.name}") {
                network.displayName = "${nadItem.namespace ? nadItem.namespace + '/' : ''}${nadItem.name}"
                doSave = true
            }
            if (network.code != "${HarvesterConstants.CATEGORY_NETWORK_CLUSTER_PREFIX}${cluster.id}${nadItem.namespace ? '.' + nadItem.namespace : ''}.${nadItem.name}") {
                network.code = "${HarvesterConstants.CATEGORY_NETWORK_CLUSTER_PREFIX}${cluster.id}${nadItem.namespace ? '.' + nadItem.namespace : ''}.${nadItem.name}"
                doSave = true
            }
            if (network.bridgeName != nadItem.bridge) {
                network.bridgeName = nadItem.bridge
                doSave = true
            }
            if (network.vlanId != nadItem.vlanID) {
                network.vlanId = nadItem.vlanID
                doSave = true
            }
            // Add deeper Compare of JSON objects if needed later
            if (network.getConfigMap() != nadItem.config) {
                network.setConfigMap(nadItem.config)
                doSave = true
            }
            // namespaces of NADs cannot be changed without deleting and recreating the NAD
            if (network.assignedZonePools == null) {
                network.assignedZonePools = []
            }
            if (DBUtils.addMissingPools(network.assignedZonePools, [resourcePool] + allNamespacePools)) {
                doSave = true
            }

            if (network.externalType != nadItem.externalType) {
                network.externalType = nadItem.externalType
                doSave = true
            }

//            if (network.dhcpServer != nadItem.dhcp) {
//                network.dhcpServer = nadItem.dhcp
//                doSave = true
//            }
//            if ((network.cidr?:"") != (networkDetails?.cidr?:"")) {
//                network.cidr = networkDetails?.cidr
//                doSave = true
//            }
//            if ((network.gateway?: "") != (networkDetails?.gateway?: "")) {
//                network.gateway = networkDetails?.gateway
//                doSave = true
//            }
            if (doSave) {
                itemsToUpdate << network
            }
        }
        if (itemsToUpdate) {
            morpheusContext.services.network.bulkSave(itemsToUpdate)
            log.info("Harvester: Successfully updated NADs: ${itemsToUpdate.size()}")
        }
    }

    private removeNADs(removeList) {
        log.info("Harvester: Removing NADs: ${removeList?.size()}")
        if (removeList) {
            morpheusContext.services.network.bulkRemove(removeList)
        }
    }

    /**
     * Processes subnet and gateway strings to determine IP types and return network properties
     * @param configMap of NAD
     * @return Map containing network properties: cidr, cidrIPv6, gateway, gatewayIPv6
     */
    // TO DO: generalize once network config is better known
    private static Map processNetworkAddresses(Map configMap) {
        def gatewayStr = configMap?.ipam?.gateway ?: configMap?.localnetGateway ?: ""
        def subnetStr = configMap?.ipam?.subnet ?: configMap?.subnets ?: ""
        def result = [
            cidr: (String) null,
            cidrIPv6: (String) null,
            gateway: (String) null,
            gatewayIPv6: (String) null
        ]
        if (!configMap) {
            return result
        }
        if (subnetStr instanceof String) {
            def ipAddress = (subnetStr && subnetStr.contains('/')) ? subnetStr.split('/')[0] : subnetStr
            def subnetIPType = CommonUtils.getIpType(ipAddress)
            result.cidr = (subnetIPType == NetAddress.AddressType.IPV4) ? subnetStr : null
            result.cidrIPv6 = (subnetIPType == NetAddress.AddressType.IPV6) ? subnetStr : null
        }

        if (gatewayStr instanceof String) {
            def gatewayIPType = CommonUtils.getIpType(gatewayStr)
            result.gateway = (gatewayIPType == NetAddress.AddressType.IPV4) ? gatewayStr : null
            result.gatewayIPv6 = (gatewayIPType == NetAddress.AddressType.IPV6) ? gatewayStr : null
        }
        return result
    }
}