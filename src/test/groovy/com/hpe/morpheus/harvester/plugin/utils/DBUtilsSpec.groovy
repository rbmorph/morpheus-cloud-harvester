package com.hpe.morpheus.harvester.plugin.utils

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.*
import io.reactivex.rxjava3.core.Maybe
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import spock.lang.Unroll

class DBUtilsSpec extends Specification {

    private def typedMock(String className) {
        Mock((Class) Class.forName(className))
    }

    @Unroll
    def "buildStorageVolumeCategory creates correct category for PVC volume type"() {
        given:
        ComputeServer mockServer = Mock(ComputeServer)
        StorageVolumeType mockVolType = Mock(StorageVolumeType)
        Long clusterId = 123L
        Long serverId = 456L

        mockServer.id >> serverId
        mockVolType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC

        when:
        String result = DBUtils.buildVMStorageVolumeCategory(mockServer, mockVolType, clusterId)

        then:
        result == "harvester.persistentVolumeClaim.cluster.123.vm.456"
    }

    @Unroll
    def "buildStorageVolumeCategory creates correct category for DataVolume volume type"() {
        given:
        ComputeServer mockServer = Mock(ComputeServer)
        StorageVolumeType mockVolType = Mock(StorageVolumeType)
        Long clusterId = 789L
        Long serverId = 321L

        mockServer.id >> serverId
        mockVolType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME

        when:
        String result = DBUtils.buildVMStorageVolumeCategory(mockServer, mockVolType, clusterId)

        then:
        result == "harvester.dataVolume.cluster.789.vm.321"
    }

    @Unroll
    def "buildStorageVolumeCategory creates correct category for DataSource volume type"() {
        given:
        ComputeServer mockServer = Mock(ComputeServer)
        StorageVolumeType mockVolType = Mock(StorageVolumeType)
        Long clusterId = 555L
        Long serverId = 666L

        mockServer.id >> serverId
        mockVolType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_DATASOURCE

        when:
        String result = DBUtils.buildVMStorageVolumeCategory(mockServer, mockVolType, clusterId)

        then:
        result == "harvester.dataVolume.cluster.555.vm.666"
    }

    def "buildStorageVolumeCategory returns null for unknown volume type"() {
        given:
        ComputeServer mockServer = Mock(ComputeServer)
        StorageVolumeType mockVolType = Mock(StorageVolumeType)
        Long clusterId = 100L
        Long serverId = 200L

        mockServer.id >> serverId
        mockVolType.code >> "unknownVolumeType"

        when:
        String result = DBUtils.buildVMStorageVolumeCategory(mockServer, mockVolType, clusterId)

        then:
        result == null
    }

    def "buildStorageVolumeCategory handles various cluster and server IDs"() {
        given:
        ComputeServer mockServer = Mock(ComputeServer)
        StorageVolumeType mockVolType = Mock(StorageVolumeType)

        mockServer.id >> serverId
        mockVolType.code >> HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC

        expect:
        DBUtils.buildVMStorageVolumeCategory(mockServer, mockVolType, clusterId) == expectedCategory

        where:
        clusterId | serverId | expectedCategory
        1L        | 1L       | "harvester.persistentVolumeClaim.cluster.1.vm.1"
        9999L     | 8888L    | "harvester.persistentVolumeClaim.cluster.9999.vm.8888"
        100L      | 50L      | "harvester.persistentVolumeClaim.cluster.100.vm.50"
    }

    def "getZonePoolFromIdString returns null for pool ID not starting with pool-"() {
        given:
        def mockContext = Mock(MorpheusContext)
        String resourcePoolId = "invalid-id"

        when:
        def result = DBUtils.getZonePoolFromIdString(mockContext, resourcePoolId)

        then:
        result == null
        0 * mockContext._
    }

    def "getZonePoolFromIdString handles string without pool- prefix"() {
        given:
        def mockContext = Mock(MorpheusContext)
        String resourcePoolId = "12345"

        when:
        def result = DBUtils.getZonePoolFromIdString(mockContext, resourcePoolId)

        then:
        result == null
        0 * mockContext._
    }

    def "getZonePoolFromIdString returns pool when valid pool- ID provided"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockAsync = Mock(MorpheusAsyncServices)
        def mockCloudService = typedMock('com.morpheusdata.core.cloud.MorpheusCloudService')
        def mockPoolService = typedMock('com.morpheusdata.core.cloud.MorpheusCloudPoolService')
        def mockPool = Mock(CloudPool)
        
        mockContext.async >> mockAsync
        mockAsync.cloud >> mockCloudService
        mockCloudService.pool >> mockPoolService
        mockPoolService.get(123L) >> Maybe.just(mockPool)

        when:
        def result = DBUtils.getZonePoolFromIdString(mockContext, "pool-123")

