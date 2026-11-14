package com.hpe.morpheus.harvester.plugin.datasets

import com.hpe.morpheus.harvester.plugin.HpeHarvesterPlugin
import com.hpe.morpheus.harvester.plugin.utils.DBUtils
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DatasetQuery
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.VirtualImage
import spock.lang.Specification
import spock.lang.Unroll

class HpeHarvesterImageDataSetProviderSpec extends Specification {

    MorpheusContext morpheusContext
    HpeHarvesterPlugin plugin
    HpeHarvesterImageDataSetProvider provider

    def setup() {
        morpheusContext = Mock(MorpheusContext)
        plugin = Mock(HpeHarvesterPlugin)
        provider = new HpeHarvesterImageDataSetProvider(plugin, morpheusContext)
    }

    def cleanup() {
        GroovySystem.metaClassRegistry.removeMetaClass(DBUtils)
    }

    private def typedMock(String className) {
        Mock((Class) Class.forName(className))
    }

    private void setupImageServices(List<VirtualImage> images) {
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def imageService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def cloudService = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService')
        def cloudPoolService = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService')

        morpheusContext.services >> services
        morpheusContext.getServices() >> services
        services.virtualImage >> imageService
        services.getVirtualImage() >> imageService
        services.cloud >> cloudService
        services.getCloud() >> cloudService
        cloudService.pool >> cloudPoolService
        cloudService.getPool() >> cloudPoolService

        imageService.list(_) >> images
        images.each { image ->
            imageService.get(image.id as Long) >> image
        }
    }

    def "getInfo should return expected dataset metadata"() {
        when:
        def info = provider.getInfo()

        then:
        info.name == 'Harvester Images'
        info.namespace == null
        info.key == 'harvesterVirtualImages'
        info.description == 'List the available virtual images for Harvester'
    }

    def "getItemType and plugin flag are correct"() {
        expect:
        provider.getItemType() == VirtualImage
        provider.isPlugin()
    }

    def "item returns image from virtual image service"() {
        given:
        def image = new VirtualImage(id: 9L, name: 'img-9')
        setupImageServices([image])

        when:
        def result = provider.item(9L)

        then:
        result.id == 9L
        result.name == 'img-9'
    }

    @Unroll
    def "fetchItem should handle value #value"() {
        given:
        setupImageServices([new VirtualImage(id: 15L, name: 'img-15')])

        when:
        def result = provider.fetchItem(value)

        then:
        result?.id == expectedId

        where:
        value      || expectedId
        15L        || 15L
        '15'       || 15L
        'x15'      || null
        [id: 15L]  || null
        null       || null
        ''         || null
    }

    def "itemName and itemValue map image fields"() {
        given:
        def image = new VirtualImage(id: 21L, name: 'fedora')

        expect:
        provider.itemName(image) == 'fedora'
        provider.itemValue(image) == 21L
    }

    def "listOptions should return mapped image options for map-style args"() {
        given:
        GroovyMock(DBUtils, global: true)
        // DBUtils is fully mocked here: resolve a pool's Harvester connection from its linked server group
        DBUtils.resolveConnection(_, _) >> { args -> args[1]?.serverGroup }
        def pool = new CloudPool(id: 11L, serverGroup: new ComputeServerGroup(id: 101L))
        DBUtils.getZonePoolFromIdString(morpheusContext, 'pool-11') >> pool
        setupImageServices([
            new VirtualImage(id: 1L, name: 'rhel'),
            new VirtualImage(id: 2L, name: 'fedora')
        ])
        def datasetQuery = new DatasetQuery(parameters: [accountId: 1L, config: [resourcePoolId: 'pool-11']])

        when:
        def result = provider.listOptions(datasetQuery).toList().blockingGet()

        then:
        result == [[name: 'rhel', value: 1L], [name: 'fedora', value: 2L]]
    }

    def "list should support list-style args path for accountId extraction"() {
        given:
        GroovyMock(DBUtils, global: true)
        // DBUtils is fully mocked here: resolve a pool's Harvester connection from its linked server group
        DBUtils.resolveConnection(_, _) >> { args -> args[1]?.serverGroup }
        def pool = new CloudPool(id: 22L, serverGroup: new ComputeServerGroup(id: 202L))
        DBUtils.getZonePoolFromIdString(morpheusContext, 'pool-22') >> pool
        setupImageServices([new VirtualImage(id: 3L, name: 'ubuntu')])
        def datasetQuery = new DatasetQuery(parameters: [[accountId: 2L, config: [resourcePoolId: 'pool-22']]])

        when:
        def result = provider.list(datasetQuery).toList().blockingGet()

        then:
        result == [[name: 'ubuntu', value: 3L]]
    }

    def "listOptions returns empty when accountId is missing"() {
        given:
        def datasetQuery = Mock(DatasetQuery)
        datasetQuery.getParameters() >> [config: [resourcePoolId: 'pool-1']]

        when:
        def result = provider.listOptions(datasetQuery).toList().blockingGet()

        then:
        result == []
    }

    def "listOptions should return empty when image query returns none"() {
        given:
        GroovyMock(DBUtils, global: true)
        // DBUtils is fully mocked here: resolve a pool's Harvester connection from its linked server group
        DBUtils.resolveConnection(_, _) >> { args -> args[1]?.serverGroup }
        def pool = new CloudPool(id: 33L, serverGroup: new ComputeServerGroup(id: 303L))
        DBUtils.getZonePoolFromIdString(morpheusContext, 'pool-33') >> pool
        setupImageServices([])
        def datasetQuery = Mock(DatasetQuery)
        datasetQuery.getParameters() >> [accountId: 3L, config: [resourcePoolId: 'pool-3']]

        when:
        def result = provider.listOptions(datasetQuery).toList().blockingGet()

        then:
        result == []
    }

    def "list should return empty on exception in fetchImages"() {
        given:
        GroovyMock(DBUtils, global: true)
        // DBUtils is fully mocked here: resolve a pool's Harvester connection from its linked server group
        DBUtils.resolveConnection(_, _) >> { args -> args[1]?.serverGroup }
        DBUtils.getZonePoolFromIdString(morpheusContext, 'pool-x') >> { throw new RuntimeException('pool lookup failed') }
        setupImageServices([])
        def datasetQuery = Mock(DatasetQuery)
        datasetQuery.getParameters() >> [accountId: 9L, config: [resourcePoolId: 'pool-x']]

        when:
        def result = provider.list(datasetQuery).toList().blockingGet()

        then:
        result == []
    }

    def "list handles null datasetQuery"() {
        when:
        def result = provider.list(null)

        then:
        def error = thrown(Exception)
        error != null
    }

    def "listOptions handles null config"() {
        given:
        def datasetQuery = Mock(DatasetQuery)
        datasetQuery.getParameters() >> [accountId: 5L, config: null]

        when:
        def result = provider.listOptions(datasetQuery).toList().blockingGet()

        then:
        result == []
    }

    def "list handles empty parameters map"() {
        given:
        def datasetQuery = Mock(DatasetQuery)
        datasetQuery.getParameters() >> [:]

        when:
        def result = provider.list(datasetQuery).toList().blockingGet()

        then:
        result == []
    }

    def "getInfo returns consistent values across multiple calls"() {
        when:
        def info1 = provider.getInfo()
        def info2 = provider.getInfo()

        then:
        info1.key == info2.key
        info1.name == info2.name
        info1.description == info2.description
    }
}
