// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.apihelper

import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Unit tests for PVCApiHelper class
 */
class PVCApiHelperSpec extends Specification {

    PVCApiHelper pvcApiHelper
    HarvesterApiClient mockApiClient
    ComputeServerGroup mockServerGroup

    def setup() {
        mockServerGroup = Mock(ComputeServerGroup)
        mockApiClient = Mock(HarvesterApiClient)
        pvcApiHelper = new PVCApiHelper(mockServerGroup)
        pvcApiHelper.apiClient = mockApiClient
    }

    def "getPVCs should retrieve PVCs successfully with list format"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            uid: "pvc-uid-1",
                            name: "data-volume",
                            namespace: "default"
                        ],
                        spec: [
                            storageClassName: "standard",
                            resources: [
                                requests: [storage: "10Gi"]
                            ]
                        ],
                        status: [
                            phase: "Bound"
                        ]
                    ],
                    [
                        metadata: [
                            uid: "pvc-uid-2",
                            name: "logs-volume",
                            namespace: "my-app",
                            ownerReferences: [
                                [kind: "StatefulSet", name: "app-statefulset"]
                            ]
                        ],
                        spec: [
                            storageClassName: "fast-storage",
                            resources: [
                                requests: [storage: "20Gi"]
                            ],
                            dataSource: [
                                kind: "VolumeSnapshot",
                                name: "snapshot-123"
                            ]
                        ],
                        status: [
                            phase: "Bound"
                        ]
                    ]
                ]
            ]
        )

        when:
        def result = pvcApiHelper.getPVCs(false)

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_PVCS) >> mockResponse

        result.success == true
        result.pvcs.size() == 2
        result.pvcs[0].uid == "pvc-uid-1"
        result.pvcs[0].name == "data-volume"
        result.pvcs[0].namespace == "default"
        result.pvcs[0].storageClass == "standard"
        result.pvcs[0].sizeBytes == 10737418240L  // 10Gi
        result.pvcs[0].status == "Bound"

        result.pvcs[1].uid == "pvc-uid-2"
        result.pvcs[1].name == "logs-volume"
        result.pvcs[1].namespace == "my-app"
        result.pvcs[1].storageClass == "fast-storage"
        result.pvcs[1].sizeBytes == 21474836480L  // 20Gi
        result.pvcs[1].ownerReferences != null
        result.pvcs[1].dataSource != null
    }

    def "getPVCs should retrieve PVCs with namespace map format"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            uid: "pvc-uid-1",
                            name: "pvc-1",
                            namespace: "ns1"
                        ],
                        spec: [
                            storageClassName: "standard",
                            resources: [requests: [storage: "5Gi"]]
                        ],
                        status: [phase: "Bound"]
                    ],
                    [
                        metadata: [
                            uid: "pvc-uid-2",
                            name: "pvc-2",
                            namespace: "ns2"
                        ],
                        spec: [
                            storageClassName: "fast",
                            resources: [requests: [storage: "10Gi"]]
                        ],
                        status: [phase: "Pending"]
                    ]
                ]
            ]
        )

        when:
        def result = pvcApiHelper.getPVCs(true)

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_PVCS) >> mockResponse

        result.success == true
        result.namespacePVCMap.size() == 2
        result.namespacePVCMap["ns1:pvc-1"] != null
        result.namespacePVCMap["ns1:pvc-1"].uid == "pvc-uid-1"
        result.namespacePVCMap["ns1:pvc-1"].storageClass == "standard"
        result.namespacePVCMap["ns2:pvc-2"] != null
        result.namespacePVCMap["ns2:pvc-2"].uid == "pvc-uid-2"
        result.namespacePVCMap["ns2:pvc-2"].storageClass == "fast"
    }

    def "getPVCs should filter out system namespaces"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [uid: "pvc-1", name: "user-pvc", namespace: "my-app"],
                        spec: [storageClassName: "standard", resources: [requests: [storage: "1Gi"]]],
                        status: [phase: "Bound"]
                    ],
                    [
                        metadata: [uid: "pvc-2", name: "system-pvc", namespace: "kube-system"],
                        spec: [storageClassName: "standard", resources: [requests: [storage: "1Gi"]]],
                        status: [phase: "Bound"]
                    ],
                    [
                        metadata: [uid: "pvc-3", name: "harvester-pvc", namespace: "longhorn-system"],
                        spec: [storageClassName: "standard", resources: [requests: [storage: "1Gi"]]],
                        status: [phase: "Bound"]
                    ],
                    [
                        metadata: [uid: "pvc-4", name: "default-pvc", namespace: "default"],
                        spec: [storageClassName: "standard", resources: [requests: [storage: "1Gi"]]],
                        status: [phase: "Bound"]
                    ]
                ]
            ]
        )

        when:
        def result = pvcApiHelper.getPVCs(false)

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_PVCS) >> mockResponse

        result.success == true
        result.pvcs.size() == 2
        result.pvcs*.name.containsAll(["user-pvc", "default-pvc"])
        result.pvcs*.namespace.containsAll(["my-app", "default"])
    }

    def "getPVCs should handle missing storage class"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [uid: "pvc-1", name: "no-sc", namespace: "default"],
                        spec: [
                            resources: [requests: [storage: "5Gi"]]
                            // No storageClassName
                        ],
                        status: [phase: "Pending"]
                    ]
                ]
            ]
        )

        when:
        def result = pvcApiHelper.getPVCs(false)

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_PVCS) >> mockResponse

        result.success == true
        result.pvcs.size() == 1
        result.pvcs[0].storageClass == null
        result.pvcs[0].sizeBytes == 5368709120L
    }

    def "getPVCs should handle missing requested storage"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [uid: "pvc-1", name: "no-size", namespace: "default"],
                        spec: [
                            storageClassName: "standard"
                            // No resources.requests.storage
                        ],
                        status: [phase: "Pending"]
                    ]
                ]
            ]
        )

        when:
        def result = pvcApiHelper.getPVCs(false)

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_PVCS) >> mockResponse

        result.success == true
        result.pvcs.size() == 1
        result.pvcs[0].sizeBytes == 0L
    }

    def "getPVCs should handle missing status"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [uid: "pvc-1", name: "no-status", namespace: "default"],
                        spec: [
                            storageClassName: "standard",
                            resources: [requests: [storage: "10Gi"]]
                        ]
                        // No status
                    ]
                ]
            ]
        )

        when:
        def result = pvcApiHelper.getPVCs(false)

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_PVCS) >> mockResponse

        result.success == true
        result.pvcs.size() == 1
        result.pvcs[0].status == "Unknown"
    }

    def "getPVCs should handle ownerReferences"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            uid: "pvc-1",
                            name: "owned-pvc",
                            namespace: "default",
                            ownerReferences: [
                                [
                                    kind: "VirtualMachine",
                                    name: "test-vm",
                                    uid: "vm-uid-123",
                                    controller: true
                                ]
                            ]
                        ],
                        spec: [
                            storageClassName: "standard",
                            resources: [requests: [storage: "10Gi"]]
                        ],
                        status: [phase: "Bound"]
                    ]
                ]
            ]
        )

        when:
        def result = pvcApiHelper.getPVCs(false)

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_PVCS) >> mockResponse

        result.success == true
        result.pvcs[0].ownerReferences != null
        result.pvcs[0].ownerReferences[0].kind == "VirtualMachine"
        result.pvcs[0].ownerReferences[0].name == "test-vm"
    }

    def "getPVCs should handle dataSource"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [uid: "pvc-1", name: "cloned-pvc", namespace: "default"],
                        spec: [
                            storageClassName: "standard",
                            resources: [requests: [storage: "10Gi"]],
                            dataSource: [
                                kind: "PersistentVolumeClaim",
                                name: "source-pvc"
                            ]
                        ],
                        status: [phase: "Bound"]
                    ]
                ]
            ]
        )

        when:
        def result = pvcApiHelper.getPVCs(false)

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_PVCS) >> mockResponse

        result.success == true
        result.pvcs[0].dataSource != null
        result.pvcs[0].dataSource.kind == "PersistentVolumeClaim"
        result.pvcs[0].dataSource.name == "source-pvc"
    }

    def "getPVCs should handle empty items list"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [items: []]
        )

        when:
        def result = pvcApiHelper.getPVCs(false)

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_PVCS) >> mockResponse

        result.success == true
        result.pvcs.size() == 0
    }

    def "getPVCs should handle null items"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [:]
        )

        when:
        def result = pvcApiHelper.getPVCs(false)

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_PVCS) >> mockResponse

        result.success == true
        result.pvcs.size() == 0
    }

    def "getPVCs should handle API failure"() {
        given:
        def mockResponse = new ServiceResponse(
            success: false,
            msg: "Unauthorized",
            errorCode: "401"
        )

        when:
        def result = pvcApiHelper.getPVCs(false)

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_PVCS) >> mockResponse

        result.success == false
        result.message == "Unauthorized"
    }

    def "getPVCs should handle API failure with null message"() {
        given:
        def mockResponse = new ServiceResponse(
            success: false,
            errorCode: "500"
        )

        when:
        def result = pvcApiHelper.getPVCs(false)

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_PVCS) >> mockResponse

        result.success == false
        result.message == "Failed to fetch PVCs from Harvester"
    }

    def "getPVCs should handle exception"() {
        when:
        def result = pvcApiHelper.getPVCs(false)

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_PVCS) >> {
            throw new RuntimeException("Network timeout")
        }

        result.success == false
        result.message.contains("Network timeout")
    }

    @Unroll
    def "getPVCs should parse various storage sizes: #storageSize = #expectedBytes bytes"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [uid: "pvc-1", name: "test-pvc", namespace: "default"],
                        spec: [
                            storageClassName: "standard",
                            resources: [requests: [storage: storageSize]]
                        ],
                        status: [phase: "Bound"]
                    ]
                ]
            ]
        )

        when:
        def result = pvcApiHelper.getPVCs(false)

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_PVCS) >> mockResponse

        result.success == true
        result.pvcs[0].sizeBytes == expectedBytes

        where:
        storageSize | expectedBytes
        "1Gi"       | 1073741824L
        "5Gi"       | 5368709120L
        "10Gi"      | 10737418240L
        "50Gi"      | 53687091200L
        "100Gi"     | 107374182400L
        "1Ti"       | 1099511627776L
    }

    @Unroll
    def "getPVCs should handle different status phases: #phase"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [uid: "pvc-1", name: "test-pvc", namespace: "default"],
                        spec: [
                            storageClassName: "standard",
                            resources: [requests: [storage: "10Gi"]]
                        ],
                        status: [phase: phase]
                    ]
                ]
            ]
        )

        when:
        def result = pvcApiHelper.getPVCs(false)

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_PVCS) >> mockResponse

        result.success == true
        result.pvcs[0].status == phase

        where:
        phase << ["Pending", "Bound", "Lost", "Failed"]
    }

    def "getPVCs should handle both map and list formats correctly"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [uid: "pvc-1", name: "pvc-a", namespace: "ns1"],
                        spec: [storageClassName: "standard", resources: [requests: [storage: "1Gi"]]],
                        status: [phase: "Bound"]
                    ],
                    [
                        metadata: [uid: "pvc-2", name: "pvc-b", namespace: "ns2"],
                        spec: [storageClassName: "fast", resources: [requests: [storage: "2Gi"]]],
                        status: [phase: "Bound"]
                    ]
                ]
            ]
        )

        when:
        def listResult = pvcApiHelper.getPVCs(false)
        def mapResult = pvcApiHelper.getPVCs(true)

        then:
        2 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_PVCS) >> mockResponse

        listResult.success == true
        listResult.pvcs.size() == 2
        listResult.pvcs[0].name == "pvc-a"
        listResult.pvcs[1].name == "pvc-b"

        mapResult.success == true
        mapResult.namespacePVCMap.size() == 2
        mapResult.namespacePVCMap["ns1:pvc-a"].name == "pvc-a"
        mapResult.namespacePVCMap["ns2:pvc-b"].name == "pvc-b"
    }

    def "getPVCs should handle null response data"() {
        given:
        def mockResponse = new ServiceResponse(success: true, data: null)

        when:
        def result = pvcApiHelper.getPVCs(false)

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_PVCS) >> mockResponse

        result.success == true
        result.pvcs.size() == 0
    }
}
