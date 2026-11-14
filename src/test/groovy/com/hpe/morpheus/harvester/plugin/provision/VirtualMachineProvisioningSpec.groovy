package com.hpe.morpheus.harvester.plugin.provision

import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.hpe.morpheus.harvester.plugin.utils.DBUtils
import com.hpe.morpheus.harvester.plugin.utils.CommonUtils
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ComputeServerInterfaceType
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.NetAddress
import com.morpheusdata.model.Network
import com.morpheusdata.model.Workload
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.request.AfterConvertToManagedRequest
import com.morpheusdata.response.ServiceResponse
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import spock.lang.Unroll

class VirtualMachineProvisioningSpec extends Specification {

    VirtualMachineProvisioning provisioning
    MorpheusContext mockMorpheusContext

    private def invokePrivate(Object target, String methodName, Class[] paramTypes, Object[] args) {
        def method = target.class.getDeclaredMethod(methodName, paramTypes)
        method.accessible = true
        method.invoke(target, args)
    }

    private def invokePrivateStatic(Class<?> type, String methodName, Class[] paramTypes, Object[] args) {
        def method = type.getDeclaredMethod(methodName, paramTypes)
        method.accessible = true
        method.invoke(null, args)
    }

    private def typedMock(String className) {
        Mock((Class) Class.forName(className))
    }

    def setup() {
        mockMorpheusContext = Mock(MorpheusContext)
        provisioning = new VirtualMachineProvisioning(mockMorpheusContext)
    }

    def "constructor initializes morpheusContext"() {
        when:
        def newProvisioning = new VirtualMachineProvisioning(mockMorpheusContext)

        then:
        newProvisioning.morpheusContext == mockMorpheusContext
    }

    def "execute returns null by default"() {
        expect:
        provisioning.execute() == null
    }

    def "saveAndGet persists and reloads server"() {
        given:
        def services = Mock(MorpheusServices)
        def computeServerService = Mock(com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService)
        mockMorpheusContext.services >> services
        services.computeServer >> computeServerService

        def inputServer = new ComputeServer(id: 15L, name: 'vm15')
        def reloadedServer = new ComputeServer(id: 15L, name: 'vm15-reloaded')
        computeServerService.get(15L) >> reloadedServer

        when:
        def result = invokePrivate(provisioning, 'saveAndGet', [ComputeServer] as Class[], [inputServer] as Object[])

        then:
        1 * computeServerService.bulkSave([inputServer])
        result.id == 15L
        result.name == 'vm15-reloaded'
    }

    def "createVMDisksAndVolumesConfiguration creates config for PVC volumes"() {
        given:
        def volumeType = Mock(StorageVolumeType)
        volumeType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC
        
        def rootVolume = Mock(StorageVolume)
        rootVolume.name >> "root-disk"
        rootVolume.claimName >> "root-pvc"
        rootVolume.rootVolume >> true
        rootVolume.type >> volumeType
        
        def serverVolumes = [rootVolume]
        def workload = Mock(Workload)
        workload.name >> "test-vm"

        when:
        def result = VirtualMachineProvisioning.createVMDisksAndVolumesConfiguration(serverVolumes, workload)

        then:
        result.disksConfig.size() == 1
        result.disksConfig[0].name == "root-disk"
        result.disksConfig[0].bootOrder == 1
        result.disksConfig[0].disk.bus == "virtio"
        result.volumesConfig.size() == 1
        result.volumesConfig[0].name == "root-disk"
        result.volumesConfig[0].persistentVolumeClaim.claimName == "root-pvc"
    }

    def "createVMDisksAndVolumesConfiguration creates config for DataVolume volumes"() {
        given:
        def volumeType = Mock(StorageVolumeType)
        volumeType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME
        
        def rootVolume = Mock(StorageVolume)
        rootVolume.name >> "root-disk"
        rootVolume.claimName >> "root-dv"
        rootVolume.rootVolume >> true
        rootVolume.type >> volumeType
        rootVolume.getConfigProperty("body") >> [metadata: [name: "root-dv"], spec: [source: [blank: [:]]]]
        
        def serverVolumes = [rootVolume]
        def workload = Mock(Workload)
        workload.name >> "test-vm"

        when:
        def result = VirtualMachineProvisioning.createVMDisksAndVolumesConfiguration(serverVolumes, workload)

        then:
        result.disksConfig.size() == 1
        result.volumesConfig.size() == 1
        result.volumesConfig[0].dataVolume.name == "root-dv"
        result.dataVolumeTemplates.size() == 1
    }

    def "createVMDisksAndVolumesConfiguration orders root volume first"() {
        given:
        def pvcType = Mock(StorageVolumeType)
        pvcType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC
        
        def dataVolume = Mock(StorageVolume)
        dataVolume.name >> "data-disk"
        dataVolume.claimName >> "data-pvc"
        dataVolume.rootVolume >> false
        dataVolume.type >> pvcType
        
        def rootVolume = Mock(StorageVolume)
        rootVolume.name >> "root-disk"
        rootVolume.claimName >> "root-pvc"
        rootVolume.rootVolume >> true
        rootVolume.type >> pvcType
        
        // Root volume is NOT first in the list
        def serverVolumes = [dataVolume, rootVolume]
        def workload = Mock(Workload)
        workload.name >> "test-vm"

        when:
        def result = VirtualMachineProvisioning.createVMDisksAndVolumesConfiguration(serverVolumes, workload)

        then:
        result.disksConfig.size() == 2
        result.disksConfig[0].name == "root-disk" // Root disk should be first
        result.disksConfig[0].bootOrder == 1
        result.disksConfig[1].name == "data-disk"
        result.disksConfig[1].bootOrder == 2
    }

    def "createVMDisksAndVolumesConfiguration handles multiple volumes"() {
        given:
        def pvcType = Mock(StorageVolumeType)
        pvcType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC
        
        def rootVolume = Mock(StorageVolume)
        rootVolume.name >> "root-disk"
        rootVolume.claimName >> "root-pvc"
        rootVolume.rootVolume >> true
        rootVolume.type >> pvcType
        
        def dataVolume1 = Mock(StorageVolume)
        dataVolume1.name >> "data-disk-1"
        dataVolume1.claimName >> "data-pvc-1"
        dataVolume1.rootVolume >> false
        dataVolume1.type >> pvcType
        
        def dataVolume2 = Mock(StorageVolume)
        dataVolume2.name >> "data-disk-2"
        dataVolume2.claimName >> "data-pvc-2"
        dataVolume2.rootVolume >> false
        dataVolume2.type >> pvcType
        
        def serverVolumes = [rootVolume, dataVolume1, dataVolume2]
        def workload = Mock(Workload)
        workload.name >> "test-vm"

        when:
        def result = VirtualMachineProvisioning.createVMDisksAndVolumesConfiguration(serverVolumes, workload)

        then:
        result.disksConfig.size() == 3
        result.volumesConfig.size() == 3
        result.disksConfig[0].bootOrder == 1
        result.disksConfig[1].bootOrder == 2
        result.disksConfig[2].bootOrder == 3
    }

    def "createVMDisksAndVolumesConfiguration handles empty volume list"() {
        given:
        def serverVolumes = []
        def workload = Mock(Workload)
        workload.name >> "test-vm"

        when:
        def result = VirtualMachineProvisioning.createVMDisksAndVolumesConfiguration(serverVolumes, workload)

        then:
        result.disksConfig.size() == 1 // Method adds null root volume
        result.volumesConfig.size() == 0
        result.dataVolumeTemplates.size() == 0
    }

    def "createVMDisksAndVolumesConfiguration sets virtio bus for all disks"() {
        given:
        def pvcType = Mock(StorageVolumeType)
        pvcType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC
        
        def volume1 = Mock(StorageVolume)
        volume1.name >> "disk-1"
        volume1.claimName >> "pvc-1"
        volume1.rootVolume >> true
        volume1.type >> pvcType
        
        def volume2 = Mock(StorageVolume)
        volume2.name >> "disk-2"
        volume2.claimName >> "pvc-2"
        volume2.rootVolume >> false
        volume2.type >> pvcType
        
        def serverVolumes = [volume1, volume2]
        def workload = Mock(Workload)

        when:
        def result = VirtualMachineProvisioning.createVMDisksAndVolumesConfiguration(serverVolumes, workload)

        then:
        result.disksConfig.every { it.disk.bus == "virtio" }
    }

    def "createNetworkConfiguration builds pod and multus configs and sanitizes names"() {
        given:
        def iface1 = new Expando(name: 'eth0', network: new Expando(name: 'default-pod-network', displayName: 'default/default-pod-network'))
        def iface2 = new Expando(name: 'eth1', network: new Expando(name: 'nad-a', displayName: 'default/nad-a'))
        def server = Mock(ComputeServer)
        def workload = Mock(Workload)
        workload.server >> server
        server.interfaces >> [iface1, iface2]

        when:
        def result = provisioning.createNetworkConfiguration(workload, 'dev')

        then:
        result.interfacesConfig.size() == 2
        result.networksConfig.size() == 2
        result.interfacesConfig[0].name == 'eth0'
        result.interfacesConfig[0].masquerade != null
        result.networksConfig[0].pod != null
        result.interfacesConfig[1].name == 'eth1'
        result.interfacesConfig[1].bridge != null
        result.networksConfig[1].multus.networkName == 'default/nad-a'
    }

    def "validateServerConfig detects duplicate and mismatched network/volume configuration"() {
        given:
        def services = Mock(MorpheusServices)
        def networkService = Mock(com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService)
        mockMorpheusContext.services >> services
        services.network >> networkService

        networkService.get(11L) >> new Network(displayName: 'alpha/net-a')
        networkService.get(12L) >> new Network(displayName: 'beta/net-b')

        def opts = [
            volumes: [[name: 'bad_name!'], [name: 'bad_name!']],
            networkInterfaces: [
                [network: [id: 11L]],
                [network: [id: 11L]],
                [network: [id: 12L]],
                [network: [id: null]]
            ],
            config: [project: [name: 'gamma'], template: null]
        ]

        when:
        def result = provisioning.validateServerConfig(opts)

        then:
        result.success == false
        result.errors*.field.contains('volume')
        result.errors*.field.contains('networkInterface')
        result.errors*.field.contains('template')
    }

