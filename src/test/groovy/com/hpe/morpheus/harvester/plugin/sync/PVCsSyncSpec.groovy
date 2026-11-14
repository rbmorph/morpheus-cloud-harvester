package com.hpe.morpheus.harvester.plugin.sync

import com.hpe.morpheus.harvester.plugin.HpeHarvesterPlugin
import com.hpe.morpheus.harvester.plugin.apihelper.PVCApiHelper
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusStorageService
import com.morpheusdata.core.MorpheusStorageVolumeService
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.MorpheusSynchronousStorageService
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService
import com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeTypeService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService
import com.morpheusdata.model.projection.StorageVolumeIdentityProjection
import io.reactivex.rxjava3.core.Observable
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import spock.lang.Specification

class PVCsSyncSpec extends Specification {

    private def invokePrivate(Object target, String methodName, Class[] paramTypes, Object[] args) {
        def method = target.class.getDeclaredMethod(methodName, paramTypes)
        method.accessible = true
        method.invoke(target, args)
    }

    HpeHarvesterPlugin plugin
    MorpheusContext morpheusContext
    ComputeServerGroup cluster
    PVCsSync sync

    def setup() {
        plugin = Mock(HpeHarvesterPlugin)
        morpheusContext = Mock(MorpheusContext)
        cluster = Mock(ComputeServerGroup)

        def cloud = Mock(Cloud)
        cloud.id >> 22L
        cluster.id >> 200L
        cluster.name >> 'pvc-cluster'
        cluster.cloud >> cloud
        cluster.owner >> Mock(com.morpheusdata.model.Account)

        plugin.morpheusContext >> morpheusContext
        sync = new PVCsSync(plugin, cluster)
    }

    def "constructor initializes fields"() {
        expect:
        sync.@plugin == plugin
        sync.@cluster == cluster
        sync.@morpheusContext == morpheusContext
    }

