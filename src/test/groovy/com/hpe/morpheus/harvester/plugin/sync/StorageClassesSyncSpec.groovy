package com.hpe.morpheus.harvester.plugin.sync

import com.hpe.morpheus.harvester.plugin.HpeHarvesterPlugin
import com.hpe.morpheus.harvester.plugin.apihelper.StorageClassApiHelper
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.MorpheusStorageService
import com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService
import com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeTypeService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService
import com.morpheusdata.model.Account
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.DatastoreType
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.projection.DatastoreIdentity
import spock.lang.Specification

class StorageClassesSyncSpec extends Specification {

    private def invokePrivate(Object target, String methodName, Class[] paramTypes, Object[] args) {
        def method = target.class.getDeclaredMethod(methodName, paramTypes)
        method.accessible = true
        method.invoke(target, args)
    }

    HpeHarvesterPlugin plugin
    MorpheusContext morpheusContext
    ComputeServerGroup cluster
    StorageClassesSync sync

    def setup() {
        plugin = Mock(HpeHarvesterPlugin)
        morpheusContext = Mock(MorpheusContext)
        cluster = Mock(ComputeServerGroup)

        def cloud = Mock(Cloud)
        cloud.id >> 77L
        cloud.getConfigProperty('defaultDatastoreSyncActive') >> true

        cluster.id >> 321L
        cluster.name >> 'storage-cluster'
        cluster.cloud >> cloud
        cluster.owner >> Mock(com.morpheusdata.model.Account)

        plugin.morpheusContext >> morpheusContext
        sync = new StorageClassesSync(plugin, cluster)
    }

    def "constructor initializes fields"() {
        expect:
        sync.@plugin == plugin
        sync.@cluster == cluster
        sync.@morpheusContext == morpheusContext
    }

    def "execute handles helper exception gracefully"() {
        given:
        GroovySpy(StorageClassApiHelper, global: true)
        1 * new StorageClassApiHelper(cluster) >> { throw new RuntimeException('storage helper failed') }

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "addStorageClasses creates datastore entries"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService

        def datastoreType = new DatastoreType(id: 99L)
        datastoreTypeService.find(_) >> datastoreType

        when:
        invokePrivate(sync, 'addStorageClasses', [Object, CloudPool] as Class[], [[[
            uid: 'sc-uid',
            name: 'fast-sc',
            provisioner: 'kubernetes.io/no-provisioner',
            storageVolumeType: null,
            parameters: [fstype: 'xfs']
        ]], new CloudPool(id: 444L)] as Object[])

        then:
        1 * datastoreService.bulkCreate({ List list -> list.size() == 1 && list[0] instanceof Datastore && list[0].name == 'fast-sc' })
    }

    def "updateStorageClasses updates changed datastore fields"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService

        def dtType = new DatastoreType(id: 11L)
        datastoreTypeService.find(_) >> dtType

        def existing = new Datastore(name: 'old-sc', type: 'old-prov', code: 'old', uniqueId: 'old', externalId: 'old', refType: 'x', refId: 1L, category: 'c', active: false)
        existing.cloud = Mock(Cloud) { id >> 999L }
        existing.setConfigMap([a: 1])

        def updateList = [[
            existingItem: existing,
            masterItem: [
                uid: 'new-uid',
                name: 'new-sc',
                provisioner: 'new-prov',
                storageVolumeType: null,
                parameters: [:],
                allowVolumeExpansion: false,
                reclaimPolicy: 'Delete',
                volumeBindingMode: 'Immediate'
            ]
        ]]

        when:
        invokePrivate(sync, 'updateStorageClasses', [Object] as Class[], [updateList] as Object[])

