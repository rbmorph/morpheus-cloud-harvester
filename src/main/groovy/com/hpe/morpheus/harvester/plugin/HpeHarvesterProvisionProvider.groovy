// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin

import com.hpe.morpheus.harvester.plugin.apihelper.NodeApiHelper
import com.morpheusdata.PrepareHostResponse
import com.morpheusdata.core.providers.HostProvisionProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.model.Instance
import com.morpheusdata.model.provisioning.HostRequest
import com.morpheusdata.request.AfterConvertToManagedRequest
import com.morpheusdata.request.BeforeConvertToManagedRequest
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.request.ResizeV2Request
import com.morpheusdata.response.AfterConvertToManagedResponse
import com.morpheusdata.response.BeforeConvertToManagedResponse
import com.morpheusdata.response.PrepareResizeV2WorkloadResponse
import com.morpheusdata.response.ResizeV2WorkloadResponse
import com.morpheusdata.response.ValidateResizeV2WorkloadResponse
import groovy.util.logging.Slf4j

import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.WorkloadProvisionProvider
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Icon
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.Workload
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ServiceResponse

import com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.hpe.morpheus.harvester.plugin.utils.CommonUtils
import com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning
import com.hpe.morpheus.harvester.plugin.provision.VirtualMachineDeletion
import com.hpe.morpheus.harvester.plugin.provision.VirtualMachineSnapshots
import com.morpheusdata.model.Snapshot

