package com.hpe.morpheus.harvester.plugin.sync

import com.hpe.morpheus.harvester.plugin.HpeHarvesterPlugin
import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterImageApiHelper
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.hpe.morpheus.harvester.plugin.utils.parsers.TemplateParser
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.StorageController
import com.morpheusdata.model.StorageControllerType
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Unroll

class VirtualImagesSyncSpec extends Specification {

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

    private def invokePrivateStatic(Class<?> clazz, String methodName, Class[] paramTypes, Object[] args) {
        def method = clazz.getDeclaredMethod(methodName, paramTypes)
        method.accessible = true
        method.invoke(null, args)
    }

    HpeHarvesterPlugin plugin
    ComputeServerGroup cluster
    MorpheusContext morpheusContext
    VirtualImagesSync sync

    def setup() {
        plugin = Mock(HpeHarvesterPlugin)
        cluster = Mock(ComputeServerGroup)
        morpheusContext = Mock(MorpheusContext)

        def cloud = typedMock('com.morpheusdata.model.Cloud')
        cloud.id >> 44L
        cloud.name >> 'cloud-a'
        cloud.regionCode >> 'region-a'
        cloud.account >> typedMock('com.morpheusdata.model.Account')

        def owner = typedMock('com.morpheusdata.model.Account')
        owner.id >> 9L

        plugin.morpheusContext >> morpheusContext
        cluster.id >> 555L
        cluster.name >> 'test-images-cluster'
        cluster.cloud >> cloud
        cluster.owner >> owner

        sync = new VirtualImagesSync(plugin, cluster)
    }

    def "constructor initializes all properties"() {
        expect:
        sync.plugin == plugin
        sync.cluster == cluster
        sync.morpheusContext == morpheusContext
    }

    def "execute handles service exception gracefully"() {
        given:
        def services = Mock(Object)
        def osTypeSvc = Mock(Object)
        morpheusContext.services >> services
        services.osType >> osTypeSvc
        osTypeSvc.list(_) >> { throw new RuntimeException('db down') }

        when:
        sync.execute()

        then:
        notThrown(Exception)
    }

    def "execute handles no templates response gracefully"() {
        given:
        def services = Mock(Object)
        def osTypeSvc = Mock(Object)
        morpheusContext.services >> services
        services.osType >> osTypeSvc
        osTypeSvc.list(_) >> []

        def spySync = Spy(sync)
        spySync.getAllVMTemplates(_, _) >> null

        when:
        spySync.execute()

        then:
        notThrown(Exception)
    }

    @Unroll
    def "mapOperatingSystemToPlatform maps '#os' to '#platform'"() {
        expect:
        invokePrivateStatic(VirtualImagesSync, 'mapOperatingSystemToPlatform',
                [String] as Class[], [os] as Object[]) == platform

        where:
        os             || platform
        'fedora'       || 'linux'
        'windows2019'  || 'windows'
        'macos'        || 'mac'
        'unknown-os'   || 'linux'
        null           || 'linux'
    }

    def "isUserDefinedTemplate treats every Harvester image as user defined"() {
        expect:
        invokePrivateStatic(VirtualImagesSync, 'isUserDefinedTemplate', [Map] as Class[],
                [[name: 'img', namespace: 'default']] as Object[])
        invokePrivateStatic(VirtualImagesSync, 'isUserDefinedTemplate', [Map] as Class[],
                [null] as Object[])
    }

    def "findOsTypeInList returns exact 64-bit match when available"() {
        given:
        def osTypes = [
            new Expando(category: 'centos', osVersion: '8', code: 'centos.8', name: 'CentOS 8'),
            new Expando(category: 'centos', osVersion: '8', code: 'centos.8.64', name: 'CentOS 8 64')
        ]

        when:
        def result = invokePrivateStatic(VirtualImagesSync, 'findOsTypeInList',
                [String, String, List] as Class[], ['centos', '8', osTypes] as Object[])

        then:
        result.name == 'CentOS 8 64'
    }

    def "findOsTypeInList supports centos-stream fallback"() {
        given:
        def osTypes = [
            new Expando(category: 'centos', osVersion: 'all', code: 'centos.64', name: 'CentOS Generic 64')
        ]

        when:
        def result = invokePrivateStatic(VirtualImagesSync, 'findOsTypeInList',
                [String, String, List] as Class[], ['centos-stream', '9', osTypes] as Object[])

        then:
        result.name == 'CentOS Generic 64'
    }

    def "findOsTypeInList supports category fallback rhel to redhat"() {
        given:
        def osTypes = [
            new Expando(category: 'redhat', osVersion: 'all', code: 'redhat.64', name: 'Red Hat 64')
        ]

        when:
        def result = invokePrivateStatic(VirtualImagesSync, 'findOsTypeInList',
                [String, String, List] as Class[], ['rhel', '9', osTypes] as Object[])

        then:
        result.name == 'Red Hat 64'
    }

    def "findOsTypeInList returns null for invalid inputs"() {
        expect:
        invokePrivateStatic(VirtualImagesSync, 'findOsTypeInList',
                [String, String, List] as Class[], [null, '1', []] as Object[]) == null
        invokePrivateStatic(VirtualImagesSync, 'findOsTypeInList',
                [String, String, List] as Class[], ['ubuntu', '22', []] as Object[]) == null
    }

    def "getOsTypeForOperatingSystem returns match and null paths"() {
        given:
        def osTypes = [
            new Expando(category: 'ubuntu', osVersion: 'all', code: 'ubuntu.64', name: 'Ubuntu 64')
        ]

        expect:
        invokePrivateStatic(VirtualImagesSync, 'getOsTypeForOperatingSystem',
                [String, String, List, String] as Class[], ['ubuntu', '22', osTypes, 'tmpl'] as Object[]).name == 'Ubuntu 64'
        invokePrivateStatic(VirtualImagesSync, 'getOsTypeForOperatingSystem',
                [String, String, List, String] as Class[], ['unknown', '1', osTypes, 'tmpl'] as Object[]) == null
        invokePrivateStatic(VirtualImagesSync, 'getOsTypeForOperatingSystem',
                [String, String, List, String] as Class[], [null, '1', osTypes, 'tmpl'] as Object[]) == null
    }

    @Unroll
    def "convertDisplayOrderToDeviceName returns expected for os=#os order=#order"() {
        expect:
        invokePrivateStatic(VirtualImagesSync, 'convertDisplayOrderToDeviceName',
                [String, int] as Class[], [os, order] as Object[]) == expected

        where:
        os         | order || expected
        'linux'    | 0     || '/dev/vda'
        'linux'    | 2     || '/dev/vdc'
        'windows'  | 0     || 'disk 1'
        'windows'  | 2     || 'disk 3'
        'linux'    | 99    || '/dev/vda'
        'windows'  | -1    || 'disk 1'
    }

    @Unroll
    def "mapBusTypeToStorageController maps #busType to #expected"() {
        expect:
        invokePrivateStatic(VirtualImagesSync, 'mapBusTypeToStorageController',
                [String] as Class[], [busType] as Object[]) == expected

        where:
        busType   || expected
        'virtio'  || HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO
        'scsi'    || HarvesterConstants.STORAGE_CONTROLLER_TYPE_SCSI
        'sata'    || HarvesterConstants.STORAGE_CONTROLLER_TYPE_SATA
        'ide'     || HarvesterConstants.STORAGE_CONTROLLER_TYPE_IDE
        'unknown' || HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO
        null      || HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO
    }

    def "parseStorageSize handles valid invalid and null inputs"() {
        when:
        def good = invokePrivate(sync, 'parseStorageSize', [String] as Class[], ['1Gi'] as Object[])
        def bad = invokePrivate(sync, 'parseStorageSize', [String] as Class[], ['BAD-SIZE'] as Object[])
        def empty = invokePrivate(sync, 'parseStorageSize', [String] as Class[], [null] as Object[])

        then:
        good > 0
        (bad == 0 || bad == null)
        empty == 0
    }

    private static Map harvesterImage(Map overrides = [:]) {
        Map image = [
            metadata: [name: 'image-abc12', namespace: 'default', uid: 'uid-img-1',
                       labels: [:], annotations: ['field.cattle.io/description': 'Ubuntu cloud image']],
            spec: [displayName: 'ubuntu-22.04-server-cloudimg-amd64.img', sourceType: 'download',
                   url: 'https://cloud-images.ubuntu.com/jammy.img'],
            status: [progress: 100, size: 700L * 1024 * 1024, virtualSize: 2361393152L,
                     storageClassName: 'longhorn-image-abc12',
                     conditions: [[type: 'Imported', status: 'True']]]
        ]
        overrides.each { k, v -> image[k] = v }
        return image
    }

    private VirtualImagesSync syncWithHelper(HarvesterImageApiHelper helper) {
        VirtualImagesSync spySync = Spy(VirtualImagesSync, constructorArgs: [plugin, cluster])
        spySync.getImageApiHelper() >> helper
        return spySync
    }

    def "getVMTemplatesWithPagination converts ready Harvester images into image records"() {
        given:
        def helper = Mock(HarvesterImageApiHelper)
        def spySync = syncWithHelper(helper)
        def notReady = harvesterImage(metadata: [name: 'image-pending', namespace: 'default', uid: 'uid-2'],
                status: [progress: 40, conditions: [[type: 'Imported', status: 'False']]])

        when:
        def result = spySync.getVMTemplatesWithPagination(null, 50)

        then:
        1 * helper.getVMImages(null, 50) >> ServiceResponse.success([
            items: [harvesterImage(), notReady],
            metadata: [continue: 'nxt', remainingItemCount: 1]
        ])
        1 * helper.getDefaultStorageClassName() >> 'harvester-longhorn'

        result.success
        result.hasMore
        result.continueToken == 'nxt'
        result.filteredCount == 1
        result.unfilteredCount == 2

        def record = result.vmTemplates[0]
        record.name == 'ubuntu-22.04-server-cloudimg-amd64.img'
        record.uid == 'uid-img-1'
        record.namespace == 'default'
        record.description == 'Ubuntu cloud image'
        record.operatingSystem == 'ubuntu'
        record.osVersion == '22.04'
        record.imageId == 'default/image-abc12'
        record.imageStorageClassName == 'longhorn-image-abc12'
        record.bootSource.size == '3Gi'

        def vol = record.volumes[0]
        vol.isBootDisk
        vol.type == HarvesterConstants.VOLUME_TYPE_PVC
        vol.sourceType == HarvesterConstants.VOLUME_SOURCE_HARVESTER_IMAGE
        vol.storageClassName == 'harvester-longhorn'
        vol.imageStorageClassName == 'longhorn-image-abc12'
        vol.imageId == 'default/image-abc12'
        vol.requestedStorage == '3Gi'
    }

