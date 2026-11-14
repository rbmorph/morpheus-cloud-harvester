package com.hpe.morpheus.harvester.plugin.volumes

import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.hpe.morpheus.harvester.plugin.events.HarvesterResourceEvents
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeTypeService
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.StorageController
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.projection.StorageVolumeIdentityProjection
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Unroll

class HarvesterVolumesSpec extends Specification {

    private def typedMock(String className) {
        Mock((Class) Class.forName(className))
    }

    private def invokePrivate(Object target, String methodName, Class[] paramTypes, Object[] args) {
        Class<?> type = target.class
        def method = null
        while (type && !method) {
            try {
                method = type.getDeclaredMethod(methodName, paramTypes)
            } catch (NoSuchMethodException ignored) {
                type = type.superclass
            }
        }
        if (!method) {
            throw new NoSuchMethodException("${methodName}(${paramTypes*.simpleName?.join(', ')})")
        }
        method.accessible = true
        method.invoke(target, args)
    }

    private Map buildStorageContextMocks() {
        def context = Mock(MorpheusContext)
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def storageService = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def volumeService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def controllerService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageControllerService')
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')

        context.services >> services
        services.storage >> storageService
        storageService.volume >> volumeService
        storageService.controller >> controllerService
        services.computeServer >> computeServerService

        [context: context, services: services, storageService: storageService, volumeService: volumeService, controllerService: controllerService, computeServerService: computeServerService]
    }

    def "getPvcApiResourcePath should build collection and item paths"() {
        expect:
        HarvesterVolumes.getPvcApiResourcePath('ns') == '/api/v1/namespaces/ns/persistentvolumeclaims'
        HarvesterVolumes.getPvcApiResourcePath('ns', 'pvc-a') == '/api/v1/namespaces/ns/persistentvolumeclaims/pvc-a'
    }

    def "getDataVolumeApiResourcePath should build collection and item paths"() {
        expect:
        HarvesterVolumes.getDataVolumeApiResourcePath('ns') == '/apis/cdi.kubevirt.io/v1beta1/namespaces/ns/datavolumes'
        HarvesterVolumes.getDataVolumeApiResourcePath('ns', 'dv-a') == '/apis/cdi.kubevirt.io/v1beta1/namespaces/ns/datavolumes/dv-a'
    }

    def "validateVolumeInputs should validate nulls and success"() {
        given:
        def volume = Mock(StorageVolume)
        volume.datastore >> typedMock('com.morpheusdata.model.projection.DatastoreIdentity')
        def noDatastoreVolume = Mock(StorageVolume)
        noDatastoreVolume.datastore >> null

        expect:
        !HarvesterVolumes.validateVolumeInputs(null).success
        !HarvesterVolumes.validateVolumeInputs(noDatastoreVolume).success
        HarvesterVolumes.validateVolumeInputs(volume).success
    }

    @Unroll
    def "buildHarvesterCreatePvcParams should build expected payload for clone=#isClone"() {
        given:
        def opts = [
            namespace: 'ns1',
            name: 'pvc1',
            storageClassName: 'fast',
            storage: '10Gi',
            isClone: isClone,
            sourceVolumeName: 'source-pvc',
            sourcePvcNamespace: 'src-ns'
        ]

        when:
        def params = HarvesterVolumes.buildHarvesterCreatePvcParams(opts)

        then:
        params.apiVersion == 'v1'
        params.kind == 'PersistentVolumeClaim'
        params.metadata.name == 'pvc1'
        params.metadata.namespace == 'ns1'
        params.spec.resources.requests.storage == '10Gi'
        params.spec.storageClassName == 'fast'
        params.spec.volumeMode == 'Block'
        (params.spec.dataSource != null) == isClone

        where:
        isClone << [true, false]
    }

    @Unroll
    def "buildHarvesterDataVolumeCreateParams should build expected payload for clone=#isClone"() {
        given:
        def opts = [
            namespace: 'ns1',
            name: 'dv1',
            storageClassName: 'fast',
            storage: '20Gi',
            isClone: isClone,
            sourceVolumeName: 'source-pvc',
            sourcePvcNamespace: 'src-ns'
        ]

        when:
        def params = HarvesterVolumes.buildHarvesterDataVolumeCreateParams(opts)

        then:
        params.apiVersion == 'cdi.kubevirt.io/v1beta1'
        params.kind == 'DataVolume'
        params.metadata.name == 'dv1'
        params.spec.pvc.resources.requests.storage == '20Gi'
        params.spec.pvc.storageClassName == 'fast'
        params.spec.pvc.volumeMode == 'Block'
        (params.spec.source.pvc != null) == isClone
        (params.spec.source.blank != null) == !isClone

        where:
        isClone << [true, false]
    }

    def "generateValidName should sanitize and fallback when needed"() {
        when:
        def normal = HarvesterVolumes.generateValidName('Server_01', 'Disk*Main', 'pvc-')
        def fallback = HarvesterVolumes.generateValidName('...###...', '***', 'pvc-')

        then:
        normal.startsWith('pvc-')
        normal == normal.toLowerCase()
        fallback ==~ /pvc-\d+/
    }

    def "setters should set compute server group and api client"() {
        given:
        def volumes = new HarvesterVolumes(Mock(MorpheusContext))
        def group = Mock(ComputeServerGroup)
        def apiClient = Mock(HarvesterApiClient)

        when:
        volumes.setComputeServerGroup(group)
        volumes.setHarvesterApiClient(apiClient)

        then:
        volumes.@computeServerGroup == group
        volumes.@harvesterApiClient == apiClient
    }

    def "executeHarvesterApiCall should call method with query params"() {
        given:
        def volumes = new HarvesterVolumes(Mock(MorpheusContext))
        def apiClient = Mock(HarvesterApiClient)
        volumes.setHarvesterApiClient(apiClient)
        def success = ServiceResponse.success([:])

        when:
        def response = invokePrivate(volumes, 'executeHarvesterApiCall',
            [String, String, Map, Map] as Class[],
            ['post', '/api/path', [k: 'v'], [dryRun: 'All']] as Object[])

        then:
        1 * apiClient.post('/api/path', [k: 'v'], [dryRun: 'All']) >> success
        response.success
    }

    def "executeHarvesterApiCall should call method without query params"() {
        given:
        def volumes = new HarvesterVolumes(Mock(MorpheusContext))
        def apiClient = Mock(HarvesterApiClient)
        volumes.setHarvesterApiClient(apiClient)
        def success = ServiceResponse.success([:])

        when:
        def response = invokePrivate(volumes, 'executeHarvesterApiCall',
            [String, String, Map, Map] as Class[],
            ['delete', '/api/path', null, null] as Object[])

        then:
        1 * apiClient.delete('/api/path', null) >> success
        response.success
    }

    def "executeHarvesterApiCall should return response for api failure"() {
        given:
        def volumes = new HarvesterVolumes(Mock(MorpheusContext))
        def apiClient = Mock(HarvesterApiClient)
        volumes.setHarvesterApiClient(apiClient)

        when:
        def response = invokePrivate(volumes, 'executeHarvesterApiCall',
            [String, String, Map, Map] as Class[],
            ['post', '/api/path', [a: 1], null] as Object[])

        then:
        1 * apiClient.post('/api/path', [a: 1]) >> ServiceResponse.error('post failed')
        !response.success
    }

    def "executeHarvesterApiCall should handle exception"() {
        given:
        def volumes = new HarvesterVolumes(Mock(MorpheusContext))
        def apiClient = Mock(HarvesterApiClient)
        volumes.setHarvesterApiClient(apiClient)

        when:
        def response = invokePrivate(volumes, 'executeHarvesterApiCall',
            [String, String, Map, Map] as Class[],
            ['post', '/api/path', [a: 1], null] as Object[])

        then:
        1 * apiClient.post('/api/path', [a: 1]) >> { throw new RuntimeException('boom') }
        !response.success
        response.errors?.error?.contains('Exception while initiating API call: boom')
    }

    def "waitForResourceStatus should return success when acceptable state observed"() {
        given:
        def volumes = new HarvesterVolumes(Mock(MorpheusContext))
        def apiClient = Mock(HarvesterApiClient)
        volumes.setHarvesterApiClient(apiClient)
        def okResponse = ServiceResponse.success([status: [phase: 'Bound']])

        when:
        def response = invokePrivate(volumes, 'waitForResourceStatus',
            [String, String, String, List, List, int, int] as Class[],
            ['/api/pvc/x', 'x', 'ns', ['Bound'], ['Failed'], 1, 0] as Object[])

        then:
        1 * apiClient.get('/api/pvc/x') >> okResponse
        response.success
    }

