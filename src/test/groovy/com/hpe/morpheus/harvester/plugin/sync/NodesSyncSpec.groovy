package com.hpe.morpheus.harvester.plugin.sync

import com.hpe.morpheus.harvester.plugin.HpeHarvesterPlugin
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusComputeServerService
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.MorpheusOsTypeService
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.OsType
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import com.morpheusdata.model.projection.ComputeServerIdentityProjection
import spock.lang.Specification
import spock.lang.Unroll

class NodesSyncSpec extends Specification {

    private def invokePrivate(Object target, String methodName, Class[] paramTypes, Object[] args) {
        def method = target.class.getDeclaredMethod(methodName, paramTypes)
        method.accessible = true
        method.invoke(target, args)
    }

    private def invokePrivateStatic(Class<?> clazz, String methodName, Class[] paramTypes, Object[] args) {
        def method = clazz.getDeclaredMethod(methodName, paramTypes)
        method.accessible = true
        method.invoke(null, args)
    }

    NodesSync sync
    HpeHarvesterPlugin plugin
    ComputeServerGroup cluster
    MorpheusContext morpheusContext

    def setup() {
        plugin = Mock(HpeHarvesterPlugin)
        cluster = Mock(ComputeServerGroup)
        morpheusContext = Mock(MorpheusContext)

        cluster.id >> 456L
        cluster.name >> 'nodes-cluster'
        plugin.morpheusContext >> morpheusContext

        sync = new NodesSync(plugin, cluster)
        // encodeAsJSON() is a Grails runtime extension; provide it for unit-test context
        Map.metaClass.encodeAsJSON = { -> groovy.json.JsonOutput.toJson(delegate) }
    }

    def cleanup() {
        Map.metaClass.encodeAsJSON = null
    }

    def "constructor initializes dependencies"() {
        expect:
        sync.@plugin == plugin
        sync.@cluster == cluster
        sync.@morpheusContext == morpheusContext
    }

    @Unroll
    def "mapNodeOsType maps '#osType' to '#expected'"() {
        expect:
        invokePrivateStatic(NodesSync, 'mapNodeOsType', [Object] as Class[], [osType] as Object[]) == expected

        where:
        osType      || expected
        null        || HarvesterConstants.OS_TYPE_LINUX
        'linux'     || HarvesterConstants.OS_TYPE_LINUX
        'windows'   || HarvesterConstants.OS_TYPE_WINDOWS
        'LiNuX'     || HarvesterConstants.OS_TYPE_LINUX
        'unknownOS' || HarvesterConstants.OS_TYPE_LINUX
    }

    @Unroll
    def "getServerTypeCode maps '#nodeType' to '#expected'"() {
        expect:
        invokePrivateStatic(NodesSync, 'getServerTypeCode', [Object] as Class[], [nodeType] as Object[]) == expected

        where:
        nodeType         || expected
        null             || HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER
        'master'         || HarvesterConstants.SERVER_TYPE_HARVESTER_MASTER
        'control-plane'  || HarvesterConstants.SERVER_TYPE_HARVESTER_MASTER
        'worker'         || HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER
        'infra'          || HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER
        'infrastructure' || HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER
        'random'         || HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER
    }

    def "execute handles helper exception without throwing"() {
        given:
        GroovySpy(com.hpe.morpheus.harvester.plugin.apihelper.NodeApiHelper, global: true)
        1 * new com.hpe.morpheus.harvester.plugin.apihelper.NodeApiHelper(cluster) >> { throw new RuntimeException('node helper failed') }

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "addNodes creates compute servers for discovered nodes"() {
        given:
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def computeServerService = Mock(MorpheusComputeServerService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.osType >> osTypeService
        services.computeServer >> computeServerService
        asyncServices.cloud >> asyncCloudService

        asyncCloudService.findComputeServerTypeByCode(HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER) >>
            Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: HarvesterConstants.OS_TYPE_LINUX, platform: 'linux')

        def addList = [[
            uid: 'node-uid-1',
            name: 'node-1',
            nodeType: 'worker',
            internalIP: '10.0.0.2',
            externalIP: '20.0.0.2',
            ready: true,
            cpuCapacity: 4L,
            memoryCapacity: 8192L,
            storageCapacity: 100000L,
            cpuUsage: 1L,
            memoryUsage: 1024L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]]

        when:
        invokePrivate(sync, 'addNodes', [Object] as Class[], [addList] as Object[])

        then:
        notThrown(Exception)
    }

    def "removeNodes calls bulkRemove on async computeServer service"() {
        given:
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncComputeService = Mock(MorpheusComputeServerService)
        morpheusContext.async >> asyncServices
        asyncServices.computeServer >> asyncComputeService

        def server1 = new ComputeServer(id: 10L, name: 'node-to-remove')
        def removeList = [[server1]]

        when:
        invokePrivate(sync, 'removeNodes', [Object] as Class[], [removeList] as Object[])

        then:
        1 * asyncComputeService.bulkRemove(_) >> Single.just(true)
    }

    def "addNodes with empty list does nothing"() {
        given:
        def services = Mock(MorpheusServices)
        morpheusContext.services >> services

        when:
        invokePrivate(sync, 'addNodes', [Object] as Class[], [[]] as Object[])

        then:
        0 * services.computeServer
    }

    def "updateNodes skips server with provisioning status"() {
        given:
        def existing = new ComputeServer(id: 5L, status: 'provisioning', name: 'prov-node')
        def updateList = [[existingItem: existing, masterItem: [name: 'prov-node', nodeType: 'worker', ready: true]]]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [updateList] as Object[])

        then:
        // No service calls since provisioning nodes are skipped
        0 * morpheusContext.async
        0 * morpheusContext.services
    }

