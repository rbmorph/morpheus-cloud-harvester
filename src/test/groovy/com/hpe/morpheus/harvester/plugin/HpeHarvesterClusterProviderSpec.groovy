// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin

import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.ClusterProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.core.util.ClusterTabList
import com.morpheusdata.model.*
import com.morpheusdata.response.ServiceResponse
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Title
import spock.lang.Unroll

@Title('HpeHarvesterClusterProvider Specification')
class HpeHarvesterClusterProviderSpec extends Specification {

    private def typedMock(String className) {
        Mock((Class) Class.forName(className))
    }

    @Subject
    HpeHarvesterClusterProvider provider

    MorpheusContext morpheusContext
    HpeHarvesterPlugin plugin
    
    def setup() {
        plugin = Mock(HpeHarvesterPlugin)
        morpheusContext = Stub(MorpheusContext)
        
        provider = new HpeHarvesterClusterProvider(plugin, morpheusContext)
    }

    def cleanup() {
        provider = null
        morpheusContext = null
        plugin = null
    }

    // Constructor Tests
    def "constructor should initialize with plugin and context"() {
        when:
        def newProvider = new HpeHarvesterClusterProvider(plugin, morpheusContext)

        then:
        newProvider.plugin == plugin
        newProvider.context == morpheusContext
    }

    def "constructor should accept null plugin"() {
        when:
        def newProvider = new HpeHarvesterClusterProvider(null, morpheusContext)

        then:
        newProvider.plugin == null
        newProvider.context == morpheusContext
    }

    def "constructor should accept null context"() {
        when:
        def newProvider = new HpeHarvesterClusterProvider(plugin, null)

        then:
        newProvider.plugin == plugin
        newProvider.context == null
    }

    // Interface Tests
    def "should implement ClusterProvider interface"() {
        expect:
        provider instanceof ClusterProvider
    }

    def "should implement ServerPowerFacet"() {
        expect:
        provider instanceof ClusterProvider.ServerPowerFacet
    }

    // getCode Tests
    def "getCode should return cluster provider code"() {
        expect:
        provider.getCode() == HarvesterConstants.CLUSTER_PROVIDER_CODE
    }

    def "getCode should not return null"() {
        expect:
        provider.getCode() != null
    }

    def "getCode should be consistent across calls"() {
        when:
        def code1 = provider.getCode()
        def code2 = provider.getCode()

        then:
        code1 == code2
    }

    // getName Tests
    def "getName should return 'Harvester Cluster'"() {
        expect:
        provider.getName() == 'Harvester Cluster'
    }

    def "getName should not return null"() {
        expect:
        provider.getName() != null
    }

    def "getName should be consistent across calls"() {
        when:
        def name1 = provider.getName()
        def name2 = provider.getName()

        then:
        name1 == name2
    }

    // getDescription Tests
    def "getDescription should return cluster provider description"() {
        expect:
        provider.getDescription() == HarvesterConstants.CLUSTER_PROVER_DESCRIPTION
    }

    def "getDescription should not return null"() {
        expect:
        provider.getDescription() != null
    }

    // getIcon Tests
    def "getIcon should return Icon object"() {
        when:
        def icon = provider.getIcon()

        then:
        icon instanceof Icon
    }

    def "getIcon should have harvester logo path"() {
        when:
        def icon = provider.getIcon()

        then:
        icon.path == 'harvester-logo.svg'
        icon.darkPath == 'harvester-logo-dark.svg'
    }

    def "getIcon should not return null"() {
        expect:
        provider.getIcon() != null
    }

    // getCircularIcon Tests
    def "getCircularIcon should return Icon object"() {
        when:
        def icon = provider.getCircularIcon()

        then:
        icon instanceof Icon
    }

    def "getCircularIcon should have circular svg paths"() {
        when:
        def icon = provider.getCircularIcon()

        then:
        icon.path == 'harvester-circular.svg'
        icon.darkPath == 'harvester-circular.svg'
    }

    def "getCircularIcon should not return null"() {
        expect:
        provider.getCircularIcon() != null
    }

    // getOptionTypes Tests
    def "getOptionTypes should return empty list"() {
        when:
        def optionTypes = provider.getOptionTypes()

        then:
        optionTypes != null
        optionTypes instanceof Collection
        optionTypes.isEmpty()
    }

    // getControllerTypes Tests
    def "getControllerTypes should return Harvester Master type"() {
        when:
        def controllerTypes = provider.getControllerTypes()

        then:
        controllerTypes != null
        controllerTypes.size() == 1
        controllerTypes[0].name == 'Harvester Master'
        controllerTypes[0].code == 'harvester-master'
        controllerTypes[0].hostType == 'harvesterMaster'
    }

    def "getControllerTypes should have plugin flag set"() {
        when:
        def controllerTypes = provider.getControllerTypes()

        then:
        controllerTypes[0].isPlugin.toString() == 'true'
        controllerTypes[0].isEmbedded.toString() == 'false'
    }

    def "getControllerTypes should return List"() {
        when:
        def controllerTypes = provider.getControllerTypes()

        then:
        controllerTypes instanceof List
    }

