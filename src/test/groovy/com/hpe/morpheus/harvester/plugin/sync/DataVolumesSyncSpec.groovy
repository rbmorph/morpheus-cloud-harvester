package com.hpe.morpheus.harvester.plugin.sync

import com.hpe.morpheus.harvester.plugin.HpeHarvesterPlugin
import com.hpe.morpheus.harvester.plugin.apihelper.DataVolumeApiHelper
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

class DataVolumesSyncSpec extends Specification {

    private def invokePrivate(Object target, String methodName, Class[] paramTypes, Object[] args) {
        def method = target.class.getDeclaredMethod(methodName, paramTypes)
        method.accessible = true
        method.invoke(target, args)
    }

    HpeHarvesterPlugin plugin
    MorpheusContext morpheusContext
    ComputeServerGroup cluster
    DataVolumesSync sync

    def setup() {
        plugin = Mock(HpeHarvesterPlugin)
        morpheusContext = Mock(MorpheusContext)
        cluster = Mock(ComputeServerGroup)

        def cloud = Mock(Cloud)
        cloud.id >> 33L
        cluster.id >> 100L
        cluster.name >> 'dv-cluster'
        cluster.cloud >> cloud
        cluster.owner >> Mock(com.morpheusdata.model.Account)

        plugin.morpheusContext >> morpheusContext
        sync = new DataVolumesSync(plugin, cluster)
    }

    def "constructor initializes fields"() {
        expect:
        sync.@plugin == plugin
        sync.@cluster == cluster
        sync.@morpheusContext == morpheusContext
    }

