// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin

import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.DatastoreTypeProvider
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.Snapshot
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Title
import spock.lang.Unroll

@Title('HpeHarvesterDatastoreTypeProvider Specification')
class HpeHarvesterDatastoreTypeProviderSpec extends Specification {

    private def typedMock(String className) {
        Mock((Class) Class.forName(className))
    }

    @Subject
    HpeHarvesterDatastoreTypeProvider provider

    MorpheusContext morpheusContext
    HpeHarvesterPlugin plugin

    def setup() {
        plugin = Mock(HpeHarvesterPlugin)
        morpheusContext = Stub(MorpheusContext)
        
        def volumeService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def storageService = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        
        morpheusContext.getServices() >> services
        services.getStorage() >> storageService
        storageService.getVolume() >> volumeService
        
        provider = new HpeHarvesterDatastoreTypeProvider(plugin, morpheusContext)
    }

    def cleanup() {
        provider = null
        morpheusContext = null
        plugin = null
    }

    // Constructor Tests
    def "constructor should initialize with plugin and context"() {
        when:
        def newProvider = new HpeHarvesterDatastoreTypeProvider(plugin, morpheusContext)

        then:
        newProvider.plugin == plugin
        newProvider.context == morpheusContext
    }

    def "constructor should accept null plugin"() {
        when:
        def newProvider = new HpeHarvesterDatastoreTypeProvider(null, morpheusContext)

        then:
        newProvider.plugin == null
        newProvider.context == morpheusContext
    }

    def "constructor should accept null context"() {
        when:
        def newProvider = new HpeHarvesterDatastoreTypeProvider(plugin, null)

        then:
        newProvider.plugin == plugin
        newProvider.context == null
    }

    // Interface Tests
    def "should implement DatastoreTypeProvider interface"() {
        expect:
        provider instanceof DatastoreTypeProvider
    }

    def "should implement MvmProvisionFacet"() {
        expect:
        provider instanceof DatastoreTypeProvider.MvmProvisionFacet
    }

    def "should implement SnapshotServerFacet"() {
        expect:
        provider instanceof DatastoreTypeProvider.SnapshotFacet.SnapshotServerFacet
    }