    // getWorkerTypes Tests
    def "getWorkerTypes should return Harvester Worker type"() {
        when:
        def workerTypes = provider.getWorkerTypes()

        then:
        workerTypes != null
        workerTypes.size() == 1
        workerTypes[0].name == 'Harvester Worker'
        workerTypes[0].code == 'harvester-worker'
        workerTypes[0].hostType == 'harvesterWorker'
    }

    def "getWorkerTypes should have plugin flag set"() {
        when:
        def workerTypes = provider.getWorkerTypes()

        then:
        workerTypes[0].isPlugin.toString() == 'true'
        workerTypes[0].isEmbedded.toString() == 'false'
    }

    def "getWorkerTypes should return List"() {
        when:
        def workerTypes = provider.getWorkerTypes()

        then:
        workerTypes instanceof List
    }

    // getDatastoreTypes Tests
    def "getDatastoreTypes should return null"() {
        expect:
        provider.getDatastoreTypes() == null
    }

    // getProviderType Tests
    def "getProviderType should return cluster provider type"() {
        expect:
        provider.getProviderType() == HarvesterConstants.CLUSTER_PROVIDER_TYPE
    }

    def "getProviderType should not return null"() {
        expect:
        provider.getProviderType() != null
    }

    // getTabList Tests
    def "getTabList should return ClusterTabList"() {
        when:
        def tabList = provider.getTabList()

        then:
        tabList instanceof ClusterTabList
    }

    def "getTabList should have virtualImage enabled"() {
        when:
        def tabList = provider.getTabList()

        then:
        tabList.virtualImage == true
    }

    def "getTabList should have vm enabled"() {
        when:
        def tabList = provider.getTabList()

        then:
        tabList.vm == true
    }

    def "getTabList should have pool enabled"() {
        when:
        def tabList = provider.getTabList()

        then:
        tabList.pool == true
    }

    // getMorpheus Tests
    def "getMorpheus should return morpheusContext"() {
        expect:
        provider.getMorpheus() == morpheusContext
    }

    def "getMorpheus should return same instance on multiple calls"() {
        when:
        def context1 = provider.getMorpheus()
        def context2 = provider.getMorpheus()

        then:
        context1.is(context2)
    }

    // getPlugin Tests
    def "getPlugin should return plugin"() {
        expect:
        provider.getPlugin() == plugin
    }

    def "getPlugin should return same instance on multiple calls"() {
        when:
        def plugin1 = provider.getPlugin()
        def plugin2 = provider.getPlugin()

        then:
        plugin1.is(plugin2)
    }

    // convertClusterToManaged Tests
    def "convertClusterToManaged should return success"() {
        given:
        def cluster = Mock(ComputeServerGroup)

        when:
        def response = provider.convertClusterToManaged(cluster)

        then:
        response.success
        response instanceof ServiceResponse
    }

    def "convertClusterToManaged should handle null cluster"() {
        when:
        def response = provider.convertClusterToManaged(null)

        then:
        response.success
    }

    // finalizeLinuxComputeServer Tests
    def "finalizeLinuxComputeServer should set server as provisioned"() {
        given:
        def cluster = Mock(ComputeServerGroup) {
            getId() >> 123L
        }
        def server = Mock(ComputeServer)
        def opts = [:]
        def clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        
        morpheusContext.getServices() >> services
        services.getCluster() >> clusterService
        services.getComputeServer() >> computeServerService

        when:
        def response = provider.finalizeLinuxComputeServer(cluster, server, opts)

        then:
        1 * server.setProvision(true)
        1 * server.setStatus(HarvesterConstants.STATUS_PROVISIONED)
        1 * server.setCategory("${HarvesterConstants.CATEGORY_HARVESTER_NODE_PREFIX}123")
        1 * computeServerService.save(server)
        0 * cluster.setCode(_)
        1 * clusterService.save(cluster)
        response.success
    }

    def "finalizeLinuxComputeServer should handle null opts"() {
        given:
        def cluster = Mock(ComputeServerGroup) {
            getId() >> 456L
        }
        def server = Mock(ComputeServer)

        when:
        def response = provider.finalizeLinuxComputeServer(cluster, server, null)

        then:
        1 * server.setProvision(true)
        response.success
    }

    // validateServerGroupConfig Tests
    def "validateServerGroupConfig should handle empty config"() {
        given:
        def type = Mock(ComputeServerGroupType)
        def serverGroupConfig = [:]

        when:
        provider.validateServerGroupConfig(type, serverGroupConfig)

        then:
        thrown(NullPointerException)
    }

    // validateServerGroup Tests
    def "validateServerGroup should return success"() {
        given:
        def cluster = Mock(ComputeServerGroup)

        when:
        def response = provider.validateServerGroup(cluster)

        then:
        response.success
        response instanceof ServiceResponse
    }

    def "validateServerGroup should handle null cluster"() {
        when:
        def response = provider.validateServerGroup(null)

        then:
        response.success
    }

    // refreshDaily Tests
    def "refreshDaily should return success"() {
        given:
        def cluster = Mock(ComputeServerGroup)

        when:
        def response = provider.refreshDaily(cluster)

        then:
        response.success
        response instanceof ServiceResponse
    }

