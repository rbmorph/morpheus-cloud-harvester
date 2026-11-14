package com.hpe.morpheus.harvester.plugin.provision

import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.hpe.morpheus.harvester.plugin.utils.CommonUtils
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification

class VirtualMachineDeletionSpec extends Specification {

    private def typedMock(String className) {
        Mock((Class) Class.forName(className))
    }

    MorpheusContext morpheusContext
    VirtualMachineDeletion deletion

    def setup() {
        morpheusContext = Mock(MorpheusContext)
        deletion = new VirtualMachineDeletion(morpheusContext)
    }

    // Constructor Tests

    def "constructor should initialize morpheusContext"() {
        when:
        def newDeletion = new VirtualMachineDeletion(morpheusContext)

        then:
        newDeletion.morpheusContext == morpheusContext
    }

    // getVMApiResourcePath Tests

    def "getVMApiResourcePath should construct correct path"() {
        when:
        def result = VirtualMachineDeletion.getVMApiResourcePath('test-namespace', 'my-vm')

        then:
        result == '/apis/kubevirt.io/v1/namespaces/test-namespace/virtualmachines/my-vm'
    }

    def "getVMApiResourcePath should handle different namespaces"() {
        when:
        def result = VirtualMachineDeletion.getVMApiResourcePath('production', 'app-server')

        then:
        result == '/apis/kubevirt.io/v1/namespaces/production/virtualmachines/app-server'
    }

    // deleteVirtualMachine Tests

    def "deleteVirtualMachine should return true when VM deleted successfully"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.error('Not Found', null, [code: 404])

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachine(mockApiClient, 'test-ns', 'test-vm')