    // getCode Tests
    def "getCode should return datastore provision provider code"() {
        expect:
        provider.getCode() == HarvesterConstants.DATASTORE_PROVISION_PROVIDER_CODE
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
    def "getName should return 'HpeHarvester Datastore'"() {
        expect:
        provider.getName() == 'HpeHarvester Datastore'
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

    // getProvisionTypeCode Tests
    def "getProvisionTypeCode should return provision provider code"() {
        expect:
        provider.getProvisionTypeCode() == HarvesterConstants.PROVISION_PROVIDER_CODE
    }

    def "getProvisionTypeCode should not return null"() {
        expect:
        provider.getProvisionTypeCode() != null
    }

    // getStorageProviderCode Tests
    def "getStorageProviderCode should return storage provider code"() {
        expect:
        provider.getStorageProviderCode() == HarvesterConstants.STORAGE_PROVIDER_CODE
    }

    def "getStorageProviderCode should not return null"() {
        expect:
        provider.getStorageProviderCode() != null
    }

    // getOptionTypes Tests
    def "getOptionTypes should return empty collection"() {
        when:
        def optionTypes = provider.getOptionTypes()

        then:
        optionTypes != null
        optionTypes instanceof Collection
        optionTypes.isEmpty()
    }

    def "getOptionTypes should return List"() {
        when:
        def optionTypes = provider.getOptionTypes()

        then:
        optionTypes instanceof List
    }

    def "getOptionTypes should return mutable collection"() {
        when:
        def optionTypes = provider.getOptionTypes()
        optionTypes.add(Mock(OptionType))

        then:
        optionTypes.size() == 1
    }

    // Boolean Flag Tests
    def "getCreatable should return true"() {
        expect:
        provider.getCreatable() == true
    }

    def "getEditable should return true"() {
        expect:
        provider.getEditable() == true
    }

    def "getRemovable should return true"() {
        expect:
        provider.getRemovable() == true
    }

    def "boolean flags should return primitive boolean"() {
        expect:
        provider.getCreatable() instanceof Boolean
        provider.getEditable() instanceof Boolean
        provider.getRemovable() instanceof Boolean
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

    // removeVolume Tests
    def "removeVolume should handle valid parameters"() {
        given:
        def volume = Mock(StorageVolume) {
            getId() >> 1L
            getName() >> 'test-volume'
        }
        def server = Mock(ComputeServer) {
            getName() >> 'test-server'
        }
        def volumeService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def storageService = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        
        volumeService.get(1L) >> volume
        morpheusContext.getServices() >> services
        services.getStorage() >> storageService
        storageService.getVolume() >> volumeService

        when:
        provider.removeVolume(volume, server, false, false)

        then:
        thrown(NullPointerException)
    }

    def "removeVolume should handle null server"() {
        given:
        def volume = Mock(StorageVolume) {
            getId() >> 1L
            getName() >> 'test-volume'
        }
        def volumeService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def storageService = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        
        volumeService.get(1L) >> volume
        morpheusContext.getServices() >> services
        services.getStorage() >> storageService
        storageService.getVolume() >> volumeService

        when:
        provider.removeVolume(volume, null, false, false)

        then:
        thrown(NullPointerException)
    }

    def "removeVolume should reload volume from database"() {
        given:
        def volume = Mock(StorageVolume) {
            getId() >> 1L
            getName() >> 'test-volume'
        }
        def reloadedVolume = Mock(StorageVolume)
        def server = Mock(ComputeServer)
        def volumeService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def storageService = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        
        volumeService.get(1L) >> reloadedVolume
        morpheusContext.getServices() >> services
        services.getStorage() >> storageService
        storageService.getVolume() >> volumeService

        when:
        provider.removeVolume(volume, server, false, false)

        then:
        thrown(NullPointerException)
    }

    @Unroll
    def "removeVolume should handle different flag combinations: removeSnapshots=#removeSnapshots, force=#force"() {
        given:
        def volume = Mock(StorageVolume) {
            getId() >> 1L
            getName() >> 'test-volume'
        }
        def server = Mock(ComputeServer)
        def volumeService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def storageService = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        
        volumeService.get(1L) >> volume
        morpheusContext.getServices() >> services
        services.getStorage() >> storageService
        storageService.getVolume() >> volumeService

        when:
        provider.removeVolume(volume, server, removeSnapshots, force)

        then:
        thrown(NullPointerException)

        where:
        removeSnapshots | force
        true            | true
        true            | false
        false           | true
        false           | false
    }

    // createVolume Tests
    def "createVolume should handle valid parameters"() {
        given:
        def volume = Mock(StorageVolume) {
            getId() >> 1L
        }
        def server = Mock(ComputeServer) {
            getName() >> 'test-server'
            getExternalId() >> 'ext-123'
        }

        when:
        def response = provider.createVolume(volume, server)

        then:
        response != null
        response instanceof ServiceResponse
    }

    def "createVolume should handle null volume"() {
        given:
        def server = Mock(ComputeServer)

        when:
        def response = provider.createVolume(null, server)

        then:
        response != null
    }

    def "createVolume should handle null server"() {
        given:
        def volume = Mock(StorageVolume) {
            getId() >> 1L
        }

        when:
        def response = provider.createVolume(volume, null)

        then:
        response != null
    }

    @Unroll
    def "createVolume with different volume IDs: #volumeId"() {
        given:
        def volume = Mock(StorageVolume) {
            getId() >> volumeId
        }
        def server = Mock(ComputeServer) {
            getName() >> 'test-server'
        }

        when:
        def response = provider.createVolume(volume, server)

        then:
        response != null

        where:
        volumeId << [1L, 100L, 999L, null]
    }

    // cloneVolume (from StorageVolume) Tests
    def "cloneVolume should handle valid parameters"() {
        given:
        def volume = Mock(StorageVolume) {
            getVolumeName() >> 'target-volume'
        }
        def server = Mock(ComputeServer) {
            getName() >> 'test-server'
            getExternalId() >> 'ext-123'
        }
        def sourceVolume = Mock(StorageVolume) {
            getVolumeName() >> 'source-volume'
        }

        when:
        def response = provider.cloneVolume(volume, server, sourceVolume)

        then:
        response != null
        response instanceof ServiceResponse
    }

    def "cloneVolume should handle null target volume"() {
        given:
        def server = Mock(ComputeServer)
        def sourceVolume = Mock(StorageVolume)

        when:
        def response = provider.cloneVolume(null, server, sourceVolume)

        then:
        response != null
    }

    def "cloneVolume should handle null server"() {
        given:
        def volume = Mock(StorageVolume) {
            getVolumeName() >> 'target-volume'
        }
        def sourceVolume = Mock(StorageVolume) {
            getVolumeName() >> 'source-volume'
        }

        when:
        def response = provider.cloneVolume(volume, null, sourceVolume)

        then:
        response != null
    }

    def "cloneVolume should handle null source volume"() {
        given:
        def volume = Mock(StorageVolume) {
            getVolumeName() >> 'target-volume'
        }
        def server = Mock(ComputeServer)

        when:
        def response = provider.cloneVolume(volume, server, null)

        then:
        response != null
    }

    // cloneVolume (from VirtualImage) Tests
    def "cloneVolume from VirtualImage should return not implemented"() {
        given:
        def volume = Mock(StorageVolume)
        def server = Mock(ComputeServer)
        def virtualImage = Mock(VirtualImage)
        def cloudFile = Mock(com.bertramlabs.plugins.karman.CloudFileInterface)

        when:
        def response = provider.cloneVolume(volume, server, virtualImage, cloudFile)

        then:
        !response.success
        response.errors?.error == "Not Implemented"
    }

    def "cloneVolume from VirtualImage should handle null parameters"() {
        when:
        def response = provider.cloneVolume(null, null, null, null)

        then:
        !response.success
    }

    // resizeVolume Tests
    def "resizeVolume should return not implemented"() {
        given:
        def volume = Mock(StorageVolume)
        def server = Mock(ComputeServer)
        def newSize = 1024L

        when:
        def response = provider.resizeVolume(volume, server, newSize)

        then:
        !response.success
        response.errors?.error == "Not Implemented"
    }

    def "resizeVolume should handle null volume"() {
        given:
        def server = Mock(ComputeServer)
        def newSize = 1024L

        when:
        def response = provider.resizeVolume(null, server, newSize)

        then:
        !response.success
    }

    def "resizeVolume should handle null server"() {
        given:
        def volume = Mock(StorageVolume)
        def newSize = 1024L

        when:
        def response = provider.resizeVolume(volume, null, newSize)

        then:
        !response.success
    }

    @Unroll
    def "resizeVolume with different sizes: #newSize"() {
        given:
        def volume = Mock(StorageVolume)
        def server = Mock(ComputeServer)

        when:
        def response = provider.resizeVolume(volume, server, newSize)

        then:
        !response.success

        where:
        newSize << [1024L, 2048L, 10240L, null]
    }

    // createDatastore Tests
    def "createDatastore should return not implemented"() {
        given:
        def datastore = Mock(Datastore)

        when:
        def response = provider.createDatastore(datastore)

        then:
        !response.success
        response.errors?.error == "Not Implemented"
    }

    def "createDatastore should handle null datastore"() {
        when:
        def response = provider.createDatastore(null)

        then:
        !response.success
    }

    // removeDatastore Tests
    def "removeDatastore should return not implemented"() {
        given:
        def datastore = Mock(Datastore)

        when:
        def response = provider.removeDatastore(datastore)

        then:
        !response.success
        response.errors?.error == "Not Implemented"
    }

    def "removeDatastore should handle null datastore"() {
        when:
        def response = provider.removeDatastore(null)

        then:
        !response.success
    }

    // prepareHostForVolume Tests
    def "prepareHostForVolume should return not implemented"() {
        given:
        def cluster = Mock(ComputeServerGroup)
        def server = Mock(ComputeServer)
        def volume = Mock(StorageVolume)

        when:
        def response = provider.prepareHostForVolume(cluster, server, volume)

        then:
        !response.success
        response.errors?.error == "Not Implemented"
    }

    def "prepareHostForVolume should handle null parameters"() {
        when:
        def response = provider.prepareHostForVolume(null, null, null)

        then:
        !response.success
    }

    // buildDiskConfig Tests
    def "buildDiskConfig should return not implemented"() {
        given:
        def cluster = Mock(ComputeServerGroup)
        def server = Mock(ComputeServer)
        def volume = Mock(StorageVolume)

        when:
        def response = provider.buildDiskConfig(cluster, server, volume)

        then:
        !response.success
        response.errors?.error == "Not Implemented"
    }

    def "buildDiskConfig should handle null parameters"() {
        when:
        def response = provider.buildDiskConfig(null, null, null)

        then:
        !response.success
    }

    // releaseVolumeFromHost Tests
    def "releaseVolumeFromHost should return not implemented"() {
        given:
        def cluster = Mock(ComputeServerGroup)
        def server = Mock(ComputeServer)
        def volume = Mock(StorageVolume)

        when:
        def response = provider.releaseVolumeFromHost(cluster, server, volume)

        then:
        !response.success
        response.errors?.error == "Not Implemented"
    }

    def "releaseVolumeFromHost should handle null cluster"() {
        given:
        def server = Mock(ComputeServer)
        def volume = Mock(StorageVolume)

        when:
        def response = provider.releaseVolumeFromHost(null, server, volume)

        then:
        !response.success
    }

    // createSnapshot Tests
    def "createSnapshot should return not implemented"() {
        given:
        def server = Mock(ComputeServer)

        when:
        def response = provider.createSnapshot(server, false, false)

        then:
        !response.success
        response.errors?.error == "Not Implemented"
    }

    def "createSnapshot should handle null server"() {
        when:
        def response = provider.createSnapshot(null, false, false)

        then:
        !response.success
    }

    @Unroll
    def "createSnapshot with different flags: forBackup=#forBackup, forExport=#forExport"() {
        given:
        def server = Mock(ComputeServer)

        when:
        def response = provider.createSnapshot(server, forBackup, forExport)

        then:
        !response.success

        where:
        forBackup | forExport
        true      | true
        true      | false
        false     | true
        false     | false
    }

    // revertSnapshot Tests
    def "revertSnapshot should return not implemented"() {
        given:
        def server = Mock(ComputeServer)
        def snapshot = Mock(Snapshot)

        when:
        def response = provider.revertSnapshot(server, snapshot)

        then:
        !response.success
        response.errors?.error == "Not Implemented"
    }

    def "revertSnapshot should handle null server"() {
        given:
        def snapshot = Mock(Snapshot)

        when:
        def response = provider.revertSnapshot(null, snapshot)

        then:
        !response.success
    }

    def "revertSnapshot should handle null snapshot"() {
        given:
        def server = Mock(ComputeServer)

        when:
        def response = provider.revertSnapshot(server, null)

        then:
        !response.success
    }

    // removeSnapshot Tests
    def "removeSnapshot should return not implemented"() {
        given:
        def server = Mock(ComputeServer)
        def snapshot = Mock(Snapshot)

        when:
        def response = provider.removeSnapshot(server, snapshot)

        then:
        !response.success
        response.errors?.error == "Not Implemented"
    }

    def "removeSnapshot should handle null server"() {
        given:
        def snapshot = Mock(Snapshot)

        when:
        def response = provider.removeSnapshot(null, snapshot)

        then:
        !response.success
    }

    def "removeSnapshot should handle null snapshot"() {
        given:
        def server = Mock(ComputeServer)

        when:
        def response = provider.removeSnapshot(server, null)

        then:
        !response.success
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

    def "should handle concurrent getCreatable calls"() {
        given:
        def results = Collections.synchronizedList([])

        when:
        def threads = (1..5).collect {
            Thread.start {
                results.add(provider.getCreatable())
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

    def "getProvisionTypeCode should be idempotent"() {
        when:
        def codes = (1..10).collect { provider.getProvisionTypeCode() }

        then:
        codes.unique().size() == 1
    }

    def "getStorageProviderCode should be idempotent"() {
        when:
        def codes = (1..10).collect { provider.getStorageProviderCode() }

        then:
        codes.unique().size() == 1
    }

    // Provider Instance Tests
    def "multiple provider instances should be independent"() {
        given:
        def provider1 = new HpeHarvesterDatastoreTypeProvider(plugin, morpheusContext)
        def provider2 = new HpeHarvesterDatastoreTypeProvider(Mock(HpeHarvesterPlugin), Mock(MorpheusContext))

        expect:
        !provider1.is(provider2)
        provider1.getCode() == provider2.getCode()
        provider1.getName() == provider2.getName()
    }

    def "provider should maintain state consistency"() {
        when:
        provider.getCode()
        provider.getName()
        provider.getOptionTypes()

        then:
        provider.plugin == plugin
        provider.context == morpheusContext
    }

    // Boolean Flags Consistency Tests
    def "boolean flags should have expected values"() {
        expect:
        provider.getCreatable() == true
        provider.getEditable() == true
        provider.getRemovable() == true
    }

    def "boolean flags should be consistent across calls"() {
        when:
        def creatable1 = provider.getCreatable()
        def creatable2 = provider.getCreatable()
        def editable1 = provider.getEditable()
        def editable2 = provider.getEditable()
        def removable1 = provider.getRemovable()
        def removable2 = provider.getRemovable()

        then:
        creatable1 == creatable2
        editable1 == editable2
        removable1 == removable2
    }

    // Interface Coverage Tests
    @Unroll
    def "DatastoreTypeProvider method should exist: #methodName"() {
        expect:
        provider.respondsTo(methodName)

        where:
        methodName << ['getCode', 'getName', 'getMorpheus', 'getPlugin',
                       'getProvisionTypeCode', 'getStorageProviderCode',
                       'getOptionTypes', 'getCreatable', 'getEditable', 'getRemovable']
    }

    @Unroll
    def "MvmProvisionFacet method should exist: #methodName"() {
        expect:
        provider.respondsTo(methodName)

        where:
        methodName << ['createVolume', 'removeVolume', 'cloneVolume',
                       'resizeVolume', 'createDatastore', 'removeDatastore',
                       'prepareHostForVolume', 'buildDiskConfig', 'releaseVolumeFromHost']
    }

    @Unroll
    def "SnapshotServerFacet method should exist: #methodName"() {
        expect:
        provider.respondsTo(methodName)

        where:
        methodName << ['createSnapshot', 'revertSnapshot', 'removeSnapshot']
    }

    def "removeVolume should reload volume and delegate delete to HarvesterVolumes"() {
        given:
        def inputVolume = Mock(StorageVolume) {
            getId() >> 501L
            getName() >> 'input-volume'
        }
        def reloadedVolume = Mock(StorageVolume) {
            getSourceId() >> null
        }
        def server = Mock(ComputeServer) {
            getName() >> 'volume-server'
        }

        // Create fresh context with proper mock chain
        def testContext = Stub(MorpheusContext)
        def volumeService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousStorageVolumeService')
        def storageService = typedMock('com.morpheusdata.core.MorpheusSynchronousStorageService')
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        testContext.getServices() >> services
        testContext.services >> services
        services.getStorage() >> storageService
        services.storage >> storageService
        storageService.getVolume() >> volumeService
        storageService.volume >> volumeService
        volumeService.get(501L) >> reloadedVolume

        // Create provider with test context
        def testProvider = new HpeHarvesterDatastoreTypeProvider(plugin, testContext)

        GroovyMock(com.hpe.morpheus.harvester.plugin.volumes.HarvesterVolumes, global: true)
        def harvesterVolumes = Mock(com.hpe.morpheus.harvester.plugin.volumes.HarvesterVolumes)
        1 * new com.hpe.morpheus.harvester.plugin.volumes.HarvesterVolumes(testContext) >> harvesterVolumes
        1 * harvesterVolumes.deleteHarvesterVolume(reloadedVolume, server, true) >> ServiceResponse.success([removed: true])

        when:
        def response = testProvider.removeVolume(inputVolume, server, false, true)

        then:
        response.success
        response.data.removed == true
    }

    def "createVolume should delegate to HarvesterVolumes create method"() {
        given:
        def volume = Mock(StorageVolume) { getId() >> 601L }
        def server = Mock(ComputeServer) {
            getName() >> 'create-server'
            getExternalId() >> 'ext-create'
        }

        GroovyMock(com.hpe.morpheus.harvester.plugin.volumes.HarvesterVolumes, global: true)
        def harvesterVolumes = Mock(com.hpe.morpheus.harvester.plugin.volumes.HarvesterVolumes)
        def delegated = new ServiceResponse<StorageVolume>(success: true, data: volume)
        1 * new com.hpe.morpheus.harvester.plugin.volumes.HarvesterVolumes(morpheusContext) >> harvesterVolumes
        1 * harvesterVolumes.createHarvesterVolume(volume, server) >> delegated

        when:
        def response = provider.createVolume(volume, server)

        then:
        response.success
        response.data == volume
    }

    def "cloneVolume should delegate to HarvesterVolumes clone method"() {
        given:
        def targetVolume = Mock(StorageVolume) { getVolumeName() >> 'target-v' }
        def sourceVolume = Mock(StorageVolume) { getVolumeName() >> 'source-v' }
        def server = Mock(ComputeServer) {
            getName() >> 'clone-server'
            getExternalId() >> 'ext-clone'
        }

        GroovyMock(com.hpe.morpheus.harvester.plugin.volumes.HarvesterVolumes, global: true)
        def harvesterVolumes = Mock(com.hpe.morpheus.harvester.plugin.volumes.HarvesterVolumes)
        def delegated = new ServiceResponse<StorageVolume>(success: true, data: targetVolume)
        1 * new com.hpe.morpheus.harvester.plugin.volumes.HarvesterVolumes(morpheusContext) >> harvesterVolumes
        1 * harvesterVolumes.cloneHarvesterVolume(targetVolume, server, sourceVolume) >> delegated

        when:
        def response = provider.cloneVolume(targetVolume, server, sourceVolume)

        then:
        response.success
        response.data == targetVolume
    }
}