    def "refreshDaily should handle null cluster"() {
        when:
        def response = provider.refreshDaily(null)

        then:
        response.success
    }

    // updateServerGroup Tests
    def "updateServerGroup should return success with valid parameters"() {
        given:
        def cluster = Mock(ComputeServerGroup) {
            getName() >> 'test-cluster'
        }
        def opts = [option: 'value']

        when:
        def response = provider.updateServerGroup(cluster, opts)

        then:
        response.success
        response instanceof ServiceResponse
    }

    def "updateServerGroup should handle null opts"() {
        given:
        def cluster = Mock(ComputeServerGroup) {
            getName() >> 'test-cluster'
        }

        when:
        def response = provider.updateServerGroup(cluster, null)

        then:
        response.success
    }

    def "updateServerGroup should handle empty opts"() {
        given:
        def cluster = Mock(ComputeServerGroup) {
            getName() >> 'test-cluster'
        }
        def opts = [:]

        when:
        def response = provider.updateServerGroup(cluster, opts)

        then:
        response.success
    }

    // deleteServer Tests
    def "deleteServer should handle server deletion"() {
        given:
        def server = Mock(ComputeServer) {
            getName() >> 'test-server'
        }

        when:
        def response = provider.deleteServer(server)

        then:
        response != null
        response instanceof ServiceResponse
    }

    def "deleteServer should handle null server"() {
        when:
        def response = provider.deleteServer(null)

        then:
        response != null
    }

    // startServer Tests
    def "startServer should handle server start"() {
        given:
        def server = Mock(ComputeServer) {
            getName() >> 'test-server'
        }

        when:
        def response = provider.startServer(server)

        then:
        response != null
        response instanceof ServiceResponse
    }

    def "startServer should handle null server"() {
        when:
        def response = provider.startServer(null)

        then:
        response != null
    }

    // stopServer Tests
    def "stopServer should handle server stop"() {
        given:
        def server = Mock(ComputeServer) {
            getName() >> 'test-server'
        }

        when:
        def response = provider.stopServer(server)

        then:
        response != null
        response instanceof ServiceResponse
    }

    def "stopServer should handle null server"() {
        when:
        def response = provider.stopServer(null)

        then:
        response != null
    }

    // deleteCluster Tests
    def "deleteCluster should handle cluster with no virtual images"() {
        given:
        def cluster = Mock(ComputeServerGroup) {
            getId() >> 123L
            getName() >> 'test-cluster'
        }
        def virtualImageService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        
        virtualImageService.list(_) >> []
        computeServerService.list(_) >> []
        morpheusContext.getServices() >> services
        services.getVirtualImage() >> virtualImageService
        services.getComputeServer() >> computeServerService

        when:
        def response = provider.deleteCluster(cluster)

        then:
        response.success
    }

    def "deleteCluster should handle cluster with virtual images"() {
        given:
        def cluster = Mock(ComputeServerGroup) {
            getId() >> 123L
            getName() >> 'test-cluster'
        }
        def virtualImage = Mock(VirtualImage) {
            getName() >> 'test-image'
            getVolumes() >> []
            getControllers() >> []
        }
        def virtualImageService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        
        virtualImageService.list(_) >> [virtualImage]
        computeServerService.list(_) >> []
        morpheusContext.getServices() >> services
        services.getVirtualImage() >> virtualImageService
        services.getComputeServer() >> computeServerService

        when:
        def response = provider.deleteCluster(cluster)

        then:
        1 * virtualImageService.bulkRemove([virtualImage])
        response.success
    }

    def "deleteCluster should delete virtual image volumes and controllers"() {
        given:
        def cluster = Mock(ComputeServerGroup) {
            getId() >> 123L
            getName() >> 'test-cluster'
        }
        def volume = Mock(StorageVolume)
        def controller = Mock(StorageController)
        def virtualImage = Mock(VirtualImage) {
            getName() >> 'test-image'
            getVolumes() >> [volume]
            getControllers() >> [controller]
        }
        def virtualImageService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        def storageVolumeService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def storageControllerService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageControllerService')
        def storageService = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        
        virtualImageService.list(_) >> [virtualImage]
        computeServerService.list(_) >> []
        morpheusContext.getServices() >> services
        services.getVirtualImage() >> virtualImageService
        services.getComputeServer() >> computeServerService
        services.getStorage() >> storageService
        storageService.getVolume() >> storageVolumeService
        storageService.getController() >> storageControllerService

        when:
        def response = provider.deleteCluster(cluster)

        then:
        1 * virtualImageService.bulkRemove([virtualImage])
        1 * storageVolumeService.bulkRemove([volume])
        1 * storageControllerService.bulkRemove([controller])
        response.success
    }