@Slf4j
class HpeHarvesterProvisionProvider extends AbstractProvisionProvider implements WorkloadProvisionProvider,
        WorkloadProvisionProvider.ResizeV2Facet, HostProvisionProvider, HostProvisionProvider.ResizeFacet,
        ProvisionProvider.HypervisorConsoleFacet, ProvisionProvider.ConvertToManagedFacet, ProvisionProvider.SnapshotFacet {
	public static final String PROVISION_PROVIDER_NAME = 'Harvester Provision Provider'
	public MorpheusContext context
	protected Plugin plugin

	 HpeHarvesterProvisionProvider(Plugin plugin, MorpheusContext ctx) {
		super()
		this.plugin = plugin
		this.context = ctx
	}

    @Override
    ServiceResponse<ProvisionResponse> waitForHost(ComputeServer server) {
        log.info("Harvester: waitForHost, severName: ${server.name}, serverId: ${server.id}")
        ProvisionResponse response = new ProvisionResponse()
        response.installAgent = false
        response.noAgent = true
        response.skipNetworkWait = true
        NodeApiHelper nodeApiHelper = new NodeApiHelper(server.serverGroup)
        def masterNode = nodeApiHelper.getMasterNodeDetails()
        if (masterNode) {
            def hostname = masterNode.hostname
            log.info("Harvester: waitForHost, found master node with IP: ${masterNode.internalIP}, hostname: ${hostname}")
            response.privateIp = masterNode.internalIP
            response.hostname = hostname
            response.publicIp = masterNode.externalIP

            server.uniqueId = masterNode.uid
            server.externalId = masterNode.uid
            server.hostname = masterNode.hostname
            server = saveAndGet(server)
            log.info("Harvester: waitForHost, updated server:" +
                    " ${server.name}, serverId: ${server.id} with master node details")
        } else {
            log.error("Harvester: waitForHost, could not find master node details for server:" +
                    " ${server.name}, serverId: ${server.id}")
            response.message = 'Could not find master node details'
            new ServiceResponse<ProvisionResponse>(success: false, data: response)
        }
        return new ServiceResponse<ProvisionResponse>(success: true, data: response)
    }

    @Override
    ServiceResponse validateHost(ComputeServer server, Map opts) {
        log.info("Harvester: validateHost")
        return ServiceResponse.success()
    }

    @Override
    ServiceResponse<PrepareHostResponse> prepareHost(ComputeServer server, HostRequest hostRequest, Map opts) {
        log.info("Harvester: prepareHost")
        PrepareHostResponse response = new PrepareHostResponse()
        response.disableAutoUpdates = true
        return new ServiceResponse<PrepareHostResponse>(success: true, data: response)

    }

    @Override
    ServiceResponse<ProvisionResponse> runHost(ComputeServer server, HostRequest hostRequest, Map opts) {
        log.info("Harvester: runHost")
        ProvisionResponse response = new ProvisionResponse()
        return new ServiceResponse<ProvisionResponse>(success: true, data: response)
    }

    @Override
    ServiceResponse finalizeHost(ComputeServer server) {
        log.info("Harvester: finalizeHost")
        return ServiceResponse.success()
    }

    /**
	 * This method is called before runWorkload and provides an opportunity to perform action or obtain configuration
	 * that will be needed in runWorkload. At the end of this method, if deploying a ComputeServer with a VirtualImage,
	 * the sourceImage on ComputeServer should be determined and saved.
	 * @param workload the Workload object we intend to provision along with some of the associated data needed to determine
	 *                 how best to provision the workload
	 * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
	 *                        in running the Workload. This will be passed along into runWorkload
	 * @param opts additional configuration options that may have been passed during provisioning
	 * @return Response from API
	 */
	@Override
	ServiceResponse<PrepareWorkloadResponse> prepareWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		log.info("HpeHarvesterProvisionProvider: prepareWorkload called for workload ${workload.name}")

		try {
			// Create an instance of VirtualMachineProvisioning and delegate to its prepareWorkload method
			def vmProvisioning = new VirtualMachineProvisioning(context)
			return vmProvisioning.prepareWorkload(workload, workloadRequest, opts)
		} catch (Exception e) {
			log.error("HpeHarvesterProvisionProvider: Error in prepareWorkload: ${e.message}", e)
			return new ServiceResponse(false, "Error in prepareWorkload: ${e.message}", null, null)
		}
	}

	/**
	 * Some older clouds have a provision type code that is the exact same as the cloud code. This allows one to set it
	 * to match and in doing so the provider will be fetched via the cloud providers  method.
	 * @return code for overriding the ProvisionType record code property
	 */
	@Override
	String getProvisionTypeCode() {
		return HarvesterConstants.PROVISION_TYPE_CODE
    }

	/**
	 * Provide an icon to be displayed for ServicePlans, VM detail page, etc.
	 * where a circular icon is displayed
	 * @since 0.13.6
	 * @return Icon
	 */
	@Override
	Icon getCircularIcon() {
		// TODO: change icon paths to correct filenames once added to your project
		return new Icon(path:'harvester-circular.svg', darkPath:'harvester-circular.svg')
	}

	/**
	 * Provides a Collection of OptionType inputs that need to be made available to various provisioning Wizards
	 * @return Collection of OptionTypes
	 */
	@Override
	Collection<OptionType> getOptionTypes() {
		Collection<OptionType> nodeOptionTypes = []
		return nodeOptionTypes
	}

	/**
	 * Provides a Collection of OptionType inputs for configuring node types
	 * @since 0.9.0
	 * @return Collection of OptionTypes
	 */
	@Override
	Collection<OptionType> getNodeOptionTypes() {
		Collection<OptionType> nodeOptionTypes = []
		// "Virtual Image / OS Type" radio on the Harvester node type form.
		// Values come from Morpheus' built-in virtualImageTypeList: 'vi' = Virtual Image, 'os' = OS Type.
		nodeOptionTypes << new OptionType(
				name: 'virtual image type',
				code: 'harvester-node-virtual-image-type',
				category: 'provisionType.harvester.custom',
				fieldContext: 'config',
				fieldName: 'virtualImageSelect',
				fieldCode: null,
				fieldLabel: null,
				fieldGroup: null,
				inputType: OptionType.InputType.RADIO,
				optionSource: 'virtualImageTypeList',
				defaultValue: 'vi',
				fieldClass: 'inline',
				displayOrder: 9,
				required: false,
				enabled: true,
				editable: true
		)
		// VM Image picker for Library > Node Types. Writes to the node type's virtualImage,
		// which Morpheus copies to server.sourceImage when a custom layout is provisioned.
		nodeOptionTypes << new OptionType(
				name: 'virtual image',
				code: 'harvester-node-virtual-image',
				category: 'provisionType.harvester.custom',
				fieldName: 'virtualImage.id',
				fieldCode: 'gomorpheus.label.vmImage',
				fieldLabel: 'VM Image',
				fieldContext: 'domain',
				inputType: OptionType.InputType.TYPEAHEAD,
				optionSource: 'harvesterNodeImages',
				placeHolder: 'Search images',
				visibleOnCode: 'config.virtualImageSelect:vi',
				displayOrder: 10,
				required: false,
				enabled: true,
				editable: true,
				global: false,
				custom: false
		)
		// OS type for the node type (uses Morpheus' built-in OS type list)
		nodeOptionTypes << new OptionType(
				name: 'osType',
				code: 'harvester-node-os-type',
				category: 'provisionType.harvester.custom',
				fieldName: 'osType.id',
				fieldCode: 'gomorpheus.optiontype.OsType',
				fieldLabel: 'OS Type',
				fieldContext: 'domain',
				inputType: OptionType.InputType.SELECT,
				optionSource: 'osTypes',
				noSelection: 'Select',
				visibleOnCode: 'config.virtualImageSelect:os',
				displayOrder: 11,
				required: false,
				enabled: true,
				editable: true,
				global: false,
				custom: false
		)
		return nodeOptionTypes
	}

	/**
	 * Node types for this provider are virtual machines, so the Library node type form
	 * should treat them as VM node types.
	 * @return node format
	 */
	@Override
	String getNodeFormat() {
		return 'vm'
	}

	@Override
	String getDeployTargetService() {
		return 'vmDeployTargetService'
	}

	/**
	 * Provides a Collection of StorageVolumeTypes that are available for root StorageVolumes
	 * @return Collection of StorageVolumeTypes
	 */
	@Override
	Collection<StorageVolumeType> getRootVolumeStorageTypes() {
		Collection<StorageVolumeType> volumeTypes = []
		// TODO: create some storage volume types and add to collection
		return volumeTypes
	}

	/**
	 * Provides a Collection of StorageVolumeTypes that are available for data StorageVolumes
	 * @return Collection of StorageVolumeTypes
	 */
	@Override
	Collection<StorageVolumeType> getDataVolumeStorageTypes() {
		Collection<StorageVolumeType> dataVolTypes = []
		// TODO: create some data volume types and add to collection
		return dataVolTypes
	}

	/**
	 * Provides a Collection of ${@link ServicePlan} related to this ProvisionProvider that can be seeded in.
	 * Some clouds do not use this as they may be synced in from the public cloud. This is more of a factor for
	 * On-Prem clouds that may wish to have some precanned plans provided for it.
	 * @return Collection of ServicePlan sizes that can be seeded in at plugin startup.
	 */
	@Override
	Collection<ServicePlan> getServicePlans() {
		return harvesterPlans()
	}

	/**
	 * Fixed sizes: [vCPUs, memory GiB, root disk GiB]. Disks stay resizable; CPU and memory are fixed (use the
	 * Custom plan for other sizes). Each plan is 2 sockets with its cores split between them (4 CPU = 2 sockets ×
	 * 2 cores); a 1 CPU plan is 1 socket × 1 core. Change Cores Per Socket in Administration > Plans if needed.
	 */
	static final List<List<Integer>> PLAN_SIZES = [
			[1, 1, 20], [1, 2, 20], [2, 2, 40], [2, 4, 40], [2, 8, 40], [4, 8, 80], [4, 16, 80],
			[8, 16, 100], [8, 32, 100], [16, 32, 150], [16, 64, 150]
	]

	static String planCode(int cpu, int memoryGb) {
		return "harvester-vm-${cpu}cpu-${memoryGb}gb".toString()
	}

	/** Plan config key marking a plan as already set to the default CPU layout (so later edits are kept) */
	static final String PLAN_CPU_LAYOUT_KEY = 'harvesterCpuLayout'
	static final String PLAN_CPU_LAYOUT = '2-socket'

	/** Default cores per socket for a fixed plan: the cores split over 2 sockets, or 1 socket when that can't be done. */
	static long defaultCoresPerSocket(long cpu) {
		return cpu >= 2L && cpu % 2L == 0L ? (cpu / 2L) as long : Math.max(cpu, 1L)
	}

	static Collection<ServicePlan> harvesterPlans() {
		long gib = 1024L * 1024L * 1024L
		int order = 1
		return PLAN_SIZES.collect { List<Integer> size ->
			int cpu = size[0], memory = size[1], disk = size[2]
			String name = "${cpu} CPU, ${memory}GB Memory".toString()
			long coresPerSocket = defaultCoresPerSocket(cpu)
			ServicePlan plan = new ServicePlan([code: planCode(cpu, memory), editable: true, name: name, description: name,
							 sortOrder: order++, maxCpu: (cpu / coresPerSocket) as Long, maxCores: cpu as Long,
							 coresPerSocket: coresPerSocket,
							 maxMemory: memory * gib, maxStorage: disk * gib,
							 customCpu: false, customCores: false, customMaxMemory: false,
							 customMaxStorage: true, customMaxDataStorage: true, addVolumes: true])
			try {
				plan.setConfigProperty(PLAN_CPU_LAYOUT_KEY, PLAN_CPU_LAYOUT)
			} catch (Exception ignored) {
				// the marker only stops the one-time update in HpeHarvesterCloudProvider.useDefaultPlanCpuLayout
			}
			plan
		}
	}

	/**
	 * Validates the provided provisioning options of a workload. A return of success = false will halt the
	 * creation and display errors
	 * @param opts options
	 * @return Response from API. Errors should be returned in the errors Map with the key being the field name and the error
	 * message as the value.
	 */
	@Override
	ServiceResponse validateWorkload(Map opts) {
		log.info("HpeHarvesterProvisionProvider: validateWorkload called - delegating to VirtualMachineProvisioning")
		try {
			def vmProvisioning = new VirtualMachineProvisioning(context)
			def validationResult = vmProvisioning.validateWorkload(opts)
			return validationResult
		} catch (Exception e) {
			log.error("HpeHarvesterProvisionProvider: Error in validateWorkload: ${e.message}", e)
			def errorResponse = new ServiceResponse(success: false, msg: "Error in validateWorkload: ${e.message}")
			errorResponse.errors = [:] as Map<String, String>
			return errorResponse
		}
	}

	static validateConfig(Map opts = [:]) {
		def rtn = [success:false, errors:[], dataStoreIds: []]
		try {
			if (!opts.config?.catalogId) {
				rtn.success = false
				rtn.errors << [field: 'catalogId', msg: 'You must select a catalog']
			}
			if (!opts.config?.vappId) {
				rtn.success = false
				rtn.errors << [field: 'vappId', msg: 'You must select a vApp']
			}
			rtn.success = rtn.errors.size() == 0
		} catch(e) {
			log.error "Harvester: validateConfig error: ${e}", e
		}
		return rtn
	}

