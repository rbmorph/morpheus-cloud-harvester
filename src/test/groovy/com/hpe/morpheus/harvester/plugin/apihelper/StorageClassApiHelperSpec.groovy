// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.apihelper

import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Unit tests for StorageClassApiHelper class
 */
class StorageClassApiHelperSpec extends Specification {

    StorageClassApiHelper storageClassApiHelper
    HarvesterApiClient mockApiClient
    ComputeServerGroup mockServerGroup

    def setup() {
        mockServerGroup = Mock(ComputeServerGroup)
        mockApiClient = Mock(HarvesterApiClient)
        storageClassApiHelper = new StorageClassApiHelper(mockServerGroup)
        storageClassApiHelper.apiClient = mockApiClient
    }

    def "getStorageClasses should retrieve storage classes successfully"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            name: "standard",
                            uid: "sc-uid-1",
                            creationTimestamp: "2025-01-01T00:00:00Z"
                        ],
                        provisioner: "driver.longhorn.io",
                        parameters: [
                            type: "gp2",
                            fsType: "ext4"
                        ],
                        reclaimPolicy: "Delete",
                        allowVolumeExpansion: true,
                        volumeBindingMode: "Immediate"
                    ],
                    [
                        metadata: [
                            name: "fast-storage",
                            uid: "sc-uid-2",
                            creationTimestamp: "2025-01-02T00:00:00Z"
                        ],
                        provisioner: "lvm.driver.harvesterhci.io",
                        parameters: [
                            type: "pd-ssd",
                            "replication-type": "regional-pd"
                        ],
                        reclaimPolicy: "Retain",
                        allowVolumeExpansion: true,
                        volumeBindingMode: "WaitForFirstConsumer"
                    ],
                    [
                        metadata: [
                            name: "nfs-storage",
                            uid: "sc-uid-3",
                            creationTimestamp: "2025-01-03T00:00:00Z"
                        ],
                        provisioner: "nfs.io/nfs",
                        reclaimPolicy: "Delete",
                        volumeBindingMode: "Immediate"
                    ]
                ]
            ]
        )

        when:
        def response = storageClassApiHelper.getStorageClasses()

        then:
        1 * mockApiClient.get('/apis/storage.k8s.io/v1/storageclasses') >> mockResponse

        response.success == true
        response.data.storageClasses.size() == 3

        // Verify first storage class
        response.data.storageClasses[0].name == "standard"
        response.data.storageClasses[0].uid == "sc-uid-1"
        response.data.storageClasses[0].provisioner == "driver.longhorn.io"
        response.data.storageClasses[0].parameters.type == "gp2"
        response.data.storageClasses[0].parameters.fsType == "ext4"
        response.data.storageClasses[0].reclaimPolicy == "Delete"
        response.data.storageClasses[0].allowVolumeExpansion == true
        response.data.storageClasses[0].volumeBindingMode == "Immediate"
        response.data.storageClasses[0].creationTimestamp == "2025-01-01T00:00:00Z"

        // Verify second storage class
        response.data.storageClasses[1].name == "fast-storage"
        response.data.storageClasses[1].provisioner == "lvm.driver.harvesterhci.io"
        response.data.storageClasses[1].reclaimPolicy == "Retain"
        response.data.storageClasses[1].volumeBindingMode == "WaitForFirstConsumer"

        // Verify third storage class (minimal parameters)
        response.data.storageClasses[2].name == "nfs-storage"
        response.data.storageClasses[2].parameters == [:]
        response.data.storageClasses[2].allowVolumeExpansion == false
    }

    def "getStorageClasses should handle missing allowVolumeExpansion"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [name: "basic-sc", uid: "sc-1", creationTimestamp: "2025-01-01T00:00:00Z"],
                        provisioner: "kubernetes.io/no-provisioner",
                        reclaimPolicy: "Delete",
                        volumeBindingMode: "Immediate"
                        // allowVolumeExpansion is missing
                    ]
                ]
            ]
        )

        when:
        def response = storageClassApiHelper.getStorageClasses()

        then:
        1 * mockApiClient.get('/apis/storage.k8s.io/v1/storageclasses') >> mockResponse

        response.success == true
        response.data.storageClasses.size() == 1
        response.data.storageClasses[0].allowVolumeExpansion == false
    }

    def "getStorageClasses should handle missing parameters"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [name: "no-params-sc", uid: "sc-1", creationTimestamp: "2025-01-01T00:00:00Z"],
                        provisioner: "kubernetes.io/no-provisioner",
                        reclaimPolicy: "Delete",
                        volumeBindingMode: "Immediate"
                        // parameters is missing
                    ]
                ]
            ]
        )

        when:
        def response = storageClassApiHelper.getStorageClasses()

        then:
        1 * mockApiClient.get('/apis/storage.k8s.io/v1/storageclasses') >> mockResponse

        response.success == true
        response.data.storageClasses[0].parameters == [:]
    }

    def "getStorageClasses should handle null parameters"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [name: "null-params-sc", uid: "sc-1", creationTimestamp: "2025-01-01T00:00:00Z"],
                        provisioner: "kubernetes.io/no-provisioner",
                        parameters: null,
                        reclaimPolicy: "Delete",
                        volumeBindingMode: "Immediate"
                    ]
                ]
            ]
        )

        when:
        def response = storageClassApiHelper.getStorageClasses()

        then:
        1 * mockApiClient.get('/apis/storage.k8s.io/v1/storageclasses') >> mockResponse

        response.success == true
        response.data.storageClasses[0].parameters == [:]
    }

    def "getStorageClasses should handle empty items list"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [items: []]
        )

        when:
        def response = storageClassApiHelper.getStorageClasses()

        then:
        1 * mockApiClient.get('/apis/storage.k8s.io/v1/storageclasses') >> mockResponse

        response.success == true
        response.data.storageClasses.size() == 0
    }

    def "getStorageClasses should handle null items"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [items: null] // Explicitly null items instead of missing key
        )

        when:
        def response = storageClassApiHelper.getStorageClasses()

        then:
        1 * mockApiClient.get('/apis/storage.k8s.io/v1/storageclasses') >> mockResponse

        response.success == true
        response.data?.storageClasses?.size() == 0
    }

    def "getStorageClasses should handle null response data"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: null
        )

        when:
        def response = storageClassApiHelper.getStorageClasses()

        then:
        1 * mockApiClient.get('/apis/storage.k8s.io/v1/storageclasses') >> mockResponse

        // When data is null, the original response is returned
        response.success == true
        response.data == null
    }

    def "getStorageClasses should handle API failure"() {
        given:
        def mockResponse = new ServiceResponse(
            success: false,
            msg: "Unauthorized"
        )

        when:
        def response = storageClassApiHelper.getStorageClasses()

        then:
        1 * mockApiClient.get('/apis/storage.k8s.io/v1/storageclasses') >> mockResponse

        response.success == false
        response.msg == "Unauthorized"
    }

    @Unroll
    def "getStorageClasses should handle different provisioners: #provisioner"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [name: "test-sc", uid: "sc-1", creationTimestamp: "2025-01-01T00:00:00Z"],
                        provisioner: provisioner,
                        reclaimPolicy: "Delete",
                        volumeBindingMode: "Immediate"
                    ]
                ]
            ]
        )

        when:
        def response = storageClassApiHelper.getStorageClasses()

        then:
        1 * mockApiClient.get('/apis/storage.k8s.io/v1/storageclasses') >> mockResponse

        response.success == true
        response.data.storageClasses[0].provisioner == provisioner

        where:
        provisioner << [
            "driver.longhorn.io",
            "lvm.driver.harvesterhci.io",
            "example.com/block",
            "example.com/file",
            "csi.example.org/disk",
            "kubernetes.io/no-provisioner",
            "nfs.io/nfs",
            "nfs.csi.k8s.io",
            "rook-ceph.rbd.csi.ceph.com",
            "rook-ceph.cephfs.csi.ceph.com"
        ]
    }

    @Unroll
    def "getStorageClasses should handle different reclaim policies: #reclaimPolicy"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [name: "test-sc", uid: "sc-1", creationTimestamp: "2025-01-01T00:00:00Z"],
                        provisioner: "kubernetes.io/no-provisioner",
                        reclaimPolicy: reclaimPolicy,
                        volumeBindingMode: "Immediate"
                    ]
                ]
            ]
        )

        when:
        def response = storageClassApiHelper.getStorageClasses()

        then:
        1 * mockApiClient.get('/apis/storage.k8s.io/v1/storageclasses') >> mockResponse

        response.success == true
        response.data.storageClasses[0].reclaimPolicy == reclaimPolicy

        where:
        reclaimPolicy << ["Delete", "Retain", "Recycle"]
    }

    @Unroll
    def "getStorageClasses should handle different volume binding modes: #bindingMode"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [name: "test-sc", uid: "sc-1", creationTimestamp: "2025-01-01T00:00:00Z"],
                        provisioner: "kubernetes.io/no-provisioner",
                        reclaimPolicy: "Delete",
                        volumeBindingMode: bindingMode
                    ]
                ]
            ]
        )

        when:
        def response = storageClassApiHelper.getStorageClasses()

        then:
        1 * mockApiClient.get('/apis/storage.k8s.io/v1/storageclasses') >> mockResponse

        response.success == true
        response.data.storageClasses[0].volumeBindingMode == bindingMode

        where:
        bindingMode << ["Immediate", "WaitForFirstConsumer"]
    }

    def "getStorageClasses should handle complex parameters"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [name: "complex-sc", uid: "sc-1", creationTimestamp: "2025-01-01T00:00:00Z"],
                        provisioner: "driver.longhorn.io",
                        parameters: [
                            type: "io1",
                            iopsPerGB: "10",
                            fsType: "ext4",
                            encrypted: "true",
                            kmsKeyId: "key-abc123"
                        ],
                        reclaimPolicy: "Delete",
                        allowVolumeExpansion: true,
                        volumeBindingMode: "Immediate"
                    ]
                ]
            ]
        )

        when:
        def response = storageClassApiHelper.getStorageClasses()

        then:
        1 * mockApiClient.get('/apis/storage.k8s.io/v1/storageclasses') >> mockResponse

        response.success == true
        response.data.storageClasses[0].parameters.size() == 5
        response.data.storageClasses[0].parameters.type == "io1"
        response.data.storageClasses[0].parameters.iopsPerGB == "10"
        response.data.storageClasses[0].parameters.encrypted == "true"
    }

    def "getAuthStatus should return auth info from API client"() {
        given:
        def expectedAuthInfo = [
            hasToken: true,
            expiresAt: new Date(),
            tokenType: "Bearer"
        ]

        when:
        def authStatus = storageClassApiHelper.getAuthStatus()

        then:
        1 * mockApiClient.getAuthInfo() >> expectedAuthInfo
        authStatus == expectedAuthInfo
    }

    def "refreshAuth should call API client refresh"() {
        when:
        storageClassApiHelper.refreshAuth()

        then:
        1 * mockApiClient.refreshAuth()
    }

    def "getStorageClasses should handle large number of storage classes"() {
        given:
        def storageClasses = (1..50).collect { i ->
            [
                metadata: [
                    name: "storage-class-${i}",
                    uid: "sc-uid-${i}",
                    creationTimestamp: "2025-01-01T00:00:00Z"
                ],
                provisioner: "kubernetes.io/provisioner-${i}",
                parameters: [key: "value-${i}"],
                reclaimPolicy: i % 2 == 0 ? "Delete" : "Retain",
                allowVolumeExpansion: i % 3 == 0,
                volumeBindingMode: i % 2 == 0 ? "Immediate" : "WaitForFirstConsumer"
            ]
        }
        def mockResponse = new ServiceResponse(
            success: true,
            data: [items: storageClasses]
        )

        when:
        def response = storageClassApiHelper.getStorageClasses()

        then:
        1 * mockApiClient.get('/apis/storage.k8s.io/v1/storageclasses') >> mockResponse

        response.success == true
        response.data.storageClasses.size() == 50
        response.data.storageClasses.every { it.name && it.provisioner }
    }

    // --- getDefaultStorageClass tests ---

    def "getDefaultStorageClass returns virt-default when available"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            name: "standard", uid: "sc-1", creationTimestamp: "2025-01-01T00:00:00Z",
                            annotations: [:]
                        ],
                        provisioner: "driver.longhorn.io",
                        reclaimPolicy: "Delete",
                        volumeBindingMode: "Immediate"
                    ],
                    [
                        metadata: [
                            name: "longhorn-virt",
                            uid: "sc-2",
                            creationTimestamp: "2025-01-01T00:00:00Z",
                            annotations: [
                                'storageclass.kubevirt.io/is-default-virt-class': 'true'
                            ]
                        ],
                        provisioner: "harvester-storage.rbd.csi.ceph.com",
                        reclaimPolicy: "Delete",
                        volumeBindingMode: "Immediate"
                    ]
                ]
            ]
        )

        when:
        def response = storageClassApiHelper.getDefaultStorageClass()

        then:
        1 * mockApiClient.get('/apis/storage.k8s.io/v1/storageclasses') >> mockResponse
        response.success == true
        response.data.storageClass.name == 'longhorn-virt'
        response.data.source == 'kubevirt-virt-default'
    }

    def "getDefaultStorageClass falls back to k8s default when no virt-default"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            name: "gp3-default",
                            uid: "sc-1",
                            creationTimestamp: "2025-01-01T00:00:00Z",
                            annotations: [
                                'storageclass.kubernetes.io/is-default-class': 'true'
                            ]
                        ],
                        provisioner: "driver.longhorn.io",
                        reclaimPolicy: "Delete",
                        volumeBindingMode: "WaitForFirstConsumer"
                    ]
                ]
            ]
        )

        when:
        def response = storageClassApiHelper.getDefaultStorageClass()

        then:
        1 * mockApiClient.get('/apis/storage.k8s.io/v1/storageclasses') >> mockResponse
        response.success == true
        response.data.storageClass.name == 'gp3-default'
        response.data.source == 'kubernetes-default'
    }

    def "getDefaultStorageClass returns failure when no default found"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            name: "non-default-sc",
                            uid: "sc-1",
                            creationTimestamp: "2025-01-01T00:00:00Z",
                            annotations: [:]
                        ],
                        provisioner: "k8s.io/no-provisioner",
                        reclaimPolicy: "Delete",
                        volumeBindingMode: "Immediate"
                    ]
                ]
            ]
        )

        when:
        def response = storageClassApiHelper.getDefaultStorageClass()

        then:
        1 * mockApiClient.get('/apis/storage.k8s.io/v1/storageclasses') >> mockResponse
        response.success == false
        response.msg.contains('No default storage class found')
    }

    def "getDefaultStorageClass returns failure when getStorageClasses fails"() {
        given:
        def mockResponse = new ServiceResponse(success: false, msg: 'Unauthorized')

        when:
        def response = storageClassApiHelper.getDefaultStorageClass()

        then:
        1 * mockApiClient.get('/apis/storage.k8s.io/v1/storageclasses') >> mockResponse
        response.success == false
        response.msg == 'Unauthorized'
    }

    def "getDefaultStorageClass prefers virt-default over k8s default"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            name: "k8s-default",
                            uid: "sc-1",
                            creationTimestamp: "2025-01-01T00:00:00Z",
                            annotations: [
                                'storageclass.kubernetes.io/is-default-class': 'true'
                            ]
                        ],
                        provisioner: "driver.longhorn.io",
                        reclaimPolicy: "Delete",
                        volumeBindingMode: "Immediate"
                    ],
                    [
                        metadata: [
                            name: "virt-default",
                            uid: "sc-2",
                            creationTimestamp: "2025-01-01T00:00:00Z",
                            annotations: [
                                'storageclass.kubevirt.io/is-default-virt-class': 'true'
                            ]
                        ],
                        provisioner: "rook-ceph.rbd.csi.ceph.com",
                        reclaimPolicy: "Delete",
                        volumeBindingMode: "Immediate"
                    ]
                ]
            ]
        )

        when:
        def response = storageClassApiHelper.getDefaultStorageClass()

        then:
        1 * mockApiClient.get('/apis/storage.k8s.io/v1/storageclasses') >> mockResponse
        response.success == true
        response.data.storageClass.name == 'virt-default'
        response.data.source == 'kubevirt-virt-default'
    }

    def "getStorageClasses skips Harvester per-image storage classes"() {
        given:
        def mockResponse = new ServiceResponse(success: true, data: [items: [
                [metadata: [name: 'harvester-longhorn', uid: 'sc-1'], provisioner: 'driver.longhorn.io', parameters: [numberOfReplicas: '3']],
                [metadata: [name: 'longhorn-image-abc12', uid: 'sc-2'], provisioner: 'driver.longhorn.io', parameters: [backingImage: 'default-image-abc12']],
                [metadata: [name: 'custom-image-class', uid: 'sc-3'], provisioner: 'driver.longhorn.io', parameters: [backingImage: 'x']]
        ]])

        when:
        def result = storageClassApiHelper.getStorageClasses()

        then:
        1 * mockApiClient.get('/apis/storage.k8s.io/v1/storageclasses') >> mockResponse
        result.success
        result.data.storageClasses*.name == ['harvester-longhorn']
    }

    def "isImageStorageClass detects backing-image and longhorn-image classes"() {
        expect:
        StorageClassApiHelper.isImageStorageClass([metadata: [name: 'longhorn-image-x'], parameters: [:]])
        StorageClassApiHelper.isImageStorageClass([metadata: [name: 'other'], parameters: [backingImage: 'b']])
        !StorageClassApiHelper.isImageStorageClass([metadata: [name: 'harvester-longhorn'], parameters: [:]])
        !StorageClassApiHelper.isImageStorageClass(null)
    }

    // --- storage classes VMs can be deployed to ---

    def "getVmStorageProvisioners adds the drivers from csi-driver-config"() {
        given:
        mockApiClient.get('/apis/harvesterhci.io/v1beta1/settings/csi-driver-config') >> ServiceResponse.success([
                value: '{"driver.longhorn.io":{"volumeSnapshotClassName":"longhorn-snapshot"},"rbd.csi.ceph.com":{"volumeSnapshotClassName":"ceph"}}'
        ])

        expect:
        storageClassApiHelper.getVmStorageProvisioners() == ['driver.longhorn.io', 'rbd.csi.ceph.com'] as Set
    }

    def "getVmStorageProvisioners uses the default value when no value is set"() {
        given:
        mockApiClient.get(_) >> ServiceResponse.success([value: '', default: '{"driver.longhorn.io":{}}'])

        expect:
        storageClassApiHelper.getVmStorageProvisioners() == ['driver.longhorn.io'] as Set
    }

    @Unroll
    def "getVmStorageProvisioners falls back to Longhorn when the setting #desc"() {
        given:
        mockApiClient.get(_) >> response

        expect:
        storageClassApiHelper.getVmStorageProvisioners() == ['driver.longhorn.io'] as Set

        where:
        desc              | response
        "can't be read"   | new ServiceResponse(success: false, errorCode: '403')
        'is not JSON'     | ServiceResponse.success([value: 'not json'])
        'is missing'      | null
    }

    @Unroll
    def "isVmStorageClass(#name, #provisioner) == #expected"() {
        expect:
        StorageClassApiHelper.isVmStorageClass([name: name, provisioner: provisioner, parameters: params],
                ['driver.longhorn.io', 'rbd.csi.ceph.com']) == expected

        where:
        name                     | provisioner                | params                    || expected
        'harvester-longhorn'     | 'driver.longhorn.io'       | [:]                       || true
        'longhorn-ssd'           | 'driver.longhorn.io'       | [:]                       || true
        'ceph-block'             | 'rbd.csi.ceph.com'         | [:]                       || true
        'longhorn-static'        | 'driver.longhorn.io'       | [:]                       || false
        'longhorn-image-abc12'   | 'driver.longhorn.io'       | [:]                       || false
        'custom-image-class'     | 'driver.longhorn.io'       | [backingImage: 'img']     || false
        'local-path'             | 'rancher.io/local-path'    | [:]                       || false
        'nfs'                    | 'nfs.csi.k8s.io'           | [:]                       || false
    }

    def "isVmStorageClass rejects null"() {
        expect:
        !StorageClassApiHelper.isVmStorageClass(null, ['driver.longhorn.io'])
    }
}