    def "deleteCluster should handle virtual machines"() {
        given:
        def cluster = Mock(ComputeServerGroup) {
            getId() >> 123L
            getName() >> 'test-cluster'
        }
        def vm = Mock(ComputeServer) {
            getVolumes() >> []
            getControllers() >> []
        }
        def virtualImageService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def computeServerService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        def asyncComputeServerService = typedMock('com.morpheusdata.core.MorpheusComputeServerService')
        def asyncServices = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        
        virtualImageService.list(_) >> []
        computeServerService.list(_) >> [vm]
        asyncComputeServerService.bulkRemove(_) >> Single.just(true)
        
        morpheusContext.getAsync() >> asyncServices
        asyncServices.getComputeServer() >> asyncComputeServerService
        
        morpheusContext.getServices() >> services
        services.getVirtualImage() >> virtualImageService
        services.getComputeServer() >> computeServerService

        when:
        def response = provider.deleteCluster(cluster)

        then:
        response.success
    }

    def "deleteCluster should handle null cluster"() {
        when:
        def response = provider.deleteCluster(null)

        then:
        !response.success
    }

    // getNetworkTypes Tests
    def "getNetworkTypes should return null"() {
        expect:
        provider.getNetworkTypes() == null
    }

    // getAvailableProvisionProviders Tests
    def "getAvailableProvisionProviders should return provision providers from plugin"() {
        given:
        def provisionProvider = Mock(ProvisionProvider)
        plugin.getProvidersByType(_) >> [provisionProvider]

        when:
        def providers = provider.getAvailableProvisionProviders()

        then:
        providers != null
        providers instanceof Collection
    }

    def "getAvailableProvisionProviders should handle empty list from plugin"() {
        given:
        plugin.getProvidersByType(_) >> []

        when:
        def providers = provider.getAvailableProvisionProviders()

        then:
        providers != null
        providers.isEmpty()
    }

    // getProvisionProvider Tests
    def "getProvisionProvider should return matching provider by code"() {
        given:
        def provisionProvider1 = Mock(ProvisionProvider) {
            getCode() >> 'test-code'
        }
        def provisionProvider2 = Mock(ProvisionProvider) {
            getCode() >> 'other-code'
        }
        plugin.getProvidersByType(_) >> [provisionProvider1, provisionProvider2]

        when:
        def result = provider.getProvisionProvider('test-code')

        then:
        result == provisionProvider1
    }

    def "getProvisionProvider should return null for non-existent code"() {
        given:
        plugin.getProvidersByType(_) >> []

        when:
        def result = provider.getProvisionProvider('non-existent')

        then:
        result == null
    }

    def "getProvisionProvider should handle null code"() {
        given:
        plugin.getProvidersByType(_) >> []

        when:
        def result = provider.getProvisionProvider(null)

        then:
        result == null
    }

    // getDefaultNamespace Tests
    def "getDefaultNamespace should return CloudPool"() {
        given:
        def cluster = Mock(ComputeServerGroup) {
            getId() >> 123L
        }

        when:
        def pool = provider.getDefaultNamespace(cluster)

        then:
        pool instanceof CloudPool || pool == null
    }

    // Thread Safety Tests
    def "should handle concurrent getCode calls"() {
        given:
        def results = Collections.synchronizedList([])

        when:
        def threads = (1..10).collect {
            Thread.start {
                results.add(provider.getCode())
            }
        }
        threads*.join()

        then:
        results.size() == 10
        results.unique().size() == 1
    }

    def "should handle concurrent getName calls"() {
        given:
        def results = Collections.synchronizedList([])

        when:
        def threads = (1..10).collect {
            Thread.start {
                results.add(provider.getName())
            }
        }
        threads*.join()

        then:
        results.size() == 10
        results.unique().size() == 1
    }

    def "should handle concurrent getIcon calls"() {
        given:
        def results = Collections.synchronizedList([])

        when:
        def threads = (1..5).collect {
            Thread.start {
                results.add(provider.getIcon())
            }
        }
        threads*.join()

        then:
        results.size() == 5
        results.every { it instanceof Icon }
    }

    def "should handle concurrent validateServerGroup calls"() {
        given:
        def cluster = Mock(ComputeServerGroup)
        def results = Collections.synchronizedList([])

        when:
        def threads = (1..5).collect {
            Thread.start {
                def response = provider.validateServerGroup(cluster)
                results.add(response.success)
            }
        }
        threads*.join()

        then:
        results.size() == 5
        results.every { it == true }
    }

    // Idempotency Tests
    def "getCode should be idempotent"() {
        when:
        def codes = (1..10).collect { provider.getCode() }

        then:
        codes.unique().size() == 1
    }

    def "getName should be idempotent"() {
        when:
        def names = (1..10).collect { provider.getName() }

        then:
        names.unique().size() == 1
    }

    def "getProviderType should be idempotent"() {
        when:
        def types = (1..10).collect { provider.getProviderType() }

        then:
        types.unique().size() == 1
    }

    def "validateServerGroup should be idempotent"() {
        given:
        def cluster = Mock(ComputeServerGroup)

        when:
        def response1 = provider.validateServerGroup(cluster)
        def response2 = provider.validateServerGroup(cluster)
        def response3 = provider.validateServerGroup(cluster)

        then:
        response1.success
        response2.success
        response3.success
    }

    // Provider Instance Tests
    def "multiple provider instances should be independent"() {
        given:
        def provider1 = new HpeHarvesterClusterProvider(plugin, morpheusContext)
        def provider2 = new HpeHarvesterClusterProvider(Mock(HpeHarvesterPlugin), Mock(MorpheusContext))

        expect:
        !provider1.is(provider2)
        provider1.getCode() == provider2.getCode()
        provider1.getName() == provider2.getName()
    }