    def "execute handles helper exception gracefully"() {
        given:
        GroovySpy(PVCApiHelper, global: true)
        1 * new PVCApiHelper(cluster) >> { throw new RuntimeException('pvc helper failed') }

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "addPVCs creates pvc volume and bulk saves"() {
        given:
        def services = Mock(MorpheusServices)
        def storageService = Mock(MorpheusSynchronousStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastore = Mock(Datastore)

        morpheusContext.services >> services
        services.storage >> storageService
        storageService.volume >> volumeService
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.find(_ as DataQuery) >> datastore

        def volType = new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)

        when:
        invokePrivate(sync, 'addPVCs', [Object, Object] as Class[], [[[
            uid: 'uid-1',
            name: 'pvc-a',
            namespace: 'ns1',
            storageClass: 'standard',
            sizeBytes: 512L,
            ownerReferences: [],
            dataSource: [:]
        ]], volType] as Object[])

        then:
        1 * volumeService.bulkCreate({ List list -> list.size() == 1 && list[0].name == 'pvc-a' && list[0].volumeType == HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC })
    }

    def "updatePVCs updates modified fields and bulk saves"() {
        given:
        def services = Mock(MorpheusServices)
        def storageService = Mock(MorpheusSynchronousStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        morpheusContext.services >> services
        services.storage >> storageService
        storageService.volume >> volumeService

        def local = new StorageVolume(name: 'old', maxStorage: 100L, poolName: 'oldns', config: '{}')
        def cloudItem = [name: 'new', sizeBytes: 200L, namespace: 'newns', storageClass: 'sc1', ownerReferences: [], dataSource: [:]]

        when:
        invokePrivate(sync, 'updatePVCs', [Object] as Class[], [[[existingItem: local, masterItem: cloudItem]]] as Object[])

        then:
        1 * volumeService.bulkSave({ List list -> list.size() == 1 && list[0].name == 'new' && list[0].poolName == 'newns' })
    }

    def "removePVCs invokes bulk remove"() {
        given:
        def services = Mock(MorpheusServices)
        def storageService = Mock(MorpheusSynchronousStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        morpheusContext.services >> services
        services.storage >> storageService
        storageService.volume >> volumeService

        when:
        invokePrivate(sync, 'removePVCs', [Object] as Class[], [[[new StorageVolume(name: 'pvc-x')]]] as Object[])

        then:
        1 * volumeService.bulkRemove(_)
    }

    def "addPVCs with empty list does nothing"() {
        given:
        def services = Mock(MorpheusServices)
        morpheusContext.services >> services

        when:
        invokePrivate(sync, 'addPVCs', [Object, Object] as Class[], [[], Mock(StorageVolumeType)] as Object[])

        then:
        0 * services.storage
    }

    def "updatePVCs with no changes does not call bulkSave"() {
        given:
        def services = Mock(MorpheusServices)
        def storageService = Mock(MorpheusSynchronousStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        morpheusContext.services >> services
        services.storage >> storageService
        storageService.volume >> volumeService

        // All fields match so doSave should remain false
        def pvcConfig = groovy.json.JsonOutput.toJson([ownerReferences: [], dataSource: [:]])
        def local = new StorageVolume(name: 'pvc-same', maxStorage: 512L, poolName: 'ns-same', config: pvcConfig)
        def cloudItem = [name: 'pvc-same', sizeBytes: 512L, namespace: 'ns-same', storageClass: 'sc1', ownerReferences: [], dataSource: [:]]

        when:
        invokePrivate(sync, 'updatePVCs', [Object] as Class[], [[[existingItem: local, masterItem: cloudItem]]] as Object[])

        then:
        0 * volumeService.bulkSave(_)
    }

    def "updatePVCs with empty list does nothing"() {
        given:
        def services = Mock(MorpheusServices)
        morpheusContext.services >> services

        when:
        invokePrivate(sync, 'updatePVCs', [Object] as Class[], [[]] as Object[])

        then:
        0 * services.storage
    }

    // --- execute() SyncTask path tests ---

    def "execute() removes stale PVC from DB when API returns empty"() {
        given:
        GroovySpy(com.hpe.morpheus.harvester.plugin.apihelper.PVCApiHelper, global: true)
        def mockHelper = Mock(com.hpe.morpheus.harvester.plugin.apihelper.PVCApiHelper)
        new com.hpe.morpheus.harvester.plugin.apihelper.PVCApiHelper(cluster) >> mockHelper
        mockHelper.getPVCs() >> [success: true, pvcs: []]

        def services = Mock(MorpheusServices)
        def storageService = Mock(MorpheusSynchronousStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        def volumeTypeService = Mock(MorpheusSynchronousStorageVolumeTypeService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncStorageSvc = Mock(MorpheusStorageService)
        def asyncVolumeSvc = Mock(MorpheusStorageVolumeService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.storage >> storageService
        storageService.volume >> volumeService
        volumeService.storageVolumeType >> volumeTypeService
        asyncServices.storage >> asyncStorageSvc
        asyncStorageSvc.volume >> asyncVolumeSvc

        volumeTypeService.find(_) >> new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)

        def proj = new StorageVolumeIdentityProjection(id: 3L, externalId: 'stale-pvc')
        asyncVolumeSvc.listIdentityProjections(_) >> Observable.fromIterable([proj])

        when:
        sync.execute()

        then:
        1 * volumeService.bulkRemove(_)
    }

    def "execute() adds PVC from API when DB is empty"() {
        given:
        GroovySpy(com.hpe.morpheus.harvester.plugin.apihelper.PVCApiHelper, global: true)
        def mockHelper = Mock(com.hpe.morpheus.harvester.plugin.apihelper.PVCApiHelper)
        new com.hpe.morpheus.harvester.plugin.apihelper.PVCApiHelper(cluster) >> mockHelper
        mockHelper.getPVCs() >> [success: true, pvcs: [[
            uid: 'pvc-exec-1', name: 'pvc-a', namespace: 'ns1', storageClass: 'standard',
            sizeBytes: 1024L, ownerReferences: [], dataSource: [:]
        ]]]

        def services = Mock(MorpheusServices)
        def storageService = Mock(MorpheusSynchronousStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        def volumeTypeService = Mock(MorpheusSynchronousStorageVolumeTypeService)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncStorageSvc = Mock(MorpheusStorageService)
        def asyncVolumeSvc = Mock(MorpheusStorageVolumeService)

        morpheusContext.services >> services
        morpheusContext.async >> asyncServices
        services.storage >> storageService
        services.cloud >> cloudService
        storageService.volume >> volumeService
        volumeService.storageVolumeType >> volumeTypeService
        cloudService.datastore >> datastoreService
        asyncServices.storage >> asyncStorageSvc
        asyncStorageSvc.volume >> asyncVolumeSvc

        def volType = new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)
        volumeTypeService.find(_) >> volType
        datastoreService.find(_) >> new com.morpheusdata.model.Datastore(id: 66L, name: 'standard')
        asyncVolumeSvc.listIdentityProjections(_) >> Observable.empty()

        when:
        sync.execute()

        then:
        1 * volumeService.bulkCreate({ List list -> list.size() == 1 && list[0].name == 'pvc-a' })
    }

    def "execute() error path - API failure is caught without throwing"() {
        given:
        GroovySpy(com.hpe.morpheus.harvester.plugin.apihelper.PVCApiHelper, global: true)
        def mockHelper = Mock(com.hpe.morpheus.harvester.plugin.apihelper.PVCApiHelper)
        new com.hpe.morpheus.harvester.plugin.apihelper.PVCApiHelper(cluster) >> mockHelper
        mockHelper.getPVCs() >> [success: false, error: 'network error']

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "addPVCs with null datastore still creates PVC"() {
        given:
        def services = Mock(MorpheusServices)
        def storageService = Mock(MorpheusSynchronousStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        morpheusContext.services >> services
        services.storage >> storageService
        storageService.volume >> volumeService
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.find(_) >> null  // No matching datastore

        def volType = new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)

        when:
        invokePrivate(sync, 'addPVCs', [Object, Object] as Class[], [[[
            uid: 'uid-2',
            name: 'pvc-b',
            namespace: 'ns2',
            storageClass: 'unknown-sc',
            sizeBytes: null,  // also test null sizeBytes -> 0L
            ownerReferences: [],
            dataSource: [:]
        ]], volType] as Object[])

        then:
        1 * volumeService.bulkCreate({ List list ->
            list.size() == 1 && list[0].name == 'pvc-b' && list[0].maxStorage == 0L && list[0].datastore == null
        })
    }

    def "updatePVCs updates only maxStorage when name and namespace match"() {
        given:
        def services = Mock(MorpheusServices)
        def storageService = Mock(MorpheusSynchronousStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        morpheusContext.services >> services
        services.storage >> storageService
        storageService.volume >> volumeService

        def pvcConfig = groovy.json.JsonOutput.toJson([ownerReferences: [], dataSource: [:]])
        def local = new StorageVolume(name: 'pvc-1', maxStorage: 100L, poolName: 'ns-1', config: pvcConfig)
        def cloudItem = [name: 'pvc-1', sizeBytes: 999L, namespace: 'ns-1', storageClass: 'sc1', ownerReferences: [], dataSource: [:]]

        when:
        invokePrivate(sync, 'updatePVCs', [Object] as Class[], [[[existingItem: local, masterItem: cloudItem]]] as Object[])

        then:
        1 * volumeService.bulkSave({ List list -> list.size() == 1 && list[0].maxStorage == 999L })
    }

    def "updatePVCs updates poolName and internalId when namespace changes"() {
        given:
        def services = Mock(MorpheusServices)
        def storageService = Mock(MorpheusSynchronousStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        morpheusContext.services >> services
        services.storage >> storageService
        storageService.volume >> volumeService

        def pvcConfig = groovy.json.JsonOutput.toJson([ownerReferences: [], dataSource: [:]])
        def local = new StorageVolume(name: 'pvc-x', maxStorage: 512L, poolName: 'old-ns', config: pvcConfig, internalId: 'sc:old-ns:pvc-x')
        def cloudItem = [name: 'pvc-x', sizeBytes: 512L, namespace: 'new-ns', storageClass: 'sc1', ownerReferences: [], dataSource: [:]]

        when:
        invokePrivate(sync, 'updatePVCs', [Object] as Class[], [[[existingItem: local, masterItem: cloudItem]]] as Object[])

        then:
        1 * volumeService.bulkSave({ List list ->
            list.size() == 1 && list[0].poolName == 'new-ns' && list[0].internalId == 'sc1:new-ns:pvc-x'
        })
    }

    def "addPVCs with multiple items builds all volumes"() {
        given:
        def services = Mock(MorpheusServices)
        def storageService = Mock(MorpheusSynchronousStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        morpheusContext.services >> services
        services.storage >> storageService
        storageService.volume >> volumeService
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.find(_) >> null

        def volType = new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)
        def items = [
            [uid: 'x', name: 'pvc-x', namespace: 'ns', storageClass: 'sc1', sizeBytes: 100L, ownerReferences: [], dataSource: [:]],
            [uid: 'y', name: 'pvc-y', namespace: 'ns', storageClass: 'sc2', sizeBytes: 200L, ownerReferences: [], dataSource: [:]],
        ]

        when:
        invokePrivate(sync, 'addPVCs', [Object, Object] as Class[], [items, volType] as Object[])

        then:
        1 * volumeService.bulkCreate({ List list -> list.size() == 2 })
    }
}