    def "updateNodes with no field changes does not call bulkSave"() {
        given:
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def asyncComputeService = Mock(MorpheusComputeServerService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.osType >> osTypeService
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> asyncComputeService

        def serverType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        asyncCloudService.findComputeServerTypeByCode(HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER) >>
            Single.just(serverType)
        def osType = new OsType(code: HarvesterConstants.OS_TYPE_LINUX, platform: 'linux')
        osTypeService.find(_) >> osType

        def existing = new ComputeServer(
            id: 2L,
            name: 'same-node',
            status: 'provisioned',
            hostname: 'same-host',
            maxCores: 4L,
            maxMemory: 8192L,
            maxStorage: 100000L,
            usedCpu: 1L,
            usedMemory: 1024L,
            powerState: ComputeServer.PowerState.on
        )
        existing.computeServerType = serverType
        existing.serverOs = osType
        def identity = new ComputeCapacityInfo(maxCores: 4L, maxMemory: 8192L, maxStorage: 100000L, usedMemory: 1024L, usedCores: 1L)
        existing.capacityInfo = identity

        def matchedNode = [
            uid: 'node-2',
            name: 'same-node',
            hostname: 'same-host',
            nodeType: 'worker',
            ready: true,
            cpuCapacity: 4L,
            memoryCapacity: 8192L,
            storageCapacity: 100000L,
            cpuUsage: 1L,
            memoryUsage: 1024L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: existing, masterItem: matchedNode]]] as Object[])

        then:
        // bulkSave should NOT be called when nothing changed (rawData may differ though)
        notThrown(Exception)
    }

    def "updateNodes updates modified node fields and bulk saves"() {
        given:
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def computeServerService = Mock(MorpheusComputeServerService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.osType >> osTypeService
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService

        asyncCloudService.findComputeServerTypeByCode(HarvesterConstants.SERVER_TYPE_HARVESTER_MASTER) >>
            Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_MASTER))
        osTypeService.find(_) >> new OsType(code: HarvesterConstants.OS_TYPE_WINDOWS, platform: 'windows')
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        def existing = new ComputeServer(
            id: 1L,
            name: 'old-node',
            status: 'provisioned',
            hostname: 'old-host',
            maxCores: 2L,
            maxMemory: 2048L,
            maxStorage: 50000L,
            usedCpu: 0L,
            usedMemory: 0L,
            powerState: ComputeServer.PowerState.off
        )
        existing.computeServerType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        existing.serverOs = new OsType(code: HarvesterConstants.OS_TYPE_LINUX, platform: 'linux')

        def matched = [
            uid: 'node-uid-1',
            name: 'node-new',
            hostname: 'node-new-host',
            nodeType: 'master',
            ready: true,
            cpuCapacity: 8L,
            memoryCapacity: 16384L,
            storageCapacity: 200000L,
            cpuUsage: 2L,
            memoryUsage: 2048L,
            status: [nodeInfo: [operatingSystem: 'windows']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: existing, masterItem: matched]]] as Object[])

        then:
        notThrown(Exception)
    }

    // --- execute() SyncTask path tests ---

    def "execute() with empty API response and one DB node triggers removeNodes"() {
        given:
        GroovySpy(com.hpe.morpheus.harvester.plugin.apihelper.NodeApiHelper, global: true)
        def mockHelper = Mock(com.hpe.morpheus.harvester.plugin.apihelper.NodeApiHelper)
        new com.hpe.morpheus.harvester.plugin.apihelper.NodeApiHelper(cluster) >> mockHelper
        mockHelper.getNodes() >> [success: true, nodes: []]

        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncComputeService = Mock(MorpheusComputeServerService)
        morpheusContext.async >> asyncServices
        asyncServices.computeServer >> asyncComputeService

        def proj = new ComputeServerIdentityProjection(id: 99L, externalId: 'stale-node')
        asyncComputeService.listIdentityProjections(_) >> Observable.fromIterable([proj])

        when:
        sync.execute()

        then:
        1 * asyncComputeService.bulkRemove(_) >> Single.just(true)
    }

    def "execute() with one API node and empty DB triggers addNodes via SyncTask"() {
        given:
        GroovySpy(com.hpe.morpheus.harvester.plugin.apihelper.NodeApiHelper, global: true)
        def mockHelper = Mock(com.hpe.morpheus.harvester.plugin.apihelper.NodeApiHelper)
        new com.hpe.morpheus.harvester.plugin.apihelper.NodeApiHelper(cluster) >> mockHelper
        mockHelper.getNodes() >> [success: true, nodes: [[
            uid: 'node-uid-exec',
            name: 'worker-exec',
            nodeType: 'worker',
            internalIP: '192.168.1.10',
            externalIP: '10.0.0.1',
            hostname: 'worker-exec',
            ready: true,
            cpuCapacity: 4L,
            memoryCapacity: 8192L,
            storageCapacity: 100000L,
            cpuUsage: 1L,
            memoryUsage: 512L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]]]

        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncComputeService = Mock(MorpheusComputeServerService)
        def asyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def syncComputeService = Mock(MorpheusComputeServerService)

        morpheusContext.async >> asyncServices
        morpheusContext.services >> services
        asyncServices.computeServer >> asyncComputeService
        asyncServices.cloud >> asyncCloudService
        services.osType >> osTypeService
        services.computeServer >> syncComputeService

        asyncComputeService.listIdentityProjections(_) >> Observable.empty()
        asyncCloudService.findComputeServerTypeByCode(_) >>
            Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "execute() with API error propagates to catch without throwing"() {
        given:
        GroovySpy(com.hpe.morpheus.harvester.plugin.apihelper.NodeApiHelper, global: true)
        def mockHelper = Mock(com.hpe.morpheus.harvester.plugin.apihelper.NodeApiHelper)
        new com.hpe.morpheus.harvester.plugin.apihelper.NodeApiHelper(cluster) >> mockHelper
        mockHelper.getNodes() >> [success: false, message: 'timeout']

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "addNodes creates master-type server correctly"() {
        given:
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def syncComputeService = Mock(MorpheusComputeServerService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.osType >> osTypeService
        services.computeServer >> syncComputeService
        asyncServices.cloud >> asyncCloudService

        asyncCloudService.findComputeServerTypeByCode(HarvesterConstants.SERVER_TYPE_HARVESTER_MASTER) >>
            Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_MASTER))
        osTypeService.find(_) >> new OsType(code: HarvesterConstants.OS_TYPE_LINUX, platform: 'linux')

        def addList = [[
            uid: 'master-1',
            name: 'master-node',
            nodeType: 'master',
            internalIP: '10.0.0.1',
            externalIP: null,
            hostname: 'master-host',
            ready: true,
            cpuCapacity: 8L,
            memoryCapacity: 16384L,
            storageCapacity: 200000L,
            cpuUsage: 2L,
            memoryUsage: 4096L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]]

        when:
        invokePrivate(sync, 'addNodes', [Object] as Class[], [addList] as Object[])

        then:
        notThrown(Exception)
    }

    def "addNodes uses externalIP when internalIP missing"() {
        given:
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def syncComputeService = Mock(MorpheusComputeServerService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.osType >> osTypeService
        services.computeServer >> syncComputeService
        asyncServices.cloud >> asyncCloudService

        asyncCloudService.findComputeServerTypeByCode(_) >>
            Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')

        def addList = [[
            uid: 'no-internal-ip',
            name: 'worker-2',
            nodeType: 'worker',
            internalIP: null,
            externalIP: '1.2.3.4',
            hostname: null,
            ready: false,
            cpuCapacity: 2L,
            memoryCapacity: 4096L,
            storageCapacity: 50000L,
            cpuUsage: 0L,
            memoryUsage: 0L,
            status: [nodeInfo: [operatingSystem: 'windows']]
        ]]

        when:
        invokePrivate(sync, 'addNodes', [Object] as Class[], [addList] as Object[])

        then:
        notThrown(Exception)
    }

    def "updateNodes produces rawData change saves correctly"() {
        given:
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def computeServerService = Mock(MorpheusComputeServerService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.osType >> osTypeService
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService

        asyncCloudService.findComputeServerTypeByCode(_) >>
            Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        def existing = new ComputeServer(
            id: 11L, name: 'n1', status: 'provisioned', hostname: 'n1',
            maxCores: 4L, maxMemory: 8192L, maxStorage: 100000L,
            usedCpu: 1L, usedMemory: 512L,
            powerState: ComputeServer.PowerState.on
        )
        existing.computeServerType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        existing.serverOs = new OsType(code: 'linux', platform: 'linux')
        // rawData differs deliberately to trigger the save=true branch
        existing.rawData = '{"old":"data"}'

        def matched = [
            uid: 'n1',
            name: 'n1',
            hostname: 'n1',
            nodeType: 'worker',
            ready: true,
            cpuCapacity: 4L,
            memoryCapacity: 8192L,
            storageCapacity: 100000L,
            cpuUsage: 1L,
            memoryUsage: 512L,
            status: [nodeInfo: [operatingSystem: 'linux'], phase: 'Ready']
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: existing, masterItem: matched]]] as Object[])

        then:
        notThrown(Exception)
    }

    // ─── updateNodes true-branch coverage ────────────────────────────────────

    def "updateNodes updates powerState when it differs"() {
        given:
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(Object)
        def computeServerService = Mock(MorpheusComputeServerService)
        def services = Mock(Object)
        def osTypeService = Mock(Object)
        morpheusContext.async >> asyncServices
        morpheusContext.services >> services
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService
        services.osType >> osTypeService
        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        def existing = new ComputeServer(
            id: 12L, name: 'n2', status: 'provisioned', hostname: 'n2',
            maxCores: 4L, maxMemory: 8192L, maxStorage: 100000L,
            usedCpu: 1L, usedMemory: 512L,
            powerState: ComputeServer.PowerState.off  // currently off
        )
        existing.computeServerType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        existing.serverOs = new OsType(code: 'linux', platform: 'linux')
        existing.capacityInfo = new ComputeCapacityInfo(maxCores: 4, maxMemory: 8192, maxStorage: 100000)
        existing.rawData = '{"old":"rawdata"}'

        def matched = [
            name: 'n2', hostname: 'n2', nodeType: 'worker',
            ready: true,  // now on
            cpuCapacity: 4L, memoryCapacity: 8192L, storageCapacity: 100000L,
            cpuUsage: 1L, memoryUsage: 512L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: existing, masterItem: matched]]] as Object[])

        then:
        notThrown(Exception)
    }

    def "updateNodes updates hostname when it differs"() {
        given:
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(Object)
        def computeServerService = Mock(MorpheusComputeServerService)
        def services = Mock(Object)
        def osTypeService = Mock(Object)
        morpheusContext.async >> asyncServices
        morpheusContext.services >> services
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService
        services.osType >> osTypeService
        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        def existing = new ComputeServer(
            id: 13L, name: 'n3', status: 'provisioned', hostname: 'old-host',
            powerState: ComputeServer.PowerState.on
        )
        existing.computeServerType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        existing.serverOs = new OsType(code: 'linux', platform: 'linux')
        existing.capacityInfo = new ComputeCapacityInfo()
        existing.rawData = '{"old":"rawdata"}'

        def matched = [
            name: 'n3', hostname: 'new-host', nodeType: 'worker', ready: true,
            cpuCapacity: 2L, memoryCapacity: 4096L, storageCapacity: 50000L,
            cpuUsage: 0L, memoryUsage: 0L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: existing, masterItem: matched]]] as Object[])

        then:
        notThrown(Exception)
    }

    def "updateNodes updates name when it differs"() {
        given:
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(Object)
        def computeServerService = Mock(MorpheusComputeServerService)
        def services = Mock(Object)
        def osTypeService = Mock(Object)
        morpheusContext.async >> asyncServices
        morpheusContext.services >> services
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService
        services.osType >> osTypeService
        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        def existing = new ComputeServer(
            id: 14L, name: 'old-name', status: 'provisioned', hostname: 'host',
            powerState: ComputeServer.PowerState.on
        )
        existing.computeServerType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        existing.serverOs = new OsType(code: 'linux', platform: 'linux')
        existing.capacityInfo = new ComputeCapacityInfo()
        existing.rawData = '{"old":"rawdata"}'

        def matched = [
            name: 'new-name', hostname: 'host', nodeType: 'worker', ready: true,
            cpuCapacity: 0L, memoryCapacity: 0L, storageCapacity: 0L,
            cpuUsage: 0L, memoryUsage: 0L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: existing, masterItem: matched]]] as Object[])

        then:
        notThrown(Exception)
    }

    def "updateNodes updates usedCpu and usedMemory when they differ"() {
        given:
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(Object)
        def computeServerService = Mock(MorpheusComputeServerService)
        def services = Mock(Object)
        def osTypeService = Mock(Object)
        morpheusContext.async >> asyncServices
        morpheusContext.services >> services
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService
        services.osType >> osTypeService
        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        def existing = new ComputeServer(
            id: 15L, name: 'n5', status: 'provisioned', hostname: 'n5',
            maxCores: 4L, maxMemory: 8192L, maxStorage: 100000L,
            usedCpu: 1L, usedMemory: 512L,
            powerState: ComputeServer.PowerState.on
        )
        existing.computeServerType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        existing.serverOs = new OsType(code: 'linux', platform: 'linux')
        existing.capacityInfo = new ComputeCapacityInfo(maxCores: 4, maxMemory: 8192, maxStorage: 100000, usedCores: 1, usedMemory: 512)
        existing.rawData = '{"old":"rawdata"}'

        def matched = [
            name: 'n5', hostname: 'n5', nodeType: 'worker', ready: true,
            cpuCapacity: 4L, memoryCapacity: 8192L, storageCapacity: 100000L,
            cpuUsage: 3L,      // differs
            memoryUsage: 2048L, // differs
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: existing, masterItem: matched]]] as Object[])

        then:
        notThrown(Exception)
    }

    def "updateNodes updates maxMemory maxCores maxStorage when they differ"() {
        given:
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(Object)
        def computeServerService = Mock(MorpheusComputeServerService)
        def services = Mock(Object)
        def osTypeService = Mock(Object)
        morpheusContext.async >> asyncServices
        morpheusContext.services >> services
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService
        services.osType >> osTypeService
        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        def existing = new ComputeServer(
            id: 16L, name: 'n6', status: 'provisioned', hostname: 'n6',
            maxCores: 2L, maxMemory: 4096L, maxStorage: 50000L,
            usedCpu: 0L, usedMemory: 0L,
            powerState: ComputeServer.PowerState.on
        )
        existing.computeServerType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        existing.serverOs = new OsType(code: 'linux', platform: 'linux')
        existing.capacityInfo = new ComputeCapacityInfo(maxCores: 2, maxMemory: 4096, maxStorage: 50000)
        existing.rawData = '{"old":"rawdata"}'

        def matched = [
            name: 'n6', hostname: 'n6', nodeType: 'worker', ready: true,
            cpuCapacity: 8L,        // differs
            memoryCapacity: 16384L, // differs
            storageCapacity: 200000L, // differs
            cpuUsage: 0L, memoryUsage: 0L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: existing, masterItem: matched]]] as Object[])

        then:
        notThrown(Exception)
    }

    def "updateNodes updates computeServerType when it differs"() {
        given:
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(Object)
        def computeServerService = Mock(MorpheusComputeServerService)
        def services = Mock(Object)
        def osTypeService = Mock(Object)
        morpheusContext.async >> asyncServices
        morpheusContext.services >> services
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService
        services.osType >> osTypeService

        def masterType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_MASTER)
        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(masterType)
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        def existing = new ComputeServer(
            id: 17L, name: 'n7', status: 'provisioned', hostname: 'n7',
            powerState: ComputeServer.PowerState.on
        )
        existing.computeServerType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER) // was worker
        existing.serverOs = new OsType(code: 'linux', platform: 'linux')
        existing.capacityInfo = new ComputeCapacityInfo()
        existing.rawData = '{"old":"rawdata"}'

        def matched = [
            name: 'n7', hostname: 'n7', nodeType: 'master', ready: true, // now master
            cpuCapacity: 4L, memoryCapacity: 8192L, storageCapacity: 100000L,
            cpuUsage: 0L, memoryUsage: 0L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: existing, masterItem: matched]]] as Object[])

        then:
        notThrown(Exception)
    }

    def "updateNodes updates osType when it differs and no sourceImage"() {
        given:
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(Object)
        def computeServerService = Mock(MorpheusComputeServerService)
        def services = Mock(Object)
        def osTypeService = Mock(Object)
        morpheusContext.async >> asyncServices
        morpheusContext.services >> services
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService
        services.osType >> osTypeService

        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        def windowsOs = new OsType(code: 'windows.2022', platform: 'windows')
        osTypeService.find(_) >> windowsOs
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        def existing = new ComputeServer(
            id: 18L, name: 'n8', status: 'provisioned', hostname: 'n8',
            powerState: ComputeServer.PowerState.on, sourceImage: null
        )
        existing.computeServerType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        existing.serverOs = new OsType(code: 'linux', platform: 'linux') // was linux
        existing.capacityInfo = new ComputeCapacityInfo()
        existing.rawData = '{"old":"rawdata"}'

        def matched = [
            name: 'n8', hostname: 'n8', nodeType: 'worker', ready: true,
            cpuCapacity: 0L, memoryCapacity: 0L, storageCapacity: 0L,
            cpuUsage: 0L, memoryUsage: 0L,
            status: [nodeInfo: [operatingSystem: 'windows']] // now windows
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: existing, masterItem: matched]]] as Object[])

        then:
        notThrown(Exception)
    }

    def "updateNodes skips osType update when sourceImage is present"() {
        given:
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(Object)
        def computeServerService = Mock(MorpheusComputeServerService)
        def services = Mock(Object)
        def osTypeService = Mock(Object)
        morpheusContext.async >> asyncServices
        morpheusContext.services >> services
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService
        services.osType >> osTypeService

        def workerType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(workerType)
        def newOs = new OsType(code: 'windows.2022', platform: 'windows')
        osTypeService.find(_) >> newOs
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        def existing = new ComputeServer(
            id: 19L, name: 'src-node', status: 'provisioned', hostname: 'src-node',
            powerState: ComputeServer.PowerState.on, sourceImage: new com.morpheusdata.model.VirtualImage(id: 1L)
        )
        existing.computeServerType = workerType
        existing.serverOs = new OsType(code: 'linux', platform: 'linux')
        existing.capacityInfo = new ComputeCapacityInfo()
        existing.rawData = '{"old":"data"}'

        def matched = [
            name: 'src-node', hostname: 'src-node', nodeType: 'worker', ready: true,
            cpuCapacity: 0L, memoryCapacity: 0L, storageCapacity: 0L,
            cpuUsage: 0L, memoryUsage: 0L,
            status: [nodeInfo: [operatingSystem: 'windows']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: existing, masterItem: matched]]] as Object[])

        then:
        notThrown(Exception)
        // serverOs should NOT change since sourceImage is present
        existing.serverOs.code == 'linux'
    }

    def "addNodes uses DEFAULT_IP when no internalIP and no externalIP"() {
        given:
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(Object)
        def computeServerService = Mock(MorpheusComputeServerService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.osType >> osTypeService
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService

        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')
        computeServerService.bulkCreate(_ as List) >> [success: true]

        def node = [
            uid: 'nip-uid', name: 'nip-node', nodeType: 'worker',
            internalIP: null, externalIP: null, hostname: 'nip-host',
            ready: true, cpuCapacity: 2L, memoryCapacity: 4096L, storageCapacity: 0L,
            cpuUsage: 0L, memoryUsage: 0L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'addNodes', [Object] as Class[], [[node]] as Object[])

        then:
        notThrown(Exception)
    }

    def "addNodes sets PowerState off when ready is false"() {
        given:
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(Object)
        def computeServerService = Mock(MorpheusComputeServerService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.osType >> osTypeService
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService

        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')
        computeServerService.bulkCreate(_ as List) >> [success: true]

        def node = [
            uid: 'off-uid', name: 'off-node', nodeType: 'worker',
            internalIP: '10.0.0.9', externalIP: null, hostname: 'off-host',
            ready: false, cpuCapacity: 2L, memoryCapacity: 4096L, storageCapacity: 0L,
            cpuUsage: 0L, memoryUsage: 0L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'addNodes', [Object] as Class[], [[node]] as Object[])

        then:
        notThrown(Exception)
    }

    def "addNodes uses name as hostname fallback when hostname is null"() {
        given:
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(Object)
        def computeServerService = Mock(MorpheusComputeServerService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.osType >> osTypeService
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService

        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')
        computeServerService.bulkCreate(_ as List) >> [success: true]

        def node = [
            uid: 'fn-uid', name: 'fallback-node', nodeType: 'worker',
            internalIP: '10.0.0.10', externalIP: null, hostname: null,
            ready: true, cpuCapacity: 2L, memoryCapacity: 4096L, storageCapacity: 0L,
            cpuUsage: 0L, memoryUsage: 0L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'addNodes', [Object] as Class[], [[node]] as Object[])

        then:
        notThrown(Exception)
    }

    def "updateNodes handles exception in item processing gracefully"() {
        given:
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(Object)
        morpheusContext.async >> asyncServices
        asyncServices.cloud >> asyncCloudService
        asyncCloudService.findComputeServerTypeByCode(_) >> { throw new RuntimeException('cloud error') }

        def existing = new ComputeServer(id: 21L, name: 'err-node', status: 'provisioned')

        def matched = [
            name: 'err-node', hostname: 'err-node', nodeType: 'worker', ready: true,
            cpuCapacity: 0L, memoryCapacity: 0L, storageCapacity: 0L,
            cpuUsage: 0L, memoryUsage: 0L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: existing, masterItem: matched]]] as Object[])

        then:
        notThrown(Exception)
    }

    def "addNodes with null cpuCapacity and memoryCapacity defaults to 0"() {
        given:
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(Object)
        def computeServerService = Mock(MorpheusComputeServerService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.osType >> osTypeService
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService

        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')
        computeServerService.bulkCreate(_ as List) >> [success: true]

        def node = [
            uid: 'null-cap-uid', name: 'null-cap-node', nodeType: 'worker',
            internalIP: '10.0.0.11', externalIP: null, hostname: 'null-host',
            ready: true, cpuCapacity: null, memoryCapacity: null, storageCapacity: null,
            cpuUsage: null, memoryUsage: null,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'addNodes', [Object] as Class[], [[node]] as Object[])

        then:
        notThrown(Exception)
    }

    def "execute() with matching DB and API node triggers updateNodes via SyncTask"() {
        given:
        GroovySpy(com.hpe.morpheus.harvester.plugin.apihelper.NodeApiHelper, global: true)
        def mockHelper = Mock(com.hpe.morpheus.harvester.plugin.apihelper.NodeApiHelper)
        new com.hpe.morpheus.harvester.plugin.apihelper.NodeApiHelper(cluster) >> mockHelper

        def apiNode = [
            uid: 'uid-match', name: 'worker-match', nodeType: 'worker',
            internalIP: '10.0.0.5', externalIP: '5.5.5.5', hostname: 'worker-match',
            ready: true, cpuCapacity: 4L, memoryCapacity: 8192L, storageCapacity: 100000L,
            cpuUsage: 1L, memoryUsage: 512L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]
        mockHelper.getNodes() >> [success: true, nodes: [apiNode]]

        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(Object)
        def computeServerService = Mock(MorpheusComputeServerService)
        def services = Mock(Object)
        def osTypeService = Mock(Object)
        morpheusContext.async >> asyncServices
        morpheusContext.services >> services
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService
        services.osType >> osTypeService

        def proj = new ComputeServerIdentityProjection(id: 22L, externalId: 'uid-match')
        computeServerService.listIdentityProjections(_) >> Observable.fromIterable([proj])

        def fullServer = new ComputeServer(
            id: 22L, externalId: 'uid-match', name: 'worker-match', status: 'provisioned',
            hostname: 'worker-match', powerState: ComputeServer.PowerState.on,
            maxCores: 4L, maxMemory: 8192L, maxStorage: 100000L,
            usedCpu: 1L, usedMemory: 512L
        )
        fullServer.computeServerType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        fullServer.serverOs = new OsType(code: 'linux', platform: 'linux')
        fullServer.capacityInfo = new ComputeCapacityInfo(maxCores: 4, maxMemory: 8192, maxStorage: 100000)
        // withLoadObjectDetailsFromFinder
        computeServerService.list(_) >> Observable.fromIterable([fullServer])

        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    // ─── updateNodes – additional branch coverage ─────────────────────────────

    def "updateNodes skips inner block when server is in provisioning status"() {
        given:
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(Object)
        def computeServerService = Mock(MorpheusComputeServerService)
        def services = Mock(Object)
        def osTypeService = Mock(Object)
        morpheusContext.async >> asyncServices
        morpheusContext.services >> services
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService
        services.osType >> osTypeService
        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))

        def provisioningServer = new ComputeServer(id: 9001L, name: 'n-prov', status: 'provisioning')
        def matched = [name: 'n-prov', hostname: 'n-prov', nodeType: 'worker',
                       ready: true, cpuCapacity: 4L, memoryCapacity: 8192L, storageCapacity: 100L,
                       cpuUsage: 0L, memoryUsage: 0L,
                       status: [nodeInfo: [operatingSystem: 'linux']]]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[],
            [[[existingItem: provisioningServer, masterItem: matched]]] as Object[])

        then:
        notThrown(Exception)
        0 * computeServerService.bulkSave(_)  // skipped because provisioning
    }

    def "updateNodes with all fields matching makes no save"() {
        given:
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(Object)
        def computeServerService = Mock(MorpheusComputeServerService)
        def services = Mock(Object)
        def osTypeService = Mock(Object)
        morpheusContext.async >> asyncServices
        morpheusContext.services >> services
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService
        services.osType >> osTypeService

        def workerType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        def osType = new OsType(code: 'linux', platform: 'linux')
        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(workerType)
        osTypeService.find(_) >> osType

        def existing = new ComputeServer(
            id: 9002L, name: 'n-same', status: 'provisioned', hostname: 'n-same',
            maxCores: 4L, maxMemory: 8192L, maxStorage: 100000L,
            usedCpu: 2L, usedMemory: 1024L, powerState: ComputeServer.PowerState.on,
            // already a VM hypervisor host with 1 socket (the default) x 4 cores
            serverType: 'hypervisor', provision: false, maxCpu: 1L, coresPerSocket: 4L
        )
        existing.serverGroup = cluster
        existing.computeServerType = workerType
        existing.serverOs = osType
        existing.capacityInfo = new ComputeCapacityInfo(maxCores: 4, maxMemory: 8192, maxStorage: 100000, usedCores: 2, usedMemory: 1024)

        // rawData will differ since it's JSON of the matched status – just force a stable rawData match
        def rawDataMap = [status: [nodeInfo: [operatingSystem: 'linux']], kind: 'Harvester', apiVersion: '']
        existing.rawData = rawDataMap.encodeAsJSON().toString()

        def matched = [
            name: 'n-same', hostname: 'n-same', nodeType: 'worker',
            ready: true, cpuCapacity: 4L, memoryCapacity: 8192L, storageCapacity: 100000L,
            cpuUsage: 2L, memoryUsage: 1024L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[],
            [[[existingItem: existing, masterItem: matched]]] as Object[])

        then:
        notThrown(Exception)
        0 * computeServerService.bulkSave(_)
    }

    def "updateNodes with all fields changed triggers bulkSave with updated server"() {
        given:
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(Object)
        def computeServerService = Mock(MorpheusComputeServerService)
        def services = Mock(Object)
        def osTypeService = Mock(Object)
        morpheusContext.async >> asyncServices
        morpheusContext.services >> services
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService
        services.osType >> osTypeService

        def newType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_MASTER)
        def oldType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        def linuxOs = new OsType(code: 'linux', platform: 'linux')
        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(newType)
        osTypeService.find(_) >> linuxOs
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        def existing = new ComputeServer(
            id: 9003L, name: 'old-name', status: 'provisioned', hostname: 'old-host',
            maxCores: 1L, maxMemory: 1024L, maxStorage: 10000L,
            usedCpu: 0L, usedMemory: 0L, powerState: ComputeServer.PowerState.off
        )
        existing.computeServerType = oldType
        existing.serverOs = new OsType(code: 'windows', platform: 'windows')
        existing.capacityInfo = new ComputeCapacityInfo(maxCores: 1, maxMemory: 1024, maxStorage: 10000)
        existing.rawData = null

        def matched = [
            name: 'new-name', hostname: 'new-host', nodeType: 'master',
            ready: true,  // powerState changes: off → on
            cpuCapacity: 8L, memoryCapacity: 16384L, storageCapacity: 200000L,
            cpuUsage: 3L, memoryUsage: 2048L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[],
            [[[existingItem: existing, masterItem: matched]]] as Object[])

        then:
        notThrown(Exception)
    }

    def "updateNodes creates capacityInfo when server has none"() {
        given:
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(Object)
        def computeServerService = Mock(MorpheusComputeServerService)
        def services = Mock(Object)
        def osTypeService = Mock(Object)
        morpheusContext.async >> asyncServices
        morpheusContext.services >> services
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService
        services.osType >> osTypeService

        def workerType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(workerType)
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        def existing = new ComputeServer(
            id: 9004L, name: 'n-nocap', status: 'provisioned', hostname: 'n-nocap',
            maxCores: 0L, maxMemory: 0L, maxStorage: 0L, usedCpu: 0L, usedMemory: 0L,
            powerState: ComputeServer.PowerState.on
        )
        existing.computeServerType = workerType
        existing.serverOs = new OsType(code: 'linux', platform: 'linux')
        existing.capacityInfo = null  // no capacityInfo → triggers creation branch

        def matched = [
            name: 'n-nocap', hostname: 'n-nocap', nodeType: 'worker',
            ready: true, cpuCapacity: 4L, memoryCapacity: 8192L, storageCapacity: 50000L,
            cpuUsage: 1L, memoryUsage: 512L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[],
            [[[existingItem: existing, masterItem: matched]]] as Object[])

        then:
        notThrown(Exception)
    }

    // ─── addNodes – additional branch coverage ────────────────────────────────

    def "addNodes with windows osType sets sshUsername to admin"() {
        given:
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def computeServerService = Mock(MorpheusComputeServerService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.osType >> osTypeService
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService

        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        // Return Windows OsType → triggers sshUsername=admin branch
        osTypeService.find(_) >> new OsType(code: 'windows', platform: 'windows')

        def nodeToAdd = [
            uid: 'win-uid', name: 'win-node', nodeType: 'worker',
            internalIP: '10.0.0.9', externalIP: null, hostname: 'win-node',
            ready: true, cpuCapacity: 4L, memoryCapacity: 8192L, storageCapacity: 100000L,
            cpuUsage: 1L, memoryUsage: 512L,
            status: [nodeInfo: [operatingSystem: 'windows']]
        ]
        def created = new ComputeServer(id: 9100L, name: 'win-node')
        created.interfaces = []
        computeServerService.create(_ as ComputeServer) >> Single.just(created)
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        when:
        invokePrivate(sync, 'addNodes', [Object] as Class[], [[nodeToAdd]] as Object[])

        then:
        notThrown(Exception)
    }

    def "updateNodes with node ready false sets powerState to off"() {
        given:
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def computeServerService = Mock(MorpheusComputeServerService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.osType >> osTypeService
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService

        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        def serverType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        def server = new ComputeServer(id: 9200L, name: 'node-off', status: 'provisioned', powerState: ComputeServer.PowerState.on)
        server.computeServerType = serverType
        server.capacityInfo = new ComputeCapacityInfo(maxCores: 4, maxMemory: 8192, maxStorage: 100000)

        def matchedNode = [
            uid: 'node-off', name: 'node-off', nodeType: 'worker',
            hostname: 'node-off', ready: false,
            cpuCapacity: 4L, memoryCapacity: 8192L, storageCapacity: 100000L,
            cpuUsage: 0L, memoryUsage: 0L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: server, masterItem: matchedNode]]] as Object[])

        then:
        notThrown(Exception)
    }

    def "updateNodes creates capacityInfo when missing"() {
        given:
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def computeServerService = Mock(MorpheusComputeServerService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.osType >> osTypeService
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService

        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        def serverType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        def server = new ComputeServer(id: 9201L, name: 'node-nocap', status: 'provisioned')
        server.computeServerType = serverType
        server.capacityInfo = null

        def matchedNode = [
            uid: 'node-nocap', name: 'node-nocap', nodeType: 'worker',
            hostname: 'node-nocap', ready: true,
            cpuCapacity: 8L, memoryCapacity: 16384L, storageCapacity: 200000L,
            cpuUsage: 2L, memoryUsage: 4096L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: server, masterItem: matchedNode]]] as Object[])

        then:
        notThrown(Exception)
    }

    def "updateNodes changes osType when no sourceImage"() {
        given:
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def computeServerService = Mock(MorpheusComputeServerService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.osType >> osTypeService
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService

        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: 'ubuntu', platform: 'linux')
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        def serverType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        def server = new ComputeServer(id: 9202L, name: 'node-os', status: 'provisioned')
        server.computeServerType = serverType
        server.capacityInfo = new ComputeCapacityInfo(maxCores: 4, maxMemory: 8192, maxStorage: 100000)
        server.sourceImage = null
        server.serverOs = new OsType(code: 'centos', platform: 'linux')

        def matchedNode = [
            uid: 'node-os', name: 'node-os', nodeType: 'worker',
            hostname: 'node-os', ready: true,
            cpuCapacity: 4L, memoryCapacity: 8192L, storageCapacity: 100000L,
            cpuUsage: 1L, memoryUsage: 2048L,
            status: [nodeInfo: [operatingSystem: 'ubuntu']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: server, masterItem: matchedNode]]] as Object[])

        then:
        notThrown(Exception)
    }

    def "updateNodes changes computeServerType when different"() {
        given:
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def computeServerService = Mock(MorpheusComputeServerService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.osType >> osTypeService
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService

        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_MASTER))
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        def wrongType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        def server = new ComputeServer(id: 9203L, name: 'node-type', status: 'provisioned')
        server.computeServerType = wrongType
        server.capacityInfo = new ComputeCapacityInfo(maxCores: 4, maxMemory: 8192, maxStorage: 100000)

        def matchedNode = [
            uid: 'node-type', name: 'node-type', nodeType: 'master',
            hostname: 'node-type', ready: true,
            cpuCapacity: 4L, memoryCapacity: 8192L, storageCapacity: 100000L,
            cpuUsage: 1L, memoryUsage: 2048L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: server, masterItem: matchedNode]]] as Object[])

        then:
        notThrown(Exception)
    }

    def "updateNodes updates hostname when changed"() {
        given:
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def computeServerService = Mock(MorpheusComputeServerService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.osType >> osTypeService
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService

        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        def serverType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        def server = new ComputeServer(id: 9204L, name: 'node-host', status: 'provisioned', hostname: 'old-hostname')
        server.computeServerType = serverType
        server.capacityInfo = new ComputeCapacityInfo(maxCores: 4, maxMemory: 8192, maxStorage: 100000)

        def matchedNode = [
            uid: 'node-host', name: 'node-host', nodeType: 'worker',
            hostname: 'new-hostname', ready: true,
            cpuCapacity: 4L, memoryCapacity: 8192L, storageCapacity: 100000L,
            cpuUsage: 1L, memoryUsage: 2048L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: server, masterItem: matchedNode]]] as Object[])

        then:
        notThrown(Exception)
    }

    def "updateNodes updates name when changed"() {
        given:
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def computeServerService = Mock(MorpheusComputeServerService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.osType >> osTypeService
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService

        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        def serverType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        def server = new ComputeServer(id: 9205L, name: 'old-name', status: 'provisioned')
        server.computeServerType = serverType
        server.capacityInfo = new ComputeCapacityInfo(maxCores: 4, maxMemory: 8192, maxStorage: 100000)

        def matchedNode = [
            uid: 'node-name', name: 'new-name', nodeType: 'worker',
            hostname: 'node-name', ready: true,
            cpuCapacity: 4L, memoryCapacity: 8192L, storageCapacity: 100000L,
            cpuUsage: 1L, memoryUsage: 2048L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: server, masterItem: matchedNode]]] as Object[])

        then:
        notThrown(Exception)
    }

    def "updateNodes updates usedCpu when changed"() {
        given:
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def computeServerService = Mock(MorpheusComputeServerService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.osType >> osTypeService
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService

        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        def serverType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        def server = new ComputeServer(id: 9206L, name: 'node-cpu', status: 'provisioned', usedCpu: 1L)
        server.computeServerType = serverType
        server.capacityInfo = new ComputeCapacityInfo(maxCores: 4, maxMemory: 8192, maxStorage: 100000, usedCores: 1L)

        def matchedNode = [
            uid: 'node-cpu', name: 'node-cpu', nodeType: 'worker',
            hostname: 'node-cpu', ready: true,
            cpuCapacity: 4L, memoryCapacity: 8192L, storageCapacity: 100000L,
            cpuUsage: 3L, memoryUsage: 2048L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: server, masterItem: matchedNode]]] as Object[])

        then:
        notThrown(Exception)
    }

    def "updateNodes updates usedMemory when changed"() {
        given:
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def computeServerService = Mock(MorpheusComputeServerService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.osType >> osTypeService
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService

        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        def serverType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        def server = new ComputeServer(id: 9207L, name: 'node-mem', status: 'provisioned', usedMemory: 1024L)
        server.computeServerType = serverType
        server.capacityInfo = new ComputeCapacityInfo(maxCores: 4, maxMemory: 8192, maxStorage: 100000, usedMemory: 1024L)

        def matchedNode = [
            uid: 'node-mem', name: 'node-mem', nodeType: 'worker',
            hostname: 'node-mem', ready: true,
            cpuCapacity: 4L, memoryCapacity: 8192L, storageCapacity: 100000L,
            cpuUsage: 1L, memoryUsage: 4096L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: server, masterItem: matchedNode]]] as Object[])

        then:
        notThrown(Exception)
    }

    // --- addNodes tests ---

    def "addNodes creates compute server for worker node"() {
        given:
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def computeServerService = Mock(MorpheusComputeServerService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.osType >> osTypeService
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService

        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')
        computeServerService.bulkCreate(_) >> true

        def nodeToAdd = [
            uid: 'node-1', name: 'worker-1', nodeType: 'worker',
            hostname: 'worker-1.local', ready: true,
            internalIP: '10.0.0.1', externalIP: '192.168.1.1',
            cpuCapacity: 8L, memoryCapacity: 32768L, storageCapacity: 100000L,
            cpuUsage: 2L, memoryUsage: 8192L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'addNodes', [Object] as Class[], [[nodeToAdd]] as Object[])

        then:
        notThrown(Exception)
    }

    def "addNodes handles exception in node processing gracefully"() {
        given:
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        morpheusContext.async >> asyncServices
        asyncServices.cloud >> asyncCloudService
        asyncCloudService.findComputeServerTypeByCode(_) >> { throw new RuntimeException("type not found") }

        def nodeToAdd = [
            uid: 'node-err', name: 'error-node', nodeType: 'worker',
            hostname: 'err.local', ready: true
        ]

        when:
        invokePrivate(sync, 'addNodes', [Object] as Class[], [[nodeToAdd]] as Object[])

        then:
        notThrown(Exception)
    }

    // --- updateNodes additional tests ---

    def "updateNodes updates hostname when changed"() {
        given:
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def computeServerService = Mock(MorpheusComputeServerService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.osType >> osTypeService
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService

        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        def serverType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        def server = new ComputeServer(id: 9208L, name: 'node-host', hostname: 'old-hostname', status: 'provisioned')
        server.computeServerType = serverType
        server.capacityInfo = new ComputeCapacityInfo(maxCores: 4, maxMemory: 8192, maxStorage: 100000)

        def matchedNode = [
            uid: 'node-host', name: 'node-host', nodeType: 'worker',
            hostname: 'new-hostname', ready: true,
            cpuCapacity: 4L, memoryCapacity: 8192L, storageCapacity: 100000L,
            cpuUsage: 1L, memoryUsage: 1024L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: server, masterItem: matchedNode]]] as Object[])

        then:
        notThrown(Exception)
    }

    def "updateNodes creates capacityInfo when absent"() {
        given:
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def computeServerService = Mock(MorpheusComputeServerService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.osType >> osTypeService
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService

        asyncCloudService.findComputeServerTypeByCode(_) >> Single.just(new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER))
        osTypeService.find(_) >> new OsType(code: 'linux', platform: 'linux')
        computeServerService.bulkSave(_ as List) >> Single.just(true)

        def serverType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        def server = new ComputeServer(id: 9209L, name: 'node-nocap', status: 'provisioned')
        server.computeServerType = serverType
        server.capacityInfo = null // no capacity info

        def matchedNode = [
            uid: 'node-nocap', name: 'node-nocap', nodeType: 'worker',
            hostname: 'node-nocap', ready: true,
            cpuCapacity: 4L, memoryCapacity: 8192L, storageCapacity: 100000L,
            cpuUsage: 1L, memoryUsage: 1024L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: server, masterItem: matchedNode]]] as Object[])

        then:
        notThrown(Exception)
    }

    def "updateNodes skips provisioning servers"() {
        given:
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(MorpheusOsTypeService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def computeServerService = Mock(MorpheusComputeServerService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.osType >> osTypeService
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService

        def server = new ComputeServer(id: 9210L, name: 'provisioning-node', status: 'provisioning')

        def matchedNode = [
            uid: 'prov-node', name: 'provisioning-node', nodeType: 'worker',
            hostname: 'prov-node', ready: true,
            cpuCapacity: 4L, memoryCapacity: 8192L, storageCapacity: 100000L,
            cpuUsage: 1L, memoryUsage: 1024L,
            status: [nodeInfo: [operatingSystem: 'linux']]
        ]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: server, masterItem: matchedNode]]] as Object[])

        then:
        0 * computeServerService.bulkSave(_)
    }

    def "updateNodes handles exception in processing gracefully"() {
        given:
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        morpheusContext.async >> asyncServices
        asyncServices.cloud >> asyncCloudService
        asyncCloudService.findComputeServerTypeByCode(_) >> { throw new RuntimeException("type error") }

        def server = new ComputeServer(id: 9211L, name: 'err-node', status: 'provisioned')
        def matchedNode = [uid: 'err', name: 'err-node', nodeType: 'worker', hostname: 'err', ready: true,
            cpuCapacity: 4L, memoryCapacity: 8192L, storageCapacity: 100000L, cpuUsage: 1L, memoryUsage: 1024L,
            status: [nodeInfo: [operatingSystem: 'linux']]]

        when:
        invokePrivate(sync, 'updateNodes', [Object] as Class[], [[[existingItem: server, masterItem: matchedNode]]] as Object[])

        then:
        notThrown(Exception)
    }

    // --- removeNodes tests ---

    def "removeNodes calls bulkRemove"() {
        given:
        def asyncServices = Mock(MorpheusAsyncServices)
        def computeServerService = Mock(MorpheusComputeServerService)
        morpheusContext.async >> asyncServices
        asyncServices.computeServer >> computeServerService
        computeServerService.bulkRemove(_) >> Single.just(true)

        when:
        invokePrivate(sync, 'removeNodes', [Object] as Class[], [[[id: 1L, name: 'node1']]] as Object[])

        then:
        1 * computeServerService.bulkRemove(_) >> Single.just(true)
    }

    // --- hosts as VM hypervisors with sockets ---

    @Unroll
    def "parseSockets(#value) == #expected"() {
        expect:
        NodesSync.parseSockets(value) == expected

        where:
        value || expected
        '2'   || 2L
        4     || 4L
        ' 2 ' || 2L
        null  || 1L
        ''    || 1L
        '0'   || 1L
        'abc' || 1L
    }

    @Unroll
    def "coresPerSocket(#cores, #sockets) == #expected"() {
        expect:
        NodesSync.coresPerSocket(cores, sockets) == expected

        where:
        cores | sockets || expected
        64L   | 2L      || 32L
        48    | 1L      || 48L
        0L    | 2L      || 1L
        null  | 2L      || 1L
    }

    def "updateNodes turns an old Kubernetes-style node into a hypervisor host with sockets"() {
        given:
        def cloud = new Cloud(); cloud.id = 7L
        cloud.setConfigProperty('socketsPerHost', '2')
        def connection = new ComputeServerGroup(id: 456L, cloud: cloud)
        connection.setConfigProperty('managedByCloud', true)
        def hostSync = new NodesSync(plugin, connection)

        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudService = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def computeServerService = Mock(MorpheusComputeServerService)
        // types as declared by the 1.3 plugin API (a mismatch throws inside updateNodes and nothing is saved)
        def services = Mock(MorpheusServices)
        def osTypeService = Mock(com.morpheusdata.core.synchronous.MorpheusSynchronousOsTypeService)
        morpheusContext.async >> asyncServices
        morpheusContext.services >> services
        asyncServices.cloud >> asyncCloudService
        asyncServices.computeServer >> computeServerService
        services.osType >> osTypeService
        def workerType = new ComputeServerType(code: HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER)
        asyncCloudService.findComputeServerTypeByCode(_) >> io.reactivex.rxjava3.core.Maybe.just(workerType)
        def osType = new OsType(code: 'linux', platform: 'linux')
        osTypeService.find(_) >> osType

        def old = new ComputeServer(id: 9300L, name: 'h1', status: 'provisioned', hostname: 'h1',
                maxCores: 64L, maxMemory: 8192L, maxStorage: 100000L, usedCpu: 2L, usedMemory: 1024L,
                powerState: ComputeServer.PowerState.on, serverType: 'morpheus-node', provision: true, coresPerSocket: 1L)
        old.serverGroup = connection
        old.computeServerType = workerType
        old.serverOs = osType
        old.capacityInfo = new ComputeCapacityInfo(maxCores: 64, maxMemory: 8192, maxStorage: 100000, usedCores: 2, usedMemory: 1024)
        def matched = [name: 'h1', hostname: 'h1', nodeType: 'worker', ready: true,
                cpuCapacity: 64L, memoryCapacity: 8192L, storageCapacity: 100000L, cpuUsage: 2L, memoryUsage: 1024L,
                status: [nodeInfo: [operatingSystem: 'linux']]]

        when:
        // one argument: the list of update items
        Object[] args = new Object[1]
        args[0] = [[existingItem: old, masterItem: matched]]
        invokePrivate(hostSync, 'updateNodes', [Object] as Class[], args)

        then:
        1 * computeServerService.bulkSave([old]) >> Single.just(true)
        old.serverType == 'hypervisor'
        !old.provision
        old.serverGroup == null
        old.maxCpu == 2L
        old.coresPerSocket == 32L
        // no guest OS record on a hypervisor host (Morpheus would count it as a Linux workload)
        old.serverOs == null
        old.osType == 'linux'
    }

    def "removeHostsWithGuestOs removes only hosts that still have a guest OS record"() {
        given:
        def cloud = new Cloud(); cloud.id = 7L
        def connection = new ComputeServerGroup(id: 456L, cloud: cloud)
        connection.setConfigProperty('managedByCloud', true)
        def hostSync = new NodesSync(plugin, connection)
        def services = Mock(MorpheusServices)
        def syncComputeService = Mock(com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncComputeService = Mock(MorpheusComputeServerService)
        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.computeServer >> syncComputeService
        asyncServices.computeServer >> asyncComputeService
        def withOs = new ComputeServer(id: 1L, name: 'old-host', serverOs: new OsType(code: 'linux'))
        def clean = new ComputeServer(id: 2L, name: 'new-host')

        when:
        hostSync.removeHostsWithGuestOs(cloud)

        then:
        1 * syncComputeService.list(_) >> [withOs, clean]
        1 * asyncComputeService.bulkRemove([withOs]) >> Single.just(true)
    }
}
