package com.hpe.morpheus.harvester.plugin.datasets

import com.hpe.morpheus.harvester.plugin.HpeHarvesterPlugin
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DatasetQuery
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.VirtualImage
import spock.lang.Specification

class HpeHarvesterNodeImageDataSetProviderSpec extends Specification {

    MorpheusContext morpheusContext
    HpeHarvesterPlugin plugin
    HpeHarvesterNodeImageDataSetProvider provider

    def setup() {
        morpheusContext = Mock(MorpheusContext)
        plugin = Mock(HpeHarvesterPlugin)
        provider = new HpeHarvesterNodeImageDataSetProvider(plugin, morpheusContext)
    }

    private def typedMock(String className) {
        Mock((Class) Class.forName(className))
    }

    private void setupServices(List<VirtualImage> images, Map<Long, ComputeServerGroup> clusters = [:]) {
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def imageService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        def clusterService = typedMock('com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService')

        morpheusContext.services >> services
        morpheusContext.getServices() >> services
        services.virtualImage >> imageService
        services.getVirtualImage() >> imageService
        services.cluster >> clusterService
        services.getCluster() >> clusterService

        imageService.list(_) >> images
        images.each { image -> imageService.get(image.id as Long) >> image }
        clusterService.get(_ as Long) >> { Long id -> clusters[id] }
    }

    def "getInfo exposes the harvesterNodeImages key"() {
        when:
        def info = provider.getInfo()

        then:
        info.key == 'harvesterNodeImages'
        info.namespace == null
        provider.getItemType() == VirtualImage
        provider.isPlugin()
    }

    def "listOptions works without a resource pool and labels images with their cluster"() {
        given:
        setupServices([
                new VirtualImage(id: 1L, name: 'rhel9', category: 'harvester.image.template.10'),
                new VirtualImage(id: 2L, name: 'fedora', category: 'harvester.image.template.20'),
                new VirtualImage(id: 3L, name: 'not-harvester', category: 'other.cluster.template.10')
        ], [10L: new ComputeServerGroup(id: 10L, name: 'ocp-east'), 20L: new ComputeServerGroup(id: 20L, name: 'ocp-west')])
        def datasetQuery = new DatasetQuery(parameters: [accountId: 1L])

        when:
        def result = provider.listOptions(datasetQuery).toList().blockingGet()

        then:
        result == [[name: 'rhel9 (ocp-east)', value: 1L], [name: 'fedora (ocp-west)', value: 2L]]
    }

    def "list supports list-style args and falls back to the image name when the cluster is unknown"() {
        given:
        setupServices([new VirtualImage(id: 4L, name: 'centos', category: 'harvester.image.template.99')])
        def datasetQuery = new DatasetQuery(parameters: [[accountId: 2L]])

        when:
        def result = provider.list(datasetQuery).toList().blockingGet()

        then:
        result == [[name: 'centos', value: 4L]]
    }

    def "listOptions filters by the typeahead search phrase (case-insensitive)"() {
        given:
        setupServices([
                new VirtualImage(id: 1L, name: 'rhel9-server', category: 'harvester.image.template.10'),
                new VirtualImage(id: 2L, name: 'fedora', category: 'harvester.image.template.10'),
                new VirtualImage(id: 3L, name: 'RHEL8', category: 'harvester.image.template.20')
        ], [10L: new ComputeServerGroup(id: 10L, name: 'ocp-east'), 20L: new ComputeServerGroup(id: 20L, name: 'ocp-west')])
        def datasetQuery = new DatasetQuery(parameters: [accountId: 1L])
        datasetQuery.phrase = phrase

        when:
        def result = provider.listOptions(datasetQuery).toList().blockingGet()

        then:
        result*.value == expected

        where:
        phrase  | expected
        'rhel'  | [1L, 3L]
        'WEST'  | [3L]
        'nope'  | []
        '  '    | [1L, 2L, 3L]
        null    | [1L, 2L, 3L]
    }

    def "listOptions reads the phrase from request parameters"() {
        given:
        setupServices([
                new VirtualImage(id: 1L, name: 'rhel9', category: 'harvester.image.template.10'),
                new VirtualImage(id: 2L, name: 'fedora', category: 'harvester.image.template.10')
        ])
        def datasetQuery = new DatasetQuery(parameters: [accountId: 1L, phrase: 'fed'])

        when:
        def result = provider.listOptions(datasetQuery).toList().blockingGet()

        then:
        result == [[name: 'fedora', value: 2L]]
    }

    def "listOptions returns empty when accountId is missing"() {
        given:
        def datasetQuery = new DatasetQuery(parameters: [:])

        when:
        def result = provider.listOptions(datasetQuery).toList().blockingGet()

        then:
        result == []
    }

    def "listOptions returns empty when the image query fails"() {
        given:
        def services = typedMock('com.morpheusdata.core.MorpheusServices')
        def imageService = typedMock('com.morpheusdata.core.synchronous.MorpheusSynchronousVirtualImageService')
        morpheusContext.getServices() >> services
        services.getVirtualImage() >> imageService
        imageService.list(_) >> { throw new RuntimeException('db down') }
        def datasetQuery = new DatasetQuery(parameters: [accountId: 5L])

        when:
        def result = provider.listOptions(datasetQuery).toList().blockingGet()

        then:
        result == []
    }

    def "fetchItem resolves Long and String ids"() {
        given:
        def image = new VirtualImage(id: 7L, name: 'rhel')
        setupServices([image])

        expect:
        provider.fetchItem(7L) == image
        provider.fetchItem('7') == image
        provider.itemName(image) == 'rhel'
        provider.itemValue(image) == 7L
    }
}
