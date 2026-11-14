// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.apihelper

import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Unit tests for DataVolumeApiHelper class
 */
class DataVolumeApiHelperSpec extends Specification {

    DataVolumeApiHelper dataVolumeApiHelper
    HarvesterApiClient mockApiClient
    ComputeServerGroup mockServerGroup

    def setup() {
        mockServerGroup = Mock(ComputeServerGroup)
        mockApiClient = Mock(HarvesterApiClient)
        dataVolumeApiHelper = new DataVolumeApiHelper(mockServerGroup)
        dataVolumeApiHelper.apiClient = mockApiClient
    }

    def "getDataVolumes should retrieve DataVolumes successfully"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            uid: "dv-uid-1",
                            name: "test-dv-1",
                            namespace: "default"
                        ],
                        spec: [
                            storage: [
                                storageClassName: "standard",
                                resources: [
                                    requests: [
                                        storage: "10Gi"
                                    ]
                                ]
                            ],
                            source: [
                                registry: [
                                    url: "docker://quay.io/kubevirt/fedora-cloud-container-disk-demo"
                                ]
                            ]
                        ],
                        status: [
                            phase: "Succeeded",
                            claimName: "test-dv-1"
                        ]
                    ],
                    [
                        metadata: [
                            uid: "dv-uid-2",
                            name: "test-dv-2",
                            namespace: "my-project",
                            ownerReferences: [
                                [kind: "VirtualMachine", name: "test-vm"]
                            ]
                        ],
                        spec: [
                            pvc: [
                                storageClassName: "fast-storage",
                                resources: [
                                    requests: [
                                        storage: "20Gi"
                                    ]
                                ]
                            ],
                            source: [
                                blank: [:]
                            ]
                        ],
                        status: [
                            phase: "WaitForFirstConsumer",
                            claimName: "test-dv-2"
                        ]
                    ]
                ]
            ]
        )

        when:
        def result = dataVolumeApiHelper.getDataVolumes()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_DATAVOLUMES) >> mockResponse

        result.success == true
        result.datavolumes.size() == 2
        result.datavolumes[0].uid == "dv-uid-1"
        result.datavolumes[0].name == "test-dv-1"
        result.datavolumes[0].namespace == "default"
        result.datavolumes[0].storageClass == "standard"
        result.datavolumes[0].sizeBytes == 10737418240L  // 10Gi in bytes
        result.datavolumes[0].status == "Succeeded"
        result.datavolumes[0].claimName == "test-dv-1"

        result.datavolumes[1].uid == "dv-uid-2"
        result.datavolumes[1].storageClass == "fast-storage"
        result.datavolumes[1].sizeBytes == 21474836480L  // 20Gi in bytes
    }

    def "getDataVolumes should handle spec.resources format"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            uid: "dv-uid-1",
                            name: "test-dv",
                            namespace: "default"
                        ],
                        spec: [
                            resources: [
                                storageClassName: "fast",
                                requests: [
                                    storage: "5Gi"
                                ]
                            ],
                            source: [blank: [:]]
                        ],
                        status: [phase: "Succeeded"]
                    ]
                ]
            ]
        )

        when:
        def result = dataVolumeApiHelper.getDataVolumes()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_DATAVOLUMES) >> mockResponse

        result.success == true
        result.datavolumes.size() == 1
        result.datavolumes[0].storageClass == "fast"
        result.datavolumes[0].sizeBytes == 5368709120L  // 5Gi in bytes
    }

    def "getDataVolumes should filter out system namespaces"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            uid: "dv-1",
                            name: "dv-user",
                            namespace: "my-app"
                        ],
                        spec: [
                            storage: [
                                storageClassName: "standard",
                                resources: [requests: [storage: "1Gi"]]
                            ]
                        ],
                        status: [phase: "Succeeded"]
                    ],
                    [
                        metadata: [
                            uid: "dv-2",
                            name: "dv-system",
                            namespace: "kube-system"
                        ],
                        spec: [
                            storage: [
                                storageClassName: "standard",
                                resources: [requests: [storage: "1Gi"]]
                            ]
                        ],
                        status: [phase: "Succeeded"]
                    ],
                    [
                        metadata: [
                            uid: "dv-3",
                            name: "dv-harvester",
                            namespace: "longhorn-system"
                        ],
                        spec: [
                            storage: [
                                storageClassName: "standard",
                                resources: [requests: [storage: "1Gi"]]
                            ]
                        ],
                        status: [phase: "Succeeded"]
                    ]
                ]
            ]
        )

        when:
        def result = dataVolumeApiHelper.getDataVolumes()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_DATAVOLUMES) >> mockResponse

        result.success == true
        result.datavolumes.size() == 1
        result.datavolumes[0].name == "dv-user"
        result.datavolumes[0].namespace == "my-app"
    }

    def "getDataVolumes should handle missing storage size"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            uid: "dv-1",
                            name: "dv-no-size",
                            namespace: "default"
                        ],
                        spec: [
                            storage: [
                                storageClassName: "standard"
                                // No resources.requests.storage
                            ]
                        ],
                        status: [phase: "Pending"]
                    ]
                ]
            ]
        )

        when:
        def result = dataVolumeApiHelper.getDataVolumes()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_DATAVOLUMES) >> mockResponse

        result.success == true
        result.datavolumes.size() == 1
        result.datavolumes[0].sizeBytes == 0L
    }

    def "getDataVolumes should handle missing storageClass"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            uid: "dv-1",
                            name: "dv-no-sc",
                            namespace: "default"
                        ],
                        spec: [
                            storage: [
                                resources: [
                                    requests: [storage: "5Gi"]
                                ]
                                // No storageClassName
                            ]
                        ],
                        status: [phase: "Pending"]
                    ]
                ]
            ]
        )

        when:
        def result = dataVolumeApiHelper.getDataVolumes()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_DATAVOLUMES) >> mockResponse

        result.success == true
        result.datavolumes.size() == 1
        result.datavolumes[0].storageClass == null
        result.datavolumes[0].sizeBytes == 5368709120L
    }

    def "getDataVolumes should handle missing status"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            uid: "dv-1",
                            name: "dv-no-status",
                            namespace: "default"
                        ],
                        spec: [
                            storage: [
                                storageClassName: "standard",
                                resources: [requests: [storage: "10Gi"]]
                            ]
                        ]
                        // No status
                    ]
                ]
            ]
        )

        when:
        def result = dataVolumeApiHelper.getDataVolumes()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_DATAVOLUMES) >> mockResponse

        result.success == true
        result.datavolumes.size() == 1
        result.datavolumes[0].status == "Unknown"
        result.datavolumes[0].claimName == null
    }

    def "getDataVolumes should handle ownerReferences"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            uid: "dv-1",
                            name: "vm-disk",
                            namespace: "default",
                            ownerReferences: [
                                [
                                    kind: "VirtualMachine",
                                    name: "test-vm",
                                    uid: "vm-uid-123"
                                ]
                            ]
                        ],
                        spec: [
                            storage: [
                                storageClassName: "standard",
                                resources: [requests: [storage: "10Gi"]]
                            ]
                        ],
                        status: [phase: "Succeeded"]
                    ]
                ]
            ]
        )

        when:
        def result = dataVolumeApiHelper.getDataVolumes()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_DATAVOLUMES) >> mockResponse

        result.success == true
        result.datavolumes[0].ownerReferences != null
        result.datavolumes[0].ownerReferences[0].kind == "VirtualMachine"
        result.datavolumes[0].ownerReferences[0].name == "test-vm"
    }

    def "getDataVolumes should handle dataSource"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            uid: "dv-1",
                            name: "cloned-dv",
                            namespace: "default"
                        ],
                        spec: [
                            storage: [
                                storageClassName: "standard",
                                resources: [requests: [storage: "10Gi"]]
                            ],
                            source: [
                                pvc: [
                                    namespace: "source-ns",
                                    name: "source-pvc"
                                ]
                            ]
                        ],
                        status: [phase: "CloneInProgress"]
                    ]
                ]
            ]
        )

        when:
        def result = dataVolumeApiHelper.getDataVolumes()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_DATAVOLUMES) >> mockResponse

        result.success == true
        result.datavolumes[0].dataSource != null
        result.datavolumes[0].dataSource.pvc.name == "source-pvc"
    }

    def "getDataVolumes should handle empty items list"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [items: []]
        )

        when:
        def result = dataVolumeApiHelper.getDataVolumes()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_DATAVOLUMES) >> mockResponse

        result.success == true
        result.datavolumes.size() == 0
    }

    def "getDataVolumes should handle null items"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [:]
        )

        when:
        def result = dataVolumeApiHelper.getDataVolumes()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_DATAVOLUMES) >> mockResponse

        result.success == true
        result.datavolumes.size() == 0
    }

    def "getDataVolumes should handle API failure"() {
        given:
        def mockResponse = new ServiceResponse(
            success: false,
            msg: "Unauthorized",
            errorCode: "401"
        )

        when:
        def result = dataVolumeApiHelper.getDataVolumes()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_DATAVOLUMES) >> mockResponse

        result.success == false
        result.message == "Unauthorized"
    }

    def "getDataVolumes should handle API failure with null message"() {
        given:
        def mockResponse = new ServiceResponse(
            success: false,
            errorCode: "500"
        )

        when:
        def result = dataVolumeApiHelper.getDataVolumes()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_DATAVOLUMES) >> mockResponse

        result.success == false
        result.message == "Failed to fetch DataVolumes from Harvester"
    }

    def "getDataVolumes should handle exception"() {
        when:
        def result = dataVolumeApiHelper.getDataVolumes()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_DATAVOLUMES) >> {
            throw new RuntimeException("Network timeout")
        }

        result.success == false
        result.message.contains("Network timeout")
    }

    @Unroll
    def "getDataVolumes should parse various storage sizes: #storageSize = #expectedBytes bytes"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            uid: "dv-1",
                            name: "test-dv",
                            namespace: "default"
                        ],
                        spec: [
                            storage: [
                                storageClassName: "standard",
                                resources: [
                                    requests: [storage: storageSize]
                                ]
                            ]
                        ],
                        status: [phase: "Succeeded"]
                    ]
                ]
            ]
        )

        when:
        def result = dataVolumeApiHelper.getDataVolumes()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_DATAVOLUMES) >> mockResponse

        result.success == true
        result.datavolumes[0].sizeBytes == expectedBytes

        where:
        storageSize | expectedBytes
        "1Gi"       | 1073741824L
        "5Gi"       | 5368709120L
        "10Gi"      | 10737418240L
        "100Gi"     | 107374182400L
        "1Ti"       | 1099511627776L
    }

    @Unroll
    def "getDataVolumes should handle different status phases: #phase"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            uid: "dv-1",
                            name: "test-dv",
                            namespace: "default"
                        ],
                        spec: [
                            storage: [
                                storageClassName: "standard",
                                resources: [requests: [storage: "10Gi"]]
                            ]
                        ],
                        status: [phase: phase]
                    ]
                ]
            ]
        )

        when:
        def result = dataVolumeApiHelper.getDataVolumes()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_DATAVOLUMES) >> mockResponse

        result.success == true
        result.datavolumes[0].status == phase

        where:
        phase << [
            "Pending",
            "PVCBound",
            "ImportScheduled",
            "ImportInProgress",
            "CloneScheduled",
            "CloneInProgress",
            "Succeeded",
            "Failed",
            "Unknown",
            "WaitForFirstConsumer"
        ]
    }

    def "getDataVolumes treats a missing CDI API (404) as nothing to sync"() {
        when:
        def result = dataVolumeApiHelper.getDataVolumes()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_DATAVOLUMES) >> new ServiceResponse(success: false, errorCode: '404', msg: 'Not Found')
        result.success == true
        result.datavolumes == []
    }

    def "isCdiNotInstalled only matches 404 responses"() {
        expect:
        DataVolumeApiHelper.isCdiNotInstalled(new ServiceResponse(success: false, errorCode: '404'))
        DataVolumeApiHelper.isCdiNotInstalled(new ServiceResponse(success: false, msg: 'HTTP 404 page not found'))
        !DataVolumeApiHelper.isCdiNotInstalled(new ServiceResponse(success: false, errorCode: '500', msg: 'boom'))
        !DataVolumeApiHelper.isCdiNotInstalled(null)
    }
}