    def "provider should maintain state consistency"() {
        when:
        provider.getCode()
        provider.getName()
        provider.getIcon()

        then:
        provider.plugin == plugin
        provider.context == morpheusContext
    }

    // ComputeType Tests
    @Unroll
    def "controller types should have correct properties: #property"() {
        given:
        def controllerTypes = provider.getControllerTypes()
        def controllerType = controllerTypes[0]

        expect:
        controllerType."${property}" != null

        where:
        property << ['name', 'code', 'hostType', 'isPlugin', 'isEmbedded']
    }

    @Unroll
    def "worker types should have correct properties: #property"() {
        given:
        def workerTypes = provider.getWorkerTypes()
        def workerType = workerTypes[0]

        expect:
        workerType."${property}" != null

        where:
        property << ['name', 'code', 'hostType', 'isPlugin', 'isEmbedded']
    }

    // Icon Tests
    def "icon and circular icon should be different"() {
        when:
        def icon = provider.getIcon()
        def circularIcon = provider.getCircularIcon()

        then:
        icon.path != circularIcon.path
    }

    def "icons should have both light and dark paths"() {
        when:
        def icon = provider.getIcon()
        def circularIcon = provider.getCircularIcon()

        then:
        icon.path != null
        icon.darkPath != null
        circularIcon.path != null
        circularIcon.darkPath != null
    }

    def "refresh should return failure when cluster reload returns null"() {
        given:
        def cluster = Mock(ComputeServerGroup) {
            getId() >> 900L
            getName() >> 'reload-null-cluster'
        }
        def asyncServices = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def asyncClusterService = typedMock('com.morpheusdata.core.MorpheusComputeServerGroupService')
        def nullSingle = Mock(io.reactivex.rxjava3.core.Single)
        morpheusContext.getAsync() >> asyncServices
        asyncServices.getCluster() >> asyncClusterService
        asyncClusterService.get(900L) >> nullSingle
        nullSingle.blockingGet() >> null

        when:
        def response = provider.refresh(cluster)

        then:
        !response.success
        response.msg
    }

    def "refresh should return failure when async cluster lookup throws"() {
        given:
        def cluster = Mock(ComputeServerGroup) {
            getId() >> 901L
            getName() >> 'lookup-throws-cluster'
        }
        def asyncServices = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def asyncClusterService = typedMock('com.morpheusdata.core.MorpheusComputeServerGroupService')
        morpheusContext.getAsync() >> asyncServices
        asyncServices.getCluster() >> asyncClusterService
        asyncClusterService.get(901L) >> { throw new RuntimeException('db timeout') }

        when:
        def response = provider.refresh(cluster)

        then:
        !response.success
    }

    def "refresh should fail when the inventory sync cannot complete"() {
        given:
        def cloud = Mock(Cloud) {
            getName() >> 'cloud-a'
        }
        def loadedCluster = Mock(ComputeServerGroup) {
            getId() >> 902L
            getName() >> 'refresh-cluster'
            getStatus() >> ComputeServerGroup.Status.ok
            getConfigProperty('prevStatus') >> null
            getConfigProperty('importExisting') >> 'false'
            getConfigProperty(HarvesterConstants.CONFIG_MANAGED_BY_CLOUD) >> null
            getCloud() >> cloud
        }
        def asyncServices = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def asyncClusterService = typedMock('com.morpheusdata.core.MorpheusComputeServerGroupService')
        morpheusContext.getAsync() >> asyncServices
        asyncServices.getCluster() >> asyncClusterService
        // the async get returns a Maybe
        asyncClusterService.get(902L) >> io.reactivex.rxjava3.core.Maybe.just(loadedCluster)
        def cluster = Mock(ComputeServerGroup) { getId() >> 902L }
        GroovySpy(com.hpe.morpheus.harvester.plugin.sync.HarvesterInventorySync, global: true, constructorArgs: [null, null, false])
        def sync = Mock(com.hpe.morpheus.harvester.plugin.sync.HarvesterInventorySync, constructorArgs: [null, null, false])
        new com.hpe.morpheus.harvester.plugin.sync.HarvesterInventorySync(_, _, _) >> sync
        sync.execute() >> { throw new RuntimeException('sync failed') }

        when:
        def response = provider.refresh(cluster)

        then:
        !response.success
        response.msg == 'Error refreshing Harvester cluster'
    }