    def "waitForResourceStatus should return error for failure state"() {
        given:
        def volumes = new HarvesterVolumes(Mock(MorpheusContext))
        def apiClient = Mock(HarvesterApiClient)
        volumes.setHarvesterApiClient(apiClient)
        def failedResponse = ServiceResponse.success([status: [phase: 'Failed']])

        when:
        def response = invokePrivate(volumes, 'waitForResourceStatus',
            [String, String, String, List, List, int, int] as Class[],
            ['/api/dv/x', 'x', 'ns', ['Succeeded'], ['Failed'], 1, 0] as Object[])

        then:
        1 * apiClient.get('/api/dv/x') >> failedResponse
        !response.success
        response.errors?.error?.contains('Resource status Failed')
    }

    def "waitForResourceStatus should return timeout error after retries"() {
        given:
        def volumes = new HarvesterVolumes(Mock(MorpheusContext))
        def apiClient = Mock(HarvesterApiClient)
        volumes.setHarvesterApiClient(apiClient)
        def pendingResponse = ServiceResponse.success([status: [phase: 'Pending']])

        when:
        def response = invokePrivate(volumes, 'waitForResourceStatus',
            [String, String, String, List, List, int, int] as Class[],
            ['/api/pvc/y', 'y', 'ns', ['Bound'], ['Lost'], 2, 0] as Object[])

        then:
        2 * apiClient.get('/api/pvc/y') >> pendingResponse
        !response.success
        response.errors?.error?.contains('did not reach acceptable state')
    }

    def "waitForResourceStatus should continue on exception and then timeout"() {
        given:
        def volumes = new HarvesterVolumes(Mock(MorpheusContext))
        def apiClient = Mock(HarvesterApiClient)
        volumes.setHarvesterApiClient(apiClient)

        when:
        def response = invokePrivate(volumes, 'waitForResourceStatus',
            [String, String, String, List, List, int, int] as Class[],
            ['/api/pvc/z', 'z', 'ns', ['Bound'], ['Lost'], 1, 0] as Object[])

        then:
        1 * apiClient.get('/api/pvc/z') >> { throw new RuntimeException('network') }
        !response.success
        response.errors?.error?.contains('did not reach acceptable state')
    }

    def "queryPvcEvents should fail when compute server group is missing"() {
        given:
        def volumes = new HarvesterVolumes(Mock(MorpheusContext))

        when:
        def response = invokePrivate(volumes, 'queryPvcEvents',
            [String, String] as Class[],
            ['pvc-a', 'ns'] as Object[])

        then:
        !response.success
        response.errors?.error?.contains('ComputeServerGroup is not initialized')
    }

    def "queryPvcEvents should propagate fetch error"() {
        given:
        def volumes = new HarvesterVolumes(Mock(MorpheusContext))
        volumes.setComputeServerGroup(Mock(ComputeServerGroup))
        def mockEvents = Mock(HarvesterResourceEvents)

        GroovySpy(HarvesterResourceEvents, global: true)

        when:
        def response = invokePrivate(volumes, 'queryPvcEvents',
            [String, String] as Class[],
            ['pvc-a', 'ns'] as Object[])

        then:
        1 * new HarvesterResourceEvents(_ as ComputeServerGroup) >> mockEvents
        1 * mockEvents.fetchEventsByNamespace('ns', 'pvc-a', 'PersistentVolumeClaim') >> ServiceResponse.error('failed')
        !response.success
    }

    def "queryPvcEvents should fail when no event items found"() {
        given:
        def volumes = new HarvesterVolumes(Mock(MorpheusContext))
        volumes.setComputeServerGroup(Mock(ComputeServerGroup))
        def mockEvents = Mock(HarvesterResourceEvents)

        GroovySpy(HarvesterResourceEvents, global: true)

        when:
        def response = invokePrivate(volumes, 'queryPvcEvents',
            [String, String] as Class[],
            ['pvc-a', 'ns'] as Object[])

        then:
        1 * new HarvesterResourceEvents(_ as ComputeServerGroup) >> mockEvents
        1 * mockEvents.fetchEventsByNamespace('ns', 'pvc-a', 'PersistentVolumeClaim') >> ServiceResponse.success([items: null])
        !response.success
        response.errors?.error?.contains('No event items found for PVC')
    }

    def "queryPvcEvents should return error for warning events"() {
        given:
        def volumes = new HarvesterVolumes(Mock(MorpheusContext))
        volumes.setComputeServerGroup(Mock(ComputeServerGroup))
        def mockEvents = Mock(HarvesterResourceEvents)

        GroovySpy(HarvesterResourceEvents, global: true)

        when:
        def response = invokePrivate(volumes, 'queryPvcEvents',
            [String, String] as Class[],
            ['pvc-a', 'ns'] as Object[])

        then:
        1 * new HarvesterResourceEvents(_ as ComputeServerGroup) >> mockEvents
        1 * mockEvents.fetchEventsByNamespace('ns', 'pvc-a', 'PersistentVolumeClaim') >>
            ServiceResponse.success([items: [[type: 'Warning', reason: 'ProvisioningFailed']]])
        !response.success
        response.errors?.error?.contains('Warning events found for PVC')
    }

    def "queryPvcEvents should return success for all normal events"() {
        given:
        def volumes = new HarvesterVolumes(Mock(MorpheusContext))
        volumes.setComputeServerGroup(Mock(ComputeServerGroup))
        def mockEvents = Mock(HarvesterResourceEvents)

        GroovySpy(HarvesterResourceEvents, global: true)

        when:
        def response = invokePrivate(volumes, 'queryPvcEvents',
            [String, String] as Class[],
            ['pvc-a', 'ns'] as Object[])

        then:
        1 * new HarvesterResourceEvents(_ as ComputeServerGroup) >> mockEvents
        1 * mockEvents.fetchEventsByNamespace('ns', 'pvc-a', 'PersistentVolumeClaim') >>
            ServiceResponse.success([items: [[type: 'Normal', reason: 'Bound'], [type: 'Normal', reason: 'Ready']]])
        response.success
    }

    def "resizePvc should return success when dry-run and patch succeed"() {
        given:
        def volumes = new HarvesterVolumes(Mock(MorpheusContext))
        def apiClient = Mock(HarvesterApiClient)
        volumes.setHarvesterApiClient(apiClient)

        when:
        def response = invokePrivate(volumes, 'resizePvc',
            [String, String, String] as Class[],
            ['ns', 'pvc-a', '20Gi'] as Object[])

        then:
        1 * apiClient.patch('/api/v1/namespaces/ns/persistentvolumeclaims/pvc-a', _ as String,
            'application/json-patch+json', [dryRun: 'All']) >> ServiceResponse.success()
        1 * apiClient.patch('/api/v1/namespaces/ns/persistentvolumeclaims/pvc-a', _ as String,
            'application/json-patch+json') >> ServiceResponse.success()
        response.success
    }

    def "resizePvc should fail when dry-run fails"() {
        given:
        def volumes = new HarvesterVolumes(Mock(MorpheusContext))
        def apiClient = Mock(HarvesterApiClient)
        volumes.setHarvesterApiClient(apiClient)

        when:
        def response = invokePrivate(volumes, 'resizePvc',
            [String, String, String] as Class[],
            ['ns', 'pvc-a', '20Gi'] as Object[])

        then:
        1 * apiClient.patch('/api/v1/namespaces/ns/persistentvolumeclaims/pvc-a', _ as String,
            'application/json-patch+json', [dryRun: 'All']) >> ServiceResponse.error('dry-run failed')
        0 * apiClient.patch('/api/v1/namespaces/ns/persistentvolumeclaims/pvc-a', _ as String,
            'application/json-patch+json')
        !response.success
    }

    def "resizePvc should fail when actual patch fails"() {
        given:
        def volumes = new HarvesterVolumes(Mock(MorpheusContext))
        def apiClient = Mock(HarvesterApiClient)
        volumes.setHarvesterApiClient(apiClient)

        when:
        def response = invokePrivate(volumes, 'resizePvc',
            [String, String, String] as Class[],
            ['ns', 'pvc-a', '20Gi'] as Object[])

        then:
        1 * apiClient.patch('/api/v1/namespaces/ns/persistentvolumeclaims/pvc-a', _ as String,
            'application/json-patch+json', [dryRun: 'All']) >> ServiceResponse.success()
        1 * apiClient.patch('/api/v1/namespaces/ns/persistentvolumeclaims/pvc-a', _ as String,
            'application/json-patch+json') >> ServiceResponse.error('patch failed')
        !response.success
    }