        then:
        result == true
    }

    def "deleteVirtualMachine should return true when VM already deleted (404)"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.error('Not Found', null, [code: 404])

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachine(mockApiClient, 'test-ns', 'test-vm')

        then:
        result == true
        0 * mockApiClient.delete(_)
    }


    def "deleteVirtualMachine should return false when VM fetch fails"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.error('Server Error', null, [code: 500])

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachine(mockApiClient, 'test-ns', 'test-vm')

        then:
        result == false
    }

    def "deleteVirtualMachine should return false when delete fails"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.success([metadata: [name: 'test-vm']])
        mockApiClient.delete(_) >> ServiceResponse.error('Delete Failed')

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachine(mockApiClient, 'test-ns', 'test-vm')

        then:
        result == false
    }

    def "deleteVirtualMachine should handle exceptions gracefully"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> { throw new RuntimeException('Network error') }

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachine(mockApiClient, 'test-ns', 'test-vm')

        then:
        result == false
    }

    def "deleteVirtualMachine should return true when deletion polling times out"() {
        given:
        VirtualMachineDeletion.metaClass.'static'.sleep = { long ms -> }
        def mockApiClient = Mock(HarvesterApiClient)
        int callCount = 0
        mockApiClient.get(_) >> {
            callCount++
            if (callCount == 1) {
                return ServiceResponse.success([status: [printableStatus: 'Running']])
            }
            return ServiceResponse.success([metadata: [:], status: [printableStatus: 'Terminating']])
        }
        mockApiClient.delete(_) >> ServiceResponse.success([:])

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachine(mockApiClient, 'test-ns', 'test-vm')

        then:
        result
    }

    def "deleteVirtualMachine should handle deletion timestamp then 404"() {
        given:
        VirtualMachineDeletion.metaClass.'static'.sleep = { long ms -> }
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >>> [
            ServiceResponse.success([status: [printableStatus: 'Running']]),
            ServiceResponse.success([metadata: [deletionTimestamp: '2026-02-19T11:00:00Z']]),
            ServiceResponse.error('Not Found', null, [code: 404])
        ]
        mockApiClient.delete(_) >> ServiceResponse.success([:])

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachine(mockApiClient, 'test-ns', 'vm-delete-ts')

        then:
        result
    }

    // deleteVirtualMachineInstance Tests

    def "deleteVirtualMachineInstance should return true when VMI deleted"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.delete(_) >> ServiceResponse.success()
        mockApiClient.get(_) >> ServiceResponse.error('Not Found', null, [code: 404])

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachineInstance(mockApiClient, 'test-ns', 'test-vm')

        then:
        result == true
    }

    def "deleteVirtualMachineInstance should return true when VMI not exists (404)"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.error('Not Found', null, [code: 404])

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachineInstance(mockApiClient, 'test-ns', 'test-vm')

        then:
        result == true
        0 * mockApiClient.delete(_)
    }

    def "deleteVirtualMachineInstance should return false when VMI check fails"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.error('Server Error', null, [code: 500])

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachineInstance(mockApiClient, 'test-ns', 'test-vm')

        then:
        result == false
    }

    def "deleteVirtualMachineInstance should handle exceptions"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> { throw new Exception('API Error') }

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachineInstance(mockApiClient, 'test-ns', 'test-vm')

        then:
        result == false
    }

    def "deleteVirtualMachineInstance should return false when delete call fails"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.success([status: [phase: 'Running']])
        mockApiClient.delete(_) >> ServiceResponse.error('Delete failed')

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachineInstance(mockApiClient, 'test-ns', 'test-vm')

        then:
        !result
    }

    def "deleteVirtualMachineInstance should handle deletion timestamp then 404"() {
        given:
        VirtualMachineDeletion.metaClass.'static'.sleep = { long ms -> }
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >>> [
            ServiceResponse.success([status: [phase: 'Running']]),
            ServiceResponse.success([metadata: [deletionTimestamp: '2026-02-18T10:00:00Z']]),
            ServiceResponse.error('Not Found', null, [code: 404])
        ]
        mockApiClient.delete(_) >> ServiceResponse.success([:])

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachineInstance(mockApiClient, 'test-ns', 'test-vm')

        then:
        result
    }

    def "deleteVirtualMachineInstance should return true when deletion polling times out"() {
        given:
        VirtualMachineDeletion.metaClass.'static'.sleep = { long ms -> }
        def mockApiClient = Mock(HarvesterApiClient)
        int callCount = 0
        mockApiClient.get(_) >> {
            callCount++
            if (callCount == 1) {
                return ServiceResponse.success([status: [phase: 'Running']])
            }
            return ServiceResponse.success([metadata: [:], status: [phase: 'Running']])
        }
        mockApiClient.delete(_) >> ServiceResponse.success([:])

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachineInstance(mockApiClient, 'test-ns', 'vm-vmi-timeout')

        then:
        result
    }

    // deleteAssociatedVolumes Tests

    def "deleteAssociatedVolumes should skip when no volumes"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        def server = Mock(ComputeServer)
        server.hostname >> 'test-vm'
        server.volumes >> []

        when:
        VirtualMachineDeletion.deleteAssociatedVolumes(mockApiClient, server, 'test-ns')

        then:
        0 * mockApiClient.delete(_)
    }

    def "deleteAssociatedVolumes should delete DataVolumes"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        def volumeType = Mock(StorageVolumeType)
        volumeType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME
        
        def volume = Mock(StorageVolume)
        volume.name >> 'test-dv'
        volume.type >> volumeType
        
        def server = Mock(ComputeServer)
        server.hostname >> 'test-vm'
        server.volumes >> [volume]
        
        mockApiClient.delete(_) >> ServiceResponse.success()

        when:
        VirtualMachineDeletion.deleteAssociatedVolumes(mockApiClient, server, 'test-ns')

        then:
        1 * mockApiClient.delete('/apis/cdi.kubevirt.io/v1beta1/namespaces/test-ns/datavolumes/test-dv')
    }

    def "deleteAssociatedVolumes should delete PVCs"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        def volumeType = Mock(StorageVolumeType)
        volumeType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC
        
        def volume = Mock(StorageVolume)
        volume.name >> 'test-pvc'
        volume.type >> volumeType
        
        def server = Mock(ComputeServer)
        server.hostname >> 'test-vm'
        server.volumes >> [volume]

        // No DataVolume exists for this PVC-typed volume — GET returns 404, fallback to PVC delete
        mockApiClient.get('/apis/cdi.kubevirt.io/v1beta1/namespaces/test-ns/datavolumes/test-pvc') >>
                ServiceResponse.error('Not Found', null, [code: 404])
        mockApiClient.delete(_) >> ServiceResponse.success()

        when:
        VirtualMachineDeletion.deleteAssociatedVolumes(mockApiClient, server, 'test-ns')

        then:
        1 * mockApiClient.delete('/api/v1/namespaces/test-ns/persistentvolumeclaims/test-pvc')
    }

    def "deleteAssociatedVolumes should skip unknown volume types"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        def volumeType = Mock(StorageVolumeType)
        volumeType.code >> 'unknown-type'
        
        def volume = Mock(StorageVolume)
        volume.name >> 'test-vol'
        volume.type >> volumeType
        
        def server = Mock(ComputeServer)
        server.hostname >> 'test-vm'
        server.volumes >> [volume]

        when:
        VirtualMachineDeletion.deleteAssociatedVolumes(mockApiClient, server, 'test-ns')

        then:
        0 * mockApiClient.delete(_)
    }

    def "deleteAssociatedVolumes should handle exceptions gracefully"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        def server = Mock(ComputeServer)
        server.hostname >> 'test-vm'
        server.volumes >> { throw new Exception('Volume fetch error') }

        when:
        VirtualMachineDeletion.deleteAssociatedVolumes(mockApiClient, server, 'test-ns')

        then:
        notThrown(Exception)
    }

    // deleteDataVolume Tests

    def "deleteDataVolume should delete successfully when DV exists"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.delete(_) >> ServiceResponse.success()

        when:
        VirtualMachineDeletion.deleteDataVolume(mockApiClient, 'test-ns', 'my-dv')

        then:
        1 * mockApiClient.delete('/apis/cdi.kubevirt.io/v1beta1/namespaces/test-ns/datavolumes/my-dv')
    }

    def "deleteDataVolume should handle 404 gracefully"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.delete(_) >> ServiceResponse.error('Not Found', null, [code: 404])

        when:
        VirtualMachineDeletion.deleteDataVolume(mockApiClient, 'test-ns', 'my-dv')

        then:
        notThrown(Exception)
    }

    def "deleteDataVolume should handle deletion failure"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.delete(_) >> ServiceResponse.error('Delete Failed')

        when:
        VirtualMachineDeletion.deleteDataVolume(mockApiClient, 'test-ns', 'my-dv')

        then:
        notThrown(Exception)
    }

    def "deleteDataVolume should handle exceptions"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.delete(_) >> { throw new Exception('API Error') }

        when:
        VirtualMachineDeletion.deleteDataVolume(mockApiClient, 'test-ns', 'my-dv')

        then:
        notThrown(Exception)
    }

    // deletePVC Tests

    def "deletePVC should delete successfully"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.delete(_) >> ServiceResponse.success()

        when:
        VirtualMachineDeletion.deletePVC(mockApiClient, 'test-ns', 'my-pvc')

        then:
        1 * mockApiClient.delete('/api/v1/namespaces/test-ns/persistentvolumeclaims/my-pvc')
    }

    def "deletePVC should handle 404 gracefully"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.delete(_) >> ServiceResponse.error('Not Found', null, [code: 404])

        when:
        VirtualMachineDeletion.deletePVC(mockApiClient, 'test-ns', 'my-pvc')

        then:
        notThrown(Exception)
    }

    def "deletePVC should handle exceptions"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.delete(_) >> { throw new RuntimeException('Network error') }

        when:
        VirtualMachineDeletion.deletePVC(mockApiClient, 'test-ns', 'my-pvc')

        then:
        notThrown(Exception)
    }

    // deleteSecret Tests

    def "deleteSecret should check existence before deleting"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.success([metadata: [name: 'my-secret']])
        mockApiClient.delete(_) >> ServiceResponse.success()

        when:
        VirtualMachineDeletion.deleteSecret(mockApiClient, 'test-ns', 'my-secret')

        then:
        notThrown(Exception)
    }

    def "deleteSecret should skip when secret not exists"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.error('Not Found', null, [code: 404])

        when:
        VirtualMachineDeletion.deleteSecret(mockApiClient, 'test-ns', 'my-secret')

        then:
        0 * mockApiClient.delete(_)
    }

    def "deleteSecret should handle get failure"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.error('Server Error')

        when:
        VirtualMachineDeletion.deleteSecret(mockApiClient, 'test-ns', 'my-secret')

        then:
        0 * mockApiClient.delete(_)
        notThrown(Exception)
    }

    def "deleteSecret should handle delete 404"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.success([metadata: [name: 'my-secret']])
        mockApiClient.delete(_) >> ServiceResponse.error('Not Found', null, [code: 404])

        when:
        VirtualMachineDeletion.deleteSecret(mockApiClient, 'test-ns', 'my-secret')

        then:
        notThrown(Exception)
    }

    def "deleteSecret should handle exceptions"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> { throw new Exception('API Error') }

        when:
        VirtualMachineDeletion.deleteSecret(mockApiClient, 'test-ns', 'my-secret')

        then:
        notThrown(Exception)
    }

    // deleteConfigMap Tests

    def "deleteConfigMap should check existence before deleting"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.success([metadata: [name: 'my-cm']])
        mockApiClient.delete(_) >> ServiceResponse.success()

        when:
        VirtualMachineDeletion.deleteConfigMap(mockApiClient, 'test-ns', 'my-cm')

        then:
        notThrown(Exception)
    }

    def "deleteConfigMap should skip when configmap not exists"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.error('Not Found', null, [code: 404])

        when:
        VirtualMachineDeletion.deleteConfigMap(mockApiClient, 'test-ns', 'my-cm')

        then:
        0 * mockApiClient.delete(_)
    }

    def "deleteConfigMap should handle get failure"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.error('Server Error')

        when:
        VirtualMachineDeletion.deleteConfigMap(mockApiClient, 'test-ns', 'my-cm')

        then:
        0 * mockApiClient.delete(_)
        notThrown(Exception)
    }

    def "deleteConfigMap should handle delete 404"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.success([metadata: [name: 'my-cm']])
        mockApiClient.delete(_) >> ServiceResponse.error('Not Found', null, [code: 404])

        when:
        VirtualMachineDeletion.deleteConfigMap(mockApiClient, 'test-ns', 'my-cm')

        then:
        notThrown(Exception)
    }

    def "deleteConfigMap should handle exceptions"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> { throw new RuntimeException('Network error') }

        when:
        VirtualMachineDeletion.deleteConfigMap(mockApiClient, 'test-ns', 'my-cm')

        then:
        notThrown(Exception)
    }

    def "deleteServer returns success when vm name is missing"() {
        given:
        def server = Mock(ComputeServer)
        server.name >> null

        when:
        def response = deletion.deleteServer(server)

        then:
        response.success
        response.msg == 'No VM name found to remove'
    }

    def "deleteServer returns failure when namespace is missing"() {
        given:
        def server = Mock(ComputeServer)
        server.name >> 'vm1'
        server.getConfigProperty('project') >> null

        when:
        def response = deletion.deleteServer(server)

        then:
        !response.success
        response.msg == 'No namespace found for VM deletion'
    }

    def "deleteServer returns failure when server group lookup fails"() {
        given:
        GroovySpy(CommonUtils, global: true)
        CommonUtils.getServerGroupId(_ as ComputeServer, _ as MorpheusContext) >> 55L

        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')
        morpheusContext.services >> services
        services.cluster >> clusterService
        clusterService.get(55L) >> null

        def server = Mock(ComputeServer)
        server.name >> 'vm1'
        server.uuid >> 'uuid-1'
        server.getConfigProperty('project') >> [name: 'ns1']

        when:
        def response = deletion.deleteServer(server)

        then:
        !response.success
        response.msg == 'No server group found for VM deletion'
    }

    def "deleteServer returns failure when vm deletion step fails"() {
        given:
        GroovySpy(CommonUtils, global: true)
        GroovySpy(VirtualMachineDeletion, global: true)
        CommonUtils.getServerGroupId(_ as ComputeServer, _ as MorpheusContext) >> 66L

        def mockApiClient = Mock(HarvesterApiClient)
        HarvesterApiClient.metaClass.constructor = { ComputeServerGroup sg -> mockApiClient }

        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')
        morpheusContext.services >> services
        services.cluster >> clusterService
        clusterService.get(66L) >> new ComputeServerGroup(id: 66L)

        VirtualMachineDeletion.deleteVirtualMachine(_ as Object, 'ns1', 'vm1') >> false

        def server = Mock(ComputeServer)
        server.name >> 'vm1'
        server.uuid >> 'uuid-2'
        server.getConfigProperty('project') >> [name: 'ns1']

        when:
        def response = deletion.deleteServer(server)

        then:
        !response.success
        response.msg == 'Failed to delete VirtualMachine'
    }

    def "deleteServer success for linux triggers secret cleanup"() {
        given:
        GroovySpy(CommonUtils, global: true)
        GroovySpy(VirtualMachineDeletion, global: true)
        CommonUtils.getServerGroupId(_ as ComputeServer, _ as MorpheusContext) >> 77L

        def mockApiClient = Mock(HarvesterApiClient)
        HarvesterApiClient.metaClass.constructor = { ComputeServerGroup sg -> mockApiClient }

        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')
        morpheusContext.services >> services
        services.cluster >> clusterService
        clusterService.get(77L) >> new ComputeServerGroup(id: 77L)

        VirtualMachineDeletion.deleteVirtualMachine(_ as Object, 'ns1', 'vm-linux') >> true
        VirtualMachineDeletion.deleteVirtualMachineInstance(_ as Object, 'ns1', 'vm-linux') >> true

        def server = Mock(ComputeServer)
        server.name >> 'vm-linux'
        server.uuid >> 'uuid-3'
        server.platform >> 'linux'
        server.getConfigProperty('project') >> [name: 'ns1']

        when:
        def response = deletion.deleteServer(server)

        then:
        response.success
        1 * VirtualMachineDeletion.deleteAssociatedVolumes(_ as Object, _ as ComputeServer, 'ns1')
        1 * VirtualMachineDeletion.deleteSecret(_ as Object, 'ns1', 'secret-vm-linux')
        0 * VirtualMachineDeletion.deleteConfigMap(_, _, _)
    }

    def "deleteServer success for windows triggers configmap cleanup"() {
        given:
        GroovySpy(CommonUtils, global: true)
        GroovySpy(VirtualMachineDeletion, global: true)
        CommonUtils.getServerGroupId(_ as ComputeServer, _ as MorpheusContext) >> 88L

        def mockApiClient = Mock(HarvesterApiClient)
        HarvesterApiClient.metaClass.constructor = { ComputeServerGroup sg -> mockApiClient }

        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')
        morpheusContext.services >> services
        services.cluster >> clusterService
        clusterService.get(88L) >> new ComputeServerGroup(id: 88L)

        VirtualMachineDeletion.deleteVirtualMachine(_ as Object, 'ns1', 'vm-win') >> true
        VirtualMachineDeletion.deleteVirtualMachineInstance(_ as Object, 'ns1', 'vm-win') >> true

        def server = Mock(ComputeServer)
        server.name >> 'vm-win'
        server.uuid >> 'uuid-4'
        server.platform >> 'windows'
        server.getConfigProperty('project') >> [name: 'ns1']

        when:
        def response = deletion.deleteServer(server)

        then:
        response.success
        1 * VirtualMachineDeletion.deleteAssociatedVolumes(_ as Object, _ as ComputeServer, 'ns1')
        1 * VirtualMachineDeletion.deleteConfigMap(_ as Object, 'ns1', 'vm-win-sysprep')
        0 * VirtualMachineDeletion.deleteSecret(_, _, _)
    }

    def "deleteServer should still succeed when VMI deletion returns false"() {
        given:
        GroovySpy(CommonUtils, global: true)
        GroovySpy(VirtualMachineDeletion, global: true)
        CommonUtils.getServerGroupId(_ as ComputeServer, _ as MorpheusContext) >> 99L

        def mockApiClient = Mock(HarvesterApiClient)
        HarvesterApiClient.metaClass.constructor = { ComputeServerGroup sg -> mockApiClient }

        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')
        morpheusContext.services >> services
        services.cluster >> clusterService
        clusterService.get(99L) >> new ComputeServerGroup(id: 99L)

        VirtualMachineDeletion.deleteVirtualMachine(_ as Object, 'ns1', 'vm-partial') >> true
        VirtualMachineDeletion.deleteVirtualMachineInstance(_ as Object, 'ns1', 'vm-partial') >> false

        def server = Mock(ComputeServer)
        server.name >> 'vm-partial'
        server.uuid >> 'uuid-partial'
        server.platform >> 'linux'
        server.getConfigProperty('project') >> [name: 'ns1']

        when:
        def response = deletion.deleteServer(server)

        then:
        response.success
        response.msg == 'VM vm-partial removed successfully'
    }

    def "deleteServer skips all deletion steps when VM UID does not match externalId"() {
        given:
        GroovySpy(CommonUtils, global: true)
        GroovySpy(VirtualMachineDeletion, global: true)
        CommonUtils.getServerGroupId(_ as ComputeServer, _ as MorpheusContext) >> 200L

        def mockApiClient = Mock(HarvesterApiClient)
        HarvesterApiClient.metaClass.constructor = { ComputeServerGroup sg -> mockApiClient }

        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')
        morpheusContext.services >> services
        services.cluster >> clusterService
        clusterService.get(200L) >> new ComputeServerGroup(id: 200L)

        // Pre-check GET returns a VM with a different UID (UID mismatch)
        mockApiClient.get(_) >> ServiceResponse.success([metadata: [name: 'vm-replaced', uid: 'new-uid-999']])

        def server = Mock(ComputeServer)
        server.name >> 'vm-replaced'
        server.uuid >> 'original-uuid'
        server.externalId >> 'original-uid-111'
        server.getConfigProperty('project') >> [name: 'ns1']

        when:
        def response = deletion.deleteServer(server)

        then:
        response.success
        response.msg == 'VM vm-replaced already removed (UID mismatch)'
        // Verify none of the deletion steps were called
        0 * VirtualMachineDeletion.deleteVirtualMachine(_, _, _, _)
        0 * VirtualMachineDeletion.deleteVirtualMachineInstance(_, _, _)
        0 * VirtualMachineDeletion.deleteAssociatedVolumes(_, _, _)
        0 * VirtualMachineDeletion.deleteSecret(_, _, _)
        0 * VirtualMachineDeletion.deleteConfigMap(_, _, _)
    }

    def "deleteServer proceeds with cleanup steps when VM is already gone (404 on pre-check)"() {
        given:
        GroovySpy(CommonUtils, global: true)
        GroovySpy(VirtualMachineDeletion, global: true)
        CommonUtils.getServerGroupId(_ as ComputeServer, _ as MorpheusContext) >> 201L

        def mockApiClient = Mock(HarvesterApiClient)
        HarvesterApiClient.metaClass.constructor = { ComputeServerGroup sg -> mockApiClient }

        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')
        morpheusContext.services >> services
        services.cluster >> clusterService
        clusterService.get(201L) >> new ComputeServerGroup(id: 201L)

        // Pre-check GET returns 404 — VM resource is gone but associated resources may still exist
        mockApiClient.get(_) >> ServiceResponse.error('Not Found', null, [code: 404])

        def server = Mock(ComputeServer)
        server.name >> 'vm-gone'
        server.uuid >> 'some-uuid'
        server.externalId >> 'some-uid-222'
        server.platform >> 'linux'
        server.getConfigProperty('project') >> [name: 'ns1']

        VirtualMachineDeletion.deleteVirtualMachine(_ as Object, 'ns1', 'vm-gone') >> true
        VirtualMachineDeletion.deleteVirtualMachineInstance(_ as Object, 'ns1', 'vm-gone') >> true

        when:
        def response = deletion.deleteServer(server)

        then:
        response.success
        // All cleanup steps should still be attempted
        1 * VirtualMachineDeletion.deleteVirtualMachine(_ as Object, 'ns1', 'vm-gone')
        1 * VirtualMachineDeletion.deleteVirtualMachineInstance(_ as Object, 'ns1', 'vm-gone')
        1 * VirtualMachineDeletion.deleteAssociatedVolumes(_ as Object, _ as ComputeServer, 'ns1')
        1 * VirtualMachineDeletion.deleteSecret(_ as Object, 'ns1', 'secret-vm-gone')
    }

    def "deleteServer should return failure when exception is thrown"() {
        given:
        def server = Mock(ComputeServer)
        server.name >> 'vm-error'
        server.uuid >> 'uuid-error'
        server.getConfigProperty('project') >> [name: 'ns1']

        GroovySpy(CommonUtils, global: true)
        CommonUtils.getServerGroupId(_ as ComputeServer, _ as MorpheusContext) >> { throw new RuntimeException('group lookup failed') }

        when:
        def response = deletion.deleteServer(server)

        then:
        !response.success
        response.msg.contains('Exception during VM removal')
    }

    def "deleteVirtualMachine should continue polling on non-404 errors and timeout successfully"() {
        given:
        VirtualMachineDeletion.metaClass.'static'.sleep = { long ms -> }
        def mockApiClient = Mock(HarvesterApiClient)
        int getCalls = 0
        mockApiClient.get(_) >> {
            getCalls++
            if (getCalls == 1) {
                return ServiceResponse.success([status: [printableStatus: 'Running']])
            }
            return ServiceResponse.error('Temporary API failure', null, [code: 500])
        }
        mockApiClient.delete(_) >> ServiceResponse.success([:])

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachine(mockApiClient, 'test-ns', 'vm-non404-timeout')

        then:
        result
    }

    def "deleteVirtualMachineInstance should continue polling on non-404 errors and timeout successfully"() {
        given:
        VirtualMachineDeletion.metaClass.'static'.sleep = { long ms -> }
        def mockApiClient = Mock(HarvesterApiClient)
        int getCalls = 0
        mockApiClient.get(_) >> {
            getCalls++
            if (getCalls == 1) {
                return ServiceResponse.success([status: [phase: 'Running']])
            }
            return ServiceResponse.error('Temporary API failure', null, [code: 500])
        }
        mockApiClient.delete(_) >> ServiceResponse.success([:])

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachineInstance(mockApiClient, 'test-ns', 'vmi-non404-timeout')

        then:
        result
    }

    def "deleteAssociatedVolumes should process mixed volume list and skip null type"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)

        def dvType = Mock(StorageVolumeType)
        dvType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME
        def pvcType = Mock(StorageVolumeType)
        pvcType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC

        def dvVolume = Mock(StorageVolume)
        dvVolume.name >> 'dv-1'
        dvVolume.type >> dvType

        def pvcVolume = Mock(StorageVolume)
        pvcVolume.name >> 'pvc-1'
        pvcVolume.type >> pvcType

        def unknownVolume = Mock(StorageVolume)
        unknownVolume.name >> 'unknown-1'
        unknownVolume.type >> null

        def server = Mock(ComputeServer)
        server.hostname >> 'mixed-vm'
        server.volumes >> [dvVolume, pvcVolume, unknownVolume]

        // No DataVolume exists for the PVC-typed volume — GET returns 404, fallback to PVC delete
        mockApiClient.get('/apis/cdi.kubevirt.io/v1beta1/namespaces/test-ns/datavolumes/pvc-1') >>
                ServiceResponse.error('Not Found', null, [code: 404])
        mockApiClient.delete(_) >> ServiceResponse.success()

        when:
        VirtualMachineDeletion.deleteAssociatedVolumes(mockApiClient, server, 'test-ns')

        then:
        1 * mockApiClient.delete('/apis/cdi.kubevirt.io/v1beta1/namespaces/test-ns/datavolumes/dv-1')
        1 * mockApiClient.delete('/api/v1/namespaces/test-ns/persistentvolumeclaims/pvc-1')
    }

    def "deletePVC should handle non-404 deletion failure gracefully"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.delete(_) >> ServiceResponse.error('Delete denied', null, [code: 403])

        when:
        VirtualMachineDeletion.deletePVC(mockApiClient, 'test-ns', 'my-pvc')

        then:
        notThrown(Exception)
    }

    def "deleteSecret should handle non-404 delete failure gracefully"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.success([metadata: [name: 'sec-a']])
        mockApiClient.delete(_) >> ServiceResponse.error('Forbidden', null, [code: 403])

        when:
        VirtualMachineDeletion.deleteSecret(mockApiClient, 'test-ns', 'sec-a')

        then:
        notThrown(Exception)
    }

    def "deleteConfigMap should handle non-404 delete failure gracefully"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.success([metadata: [name: 'cm-a']])
        mockApiClient.delete(_) >> ServiceResponse.error('Forbidden', null, [code: 403])

        when:
        VirtualMachineDeletion.deleteConfigMap(mockApiClient, 'test-ns', 'cm-a')

        then:
        notThrown(Exception)
    }

    def "deleteServer should treat uppercase WINDOWS platform as windows branch"() {
        given:
        GroovySpy(CommonUtils, global: true)
        GroovySpy(VirtualMachineDeletion, global: true)
        CommonUtils.getServerGroupId(_ as ComputeServer, _ as MorpheusContext) >> 111L

        def mockApiClient = Mock(HarvesterApiClient)
        HarvesterApiClient.metaClass.constructor = { ComputeServerGroup sg -> mockApiClient }

        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')
        morpheusContext.services >> services
        services.cluster >> clusterService
        clusterService.get(111L) >> new ComputeServerGroup(id: 111L)

        VirtualMachineDeletion.deleteVirtualMachine(_ as Object, 'ns1', 'vm-up-win') >> true
        VirtualMachineDeletion.deleteVirtualMachineInstance(_ as Object, 'ns1', 'vm-up-win') >> true

        def server = Mock(ComputeServer)
        server.name >> 'vm-up-win'
        server.uuid >> 'uuid-111'
        server.platform >> 'WINDOWS'
        server.getConfigProperty('project') >> [name: 'ns1']

        when:
        def response = deletion.deleteServer(server)

        then:
        response.success
        1 * VirtualMachineDeletion.deleteConfigMap(_ as Object, 'ns1', 'vm-up-win-sysprep')
        0 * VirtualMachineDeletion.deleteSecret(_, _, _)
    }

    def "deleteVirtualMachine should continue when poll returns non-404 error then succeeds with 404"() {
        given:
        VirtualMachineDeletion.metaClass.'static'.sleep = { long ms -> }
        def mockApiClient = Mock(HarvesterApiClient)
        int callCount = 0
        mockApiClient.get(_) >> {
            callCount++
            if (callCount == 1) {
                return ServiceResponse.success([status: [printableStatus: 'Running']])
            } else if (callCount == 2) {
                // Non-404 error in poll loop - should just continue
                return ServiceResponse.error('Service Unavailable', null, [code: 503])
            } else {
                return ServiceResponse.error('Not Found', null, [code: 404])
            }
        }
        mockApiClient.delete(_) >> ServiceResponse.success([:])

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachine(mockApiClient, 'test-ns', 'vm-poll-err')

        then:
        result == true
    }

    def "deleteVirtualMachineInstance should continue when poll returns non-404 error then succeeds with 404"() {
        given:
        VirtualMachineDeletion.metaClass.'static'.sleep = { long ms -> }
        def mockApiClient = Mock(HarvesterApiClient)
        int callCount = 0
        mockApiClient.get(_) >> {
            callCount++
            if (callCount == 1) {
                return ServiceResponse.success([status: [phase: 'Running']])
            } else if (callCount == 2) {
                // Non-404 error in poll loop - implicit else branch (neither success nor 404)
                return ServiceResponse.error('Service Unavailable', null, [code: 503])
            } else {
                return ServiceResponse.error('Not Found', null, [code: 404])
            }
        }
        mockApiClient.delete(_) >> ServiceResponse.success([:])

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachineInstance(mockApiClient, 'test-ns', 'vmi-poll-err')

        then:
        result == true
    }

    def "deleteSecret should handle non-404 delete failure"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.success([metadata: [name: 'my-secret']])
        mockApiClient.delete(_) >> ServiceResponse.error('Forbidden', null, [code: 403])

        when:
        VirtualMachineDeletion.deleteSecret(mockApiClient, 'test-ns', 'my-secret')

        then:
        notThrown(Exception)
    }

    def "deleteVirtualMachine returns true when delete succeeds and VM poll returns 404 immediately"() {
        given:
        VirtualMachineDeletion.metaClass.'static'.sleep = { long ms -> }
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >>> [
            ServiceResponse.success([status: [printableStatus: 'Running']]),  // Initial check
            ServiceResponse.error('Not Found', null, [code: 404])  // Poll immediately returns 404
        ]
        mockApiClient.delete(_) >> ServiceResponse.success([:])

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachine(mockApiClient, 'test-ns', 'vm-quick-delete')

        then:
        result == true
    }

    def "deleteVirtualMachine logs deletionTimestamp during poll wait"() {
        given:
        VirtualMachineDeletion.metaClass.'static'.sleep = { long ms -> }
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >>> [
            ServiceResponse.success([status: [printableStatus: 'Running']]),  // Initial check
            ServiceResponse.success([metadata: [deletionTimestamp: '2026-03-02T10:00:00Z']]),  // Has deletionTimestamp
            ServiceResponse.error('Not Found', null, [code: 404])  // Finally deleted
        ]
        mockApiClient.delete(_) >> ServiceResponse.success([:])

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachine(mockApiClient, 'test-ns', 'vm-with-timestamp')

        then:
        result == true
    }

    def "deleteVirtualMachine returns true when VM poll times out after max retries"() {
        given:
        VirtualMachineDeletion.metaClass.'static'.sleep = { long ms -> }
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.success([status: [printableStatus: 'Terminating']])  // Never returns 404
        mockApiClient.delete(_) >> ServiceResponse.success([:])

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachine(mockApiClient, 'test-ns', 'vm-timeout')

        then:
        result == true  // Still returns true after timeout
    }

    def "deleteVirtualMachine returns false when delete request fails"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.success([status: [printableStatus: 'Running']])
        mockApiClient.delete(_) >> ServiceResponse.error('Forbidden', null, [code: 403])

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachine(mockApiClient, 'test-ns', 'vm-delete-fail')

        then:
        result == false
    }

    def "deleteVirtualMachine returns false when initial check fails with non-404 error"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.error('Service Unavailable', null, [code: 503])

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachine(mockApiClient, 'test-ns', 'vm-check-fail')

        then:
        result == false
    }

    def "deleteVirtualMachine returns false when exception thrown"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> { throw new RuntimeException('Connection timeout') }

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachine(mockApiClient, 'test-ns', 'vm-exception')

        then:
        result == false
    }

    def "deleteVirtualMachineInstance returns true when VMI deleted successfully after retries"() {
        given:
        VirtualMachineDeletion.metaClass.'static'.sleep = { long ms -> }
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >>> [
            ServiceResponse.success([status: [phase: 'Running']]),  // Initial check
            ServiceResponse.success([metadata: [deletionTimestamp: '2026-03-02T10:00:00Z']]),  // Has deletionTimestamp
            ServiceResponse.success([status: [phase: 'Terminating']]),  // Still exists without timestamp
            ServiceResponse.error('Not Found', null, [code: 404])  // Finally deleted
        ]
        mockApiClient.delete(_) >> ServiceResponse.success([:])

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachineInstance(mockApiClient, 'test-ns', 'vmi-slow-delete')

        then:
        result == true
    }

    def "deleteVirtualMachineInstance returns true when poll times out after max retries"() {
        given:
        VirtualMachineDeletion.metaClass.'static'.sleep = { long ms -> }
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.success([status: [phase: 'Terminating']])  // Never returns 404
        mockApiClient.delete(_) >> ServiceResponse.success([:])

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachineInstance(mockApiClient, 'test-ns', 'vmi-timeout')

        then:
        result == true  // Still returns true after timeout
    }

    def "deleteVirtualMachineInstance returns false when delete request fails"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.success([status: [phase: 'Running']])
        mockApiClient.delete(_) >> ServiceResponse.error('Forbidden', null, [code: 403])

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachineInstance(mockApiClient, 'test-ns', 'vmi-delete-fail')

        then:
        result == false
    }

    def "deleteVirtualMachineInstance returns false when initial check fails with non-404 error"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> ServiceResponse.error('Service Unavailable', null, [code: 503])

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachineInstance(mockApiClient, 'test-ns', 'vmi-check-fail')

        then:
        result == false
    }

    def "deleteVirtualMachineInstance returns false when exception thrown"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        mockApiClient.get(_) >> { throw new RuntimeException('Connection error') }

        when:
        def result = VirtualMachineDeletion.deleteVirtualMachineInstance(mockApiClient, 'test-ns', 'vmi-exception')

        then:
        result == false
    }

    def "deleteAssociatedVolumes handles empty volumes list"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        def server = new ComputeServer(hostname: 'vm-no-vols')
        server.volumes = []

        when:
        VirtualMachineDeletion.deleteAssociatedVolumes(mockApiClient, server, 'test-ns')

        then:
        notThrown(Exception)
    }

    def "deleteAssociatedVolumes handles null volumes list"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        def server = new ComputeServer(hostname: 'vm-null-vols')
        server.volumes = null

        when:
        VirtualMachineDeletion.deleteAssociatedVolumes(mockApiClient, server, 'test-ns')

        then:
        notThrown(Exception)
    }

    def "deleteAssociatedVolumes skips volumes with unknown types"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        def server = new ComputeServer(hostname: 'vm-unknown-vol')
        def unknownVolType = new StorageVolumeType(code: 'unknown-type')
        def volume = new StorageVolume(name: 'vol1', type: unknownVolType)
        server.volumes = [volume]

        when:
        VirtualMachineDeletion.deleteAssociatedVolumes(mockApiClient, server, 'test-ns')

        then:
        notThrown(Exception)
        0 * mockApiClient.delete(_)
    }

    def "deleteAssociatedVolumes handles exception gracefully"() {
        given:
        def mockApiClient = Mock(HarvesterApiClient)
        def server = new ComputeServer(hostname: 'vm-exc')
        server.volumes >> { throw new RuntimeException('Volume access error') }

        when:
        VirtualMachineDeletion.deleteAssociatedVolumes(mockApiClient, server, 'test-ns')

        then:
        notThrown(Exception)
    }
}