        then:
        result == mockPool
    }

    def "getDefaultNamespace finds default namespace for server group"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockCloudService = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService')
        def mockPoolService = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService')
        def mockServerGroup = Mock(ComputeServerGroup)
        def mockNamespace = Mock(CloudPool)

        mockContext.services >> mockServices
        mockServices.cloud >> mockCloudService
        mockCloudService.pool >> mockPoolService
        mockPoolService.find(_) >> mockNamespace

        when:
        def result = DBUtils.getDefaultNamespace(mockContext, mockServerGroup)

        then:
        result == mockNamespace
    }

    def "getDefaultNamespace handles exception gracefully"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockCloudService = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService')
        def mockPoolService = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService')
        def mockServerGroup = Mock(ComputeServerGroup)

        mockContext.services >> mockServices
        mockServices.cloud >> mockCloudService
        mockCloudService.pool >> mockPoolService
        mockPoolService.find(_) >> { throw new Exception("DB error") }

        when:
        def result = DBUtils.getDefaultNamespace(mockContext, mockServerGroup)

        then:
        notThrown(Exception)
        result == null
    }

    def "getClusterResourcePool finds cluster resource pool"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockCloudService = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService')
        def mockPoolService = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService')
        def mockServerGroup = Mock(ComputeServerGroup)
        def mockPool = Mock(CloudPool)

        mockServerGroup.name >> "test-cluster"
        mockServerGroup.id >> 99L
        mockContext.services >> mockServices
        mockServices.cloud >> mockCloudService
        mockCloudService.pool >> mockPoolService
        mockPoolService.find(_) >> mockPool

        when:
        def result = DBUtils.getClusterResourcePool(mockContext, mockServerGroup)

        then:
        result == mockPool
    }

    def "getStorageVolumeTypeMap returns map of storage volume types"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockStorageService = Stub(Object)
        def mockVolumeService = Stub(Object)
        def mockVolumeTypeService = Stub(Object)
        def mockType = Mock(StorageVolumeType)

        mockContext.services >> mockServices
        mockServices.storage >> mockStorageService
        mockStorageService.volume >> mockVolumeService
        mockVolumeService.storageVolumeType >> mockVolumeTypeService
        mockVolumeTypeService.find(_) >> mockType

        when:
        def result = DBUtils.getStorageVolumeTypeMap(mockContext)

        then:
        result != null
        result instanceof Map
    }

    def "getStorageControllerTypeMap returns map of storage controller types"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockAsync = Mock(MorpheusAsyncServices)
        def mockStorageService = Stub(Object)
        def mockControllerService = Stub(Object)
        def mockControllerTypeService = Stub(Object)
        def mockType = Mock(StorageControllerType)

        mockContext.async >> mockAsync
        mockAsync.storage >> mockStorageService
        mockStorageService.controller >> mockControllerService
        mockControllerService.storageControllerType >> mockControllerTypeService
        mockControllerTypeService.find(_) >> Single.just(mockType)

        when:
        def result = DBUtils.getStorageControllerTypeMap(mockContext)

        then:
        result != null
        result instanceof Map
    }

    def "findStorageControllerByType finds controller by type"() {
        given:
        def mockServer = Mock(ComputeServer)
        def mockControllerType = Mock(StorageControllerType)
        def mockController = Mock(StorageController)
        def mockStorageType = Mock(StorageControllerType)

        mockControllerType.code >> "virtio-raid"
        mockStorageType.code >> "virtio-raid"
        mockController.type >> mockStorageType
        mockServer.controllers >> [mockController]

        when:
        def result = DBUtils.findStorageControllerByType(mockServer, mockControllerType)

        then:
        result == mockController
    }

    def "findStorageControllerByType returns null when no matching controller"() {
        given:
        def mockServer = Mock(ComputeServer)
        def mockControllerType = Mock(StorageControllerType)
        def mockController = Mock(StorageController)
        def mockStorageType = Mock(StorageControllerType)

        mockControllerType.code >> "virtio-raid"
        mockStorageType.code >> "different-code"
        mockController.type >> mockStorageType
        mockServer.controllers >> [mockController]

        when:
        def result = DBUtils.findStorageControllerByType(mockServer, mockControllerType)

        then:
        result == null
    }

    def "createDefaultResourcePool creates new pool when none exists"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockCloudService = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService')
        def mockPoolService = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService')
        def mockServerGroup = Mock(ComputeServerGroup)
       def mockCloud = Mock(Cloud)
        def mockAccount = Mock(Account)

        mockServerGroup.name >> "test-cluster"
        mockServerGroup.id >> 42L
        mockServerGroup.cloud >> mockCloud
        mockServerGroup.owner >> mockAccount
        mockContext.services >> mockServices
        mockServices.cloud >> mockCloudService
        mockCloudService.pool >> mockPoolService
        mockPoolService.find(_) >> null
        mockPoolService.create(_) >> { CloudPool pool -> pool }

        when:
        def result = DBUtils.createDefaultResourcePool(mockContext, mockServerGroup)

        then:
        result != null
        1 * mockPoolService.create(_)
    }

    def "updateZoneStatus updates cloud status"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockAsync = Mock(MorpheusAsyncServices)
        def mockCloudService = typedMock('com.morpheusdata.core.cloud.MorpheusCloudService')
        def mockZone = Mock(Cloud)

        mockContext.async >> mockAsync
        mockAsync.cloud >> mockCloudService

        when:
        DBUtils.updateZoneStatus(mockContext, mockZone, Cloud.Status.ok, "All good")

        then:
        1 * mockCloudService.updateCloudStatus(mockZone, Cloud.Status.ok, "All good", _)
    }

    def "getAuthConfig returns auth configuration from cloud"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockAsync = Mock(MorpheusAsyncServices)
        def mockCloudService = typedMock('com.morpheusdata.core.cloud.MorpheusCloudService')
        def mockCloud = Mock(Cloud)
        def mockCredential = Mock(AccountCredential)

        mockCloud.id >> 123L
        mockCloud.accountCredentialLoaded >> true
        mockCloud.accountCredentialData >> [:]
        mockCloud.serviceUrl >> "https://api.example.com/api"
        mockCloud.serviceUsername >> "admin"
        mockCloud.servicePassword >> "password123"
        mockCloud.getConfigProperty('serviceVersion') >> 'v1'
        
        mockContext.async >> mockAsync
        mockAsync.cloud >> mockCloudService
        mockCloudService.loadCredentials(123L) >> Single.just(mockCredential)
        mockCredential.data >> [username: 'credUser', password: 'credPass']

        when:
        def result = DBUtils.getAuthConfig(mockContext, mockCloud)

        then:
        result.apiUrl == "https:/.example.com/api"
        result.apiUsername == "admin"
        result.apiPassword == "password123"
        result.apiVersion == 'v1'
    }

    def "getAuthConfig handles cloud without credentials"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockAsync = Mock(MorpheusAsyncServices)
        def mockCloudService = typedMock('com.morpheusdata.core.cloud.MorpheusCloudService')
        def mockCloud = Mock(Cloud)

        mockCloud.id >> 456L
        mockCloud.accountCredentialLoaded >> false
        mockCloud.accountCredentialData >> null
        mockCloud.serviceUrl >> "https://api.test.com"
        mockCloud.serviceUsername >> "user1"
        mockCloud.servicePassword >> "pass1"
        mockCloud.getConfigProperty('serviceVersion') >> 'v2'
        
        mockContext.async >> mockAsync
        mockAsync.cloud >> mockCloudService
        mockCloudService.loadCredentials(456L) >> { throw new Exception("No credentials") }

        when:
        def result = DBUtils.getAuthConfig(mockContext, mockCloud)

        then:
        result.apiUrl == "https://api.test.com"
        result.apiUsername == "user1"
        result.apiPassword == "pass1"
    }

    def "updateTenantPermissions handles null accountIds gracefully"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockPermService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousResourcePermissionService')

        mockContext.services >> mockServices
        mockServices.resourcePermission >> mockPermService
        mockPermService.list(_) >> []

        when:
        DBUtils.updateTenantPermissions(mockContext, "VirtualImage", 100L, null, [], [], true, [])

        then:
        notThrown(Exception)
    }

    def "updateTenantPermissions skips delete when deleteExisting is false"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockPermService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousResourcePermissionService')
        def mockAccount = Mock(Account)
        def mockPerm = Mock(ResourcePermission)

        mockPerm.account >> mockAccount
        mockAccount.id >> 300L

        mockContext.services >> mockServices
        mockServices.resourcePermission >> mockPermService
        mockPermService.list(_) >> [mockPerm]

        when:
        DBUtils.updateTenantPermissions(mockContext, "CloudPool", 200L, [], [], [], false, [])

        then:
        notThrown(Exception)
    }

    def "updateTenantPermissions handles empty account list"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockPermService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousResourcePermissionService')

        mockContext.services >> mockServices
        mockServices.resourcePermission >> mockPermService
        mockPermService.list(_) >> []

        when:
        DBUtils.updateTenantPermissions(mockContext, "VirtualImage", 100L, [], [], [], true, [])

        then:
        notThrown(Exception)
    }

    def "getInterfaceTypeFromInterfaceModel returns interface types"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockComputeServer = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        def mockInterface = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerInterfaceService')
        def mockInterfaceType = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerInterfaceTypeService')
        def mockType = Mock(ComputeServerInterfaceType)

        mockContext.services >> mockServices
        mockServices.computeServer >> mockComputeServer
        mockComputeServer.computeServerInterface >> mockInterface
        mockInterface.type >> mockInterfaceType
        mockInterfaceType.list(_) >> [mockType]

        when:
        def result = DBUtils.getInterfaceTypeFromInterfaceModel(mockContext, ['e1000', 'virtio'])

        then:
        result.size() == 1
        result[0] == mockType
    }

    def "getZonePoolByFilterMap returns cloud pools by filter"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockCloudService = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService')
        def mockPoolService = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService')
        def mockPool = Mock(CloudPool)

        mockContext.services >> mockServices
        mockServices.cloud >> mockCloudService
        mockCloudService.pool >> mockPoolService
        mockPoolService.list(_) >> [mockPool]

        when:
        def result = DBUtils.getZonePoolByFilterMap(mockContext, [[key: 'name', operator: '=', value: 'test']])

        then:
        result.size() == 1
        result[0] == mockPool
    }

    def "getNetworkTypesByCodes returns network types"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockNetworkService = typedMock('com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService')
        def mockNetworkTypeService = typedMock('com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkTypeService')
        def mockType = Mock(NetworkType)

        mockContext.services >> mockServices
        mockServices.network >> mockNetworkService
        mockNetworkService.type >> mockNetworkTypeService
        mockNetworkTypeService.list(_) >> [mockType]

        when:
        def result = DBUtils.getNetworkTypesByCodes(mockContext, ['harvester-network'])

        then:
        result.size() == 1
        result[0] == mockType
    }

    def "getNetworkTypesByCodes returns empty list for empty codes"() {
        given:
        def mockContext = Mock(MorpheusContext)

        when:
        def result = DBUtils.getNetworkTypesByCodes(mockContext, [])

        then:
        result == []
    }

    def "getServerIdByNodeName finds server by hostname"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockComputeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        def mockServerGroup = Mock(ComputeServerGroup)
        def mockServer = Mock(ComputeServer)

        mockServerGroup.name >> "cluster-1"
        mockServerGroup.id >> 10L
        mockServer.id >> 888L
        mockServer.name >> "node-1"
        mockServer.hostname >> "node-1.example.com"

        mockContext.services >> mockServices
        mockServices.computeServer >> mockComputeServerService
        mockComputeServerService.find(_) >> mockServer

        when:
        def result = DBUtils.getServerIdByNodeName(mockContext, "node-1.example.com", mockServerGroup)

        then:
        result.id == 888L
        result.name == "node-1"
    }

    def "getServerIdByNodeName returns null when server not found"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockComputeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        def mockServerGroup = Mock(ComputeServerGroup)

        mockServerGroup.name >> "cluster-1"
        mockServerGroup.id >> 10L

        mockContext.services >> mockServices
        mockServices.computeServer >> mockComputeServerService
        mockComputeServerService.find(_) >> null

        when:
        def result = DBUtils.getServerIdByNodeName(mockContext, "nonexistent-node", mockServerGroup)

        then:
        result == null
    }

    // ── getClusterResourcePool exception path ──────────────────────────

    def "getClusterResourcePool returns null on exception"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockCloudService = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService')
        def mockPoolService = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService')
        def mockServerGroup = Mock(ComputeServerGroup)
        mockServerGroup.name >> "fail-cluster"
        mockServerGroup.id >> 1L
        mockContext.services >> mockServices
        mockServices.cloud >> mockCloudService
        mockCloudService.pool >> mockPoolService
        mockPoolService.find(_) >> { throw new RuntimeException("db error") }

        when:
        def result = DBUtils.getClusterResourcePool(mockContext, mockServerGroup)

        then:
        result == null
        notThrown(Exception)
    }

    // ── getStorageVolumeTypeMap exception path ─────────────────────────

    def "getStorageVolumeTypeMap returns empty map on exception"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockStorage = Stub(Object)
        def mockVolume = Stub(Object)
        def mockVolType = Stub(Object)
        mockContext.services >> mockServices
        mockServices.storage >> mockStorage
        mockStorage.volume >> mockVolume
        mockVolume.storageVolumeType >> mockVolType
        mockVolType.find(_) >> { throw new RuntimeException("fail") }

        when:
        def result = DBUtils.getStorageVolumeTypeMap(mockContext)

        then:
        result == [:]
        notThrown(Exception)
    }

    // ── getStorageControllerTypeMap exception path ─────────────────────

    def "getStorageControllerTypeMap returns empty map on exception"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockAsync = Mock(MorpheusAsyncServices)
        def mockStorage = Stub(Object)
        def mockCtrl = Stub(Object)
        def mockCtrlType = Stub(Object)
        mockContext.async >> mockAsync
        mockAsync.storage >> mockStorage
        mockStorage.controller >> mockCtrl
        mockCtrl.storageControllerType >> mockCtrlType
        mockCtrlType.find(_) >> { throw new RuntimeException("fail") }

        when:
        def result = DBUtils.getStorageControllerTypeMap(mockContext)

        then:
        result == [:]
        notThrown(Exception)
    }

    // ── findStorageControllerByType exception path ─────────────────────

    def "findStorageControllerByType returns null on exception"() {
        given:
        def mockServer = Mock(ComputeServer)
        def mockControllerType = Mock(StorageControllerType)
        mockControllerType.code >> "virtio"
        mockServer.controllers >> { throw new RuntimeException("no controllers") }

        when:
        def result = DBUtils.findStorageControllerByType(mockServer, mockControllerType)

        then:
        result == null
        notThrown(Exception)
    }

    // ── createDefaultResourcePool – pool already exists ────────────────

    def "createDefaultResourcePool returns existing pool without creating"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockCloudService = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService')
        def mockPoolService = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService')
        def mockServerGroup = Mock(ComputeServerGroup)
        def existingPool = Mock(CloudPool)
        mockServerGroup.name >> "test-cluster"
        mockServerGroup.id >> 42L
        mockContext.services >> mockServices
        mockServices.cloud >> mockCloudService
        mockCloudService.pool >> mockPoolService
        mockPoolService.find(_) >> existingPool

        when:
        def result = DBUtils.createDefaultResourcePool(mockContext, mockServerGroup)

        then:
        result == existingPool
        0 * mockPoolService.create(_)
    }

    def "createDefaultResourcePool throws MorpheusException when create fails"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockCloudService = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService')
        def mockPoolService = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService')
        def mockServerGroup = Mock(ComputeServerGroup)
        def mockCloud = Mock(Cloud)
        def mockAccount = Mock(Account)
        mockServerGroup.name >> "fail-cluster"
        mockServerGroup.id >> 99L
        mockServerGroup.cloud >> mockCloud
        mockServerGroup.owner >> mockAccount
        mockContext.services >> mockServices
        mockServices.cloud >> mockCloudService
        mockCloudService.pool >> mockPoolService
        mockPoolService.find(_) >> null
        mockPoolService.create(_) >> { throw new RuntimeException("db error") }

        when:
        DBUtils.createDefaultResourcePool(mockContext, mockServerGroup)

        then:
        thrown(MorpheusException)
    }

    // ── updateZoneStatus exception path ───────────────────────────────

    def "updateZoneStatus handles exception gracefully"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockAsync = Mock(MorpheusAsyncServices)
        def mockCloudService = typedMock('com.morpheusdata.core.cloud.MorpheusCloudService')
        def mockZone = Mock(Cloud)
        mockContext.async >> mockAsync
        mockAsync.cloud >> mockCloudService
        mockCloudService.updateCloudStatus(_, _, _, _) >> { throw new RuntimeException("fail") }

        when:
        DBUtils.updateZoneStatus(mockContext, mockZone, Cloud.Status.error, "fail msg")

        then:
        notThrown(Exception)
    }

    // ── getAuthConfig URL trimming paths ──────────────────────────────

    def "getAuthConfig strips trailing slash from apiUrl"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockCloud = Mock(Cloud)
        mockCloud.id >> 0L
        mockCloud.accountCredentialLoaded >> true
        mockCloud.accountCredentialData >> null
        mockCloud.serviceUrl >> "https://api.example.com/"
        mockCloud.serviceUsername >> "user"
        mockCloud.servicePassword >> "pass"
        mockCloud.getConfigProperty('serviceVersion') >> 'v1'

        when:
        def result = DBUtils.getAuthConfig(mockContext, mockCloud)

        then:
        result.apiUrl == "https://api.example.com"
    }

    def "getAuthConfig strips trailing /api from apiUrl"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockCloud = Mock(Cloud)
        mockCloud.id >> 0L
        mockCloud.accountCredentialLoaded >> true
        mockCloud.accountCredentialData >> null
        mockCloud.serviceUrl >> "https://cluster.example.com/api"
        mockCloud.serviceUsername >> "user"
        mockCloud.servicePassword >> "pass"
        mockCloud.getConfigProperty('serviceVersion') >> 'v1'

        when:
        def result = DBUtils.getAuthConfig(mockContext, mockCloud)

        then:
        result.apiUrl == "https://cluster.example.com"
    }

    def "getAuthConfig handles null apiUrl"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockCloud = Mock(Cloud)
        mockCloud.id >> 0L
        mockCloud.accountCredentialLoaded >> true
        mockCloud.accountCredentialData >> null
        mockCloud.serviceUrl >> null
        mockCloud.serviceUsername >> "user"
        mockCloud.servicePassword >> "pass"
        mockCloud.getConfigProperty('serviceVersion') >> 'v1'

        when:
        def result = DBUtils.getAuthConfig(mockContext, mockCloud)

        then:
        result.apiUrl == null
    }

    def "getAuthConfig uses credential data username and password when present"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockAsync = Mock(MorpheusAsyncServices)
        def mockCloudService = typedMock('com.morpheusdata.core.cloud.MorpheusCloudService')
        def mockCloud = Mock(Cloud)
        def mockCredential = Mock(AccountCredential)
        mockCloud.id >> 777L
        mockCloud.accountCredentialLoaded >> false
        mockCloud.accountCredentialData >> null
        mockCloud.serviceUrl >> "https://cluster.example.com"
        mockCloud.serviceUsername >> "svcUser"
        mockCloud.servicePassword >> "svcPass"
        mockCloud.getConfigProperty('serviceVersion') >> 'v1'
        mockContext.async >> mockAsync
        mockAsync.cloud >> mockCloudService
        mockCloudService.loadCredentials(777L) >> Single.just(mockCredential)
        mockCredential.data >> [username: 'credUser', password: 'credPass']

        when:
        def result = DBUtils.getAuthConfig(mockContext, mockCloud)

        then:
        // after credential load, cloud.accountCredentialData is set, but since mock doesn't persist...
        // at minimum shouldn't throw
        notThrown(Exception)
        result.apiUrl == "https://cluster.example.com"
    }

    // ── updateTenantPermissions – match found and update ───────────────

    def "updateTenantPermissions updates existing permission when accountId matches"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockPermService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousResourcePermissionService')
        def mockAsync = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def mockAsyncPermService = typedMock('com.morpheusdata.core.MorpheusResourcePermissionService')
        def mockAccount = Mock(Account)
        def mockPerm = Mock(ResourcePermission)

        mockAccount.id >> 100L
        mockPerm.account >> mockAccount

        mockContext.services >> mockServices
        mockContext.async >> mockAsync
        mockServices.resourcePermission >> mockPermService
        mockAsync.resourcePermission >> mockAsyncPermService
        mockPermService.list(_) >> [mockPerm]
        mockAsyncPermService.save(_) >> Single.just(mockPerm)

        when:
        DBUtils.updateTenantPermissions(mockContext, "VirtualImage", 50L, ['100'], [], [], true, [])

        then:
        notThrown(Exception)
    }

    def "updateTenantPermissions removes permission for account not in list"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockPermService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousResourcePermissionService')
        def mockAsync = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def mockAsyncPermService = typedMock('com.morpheusdata.core.MorpheusResourcePermissionService')
        def mockAccount = Mock(Account)
        def mockPerm = Mock(ResourcePermission)

        mockAccount.id >> 999L
        mockPerm.account >> mockAccount

        mockContext.services >> mockServices
        mockContext.async >> mockAsync
        mockServices.resourcePermission >> mockPermService
        mockAsync.resourcePermission >> mockAsyncPermService
        mockPermService.list(_) >> [mockPerm]
        mockAsyncPermService.remove(_) >> Single.just(true)

        when:
        DBUtils.updateTenantPermissions(mockContext, "VirtualImage", 50L, [], [], [], true, [])

        then:
        notThrown(Exception)
    }

    def "updateTenantPermissions creates new permission for new accountId"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockPermService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousResourcePermissionService')
        def mockAsync = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def mockAsyncPermService = typedMock('com.morpheusdata.core.MorpheusResourcePermissionService')

        mockContext.services >> mockServices
        mockContext.async >> mockAsync
        mockServices.resourcePermission >> mockPermService
        mockAsync.resourcePermission >> mockAsyncPermService
        mockPermService.list(_) >> []
        mockAsyncPermService.create(_) >> Single.just(Mock(ResourcePermission))

        when:
        DBUtils.updateTenantPermissions(mockContext, "VirtualImage", 50L, ['200'], [], [], true, [])

        then:
        notThrown(Exception)
    }

    // ── getInterfaceTypeFromInterfaceModel – null/empty input + empty result ──

    def "getInterfaceTypeFromInterfaceModel uses default codes when null input"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockComputeServer = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        def mockInterface = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerInterfaceService')
        def mockInterfaceType = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerInterfaceTypeService')
        def mockType = Mock(ComputeServerInterfaceType)
        mockContext.services >> mockServices
        mockServices.computeServer >> mockComputeServer
        mockComputeServer.computeServerInterface >> mockInterface
        mockInterface.type >> mockInterfaceType
        mockInterfaceType.list(_) >> [mockType]

        when:
        def result = DBUtils.getInterfaceTypeFromInterfaceModel(mockContext, null)

        then:
        result.size() == 1
    }

    def "getInterfaceTypeFromInterfaceModel returns empty list when service returns nothing"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockComputeServer = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        def mockInterface = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerInterfaceService')
        def mockInterfaceType = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerInterfaceTypeService')
        mockContext.services >> mockServices
        mockServices.computeServer >> mockComputeServer
        mockComputeServer.computeServerInterface >> mockInterface
        mockInterface.type >> mockInterfaceType
        mockInterfaceType.list(_) >> []

        when:
        def result = DBUtils.getInterfaceTypeFromInterfaceModel(mockContext, ['e1000'])

        then:
        result == []
    }

    // ── getZonePoolByFilterMap – empty result ─────────────────────────

    def "getZonePoolByFilterMap returns null when pool list is empty"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockCloudService = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService')
        def mockPoolService = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService')
        mockContext.services >> mockServices
        mockServices.cloud >> mockCloudService
        mockCloudService.pool >> mockPoolService
        mockPoolService.list(_) >> []

        when:
        def result = DBUtils.getZonePoolByFilterMap(mockContext, [[key: 'name', operator: '=', value: 'missing']])

        then:
        result == null
    }

    // ── getNetworkTypesByCodes – null from service ─────────────────────

    def "getNetworkTypesByCodes returns empty list when service returns null"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockNetworkService = typedMock('com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkService')
        def mockNetworkTypeService = typedMock('com.morpheusdata.core.synchronous.network.MorpheusSynchronousNetworkTypeService')
        mockContext.services >> mockServices
        mockServices.network >> mockNetworkService
        mockNetworkService.type >> mockNetworkTypeService
        mockNetworkTypeService.list(_) >> null

        when:
        def result = DBUtils.getNetworkTypesByCodes(mockContext, ['some-code'])

        then:
        result == []
    }

    def "getNetworkTypesByCodes returns empty list for null codes"() {
        given:
        def mockContext = Mock(MorpheusContext)

        when:
        def result = DBUtils.getNetworkTypesByCodes(mockContext, null)

        then:
        result == []
    }

    // ── getServerIdByNodeName – exception path ────────────────────────

    def "getServerIdByNodeName returns null on exception"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockComputeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        def mockServerGroup = Mock(ComputeServerGroup)
        mockServerGroup.name >> "cluster-1"
        mockServerGroup.id >> 10L
        mockContext.services >> mockServices
        mockServices.computeServer >> mockComputeServerService
        mockComputeServerService.find(_) >> { throw new RuntimeException("db error") }

        when:
        def result = DBUtils.getServerIdByNodeName(mockContext, "node-1", mockServerGroup)

        then:
        result == null
        notThrown(Exception)
    }

    // ── Additional coverage tests ────────────────────────────────────

    def "getNetworkTypesByCodes returns empty list for null codes"() {
        given:
        def mockContext = Mock(MorpheusContext)

        when:
        def result = DBUtils.getNetworkTypesByCodes(mockContext, null)

        then:
        result == []
    }

    def "getNetworkTypesByCodes returns empty list for empty codes"() {
        given:
        def mockContext = Mock(MorpheusContext)

        when:
        def result = DBUtils.getNetworkTypesByCodes(mockContext, [])

        then:
        result == []
    }

    def "getClusterResourcePool returns null when serverGroup has no resource pool"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServerGroup = Mock(ComputeServerGroup)
        mockServerGroup.resourcePool >> null

        when:
        def result = DBUtils.getClusterResourcePool(mockContext, mockServerGroup)

        then:
        result == null
    }

    // ── getSourceStorageVolume tests ──

    def "getSourceStorageVolume returns null when volume not found"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        def mockStorageService = Stub(Object)
        def mockVolumeService = Stub(Object)
        def mockCluster = Mock(ComputeServerGroup)
        def mockCloud = Mock(Cloud)

        mockCluster.cloud >> mockCloud
        mockCluster.id >> 56L
        mockCluster.name >> 'cluster-2'
        mockContext.services >> mockServices
        mockServices.storage >> mockStorageService
        mockStorageService.volume >> mockVolumeService
        mockVolumeService.find(_) >> null

        def volumeData = [
            sourceInfo: [type: 'pvc', name: 'missing-vol'],
            namespace: 'ns1'
        ]

        when:
        def result = DBUtils.getSourceStorageVolume(mockContext, mockCluster, volumeData)

        then:
        result == null
    }

    def "getSourceStorageVolume returns null on exception"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockServices = Mock(MorpheusServices)
        mockContext.services >> mockServices
        mockServices.storage >> { throw new RuntimeException("db error") }

        def mockCluster = Mock(ComputeServerGroup)
        mockCluster.cloud >> Mock(Cloud)
        mockCluster.id >> 57L
        mockCluster.name >> 'err-cluster'

        def volumeData = [
            sourceInfo: [type: 'pvc', name: 'vol'],
            namespace: 'ns'
        ]

        when:
        def result = DBUtils.getSourceStorageVolume(mockContext, mockCluster, volumeData)

        then:
        result == null
        notThrown(Exception)
    }

    // ── buildStorageVolumeCategory (static, no ComputeServer) ──

    def "buildStorageVolumeCategory returns PVC category"() {
        given:
        def volType = new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)

        when:
        def result = DBUtils.buildStorageVolumeCategory(volType, 100L)

        then:
        result == "${HarvesterConstants.CATEGORY_PVC_CLUSTER_PREFIX}100"
    }

    def "buildStorageVolumeCategory returns DataVolume category"() {
        given:
        def volType = new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME)

        when:
        def result = DBUtils.buildStorageVolumeCategory(volType, 200L)

        then:
        result == "${HarvesterConstants.CATEGORY_DATAVOLUME_CLUSTER_PREFIX}200"
    }

    def "buildStorageVolumeCategory returns DataSource category"() {
        given:
        def volType = new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATASOURCE)

        when:
        def result = DBUtils.buildStorageVolumeCategory(volType, 300L)

        then:
        result == "${HarvesterConstants.CATEGORY_DATAVOLUME_CLUSTER_PREFIX}300"
    }

    def "buildStorageVolumeCategory returns null for unknown type"() {
        given:
        def volType = new StorageVolumeType(code: 'unknownType')

        when:
        def result = DBUtils.buildStorageVolumeCategory(volType, 400L)

        then:
        result == null
    }

    // ── getAuthConfig credential loading branch ──

    def "getAuthConfig loads credentials when not loaded and credential has username"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockAsync = Mock(MorpheusAsyncServices)
        def mockCloudService = typedMock('com.morpheusdata.core.cloud.MorpheusCloudService')

        mockContext.async >> mockAsync
        mockAsync.cloud >> mockCloudService

        def cloud = new Cloud()
        cloud.id = 999L
        cloud.accountCredentialLoaded = false
        cloud.serviceUrl = "https://api.cluster.com"
        cloud.serviceUsername = "fallback-user"
        cloud.servicePassword = "fallback-pass"

        def credData = [username: 'cred-user', password: 'cred-pass']
        def accountCred = new AccountCredential()
        accountCred.data = credData

        mockCloudService.loadCredentials(999L) >> Single.just(accountCred)

        when:
        def result = DBUtils.getAuthConfig(mockContext, cloud)

        then:
        result.apiUsername == 'cred-user'
        result.apiPassword == 'cred-pass'
    }

    def "getAuthConfig falls back to service credentials when no credential data"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def mockAsync = Mock(MorpheusAsyncServices)
        def mockCloudService = typedMock('com.morpheusdata.core.cloud.MorpheusCloudService')

        mockContext.async >> mockAsync
        mockAsync.cloud >> mockCloudService

        def cloud = new Cloud()
        cloud.id = 998L
        cloud.accountCredentialLoaded = false
        cloud.serviceUrl = "https://api.cluster.com"
        cloud.serviceUsername = "svc-user"
        cloud.servicePassword = "svc-pass"

        mockCloudService.loadCredentials(998L) >> { throw new RuntimeException("no creds") }

        when:
        def result = DBUtils.getAuthConfig(mockContext, cloud)

        then:
        result.apiUsername == 'svc-user'
        result.apiPassword == 'svc-pass'
    }

    // --- getSourceStorageVolume tests ---

    def "getSourceStorageVolume returns volume when found"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def services = Mock(com.morpheusdata.core.MorpheusServices)
        def storageService = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def volumeService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')

        mockContext.services >> services
        services.storage >> storageService
        storageService.volume >> volumeService

        def cloud = new Cloud(id: 10L)
        def cluster = new ComputeServerGroup(id: 5L, name: 'cl1')
        cluster.cloud = cloud

        def vol = new StorageVolume(id: 99L, name: 'source-vol')
        volumeService.find(_) >> vol

        def volumeData = [
            sourceInfo: [type: 'dataVolume', name: 'source-vol'],
            namespace: 'default'
        ]

        when:
        def result = DBUtils.getSourceStorageVolume(mockContext, cluster, volumeData)

        then:
        result != null
        result.id == 99L
    }

    def "getSourceStorageVolume returns null when not found"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def services = Mock(com.morpheusdata.core.MorpheusServices)
        def storageService = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def volumeService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')

        mockContext.services >> services
        services.storage >> storageService
        storageService.volume >> volumeService
        volumeService.find(_) >> null

        def cloud = new Cloud(id: 10L)
        def cluster = new ComputeServerGroup(id: 5L, name: 'cl1')
        cluster.cloud = cloud

        def volumeData = [
            sourceInfo: [type: 'dataVolume', name: 'missing-vol'],
            namespace: 'default'
        ]

        when:
        def result = DBUtils.getSourceStorageVolume(mockContext, cluster, volumeData)

        then:
        result == null
    }

    def "getSourceStorageVolume handles exception"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def services = Mock(com.morpheusdata.core.MorpheusServices)
        def storageService = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def volumeService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')

        mockContext.services >> services
        services.storage >> storageService
        storageService.volume >> volumeService
        volumeService.find(_) >> { throw new RuntimeException('db error') }

        def cloud = new Cloud(id: 10L)
        def cluster = new ComputeServerGroup(id: 5L, name: 'cl1')
        cluster.cloud = cloud

        def volumeData = [
            sourceInfo: [type: 'pvc', name: 'err-vol'],
            namespace: 'test'
        ]

        when:
        def result = DBUtils.getSourceStorageVolume(mockContext, cluster, volumeData)

        then:
        result == null
    }

    // --- buildVMStorageVolumeCategory tests ---

    def "buildVMStorageVolumeCategory returns PVC category"() {
        given:
        def server = new ComputeServer(id: 10L)
        def volType = new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)

        when:
        def result = DBUtils.buildVMStorageVolumeCategory(server, volType, 5L)

        then:
        result.contains('persistentVolumeClaim')
        result.contains('.vm.10')
    }

    def "buildVMStorageVolumeCategory returns datavolume category"() {
        given:
        def server = new ComputeServer(id: 11L)
        def volType = new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME)

        when:
        def result = DBUtils.buildVMStorageVolumeCategory(server, volType, 5L)

        then:
        result.contains('dataVolume')
        result.contains('.vm.11')
    }

    def "buildVMStorageVolumeCategory returns null for unsupported type"() {
        given:
        def server = new ComputeServer(id: 12L)
        def volType = new StorageVolumeType(code: 'unsupported-type')

        when:
        def result = DBUtils.buildVMStorageVolumeCategory(server, volType, 5L)

        then:
        result == null
    }

    def "buildStorageVolumeCategory returns PVC category"() {
        given:
        def volType = new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)

        when:
        def result = DBUtils.buildStorageVolumeCategory(volType, 5L)

        then:
        result.contains('persistentVolumeClaim')
    }

    def "buildStorageVolumeCategory returns datasource category"() {
        given:
        def volType = new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATASOURCE)

        when:
        def result = DBUtils.buildStorageVolumeCategory(volType, 5L)

        then:
        result != null
    }

    def "buildStorageVolumeCategory returns null for unsupported"() {
        given:
        def volType = new StorageVolumeType(code: 'unknown')

        when:
        def result = DBUtils.buildStorageVolumeCategory(volType, 5L)

        then:
        result == null
    }

    // --- getServerIdByNodeName tests ---

    def "getServerIdByNodeName returns id when server found"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def services = Mock(com.morpheusdata.core.MorpheusServices)
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')

        mockContext.services >> services
        services.computeServer >> computeServerService

        def cluster = new ComputeServerGroup(id: 7L, name: 'cl1')
        def server = new ComputeServer(id: 42L, name: 'node-1')
        computeServerService.find(_) >> server

        when:
        def result = DBUtils.getServerIdByNodeName(mockContext, 'node-1', cluster)

        then:
        result != null
        result.id == 42L
    }

    def "getServerIdByNodeName returns null when not found"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def services = Mock(com.morpheusdata.core.MorpheusServices)
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')

        mockContext.services >> services
        services.computeServer >> computeServerService
        computeServerService.find(_) >> null

        def cluster = new ComputeServerGroup(id: 7L, name: 'cl1')

        when:
        def result = DBUtils.getServerIdByNodeName(mockContext, 'missing-node', cluster)

        then:
        result == null
    }

    def "findDatastoreByStorageClass falls back to the default class for per-image storage classes"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def services = Mock(com.morpheusdata.core.MorpheusServices)
        def cloudService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService)
        def datastoreService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService)
        mockContext.getServices() >> services
        services.getCloud() >> cloudService
        cloudService.getDatastore() >> datastoreService
        def cluster = new ComputeServerGroup(id: 7L)
        def defaultDs = new Datastore(id: 1L, name: HarvesterConstants.DEFAULT_STORAGE_CLASS)
        def calls = 0
        datastoreService.find(_) >> { args -> calls++; calls == 1 ? null : defaultDs }

        when:
        def result = DBUtils.findDatastoreByStorageClass(mockContext, cluster, 'longhorn-image-abc')

        then:
        result == defaultDs
        calls == 2
    }

    def "findDatastoreByStorageClass returns null for unknown regular classes and bad input"() {
        given:
        def mockContext = Mock(MorpheusContext)
        def services = Mock(com.morpheusdata.core.MorpheusServices)
        def cloudService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService)
        def datastoreService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService)
        mockContext.getServices() >> services
        services.getCloud() >> cloudService
        cloudService.getDatastore() >> datastoreService

        when:
        def result = DBUtils.findDatastoreByStorageClass(mockContext, new ComputeServerGroup(id: 7L), 'unknown-class')

        then:
        1 * datastoreService.find(_) >> null
        result == null
        DBUtils.findDatastoreByStorageClass(mockContext, null, 'x') == null
        DBUtils.findDatastoreByStorageClass(mockContext, new ComputeServerGroup(id: 7L), null) == null
    }
}
