package com.hpe.morpheus.harvester.plugin.datasets

import com.hpe.morpheus.harvester.plugin.HpeHarvesterPlugin
import com.hpe.morpheus.harvester.plugin.utils.DBUtils
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DatasetQuery
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServerGroup
import spock.lang.Specification

class HpeHarvesterProjectProviderSpec extends Specification {

    MorpheusContext morpheusContext
    HpeHarvesterPlugin plugin
    HpeHarvesterProjectProvider provider

    def setup() {
        morpheusContext = Mock(MorpheusContext)
        plugin = Mock(HpeHarvesterPlugin)
        provider = new HpeHarvesterProjectProvider(plugin, morpheusContext)
    }

    def cleanup() {
        GroovySystem.metaClassRegistry.removeMetaClass(DBUtils)
    }

    private def typedMock(String className) {
        Mock((Class) Class.forName(className))
    }

    private void setupCloudPoolServices(List<CloudPool> projects) {
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def cloudService = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService')
        def cloudPoolService = typedMock('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService')

        morpheusContext.services >> services
        morpheusContext.getServices() >> services
        services.cloud >> cloudService
        services.getCloud() >> cloudService
        cloudService.pool >> cloudPoolService
        cloudService.getPool() >> cloudPoolService
        cloudPoolService.list(_ as Object) >> projects
    }

    def "getInfo returns expected dataset metadata"() {
        when:
        def info = provider.getInfo()

        then:
        info.name == 'Harvester projects'
        info.namespace == null
        info.key == 'harvesterProjects'
        info.description == 'List all projects for harvester cluster'
    }

    def "dataset provider core metadata and helpers"() {
        expect:
        provider.getItemType() == Map
        provider.isPlugin()
        provider.item('x') == [:]
        provider.fetchItem('x') == [:]
        provider.fetchItem(1L) == null
        provider.fetchItem(null) == null
        provider.itemName([name: 'proj-a']) == 'proj-a'
        provider.itemValue([value: '12']) == '12'
    }

    def "listOptions should return mapped projects for matching cluster pool"() {
        given:
        GroovyMock(DBUtils, global: true)
        // DBUtils is fully mocked here: resolve a pool's Harvester connection from its linked server group
        DBUtils.resolveConnection(_, _) >> { args -> args[1]?.serverGroup }
        def clusterPool = new CloudPool(id: 88L, serverGroup: new ComputeServerGroup(id: 444L))
        DBUtils.getZonePoolFromIdString(_, _) >> clusterPool
        setupCloudPoolServices([
            new CloudPool(id: 1L, name: 'project-a'),
            new CloudPool(id: 2L, name: 'project-b')
        ])
        def datasetQuery = Mock(DatasetQuery)
        datasetQuery.getParameters() >> [config: [resourcePoolId: 'pool-88']]

        when:
        def result = provider.listOptions(datasetQuery).toList().blockingGet()

        then:
        result == [[name: 'project-a', value: 1L], [name: 'project-b', value: 2L]]
    }

    def "list should delegate to listOptions and return same mapped projects"() {
        given:
        GroovyMock(DBUtils, global: true)
        // DBUtils is fully mocked here: resolve a pool's Harvester connection from its linked server group
        DBUtils.resolveConnection(_, _) >> { args -> args[1]?.serverGroup }
        def clusterPool = new CloudPool(id: 99L, serverGroup: new ComputeServerGroup(id: 555L))
        DBUtils.getZonePoolFromIdString(_, _) >> clusterPool
        setupCloudPoolServices([new CloudPool(id: 9L, name: 'project-z')])
        def datasetQuery = Mock(DatasetQuery)
        datasetQuery.getParameters() >> [config: [resourcePoolId: 'pool-99']]

        when:
        def result = provider.list(datasetQuery).toList().blockingGet()

        then:
        result == [[name: 'project-z', value: 9L]]
    }

    def "listOptions should return empty when no projects are found"() {
        given:
        GroovyMock(DBUtils, global: true)
        // DBUtils is fully mocked here: resolve a pool's Harvester connection from its linked server group
        DBUtils.resolveConnection(_, _) >> { args -> args[1]?.serverGroup }
        def clusterPool = new CloudPool(id: 100L, serverGroup: new ComputeServerGroup(id: 777L))
        DBUtils.getZonePoolFromIdString(_, _) >> clusterPool
        setupCloudPoolServices([])
        def datasetQuery = Mock(DatasetQuery)
        datasetQuery.getParameters() >> [config: [resourcePoolId: 'pool-100']]

        when:
        def result = provider.listOptions(datasetQuery).toList().blockingGet()

        then:
        result == []
    }

    def "list handles null datasetQuery"() {
        when:
        provider.list(null)

        then:
        thrown(Exception)
    }

    def "getInfo returns consistent values"() {
        when:
        def info1 = provider.getInfo()
        def info2 = provider.getInfo()

        then:
        info1.key == info2.key
        info1.name == info2.name
        info1.namespace == info2.namespace
    }
}