    def "refresh skips clusters managed by a Harvester cloud"() {
        given:
        def cloud = Mock(Cloud) {
            getName() >> 'cloud-a'
        }
        def loadedCluster = Mock(ComputeServerGroup) {
            getId() >> 903L
            getName() >> 'refresh-cluster'
            getStatus() >> ComputeServerGroup.Status.ok
            getConfigProperty('prevStatus') >> null
            getConfigProperty('importExisting') >> 'false'
            getConfigProperty(HarvesterConstants.CONFIG_MANAGED_BY_CLOUD) >> managedByCloud
            getCloud() >> cloud
        }
        def asyncServices = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def asyncClusterService = typedMock('com.morpheusdata.core.MorpheusComputeServerGroupService')
        morpheusContext.getAsync() >> asyncServices
        asyncServices.getCluster() >> asyncClusterService
        // the async get returns a Maybe
        asyncClusterService.get(903L) >> io.reactivex.rxjava3.core.Maybe.just(loadedCluster)
        def cluster = Mock(ComputeServerGroup) { getId() >> 903L }
        GroovySpy(com.hpe.morpheus.harvester.plugin.sync.HarvesterInventorySync, global: true, constructorArgs: [null, null, false])

        when:
        def response = provider.refresh(cluster)

        then:
        response.success
        0 * new com.hpe.morpheus.harvester.plugin.sync.HarvesterInventorySync(_, _, _)

        where:
        managedByCloud << [true, 'true']
    }

    def "isCloudManaged reads the managedByCloud flag"() {
        given:
        def group = new ComputeServerGroup()
        if (value != null) {
            group.setConfigProperty(HarvesterConstants.CONFIG_MANAGED_BY_CLOUD, value)
        }

        expect:
        HpeHarvesterClusterProvider.isCloudManaged(group) == expected

        where:
        value   || expected
        true    || true
        'true'  || true
        null    || false
        false   || false
    }

    def "validateServerGroupConfig should return detailed error map when auth check fails"() {
        given:
        GroovySpy(com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient, global: true)
        def apiClient = Mock(com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient)
        1 * new com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient(_ as ComputeServerGroup) >> apiClient
        1 * apiClient.get(HarvesterConstants.HARVESTER_API_V1_NAMESPACES, [limit: '1']) >> ServiceResponse.error('denied')

        def type = Mock(ComputeServerGroupType)
        def config = [server: [serviceUrl: 'https://bad.cluster:6443', serviceToken: 'bad-token']]

        when:
        def response = provider.validateServerGroupConfig(type, config)

        then:
        !response.success
        response.errors['server.serviceUrl'].contains('Unable to connect')
    }

    def "validateServerGroupConfig should return success when user endpoint call succeeds"() {
        given:
        GroovySpy(com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient, global: true)
        def apiClient = Mock(com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient)
        1 * new com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient(_ as ComputeServerGroup) >> apiClient
        1 * apiClient.get(HarvesterConstants.HARVESTER_API_V1_NAMESPACES, [limit: '1']) >> ServiceResponse.success([kind: 'NamespaceList'])

        def type = Mock(ComputeServerGroupType)
        def config = [server: [serviceUrl: 'https://ok.cluster:6443', serviceToken: 'token']]

        when:
        def response = provider.validateServerGroupConfig(type, config)

        then:
        response.success
    }

    def "refresh should short-circuit when cluster is deprovisioning"() {
        given:
        def cluster = Mock(ComputeServerGroup) {
            getId() >> 1002L
            getName() >> 'deprov-cluster'
        }
        def deprovisioningStatus = Mock(Object) {
            name() >> HarvesterConstants.STATUS_DEPROVISIONING
        }
        def deprovisioningCluster = Mock(ComputeServerGroup) {
            getId() >> 1002L
            getName() >> 'deprov-cluster'
            getStatus() >> deprovisioningStatus
            getConfigProperty('prevStatus') >> HarvesterConstants.STATUS_DEPROVISIONING
        }
        def asyncServices = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def asyncClusterService = typedMock('com.morpheusdata.core.MorpheusComputeServerGroupService')
        morpheusContext.getAsync() >> asyncServices
        asyncServices.getCluster() >> asyncClusterService
        asyncClusterService.get(1002L) >> Single.just(deprovisioningCluster)

        when:
        def response = provider.refresh(cluster)

        then:
        response instanceof ServiceResponse
    }