    def "resizePvc should handle exceptions"() {
        given:
        def volumes = new HarvesterVolumes(Mock(MorpheusContext))
        def apiClient = Mock(HarvesterApiClient)
        volumes.setHarvesterApiClient(apiClient)

        when:
        def response = invokePrivate(volumes, 'resizePvc',
            [String, String, String] as Class[],
            ['ns', 'pvc-a', '20Gi'] as Object[])

        then:
        1 * apiClient.patch('/api/v1/namespaces/ns/persistentvolumeclaims/pvc-a', _ as String,
            'application/json-patch+json', [dryRun: 'All']) >> { throw new RuntimeException('network down') }
        !response.success
        response.errors?.error?.contains('Error resizing PVC: network down')
    }

    def "resizeVolume should validate required inputs"() {
        given:
        def volumes = new HarvesterVolumes(Mock(MorpheusContext))

        expect:
        !volumes.resizeVolume('ns', null, 'vol', 1024L).success
        !volumes.resizeVolume('ns', HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC, null, 1024L).success
        !volumes.resizeVolume('ns', HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC, 'vol', null).success
    }

    def "resizeVolume should reject unsupported volume type"() {
        given:
        def volumes = new HarvesterVolumes(Mock(MorpheusContext))

        when:
        def response = volumes.resizeVolume('ns', 'unsupported', 'vol1', 1024L)

        then:
        !response.success
        response.errors?.error?.contains('Unsupported volume type for resize')
    }

    @Unroll
    def "resizeVolume should succeed for supported type #volumeType"() {
        given:
        def volumes = new HarvesterVolumes(Mock(MorpheusContext))
        def apiClient = Mock(HarvesterApiClient)
        volumes.setHarvesterApiClient(apiClient)

        when:
        def response = volumes.resizeVolume('ns', volumeType, 'vol1', 2147483648L)

        then:
        1 * apiClient.patch('/api/v1/namespaces/ns/persistentvolumeclaims/vol1', _ as String,
            'application/json-patch+json', [dryRun: 'All']) >> ServiceResponse.success()
        1 * apiClient.patch('/api/v1/namespaces/ns/persistentvolumeclaims/vol1', _ as String,
            'application/json-patch+json') >> ServiceResponse.success()
        response.success

        where:
        volumeType << [HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC, HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME]
    }