    def "getVMTemplatesWithPagination returns failure when API response is unsuccessful"() {
        given:
        def helper = Mock(HarvesterImageApiHelper)
        def spySync = syncWithHelper(helper)

        when:
        def result = spySync.getVMTemplatesWithPagination('abc', 25)

        then:
        1 * helper.getVMImages('abc', 25) >> ServiceResponse.error('boom')
        !result.success
        result.message
    }

    def "getVMTemplatesWithPagination handles exceptions"() {
        given:
        def helper = Mock(HarvesterImageApiHelper)
        def spySync = syncWithHelper(helper)

        when:
        def result = spySync.getVMTemplatesWithPagination(null, 50)

        then:
        1 * helper.getVMImages(_, _) >> { throw new RuntimeException('api down') }
        !result.success
        result.message == 'api down'
    }

    def "toImageRecord prefers OS labels and falls back to the default storage class"() {
        when:
        def record = VirtualImagesSync.toImageRecord(harvesterImage(
                metadata: [name: 'image-win', namespace: 'images', uid: 'uid-w',
                           labels: ['harvesterhci.io/os-type': 'windows', 'harvesterhci.io/os-version': '2022']],
                spec: [displayName: 'my golden image']), null)

        then:
        record.operatingSystem == 'windows'
        record.osVersion == '2022'
        record.imageId == 'images/image-win'
        record.volumes[0].storageClassName == HarvesterConstants.DEFAULT_STORAGE_CLASS
    }

    @Unroll
    def "parseImageOperatingSystem maps display name '#name' to #os / #version"() {
        expect:
        def parsed = VirtualImagesSync.parseImageOperatingSystem([:], name)
        parsed.osType == os
        parsed.version.toString() == version

        where:
        name                                   || os        | version
        'ubuntu-22.04-server-cloudimg-amd64'   || 'ubuntu'  | '22.04'
        'Rocky-9-GenericCloud'                 || 'rocky'   | '9'
        'openSUSE-Leap-15.6'                   || 'opensuse'| '15'
        'SLES15-SP6-Minimal'                   || 'suse'    | '15'
        'my custom appliance'                  || 'unknown' | 'all'
        null                                   || 'unknown' | 'all'
    }

    @Unroll
    def "bytesToGiString rounds #bytes up to #expected"() {
        expect:
        VirtualImagesSync.bytesToGiString(bytes) == expected

        where:
        bytes                       || expected
        null                        || ''
        0L                          || ''
        1L                          || '1Gi'
        1073741824L                 || '1Gi'
        1073741825L                 || '2Gi'
        10L * 1073741824L           || '10Gi'
    }

    def "getAllVMTemplates handles multiple pages and truncation"() {
        given:
        def spySync = Spy(sync)

        1 * spySync.getVMTemplatesWithPagination(null, 10) >> [
            success: true,
            vmTemplates: [[name: 'a']],
            filteredCount: 1,
            continueToken: 'next',
            hasMore: true
        ]
        1 * spySync.getVMTemplatesWithPagination('next', 10) >> [
            success: true,
            vmTemplates: [[name: 'b']],
            filteredCount: 1,
            continueToken: 'next2',
            hasMore: true
        ]

        when:
        def result = spySync.getAllVMTemplates(10, 2)

        then:
        result.success
        result.vmTemplates.size() == 2
        result.totalPages == 2
        result.truncated
    }

    def "getAllVMTemplates collects errors for failed page"() {
        given:
        def spySync = Spy(sync)

        1 * spySync.getVMTemplatesWithPagination(null, 20) >> [
            success: false,
            message: 'page failed',
            vmTemplates: [],
            filteredCount: 0,
            continueToken: '',
            hasMore: false
        ]

        when:
        def result = spySync.getAllVMTemplates(20, 5)

        then:
        !result.success
        result.errors[0].contains('page failed')
    }

    def "getAllVMTemplates handles exceptions"() {
        given:
        def spySync = Spy(sync)
        1 * spySync.getVMTemplatesWithPagination(_, _) >> { throw new RuntimeException('pagination error') }

        when:
        def result = spySync.getAllVMTemplates(10, 2)

        then:
        !result.success
        result.errors[0] == 'pagination error'
    }

    def "removeVirtualImages bulk removes when list provided"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def viService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        morpheusContext.services >> services
        services.virtualImage >> viService

        when:
        invokePrivate(sync, 'removeVirtualImages', [Object] as Class[], [[1, 2]] as Object[])

