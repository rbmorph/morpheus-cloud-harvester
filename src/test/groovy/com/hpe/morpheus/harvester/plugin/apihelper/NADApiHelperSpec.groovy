// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.apihelper

import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Unit tests for NADApiHelper class
 */
class NADApiHelperSpec extends Specification {

    NADApiHelper nadApiHelper
    HarvesterApiClient mockApiClient
    ComputeServerGroup mockServerGroup

    def setup() {
        mockServerGroup = Mock(ComputeServerGroup)
        mockApiClient = Mock(HarvesterApiClient)
        nadApiHelper = new NADApiHelper(mockServerGroup)
        nadApiHelper.apiClient = mockApiClient
    }

    def "getNetworkAttachmentDefinitions should retrieve NADs successfully"() {
        given:
        def nadConfig = [
            type: "bridge",
            bridge: "br0",
            vlan: 100,
            ipam: [type: "dhcp"]
        ]
        def nadConfigJson = groovy.json.JsonOutput.toJson(nadConfig)

        def mockNADResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        kind: "NetworkAttachmentDefinition",
                        metadata: [
                            uid: "nad-uid-1",
                            name: "vlan-100",
                            namespace: "default"
                        ],
                        spec: [
                            config: nadConfigJson
                        ]
                    ],
                    [
                        kind: "NetworkAttachmentDefinition",
                        metadata: [
                            uid: "nad-uid-2",
                            name: "macvlan-net",
                            namespace: "my-project"
                        ],
                        spec: [
                            config: '{"type":"macvlan","master":"eth0","mode":"bridge","ipam":{"type":"static"}}'
                        ]
                    ]
                ]
            ]
        )

        def mockClusterNetworkResponse = new ServiceResponse(
            success: true,
            data: [kind: "ClusterNetwork", metadata: [uid: "cluster-net-uid", name: "mgmt"]]
        )

        when:
        def result = nadApiHelper.getNetworkAttachmentDefinitions()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NADS) >> mockNADResponse
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_MGMT_CLUSTER_NETWORK) >> mockClusterNetworkResponse

        result.success == true
        result.networkAttachmentDefinitions.size() == 3  // 2 NADs + 1 cluster network
        
        // Verify first NAD
        result.networkAttachmentDefinitions[0].uid == "nad-uid-1"
        result.networkAttachmentDefinitions[0].name == "vlan-100"
        result.networkAttachmentDefinitions[0].namespace == "default"
        result.networkAttachmentDefinitions[0].externalType == "NetworkAttachmentDefinition"
        result.networkAttachmentDefinitions[0].bridge == "br0"
        result.networkAttachmentDefinitions[0].vlanID == 100
        result.networkAttachmentDefinitions[0].networkType == "bridge"
        result.networkAttachmentDefinitions[0].dhcp == true

        // Verify second NAD
        result.networkAttachmentDefinitions[1].name == "macvlan-net"
        result.networkAttachmentDefinitions[1].networkType == "macvlan"

        // Verify cluster network
        result.networkAttachmentDefinitions[2].uid == "cluster-net-uid"
        result.networkAttachmentDefinitions[2].name == HarvesterConstants.POD_NETWORK_NAME
        result.networkAttachmentDefinitions[2].networkType == HarvesterConstants.POD_NETWORK_NAME
        result.networkAttachmentDefinitions[2].dhcp == true
    }

    def "getNetworkAttachmentDefinitions should handle NAD with static IP config"() {
        given:
        def nadConfig = [
            type: "bridge",
            bridge: "br1",
            ipam: [type: "static", addresses: [[address: "192.168.1.10/24"]]]
        ]
        def nadConfigJson = groovy.json.JsonOutput.toJson(nadConfig)

        def mockNADResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        kind: "NetworkAttachmentDefinition",
                        metadata: [
                            uid: "nad-uid-1",
                            name: "static-net",
                            namespace: "default"
                        ],
                        spec: [
                            config: nadConfigJson
                        ]
                    ]
                ]
            ]
        )

        def mockClusterNetworkResponse = new ServiceResponse(
            success: true,
            data: [kind: "ClusterNetwork", metadata: [uid: "cluster-net-uid", name: "mgmt"]]
        )

        when:
        def result = nadApiHelper.getNetworkAttachmentDefinitions()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NADS) >> mockNADResponse
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_MGMT_CLUSTER_NETWORK) >> mockClusterNetworkResponse

        result.success == true
        result.networkAttachmentDefinitions.size() == 2  // 1 NAD + 1 cluster network
        // NOTE: As per code comment, DHCP is assumed true temporarily even for static IP
        result.networkAttachmentDefinitions[0].dhcp == true
    }

    def "getNetworkAttachmentDefinitions should handle empty NAD list"() {
        given:
        def mockNADResponse = new ServiceResponse(
            success: true,
            data: [items: []]
        )

        def mockClusterNetworkResponse = new ServiceResponse(
            success: true,
            data: [kind: "ClusterNetwork", metadata: [uid: "net-uid", name: "mgmt"]]
        )

        when:
        def result = nadApiHelper.getNetworkAttachmentDefinitions()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NADS) >> mockNADResponse
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_MGMT_CLUSTER_NETWORK) >> mockClusterNetworkResponse

        result.success == true
        result.networkAttachmentDefinitions.size() == 1  // Only cluster network
        result.networkAttachmentDefinitions[0].name == HarvesterConstants.POD_NETWORK_NAME
    }

    def "getNetworkAttachmentDefinitions should handle missing NAD config"() {
        given:
        def mockNADResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        kind: "NetworkAttachmentDefinition",
                        metadata: [
                            uid: "nad-uid-1",
                            name: "incomplete-nad",
                            namespace: "default"
                        ],
                        spec: [:]  // No config
                    ]
                ]
            ]
        )

        def mockClusterNetworkResponse = new ServiceResponse(
            success: true,
            data: [kind: "ClusterNetwork", metadata: [uid: "cluster-net-uid", name: "mgmt"]]
        )

        when:
        def result = nadApiHelper.getNetworkAttachmentDefinitions()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NADS) >> mockNADResponse
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_MGMT_CLUSTER_NETWORK) >> mockClusterNetworkResponse

        result.success == true
        result.networkAttachmentDefinitions.size() == 2  // 1 NAD + 1 cluster network
        result.networkAttachmentDefinitions[0].config == [:]
        result.networkAttachmentDefinitions[0].dhcp == true
    }

    def "getNetworkAttachmentDefinitions should handle NAD API failure"() {
        given:
        def mockNADResponse = new ServiceResponse(
            success: false,
            msg: "Unauthorized",
            errors: [error: "401 Unauthorized"]
        )

        when:
        def result = nadApiHelper.getNetworkAttachmentDefinitions()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NADS) >> mockNADResponse
        0 * mockApiClient.get(HarvesterConstants.HARVESTER_API_MGMT_CLUSTER_NETWORK)

        result.success == false
        result.networkAttachmentDefinitions.size() == 0
        result.message == "Unauthorized"
    }

    def "getNetworkAttachmentDefinitions should handle cluster network API failure"() {
        given:
        def mockNADResponse = new ServiceResponse(
            success: true,
            data: [items: []]
        )

        def mockClusterNetworkResponse = new ServiceResponse(
            success: false,
            msg: "Network operator not found"
        )

        when:
        def result = nadApiHelper.getNetworkAttachmentDefinitions()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NADS) >> mockNADResponse
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_MGMT_CLUSTER_NETWORK) >> mockClusterNetworkResponse

        result.success == false
        result.networkAttachmentDefinitions.size() == 0
        result.message == "Failed to fetch cluster network details from Harvester"
    }

    def "getNetworkAttachmentDefinitions should handle exception"() {
        when:
        def result = nadApiHelper.getNetworkAttachmentDefinitions()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NADS) >> {
            throw new RuntimeException("Network timeout")
        }

        result.success == false  // Code doesn't catch exception properly and sets success=false
        result.message != null
        result.networkAttachmentDefinitions.size() == 0
    }

    def "getNetworkAttachmentDefinitions should parse various network types"() {
        given:
        def bridgeConfig = '{"type":"bridge","bridge":"br0","vlan":100,"ipam":{"type":"dhcp"}}'
        def macvlanConfig = '{"type":"macvlan","master":"eth0","mode":"bridge","ipam":{"type":"dhcp"}}'
        def ipoibConfig = '{"type":"ib-sriov","link":"ib0","ipam":{"type":"dhcp"}}'

        def mockNADResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        kind: "NetworkAttachmentDefinition",
                        metadata: [uid: "nad-1", name: "bridge-net", namespace: "default"],
                        spec: [config: bridgeConfig]
                    ],
                    [
                        kind: "NetworkAttachmentDefinition",
                        metadata: [uid: "nad-2", name: "macvlan-net", namespace: "default"],
                        spec: [config: macvlanConfig]
                    ],
                    [
                        kind: "NetworkAttachmentDefinition",
                        metadata: [uid: "nad-3", name: "ib-net", namespace: "default"],
                        spec: [config: ipoibConfig]
                    ]
                ]
            ]
        )

        def mockClusterNetworkResponse = new ServiceResponse(
            success: true,
            data: [kind: "ClusterNetwork", metadata: [uid: "cluster-net-uid", name: "mgmt"]]
        )

        when:
        def result = nadApiHelper.getNetworkAttachmentDefinitions()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NADS) >> mockNADResponse
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_MGMT_CLUSTER_NETWORK) >> mockClusterNetworkResponse

        result.success == true
        result.networkAttachmentDefinitions.size() == 4  // 3 NADs + 1 cluster network
        result.networkAttachmentDefinitions[0].networkType == "bridge"
        result.networkAttachmentDefinitions[0].bridge == "br0"
        result.networkAttachmentDefinitions[0].vlanID == 100
        result.networkAttachmentDefinitions[1].networkType == "macvlan"
        result.networkAttachmentDefinitions[2].networkType == "ib-sriov"
    }

    def "getClusterNetwork should use the mgmt ClusterNetwork uid"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [kind: "ClusterNetwork", metadata: [uid: "cluster-net-uid-1", name: "mgmt"]]
        )

        when:
        def result = nadApiHelper.getClusterNetwork()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_MGMT_CLUSTER_NETWORK) >> mockResponse
        0 * mockApiClient.get(HarvesterConstants.HARVESTER_API_KUBE_SYSTEM_NAMESPACE)

        result.size() == 1
        result[0].uid == "cluster-net-uid-1"
        result[0].name == HarvesterConstants.POD_NETWORK_NAME
        result[0].externalType == "ClusterNetwork"
        result[0].networkType == HarvesterConstants.POD_NETWORK_NAME
        result[0].dhcp == true
        result[0].config == [:]
    }

    def "getClusterNetwork should fall back to the kube-system namespace uid"() {
        when:
        def result = nadApiHelper.getClusterNetwork()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_MGMT_CLUSTER_NETWORK) >> new ServiceResponse(success: false, msg: "404 Not Found")
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_KUBE_SYSTEM_NAMESPACE) >> new ServiceResponse(
                success: true, data: [kind: "Namespace", metadata: [uid: "ns-uid", name: "kube-system"]])

        result.size() == 1
        result[0].uid == "ns-uid"
        result[0].externalType == "Namespace"
        result[0].name == HarvesterConstants.POD_NETWORK_NAME
    }

    def "getClusterNetwork should return empty when neither lookup works"() {
        when:
        def result = nadApiHelper.getClusterNetwork()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_MGMT_CLUSTER_NETWORK) >> new ServiceResponse(success: true, data: [:])
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_KUBE_SYSTEM_NAMESPACE) >> new ServiceResponse(success: false, msg: "denied")

        result == []
    }

    def "getClusterNetwork should handle exception"() {
        when:
        def result = nadApiHelper.getClusterNetwork()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_MGMT_CLUSTER_NETWORK) >> {
            throw new RuntimeException("Connection timeout")
        }

        result == []
    }

    def "getNetworkAttachmentDefinitions should handle null response data"() {
        given:
        def mockNADResponse = new ServiceResponse(success: true, data: null)

        when:
        def result = nadApiHelper.getNetworkAttachmentDefinitions()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NADS) >> mockNADResponse
        0 * mockApiClient.get(HarvesterConstants.HARVESTER_API_MGMT_CLUSTER_NETWORK)

        result.success == false
        result.networkAttachmentDefinitions.size() == 0
    }

    @Unroll
    def "getNetworkAttachmentDefinitions should parse NAD with vlan=#vlanID"() {
        given:
        def nadConfig = [
            type: "bridge",
            bridge: "br0",
            vlan: vlanID,
            ipam: [type: "dhcp"]
        ]
        def nadConfigJson = groovy.json.JsonOutput.toJson(nadConfig)

        def mockNADResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        kind: "NetworkAttachmentDefinition",
                        metadata: [uid: "nad-1", name: "vlan-net", namespace: "default"],
                        spec: [config: nadConfigJson]
                    ]
                ]
            ]
        )

        def mockClusterNetworkResponse = new ServiceResponse(
            success: true,
            data: [kind: "ClusterNetwork", metadata: [uid: "cluster-net-uid", name: "mgmt"]]
        )

        when:
        def result = nadApiHelper.getNetworkAttachmentDefinitions()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NADS) >> mockNADResponse
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_MGMT_CLUSTER_NETWORK) >> mockClusterNetworkResponse

        result.success == true
        result.networkAttachmentDefinitions[0].vlanID == vlanID

        where:
        vlanID << [1, 100, 200, 1000, 4094]
    }
}