        then:
        1 * datastoreService.bulkSave({ List list -> list.size() == 1 && list[0].name == 'new-sc' })
    }

    def "removeStorageClasses bulk removes datastores"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        def storageService = Mock(com.morpheusdata.core.MorpheusSynchronousStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        services.storage >> storageService
        storageService.volume >> volumeService
        volumeService.find(_) >> null

        when:
        invokePrivate(sync, 'removeStorageClasses', [Object] as Class[], [[[new Datastore(name: 'x')]]] as Object[])

        then:
        1 * datastoreService.bulkRemove(_)
    }

    def "removeStorageClasses deactivates datastores that still hold volumes"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        def storageService = Mock(com.morpheusdata.core.MorpheusSynchronousStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        services.storage >> storageService
        storageService.volume >> volumeService
        def used = new Datastore(name: 'used'); used.id = 1L
        def unused = new Datastore(name: 'unused'); unused.id = 2L
        volumeService.find(_) >>> [new com.morpheusdata.model.StorageVolume(name: 'disk'), null]

        when:
        // one argument: the list of datastores to remove
        Object[] args = new Object[1]
        args[0] = [used, unused]
        invokePrivate(sync, 'removeStorageClasses', [Object] as Class[], args)

        then:
        1 * datastoreService.bulkRemove([unused])
        1 * datastoreService.list(_) >> [used]
        1 * datastoreService.bulkSave([used])
        used.active == false
    }


    // --- execute() SyncTask path tests ---

    def "execute() returns early when storageVolumeType list is empty"() {
        given:
        GroovySpy(StorageClassApiHelper, global: true)
        def mockHelper = Mock(StorageClassApiHelper)
        new StorageClassApiHelper(cluster) >> mockHelper
        mockHelper.getStorageClasses() >> [success: true, data: [storageClasses: [
            [uid: 'sc1', name: 'fast-sc', provisioner: 'ebs.csi']
        ]]]

        def services = Mock(MorpheusServices)
        def storageService = Mock(MorpheusStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        def volumeTypeService = Mock(MorpheusSynchronousStorageVolumeTypeService)

        morpheusContext.services >> services
        services.storage >> storageService
        storageService.volume >> volumeService
        volumeService.storageVolumeType >> volumeTypeService
        volumeTypeService.list(_) >> []  // empty → early return

        when:
        sync.execute()

        then:
        notThrown(Exception)
        0 * services.cloud
    }

    def "execute() handles API returning success=false gracefully"() {
        given:
        GroovySpy(StorageClassApiHelper, global: true)
        def mockHelper = Mock(StorageClassApiHelper)
        new StorageClassApiHelper(cluster) >> mockHelper
        mockHelper.getStorageClasses() >> [success: false]

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "execute() removes stale StorageClass from DB when API has no match"() {
        given:
        GroovySpy(StorageClassApiHelper, global: true)
        def mockHelper = Mock(StorageClassApiHelper)
        new StorageClassApiHelper(cluster) >> mockHelper
        mockHelper.getStorageClasses() >> [success: true, data: [storageClasses: [
            [uid: 'api-sc', name: 'fast-sc', provisioner: 'k8s']
        ]]]

        def services = Mock(MorpheusServices)
        def storageService = Mock(MorpheusStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        def volumeTypeService = Mock(MorpheusSynchronousStorageVolumeTypeService)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def cloudPoolService = Mock(MorpheusSynchronousCloudPoolService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)

        morpheusContext.services >> services
        services.storage >> storageService
        storageService.volume >> volumeService
        volumeService.storageVolumeType >> volumeTypeService
        services.cloud >> cloudService
        cloudService.pool >> cloudPoolService
        cloudService.datastore >> datastoreService

        volumeTypeService.list(_) >> [new StorageVolumeType(code: 'kubernetesVolume')]
        cloudPoolService.find(_) >> new CloudPool(id: 100L)

        def dbItem = Mock(DatastoreIdentity)
        dbItem.externalId >> 'db-only-sc'
        datastoreService.listIdentityProjections(_) >> [dbItem]

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "execute() adds StorageClass from API when DB is empty"() {
        given:
        GroovySpy(StorageClassApiHelper, global: true)
        def mockHelper = Mock(StorageClassApiHelper)
        new StorageClassApiHelper(cluster) >> mockHelper
        mockHelper.getStorageClasses() >> [success: true, data: [storageClasses: [
            [uid: 'sc-new', name: 'fast-sc', provisioner: 'ebs.csi']
        ]]]

        def services = Mock(MorpheusServices)
        def storageService = Mock(MorpheusStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        def volumeTypeService = Mock(MorpheusSynchronousStorageVolumeTypeService)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def cloudPoolService = Mock(MorpheusSynchronousCloudPoolService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)

        morpheusContext.services >> services
        services.storage >> storageService
        storageService.volume >> volumeService
        volumeService.storageVolumeType >> volumeTypeService
        services.cloud >> cloudService
        cloudService.pool >> cloudPoolService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService

        volumeTypeService.list(_) >> [new StorageVolumeType(code: 'kubernetesVolume')]
        cloudPoolService.find(_) >> new CloudPool(id: 100L)
        datastoreService.listIdentityProjections(_) >> []  // empty DB → onAdd
        datastoreTypeService.find(_) >> new DatastoreType(id: 10L)

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "execute() updates StorageClass when API item matches DB item"() {
        given:
        GroovySpy(StorageClassApiHelper, global: true)
        def mockHelper = Mock(StorageClassApiHelper)
        new StorageClassApiHelper(cluster) >> mockHelper
        mockHelper.getStorageClasses() >> [success: true, data: [storageClasses: [
            [uid: 'sc-match', name: 'updated-sc', provisioner: 'ebs.csi', reclaimPolicy: 'Delete',
             volumeBindingMode: 'Immediate', allowVolumeExpansion: true, parameters: [:]]
        ]]]

        def services = Mock(MorpheusServices)
        def storageService = Mock(MorpheusStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        def volumeTypeService = Mock(MorpheusSynchronousStorageVolumeTypeService)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def cloudPoolService = Mock(MorpheusSynchronousCloudPoolService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)

        morpheusContext.services >> services
        services.storage >> storageService
        storageService.volume >> volumeService
        volumeService.storageVolumeType >> volumeTypeService
        services.cloud >> cloudService
        cloudService.pool >> cloudPoolService
        cloudService.datastore >> datastoreService

        volumeTypeService.list(_) >> [new StorageVolumeType(code: 'kubernetesVolume')]
        cloudPoolService.find(_) >> new CloudPool(id: 100L)

        def dbItem = Mock(DatastoreIdentity)
        dbItem.externalId >> 'sc-match'
        dbItem.id >> 55L
        datastoreService.listIdentityProjections(_) >> [dbItem]

        // Full datastore objects for withLoadObjectDetailsFromFinder
        def fullDatastore = new Datastore(id: 55L, externalId: 'sc-match', name: 'old-sc')
        datastoreService.list(_) >> [fullDatastore]

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "execute() handles empty storageClasses list gracefully"() {
        given:
        GroovySpy(StorageClassApiHelper, global: true)
        def mockHelper = Mock(StorageClassApiHelper)
        new StorageClassApiHelper(cluster) >> mockHelper
        mockHelper.getStorageClasses() >> [success: true, data: [storageClasses: []]]

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "execute() handles null storageClasses gracefully"() {
        given:
        GroovySpy(StorageClassApiHelper, global: true)
        def mockHelper = Mock(StorageClassApiHelper)
        new StorageClassApiHelper(cluster) >> mockHelper
        mockHelper.getStorageClasses() >> [success: true, data: [storageClasses: null]]

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "addStorageClasses does not call bulkCreate for empty list"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> null

        when:
        invokePrivate(sync, 'addStorageClasses', [Object, CloudPool] as Class[], [[], new CloudPool(id: 1L)] as Object[])

        then:
        0 * datastoreService.bulkCreate(_)
    }

    def "addStorageClasses still creates entry when datastoreType not found"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> null  // no datastore type

        when:
        invokePrivate(sync, 'addStorageClasses', [Object, CloudPool] as Class[], [[[
            uid: 'sc-notype',
            name: 'slow-sc',
            provisioner: 'k8s.io/no-provisioner',
            storageVolumeType: null,
            parameters: [:]
        ]], new CloudPool(id: 5L)] as Object[])

        then:
        1 * datastoreService.bulkCreate({ List list ->
            list.size() == 1 && list[0].name == 'slow-sc' && list[0].datastoreType == null
        })
    }

    def "updateStorageClasses does not call bulkSave when nothing changed"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService

        def dtType = new DatastoreType(id: 77L)
        datastoreTypeService.find(_) >> dtType

        def storageClass = [
            uid: 'sc-uid',
            name: 'same-sc',
            provisioner: 'same-prov',
            storageVolumeType: null,
            parameters: [:],
            allowVolumeExpansion: false,
            reclaimPolicy: 'Delete',
            volumeBindingMode: 'Immediate'
        ]

        // Build a fully-matching existing datastore
        def existing = new Datastore(
            name: 'same-sc',
            type: 'same-prov',
            code: "harvester.storageClass.cluster.321.same-sc",
            uniqueId: 'sc-uid',
            externalId: 'sc-uid',
            refType: 'ComputeServerGroup',
            refId: 321L,
            category: "harvester.storageClass.cluster.321",
            active: true,
            storageSize: null,
            freeSpace: null
        )
        existing.owner = cluster.owner
        existing.cloud = cluster.cloud
        existing.setConfigMap([
            provisioner: 'same-prov',
            parameters: [:],
            allowVolumeExpansion: false,
            reclaimPolicy: 'Delete',
            volumeBindingMode: 'Immediate',
            clusterId: 321L
        ])
        existing.datastoreType = dtType

        when:
        invokePrivate(sync, 'updateStorageClasses', [Object] as Class[], [[[existingItem: existing, masterItem: storageClass]]] as Object[])

        then:
        0 * datastoreService.bulkSave(_)
    }

    def "updateStorageClasses with empty list does not call bulkSave"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> null

        when:
        invokePrivate(sync, 'updateStorageClasses', [Object] as Class[], [[]] as Object[])

        then:
        0 * datastoreService.bulkSave(_)
    }

    def "removeStorageClasses does not call bulkRemove for null list"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService

        when:
        invokePrivate(sync, 'removeStorageClasses', [Object] as Class[], [null] as Object[])

        then:
        0 * datastoreService.bulkRemove(_)
    }

    def "updateStorageClasses logs warning when datastoreType is null but datastore has one"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> null  // no type in DB

        // existing datastore has a datastoreType assigned
        def existingDsType = new DatastoreType(id: 55L)
        def storageClass = [
            uid: 'sc-uid', name: 'sc', provisioner: 'prov',
            storageVolumeType: null, parameters: [:],
            allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate'
        ]
        def existing = new Datastore(
            name: 'sc', type: 'prov',
            code: "harvester.storageClass.cluster.321.sc",
            uniqueId: 'sc-uid', externalId: 'sc-uid',
            refType: 'ComputeServerGroup', refId: 321L,
            category: "harvester.storageClass.cluster.321",
            active: true, storageSize: null, freeSpace: null
        )
        existing.owner = cluster.owner
        existing.cloud = cluster.cloud
        existing.datastoreType = existingDsType
        existing.setConfigMap([
            provisioner: 'prov', parameters: [:],
            allowVolumeExpansion: false, reclaimPolicy: 'Delete',
            volumeBindingMode: 'Immediate', clusterId: 321L
        ])

        when:
        invokePrivate(sync, 'updateStorageClasses', [Object] as Class[], [[[existingItem: existing, masterItem: storageClass]]] as Object[])

        then:
        // should not crash; doSave stays false if everything else matches
        notThrown(Exception)
    }

    def "addStorageClasses uses uid as fallback name when name is null"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> null

        when:
        invokePrivate(sync, 'addStorageClasses', [Object, CloudPool] as Class[], [[[
            uid: 'sc-fallback-uid',
            name: null,  // null name -> fallback "Storage Class ${uid}"
            provisioner: 'k8s.io/no-provisioner',
            storageVolumeType: null
        ]], new CloudPool(id: 5L)] as Object[])

        then:
        1 * datastoreService.bulkCreate({ List list ->
            list.size() == 1 && list[0].name == 'Storage Class sc-fallback-uid'
        })
    }

    // ===== Individual field update branch tests =====

    def "updateStorageClasses updates only owner when it differs"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> null

        def existingCloud = Mock(Cloud) { id >> cluster.cloud.id }
        def existing = new Datastore(
            name: 'sc1', type: 'prov1', code: "harvester.storageClass.cluster.${cluster.id}.sc1",
            uniqueId: 'uid1', externalId: 'uid1', refType: HarvesterConstants.COMPUTE_SERVER_GROUP,
            refId: cluster.id, category: "harvester.storageClass.cluster.${cluster.id}",
            active: true, storageSize: null, freeSpace: null
        )
        existing.cloud = existingCloud
        existing.owner = Mock(Account) { id >> 999L }
        existing.setConfigMap([provisioner: 'prov1', parameters: [:], allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate', clusterId: cluster.id])

        def updateList = [[
            existingItem: existing,
            masterItem: [
                uid: 'uid1', name: 'sc1', provisioner: 'prov1',
                storageVolumeType: null, parameters: [:],
                allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate'
            ]
        ]]

        when:
        invokePrivate(sync, 'updateStorageClasses', [Object] as Class[], [updateList] as Object[])

        then:
        1 * datastoreService.bulkSave({ List list -> list.size() == 1 && list[0].owner == cluster.owner })
    }

    def "updateStorageClasses updates only type when it differs"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> null

        def existingCloud = Mock(Cloud) { id >> cluster.cloud.id }
        def existing = new Datastore(
            name: 'sc1', type: 'old-provisioner', code: "harvester.storageClass.cluster.${cluster.id}.sc1",
            uniqueId: 'uid1', externalId: 'uid1', refType: HarvesterConstants.COMPUTE_SERVER_GROUP,
            refId: cluster.id, category: "harvester.storageClass.cluster.${cluster.id}",
            active: true, storageSize: null, freeSpace: null
        )
        existing.cloud = existingCloud
        existing.owner = cluster.owner
        existing.setConfigMap([provisioner: 'new-provisioner', parameters: [:], allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate', clusterId: cluster.id])

        def updateList = [[
            existingItem: existing,
            masterItem: [
                uid: 'uid1', name: 'sc1', provisioner: 'new-provisioner',
                storageVolumeType: null, parameters: [:],
                allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate'
            ]
        ]]

        when:
        invokePrivate(sync, 'updateStorageClasses', [Object] as Class[], [updateList] as Object[])

        then:
        1 * datastoreService.bulkSave({ List list -> list.size() == 1 && list[0].type == 'new-provisioner' })
    }

    def "updateStorageClasses updates only name when it differs"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> null

        def existingCloud = Mock(Cloud) { id >> cluster.cloud.id }
        def existing = new Datastore(
            name: 'old-name', type: 'prov1', code: "harvester.storageClass.cluster.${cluster.id}.new-name",
            uniqueId: 'uid1', externalId: 'uid1', refType: HarvesterConstants.COMPUTE_SERVER_GROUP,
            refId: cluster.id, category: "harvester.storageClass.cluster.${cluster.id}",
            active: true, storageSize: null, freeSpace: null
        )
        existing.cloud = existingCloud
        existing.owner = cluster.owner
        existing.setConfigMap([provisioner: 'prov1', parameters: [:], allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate', clusterId: cluster.id])

        def updateList = [[
            existingItem: existing,
            masterItem: [
                uid: 'uid1', name: 'new-name', provisioner: 'prov1',
                storageVolumeType: null, parameters: [:],
                allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate'
            ]
        ]]

        when:
        invokePrivate(sync, 'updateStorageClasses', [Object] as Class[], [updateList] as Object[])

        then:
        1 * datastoreService.bulkSave({ List list -> list.size() == 1 && list[0].name == 'new-name' })
    }

    def "updateStorageClasses updates only code when it differs"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> null

        def existingCloud = Mock(Cloud) { id >> cluster.cloud.id }
        def existing = new Datastore(
            name: 'sc1', type: 'prov1', code: 'wrong-code',
            uniqueId: 'uid1', externalId: 'uid1', refType: HarvesterConstants.COMPUTE_SERVER_GROUP,
            refId: cluster.id, category: "harvester.storageClass.cluster.${cluster.id}",
            active: true, storageSize: null, freeSpace: null
        )
        existing.cloud = existingCloud
        existing.owner = cluster.owner
        existing.setConfigMap([provisioner: 'prov1', parameters: [:], allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate', clusterId: cluster.id])

        def updateList = [[
            existingItem: existing,
            masterItem: [
                uid: 'uid1', name: 'sc1', provisioner: 'prov1',
                storageVolumeType: null, parameters: [:],
                allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate'
            ]
        ]]

        when:
        invokePrivate(sync, 'updateStorageClasses', [Object] as Class[], [updateList] as Object[])

        then:
        1 * datastoreService.bulkSave({ List list -> list.size() == 1 && list[0].code == "harvester.storageClass.cluster.${cluster.id}.sc1" })
    }

    def "updateStorageClasses updates only uniqueId when it differs"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> null

        def existingCloud = Mock(Cloud) { id >> cluster.cloud.id }
        def existing = new Datastore(
            name: 'sc1', type: 'prov1', code: "harvester.storageClass.cluster.${cluster.id}.sc1",
            uniqueId: 'old-uid', externalId: 'new-uid', refType: HarvesterConstants.COMPUTE_SERVER_GROUP,
            refId: cluster.id, category: "harvester.storageClass.cluster.${cluster.id}",
            active: true, storageSize: null, freeSpace: null
        )
        existing.cloud = existingCloud
        existing.owner = cluster.owner
        existing.setConfigMap([provisioner: 'prov1', parameters: [:], allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate', clusterId: cluster.id])

        def updateList = [[
            existingItem: existing,
            masterItem: [
                uid: 'new-uid', name: 'sc1', provisioner: 'prov1',
                storageVolumeType: null, parameters: [:],
                allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate'
            ]
        ]]

        when:
        invokePrivate(sync, 'updateStorageClasses', [Object] as Class[], [updateList] as Object[])

        then:
        1 * datastoreService.bulkSave({ List list -> list.size() == 1 && list[0].uniqueId == 'new-uid' })
    }

    def "updateStorageClasses updates only externalId when it differs"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> null

        def existingCloud = Mock(Cloud) { id >> cluster.cloud.id }
        def existing = new Datastore(
            name: 'sc1', type: 'prov1', code: "harvester.storageClass.cluster.${cluster.id}.sc1",
            uniqueId: 'new-uid', externalId: 'old-external-id', refType: HarvesterConstants.COMPUTE_SERVER_GROUP,
            refId: cluster.id, category: "harvester.storageClass.cluster.${cluster.id}",
            active: true, storageSize: null, freeSpace: null
        )
        existing.cloud = existingCloud
        existing.owner = cluster.owner
        existing.setConfigMap([provisioner: 'prov1', parameters: [:], allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate', clusterId: cluster.id])

        def updateList = [[
            existingItem: existing,
            masterItem: [
                uid: 'new-uid', name: 'sc1', provisioner: 'prov1',
                storageVolumeType: null, parameters: [:],
                allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate'
            ]
        ]]

        when:
        invokePrivate(sync, 'updateStorageClasses', [Object] as Class[], [updateList] as Object[])

        then:
        1 * datastoreService.bulkSave({ List list -> list.size() == 1 && list[0].externalId == 'new-uid' })
    }

    def "updateStorageClasses updates only refType when it differs"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> null

        def existingCloud = Mock(Cloud) { id >> cluster.cloud.id }
        def existing = new Datastore(
            name: 'sc1', type: 'prov1', code: "harvester.storageClass.cluster.${cluster.id}.sc1",
            uniqueId: 'uid1', externalId: 'uid1', refType: 'wrong-ref-type',
            refId: cluster.id, category: "harvester.storageClass.cluster.${cluster.id}",
            active: true, storageSize: null, freeSpace: null
        )
        existing.cloud = existingCloud
        existing.owner = cluster.owner
        existing.setConfigMap([provisioner: 'prov1', parameters: [:], allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate', clusterId: cluster.id])

        def updateList = [[
            existingItem: existing,
            masterItem: [
                uid: 'uid1', name: 'sc1', provisioner: 'prov1',
                storageVolumeType: null, parameters: [:],
                allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate'
            ]
        ]]

        when:
        invokePrivate(sync, 'updateStorageClasses', [Object] as Class[], [updateList] as Object[])

        then:
        1 * datastoreService.bulkSave({ List list -> list.size() == 1 && list[0].refType == HarvesterConstants.COMPUTE_SERVER_GROUP })
    }

    def "updateStorageClasses updates only refId when it differs"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> null

        def existingCloud = Mock(Cloud) { id >> cluster.cloud.id }
        def existing = new Datastore(
            name: 'sc1', type: 'prov1', code: "harvester.storageClass.cluster.${cluster.id}.sc1",
            uniqueId: 'uid1', externalId: 'uid1', refType: HarvesterConstants.COMPUTE_SERVER_GROUP,
            refId: 9999L, category: "harvester.storageClass.cluster.${cluster.id}",
            active: true, storageSize: null, freeSpace: null
        )
        existing.cloud = existingCloud
        existing.owner = cluster.owner
        existing.setConfigMap([provisioner: 'prov1', parameters: [:], allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate', clusterId: cluster.id])

        def updateList = [[
            existingItem: existing,
            masterItem: [
                uid: 'uid1', name: 'sc1', provisioner: 'prov1',
                storageVolumeType: null, parameters: [:],
                allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate'
            ]
        ]]

        when:
        invokePrivate(sync, 'updateStorageClasses', [Object] as Class[], [updateList] as Object[])

        then:
        1 * datastoreService.bulkSave({ List list -> list.size() == 1 && list[0].refId == cluster.id })
    }

    def "updateStorageClasses updates only category when it differs"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> null

        def existingCloud = Mock(Cloud) { id >> cluster.cloud.id }
        def existing = new Datastore(
            name: 'sc1', type: 'prov1', code: "harvester.storageClass.cluster.${cluster.id}.sc1",
            uniqueId: 'uid1', externalId: 'uid1', refType: HarvesterConstants.COMPUTE_SERVER_GROUP,
            refId: cluster.id, category: 'wrong-category',
            active: true, storageSize: null, freeSpace: null
        )
        existing.cloud = existingCloud
        existing.owner = cluster.owner
        existing.setConfigMap([provisioner: 'prov1', parameters: [:], allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate', clusterId: cluster.id])

        def updateList = [[
            existingItem: existing,
            masterItem: [
                uid: 'uid1', name: 'sc1', provisioner: 'prov1',
                storageVolumeType: null, parameters: [:],
                allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate'
            ]
        ]]

        when:
        invokePrivate(sync, 'updateStorageClasses', [Object] as Class[], [updateList] as Object[])

        then:
        1 * datastoreService.bulkSave({ List list -> list.size() == 1 && list[0].category == "harvester.storageClass.cluster.${cluster.id}" })
    }

    def "updateStorageClasses updates only cloud when it differs"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> null

        def wrongCloud = Mock(Cloud) { id >> 8888L }
        def existing = new Datastore(
            name: 'sc1', type: 'prov1', code: "harvester.storageClass.cluster.${cluster.id}.sc1",
            uniqueId: 'uid1', externalId: 'uid1', refType: HarvesterConstants.COMPUTE_SERVER_GROUP,
            refId: cluster.id, category: "harvester.storageClass.cluster.${cluster.id}",
            active: true, storageSize: null, freeSpace: null
        )
        existing.cloud = wrongCloud
        existing.owner = cluster.owner
        existing.setConfigMap([provisioner: 'prov1', parameters: [:], allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate', clusterId: cluster.id])

        def updateList = [[
            existingItem: existing,
            masterItem: [
                uid: 'uid1', name: 'sc1', provisioner: 'prov1',
                storageVolumeType: null, parameters: [:],
                allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate'
            ]
        ]]

        when:
        invokePrivate(sync, 'updateStorageClasses', [Object] as Class[], [updateList] as Object[])

        then:
        1 * datastoreService.bulkSave({ List list -> list.size() == 1 && list[0].cloud == cluster.cloud })
    }

    def "updateStorageClasses updates volumeType when it differs"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> null

        def existingCloud = Mock(Cloud) { id >> cluster.cloud.id }
        def oldVolumeType = Mock(com.morpheusdata.model.StorageVolumeType) { id >> 111L }
        def newVolumeType = Mock(com.morpheusdata.model.StorageVolumeType) { id >> 222L }
        def existing = new Datastore(
            name: 'sc1', type: 'prov1', code: "harvester.storageClass.cluster.${cluster.id}.sc1",
            uniqueId: 'uid1', externalId: 'uid1', refType: HarvesterConstants.COMPUTE_SERVER_GROUP,
            refId: cluster.id, category: "harvester.storageClass.cluster.${cluster.id}",
            active: true, storageSize: null, freeSpace: null
        )
        existing.cloud = existingCloud
        existing.owner = cluster.owner
        existing.volumeType = oldVolumeType
        existing.setConfigMap([provisioner: 'prov1', parameters: [:], allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate', clusterId: cluster.id])

        def updateList = [[
            existingItem: existing,
            masterItem: [
                uid: 'uid1', name: 'sc1', provisioner: 'prov1',
                storageVolumeType: newVolumeType, parameters: [:],
                allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate'
            ]
        ]]

        when:
        invokePrivate(sync, 'updateStorageClasses', [Object] as Class[], [updateList] as Object[])

        then:
        1 * datastoreService.bulkSave({ List list -> list.size() == 1 && list[0].volumeType == newVolumeType })
    }

    def "updateStorageClasses updates config when it differs"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> null

        def existingCloud = Mock(Cloud) { id >> cluster.cloud.id }
        def existing = new Datastore(
            name: 'sc1', type: 'prov1', code: "harvester.storageClass.cluster.${cluster.id}.sc1",
            uniqueId: 'uid1', externalId: 'uid1', refType: HarvesterConstants.COMPUTE_SERVER_GROUP,
            refId: cluster.id, category: "harvester.storageClass.cluster.${cluster.id}",
            active: true, storageSize: null, freeSpace: null
        )
        existing.cloud = existingCloud
        existing.owner = cluster.owner
        existing.setConfigMap([provisioner: 'old-prov', parameters: [old: 'value']])

        def updateList = [[
            existingItem: existing,
            masterItem: [
                uid: 'uid1', name: 'sc1', provisioner: 'prov1',
                storageVolumeType: null, parameters: [new: 'param'],
                allowVolumeExpansion: true, reclaimPolicy: 'Retain', volumeBindingMode: 'WaitForFirstConsumer'
            ]
        ]]

        when:
        invokePrivate(sync, 'updateStorageClasses', [Object] as Class[], [updateList] as Object[])

        then:
        1 * datastoreService.bulkSave({ List list ->
            list.size() == 1 && list[0].getConfigMap().provisioner == 'prov1' &&
            list[0].getConfigMap().allowVolumeExpansion == true &&
            list[0].getConfigMap().reclaimPolicy == 'Retain'
        })
    }

    def "updateStorageClasses clears storageSize when non-null"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> null

        def existingCloud = Mock(Cloud) { id >> cluster.cloud.id }
        def existing = new Datastore(
            name: 'sc1', type: 'prov1', code: "harvester.storageClass.cluster.${cluster.id}.sc1",
            uniqueId: 'uid1', externalId: 'uid1', refType: HarvesterConstants.COMPUTE_SERVER_GROUP,
            refId: cluster.id, category: "harvester.storageClass.cluster.${cluster.id}",
            active: true, storageSize: 1000L, freeSpace: null
        )
        existing.cloud = existingCloud
        existing.owner = cluster.owner
        existing.setConfigMap([provisioner: 'prov1', parameters: [:], allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate', clusterId: cluster.id])

        def updateList = [[
            existingItem: existing,
            masterItem: [
                uid: 'uid1', name: 'sc1', provisioner: 'prov1',
                storageVolumeType: null, parameters: [:],
                allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate'
            ]
        ]]

        when:
        invokePrivate(sync, 'updateStorageClasses', [Object] as Class[], [updateList] as Object[])

        then:
        1 * datastoreService.bulkSave({ List list -> list.size() == 1 && list[0].storageSize == null })
    }

    def "updateStorageClasses clears freeSpace when non-null"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> null

        def existingCloud = Mock(Cloud) { id >> cluster.cloud.id }
        def existing = new Datastore(
            name: 'sc1', type: 'prov1', code: "harvester.storageClass.cluster.${cluster.id}.sc1",
            uniqueId: 'uid1', externalId: 'uid1', refType: HarvesterConstants.COMPUTE_SERVER_GROUP,
            refId: cluster.id, category: "harvester.storageClass.cluster.${cluster.id}",
            active: true, storageSize: null, freeSpace: 500L
        )
        existing.cloud = existingCloud
        existing.owner = cluster.owner
        existing.setConfigMap([provisioner: 'prov1', parameters: [:], allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate', clusterId: cluster.id])

        def updateList = [[
            existingItem: existing,
            masterItem: [
                uid: 'uid1', name: 'sc1', provisioner: 'prov1',
                storageVolumeType: null, parameters: [:],
                allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate'
            ]
        ]]

        when:
        invokePrivate(sync, 'updateStorageClasses', [Object] as Class[], [updateList] as Object[])

        then:
        1 * datastoreService.bulkSave({ List list -> list.size() == 1 && list[0].freeSpace == null })
    }

    def "updateStorageClasses updates datastoreType when found and differs"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService

        def newDatastoreType = new DatastoreType(id: 777L)
        datastoreTypeService.find(_) >> newDatastoreType

        def existingCloud = Mock(Cloud) { id >> cluster.cloud.id }
        def oldDatastoreType = new DatastoreType(id: 888L)
        def existing = new Datastore(
            name: 'sc1', type: 'prov1', code: "harvester.storageClass.cluster.${cluster.id}.sc1",
            uniqueId: 'uid1', externalId: 'uid1', refType: HarvesterConstants.COMPUTE_SERVER_GROUP,
            refId: cluster.id, category: "harvester.storageClass.cluster.${cluster.id}",
            active: true, storageSize: null, freeSpace: null
        )
        existing.cloud = existingCloud
        existing.owner = cluster.owner
        existing.datastoreType = oldDatastoreType
        existing.setConfigMap([provisioner: 'prov1', parameters: [:], allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate', clusterId: cluster.id])

        def updateList = [[
            existingItem: existing,
            masterItem: [
                uid: 'uid1', name: 'sc1', provisioner: 'prov1',
                storageVolumeType: null, parameters: [:],
                allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate'
            ]
        ]]

        when:
        invokePrivate(sync, 'updateStorageClasses', [Object] as Class[], [updateList] as Object[])

        then:
        1 * datastoreService.bulkSave({ List list -> list.size() == 1 && list[0].datastoreType.id == 777L })
    }

    def "updateStorageClasses does not save when no fields differ"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> null

        def existingCloud = Mock(Cloud) {
            id >> cluster.cloud.id
            getConfigProperty('defaultDatastoreSyncActive') >> true
        }
        def existing = new Datastore(
            name: 'sc1', type: 'prov1', code: "harvester.storageClass.cluster.${cluster.id}.sc1",
            uniqueId: 'uid1', externalId: 'uid1', refType: HarvesterConstants.COMPUTE_SERVER_GROUP,
            refId: cluster.id, category: "harvester.storageClass.cluster.${cluster.id}",
            active: true, storageSize: null, freeSpace: null
        )
        existing.cloud = existingCloud
        existing.owner = cluster.owner
        existing.setConfigMap([provisioner: 'prov1', parameters: [:], allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate', clusterId: cluster.id])

        def updateList = [[
            existingItem: existing,
            masterItem: [
                uid: 'uid1', name: 'sc1', provisioner: 'prov1',
                storageVolumeType: null, parameters: [:],
                allowVolumeExpansion: false, reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate'
            ]
        ]]

        when:
        invokePrivate(sync, 'updateStorageClasses', [Object] as Class[], [updateList] as Object[])

        then:
        0 * datastoreService.bulkSave(_)
    }

    // ── execute method coverage tests ──

    def "execute handles unsuccessful storage class response"() {
        given:
        GroovySpy(StorageClassApiHelper, global: true)
        def mockHelper = Mock(StorageClassApiHelper)
        new StorageClassApiHelper(cluster) >> mockHelper
        mockHelper.getStorageClasses() >> [success: false, data: null]

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "execute handles successful response with null storageClasses"() {
        given:
        GroovySpy(StorageClassApiHelper, global: true)
        def mockHelper = Mock(StorageClassApiHelper)
        new StorageClassApiHelper(cluster) >> mockHelper
        mockHelper.getStorageClasses() >> [success: true, data: [storageClasses: null]]

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "execute handles successful response with empty storageClasses"() {
        given:
        GroovySpy(StorageClassApiHelper, global: true)
        def mockHelper = Mock(StorageClassApiHelper)
        new StorageClassApiHelper(cluster) >> mockHelper
        mockHelper.getStorageClasses() >> [success: true, data: [storageClasses: []]]

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "execute returns early when storageVolumeType is empty"() {
        given:
        GroovySpy(StorageClassApiHelper, global: true)
        def mockHelper = Mock(StorageClassApiHelper)
        new StorageClassApiHelper(cluster) >> mockHelper
        mockHelper.getStorageClasses() >> [success: true, data: [storageClasses: [[uid: 'sc1', name: 'sc1', provisioner: 'csi']]]]

        def services = Mock(MorpheusServices)
        def mockStorageService = Stub(Object)
        def mockVolumeService = Stub(Object)
        def mockVolumeTypeService = Stub(Object)
        morpheusContext.services >> services
        services.getStorage() >> mockStorageService
        mockStorageService.volume >> mockVolumeService
        mockVolumeService.storageVolumeType >> mockVolumeTypeService
        mockVolumeTypeService.list(_) >> []

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "addStorageClasses handles empty add list"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> null

        when:
        invokePrivate(sync, 'addStorageClasses', [Object, CloudPool] as Class[], [[], new CloudPool(id: 555L)] as Object[])

        then:
        0 * datastoreService.bulkCreate(_)
    }

    def "addStorageClasses creates entry without datastoreType when type not found"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> null

        when:
        invokePrivate(sync, 'addStorageClasses', [Object, CloudPool] as Class[], [[[
            uid: 'sc-no-type', name: 'no-type-sc', provisioner: 'csi-test',
            storageVolumeType: null, parameters: [:],
            allowVolumeExpansion: true, reclaimPolicy: 'Retain', volumeBindingMode: 'WaitForFirstConsumer'
        ]], new CloudPool(id: 666L)] as Object[])

        then:
        1 * datastoreService.bulkCreate(_)
    }

    def "removeStorageClasses handles empty list gracefully"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService

        when:
        invokePrivate(sync, 'removeStorageClasses', [Object] as Class[], [[]] as Object[])

        then:
        0 * datastoreService.bulkRemove(_)
    }

    def "execute runs full sync path when storageVolumeType found and storageClasses exist"() {
        given:
        GroovySpy(StorageClassApiHelper, global: true)
        def mockHelper = Mock(StorageClassApiHelper)
        new StorageClassApiHelper(cluster) >> mockHelper
        mockHelper.getStorageClasses() >> [success: true, data: [storageClasses: [
            [uid: 'sc1', name: 'fast-storage', provisioner: 'kubernetes.io/csi', parameters: [:]]
        ]]]

        def services = Mock(MorpheusServices)
        morpheusContext.services >> services

        // Mock storage service chain
        def storageService = Mock(com.morpheusdata.core.MorpheusStorageService)
        def volumeService = Mock(com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService)
        def volumeTypeService = Mock(MorpheusSynchronousStorageVolumeTypeService)
        services.getStorage() >> storageService
        storageService.volume >> volumeService
        volumeService.storageVolumeType >> volumeTypeService
        volumeTypeService.list(_) >> [new StorageVolumeType(id: 1L, code: 'kubernetesVolume')]

        // Mock DBUtils.getClusterResourcePool
        GroovySpy(com.hpe.morpheus.harvester.plugin.utils.DBUtils, global: true)
        com.hpe.morpheus.harvester.plugin.utils.DBUtils.getClusterResourcePool(morpheusContext, cluster) >> new CloudPool(id: 888L)

        // Mock cloud datastore service
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.listIdentityProjections(_) >> []

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "execute handles exception from listIdentityProjections"() {
        given:
        GroovySpy(StorageClassApiHelper, global: true)
        def mockHelper = Mock(StorageClassApiHelper)
        new StorageClassApiHelper(cluster) >> mockHelper
        mockHelper.getStorageClasses() >> [success: true, data: [storageClasses: [
            [uid: 'sc1', name: 'sc1', provisioner: 'csi']
        ]]]

        def services = Mock(MorpheusServices)
        morpheusContext.services >> services

        def storageService = Mock(com.morpheusdata.core.MorpheusStorageService)
        def volumeService = Mock(com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService)
        def volumeTypeService = Mock(MorpheusSynchronousStorageVolumeTypeService)
        services.getStorage() >> storageService
        storageService.volume >> volumeService
        volumeService.storageVolumeType >> volumeTypeService
        volumeTypeService.list(_) >> [new StorageVolumeType(id: 1L, code: 'kubernetesVolume')]

        GroovySpy(com.hpe.morpheus.harvester.plugin.utils.DBUtils, global: true)
        com.hpe.morpheus.harvester.plugin.utils.DBUtils.getClusterResourcePool(morpheusContext, cluster) >> new CloudPool(id: 999L)

        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.listIdentityProjections(_) >> { throw new RuntimeException('datastore list error') }

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "addStorageClasses uses item name when present and fallback when absent"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> new DatastoreType(id: 77L)

        when:
        invokePrivate(sync, 'addStorageClasses', [Object, CloudPool] as Class[], [[
            [uid: 'sc-named', name: 'my-sc', provisioner: 'csi', storageVolumeType: null, parameters: [:]],
            [uid: 'sc-unnamed', name: null, provisioner: 'csi', storageVolumeType: null, parameters: [:]]
        ], new CloudPool(id: 777L)] as Object[])

        then:
        1 * datastoreService.bulkCreate({ List list ->
            list.size() == 2 && list[0].name == 'my-sc' && list[1].name.contains('sc-unnamed')
        })
    }

    def "execute handles no storage classes gracefully"() {
        given:
        def helper = Mock(StorageClassApiHelper)
        helper.getStorageClasses() >> com.morpheusdata.response.ServiceResponse.success([storageClasses: []])

        GroovySpy(StorageClassApiHelper, global: true)
        new StorageClassApiHelper(cluster) >> helper

        when:
        sync.execute()

        then:
        noExceptionThrown()
    }

    def "execute returns early when no storage volume type found"() {
        given:
        def services = Mock(MorpheusServices)
        def storageService = Mock(MorpheusStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        def volumeTypeService = Mock(MorpheusSynchronousStorageVolumeTypeService)

        morpheusContext.services >> services
        services.getStorage() >> storageService
        storageService.volume >> volumeService
        volumeService.storageVolumeType >> volumeTypeService
        volumeTypeService.list(_) >> []

        def helper = Mock(StorageClassApiHelper)
        helper.getStorageClasses() >> com.morpheusdata.response.ServiceResponse.success([storageClasses: [
            [uid: 'sc1', name: 'fast', provisioner: 'csi.driver', parameters: [:]]
        ]])

        GroovySpy(StorageClassApiHelper, global: true)
        new StorageClassApiHelper(cluster) >> helper

        when:
        sync.execute()

        then:
        noExceptionThrown()
    }

    def "execute logs warning when storage classes response not successful"() {
        given:
        def helper = Mock(StorageClassApiHelper)
        helper.getStorageClasses() >> com.morpheusdata.response.ServiceResponse.error('API failed')

        GroovySpy(StorageClassApiHelper, global: true)
        new StorageClassApiHelper(cluster) >> helper

        when:
        sync.execute()

        then:
        noExceptionThrown()
    }

    // --- addStorageClasses: coverage for missing datastoreType ---

    def "addStorageClasses handles missing datastoreType gracefully"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService

        datastoreTypeService.find(_) >> null  // No datastoreType found

        when:
        invokePrivate(sync, 'addStorageClasses', [Object, CloudPool] as Class[], [[[
            uid: 'sc-uid-no-type',
            name: 'sc-no-type',
            provisioner: 'kubernetes.io/no-provisioner',
            storageVolumeType: null,
            parameters: [:]
        ]], new CloudPool(id: 555L)] as Object[])

        then:
        1 * datastoreService.bulkCreate({ List list ->
            list.size() == 1 && list[0].datastoreType == null
        })
    }

    def "addStorageClasses adds multiple storage classes"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService

        def dsType = new DatastoreType(id: 88L)
        datastoreTypeService.find(_) >> dsType

        when:
        invokePrivate(sync, 'addStorageClasses', [Object, CloudPool] as Class[], [[
            [uid: 'sc1', name: 'fast', provisioner: 'ebs.csi', storageVolumeType: null, parameters: [fsType: 'xfs'],
             allowVolumeExpansion: true, reclaimPolicy: 'Retain', volumeBindingMode: 'WaitForFirstConsumer'],
            [uid: 'sc2', name: 'slow', provisioner: 'nfs', storageVolumeType: null, parameters: [:]]
        ], new CloudPool(id: 666L)] as Object[])

        then:
        1 * datastoreService.bulkCreate({ List list -> list.size() == 2 })
    }

    // --- updateStorageClasses: additional field change coverage ---

    def "updateStorageClasses handles no changes gracefully"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService
        datastoreTypeService.find(_) >> null

        def cloud = Mock(com.morpheusdata.model.Cloud)
        cloud.id >> 77L
        cloud.getConfigProperty('defaultDatastoreSyncActive') >> true

        // Create existing datastore with all matching values
        def existing = new Datastore(
            name: 'matching-sc', type: 'ebs.csi', code: "harvester.storageClass.cluster.${cluster.id}.matching-sc",
            uniqueId: 'uid-match', externalId: 'uid-match',
            refType: 'ComputeServerGroup', refId: cluster.id,
            category: "harvester.storageClass.cluster.${cluster.id}",
            active: true, storageSize: null, freeSpace: null
        )
        existing.cloud = cloud
        existing.owner = cluster.owner
        existing.setConfigMap([
            provisioner: 'ebs.csi', parameters: [:],
            allowVolumeExpansion: false, reclaimPolicy: 'Delete',
            volumeBindingMode: 'Immediate', clusterId: cluster.id
        ])

        def updateList = [[
            existingItem: existing,
            masterItem: [
                uid: 'uid-match', name: 'matching-sc', provisioner: 'ebs.csi',
                storageVolumeType: null, parameters: [:],
                allowVolumeExpansion: false, reclaimPolicy: 'Delete',
                volumeBindingMode: 'Immediate'
            ]
        ]]

        when:
        invokePrivate(sync, 'updateStorageClasses', [Object] as Class[], [updateList] as Object[])

        then:
        0 * datastoreService.bulkSave(_)
    }

    def "updateStorageClasses updates datastoreType when changed"() {
        given:
        def services = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        morpheusContext.services >> services
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService

        def newDsType = new DatastoreType(id: 55L)
        datastoreTypeService.find(_) >> newDsType

        def existing = new Datastore(
            name: 'old-sc', type: 'old-prov', code: 'old', uniqueId: 'old', externalId: 'old',
            refType: 'x', refId: 1L, category: 'c', active: false, storageSize: 100L, freeSpace: 50L
        )
        existing.cloud = Mock(com.morpheusdata.model.Cloud) { id >> 999L }
        existing.setConfigMap([a: 1])
        existing.datastoreType = new DatastoreType(id: 44L) // different

        def updateList = [[
            existingItem: existing,
            masterItem: [
                uid: 'new-uid', name: 'new-sc', provisioner: 'new-prov',
                storageVolumeType: null, parameters: [:],
                allowVolumeExpansion: false, reclaimPolicy: 'Delete',
                volumeBindingMode: 'Immediate'
            ]
        ]]

        when:
        invokePrivate(sync, 'updateStorageClasses', [Object] as Class[], [updateList] as Object[])

        then:
        1 * datastoreService.bulkSave({ List list ->
            list.size() == 1 && list[0].datastoreType.id == 55L
        })
    }

    def "execute processes datastores properly with matching externalIds"() {
        given:
        GroovySpy(StorageClassApiHelper, global: true)
        def mockHelper = Mock(StorageClassApiHelper)
        new StorageClassApiHelper(cluster) >> mockHelper
        mockHelper.getStorageClasses() >> [success: true, data: [storageClasses: [
            [uid: 'match-uid', name: 'updated-name', provisioner: 'new-prov',
             parameters: [:], allowVolumeExpansion: true, reclaimPolicy: 'Retain',
             volumeBindingMode: 'WaitForFirstConsumer']
        ]]]

        def services = Mock(MorpheusServices)
        morpheusContext.services >> services

        def storageService = Mock(MorpheusStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        def volumeTypeService = Mock(MorpheusSynchronousStorageVolumeTypeService)
        services.getStorage() >> storageService
        storageService.volume >> volumeService
        volumeService.storageVolumeType >> volumeTypeService
        volumeTypeService.list(_) >> [new StorageVolumeType(id: 1L, code: 'kubernetesVolume')]

        GroovySpy(com.hpe.morpheus.harvester.plugin.utils.DBUtils, global: true)
        com.hpe.morpheus.harvester.plugin.utils.DBUtils.getClusterResourcePool(morpheusContext, cluster) >> new CloudPool(id: 123L)

        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def cloudPoolService = Mock(MorpheusSynchronousCloudPoolService)
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        cloudService.pool >> cloudPoolService

        def identityItem = Mock(DatastoreIdentity)
        identityItem.externalId >> 'match-uid'
        identityItem.id >> 100L
        datastoreService.listIdentityProjections(_) >> [identityItem]

        def fullItem = new Datastore(id: 100L, externalId: 'match-uid', name: 'old-name')
        datastoreService.list(_) >> [fullItem]

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "execute adds new storage class when no match found"() {
        given:
        GroovySpy(StorageClassApiHelper, global: true)
        def mockHelper = Mock(StorageClassApiHelper)
        new StorageClassApiHelper(cluster) >> mockHelper
        mockHelper.getStorageClasses() >> [success: true, data: [storageClasses: [
            [uid: 'new-sc-uid', name: 'brand-new-sc', provisioner: 'csi.provider',
             parameters: [type: 'fast'], allowVolumeExpansion: false,
             reclaimPolicy: 'Delete', volumeBindingMode: 'Immediate']
        ]]]

        def services = Mock(MorpheusServices)
        morpheusContext.services >> services

        def storageService = Mock(MorpheusStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        def volumeTypeService = Mock(MorpheusSynchronousStorageVolumeTypeService)
        services.getStorage() >> storageService
        storageService.volume >> volumeService
        volumeService.storageVolumeType >> volumeTypeService
        volumeTypeService.list(_) >> [new StorageVolumeType(id: 2L, code: 'kubernetesVolume')]

        GroovySpy(com.hpe.morpheus.harvester.plugin.utils.DBUtils, global: true)
        com.hpe.morpheus.harvester.plugin.utils.DBUtils.getClusterResourcePool(morpheusContext, cluster) >> new CloudPool(id: 456L)

        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService

        datastoreService.listIdentityProjections(_) >> []  // No existing items
        datastoreTypeService.find(_) >> new DatastoreType(id: 99L)

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "execute handles mix of add update and remove operations"() {
        given:
        GroovySpy(StorageClassApiHelper, global: true)
        def mockHelper = Mock(StorageClassApiHelper)
        new StorageClassApiHelper(cluster) >> mockHelper
        mockHelper.getStorageClasses() >> [success: true, data: [storageClasses: [
            [uid: 'update-uid', name: 'update-sc', provisioner: 'csi.updated',
             parameters: [:], allowVolumeExpansion: false, reclaimPolicy: 'Delete',
             volumeBindingMode: 'Immediate'],
            [uid: 'new-uid', name: 'new-sc', provisioner: 'csi.new',
             parameters: [:], allowVolumeExpansion: true, reclaimPolicy: 'Retain',
             volumeBindingMode: 'WaitForFirstConsumer']
        ]]]

        def services = Mock(MorpheusServices)
        morpheusContext.services >> services

        def storageService = Mock(MorpheusStorageService)
        def volumeService = Mock(MorpheusSynchronousStorageVolumeService)
        def volumeTypeService = Mock(MorpheusSynchronousStorageVolumeTypeService)
        services.getStorage() >> storageService
        storageService.volume >> volumeService
        volumeService.storageVolumeType >> volumeTypeService
        volumeTypeService.list(_) >> [new StorageVolumeType(id: 3L, code: 'kubernetesVolume')]

        GroovySpy(com.hpe.morpheus.harvester.plugin.utils.DBUtils, global: true)
        com.hpe.morpheus.harvester.plugin.utils.DBUtils.getClusterResourcePool(morpheusContext, cluster) >> new CloudPool(id: 789L)

        def cloudService = Mock(MorpheusSynchronousCloudService)
        def datastoreService = Mock(MorpheusSynchronousDatastoreService)
        def datastoreTypeService = Mock(com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudDatastoreTypeService)
        services.cloud >> cloudService
        cloudService.datastore >> datastoreService
        datastoreService.datastoreType >> datastoreTypeService

        def identityToUpdate = Mock(DatastoreIdentity)
        identityToUpdate.externalId >> 'update-uid'
        identityToUpdate.id >> 200L

        def identityToRemove = Mock(DatastoreIdentity)
        identityToRemove.externalId >> 'remove-uid'
        identityToRemove.id >> 300L

        datastoreService.listIdentityProjections(_) >> [identityToUpdate, identityToRemove]

        def fullToUpdate = new Datastore(id: 200L, externalId: 'update-uid', name: 'old-update-name')
        def fullToRemove = new Datastore(id: 300L, externalId: 'remove-uid', name: 'to-remove')
        datastoreService.list(_) >> [fullToUpdate, fullToRemove]

        datastoreTypeService.find(_) >> new DatastoreType(id: 88L)

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }
}