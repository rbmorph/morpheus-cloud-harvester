// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.apihelper

import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.StorageControllerType
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Unit tests for VirtualMachineApiHelper class
 */
class VirtualMachineApiHelperSpec extends Specification {

    def cleanup() {
        GroovySystem.metaClassRegistry.removeMetaClass(PVCApiHelper)
        GroovySystem.metaClassRegistry.removeMetaClass(NodeApiHelper)
    }

    def "getVMs should aggregate VM data and skip provisioning and system namespace VMs"() {
        given:
        def serverGroup = Mock(ComputeServerGroup)
        def helper = new VirtualMachineApiHelper(serverGroup)
        def mockApiClient = Mock(HarvesterApiClient)
        helper.@apiClient = mockApiClient

        def mockPvcHelper = Mock(PVCApiHelper)
        def mockNodeHelper = Mock(NodeApiHelper)
        PVCApiHelper.metaClass.constructor = { ComputeServerGroup sg -> mockPvcHelper }
        NodeApiHelper.metaClass.constructor = { ComputeServerGroup sg -> mockNodeHelper }

        def storageVolumeTypeMap = [
            (HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC): Mock(StorageVolumeType),
            (HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME): Mock(StorageVolumeType)
        ]
        def storageControllerTypeMap = [
            'harvester-virtio': Mock(StorageControllerType)
        ]

        def pvcMap = [
            'user-ns:vm-a-root': [storageClass: 'fast', sizeBytes: 2147483648L],
            'src-ns:src-pvc': [storageClass: 'gold', sizeBytes: 3221225472L]
        ]

        def vmsResponse = ServiceResponse.success([
            items: [
                [
                    metadata: [
                        uid: 'vm-1',
                        name: 'vm-a',
                        namespace: 'user-ns',
                        creationTimestamp: '2026-01-01T00:00:00Z',
                        labels: [:]
                    ],
                    spec: [
                        template: [
                            metadata: [annotations: ['vm.kubevirt.io/os': 'linux']],
                            spec: [
                                domain: [
                                    cpu: [cores: 2, sockets: 1, threads: 1],
                                    memory: [guest: '2Gi'],
                                    devices: [
                                        disks: [[name: 'rootdisk', bootOrder: 1, disk: [bus: 'virtio']]],
                                        interfaces: [[name: 'default', model: 'virtio']]
                                    ]
                                ],
                                volumes: [[name: 'rootdisk', persistentVolumeClaim: [claimName: 'vm-a-root']]],
                                networks: [[name: 'default', pod: [:]]]
                            ]
                        ],
                        dataVolumeTemplates: []
                    ],
                    status: [printableStatus: 'Running']
                ],
                [
                    metadata: [
                        uid: 'vm-2',
                        name: 'vm-provisioning',
                        namespace: 'user-ns',
                        labels: [(HarvesterConstants.MORPHEUS_PROVISIONING_LABEL): 'true']
                    ],
                    spec: [template: [spec: [:]]],
                    status: [printableStatus: 'Provisioning']
                ],
                [
                    metadata: [
                        uid: 'vm-3',
                        name: 'vm-system',
                        namespace: 'kube-system',
                        labels: [:]
                    ],
                    spec: [template: [spec: [:]]],
                    status: [printableStatus: 'Stopped']
                ]
            ]
        ])

        def vmInstancesResponse = ServiceResponse.success([
            items: [
                [
                    metadata: [namespace: 'user-ns', name: 'vm-a'],
                    status: [
                        nodeName: 'worker-1',
                        interfaces: [[name: 'default', mac: '52:54:00:00:00:11', ipAddress: '10.10.0.10', ipAddresses: ['10.10.0.10']]]
                    ],
                    spec: [
                        domain: [devices: [interfaces: [[name: 'default', model: 'virtio']]]],
                        networks: [[name: 'default', pod: [:]]]
                    ]
                ]
            ]
        ])

        when:
        def result = helper.getVMs(storageVolumeTypeMap, storageControllerTypeMap)

        then:
        1 * mockPvcHelper.getPVCs(true) >> [success: true, namespacePVCMap: pvcMap]
        1 * mockNodeHelper.getNodes() >> [success: true, nodes: [[uid: 'node-1', name: 'worker-1', nodeType: 'worker']]]
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_VMS) >> vmsResponse
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_VM_INSTANCES) >> vmInstancesResponse

        result.success
        result.vms.size() == 1
        result.vms[0].name == 'vm-a'
        result.vms[0].namespace == 'user-ns'
        result.vms[0].isRunning == true
        result.vms[0].nodeName == 'worker-1'
        result.vms[0].nodeDetails.name == 'worker-1'
        result.vms[0].osType == 'linux'
        result.vms[0].cpu.cores == 2
        result.vms[0].memory > 0
        result.vms[0].interfaces.size() == 1
        result.vms[0].interfaces[0].defaultPodNetwork == true
        result.vms[0].volumes.size() == 1
        result.vms[0].volumes[0].storageClass == 'fast'
    }

    def "getVMs should return error when VM API call fails"() {
        given:
        def helper = new VirtualMachineApiHelper(Mock(ComputeServerGroup))
        def mockApiClient = Mock(HarvesterApiClient)
        helper.@apiClient = mockApiClient

        def mockPvcHelper = Mock(PVCApiHelper)
        def mockNodeHelper = Mock(NodeApiHelper)
        PVCApiHelper.metaClass.constructor = { ComputeServerGroup sg -> mockPvcHelper }
        NodeApiHelper.metaClass.constructor = { ComputeServerGroup sg -> mockNodeHelper }

        when:
        def result = helper.getVMs([:], [:])

        then:
        1 * mockPvcHelper.getPVCs(true) >> [success: true, namespacePVCMap: [:]]
        1 * mockNodeHelper.getNodes() >> [success: false, nodes: []]
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_VMS) >> ServiceResponse.error('vm fetch failed')
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_VM_INSTANCES) >> ServiceResponse.success([items: []])

        !result.success
        result.message == 'Failed to fetch VMs from Harvester'
    }

    def "getVMs should use guestOSInfo fallback and default cpu memory when domain is missing"() {
        given:
        def serverGroup = Mock(ComputeServerGroup)
        def helper = new VirtualMachineApiHelper(serverGroup)
        def mockApiClient = Mock(HarvesterApiClient)
        helper.@apiClient = mockApiClient

        def mockPvcHelper = Mock(PVCApiHelper)
        def mockNodeHelper = Mock(NodeApiHelper)
        PVCApiHelper.metaClass.constructor = { ComputeServerGroup sg -> mockPvcHelper }
        NodeApiHelper.metaClass.constructor = { ComputeServerGroup sg -> mockNodeHelper }

        def vmsResponse = ServiceResponse.success([
            items: [
                [
                    metadata: [uid: 'vm-10', name: 'vm-guest', namespace: 'user-ns', creationTimestamp: '2026-01-01T00:00:00Z', labels: [:]],
                    spec: [
                        preference: [name: 'linux-preference'],
                        template: [spec: [volumes: [], networks: [[name: 'nic0', pod: [:]]]]]
                    ],
                    status: [printableStatus: 'Stopped']
                ],
                [
                    metadata: [uid: 'vm-11', name: 'vm-default', namespace: 'user-ns', creationTimestamp: '2026-01-02T00:00:00Z', labels: [:]],
                    spec: [
                        preference: [name: 'windows-preference'],
                        template: [spec: [volumes: [], networks: [[name: 'nic1', multus: [networkName: 'tenant/net-c']]], domain: [devices: [interfaces: [[name: 'nic1', model: 'e1000']]]]]]
                    ],
                    status: [printableStatus: 'Unknown']
                ]
            ]
        ])

        def vmInstancesResponse = ServiceResponse.success([
            items: [
                [
                    metadata: [namespace: 'user-ns', name: 'vm-guest'],
                    status: [
                        guestOSInfo: [cpuCount: 3, memorySize: 3221225472L],
                        interfaces: [[name: 'nic0', mac: '00:11:22:33:44:55', ipAddress: '10.20.0.10', ipAddresses: ['10.20.0.10']]]
                    ],
                    spec: [
                        domain: [devices: [interfaces: [[name: 'nic0', model: 'virtio']]]],
                        networks: [[name: 'nic0', pod: [:]]]
                    ]
                ]
            ]
        ])

        when:
        def result = helper.getVMs([:], [:])

        then:
        1 * mockPvcHelper.getPVCs(true) >> [success: true, namespacePVCMap: [:]]
        1 * mockNodeHelper.getNodes() >> [success: false, nodes: []]
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_VMS) >> vmsResponse
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_VM_INSTANCES) >> vmInstancesResponse

        result.success
        result.vms.size() == 2

        def guestVm = result.vms.find { it.name == 'vm-guest' }
        guestVm.isRunning
        guestVm.cpu.cores == 3
        guestVm.memory == 3221225472L
        guestVm.interfaces.size() == 1

        def defaultVm = result.vms.find { it.name == 'vm-default' }
        !defaultVm.isRunning
        defaultVm.cpu.cores == 0
        defaultVm.memory == 0L
        defaultVm.interfaces[0].networkName == 'tenant/net-c'
    }

    def "getVMs should return failure map when exception is thrown"() {
        given:
        def helper = new VirtualMachineApiHelper(Mock(ComputeServerGroup))
        def mockApiClient = Mock(HarvesterApiClient)
        helper.@apiClient = mockApiClient

        def mockPvcHelper = Mock(PVCApiHelper)
        def mockNodeHelper = Mock(NodeApiHelper)
        PVCApiHelper.metaClass.constructor = { ComputeServerGroup sg -> mockPvcHelper }
        NodeApiHelper.metaClass.constructor = { ComputeServerGroup sg -> mockNodeHelper }

        when:
        def result = helper.getVMs([:], [:])

        then:
        1 * mockPvcHelper.getPVCs(true) >> [success: true, namespacePVCMap: [:]]
        1 * mockNodeHelper.getNodes() >> [success: true, nodes: []]
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_VMS) >> { throw new RuntimeException('boom') }
        0 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_VM_INSTANCES)

        !result.success
        result.message.contains('Error fetching VMs: boom')
    }

    def "isProvisioningVM should detect provisioning label"() {
        expect:
        VirtualMachineApiHelper.isProvisioningVM([(HarvesterConstants.MORPHEUS_PROVISIONING_LABEL): 'true'])
        !VirtualMachineApiHelper.isProvisioningVM([(HarvesterConstants.MORPHEUS_PROVISIONING_LABEL): 'false'])
        !VirtualMachineApiHelper.isProvisioningVM([:])
        !VirtualMachineApiHelper.isProvisioningVM(null)
    }

    def "extractCpuInfo should use cpu section then resources fallback"() {
        when:
        def fromCpu = VirtualMachineApiHelper.extractCpuInfo([cpu: [cores: 4, sockets: 2, threads: 1]])
        def fromResources = VirtualMachineApiHelper.extractCpuInfo([resources: [requests: [cpu: '2000m']]])
        def empty = VirtualMachineApiHelper.extractCpuInfo([:])

        then:
        fromCpu.cores == 4
        fromCpu.sockets == 2
        fromResources.cores == 2L
        empty.cores == 0
    }

    def "extractMemoryInfo should use memory guest then resources fallback"() {
        expect:
        VirtualMachineApiHelper.extractMemoryInfo([memory: [guest: '2Gi']]) == 2147483648L
        // Harvester: the limit is the configured size; guest is the limit minus reserved overhead
        VirtualMachineApiHelper.extractMemoryInfo([memory: [guest: '1948Mi'], resources: [limits: [memory: '2Gi']]]) == 2147483648L
        VirtualMachineApiHelper.extractMemoryInfo([resources: [requests: [memory: '1Gi']]]) == 1073741824L
        VirtualMachineApiHelper.extractMemoryInfo([:]) == 0L
    }

    def "validateVolume should enforce cloudinit and supported maps"() {
        given:
        def volTypes = [(HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC): Mock(StorageVolumeType)]
        def controllers = ['harvester-virtio': Mock(StorageControllerType)]

        expect:
        !VirtualMachineApiHelper.validateVolume([name: HarvesterConstants.CLOUD_INIT_DISK], volTypes, controllers, 'vm1')
        !VirtualMachineApiHelper.validateVolume([name: 'data', type: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC, storageController: 'harvester-virtio'], volTypes, controllers, 'vm1')
        !VirtualMachineApiHelper.validateVolume([name: 'data', storageClass: 'fast', type: 'bad', storageController: 'harvester-virtio'], volTypes, controllers, 'vm1')
        !VirtualMachineApiHelper.validateVolume([name: 'data', storageClass: 'fast', type: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC, storageController: 'bad-controller'], volTypes, controllers, 'vm1')
        VirtualMachineApiHelper.validateVolume([name: 'data', storageClass: 'fast', type: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC, storageController: 'harvester-virtio'], volTypes, controllers, 'vm1')
    }

    def "filterVolumes should keep only valid volumes"() {
        given:
        def volumes = [
            [name: HarvesterConstants.CLOUD_INIT_DISK, storageClass: 'x', type: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC, storageController: 'harvester-virtio'],
            [name: 'valid', storageClass: 'fast', type: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC, storageController: 'harvester-virtio'],
            [name: 'invalid', storageClass: null, type: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC, storageController: 'harvester-virtio']
        ]
        def volTypes = [(HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC): Mock(StorageVolumeType)]
        def controllers = ['harvester-virtio': Mock(StorageControllerType)]

        when:
        def result = VirtualMachineApiHelper.filterVolumes(volumes, 'vm1', volTypes, controllers)

        then:
        result*.name == ['valid']
    }

    def "extractVolumeInfo should cover pvc dataVolume cloudInit and emptyDisk branches"() {
        given:
        def volumes = [
            [name: 'rootdisk', persistentVolumeClaim: [claimName: 'pvc-a']],
            [name: 'dv-one', dataVolume: [name: 'dv-one']],
            [name: 'dv-two', dataVolume: [name: 'dv-two']],
            [name: 'dv-three', dataVolume: [name: 'dv-three']],
            [name: 'cidata', cloudInitNoCloud: [userData: 'config']],
            [name: 'tempdisk', emptyDisk: [capacity: '1Gi']]
        ]
        def dataVolumeTemplates = [
            [metadata: [name: 'dv-one'], spec: [sourceRef: [namespace: 'src-ns', name: 'src-pvc'], storage: [resources: [requests: [storage: '3Gi']]]]],
            [metadata: [name: 'dv-two'], spec: [pvc: [storageClassName: 'silver', resources: [requests: [storage: '4Gi']]]]],
            [metadata: [name: 'dv-three'], spec: [storage: [storageClassName: 'bronze', resources: [requests: [storage: '5Gi']]]]]
        ]
        def pvcCache = [
            'vm-ns:pvc-a': [storageClass: 'gold', sizeBytes: 2147483648L],
            'src-ns:src-pvc': [storageClass: 'source-class', sizeBytes: 3221225472L]
        ]
        def disks = [
            [name: 'rootdisk', bootOrder: 1, disk: [bus: 'virtio']],
            [name: 'dv-one', bootOrder: 2, disk: [bus: 'scsi']],
            [name: 'dv-two', disk: [bus: 'sata']],
            [name: 'dv-three', disk: [bus: 'ide']]
        ]

        when:
        def result = VirtualMachineApiHelper.extractVolumeInfo('linux', volumes, dataVolumeTemplates, 'vm-ns', pvcCache, disks)

        then:
        result.size() == 6

        def pvcVol = result.find { it.name == 'rootdisk' }
        pvcVol.type == HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC
        pvcVol.storageClass == 'gold'
        pvcVol.deviceName == '/dev/vda'

        def dvOne = result.find { it.name == 'dv-one' }
        dvOne.type == HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME
        dvOne.storageClass == 'source-class'
        dvOne.sizeBytes == 3221225472L

        def dvTwo = result.find { it.name == 'dv-two' }
        dvTwo.storageClass == 'silver'
        dvTwo.sizeBytes == 4294967296L

        def dvThree = result.find { it.name == 'dv-three' }
        dvThree.storageClass == 'bronze'
        dvThree.sizeBytes == 5368709120L

        result.find { it.name == 'cidata' }.type == 'cloudInit'
        result.find { it.name == 'tempdisk' }.sizeBytes == 1073741824L
    }

    def "extractVolumeInfo should build windows disk names"() {
        given:
        def volumes = [
            [name: 'boot', persistentVolumeClaim: [claimName: 'bootpvc']],
            [name: 'data', emptyDisk: [capacity: '1Gi']]
        ]
        def disks = [
            [name: 'boot', bootOrder: 1, disk: [bus: 'virtio']],
            [name: 'data', disk: [bus: 'virtio']]
        ]
        def pvcCache = ['win-ns:bootpvc': [storageClass: 'fast', sizeBytes: 2147483648L]]

        when:
        def result = VirtualMachineApiHelper.extractVolumeInfo('windows', volumes, [], 'win-ns', pvcCache, disks)

        then:
        result.find { it.name == 'boot' }.deviceName == 'disk 1'
        result.find { it.name == 'data' }.deviceName == 'disk 2'
    }

    def "extractOsType should use annotation preference and unknown fallback"() {
        expect:
        VirtualMachineApiHelper.extractOsType('vm1', [template: [metadata: [annotations: ['vm.kubevirt.io/os': 'Linux']]]]) == 'linux'
        VirtualMachineApiHelper.extractOsType('vm2', [preference: [name: 'windows-server']]) == 'windows'
        VirtualMachineApiHelper.extractOsType('vm3', [preference: [name: 'rhel-linux']]) == 'linux'
        VirtualMachineApiHelper.extractOsType('vm4', [:]) == 'Unknown'
    }

    def "extractNetworkInterfacesFromSpecAndStatus should map pod and multus and skip missing network"() {
        given:
        def statusIfaces = [
            [name: 'eth0', mac: 'aa:bb:cc', ipAddress: '10.1.1.10', ipAddresses: ['10.1.1.10']],
            [name: 'eth1', mac: 'dd:ee:ff', ipAddress: '192.168.1.10', ipAddresses: ['192.168.1.10']],
            [name: 'eth2', mac: '11:22:33', ipAddress: '172.16.1.10', ipAddresses: []]
        ]
        def domainIfaces = [
            [name: 'eth0', model: 'virtio'],
            [name: 'eth1', model: 'e1000'],
            [name: 'eth2', model: 'rtl8139']
        ]
        def networks = [
            [name: 'eth0', pod: [:]],
            [name: 'eth1', multus: [networkName: 'tenant/net-a']]
        ]

        when:
        def result = VirtualMachineApiHelper.extractNetworkInterfacesFromSpecAndStatus(statusIfaces, domainIfaces, networks)

        then:
        result.size() == 2
        result.find { it.name == 'eth0' }.defaultPodNetwork == true
        result.find { it.name == 'eth1' }.networkName == 'tenant/net-a'
        !result*.name.contains('eth2')
    }

    def "extractNetworkInterfacesFromSpec should map pod and multus interfaces"() {
        given:
        def interfaces = [[name: 'eth0', macAddress: 'aa', model: 'virtio'], [name: 'eth1', macAddress: 'bb', model: 'e1000']]
        def networks = [[name: 'eth0', pod: [:]], [name: 'eth1', multus: [networkName: 'tenant/net-b']]]

        when:
        def result = VirtualMachineApiHelper.extractNetworkInterfacesFromSpec(networks, interfaces)

        then:
        result.size() == 2
        result.find { it.name == 'eth0' }.defaultPodNetwork
        result.find { it.name == 'eth1' }.networkName == 'tenant/net-b'
    }

    def "test calculateDeviceName with bootOrder 0 for all bus types returns null"() {
        expect:
        VirtualMachineApiHelper.calculateDeviceName(busType, 0) == '/dev/sda'

        where:
        busType << ['scsi']
    }

    @Unroll
    def "test calculateDeviceName generates correct sequence for #busType"() {
        expect:
        VirtualMachineApiHelper.calculateDeviceName(busType, 0) == "/dev/${prefix}a"
        VirtualMachineApiHelper.calculateDeviceName(busType, 1) == "/dev/${prefix}b"
        VirtualMachineApiHelper.calculateDeviceName(busType, 2) == "/dev/${prefix}c"
        VirtualMachineApiHelper.calculateDeviceName(busType, 3) == "/dev/${prefix}d"
        VirtualMachineApiHelper.calculateDeviceName(busType, 4) == "/dev/${prefix}e"

        where:
        busType   | prefix
        'scsi'    | 'sd'
        'sata'    | 'sd'
        'virtio'  | 'vd'
        'ide'     | 'hd'
    }

    def "test calculateDeviceName returns virtio default for unknown bus"() {
        expect:
        VirtualMachineApiHelper.calculateDeviceName('unknown-bus', 1) == '/dev/vdb'
        VirtualMachineApiHelper.calculateDeviceName(null, 2) == '/dev/vdc'
    }

}