        then:
        1 * viService.bulkRemove([1, 2])
    }

    def "removeVirtualImages ignores null list"() {
        when:
        invokePrivate(sync, 'removeVirtualImages', [Object] as Class[], [null] as Object[])

        then:
        notThrown(Exception)
    }

    def "processVolumeDetailsForSync filters cloudinit and enriches pvc storage class"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def storageService = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def volumeService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def volumeTypeService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeTypeService')
        morpheusContext.services >> services
        services.storage >> storageService
        storageService.volume >> volumeService
        volumeService.storageVolumeType >> volumeTypeService

        def sourceVolume = Mock(com.morpheusdata.model.StorageVolume)
        sourceVolume.datastore >> typedMock('com.morpheusdata.model.projection.DatastoreIdentity')
        sourceVolume.datastore.name >> 'fast-storage'
        volumeTypeService.find(_) >> Mock(StorageVolumeType)
        volumeService.find(_) >> sourceVolume

        def input = [
            [type: HarvesterConstants.VOLUME_TYPE_CLOUDINIT, diskName: 'cloudinit', diskIndex: 2],
            [
                type: HarvesterConstants.VOLUME_TYPE_PVC,
                diskName: 'root',
                diskIndex: 1,
                isBootDisk: true,
                sourceType: 'pvc',
                sourcePvcName: 'src-pvc',
                sourcePvcNamespace: 'ns',
                storageClassName: null,
                requestedStorage: '20Gi',
                busType: 'virtio',
                pvcName: 'pvc-root'
            ],
            [
                type: HarvesterConstants.VOLUME_TYPE_DATAVOLUME,
                diskName: 'data',
                diskIndex: 0,
                isBootDisk: false,
                sourceType: HarvesterConstants.VOLUME_TYPE_DATASOURCE,
                sourceRefName: 'ds-ref',
                requestedStorage: '10Gi',
                storageClassName: 'slow',
                busType: 'scsi',
                dataVolumeName: 'dv-data'
            ]
        ]

        when:
        def result = invokePrivate(sync, 'processVolumeDetailsForSync', [List] as Class[], [input] as Object[])

        then:
        result.size() == 2
        result[0].isBootDisk
        result[0].storageClassName == 'fast-storage'
        result[0].displayOrder == 0
        result[1].displayOrder == 1
        result[1].volumeType == HarvesterConstants.VOLUME_TYPE_DATASOURCE
        result[1].storageController == HarvesterConstants.STORAGE_CONTROLLER_TYPE_SCSI
    }

    def "createImageLocation builds expected location fields"() {
        given:
        def image = new VirtualImage(id: 101L, name: 'img-a', externalId: 'uid-1')
        def template = [uid: 'uid-1', namespace: 'ns-a']

        when:
        VirtualImageLocation location = invokePrivate(sync, 'createImageLocation',
            [VirtualImage, Map, String] as Class[],
            [image, template, 'loc-code'] as Object[])

        then:
        location.virtualImage == image
        location.code == 'loc-code'
        location.internalId == 'uid-1'
        location.externalId == 'uid-1'
        location.refType == HarvesterConstants.COMPUTE_SERVER_GROUP
        location.refId == 555L
        location.imageFolder == 'ns-a'
        location.systemImage == false
    }

    def "updateImageLocation updates changed fields and returns true"() {
        given:
        def location = new VirtualImageLocation(
            internalId: 'old', externalId: 'old', uuid: 'old', refType: 'old', refId: 1L,
            imageRegion: 'old-region', imageFolder: 'old-ns', systemImage: true, owner: '2'
        )

        when:
        def updated = invokePrivate(sync, 'updateImageLocation',
            [VirtualImageLocation, Map] as Class[],
            [location, [uid: 'new', namespace: 'new-ns']] as Object[])

        then:
        updated
        location.internalId == 'new'
        location.externalId == 'new'
        location.uuid == 'new'
        location.refType == HarvesterConstants.COMPUTE_SERVER_GROUP
        location.refId == 555L
        location.imageFolder == 'new-ns'
        !location.systemImage
        location.owner == '9'
    }

    def "updateImageLocation returns false when unchanged"() {
        given:
        def location = new VirtualImageLocation(
            internalId: 'uid', externalId: 'uid', uuid: 'uid',
            refType: HarvesterConstants.COMPUTE_SERVER_GROUP, refId: 555L,
            imageRegion: 'region-a', imageFolder: 'ns-a', systemImage: false, owner: '9'
        )

        when:
        def updated = invokePrivate(sync, 'updateImageLocation',
            [VirtualImageLocation, Map] as Class[],
            [location, [uid: 'uid', namespace: 'ns-a']] as Object[])

        then:
        !updated
    }

    def "updateExistingController updates all divergent fields"() {
        given:
        def type = new StorageControllerType(id: 99L, code: HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO,
                name: 'Virtio', displayOrder: 3, description: 'virtio')
        def controller = new StorageController(
            id: 7L,
            type: new StorageControllerType(id: 1L),
            name: 'Old',
            displayOrder: 1,
            description: 'old',
            active: false,
            busNumber: null,
            unitNumber: null
        )

        when:
        def updated = invokePrivate(sync, 'updateExistingController',
            [StorageController, StorageControllerType] as Class[],
            [controller, type] as Object[])

        then:
        updated
        controller.type.id == 99L
        controller.name == 'Virtio'
        controller.displayOrder == 3
        controller.description == 'virtio'
        controller.active
        controller.busNumber == '0'
        controller.unitNumber == '0'
    }

    def "updateExistingController returns false when type is null"() {
        expect:
        !invokePrivate(sync, 'updateExistingController',
            [StorageController, StorageControllerType] as Class[],
            [new StorageController(id: 1L), null] as Object[])
    }

    def "syncVolumesForVirtualImage exits early when volume types are missing"() {
        given:
        def staleVolume = new com.morpheusdata.model.StorageVolume(name: 'old-vol')
        def image = new VirtualImage(id: 300L, name: 'img-cleanup', volumes: [staleVolume])

        when:
        invokePrivate(sync, 'syncVolumesForVirtualImage',
            [VirtualImage, List, Map, Map] as Class[],
            [image, [[name: 'new-vol']], [:], [:]] as Object[])

        then:
        notThrown(Exception)
        image.volumes*.name == ['old-vol']
    }

    def "addVirtualImages creates image and updates location for template without volumes"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def storageSvc = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def volumeSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def volTypeSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeTypeService')
        def viSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def viLocationSvc = Mock(Object)
        def asyncRoot = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def asyncViSvc = typedMock('com.morpheusdata.core.MorpheusVirtualImageService')

        morpheusContext.services >> services
        morpheusContext.async >> asyncRoot
        services.storage >> storageSvc
        storageSvc.volume >> volumeSvc
        volumeSvc.storageVolumeType >> volTypeSvc
        services.virtualImage >> viSvc
        viSvc.location >> viLocationSvc
        asyncRoot.virtualImage >> asyncViSvc

        volTypeSvc.find(_) >>> [
            new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC),
            new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME),
            new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATASOURCE)
        ]

        def createdImage = new VirtualImage(id: 1001L, name: 'tmpl-1', externalId: 'uid-1')
        asyncViSvc.create(_ as VirtualImage) >> io.reactivex.rxjava3.core.Single.just(createdImage)
        viSvc.get(1001L) >> createdImage

        def existingLocation = new VirtualImageLocation(
            code: 'harvester.image.location.555.uid-1',
            internalId: 'uid-1',
            externalId: 'uid-1',
            uuid: 'uid-1',
            refType: HarvesterConstants.COMPUTE_SERVER_GROUP,
            refId: 555L,
            imageRegion: 'region-a',
            imageFolder: 'ns1',
            systemImage: false,
            owner: '9'
        )
        viLocationSvc.find(_) >> existingLocation

        def ubuntuOsType = typedMock('com.morpheusdata.model.OsType')
        ubuntuOsType.category >> 'ubuntu'
        ubuntuOsType.osVersion >> 'all'
        ubuntuOsType.code >> 'ubuntu.64'
        ubuntuOsType.name >> 'Ubuntu 64'
        def osTypes = [ubuntuOsType]
        def templates = [[
            name: 'tmpl-1', uid: 'uid-1', namespace: 'ns1', description: 'desc',
            operatingSystem: 'ubuntu', osVersion: '22', memory: '2Gi',
            bootSource: [size: '10Gi'], templateType: 'custom', rawTemplate: [metadata: [labels: [:], annotations: [:]]],
            volumes: null
        ]]

        when:
        invokePrivate(sync, 'addVirtualImages', [Object, Object, Object] as Class[], [templates, osTypes, [:]] as Object[])

        then:
        notThrown(Exception)
    }

    def "updateVirtualImages updates changed fields and bulk saves"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def storageSvc = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def volumeSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def volTypeSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeTypeService')
        def viSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def viLocationSvc = Mock(Object)

        morpheusContext.services >> services
        services.storage >> storageSvc
        storageSvc.volume >> volumeSvc
        volumeSvc.storageVolumeType >> volTypeSvc
        services.virtualImage >> viSvc
        viSvc.location >> viLocationSvc

        volTypeSvc.find(_) >>> [
            new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC),
            new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME),
            new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATASOURCE)
        ]

        def oldOwner = typedMock('com.morpheusdata.model.Account')
        oldOwner.id >> 1L
        def oldAccount = typedMock('com.morpheusdata.model.Account')
        oldAccount.id >> 2L

        def image = new VirtualImage(
            id: 2002L,
            name: 'old-name',
            code: 'old-code',
            description: 'old-desc',
            platform: 'windows',
            interfaceName: 'Ethernet',
            owner: oldOwner,
            account: oldAccount,
            uniqueId: 'old-uid',
            externalId: 'old-ext',
            internalId: 'old-int',
            minRam: 1L,
            minDisk: 1L,
            imageType: 'iso',
            visibility: 'public',
            systemImage: true,
            isPublic: true,
            status: 'Inactive',
            deleted: true,
            userDefined: false,
            userUploaded: true,
            folderName: 'old-ns'
        )

        viSvc.get(2002L) >> image
        viLocationSvc.find(_) >> new VirtualImageLocation(
            code: 'harvester.image.location.555.uid-2',
            internalId: 'uid-2', externalId: 'uid-2', uuid: 'uid-2',
            refType: HarvesterConstants.COMPUTE_SERVER_GROUP, refId: 555L,
            imageRegion: 'region-a', imageFolder: 'ns2', systemImage: false, owner: '9'
        )

        def ubuntuOsType = typedMock('com.morpheusdata.model.OsType')
        ubuntuOsType.category >> 'ubuntu'
        ubuntuOsType.osVersion >> 'all'
        ubuntuOsType.code >> 'ubuntu.64'
        ubuntuOsType.name >> 'Ubuntu 64'

        def updateList = [[
            existingItem: image,
            masterItem: [
                name: 'new-name', uid: 'uid-2', namespace: 'ns2', description: 'new-desc',
                operatingSystem: 'ubuntu', osVersion: '22', memory: '4Gi', bootSource: [size: '30Gi'],
                templateType: 'custom', displayName: 'New', iconClass: 'icon', workloadProfile: 'server',
                flavor: 'medium', parameters: [], rawTemplate: [metadata: [labels: [:], annotations: [:]]],
                volumes: null
            ]
        ]]
        def osTypes = [ubuntuOsType]

        when:
        invokePrivate(sync, 'updateVirtualImages', [Object, Object, Object] as Class[], [updateList, osTypes, [:]] as Object[])

        then:
        1 * viSvc.bulkSave(_ as List)
        image.name == 'new-name'
        image.platform == 'linux'
        image.interfaceName == 'eth0'
        image.status == 'Active'
    }

    def "addVolumesToVirtualImageWithTypes skips invalid controller and missing datastore volumes"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def cloudSvc = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService')
        def datastoreSvc = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService')
        def storageSvc = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def storageVolSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def controllerSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageControllerService')
        def viSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def asyncRoot = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def asyncStorage = typedMock('com.morpheusdata.core.MorpheusStorageService')
        def asyncStorageVolSvc = typedMock('com.morpheusdata.core.MorpheusStorageVolumeService')

        morpheusContext.services >> services
        morpheusContext.async >> asyncRoot
        services.cloud >> cloudSvc
        cloudSvc.datastore >> datastoreSvc
        services.storage >> storageSvc
        storageSvc.volume >> storageVolSvc
        storageSvc.controller >> controllerSvc
        services.virtualImage >> viSvc
        asyncRoot.storage >> asyncStorage
        asyncStorage.volume >> asyncStorageVolSvc

        def virtioType = new StorageControllerType(id: 71L, code: HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO, name: 'Virtio')
        def scsiType = new StorageControllerType(id: 72L, code: HarvesterConstants.STORAGE_CONTROLLER_TYPE_SCSI, name: 'SCSI')
        datastoreSvc.find(_) >> null
        storageVolSvc.find(_) >> null
        controllerSvc.find(_) >> null

        def image = new VirtualImage(id: 3003L, name: 'img-vols')
        def imageOsType = typedMock('com.morpheusdata.model.OsType')
        imageOsType.platform >> 'linux'
        image.osType = imageOsType
        viSvc.get(3003L) >> image

        def processedVolumes = [
            [name: 'disk-unsupported-controller', dataVolumeName: 'dv-skip', volumeType: HarvesterConstants.VOLUME_TYPE_DATAVOLUME,
             storageController: 'unsupported-bus', storageClassName: 'fast-storage', requestedStorage: '10Gi', displayOrder: 1],
            [name: 'disk-no-datastore', dataVolumeName: 'dv-no-ds', volumeType: HarvesterConstants.VOLUME_TYPE_DATAVOLUME,
             storageController: HarvesterConstants.STORAGE_CONTROLLER_TYPE_SCSI, storageClassName: 'missing-storage', requestedStorage: '5Gi', displayOrder: 2]
        ]
        def volumeTypes = [
            (HarvesterConstants.VOLUME_TYPE_DATAVOLUME): new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME),
            default: new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME)
        ]
        def controllerTypeMap = [
            (HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO): virtioType,
            (HarvesterConstants.STORAGE_CONTROLLER_TYPE_SCSI): scsiType
        ]

        when:
        invokePrivate(sync, 'addVolumesToVirtualImageWithTypes',
            [VirtualImage, List, Map, Map] as Class[],
            [image, processedVolumes, volumeTypes, controllerTypeMap] as Object[])

        then:
        0 * asyncStorageVolSvc.create(_, _)
        notThrown(Exception)
    }

    def "updateVolumesForVirtualImageWithTypes handles non-updatable entries without async writes"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def cloudSvc = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService')
        def datastoreSvc = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService')
        def storageSvc = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def storageVolSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def controllerSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageControllerService')
        def viSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def asyncRoot = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def asyncStorage = typedMock('com.morpheusdata.core.MorpheusStorageService')
        def asyncStorageVolSvc = typedMock('com.morpheusdata.core.MorpheusStorageVolumeService')

        morpheusContext.services >> services
        morpheusContext.async >> asyncRoot
        services.cloud >> cloudSvc
        cloudSvc.datastore >> datastoreSvc
        services.storage >> storageSvc
        storageSvc.volume >> storageVolSvc
        storageSvc.controller >> controllerSvc
        services.virtualImage >> viSvc
        asyncRoot.storage >> asyncStorage
        asyncStorage.volume >> asyncStorageVolSvc

        datastoreSvc.find(_) >> null

        def virtioType = new StorageControllerType(id: 90L, code: HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO, name: 'Virtio', displayOrder: 0)
        controllerSvc.find(_) >> null

        def existingVol = new StorageVolume(
            id: 1L,
            name: 'dv-existing',
            maxStorage: 1L,
            displayOrder: 5,
            unitNumber: '9',
            deviceName: '/dev/vdz',
            deviceDisplayName: '/dev/vdz',
            cloudId: null,
            refType: null,
            refId: null,
            type: new StorageVolumeType(id: 1L, code: 'wrong-type'),
            controller: new StorageController(id: 1L)
        )

        def image = new VirtualImage(id: 4004L, name: 'img-update', volumes: [existingVol])
        def imageOsType = typedMock('com.morpheusdata.model.OsType')
        imageOsType.platform >> 'linux'
        image.osType = imageOsType
        viSvc.get(4004L) >> image

        def volumeTypes = [
            (HarvesterConstants.VOLUME_TYPE_DATAVOLUME): new StorageVolumeType(id: 2L, code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME),
            default: new StorageVolumeType(id: 2L, code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME)
        ]
        def controllerTypeMap = [
            (HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO): virtioType
        ]

        def processed = [
            [name: 'root', dataVolumeName: 'dv-existing', volumeType: HarvesterConstants.VOLUME_TYPE_DATAVOLUME,
               storageController: HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO, storageClassName: 'missing-storage',
             requestedStorage: '20Gi', displayOrder: 0, isBootDisk: true],
            [name: 'data', dataVolumeName: 'dv-new', volumeType: HarvesterConstants.VOLUME_TYPE_DATAVOLUME,
               storageController: 'unsupported-bus', storageClassName: 'missing-storage',
             requestedStorage: '10Gi', displayOrder: 1, isBootDisk: false]
        ]

        when:
        invokePrivate(sync, 'updateVolumesForVirtualImageWithTypes',
            [VirtualImage, List, Map, Map] as Class[],
            [image, processed, volumeTypes, controllerTypeMap] as Object[])

        then:
        1 * storageVolSvc.bulkSave(_ as List)
        0 * asyncStorageVolSvc.create(_, _)
        existingVol.name == 'dv-existing'
        existingVol.unitNumber == '0'
    }

    def "syncStorageControllers ignores unsupported controllers and returns empty remove list"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def storageSvc = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def controllerSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageControllerService')
        def viSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def asyncRoot = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def asyncStorage = typedMock('com.morpheusdata.core.MorpheusStorageService')
        def asyncControllerSvc = typedMock('com.morpheusdata.core.MorpheusStorageControllerService')

        morpheusContext.services >> services
        morpheusContext.async >> asyncRoot
        services.storage >> storageSvc
        storageSvc.controller >> controllerSvc
        services.virtualImage >> viSvc
        asyncRoot.storage >> asyncStorage
        asyncStorage.controller >> asyncControllerSvc

        def virtioCode = HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO
        def virtioType = new StorageControllerType(id: 11L, code: virtioCode, name: 'Virtio', displayOrder: 0, description: 'virtio')
        def typeMap = [(virtioCode): virtioType]

        controllerSvc.find(_) >> null

        def image = new VirtualImage(id: 5005L, name: 'img-ctrl')
        image.controllers = [[id: 1L], [id: 2L]]
        viSvc.get(5005L) >> image
        controllerSvc.list(_) >> []

        def vmVolumes = [
            [name: 'root', storageController: 'unsupported-controller'],
            [name: 'ignored', storageController: 'unknown-controller']
        ]

        when:
        def result = invokePrivate(sync, 'syncStorageControllers',
            [List, Map, VirtualImage] as Class[], [vmVolumes, typeMap, image] as Object[])

        then:
        0 * asyncControllerSvc.create(_, _)
        0 * asyncControllerSvc.bulkSave(_)
        result.removeList == []
    }

    def "updateExistingVolumeWithType updates key fields and returns true"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def cloudSvc = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService')
        def datastoreSvc = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService')
        def storageSvc = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def storageVolSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def controllerSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageControllerService')

        morpheusContext.services >> services
        services.cloud >> cloudSvc
        cloudSvc.datastore >> datastoreSvc
        services.storage >> storageSvc
        storageSvc.volume >> storageVolSvc
        storageSvc.controller >> controllerSvc

        def datastore = new Datastore(id: 700L, name: 'fast-storage')
        def sourceVolume = new StorageVolume(id: 701L, name: 'src-pvc')
        def targetControllerType = new StorageControllerType(id: 702L, code: HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO, name: 'Virtio', displayOrder: 0)
        def targetController = new StorageController(id: 703L, type: targetControllerType, uniqueId: "${HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO}.image.6006")

        datastoreSvc.find(_) >> datastore
        storageVolSvc.find(_) >> sourceVolume
        controllerSvc.find(_) >> targetController

        def existing = new StorageVolume(
            id: 800L,
            name: 'old-name',
            maxStorage: 1L,
            displayOrder: 9,
            unitNumber: '9',
            deviceName: '/dev/vdz',
            deviceDisplayName: '/dev/vdz',
            cloudId: null,
            refType: null,
            refId: null,
            sourceId: null,
            type: new StorageVolumeType(id: 1L, code: 'old-type'),
            controller: new StorageController(id: 1L)
        )

        def image = new VirtualImage(id: 6006L, name: 'img-update')
        def imageOsType = typedMock('com.morpheusdata.model.OsType')
        imageOsType.platform >> 'linux'
        image.osType = imageOsType

        def processed = [
            name: 'root',
            dataVolumeName: 'dv-root',
            volumeType: HarvesterConstants.VOLUME_TYPE_DATAVOLUME,
            sourceType: HarvesterConstants.VOLUME_TYPE_DATAVOLUME,
            sourcePvcName: 'src-pvc',
            sourcePvcNamespace: 'ns1',
            storageClassName: 'fast-storage',
            requestedStorage: '20Gi',
            displayOrder: 0,
            isBootDisk: true,
            storageController: HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO
        ]
        def volumeType = new StorageVolumeType(id: 2L, code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME)

        when:
        def updated = invokePrivate(sync, 'updateExistingVolumeWithType',
            [StorageVolume, Map, StorageVolumeType, StorageControllerType, VirtualImage, int] as Class[],
            [existing, processed, volumeType, targetControllerType, image, 0] as Object[])

        then:
        updated
        existing.name == 'dv-root'
        existing.maxStorage > 1L
        existing.displayOrder == 0
        existing.unitNumber == '0'
        existing.deviceName == '/dev/vda'
        existing.deviceDisplayName == '/dev/vda'
        existing.refType == HarvesterConstants.DATASTORE
        existing.refId == 700L
        existing.cloudId == 44L
        existing.sourceId == '701'
        existing.type.id == 2L
        existing.controller.id == 703L
    }

    def "syncImageLocationForVirtualImage handles create failure gracefully"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def viSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def locationSvc = new Expando()
        morpheusContext.services >> services
        services.virtualImage >> viSvc
        viSvc.location >> locationSvc

        def image = new VirtualImage(id: 7007L, name: 'img-loc', externalId: 'uid-loc')
        image.imageLocations = []

        locationSvc.find = { Object q -> null }
        locationSvc.create = { VirtualImageLocation loc, Object cloud -> null }

        when:
        invokePrivate(sync, 'syncImageLocationForVirtualImage',
            [VirtualImage, Map] as Class[],
            [image, [uid: 'uid-loc', namespace: 'ns-x']] as Object[])

        then:
        notThrown(Exception)
    }

    def "syncImageLocationForVirtualImage handles location lookup exceptions gracefully"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def viSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def locationSvc = new Expando()
        morpheusContext.services >> services
        services.virtualImage >> viSvc
        viSvc.location >> locationSvc

        def image = new VirtualImage(id: 8008L, name: 'img-update-loc', externalId: 'uid-new')
        def existing = new VirtualImageLocation(
            code: 'harvester.image.location.555.uid-new',
            internalId: 'old', externalId: 'old', uuid: 'old',
            refType: 'x', refId: 1L, imageRegion: 'old', imageFolder: 'old',
            systemImage: true, owner: '1'
        )
        locationSvc.find = { Object q -> throw new RuntimeException('lookup failed') }

        when:
        invokePrivate(sync, 'syncImageLocationForVirtualImage',
            [VirtualImage, Map] as Class[],
            [image, [uid: 'uid-new', namespace: 'ns-new']] as Object[])

        then:
        notThrown(Exception)
    }

    def "syncVolumesForVirtualImage removes stale volumes and cleans up asynchronously"() {
        given:
        def asyncRoot = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def asyncStorage = typedMock('com.morpheusdata.core.MorpheusStorageService')
        def asyncVol = typedMock('com.morpheusdata.core.MorpheusStorageVolumeService')
        morpheusContext.async >> asyncRoot
        asyncRoot.storage >> asyncStorage
        asyncStorage.volume >> asyncVol

        def keep = new StorageVolume(name: 'dv-keep')
        def stale = new StorageVolume(name: 'dv-stale')
        def image = new VirtualImage(id: 9009L, name: 'img-sync', volumes: [keep, stale])

        def processed = [[
            name: 'root',
            dataVolumeName: 'dv-keep',
            volumeType: HarvesterConstants.VOLUME_TYPE_DATAVOLUME,
            storageController: 'unsupported-controller',
            displayOrder: 0
        ]]
        def volumeTypes = [
            (HarvesterConstants.VOLUME_TYPE_DATAVOLUME): new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME),
            default: new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME)
        ]

        when:
        invokePrivate(sync, 'syncVolumesForVirtualImage',
            [VirtualImage, List, Map, Map] as Class[],
            [image, processed, volumeTypes, [:]] as Object[])

        then:
        1 * asyncVol.remove({ List vols -> vols*.name == ['dv-stale'] }, image) >> io.reactivex.rxjava3.core.Single.just(true)
        image.volumes*.name == ['dv-keep']
    }

    def "findOsTypeInList prefers centos-stream exact stream code before fallback"() {
        given:
        def osTypes = [
            new Expando(category: 'centos', osVersion: '9', code: 'centos.9', name: 'CentOS 9'),
            new Expando(category: 'centos', osVersion: '9', code: 'centos.stream.9.64', name: 'CentOS Stream 9 64')
        ]

        when:
        def result = invokePrivateStatic(VirtualImagesSync, 'findOsTypeInList',
            [String, String, List] as Class[], ['centos-stream', '9', osTypes] as Object[])

        then:
        result.name == 'CentOS Stream 9 64'
    }

    def "getOsTypeForOperatingSystem uses all-version fallback when version missing"() {
        given:
        def osTypes = [new Expando(category: 'ubuntu', osVersion: 'all', code: 'ubuntu.64', name: 'Ubuntu Generic')]

        when:
        def result = invokePrivateStatic(VirtualImagesSync, 'getOsTypeForOperatingSystem',
            [String, String, List, String] as Class[], ['ubuntu', null, osTypes, 'tmpl-x'] as Object[])

        then:
        result.name == 'Ubuntu Generic'
    }

    def "syncStorageControllers updates existing required controller and returns obsolete remove list"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def storageSvc = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def controllerSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageControllerService')
        def viSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def asyncRoot = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def asyncStorage = typedMock('com.morpheusdata.core.MorpheusStorageService')
        def asyncControllerSvc = typedMock('com.morpheusdata.core.MorpheusStorageControllerService')

        morpheusContext.services >> services
        morpheusContext.async >> asyncRoot
        services.storage >> storageSvc
        storageSvc.controller >> controllerSvc
        services.virtualImage >> viSvc
        asyncRoot.storage >> asyncStorage
        asyncStorage.controller >> asyncControllerSvc

        def virtioType = new StorageControllerType(id: 321L, code: HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO, name: 'Virtio', displayOrder: 0, description: 'virtio')
        def existingVirtio = new StorageController(
            id: 11L,
            uniqueId: "${HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO}.image.10010",
            type: new StorageControllerType(id: 2L, code: HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO),
            name: 'OldName',
            active: false,
            busNumber: null,
            unitNumber: null,
            displayOrder: 99,
            description: 'old'
        )
        def obsolete = new StorageController(
            id: 12L,
            uniqueId: "${HarvesterConstants.STORAGE_CONTROLLER_TYPE_SATA}.image.10010",
            type: new StorageControllerType(id: 33L, code: HarvesterConstants.STORAGE_CONTROLLER_TYPE_SATA)
        )

        controllerSvc.find(_) >> existingVirtio
        def image = new VirtualImage(id: 10010L, name: 'img-ctrl-update')
        image.controllers = [[id: 11L], [id: 12L]]
        viSvc.get(10010L) >> image
        controllerSvc.list(_) >> [existingVirtio, obsolete]

        def vmVolumes = [[name: 'root', storageController: HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO]]
        def typeMap = [(HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO): virtioType]

        when:
        def result = invokePrivate(sync, 'syncStorageControllers',
            [List, Map, VirtualImage] as Class[], [vmVolumes, typeMap, image] as Object[])

        then:
        0 * asyncControllerSvc.create(_, _)
        1 * asyncControllerSvc.bulkSave({ List ctrls -> ctrls*.id == [11L] }) >> io.reactivex.rxjava3.core.Single.just(true)
        result.removeList*.id == [12L]
    }

    // ─── execute() success path – SyncTask onAdd ───────────────────────────

    def "execute() success path triggers addVirtualImages for new template"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def asyncRoot = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def osTypeSvc = Mock(Object)
        def viSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def viLocSvc = Mock(Object)
        def storageSvc = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def volumeSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def volTypeSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeTypeService')
        def asyncViSvc = typedMock('com.morpheusdata.core.MorpheusVirtualImageService')
        def asyncStorageSvc = typedMock('com.morpheusdata.core.MorpheusStorageService')
        def asyncControllerSvc = typedMock('com.morpheusdata.core.MorpheusStorageControllerService')

        morpheusContext.services >> services
        morpheusContext.async >> asyncRoot
        services.osType >> osTypeSvc
        services.virtualImage >> viSvc
        services.storage >> storageSvc
        storageSvc.volume >> volumeSvc
        volumeSvc.storageVolumeType >> volTypeSvc
        viSvc.location >> viLocSvc
        asyncRoot.virtualImage >> asyncViSvc
        asyncRoot.storage >> asyncStorageSvc
        asyncStorageSvc.controller >> asyncControllerSvc

        osTypeSvc.list(_) >> []
        // getStorageControllerTypeMap (DBUtils via async.storage.controller.storageControllerType.find)
        def asyncCtrlTypeSvc = Mock(Object)
        asyncControllerSvc.storageControllerType >> asyncCtrlTypeSvc
        asyncCtrlTypeSvc.find(_) >> io.reactivex.rxjava3.core.Single.just(new StorageControllerType(code: "dummy"))

        // getStorageVolumeTypes (services.storage.volume.storageVolumeType.find)
        volTypeSvc.find(_) >> null

        // listIdentityProjections → empty DB → onAdd fires
        viSvc.listIdentityProjections(_) >> []

        def createdImage = new VirtualImage(id: 777L, name: 'tpl-1', externalId: 'uid-tpl-1')
        asyncViSvc.create(_ as VirtualImage) >> io.reactivex.rxjava3.core.Single.just(createdImage)
        viSvc.get(777L) >> createdImage

        // syncImageLocationForVirtualImage: location.find → null → tries create → returns null (caught)
        viLocSvc.find(_) >> null
        viLocSvc.create(_, _) >> null

        def template = [
            name: 'rhel9-server', uid: 'uid-tpl-1', namespace: 'harvester',
            description: 'RHEL 9 Server', operatingSystem: 'rhel', osVersion: '9',
            memory: null, bootSource: null, templateType: 'base',
            displayName: 'RHEL 9', iconClass: 'icon-rhel', workloadProfile: 'server',
            flavor: 'medium', parameters: [], volumes: null,
            rawTemplate: [metadata: [labels: [:], annotations: [:]]]
        ]
        def spySync = Spy(sync)
        spySync.getAllVMTemplates(_, _) >> [success: true, vmTemplates: [template]]

        when:
        spySync.execute()

        then:
        notThrown(Exception)
    }

    def "execute() success path with success=false vmTemplates makes no SyncTask"() {
        given:
        def services = Mock(Object)
        def osTypeSvc = Mock(Object)
        morpheusContext.services >> services
        services.osType >> osTypeSvc
        osTypeSvc.list(_) >> []

        def spySync = Spy(sync)
        spySync.getAllVMTemplates(_, _) >> [success: false, vmTemplates: null]

        when:
        spySync.execute()

        then:
        notThrown(Exception)
    }

    def "execute() success path with empty vmTemplates list skips SyncTask"() {
        given:
        def services = Mock(Object)
        def osTypeSvc = Mock(Object)
        morpheusContext.services >> services
        services.osType >> osTypeSvc
        osTypeSvc.list(_) >> []

        def spySync = Spy(sync)
        spySync.getAllVMTemplates(_, _) >> [success: true, vmTemplates: []]  // empty → falsy → skip

        when:
        spySync.execute()

        then:
        notThrown(Exception)
    }

    def "execute() success path with matching DB image triggers updateVirtualImages"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def asyncRoot = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def osTypeSvc = Mock(Object)
        def viSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def viLocSvc = Mock(Object)
        def storageSvc = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def volumeSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def volTypeSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeTypeService')
        def asyncStorageSvc = typedMock('com.morpheusdata.core.MorpheusStorageService')
        def asyncControllerSvc = typedMock('com.morpheusdata.core.MorpheusStorageControllerService')

        morpheusContext.services >> services
        morpheusContext.async >> asyncRoot
        services.osType >> osTypeSvc
        services.virtualImage >> viSvc
        services.storage >> storageSvc
        storageSvc.volume >> volumeSvc
        volumeSvc.storageVolumeType >> volTypeSvc
        viSvc.location >> viLocSvc
        asyncRoot.storage >> asyncStorageSvc
        asyncStorageSvc.controller >> asyncControllerSvc

        osTypeSvc.list(_) >> []
        def asyncCtrlTypeSvc = Mock(Object)
        asyncControllerSvc.storageControllerType >> asyncCtrlTypeSvc
        asyncCtrlTypeSvc.find(_) >> io.reactivex.rxjava3.core.Single.just(new StorageControllerType(code: "dummy"))
        volTypeSvc.find(_) >> null
        viLocSvc.find(_) >> null

        def proj = Mock(com.morpheusdata.model.projection.VirtualImageIdentityProjection)
        proj.externalId >> 'uid-match'
        proj.id >> 888L
        viSvc.listIdentityProjections(_) >> [proj]

        def fullImage = new VirtualImage(
            id: 888L, name: 'old-name', externalId: 'uid-match',
            description: 'old', platform: 'windows', status: 'Inactive'
        )
        def ownerA = typedMock('com.morpheusdata.model.Account')
        ownerA.id >> 1L
        fullImage.owner = ownerA
        fullImage.account = ownerA
        viSvc.list(_) >> [fullImage]
        viSvc.get(888L) >> fullImage
        viSvc.bulkSave(_) >> null

        def template = [
            name: 'new-name', uid: 'uid-match', namespace: 'default',
            description: 'new', operatingSystem: 'ubuntu', osVersion: '22',
            memory: null, bootSource: null, templateType: 'custom',
            displayName: 'New', iconClass: 'ic', workloadProfile: 'server',
            flavor: 'large', parameters: [], volumes: null,
            rawTemplate: [metadata: [labels: [:], annotations: [:]]]
        ]
        def spySync = Spy(sync)
        spySync.getAllVMTemplates(_, _) >> [success: true, vmTemplates: [template]]

        when:
        spySync.execute()

        then:
        notThrown(Exception)
    }

    def "execute() success path triggers removeVirtualImages for stale DB image"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def asyncRoot = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def osTypeSvc = Mock(Object)
        def viSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def storageSvc = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def volumeSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def volTypeSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeTypeService')
        def asyncStorageSvc = typedMock('com.morpheusdata.core.MorpheusStorageService')
        def asyncControllerSvc = typedMock('com.morpheusdata.core.MorpheusStorageControllerService')

        morpheusContext.services >> services
        morpheusContext.async >> asyncRoot
        services.osType >> osTypeSvc
        services.virtualImage >> viSvc
        services.storage >> storageSvc
        storageSvc.volume >> volumeSvc
        volumeSvc.storageVolumeType >> volTypeSvc
        asyncRoot.storage >> asyncStorageSvc
        asyncStorageSvc.controller >> asyncControllerSvc

        osTypeSvc.list(_) >> []
        def asyncCtrlTypeSvc = Mock(Object)
        asyncControllerSvc.storageControllerType >> asyncCtrlTypeSvc
        asyncCtrlTypeSvc.find(_) >> io.reactivex.rxjava3.core.Single.just(new StorageControllerType(code: "dummy"))

        def staleProj = Mock(com.morpheusdata.model.projection.VirtualImageIdentityProjection)
        staleProj.externalId >> 'stale-uid'
        staleProj.id >> 999L
        viSvc.listIdentityProjections(_) >> [staleProj]

        def spySync = Spy(sync)
        spySync.getAllVMTemplates(_, _) >> [success: true, vmTemplates: [[
            name: 'different-template', uid: 'new-uid', namespace: 'ns',
            operatingSystem: 'fedora', osVersion: '39', volumes: null,
            rawTemplate: [metadata: [labels: [:], annotations: [:]]]
        ]]]

        when:
        spySync.execute()

        then:
        notThrown(Exception)
    }

    // --- syncImageLocationForVirtualImage tests ---

    def "syncImageLocationForVirtualImage creates new location when none exists"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def viLocationSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageLocationService')
        def viSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        morpheusContext.services >> services
        services.virtualImageLocation >> viLocationSvc
        services.virtualImage >> viSvc
        viLocationSvc.find(_) >> null // no existing location
        viLocationSvc.create(_, _) >> new VirtualImageLocation(id: 1L)

        def virtualImage = new VirtualImage(id: 100L, name: 'test-image')
        def template = [uid: 'uid-123', namespace: 'ns1', name: 'test-template']

        when:
        invokePrivate(sync, 'syncImageLocationForVirtualImage',
            [VirtualImage, Map] as Class[],
            [virtualImage, template] as Object[])

        then:
        notThrown(Exception)
    }

    // --- updateExistingController tests ---

    def "updateExistingController returns false when nothing changed"() {
        given:
        def controllerType = new StorageControllerType(id: 1L, name: 'virtio', displayOrder: 1, description: 'Virtio')
        def existing = new StorageController(
            type: controllerType, name: 'virtio', displayOrder: 1,
            description: 'Virtio', active: true, busNumber: '0', unitNumber: '0'
        )

        when:
        def result = invokePrivate(sync, 'updateExistingController',
            [StorageController, StorageControllerType] as Class[],
            [existing, controllerType] as Object[])

        then:
        !result
    }

    def "updateExistingController returns true when name changed"() {
        given:
        def controllerType = new StorageControllerType(id: 1L, name: 'new-name', displayOrder: 1, description: 'desc')
        def existing = new StorageController(
            type: controllerType, name: 'old-name', displayOrder: 1,
            description: 'desc', active: true, busNumber: '0', unitNumber: '0'
        )

        when:
        def result = invokePrivate(sync, 'updateExistingController',
            [StorageController, StorageControllerType] as Class[],
            [existing, controllerType] as Object[])

        then:
        result
    }

    def "updateExistingController returns false for null controllerType"() {
        given:
        def existing = new StorageController(name: 'test')

        when:
        def result = invokePrivate(sync, 'updateExistingController',
            [StorageController, StorageControllerType] as Class[],
            [existing, null] as Object[])

        then:
        !result
    }

    // --- mapBusTypeToStorageController tests ---

    @Unroll
    def "mapBusTypeToStorageController maps '#bus' to '#expected'"() {
        expect:
        invokePrivateStatic(VirtualImagesSync, 'mapBusTypeToStorageController',
            [String] as Class[], [bus] as Object[]) == expected

        where:
        bus       || expected
        null      || HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO
        'virtio'  || HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO
        'scsi'    || HarvesterConstants.STORAGE_CONTROLLER_TYPE_SCSI
        'sata'    || HarvesterConstants.STORAGE_CONTROLLER_TYPE_SATA
        'ide'     || HarvesterConstants.STORAGE_CONTROLLER_TYPE_IDE
        'unknown' || HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO
    }

    // --- findOsTypeInList deeper paths ---

    def "findOsTypeInList returns null for null osType"() {
        expect:
        invokePrivateStatic(VirtualImagesSync, 'findOsTypeInList',
            [String, String, List] as Class[], [null, '8', []] as Object[]) == null
    }

    def "findOsTypeInList returns null for empty osTypes"() {
        expect:
        invokePrivateStatic(VirtualImagesSync, 'findOsTypeInList',
            [String, String, List] as Class[], ['centos', '8', null] as Object[]) == null
    }

    def "findOsTypeInList exact version match with 64-bit preference"() {
        given:
        def osTypes = [
            [category: 'centos', osVersion: '8', code: 'centos.8.32', name: 'CentOS 8 32-bit'],
            [category: 'centos', osVersion: '8', code: 'centos.8.64', name: 'CentOS 8 64-bit']
        ]

        when:
        def result = invokePrivateStatic(VirtualImagesSync, 'findOsTypeInList',
            [String, String, List] as Class[], ['centos', '8', osTypes] as Object[])

        then:
        result.code == 'centos.8.64'
    }

    def "findOsTypeInList exact version match without 64-bit"() {
        given:
        def osTypes = [
            [category: 'centos', osVersion: '8', code: 'centos.8', name: 'CentOS 8']
        ]

        when:
        def result = invokePrivateStatic(VirtualImagesSync, 'findOsTypeInList',
            [String, String, List] as Class[], ['centos', '8', osTypes] as Object[])

        then:
        result.code == 'centos.8'
    }

    def "findOsTypeInList falls back to generic all version with 64-bit"() {
        given:
        def osTypes = [
            [category: 'centos', osVersion: 'all', code: 'centos.all.64', name: 'CentOS All 64-bit']
        ]

        when:
        def result = invokePrivateStatic(VirtualImagesSync, 'findOsTypeInList',
            [String, String, List] as Class[], ['centos', '9', osTypes] as Object[])

        then:
        result.code == 'centos.all.64'
    }

    def "findOsTypeInList falls back to generic all version without 64-bit"() {
        given:
        def osTypes = [
            [category: 'centos', osVersion: 'all', code: 'centos.all', name: 'CentOS All']
        ]

        when:
        def result = invokePrivateStatic(VirtualImagesSync, 'findOsTypeInList',
            [String, String, List] as Class[], ['centos', '9', osTypes] as Object[])

        then:
        result.code == 'centos.all'
    }

    def "findOsTypeInList centos-stream exact match"() {
        given:
        def osTypes = [
            [category: 'centos', osVersion: '8', code: 'centos.stream.8.64', name: 'CentOS Stream 8']
        ]

        when:
        def result = invokePrivateStatic(VirtualImagesSync, 'findOsTypeInList',
            [String, String, List] as Class[], ['centos-stream', '8', osTypes] as Object[])

        then:
        result.code == 'centos.stream.8.64'
    }

    def "findOsTypeInList centos-stream falls back to generic centos"() {
        given:
        def osTypes = [
            [category: 'centos', osVersion: 'all', code: 'centos.all.64', name: 'CentOS All 64']
        ]

        when:
        def result = invokePrivateStatic(VirtualImagesSync, 'findOsTypeInList',
            [String, String, List] as Class[], ['centos-stream', '9', osTypes] as Object[])

        then:
        result.code == 'centos.all.64'
    }

    def "findOsTypeInList centos-stream falls back to centos all without 64"() {
        given:
        def osTypes = [
            [category: 'centos', osVersion: 'all', code: 'centos.all', name: 'CentOS All']
        ]

        when:
        def result = invokePrivateStatic(VirtualImagesSync, 'findOsTypeInList',
            [String, String, List] as Class[], ['centos-stream', '9', osTypes] as Object[])

        then:
        result.code == 'centos.all'
    }

    def "findOsTypeInList rhel falls back to redhat category"() {
        given:
        def osTypes = [
            [category: 'redhat', osVersion: 'all', code: 'redhat.all.64', name: 'Red Hat All 64']
        ]

        when:
        def result = invokePrivateStatic(VirtualImagesSync, 'findOsTypeInList',
            [String, String, List] as Class[], ['rhel', '9', osTypes] as Object[])

        then:
        result.code == 'redhat.all.64'
    }

    def "findOsTypeInList rocky falls back to centos category"() {
        given:
        def osTypes = [
            [category: 'centos', osVersion: 'all', code: 'centos.all', name: 'CentOS All']
        ]

        when:
        def result = invokePrivateStatic(VirtualImagesSync, 'findOsTypeInList',
            [String, String, List] as Class[], ['rocky', '9', osTypes] as Object[])

        then:
        result.code == 'centos.all'
    }

    def "findOsTypeInList returns null when no match found"() {
        given:
        def osTypes = [
            [category: 'windows', osVersion: '10', code: 'windows.10.64', name: 'Windows 10']
        ]

        expect:
        invokePrivateStatic(VirtualImagesSync, 'findOsTypeInList',
            [String, String, List] as Class[], ['nonexistent', '1', osTypes] as Object[]) == null
    }

    def "findOsTypeInList version all parameter uses generic match"() {
        given:
        def osTypes = [
            [category: 'ubuntu', osVersion: 'all', code: 'ubuntu.all.64', name: 'Ubuntu All 64-bit']
        ]

        when:
        def result = invokePrivateStatic(VirtualImagesSync, 'findOsTypeInList',
            [String, String, List] as Class[], ['ubuntu', 'all', osTypes] as Object[])

        then:
        result.code == 'ubuntu.all.64'
    }

    def "findOsTypeInList fallback for almalinux to centos"() {
        given:
        def osTypes = [
            [category: 'centos', osVersion: 'all', code: 'centos.all.64', name: 'CentOS All 64']
        ]

        when:
        def result = invokePrivateStatic(VirtualImagesSync, 'findOsTypeInList',
            [String, String, List] as Class[], ['almalinux', '9', osTypes] as Object[])

        then:
        result.code == 'centos.all.64'
    }

    def "findOsTypeInList fallback for alma to centos without 64"() {
        given:
        def osTypes = [
            [category: 'centos', osVersion: 'all', code: 'centos.all.generic', name: 'CentOS All']
        ]

        when:
        def result = invokePrivateStatic(VirtualImagesSync, 'findOsTypeInList',
            [String, String, List] as Class[], ['alma', '9', osTypes] as Object[])

        then:
        result.code == 'centos.all.generic'
    }

    // --- convertDisplayOrderToDeviceName ---

    @Unroll
    def "convertDisplayOrderToDeviceName linux order #order yields '#expected'"() {
        expect:
        invokePrivateStatic(VirtualImagesSync, 'convertDisplayOrderToDeviceName',
            [String, int] as Class[], ['linux', order] as Object[]) == expected

        where:
        order || expected
        0     || '/dev/vda'
        1     || '/dev/vdb'
        2     || '/dev/vdc'
        25    || '/dev/vdz'
    }

    def "convertDisplayOrderToDeviceName windows uses disk numbering"() {
        expect:
        invokePrivateStatic(VirtualImagesSync, 'convertDisplayOrderToDeviceName',
            [String, int] as Class[], ['windows', 0] as Object[]) == 'disk 1'
        invokePrivateStatic(VirtualImagesSync, 'convertDisplayOrderToDeviceName',
            [String, int] as Class[], ['windows', 1] as Object[]) == 'disk 2'
    }

    def "convertDisplayOrderToDeviceName out of range defaults to vda"() {
        expect:
        invokePrivateStatic(VirtualImagesSync, 'convertDisplayOrderToDeviceName',
            [String, int] as Class[], ['linux', -1] as Object[]) == '/dev/vda'
        invokePrivateStatic(VirtualImagesSync, 'convertDisplayOrderToDeviceName',
            [String, int] as Class[], ['linux', 26] as Object[]) == '/dev/vda'
    }

    def "convertDisplayOrderToDeviceName out of range windows defaults to disk 1"() {
        expect:
        invokePrivateStatic(VirtualImagesSync, 'convertDisplayOrderToDeviceName',
            [String, int] as Class[], ['Windows 2022', -1] as Object[]) == 'disk 1'
    }

    // --- parseStorageSize ---

    def "parseStorageSize returns 0 for null"() {
        expect:
        invokePrivate(sync, 'parseStorageSize', [String] as Class[], [null] as Object[]) == 0
    }

    def "parseStorageSize parses valid kubernetes units"() {
        when:
        def result = invokePrivate(sync, 'parseStorageSize', [String] as Class[], ['100Gi'] as Object[])

        then:
        result > 0
    }

    // --- findStorageControllerByType ---

    def "findStorageControllerByType returns controller when found"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def storageSvc = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def controllerSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageControllerService')
        morpheusContext.services >> services
        services.storage >> storageSvc
        storageSvc.controller >> controllerSvc

        def vi = new VirtualImage(id: 10L, name: 'img')
        def sct = new StorageControllerType(code: 'virtio-blk')
        def sc = new StorageController(id: 5L, name: 'ctrl')
        controllerSvc.find(_) >> sc

        when:
        def result = invokePrivate(sync, 'findStorageControllerByType',
            [VirtualImage, StorageControllerType] as Class[], [vi, sct] as Object[])

        then:
        result == sc
    }

    def "findStorageControllerByType returns null when not found"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def storageSvc = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def controllerSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageControllerService')
        morpheusContext.services >> services
        services.storage >> storageSvc
        storageSvc.controller >> controllerSvc
        controllerSvc.find(_) >> null

        def vi = new VirtualImage(id: 10L, name: 'img')
        def sct = new StorageControllerType(code: 'virtio-blk')

        when:
        def result = invokePrivate(sync, 'findStorageControllerByType',
            [VirtualImage, StorageControllerType] as Class[], [vi, sct] as Object[])

        then:
        result == null
    }

    def "findStorageControllerByType handles exception"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def storageSvc = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def controllerSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageControllerService')
        morpheusContext.services >> services
        services.storage >> storageSvc
        storageSvc.controller >> controllerSvc
        controllerSvc.find(_) >> { throw new RuntimeException('db error') }

        def vi = new VirtualImage(id: 10L, name: 'img')
        def sct = new StorageControllerType(code: 'virtio-blk')

        when:
        def result = invokePrivate(sync, 'findStorageControllerByType',
            [VirtualImage, StorageControllerType] as Class[], [vi, sct] as Object[])

        then:
        result == null
    }

    // --- getStorageVolumeTypes ---

    def "getStorageVolumeTypes returns map of volume types"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def storageSvc = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def volumeSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def svtSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeTypeService')
        morpheusContext.services >> services
        services.storage >> storageSvc
        storageSvc.volume >> volumeSvc
        volumeSvc.storageVolumeType >> svtSvc

        def pvcType = new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)
        def dvType = new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME)
        svtSvc.find(_) >>> [pvcType, dvType, null]

        when:
        def result = invokePrivate(sync, 'getStorageVolumeTypes', [] as Class[], [] as Object[])

        then:
        result[HarvesterConstants.VOLUME_TYPE_PVC] == pvcType
        result[HarvesterConstants.VOLUME_TYPE_DATAVOLUME] == dvType
        result['default'] == dvType
    }

    // --- updateExistingVolumeWithType ---

    def "updateExistingVolumeWithType updates volume properties"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def storageSvc = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def controllerSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageControllerService')
        def cloudSvc = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService')
        def datastoreSvc = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService')
        def volumeSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        morpheusContext.services >> services
        services.storage >> storageSvc
        services.cloud >> cloudSvc
        storageSvc.controller >> controllerSvc
        storageSvc.volume >> volumeSvc
        cloudSvc.datastore >> datastoreSvc
        controllerSvc.find(_) >> null
        datastoreSvc.find(_) >> null

        def cloud = typedMock('com.morpheusdata.model.Cloud')
        cloud.id >> 44L
        cluster.cloud >> cloud

        def existingVol = new StorageVolume(id: 1L, name: 'old-name', maxStorage: 100L, displayOrder: 0,
            deviceName: '/dev/vda', deviceDisplayName: '/dev/vda', unitNumber: '0', cloudId: 44L,
            refType: HarvesterConstants.DATASTORE)
        def volType = new StorageVolumeType(id: 5L, code: 'harvesterPVC')
        def sct = new StorageControllerType(code: 'virtio-blk')
        def vi = new VirtualImage(id: 10L, name: 'img')
        vi.osType = [platform: 'linux']

        def processedVol = [
            storageClassName: 'sc1', requestedStorage: null,
            displayOrder: 1, volumeType: 'pvc', pvcName: 'new-pvc',
            sourcePvcName: null, sourceType: null,
            dataVolumeName: null, name: 'vol-name'
        ]

        when:
        def result = invokePrivate(sync, 'updateExistingVolumeWithType',
            [StorageVolume, Map, StorageVolumeType, StorageControllerType, VirtualImage, int] as Class[],
            [existingVol, processedVol, volType, sct, vi, 1] as Object[])

        then:
        result // should have been updated
    }

    // --- syncImageLocationForVirtualImage ---

    def "syncImageLocationForVirtualImage creates new location when none exists"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def viSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def viLocSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageLocationService')
        morpheusContext.services >> services
        services.virtualImage >> viSvc
        viSvc.location >> viLocSvc
        viLocSvc.find(_) >> null
        viLocSvc.create(_, _) >> new VirtualImageLocation(id: 1L)

        def vi = new VirtualImage(id: 5L, name: 'test-image', externalId: 'uid-1',
            imageType: 'qcow2', isCloudInit: true)
        def template = [uid: 'uid-1', name: 'template-1', namespace: 'ns1',
            memory: '4Gi', cores: 2, operatingSystem: 'centos', osVersion: '8']

        when:
        invokePrivate(sync, 'syncImageLocationForVirtualImage',
            [VirtualImage, Map] as Class[], [vi, template] as Object[])

        then:
        1 * viLocSvc.create(_, _)
    }

    def "syncImageLocationForVirtualImage updates existing location"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def viSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def viLocSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageLocationService')
        morpheusContext.services >> services
        services.virtualImage >> viSvc
        viSvc.location >> viLocSvc

        def existingLoc = new VirtualImageLocation(id: 10L, externalId: 'uid-1')
        viLocSvc.find(_) >> existingLoc
        viLocSvc.save(_) >> existingLoc

        def vi = new VirtualImage(id: 5L, name: 'test-image', externalId: 'uid-1',
            imageType: 'qcow2', isCloudInit: true)
        def template = [uid: 'uid-1', name: 'template-1', namespace: 'ns1',
            memory: '4Gi', cores: 2, operatingSystem: 'centos', osVersion: '8']

        when:
        invokePrivate(sync, 'syncImageLocationForVirtualImage',
            [VirtualImage, Map] as Class[], [vi, template] as Object[])

        then:
        notThrown(Exception)
    }

    def "syncImageLocationForVirtualImage handles exception"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def viSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        morpheusContext.services >> services
        services.virtualImage >> viSvc
        viSvc.location >> { throw new RuntimeException('fail') }

        def vi = new VirtualImage(id: 5L, name: 'img')

        when:
        invokePrivate(sync, 'syncImageLocationForVirtualImage',
            [VirtualImage, Map] as Class[], [vi, [uid: 'u1']] as Object[])

        then:
        notThrown(Exception)
    }

    // --- addVolumesToVirtualImageWithTypes: full closure coverage ---

    def "addVolumesToVirtualImageWithTypes creates volumes with valid controller and datastore"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def cloudSvc = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService')
        def datastoreSvc = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService')
        def storageSvc = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def storageVolSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def controllerSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageControllerService')
        def viSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def asyncRoot = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def asyncStorage = typedMock('com.morpheusdata.core.MorpheusStorageService')
        def asyncStorageVolSvc = typedMock('com.morpheusdata.core.MorpheusStorageVolumeService')

        morpheusContext.services >> services
        morpheusContext.async >> asyncRoot
        services.cloud >> cloudSvc
        cloudSvc.datastore >> datastoreSvc
        services.storage >> storageSvc
        storageSvc.volume >> storageVolSvc
        storageSvc.controller >> controllerSvc
        services.virtualImage >> viSvc
        asyncRoot.storage >> asyncStorage
        asyncStorage.volume >> asyncStorageVolSvc

        def virtioType = new StorageControllerType(id: 71L, code: HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO, name: 'Virtio')
        def scsiType = new StorageControllerType(id: 72L, code: HarvesterConstants.STORAGE_CONTROLLER_TYPE_SCSI, name: 'SCSI')
        def datastore = new Datastore(id: 500L, name: 'fast-storage')
        def sourceVol = new StorageVolume(id: 501L, name: 'src-pvc')
        def controller = new StorageController(id: 502L, type: virtioType)

        datastoreSvc.find(_) >> datastore
        storageVolSvc.find(_) >> sourceVol
        controllerSvc.find(_) >> controller

        def image = new VirtualImage(id: 7001L, name: 'img-with-vols')
        def imageOsType = typedMock('com.morpheusdata.model.OsType')
        imageOsType.platform >> 'linux'
        image.osType = imageOsType
        viSvc.get(7001L) >> image

        def processedVolumes = [
            [name: 'boot-disk', dataVolumeName: 'dv-boot', volumeType: HarvesterConstants.VOLUME_TYPE_DATAVOLUME,
             storageController: HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO, storageClassName: 'fast-storage',
             requestedStorage: '20Gi', displayOrder: 1, isBootDisk: true,
             sourcePvcName: 'src-pvc', sourceType: 'pvc', sourcePvcNamespace: 'ns1',
             sourceRefKind: null, sourceRefName: null, sourceRefNamespace: null],
            [name: 'data-disk', dataVolumeName: 'dv-data', volumeType: HarvesterConstants.VOLUME_TYPE_DATAVOLUME,
             storageController: HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO, storageClassName: 'fast-storage',
             requestedStorage: '50Gi', displayOrder: 2, isBootDisk: false,
             sourcePvcName: null, sourceType: null, sourcePvcNamespace: null,
             sourceRefKind: 'DataSource', sourceRefName: 'ds1', sourceRefNamespace: 'ns2']
        ]
        def volumeTypes = [
            (HarvesterConstants.VOLUME_TYPE_DATAVOLUME): new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME),
            default: new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME)
        ]
        def controllerTypeMap = [
            (HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO): virtioType,
            (HarvesterConstants.STORAGE_CONTROLLER_TYPE_SCSI): scsiType
        ]

        asyncStorageVolSvc.create(_, _) >> io.reactivex.rxjava3.core.Single.just(true)

        when:
        invokePrivate(sync, 'addVolumesToVirtualImageWithTypes',
            [VirtualImage, List, Map, Map] as Class[],
            [image, processedVolumes, volumeTypes, controllerTypeMap] as Object[])

        then:
        1 * asyncStorageVolSvc.create(_, _) >> io.reactivex.rxjava3.core.Single.just(true)
    }

    def "addVolumesToVirtualImageWithTypes skips volume with no volume type match"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def cloudSvc = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService')
        def datastoreSvc = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService')
        def storageSvc = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def storageVolSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def viSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def asyncRoot = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def asyncStorage = typedMock('com.morpheusdata.core.MorpheusStorageService')
        def asyncStorageVolSvc = typedMock('com.morpheusdata.core.MorpheusStorageVolumeService')

        morpheusContext.services >> services
        morpheusContext.async >> asyncRoot
        services.cloud >> cloudSvc
        cloudSvc.datastore >> datastoreSvc
        services.storage >> storageSvc
        storageSvc.volume >> storageVolSvc
        services.virtualImage >> viSvc
        asyncRoot.storage >> asyncStorage
        asyncStorage.volume >> asyncStorageVolSvc

        def virtioType = new StorageControllerType(id: 71L, code: HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO, name: 'Virtio')
        def image = new VirtualImage(id: 7002L, name: 'img-no-type')
        viSvc.get(7002L) >> image

        def processedVolumes = [
            [name: 'disk1', dataVolumeName: 'dv1', volumeType: 'unknownType',
             storageController: HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO,
             storageClassName: 'fast', requestedStorage: '10Gi', displayOrder: 1]
        ]
        // Empty volume types map - no match possible
        def volumeTypes = [:]
        def controllerTypeMap = [(HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO): virtioType]

        when:
        invokePrivate(sync, 'addVolumesToVirtualImageWithTypes',
            [VirtualImage, List, Map, Map] as Class[],
            [image, processedVolumes, volumeTypes, controllerTypeMap] as Object[])

        then:
        0 * asyncStorageVolSvc.create(_, _)
    }

    def "addVolumesToVirtualImageWithTypes returns early for null volumes"() {
        when:
        invokePrivate(sync, 'addVolumesToVirtualImageWithTypes',
            [VirtualImage, List, Map, Map] as Class[],
            [new VirtualImage(id: 1L), null, [:], [:]] as Object[])

        then:
        notThrown(Exception)
    }

    // --- updateVolumesForVirtualImageWithTypes: full closure coverage ---

    def "updateVolumesForVirtualImageWithTypes updates existing and adds new volumes"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def cloudSvc = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService')
        def datastoreSvc = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService')
        def storageSvc = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def storageVolSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def controllerSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageControllerService')
        def viSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def asyncRoot = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def asyncStorage = typedMock('com.morpheusdata.core.MorpheusStorageService')
        def asyncStorageVolSvc = typedMock('com.morpheusdata.core.MorpheusStorageVolumeService')

        morpheusContext.services >> services
        morpheusContext.async >> asyncRoot
        services.cloud >> cloudSvc
        cloudSvc.datastore >> datastoreSvc
        services.storage >> storageSvc
        storageSvc.volume >> storageVolSvc
        storageSvc.controller >> controllerSvc
        services.virtualImage >> viSvc
        asyncRoot.storage >> asyncStorage
        asyncStorage.volume >> asyncStorageVolSvc

        def virtioType = new StorageControllerType(id: 71L, code: HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO, name: 'Virtio')
        def datastore = new Datastore(id: 600L, name: 'fast-storage')
        def sourceVol = new StorageVolume(id: 601L, name: 'src-pvc')
        def controller = new StorageController(id: 602L, type: virtioType)

        datastoreSvc.find(_) >> datastore
        storageVolSvc.find(_) >> sourceVol
        controllerSvc.find(_) >> controller

        // Existing volume that should be updated
        def existingVol = new StorageVolume(
            id: 700L, name: 'dv-existing', maxStorage: 1L, displayOrder: 5,
            unitNumber: '9', deviceName: '/dev/old', deviceDisplayName: '/dev/old',
            type: new StorageVolumeType(id: 1L, code: 'old-type'),
            controller: new StorageController(id: 1L)
        )

        def image = new VirtualImage(id: 8001L, name: 'img-update-vols', volumes: [existingVol])
        def imageOsType = typedMock('com.morpheusdata.model.OsType')
        imageOsType.platform >> 'linux'
        image.osType = imageOsType
        viSvc.get(8001L) >> image

        def processedVolumes = [
            [name: 'existing', dataVolumeName: 'dv-existing', volumeType: HarvesterConstants.VOLUME_TYPE_DATAVOLUME,
             storageController: HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO, storageClassName: 'fast-storage',
             requestedStorage: '20Gi', displayOrder: 0, isBootDisk: true,
             sourcePvcName: 'src-pvc', sourceType: 'pvc', sourcePvcNamespace: 'ns1',
             sourceRefKind: null, sourceRefName: null, sourceRefNamespace: null],
            [name: 'new-disk', dataVolumeName: 'dv-new', pvcName: null, volumeType: HarvesterConstants.VOLUME_TYPE_DATAVOLUME,
             storageController: HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO, storageClassName: 'fast-storage',
             requestedStorage: '50Gi', displayOrder: 1, isBootDisk: false,
             sourcePvcName: 'src-pvc', sourceType: 'pvc', sourcePvcNamespace: 'ns2',
             sourceRefKind: 'DataSource', sourceRefName: 'ds-ref', sourceRefNamespace: 'ns-ref']
        ]
        def volumeTypes = [
            (HarvesterConstants.VOLUME_TYPE_DATAVOLUME): new StorageVolumeType(id: 2L, code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME),
            default: new StorageVolumeType(id: 2L, code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME)
        ]
        def controllerTypeMap = [(HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO): virtioType]

        asyncStorageVolSvc.create(_, _) >> io.reactivex.rxjava3.core.Single.just(true)

        when:
        invokePrivate(sync, 'updateVolumesForVirtualImageWithTypes',
            [VirtualImage, List, Map, Map] as Class[],
            [image, processedVolumes, volumeTypes, controllerTypeMap] as Object[])

        then:
        1 * storageVolSvc.bulkSave(_ as List) // existing vol updated
        1 * asyncStorageVolSvc.create(_, _) >> io.reactivex.rxjava3.core.Single.just(true) // new vol added
    }

    def "updateVolumesForVirtualImageWithTypes returns early for null volumes"() {
        when:
        invokePrivate(sync, 'updateVolumesForVirtualImageWithTypes',
            [VirtualImage, List, Map, Map] as Class[],
            [new VirtualImage(id: 1L), null, [:], [:]] as Object[])

        then:
        notThrown(Exception)
    }

    def "updateVolumesForVirtualImageWithTypes skips new volume when datastore not found"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def cloudSvc = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService')
        def datastoreSvc = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousDatastoreService')
        def storageSvc = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def storageVolSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def viSvc = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def asyncRoot = typedMock('com.morpheusdata.core.MorpheusAsyncServices')
        def asyncStorage = typedMock('com.morpheusdata.core.MorpheusStorageService')
        def asyncStorageVolSvc = typedMock('com.morpheusdata.core.MorpheusStorageVolumeService')

        morpheusContext.services >> services
        morpheusContext.async >> asyncRoot
        services.cloud >> cloudSvc
        cloudSvc.datastore >> datastoreSvc
        services.storage >> storageSvc
        storageSvc.volume >> storageVolSvc
        services.virtualImage >> viSvc
        asyncRoot.storage >> asyncStorage
        asyncStorage.volume >> asyncStorageVolSvc

        datastoreSvc.find(_) >> null // no datastore

        def virtioType = new StorageControllerType(id: 71L, code: HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO, name: 'Virtio')
        def image = new VirtualImage(id: 8002L, name: 'img-no-ds', volumes: [])
        viSvc.get(8002L) >> image

        def processedVolumes = [
            [name: 'disk1', dataVolumeName: 'dv1', pvcName: null, volumeType: HarvesterConstants.VOLUME_TYPE_DATAVOLUME,
             storageController: HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO, storageClassName: 'missing-sc',
             requestedStorage: '10Gi', displayOrder: 1]
        ]
        def volumeTypes = [
            (HarvesterConstants.VOLUME_TYPE_DATAVOLUME): new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME),
            default: new StorageVolumeType(code: HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME)
        ]
        def controllerTypeMap = [(HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO): virtioType]

        when:
        invokePrivate(sync, 'updateVolumesForVirtualImageWithTypes',
            [VirtualImage, List, Map, Map] as Class[],
            [image, processedVolumes, volumeTypes, controllerTypeMap] as Object[])

        then:
        0 * asyncStorageVolSvc.create(_, _)
    }
}
