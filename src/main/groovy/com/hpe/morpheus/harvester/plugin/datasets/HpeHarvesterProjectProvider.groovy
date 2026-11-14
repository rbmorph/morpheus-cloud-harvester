package com.hpe.morpheus.harvester.plugin.datasets

import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.hpe.morpheus.harvester.plugin.HpeHarvesterPlugin
import com.hpe.morpheus.harvester.plugin.utils.DBUtils
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataAndFilter
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.data.DatasetInfo
import com.morpheusdata.core.data.DatasetQuery
import com.morpheusdata.core.providers.AbstractDatasetProvider
import com.morpheusdata.model.CloudPool
import io.reactivex.rxjava3.core.Observable
import groovy.util.logging.Slf4j

@Slf4j
class HpeHarvesterProjectProvider extends AbstractDatasetProvider<Map, String> {
    public static final PROVIDER_NAME = "Harvester projects"
    public static final PROVIDER_NAMESPACE = null
    public static final PROVIDER_KEY = "harvesterProjects"
    public static final PROVIDER_DESCRIPTION = "List all projects for harvester cluster"

    protected MorpheusContext context
    protected HpeHarvesterPlugin plugin

    HpeHarvesterProjectProvider(HpeHarvesterPlugin plugin, MorpheusContext morpheusContext) {
        super()
        this.plugin = plugin
        this.morpheusContext = morpheusContext
    }

    /**
     * {@inheritDoc}
     */
    @Override
    DatasetInfo getInfo() {
        new DatasetInfo(
                name: PROVIDER_NAME,
                namespace: PROVIDER_NAMESPACE,
                key: PROVIDER_KEY,
                description: PROVIDER_DESCRIPTION,
        )
    }

    /**
     * {@inheritDoc}
     */
    @Override
    Class<Map> getItemType() {
        return Map.class
    }

    /**
     * {@inheritDoc}
     */
    @Override
    Observable<Map> list(DatasetQuery query) {
        return listOptions(query)
    }

    /**
     * {@inheritDoc}
     */
    @Override
    Observable<Map> listOptions(DatasetQuery datasetQuery) {

        def args = datasetQuery.parameters
        log.info("listOptions called with datasetQuery: ${args}")

        // get the resourcePool object, fetch the pool id from the args
        String resourcePoolId = args?.config?.resourcePoolId
        CloudPool pool = DBUtils.getZonePoolFromIdString(morpheusContext, resourcePoolId)

        def connection = DBUtils.resolveConnection(morpheusContext, pool)
        if (!connection) {
            return Observable.fromIterable([])
        }
        log.info("Fetching projects for pool id ${pool.id} with server group id ${connection.id}")

        // prepare data query and fetch the projects from ComputeZonePool
        DataQuery query = new DataQuery()
        query = query.withFilters(
                new DataAndFilter(
                        new DataFilter("category", "harvester.namespace.cluster.${connection.id}"),
                        new DataFilter('type', 'in', HarvesterConstants.NAMESPACE_TYPES),

                )
        )
        // do the core call here
        List<CloudPool> projects = morpheusContext.services.cloud.pool.list(query).toList()
        log.info("Found ${projects.size()} projects for pool id ${pool.id} with server group id " +
                "${connection.id}")

        def projectsMap = projects ? projects.collect{ it -> [name: it.name, value: it.id]} : []
        return Observable.fromIterable(projectsMap)
    }

    /**
     * {@inheritDoc}
     */
    @Override
    Map fetchItem(Object value) {
        def rtn = null
        if (value instanceof String) {
            rtn = item((String) value)
        }
        return rtn

    }

    /**
     * {@inheritDoc}
     */
    @Override
    Map item(String value) {
        return [:]
    }

    /**
     * {@inheritDoc}
     */
    @Override
    String itemName(Map item) {
        return item.name
    }

    /**
     * {@inheritDoc}
     */
    @Override
    String itemValue(Map item) {
        return item.value
    }

    /**
     * {@inheritDoc}
     */
    boolean isPlugin() {
        return true
    }
}

