// (C) Copyright 2025 Hewlett Packard Enterprise Development LP
package com.hpe.morpheus.harvester.plugin.apihelper
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import groovy.json.JsonSlurper

/**
 * Helper class for Harvester NAD(Network Attachment Definition) API operations
 * Purpose: Provides high-level API methods for Harvester NAD operations
 */

@Slf4j
class NADApiHelper {
    // Instance fields for configuration
    private ComputeServerGroup serverGroup
    private HarvesterApiClient apiClient

    /**
     * Create instance with ComputeServerGroup credentials
     * @param serverGroup ComputeServerGroup containing credentials
     */
    NADApiHelper(ComputeServerGroup serverGroup = null) {
        this.serverGroup = serverGroup
        this.apiClient = new HarvesterApiClient(serverGroup)
        log.debug "Initialized NADApiHelper with authentication"
    }

    /**
     * get network-attachment-definitions from Harvester API
     * get default cluster network from Harvester API
     * @return Map with network-attachment-definitions and default cluster network
     */
    def getNetworkAttachmentDefinitions() {
        def ret = [success: false, networkAttachmentDefinitions: [], message: '']
        ServiceResponse response = null

        try {
            log.info "Fetching network-attachment-definitions from Harvester API"
            response = apiClient.get(HarvesterConstants.HARVESTER_API_V1_NADS)
            if (response?.success && response?.data) {
                ret.networkAttachmentDefinitions = (response.data.items ?: []).collect { nad ->
                    def configStr = nad.spec?.config
                    def configMap = configStr ? new JsonSlurper().parseText(configStr) : [:]
                    [
                            uid: nad.metadata?.uid,
                            name: nad.metadata?.name,
                            namespace: nad.metadata?.namespace,
                            externalType: nad.kind,
                            bridge: configMap.bridge,
                            vlanID: configMap.vlan,
                            networkType: configMap.type,
                            // TEMPORARY: Assume DHCP for all NADs as static IP is not supported in Morpheus yet
                            dhcp:true,
                            //dhcp: configMap.ipam?.type == 'dhcp',
                            config: configMap,
                    ]
                }
                log.debug("Fetched Harvester network-attachment-definitions: ${ret.networkAttachmentDefinitions}")

                // get default pod network
                def clusterNetworkList = getClusterNetwork()
                if (clusterNetworkList instanceof List && clusterNetworkList) {
                    ret.networkAttachmentDefinitions.addAll(clusterNetworkList)
                    ret.success = true
                } else {
                    log.error("Failed to fetch cluster network from Harvester. Skipping NAD sync to avoid deleting existing networks.")
                    ret.message = 'Failed to fetch cluster network details from Harvester'
                }
            } else {
                log.error("Get NAD API call failed: ${response?.errors ?: 'Unknown error'}")
                ret.message = response?.msg ?: 'Failed to fetch NADs from Harvester'
            }
        } catch (Exception e) {
            log.error("Failed to fetch NADs from Harvester. Exception: ${e.message}", e)
            ret.message = response?.msg ?: 'Failed to fetch NADs from Harvester'
        }
        return ret
    }

    /**
     * Returns the pod network (Harvester's "management network") as a network entry.
     * Harvester backs it with the 'mgmt' ClusterNetwork; its uid is used as a stable external id.
     * Older releases without that CRD fall back to the kube-system namespace uid.
     * @return List with a single pod network map, or empty if neither lookup works
     */
    def getClusterNetwork(){
        def networkList = []
        ServiceResponse response = null
        try {
            log.info "Fetching management (pod) network from Harvester API"
            response = apiClient.get(HarvesterConstants.HARVESTER_API_MGMT_CLUSTER_NETWORK)
            String kind = response?.data?.kind ?: 'ClusterNetwork'
            String uid = response?.success ? response?.data?.metadata?.uid : null
            if (!uid) {
                log.info "mgmt ClusterNetwork not available (${response?.msg}); falling back to kube-system namespace uid"
                response = apiClient.get(HarvesterConstants.HARVESTER_API_KUBE_SYSTEM_NAMESPACE)
                uid = response?.success ? response?.data?.metadata?.uid : null
                kind = 'Namespace'
            }
            if (uid) {
                networkList = [[
                        uid: uid,
                        name: HarvesterConstants.POD_NETWORK_NAME,
                        externalType: kind,
                        networkType: HarvesterConstants.POD_NETWORK_NAME,
                        dhcp: true,
                        config: [:]
                ]]
                log.debug("Harvester management network: ${networkList}")
            } else {
                log.error("Get management network failed. Message: ${response?.msg}. Error: ${response?.errors ?: 'Unknown error'}")
            }
        } catch (Exception e) {
            log.error("Failed to fetch management network from Harvester. Message: ${response?.msg}. Exception: ${e.message}", e)
        }
        return networkList
    }
}
