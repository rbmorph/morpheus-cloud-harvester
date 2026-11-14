// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.datasets

import com.hpe.morpheus.harvester.plugin.HpeHarvesterPlugin
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.data.DatasetInfo
import com.morpheusdata.core.data.DatasetQuery
import com.morpheusdata.core.providers.AbstractDatasetProvider
import com.morpheusdata.core.util.MorpheusUtils
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.VirtualImage
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

/**
 * Lists Harvester virtual images for the Node Type (container type) form in the Library.
 *
 * Unlike {@link HpeHarvesterImageDataSetProvider}, this provider does not need a resource pool
 * from the provisioning wizard: the Library node type / layout editors have no cloud or pool
 * context. It returns the templates synced from every Harvester cluster the account can see,
 * labelled with the cluster name so same-named templates on different clusters can be told apart.
 */
@Slf4j
class HpeHarvesterNodeImageDataSetProvider extends AbstractDatasetProvider<VirtualImage, Long> {
    public static final providerName = 'Harvester Node Images'
    public static final providerNamespace = null
    public static final providerKey = 'harvesterNodeImages'
    public static final providerDescription = 'List the Harvester virtual images available for node types'

    static final String TEMPLATE_CATEGORY_PREFIX = 'harvester.image.template.'

    protected MorpheusContext context
    protected HpeHarvesterPlugin plugin

    HpeHarvesterNodeImageDataSetProvider(HpeHarvesterPlugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheusContext = morpheus
    }

    @Override
    DatasetInfo getInfo() {
        new DatasetInfo(
                name: providerName,
                namespace: providerNamespace,
                key: providerKey,
                description: providerDescription
        )
    }

    @Override
    Class<VirtualImage> getItemType() {
        return VirtualImage.class
    }

    @Override
    Observable<VirtualImage> list(DatasetQuery datasetQuery) {
        return Observable.fromIterable(fetchImages(datasetQuery?.parameters, searchPhrase(datasetQuery)))
    }

    @Override
    Observable<VirtualImage> listOptions(DatasetQuery datasetQuery) {
        return Observable.fromIterable(fetchImages(datasetQuery?.parameters, searchPhrase(datasetQuery)))
    }

    /**
     * The text typed into the typeahead. Morpheus sends it as the query phrase
     * (or as a 'phrase' request parameter, depending on the caller).
     */
    protected static String searchPhrase(DatasetQuery datasetQuery) {
        if (datasetQuery == null) {
            return null
        }
        def params = datasetQuery.parameters
        def first = params instanceof List ? (params.size() > 0 ? params.getAt(0) : null) : params
        def phrase = datasetQuery.phrase ?: (first instanceof Map ? first.phrase : null)
        return phrase?.toString()?.trim() ?: null
    }

    @Override
    VirtualImage fetchItem(Object value) {
        def rtn = null
        if (value instanceof Long) {
            rtn = item((Long) value)
        } else if (value instanceof CharSequence) {
            def longValue = MorpheusUtils.parseLongConfig(value)
            if (longValue) {
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

    protected List<Map> fetchImages(args, String phrase = null) {
        log.debug("fetchNodeImages called with args: ${args}")
        try {
            def params = args instanceof List ? (args.size() > 0 ? args.getAt(0) : null) : args
            def accountId = params?.accountId?.toString()?.isLong() ? params.accountId.toString().toLong() : null
            if (!accountId) {
                log.warn('fetchNodeImages: no accountId provided, returning empty list')
                return []
            }

            DataQuery query = new DataQuery()
                    .withJoins('owner', 'accounts')
                    .withSort('name', DataQuery.SortOrder.asc)
                    .withFilters(
                            new DataFilter('active', true),
                            new DataFilter('deleted', false),
                            new DataFilter('imageType', 'in', ['qcow2', 'raw', 'iso']),
                            new DataFilter('status', '!=', 'Saving'),
                            new DataFilter('status', '!=', 'Failed'),
                            new DataFilter('status', '!=', 'Converting'),
                            new DataFilter('refType', HarvesterConstants.COMPUTE_SERVER_GROUP),
                            new DataOrFilter(
                                    new DataFilter('visibility', 'public'),
                                    new DataFilter('owner.id', accountId),
                                    new DataFilter('owner.id', null),
                                    new DataFilter('accounts.id', accountId)
                            )
                    )

            List<VirtualImage> images = (morpheusContext.services.virtualImage.list(query) ?: [])
                    .findAll { it?.category?.startsWith(TEMPLATE_CATEGORY_PREFIX) }
                    .unique { it.id }

            Map<String, String> clusterNames = [:]
            List<Map> options = images.collect { VirtualImage image ->
                String clusterId = image.category.substring(TEMPLATE_CATEGORY_PREFIX.length())
                String clusterName = clusterNames.computeIfAbsent(clusterId) { lookupClusterName(it) }
                [name: clusterName ? "${image.name} (${clusterName})".toString() : image.name, value: image.id]
            }
            if (phrase) {
                String needle = phrase.toLowerCase()
                options = options.findAll { it.name?.toLowerCase()?.contains(needle) }
            }
            log.debug("fetchNodeImages: returning ${options.size()} images")
            return options
        } catch (Exception e) {
            log.error("fetchNodeImages: error fetching images: ${e.message}", e)
            return []
        }
    }

    private String lookupClusterName(String clusterId) {
        try {
            if (clusterId?.isLong()) {
                ComputeServerGroup cluster = morpheusContext.services.cluster.get(clusterId.toLong())
                return cluster?.name
            }
        } catch (Exception e) {
            log.debug("fetchNodeImages: could not resolve cluster ${clusterId}: ${e.message}")
        }
        return null
    }
}
