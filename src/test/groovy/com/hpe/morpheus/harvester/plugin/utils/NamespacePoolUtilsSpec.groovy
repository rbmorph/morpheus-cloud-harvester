// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.utils

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.synchronous.compute.MorpheusSynchronousComputeServerGroupService
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.CloudPool
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Namespaces as resource pools: DBUtils helpers
 */
class NamespacePoolUtilsSpec extends Specification {

    MorpheusContext context = Mock(MorpheusContext)

    def "isNamespacePool checks the pool category"() {
        expect:
        DBUtils.isNamespacePool(new CloudPool(type: 'default', category: 'harvester.namespace.cluster.55'))
        DBUtils.isNamespacePool(new CloudPool(type: 'namespace', category: 'harvester.namespace.cluster.55'))
        !DBUtils.isNamespacePool(new CloudPool(type: 'default', category: 'harvester.cluster.55'))
        !DBUtils.isNamespacePool(new CloudPool(type: 'default'))
        !DBUtils.isNamespacePool(null)
    }

    def "addMissingPools adds only pools that are not assigned yet"() {
        given:
        def a = new CloudPool(name: 'a'); a.id = 1L
        def b = new CloudPool(name: 'b'); b.id = 2L
        def c = new CloudPool(name: 'c'); c.id = 3L
        List<CloudPool> assigned = [a]

        expect:
        DBUtils.addMissingPools(assigned, [a, b, null, c])
        assigned*.id == [1L, 2L, 3L]
        !DBUtils.addMissingPools(assigned, [b, c])
        !DBUtils.addMissingPools(assigned, null)
    }

    @Unroll
    def "applyNamespaceFromPool uses the namespace pool (#poolId)"() {
        given:
        GroovySpy(DBUtils, global: true)
        DBUtils.getZonePoolFromIdString(context, 'pool-12') >> new CloudPool(name: 'team-a', type: HarvesterConstants.NAMESPACE_TYPE, category: 'harvester.namespace.cluster.55')
        def opts = [config: [resourcePoolId: poolId, project: [name: 'other']]]

        when:
        def namespace = DBUtils.applyNamespaceFromPool(context, opts)

        then:
        namespace == 'team-a'
        opts.config.project == [name: 'team-a']

        where:
        poolId << ['pool-12', 12L, '12']
    }

    def "applyNamespaceFromPool reads the resource pool field of the provision form"() {
        given:
        GroovySpy(DBUtils, global: true)
        DBUtils.getZonePoolFromIdString(context, 'pool-12') >> new CloudPool(name: 'team-a', type: HarvesterConstants.NAMESPACE_TYPE, category: 'harvester.namespace.cluster.55')
        def opts = [config: [harvesterResourcePoolId: 'pool-12']]

        expect:
        DBUtils.applyNamespaceFromPool(context, opts) == 'team-a'
    }

    @Unroll
    def "applyNamespaceFromPool keeps the chosen namespace, or default, for the cluster-wide pool (#project)"() {
        given:
        GroovySpy(DBUtils, global: true)
        DBUtils.getZonePoolFromIdString(context, 'pool-1') >> new CloudPool(name: 'harv', type: HarvesterConstants.RESOURCE_POOL_TYPE_DEFAULT)
        def opts = [config: [resourcePoolId: 'pool-1', project: project]]

        expect:
        DBUtils.applyNamespaceFromPool(context, opts) == expected
        opts.config.project == [name: expected]

        where:
        project          || expected
        [name: 'ns-a']   || 'ns-a'
        'ns-b'           || 'ns-b'
        null             || 'default'
    }

    def "applyNamespaceFromPool leaves opts alone without a pool"() {
        given:
        def opts = [config: [project: [name: 'p']]]

        expect:
        DBUtils.applyNamespaceFromPool(context, opts) == null
        opts.config.project == [name: 'p']
        DBUtils.applyNamespaceFromPool(context, null) == null
        DBUtils.applyNamespaceFromPool(context, [:]) == null
    }

    def "applyNamespaceFromPool ignores a pool that can't be found"() {
        given:
        GroovySpy(DBUtils, global: true)
        DBUtils.getZonePoolFromIdString(context, 'pool-99') >> null
        def opts = [config: [resourcePoolId: 'pool-99']]

        expect:
        DBUtils.applyNamespaceFromPool(context, opts) == null
        opts.config.project == null
    }

    def "resolveConnection finds the connection of a namespace pool from its category"() {
        given:
        def services = Mock(MorpheusServices)
        def clusterService = Mock(MorpheusSynchronousComputeServerGroupService)
        context.getServices() >> services
        services.getCluster() >> clusterService
        def connection = new ComputeServerGroup(id: 55L, name: 'harv')
        def pool = new CloudPool(name: 'team-a', category: 'harvester.namespace.cluster.55')

        when:
        def result = DBUtils.resolveConnection(context, pool)

        then:
        1 * clusterService.get(55L) >> connection
        result.is(connection)
    }

    def "resolveConnection falls back to the cloud's connection"() {
        given:
        def services = Mock(MorpheusServices)
        def clusterService = Mock(MorpheusSynchronousComputeServerGroupService)
        context.getServices() >> services
        services.getCluster() >> clusterService
        def cloud = new Cloud(); cloud.id = 7L
        def connection = new ComputeServerGroup(id: 55L)
        def pool = new CloudPool(name: 'other', category: 'something.else', cloud: cloud)

        when:
        def result = DBUtils.resolveConnection(context, pool)

        then:
        1 * clusterService.find(_) >> connection
        result.is(connection)
    }

    def "resolveConnection uses the linked server group of older pools"() {
        given:
        def connection = new ComputeServerGroup(id: 9L)
        def pool = new CloudPool(name: 'old', serverGroup: connection)

        expect:
        DBUtils.resolveConnection(context, pool).is(connection)
        DBUtils.resolveConnection(context, null) == null
    }

    def "namespace pools of a Harvester cloud are not linked to the connection"() {
        given:
        def cloudConnection = new ComputeServerGroup(id: 1L)
        cloudConnection.setConfigProperty(HarvesterConstants.CONFIG_MANAGED_BY_CLOUD, true)
        def oldCluster = new ComputeServerGroup(id: 2L)

        expect:
        DBUtils.namespacePoolServerGroup(cloudConnection) == null
        DBUtils.namespacePoolServerGroup(oldCluster).is(oldCluster)
    }
}