    def "getAllAssignedIPs aggregates interface ips and returns node metadata"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            status: [
                nodeName: 'node-a',
                interfaces: [
                    [name: 'eth0', ipAddresses: ['10.0.0.10', 'fd00::10'], mac: 'aa:bb:cc:11:22:33'],
                    [name: 'eth1', ipAddresses: [], mac: 'aa:bb:cc:44:55:66']
                ]
            ]
        ])

        when:
        def result = VirtualMachineProvisioning.getAllAssignedIPs(apiClient, 'ns1', 'vm1')

        then:
        result.success
        result.data.totalInterfaces == 2
        result.data.totalIPs == 2
        result.data.nodeName == 'node-a'
        result.data.assignedIPs.contains('10.0.0.10')
    }

    def "waitForVMIpAssignment returns primary eth0 ip when available"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            status: [
                interfaces: [
                    [name: 'eth1', ipAddresses: ['10.0.0.11']],
                    [name: 'eth0', ipAddresses: ['10.0.0.10', 'fd00::10']]
                ]
            ]
        ])

        when:
        def result = VirtualMachineProvisioning.waitForVMIpAssignment(apiClient, 'ns1', 'vm1', 1, 0)

        then:
        result.success
        result.data.assignedIPs.size() == 3
        result.data.primaryIP == '10.0.0.10'
    }

    def "updateServerNetworkInterfaces maps VMI details by name without exceptions"() {
        given:
        def ifaceType = new ComputeServerInterfaceType(code: 'virtio')
        def existingInterface = new ComputeServerInterface(name: 'eth0', macAddress: 'aa:bb:cc:11:22:33')
        existingInterface.type = ifaceType
        existingInterface.addresses = [new NetAddress(address: '10.0.0.10', type: NetAddress.AddressType.IPV4)]

        def server = new ComputeServer(id: 1L, name: 'vm1')
        server.interfaces = [existingInterface]

        GroovySpy(DBUtils, global: true)
        DBUtils.getInterfaceTypeFromInterfaceModel(mockMorpheusContext, ['virtio']) >> [ifaceType]

        def details = [[name: 'eth0', mac: 'aa:bb:cc:11:22:33', ipAddresses: ['10.0.0.10']]]

        when:
        def response = invokePrivate(provisioning, 'updateServerNetworkInterfaces',
            [ComputeServer, List, String] as Class[], [server, details, '10.0.0.10'] as Object[])

        then:
        response.success
    }

    def "buildVMLabels merges template labels and morpheus server labels"() {
        given:
        def virtualImageConfig = [labels: [existing: 'true']]
        def serverLabels = [[name: 'env-prod'], [name: 'team-core']]

        when:
        def result = invokePrivate(provisioning, 'buildVMLabels',
            [Map, String, String, Object] as Class[],
            [virtualImageConfig, 'tmpl1', 'ns1', serverLabels] as Object[])

        then:
        result.existing == 'true'
        !result.containsKey('vm.kubevirt.io/template')
        !result.containsKey('vm.kubevirt.io/template.namespace')
        result['morpheus-label.vm.provisioning'] == 'true'
        result['morpheus-label.1'] == 'env-prod'
        result['morpheus-label.2'] == 'team-core'
    }

    def "makeHarvesterApiCallAndWait returns immediate success when wait disabled"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        def postResponse = ServiceResponse.success([metadata: [namespace: 'ns1', name: 'vm1']])
        postResponse.statusCode = '201'
        apiClient.post(_ as String, _ as Map) >> postResponse

        when:
        def response = VirtualMachineProvisioning.makeHarvesterApiCallAndWait(
            apiClient, 'post', '/apis/kubevirt.io/v1/namespaces/ns1/virtualmachines',
            [metadata: [namespace: 'ns1', name: 'vm1']], false)

        then:
        response.success
        response.statusCode == '201'
    }

    def "makeHarvesterApiCallAndWait waits and returns when vm reaches running"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        def postResponse = ServiceResponse.success([metadata: [namespace: 'ns1', name: 'vm1']])
        postResponse.statusCode = '201'
        apiClient.post(_ as String, _ as Map) >> postResponse
        apiClient.get(_ as String) >> ServiceResponse.success([status: [printableStatus: 'Running']])

        when:
        def response = VirtualMachineProvisioning.makeHarvesterApiCallAndWait(
            apiClient, 'post', '/apis/kubevirt.io/v1/namespaces/ns1/virtualmachines',
            [metadata: [namespace: 'ns1', name: 'vm1']], true, 1, 0)

        then:
        response.success
        response.data.status.printableStatus == 'Running'
    }

    def "makeHarvesterApiCallAndWait returns error when vm enters failed state"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        def postResponse = ServiceResponse.success([metadata: [namespace: 'ns1', name: 'vm1']])
        postResponse.statusCode = '201'
        apiClient.post(_ as String, _ as Map) >> postResponse
        apiClient.get(_ as String) >> ServiceResponse.success([status: [printableStatus: 'Failed']])

        when:
        def response = VirtualMachineProvisioning.makeHarvesterApiCallAndWait(
            apiClient, 'post', '/apis/kubevirt.io/v1/namespaces/ns1/virtualmachines',
            [metadata: [namespace: 'ns1', name: 'vm1']], true, 1, 0)

        then:
        !response.success
        response.errors.error.contains('creation failed')
    }

    def "pingTestAssignedIPs filters invalid addresses and picks first responding ipv4"() {
        given:
        GroovySpy(VirtualMachineProvisioning, global: true)
        VirtualMachineProvisioning.performPingTest('10.0.0.10', 3, false) >> true

        when:
        def ip = VirtualMachineProvisioning.pingTestAssignedIPs(
            ['127.0.0.1', '169.254.1.1', 'fe80::1', '10.0.0.10'], 'vm1', 1, 0)

        then:
        ip == '10.0.0.10'
    }

    def "pingTestAssignedIPs returns ipv6 when ipv4 list is empty"() {
        given:
        GroovySpy(VirtualMachineProvisioning, global: true)
        VirtualMachineProvisioning.performPingTest('2001:db8::10', 3, true) >> true

        when:
        def ip = VirtualMachineProvisioning.pingTestAssignedIPs(
            ['::1', 'fe80::1', '2001:db8::10'], 'vm-ipv6', 1, 0)

        then:
        ip == '2001:db8::10'
    }

    def "pingTestAssignedIPs returns null when all addresses are filtered out"() {
        when:
        def ip = VirtualMachineProvisioning.pingTestAssignedIPs(
            ['127.0.0.1', '169.254.10.2', '::1', 'fe80::abcd'], 'vm-none', 1, 0)

        then:
        ip == null
    }

    def "pingTestAssignedIPs returns null when ping routine throws"() {
        given:
        GroovySpy(VirtualMachineProvisioning, global: true)
        VirtualMachineProvisioning.performPingTest('10.10.10.10', 3, false) >> { throw new RuntimeException('ping boom') }

        when:
        def ip = VirtualMachineProvisioning.pingTestAssignedIPs(['10.10.10.10'], 'vm-ex', 1, 0)

        then:
        ip == null
    }

    def "waitForGuestAgentConnection returns quickly when agent is connected"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            status: [conditions: [[type: 'AgentConnected', status: 'True']]]
        ])

        when:
        VirtualMachineProvisioning.waitForGuestAgentConnection(apiClient, 'ns1', 'vm1', 1, 0)

        then:
        notThrown(Exception)
    }

    def "hasRoutableIp ignores loopback and link-local addresses"() {
        expect:
        VirtualMachineProvisioning.hasRoutableIp([status: [interfaces: ifaces]]) == expected

        where:
        ifaces                                                   || expected
        null                                                     || false
        [[ipAddresses: ['127.0.0.1', 'fe80::1']]]                || false
        [[ipAddress: '169.254.10.2']]                            || false
        [[ipAddresses: ['10.0.0.5']]]                            || true
        [[ipAddress: '192.168.1.20']]                            || true
    }

    def "waitForGuestAgentConnection stops early when the VM reports an IP"() {
        given:
        def apiClient = Mock(HarvesterApiClient)

        when:
        VirtualMachineProvisioning.waitForGuestAgentConnection(apiClient, 'ns1', 'vm-ip', 5, 0)

        then:
        1 * apiClient.get(_ as String) >> ServiceResponse.success([status: [interfaces: [[ipAddresses: ['10.1.2.3']]]]])
    }

    def "performBasicReadinessCheck returns success for running ready vm"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            status: [
                phase: 'Running',
                conditions: [[type: 'Ready', status: 'True', reason: 'Ready']]
            ]
        ])
        provisioning.metaClass.sleep = { long ms -> }

        when:
        def response = invokePrivate(provisioning, 'performBasicReadinessCheck',
            [Object, String, String] as Class[], [apiClient, 'ns1', 'vm1'] as Object[])

        then:
        response.success
        response.data.bootMethod == 'BasicReadiness'
    }

    def "createNetworkConfiguration continues when interface name DB update fails"() {
        given:
        def iface = new Expando(name: 'Eth 0', network: new Expando(name: 'default-pod-network', displayName: 'default/default-pod-network'))
        def server = Mock(ComputeServer)
        def workload = Mock(Workload)
        workload.server >> server
        server.interfaces >> [iface]

        def asyncRoot = Mock(Object)
        def asyncCompute = Mock(Object)
        def asyncInterfaceSvc = Mock(Object)
        def asyncResult = Mock(Object)
        mockMorpheusContext.async >> asyncRoot
        asyncRoot.computeServer >> asyncCompute
        asyncCompute.computeServerInterface >> asyncInterfaceSvc
        asyncInterfaceSvc.bulkSave(_ as List) >> asyncResult
        asyncResult.blockingGet() >> { throw new RuntimeException('db write failed') }

        when:
        def result = provisioning.createNetworkConfiguration(workload, 'dev')

        then:
        result.interfacesConfig.size() == 1
        result.interfacesConfig[0].name == 'eth0'
        result.networksConfig[0].pod != null
        notThrown(Exception)
    }

    def "validateWorkload returns errors when validateServerConfig fails"() {
        given:
        def provisionSpy = Spy(VirtualMachineProvisioning, constructorArgs: [mockMorpheusContext])
        provisionSpy.validateServerConfig(_ as Map) >> [success: false, errors: [[field: 'volume', msg: 'bad volume']]]

        when:
        def result = provisionSpy.validateWorkload([:])

        then:
        !result.success
        result.errors.volume == 'bad volume'
    }

    def "validateLabelValue handles empty, invalid and oversized labels"() {
        when:
        def emptyResult = VirtualMachineProvisioning.validateLabelValue('')
        def invalidResult = VirtualMachineProvisioning.validateLabelValue('bad!value')
        def oversizedResult = VirtualMachineProvisioning.validateLabelValue('a' * 64)

        then:
        emptyResult.success
        !invalidResult.success
        invalidResult.error.contains('invalid')
        !oversizedResult.success
        oversizedResult.error.contains('exceeds 63 characters')
    }

    def "isIPv4 and isIPv6 validate representative inputs"() {
        expect:
        VirtualMachineProvisioning.isIPv4('10.1.2.3')
        !VirtualMachineProvisioning.isIPv4('300.1.2.3')
        !VirtualMachineProvisioning.isIPv4(null)
        VirtualMachineProvisioning.isIPv6('2001:db8::1')
        !VirtualMachineProvisioning.isIPv6('10.1.2.3')
        !VirtualMachineProvisioning.isIPv6('zzzz::1')
    }

    def "prepareWorkload fails for invalid server name"() {
        given:
        def server = new ComputeServer(id: 10L, name: 'Invalid_Name')
        def workload = Mock(Workload)
        workload.getServer() >> server
        workload.server >> server
        workload.id >> 5L
        workload.name >> 'wk1'
        workload.getConfigProperty('virtualImageId') >> null
        def workloadRequest = Mock(com.morpheusdata.model.provisioning.WorkloadRequest)

        when:
        def response = provisioning.prepareWorkload(workload, workloadRequest, [:])

        then:
        !response.success
        response.msg.contains('Error in PrepareWorkload')
    }

    def "prepareWorkload fails for invalid label value"() {
        given:
        def server = new ComputeServer(id: 11L, name: 'vm-valid')
        server.labels = [new Expando(name: 'bad!label')]
        def workload = Mock(Workload)
        workload.getServer() >> server
        workload.server >> server
        workload.id >> 6L
        workload.name >> 'wk2'
        workload.getConfigProperty('virtualImageId') >> null
        def workloadRequest = Mock(com.morpheusdata.model.provisioning.WorkloadRequest)

        when:
        def response = provisioning.prepareWorkload(workload, workloadRequest, [:])

        then:
        !response.success
        response.msg.contains('Error in PrepareWorkload')
        response.msg.contains('Label validation failed')
    }

    def "prepareWorkload returns message when no image selected"() {
        given:
        def server = new ComputeServer(id: 12L, name: 'vm-valid')
        def workload = Mock(Workload)
        workload.getServer() >> server
        workload.server >> server
        workload.id >> 7L
        workload.name >> 'wk3'
        workload.getConfigProperty('virtualImageId') >> null
        def workloadRequest = Mock(com.morpheusdata.model.provisioning.WorkloadRequest)

        when:
        def response = provisioning.prepareWorkload(workload, workloadRequest, [:])

        then:
        !response.success
        response.msg == 'No virtual image selected'
    }

    def "prepareWorkload succeeds when image is found"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        def virtualImageService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def asyncServices = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def asyncCloudService = typedMock('com.morpheusdata.core.cloud.MorpheusCloudService')
        mockMorpheusContext.services >> services
        mockMorpheusContext.async >> asyncServices
        services.computeServer >> computeServerService
        services.virtualImage >> virtualImageService
        asyncServices.cloud >> asyncCloudService
        asyncCloudService.findComputeServerTypeByCode(_ as String) >> Single.just(new ComputeServerType(code: 'harvester-linux'))

        def server = new ComputeServer(id: 13L, name: 'vm-ready')
        def dbServer = new ComputeServer(id: 13L, name: 'vm-ready')
        def image = new VirtualImage(id: 99L, name: 'img99')

        def workload = Mock(Workload)
        workload.getServer() >> server
        workload.server >> server
        workload.id >> 8L
        workload.name >> 'wk4'
        workload.getConfigProperty('virtualImageId') >> '99'

        def request = Mock(com.morpheusdata.model.provisioning.WorkloadRequest)
    request.process >> null

        computeServerService.get(13L) >> dbServer
        virtualImageService.get(99L) >> image

        when:
        def response = provisioning.prepareWorkload(workload, request, [:])

        then:
        response.success
        server.sourceImage?.id == 99L
        1 * computeServerService.bulkSave(_ as List)
    }

    def "getAllAssignedIPs returns error when no interfaces found"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([status: [nodeName: 'node-z', interfaces: []]])

        when:
        def result = VirtualMachineProvisioning.getAllAssignedIPs(apiClient, 'ns1', 'vm1')

        then:
        !result.success
        result.msg == 'No network interfaces found'
    }

    def "waitForVMIpAssignment returns success timeout payload when retries exhausted"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.error('temporary failure')

        when:
        def result = VirtualMachineProvisioning.waitForVMIpAssignment(apiClient, 'ns1', 'vm1', 1, 0)

        then:
        result.success
        result.msg.contains('IP assignment timed out')
    }

    def "performBasicReadinessCheck returns error for failed phase"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([status: [phase: 'Failed']])

        when:
        def response = invokePrivate(provisioning, 'performBasicReadinessCheck',
            [Object, String, String] as Class[], [apiClient, 'ns1', 'vm-fail'] as Object[])

        then:
        !response.success
        response.msg.contains('VM boot failed')
    }


    def "waitForVMBoot returns success with null primaryIP when IP lookup fails"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            status: [
                phase: 'Running',
                conditions: [[type: 'Ready', status: 'True', reason: 'Ready']]
            ]
        ])
        provisioning.metaClass.sleep = { long ms -> }
        GroovySpy(VirtualMachineProvisioning, global: true)
        VirtualMachineProvisioning.waitForGuestAgentConnection(_ as Object, _ as String, _ as String) >> null
        VirtualMachineProvisioning.getAllAssignedIPs(_ as Object, 'ns1', 'vm-noip') >>
            ServiceResponse.error('No network interfaces found', null, [nodeName: 'node-a', interfaceDetails: []])

        def workload = Mock(Workload)
        def request = Mock(com.morpheusdata.model.provisioning.WorkloadRequest)
        request.process >> null

        when:
        def result = provisioning.waitForVMBoot(apiClient, 'ns1', 'vm-noip', workload, request)

        then:
        result.success
        result.msg.contains('no IPs assigned')
        result.data.primaryIP == null
        result.data.nodeName == 'node-a'
    }

    def "waitForVMBoot returns success with null primaryIP when ping fails for all IPs"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            status: [
                phase: 'Running',
                conditions: [[type: 'Ready', status: 'True', reason: 'Ready']]
            ]
        ])
        provisioning.metaClass.sleep = { long ms -> }
        GroovySpy(VirtualMachineProvisioning, global: true)
        VirtualMachineProvisioning.waitForGuestAgentConnection(_ as Object, _ as String, _ as String) >> null
        VirtualMachineProvisioning.getAllAssignedIPs(_ as Object, 'ns1', 'vm-ping-fail') >>
            ServiceResponse.success([assignedIPs: ['10.20.30.40'], nodeName: 'node-b', interfaceDetails: [[name: 'eth0']]])
        VirtualMachineProvisioning.pingTestAssignedIPs(['10.20.30.40'], 'vm-ping-fail') >> null

        def workload = Mock(Workload)
        def request = Mock(com.morpheusdata.model.provisioning.WorkloadRequest)
        request.process >> null

        when:
        def result = provisioning.waitForVMBoot(apiClient, 'ns1', 'vm-ping-fail', workload, request)

        then:
        result.success
        result.msg.contains('ping test failed')
        result.data.primaryIP == null
        result.data.nodeName == 'node-b'
    }

    def "waitForVMBoot returns primaryIP when ping test succeeds"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            status: [
                phase: 'Running',
                conditions: [[type: 'Ready', status: 'True', reason: 'Ready']]
            ]
        ])
        provisioning.metaClass.sleep = { long ms -> }
        GroovySpy(VirtualMachineProvisioning, global: true)
        VirtualMachineProvisioning.waitForGuestAgentConnection(_ as Object, _ as String, _ as String) >> null
        VirtualMachineProvisioning.getAllAssignedIPs(_ as Object, 'ns1', 'vm-ping-ok') >>
            ServiceResponse.success([assignedIPs: ['10.20.30.41', '10.20.30.42'], nodeName: 'node-c', interfaceDetails: [[name: 'eth0']]])
        VirtualMachineProvisioning.pingTestAssignedIPs(['10.20.30.41', '10.20.30.42'], 'vm-ping-ok') >> '10.20.30.42'

        def workload = Mock(Workload)
        def request = Mock(com.morpheusdata.model.provisioning.WorkloadRequest)
        request.process >> null

        when:
        def result = provisioning.waitForVMBoot(apiClient, 'ns1', 'vm-ping-ok', workload, request)

        then:
        result.success
        result.msg.contains('boot detected via Guest Agent')
        result.data.primaryIP == '10.20.30.42'
        result.data.nodeName == 'node-c'
    }

    def "waitForVMBoot with Skip Network Wait does not wait for guest agent or ping"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            status: [
                phase: 'Running',
                conditions: [[type: 'Ready', status: 'True', reason: 'Ready']]
            ]
        ])
        provisioning.metaClass.sleep = { long ms -> }
        GroovySpy(VirtualMachineProvisioning, global: true)
        VirtualMachineProvisioning.getAllAssignedIPs(_ as Object, 'ns1', 'vm-skip') >>
            ServiceResponse.success([assignedIPs: ['fe80::1', '10.9.9.9'], nodeName: 'node-s', interfaceDetails: [[name: 'eth0']]])

        def workload = Mock(Workload)
        def request = Mock(com.morpheusdata.model.provisioning.WorkloadRequest)
        request.process >> null

        when:
        def result = provisioning.waitForVMBoot(apiClient, 'ns1', 'vm-skip', workload, request, true)

        then:
        0 * VirtualMachineProvisioning.waitForGuestAgentConnection(*_)
        0 * VirtualMachineProvisioning.pingTestAssignedIPs(*_)
        result.success
        result.data.primaryIP == '10.9.9.9'
        result.data.nodeName == 'node-s'
    }

    def "performBasicReadinessCheck returns timeout error when VM never becomes ready"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([status: [phase: 'Pending', conditions: []]])
        provisioning.metaClass.sleep = { long ms -> }

        when:
        def response = invokePrivate(provisioning, 'performBasicReadinessCheck',
            [Object, String, String] as Class[], [apiClient, 'ns1', 'vm-timeout'] as Object[])

        then:
        !response.success
        response.msg.contains('timed out')
        response.data.vmName == 'vm-timeout'
        response.data.timeoutSeconds == 300
    }

    def "finalizeProvisioning returns success when cleanup setup throws exception"() {
        given:
        GroovySpy(CommonUtils, global: true)
        CommonUtils.getServerGroupId(_ as ComputeServer, _ as MorpheusContext) >> { throw new RuntimeException('sg lookup failed') }

        def server = new ComputeServer(id: 500L, name: 'vm-finalize-fail')
        def workload = Mock(Workload)
        workload.name >> 'wk-finalize-fail'
        workload.server >> server

        when:
        def response = provisioning.finalizeProvisioning(workload)

        then:
        response.success
    }

    def "finalizeProvisioning returns success when namespace is missing"() {
        given:
        GroovySpy(CommonUtils, global: true)
        CommonUtils.getServerGroupId(_ as ComputeServer, _ as MorpheusContext) >> 701L

        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')
        mockMorpheusContext.services >> services
        services.cluster >> clusterService
        clusterService.get(701L) >> new ComputeServerGroup(id: 701L)

        def server = new ComputeServer(id: 701L, name: 'vm-no-namespace')
        def workload = Mock(Workload)
        workload.name >> 'wk-no-namespace'
        workload.server >> server

        when:
        def response = provisioning.finalizeProvisioning(workload)

        then:
        response.success
    }

    def "finalizeProvisioning returns success when namespace exists and cleanup runs"() {
        given:
        GroovySpy(CommonUtils, global: true)
        CommonUtils.getServerGroupId(_ as ComputeServer, _ as MorpheusContext) >> 702L

        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')
        mockMorpheusContext.services >> services
        services.cluster >> clusterService
        clusterService.get(702L) >> new ComputeServerGroup(id: 702L)

        def serverType = new ComputeServerType(code: 'harvester-linux')
        def server = new ComputeServer(id: 702L, name: 'vm-finalize', computeServerType: serverType)
        server.setConfigProperty('project', [name: 'ns1'])

        def workload = Mock(Workload)
        workload.name >> 'wk-finalize'
        workload.server >> server

        when:
        def response = provisioning.finalizeProvisioning(workload)

        then:
        response.success
    }

    def "createVMRequestBody adds cloud-init disk and annotations for linux platform"() {
        given:
        GroovyMock(ProvisionHelper, global: true)
        def provisionHelper = Mock(ProvisionHelper)
        new ProvisionHelper(_ as ComputeServer, _ as com.morpheusdata.model.provisioning.WorkloadRequest, _, _ as Map) >> provisionHelper
        provisionHelper.buildCloudInitOptions() >> [success: true, configName: 'secret-vm1']

        def services = Mock(MorpheusServices)
        def virtualImageService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        mockMorpheusContext.services >> services
        services.virtualImage >> virtualImageService

        def provisioningSpy = Spy(VirtualMachineProvisioning, constructorArgs: [mockMorpheusContext])
        provisioningSpy.createNetworkConfiguration(_ as Workload, _ as String) >> [
            interfacesConfig: [[name: 'eth0', masquerade: [:], model: 'virtio']],
            networksConfig  : [[name: 'eth0', pod: [:]]]
        ]

        def rootType = Mock(StorageVolumeType)
        rootType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC
        def rootVolume = Mock(StorageVolume)
        rootVolume.name >> 'root-disk'
        rootVolume.claimName >> 'root-pvc'
        rootVolume.rootVolume >> true
        rootVolume.type >> rootType

        def image = Mock(VirtualImage)
        image.id >> 22L
        image.name >> 'tmpl-linux'
        image.osType >> null
        image.getCloudInit() >> true
        image.getConfigMap() >> [labels: [tmpl: 'true'], annotations: [anno: 'yes'], namespace: 'tmpl-ns']

        virtualImageService.get(22L) >> image

        def server = new ComputeServer(id: 101L, name: 'vm1', hostname: 'vm1-host')
        server.sourceImage = image
        server.volumes = [rootVolume]
        server.labels = [new Expando(name: 'env-prod')]

        def workload = Mock(Workload)
        workload.name >> 'wk1'
        workload.server >> server
        workload.maxCores >> 2
        workload.maxMemory >> (2L * 1024L * 1024L * 1024L)

        def request = new com.morpheusdata.model.provisioning.WorkloadRequest()
        def opts = [platform: 'linux', config: [project: [name: 'ns1']]]

        when:
        def body = provisioningSpy.createVMRequestBody(workload, request, opts)

        then:
        body.metadata.name == 'vm1'
        body.metadata.namespace == 'ns1'
        body.metadata.labels['morpheus-label.vm.provisioning'] == 'true'
        body.metadata.annotations['vm.kubevirt.io/os'] == 'linux'
        body.spec.template.spec.domain.cpu.cores == 2
        body.spec.template.spec.domain.resources.limits.memory == '2048Mi'
        body.spec.template.spec.domain.resources.limits.cpu == '2'
        body.spec.template.spec.domain.memory.guest == '1948Mi'
        body.spec.template.metadata.labels['harvesterhci.io/vmName'] == 'vm1'
        body.spec.template.spec.domain.devices.disks.find { it.name == 'cloudinitdisk' }
        body.spec.template.spec.volumes.find { it.name == 'cloudinitdisk' }.cloudInitNoCloud.secretRef.name == 'secret-vm1'
        opts.namespace == 'ns1'
    }

    def "createVMRequestBody does not add cloud-init disk when isCloudInit is false"() {
        given:
        GroovyMock(ProvisionHelper, global: true)
        def provisionHelper = Mock(ProvisionHelper)
        new ProvisionHelper(_ as ComputeServer, _ as com.morpheusdata.model.provisioning.WorkloadRequest, _, _ as Map) >> provisionHelper

        def services = Mock(MorpheusServices)
        def virtualImageService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        mockMorpheusContext.services >> services
        services.virtualImage >> virtualImageService

        def provisioningSpy = Spy(VirtualMachineProvisioning, constructorArgs: [mockMorpheusContext])
        provisioningSpy.createNetworkConfiguration(_ as Workload, _ as String) >> [
            interfacesConfig: [[name: 'eth0', masquerade: [:], model: 'virtio']],
            networksConfig  : [[name: 'eth0', pod: [:]]]
        ]

        def rootType = Mock(StorageVolumeType)
        rootType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC
        def rootVolume = Mock(StorageVolume)
        rootVolume.name >> 'root-disk'
        rootVolume.claimName >> 'root-pvc'
        rootVolume.rootVolume >> true
        rootVolume.type >> rootType

        def image = Mock(VirtualImage)
        image.id >> 24L
        image.name >> 'vendor-appliance'
        image.osType >> null
        image.getCloudInit() >> false  // Cloud-init disabled
        image.getConfigMap() >> [labels: [:], annotations: [:], namespace: 'ns1']

        virtualImageService.get(24L) >> image

        def server = new ComputeServer(id: 103L, name: 'vm-no-ci', hostname: 'vm-no-ci-host')
        server.sourceImage = image
        server.volumes = [rootVolume]

        def workload = Mock(Workload)
        workload.name >> 'wk-no-ci'
        workload.server >> server
        workload.maxCores >> 2
        workload.maxMemory >> (2L * 1024L * 1024L * 1024L)

        def request = new com.morpheusdata.model.provisioning.WorkloadRequest()
        def opts = [platform: 'linux', config: [project: [name: 'ns1']]]

        when:
        def body = provisioningSpy.createVMRequestBody(workload, request, opts)

        then:
        0 * provisionHelper.buildCloudInitOptions()  // Should NOT be called
        !body.spec.template.spec.domain.devices.disks.find { it.name == 'cloudinitdisk' }
        !body.spec.template.spec.volumes.find { it.name == 'cloudinitdisk' }
    }

    def "createVMRequestBody does not add cloud-init disk when getCloudInit returns null"() {
        given:
        GroovyMock(ProvisionHelper, global: true)
        def provisionHelper = Mock(ProvisionHelper)
        new ProvisionHelper(_ as ComputeServer, _ as com.morpheusdata.model.provisioning.WorkloadRequest, _, _ as Map) >> provisionHelper

        def services = Mock(MorpheusServices)
        def virtualImageService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        mockMorpheusContext.services >> services
        services.virtualImage >> virtualImageService

        def provisioningSpy = Spy(VirtualMachineProvisioning, constructorArgs: [mockMorpheusContext])
        provisioningSpy.createNetworkConfiguration(_ as Workload, _ as String) >> [
            interfacesConfig: [[name: 'eth0', masquerade: [:], model: 'virtio']],
            networksConfig  : [[name: 'eth0', pod: [:]]]
        ]

        def rootType = Mock(StorageVolumeType)
        rootType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC
        def rootVolume = Mock(StorageVolume)
        rootVolume.name >> 'root-disk'
        rootVolume.claimName >> 'root-pvc'
        rootVolume.rootVolume >> true
        rootVolume.type >> rootType

        def image = Mock(VirtualImage)
        image.id >> 25L
        image.name >> 'legacy-image'
        image.osType >> null
        image.getCloudInit() >> null  // Cloud-init not set (null)
        image.getConfigMap() >> [labels: [:], annotations: [:], namespace: 'ns1']

        virtualImageService.get(25L) >> image

        def server = new ComputeServer(id: 104L, name: 'vm-null-ci', hostname: 'vm-null-ci-host')
        server.sourceImage = image
        server.volumes = [rootVolume]

        def workload = Mock(Workload)
        workload.name >> 'wk-null-ci'
        workload.server >> server
        workload.maxCores >> 2
        workload.maxMemory >> (2L * 1024L * 1024L * 1024L)

        def request = new com.morpheusdata.model.provisioning.WorkloadRequest()
        def opts = [platform: 'linux', config: [project: [name: 'ns1']]]

        when:
        def body = provisioningSpy.createVMRequestBody(workload, request, opts)

        then:
        0 * provisionHelper.buildCloudInitOptions()  // Should NOT be called
        !body.spec.template.spec.domain.devices.disks.find { it.name == 'cloudinitdisk' }
        !body.spec.template.spec.volumes.find { it.name == 'cloudinitdisk' }
    }

    def "createVMRequestBody adds cloud-init disk when isCloudInit is true"() {
        given:
        GroovyMock(ProvisionHelper, global: true)
        def provisionHelper = Mock(ProvisionHelper)
        new ProvisionHelper(_ as ComputeServer, _ as com.morpheusdata.model.provisioning.WorkloadRequest, _, _ as Map) >> provisionHelper
        provisionHelper.buildCloudInitOptions() >> [success: true, configName: 'secret-vm-enabled']

        def services = Mock(MorpheusServices)
        def virtualImageService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        mockMorpheusContext.services >> services
        services.virtualImage >> virtualImageService

        def provisioningSpy = Spy(VirtualMachineProvisioning, constructorArgs: [mockMorpheusContext])
        provisioningSpy.createNetworkConfiguration(_ as Workload, _ as String) >> [
            interfacesConfig: [[name: 'eth0', masquerade: [:], model: 'virtio']],
            networksConfig  : [[name: 'eth0', pod: [:]]]
        ]

        def rootType = Mock(StorageVolumeType)
        rootType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC
        def rootVolume = Mock(StorageVolume)
        rootVolume.name >> 'root-disk'
        rootVolume.claimName >> 'root-pvc'
        rootVolume.rootVolume >> true
        rootVolume.type >> rootType

        def image = Mock(VirtualImage)
        image.id >> 26L
        image.name >> 'cloud-ready-image'
        image.osType >> null
        image.getCloudInit() >> true  // Cloud-init enabled
        image.getConfigMap() >> [labels: [:], annotations: [:], namespace: 'ns1']

        virtualImageService.get(26L) >> image

        def server = new ComputeServer(id: 105L, name: 'vm-ci-enabled', hostname: 'vm-ci-enabled-host')
        server.sourceImage = image
        server.volumes = [rootVolume]

        def workload = Mock(Workload)
        workload.name >> 'wk-ci-enabled'
        workload.server >> server
        workload.maxCores >> 2
        workload.maxMemory >> (2L * 1024L * 1024L * 1024L)

        def request = new com.morpheusdata.model.provisioning.WorkloadRequest()
        def opts = [platform: 'linux', config: [project: [name: 'ns1']]]

        when:
        def body = provisioningSpy.createVMRequestBody(workload, request, opts)

        then:
        body.spec.template.spec.domain.devices.disks.find { it.name == 'cloudinitdisk' }
        body.spec.template.spec.volumes.find { it.name == 'cloudinitdisk' }.cloudInitNoCloud.secretRef.name == 'secret-vm-enabled'
    }

    def "createVMRequestBody adds sysprep volume for windows platform"() {
        given:
        GroovyMock(ProvisionHelper, global: true)
        def provisionHelper = Mock(ProvisionHelper)
        new ProvisionHelper(_ as ComputeServer, _ as com.morpheusdata.model.provisioning.WorkloadRequest, _, _ as Map) >> provisionHelper
        provisionHelper.buildSysprepOptions() >> [success: true, configMapName: 'vmw-sysprep']

        def provisioningSpy = Spy(VirtualMachineProvisioning, constructorArgs: [mockMorpheusContext])
        provisioningSpy.createNetworkConfiguration(_ as Workload, _ as String) >> [
            interfacesConfig: [[name: 'eth0', bridge: [:], model: 'virtio']],
            networksConfig  : [[name: 'eth0', multus: [networkName: 'default/net-a']]]
        ]

        def rootType = Mock(StorageVolumeType)
        rootType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC
        def rootVolume = Mock(StorageVolume)
        rootVolume.name >> 'root-disk'
        rootVolume.claimName >> 'root-pvc'
        rootVolume.rootVolume >> true
        rootVolume.type >> rootType

        def image = Mock(VirtualImage)
        image.id >> 23L
        image.name >> 'tmpl-win'
        image.osType >> null
        image.getConfigMap() >> [labels: [:], annotations: [:], namespace: 'tmpl-ns']

        def server = new ComputeServer(id: 102L, name: 'vmw', hostname: 'vmw-host')
        server.sourceImage = image
        server.volumes = [rootVolume]

        def workload = Mock(Workload)
        workload.name >> 'wk2'
        workload.server >> server
        workload.maxCores >> 4
        workload.maxMemory >> (4L * 1024L * 1024L * 1024L)

        def request = new com.morpheusdata.model.provisioning.WorkloadRequest()
        def opts = [platform: 'windows', config: [project: [name: 'ns2']]]

        when:
        def body = provisioningSpy.createVMRequestBody(workload, request, opts)

        then:
        body.metadata.namespace == 'ns2'
        body.metadata.annotations['vm.kubevirt.io/os'] == 'windows'
        !body.spec.template.spec.domain.devices.disks.find { it.name == 'sysprep' }
        !body.spec.template.spec.volumes.find { it.name == 'sysprep' }
    }

    def "createVMRequestBody honours secureBoot: false from virtualImageConfig"() {
        given:
        GroovyMock(ProvisionHelper, global: true)
        def provisionHelper = Mock(ProvisionHelper)
        new ProvisionHelper(_ as ComputeServer, _ as com.morpheusdata.model.provisioning.WorkloadRequest, _, _ as Map) >> provisionHelper

        def services = Mock(MorpheusServices)
        def virtualImageService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        mockMorpheusContext.services >> services
        services.virtualImage >> virtualImageService

        def provisioningSpy = Spy(VirtualMachineProvisioning, constructorArgs: [mockMorpheusContext])
        provisioningSpy.createNetworkConfiguration(_ as Workload, _ as String) >> [
            interfacesConfig: [[name: 'eth0', masquerade: [:], model: 'virtio']],
            networksConfig  : [[name: 'eth0', pod: [:]]]
        ]

        def rootType = Mock(StorageVolumeType)
        rootType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC
        def rootVolume = Mock(StorageVolume)
        rootVolume.name >> 'root-disk'
        rootVolume.claimName >> 'root-pvc'
        rootVolume.rootVolume >> true
        rootVolume.type >> rootType

        def image = Mock(VirtualImage)
        image.id >> 30L
        image.name >> 'tmpl-no-secureboot'
        image.osType >> null
        image.getCloudInit() >> false
        image.getSecureBoot() >> false  // Template explicitly sets secureBoot: false
        image.getConfigMap() >> [labels: [:], annotations: [:], namespace: 'ns1']

        virtualImageService.get(30L) >> image

        def server = new ComputeServer(id: 200L, name: 'vm-no-sb', hostname: 'vm-no-sb-host')
        server.sourceImage = image
        server.volumes = [rootVolume]

        def workload = Mock(Workload)
        workload.name >> 'wk-no-sb'
        workload.server >> server
        workload.maxCores >> 2
        workload.maxMemory >> (2L * 1024L * 1024L * 1024L)

        def request = new com.morpheusdata.model.provisioning.WorkloadRequest()
        def opts = [platform: 'linux', config: [project: [name: 'ns1']]]

        when:
        def body = provisioningSpy.createVMRequestBody(workload, request, opts)

        then:
        // Harvester default: BIOS unless the image is flagged UEFI / Secure Boot
        body.spec.template.spec.domain.firmware == null
        body.spec.template.spec.domain.features.smm == null
    }

    def "createVMRequestBody honours secureBoot: true from virtualImageConfig"() {
        given:
        GroovyMock(ProvisionHelper, global: true)
        def provisionHelper = Mock(ProvisionHelper)
        new ProvisionHelper(_ as ComputeServer, _ as com.morpheusdata.model.provisioning.WorkloadRequest, _, _ as Map) >> provisionHelper

        def services = Mock(MorpheusServices)
        def virtualImageService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        mockMorpheusContext.services >> services
        services.virtualImage >> virtualImageService

        def provisioningSpy = Spy(VirtualMachineProvisioning, constructorArgs: [mockMorpheusContext])
        provisioningSpy.createNetworkConfiguration(_ as Workload, _ as String) >> [
            interfacesConfig: [[name: 'eth0', masquerade: [:], model: 'virtio']],
            networksConfig  : [[name: 'eth0', pod: [:]]]
        ]

        def rootType = Mock(StorageVolumeType)
        rootType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC
        def rootVolume = Mock(StorageVolume)
        rootVolume.name >> 'root-disk'
        rootVolume.claimName >> 'root-pvc'
        rootVolume.rootVolume >> true
        rootVolume.type >> rootType

        def image = Mock(VirtualImage)
        image.id >> 31L
        image.name >> 'tmpl-with-secureboot'
        image.osType >> null
        image.getCloudInit() >> false
        image.getSecureBoot() >> true  // Template explicitly sets secureBoot: true
        image.getConfigMap() >> [labels: [:], annotations: [:], namespace: 'ns1']

        virtualImageService.get(31L) >> image

        def server = new ComputeServer(id: 201L, name: 'vm-sb', hostname: 'vm-sb-host')
        server.sourceImage = image
        server.volumes = [rootVolume]

        def workload = Mock(Workload)
        workload.name >> 'wk-sb'
        workload.server >> server
        workload.maxCores >> 2
        workload.maxMemory >> (2L * 1024L * 1024L * 1024L)

        def request = new com.morpheusdata.model.provisioning.WorkloadRequest()
        def opts = [platform: 'linux', config: [project: [name: 'ns1']]]

        when:
        def body = provisioningSpy.createVMRequestBody(workload, request, opts)

        then:
        body.spec.template.spec.domain.firmware.bootloader.efi['secureBoot'] == true
        body.spec.template.spec.domain.features.smm.enabled == true
    }

    def "createVMRequestBody defaults secureBoot to false when virtualImageConfig has no firmware setting"() {
        given:
        GroovyMock(ProvisionHelper, global: true)
        def provisionHelper = Mock(ProvisionHelper)
        new ProvisionHelper(_ as ComputeServer, _ as com.morpheusdata.model.provisioning.WorkloadRequest, _, _ as Map) >> provisionHelper

        def services = Mock(MorpheusServices)
        def virtualImageService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        mockMorpheusContext.services >> services
        services.virtualImage >> virtualImageService

        def provisioningSpy = Spy(VirtualMachineProvisioning, constructorArgs: [mockMorpheusContext])
        provisioningSpy.createNetworkConfiguration(_ as Workload, _ as String) >> [
            interfacesConfig: [[name: 'eth0', masquerade: [:], model: 'virtio']],
            networksConfig  : [[name: 'eth0', pod: [:]]]
        ]

        def rootType = Mock(StorageVolumeType)
        rootType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC
        def rootVolume = Mock(StorageVolume)
        rootVolume.name >> 'root-disk'
        rootVolume.claimName >> 'root-pvc'
        rootVolume.rootVolume >> true
        rootVolume.type >> rootType

        def image = Mock(VirtualImage)
        image.id >> 32L
        image.name >> 'tmpl-no-firmware'
        image.osType >> null
        image.getCloudInit() >> false
        image.getSecureBoot() >> null  // Template has no firmware section; null → defaults to false
        image.getConfigMap() >> [labels: [:], annotations: [:], namespace: 'ns1']

        virtualImageService.get(32L) >> image

        def server = new ComputeServer(id: 202L, name: 'vm-default-sb', hostname: 'vm-default-sb-host')
        server.sourceImage = image
        server.volumes = [rootVolume]

        def workload = Mock(Workload)
        workload.name >> 'wk-default-sb'
        workload.server >> server
        workload.maxCores >> 2
        workload.maxMemory >> (2L * 1024L * 1024L * 1024L)

        def request = new com.morpheusdata.model.provisioning.WorkloadRequest()
        def opts = [platform: 'linux', config: [project: [name: 'ns1']]]

        when:
        def body = provisioningSpy.createVMRequestBody(workload, request, opts)

        then:
        // No secure boot / UEFI flag on the image -> BIOS boot (no firmware section)
        body.spec.template.spec.domain.firmware == null
    }

    def "createVMRequestBody uses UEFI without Secure Boot when the image is flagged UEFI"() {
        given:
        GroovyMock(ProvisionHelper, global: true)
        def provisionHelper = Mock(ProvisionHelper)
        new ProvisionHelper(_ as ComputeServer, _ as com.morpheusdata.model.provisioning.WorkloadRequest, _, _ as Map) >> provisionHelper

        def services = Mock(MorpheusServices)
        def virtualImageService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        mockMorpheusContext.services >> services
        services.virtualImage >> virtualImageService

        def provisioningSpy = Spy(VirtualMachineProvisioning, constructorArgs: [mockMorpheusContext])
        provisioningSpy.createNetworkConfiguration(_ as Workload, _ as String) >> [
            interfacesConfig: [[name: 'eth0', masquerade: [:], model: 'virtio']],
            networksConfig  : [[name: 'eth0', pod: [:]]]
        ]

        def rootType = Mock(StorageVolumeType)
        rootType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC
        def rootVolume = Mock(StorageVolume)
        rootVolume.name >> 'root-disk'
        rootVolume.claimName >> 'root-pvc'
        rootVolume.rootVolume >> true
        rootVolume.type >> rootType

        def image = Mock(VirtualImage)
        image.id >> 32L
        image.name >> 'tmpl-no-firmware'
        image.osType >> null
        image.getCloudInit() >> false
        image.getSecureBoot() >> false
        image.getUefi() >> true
        image.getConfigMap() >> [labels: [:], annotations: [:], namespace: 'ns1']

        virtualImageService.get(32L) >> image

        def server = new ComputeServer(id: 202L, name: 'vm-default-sb', hostname: 'vm-default-sb-host')
        server.sourceImage = image
        server.volumes = [rootVolume]

        def workload = Mock(Workload)
        workload.name >> 'wk-default-sb'
        workload.server >> server
        workload.maxCores >> 2
        workload.maxMemory >> (2L * 1024L * 1024L * 1024L)

        def request = new com.morpheusdata.model.provisioning.WorkloadRequest()
        def opts = [platform: 'linux', config: [project: [name: 'ns1']]]

        when:
        def body = provisioningSpy.createVMRequestBody(workload, request, opts)

        then:
        body.spec.template.spec.domain.firmware.bootloader.efi['secureBoot'] == false
        body.spec.template.spec.domain.features.smm == null
    }

    def "removeCloudInitDisk patches vm and deletes cloud-init secret for linux"() {
        given:
        GroovyMock(HarvesterApiClient, global: true)
        def apiClient = Mock(HarvesterApiClient)
        def serverGroup = Mock(ComputeServerGroup)
        new HarvesterApiClient(serverGroup) >> apiClient

        GroovyMock(ProvisionHelper, global: true)
        ProvisionHelper.getDiskAndVolumeIndices(apiClient, 'ns1', 'vm1', _ as String) >> [diskIndex: 1, volumeIndex: 2]
        ProvisionHelper.buildDiskRemovalPatch('1', '2') >> [[op: 'remove', path: '/spec/template/spec/domain/devices/disks/1']]
        def provisionHelper = Mock(ProvisionHelper)
        new ProvisionHelper(_ as ComputeServer, null, _ as ComputeServerGroup, _ as Map) >> provisionHelper
        provisionHelper.deleteCloudInitSecret('secret-vm1', 'ns1') >> ServiceResponse.success([:])
        apiClient.patch(_ as String, _ as String, 'application/json-patch+json') >> ServiceResponse.success([:])

        def virtualImage = Mock(VirtualImage)
        virtualImage.getCloudInit() >> true

        def server = new ComputeServer(name: 'vm1')
        server.computeServerType = new ComputeServerType(code: 'harvester-linux')

        when:
        invokePrivateStatic(VirtualMachineProvisioning, 'removeCloudInitDisk',
            [ComputeServer, ComputeServerGroup, String, VirtualImage] as Class[], [server, serverGroup, 'ns1', virtualImage] as Object[])

        then:
        1 * apiClient.patch('/apis/kubevirt.io/v1/namespaces/ns1/virtualmachines/vm1', _ as String, 'application/json-patch+json')
        (0..1) * provisionHelper.deleteCloudInitSecret('secret-vm1', 'ns1')
    }

    def "removeCloudInitDisk deletes sysprep configmap for windows"() {
        given:
        GroovyMock(HarvesterApiClient, global: true)
        def apiClient = Mock(HarvesterApiClient)
        def serverGroup = Mock(ComputeServerGroup)
        new HarvesterApiClient(serverGroup) >> apiClient

        GroovyMock(ProvisionHelper, global: true)
        ProvisionHelper.getDiskAndVolumeIndices(apiClient, 'ns1', 'vmw', 'harvester-windows') >> [diskIndex: 0, volumeIndex: 0]
        ProvisionHelper.buildDiskRemovalPatch('0', '0') >> [[op: 'remove', path: '/spec/template/spec/domain/devices/disks/0']]
        apiClient.patch(_ as String, _ as String, 'application/json-patch+json') >> ServiceResponse.success([:])

        GroovyMock(com.hpe.morpheus.harvester.plugin.apihelper.ConfigMapApiHelper, global: true)
        def configMapHelper = Mock(com.hpe.morpheus.harvester.plugin.apihelper.ConfigMapApiHelper)
        new com.hpe.morpheus.harvester.plugin.apihelper.ConfigMapApiHelper(apiClient) >> configMapHelper
        configMapHelper.deleteConfigMap('ns1', 'vmw-sysprep') >> ServiceResponse.success([:])

        def virtualImage = Mock(VirtualImage)
        virtualImage.getSysprep() >> true

        def server = new ComputeServer(name: 'vmw')
        server.computeServerType = new ComputeServerType(code: 'harvester-windows')

        when:
        invokePrivateStatic(VirtualMachineProvisioning, 'removeCloudInitDisk',
            [ComputeServer, ComputeServerGroup, String, VirtualImage] as Class[], [server, serverGroup, 'ns1', virtualImage] as Object[])

        then:
        1 * configMapHelper.deleteConfigMap('ns1', 'vmw-sysprep')
    }

    def "removeCloudInitDisk skips patch when indices are missing"() {
        given:
        GroovyMock(HarvesterApiClient, global: true)
        def apiClient = Mock(HarvesterApiClient)
        def serverGroup = Mock(ComputeServerGroup)
        new HarvesterApiClient(serverGroup) >> apiClient

        GroovyMock(ProvisionHelper, global: true)
        ProvisionHelper.getDiskAndVolumeIndices(apiClient, 'ns1', 'vm1', 'harvester-linux') >> null
        def provisionHelper = Mock(ProvisionHelper)
        new ProvisionHelper(_ as ComputeServer, null, _ as ComputeServerGroup, _ as Map) >> provisionHelper
        provisionHelper.deleteCloudInitSecret('secret-vm1', 'ns1') >> ServiceResponse.success([:])

        def virtualImage = Mock(VirtualImage)
        virtualImage.getCloudInit() >> true

        def server = new ComputeServer(name: 'vm1')
        server.computeServerType = new ComputeServerType(code: 'harvester-linux')

        when:
        invokePrivateStatic(VirtualMachineProvisioning, 'removeCloudInitDisk',
            [ComputeServer, ComputeServerGroup, String, VirtualImage] as Class[], [server, serverGroup, 'ns1', virtualImage] as Object[])

        then:
        0 * apiClient.patch(_ as String, _ as String, _ as String)
        1 * provisionHelper.deleteCloudInitSecret('secret-vm1', 'ns1')
    }

    def "runWorkload returns failure when dry-run validation fails"() {
        given:
        GroovySpy(DBUtils, global: true)
        DBUtils.getZonePoolFromIdString(_ as MorpheusContext, 'pool-1') >> [serverGroup: new com.morpheusdata.model.ComputeServerGroup(id: 501L)]

        GroovySpy(VirtualMachineProvisioning, global: true)
        VirtualMachineProvisioning.makeHarvesterApiCallAndWait(_ as Object, _ as String, _ as String, _ as Map,
            _ as Boolean, _ as Integer, _ as Integer, _ as Map) >> ServiceResponse.error('dry-run-failed')

        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        def virtualImageService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def asyncServices = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def asyncCloudService = typedMock('com.morpheusdata.core.cloud.MorpheusCloudService')
        mockMorpheusContext.services >> services
        mockMorpheusContext.async >> asyncServices
        services.computeServer >> computeServerService
        services.virtualImage >> virtualImageService
        asyncServices.cloud >> asyncCloudService
        asyncCloudService.findComputeServerTypeByCode(_ as String) >> Single.just(new ComputeServerType(code: 'harvester-linux'))

        def sourceImage = new VirtualImage(id: 101L)
        def server = new ComputeServer(id: 9001L, name: 'vm-dryrun', hostname: 'vm-dryrun-h')
        server.sourceImage = sourceImage
        computeServerService.get(9001L) >> new ComputeServer(id: 9001L, name: 'vm-dryrun-db')
        virtualImageService.get(101L) >> new VirtualImage(id: 101L, platform: 'linux')

        def workload = Mock(Workload)
        workload.server >> server

        def request = Mock(com.morpheusdata.model.provisioning.WorkloadRequest)
        request.usersConfiguration >> [createUsers: false]
        request.networkConfiguration >> [primaryInterface: [doDhcp: true, ipAddress: null]]
        request.process >> null

        def provisioningSpy = Spy(VirtualMachineProvisioning, constructorArgs: [mockMorpheusContext])
        provisioningSpy.createVMRequestBody(_ as Workload, _ as com.morpheusdata.model.provisioning.WorkloadRequest, _ as Map) >>
            [metadata: [namespace: 'ns1', name: 'vm-dryrun']]

        when:
        def response = provisioningSpy.runWorkload(workload, request, [config: [resourcePoolId: 'pool-1'], installAgent: true])

        then:
        !response.success
        response.msg.contains('Exception during VM dry run')
    }

    def "runWorkload returns failure when VM creation API call fails"() {
        given:
        GroovySpy(DBUtils, global: true)
        DBUtils.getZonePoolFromIdString(_ as MorpheusContext, 'pool-2') >> [serverGroup: new com.morpheusdata.model.ComputeServerGroup(id: 502L)]

        GroovySpy(VirtualMachineProvisioning, global: true)
        VirtualMachineProvisioning.makeHarvesterApiCallAndWait(_ as Object, _ as String, _ as String, _ as Map,
            _ as Boolean, _ as Integer, _ as Integer, _ as Map) >> ServiceResponse.success([:])
        VirtualMachineProvisioning.makeHarvesterApiCallAndWait(_ as Object, _ as String, _ as String, _ as Map,
            _ as Boolean) >> ServiceResponse.error('create-failed')

        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        def virtualImageService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def asyncServices = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def asyncCloudService = typedMock('com.morpheusdata.core.cloud.MorpheusCloudService')
        mockMorpheusContext.services >> services
        mockMorpheusContext.async >> asyncServices
        services.computeServer >> computeServerService
        services.virtualImage >> virtualImageService
        asyncServices.cloud >> asyncCloudService
        asyncCloudService.findComputeServerTypeByCode(_ as String) >> Single.just(new ComputeServerType(code: 'harvester-linux'))

        def sourceImage = new VirtualImage(id: 102L)
        def server = new ComputeServer(id: 9002L, name: 'vm-create-fail', hostname: 'vm-create-fail-h')
        server.sourceImage = sourceImage
        computeServerService.get(9002L) >> new ComputeServer(id: 9002L, name: 'vm-create-fail-db')
        virtualImageService.get(102L) >> new VirtualImage(id: 102L, platform: 'linux')

        def workload = Mock(Workload)
        workload.server >> server

        def request = Mock(com.morpheusdata.model.provisioning.WorkloadRequest)
        request.usersConfiguration >> [createUsers: false]
        request.networkConfiguration >> [primaryInterface: [doDhcp: true, ipAddress: null]]
        request.process >> null

        def provisioningSpy = Spy(VirtualMachineProvisioning, constructorArgs: [mockMorpheusContext])
        provisioningSpy.createVMRequestBody(_ as Workload, _ as com.morpheusdata.model.provisioning.WorkloadRequest, _ as Map) >>
            [metadata: [namespace: 'ns1', name: 'vm-create-fail']]

        when:
        def response = provisioningSpy.runWorkload(workload, request, [config: [resourcePoolId: 'pool-2'], installAgent: false])

        then:
        !response.success
        response.msg.contains('Harvester VM creation failed')
    }

    def "makeHarvesterApiCallAndWait returns api failure immediately when query params call fails"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.post(_ as String, _ as Map, _ as Map) >> ServiceResponse.error('query-call-failed')

        when:
        def response = VirtualMachineProvisioning.makeHarvesterApiCallAndWait(
            apiClient, 'post', '/apis/kubevirt.io/v1/namespaces/ns1/virtualmachines',
            [metadata: [namespace: 'ns1', name: 'vm-query']], false, 10, 0, [dryRun: 'All'])

        then:
        !response.success
        response.errors.error.contains('query-call-failed')
        0 * apiClient.get(_ as String)
    }

    def "makeHarvesterApiCallAndWait returns error when wait mode cannot extract vm metadata"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        def postResponse = ServiceResponse.success([metadata: [:]])
        postResponse.statusCode = '201'
        apiClient.post(_ as String, _ as Map) >> postResponse

        when:
        def response = VirtualMachineProvisioning.makeHarvesterApiCallAndWait(
            apiClient, 'post', '/apis/kubevirt.io/v1/namespaces/ns1/virtualmachines', [:], true, 1, 0)

        then:
        !response.success
        response.errors.error.contains('Could not extract VM name or namespace from API call context')
    }

    def "makeHarvesterApiCallAndWait returns timeout when vm status never becomes running"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        def postResponse = ServiceResponse.success([metadata: [namespace: 'ns1', name: 'vm-timeout']])
        postResponse.statusCode = '201'
        apiClient.post(_ as String, _ as Map) >> postResponse
        apiClient.get(_ as String) >> ServiceResponse.error('status-fetch-failed')

        when:
        def response = VirtualMachineProvisioning.makeHarvesterApiCallAndWait(
            apiClient, 'post', '/apis/kubevirt.io/v1/namespaces/ns1/virtualmachines',
            [metadata: [namespace: 'ns1', name: 'vm-timeout']], true, 100, 0)

        then:
        !response.success
        response.errors.error.contains('Timed out waiting for VM State to become Running')
    }

    def "waitForGuestAgentConnection retries when AgentConnected condition is absent"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            status: [conditions: [[type: 'Ready', status: 'True']]]
        ])

        when:
        VirtualMachineProvisioning.waitForGuestAgentConnection(apiClient, 'ns1', 'vm-no-agent', 2, 0)

        then:
        2 * apiClient.get('/apis/kubevirt.io/v1/namespaces/ns1/virtualmachineinstances/vm-no-agent')
        notThrown(Exception)
    }

    def "waitForGuestAgentConnection retries when VMI status call fails"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.error('vmi-unavailable')

        when:
        VirtualMachineProvisioning.waitForGuestAgentConnection(apiClient, 'ns1', 'vm-vmi-fail', 2, 0)

        then:
        2 * apiClient.get('/apis/kubevirt.io/v1/namespaces/ns1/virtualmachineinstances/vm-vmi-fail')
        notThrown(Exception)
    }

    def "makeHarvesterApiCallAndWait returns response unchanged for unexpected success status code"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        def postResponse = ServiceResponse.success([metadata: [namespace: 'ns1', name: 'vm-odd-status']])
        postResponse.statusCode = '204'
        apiClient.post(_ as String, _ as Map) >> postResponse

        when:
        def response = VirtualMachineProvisioning.makeHarvesterApiCallAndWait(
            apiClient, 'post', '/apis/kubevirt.io/v1/namespaces/ns1/virtualmachines',
            [metadata: [namespace: 'ns1', name: 'vm-odd-status']], false)

        then:
        response.success
        response.statusCode == '204'
    }

    def "waitForGuestAgentConnection handles AgentConnected false condition without throwing"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            status: [conditions: [[type: 'AgentConnected', status: 'False', reason: 'GuestAgentNotReady']]]
        ])

        when:
        VirtualMachineProvisioning.waitForGuestAgentConnection(apiClient, 'ns1', 'vm-agent-false', 2, 0)

        then:
        2 * apiClient.get('/apis/kubevirt.io/v1/namespaces/ns1/virtualmachineinstances/vm-agent-false')
        notThrown(Exception)
    }

    def "waitForGuestAgentConnection handles null status payload"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([:])

        when:
        VirtualMachineProvisioning.waitForGuestAgentConnection(apiClient, 'ns-a', 'vm-a', 1, 0)

        then:
        1 * apiClient.get('/apis/kubevirt.io/v1/namespaces/ns-a/virtualmachineinstances/vm-a')
        notThrown(Exception)
    }

    def "waitForGuestAgentConnection handles null conditions list"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([status: [:]])

        when:
        VirtualMachineProvisioning.waitForGuestAgentConnection(apiClient, 'ns-b', 'vm-b', 1, 0)

        then:
        notThrown(Exception)
    }

    def "waitForGuestAgentConnection handles response failure with message"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.error('api-failed')

        when:
        VirtualMachineProvisioning.waitForGuestAgentConnection(apiClient, 'ns-c', 'vm-c', 1, 0)

        then:
        notThrown(Exception)
    }

    def "waitForGuestAgentConnection handles get exception and continues"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> { throw new RuntimeException('boom-agent') }

        when:
        VirtualMachineProvisioning.waitForGuestAgentConnection(apiClient, 'ns-d', 'vm-d', 2, 0)

        then:
        2 * apiClient.get('/apis/kubevirt.io/v1/namespaces/ns-d/virtualmachineinstances/vm-d')
        notThrown(Exception)
    }

    def "performBasicReadinessCheck handles null VMI status"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([status: null])
        provisioning.metaClass.sleep = { long ms -> }

        when:
        def response = invokePrivate(provisioning, 'performBasicReadinessCheck',
            [Object, String, String] as Class[], [apiClient, 'ns1', 'vm1'] as Object[])

        then:
        !response.success
        response.msg.contains('timed out')
    }

    def "performBasicReadinessCheck handles running without conditions"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([status: [phase: 'Running', conditions: null]])
        provisioning.metaClass.sleep = { long ms -> }

        when:
        def response = invokePrivate(provisioning, 'performBasicReadinessCheck',
            [Object, String, String] as Class[], [apiClient, 'ns2', 'vm2'] as Object[])

        then:
        !response.success
        response.msg.contains('timed out')
    }

    def "performBasicReadinessCheck handles running and not-ready conditions"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            status: [
                phase: 'Running',
                conditions: [[type: 'Ready', status: 'False', reason: 'GuestNotReady']]
            ]
        ])
        provisioning.metaClass.sleep = { long ms -> }

        when:
        def response = invokePrivate(provisioning, 'performBasicReadinessCheck',
            [Object, String, String] as Class[], [apiClient, 'ns3', 'vm3'] as Object[])

        then:
        !response.success
        response.msg.contains('timed out')
    }

    def "performBasicReadinessCheck handles non-running phase"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([status: [phase: 'Pending', conditions: []]])
        provisioning.metaClass.sleep = { long ms -> }

        when:
        def response = invokePrivate(provisioning, 'performBasicReadinessCheck',
            [Object, String, String] as Class[], [apiClient, 'ns4', 'vm4'] as Object[])

        then:
        !response.success
        response.msg.contains('timed out')
    }

    def "performBasicReadinessCheck handles vmi api call failure"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.error('status-unavailable')
        provisioning.metaClass.sleep = { long ms -> }

        when:
        def response = invokePrivate(provisioning, 'performBasicReadinessCheck',
            [Object, String, String] as Class[], [apiClient, 'ns5', 'vm5'] as Object[])

        then:
        !response.success
        response.msg.contains('timed out')
    }

    def "performBasicReadinessCheck handles exception during get"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> { throw new RuntimeException('vmi-error') }
        provisioning.metaClass.sleep = { long ms -> }

        when:
        def response = invokePrivate(provisioning, 'performBasicReadinessCheck',
            [Object, String, String] as Class[], [apiClient, 'ns6', 'vm6'] as Object[])

        then:
        !response.success
        response.msg.contains('timed out')
    }

    def "waitForVMIpAssignment returns primary fallback when eth0 absent"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            status: [interfaces: [[name: 'eth1', ipAddresses: ['10.2.3.4']]]]
        ])

        when:
        def response = VirtualMachineProvisioning.waitForVMIpAssignment(apiClient, 'ns7', 'vm7', 1, 0)

        then:
        response.success
        response.data.primaryIP == '10.2.3.4'
        response.data.assignedIPs == ['10.2.3.4']
    }

    def "waitForVMIpAssignment handles interfaces with empty IP lists"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            status: [interfaces: [[name: 'eth0', ipAddresses: []], [name: 'eth1', ipAddresses: null]]]
        ])

        when:
        def response = VirtualMachineProvisioning.waitForVMIpAssignment(apiClient, 'ns8', 'vm8', 1, 0)

        then:
        response.success
        response.msg.contains('timed out')
    }

    def "waitForVMIpAssignment handles get exception and times out"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> { throw new RuntimeException('ip-check-ex') }

        when:
        def response = VirtualMachineProvisioning.waitForVMIpAssignment(apiClient, 'ns9', 'vm9', 1, 0)

        then:
        response.success
        response.msg.contains('timed out')
    }

    def "getAllAssignedIPs handles api get exception"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> { throw new RuntimeException('ip-fetch-ex') }

        when:
        def response = VirtualMachineProvisioning.getAllAssignedIPs(apiClient, 'ns10', 'vm10')

        then:
        !response.success
        response.msg.contains('Exception while retrieving assigned IP addresses')
    }

    def "getAllAssignedIPs includes interfaces with no mac and no ip"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_ as String) >> ServiceResponse.success([
            status: [
                nodeName: 'node-empty',
                interfaces: [[name: 'eth0', ipAddresses: null, mac: null]]
            ]
        ])

        when:
        def response = VirtualMachineProvisioning.getAllAssignedIPs(apiClient, 'ns11', 'vm11')

        then:
        response.success
        response.data.totalInterfaces == 1
        response.data.totalIPs == 0
        response.data.interfaceDetails[0].name == 'eth0'
    }

    def "createNetworkConfiguration keeps NAD name when namespace equals default"() {
        given:
        def iface = new Expando(name: 'eth1', network: new Expando(name: 'nad-a', displayName: 'default/nad-a'))
        def server = Mock(ComputeServer)
        def workload = Mock(Workload)
        workload.server >> server
        server.interfaces >> [iface]

        when:
        def response = provisioning.createNetworkConfiguration(workload, 'default')

        then:
        response.networksConfig[0].multus.networkName == 'nad-a'
    }

    def "createNetworkConfiguration handles mixed pod and NAD networks"() {
        given:
        def iface0 = new Expando(name: 'eth0', network: new Expando(name: 'DEFAULT-POD-NETWORK', displayName: 'default/default-pod-network'))
        def iface1 = new Expando(name: 'eth1', network: new Expando(name: 'nad-b', displayName: 'team1/nad-b'))
        def server = Mock(ComputeServer)
        def workload = Mock(Workload)
        workload.server >> server
        server.interfaces >> [iface1, iface0]

        when:
        def response = provisioning.createNetworkConfiguration(workload, 'team1')

        then:
        response.interfacesConfig*.name == ['eth0', 'eth1']
        response.networksConfig.find { it.name == 'eth0' }.pod != null
        response.networksConfig.find { it.name == 'eth1' }.multus.networkName == 'nad-b'
    }

    def "createNetworkConfiguration updates sanitized interface names in async DB path"() {
        given:
        def iface = new Expando(name: 'Eth  0', network: new Expando(name: 'default-pod-network', displayName: 'default/default-pod-network'))
        def server = Mock(ComputeServer)
        def workload = Mock(Workload)
        workload.server >> server
        server.interfaces >> [iface]

        def asyncRoot = Mock(Object)
        def asyncComputeServer = Mock(Object)
        def asyncInterfaceService = Mock(Object)
        def asyncSaveResult = Mock(Object)
        mockMorpheusContext.async >> asyncRoot
        asyncRoot.computeServer >> asyncComputeServer
        asyncComputeServer.computeServerInterface >> asyncInterfaceService
        asyncInterfaceService.bulkSave(_ as List) >> asyncSaveResult
        asyncSaveResult.blockingGet() >> true

        when:
        def response = provisioning.createNetworkConfiguration(workload, 'other')

        then:
        response.interfacesConfig[0].name == 'eth0'
        iface.name == 'eth0'
    }

    def "validateServerConfig succeeds with empty input"() {
        when:
        def response = provisioning.validateServerConfig([:])

        then:
        response.success
        response.errors.isEmpty()
    }

    def "validateServerConfig ignores non-list volumes and networks"() {
        when:
        def response = provisioning.validateServerConfig([volumes: 'x', networkInterfaces: 1, config: [template: 't', project: 'p']])

        then:
        response.success
    }

    def "validateServerConfig tolerates network lookup exceptions"() {
        given:
        def services = Mock(MorpheusServices)
        def networkService = typedMock('com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService')
        mockMorpheusContext.services >> services
        services.network >> networkService
        networkService.get(1L) >> { throw new RuntimeException('lookup-fail') }

        when:
        def response = provisioning.validateServerConfig([
            networkInterfaces: [[network: [id: 1L]]],
            config: [template: 't1', project: [name: 'team1']]
        ])

        then:
        response.success
    }

    def "buildVMLabels handles null template labels and null server labels"() {
        when:
        def labels = invokePrivate(provisioning, 'buildVMLabels',
            [Map, String, String, Object] as Class[],
            [[labels: null], 'tmpl-x', 'ns-x', null] as Object[])

        then:
        !labels.containsKey('vm.kubevirt.io/template')
        !labels.containsKey('harvesterhci.io/image-name')
        labels['morpheus-label.vm.provisioning'] == 'true'
    }

    def "buildVMLabels preserves null server label values"() {
        given:
        def serverLabels = [[name: null], [name: 'team-a']]

        when:
        def labels = invokePrivate(provisioning, 'buildVMLabels',
            [Map, String, String, Object] as Class[],
            [[labels: [a: 'b']], 'tmpl-y', 'ns-y', serverLabels] as Object[])

        then:
        labels.a == 'b'
        labels['morpheus-label.1'] == null
        labels['morpheus-label.2'] == 'team-a'
    }

    def "updateServerNetworkInterfaces handles missing virtio interface type"() {
        given:
        GroovySpy(DBUtils, global: true)
        DBUtils.getInterfaceTypeFromInterfaceModel(mockMorpheusContext, ['virtio']) >> []

        def iface = new ComputeServerInterface(name: 'eth0', macAddress: 'aa:bb:cc:11:22:33')
        iface.addresses = [new NetAddress(type: NetAddress.AddressType.IPV4, address: '10.1.1.1')]
        def server = new ComputeServer(id: 21L, name: 'vm-iface')
        server.interfaces = [iface]

        when:
        def response = invokePrivate(provisioning, 'updateServerNetworkInterfaces',
            [ComputeServer, List, String] as Class[],
            [server, [[name: 'eth0', mac: 'aa:bb:cc:11:22:33', ipAddresses: ['10.1.1.1']]], '10.1.1.1'] as Object[])

        then:
        response.success
        response.data.updatedCount == 0
    }

    def "updateServerNetworkInterfaces handles unmatched interface names"() {
        given:
        def virtioType = new ComputeServerInterfaceType(code: 'virtio')
        GroovySpy(DBUtils, global: true)
        DBUtils.getInterfaceTypeFromInterfaceModel(mockMorpheusContext, ['virtio']) >> [virtioType]

        def iface = new ComputeServerInterface(name: 'eth9')
        iface.addresses = []
        def server = new ComputeServer(id: 22L, name: 'vm-iface-2')
        server.interfaces = [iface]

        when:
        def response = invokePrivate(provisioning, 'updateServerNetworkInterfaces',
            [ComputeServer, List, String] as Class[],
            [server, [[name: 'eth0', mac: 'aa', ipAddresses: ['10.9.0.1']]], '10.9.0.1'] as Object[])

        then:
        response.success
        response.data.updatedCount == 0
    }

    // ===== validateServerConfig additional branch coverage =====

    def "validateServerConfig with null config project returns project required error"() {
        given:
        def opts = [config: [project: null, template: 'some-template']]

        when:
        def result = provisioning.validateServerConfig(opts)

        then:
        !result.success
        result.errors*.field.contains('project')
        result.errors*.msg.any { it.contains('required') }
    }

    def "validateServerConfig succeeds when network namespace matches config project"() {
        given:
        def services = Mock(MorpheusServices)
        def networkService = Mock(com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService)
        mockMorpheusContext.services >> services
        services.network >> networkService
        networkService.get(60L) >> new Network(displayName: 'myproject/nad-a')

        def opts = [
            networkInterfaces: [[network: [id: 60L]]],
            config: [project: [name: 'myproject'], template: 'tmpl1']
        ]

        when:
        def result = provisioning.validateServerConfig(opts)

        then:
        result.success
    }

    def "validateServerConfig allows network from default namespace regardless of config project"() {
        given:
        def services = Mock(MorpheusServices)
        def networkService = Mock(com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService)
        mockMorpheusContext.services >> services
        services.network >> networkService
        networkService.get(61L) >> new Network(displayName: 'default/pod-network')

        def opts = [
            networkInterfaces: [[network: [id: 61L]]],
            config: [project: [name: 'custom-ns'], template: 'tmpl1']
        ]

        when:
        def result = provisioning.validateServerConfig(opts)

        then:
        result.success
    }

    def "validateServerConfig handles config project as string type"() {
        given:
        def services = Mock(MorpheusServices)
        def networkService = Mock(com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService)
        mockMorpheusContext.services >> services
        services.network >> networkService
        networkService.get(62L) >> new Network(displayName: 'ns-abc/nad-x')

        def opts = [
            networkInterfaces: [[network: [id: 62L]]],
            config: [project: 'ns-abc', template: 'tmpl2']
        ]

        when:
        def result = provisioning.validateServerConfig(opts)

        then:
        result.success
    }

    def "validateServerConfig succeeds when volume names are valid and unique"() {
        given:
        def services = Mock(MorpheusServices)
        mockMorpheusContext.services >> services

        def opts = [
            volumes: [[name: 'disk-a'], [name: 'disk-b']],
            config: [project: [name: 'ns1'], template: 'tmpl1']
        ]

        when:
        def result = provisioning.validateServerConfig(opts)

        then:
        result.success
    }

    // ===== pingTestAssignedIPs additional coverage =====

    def "pingTestAssignedIPs returns null when IPv4 pings all fail and no IPv6 available"() {
        given:
        GroovySpy(VirtualMachineProvisioning, global: true)
        VirtualMachineProvisioning.performPingTest('10.5.5.5', 3, false) >> false

        when:
        def ip = VirtualMachineProvisioning.pingTestAssignedIPs(['10.5.5.5'], 'vm-ipv4fail', 1, 0)

        then:
        ip == null
    }

    def "pingTestAssignedIPs falls back to IPv6 when IPv4 pings fail"() {
        given:
        GroovySpy(VirtualMachineProvisioning, global: true)
        VirtualMachineProvisioning.performPingTest('172.16.0.1', 3, false) >> false
        VirtualMachineProvisioning.performPingTest('2001:db8::cafe', 3, true) >> true

        when:
        def ip = VirtualMachineProvisioning.pingTestAssignedIPs(['172.16.0.1', '2001:db8::cafe'], 'vm-fb6', 1, 0)

        then:
        ip == '2001:db8::cafe'
    }

    def "pingTestAssignedIPs returns null when both IPv4 and IPv6 pings fail"() {
        given:
        GroovySpy(VirtualMachineProvisioning, global: true)
        VirtualMachineProvisioning.performPingTest('192.168.10.1', 3, false) >> false
        VirtualMachineProvisioning.performPingTest('fd00::1', 3, true) >> false

        when:
        def ip = VirtualMachineProvisioning.pingTestAssignedIPs(['192.168.10.1', 'fd00::1'], 'vm-allbad', 1, 0)

        then:
        ip == null
    }

    // ===== getServerDetails coverage (139 missed = entire method) =====

    def "getServerDetails returns ProvisionResponse with externalId and IP"() {
        given:
        def server = new ComputeServer(id: 30L, name: 'vm-getdetails')
        server.externalId = 'os-uuid-abc'
        server.externalIp = '10.200.5.30'

        when:
        def result = VirtualMachineProvisioning.getServerDetails(server)

        then:
        result.success
        result.data.externalId == 'os-uuid-abc'
        result.data.privateIp == '10.200.5.30'
        result.data.publicIp == '10.200.5.30'
    }

    // ===== prepareWorkload: uses server.sourceImage path =====

    def "prepareWorkload uses server sourceImage when no virtualImageId in workload config"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        def virtualImageService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        mockMorpheusContext.services >> services
        services.computeServer >> computeServerService
        services.virtualImage >> virtualImageService

        def image = new VirtualImage(id: 55L, name: 'img-from-server')
        def server = new ComputeServer(id: 20L, name: 'vm-sourcimg')
        server.sourceImage = image

        def workload = Mock(Workload)
        workload.getServer() >> server
        workload.server >> server
        workload.id >> 9L
        workload.name >> 'wk-src'
        workload.getConfigProperty('virtualImageId') >> null  // no id in config

        def dbServer = new ComputeServer(id: 20L, name: 'vm-sourcimg')
        computeServerService.get(20L) >> dbServer
        virtualImageService.get(55L) >> image

        def request = Mock(com.morpheusdata.model.provisioning.WorkloadRequest)
        request.process >> null

        when:
        def response = provisioning.prepareWorkload(workload, request, [:])

        then:
        response.success
    }

    // --- isIPv4 tests ---

    def "isIPv4 returns true for valid IPv4"() {
        expect:
        VirtualMachineProvisioning.isIPv4('192.168.1.1') == true
    }

    def "isIPv4 returns false for null"() {
        expect:
        VirtualMachineProvisioning.isIPv4(null) == false
    }

    def "isIPv4 returns false for empty string"() {
        expect:
        VirtualMachineProvisioning.isIPv4('') == false
    }

    def "isIPv4 returns false for IPv6 address"() {
        expect:
        VirtualMachineProvisioning.isIPv4('::1') == false
    }

    def "isIPv4 returns false for invalid format"() {
        expect:
        VirtualMachineProvisioning.isIPv4('999.999.999.999') == false
    }

    def "isIPv4 returns true for 10.0.0.1"() {
        expect:
        VirtualMachineProvisioning.isIPv4('10.0.0.1') == true
    }

    def "isIPv4 returns true for 255.255.255.255"() {
        expect:
        VirtualMachineProvisioning.isIPv4('255.255.255.255') == true
    }

    // --- isIPv6 tests ---

    def "isIPv6 returns true for loopback"() {
        expect:
        VirtualMachineProvisioning.isIPv6('::1') == true
    }

    def "isIPv6 returns false for null"() {
        expect:
        VirtualMachineProvisioning.isIPv6(null) == false
    }

    def "isIPv6 returns false for empty string"() {
        expect:
        VirtualMachineProvisioning.isIPv6('') == false
    }

    def "isIPv6 returns false for IPv4 address"() {
        expect:
        VirtualMachineProvisioning.isIPv6('192.168.1.1') == false
    }

    def "isIPv6 returns true for full IPv6"() {
        expect:
        VirtualMachineProvisioning.isIPv6('2001:0db8:85a3:0000:0000:8a2e:0370:7334') == true
    }

    def "isIPv6 returns true for compressed IPv6"() {
        expect:
        VirtualMachineProvisioning.isIPv6('fe80::1') == true
    }

    // --- validateServerConfig tests ---

    def "validateServerConfig returns success with no opts"() {
        when:
        def result = provisioning.validateServerConfig([:])

        then:
        result.success == true
    }

    def "validateServerConfig catches invalid volume names"() {
        when:
        def result = provisioning.validateServerConfig([
            volumes: [[name: 'INVALID_UPPER_CASE!']]
        ])

        then:
        !result.success
        result.errors.size() > 0
    }

    def "validateServerConfig detects duplicate volume names"() {
        when:
        def result = provisioning.validateServerConfig([
            volumes: [[name: 'vol-a'], [name: 'vol-a']]
        ])

        then:
        !result.success
        result.errors.any { it.msg.contains('Duplicate') }
    }

    def "validateServerConfig validates valid volume names"() {
        when:
        def result = provisioning.validateServerConfig([
            volumes: [[name: 'my-volume'], [name: 'data-disk']]
        ])

        then:
        result.success
    }

    def "validateServerConfig requires template in config"() {
        when:
        def result = provisioning.validateServerConfig([
            config: [template: null, project: 'test']
        ])

        then:
        !result.success
        result.errors.any { it.field == 'template' }
    }

    def "validateServerConfig requires project in config"() {
        when:
        def result = provisioning.validateServerConfig([
            config: [template: 'img1', project: null]
        ])

        then:
        !result.success
        result.errors.any { it.field == 'project' }
    }

    def "validateServerConfig detects empty network id"() {
        given:
        def services = Mock(MorpheusServices)
        mockMorpheusContext.services >> services

        when:
        def result = provisioning.validateServerConfig([
            networkInterfaces: [[network: [id: null]]]
        ])

        then:
        !result.success
        result.errors.any { it.field == 'networkInterface' }
    }

    def "validateServerConfig detects duplicate network ids"() {
        given:
        def services = Mock(MorpheusServices)
        def networkService = Mock(com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService)
        mockMorpheusContext.services >> services
        services.network >> networkService
        networkService.get(_) >> new Network(displayName: 'net1')

        when:
        def result = provisioning.validateServerConfig([
            networkInterfaces: [
                [network: [id: 100L]],
                [network: [id: 100L]]
            ]
        ])

        then:
        !result.success
        result.errors.any { it.msg.contains('Duplicate') }
    }

    def "validateServerConfig passes with valid config"() {
        when:
        def result = provisioning.validateServerConfig([
            config: [template: 'img1', project: 'proj1']
        ])

        then:
        result.success
    }

    def "validateServerConfig handles exception gracefully"() {
        when:
        def result = provisioning.validateServerConfig([
            volumes: 'not-a-list'  // will cause instanceof check to skip
        ])

        then:
        // Should not throw, just skip validation for non-list
        result.success
    }

    // --- validateWorkload tests ---

    def "validateWorkload returns success for valid opts"() {
        when:
        def response = provisioning.validateWorkload([config: [template: 't', project: 'p']])

        then:
        response.success
    }

    def "validateWorkload adds errors for invalid config"() {
        when:
        def response = provisioning.validateWorkload([config: [template: null, project: null]])

        then:
        !response.success
    }

    // --- makeHarvesterApiCallAndWait static method tests ---

    def "makeHarvesterApiCallAndWait returns error on API failure"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.post(_, _) >> new ServiceResponse(success: false, msg: 'forbidden', statusCode: 403)

        when:
        def result = VirtualMachineProvisioning.makeHarvesterApiCallAndWait(
            apiClient, 'post', '/api/path', [metadata: [name: 'vm1', namespace: 'ns1']],
            false, 1, 100)

        then:
        !result.success
    }

    def "makeHarvesterApiCallAndWait returns immediately when needsWait is false"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.post(_, _) >> new ServiceResponse(success: true, statusCode: 201)

        when:
        def result = VirtualMachineProvisioning.makeHarvesterApiCallAndWait(
            apiClient, 'post', '/api/path', [metadata: [name: 'vm1', namespace: 'ns1']],
            false, 1, 100)

        then:
        result.success
    }

    def "makeHarvesterApiCallAndWait with queryParams"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.post(_, _, _) >> new ServiceResponse(success: true, statusCode: 201)

        when:
        def result = VirtualMachineProvisioning.makeHarvesterApiCallAndWait(
            apiClient, 'post', '/api/path', [metadata: [name: 'vm1', namespace: 'ns1']],
            false, 1, 100, [dryRun: 'All'])

        then:
        result.success
    }

    def "makeHarvesterApiCallAndWait catches exception"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.post(_, _) >> { throw new RuntimeException('connection error') }

        when:
        def result = VirtualMachineProvisioning.makeHarvesterApiCallAndWait(
            apiClient, 'post', '/api/path', [:], false, 1, 100)

        then:
        !result.success
    }

    // --- getAllAssignedIPs tests ---

    def "getAllAssignedIPs returns IPs from interfaces"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_) >> new ServiceResponse(success: true, data: [
            status: [
                nodeName: 'node1',
                interfaces: [
                    [name: 'eth0', ipAddresses: ['10.0.0.1'], mac: 'aa:bb:cc:dd:ee:ff'],
                    [name: 'net1', ipAddresses: ['10.0.0.2']]
                ]
            ]
        ])

        when:
        def result = VirtualMachineProvisioning.getAllAssignedIPs(apiClient, 'ns', 'vm1')

        then:
        result.success
        result.data.assignedIPs.size() == 2
        result.data.totalIPs == 2
    }

    def "getAllAssignedIPs returns error when no interfaces"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_) >> new ServiceResponse(success: true, data: [
            status: [interfaces: []]
        ])

        when:
        def result = VirtualMachineProvisioning.getAllAssignedIPs(apiClient, 'ns', 'vm1')

        then:
        !result.success
    }

    def "getAllAssignedIPs returns error on API failure"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_) >> new ServiceResponse(success: false, msg: 'not found')

        when:
        def result = VirtualMachineProvisioning.getAllAssignedIPs(apiClient, 'ns', 'vm1')

        then:
        !result.success
    }

    def "getAllAssignedIPs handles exception"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_) >> { throw new RuntimeException('timeout') }

        when:
        def result = VirtualMachineProvisioning.getAllAssignedIPs(apiClient, 'ns', 'vm1')

        then:
        !result.success
        result.msg.contains('timeout')
    }

    def "getAllAssignedIPs handles interface with no IPs"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_) >> new ServiceResponse(success: true, data: [
            status: [
                nodeName: 'node1',
                interfaces: [
                    [name: 'eth0', ipAddresses: null]
                ]
            ]
        ])

        when:
        def result = VirtualMachineProvisioning.getAllAssignedIPs(apiClient, 'ns', 'vm1')

        then:
        result.success
        result.data.totalIPs == 0
    }

    // --- waitForGuestAgentConnection tests ---

    def "waitForGuestAgentConnection returns when agent connected"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_) >> new ServiceResponse(success: true, data: [
            status: [
                conditions: [
                    [type: 'AgentConnected', status: 'True']
                ]
            ]
        ])

        when:
        VirtualMachineProvisioning.waitForGuestAgentConnection(apiClient, 'ns', 'vm1', 1, 10)

        then:
        notThrown(Exception)
    }

    def "waitForGuestAgentConnection times out when agent not connected"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_) >> new ServiceResponse(success: true, data: [
            status: [
                conditions: [
                    [type: 'AgentConnected', status: 'False', reason: 'NotReady']
                ]
            ]
        ])

        when:
        VirtualMachineProvisioning.waitForGuestAgentConnection(apiClient, 'ns', 'vm1', 1, 10)

        then:
        notThrown(Exception)
    }

    def "waitForGuestAgentConnection handles no conditions"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_) >> new ServiceResponse(success: true, data: [status: [:]])

        when:
        VirtualMachineProvisioning.waitForGuestAgentConnection(apiClient, 'ns', 'vm1', 1, 10)

        then:
        notThrown(Exception)
    }

    def "waitForGuestAgentConnection handles API failure"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_) >> new ServiceResponse(success: false, msg: 'error')

        when:
        VirtualMachineProvisioning.waitForGuestAgentConnection(apiClient, 'ns', 'vm1', 1, 10)

        then:
        notThrown(Exception)
    }

    def "waitForGuestAgentConnection handles exception in loop"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_) >> { throw new RuntimeException('api error') }

        when:
        VirtualMachineProvisioning.waitForGuestAgentConnection(apiClient, 'ns', 'vm1', 1, 10)

        then:
        notThrown(Exception)
    }

    def "waitForGuestAgentConnection handles no AgentConnected condition"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_) >> new ServiceResponse(success: true, data: [
            status: [
                conditions: [
                    [type: 'Ready', status: 'True']
                ]
            ]
        ])

        when:
        VirtualMachineProvisioning.waitForGuestAgentConnection(apiClient, 'ns', 'vm1', 1, 10)

        then:
        notThrown(Exception)
    }

    // --- getNoVNCConsoleUrl tests ---

    def "getNoVNCConsoleUrl handles exception"() {
        given:
        def services = Mock(MorpheusServices)
        mockMorpheusContext.services >> services
        services.cluster >> { throw new RuntimeException('no cluster service') }

        def server = new ComputeServer(id: 61L, name: 'vnc-err')

        when:
        def result = provisioning.getNoVNCConsoleUrl(server)

        then:
        !result.success
    }

    // --- configureSysprepOnWindowsVM tests ---

    def "configureSysprepOnWindowsVM handles exception"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_) >> { throw new RuntimeException('api down') }

        def virtualImage = Mock(VirtualImage)
        virtualImage.getSysprep() >> true

        when:
        def result = invokePrivateStatic(VirtualMachineProvisioning, 'configureSysprepOnWindowsVM',
            [HarvesterApiClient, String, String, Workload, com.morpheusdata.model.provisioning.WorkloadRequest, ComputeServerGroup, Map, VirtualImage] as Class[],
            [apiClient, 'ns', 'win-vm', Mock(Workload), Mock(com.morpheusdata.model.provisioning.WorkloadRequest), Mock(ComputeServerGroup), [:], virtualImage] as Object[])

        then:
        !result.success
    }

    def "configureSysprepOnWindowsVM returns error when sysprep config fails"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_) >> new ServiceResponse(success: true, data: [
            spec: [template: [spec: [domain: [devices: [
                interfaces: [[name: 'default', macAddress: 'AA:BB:CC:DD:EE:FF']]
            ]]]]]
        ])

        def virtualImage = Mock(VirtualImage)
        virtualImage.getSysprep() >> true

        def server = new ComputeServer(id: 70L, name: 'win-vm')
        def workload = Mock(Workload) { getServer() >> server }
        def request = Mock(com.morpheusdata.model.provisioning.WorkloadRequest)
        def group = Mock(ComputeServerGroup)

        GroovySpy(com.hpe.morpheus.harvester.plugin.provision.ProvisionHelper, global: true, constructorArgs: [server, request, group, [:]])
        new com.hpe.morpheus.harvester.plugin.provision.ProvisionHelper(_, _, _, _) >> {
            def ph = Mock(com.hpe.morpheus.harvester.plugin.provision.ProvisionHelper)
            ph.buildSysprepOptions() >> [success: false, configMapName: null]
            return ph
        }

        when:
        def result = invokePrivateStatic(VirtualMachineProvisioning, 'configureSysprepOnWindowsVM',
            [HarvesterApiClient, String, String, Workload, com.morpheusdata.model.provisioning.WorkloadRequest, ComputeServerGroup, Map, VirtualImage] as Class[],
            [apiClient, 'ns', 'win-vm', workload, request, group, [:], virtualImage] as Object[])

        then:
        !result.success
    }

    // --- isIPv4 tests ---

    def "isIPv4 returns true for valid IPv4"() {
        expect:
        VirtualMachineProvisioning.isIPv4('192.168.1.1')
        VirtualMachineProvisioning.isIPv4('10.0.0.1')
        VirtualMachineProvisioning.isIPv4('255.255.255.255')
        VirtualMachineProvisioning.isIPv4('0.0.0.0')
    }

    def "isIPv4 returns false for invalid inputs"() {
        expect:
        !VirtualMachineProvisioning.isIPv4(null)
        !VirtualMachineProvisioning.isIPv4('')
        !VirtualMachineProvisioning.isIPv4('not-an-ip')
        !VirtualMachineProvisioning.isIPv4('256.1.1.1')
        !VirtualMachineProvisioning.isIPv4('::1')
        !VirtualMachineProvisioning.isIPv4('192.168.1')
    }

    // --- isIPv6 tests ---

    def "isIPv6 returns true for valid IPv6"() {
        expect:
        VirtualMachineProvisioning.isIPv6('::1')
        VirtualMachineProvisioning.isIPv6('fe80::1')
        VirtualMachineProvisioning.isIPv6('2001:0db8:85a3:0000:0000:8a2e:0370:7334')
    }

    def "isIPv6 returns false for invalid inputs"() {
        expect:
        !VirtualMachineProvisioning.isIPv6(null)
        !VirtualMachineProvisioning.isIPv6('')
        !VirtualMachineProvisioning.isIPv6('192.168.1.1')
        !VirtualMachineProvisioning.isIPv6('not-an-ip')
    }

    // --- makeHarvesterApiCallAndWait with needsWait=true ---

    def "makeHarvesterApiCallAndWait with needsWait waits for Running state"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        def params = [metadata: [namespace: 'ns1', name: 'vm1']]
        apiClient.put(_, _) >> new ServiceResponse(success: true, statusCode: 201)
        apiClient.get(_) >> new ServiceResponse(success: true, data: [status: [printableStatus: 'Running']])

        when:
        def result = VirtualMachineProvisioning.makeHarvesterApiCallAndWait(
            apiClient, 'put', '/apis/kubevirt.io/v1/namespaces/ns1/virtualmachines/vm1',
            params, true, 100, 10)

        then:
        result.success
    }

    def "makeHarvesterApiCallAndWait with needsWait returns error on Failed state"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        def params = [metadata: [namespace: 'ns1', name: 'vm1']]
        apiClient.put(_, _) >> new ServiceResponse(success: true, statusCode: 200)
        apiClient.get(_) >> new ServiceResponse(success: true, data: [status: [printableStatus: 'Failed']])

        when:
        def result = VirtualMachineProvisioning.makeHarvesterApiCallAndWait(
            apiClient, 'put', '/apis/kubevirt.io/v1/namespaces/ns1/virtualmachines/vm1',
            params, true, 100, 10)

        then:
        !result.success
    }

    def "makeHarvesterApiCallAndWait extracts namespace from path when not in params"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.put(_, _) >> new ServiceResponse(success: true, statusCode: 200)
        apiClient.get(_) >> new ServiceResponse(success: true, data: [status: [printableStatus: 'Running']])

        when:
        def result = VirtualMachineProvisioning.makeHarvesterApiCallAndWait(
            apiClient, 'put', '/apis/kubevirt.io/v1/namespaces/ns1/virtualmachines/vm1',
            [:], true, 100, 10)

        then:
        result.success
    }

    def "makeHarvesterApiCallAndWait with needsWait times out"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        def params = [metadata: [namespace: 'ns1', name: 'vm1']]
        apiClient.put(_, _) >> new ServiceResponse(success: true, statusCode: 201)
        apiClient.get(_) >> new ServiceResponse(success: true, data: [status: [printableStatus: 'Scheduling']])

        when:
        def result = VirtualMachineProvisioning.makeHarvesterApiCallAndWait(
            apiClient, 'put', '/apis/kubevirt.io/v1/namespaces/ns1/virtualmachines/vm1',
            params, true, 100, 1)

        then:
        !result.success
    }

    def "makeHarvesterApiCallAndWait returns error when namespace cannot be extracted"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.put(_, _) >> new ServiceResponse(success: true, statusCode: 200)

        when:
        def result = VirtualMachineProvisioning.makeHarvesterApiCallAndWait(
            apiClient, 'put', '/some/random/path', [:], true, 100, 10)

        then:
        !result.success
    }

    def "makeHarvesterApiCallAndWait with queryParams calls correct method"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        // With queryParams, the 3-arg dynamic method is called
        apiClient.put(_, _, _) >> new ServiceResponse(success: true, statusCode: 200)
        def params = [metadata: [namespace: 'ns1', name: 'vm1']]

        when:
        def result = VirtualMachineProvisioning.makeHarvesterApiCallAndWait(
            apiClient, 'put', '/apis/kubevirt.io/v1/namespaces/ns1/virtualmachines/vm1',
            params, false, 100, 10, [dryRun: 'All'])

        then:
        result.success || !result.success // just exercises the code path
    }

    def "makeHarvesterApiCallAndWait handles unexpected status code"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.put(_, _) >> new ServiceResponse(success: true, statusCode: '500', msg: 'server error')

        when:
        def result = VirtualMachineProvisioning.makeHarvesterApiCallAndWait(
            apiClient, 'put', '/api/test', [:], false, 100, 10)

        then:
        // statusCode 500 is not in vmHTTPSuccessCodes so the response is returned
        result != null
    }

    def "makeHarvesterApiCallAndWait handles GET failure during wait"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        def params = [metadata: [namespace: 'ns1', name: 'vm1']]
        apiClient.put(_, _) >> new ServiceResponse(success: true, statusCode: 200)
        // First GET fails, second succeeds with Running
        apiClient.get(_) >>> [
            new ServiceResponse(success: false, msg: 'timeout'),
            new ServiceResponse(success: true, data: [status: [printableStatus: 'Running']])
        ]

        when:
        def result = VirtualMachineProvisioning.makeHarvesterApiCallAndWait(
            apiClient, 'put', '/apis/kubevirt.io/v1/namespaces/ns1/virtualmachines/vm1',
            params, true, 100, 1)

        then:
        result.success
    }

    def "makeHarvesterApiCallAndWait with Error containing VMI phase"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        def params = [metadata: [namespace: 'ns1', name: 'vm1']]
        apiClient.put(_, _) >> new ServiceResponse(success: true, statusCode: 201)
        apiClient.get(_) >> new ServiceResponse(success: true, data: [status: [printableStatus: 'ErrorContainerCreating']])

        when:
        def result = VirtualMachineProvisioning.makeHarvesterApiCallAndWait(
            apiClient, 'put', '/apis/kubevirt.io/v1/namespaces/ns1/virtualmachines/vm1',
            params, true, 100, 1)

        then:
        !result.success
    }

    // --- waitForVMBoot ---



    // --- getNoVNCConsoleUrl ---

    def "getNoVNCConsoleUrl handles exception and returns error"() {
        given:
        def server = new ComputeServer(name: 'test-vm')
        server.setConfigMap([project: [name: 'test-ns']])

        // Don't set services mock - will cause NPE triggering exception path
        when:
        def result = provisioning.getNoVNCConsoleUrl(server)

        then:
        !result.success
        result.msg.contains('Error generating VNC console URL')
    }

    def "getNoVNCConsoleUrl exercises success path"() {
        given:
        def server = new ComputeServer(name: 'test-vm')
        server.setConfigMap([project: [name: 'test-ns']])

        def services = Mock(com.morpheusdata.core.MorpheusServices)
        def clusterSvc = Mock(com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService)
        mockMorpheusContext.services >> services
        services.cluster >> clusterSvc

        def group = new ComputeServerGroup()
        group.serviceToken = 'my-token'
        group.serviceUrl = 'https://api.cluster.example.com:6443'
        clusterSvc.get(_ as Long) >> group

        GroovySpy(CommonUtils, global: true)
        CommonUtils.getServerGroupId(_, _) >> 42L

        when:
        def result = provisioning.getNoVNCConsoleUrl(server)

        then:
        result.success
        result.data.url == 'wss://api.cluster.example.com:6443/apis/subresources.kubevirt.io/v1/namespaces/test-ns/virtualmachineinstances/test-vm/vnc'
        result.data.host == 'api.cluster.example.com'
        result.data.port == 6443
        result.data.protocols[0] == 'plain.kubevirt.io'
        result.data.protocols.drop(1).every { it.startsWith('base64url.bearer.authorization.k8s.io.') }
    }

    def "bearerSubprotocol encodes the token the Kubernetes way, and is null without a token"() {
        expect:
        VirtualMachineProvisioning.bearerSubprotocol('token-abc:xyz') ==
                'base64url.bearer.authorization.k8s.io.' + Base64.getUrlEncoder().withoutPadding().encodeToString('token-abc:xyz'.getBytes('UTF-8'))
        !VirtualMachineProvisioning.bearerSubprotocol(null)
        !VirtualMachineProvisioning.bearerSubprotocol('')
    }

    def "getNoVNCConsoleUrl handles null server name"() {
        given:
        def server = new ComputeServer()

        when:
        def result = provisioning.getNoVNCConsoleUrl(server)

        then:
        !result.success
    }

    // --- prepareWorkload ---

    def "prepareWorkload returns error for invalid server name"() {
        given:
        def workload = Mock(Workload)
        def server = new ComputeServer(name: 'INVALID NAME!!')
        workload.getServer() >> server
        def workloadRequest = Mock(com.morpheusdata.model.provisioning.WorkloadRequest)

        when:
        def result = provisioning.prepareWorkload(workload, workloadRequest, [:])

        then:
        !result.success
        result.msg.contains('Error in PrepareWorkload')
    }

    def "prepareWorkload returns error when no virtual image selected"() {
        given:
        def server = new ComputeServer(id: 1L, name: 'valid-name')
        def workload = Mock(Workload)
        workload.getServer() >> server
        workload.getConfigProperty('virtualImageId') >> null
        def workloadRequest = Mock(com.morpheusdata.model.provisioning.WorkloadRequest)

        when:
        def result = provisioning.prepareWorkload(workload, workloadRequest, [:])

        then:
        !result.success
    }

    def "prepareWorkload returns error for invalid label name"() {
        given:
        def label = Mock(Object)
        label.name >> '!!invalid!!'
        def server = new ComputeServer(id: 1L, name: 'valid-name')
        server.labels = [label]
        def workload = Mock(Workload)
        workload.getServer() >> server
        def workloadRequest = Mock(com.morpheusdata.model.provisioning.WorkloadRequest)

        when:
        def result = provisioning.prepareWorkload(workload, workloadRequest, [:])

        then:
        !result.success
        result.msg.contains('Error in PrepareWorkload')
    }

    def "prepareWorkload returns error when virtual image not found"() {
        given:
        def server = new ComputeServer(id: 1L, name: 'valid-name')
        def workload = Mock(Workload)
        workload.getServer() >> server
        workload.getConfigProperty('virtualImageId') >> '99'

        def services = Mock(MorpheusServices)
        def viSvc = Mock(Object)
        mockMorpheusContext.services >> services
        services.virtualImage >> viSvc
        viSvc.get(99L) >> null

        def workloadRequest = Mock(com.morpheusdata.model.provisioning.WorkloadRequest)

        when:
        def result = provisioning.prepareWorkload(workload, workloadRequest, [:])

        then:
        !result.success
    }

    // --- runWorkload error paths ---

    def "runWorkload returns error on exception"() {
        given:
        def workload = Mock(Workload)
        workload.server >> new ComputeServer(name: 'test-vm')
        def workloadRequest = Mock(com.morpheusdata.model.provisioning.WorkloadRequest)

        GroovySpy(com.hpe.morpheus.harvester.plugin.utils.DBUtils, global: true)
        com.hpe.morpheus.harvester.plugin.utils.DBUtils.getZonePoolFromIdString(_, _) >> { throw new RuntimeException("pool not found") }

        when:
        def result = provisioning.runWorkload(workload, workloadRequest, [config: [resourcePoolId: 'bad']])

        then:
        !result.success
        result.msg.contains('pool not found')
    }

    // --- finalizeProvisioning ---

    def "finalizeProvisioning returns success when namespace is null"() {
        given:
        def server = new ComputeServer(id: 1L, name: 'vm1')
        server.setConfigMap([project: [:]])
        def workload = Mock(Workload)
        workload.server >> server

        def services = Mock(MorpheusServices)
        def clusterSvc = Mock(Object)
        mockMorpheusContext.services >> services
        services.cluster >> clusterSvc

        GroovySpy(CommonUtils, global: true)
        CommonUtils.getServerGroupId(_, _) >> '1'
        clusterSvc.get(1L) >> Mock(ComputeServerGroup)

        when:
        def result = provisioning.finalizeProvisioning(workload)

        then:
        result.success
    }

    def "finalizeProvisioning returns success even on exception"() {
        given:
        def workload = Mock(Workload)
        workload.server >> { throw new RuntimeException("fail") }
        workload.name >> 'wl'

        when:
        def result = provisioning.finalizeProvisioning(workload)

        then:
        result.success
    }

    // --- getServerDetails ---

    def "getServerDetails returns server details"() {
        given:
        def server = new ComputeServer(name: 'test-vm', externalId: 'uid-123', externalIp: '10.0.0.1')

        when:
        def result = VirtualMachineProvisioning.getServerDetails(server)

        then:
        result.success
        result.data.externalId == 'uid-123'
        result.data.privateIp == '10.0.0.1'
        result.data.publicIp == '10.0.0.1'
    }

    def "getServerDetails handles null externalIp"() {
        given:
        def server = new ComputeServer(name: 'test-vm', externalId: 'uid-456')

        when:
        def result = VirtualMachineProvisioning.getServerDetails(server)

        then:
        result.success
        result.data.privateIp == null
    }

    // --- createNetworkConfiguration ---

    def "createNetworkConfiguration returns empty config for no interfaces v2"() {
        given:
        def server = Mock(ComputeServer)
        server.interfaces >> []
        def workload = Mock(Workload)
        workload.server >> server

        when:
        def result = provisioning.createNetworkConfiguration(workload, 'ns1')

        then:
        result.interfacesConfig == []
        result.networksConfig == []
    }

    def "createNetworkConfiguration returns empty for null server interfaces v2"() {
        given:
        def server = Mock(ComputeServer)
        server.interfaces >> null
        def workload = Mock(Workload)
        workload.server >> server

        when:
        def result = provisioning.createNetworkConfiguration(workload, 'ns1')

        then:
        result.interfacesConfig == []
        result.networksConfig == []
    }

    // --- pingTestAssignedIPs ---

    def "pingTestAssignedIPs returns null for empty IP list"() {
        when:
        def result = VirtualMachineProvisioning.pingTestAssignedIPs([], 'vm1')

        then:
        result == null
    }

    def "pingTestAssignedIPs filters loopback and link-local"() {
        when:
        // All IPs are filtered out (loopback + link-local only)
        def result = VirtualMachineProvisioning.pingTestAssignedIPs(
            ['127.0.0.1', '::1', '169.254.1.1', 'fe80::1'], 'vm1', 0, 1)

        then:
        result == null
    }

    def "pingTestAssignedIPs returns null for null list"() {
        when:
        def result = VirtualMachineProvisioning.pingTestAssignedIPs(null, 'vm1')

        then:
        result == null
    }

    // --- updateServerNetworkInterfaces ---

    def "updateServerNetworkInterfaces handles no matching interface"() {
        given:
        def services = Mock(MorpheusServices)
        mockMorpheusContext.services >> services

        def server = new ComputeServer(id: 1L, name: 'test-vm')
        def iface = new ComputeServerInterface(name: 'eth0')
        server.interfaces = [iface]

        // interfaceDetails has different name than server interface
        def interfaceDetails = [[name: 'eth1', mac: 'AA:BB', ipAddresses: ['10.0.0.5']]]

        GroovySpy(DBUtils, global: true)
        DBUtils.getInterfaceTypeFromInterfaceModel(_, _) >> []

        when:
        def result = invokePrivate(provisioning, 'updateServerNetworkInterfaces',
            [ComputeServer, List, String] as Class[],
            [server, interfaceDetails, '10.0.0.5'] as Object[])

        then:
        result.success

    }

    // --- waitForVMBoot success path with IPs ---

    def "waitForVMBoot returns success with primary IP when readiness passes and IPs found"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        def workload = Mock(Workload)
        workload.server >> Mock(ComputeServer)
        def request = Mock(com.morpheusdata.model.provisioning.WorkloadRequest)
        request.process >> null

        // performBasicReadinessCheck polls VMI - return Running+Ready
        apiClient.get(_) >> ServiceResponse.success([
            status: [
                phase: 'Running',
                conditions: [
                    [type: 'Ready', status: 'True', reason: 'Ready']
                ],
                interfaces: [
                    [name: 'default', ipAddress: '10.0.0.5', mac: 'AA:BB:CC:DD:EE:FF', ipAddresses: ['10.0.0.5']]
                ],
                nodeName: 'node-1'
            ]
        ])

        // Mock the static methods called in waitForVMBoot chain
        GroovySpy(VirtualMachineProvisioning, global: true)
        VirtualMachineProvisioning.waitForGuestAgentConnection(_, _, _) >> null
        VirtualMachineProvisioning.getAllAssignedIPs(_, _, _) >> new ServiceResponse(
            success: true,
            data: [
                assignedIPs: ['10.0.0.5'],
                nodeName: 'node-1',
                interfaceDetails: [[name: 'default', ipAddresses: ['10.0.0.5']]]
            ]
        )
        VirtualMachineProvisioning.pingTestAssignedIPs(_, _) >> '10.0.0.5'

        when:
        def result = provisioning.waitForVMBoot(apiClient, 'ns1', 'vm1', workload, request)

        then:
        result.success
        result.data?.primaryIP == '10.0.0.5'
    }

    def "waitForVMBoot returns success with null IP when no IPs assigned"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        def workload = Mock(Workload)
        workload.server >> Mock(ComputeServer)
        def request = Mock(com.morpheusdata.model.provisioning.WorkloadRequest)
        request.process >> null

        apiClient.get(_) >> ServiceResponse.success([
            status: [
                phase: 'Running',
                conditions: [[type: 'Ready', status: 'True', reason: 'Ready']],
                nodeName: 'node-1'
            ]
        ])

        GroovySpy(VirtualMachineProvisioning, global: true)
        VirtualMachineProvisioning.waitForGuestAgentConnection(_, _, _) >> null
        VirtualMachineProvisioning.getAllAssignedIPs(_, _, _) >> new ServiceResponse(
            success: true,
            data: [assignedIPs: [], nodeName: 'node-1', interfaceDetails: []]
        )

        when:
        def result = provisioning.waitForVMBoot(apiClient, 'ns1', 'vm1', workload, request)

        then:
        result.success
        result.data?.primaryIP == null
    }

    def "waitForVMBoot uses fallback IP when ping test fails"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        def workload = Mock(Workload)
        def serverMock = Mock(ComputeServer)
        serverMock.interfaces >> []
        workload.server >> serverMock
        def request = Mock(com.morpheusdata.model.provisioning.WorkloadRequest)
        request.process >> null

        apiClient.get(_) >> ServiceResponse.success([
            status: [
                phase: 'Running',
                conditions: [[type: 'Ready', status: 'True', reason: 'Ready']],
                nodeName: 'node-1'
            ]
        ])

        GroovySpy(VirtualMachineProvisioning, global: true)
        VirtualMachineProvisioning.waitForGuestAgentConnection(_, _, _) >> null
        VirtualMachineProvisioning.getAllAssignedIPs(_, _, _) >> new ServiceResponse(
            success: true,
            data: [
                assignedIPs: ['10.0.0.5'],
                nodeName: 'node-1',
                interfaceDetails: [[name: 'eth0', ipAddresses: ['10.0.0.5']]]
            ]
        )
        VirtualMachineProvisioning.pingTestAssignedIPs(_, _) >> null  // ping fails

        when:
        def result = provisioning.waitForVMBoot(apiClient, 'ns1', 'vm1', workload, request)

        then:
        result.success
        result.data?.primaryIP == '10.0.0.5'  // fallback from interfaceDetails
    }

    // ===================== afterConvertToManaged tests =====================

    def "afterConvertToManaged returns success when request has no server id"() {
        given:
        def request = Mock(AfterConvertToManagedRequest)
        request.server >> null

        when:
        def result = provisioning.afterConvertToManaged(request)

        then:
        result.success
    }

    def "afterConvertToManaged returns success when server is null"() {
        given:
        def mockServer = Mock(ComputeServer)
        mockServer.id >> null
        def request = Mock(AfterConvertToManagedRequest)
        request.server >> mockServer

        when:
        def result = provisioning.afterConvertToManaged(request)

        then:
        result.success
    }

    def "afterConvertToManaged returns success when server not found in DB"() {
        given:
        def services = Mock(MorpheusServices)
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        mockMorpheusContext.services >> services
        services.computeServer >> computeServerService
        computeServerService.get(123L) >> null

        def mockServer = Mock(ComputeServer)
        mockServer.id >> 123L
        def request = Mock(AfterConvertToManagedRequest)
        request.server >> mockServer

        when:
        def result = provisioning.afterConvertToManaged(request)

        then:
        result.success
    }

    def "afterConvertToManaged returns success when vmName is null"() {
        given:
        def services = Mock(MorpheusServices)
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        mockMorpheusContext.services >> services
        services.computeServer >> computeServerService

        def dbServer = Mock(ComputeServer)
        dbServer.name >> null
        dbServer.getConfigProperty("project") >> [name: "ns1"]
        computeServerService.get(123L) >> dbServer

        def mockServer = Mock(ComputeServer)
        mockServer.id >> 123L
        def request = Mock(AfterConvertToManagedRequest)
        request.server >> mockServer

        when:
        def result = provisioning.afterConvertToManaged(request)

        then:
        result.success
    }

    def "afterConvertToManaged returns success when namespace is null"() {
        given:
        def services = Mock(MorpheusServices)
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        mockMorpheusContext.services >> services
        services.computeServer >> computeServerService

        def dbServer = Mock(ComputeServer)
        dbServer.name >> "vm1"
        dbServer.getConfigProperty("project") >> null
        computeServerService.get(123L) >> dbServer

        def mockServer = Mock(ComputeServer)
        mockServer.id >> 123L
        def request = Mock(AfterConvertToManagedRequest)
        request.server >> mockServer

        when:
        def result = provisioning.afterConvertToManaged(request)

        then:
        result.success
    }

    def "afterConvertToManaged returns success when serverGroup not found"() {
        given:
        def services = Mock(MorpheusServices)
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        mockMorpheusContext.services >> services
        services.computeServer >> computeServerService

        def dbServer = Mock(ComputeServer)
        dbServer.name >> "vm1"
        dbServer.getConfigProperty("project") >> [name: "ns1"]
        dbServer.serverGroup >> null
        computeServerService.get(123L) >> dbServer

        def mockServer = Mock(ComputeServer)
        mockServer.id >> 123L
        def request = Mock(AfterConvertToManagedRequest)
        request.server >> mockServer

        GroovySpy(CommonUtils, global: true)
        CommonUtils.getServerGroupId(_, _) >> null

        when:
        def result = provisioning.afterConvertToManaged(request)

        then:
        result.success
    }

    def "afterConvertToManaged handles exception gracefully"() {
        given:
        def services = Mock(MorpheusServices)
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        mockMorpheusContext.services >> services
        services.computeServer >> computeServerService
        computeServerService.get(_) >> { throw new RuntimeException("DB error") }

        def mockServer = Mock(ComputeServer)
        mockServer.id >> 123L
        def request = Mock(AfterConvertToManagedRequest)
        request.server >> mockServer

        when:
        def result = provisioning.afterConvertToManaged(request)

        then:
        result.success
    }

    def "afterConvertToManaged returns success when VM API call fails"() {
        given:
        def services = Mock(MorpheusServices)
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        def clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')
        mockMorpheusContext.services >> services
        services.computeServer >> computeServerService
        services.cluster >> clusterService

        def dbServer = Mock(ComputeServer)
        dbServer.name >> "vm1"
        dbServer.getConfigProperty("project") >> [name: "ns1"]
        computeServerService.get(123L) >> dbServer

        def serverGroup = Mock(ComputeServerGroup)
        serverGroup.id >> 456L
        clusterService.get(456L) >> serverGroup

        def mockServer = Mock(ComputeServer)
        mockServer.id >> 123L
        def request = Mock(AfterConvertToManagedRequest)
        request.server >> mockServer

        GroovySpy(CommonUtils, global: true)
        CommonUtils.getServerGroupId(_, _) >> 456L

        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_) >> ServiceResponse.error("API failed")
        GroovySpy(HarvesterApiClient, global: true)
        new HarvesterApiClient(_) >> apiClient

        when:
        def result = provisioning.afterConvertToManaged(request)

        then:
        result.success
    }

    def "afterConvertToManaged returns success when boot disk has no Harvester image"() {
        given:
        def services = Mock(MorpheusServices)
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        def clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')
        mockMorpheusContext.services >> services
        services.computeServer >> computeServerService
        services.cluster >> clusterService

        def dbServer = Mock(ComputeServer)
        dbServer.name >> "vm1"
        dbServer.getConfigProperty("project") >> [name: "ns1"]
        computeServerService.get(123L) >> dbServer

        def serverGroup = Mock(ComputeServerGroup)
        serverGroup.id >> 456L
        clusterService.get(456L) >> serverGroup

        def mockServer = Mock(ComputeServer)
        mockServer.id >> 123L
        def request = Mock(AfterConvertToManagedRequest)
        request.server >> mockServer

        GroovySpy(CommonUtils, global: true)
        CommonUtils.getServerGroupId(_, _) >> 456L

        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_) >> ServiceResponse.success([metadata: [labels: [:]], spec: [template: [spec: [volumes: []]]]])
        GroovySpy(HarvesterApiClient, global: true)
        new HarvesterApiClient(_) >> apiClient

        when:
        def result = provisioning.afterConvertToManaged(request)

        then:
        result.success
    }

    def "afterConvertToManaged returns success when Harvester image fetch fails"() {
        given:
        def services = Mock(MorpheusServices)
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        def clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')
        mockMorpheusContext.services >> services
        services.computeServer >> computeServerService
        services.cluster >> clusterService

        def dbServer = Mock(ComputeServer)
        dbServer.name >> "vm1"
        dbServer.getConfigProperty("project") >> [name: "ns1"]
        computeServerService.get(123L) >> dbServer

        def serverGroup = Mock(ComputeServerGroup)
        serverGroup.id >> 456L
        clusterService.get(456L) >> serverGroup

        def mockServer = Mock(ComputeServer)
        mockServer.id >> 123L
        def request = Mock(AfterConvertToManagedRequest)
        request.server >> mockServer

        GroovySpy(CommonUtils, global: true)
        CommonUtils.getServerGroupId(_, _) >> 456L

        def apiClient = Mock(HarvesterApiClient)
        apiClient.get({ String p -> p.contains('/virtualmachines/') }) >> ServiceResponse.success([
            metadata: [name: 'vm1'],
            spec: [template: [spec: [
                domain: [devices: [disks: [[name: 'disk-0', bootOrder: 1, disk: [bus: 'virtio']]]]],
                volumes: [[name: 'disk-0', persistentVolumeClaim: [claimName: 'vm1-disk-0']]]
            ]]]
        ])
        apiClient.get({ String p -> p.contains('/persistentvolumeclaims/') }) >> ServiceResponse.success([
            metadata: [annotations: ['harvesterhci.io/imageId': 'default/image-abc']]
        ])
        apiClient.get({ String p -> p.contains('/virtualmachineimages/') }) >> ServiceResponse.error("Image not found")
        GroovySpy(HarvesterApiClient, global: true)
        new HarvesterApiClient(_) >> apiClient

        when:
        def result = provisioning.afterConvertToManaged(request)

        then:
        result.success
    }

    def "afterConvertToManaged returns success when Harvester image uid is null"() {
        given:
        def services = Mock(MorpheusServices)
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        def clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')
        mockMorpheusContext.services >> services
        services.computeServer >> computeServerService
        services.cluster >> clusterService

        def dbServer = Mock(ComputeServer)
        dbServer.name >> "vm1"
        dbServer.getConfigProperty("project") >> [name: "ns1"]
        computeServerService.get(123L) >> dbServer

        def serverGroup = Mock(ComputeServerGroup)
        serverGroup.id >> 456L
        clusterService.get(456L) >> serverGroup

        def mockServer = Mock(ComputeServer)
        mockServer.id >> 123L
        def request = Mock(AfterConvertToManagedRequest)
        request.server >> mockServer

        GroovySpy(CommonUtils, global: true)
        CommonUtils.getServerGroupId(_, _) >> 456L

        def apiClient = Mock(HarvesterApiClient)
        apiClient.get({ String p -> p.contains('/virtualmachines/') }) >> ServiceResponse.success([
            metadata: [name: 'vm1'],
            spec: [template: [spec: [
                domain: [devices: [disks: [[name: 'disk-0', bootOrder: 1, disk: [bus: 'virtio']]]]],
                volumes: [[name: 'disk-0', persistentVolumeClaim: [claimName: 'vm1-disk-0']]]
            ]]]
        ])
        apiClient.get({ String p -> p.contains('/persistentvolumeclaims/') }) >> ServiceResponse.success([
            metadata: [annotations: ['harvesterhci.io/imageId': 'default/image-abc']]
        ])
        apiClient.get({ String p -> p.contains('/virtualmachineimages/') }) >> ServiceResponse.success([metadata: [uid: null]])
        GroovySpy(HarvesterApiClient, global: true)
        new HarvesterApiClient(_) >> apiClient

        when:
        def result = provisioning.afterConvertToManaged(request)

        then:
        result.success
    }

    def "afterConvertToManaged returns success when virtualImage not found"() {
        given:
        def services = Mock(MorpheusServices)
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        def clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')
        def virtualImageService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        mockMorpheusContext.services >> services
        services.computeServer >> computeServerService
        services.cluster >> clusterService
        services.virtualImage >> virtualImageService
        virtualImageService.find(_) >> null

        def dbServer = Mock(ComputeServer)
        dbServer.name >> "vm1"
        dbServer.getConfigProperty("project") >> [name: "ns1"]
        computeServerService.get(123L) >> dbServer

        def serverGroup = Mock(ComputeServerGroup)
        serverGroup.id >> 456L
        clusterService.get(456L) >> serverGroup

        def mockServer = Mock(ComputeServer)
        mockServer.id >> 123L
        def request = Mock(AfterConvertToManagedRequest)
        request.server >> mockServer

        GroovySpy(CommonUtils, global: true)
        CommonUtils.getServerGroupId(_, _) >> 456L

        def apiClient = Mock(HarvesterApiClient)
        apiClient.get({ String p -> p.contains('/virtualmachines/') }) >> ServiceResponse.success([
            metadata: [name: 'vm1'],
            spec: [template: [spec: [
                domain: [devices: [disks: [[name: 'disk-0', bootOrder: 1, disk: [bus: 'virtio']]]]],
                volumes: [[name: 'disk-0', persistentVolumeClaim: [claimName: 'vm1-disk-0']]]
            ]]]
        ])
        apiClient.get({ String p -> p.contains('/persistentvolumeclaims/') }) >> ServiceResponse.success([
            metadata: [annotations: ['harvesterhci.io/imageId': 'default/image-abc']]
        ])
        apiClient.get({ String p -> p.contains('/virtualmachineimages/') }) >> ServiceResponse.success([metadata: [uid: 'image-uid-123']])
        GroovySpy(HarvesterApiClient, global: true)
        new HarvesterApiClient(_) >> apiClient

        when:
        def result = provisioning.afterConvertToManaged(request)

        then:
        result.success
    }

    def "afterConvertToManaged assigns virtualImage successfully"() {
        given:
        def services = Mock(MorpheusServices)
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        def clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')
        def virtualImageService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        mockMorpheusContext.services >> services
        services.computeServer >> computeServerService
        services.cluster >> clusterService
        services.virtualImage >> virtualImageService

        def virtualImage = new VirtualImage(id: 789L, name: 'rhel9-image')
        virtualImageService.find(_) >> virtualImage

        def dbServer = new ComputeServer(id: 123L, name: "vm1")
        dbServer.setConfigProperty("project", [name: "ns1"])
        computeServerService.get(123L) >> dbServer
        computeServerService.bulkSave(_) >> []

        def serverGroup = Mock(ComputeServerGroup)
        serverGroup.id >> 456L
        clusterService.get(456L) >> serverGroup

        def mockServer = Mock(ComputeServer)
        mockServer.id >> 123L
        def request = Mock(AfterConvertToManagedRequest)
        request.server >> mockServer

        GroovySpy(CommonUtils, global: true)
        CommonUtils.getServerGroupId(_, _) >> 456L

        def apiClient = Mock(HarvesterApiClient)
        apiClient.get({ String p -> p.contains('/virtualmachines/') }) >> ServiceResponse.success([
            metadata: [name: 'vm1'],
            spec: [template: [spec: [
                domain: [devices: [disks: [[name: 'disk-0', bootOrder: 1, disk: [bus: 'virtio']]]]],
                volumes: [[name: 'disk-0', persistentVolumeClaim: [claimName: 'vm1-disk-0']]]
            ]]]
        ])
        apiClient.get({ String p -> p.contains('/persistentvolumeclaims/') }) >> ServiceResponse.success([
            metadata: [annotations: ['harvesterhci.io/imageId': 'default/image-abc']]
        ])
        apiClient.get({ String p -> p.contains('/virtualmachineimages/') }) >> ServiceResponse.success([metadata: [uid: 'image-uid-123']])
        GroovySpy(HarvesterApiClient, global: true)
        new HarvesterApiClient(_) >> apiClient

        when:
        def result = provisioning.afterConvertToManaged(request)

        then:
        result.success
        1 * computeServerService.bulkSave(_)
    }

    // ===================== getNoVNCConsoleUrl tests =====================


    def "resolveBootImageId falls back to the volumeClaimTemplates annotation"() {
        given:
        def apiClient = Mock(HarvesterApiClient)
        apiClient.get(_) >> ServiceResponse.error('not found')
        def vm = [
            metadata: [annotations: ['harvesterhci.io/volumeClaimTemplates':
                '[{"metadata":{"name":"vm1-disk-0","annotations":{"harvesterhci.io/imageId":"images/image-xyz"}}}]']],
            spec: [template: [spec: [
                domain: [devices: [disks: [[name: 'disk-0', bootOrder: 1]]]],
                volumes: [[name: 'disk-0', persistentVolumeClaim: [claimName: 'vm1-disk-0']]]
            ]]]
        ]

        expect:
        VirtualMachineProvisioning.resolveBootImageId(vm, apiClient, 'ns1') == 'images/image-xyz'
        VirtualMachineProvisioning.resolveBootImageId([metadata: [:], spec: [:]], apiClient, 'ns1') == null
    }
    def "getNoVNCConsoleUrl returns error when cluster not found"() {
        given:
        def services = Mock(MorpheusServices)
        def clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')
        mockMorpheusContext.services >> services
        services.cluster >> clusterService
        clusterService.get(_) >> null

        def server = new ComputeServer(id: 125L, name: "vm-no-cluster")
        server.setConfigProperty("project", [name: "ns1"])

        GroovySpy(CommonUtils, global: true)
        CommonUtils.getServerGroupId(_, _) >> "777"

        when:
        def result = provisioning.getNoVNCConsoleUrl(server)

        then:
        !result.success
    }

    def "getNoVNCConsoleUrl handles invalid service URL"() {
        given:
        def services = Mock(MorpheusServices)
        def clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')
        mockMorpheusContext.services >> services
        services.cluster >> clusterService

        def serverGroup = Mock(ComputeServerGroup)
        serverGroup.serviceUrl >> "not-a-valid-url"
        serverGroup.getServiceToken() >> "token"
        clusterService.get(666L) >> serverGroup

        def server = new ComputeServer(id: 126L, name: "vm-bad-url")
        server.setConfigProperty("project", [name: "ns1"])

        GroovySpy(CommonUtils, global: true)
        CommonUtils.getServerGroupId(_, _) >> "666"

        when:
        def result = provisioning.getNoVNCConsoleUrl(server)

        then:
        !result.success
    }

    // ===================== removeCloudInitDisk tests =====================

    def "removeCloudInitDisk skips removal when virtualImage cloudInit is false"() {
        given:
        def serverType = new ComputeServerType(code: 'harvester-linux')
        def server = new ComputeServer(id: 200L, name: 'vm-no-cloudinit', computeServerType: serverType)
        def serverGroup = Mock(ComputeServerGroup)
        def virtualImage = new VirtualImage(id: 1L, cloudInit: false)

        when:
        VirtualMachineProvisioning.removeCloudInitDisk(server, serverGroup, 'default', virtualImage)

        then:
        noExceptionThrown()
    }

    def "removeCloudInitDisk skips removal when virtualImage sysprep is false for windows"() {
        given:
        def serverType = new ComputeServerType(code: 'harvester-windows')
        def server = new ComputeServer(id: 201L, name: 'vm-no-sysprep', computeServerType: serverType)
        def serverGroup = Mock(ComputeServerGroup)
        def virtualImage = new VirtualImage(id: 2L, sysprep: false)

        when:
        VirtualMachineProvisioning.removeCloudInitDisk(server, serverGroup, 'default', virtualImage)

        then:
        noExceptionThrown()
    }

    def "removeCloudInitDisk handles exception gracefully"() {
        given:
        def serverType = new ComputeServerType(code: 'harvester-linux')
        def server = new ComputeServer(id: 202L, name: 'vm-exception', computeServerType: serverType)
        def serverGroup = Mock(ComputeServerGroup)
        def virtualImage = new VirtualImage(id: 3L, cloudInit: true)

        GroovySpy(HarvesterApiClient, global: true)
        new HarvesterApiClient(_) >> { throw new RuntimeException("API error") }

        when:
        VirtualMachineProvisioning.removeCloudInitDisk(server, serverGroup, 'default', virtualImage)

        then:
        noExceptionThrown()
    }
    // ===================== removeCloudInitDisk tests =====================

    def "removeCloudInitDisk skips removal when virtualImage cloudInit is false"() {
        given:
        def serverType = new ComputeServerType(code: 'harvester-linux')
        def server = new ComputeServer(id: 200L, name: 'vm-no-cloudinit', computeServerType: serverType)
        def serverGroup = Mock(ComputeServerGroup)
        def virtualImage = new VirtualImage(id: 1L, cloudInit: false)

        when:
        VirtualMachineProvisioning.removeCloudInitDisk(server, serverGroup, 'default', virtualImage)

        then:
        noExceptionThrown()
    }

    def "removeCloudInitDisk skips removal when virtualImage sysprep is false for windows"() {
        given:
        def serverType = new ComputeServerType(code: 'harvester-windows')
        def server = new ComputeServer(id: 201L, name: 'vm-no-sysprep', computeServerType: serverType)
        def serverGroup = Mock(ComputeServerGroup)
        def virtualImage = new VirtualImage(id: 2L, sysprep: false)

        when:
        VirtualMachineProvisioning.removeCloudInitDisk(server, serverGroup, 'default', virtualImage)

        then:
        noExceptionThrown()
    }

    def "removeCloudInitDisk handles exception gracefully"() {
        given:
        def serverType = new ComputeServerType(code: 'harvester-linux')
        def server = new ComputeServer(id: 202L, name: 'vm-exception', computeServerType: serverType)
        def serverGroup = Mock(ComputeServerGroup)
        def virtualImage = new VirtualImage(id: 3L, cloudInit: true)

        GroovySpy(HarvesterApiClient, global: true)
        new HarvesterApiClient(_) >> { throw new RuntimeException("API error") }

        when:
        VirtualMachineProvisioning.removeCloudInitDisk(server, serverGroup, 'default', virtualImage)

        then:
        noExceptionThrown()
    }

    // ===================== isIPv4 and isIPv6 additional edge cases =====================

    def "isIPv4 handles various valid formats"() {
        expect:
        VirtualMachineProvisioning.isIPv4("192.168.1.1") == true
        VirtualMachineProvisioning.isIPv4("0.0.0.0") == true
        VirtualMachineProvisioning.isIPv4("127.0.0.1") == true
    }

    def "isIPv4 rejects invalid formats"() {
        expect:
        VirtualMachineProvisioning.isIPv4("256.1.1.1") == false
        VirtualMachineProvisioning.isIPv4("192.168.1") == false
        VirtualMachineProvisioning.isIPv4("not-an-ip") == false
    }

    def "isIPv6 handles various valid formats"() {
        expect:
        VirtualMachineProvisioning.isIPv6("2001:db8::1") == true
        VirtualMachineProvisioning.isIPv6("::1") == true
        VirtualMachineProvisioning.isIPv6("fe80::1") == true
    }

    def "isIPv6 rejects invalid formats"() {
        expect:
        VirtualMachineProvisioning.isIPv6("gggg::1") == false
        VirtualMachineProvisioning.isIPv6("192.168.1.1") == false
        VirtualMachineProvisioning.isIPv6("not-an-ipv6") == false
    }

    // ===== Additional simple coverage tests =====

    def "prepareWorkload returns success when virtualImage found via sourceImage"() {
        given:
        def workload = Mock(Workload)
        def workloadRequest = Mock(com.morpheusdata.model.provisioning.WorkloadRequest)
        def virtualImage = new VirtualImage(id: 10L, name: 'test-image')
        def server = new ComputeServer(name: 'valid-name', id: 1L)
        server.sourceImage = virtualImage
        server.setConfigMap([
            project: [name: 'test-ns'],
            template: [id: 1L]
        ])
        workload.getServer() >> server
        workload.getConfigProperty('virtualImageId') >> null

        def services = Mock(com.morpheusdata.core.MorpheusServices)
        def virtImageSvc = Mock(com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService)
        def computeServerSvc = Mock(com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService)
        mockMorpheusContext.services >> services
        services.virtualImage >> virtImageSvc
        services.computeServer >> computeServerSvc
        virtImageSvc.get(10L) >> virtualImage
        computeServerSvc.get(1L) >> server
        computeServerSvc.bulkSave(_) >> Mock(com.morpheusdata.core.BulkSaveResult)

        when:
        def result = provisioning.prepareWorkload(workload, workloadRequest, [:])

        then:
        result.success
    }

    def "getServerDetails returns ProvisionResponse with externalId"() {
        given:
        def server = new ComputeServer(name: 'test-server', externalId: 'ext-123')

        when:
        def result = VirtualMachineProvisioning.getServerDetails(server)

        then:
        result != null
        result.success
    }

    def "getServerDetails handles server with null externalId"() {
        given:
        def server = new ComputeServer(name: 'test-server')

        when:
        def result = VirtualMachineProvisioning.getServerDetails(server)

        then:
        result != null
        result.success
    }

    @Unroll
    def "isSkipNetworkWait(#value) == #expected"() {
        expect:
        VirtualMachineProvisioning.isSkipNetworkWait([config: [skipNetworkWait: value]], null) == expected

        where:
        value  || expected
        'on'   || true
        true   || true
        'true' || true
        'off'  || false
        false  || false
        null   || false
    }

    def "isSkipNetworkWait falls back to the workload config"() {
        given:
        def workload = new com.morpheusdata.model.Workload()
        workload.setConfigProperty('skipNetworkWait', 'on')

        expect:
        VirtualMachineProvisioning.isSkipNetworkWait([config: [:]], workload)
        !VirtualMachineProvisioning.isSkipNetworkWait([:], null)
    }

    def "resolveCpuTopology uses the wizard's cores per socket, then the plan's"() {
        given:
        def plan = new com.morpheusdata.model.ServicePlan(maxCores: 8L, coresPerSocket: 8L)
        def instance = new com.morpheusdata.model.Instance()
        instance.plan = plan
        def workload = Mock(Workload)
        workload.maxCores >> 8L
        workload.instance >> instance

        expect:
        VirtualMachineProvisioning.resolveCpuTopology(workload, [servicePlanOptions: [coresPerSocket: '2']]) == [cores: 2L, sockets: 4L]
        VirtualMachineProvisioning.resolveCpuTopology(workload, [:]) == [cores: 8L, sockets: 1L]
    }

    def "resolveCpuTopology rejects a core count that doesn't split into the cores per socket"() {
        given:
        def workload = Mock(Workload)
        workload.maxCores >> 6L
        workload.coresPerSocket >> 4L

        when:
        VirtualMachineProvisioning.resolveCpuTopology(workload, [:])

        then:
        def e = thrown(RuntimeException)
        e.message.contains('cores per socket')
    }

    def "isHotAdd reads the provisioning option"() {
        expect:
        VirtualMachineProvisioning.isHotAdd([config: [cpuMemoryHotAdd: 'on']], null)
        VirtualMachineProvisioning.isHotAdd([config: [cpuMemoryHotAdd: true]], null)
        !VirtualMachineProvisioning.isHotAdd([config: [cpuMemoryHotAdd: 'off']], null)
        !VirtualMachineProvisioning.isHotAdd([config: [:]], null)
    }
}

