// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin

import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.hpe.morpheus.harvester.plugin.provision.VirtualMachineDeletion
import com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure
import com.hpe.morpheus.harvester.plugin.sync.DataVolumesSync
import com.hpe.morpheus.harvester.plugin.sync.NodesSync
import com.hpe.morpheus.harvester.plugin.sync.PVCsSync
import com.hpe.morpheus.harvester.plugin.sync.ProjectsSync
import com.hpe.morpheus.harvester.plugin.sync.HarvesterInventorySync
import com.hpe.morpheus.harvester.plugin.sync.StorageClassesSync
import com.hpe.morpheus.harvester.plugin.sync.VMsSync
import com.hpe.morpheus.harvester.plugin.sync.NADsSync
import com.hpe.morpheus.harvester.plugin.sync.VirtualImagesSync
import com.hpe.morpheus.harvester.plugin.utils.DBUtils
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.ClusterProvider
import com.morpheusdata.core.providers.IacResourceMappingProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.core.util.ClusterTabList
import com.morpheusdata.model.*
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class HpeHarvesterClusterProvider implements ClusterProvider, ClusterProvider.ServerPowerFacet {
	private static final String PROVIDER_NAME = 'Harvester Cluster'

	protected MorpheusContext context
	protected HpeHarvesterPlugin plugin

	HpeHarvesterClusterProvider(HpeHarvesterPlugin plugin, MorpheusContext ctx) {
		super()
		log.info("Harvester: Initializing the cluster provider")
		this.plugin = plugin
		this.context = ctx
	}

    ClusterTabList getTabList() {
        ClusterTabList tabList = new ClusterTabList()
        tabList.virtualImage = true
        tabList.vm = true
        tabList.pool = true
        return tabList
    }

	/**
	 * Grabs the description for the CloudProvider
	 * @return String
	 */
	@Override
	String getDescription() {
		return HarvesterConstants.CLUSTER_PROVER_DESCRIPTION
	}

	/**
	 * Returns the Cloud logo for display when a user needs to view or add this cloud. SVGs are preferred.
	 * @since 0.13.0
	 * @return Icon representation of assets stored in the src/assets of the project.
	 */
	@Override
	Icon getIcon() {
		// TODO: change icon paths to correct filenames once added to your project
		return new Icon(path: 'harvester-logo.svg', darkPath: 'harvester-logo-dark.svg')
	}

	/**
	 * Returns the circular Cloud logo for display when a user needs to view or add this cloud. SVGs are preferred.
	 * @since 0.13.6
	 * @return Icon
	 */
	@Override
	Icon getCircularIcon() {
		// TODO: change icon paths to correct filenames once added to your project
		return new Icon(path:'harvester-circular.svg', darkPath:'harvester-circular.svg')
	}

	/**
	 * Provides a Collection of OptionType inputs that define the required input fields for defining a cloud integration
	 * @return Collection of OptionType
	 */
	@Override
	Collection<OptionType> getOptionTypes() {
        log.info("Harvester: getOptionTypes")

        // Option types are now defined in scribe files
        return []
	}

    @Override
    Collection<ComputeType> getControllerTypes() {
        List<ComputeType> controllerTypes = []
        ComputeType harvesterControllerType = new ComputeType(
                name: 'Harvester Master',
                code: 'harvester-master',
                hostType: 'harvesterMaster',
                isPlugin: true,
                isEmbedded: false)
        controllerTypes << harvesterControllerType
        return controllerTypes
    }

    @Override
    Collection<ComputeType> getWorkerTypes() {
        List<ComputeType> workerTypes = []
        ComputeType harvesterWorkerType = new ComputeType(
                name: 'Harvester Worker',
                code: 'harvester-worker',
                hostType: 'harvesterWorker',
                isPlugin: true,
                isEmbedded: false)
        workerTypes << harvesterWorkerType
        return workerTypes
    }

    @Override
    Collection<DatastoreType> getDatastoreTypes() {
        return null
    }

    // Return the default namespace for the cluster, will get listed for provisioning
    @Override
    CloudPool getDefaultNamespace(ComputeServerGroup cluster) {
        DBUtils.createDefaultResourcePool(context, cluster)
        return DBUtils.getClusterResourcePool(context, cluster)
    }

    @Override
    String getProviderType() {
        // any string
        return HarvesterConstants.CLUSTER_PROVIDER_TYPE
    }

    @Override
    ServiceResponse convertClusterToManaged(ComputeServerGroup cluster) {
        return ServiceResponse.success()
    }

    @Override
    ServiceResponse finalizeLinuxComputeServer(ComputeServerGroup cluster, ComputeServer server,  Map opts) {
        log.info('Harvester: finalizeLinuxComputeServer cluster={}, server={}, opts={}', cluster, server, opts)
        server.provision = true
        server.status = HarvesterConstants.STATUS_PROVISIONED
        server.category = "${HarvesterConstants.CATEGORY_HARVESTER_NODE_PREFIX}${cluster.id}"
        context.services.computeServer.save(server)
        //cluster.code =  HarvesterConstants.CLUSTER_CODE
        context.services.cluster.save(cluster)
        return ServiceResponse.success();
    }

	@Override
	ServiceResponse refresh(ComputeServerGroup cluster) {
		log.info('Harvester: Refreshing cluster name={}, status={}', cluster.name, cluster.status)
		try {
            // Reload cluster from database at the very beginning to ensure we have the latest data
            log.debug("Harvester: Reloading cluster from database to ensure fresh data")
            cluster = context.async.cluster.get(cluster.id).blockingGet()
            if (!cluster) {
                log.error("Harvester: Failed to reload cluster from database")
                return new ServiceResponse(success: false, msg: 'Failed to reload cluster data from database')
            }

            // check for cluster state
            def prevStatus = cluster.getConfigProperty('prevStatus')
            if (cluster.status?.name() == HarvesterConstants.STATUS_DEPROVISIONING || prevStatus == HarvesterConstants.STATUS_DEPROVISIONING){
                log.warn("Harvester: Cluster ${cluster.name} is deprovisioning, skipping refresh")
                return ServiceResponse.success()
            }

            def cloud = cluster.cloud
            def importExisting = false
            def importExistingProp = cluster.getConfigProperty('importExisting')
            if (importExistingProp == 'on' || importExistingProp == 'true' || importExistingProp == true) {
                importExisting = true
            }

            log.info("Harvester: refresh cluster: ${cluster.name}," +
                    " cloud: ${cloud.name}, importExisting: ${importExisting}")

            // Clusters created by a Harvester cloud are synced by the cloud refresh
            if (isCloudManaged(cluster)) {
                log.debug("Harvester: cluster ${cluster.name} is managed by its Harvester cloud, skipping cluster refresh")
                return ServiceResponse.success()
            }

            new HarvesterInventorySync(this.plugin, cluster, importExisting).execute()

            ComputeServerGroup serverGroup = context.services.cluster.get(cluster.id)
            if (!serverGroup.provisionComplete){
                log.info("Harvester: Marking provision complete for cluster: ${serverGroup.name}")
                serverGroup.provisionComplete = true
                context.services.cluster.save(serverGroup)
            }
            log.info("Harvester: Completed sync operations for cluster: ${cluster.name}")

        } catch (e) {
			log.error("Harvester: refresh cluster error: ${e}", e)
			return new ServiceResponse(success: false, msg: 'Error refreshing Harvester cluster')
		}
		return ServiceResponse.success()
	}


    static boolean isCloudManaged(ComputeServerGroup cluster) {
        def managedBy = cluster?.getConfigProperty(HarvesterConstants.CONFIG_MANAGED_BY_CLOUD)
        return managedBy == true || managedBy == 'true'
    }

    @Override
    ServiceResponse refreshDaily(ComputeServerGroup cluster) {
        return ServiceResponse.success()
	}

    ServiceResponse updateServerGroup(ComputeServerGroup cluster, Map opts) {
        log.info("Harvester: updateServerGroup cluster=${cluster.name}, opts=${opts}")
        return ServiceResponse.success()
    }

    @Override
    ServiceResponse deleteServer(ComputeServer server) {
        log.info("Harvester: deleteServer for server: ${server.name}")
        try {
            def vmDeletion = new VirtualMachineDeletion(context)
            return vmDeletion.deleteServer(server)
        } catch (Exception e) {
            log.error "Harvester: Error in deleteServer: ${e.message}", e
            return new ServiceResponse(false, "Error in deleteServer: ${e.message}", null, null)
        }
    }

    @Override
    ServiceResponse startServer(ComputeServer server) {
        log.info("Harvester: startServer for server: ${server.name}")
        try {
            def vmReconfigure = new VirtualMachineReconfigure(context, server)
            return vmReconfigure.startVM()
        } catch (Exception e) {
            log.error "Harvester: Error in startServer: ${e.message}", e
            return new ServiceResponse(success: false, msg: "Error in startServer: ${e.message}")
        }
    }

    @Override
    ServiceResponse stopServer(ComputeServer server) {
        log.info("Harvester: stopServer for server: ${server.name}")
        try {
            def vmReconfigure = new VirtualMachineReconfigure(context, server)
            return vmReconfigure.stopVM()
        } catch (Exception e) {
            log.error "Harvester: Error in stopServer: ${e.message}", e
            return new ServiceResponse(success: false, msg: "Error in stopServer: ${e.message}")
        }
    }

    @Override
    ServiceResponse deleteCluster(ComputeServerGroup cluster) {
        log.info("Harvester: deleteCluster for cluster: ${cluster.name}")

        try {
            // First, get all virtual images associated with this cluster
            def virtualImages = context.services.virtualImage.list(
                new DataQuery().withFilters(
                    new DataFilter('refType', HarvesterConstants.COMPUTE_SERVER_GROUP),
                    new DataFilter('refId', cluster.id),
                )
            )

            if (virtualImages) {
                log.debug("Harvester: Found ${virtualImages.size()} virtual images for cluster: ${cluster.name}")

                // Get all storage volumes and controllers associated with these virtual images
                def allStorageVolumes = []
                def allStorageControllers = []
                virtualImages.each { virtualImage ->
                    if (virtualImage.volumes) {
                        log.debug("Harvester: Virtual image ${virtualImage.name} has ${virtualImage.volumes.size()} storage volumes")
                        allStorageVolumes.addAll(virtualImage.volumes)
                    }
                    if (virtualImage.controllers) {
                        log.debug("Harvester: Virtual image ${virtualImage.name} has ${virtualImage.controllers.size()} storage controllers")
                        allStorageControllers.addAll(virtualImage.controllers)
                    }
                }

                // Delete virtual images first to remove foreign key references
                log.info("Harvester: Deleting ${virtualImages.size()} virtual images for cluster: ${cluster.name}")
                context.services.virtualImage.bulkRemove(virtualImages)

                // Then delete orphaned storage volumes
                if (allStorageVolumes) {
                    log.info("Harvester: Deleting ${allStorageVolumes.size()} orphaned storage volumes of virtual images for cluster: ${cluster.name}")
                    context.services.storage.volume.bulkRemove(allStorageVolumes)
                }

                // Then delete orphaned storage controllers
                if (allStorageControllers) {
                    log.info("Harvester: Deleting ${allStorageControllers.size()} orphaned storage controllers of virtual images for cluster: ${cluster.name}")
                    context.services.storage.controller.bulkRemove(allStorageControllers)
                }
            } else {
                log.debug("Harvester: No virtual images found for cluster: ${cluster.name}")
            }

            def virtualMachines = context.services.computeServer.list(
                    new DataQuery().withFilters(
                            new DataFilter('category', "${HarvesterConstants.CATEGORY_HARVESTER_VM_PREFIX}${cluster.id}"),
                            new DataFilter('serverType', HarvesterConstants.SERVER_TYPE_VM)
                    )
            )
            if (virtualMachines){
                def vmVolumes = []
                def vmControllers = []
                virtualMachines.each { vm ->
                    if (vm.volumes) {
                        vmVolumes.addAll(vm.volumes)
                    }
                    if (vm.controllers) {
                        vmControllers.addAll(vm.controllers)
                    }
                }

                log.info("Harvester: Deleting ${virtualMachines.size()} virtual machines for cluster: ${cluster.name}")
                context.async.computeServer.bulkRemove(virtualMachines).blockingGet()

                // Then delete VM orphaned storage volumes
                if (vmVolumes) {
                    log.debug("Harvester: Deleting ${vmVolumes.size()} orphaned storage volumes of VMs for cluster: ${cluster.name}")
                    context.services.storage.volume.bulkRemove(vmVolumes)
                }

                // Then delete orphaned storage volumes
                if (vmControllers) {
                    log.debug("Harvester: Deleting ${vmControllers.size()} orphaned storage controllers of VMs for cluster: ${cluster.name}")
                    context.services.storage.controller.bulkRemove(vmControllers)
                }
            }

            return ServiceResponse.success()

        } catch (Exception e) {
            log.error("Harvester: Error deleting cluster resources for ${cluster.name}: ${e.message}", e)
            return new ServiceResponse(success: false, msg: "Failed to delete cluster resources: ${e.message}")
        }
    }

    @Override
    ServiceResponse validateServerGroupConfig(ComputeServerGroupType type, Map serverGroupConfig){
        log.info("Harvester: validateServerGroupConfig")
        ComputeServerGroup serverGroup = new ComputeServerGroup()
        def serviceUrl = serverGroupConfig.server.serviceUrl
        serverGroup.serviceUrl = serviceUrl
        serverGroup.serviceToken = serverGroupConfig.server.serviceToken
        serverGroup.serviceUsername = serverGroupConfig.server.serviceUsername
        serverGroup.servicePassword = serverGroupConfig.server.servicePassword
        def apiClient = new HarvesterApiClient(serverGroup)
        // Any call that needs a valid token works; listing a single namespace also checks basic read access
        ServiceResponse response
        try {
            response = apiClient.get(HarvesterConstants.HARVESTER_API_V1_NAMESPACES, [limit: '1'])
        } catch (Exception e) {
            response = ServiceResponse.error(e.message)
        }
        if (!response?.success) {
            log.error("Harvester: Unable to connect to Harvester Cluster: ${serviceUrl}")
            ServiceResponse errorResponse =  new ServiceResponse(success: false, msg: "Failed to validate Harvester cluster configuration.")
            errorResponse.errors = [:] as Map<String, String>
            errorResponse.errors['server.serviceUrl'] = "Unable to connect to the Harvester cluster ${serviceUrl}. Please verify the API URL and the Harvester API key or username and password."
            return errorResponse
        }
        return ServiceResponse.success()
    }

    @Override
    ServiceResponse validateServerGroup(ComputeServerGroup cluster){
        log.info("Harvester: validateServerGroup")
        return ServiceResponse.success()
    }

	/**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
	 * @return an implementation of the MorpheusContext for running Future based rxJava queries
	 */
	@Override
	MorpheusContext getMorpheus() {
		return this.@context
	}

	/**
	 * Returns the instance of the Plugin class that this provider is loaded from
	 * @return Plugin class contains references to other providers
	 */
	@Override
	Plugin getPlugin() {
		return this.@plugin
	}

	/**
	 * A unique shortcode used for referencing the provided provider. Make sure this is going to be unique as any data
	 * that is seeded or generated related to this provider will reference it by this code.
	 * @return short code string that should be unique across all other plugin implementations.
	 */
	@Override
	String getCode() {
		return HarvesterConstants.CLUSTER_PROVIDER_CODE
	}

	/**
	 * Provides the provider name for reference when adding to the Morpheus Orchestrator
	 * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
	 *
	 * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
	 */
	@Override
	String getName() {
		return PROVIDER_NAME
	}

	/**
	 * Grabs available provisioning providers related to the target Cloud Plugin. Some clouds have multiple provisioning
	 * providers or some clouds allow for service based providers on top like (Docker or Kubernetes).
	 * @return Collection of ProvisionProvider
	 */
	@Override
	Collection<ProvisionProvider> getAvailableProvisionProviders() {
        return this.@plugin.getProvidersByType(ProvisionProvider) as Collection<ProvisionProvider>
	}

	/**
	 * Provides a Collection of {@link NetworkType} related to this ClusterProvider
	 * @return Collection of NetworkType
	 */
	@Override
	Collection<NetworkType> getNetworkTypes() {
		return null
	}

	/**
	 * Grabs the singleton instance of the provisioning provider based on the code defined in its implementation.
	 * Typically Providers are singleton and instanced in the {@link Plugin} class
	 * @param providerCode String representation of the provider short code
	 * @return the ProvisionProvider requested
	 */
	@Override
	ProvisionProvider getProvisionProvider(String providerCode) {
        return getAvailableProvisionProviders().find { it.code == providerCode }
	}

    default IacResourceMappingProvider getIacResourceMappingProvider() { return null; }

    Collection<BackupProvider> getAvailableBackupProviders() {
        Collection<BackupProvider> providers = []
        return providers
    }

}