    def "updateVolumeController should validate required args"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)

        expect:
        !volumes.updateVolumeController(null, '389:0:13:0').success
        !volumes.updateVolumeController(Mock(StorageVolume), null).success
    }

    def "updateVolumeController should validate mountpoint format"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)

        when:
        def response = volumes.updateVolumeController(Mock(StorageVolume), '389:0:13')

        then:
        !response.success
        response.errors?.error?.contains('Invalid controllerMountPoint format')
    }

    def "updateVolumeController should return error when controller is missing"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)
        def volume = Mock(StorageVolume)
        contextMocks.controllerService.get(389L) >> null

        when:
        def response = volumes.updateVolumeController(volume, '389:0:13:0')

        then:
        !response.success
        response.errors?.error?.contains('Storage controller not found with id: 389')
    }

    def "updateVolumeController should update and save volume"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)
        def volume = new StorageVolume(name: 'disk1')
        def controller = Mock(StorageController)
        controller.name >> 'virtio0'
        contextMocks.controllerService.get(389L) >> controller

        when:
        def response = volumes.updateVolumeController(volume, '389:0:13:7')

        then:
        1 * contextMocks.volumeService.save(volume)
        response.success
        volume.controller == controller
        volume.unitNumber == '7'
    }

    def "updateVolumeController should handle exceptions"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)

        when:
        def response = volumes.updateVolumeController(Mock(StorageVolume), 'abc:0:13:7')

        then:
        !response.success
        response.errors?.error?.contains('Error updating volume controller')
    }

    def "createHarvesterVolume should return validation error for invalid volume"() {
        given:
        def volumes = new HarvesterVolumes(Mock(MorpheusContext))

        when:
        def response = volumes.createHarvesterVolume(null, Mock(com.morpheusdata.model.ComputeServer))

        then:
        !response.success
    }

    def "createHarvesterPvcVolume should succeed on happy path"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)
        def group = Mock(ComputeServerGroup)
        group.id >> 99L
        volumes.setComputeServerGroup(group)

        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)

        def volume = new StorageVolume(id: 1L, name: 'disk-a')
        def volumeType = Mock(StorageVolumeType)
        volumeType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC
        volume.type = volumeType
        
        def server = Mock(ComputeServer)
        server.id >> 456L
        
        def opts = [namespace: 'ns', name: 'pvc-disk-a', storageClassName: 'fast', storage: '5Gi', isClone: false, serverId: 456L]

        when:
        def response = invokePrivate(volumes, 'createHarvesterPvcVolume',
            [StorageVolume, String, Map] as Class[],
            [volume, 'ns', opts] as Object[])

        then:
        1 * new HarvesterApiClient(group) >> apiClient
        1 * apiClient.post('/api/v1/namespaces/ns/persistentvolumeclaims', _ as Map, [dryRun: 'All']) >> ServiceResponse.success([:])
        1 * apiClient.post('/api/v1/namespaces/ns/persistentvolumeclaims', _ as Map) >>
            ServiceResponse.success([metadata: [name: 'pvc-disk-a', namespace: 'ns']])
        1 * apiClient.get('/api/v1/namespaces/ns/persistentvolumeclaims/pvc-disk-a') >>
            ServiceResponse.success([status: [phase: 'Bound'], metadata: [uid: 'uid-123']])
        1 * contextMocks.computeServerService.get(456L) >> server
        1 * contextMocks.volumeService.find(_) >> null
        1 * contextMocks.volumeService.create(_) >> { StorageVolume v -> v.id = 999L; v }
        1 * contextMocks.volumeService.save(volume)
        response.success
    }

    def "createHarvesterDataVolume should succeed on happy path"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)
        def group = Mock(ComputeServerGroup)
        group.id >> 88L
        volumes.setComputeServerGroup(group)

        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)

        def volume = new StorageVolume(id: 2L, name: 'disk-b')
        def volumeType = Mock(StorageVolumeType)
        volumeType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME
        volume.type = volumeType

        def server = Mock(ComputeServer)
        server.id >> 789L

        def opts = [namespace: 'ns', name: 'dv-disk-b', storageClassName: 'fast', storage: '5Gi', isClone: false, serverId: 789L]

        when:
        def response = invokePrivate(volumes, 'createHarvesterDataVolume',
            [StorageVolume, String, Map] as Class[],
            [volume, 'ns', opts] as Object[])

        then:
        1 * new HarvesterApiClient(group) >> apiClient
        1 * apiClient.post('/apis/cdi.kubevirt.io/v1beta1/namespaces/ns/datavolumes', _ as Map, [dryRun: 'All']) >> ServiceResponse.success([:])
        1 * apiClient.post('/apis/cdi.kubevirt.io/v1beta1/namespaces/ns/datavolumes', _ as Map) >>
            ServiceResponse.success([metadata: [name: 'dv-disk-b', namespace: 'ns']])
        1 * apiClient.get('/apis/cdi.kubevirt.io/v1beta1/namespaces/ns/datavolumes/dv-disk-b') >>
            ServiceResponse.success([status: [phase: 'Succeeded'], metadata: [uid: 'uid-456']])
        1 * contextMocks.computeServerService.get(789L) >> server
        1 * contextMocks.volumeService.find(_) >> null
        1 * contextMocks.volumeService.create(_) >> { StorageVolume v -> v.id = 998L; v }
        1 * contextMocks.volumeService.save(volume)
        response.success
    }

    // ─── deleteHarvesterVolume ────────────────────────────────────────────────

    private Map buildDeleteContextMocks() {
        def mocks = buildStorageContextMocks()
        def storageVolumeTypeService = Mock(MorpheusSynchronousStorageVolumeTypeService)
        mocks.volumeService.storageVolumeType >> storageVolumeTypeService
        mocks['storageVolumeTypeService'] = storageVolumeTypeService
        return mocks
    }

    def "deleteHarvesterVolume returns error when volumeType cannot be resolved"() {
        given:
        def mocks = buildDeleteContextMocks()
        def volumes = new HarvesterVolumes(mocks.context)
        def group = Mock(ComputeServerGroup)
        volumes.setComputeServerGroup(group)

        def vType = new StorageVolumeType(id: 5L)
        def volume = new StorageVolume(id: 1L, name: 'pvc-a')
        volume.type = vType
        mocks.storageVolumeTypeService.get(5L) >> null

        when:
        def result = volumes.deleteHarvesterVolume(volume, Mock(ComputeServer), false)

        then:
        !result.success
        result.errors?.error?.contains('Unable to determine volume type')
    }

    def "deleteHarvesterVolume returns error for unsupported volume type"() {
        given:
        def mocks = buildDeleteContextMocks()
        def volumes = new HarvesterVolumes(mocks.context)
        def group = Mock(ComputeServerGroup)
        volumes.setComputeServerGroup(group)
        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)

        def vType = new StorageVolumeType(id: 5L, code: 'unsupportedType')
        def volume = new StorageVolume(id: 1L, name: 'pvc-a')
        volume.type = vType
        mocks.storageVolumeTypeService.get(5L) >> vType

        def server = Mock(ComputeServer)
        server.getConfigMap() >> [project: [name: 'ns1']]

        when:
        def result = volumes.deleteHarvesterVolume(volume, server, false)

        then:
        1 * new HarvesterApiClient(group) >> apiClient
        !result.success
        result.errors?.error?.contains('Unsupported volume type')
    }

    def "deleteHarvesterVolume returns success when dry run returns 404 (already deleted)"() {
        given:
        def mocks = buildDeleteContextMocks()
        def volumes = new HarvesterVolumes(mocks.context)
        def group = Mock(ComputeServerGroup)
        volumes.setComputeServerGroup(group)
        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)

        def vType = new StorageVolumeType(id: 5L, code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)
        def volume = new StorageVolume(id: 1L, name: 'pvc-a')
        volume.type = vType
        mocks.storageVolumeTypeService.get(5L) >> vType

        def server = Mock(ComputeServer)
        server.getConfigMap() >> [project: [name: 'ns1']]

        def notFoundResponse = Mock(ServiceResponse)
        notFoundResponse.success >> false
        notFoundResponse.data >> [code: 404]

        when:
        def result = volumes.deleteHarvesterVolume(volume, server, false)

        then:
        1 * new HarvesterApiClient(group) >> apiClient
        1 * apiClient.delete('/api/v1/namespaces/ns1/persistentvolumeclaims/pvc-a', null, [dryRun: 'All']) >> notFoundResponse
        result.success
    }

    def "deleteHarvesterVolume returns error when dry run fails with non-404 error"() {
        given:
        def mocks = buildDeleteContextMocks()
        def volumes = new HarvesterVolumes(mocks.context)
        def group = Mock(ComputeServerGroup)
        volumes.setComputeServerGroup(group)
        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)

        def vType = new StorageVolumeType(id: 5L, code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)
        def volume = new StorageVolume(id: 1L, name: 'pvc-a')
        volume.type = vType
        mocks.storageVolumeTypeService.get(5L) >> vType

        def server = Mock(ComputeServer)
        server.getConfigMap() >> [project: [name: 'ns1']]

        def dryRunError = Mock(ServiceResponse)
        dryRunError.success >> false
        dryRunError.data >> [code: 403]
        dryRunError.msg >> 'Forbidden'

        when:
        def result = volumes.deleteHarvesterVolume(volume, server, false)

        then:
        1 * new HarvesterApiClient(group) >> apiClient
        1 * apiClient.delete('/api/v1/namespaces/ns1/persistentvolumeclaims/pvc-a', null, [dryRun: 'All']) >> dryRunError
        !result.success
        result.errors?.error?.contains('Dry run validation failed')
    }

    def "deleteHarvesterVolume returns error when actual delete fails"() {
        given:
        def mocks = buildDeleteContextMocks()
        def volumes = new HarvesterVolumes(mocks.context)
        def group = Mock(ComputeServerGroup)
        volumes.setComputeServerGroup(group)
        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)

        def vType = new StorageVolumeType(id: 5L, code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)
        def volume = new StorageVolume(id: 1L, name: 'pvc-a')
        volume.type = vType
        mocks.storageVolumeTypeService.get(5L) >> vType

        def server = Mock(ComputeServer)
        server.getConfigMap() >> [project: [name: 'ns1']]

        when:
        def result = volumes.deleteHarvesterVolume(volume, server, false)

        then:
        1 * new HarvesterApiClient(group) >> apiClient
        1 * apiClient.delete('/api/v1/namespaces/ns1/persistentvolumeclaims/pvc-a', null, [dryRun: 'All']) >> ServiceResponse.success()
        1 * apiClient.delete('/api/v1/namespaces/ns1/persistentvolumeclaims/pvc-a', null) >> ServiceResponse.error('delete failed')
        !result.success
        result.errors?.error?.contains('Failed to delete')
    }

    def "deleteHarvesterVolume succeeds happy path for PVC volume type"() {
        given:
        def mocks = buildDeleteContextMocks()
        def volumes = new HarvesterVolumes(mocks.context)
        def group = Mock(ComputeServerGroup)
        volumes.setComputeServerGroup(group)
        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)

        def vType = new StorageVolumeType(id: 5L, code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)
        def volume = new StorageVolume(id: 1L, name: 'pvc-a')
        volume.type = vType
        mocks.storageVolumeTypeService.get(5L) >> vType

        def server = Mock(ComputeServer)
        server.getConfigMap() >> [project: [name: 'ns1']]

        when:
        def result = volumes.deleteHarvesterVolume(volume, server, false)

        then:
        1 * new HarvesterApiClient(group) >> apiClient
        1 * apiClient.delete('/api/v1/namespaces/ns1/persistentvolumeclaims/pvc-a', null, [dryRun: 'All']) >> ServiceResponse.success()
        1 * apiClient.delete('/api/v1/namespaces/ns1/persistentvolumeclaims/pvc-a', null) >> ServiceResponse.success()
        result.success
    }

    def "deleteHarvesterVolume succeeds happy path for DataVolume type"() {
        given:
        def mocks = buildDeleteContextMocks()
        def volumes = new HarvesterVolumes(mocks.context)
        def group = Mock(ComputeServerGroup)
        volumes.setComputeServerGroup(group)
        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)

        def vType = new StorageVolumeType(id: 7L, code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME)
        def volume = new StorageVolume(id: 2L, name: 'dv-b')
        volume.type = vType
        mocks.storageVolumeTypeService.get(7L) >> vType

        def server = Mock(ComputeServer)
        server.getConfigMap() >> [project: [name: 'ns2']]

        when:
        def result = volumes.deleteHarvesterVolume(volume, server, false)

        then:
        1 * new HarvesterApiClient(group) >> apiClient
        1 * apiClient.delete('/apis/cdi.kubevirt.io/v1beta1/namespaces/ns2/datavolumes/dv-b', null, [dryRun: 'All']) >> ServiceResponse.success()
        1 * apiClient.delete('/apis/cdi.kubevirt.io/v1beta1/namespaces/ns2/datavolumes/dv-b', null) >> ServiceResponse.success()
        result.success
    }

    def "deleteHarvesterVolume handles exception during API call"() {
        given:
        def mocks = buildDeleteContextMocks()
        def volumes = new HarvesterVolumes(mocks.context)
        def group = Mock(ComputeServerGroup)
        volumes.setComputeServerGroup(group)
        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)

        def vType = new StorageVolumeType(id: 5L, code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)
        def volume = new StorageVolume(id: 1L, name: 'pvc-a')
        volume.type = vType
        mocks.storageVolumeTypeService.get(5L) >> vType

        def server = Mock(ComputeServer)
        server.getConfigMap() >> [project: [name: 'ns1']]

        when:
        def result = volumes.deleteHarvesterVolume(volume, server, false)

        then:
        1 * new HarvesterApiClient(group) >> apiClient
        1 * apiClient.delete('/api/v1/namespaces/ns1/persistentvolumeclaims/pvc-a', null, [dryRun: 'All']) >> { throw new RuntimeException('api error') }
        !result.success
    }

    def "deleteHarvesterVolume uses namespace fallback from volume config when server config lacks project"() {
        given:
        def mocks = buildDeleteContextMocks()
        def volumes = new HarvesterVolumes(mocks.context)
        def group = Mock(ComputeServerGroup)
        volumes.setComputeServerGroup(group)
        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)

        def vType = new StorageVolumeType(id: 5L, code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)
        def volume = new StorageVolume(id: 1L, name: 'pvc-b')
        volume.type = vType
        volume.setConfigProperty('namespace', 'fallback-ns')
        mocks.storageVolumeTypeService.get(5L) >> vType

        def server = Mock(ComputeServer)
        server.getConfigMap() >> [:]   // no project key

        when:
        def result = volumes.deleteHarvesterVolume(volume, server, false)

        then:
        1 * new HarvesterApiClient(group) >> apiClient
        1 * apiClient.delete('/api/v1/namespaces/fallback-ns/persistentvolumeclaims/pvc-b', null, [dryRun: 'All']) >> ServiceResponse.success()
        1 * apiClient.delete('/api/v1/namespaces/fallback-ns/persistentvolumeclaims/pvc-b', null) >> ServiceResponse.success()
        result.success
    }

    // ─── createHarvesterVolume (full integration paths) ──────────────────────

    private Map buildCreateContextMocks() {
        def mocks = buildDeleteContextMocks()

        def clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')
        def virtualImageService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')

        mocks.services.cluster >> clusterService
        mocks.services.virtualImage >> virtualImageService
        mocks['clusterService'] = clusterService
        mocks['virtualImageService'] = virtualImageService
        return mocks
    }

    def "createHarvesterVolume returns error for unsupported volume type"() {
        given:
        def mocks = buildCreateContextMocks()
        def volumes = new HarvesterVolumes(mocks.context)
        def group = Mock(ComputeServerGroup)
        group.id >> 55L

        def vType = new StorageVolumeType(id: 10L, code: 'unknownType')
        def dsIdentity = typedMock('com.morpheusdata.model.projection.DatastoreIdentity')
        def volume = new StorageVolume(id: 1L, name: 'disk')
        volume.type = vType
        volume.maxStorage = 1073741824L
        volume.datastore = dsIdentity
        volume.deviceName = 'vda'

        def server = Mock(ComputeServer)
        server.getConfigMap() >> [project: [name: 'ns1']]

        def sourceImage = new VirtualImage(id: 100L)
        server.sourceImage >> sourceImage

        def virtualImage = new VirtualImage(id: 100L, refId: 55L)
        virtualImage.volumes = []

        mocks.virtualImageService.get(100L) >> virtualImage
        mocks.clusterService.get(55L) >> group
        mocks.storageVolumeTypeService.get(10L) >> vType

        when:
        def result = volumes.createHarvesterVolume(volume, server)

        then:
        !result.success
        (result.errors?.error ?: result.msg)?.contains('Unsupported volume type')
    }

    def "createHarvesterVolume succeeds for PVC volume type"() {
        given:
        def mocks = buildCreateContextMocks()
        def volumes = new HarvesterVolumes(mocks.context)
        def group = Mock(ComputeServerGroup)
        group.id >> 55L

        def vType = new StorageVolumeType(id: 10L, code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)
        def dsIdentity = typedMock('com.morpheusdata.model.projection.DatastoreIdentity')
        dsIdentity.name >> 'fast-storage'
        def volume = new StorageVolume(id: 1L, name: 'disk')
        volume.type = vType
        volume.maxStorage = 5368709120L
        volume.datastore = dsIdentity
        volume.deviceName = 'vda'

        def server = Mock(ComputeServer)
        server.id >> 123L
        server.name >> 'vm-1'
        server.getConfigMap() >> [project: [name: 'ns1']]

        def sourceImage = new VirtualImage(id: 100L)
        server.sourceImage >> sourceImage

        def virtualImage = new VirtualImage(id: 100L, refId: 55L)
        virtualImage.volumes = []

        mocks.virtualImageService.get(100L) >> virtualImage
        mocks.clusterService.get(55L) >> group
        mocks.storageVolumeTypeService.get(10L) >> vType

        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)

        when:
        def result = volumes.createHarvesterVolume(volume, server)

        then:
        1 * new HarvesterApiClient(group) >> apiClient
        1 * apiClient.post('/api/v1/namespaces/ns1/persistentvolumeclaims', _ as Map, [dryRun: 'All']) >> ServiceResponse.success([:])
        1 * apiClient.post('/api/v1/namespaces/ns1/persistentvolumeclaims', _ as Map) >>
            ServiceResponse.success([metadata: [name: 'pvc-vm-1-disk', namespace: 'ns1']])
        1 * apiClient.get('/api/v1/namespaces/ns1/persistentvolumeclaims/pvc-vm-1-disk') >>
            ServiceResponse.success([status: [phase: 'Bound'], metadata: [uid: 'uid-pvc-1']])
        1 * mocks.computeServerService.get(123L) >> server
        1 * mocks.volumeService.find(_) >> null
        1 * mocks.volumeService.create(_) >> { StorageVolume v -> v.id = 997L; v }
        1 * mocks.volumeService.save(volume)
        result.success
    }

    def "createHarvesterVolume succeeds for DataVolume type"() {
        given:
        def mocks = buildCreateContextMocks()
        def volumes = new HarvesterVolumes(mocks.context)
        def group = Mock(ComputeServerGroup)
        group.id >> 66L

        def vType = new StorageVolumeType(id: 20L, code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME)
        def dsIdentity = typedMock('com.morpheusdata.model.projection.DatastoreIdentity')
        dsIdentity.name >> 'fast-dv'
        def volume = new StorageVolume(id: 2L, name: 'dvdisk')
        volume.type = vType
        volume.maxStorage = 10737418240L
        volume.datastore = dsIdentity
        volume.deviceName = 'vdb'

        def server = Mock(ComputeServer)
        server.id >> 234L
        server.name >> 'vm-2'
        server.getConfigMap() >> [project: [name: 'ns2']]

        def sourceImage = new VirtualImage(id: 200L)
        server.sourceImage >> sourceImage

        def virtualImage = new VirtualImage(id: 200L, refId: 66L)
        virtualImage.volumes = []

        mocks.virtualImageService.get(200L) >> virtualImage
        mocks.clusterService.get(66L) >> group
        mocks.storageVolumeTypeService.get(20L) >> vType

        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)

        when:
        def result = volumes.createHarvesterVolume(volume, server)

        then:
        1 * new HarvesterApiClient(group) >> apiClient
        1 * apiClient.post('/apis/cdi.kubevirt.io/v1beta1/namespaces/ns2/datavolumes', _ as Map, [dryRun: 'All']) >> ServiceResponse.success([:])
        1 * apiClient.post('/apis/cdi.kubevirt.io/v1beta1/namespaces/ns2/datavolumes', _ as Map) >>
            ServiceResponse.success([metadata: [name: 'dv-vm-2-dvdisk', namespace: 'ns2']])
        1 * apiClient.get('/apis/cdi.kubevirt.io/v1beta1/namespaces/ns2/datavolumes/dv-vm-2-dvdisk') >>
            ServiceResponse.success([status: [phase: 'Succeeded'], metadata: [uid: 'uid-dv-2']])
        1 * mocks.computeServerService.get(234L) >> server
        1 * mocks.volumeService.find(_) >> null
        1 * mocks.volumeService.create(_) >> { StorageVolume v -> v.id = 996L; v }
        1 * mocks.volumeService.save(volume)
        result.success
    }

    def "createHarvesterVolume returns error when internal create fails"() {
        given:
        def mocks = buildCreateContextMocks()
        def volumes = new HarvesterVolumes(mocks.context)
        def group = Mock(ComputeServerGroup)
        group.id >> 55L

        def vType = new StorageVolumeType(id: 10L, code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)
        def dsIdentity = typedMock('com.morpheusdata.model.projection.DatastoreIdentity')
        dsIdentity.name >> 'fast-storage'
        def volume = new StorageVolume(id: 1L, name: 'disk')
        volume.type = vType
        volume.maxStorage = 5368709120L
        volume.datastore = dsIdentity
        volume.deviceName = 'vda'

        def server = Mock(ComputeServer)
        server.name >> 'vm-1'
        server.getConfigMap() >> [project: [name: 'ns1']]

        def sourceImage = new VirtualImage(id: 100L)
        server.sourceImage >> sourceImage

        def virtualImage = new VirtualImage(id: 100L, refId: 55L)
        virtualImage.volumes = []

        mocks.virtualImageService.get(100L) >> virtualImage
        mocks.clusterService.get(55L) >> group
        mocks.storageVolumeTypeService.get(10L) >> vType

        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)

        when:
        def result = volumes.createHarvesterVolume(volume, server)

        then:
        1 * new HarvesterApiClient(group) >> apiClient
        1 * apiClient.post('/api/v1/namespaces/ns1/persistentvolumeclaims', _ as Map, [dryRun: 'All']) >> ServiceResponse.error('dry run failed')
        !result.success
    }

    def "createHarvesterVolume uses clone path when source image volume matches deviceName"() {
        given:
        def mocks = buildCreateContextMocks()
        def volumes = new HarvesterVolumes(mocks.context)
        def group = Mock(ComputeServerGroup)
        group.id >> 55L

        def vType = new StorageVolumeType(id: 10L, code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)
        def dsIdentity = typedMock('com.morpheusdata.model.projection.DatastoreIdentity')
        dsIdentity.name >> 'fast-storage'
        def volume = new StorageVolume(id: 1L, name: 'disk')
        volume.type = vType
        volume.maxStorage = 5368709120L
        volume.datastore = dsIdentity
        volume.deviceName = 'vda'

        def server = Mock(ComputeServer)
        server.id >> 345L
        server.name >> 'vm-3'
        server.getConfigMap() >> [project: [name: 'ns3']]

        def sourceImage = new VirtualImage(id: 300L)
        server.sourceImage >> sourceImage

        def imgVolProj = Mock(StorageVolumeIdentityProjection)
        imgVolProj.id >> 50L

        def virtualImage = new VirtualImage(id: 300L, refId: 55L)
        virtualImage.volumes = [imgVolProj]

        def sourceVol = new StorageVolume(id: 50L, name: 'src-disk', deviceName: 'vda')
        sourceVol.setConfigProperty('sourceType', 'pvc')
        sourceVol.setConfigProperty('sourcePvcName', 'src-pvc-name')
        sourceVol.setConfigProperty('sourcePvcNamespace', 'src-ns')

        mocks.virtualImageService.get(300L) >> virtualImage
        mocks.volumeService.get(50L) >> sourceVol
        mocks.clusterService.get(55L) >> group
        mocks.storageVolumeTypeService.get(10L) >> vType

        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)

        when:
        def result = volumes.createHarvesterVolume(volume, server)

        then:
        1 * new HarvesterApiClient(group) >> apiClient
        1 * apiClient.post('/api/v1/namespaces/ns3/persistentvolumeclaims', { Map params ->
            params.spec?.dataSource != null
        }, [dryRun: 'All']) >> ServiceResponse.success([:])
        1 * apiClient.post('/api/v1/namespaces/ns3/persistentvolumeclaims', _ as Map) >>
            ServiceResponse.success([metadata: [name: 'pvc-vm-3-disk', namespace: 'ns3']])
        1 * apiClient.get('/api/v1/namespaces/ns3/persistentvolumeclaims/pvc-vm-3-disk') >>
            ServiceResponse.success([status: [phase: 'Bound'], metadata: [uid: 'uid-clone-1']])
        1 * mocks.computeServerService.get(345L) >> server
        1 * mocks.volumeService.find(_) >> null
        1 * mocks.volumeService.create(_) >> { StorageVolume v -> v.id = 995L; v }
        1 * mocks.volumeService.save(volume)
        result.success
    }

    // ─── deleteHarvesterVolume: computeServerGroup initialization paths ───────

    def "deleteHarvesterVolume returns error when no computeServerGroup and null server"() {
        given:
        def mocks = buildDeleteContextMocks()
        def volumes = new HarvesterVolumes(mocks.context)
        // computeServerGroup is NOT set

        def vType = new StorageVolumeType(id: 5L, code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)
        def volume = new StorageVolume(id: 1L, name: 'pvc-x')
        volume.type = vType
        mocks.storageVolumeTypeService.get(5L) >> vType

        when:
        def result = volumes.deleteHarvesterVolume(volume, null, false)

        then:
        !result.success
        result.errors?.error?.contains('Unable to determine cluster')
    }

    def "deleteHarvesterVolume returns error when volume category contains non-numeric cluster id"() {
        given:
        def mocks = buildDeleteContextMocks()
        def volumes = new HarvesterVolumes(mocks.context)
        // computeServerGroup is NOT set, no server

        def vType = new StorageVolumeType(id: 5L, code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)
        def volume = new StorageVolume(id: 2L, name: 'pvc-badcat')
        volume.type = vType
        volume.category = HarvesterConstants.CATEGORY_DATAVOLUME_CLUSTER_PREFIX + 'not-a-number'
        mocks.storageVolumeTypeService.get(5L) >> vType

        when:
        def result = volumes.deleteHarvesterVolume(volume, null, false)

        then:
        !result.success
        result.errors?.error?.contains('Unable to determine cluster')
    }

    def "deleteHarvesterVolume initializes computeServerGroup from volume category"() {
        given:
        def mocks = buildDeleteContextMocks()
        def clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')
        mocks.services.cluster >> clusterService
        def volumes = new HarvesterVolumes(mocks.context)
        // computeServerGroup is NOT set, no server

        def group = Mock(ComputeServerGroup)
        clusterService.get(456L) >> group

        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)

        def vType = new StorageVolumeType(id: 5L, code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)
        def volume = new StorageVolume(id: 3L, name: 'pvc-cat')
        volume.type = vType
        volume.category = HarvesterConstants.CATEGORY_DATAVOLUME_CLUSTER_PREFIX + '456'
        volume.setConfigProperty('namespace', 'ns-cat')
        mocks.storageVolumeTypeService.get(5L) >> vType

        when:
        def result = volumes.deleteHarvesterVolume(volume, null, false)

        then:
        1 * new HarvesterApiClient(group) >> apiClient
        1 * apiClient.delete('/api/v1/namespaces/ns-cat/persistentvolumeclaims/pvc-cat', null, [dryRun: 'All']) >> ServiceResponse.success()
        1 * apiClient.delete('/api/v1/namespaces/ns-cat/persistentvolumeclaims/pvc-cat', null) >> ServiceResponse.success()
        result.success
    }

    def "deleteHarvesterVolume initializes computeServerGroup from server via CommonUtils"() {
        given:
        def mocks = buildDeleteContextMocks()
        def clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')
        mocks.services.cluster >> clusterService
        def volumes = new HarvesterVolumes(mocks.context)
        // computeServerGroup is NOT set

        def group = Mock(ComputeServerGroup)
        clusterService.get(789L) >> group

        def server = Mock(ComputeServer)
        server.getConfigMap() >> [project: [name: 'ns-srv']]

        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)
        GroovySpy(com.hpe.morpheus.harvester.plugin.utils.CommonUtils, global: true)

        def vType = new StorageVolumeType(id: 5L, code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)
        def volume = new StorageVolume(id: 4L, name: 'pvc-srv')
        volume.type = vType
        mocks.storageVolumeTypeService.get(5L) >> vType

        when:
        def result = volumes.deleteHarvesterVolume(volume, server, false)

        then:
        1 * com.hpe.morpheus.harvester.plugin.utils.CommonUtils.getServerGroupId(server, _) >> 789L
        1 * new HarvesterApiClient(group) >> apiClient
        1 * apiClient.delete('/api/v1/namespaces/ns-srv/persistentvolumeclaims/pvc-srv', null, [dryRun: 'All']) >> ServiceResponse.success()
        1 * apiClient.delete('/api/v1/namespaces/ns-srv/persistentvolumeclaims/pvc-srv', null) >> ServiceResponse.success()
        result.success
    }

    // ─── resizeVolume: volumeId DB update paths ───────────────────────────────

    def "resizeVolume updates StorageVolume maxStorage when resize succeeds and volumeId provided"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)
        def apiClient = Mock(HarvesterApiClient)
        volumes.setHarvesterApiClient(apiClient)

        def storageVolume = new StorageVolume(id: 10L, name: 'vol-to-update')

        when:
        def response = volumes.resizeVolume('ns', HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC, 'vol1', 2147483648L, 10L)

        then:
        1 * apiClient.patch('/api/v1/namespaces/ns/persistentvolumeclaims/vol1', _ as String,
            'application/json-patch+json', [dryRun: 'All']) >> ServiceResponse.success()
        1 * apiClient.patch('/api/v1/namespaces/ns/persistentvolumeclaims/vol1', _ as String,
            'application/json-patch+json') >> ServiceResponse.success()
        1 * contextMocks.volumeService.get(10L) >> storageVolume
        1 * contextMocks.volumeService.save({ StorageVolume v -> v.maxStorage == 2147483648L })
        response.success
    }

    def "resizeVolume does not fail when StorageVolume not found during DB update"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)
        def apiClient = Mock(HarvesterApiClient)
        volumes.setHarvesterApiClient(apiClient)

        when:
        def response = volumes.resizeVolume('ns', HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC, 'vol2', 1073741824L, 99L)

        then:
        1 * apiClient.patch('/api/v1/namespaces/ns/persistentvolumeclaims/vol2', _ as String,
            'application/json-patch+json', [dryRun: 'All']) >> ServiceResponse.success()
        1 * apiClient.patch('/api/v1/namespaces/ns/persistentvolumeclaims/vol2', _ as String,
            'application/json-patch+json') >> ServiceResponse.success()
        1 * contextMocks.volumeService.get(99L) >> null
        0 * contextMocks.volumeService.save(_)
        response.success
    }

    def "resizeVolume does not fail when DB update throws exception"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)
        def apiClient = Mock(HarvesterApiClient)
        volumes.setHarvesterApiClient(apiClient)

        when:
        def response = volumes.resizeVolume('ns', HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC, 'vol3', 1073741824L, 77L)

        then:
        1 * apiClient.patch('/api/v1/namespaces/ns/persistentvolumeclaims/vol3', _ as String,
            'application/json-patch+json', [dryRun: 'All']) >> ServiceResponse.success()
        1 * apiClient.patch('/api/v1/namespaces/ns/persistentvolumeclaims/vol3', _ as String,
            'application/json-patch+json') >> ServiceResponse.success()
        1 * contextMocks.volumeService.get(77L) >> { throw new RuntimeException('DB offline') }
        response.success
    }

    // ─── createHarvesterDataVolume: failure paths ─────────────────────────────

    def "createHarvesterDataVolume returns error when dry-run fails"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)
        def group = Mock(ComputeServerGroup)
        group.id >> 88L
        volumes.setComputeServerGroup(group)

        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)

        def volume = new StorageVolume(id: 20L, name: 'disk-dryfail')
        def opts = [namespace: 'ns', name: 'dv-disk-dryfail', storageClassName: 'fast', storage: '5Gi', isClone: false]

        when:
        def response = invokePrivate(volumes, 'createHarvesterDataVolume',
            [StorageVolume, String, Map] as Class[],
            [volume, 'ns', opts] as Object[])

        then:
        1 * new HarvesterApiClient(group) >> apiClient
        1 * apiClient.post('/apis/cdi.kubevirt.io/v1beta1/namespaces/ns/datavolumes', _ as Map, [dryRun: 'All']) >>
            ServiceResponse.error('dryrun fail')
        0 * apiClient.post('/apis/cdi.kubevirt.io/v1beta1/namespaces/ns/datavolumes', _ as Map)
        !response.success
    }

    def "createHarvesterDataVolume returns error when main POST fails"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)
        def group = Mock(ComputeServerGroup)
        group.id >> 88L
        volumes.setComputeServerGroup(group)

        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)

        def volume = new StorageVolume(id: 21L, name: 'disk-postfail')
        def opts = [namespace: 'ns', name: 'dv-disk-postfail', storageClassName: 'fast', storage: '5Gi', isClone: false]

        when:
        def response = invokePrivate(volumes, 'createHarvesterDataVolume',
            [StorageVolume, String, Map] as Class[],
            [volume, 'ns', opts] as Object[])

        then:
        1 * new HarvesterApiClient(group) >> apiClient
        1 * apiClient.post('/apis/cdi.kubevirt.io/v1beta1/namespaces/ns/datavolumes', _ as Map, [dryRun: 'All']) >>
            ServiceResponse.success([:])
        1 * apiClient.post('/apis/cdi.kubevirt.io/v1beta1/namespaces/ns/datavolumes', _ as Map) >>
            ServiceResponse.error('create failed')
        !response.success
    }

    def "createHarvesterDataVolume returns error when resource reaches failure state"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)
        def group = Mock(ComputeServerGroup)
        group.id >> 88L
        volumes.setComputeServerGroup(group)

        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)

        def volume = new StorageVolume(id: 22L, name: 'disk-waitfail')
        def opts = [namespace: 'ns', name: 'dv-disk-waitfail', storageClassName: 'fast', storage: '5Gi', isClone: false]

        when:
        def response = invokePrivate(volumes, 'createHarvesterDataVolume',
            [StorageVolume, String, Map] as Class[],
            [volume, 'ns', opts] as Object[])

        then:
        1 * new HarvesterApiClient(group) >> apiClient
        1 * apiClient.post('/apis/cdi.kubevirt.io/v1beta1/namespaces/ns/datavolumes', _ as Map, [dryRun: 'All']) >>
            ServiceResponse.success([:])
        1 * apiClient.post('/apis/cdi.kubevirt.io/v1beta1/namespaces/ns/datavolumes', _ as Map) >>
            ServiceResponse.success([metadata: [name: 'dv-disk-waitfail', namespace: 'ns']])
        1 * apiClient.get('/apis/cdi.kubevirt.io/v1beta1/namespaces/ns/datavolumes/dv-disk-waitfail') >>
            ServiceResponse.success([status: [phase: 'Failed'], metadata: [uid: 'uid-fail']])
        !response.success
    }

    def "createHarvesterDataVolume returns error when dry-run throws exception"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)
        def group = Mock(ComputeServerGroup)
        group.id >> 88L
        volumes.setComputeServerGroup(group)

        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)

        def volume = new StorageVolume(id: 23L, name: 'disk-dryex')
        def opts = [namespace: 'ns', name: 'dv-disk-dryex', storageClassName: 'fast', storage: '5Gi', isClone: false]

        when:
        def response = invokePrivate(volumes, 'createHarvesterDataVolume',
            [StorageVolume, String, Map] as Class[],
            [volume, 'ns', opts] as Object[])

        then:
        1 * new HarvesterApiClient(group) >> apiClient
        1 * apiClient.post('/apis/cdi.kubevirt.io/v1beta1/namespaces/ns/datavolumes', _ as Map, [dryRun: 'All']) >> {
            throw new RuntimeException('connection refused')
        }
        !response.success
    }

    // ─── createHarvesterPvcVolume: failure paths ──────────────────────────────

    def "createHarvesterPvcVolume returns error when dry-run fails"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)
        def group = Mock(ComputeServerGroup)
        group.id >> 99L
        volumes.setComputeServerGroup(group)

        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)

        def volume = new StorageVolume(id: 30L, name: 'pvc-dryfail')
        def opts = [namespace: 'ns', name: 'pvc-dryfail', storageClassName: 'fast', storage: '5Gi', isClone: false]

        when:
        def response = invokePrivate(volumes, 'createHarvesterPvcVolume',
            [StorageVolume, String, Map] as Class[],
            [volume, 'ns', opts] as Object[])

        then:
        1 * new HarvesterApiClient(group) >> apiClient
        1 * apiClient.post('/api/v1/namespaces/ns/persistentvolumeclaims', _ as Map, [dryRun: 'All']) >>
            ServiceResponse.error('dry run error')
        0 * apiClient.post('/api/v1/namespaces/ns/persistentvolumeclaims', _ as Map)
        !response.success
    }

    def "createHarvesterPvcVolume returns error when main POST fails"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)
        def group = Mock(ComputeServerGroup)
        group.id >> 99L
        volumes.setComputeServerGroup(group)

        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)

        def volume = new StorageVolume(id: 31L, name: 'pvc-postfail')
        def opts = [namespace: 'ns', name: 'pvc-postfail', storageClassName: 'fast', storage: '5Gi', isClone: false]

        when:
        def response = invokePrivate(volumes, 'createHarvesterPvcVolume',
            [StorageVolume, String, Map] as Class[],
            [volume, 'ns', opts] as Object[])

        then:
        1 * new HarvesterApiClient(group) >> apiClient
        1 * apiClient.post('/api/v1/namespaces/ns/persistentvolumeclaims', _ as Map, [dryRun: 'All']) >>
            ServiceResponse.success([:])
        1 * apiClient.post('/api/v1/namespaces/ns/persistentvolumeclaims', _ as Map) >>
            ServiceResponse.error('post error')
        !response.success
    }

    def "createHarvesterPvcVolume returns error when resource reaches Lost state"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)
        def group = Mock(ComputeServerGroup)
        group.id >> 99L
        volumes.setComputeServerGroup(group)

        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)

        def volume = new StorageVolume(id: 32L, name: 'pvc-lost')
        def opts = [namespace: 'ns', name: 'pvc-lost', storageClassName: 'fast', storage: '5Gi', isClone: false]

        when:
        def response = invokePrivate(volumes, 'createHarvesterPvcVolume',
            [StorageVolume, String, Map] as Class[],
            [volume, 'ns', opts] as Object[])

        then:
        1 * new HarvesterApiClient(group) >> apiClient
        1 * apiClient.post('/api/v1/namespaces/ns/persistentvolumeclaims', _ as Map, [dryRun: 'All']) >>
            ServiceResponse.success([:])
        1 * apiClient.post('/api/v1/namespaces/ns/persistentvolumeclaims', _ as Map) >>
            ServiceResponse.success([metadata: [name: 'pvc-lost', namespace: 'ns']])
        1 * apiClient.get('/api/v1/namespaces/ns/persistentvolumeclaims/pvc-lost') >>
            ServiceResponse.success([status: [phase: 'Lost'], metadata: [uid: 'uid-lost']])
        !response.success
    }

    def "createHarvesterPvcVolume returns error when dry-run throws exception"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)
        def group = Mock(ComputeServerGroup)
        group.id >> 99L
        volumes.setComputeServerGroup(group)

        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)

        def volume = new StorageVolume(id: 33L, name: 'pvc-dryex')
        def opts = [namespace: 'ns', name: 'pvc-dryex', storageClassName: 'fast', storage: '5Gi', isClone: false]

        when:
        def response = invokePrivate(volumes, 'createHarvesterPvcVolume',
            [StorageVolume, String, Map] as Class[],
            [volume, 'ns', opts] as Object[])

        then:
        1 * new HarvesterApiClient(group) >> apiClient
        1 * apiClient.post('/api/v1/namespaces/ns/persistentvolumeclaims', _ as Map, [dryRun: 'All']) >> {
            throw new RuntimeException('timeout')
        }
        !response.success
    }

    // --- generateValidName tests ---

    def "generateValidName produces valid k8s name from server and volume"() {
        expect:
        HarvesterVolumes.generateValidName('myServer', 'vol1') == 'pvc-myserver-vol1'
    }

    def "generateValidName sanitizes uppercase and special chars"() {
        expect:
        HarvesterVolumes.generateValidName('My_Server', 'Vol@2') == 'pvc-my-server-vol-2'
    }

    def "generateValidName handles null server name"() {
        when:
        def result = HarvesterVolumes.generateValidName(null, 'vol1')

        then:
        result.startsWith('pvc-')
    }

    def "generateValidName handles null volume name"() {
        when:
        def result = HarvesterVolumes.generateValidName('server1', null)

        then:
        result.startsWith('pvc-')
    }

    def "generateValidName handles both null"() {
        when:
        def result = HarvesterVolumes.generateValidName(null, null)

        then:
        result.startsWith('pvc-')
    }

    def "generateValidName with custom prefix"() {
        expect:
        HarvesterVolumes.generateValidName('srv', 'disk', 'dv-') == 'dv-srv-disk'
    }

    // --- createHarvesterVolume additional tests ---

    def "createHarvesterVolume returns error for null volume"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)

        when:
        def result = volumes.createHarvesterVolume(null, new ComputeServer(id: 1L))

        then:
        !result.success
    }

    // --- deleteHarvesterVolume additional tests ---

    def "deleteHarvesterVolume returns error for null volume type"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)
        def group = Mock(ComputeServerGroup) { id >> 1L }
        volumes.setComputeServerGroup(group)

        def svtService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeTypeService')
        contextMocks.volumeService.storageVolumeType >> svtService
        svtService.get(_ as Long) >> null

        def volType = new StorageVolumeType(id: 99L, code: 'some-type')
        def volume = new StorageVolume(id: 2L, name: 'no-type-vol')
        volume.type = volType
        def server = new ComputeServer(id: 2L, name: 'srv2')

        when:
        def result = volumes.deleteHarvesterVolume(volume, server, false)

        then:
        !result.success
    }

    def "deleteHarvesterVolume returns error for unsupported type"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)
        def group = Mock(ComputeServerGroup) { id >> 1L }
        volumes.setComputeServerGroup(group)

        def svtService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeTypeService')
        contextMocks.volumeService.storageVolumeType >> svtService
        def unsupType = new StorageVolumeType(id: 88L, code: 'unknown-code')
        svtService.get(88L) >> unsupType

        def volume = new StorageVolume(id: 3L, name: 'unsup-vol')
        volume.type = unsupType
        def server = new ComputeServer(id: 3L, name: 'srv3')
        server.setConfigMap([project: [name: 'ns']])

        when:
        def result = volumes.deleteHarvesterVolume(volume, server, false)

        then:
        !result.success
    }

    // --- resizePvc tests ---

    def "resizePvc returns error on API failure"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)
        def group = Mock(ComputeServerGroup) { id >> 1L }
        volumes.setComputeServerGroup(group)

        def apiClient = Mock(HarvesterApiClient)
        GroovySpy(HarvesterApiClient, global: true)
        new HarvesterApiClient(group) >> apiClient
        apiClient.patch(_, _, _) >> new ServiceResponse(success: false, msg: 'resize failed')

        when:
        def result = invokePrivate(volumes, 'resizePvc',
            [String, String, String] as Class[],
            ['ns', 'my-pvc', '10Gi'] as Object[])

        then:
        !result.success
    }

    // --- updateVolumeController tests ---

    def "updateVolumeController updates mount point"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)

        def controller = new StorageController(id: 10L, name: 'virtio-ctrl')
        contextMocks.controllerService.get(10L) >> controller

        def volume = new StorageVolume(id: 5L, name: 'vol-ctrl')
        contextMocks.volumeService.save(_ as StorageVolume) >> volume

        when:
        def result = volumes.updateVolumeController(volume, '10:0:13:0')

        then:
        result.success
    }

    def "updateVolumeController handles null controller"() {
        given:
        def contextMocks = buildStorageContextMocks()
        def volumes = new HarvesterVolumes(contextMocks.context)

        def volume = new StorageVolume(id: 6L, name: 'vol-no-ctrl')
        volume.controller = null

        when:
        def result = volumes.updateVolumeController(volume, null)

        then:
        !result.success
    }

    def "buildHarvesterCreatePvcParams adds the Harvester image annotation when given"() {
        when:
        def params = HarvesterVolumes.buildHarvesterCreatePvcParams([
                namespace: 'ns1', name: 'pvc-root', storageClassName: 'longhorn-image-abc', storage: '20Gi',
                isClone: false, annotations: ['harvesterhci.io/imageId': 'default/image-abc']
        ])

        then:
        params.metadata.name == 'pvc-root'
        params.metadata.annotations['harvesterhci.io/imageId'] == 'default/image-abc'
        params.spec.storageClassName == 'longhorn-image-abc'
        params.spec.dataSource == null
        params.spec.dataSourceRef == null
    }

    def "buildHarvesterCreatePvcParams omits annotations when none are given"() {
        expect:
        !HarvesterVolumes.buildHarvesterCreatePvcParams([namespace: 'n', name: 'p', storage: '1Gi']).metadata.containsKey('annotations')
    }

    // --- root disk on a storage class other than the image's ---

    @Unroll
    def "needsImageCopy: #desc"() {
        given:
        def volume = new StorageVolume(name: 'root')
        if (target) {
            volume.datastore = new com.morpheusdata.model.Datastore(name: target)
        }
        if (auto) {
            volume.setConfigProperty(HarvesterConstants.CONFIG_AUTO_DATASTORE, true)
        }
        def config = [imageStorageClassName: 'longhorn-image-abc12', imageId: 'default/image-abc12']

        expect:
        HarvesterVolumes.needsImageCopy(volume, config, fromImage) == expected

        where:
        desc                                   | target                  | auto  | fromImage || expected
        'another storage class is chosen'      | 'longhorn-ssd'          | false | true      || true
        'the image class is chosen'            | 'longhorn-image-abc12'  | false | true      || false
        'another image class is chosen'        | 'longhorn-image-zzz99'  | false | true      || false
        '"Auto" filled in the datastore'       | 'harvester-longhorn'    | true  | true      || false
        'no datastore'                         | null                    | false | true      || false
        'the disk is not from an image'        | 'longhorn-ssd'          | false | false     || false
    }
}