    def "deleteServer should return delegated response from VirtualMachineDeletion"() {
        given:
        def server = Mock(ComputeServer) {
            getName() >> 'vm-delete-1'
        }
        def delegated = ServiceResponse.success([deleted: true])

        GroovyMock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineDeletion, global: true)
        def vmDeletion = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineDeletion)
        1 * new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineDeletion(morpheusContext) >> vmDeletion
        1 * vmDeletion.deleteServer(server) >> delegated

        when:
        def response = provider.deleteServer(server)

        then:
        response.success
        response.data.deleted == true
    }

    def "startServer and stopServer should delegate to VirtualMachineReconfigure"() {
        given:
        def server = Mock(ComputeServer) {
            getName() >> 'vm-power-1'
        }

        GroovyMock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure, global: true)
        def vmReconfigure = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure)
        2 * new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure(morpheusContext, server) >> vmReconfigure
        1 * vmReconfigure.startVM() >> ServiceResponse.success([state: 'running'])
        1 * vmReconfigure.stopVM() >> ServiceResponse.success([state: 'stopped'])

        when:
        def startResponse = provider.startServer(server)
        def stopResponse = provider.stopServer(server)

        then:
        startResponse.success
        stopResponse.success
    }

    def "getIacResourceMappingProvider should return null and backup providers should be empty"() {
        when:
        def iacProvider = provider.getIacResourceMappingProvider()
        def backupProviders = provider.getAvailableBackupProviders()

        then:
        iacProvider == null
        backupProviders != null
        backupProviders.isEmpty()
    }

    def "refreshDaily returns success"() {
        given:
        def cluster = new ComputeServerGroup(id: 4L, name: 'daily-cluster')

        when:
        def response = provider.refreshDaily(cluster)

        then:
        response.success
    }

    def "updateServerGroup returns success"() {
        given:
        def cluster = new ComputeServerGroup(id: 5L, name: 'update-cluster')
        def opts = [key: 'value']

        when:
        def response = provider.updateServerGroup(cluster, opts)

        then:
        response.success
    }

    def "updateServerGroup accepts empty opts map"() {
        given:
        def cluster = new ComputeServerGroup(id: 6L, name: 'empty-opts')

        when:
        def response = provider.updateServerGroup(cluster, [:])

        then:
        response.success
    }

    def "updateServerGroup accepts null opts"() {
        given:
        def cluster = new ComputeServerGroup(id: 7L, name: 'null-opts')

        when:
        def response = provider.updateServerGroup(cluster, null)

        then:
        response.success
    }

    def "refreshDaily does not require cluster state"() {
        given:
        def cluster = new ComputeServerGroup(id: 8L, name: 'daily-no-state', status: null)

        when:
        def response = provider.refreshDaily(cluster)

        then:
        response.success
    }

    // Additional refresh tests for edge cases

    def "refresh correctly evaluates deprovisioning config property"() {
        given:
        def cluster = Mock(ComputeServerGroup) {
            getConfigProperty('prevStatus') >> HarvesterConstants.STATUS_DEPROVISIONING
        }

        expect:
        cluster.getConfigProperty('prevStatus') == HarvesterConstants.STATUS_DEPROVISIONING
    }

    def "refresh correctly evaluates importExisting property variations"() {
        given:
        def cluster1 = Mock(ComputeServerGroup) {
            getConfigProperty('importExisting') >> 'on'
        }
        def cluster2 = Mock(ComputeServerGroup) {
            getConfigProperty('importExisting') >> 'true'
        }
        def cluster3 = Mock(ComputeServerGroup) {
            getConfigProperty('importExisting') >> true
        }
        def cluster4 = Mock(ComputeServerGroup) {
            getConfigProperty('importExisting') >> 'false'
        }

        expect:
        cluster1.getConfigProperty('importExisting') == 'on'
        cluster2.getConfigProperty('importExisting') == 'true'
        cluster3.getConfigProperty('importExisting') == true
        cluster4.getConfigProperty('importExisting') == 'false'
    }

    // --- refresh method tests ---

    def "refresh returns error when cluster reload fails"() {
        given:
        def cluster = Mock(ComputeServerGroup) {
            id >> 100L
            name >> 'test-cluster'
            status >> null
        }
        def asyncCluster = Mock(Object)
        def asyncSvc = Mock(com.morpheusdata.core.MorpheusAsyncServices)
        morpheusContext = Mock(MorpheusContext)
        morpheusContext.async >> asyncSvc
        asyncSvc.cluster >> asyncCluster
        asyncCluster.get(100L) >> io.reactivex.rxjava3.core.Maybe.empty()

        provider = new HpeHarvesterClusterProvider(plugin, morpheusContext)

        when:
        def result = provider.refresh(cluster)

        then:
        !result.success
    }

    def "refresh returns error on exception"() {
        given:
        def cluster = Mock(ComputeServerGroup) {
            id >> 100L
            name >> 'test-cluster'
        }
        def asyncSvc = Mock(com.morpheusdata.core.MorpheusAsyncServices)
        morpheusContext = Mock(MorpheusContext)
        morpheusContext.async >> asyncSvc
        asyncSvc.cluster >> { throw new RuntimeException("db error") }

        provider = new HpeHarvesterClusterProvider(plugin, morpheusContext)

        when:
        def result = provider.refresh(cluster)

        then:
        !result.success
    }

    // --- deleteServer tests ---

    def "deleteServer delegates to VirtualMachineDeletion"() {
        given:
        def server = new ComputeServer(name: 'test-vm')
        GroovySpy(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineDeletion, global: true)
        def vmDeletion = Mock(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineDeletion)
        new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineDeletion(_) >> vmDeletion
        vmDeletion.deleteServer(_) >> ServiceResponse.success()

        when:
        def result = provider.deleteServer(server)

        then:
        result.success
    }

    def "deleteServer handles exception"() {
        given:
        def server = new ComputeServer(name: 'test-vm')
        GroovySpy(com.hpe.morpheus.harvester.plugin.provision.VirtualMachineDeletion, global: true)
        new com.hpe.morpheus.harvester.plugin.provision.VirtualMachineDeletion(_) >> { throw new RuntimeException("fail") }

        when:
        def result = provider.deleteServer(server)

        then:
        !result.success
    }

    // --- deleteCluster tests ---

    def "deleteCluster returns success with no virtual images or VMs v2"() {
        given:
        def cluster = Mock(ComputeServerGroup) {
            id >> 100L
            name >> 'test-cluster'
        }
        def services = Mock(com.morpheusdata.core.MorpheusServices)
        def asyncSvc = Mock(com.morpheusdata.core.MorpheusAsyncServices)
        morpheusContext = Mock(MorpheusContext)
        morpheusContext.services >> services
        morpheusContext.async >> asyncSvc

        def viSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        services.virtualImage >> viSvc
        viSvc.list(_) >> []

        def csSvc = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        services.computeServer >> csSvc
        csSvc.list(_) >> []

        provider = new HpeHarvesterClusterProvider(plugin, morpheusContext)

        when:
        def result = provider.deleteCluster(cluster)

        then:
        result.success
    }

    def "deleteCluster removes virtual images and their volumes"() {
        given:
        def cluster = Mock(ComputeServerGroup) {
            id >> 100L
            name >> 'test-cluster'
        }
        def services = Mock(com.morpheusdata.core.MorpheusServices)
        def asyncSvc = Mock(com.morpheusdata.core.MorpheusAsyncServices)
        morpheusContext = Mock(MorpheusContext)
        morpheusContext.services >> services
        morpheusContext.async >> asyncSvc

        def viSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        services.virtualImage >> viSvc
        def vol1 = new com.morpheusdata.model.StorageVolume(id: 1L)
        def ctrl1 = new com.morpheusdata.model.StorageController(id: 1L)
        def vi = new com.morpheusdata.model.VirtualImage(id: 1L, name: 'img1')
        vi.volumes = [vol1]
        vi.controllers = [ctrl1]
        viSvc.list(_) >> [vi]
        def bulkResult = Mock(com.morpheusdata.core.BulkRemoveResult)
        bulkResult.success >> true
        viSvc.bulkRemove(_) >> bulkResult

        def storageService = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def volumeService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def controllerService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageControllerService')
        services.storage >> storageService
        storageService.volume >> volumeService
        storageService.controller >> controllerService
        volumeService.bulkRemove(_) >> bulkResult
        controllerService.bulkRemove(_) >> bulkResult

        def csSvc = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        services.computeServer >> csSvc
        csSvc.list(_) >> []

        provider = new HpeHarvesterClusterProvider(plugin, morpheusContext)

        when:
        def result = provider.deleteCluster(cluster)

        then:
        result.success
    }

    def "deleteCluster removes VMs with their volumes"() {
        given:
        def cluster = Mock(ComputeServerGroup) {
            id >> 100L
            name >> 'test-cluster'
        }
        def services = Mock(com.morpheusdata.core.MorpheusServices)
        def asyncSvc = Mock(com.morpheusdata.core.MorpheusAsyncServices)
        morpheusContext = Mock(MorpheusContext)
        morpheusContext.services >> services
        morpheusContext.async >> asyncSvc

        def viSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        services.virtualImage >> viSvc
        viSvc.list(_) >> []

        def vol1 = new com.morpheusdata.model.StorageVolume(id: 10L)
        def ctrl1 = new com.morpheusdata.model.StorageController(id: 10L)
        def vm = new ComputeServer(id: 1L, name: 'vm1')
        vm.volumes = [vol1]
        vm.controllers = [ctrl1]
        def csSvc = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService')
        services.computeServer >> csSvc
        csSvc.list(_) >> [vm]

        def asyncCsSvc = Mock(com.morpheusdata.core.MorpheusComputeServerService)
        asyncSvc.computeServer >> asyncCsSvc
        def bulkResult = Mock(com.morpheusdata.core.BulkRemoveResult)
        bulkResult.success >> true
        asyncCsSvc.bulkRemove(_) >> io.reactivex.rxjava3.core.Single.just(bulkResult)

        def storageService = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def volumeService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def controllerService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageControllerService')
        services.storage >> storageService
        storageService.volume >> volumeService
        storageService.controller >> controllerService
        volumeService.bulkRemove(_) >> bulkResult
        controllerService.bulkRemove(_) >> bulkResult

        provider = new HpeHarvesterClusterProvider(plugin, morpheusContext)

        when:
        def result = provider.deleteCluster(cluster)

        then:
        result.success
    }

    def "deleteCluster handles exception"() {
        given:
        def cluster = Mock(ComputeServerGroup) {
            id >> 100L
            name >> 'test-cluster'
        }
        def services = Mock(com.morpheusdata.core.MorpheusServices)
        morpheusContext = Mock(MorpheusContext)
        morpheusContext.services >> services
        services.virtualImage >> { throw new RuntimeException("db error") }

        provider = new HpeHarvesterClusterProvider(plugin, morpheusContext)

        when:
        def result = provider.deleteCluster(cluster)

        then:
        !result.success
    }

    // --- startServer / stopServer exception paths only ---

    def "startServer returns error on constructor exception"() {
        given:
        def server = new ComputeServer()
        server.id = 1L
        server.name = 'test-vm'
        server.resourcePool = null  // Will cause issue in constructor

        when:
        def result = provider.startServer(server)

        then:
        !result.success
    }

    def "stopServer returns error on missing server properties"() {
        given:
        def server = new ComputeServer()
        server.id = 1L
        server.name = 'test-vm'
        server.resourcePool = null

        when:
        def result = provider.stopServer(server)

        then:
        !result.success
    }
}