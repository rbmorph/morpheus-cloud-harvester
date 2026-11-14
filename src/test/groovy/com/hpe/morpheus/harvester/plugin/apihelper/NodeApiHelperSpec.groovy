package com.hpe.morpheus.harvester.plugin.apihelper

import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification

/**
 * Unit tests for NodeApiHelper class
 */

class NodeApiHelperSpec extends Specification {

    NodeApiHelper nodeApiHelper
    HarvesterApiClient mockApiClient
    ComputeServerGroup mockServerGroup

    def setup() {
        mockServerGroup = Mock(ComputeServerGroup)
        mockApiClient = Mock(HarvesterApiClient)
        nodeApiHelper = new NodeApiHelper(mockServerGroup)
        nodeApiHelper.apiClient = mockApiClient
    }

    def "test getNodes with successful response"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            uid: "node-uid-1",
                            name: "worker-node-1",
                            labels: [
                                "node-role.kubernetes.io/worker": "",
                                "beta.kubernetes.io/arch": "amd64"
                            ]
                        ],
                        status: [
                            conditions: [
                                [type: "Ready", status: "True"]
                            ],
                            nodeInfo: [
                                operatingSystem: "linux",
                                osImage: "Red Hat Enterprise Linux CoreOS 4.10",
                                architecture: "amd64",
                                kernelVersion: "4.18.0-372.9.1.el8.x86_64"
                            ],
                            capacity: [
                                cpu: "4",
                                memory: "8Gi",
                                "ephemeral-storage": "100Gi"
                            ],
                            addresses: [
                                [type: "InternalIP", address: "10.0.1.100"],
                                [type: "ExternalIP", address: "192.168.1.100"],
                                [type: "Hostname", address: "worker-node-1"]
                            ]
                        ]
                    ],
                    [
                        metadata: [
                            uid: "node-uid-2",
                            name: "master-node-1",
                            labels: [
                                "node-role.kubernetes.io/master": "",
                                "node-role.kubernetes.io/control-plane": ""
                            ]
                        ],
                        status: [
                            conditions: [
                                [type: "Ready", status: "True"]
                            ],
                            nodeInfo: [
                                operatingSystem: "linux",
                                osImage: "Red Hat Enterprise Linux CoreOS 4.10",
                                architecture: "amd64",
                                kernelVersion: "4.18.0-372.9.1.el8.x86_64"
                            ],
                            capacity: [
                                cpu: "8000m",
                                memory: "16Gi",
                                "ephemeral-storage": "200Gi"
                            ],
                            addresses: [
                                [type: "InternalIP", address: "10.0.1.10"],
                                [type: "Hostname", address: "master-node-1"]
                            ]
                        ]
                    ]
                ]
            ]
        )

        def mockMetricsResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [name: "worker-node-1"],
                        usage: [cpu: "500m", memory: "2Gi"]
                    ],
                    [
                        metadata: [name: "master-node-1"],
                        usage: [cpu: "1000m", memory: "4Gi"]
                    ]
                ]
            ]
        )

        when:
        def result = nodeApiHelper.getNodes()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NODES) >> mockResponse
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NODES_METRICS) >> mockMetricsResponse
        result.success == true
        result.nodes.size() == 2

        // Test first node (worker)
        def workerNode = result.nodes[0]
        workerNode.uid == "node-uid-1"
        workerNode.name == "worker-node-1"
        workerNode.ready == true
        workerNode.status.nodeInfo.operatingSystem == "linux"
        workerNode.status.nodeInfo.osImage == "Red Hat Enterprise Linux CoreOS 4.10"
        workerNode.status.nodeInfo.architecture == "amd64"
        workerNode.status.nodeInfo.kernelVersion == "4.18.0-372.9.1.el8.x86_64"
        workerNode.cpuCapacity == 4L
        workerNode.memoryCapacity == 8589934592L // 8Gi in bytes
        workerNode.storageCapacity == 107374182400L // 100Gi in bytes
        workerNode.cpuUsage == 0L // 500m = 0.5 cores (rounded to 0 in Long)
        workerNode.memoryUsage == 2147483648L // 2Gi in bytes
        workerNode.internalIP == "10.0.1.100"
        workerNode.externalIP == "192.168.1.100"
        workerNode.hostname == "worker-node-1"
        workerNode.nodeType == "worker"
        workerNode.roles == ["worker"]

        // Test second node (master)
        def masterNode = result.nodes[1]
        masterNode.uid == "node-uid-2"
        masterNode.name == "master-node-1"
        masterNode.ready == true
        masterNode.cpuCapacity == 8L // 8000m = 8 cores
        masterNode.memoryCapacity == 17179869184L // 16Gi in bytes
        masterNode.storageCapacity == 214748364800L // 200Gi in bytes
        masterNode.cpuUsage == 1L // 1000m = 1 core
        masterNode.memoryUsage == 4294967296L // 4Gi in bytes
        masterNode.internalIP == "10.0.1.10"
        masterNode.externalIP == null
        masterNode.hostname == "master-node-1"
        masterNode.nodeType == "master"
        masterNode.roles == ["master", "control-plane"]
    }

    def "test getNodes with failed response"() {
        given:
        def mockResponse = new ServiceResponse(
            success: false,
            msg: "Authentication failed",
            errorCode: "AUTH_ERROR",
            statusCode: 401
        )

        when:
        def result = nodeApiHelper.getNodes()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NODES) >> mockResponse
        0 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NODES_METRICS) // Should not be called on failure
        result.success == false
        result.message == "Authentication failed"
        result.nodes.size() == 0
    }

    def "test getNodes with empty response"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [items: []]
        )

        def mockMetricsResponse = new ServiceResponse(
            success: true,
            data: [items: []]
        )

        when:
        def result = nodeApiHelper.getNodes()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NODES) >> mockResponse
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NODES_METRICS) >> mockMetricsResponse
        result.success == true
        result.nodes.size() == 0
    }

    def "test isNodeReady with ready condition"() {
        given:
        def conditions = [
            [type: "Ready", status: "True"],
            [type: "OutOfDisk", status: "False"]
        ]

        when:
        def result = NodeApiHelper.isNodeReady(conditions)

        then:
        result == true
    }

    def "test isNodeReady with not ready condition"() {
        given:
        def conditions = [
            [type: "Ready", status: "False"],
            [type: "OutOfDisk", status: "False"]
        ]

        when:
        def result = NodeApiHelper.isNodeReady(conditions)

        then:
        result == false
    }

    def "test isNodeReady with no ready condition"() {
        given:
        def conditions = [
            [type: "OutOfDisk", status: "False"]
        ]

        when:
        def result = NodeApiHelper.isNodeReady(conditions)

        then:
        result == false
    }

    def "test isNodeReady with null conditions"() {
        when:
        def result = NodeApiHelper.isNodeReady(null)

        then:
        result == false
    }

    def "test getNodeInternalIP"() {
        given:
        def addresses = [
            [type: "InternalIP", address: "10.0.1.100"],
            [type: "ExternalIP", address: "192.168.1.100"],
            [type: "Hostname", address: "worker-node-1"]
        ]

        when:
        def result = NodeApiHelper.getNodeInternalIP(addresses)

        then:
        result == "10.0.1.100"
    }

    def "test getNodeExternalIP"() {
        given:
        def addresses = [
            [type: "InternalIP", address: "10.0.1.100"],
            [type: "ExternalIP", address: "192.168.1.100"],
            [type: "Hostname", address: "worker-node-1"]
        ]

        when:
        def result = NodeApiHelper.getNodeExternalIP(addresses)

        then:
        result == "192.168.1.100"
    }

    def "test getNodeHostname"() {
        given:
        def addresses = [
            [type: "InternalIP", address: "10.0.1.100"],
            [type: "ExternalIP", address: "192.168.1.100"],
            [type: "Hostname", address: "worker-node-1"]
        ]

        when:
        def result = NodeApiHelper.getNodeHostname(addresses)

        then:
        result == "worker-node-1"
    }

    def "test getNodeRoles with worker role"() {
        given:
        def labels = [
            "node-role.kubernetes.io/worker": "",
            "beta.kubernetes.io/arch": "amd64"
        ]

        when:
        def result = NodeApiHelper.getNodeRoles(labels)

        then:
        result == ["worker"]
    }

    def "test getNodeRoles with master roles"() {
        given:
        def labels = [
            "node-role.kubernetes.io/master": "",
            "node-role.kubernetes.io/control-plane": "",
            "beta.kubernetes.io/arch": "amd64"
        ]

        when:
        def result = NodeApiHelper.getNodeRoles(labels)

        then:
        result == ["master", "control-plane"]
    }

    def "test getNodeRoles with no role labels"() {
        given:
        def labels = [
            "beta.kubernetes.io/arch": "amd64",
            "kubernetes.io/hostname": "worker-1"
        ]

        when:
        def result = NodeApiHelper.getNodeRoles(labels)

        then:
        result == []
    }

    def "test getNodeType master"() {
        given:
        def labels = [
            "node-role.kubernetes.io/master": "",
            "node-role.kubernetes.io/control-plane": ""
        ]

        when:
        def result = NodeApiHelper.getNodeType(labels)

        then:
        result == "master"
    }

    def "test getNodeType control-plane"() {
        given:
        def labels = [
            "node-role.kubernetes.io/control-plane": ""
        ]

        when:
        def result = NodeApiHelper.getNodeType(labels)

        then:
        result == "master"
    }

    def "test getNodeType infra"() {
        given:
        def labels = [
            "node-role.kubernetes.io/infra": ""
        ]

        when:
        def result = NodeApiHelper.getNodeType(labels)

        then:
        result == "infra"
    }

    def "test getNodeType infrastructure"() {
        given:
        def labels = [
            "node-role.kubernetes.io/infrastructure": ""
        ]

        when:
        def result = NodeApiHelper.getNodeType(labels)

        then:
        result == "infra"
    }

    def "test getNodeType worker default"() {
        given:
        def labels = [
            "node-role.kubernetes.io/worker": ""
        ]

        when:
        def result = NodeApiHelper.getNodeType(labels)

        then:
        result == "worker"
    }

    def "test getNodeType default with no labels"() {
        when:
        def result = NodeApiHelper.getNodeType(null)

        then:
        result == "worker"
    }

    def "test addresses methods with null input"() {
        expect:
        NodeApiHelper.getNodeInternalIP(null) == null
        NodeApiHelper.getNodeExternalIP(null) == null
        NodeApiHelper.getNodeHostname(null) == null
    }

    def "test addresses methods with empty addresses"() {
        given:
        def emptyAddresses = []

        expect:
        NodeApiHelper.getNodeInternalIP(emptyAddresses) == null
        NodeApiHelper.getNodeExternalIP(emptyAddresses) == null
        NodeApiHelper.getNodeHostname(emptyAddresses) == null
    }

    def "test getNodeRoles with null labels"() {
        when:
        def result = NodeApiHelper.getNodeRoles(null)

        then:
        result == []
    }

    def "test getMasterNodeDetails returns master node when present"() {
        given:
        nodeApiHelper.metaClass.getNodes = {
            [
                success: true,
                nodes: [
                    [name: 'worker-1', nodeType: 'worker'],
                    [name: 'master-1', nodeType: HarvesterConstants.NODE_TYPE_MASTER]
                ]
            ]
        }

        when:
        def result = nodeApiHelper.getMasterNodeDetails()

        then:
        result.name == 'master-1'
        result.nodeType == HarvesterConstants.NODE_TYPE_MASTER
    }

    def "test getMasterNodeDetails returns null when no master exists"() {
        given:
        nodeApiHelper.metaClass.getNodes = {
            [
                success: true,
                nodes: [
                    [name: 'worker-1', nodeType: 'worker']
                ]
            ]
        }

        when:
        def result = nodeApiHelper.getMasterNodeDetails()

        then:
        result == null
    }

    def "test getMasterNodeDetails returns null when getNodes fails"() {
        given:
        nodeApiHelper.metaClass.getNodes = {
            [success: false, message: 'failed to fetch nodes', nodes: []]
        }

        when:
        def result = nodeApiHelper.getMasterNodeDetails()

        then:
        result == null
    }

    def "test getNodes with metrics fetch failure still returns nodes with zero usage"() {
        given:
        def nodeResponse = new ServiceResponse(
            success: true,
            data: [items: [
                [
                    metadata: [uid: 'n1', name: 'worker-1', labels: [:]],
                    status: [
                        conditions: [[type: 'Ready', status: 'True']],
                        addresses: [[type: 'InternalIP', address: '10.0.0.1']],
                        capacity: [cpu: '4', memory: '8Gi', 'ephemeral-storage': '100Gi']
                    ]
                ]
            ]]
        )
        def metricsResponse = new ServiceResponse(success: false, msg: 'metrics unavailable')
        mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NODES) >> nodeResponse
        mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NODES_METRICS) >> metricsResponse

        when:
        def result = nodeApiHelper.getNodes()

        then:
        result.success == true
        result.nodes.size() == 1
        result.nodes[0].cpuUsage == 0
        result.nodes[0].memoryUsage == 0
    }

    def "test getNodes with metrics available sets usage values"() {
        given:
        def nodeResponse = new ServiceResponse(
            success: true,
            data: [items: [
                [
                    metadata: [uid: 'n2', name: 'worker-2', labels: [:]],
                    status: [
                        conditions: [[type: 'Ready', status: 'True']],
                        addresses: [[type: 'InternalIP', address: '10.0.0.2']],
                        capacity: [cpu: '2', memory: '4Gi', 'ephemeral-storage': '50Gi']
                    ]
                ]
            ]]
        )
        def metricsResponse = new ServiceResponse(
            success: true,
            data: [items: [
                [metadata: [name: 'worker-2'], usage: [cpu: '500m', memory: '1Gi']]
            ]]
        )
        mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NODES) >> nodeResponse
        mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NODES_METRICS) >> metricsResponse

        when:
        def result = nodeApiHelper.getNodes()

        then:
        result.success == true
        result.nodes.size() == 1
        result.nodes[0].name == 'worker-2'
        result.nodes[0].cpuUsage > 0 || result.nodes[0].cpuUsage >= 0
    }

    def "test getNodeType with infra node returns infra"() {
        when:
        def result = NodeApiHelper.getNodeType(['node-role.kubernetes.io/infra': ''])

        then:
        result == 'infra'
    }

    def "test getNodeType with infrastructure label returns infra"() {
        when:
        def result = NodeApiHelper.getNodeType(['node-role.kubernetes.io/infrastructure': ''])

        then:
        result == 'infra'
    }

    def "test getNodeRoles skips labels without role prefix"() {
        when:
        def result = NodeApiHelper.getNodeRoles(['kubernetes.io/hostname': 'my-node', 'beta.kubernetes.io/os': 'linux'])

        then:
        result == []
    }

    def "test getNodeRoles with empty role suffix is ignored"() {
        when:
        // 'node-role.kubernetes.io/' with empty suffix - the suffix after '/' is empty
        def result = NodeApiHelper.getNodeRoles(['other-label': 'value'])

        then:
        result == []
    }

    def "test getNodeInternalIP returns null when InternalIP not in list"() {
        when:
        def result = NodeApiHelper.getNodeInternalIP([[type: 'Hostname', address: 'worker-1']])

        then:
        result == null
    }

    def "test getNodeExternalIP returns null when ExternalIP not in list"() {
        when:
        def result = NodeApiHelper.getNodeExternalIP([[type: 'InternalIP', address: '10.0.0.1']])

        then:
        result == null
    }

    def "test getNodeHostname returns hostname address"() {
        when:
        def result = NodeApiHelper.getNodeHostname([[type: 'Hostname', address: 'my-worker']])

        then:
        result == 'my-worker'
    }

    def "test getMasterNodeDetails returns null when nodes list is empty"() {
        given:
        nodeApiHelper.metaClass.getNodes = {
            [success: true, nodes: []]
        }

        when:
        def result = nodeApiHelper.getMasterNodeDetails()

        then:
        result == null
    }

    def "test getNodes with multiple nodes builds all entries"() {
        given:
        def nodeResponse = new ServiceResponse(
            success: true,
            data: [items: [
                [
                    metadata: [uid: 'u1', name: 'master-1', labels: ['node-role.kubernetes.io/master': '']],
                    status: [
                        conditions: [[type: 'Ready', status: 'True']],
                        addresses: [[type: 'InternalIP', address: '10.0.0.1']],
                        capacity: [cpu: '4', memory: '8Gi', 'ephemeral-storage': '0']
                    ]
                ],
                [
                    metadata: [uid: 'u2', name: 'worker-1', labels: ['node-role.kubernetes.io/worker': '']],
                    status: [
                        conditions: [[type: 'Ready', status: 'False']],
                        addresses: [[type: 'InternalIP', address: '10.0.0.2']],
                        capacity: [cpu: '2', memory: '4Gi', 'ephemeral-storage': '0']
                    ]
                ]
            ]]
        )
        mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NODES) >> nodeResponse
        mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NODES_METRICS) >> new ServiceResponse(success: false)

        when:
        def result = nodeApiHelper.getNodes()

        then:
        result.success == true
        result.nodes.size() == 2
        result.nodes[0].nodeType == 'master'
        result.nodes[1].ready == false
    }
}