/**
	 * This method is a key entry point in provisioning a workload. This could be a vm, a container, or something else.
	 * Information associated with the passed Workload object is used to kick off the workload provision request
	 * @param workload the Workload object we intend to provision along with some of the associated data needed to determine
	 *                 how best to provision the workload
	 * @param workloadRequest the RunWorkloadRequest object containing the various configurations that may be needed
	 *                        in running the Workload
	 * @param opts additional configuration options that may have been passed during provisioning
	 * @return Response from API
	 */
	@Override
	ServiceResponse<ProvisionResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
		log.info "HpeHarvesterProvisionProvider: runWorkload called for workload ${workload.name}"

		try {
			// Add morpheusContext to opts for VirtualMachineProvisioning to use
			opts.morpheusContext = context

			// Create an instance of VirtualMachineProvisioning and delegate to its runWorkload method
			def vmProvisioning = new VirtualMachineProvisioning(context)
			return vmProvisioning.runWorkload(workload, workloadRequest, opts)
		} catch (Exception e) {
			log.error "HpeHarvesterProvisionProvider: Error in runWorkload: ${e.message}", e
			ProvisionResponse errorResponse = new ProvisionResponse(success: false)
			errorResponse.setError(e.message)
			return new ServiceResponse<ProvisionResponse>(success: false, msg: e.message, error: e.message, data: errorResponse)
		}
	}

	/**
	 * This method is called after successful completion of runWorkload and provides an opportunity to perform some final
	 * actions during the provisioning process. For example, ejected CDs, cleanup actions, etc
	 * @param workload the Workload object that has been provisioned
	 * @return Response from the API
	 */
	@Override
	ServiceResponse finalizeWorkload(Workload workload) {
		log.info "HpeHarvesterProvisionProvider: finalizeWorkload called for workload ${workload.name}"
		try {
			VirtualMachineProvisioning vmProvisioning = new VirtualMachineProvisioning(context)
			return vmProvisioning.finalizeProvisioning(workload)
		} catch (Exception e) {
			log.error "HpeHarvesterProvisionProvider: Error in finalizeWorkload: ${e.message}", e
			// Return success even on error since this is cleanup and should not fail provisioning
			return ServiceResponse.success()
		}
	}

	/**
	 * Issues the remote calls necessary top stop a workload element from running.
	 * @param workload the Workload we want to shut down
	 * @return Response from API
	 */
	@Override
	ServiceResponse stopWorkload(Workload workload) {
		log.info("HpeHarvesterProvisionProvider: stopWorkload called for workload ${workload.name}")
		try {
			def vmReconfigure = new VirtualMachineReconfigure(context, workload.server)
			return vmReconfigure.stopVM()
		} catch (Exception e) {
			log.error("HpeHarvesterProvisionProvider: Error in stopWorkload: ${e.message}", e)
			// Rethrow so Morpheus core marks the task as failed and surfaces the message in the UI
			throw e
		}
	}

	/**
	 * Issues the remote calls necessary to start a workload element for running.
	 * @param workload the Workload we want to start up.
	 * @return Response from API
	 */
	@Override
	ServiceResponse startWorkload(Workload workload) {
		log.info("HpeHarvesterProvisionProvider: startWorkload called for workload ${workload.name}")
		try {
			def vmReconfigure = new VirtualMachineReconfigure(context, workload.server)
			return vmReconfigure.startVM()
		} catch (Exception e) {
			// Note: startContainer() in Morpheus core ignores the return value of startWorkload,
			// so rethrowing here would only be caught there anyway with no UI impact.
			// Return error so it is at least logged clearly.
			log.error("HpeHarvesterProvisionProvider: Error in startWorkload: ${e.message}", e)
			return new ServiceResponse(success: false, msg: "Error in startWorkload: ${e.message}")
		}
	}

	/**
	 * Issues the remote calls to restart a workload element. In some cases this is just a simple alias call to do a stop/start,
	 * however, in some cases cloud providers provide a direct restart call which may be preferred for speed.
	 * @param workload the Workload we want to restart.
	 * @return Response from API
	 */
	@Override
	ServiceResponse restartWorkload(Workload workload) {
		log.info("HpeHarvesterProvisionProvider: restartWorkload called for workload ${workload.name}")
		try {
			def vmReconfigure = new VirtualMachineReconfigure(context, workload.server)
			return vmReconfigure.restartVM()
		} catch (Exception e) {
			log.error("HpeHarvesterProvisionProvider: Error in restartWorkload: ${e.message}", e)
			return new ServiceResponse(success: false, msg: "Error in restartWorkload: ${e.message}")
		}
	}

	@Override
	ServiceResponse resizeServer(ComputeServer server, ResizeRequest resizeRequest, Map opts) {
        ServiceResponse rtn = new ServiceResponse(true, null, [:], null)
        return rtn
	}

	/**
	 * This is the key method called to destroy / remove a workload. This should make the remote calls necessary to remove any assets
	 * associated with the workload.
	 * @param workload to remove
	 * @param opts map of options
	 * @return Response from API
	 */
	@Override
	ServiceResponse removeWorkload(Workload workload, Map opts) {
		try {
			log.info "HpeHarvesterProvisionProvider: removeWorkload called for workload ${workload.name}"
			ComputeServer server = workload.getServer()
			// Use VirtualMachineDeletion to delete the server
			def vmDeletion = new VirtualMachineDeletion(context)
			return vmDeletion.deleteServer(server)
		} catch (Exception e) {
			log.error "HpeHarvesterProvisionProvider: Error in removeWorkload: ${e.message}", e
			return new ServiceResponse(false, "Error in removeWorkload: ${e.message}", null, null)
		}
	}

	/**
	 * Method called after a successful call to runWorkload to obtain the details of the ComputeServer. Implementations
	 * should not return until the server is successfully created in the underlying cloud or the server fails to
	 * create.
	 * @param server to check status
	 * @return Response from API. The publicIp and privateIp set on the WorkloadResponse will be utilized to update the ComputeServer
	 */
	@Override
	ServiceResponse<ProvisionResponse> getServerDetails(ComputeServer server) {
		log.info "HpeHarvesterProvisionProvider: getServerDetails called for server ${server.name} - delegating to VirtualMachineProvisioning"

		try {
			// Create an instance of VirtualMachineProvisioning and delegate to its getServerDetails method
			def vmProvisioning = new VirtualMachineProvisioning(context)
			return vmProvisioning.getServerDetails(server)
		} catch (Exception e) {
			log.error "HpeHarvesterProvisionProvider: Error in getServerDetails: ${e.message}", e
			ProvisionResponse errorResponse = new ProvisionResponse(success: false)
			errorResponse.setError(e.message)
			return new ServiceResponse<ProvisionResponse>(success: false, msg: e.message, error: e.message, data: errorResponse)
		}
	}

	/**
	 * Method called before runWorkload to allow implementers to create resources required before runWorkload is called
	 * @param workload that will be provisioned
	 * @param opts additional options
	 * @return Response from API
	 */
	@Override
	ServiceResponse createWorkloadResources(Workload workload, Map opts) {
		return ServiceResponse.success()
	}

	/**
	 * Stop the server
	 * @param computeServer to stop
	 * @return Response from API
	 */
	@Override
	ServiceResponse stopServer(ComputeServer computeServer) {
		log.info("HpeHarvesterProvisionProvider: stopServer called for server ${computeServer.name}")
		try {
			def vmReconfigure = new VirtualMachineReconfigure(context, computeServer)
			return vmReconfigure.stopVM()
		} catch (Exception e) {
			log.error("HpeHarvesterProvisionProvider: Error in stopServer: ${e.message}", e)
			return new ServiceResponse(success: false, msg: "Error in stopServer: ${e.message}")
		}
	}

	/**
	 * Start the server
	 * @param computeServer to start
	 * @return Response from API
	 */
	@Override
	ServiceResponse startServer(ComputeServer computeServer) {
		log.info("HpeHarvesterProvisionProvider: startServer called for server ${computeServer.name}")
		try {
			def vmReconfigure = new VirtualMachineReconfigure(context, computeServer)
			return vmReconfigure.startVM()
		} catch (Exception e) {
			log.error("HpeHarvesterProvisionProvider: Error in startServer: ${e.message}", e)
			return new ServiceResponse(success: false, msg: "Error in startServer: ${e.message}")
		}
	}

	ServiceResponse suspendWorkload(Workload workload){
		log.info("HpeHarvesterProvisionProvider: suspendWorkload called for workload ${workload.name}")
		try {
			def vmReconfigure = new VirtualMachineReconfigure(context, workload.server)
			return vmReconfigure.suspendVM()
		} catch (Exception e) {
			log.error("HpeHarvesterProvisionProvider: Error in suspendWorkload: ${e.message}", e)
			return new ServiceResponse(success: false, msg: "Error in suspendWorkload: ${e.message}")
		}
	}

	/**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
	 *
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
		return HarvesterConstants.PROVISION_PROVIDER_CODE
	}

	/**
	 * Provides the provider name for reference when adding to the Morpheus Orchestrator
	 * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
	 *
	 * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
	 */
	@Override
	String getName() {
		return PROVISION_PROVIDER_NAME
	}

	/**
	 * Cores per socket is chosen along with the core count, and passed to Harvester as cores (per socket) and sockets.
	 */
	/*
	 * What the provisioning and reconfigure screens allow. Morpheus reads these from the provider, and they all
	 * default to off, which greyed out the network and disk inputs on reconfigure even though the plugin handles them.
	 */

	/** NICs on Harvester networks (or the pod network) */
	@Override
	Boolean hasNetworks() {
		return true
	}

	/** NICs can be added, removed or moved to another network on reconfigure */
	@Override
	Boolean canReconfigureNetwork() {
		return true
	}

	/** Networks are listed per namespace (resource pool) */
	@Override
	Boolean networksScopedToPools() {
		return true
	}

	/** Data disks can be added when provisioning and on reconfigure */
	@Override
	Boolean canAddVolumes() {
		return true
	}

	/** The root disk's size and datastore can be set when provisioning */
	@Override
	Boolean canCustomizeRootVolume() {
		return true
	}

	/** Data disks' size and datastore can be set */
	@Override
	Boolean canCustomizeDataVolumes() {
		return true
	}

	/** The root disk can be grown on reconfigure (Harvester PVCs only grow) */
	@Override
	Boolean canResizeRootVolume() {
		return true
	}

	/** The disk size of the image is known, so the root disk defaults to it */
	@Override
	Boolean rootVolumeSizeKnown() {
		return true
	}

	/** Custom plans (the Harvester Custom plan) are allowed */
	@Override
	Boolean supportsCustomServicePlans() {
		return true
	}

	@Override
	Boolean hasConfigurableSockets() {
		return true
	}

	@Override
	Boolean createDefaultInstanceType() {
		return false
	}

	/**
	 * VMs go into one of the cloud's resource pools (its Harvester namespaces): this is what makes the wizard list
	 * the cloud's pools, as it does for other clouds.
	 */
	@Override
	Boolean hasComputeZonePools() {
		return true
	}

	/**
	 * Every Harvester VM lives in a namespace, so a pool must be chosen.
	 */
	@Override
	Boolean computeZonePoolRequired() {
		return true
	}

	/**
	 * Show the Datastore (storage class) choice on each disk in the provisioning wizard.
	 */
	@Override
	Boolean hasDatastores() {
		return true
	}

	/**
	 * The root disk's Datastore can be chosen: "Auto" (or the image's own class) keeps it on the image's storage class;
	 * any other storage class gets a copy of the image (see HarvesterVolumes.createVolumeFromImageCopy).
	 */
	@Override
	Boolean disableRootDatastore() {
		return false
	}

	/**
	 * "Auto" on a data disk uses Harvester's default VM storage class.
	 */
	@Override
	Boolean supportsAutoDatastore() {
		return true
	}

	private ComputeServer saveAndGet(ComputeServer server) {
		context.services.computeServer.bulkSave([server])
		return context.services.computeServer.get(server.id)
	}

    /**
     * Fills in the request's core count and cores per socket: from the reconfigure form, otherwise from a fixed plan.
     * A plan still sized by a CPU value (customCpu) has that value as its total cores.
     */
    static void applyPlanCpu(ResizeV2Request resizeRequest, Map opts) {
        if (!resizeRequest) return
        Long cpu = CommonUtils.requestedCpu(opts)
        if (cpu && resizeRequest.plan?.customCpu == true && resizeRequest.plan?.customCores != true) {
            resizeRequest.maxCores = cpu
        } else if (!resizeRequest.maxCores && resizeRequest.plan?.maxCores) {
            resizeRequest.maxCores = resizeRequest.plan.maxCores
        }
        if (!resizeRequest.coresPerSocket) {
            Map planOptions = opts?.servicePlanOptions instanceof Map ? opts.servicePlanOptions as Map : [:]
            Long requested = CommonUtils.toPositiveLong(planOptions.coresPerSocket ?: opts?.coresPerSocket)
            Long fromSockets = VirtualMachineProvisioning.coresPerSocketFromSockets(resizeRequest.plan,
                    CommonUtils.toPositiveLong(resizeRequest.maxCores), cpu)
            if (requested) {
                resizeRequest.coresPerSocket = requested
            } else if (fromSockets) {
                resizeRequest.coresPerSocket = fromSockets
            } else if (resizeRequest.plan && resizeRequest.plan.customCores != true && resizeRequest.plan.coresPerSocket) {
                resizeRequest.coresPerSocket = resizeRequest.plan.coresPerSocket
            }
        }
    }

    @Override
    ServiceResponse<ValidateResizeV2WorkloadResponse> validateResizeWorkload(
            Instance instance, Workload workload, ResizeV2Request resizeRequest, Map opts) {
        log.info("HpeHarvesterProvisionProvider: validateResizeWorkload called for workload ${workload?.name}")
        applyPlanCpu(resizeRequest, opts)
        ValidateResizeV2WorkloadResponse response = new ValidateResizeV2WorkloadResponse()
        try {
            VirtualMachineReconfigure vmReconfigure = new VirtualMachineReconfigure(context, workload?.server)
            ServiceResponse validationResponse = vmReconfigure.validateResizeRequest(resizeRequest, opts)

            if (!validationResponse.success) {
                log.error "validation results ${validationResponse}"
                response.allowed = false
                response.hotResize = false
                // Prefer msg if already set on the validation response; otherwise build it from the
                // errors map (ServiceResponse.error(String) puts text into errors.error, not msg).
                String errorMsg = validationResponse.msg
                        ?: validationResponse.errors
                                ?.findAll { k, v -> k != 'error' }
                                ?.collect { k, v -> v }
                                ?.join('; ')
                        ?: validationResponse.errors?.get('error')
                        ?: 'Validation failed'
                log.error "Returning validation error to UI: ${errorMsg}"
                // data MUST be ValidateResizeV2WorkloadResponse — Morpheus core (ContainerService)
                // accesses .hotResize on the data object; null data throws MissingPropertyException
                // and the error never reaches the UI.
                return new ServiceResponse(false, errorMsg, validationResponse.errors, response)
            }
            // CPU and memory hot add: Morpheus leaves the VM running when the change can be made live
            response.hotResize = vmReconfigure.canHotResize(resizeRequest)
        } catch (Exception e) {
            log.error("Error validating resize request: ${e.message}", e)
            response.hotResize = false
        }
        response.allowed = true
        if (response.hotResize == null) {
            response.hotResize = false
        }
        return new ServiceResponse<>(true, null, null, response)
    }

    @Override
    ServiceResponse<PrepareResizeV2WorkloadResponse> prepareResizeWorkload(
            Instance instance, Workload workload, ResizeV2Request resizeRequest, Map opts) {
        log.info("HpeHarvesterProvisionProvider: prepareResizeWorkload called for workload ${workload?.name}")
        applyPlanCpu(resizeRequest, opts)
        try {
            VirtualMachineReconfigure vmReconfigure = new VirtualMachineReconfigure(context, workload?.server)
            return vmReconfigure.prepareResizeWorkload(instance, workload, resizeRequest, opts)
        } catch (Exception e) {
            log.error("HpeHarvesterProvisionProvider: Error in prepareResizeWorkload: ${e.message}", e)
            return ServiceResponse.error("Error preparing resize: ${e.message}")
        }
    }

    @Override
    ServiceResponse<ResizeV2WorkloadResponse> resizeWorkload(
            Instance instance, Workload workload, ResizeV2Request resizeRequest, Map opts) {
        log.info("HpeHarvesterProvisionProvider: resizeWorkload called for workload ${workload?.name}")
        applyPlanCpu(resizeRequest, opts)
        try {
            VirtualMachineReconfigure vmReconfigure = new VirtualMachineReconfigure(context, workload?.server)
            return vmReconfigure.resizeWorkload(instance, workload, resizeRequest, opts)
        } catch (Exception e) {
            log.error("HpeHarvesterProvisionProvider: Error in resizeWorkload: ${e.message}", e)
            return ServiceResponse.error("Error executing resize: ${e.message}")
        }
    }

    // --- Snapshots: Harvester VM snapshots (VirtualMachineBackup type "snapshot") -----------------------------------

    @Override
    ServiceResponse createSnapshot(ComputeServer server, Map opts) {
        return new VirtualMachineSnapshots(context).createSnapshot(server, opts ?: [:])
    }

    @Override
    ServiceResponse deleteSnapshot(Snapshot snapshot, Map opts) {
        return new VirtualMachineSnapshots(context).deleteSnapshot(snapshot, opts ?: [:])
    }

    @Override
    ServiceResponse deleteSnapshots(ComputeServer server, Map opts) {
        return new VirtualMachineSnapshots(context).deleteSnapshots(server, opts ?: [:])
    }

    @Override
    ServiceResponse revertSnapshot(ComputeServer server, Snapshot snapshot, Map opts) {
        return new VirtualMachineSnapshots(context).revertSnapshot(server, snapshot, opts ?: [:])
    }

    /**
     * Returns the VNC console URL for accessing the KubeVirt virtual machine console
     * @param server the ComputeServer to get console URL for
     * @param opts additional options
     * @return ServiceResponse containing the VNC WebSocket URL
     */
    @Override
    ServiceResponse<Map> getNoVNCConsoleUrl(ComputeServer server) {
        try {
			log.info("HpeHarvesterProvisionProvider: getNoVNCConsoleUrl called for server ${server?.name} - delegating to VirtualMachineProvisioning")
            def vmProvisioning = new VirtualMachineProvisioning(context)
            def result = vmProvisioning.getNoVNCConsoleUrl(server)
			log.info("HpeHarvesterProvisionProvider: getNoVNCConsoleUrl successfully completed")
            return result
        } catch (Exception e) {
            log.error("HpeHarvesterProvisionProvider: Error in getNoVNCConsoleUrl: ${e.message}", e)
            return new ServiceResponse<Map>(success: false, msg: "Error generating VNC console URL: ${e.message}", data: [success: false])
        }
    }

    @Override
    ServiceResponse<BeforeConvertToManagedResponse> beforeConvertToManaged(BeforeConvertToManagedRequest request) {
        log.info("HpeHarvesterProvisionProvider: beforeConvertToManaged called for server")
        return new ServiceResponse<BeforeConvertToManagedResponse>(success: true, data: new BeforeConvertToManagedResponse())
    }

    @Override
    ServiceResponse<AfterConvertToManagedResponse> afterConvertToManaged(AfterConvertToManagedRequest request) {
        log.info("HpeHarvesterProvisionProvider: afterConvertToManaged called for server: ${request?.server?.name}")
        def vmProvisioning = new VirtualMachineProvisioning(context)
        return vmProvisioning.afterConvertToManaged(request)
    }
}