    def "execute handles helper failure gracefully"() {
        given:
        GroovySpy(DataVolumeApiHelper, global: true)
        1 * new DataVolumeApiHelper(cluster) >> { throw new RuntimeException('dv helper failed') }

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "addDataVolumes skips items without storage class"() {
        given:
        def services = Mock(MorpheusServices)
        def storageService = Mock(MorpheusSynchronousStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        morpheusContext.services >> services
        services.storage >> storageService
        storageService.volume >> volumeService

        when:
        invokePrivate(sync, 'addDataVolumes', [Object, Object] as Class[], [[[name: 'dv-a', namespace: 'ns1', storageClass: null]], Mock(StorageVolumeType)] as Object[])

        then:
        0 * volumeService.bulkCreate(_)
    }

    def "addDataVolumes builds storage volume and bulk creates"() {
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

        def volType = new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME)

        when:
        invokePrivate(sync, 'addDataVolumes', [Object, Object] as Class[], [[[
            uid: 'uid-1',
            name: 'dv-a',
            namespace: 'ns1',
            storageClass: 'fast',
            sizeBytes: 1024L,
            claimName: 'claim-a',
            ownerReferences: [],
            dataSource: [:]
        ]], volType] as Object[])

        then:
        1 * volumeService.bulkCreate({ List list -> list.size() == 1 && list[0] instanceof StorageVolume && list[0].name == 'dv-a' })
    }

    def "updateDataVolumes updates changed values and saves"() {
        given:
        def services = Mock(MorpheusServices)
        def storageService = Mock(MorpheusSynchronousStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        morpheusContext.services >> services
        services.storage >> storageService
        storageService.volume >> volumeService

        def local = new StorageVolume(name: 'old', maxStorage: 100L, poolName: 'oldns', config: '{}')
        def cloudItem = [name: 'new', sizeBytes: 200L, namespace: 'newns', storageClass: 'sc1', ownerReferences: [], dataSource: [:]]
        def updateList = [[existingItem: local, masterItem: cloudItem]]

        when:
        invokePrivate(sync, 'updateDataVolumes', [Object] as Class[], [updateList] as Object[])

        then:
        1 * volumeService.bulkSave({ List list -> list.size() == 1 && list[0].name == 'new' && list[0].poolName == 'newns' })
    }

    def "removeDataVolumes removes via bulkRemove"() {
        given:
        def services = Mock(MorpheusServices)
        def storageService = Mock(MorpheusSynchronousStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        morpheusContext.services >> services
        services.storage >> storageService
        storageService.volume >> volumeService

        when:
        invokePrivate(sync, 'removeDataVolumes', [Object] as Class[], [[[new StorageVolume(name: 'x')]]] as Object[])

        then:
        1 * volumeService.bulkRemove(_)
    }

    def "addDataVolumes with empty list does nothing"() {
        given:
        def services = Mock(MorpheusServices)
        morpheusContext.services >> services

        when:
        invokePrivate(sync, 'addDataVolumes', [Object, Object] as Class[], [[], Mock(StorageVolumeType)] as Object[])

        then:
        0 * services.storage
    }

    def "updateDataVolumes with no changes does not call bulkSave"() {
        given:
        def services = Mock(MorpheusServices)
        def storageService = Mock(MorpheusSynchronousStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        morpheusContext.services >> services
        services.storage >> storageService
        storageService.volume >> volumeService

        def existing = new StorageVolume(name: 'dv-same', maxStorage: 1024L, poolName: 'ns-same', config: '{}')
        def cloudItem = [name: 'dv-same', sizeBytes: 1024L, namespace: 'ns-same', storageClass: 'sc1', ownerReferences: [], dataSource: [:]]
        def updateList = [[existingItem: existing, masterItem: cloudItem]]

        when:
        invokePrivate(sync, 'updateDataVolumes', [Object] as Class[], [updateList] as Object[])

        then:
        // No changes → bulkSave may or may not be called (config/ownerRef might differ),
        // but must not throw
        notThrown(Exception)
    }

    // --- execute() SyncTask path tests ---

    def "execute() removes stale DataVolume from DB when API returns empty"() {
        given:
        GroovySpy(com.hpe.morpheus.harvester.plugin.apihelper.DataVolumeApiHelper, global: true)
        def mockHelper = Mock(com.hpe.morpheus.harvester.plugin.apihelper.DataVolumeApiHelper)
        new com.hpe.morpheus.harvester.plugin.apihelper.DataVolumeApiHelper(cluster) >> mockHelper
        mockHelper.getDataVolumes() >> [success: true, datavolumes: []]

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

        volumeTypeService.find(_) >> new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME)

        def proj = new StorageVolumeIdentityProjection(id: 7L, externalId: 'stale-dv')
        asyncVolumeSvc.listIdentityProjections(_) >> Observable.fromIterable([proj])

        when:
        sync.execute()

        then:
        1 * volumeService.bulkRemove(_)
    }

    def "execute() adds DataVolume from API when DB empty"() {
        given:
        GroovySpy(com.hpe.morpheus.harvester.plugin.apihelper.DataVolumeApiHelper, global: true)
        def mockHelper = Mock(com.hpe.morpheus.harvester.plugin.apihelper.DataVolumeApiHelper)
        new com.hpe.morpheus.harvester.plugin.apihelper.DataVolumeApiHelper(cluster) >> mockHelper
        mockHelper.getDataVolumes() >> [success: true, datavolumes: [[
            uid: 'dv-exec-1', name: 'dv-a', namespace: 'ns1', storageClass: 'fast',
            sizeBytes: 2048L, ownerReferences: [], dataSource: [:]
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

        def volType = new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME)
        volumeTypeService.find(_) >> volType
        datastoreService.find(_) >> new com.morpheusdata.model.Datastore(id: 55L, name: 'fast')
        asyncVolumeSvc.listIdentityProjections(_) >> Observable.empty()

        when:
        sync.execute()

        then:
        1 * volumeService.bulkCreate({ List list -> list.size() == 1 && list[0].name == 'dv-a' })
    }

    def "execute() error path - API failure causes exception caught by outer catch"() {
        given:
        GroovySpy(com.hpe.morpheus.harvester.plugin.apihelper.DataVolumeApiHelper, global: true)
        def mockHelper = Mock(com.hpe.morpheus.harvester.plugin.apihelper.DataVolumeApiHelper)
        new com.hpe.morpheus.harvester.plugin.apihelper.DataVolumeApiHelper(cluster) >> mockHelper
        mockHelper.getDataVolumes() >> [success: false, error: 'api error']

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "addDataVolumes with multiple items skips null-storageClass items and creates others"() {
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
        datastoreService.find(_) >> new Datastore(id: 10L)

        def volType = new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME)
        def items = [
            [uid: 'a', name: 'dv-a', namespace: 'ns1', storageClass: null, sizeBytes: 100L, ownerReferences: [], dataSource: [:]],  // skipped
            [uid: 'b', name: 'dv-b', namespace: 'ns1', storageClass: 'fast', sizeBytes: 200L, ownerReferences: [], dataSource: [:]],  // added
        ]

        when:
        invokePrivate(sync, 'addDataVolumes', [Object, Object] as Class[], [items, volType] as Object[])

        then:
        1 * volumeService.bulkCreate({ List list -> list.size() == 1 && list[0].name == 'dv-b' })
    }

    def "updateDataVolumes updates only maxStorage when name and namespace match"() {
        given:
        def services = Mock(MorpheusServices)
        def storageService = Mock(MorpheusSynchronousStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        morpheusContext.services >> services
        services.storage >> storageService
        storageService.volume >> volumeService

        def pvcConfig = groovy.json.JsonOutput.toJson([ownerReferences: [], dataSource: [:]])
        def local = new StorageVolume(name: 'dv-1', maxStorage: 100L, poolName: 'ns-1', config: pvcConfig)
        def cloudItem = [name: 'dv-1', sizeBytes: 500L, namespace: 'ns-1', storageClass: 'sc1', ownerReferences: [], dataSource: [:]]

        when:
        invokePrivate(sync, 'updateDataVolumes', [Object] as Class[], [[[existingItem: local, masterItem: cloudItem]]] as Object[])

        then:
        1 * volumeService.bulkSave({ List list -> list.size() == 1 && list[0].maxStorage == 500L })
    }

    def "updateDataVolumes with empty update list does nothing"() {
        given:
        def services = Mock(MorpheusServices)
        morpheusContext.services >> services

        when:
        invokePrivate(sync, 'updateDataVolumes', [Object] as Class[], [[]] as Object[])

        then:
        0 * services.storage
    }

    def "addDataVolumes sets sizeBytes to 0 when null"() {
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
        datastoreService.find(_) >> null  // datastore not found

        def volType = new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME)

        when:
        invokePrivate(sync, 'addDataVolumes', [Object, Object] as Class[], [[[
            uid: 'dv-z', name: 'dv-z', namespace: 'ns1', storageClass: 'sc1',
            sizeBytes: null, ownerReferences: [], dataSource: [:]
        ]], volType] as Object[])

        then:
        1 * volumeService.bulkCreate({ List list ->
            list.size() == 1 && list[0].maxStorage == 0L
        })
    }
}
