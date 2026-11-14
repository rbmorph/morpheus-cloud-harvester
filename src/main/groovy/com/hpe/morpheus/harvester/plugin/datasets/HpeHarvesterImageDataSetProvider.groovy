package com.hpe.morpheus.harvester.plugin.datasets

import com.hpe.morpheus.harvester.plugin.HpeHarvesterPlugin
import com.hpe.morpheus.harvester.plugin.utils.DBUtils
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataAndFilter
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.data.DatasetInfo
import com.morpheusdata.core.data.DatasetQuery
import com.morpheusdata.core.providers.AbstractDatasetProvider
import com.morpheusdata.core.util.MorpheusUtils
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.VirtualImage
import io.reactivex.rxjava3.core.Observable
import groovy.util.logging.Slf4j

@Slf4j
class HpeHarvesterImageDataSetProvider extends AbstractDatasetProvider<VirtualImage, Long> {
    public static final providerName = 'Harvester Images'
    public static final providerNamespace = null // Remove namespace to match direct method lookup
    public static final providerKey = 'harvesterVirtualImages'
    public static final providerDescription = 'List the available virtual images for Harvester'

    protected MorpheusContext context
    protected HpeHarvesterPlugin plugin

    @Override
    DatasetInfo getInfo() {
        new DatasetInfo(
                name: providerName,
                namespace: providerNamespace,
                key: providerKey,
                description: providerDescription
        )
    }

    HpeHarvesterImageDataSetProvider(HpeHarvesterPlugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheusContext = morpheus
    }

    @Override
    Class<VirtualImage> getItemType() {
        return VirtualImage.class
    }

    @Override
    Observable<VirtualImage> list(DatasetQuery datasetQuery) {
        log.info("list called with datasetQuery: ${datasetQuery}")
        def images = fetchImages(datasetQuery.parameters)
        return Observable.fromIterable(images)
    }

    @Override
    Observable<VirtualImage> listOptions(DatasetQuery datasetQuery) {
        log.info("listOptions called with datasetQuery: ${datasetQuery}")
        def images = fetchImages(datasetQuery.parameters)
        return Observable.fromIterable(images)
    }

    @Override
    VirtualImage fetchItem(Object value) {
        def rtn = null
        if(value instanceof Long) {
            rtn = item((Long) value)
        } else if(value instanceof CharSequence) {
            def longValue = MorpheusUtils.parseLongConfig(value)
            if(longValue) {
                rtn = item(longValue)
            }
        }
        return rtn
    }

    @Override
    VirtualImage item(Long value) {
        return morpheusContext.services.virtualImage.get(value.toLong())
    }

    @Override
    String itemName(VirtualImage item) {
        return item.name
    }

    @Override
    Long itemValue(VirtualImage item) {
        return item.id
    }

    boolean isPlugin() {
        return true
    }

    private fetchImages(args) {
        log.info("fetchImages called with args: ${args}")
        
        try {
            // Get account ID from args
            def accountId = args instanceof List && args?.size() > 0 ? args.getAt(0).accountId?.toLong() : args.accountId?.toLong()
            if(!accountId) {
                log.warn("No accountId provided, returning empty list")
                return []
            }

            // fetch the pool id from the args
            String resourcePoolId = args?.config?.resourcePoolId
            CloudPool pool = DBUtils.getZonePoolFromIdString(morpheusContext, resourcePoolId)

            def connection = DBUtils.resolveConnection(morpheusContext, pool)
            if (!connection) {
                log.warn("No Harvester connection found for pool ${pool?.id}")
                return []
            }
            log.info("Fetching images for pool id ${pool.id} with server group id ${connection.id}")

            // Create a proper DataQuery for virtual images
            DataQuery query = new DataQuery()
                .withJoins('locations', 'owner', 'accounts')
                .withSort('name', DataQuery.SortOrder.asc)
                .withFilters(
                    new DataFilter('active', true),
                    new DataFilter('imageType', 'in', ['qcow2', 'raw', 'iso']),
                    new DataFilter('status', '!=', 'Saving'),
                    new DataFilter('status', '!=', 'Failed'),
                    new DataFilter('status', '!=', 'Converting'),
                    new DataOrFilter(
                        new DataFilter('visibility', 'public'),
                        new DataFilter('owner.id', accountId.toString()),
                        new DataFilter('owner.id', null),
                        new DataFilter('accounts.id', accountId.toString())
                    )
                )

            // now add query for the Harvester images specifically, filter by the server group
            query = query.withFilters(
                    new DataOrFilter(
                            new DataFilter('category', "harvester.image.template.${connection.id}"),
                            new DataAndFilter(
                                    new DataFilter('refType', 'ComputeServerGroup'),
                                    new DataFilter('refId', connection.id.toString())
                            )
                    )
            )
            // Use morpheusContext to execute the query
            List<VirtualImage> images = morpheusContext.services.virtualImage.list(query).toList()
            log.info("Found ${images?.size()} virtual images")
            images.each { image ->
                log.info("Fetched image - ID: ${image.id}, Name: ${image.name}")
            }
            return images ? images.collect{ it -> [name: it.name, value: it.id]} : []

        } catch (Exception e) {
            log.error("Error fetching images: ${e.message}", e)
            return []
        }
    }
}
