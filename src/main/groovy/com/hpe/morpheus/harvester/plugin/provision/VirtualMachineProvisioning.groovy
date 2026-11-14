package com.hpe.morpheus.harvester.plugin.provision

import com.hpe.morpheus.harvester.plugin.utils.CpuTopology
import com.hpe.morpheus.harvester.plugin.auth.AuthManager

import com.hpe.morpheus.harvester.plugin.apihelper.ConfigMapApiHelper
import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterImageApiHelper
import com.hpe.morpheus.harvester.plugin.apihelper.StorageClassApiHelper
import com.hpe.morpheus.harvester.plugin.common.Constants
import com.hpe.morpheus.harvester.plugin.utils.CommonUtils
import com.hpe.morpheus.harvester.plugin.utils.DBUtils
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.request.AfterConvertToManagedRequest
import com.morpheusdata.response.AfterConvertToManagedResponse
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.ComputeServerInterfaceType
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.NetAddress
import com.morpheusdata.model.ProcessEvent
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.Workload
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j

@Slf4j
class VirtualMachineProvisioning {

    MorpheusContext morpheusContext
    ComputeServerGroup computeServerGroup

    private static final List<String> vmHTTPSuccessCodes = ["200", "201", "202"]
    private static final String vmResourceStatusRunning = "Running" // status when VM is successfully running
    private static final String vmResourceStatusFailed = "Failed" // status when VM creation failed
    private static final String vmResourceStatusStopped = "Stopped" // status when VM is stopped
    private static final String vmResourceStatusTerminated = "Terminating" // status when VM is terminated
    private static final String vmResourceStatusProvisioning = "Provisioning" // status when VM is being Provisioned
    private static final String vmResourceStatusStarting = "Starting" // status when VM is starting
    private static final String apiVersion = "kubevirt.io/v1"
    private static final String kind = "VirtualMachine"
    private static final String runStrategy = "Always"
    private static final String runStrategyHalted  = "Halted"
    private static final String architecture = "amd64"
    private static final String KUBEVIRT_API_NAMESPACE_PATH = "/apis/kubevirt.io/v1/namespaces/"
    private static final String VIRTUAL_MACHINE_INSTANCES = "/virtualmachineinstances/"
    private static final String MORPHEUS_VM_PROVISIONING_LABEL = "morpheus-label.vm.provisioning"


    // constructor
    VirtualMachineProvisioning(MorpheusContext morpheusContext) {
        this.morpheusContext = morpheusContext
    }

    // execute VM provisioning logic here
    def execute(){


    }

    /**
     * Handles post-convert-to-managed logic: resolves the Harvester image behind the VM's boot disk
     * and assigns the matching VirtualImage as the server's sourceImage.
     * All failures are swallowed as warnings — no exception is propagated.
     */
    ServiceResponse<AfterConvertToManagedResponse> afterConvertToManaged(AfterConvertToManagedRequest request) {
        log.info("VirtualMachineProvisioning: afterConvertToManaged called for server: ${request?.server?.name}")
        try {
            // Step 1: Get server id from request and fetch from DB
            Long serverId = request?.server?.id
            if (!serverId) {
                log.warn("VirtualMachineProvisioning: afterConvertToManaged - no server id in request, skipping sourceImage assignment")
                return new ServiceResponse<AfterConvertToManagedResponse>(success: true, data: new AfterConvertToManagedResponse())
            }
            ComputeServer dbServer = morpheusContext.services.computeServer.get(serverId)
            if (!dbServer) {
                log.warn("VirtualMachineProvisioning: afterConvertToManaged - server ${serverId} not found in DB, skipping sourceImage assignment")
                return new ServiceResponse<AfterConvertToManagedResponse>(success: true, data: new AfterConvertToManagedResponse())
            }

            // Step 2: Resolve VM name and namespace
            String vmName = dbServer.name
            String namespace = dbServer.getConfigProperty("project")?.name
            if (!vmName || !namespace) {
                log.warn("VirtualMachineProvisioning: afterConvertToManaged - missing vmName or namespace for server ${serverId}, skipping sourceImage assignment")
                return new ServiceResponse<AfterConvertToManagedResponse>(success: true, data: new AfterConvertToManagedResponse())
            }

            // Step 3: Resolve serverGroup and create API client
            Long serverGroupId = CommonUtils.getServerGroupId(dbServer, morpheusContext)
            if (!serverGroupId) {
                log.warn("VirtualMachineProvisioning: afterConvertToManaged - no serverGroup found for server ${vmName}, skipping sourceImage assignment")
                return new ServiceResponse<AfterConvertToManagedResponse>(success: true, data: new AfterConvertToManagedResponse())
            }
            ComputeServerGroup serverGroup = morpheusContext.services.cluster.get(serverGroupId)
            HarvesterApiClient apiClient = new HarvesterApiClient(serverGroup)

            // Step 4: Fetch VM from Harvester and extract the template name label
            String vmApiPath = getVMApiResourcePath(namespace, vmName)
            ServiceResponse vmResponse = apiClient.get(vmApiPath)
            if (!vmResponse.success) {
                log.warn("VirtualMachineProvisioning: afterConvertToManaged - failed to fetch VM ${vmName} from Harvester: ${vmResponse.msg}")
                return new ServiceResponse<AfterConvertToManagedResponse>(success: true, data: new AfterConvertToManagedResponse())
            }
            def vm = vmResponse.data

            // Step 5: Find the Harvester image behind the VM's boot disk (harvesterhci.io/imageId on its PVC)
            String imageId = resolveBootImageId(vm as Map, apiClient, namespace)
            if (!imageId) {
                log.warn("VirtualMachineProvisioning: afterConvertToManaged - no Harvester image found for the boot disk of VM ${vmName}, skipping sourceImage assignment")
                return new ServiceResponse<AfterConvertToManagedResponse>(success: true, data: new AfterConvertToManagedResponse())
            }
            def (String imageNamespace, String imageName) = imageId.tokenize('/')
            ServiceResponse imageResponse = apiClient.get(HarvesterImageApiHelper.getImageApiPath(imageNamespace, imageName))
            String imageUid = imageResponse?.success ? imageResponse.data?.metadata?.uid : null
            log.info "Harvester image ${imageId} uid is ${imageUid}"
            if (!imageUid) {
                log.warn("VirtualMachineProvisioning: afterConvertToManaged - failed to fetch image ${imageId}: ${imageResponse?.msg}, skipping sourceImage assignment")
                return new ServiceResponse<AfterConvertToManagedResponse>(success: true, data: new AfterConvertToManagedResponse())
            }

            // Step 6: Find VirtualImage in DB matching uid (externalId) and belonging to the same cluster
            VirtualImage virtualImage = morpheusContext.services.virtualImage.find(
                new DataQuery().withFilters(
                    new DataFilter('externalId', imageUid),
                    new DataFilter('category', "harvester.image.template.${serverGroupId}"),
                )
            )
            if (!virtualImage) {
                log.warn("VirtualMachineProvisioning: afterConvertToManaged - no VirtualImage found matching image uid ${imageUid} for cluster ${serverGroupId}, skipping sourceImage assignment")
                return new ServiceResponse<AfterConvertToManagedResponse>(success: true, data: new AfterConvertToManagedResponse())
            }

            // Step 7: Assign the virtual image as the server's sourceImage and save
            log.info("VirtualMachineProvisioning: afterConvertToManaged - assigning VirtualImage '${virtualImage.name}' (id: ${virtualImage.id}) to server ${vmName}")
            dbServer.sourceImage = virtualImage
            dbServer.discovered = false
            morpheusContext.services.computeServer.bulkSave([dbServer])
            log.info("VirtualMachineProvisioning: afterConvertToManaged - successfully assigned sourceImage to server ${vmName}")

        } catch (Exception e) {
            log.warn("VirtualMachineProvisioning: afterConvertToManaged - error assigning sourceImage, continuing: ${e.message}", e)
        }
        return new ServiceResponse<AfterConvertToManagedResponse>(success: true, data: new AfterConvertToManagedResponse())
    }

    /**
     * Returns the Harvester image id (<namespace>/<name>) the VM's boot disk was created from.
     * Checks the boot PVC's harvesterhci.io/imageId annotation, then the VM's
     * harvesterhci.io/volumeClaimTemplates annotation (set by the Harvester UI).
     */
    static String resolveBootImageId(Map vm, def apiClient, String namespace) {
        def spec = vm?.spec?.template?.spec
        def disks = spec?.domain?.devices?.disks ?: []
        def volumes = spec?.volumes ?: []
        def bootDisk = disks.find { it?.bootOrder == 1 } ?: disks.find { it?.disk }
        def bootVolume = bootDisk ? volumes.find { it?.name == bootDisk.name } : null
        String claimName = bootVolume?.persistentVolumeClaim?.claimName ?: bootVolume?.dataVolume?.name

        if (claimName) {
            try {
                ServiceResponse pvcResponse = apiClient.get("/api/v1/namespaces/${namespace}/persistentvolumeclaims/${claimName}")
                String imageId = pvcResponse?.success ?
                        pvcResponse.data?.metadata?.annotations?.get(HarvesterConstants.ANNOTATION_IMAGE_ID) : null
                if (imageId) {
                    return imageId
                }
            } catch (Exception e) {
                log.debug "Unable to read boot PVC ${claimName}: ${e.message}"
            }
        }

        String claimTemplates = vm?.metadata?.annotations?.get(HarvesterConstants.ANNOTATION_VOLUME_CLAIM_TEMPLATES)
        if (claimTemplates) {
            try {
                def templates = new groovy.json.JsonSlurper().parseText(claimTemplates) as List
                def match = templates.find { it?.metadata?.name == claimName } ?:
                        templates.find { it?.metadata?.annotations?.get(HarvesterConstants.ANNOTATION_IMAGE_ID) }
                return match?.metadata?.annotations?.get(HarvesterConstants.ANNOTATION_IMAGE_ID)
            } catch (Exception e) {
                log.debug "Unable to parse volumeClaimTemplates annotation: ${e.message}"
            }
        }
        return null
    }

    /**
     * Utility method to save a ComputeServer and return the updated instance
     * @param server the ComputeServer to save
     * @return the saved ComputeServer
     */
    private ComputeServer saveAndGet(ComputeServer server) {
        morpheusContext.services.computeServer.bulkSave([server])
        return morpheusContext.services.computeServer.get(server.id)
    }

    /**
     * Creates both disk and volume configurations for the VM based on server volumes
     * @param serverVolumes the list of volumes from the server
     * @param workload the Workload object containing VM specifications
     * @return Map containing both disksConfig and volumesConfig lists
     */
    static Map createVMDisksAndVolumesConfiguration(def serverVolumes, Workload workload) {
        log.info "Creating VM disks and volumes configuration for workload: ${workload?.name}"

        List disksConfig = []
        List volumesConfig = []
        List dataVolumeTemplates = []

        log.info "Found ${serverVolumes.size()} volumes in server"

        // First, find the root volume and separate it from others
        def rootVolume = serverVolumes.find { volume -> volume?.rootVolume == true }
        def otherVolumes = serverVolumes.findAll { volume -> volume?.rootVolume != true }

        // Create list with root volume first, then others
        List orderedVolumes = []

        orderedVolumes.add(rootVolume)
        log.info "Found root volume: ${rootVolume?.claimName}"

        orderedVolumes.addAll(otherVolumes)

        // Create disk and volume configurations
        orderedVolumes.eachWithIndex { volume, index ->
            int diskNumber = index + 1
            String diskName = volume?.name
            int bootOrder = diskNumber

            log.info "Creating diskconfig and volumeconfig for volume: ${volume?.claimName}, rootVolume: ${volume?.rootVolume}"

            // Retrieve serial number from internalId property (generated during volume creation)
            // If not present, generate it now for backwards compatibility
            String diskSerial = volume?.internalId
            log.info "serial number for volume ${volume?.claimName} is : ${diskSerial}"
            // Create disk configuration with serial number
            disksConfig.add([
                bootOrder: bootOrder,
                disk: [
                    bus: "virtio"
                ],
                name: diskName,
                serial: diskSerial  // Add serial number for disk identification in guest OS
            ])

            // Create volume configuration based on storage volume type code
            String externalId = volume?.claimName
            String storageVolumeTypeCode = volume?.type?.code

            log.info "Volume ${externalId} has storage volume type code: ${storageVolumeTypeCode}"

            if (storageVolumeTypeCode == HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC) {
                // Use persistentVolumeClaim for Harvester PVC type
                volumesConfig.add([
                    name: diskName,
                    persistentVolumeClaim: [
                        claimName: externalId
                    ]
                ])
                log.info "Added PVC volume configuration for ${diskName} with claimName: ${externalId}"
            } else if (storageVolumeTypeCode == HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME) {
                // Use dataVolume for Harvester DataVolume type
                volumesConfig.add([
                    name: diskName,
                    dataVolume: [
                        name: externalId
                    ]
                ])
                // Data volume template configuration
                Map body = volume.getConfigProperty("body")
                log.info "Data volume template body for volume ${externalId}: ${body}"
                dataVolumeTemplates.add(body)
                log.info "Added DataVolume configuration for ${diskName} with name: ${externalId}"
            }
        }

        log.info "Created ${disksConfig.size()} disks and ${volumesConfig.size()} volumes configuration"

        return [
            disksConfig: disksConfig,
            volumesConfig: volumesConfig,
            dataVolumeTemplates: dataVolumeTemplates
        ]
    }

    /**
     * Creates network configuration for the VM based on network interfaces from server and workload request
     * @param workload the Workload object containing server with interfaces
     * @param workloadRequest the WorkloadRequest containing network configuration
     * @return Map containing both interfaces and networks configurations
     */
    Map createNetworkConfiguration(Workload workload, String namespace) {
        log.info "Creating network configuration for VM"

        List interfacesConfig = []
        List networksConfig = []
        Map<String, String> interfaceNameMap = [:] // Map to track old name -> new name

        // Get server interfaces from workload - this has all the information we need
        def serverInterfaces = workload?.server?.interfaces
        log.info "Found ${serverInterfaces?.size() ?: 0} server interfaces"

        // Sort server interfaces by name to ensure consistent ordering (e.g., eth0 before eth1)
        serverInterfaces = serverInterfaces?.sort { it?.name }

        // Process all interfaces from workload.server.interfaces
        serverInterfaces.each { serverInterface ->
            String originalInterfaceName = serverInterface?.name
            String networkName = serverInterface?.network?.name
            String networkNameSpaceFull = serverInterface?.network?.displayName
            log.info "networkNamespaceFull before splitting: ${networkNameSpaceFull}"
            String networkNameSpace = networkNameSpaceFull?.split('/')?.first()
            log.info "networkNamespace after splitting: ${networkNameSpace}"
            // Sanitize interface name: remove spaces and convert to lowercase
            String sanitizedInterfaceName = originalInterfaceName.replaceAll("\\s+", "").toLowerCase()

            // Track the name change
            if (originalInterfaceName != sanitizedInterfaceName) {
                interfaceNameMap[originalInterfaceName] = sanitizedInterfaceName
                log.info "Interface name sanitized: '${originalInterfaceName}' -> '${sanitizedInterfaceName}'"
            }

            log.info "Processing interface: ${sanitizedInterfaceName}, network: ${networkName}"

            // Check if this network is default pod network
            if (networkName.equalsIgnoreCase("default-pod-network")) {
                log.info "Interface ${sanitizedInterfaceName} is using default pod network"
                interfacesConfig.add([
                    masquerade: [:],
                    model: "virtio",
                    name: sanitizedInterfaceName
                ])

                networksConfig.add([
                    name: sanitizedInterfaceName,
                    pod: [:]
                ])
            } else {
                // Interface uses external NAD
                log.info "Adding interface ${sanitizedInterfaceName} with NAD: ${networkName}"
                interfacesConfig.add([
                    bridge: [:],
                    model: "virtio",
                    name: sanitizedInterfaceName
                ])
                // Harvester VM networks may live in any namespace; reference NADs outside the VM's
                // namespace as <namespace>/<name> (Multus syntax)
                if (networkNameSpace && namespace != networkNameSpace) {
                    networkName = "${networkNameSpace}/${networkName}"
                    log.info "Network is in namespace ${networkNameSpace}, using fully qualified name: ${networkName}"
                } else if (namespace == networkNameSpace) {
                    log.info "Namespace and networkNamespace are the same. Not prepending anything"
                }

                networksConfig.add([
                    name: sanitizedInterfaceName,
                    multus: [
                        networkName: networkName
                    ]
                ])
            }
        }

        log.info "Created ${interfacesConfig.size()} interfaces and ${networksConfig.size()} networks configuration"

        // Update interface names in database if any were sanitized
        if (interfaceNameMap) {
            log.info "Updating ${interfaceNameMap.size()} interface names in database"
            try {
                List<ComputeServerInterface> interfacesToUpdate = []

                serverInterfaces.each { serverInterface ->
                    String originalName = serverInterface?.name
                    if (interfaceNameMap.containsKey(originalName)) {
                        serverInterface.name = interfaceNameMap[originalName]
                        interfacesToUpdate.add(serverInterface)
                        log.info "Updated interface name in object: '${originalName}' -> '${serverInterface.name}'"
                    }
                }

                // Save all updated interfaces
                if (interfacesToUpdate) {
                    morpheusContext.async.computeServer.computeServerInterface.bulkSave(interfacesToUpdate).blockingGet()
                    log.info "Successfully updated ${interfacesToUpdate.size()} interface names in database"
                }
            } catch (Exception e) {
                log.error "Error updating interface names in database: ${e.message}", e
            }
        }

        return [
            interfacesConfig: interfacesConfig,
            networksConfig: networksConfig
        ]
    }

    /**
     * Validates only the value of an Harvester/Kubernetes label according to standard rules
     * Label values:
     * - Max 63 characters
     * - Must begin and end with alphanumeric [a-z0-9A-Z]
     * - Can contain dashes (-), underscores (_), dots (.), and alphanumerics
     * - Empty string is allowed
     *
     * @param labelKey The label key (for error messaging only)
     * @param labelValue The label value to validate
     * @return Map with success boolean and error message if validation fails
     */
    static Map validateLabelValue(String labelValue) {
        if (!labelValue) {
            return [success: true]  // Empty string is allowed
        }

        // Regex for label value - must start and end with alphanumeric, can contain [-_.a-zA-Z0-9]
        def valuePattern = ~/^[a-zA-Z0-9]([-_.a-zA-Z0-9]*[a-zA-Z0-9])?$/

        // Check length
        if (labelValue.length() > 63) {
            return [success: false, error: "Label value '${labelValue}' exceeds 63 characters (length: ${labelValue.length()})"]
        }

        // Check format
        if (!labelValue.matches(valuePattern)) {
            return [success: false, error: "Label value '${labelValue}' is invalid. Must start and end with alphanumeric, can contain [-_.a-zA-Z0-9]"]
        }

        return [success: true]
    }

    /**
     * Creates the VM request body for Harvester VM creation
     * @param workload the Workload object containing VM specifications
     * @param workloadRequest the WorkloadRequest object containing configuration details
     * @param opts additional options that may contain configuration parameters
     * @return Map representing the VM request body for Harvester API
     */
    Map createVMRequestBody(Workload workload, WorkloadRequest workloadRequest, Map opts) {
        log.info "Creating VM request body for workload: ${workload?.name} with server" +
                " : ${workload?.server?.name} and hostname : ${workload?.server?.hostname}"

        // Extract configuration from workload and opts
        def vmName = workload.server?.name
        def namespace = opts?.config?.project?.name
        // vCPUs (the plan's core count) split into Harvester cores per socket × sockets
        Map<String, Long> cpuTopology = resolveCpuTopology(workload, opts)
        def maxCores = cpuTopology.cores * cpuTopology.sockets
        long maxMemoryMi = (workload.maxMemory?.div(ComputeUtility.ONE_MEGABYTE) ?: workload.instance.plan.maxMemory.div(ComputeUtility.ONE_MEGABYTE)) as long
        def maxMemory = "${maxMemoryMi}Mi".toString()
        // Harvester: limits carry the configured memory, the guest sees it minus the reserved overhead
        def guestMemory = "${CommonUtils.harvesterGuestMemoryMi(maxMemoryMi)}Mi".toString()
        boolean hotAdd = isHotAdd(opts, workload)
        if (hotAdd && maxMemoryMi < CpuTopology.MIN_HOTPLUG_MEMORY_MI) {
            throw new RuntimeException("CPU and memory hot add needs at least 1 GB of memory (this VM has ${maxMemoryMi} MB).")
        }
        long hotAddRatio = (opts?.hotplugRatio ?: CpuTopology.DEFAULT_HOTPLUG_RATIO) as long

        // store namespace in opts
        opts.namespace = namespace

        // Get template/image information for VM metadata
        def virtualImage = workload.server?.sourceImage
        log.info "Virtual image ID for VM: ${virtualImage?.id}"
        if (!virtualImage) {
            throw new RuntimeException("No virtual image is set for VM '${vmName}'. " +
                    "Select a VM Image on the Harvester node type used by this layout, or choose an image when provisioning.")
        }
        def templateName = virtualImage?.name ?: "Unknown"
        def osType = virtualImage?.osType?.name ?: opts.platform ?: "linux"
        log.info "Template name for VM: ${templateName}, OS Type: ${osType}"

        def virtualImageConfig = virtualImage.getConfigMap()
        def templateNamespace = null
        if (virtualImageConfig?.namespace) {
            templateNamespace = virtualImageConfig.namespace
        }
        String effectiveRunStrategy = runStrategy
        if (opts.platform == HarvesterConstants.OS_TYPE_WINDOWS){
            effectiveRunStrategy  = runStrategyHalted
        }
        log.info "Using namespace from virtualImage config for updating the template label: ${templateNamespace}"
        // Get disks and volumes configuration from the combined function
        Map diskAndVolumeConfig = createVMDisksAndVolumesConfiguration(workload?.server?.volumes, workload)
        List disksConfig = diskAndVolumeConfig.disksConfig
        List volumesConfig = diskAndVolumeConfig.volumesConfig
        List dataVolumeTemplates = diskAndVolumeConfig.dataVolumeTemplates

        // Get network configuration from the network function
        Map networkConfig = createNetworkConfiguration(workload, namespace)
        List interfacesConfig = networkConfig.interfacesConfig
        List networksConfig = networkConfig.networksConfig

        // Build cloudinit parameters for VM customization
        ProvisionHelper provHelper = new ProvisionHelper(workload.server, workloadRequest, this.computeServerGroup, opts)
        Map cloudInitResponse = [:]
        // Map sysprepResponse = [:]
        switch (opts.platform) {
            case "linux": {
                // Only build cloud-init options if the image has cloud-init enabled
                Long virtualImageId = workload.server.sourceImage?.id
                virtualImage = morpheusContext.services.virtualImage.get(virtualImageId)
                if (virtualImage?.getCloudInit()) {
                    log.info "Virtual image has cloud-init enabled - building cloud-init options"
                    cloudInitResponse = provHelper.buildCloudInitOptions()
                } else {
                    log.info "Virtual image does not have cloud-init enabled - skipping cloud-init configuration"
                }
                break
            }
//            sysprep for windows VM is now handled post VM is created , before powering it on
//            case "windows": {
//                sysprepResponse = provHelper.buildSysprepOptions()
//                break
//            }

        }
        // if cloudInitUserData is present
        if (cloudInitResponse?.success) {
            log.info "Adding cloud-init configuration to VM request body"
            // add to disk configuration here
            disksConfig.add([
                disk: [
                    bus: "scsi"
                ],
                name: "cloudinitdisk"
            ])
            // build the cloudinit volume config here
            Map cloudInitVolume = [
                name: "cloudinitdisk",
                cloudInitNoCloud: [
                    secretRef: [
                        name: cloudInitResponse.configName
                    ],
                    networkDataSecretRef: [
                        name: cloudInitResponse.configName
                    ]
                ]
            ]
            // add the cloudinit volume to volumesConfig
            volumesConfig.add(cloudInitVolume)
        }
//        sysprep for windows VM is now handled post VM is created , before powering it on
//        if (sysprepResponse?.success && sysprepResponse?.configMapName) {
//            disksConfig.add([
//                    cdrom: [
//                            bus: "sata"
//                    ],
//                    name : "sysprep"
//            ])
//            volumesConfig.add([
//                    name   : "sysprep",
//                    sysprep: [
//                            configMap: [
//                                    name: sysprepResponse.configMapName
//                            ]
//                    ]
//            ])
//        }

        // Build labels and annotations maps by merging template values with defaults
        Map vmLabels = buildVMLabels(virtualImageConfig, templateName, templateNamespace as String, workload.server?.labels)
        Map vmAnnotations = buildVMAnnotations(virtualImageConfig, osType as String, templateName)
        log.debug("Final VM Labels: ${vmLabels} , annotations: ${vmAnnotations}")

        // Harvester VMs boot with BIOS unless UEFI is chosen. Follow the Morpheus image settings:
        // UEFI (and optionally Secure Boot, which needs SMM) only when the image is flagged for it.
        boolean secureBoot = virtualImage.getSecureBoot() != null ? virtualImage.getSecureBoot() : false
        boolean uefi = secureBoot || (virtualImage.getUefi() != null ? virtualImage.getUefi() : false)
        log.info "Resolved uefi=${uefi}, secureBoot=${secureBoot} for VM '${vmName}' from virtual image '${templateName}'"

        Map vmRequestBody = [
            apiVersion: apiVersion,
            kind: kind,
            metadata: [
                name: vmName,
                namespace: namespace,
                labels: vmLabels,
                annotations: vmAnnotations
            ],
            spec: [
                runStrategy: effectiveRunStrategy,
                dataVolumeTemplates: dataVolumeTemplates,
                template: [
                    metadata: [
                        labels: [
                            (HarvesterConstants.LABEL_VM_NAME): vmName
                        ]
                    ],
                    spec: [
                        architecture: architecture,
                        domain: [
                            cpu: [
                                cores: maxCores,
                                sockets: 1,
                                threads: 1
                            ],
                            devices: [
                                disks: disksConfig,
                                interfaces: interfacesConfig,
                            ],
                            machine: [
                                type: "q35"
                            ],
                            features: [
                                acpi: [:]
                            ],
                            // Same shape as VMs created in the Harvester UI: limits hold the configured size,
                            // guest memory is the limit minus Harvester's reserved overhead, and Harvester
                            // derives the requests from the limits using its overcommit settings
                            memory: [
                                guest: guestMemory
                            ],
                            resources: [
                                limits: [
                                    cpu: maxCores.toString(),
                                    memory: maxMemory
                                ]
                            ],
                        ],
                        networks: networksConfig,
                        volumes: volumesConfig,
                        hostname: workload.server?.hostname ?: workload.server?.name
                    ]
                ]
            ]
        ]

        // CPU topology and memory, with CPU and memory hot add when chosen (see CpuTopology)
        CpuTopology.applyToDomain(vmRequestBody.spec.template.spec.domain as Map, cpuTopology, maxMemoryMi, hotAdd, hotAddRatio)
        if (hotAdd) {
            vmRequestBody.metadata.annotations[CpuTopology.ANNOTATION_HOTPLUG] = 'true'
            log.info "CPU and memory hot add on for VM '${vmName}': up to ${cpuTopology.sockets * hotAddRatio} sockets " +
                    "and ${maxMemoryMi * hotAddRatio} MiB (ratio ${hotAddRatio})"
        }

        if (uefi) {
            vmRequestBody.spec.template.spec.domain.firmware = [bootloader: [efi: [secureBoot: secureBoot]]]
            if (secureBoot) {
                vmRequestBody.spec.template.spec.domain.features.smm = [enabled: true]
            }
        }

        // Add nodeSelector to VM request body only if the template has it
        if (virtualImageConfig?.nodeSelector) {
            log.info "Template has nodeSelector: ${JsonOutput.toJson(virtualImageConfig.nodeSelector)}"
            vmRequestBody.spec.template.spec.nodeSelector = virtualImageConfig.nodeSelector
        }

        //log.info "Created VM request body:\n${JsonOutput.prettyPrint(JsonOutput.toJson(vmRequestBody))}"
        return vmRequestBody
    }

    /**
     * Builds the VM labels map by merging template labels with default and morpheus labels
     * @param virtualImageConfig the config map from the virtual image containing template labels
     * @param templateName the name of the template
     * @param templateNamespace the namespace of the template
     * @param serverLabels the labels from the server object (morpheus labels)
     * @return Map containing all merged labels for the VM
     */
    private Map buildVMLabels(Map virtualImageConfig, String templateName, String templateNamespace, def serverLabels) {
        Map vmLabels = [:]

        // Add labels from virtual image config (template labels) as base
        if (virtualImageConfig?.labels) {
            vmLabels.putAll(virtualImageConfig.labels as Map)
            log.debug "Added ${virtualImageConfig.labels.size()} labels from virtual image template"
        }

        // Record which Harvester image the VM was built from (label values cannot contain '/')
        if (virtualImageConfig?.imageName) {
            vmLabels["harvesterhci.io/image-name"] = virtualImageConfig.imageName
        }
        // this label is added to indicate that this VM is provisioned via morpheus
        vmLabels[MORPHEUS_VM_PROVISIONING_LABEL] = "true"

        // Add morpheus labels from server object if they exist
        // Create separate label keys for each label: morpheus-label.1, morpheus-label.2, etc.
        if (serverLabels) {
            serverLabels.eachWithIndex { label, index ->
                String labelKey = "morpheus-label.${index + 1}".toString()
                vmLabels[labelKey] = label.name
                log.debug "Adding morpheus label to VM: ${labelKey} = ${label.name}"
            }
        }

        return vmLabels
    }

    /**
     * Builds the VM annotations map by merging template annotations with default annotations
     * @param virtualImageConfig the config map from the virtual image containing template annotations
     * @param osType the OS type for the VM
     * @param templateName the name of the template
     * @return Map containing all merged annotations for the VM
     */
    private Map buildVMAnnotations(Map virtualImageConfig, String osType, String templateName) {
        Map vmAnnotations = [:]

        // Add annotations from virtual image config (template annotations) as base
        if (virtualImageConfig?.annotations) {
            vmAnnotations.putAll(virtualImageConfig.annotations as Map)
            log.debug "Added ${virtualImageConfig.annotations.size()} annotations from virtual image template"
        }

        // Override with default annotations
        vmAnnotations["vm.kubevirt.io/os"] = osType
        if (virtualImageConfig?.imageId) {
            vmAnnotations[HarvesterConstants.ANNOTATION_IMAGE_ID] = virtualImageConfig.imageId
        }

        return vmAnnotations
    }

    /**
     * Build the API path for Harvester VM resources (KubeVirt VirtualMachines)
     * @param namespace The namespace where the VM resource is located
     * @param vmName The VM resource name (optional)
     * @return String representing the API path for VM operations
     */
    static String getVMApiResourcePath(String namespace, String vmName = null) {
        String apiPath = "/apis/kubevirt.io/v1/namespaces/${namespace}/virtualmachines"
        if (vmName) {
            apiPath += "/${vmName}"
        }
        return apiPath
    }

    /**
     * Function which makes the Harvester API call for VM operations
     * @param harvesterApiClient the API client to use for the call
     * @param method HTTP method (post, get, put, delete)
     * @param apiPath the API path to call
     * @param params the request body parameters
     * @param needsWait whether to wait for resource to be ready (default false for VMs)
     * @param maxRetries maximum number of retries for status checking
     * @param waitInterval wait interval between retries in milliseconds
     * @param queryParams optional query parameters for the API call
     * @param targetState the target state to wait for (default "Running" for VMs)
     * @return ServiceResponse containing the result of the operation
     */
    static ServiceResponse makeHarvesterApiCallAndWait(
            def harvesterApiClient, String method, String apiPath, Map params, boolean needsWait = false,
            int maxRetries = 100, int waitInterval = 30000, Map queryParams = null, String targetState = vmResourceStatusRunning) {

        try {
            log.info "Making ${method.toUpperCase()} API call to: ${apiPath}"
            log.debug "Request parameters: ${params}"

            // make the actual PUT/POST/DELETE api call here
            ServiceResponse response = queryParams ?
                harvesterApiClient."$method"(apiPath, params, queryParams) :
                harvesterApiClient."$method"(apiPath, params)

            // if REST call is not successful, return the response
            if (!response.success) {
                log.error "Failed to make API call: ${response.msg}, API: ${apiPath}, Method: ${method}, response status code: ${response.statusCode}"
                return response
            }

            // For VM operations, we typically consider 200, 201, 202 as success
            if (response.statusCode in vmHTTPSuccessCodes) {
                log.info "API call successful: ${apiPath}, Method: ${method}, response status code: ${response.statusCode}"

                // if needsWait is false, return the response immediately
                if (!needsWait) {
                    return response
                }

                // wait for the VM to be in proper state before returning
                log.info "API call successful: ${apiPath}, Method: ${method}, response status code: ${response.statusCode}, waiting for VM to be in ${targetState} state"

                String createdVMNamespace = null
                String createdVMName = null
                if (params?.metadata?.namespace && params?.metadata?.name) {
                    createdVMNamespace = params.metadata.namespace
                    createdVMName = params.metadata.name
                } else {
                    // Try to extract from apiPath if it exists. Update regex if needed for other kind of apis
                    def matcher = apiPath =~ /\/namespaces\/([^\/]+)\/virtualmachines\/([^\/]+)/
                    if (matcher.find()) {
                        createdVMNamespace = matcher.group(1)
                        createdVMName = matcher.group(2)
                    }
                }
                if (!createdVMName || !createdVMNamespace) {
                    log.error "Could not extract VM name or namespace from params or apiPath"
                    return ServiceResponse.error("Could not extract VM name or namespace from API call context")
                }

                // prepare apiPath for GET request to check VM status
                String getVMPath = getVMApiResourcePath(createdVMNamespace, createdVMName)
                log.info "Checking VM status at path: ${getVMPath}"

                // Poll often so a VM that starts quickly isn't held up; keep roughly a 5 minute ceiling
                int calculatedMaxRetries = Math.max(10, (int) (300000 / Math.max(waitInterval, 1000)))
                log.info "Will check VM status for maximum ${calculatedMaxRetries} retries with ${waitInterval}ms intervals"

                int retries = 0
                while (retries < calculatedMaxRetries) {
                    try {
                        ServiceResponse getVMResponse = harvesterApiClient.get(getVMPath)

                        if (!getVMResponse.success) {
                            log.error "GET call to check VM status returned failure: ${getVMResponse.msg}"
                            log.info "Continuing with retries ${retries + 1}/${calculatedMaxRetries}"
                        } else {
                            // For KubeVirt VMs, the status can be found in different places depending on the resource state
//                            if(getVMResponse?.data) {
//                                String pretty = JsonOutput.prettyPrint(JsonOutput.toJson(getVMResponse.data))
//                                log.info "VM GET Response (pretty):\n${pretty}"
//                            }
                            def vmStatus = getVMResponse.data?.status
                            String vmPhase = vmStatus?.printableStatus ?: 'Unknown'

                            log.info "VM status check - Phase: ${vmPhase}"

                            // check if the VM is in a successful "Running" state
                            if (vmPhase == targetState) {
                                log.info "VM is ${targetState} : ${createdVMName} in namespace: ${createdVMNamespace}, status: ${vmPhase}"
                                return getVMResponse
                            } else if (vmPhase == vmResourceStatusFailed || vmPhase.contains("Error")) {
                                // VM is in "Failed" state, no recovery
                                log.error "VM creation failed with status: ${vmPhase}"
                                return ServiceResponse.error("VM creation failed with status: ${vmPhase}")
                            } else {
                                log.info "VM not yet ${targetState}, current phase: ${vmPhase}, retrying... (${retries + 1}/${calculatedMaxRetries})"
                            }
                        }
                    } catch (Exception ex) {
                        log.error "Exception while making GET call to check VM status: ${ex.message}"
                    }
                    // sleep for the wait interval before retrying
                    sleep(waitInterval)
                    retries++
                }
                // return error here since the retries are exceeded or timeout reached
                log.error "VM did not reach ${targetState} state after ${calculatedMaxRetries} retries. Timed out)"
                return ServiceResponse.error("Timed out waiting for VM State to become ${targetState}")

            } else {
                log.error "Unexpected response code from API call: ${response.statusCode}, message: ${response.msg}"
                return response
            }
        } catch (Exception e) {
            log.error "Exception while making API call: ${e.message}", e
            return ServiceResponse.error("Exception while making API call: ${e.message}")
        }
    }

    /**
     * Gets all assigned IP addresses for a provisioned VM by checking VirtualMachineInstance status
     * @param harvesterApiClient the API client to use for calls
     * @param namespace the namespace where the VM is located
     * @param vmName the name of the VM
     * @return ServiceResponse containing all assigned IP address information
     */
    static ServiceResponse getAllAssignedIPs(def harvesterApiClient, String namespace, String vmName) {
        log.info "getAllAssignedIPs: Getting all assigned IP addresses for VM: ${vmName} in namespace: ${namespace}"

        try {
            // Build API path for VirtualMachineInstance (VMI) which contains the actual IP information
            String vmiApiPath = "${KUBEVIRT_API_NAMESPACE_PATH}${namespace}${VIRTUAL_MACHINE_INSTANCES}${vmName}"
            log.info "getAllAssignedIPs: Checking VMI status at path: ${vmiApiPath}"
            ServiceResponse getVMIResponse = harvesterApiClient.get(vmiApiPath)

            if (!getVMIResponse.success) {
                log.warn "getAllAssignedIPs: GET call to check VMI status failed: ${getVMIResponse.msg}"
                return ServiceResponse.error("Failed to retrieve VMI status: ${getVMIResponse.msg}", null, [
                    vmName: vmName,
                    namespace: namespace
                ])
            }

            def vmiStatus = getVMIResponse.data?.status
            def interfaces = vmiStatus?.interfaces
            String nodeName = getVMIResponse.data?.status?.nodeName
            if (!interfaces || interfaces.size() == 0) {
                log.warn "getAllAssignedIPs: VM ${vmName} has no network interfaces"
                return ServiceResponse.error("No network interfaces found", null, [
                    vmName: vmName
                ])
            }

            // Collect all IP addresses from all interfaces
            def allAssignedIPs = []
            def interfaceDetails = []

            interfaces.each { iface ->
                def interfaceInfo = [
                    name: iface.name,
                    ipAddresses: iface.ipAddresses ?: [],
                    mac: iface.mac ?: null
                ]

                if (iface.ipAddresses) {
                    allAssignedIPs.addAll(iface.ipAddresses)
                    log.info "getAllAssignedIPs: Found IP addresses on interface ${iface.name}: ${iface.ipAddresses}"
                } else {
                    log.info "getAllAssignedIPs: Interface ${iface.name} has no IP addresses assigned"
                }

                interfaceDetails.add(interfaceInfo)
            }

            // Return comprehensive IP information
            def responseData = [
                vmName: vmName,
                namespace: namespace,
                assignedIPs: allAssignedIPs,
                interfaceDetails: interfaceDetails,
                totalInterfaces: interfaces.size(),
                totalIPs: allAssignedIPs.size(),
                nodeName: nodeName
            ]

            log.info "getAllAssignedIPs: Successfully retrieved ${allAssignedIPs.size()} IP addresses for VM ${vmName}"
            return ServiceResponse.success(responseData)

        } catch (Exception ex) {
            String errorMsg = "Exception while retrieving assigned IP addresses for VM ${vmName}: ${ex.message}"
            log.error errorMsg, ex
            return ServiceResponse.error(errorMsg, null, [
                vmName: vmName,
                namespace: namespace
            ])
        }
    }

    /**
     * Updates the server network interfaces with actual interface details from the VMI
     * Maps VMI interface details (MAC address, IPs) to existing server interfaces by interface name
     * @param server the ComputeServer to update
     * @param interfaceDetails list of interface details from VMI status (contains name, mac, ipAddresses)
     * @param primaryIP the primary IP address determined from ping test
     * @return ServiceResponse indicating success or failure
     */
    private ServiceResponse updateServerNetworkInterfaces(ComputeServer server, List interfaceDetails, String primaryIP) {
        log.info "updateServerNetworkInterfaces: Updating network interfaces for server: ${server.name}"
        log.info "updateServerNetworkInterfaces: Interface details: ${interfaceDetails}"

        try {
            String ifaceCode = "virtio"
            def interfaceTypeList = DBUtils.getInterfaceTypeFromInterfaceModel(morpheusContext, [ifaceCode])
            def interfaceType = interfaceTypeList?.find { it.code == ifaceCode }

            if (!interfaceType) {
                log.warn "updateServerNetworkInterfaces: Could not find virtio interface type"
            }

            // Create a map of VMI interface details by name for quick lookup
            Map vmiInterfaceMap = [:]
            interfaceDetails.each { vmiInterface ->
                if (vmiInterface?.mac && vmiInterface?.name) {
                    vmiInterfaceMap[vmiInterface.name as String] = [
                            mac        : vmiInterface.mac,
                            ipAddresses: vmiInterface.ipAddresses ?: []
                    ]
                }
            }

            log.info "updateServerNetworkInterfaces: VMI interface map: ${vmiInterfaceMap.keySet()}"

            // Get existing server interfaces - use server.interfaces which are already loaded
            def existingInterfaces = server.interfaces
            log.info "updateServerNetworkInterfaces: Found ${existingInterfaces?.size() ?: 0} existing interfaces on server"

            List<ComputeServerInterface> interfacesToSave = []

            // Update existing interfaces by matching interface name
            existingInterfaces?.each { existingInterface ->
                def interfaceName = existingInterface.name
                def vmiInterfaceData = vmiInterfaceMap[interfaceName]

                if (vmiInterfaceData) {
                    log.info "updateServerNetworkInterfaces: Found matching VMI data for interface ${interfaceName}"
                    boolean needsSave = false

                    def macAddress = vmiInterfaceData.mac
                    def ipAddresses = vmiInterfaceData.ipAddresses

                    // Update MAC address
                    if (macAddress && existingInterface.macAddress != macAddress) {
                        log.info "updateServerNetworkInterfaces: Updating MAC address for ${interfaceName}: ${existingInterface.macAddress} -> ${macAddress}"
                        existingInterface.macAddress = macAddress
                        needsSave = true
                    }

                    // Update interface type to virtio if not already set
                    if (interfaceType && existingInterface.type?.code != ifaceCode) {
                        log.info "updateServerNetworkInterfaces: Setting interface type to virtio for ${interfaceName}"
                        existingInterface.type = interfaceType as ComputeServerInterfaceType
                        needsSave = true
                    }

                    // Update IP addresses
                    ipAddresses.each { ipEntry ->
                        NetAddress.AddressType ipType = CommonUtils.getIpType(ipEntry as String)
                        def existingAddress = existingInterface.addresses?.find { it.address == ipEntry }
                        if (!existingAddress && ipType) {
                            log.info "updateServerNetworkInterfaces: Adding IP ${ipEntry} to interface ${interfaceName}"
                            existingInterface.addresses.add(new NetAddress(type: ipType, address: ipEntry))
                            needsSave = true
                        }
                    }

                    if (needsSave) {
                        interfacesToSave.add(existingInterface)
                    }
                } else {
                    log.warn "updateServerNetworkInterfaces: No matching VMI data found for interface ${interfaceName}"
                }
            }

            // Save updated interfaces
            if (interfacesToSave) {
                log.info "updateServerNetworkInterfaces: Saving ${interfacesToSave.size()} updated interfaces"
                morpheusContext.async.computeServer.computeServerInterface.save(interfacesToSave).blockingGet()
            }

            log.info "updateServerNetworkInterfaces: Successfully updated network interfaces for server ${server.name}"
            return ServiceResponse.success([
                updatedCount: interfacesToSave.size()
            ])

        } catch (Exception e) {
            String errorMsg = "Exception while updating server network interfaces: ${e.message}"
            log.error errorMsg, e
            return ServiceResponse.error(errorMsg, null, [
                serverId: server.id,
                exception: e.message
            ])
        }
    }

    /**
     * Tests connectivity to assigned IP addresses using ping in round-robin fashion
     * @param assignedIPs list of IP addresses to test
     * @param vmName the VM name for logging purposes
     * @param maxTimeoutMinutes maximum time to spend testing (default 5 minutes)
     * @param pingIntervalSeconds interval between ping attempts in seconds (default 5 seconds)
     * @return String containing the first IP that responds to ping, or null if none respond
     */
    static String pingTestAssignedIPs(List assignedIPs, String vmName, int maxTimeoutMinutes = 1, int pingIntervalSeconds = 2) {
        log.info "pingTestAssignedIPs: Starting ping test for VM ${vmName} with IPs: ${assignedIPs}"

        if (!assignedIPs || assignedIPs.size() == 0) {
            log.warn "pingTestAssignedIPs: No IPs provided for ping test"
            return null
        }
        log.info "pingTestAssignedIPs: Assigned IPs before filtering: ${assignedIPs}"
        try {
            // Filter out link-local, loopback, and ::1 addresses
            List<String> filteredIPs = assignedIPs.findAll { ip ->
                String ipStr = ip?.toString()
                if (!ipStr) return false

                // IPv4 loopback (127.0.0.0/8)
                if (ipStr ==~ /^127\.\d{1,3}\.\d{1,3}\.\d{1,3}$/) {
                    log.debug "pingTestAssignedIPs: Filtering out IPv4 loopback address: ${ipStr}"
                    return false
                }

                // IPv6 loopback (::1)
                if (ipStr == "::1") {
                    log.debug "pingTestAssignedIPs: Filtering out IPv6 loopback address: ${ipStr}"
                    return false
                }

                // IPv4 link-local (169.254.0.0/16)
                if (ipStr ==~ /^169\.254\.\d{1,3}\.\d{1,3}$/) {
                    log.debug "pingTestAssignedIPs: Filtering out IPv4 link-local address: ${ipStr}"
                    return false
                }

                // IPv6 link-local (fe80::/10)
                if (ipStr.toLowerCase().startsWith("fe80:")) {
                    log.debug "pingTestAssignedIPs: Filtering out IPv6 link-local address: ${ipStr}"
                    return false
                }

                return true
            }

            log.info "pingTestAssignedIPs: Filtered IPs (after removing loopback/link-local): ${filteredIPs}"

            if (!filteredIPs || filteredIPs.size() == 0) {
                log.warn "pingTestAssignedIPs: No valid IPs remaining after filtering"
                return null
            }

            // Separate IPs into IPv4 and IPv6 lists
            List<String> ipv4List = []
            List<String> ipv6List = []

            filteredIPs.each { ip ->
                String ipStr = ip?.toString()
                if (isIPv6(ipStr)) {
                    ipv6List.add(ipStr)
                } else if (isIPv4(ipStr)) {
                    ipv4List.add(ipStr)
                }
            }

            log.info "pingTestAssignedIPs: Separated IPs - IPv4: ${ipv4List}, IPv6: ${ipv6List}"
            // Calculate maximum attempts based on timeout and interval
            // Each IP type gets its own dedicated timeout period
            // About a minute per IP family (3s ping timeout + interval). If the appliance can't ping the VM
            // (ICMP blocked), the interface IP fallback below is used instead of waiting minutes.
            int maxAttempt = Math.max(1, (int) ((maxTimeoutMinutes * 60) / (3 + Math.max(pingIntervalSeconds, 0))))

            // Test IPv4 addresses first (dedicated 5-minute window)
            if (ipv4List.size() > 0) {
                log.info "pingTestAssignedIPs: Starting IPv4 ping tests (max ${maxAttempt} attempts)"
                int ipv4Attempts = 0

                while (ipv4Attempts < maxAttempt) {
                    for (int ipIndex = 0; ipIndex < ipv4List.size(); ipIndex++) {
                        String currentIP = ipv4List[ipIndex]
                        ipv4Attempts++

                        log.info "pingTestAssignedIPs: Testing IPv4 address ${currentIP} (attempt ${ipv4Attempts}/${maxAttempt})"

                        // Perform ping test for IPv4
                        boolean pingSuccessful = performPingTest(currentIP, 3, false)
                        if (pingSuccessful) {
                            log.info "pingTestAssignedIPs: SUCCESS! IPv4 address ${currentIP} responded to ping after ${ipv4Attempts} attempts"
                            return currentIP
                        } else {
                            log.info "pingTestAssignedIPs: IPv4 address ${currentIP} did not respond to ping"
                        }
                    }

                    if (ipv4Attempts < maxAttempt) {
                        sleep(pingIntervalSeconds * 1000)
                    }
                }

                log.warn "pingTestAssignedIPs: IPv4 ping test completed after ${ipv4Attempts} attempts. No IPv4 address responded."
            } else {
                log.info "pingTestAssignedIPs: No IPv4 addresses to test"
            }

            // Test IPv6 addresses (dedicated 5-minute window)
            if (ipv6List.size() > 0) {
                log.info "pingTestAssignedIPs: Starting IPv6 ping tests (max ${maxAttempt} attempts)"
                int ipv6Attempts = 0

                while (ipv6Attempts < maxAttempt) {
                    for (int ipIndex = 0; ipIndex < ipv6List.size(); ipIndex++) {
                        String currentIP = ipv6List[ipIndex]
                        ipv6Attempts++

                        log.info "pingTestAssignedIPs: Testing IPv6 address ${currentIP} (attempt ${ipv6Attempts}/${maxAttempt})"

                        // Perform ping test for IPv6
                        boolean pingSuccessful = performPingTest(currentIP, 3, true)
                        if (pingSuccessful) {
                            log.info "pingTestAssignedIPs: SUCCESS! IPv6 address ${currentIP} responded to ping after ${ipv6Attempts} attempts"
                            return currentIP
                        } else {
                            log.info "pingTestAssignedIPs: IPv6 address ${currentIP} did not respond to ping"
                        }
                    }

                    if (ipv6Attempts < maxAttempt) {
                        sleep(pingIntervalSeconds * 1000)
                    }
                }

                log.warn "pingTestAssignedIPs: IPv6 ping test completed after ${ipv6Attempts} attempts. No IPv6 address responded."
            } else {
                log.info "pingTestAssignedIPs: No IPv6 addresses to test"
            }

            log.warn "pingTestAssignedIPs: All ping tests completed. No IP responded."
            return null

        } catch (Exception ex) {
            log.error "pingTestAssignedIPs: Exception during ping test for VM ${vmName}: ${ex.message}", ex
            return null
        }
    }

    /**
     * Performs a single ping test to the specified IP address
     * @param ipAddress the IP address to ping
     * @param timeoutSeconds timeout for the ping command (default 3 seconds)
     * @param isIPv6 flag indicating if the IP is IPv6 (default false)
     * @return boolean indicating whether ping was successful
     */
    static boolean performPingTest(String ipAddress, int timeoutSeconds = 3, boolean isIPv6 = false) {
        try {
            String pingCommand
            // Use ping6 for IPv6 addresses, ping for IPv4
            if (isIPv6) {
                // Linux/Unix ping6: ping6 -c 1 -W 3 <ip>
                pingCommand = "ping6 -c 1 -W ${timeoutSeconds} ${ipAddress}"
            } else {
                // Linux/Unix ping: ping -c 1 -W 3 <ip>
                pingCommand = "ping -c 1 -W ${timeoutSeconds} ${ipAddress}"
            }

            log.debug "performPingTest: Executing: ${pingCommand}"

            // Execute ping command
            Process process = Runtime.getRuntime().exec(pingCommand)
            int exitCode = process.waitFor()

            // Check exit code (0 = success)
            boolean success = (exitCode == 0)
            log.debug "performPingTest: Ping to ${ipAddress} ${success ? 'succeeded' : 'failed'} (exit code: ${exitCode})"

            return success

        } catch (Exception ex) {
            log.warn "performPingTest: Exception during ping to ${ipAddress}: ${ex.message}"
            return false
        }
    }

    /**
     * Checks if the given string is a valid IPv4 address
     * @param ip the IP address string to validate
     * @return true if the string is a valid IPv4 address, false otherwise
     */
    static boolean isIPv4(String ip) {
        if (!ip) return false
        try {
            // IPv4 pattern: four octets separated by dots (0-255.0-255.0-255.0-255)
            def ipv4Pattern = /^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$/
            return ip.matches(ipv4Pattern)
        } catch (Exception ex) {
            log.warn "isIPv4: Exception while validating IPv4 address ${ip}: ${ex.message}"
            return false
        }
    }

    /**
     * Checks if the given string is a valid IPv6 address
     * @param ip the IP address string to validate
     * @return true if the string is a valid IPv6 address, false otherwise
     */
    static boolean isIPv6(String ip) {
        if (!ip) return false
        try {
            // Simple IPv6 validation - contains colons and hexadecimal characters
            // Full validation would be more complex, but this covers common cases
            // IPv6 can have :: for compressed zeros, and contains hexadecimal digits with colons
            return ip.contains(':') && ip.matches(/^[0-9a-fA-F:]+$/)
        } catch (Exception ex) {
            log.warn "isIPv6: Exception while validating IPv6 address ${ip}: ${ex.message}"
            return false
        }
    }

    /**
     * Waits for IP address assignment to the VM by checking VirtualMachineInstance status
     * @param harvesterApiClient the API client to use for calls
     * @param namespace the namespace where the VM is located
     * @param vmName the name of the VM
     * @param maxRetries maximum number of retries for IP address checking (default 60 - 5 minutes)
     * @param waitInterval wait interval between retries in milliseconds (default 5000ms)
     * @return ServiceResponse containing IP address information or timeout error
     */
    static ServiceResponse waitForVMIpAssignment(
            def harvesterApiClient, String namespace, String vmName,
            int maxRetries = 60, int waitInterval = 5000) {

        log.info "Waiting for IP address assignment for VM: ${vmName} in namespace: ${namespace}"

        // Build API path for VirtualMachineInstance (VMI) which contains the actual IP information
        String vmiApiPath = "${KUBEVIRT_API_NAMESPACE_PATH}${namespace}${VIRTUAL_MACHINE_INSTANCES}${vmName}"
        log.info "Checking VMI status at path: ${vmiApiPath}"

        int retries = 0
        while (retries < maxRetries) {
            try {
                ServiceResponse getVMIResponse = harvesterApiClient.get(vmiApiPath)

                if (!getVMIResponse.success) {
                    log.warn "GET call to check VMI status returned failure: ${getVMIResponse.msg}"
                } else {
                    def vmiStatus = getVMIResponse.data?.status
                    def interfaces = vmiStatus?.interfaces

                    if (interfaces && interfaces.size() > 0) {
                        // Check for IP addresses in interfaces and identify primary interface IP
                        def assignedIPs = []
                        def primaryIP = null

                        interfaces.each { iface ->
                            if (iface.ipAddresses) {
                                assignedIPs.addAll(iface.ipAddresses)
                                log.info "Found IP addresses on interface ${iface.name}: ${iface.ipAddresses}"

                                // Check if this is the primary interface (named "eth0")
                                if (iface.name == "eth0") {
                                    primaryIP = iface.ipAddresses[0] // Take first IP from primary interface
                                    log.info "Primary interface ${iface.name} has IP: ${primaryIP}"
                                }
                            }
                        }

                        if (assignedIPs.size() > 0) {
                            log.info "VM ${vmName} has been assigned IP address(es): ${assignedIPs}"
                            // Return the response with IP information
                            getVMIResponse.data.assignedIPs = assignedIPs
                            // Use primary interface IP if found, otherwise fall back to first available IP
                            getVMIResponse.data.primaryIP = primaryIP ?: assignedIPs[0]
                            log.info "Selected primary IP: ${getVMIResponse.data.primaryIP}"
                            return getVMIResponse
                        } else {
                            log.info "VM ${vmName} interfaces exist but no IP addresses assigned yet (retry ${retries + 1}/${maxRetries})"
                        }
                    } else {
                        log.info "VM ${vmName} has no network interfaces ready yet (retry ${retries + 1}/${maxRetries})"
                    }
                }
            } catch (Exception ex) {
                log.error "Exception while checking VMI IP assignment: ${ex.message}"
            }

            // Sleep before next retry
            sleep(waitInterval)
            retries++
        }

        log.warn "VM ${vmName} did not get IP address assigned after ${maxRetries} - continuing with provisioning"
        return ServiceResponse.create([success: true, msg: "VM provisioning completed but IP assignment timed out"])
    }

    /**
     * Prepares the workload for provisioning
     * @param workload the Workload object to be provisioned
     * @param workloadRequest the WorkloadRequest object containing configuration details
     * @param opts additional options that may be needed for provisioning
     * @return ServiceResponse containing the PrepareWorkloadResponse
     */
    /**
     * Validates server configuration for Harvester provisioning
     * @param opts options containing instance and volume information
     * @return Map with success flag and list of errors
     */
    /**
     * Checks the disks chosen for each datastore fit in its free space (as of the last storage class sync).
     * Datastores whose capacity isn't known, and "Auto" selections, aren't checked.
     * @return validation errors (field 'volume')
     */
    List<Map> validateDatastoreCapacity(List volumes) {
        List<Map> errors = []
        Map<Long, Long> requestedBytes = [:]
        volumes?.each { volume ->
            Long datastoreId = toLongOrNull(volume?.datastoreId)
            Long sizeGb = toLongOrNull(volume?.size ?: volume?.maxStorage)
            if (datastoreId && sizeGb) {
                // maxStorage is already bytes; size is GB
                long bytes = volume?.size ? sizeGb * 1024L * 1024L * 1024L : sizeGb
                requestedBytes[datastoreId] = (requestedBytes[datastoreId] ?: 0L) + bytes
            }
        }
        requestedBytes.each { Long datastoreId, Long bytes ->
            try {
                def datastore = morpheusContext.services.cloud.datastore.get(datastoreId)
                Long free = datastore?.freeSpace
                if (free != null && bytes > free) {
                    errors << [field: 'volume', msg: "Not enough space on datastore ${datastore.name}: ${formatGb(bytes)} requested, " +
                            "${formatGb(free)} free. Choose another datastore or smaller disks."]
                }
            } catch (Exception e) {
                log.debug "validateDatastoreCapacity: datastore ${datastoreId} not checked: ${e.message}"
            }
        }
        return errors
    }

    private static Long toLongOrNull(def value) {
        if (value == null) return null
        if (value instanceof Number) return ((Number) value).longValue()
        String s = value.toString().trim()
        return s.isLong() ? s.toLong() : (s.isBigDecimal() ? s.toBigDecimal().longValue() : null)
    }

    private static String formatGb(long bytes) {
        return String.format('%.1f GB', bytes / (1024d * 1024d * 1024d))
    }

    def validateServerConfig(Map opts = [:]) {
        // The selected resource pool is the namespace to provision into
        try {
            DBUtils.applyNamespaceFromPool(morpheusContext, opts)
        } catch (e) {
            log.warn "Unable to resolve the namespace from the resource pool: ${e.message}"
        }
        log.debug("validateServerConfig: ${opts}")
        def rtn = [success:false, errors:[]]

        try {
            // Regex pattern for Harvester DNS-1123 subdomain names
            String regex = "^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*"
            String errorMessage = 'Name must consist of lower case alphanumeric characters, \'-\' or \'.\', and must start and end with an alphanumeric character (e.g. \'example.com\')'

            // Validate instance.name
//            def instanceName = opts.instance?.name
//            log.debug "Validating instance name: '${instanceName}'"
//
//            if (instanceName) {
//                def matches = (instanceName ==~ regex)
//                if (!matches) {
//                    log.warn "Instance name '${instanceName}' failed validation"
//                    rtn.errors << [field:'name', msg:errorMessage]
//                }
//            }

            // Validate volume names
            def volumes = opts?.volumes
            log.debug "Validating volumes in opts: ${volumes}"
            if (volumes && volumes instanceof List) {
                log.debug "Validating ${volumes.size()} volume name(s)"

                // Track volume names to check for duplicates
                def volumeNames = []
                def hasDuplicateVolumes = false

                volumes.eachWithIndex { volume, index ->
                    def volumeName = volume?.name
                    if (volumeName) {
                        // Check regex validation
                        def matches = (volumeName ==~ regex)
                        if (!matches) {
                            log.warn "Volume[${index}] name '${volumeName}' failed validation"
                            rtn.errors << [field:"volume", msg:errorMessage]
                        }

                        // Check for duplicate volume names
                        if (volumeNames.contains(volumeName)) {
                            log.warn "Volume[${index}] has duplicate name: ${volumeName}"
                            hasDuplicateVolumes = true
                        } else {
                            volumeNames << volumeName
                        }
                    }
                }

                // Add error message for duplicate volumes
                if (hasDuplicateVolumes) {
                    rtn.errors << [field:"volume", msg:"Duplicate volume names detected. Please ensure each volume has a unique name."]
                }

                // Refuse disks that don't fit on their datastore (storage class)
                rtn.errors.addAll(validateDatastoreCapacity(volumes as List))
            } else {
                log.debug "No volumes to validate or volumes is not a list"
            }

            // Validate networkInterfaces
            def networkInterfaces = opts?.networkInterfaces
            log.debug "Validating networkInterfaces in opts: ${networkInterfaces}"
            if (networkInterfaces && networkInterfaces instanceof List) {
                log.debug "Validating ${networkInterfaces.size()} network interface(s)"

                // Track network IDs to check for duplicates
                def networkIds = []
                def hasEmptyNetwork = false
                def hasDuplicates = false

                // Track project prefixes for network names
                def projectPrefixes = []
                def hasMismatchedProjects = false

                networkInterfaces.eachWithIndex { netInterface, index ->
                    def networkId = netInterface?.network?.id

                    // Check if network ID is empty/null
                    if (!networkId) {
                        log.warn "NetworkInterface[${index}] has empty network ID"
                        hasEmptyNetwork = true
                    } else {
                        // Check for duplicate network IDs
                        if (networkIds.contains(networkId)) {
                            log.warn "NetworkInterface[${index}] has duplicate network ID: ${networkId}"
                            hasDuplicates = true
                        } else {
                            networkIds << networkId
                        }

                        // Fetch network from database using networkId to get displayName
                        try {
                            Long netId = networkId as Long
                            def network = morpheusContext.services.network.get(netId)
                            def networkName = network?.displayName
                            log.info "NetworkInterface[${index}] has networkId: ${networkId}, displayName: '${networkName}'"

                            // Validate project prefix consistency for networks with project/name format
                            if (networkName && networkName.contains('/')) {
                                def projectPrefix = networkName.substring(0, networkName.indexOf('/'))
                                log.info "NetworkInterface[${index}] has network displayName: '${networkName}' with project prefix: '${projectPrefix}'"

                                if (projectPrefixes.isEmpty()) {
                                    projectPrefixes << projectPrefix
                                } else if (!projectPrefixes.contains(projectPrefix)) {
                                    log.warn "NetworkInterface[${index}] has mismatched project prefix: '${projectPrefix}', expected: '${projectPrefixes[0]}'"
                                    projectPrefixes << projectPrefix
                                    hasMismatchedProjects = true
                                }
                            }
                        } catch (Exception e) {
                            log.error "Error fetching network with ID ${networkId}: ${e.message}", e
                        }
                    }
                }

                // Add error messages if validation failed
                if (hasEmptyNetwork) {
                    rtn.errors << [field:"networkInterface", msg:"One or more network interfaces have no network selected. Please select a network for all interfaces."]
                }
                if (hasDuplicates) {
                    rtn.errors << [field:"networkInterface", msg:"Duplicate network interfaces detected. Please ensure each interface uses a unique network."]
                }
                if (hasMismatchedProjects) {
                    rtn.errors << [field:"networkInterface", msg:"All network interfaces must belong to the same project. Networks from different projects detected: ${projectPrefixes.join(', ')}."]
                }

                // Validate that network project matches config project
                def config = opts?.config
                if (!projectPrefixes.isEmpty()) {
                    def configProjectName = null

                    // Extract project name from config
                    if (config?.project) {
                        if (config.project instanceof Map) {
                            configProjectName = config.project.name
                        } else {
                            configProjectName = config.project.toString()
                        }
                    }

                    log.debug "Config project: '${configProjectName}', Network project prefixes: ${projectPrefixes}"

                    if (configProjectName) {
                        def networkProjectPrefix = projectPrefixes[0]

                        log.debug "Comparing config project '${configProjectName}' with network project prefix '${networkProjectPrefix}'"

                        // Harvester lets a VM attach to VM networks in other namespaces, so this is informational only
                        if (networkProjectPrefix != "default" && configProjectName != networkProjectPrefix) {
                            log.info "Config project '${configProjectName}' differs from network namespace '${networkProjectPrefix}' - the network will be referenced as '${networkProjectPrefix}/<name>'"
                        }
                    }
                }
            } else {
                log.debug "No network interfaces to validate or networkInterfaces is not a list"
            }

            // Validate config object
            def config = opts?.config
            log.debug "Validating config in opts: ${config}"
            if (config) {
                // Check if template is null
                if (config.template == null) {
                    log.warn "Config template is null - image is required"
                    rtn.errors << [field:"template", msg:"Image is required"]
                }

                // Check if project is null
                if (config.project == null) {
                    log.warn "Config project is null - project is required"
                    rtn.errors << [field:"project", msg:"Project is required"]
                }
            } else {
                log.debug "No config to validate"
            }

            rtn.success = rtn.errors.size() == 0
        } catch(e) {
            log.error "validateServerConfig error: ${e}", e
        }

        return rtn
    }

    /**
     * Validates the provided provisioning options of a workload. A return of success = false will halt the
     * creation and display errors
     * @param opts options
     * @return ServiceResponse with errors. Errors should be added using addError(field, message)
     */
    ServiceResponse validateWorkload(Map opts = [:]) {
        log.info("validateWorkload: ${opts}")
        ServiceResponse rtn = new ServiceResponse(true, null, [:], null)

        try {
            def validationResults = validateServerConfig(opts)
            if(!validationResults.success) {
                validationResults.errors?.each { it ->
                    rtn.addError(it.field, it.msg)
                }
            }
        } catch(e) {
            log.error("validateWorkload error: ${e}", e)
        }

        return rtn
    }

    ServiceResponse prepareWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {

        log.info "Starting prepareWorkload for workload ID: ${workload?.id}, name: ${workload?.name}"
        log.info "prepareWorkload full parameters: ${workload} ${workloadRequest} ${opts}"
        def rtn = [success: false, msg: null]
        rtn.data = new PrepareWorkloadResponse(workload: workload)
        try {
            ComputeServer server = workload.getServer()

            // The selected resource pool is the namespace: record it on the server now, because the disks are created
            // (by the datastore provider) before runWorkload and read the namespace from the server
            try {
                String namespace = DBUtils.applyNamespaceFromPool(morpheusContext, opts)
                if (namespace && server) {
                    server.setConfigProperty('project', [name: namespace])
                    if (server.id) {
                        ComputeServer dbServer = morpheusContext.services.computeServer.get(server.id)
                        if (dbServer) {
                            dbServer.setConfigProperty('project', [name: namespace])
                            morpheusContext.services.computeServer.save(dbServer)
                        }
                    }
                    log.info "Provisioning ${server.name} into namespace ${namespace}"
                }
            } catch (Exception e) {
                log.warn "Unable to record the namespace for ${server?.name}: ${e.message}"
            }

            // Validate server name using Harvester DNS-1123 subdomain naming rules
            String regex = "^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*"
            String serverName = server?.name
            log.info "Validating server name: '${serverName}'"
            def matches = (serverName ==~ regex)
            if (!matches) {
                String errorMessage = "Server name '${serverName}' is invalid. Name must consist of lower case alphanumeric characters, '-' or '.', and must start and end with an alphanumeric character (e.g. 'example.com')"
                log.error errorMessage
                throw new RuntimeException(errorMessage)
            }

            // Cores per socket must divide the core count (fails here, before any disk is created)
            Map<String, Long> cpuTopology = resolveCpuTopology(workload, opts)
            log.info "CPU layout for ${serverName}: ${cpuTopology.sockets} socket(s) × ${cpuTopology.cores} core(s)"

            // Validate labels from server object
            if (server?.labels) {
                log.info "Validating labels for server: ${server.name}"

                // Collect all label names
                def labelNames = server.labels.collect { it.name }
                log.info "Server labels to validate: ${labelNames} for server ${server.name}"

                // Validate each individual label name separately with numbered keys
                server.labels.eachWithIndex { label, index ->
                    Map validationResult = validateLabelValue(label.name)
                    if (!validationResult.success) {
                        String errorMessage = "Label validation failed (value: '${label.name}'): ${validationResult.error}"
                        log.error errorMessage
                        throw new RuntimeException(errorMessage)
                    }
                }
            }

            Long virtualImageId
            if(workload.getConfigProperty('virtualImageId')) {
                virtualImageId = workload.getConfigProperty('virtualImageId')?.toLong()
                log.info "Using virtualImageId from workload config: ${virtualImageId}"
            } else if(server?.sourceImage) {
                virtualImageId = server.sourceImage.id
                log.info "Using virtualImageId from server.sourceImage: ${virtualImageId}"
            }

            if(!virtualImageId) {
                log.warn "No virtual image selected for workload ${workload?.id}"
                rtn.msg = "No virtual image selected"
            } else {
                log.info "Retrieving virtual image with ID: ${virtualImageId}"
                VirtualImage virtualImage
                try {
                    virtualImage = morpheusContext.services.virtualImage.get(virtualImageId)
                    log.info "Retrieved virtualImage: ${virtualImage?.id}, name: ${virtualImage?.name}, osType: ${virtualImage?.osType}"
                } catch(e) {
                    log.error "Error retrieving virtual image with ID ${virtualImageId}: ${e.message}", e
                }

                if(!virtualImage) {
                    log.warn "No virtual image found for ID: ${virtualImageId}"
                    rtn.msg = "No virtual image found for ${virtualImageId}"
                } else {
                    log.info "Setting sourceImage for server ${server?.id} to virtualImage ${virtualImage?.id}"
                    workload.server.sourceImage = virtualImage
                    ComputeServer dbServer = morpheusContext.services.computeServer.get(server.id)
                    dbServer.sourceImage = virtualImage
                    morpheusContext.services.computeServer.bulkSave([dbServer])

                    //AUTO-DATASTORE RESOLUTION 
                    def serverVolumes = workload?.server?.volumes
                    if (serverVolumes) {
                        def volumesWithNullDatastore = serverVolumes.findAll { it.datastore == null }
                        if (volumesWithNullDatastore) {
                            log.info "Found ${volumesWithNullDatastore.size()} volumes with null datastore " +
                                    "(Auto selection) - resolving to cluster default storage class"

                            def resourcePoolId = opts?.config?.resourcePoolId
                            def poolInfo = DBUtils.getZonePoolFromIdString(morpheusContext, resourcePoolId)
                            ComputeServerGroup clusterServerGroup = DBUtils.resolveConnection(morpheusContext, poolInfo)

                            if (!clusterServerGroup) {
                                throw new RuntimeException(
                                        "Cannot resolve auto-datastore: cluster not found from resourcePoolId ${resourcePoolId}")
                            }

                            // Live-fetch storage classes from the K8s API
                            def storageClassHelper = new StorageClassApiHelper(clusterServerGroup)
                            ServiceResponse defaultResponse = storageClassHelper.getDefaultStorageClass()

                            if (!defaultResponse.success) {
                                throw new RuntimeException(
                                        "Auto-datastore selected but ${defaultResponse.msg}")
                            }

                            String defaultStorageClassName = defaultResponse.data.storageClass.name
                            String resolvedFrom = defaultResponse.data.source
                            log.info "Resolved default storage class: '${defaultStorageClassName}' " +
                                    "(source: ${resolvedFrom})"

                            def datastoreList = morpheusContext.services.cloud.datastore.list(
                                    new DataQuery().withFilters(
                                            DBUtils.datastoreFilter(clusterServerGroup),
                                            new DataFilter('name', defaultStorageClassName),
                                    )
                            ).toList()

                            if (!datastoreList) {
                                throw new RuntimeException(
                                        "Default storage class '${defaultStorageClassName}' is not synced " +
                                                "as a Datastore. Run a storage class sync first.")
                            }

                            def resolvedDatastore = datastoreList[0]
                            log.info "Matched to Datastore id=${resolvedDatastore.id}, " +
                                    "name=${resolvedDatastore.name}, type=${resolvedDatastore.type}"

                            // Populate each null-datastore volume with the resolved Datastore
                            volumesWithNullDatastore.each { volume ->
                                volume.datastore = resolvedDatastore
                                // "Auto" was chosen: a root disk stays on its image's storage class (no copy)
                                volume.setConfigProperty(HarvesterConstants.CONFIG_AUTO_DATASTORE, true)
                                morpheusContext.services.storageVolume.save(volume)
                                log.info "Volume ${volume.id} (${volume.name}) - assigned Datastore: " +
                                        "id=${resolvedDatastore.id}, name=${resolvedDatastore.name}, type=${resolvedDatastore.type}"
                            }
                        }
                    }

                    rtn.success = true
                    log.info "Successfully prepared workload ${workload?.id} with virtual image ${virtualImage?.id}"
                    // Add task update for creating volumes
                    if (workloadRequest.process) {
                        morpheusContext.services.process.startProcessStep(workloadRequest.process, new ProcessEvent(stepType: Constants.PROCESS_STEP_PROVISION_STORAGE), "Creating volumes")
                    }
                }
            }
        } catch(e) {
            rtn.msg = "Error in PrepareWorkload: ${e.message}"
            log.error "Error in PrepareWorkload: ${e.message}", e
        }
        log.info "PrepareWorkload completed for workload ${workload?.id}, success: ${rtn.success}, message: ${rtn.msg}"
        log.debug "Data: ${rtn.data} - ${rtn.data.workload?.server?.volumes}"
        return new ServiceResponse(rtn.success, rtn.msg, null, rtn.data)
    }

    /**
     * Runs the workload provisioning
     * @param workload the Workload object to be provisioned
     * @param workloadRequest the WorkloadRequest object containing configuration details
     * @param opts additional options that may be needed for provisioning
     * @return ServiceResponse containing the ProvisionResponse
     */
    ServiceResponse<ProvisionResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
        log.info "VirtualMachineProvisioning: new method call runWorkload called for  workloadRequest ${workloadRequest}"
        try {
            def resourcePoolId = opts.config.resourcePoolId
            def poolInfo = DBUtils.getZonePoolFromIdString(morpheusContext, resourcePoolId)
            def serverGroup = DBUtils.resolveConnection(morpheusContext, poolInfo)
            // The selected resource pool is the namespace to provision into (sets opts.config.project)
            try {
                DBUtils.applyNamespaceFromPool(morpheusContext, opts)
            } catch (e) {
                log.warn "Unable to resolve the namespace from the resource pool: ${e.message}"
            }
            // keep the server group object stored here
            if (!this.computeServerGroup) {
                this.computeServerGroup = serverGroup
            }

            ProvisionResponse provisionResponse = new ProvisionResponse()
            // Create the Harvester API client
            def harvesterApiClient = new HarvesterApiClient(serverGroup)

            Long virtualImageId = workload.server.sourceImage?.id
            VirtualImage virtualImage = morpheusContext.services.virtualImage.get(virtualImageId)
            String platform = virtualImage.platform

            opts.platform = platform
            if (isHotAdd(opts, workload)) {
                opts.hotplugRatio = hotplugRatio(harvesterApiClient)
            }

            // Build VM request body using the separate function
            Map vmRequestBody = createVMRequestBody(workload, workloadRequest, opts)

            // ISO image: the root disk is created blank (see HarvesterVolumes) and the ISO is attached as a CD-ROM
            // that boots first, the way Harvester installs from an ISO
            if (CdromHelper.isIsoImage(virtualImage)) {
                attachIsoForProvisioning(harvesterApiClient, vmRequestBody, virtualImage)
            }

            // Get namespace from the VM request body or use default
            String namespace = vmRequestBody.metadata.namespace
            log.info "namespace is ${namespace}"
            // Build API path using the generic function
            String apiPath = getVMApiResourcePath(namespace)

            if (workloadRequest.process) {
                morpheusContext.services.process.startProcessStep(workloadRequest.process, new ProcessEvent(stepType: Constants.PROCESS_STEP_PROVISION_VM), "Deploy Virtual Machine")
            }
            // Dry run to check if the VM request is valid
            log.info "Making API call to create VM with dryRun at path: ${apiPath}"
            
            try {
                ServiceResponse dryRunResponse = makeHarvesterApiCallAndWait(
                        harvesterApiClient, 'post', apiPath, vmRequestBody, false, 100,
                        30000, [dryRun: 'All'])
                if (!dryRunResponse.success) {
                    // Extract error message from data.message if msg is null
                    String errorMessage = dryRunResponse.msg
                    if (!errorMessage && dryRunResponse.data?.message) {
                        errorMessage = dryRunResponse.data.message
                    }
                    if (!errorMessage) {
                        errorMessage = "VM dry run validation failed with unknown error"
                    }
                     
                    log.error "Dry run for VM creation failed: ${errorMessage}, API Path: ${apiPath}"
                    throw new RuntimeException("VM dry run validation failed: ${errorMessage}")
                }
                log.info "Dry run for VM creation succeeded."
            } catch (Exception ex) {
                log.error("Exception during VM dry run: ${ex.message}", ex)
                throw new RuntimeException("Exception during VM dry run: ${ex.message}")
            }
            // Make the API call using the reusable function (similar to HarvesterVolumes pattern)
            String desiredVMState = vmResourceStatusRunning
            if (platform == HarvesterConstants.OS_TYPE_WINDOWS) {
                desiredVMState = vmResourceStatusStopped
            }
            ServiceResponse apiResponse = makeHarvesterApiCallAndWait(harvesterApiClient, 'post', apiPath,
                    vmRequestBody, true, 100, 5000, null, desiredVMState)
            if (!apiResponse.success) {
                throw new RuntimeException("Harvester VM creation failed. Error: ${apiResponse?.error}")
            } else {
                log.info "Harvester VM creation API call successful"
            }

            // extract and update the uid after the vm is created
            String uuid = apiResponse?.data?.metadata?.uid
            if (uuid) {
                log.info "Setting server uniqueId and externalId to ${uuid}"
                workload.server.uniqueId = uuid
                workload.server.externalId = uuid
                ComputeServer dbServer = morpheusContext.services.computeServer.get(workload.server.id)
                dbServer.uniqueId = uuid
                dbServer.externalId = uuid
                morpheusContext.services.computeServer.bulkSave([dbServer])
            }

            // Wait for VM to boot up after successful VM creation
            String vmName = workload.server?.name
            String vmNamespace = vmRequestBody.metadata.namespace

            // Handle windows VM differently before VM boots up
            if (platform == HarvesterConstants.OS_TYPE_WINDOWS) {
                log.info "Handling Windows VM post-creation steps for VM: ${workload.server?.name}"
                ServiceResponse configureResp = configureSysprepOnWindowsVM(harvesterApiClient, vmNamespace, vmName, workload, workloadRequest, serverGroup, opts, virtualImage)
                if (configureResp?.success) {
                    log.info "Successfully configured sysprep on Windows VM ${vmName}"
                } else {
                    log.error "Failed to configure sysprep on Windows VM ${vmName}: ${configureResp?.msg}"
                }
                // try powering on the VM regardless of the outcome and wait till it reaches running state.
                String startAPIPath = VirtualMachineReconfigure.getVMApiSubresourcePath(namespace, vmName) + "/start"
                log.info("Calling start for VM ${vmName}. Path: ${startAPIPath}")
                apiResponse = makeHarvesterApiCallAndWait(harvesterApiClient, 'put', startAPIPath,
                        [:], true, 100, 5000, null, vmResourceStatusRunning)
                if (!apiResponse.success) {
                    throw new RuntimeException("Start of VM ${vmName} failed. Error: ${apiResponse.msg}, API Path: ${startAPIPath}")
                } else {
                    log.info "Successfully powered on VM ${vmName}"
                }
            }
            log.info "Waiting for VM ${vmName} to boot up..."

            if (workloadRequest.process) {
                morpheusContext.services.process.startProcessStep(workloadRequest.process, new ProcessEvent(stepType: Constants.PROCESS_STEP_WAIT_FOR_VM_BOOT), "Wait for VM Boot")
            }
            ServiceResponse bootResponse = waitForVMBoot(harvesterApiClient, namespace, vmName, workload, workloadRequest,
                    isSkipNetworkWait(opts, workload))

            if (!bootResponse.success) {
                log.error "VM ${vmName} boot check failed: ${bootResponse.msg}"
                throw new RuntimeException("VM boot check failed for ${vmName}: ${bootResponse.msg}")
            }

            String nodeName = bootResponse.data?.nodeName
            def primaryIP = bootResponse.data?.primaryIP

            if (nodeName) {
                log.info "VM ${vmName} is running on node: ${nodeName}"
                def parentServerDetails = DBUtils.getServerIdByNodeName(morpheusContext, nodeName, serverGroup)
                if (parentServerDetails) {
                    log.info "Found parent server details: ${parentServerDetails}"
                    workload.server.parentServer = [id: parentServerDetails.id]
                    ComputeServer dbServer = morpheusContext.services.computeServer.get(workload.server.id)
                    dbServer.parentServer = [id: parentServerDetails.id]
                    morpheusContext.services.computeServer.bulkSave([dbServer])
                } else {
                    log.warn "No parent server found for node: ${nodeName}"
                }
            }

            // Query DataVolumes for volumes with sourceId
            log.info "Querying DataVolumes for server volumes with sourceId"
            ProvisionHelper.queryDataVolumesForServerVolumes(workload.server, morpheusContext, harvesterApiClient, namespace)
            // Prepare the provisionResponse based on the API response
            log.info "setting provision response values"
            provisionResponse.externalId = uuid
            provisionResponse.createUsers = workloadRequest.usersConfiguration.createUsers
            // Use assigned IP if available, otherwise fall back to configured IP or null
            if (primaryIP) {
                // Use the IP address that was actually assigned by Harvester
                provisionResponse.privateIp = primaryIP
                provisionResponse.publicIp = primaryIP
                provisionResponse.poolId = null
                provisionResponse.hostname = workload.server?.hostname
                log.info "Using assigned IP address: ${primaryIP}"
            } else if(workloadRequest.networkConfiguration.primaryInterface && !workloadRequest.networkConfiguration.primaryInterface?.doDhcp) {
                // Fall back to configured static IP if DHCP is not enabled
                provisionResponse.privateIp = workloadRequest.networkConfiguration.primaryInterface?.ipAddress
                provisionResponse.publicIp = workloadRequest.networkConfiguration.primaryInterface?.ipAddress
                provisionResponse.poolId = null
                provisionResponse.hostname = workload.server?.hostname
                log.info "Using configured static IP address: ${workloadRequest.networkConfiguration.primaryInterface?.ipAddress}"
            } else {
                // No IP available
                provisionResponse.privateIp = null
                provisionResponse.publicIp = null
                provisionResponse.poolId = null
                provisionResponse.hostname = workload.server?.hostname
                log.info "No IP address available"
            }

            // update the proper compute-server-type here since we cannot seed it in
            ComputeServerType serverType = null
            switch (platform) {
                case "linux": {
                    serverType = morpheusContext.async.cloud.findComputeServerTypeByCode("harvester-linux").blockingGet()
                    break
                }
                case "windows": {
                    serverType = morpheusContext.async.cloud.findComputeServerTypeByCode("harvester-windows").blockingGet()
                    break
                }

            }

            if (serverType) {
                log.info "Updating serverType to ${serverType.id} with platform ${serverType.platform}"
                workload.server.computeServerType = serverType
            }

            // update the project in which the VM is deployed into
            workload.server.setConfigProperty("project", ["name": namespace])
            workload.server.setConfigProperty("vmname", vmName)
            workload.server.externalIp = provisionResponse.privateIp
            // set the category to include the server group id
            workload.server.category = HarvesterConstants.CATEGORY_HARVESTER_VM_PREFIX + serverGroup.id
            // Update server powerState to "on"
            workload.server.powerState = ComputeServer.PowerState.on
            log.info "Updated server powerState to 'on' for VM: ${workload.server.name}"
            ComputeServer dbServer = morpheusContext.services.computeServer.get(workload.server.id)
            if (serverType) {
                dbServer.computeServerType = serverType
            }
            dbServer.setConfigProperty("project", ["name": namespace])
            dbServer.setConfigProperty("vmname", vmName)
            dbServer.externalIp = provisionResponse.privateIp
            dbServer.category = HarvesterConstants.CATEGORY_HARVESTER_VM_PREFIX + serverGroup.id
            dbServer.powerState = ComputeServer.PowerState.on
            // Guest console (SSH for Linux, RDP for Windows) as on other clouds; the hypervisor console is separate
            dbServer.guestConsoleType = CommonUtils.guestConsoleTypeFor(platform ?: dbServer.osType)
            // CPU layout as created, and whether CPU and memory can be added while it runs
            try {
                Map<String, Long> topology = resolveCpuTopology(workload, opts)
                dbServer.maxCores = topology.cores * topology.sockets
                dbServer.coresPerSocket = topology.cores
            } catch (Exception ignored) {
                // already validated before the VM was created
            }
            boolean hotAdd = isHotAdd(opts, workload)
            dbServer.hotResize = hotAdd
            dbServer.cpuHotResize = hotAdd
            morpheusContext.services.computeServer.bulkSave([dbServer])
            // Update network interfaces with actual VMI details (MAC addresses, IPs, etc.)
            if (bootResponse.data?.interfaceDetails) {
                log.info "Updating server network interfaces with VMI details"
                ServiceResponse interfaceUpdateResponse = updateServerNetworkInterfaces(
                    workload.server as ComputeServer,
                    bootResponse.data.interfaceDetails as List,
                    primaryIP as String
                )
                if (interfaceUpdateResponse.success) {
                    log.info "Successfully updated network interfaces: updated ${interfaceUpdateResponse.data?.updatedCount ?: 0} interfaces"
                } else {
                    log.warn "Failed to update network interfaces: ${interfaceUpdateResponse.msg}"
                }
            } else {
                log.warn "No interface details available from boot response"
            }
            log.info "installAgent=${opts.installAgent}, noAgent=${opts.noAgent}"
            provisionResponse.message = "Harvester VM Deployment initiated successfully"
            provisionResponse.installAgent = opts.installAgent
            provisionResponse.noAgent = opts.noAgent
            provisionResponse.skipNetworkWait = false
            if (isSkipNetworkWait(opts, workload)) {
                // ISO installs and VMs without a network never report an IP or run the agent: finish without waiting
                log.info "Skip Network Wait is set for ${workload.server?.name}: not waiting for network or agent"
                provisionResponse.skipNetworkWait = true
                provisionResponse.installAgent = false
                provisionResponse.noAgent = true
            }
            provisionResponse.createUsers = workloadRequest.usersConfiguration.createUsers

            // remove Vm label here which was added
            ProvisionHelper provHelper = new ProvisionHelper(workload.server, workloadRequest,serverGroup, opts)
            provHelper.removeVMLabel(harvesterApiClient, namespace, vmName, MORPHEUS_VM_PROVISIONING_LABEL)

            return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)

        } catch (Exception e) {
            log.error "VirtualMachineProvisioning: Error in runWorkload: ${e.message}", e
            ProvisionResponse errorResponse = new ProvisionResponse(success: false)
            errorResponse.setError(e.message)
            return new ServiceResponse<ProvisionResponse>(success: false, msg: e.message, error: e.message, data: errorResponse)
        }
    }

    /**
     * The "Skip Network Wait" provisioning option (config.skipNetworkWait), from the request or the workload's config.
     */
    /**
     * Creates the ISO PVC for a VM being provisioned from an ISO image and adds the CD-ROM (boot order 1) to the VM body.
     * The PVC is labelled with the VM name, so it is deleted with the VM, also when provisioning fails.
     */
    void attachIsoForProvisioning(def harvesterApiClient, Map vmBody, VirtualImage virtualImage) {
        Map config = virtualImage.getConfigMap() ?: [:]
        String namespace = vmBody.metadata.namespace
        String vmName = vmBody.metadata.name
        String imageId = config.imageId
        String storageClass = config.imageStorageClassName
        if (!imageId || !storageClass) {
            throw new RuntimeException("ISO image ${virtualImage.name} has no Harvester image reference. Refresh the cloud and try again.")
        }
        Long size = isoSize(harvesterApiClient, virtualImage, config)
        String diskName = CdromHelper.nextCdromName(vmBody)
        String claimName = CdromHelper.claimName(vmName, diskName)
        log.info "Attaching ISO ${imageId} to VM ${vmName} as CD-ROM ${diskName} (PVC ${claimName}, ${size} bytes)"
        ServiceResponse created = harvesterApiClient.post(CdromHelper.pvcPath(namespace),
                CdromHelper.isoPvcBody(namespace, claimName, vmName, imageId, storageClass, size))
        if (!created?.success && created?.errorCode?.toString() != '409') {
            throw new RuntimeException("Couldn't create the CD-ROM volume for ISO ${virtualImage.name}: " +
                    "${created?.data?.message ?: created?.msg ?: created?.errorCode}")
        }
        CdromHelper.addCdrom(vmBody, diskName, claimName, true)
    }

    /** ISO size: from the synced image, otherwise from Harvester (status.virtualSize or size) */
    static Long isoSize(def harvesterApiClient, def virtualImage, Map config) {
        Long size = (config?.virtualSize ?: virtualImage?.minDisk) as Long
        if (size) return size
        String imageId = config?.imageId
        if (imageId?.contains('/')) {
            String[] parts = imageId.split('/', 2)
            ServiceResponse resp = harvesterApiClient.get(
                    "/apis/harvesterhci.io/v1beta1/namespaces/${parts[0]}/virtualmachineimages/${parts[1]}".toString())
            if (resp?.success) {
                return (resp.data?.status?.virtualSize ?: resp.data?.status?.size) as Long
            }
        }
        return null
    }

    /**
     * The VM's CPU layout: total vCPUs (the plan's core count, or the CPU value on an older CPU-sized plan) and the
     * cores per socket chosen in the wizard or set on the plan. No cores per socket means one socket.
     * @throws RuntimeException when the core count isn't a whole number of sockets
     */
    static Map<String, Long> resolveCpuTopology(Workload workload, Map opts) {
        Long vcpus = CommonUtils.vcpusFor(workload?.instance?.plan, workload?.maxCpu, workload?.maxCores)
        Map planOptions = opts?.servicePlanOptions instanceof Map ? opts.servicePlanOptions as Map : [:]
        Long coresPerSocket = CpuTopology.firstPositive(planOptions.coresPerSocket, opts?.config?.coresPerSocket,
                workload?.coresPerSocket, workload?.server?.coresPerSocket, workload?.instance?.coresPerSocket,
                coresPerSocketFromSockets(workload?.instance?.plan, vcpus, workload?.maxCpu),
                workload?.instance?.plan?.coresPerSocket)
        Map<String, Long> topology = CpuTopology.split(vcpus, coresPerSocket)
        if (topology == null) {
            throw new RuntimeException(CpuTopology.splitError(vcpus, coresPerSocket))
        }
        return topology
    }

    /**
     * On a plan with both custom CPU and custom cores, the CPU value is the number of sockets: cores per socket is
     * the core count divided by it (when it divides evenly).
     */
    static Long coresPerSocketFromSockets(def plan, Long vcpus, def cpu) {
        Long sockets = CommonUtils.toPositiveLong(cpu)
        if (plan?.customCpu == true && plan?.customCores == true && vcpus && sockets && vcpus % sockets == 0) {
            return (vcpus / sockets) as Long
        }
        return null
    }

    /** The "CPU and Memory Hot Add" provisioning option (config.cpuMemoryHotAdd), from the request or the workload. */
    static boolean isHotAdd(Map opts, def workload) {
        def value = opts?.config?.cpuMemoryHotAdd
        if (value == null) {
            try {
                value = workload?.getConfigProperty('cpuMemoryHotAdd')
            } catch (Exception ignored) {
                value = null
            }
        }
        return CpuTopology.isOn(value)
    }

    /** Harvester's max-hotplug-ratio setting (how far a hot-add VM can grow), default 4. */
    static long hotplugRatio(HarvesterApiClient client) {
        try {
            ServiceResponse resp = client.get(CpuTopology.settingPath())
            return CpuTopology.parseRatio(resp?.success ? resp.data as Map : null)
        } catch (Exception e) {
            log.warn "Harvester: unable to read the max-hotplug-ratio setting, using ${CpuTopology.DEFAULT_HOTPLUG_RATIO}: ${e.message}"
            return CpuTopology.DEFAULT_HOTPLUG_RATIO
        }
    }

    static boolean isSkipNetworkWait(Map opts, def workload) {
        def value = opts?.config?.skipNetworkWait
        if (value == null) {
            try {
                value = workload?.getConfigProperty('skipNetworkWait')
            } catch (Exception ignored) {
                value = null
            }
        }
        return value == true || value?.toString()?.toLowerCase() in ['on', 'true', '1', 'yes']
    }

    /**
     * Finalizes the provisioning process by performing cleanup operations
     * This is called after successful VM provisioning to clean up temporary resources
     * @param workload the Workload object that has been provisioned
     * @return ServiceResponse indicating success or failure
     */
    ServiceResponse finalizeProvisioning(Workload workload) {
        log.info "VirtualMachineProvisioning: finalizeProvisioning called for workload ${workload.name}"

        try {
            ComputeServer server = workload.server
            // Get namespace and serverGroup from server config
            String serverGroupId = CommonUtils.getServerGroupId(server, morpheusContext)
            ComputeServerGroup serverGroup = morpheusContext.services.cluster.get(serverGroupId.toLong())
            Map serverConfig = server.getConfigMap()
            String namespace = serverConfig?.project?.name

            if (!namespace) {
                log.warn "No namespace found in server config for server ${server.name}"
                return ServiceResponse.success()
            }

            // Get virtualImage to check if cloud-init/sysprep was actually created
            Long virtualImageId = server.sourceImage?.id
            VirtualImage virtualImage = morpheusContext.services.virtualImage.get(virtualImageId)

            // Remove the cloud-init disk - best effort attempt, do not error out if this fails
            // Based on server.serverType attempt to delete cloud-init (linux) or sysprep (windows) disk
            log.info "Removing cloud-init/sysprep disk for VM ${server.name} in namespace ${namespace}"
            removeCloudInitDisk(server, serverGroup, namespace, virtualImage)

            return ServiceResponse.success()

        } catch (Exception e) {
            log.error "VirtualMachineProvisioning: Error in finalizeProvisioning: ${e.message}", e
            // Return success even on error since this is cleanup and should not fail provisioning
            return ServiceResponse.success()
        }
    }

    /**
     * Method called after a successful call to runWorkload to obtain the details of the ComputeServer.
     * Since the server object may not have updated IP addresses, this method retrieves current IP information
     * using the same logic as runWorkload but with a quick check.
     * @param server ComputeServer to get details for
     * @return ServiceResponse containing ProvisionResponse with current server details
     */
    static ServiceResponse<ProvisionResponse> getServerDetails(ComputeServer server) {
        log.info "VirtualMachineProvisioning: getServerDetails called for server ${server.name} with externalId: ${server.externalId}"

        try {
            ProvisionResponse provisionResponse = new ProvisionResponse(success: true)

            provisionResponse.success = true
            provisionResponse.message = "Harvester VM details retrieved successfully"
            provisionResponse.externalId = server.externalId
            provisionResponse.privateIp = server.externalIp
            provisionResponse.publicIp = server.externalIp

            log.info "VirtualMachineProvisioning: Returning server details - Private IP: ${provisionResponse.privateIp}, Public IP: ${provisionResponse.publicIp}, ExternalId: ${provisionResponse.externalId}"
            return new ServiceResponse<ProvisionResponse>(success: true, data: provisionResponse)

        } catch (Exception e) {
            log.error "VirtualMachineProvisioning: Exception getting current IP: ${e.message}", e
            ProvisionResponse errorResponse = new ProvisionResponse(success: false)
            errorResponse.setError("Failed to retrieve IP address: ${e.message}")
            return new ServiceResponse<ProvisionResponse>(success: false, msg: errorResponse.message, error: errorResponse.message, data: errorResponse)
        }
    }

    /**
     * Waits for the Guest Agent to connect by monitoring the VirtualMachineInstance (VMI) status
     * @param harvesterApiClient the Harvester API client
     * @param namespace the Harvester namespace
     * @param vmName the name of the virtual machine
     * @param maxRetries maximum number of retries (default 30 - 5 minutes)
     * @param waitInterval wait interval between retries in milliseconds (default 10000ms - 10 seconds)
     * @return ServiceResponse indicating success or failure of the Guest Agent connection check
     */
    /**
     * @return true when the VMI reports an IP on any interface that isn't loopback or link-local
     */
    static boolean hasRoutableIp(def vmiData) {
        def interfaces = vmiData?.status?.interfaces ?: []
        return interfaces.any { iface ->
            (iface?.ipAddresses ?: (iface?.ipAddress ? [iface.ipAddress] : [])).any { ip ->
                String s = ip?.toString()
                s && !(s ==~ /^127\..*/ || s == '::1' || s ==~ /^169\.254\..*/ || s.toLowerCase().startsWith('fe80:'))
            }
        }
    }

    static ServiceResponse waitForGuestAgentConnection(def harvesterApiClient, String namespace, String vmName,
                                                       int maxRetries = 18, int waitInterval = 5000) {
        log.info "waitForGuestAgentConnection: Starting Guest Agent connection check for VM: ${vmName} in namespace: ${namespace}"
        log.info "waitForGuestAgentConnection: Will check for ${maxRetries} retries with ${waitInterval}ms intervals (total timeout: ${(maxRetries * waitInterval) / 1000} seconds)"

        try {
            int retryCount = 0

            while (retryCount < maxRetries) {
                try {
                    // Build VMI API path
                    String vmiApiPath = "${KUBEVIRT_API_NAMESPACE_PATH}${namespace}${VIRTUAL_MACHINE_INSTANCES}${vmName}"

                    // Get VMI status
                    ServiceResponse vmiResponse = harvesterApiClient.get(vmiApiPath)

                    if (vmiResponse.success && vmiResponse.data) {
                        def vmiData = vmiResponse.data
                        log.info "waitForGuestAgentConnection: VMI found for ${vmName}, checking Guest Agent status..."
//                        log.info "waitForVMBoot: VMI data (pretty):\n${JsonOutput.prettyPrint(JsonOutput.toJson(vmiData))}"

                        // The agent is only needed to learn the VM's IPs: stop waiting once an IP is reported
                        if (hasRoutableIp(vmiData)) {
                            log.info "waitForGuestAgentConnection: VM ${vmName} already reports an IP address, not waiting for the Guest Agent"
                            return
                        }
                        // Check if VMI has status and conditions
                        if (vmiData.status && vmiData.status.conditions) {
                            // Look for AgentConnected condition
                            def agentConnectedCondition = vmiData.status.conditions.find { condition ->
                                condition.type == "AgentConnected"
                            }
                            if (agentConnectedCondition) {
                                if (agentConnectedCondition.status == "True") {
                                    log.info "waitForGuestAgentConnection: Guest Agent successfully connected for VM: ${vmName}"
                                    return
                                } else {
                                    log.info "waitForGuestAgentConnection: AgentConnected condition found but status is '${agentConnectedCondition.status}' for VM: ${vmName} (retry ${retryCount + 1}/${maxRetries})"
                                    if (agentConnectedCondition.reason) {
                                        log.info "waitForGuestAgentConnection: AgentConnected reason: ${agentConnectedCondition.reason}"
                                    }
                                }
                            } else {
                                log.info "waitForGuestAgentConnection: No AgentConnected condition found for VM: ${vmName} (retry ${retryCount + 1}/${maxRetries})"
                                // Log all available conditions for debugging
                                log.info "waitForGuestAgentConnection: Available conditions for ${vmName}:"
                                vmiData.status.conditions.each { condition ->
                                    log.info "  - type: ${condition.type}, status: ${condition.status}"
                                }
                            }
                        } else {
                            log.info "waitForGuestAgentConnection: VMI ${vmName} found but no status conditions available yet (retry ${retryCount + 1}/${maxRetries})"
                        }
                    } else {
                        // VMI doesn't exist yet or failed to retrieve
                        log.info "waitForGuestAgentConnection: VMI ${vmName} not found or failed to retrieve (retry ${retryCount + 1}/${maxRetries})"
                        if (!vmiResponse.success) {
                            log.info "waitForGuestAgentConnection: VMI API call failed: ${vmiResponse.msg}"
                        }
                    }

                } catch (Exception e) {
                    log.warn "waitForGuestAgentConnection: Exception during VMI check for ${vmName}: ${e.message}"
                }

                // Wait before next retry
                retryCount++
                if (retryCount < maxRetries) {
                    log.info "waitForGuestAgentConnection: Waiting ${waitInterval}ms before retry ${retryCount + 1}..."
                    sleep(waitInterval)
                }
            }

            // Timeout reached - Guest Agent did not connect
            String timeoutMsg = "Guest Agent connection check timed out after ${maxRetries * waitInterval / 1000} seconds for VM: ${vmName}"
            log.warn timeoutMsg

        } catch (Exception e) {
            String errorMsg = "Exception during Guest Agent connection check for ${vmName}: ${e.message}"
            log.error errorMsg
        }
    }

    /**
     * Waits for the VM to boot up by monitoring the VirtualMachineInstance (VMI) status
     * @param harvesterApiClient the Harvester API client
     * @param namespace the Harvester namespace
     * @param vmName the name of the virtual machine
     * @return ServiceResponse indicating success or failure of the boot check
     */
    ServiceResponse waitForVMBoot(def harvesterApiClient, String namespace, String vmName, Workload workload, WorkloadRequest workloadRequest,
                                  boolean skipNetworkWait = false) {
        try {
            log.info "waitForVMBoot: Starting boot check for VM: ${vmName} in namespace: ${namespace}"
            ServiceResponse readinessCheckResponse = performBasicReadinessCheck(harvesterApiClient, namespace, vmName)

            if (!readinessCheckResponse.success) {
                log.error "waitForVMBoot: Basic readiness check failed for VM: ${vmName}"
                return ServiceResponse.error(readinessCheckResponse.msg ?: "Basic readiness check failed for VM: ${vmName}", null, [
                    vmName: vmName,
                    msg: readinessCheckResponse.msg
                ])
            }

            if (readinessCheckResponse.success && skipNetworkWait) {
                // Skip Network Wait: the VM is Running and Ready, so don't wait for the guest agent or ping.
                // Take whatever addresses Harvester reports right now (often none for ISO installs).
                log.info "waitForVMBoot: Skip Network Wait set for ${vmName} - not waiting for Guest Agent or network"
                ServiceResponse quickIps = null
                try {
                    quickIps = getAllAssignedIPs(harvesterApiClient, namespace, vmName)
                } catch (Exception e) {
                    log.warn "waitForVMBoot: Could not read IPs for ${vmName}: ${e.message}"
                }
                def quickIp = (quickIps?.success ? quickIps.data?.assignedIPs : [])?.find { ip ->
                    String a = ip?.toString()
                    a && !(a ==~ /^127\..*/ || a == '::1' || a ==~ /^169\.254\..*/ || a.toLowerCase().startsWith('fe80:'))
                }
                return new ServiceResponse(success: true,
                        msg: "VM ${vmName} is running (Skip Network Wait)",
                        data: [primaryIP: quickIp,
                               nodeName: quickIps?.data?.nodeName,
                               interfaceDetails: quickIps?.data?.interfaceDetails])
            }

            if (readinessCheckResponse.success ){

                waitForGuestAgentConnection(harvesterApiClient, namespace, vmName)
                log.info "Get all the assigned IPs for the VM ${vmName}"
                ServiceResponse ipResponse = getAllAssignedIPs(harvesterApiClient, namespace, vmName)
                if (!ipResponse.success || !ipResponse.data.assignedIPs) {
                    log.warn "waitForVMBoot: Get All Ip address failed or no IPs assigned for VM: ${vmName} - proceeding with workflow"
                    return new ServiceResponse(success: true,
                            msg: "VM ${vmName} boot detected via Guest Agent, but no IPs assigned",
                            data: [primaryIP: null,
                            nodeName: ipResponse.data?.nodeName,
                            interfaceDetails: ipResponse.data?.interfaceDetails])
                }
                if (ipResponse.success) {
                    def assignedIPs = ipResponse.data.assignedIPs
                    log.info "waitForVMBoot: VM ${vmName} has assigned IPs: ${assignedIPs}"
                    if (workloadRequest.process) {
                        morpheusContext.services.process.startProcessStep(workloadRequest.process, new ProcessEvent(stepType: Constants.PROCESS_STEP_WAIT_FOR_VM_NETWORK), "Wait for Network")
                    }
                    // Ping all assigned IPs in round-robin fashion with 5-minute timeout
                    if (assignedIPs && assignedIPs.size() > 0) {
                        String primaryIP = pingTestAssignedIPs(assignedIPs, vmName)
                        if (primaryIP) {
                            log.info "waitForVMBoot: Primary IP determined via ping test: ${primaryIP}"
                            return new ServiceResponse(success: true,
                                    msg: "VM ${vmName} boot detected via Guest Agent",
                                    data: [primaryIP: primaryIP,
                                    nodeName: ipResponse.data.nodeName,
                                    interfaceDetails: ipResponse.data.interfaceDetails])
                        } else {
                            log.warn "waitForVMBoot: No IP responded to ping test within 5 minutes or all IPs filtered out - attempting to get primary interface IP"
                            // If ping test failed, try to get IP from primary interface
                            String fallbackIP = null
                            if (ipResponse.data?.interfaceDetails && ipResponse.data.interfaceDetails.size() > 0) {
                                // Find primary interface by matching with server.interfaces
                                def serverInterfaces = workload?.server?.interfaces
                                def primaryInterface = null

                                // First try to find interface marked as primary
                                if (serverInterfaces) {
                                    def primaryServerIface = serverInterfaces.find { it.primaryInterface == true }
                                    if (primaryServerIface) {
                                        // Find matching interface in interfaceDetails by name
                                        primaryInterface = ipResponse.data.interfaceDetails.find { it.name == primaryServerIface.name }
                                        log.info "waitForVMBoot: Found primary interface by primaryInterface flag: ${primaryInterface?.name}"
                                    }
                                }

                                // If no primary interface found, use the first interface (sorted alphabetically by name)
                                if (!primaryInterface && ipResponse.data.interfaceDetails.size() > 0) {
                                    primaryInterface = ipResponse.data.interfaceDetails.sort { it.name }[0]
                                    log.info "waitForVMBoot: Using first interface (alphabetically): ${primaryInterface?.name}"
                                }

                                if (primaryInterface?.ipAddresses && primaryInterface.ipAddresses.size() > 0) {
                                    // Filter out link-local and loopback addresses
                                    def validIPs = primaryInterface.ipAddresses.findAll { ip ->
                                        String ipStr = ip?.toString()
                                        if (!ipStr) return false
                                        // Filter out IPv4 loopback, IPv6 loopback, IPv4 link-local, IPv6 link-local
                                        return !(ipStr ==~ /^127\.\d{1,3}\.\d{1,3}\.\d{1,3}$/ ||
                                                 ipStr == "::1" ||
                                                 ipStr ==~ /^169\.254\.\d{1,3}\.\d{1,3}$/ ||
                                                 ipStr.toLowerCase().startsWith("fe80:"))
                                    }
                                    if (validIPs && validIPs.size() > 0) {
                                        fallbackIP = validIPs[0]
                                        log.info "waitForVMBoot: Using IP from interface (${primaryInterface.name}): ${fallbackIP}"
                                    }
                                }
                            }
                            return new ServiceResponse(success: true,
                                    msg: "VM ${vmName} boot detected via Guest Agent, but ping test failed for all IPs",
                                    data: [primaryIP: fallbackIP,
                                    nodeName: ipResponse.data.nodeName,
                                    interfaceDetails: ipResponse.data.interfaceDetails])
                        }
                    }

                }
            }
            }
        catch (Exception e) {
            String errorMsg = "Exception during wait for vm boot ${vmName}: ${e.message}"
            log.error errorMsg, e
            return ServiceResponse.error(errorMsg, null, [
                vmName: vmName,
                exception: e.message,
                exceptionType: e.class.simpleName
            ])
        }
    }

    /**
     * Performs basic readiness checks for VM boot when Guest Agent is not available
     * @param harvesterApiClient the Harvester API client
     * @param namespace the Harvester namespace
     * @param vmName the name of the virtual machine
     * @return ServiceResponse indicating success or failure of the basic readiness check
     */
    private ServiceResponse performBasicReadinessCheck(def harvesterApiClient, String namespace, String vmName) {
        log.info "performBasicReadinessCheck: Starting basic readiness check for VM: ${vmName}"

        try {
            int maxRetries = 60 // 60 * 5 seconds = 5 minutes
            int retryCount = 0
            int retryIntervalSeconds = 5

            while (retryCount < maxRetries) {
                try {
                    // Build VMI API path
                    String vmiApiPath = "${KUBEVIRT_API_NAMESPACE_PATH}${namespace}${VIRTUAL_MACHINE_INSTANCES}${vmName}"

                    // Get VMI status
                    ServiceResponse vmiResponse = harvesterApiClient.get(vmiApiPath)

                    if (vmiResponse.success && vmiResponse.data) {
                        def vmiData = vmiResponse.data
                        log.info "performBasicReadinessCheck: VMI found for ${vmName}, checking status..."

                        // Check if VMI has status
                        if (vmiData.status) {
                            String phase = vmiData.status.phase
                            log.info "performBasicReadinessCheck: VMI ${vmName} phase: ${phase}"

                            // Check if VMI is in Running state
                            if (phase == "Running") {
                                log.info "performBasicReadinessCheck: VMI ${vmName} is in Running state, checking readiness..."

                                // Check status conditions for readiness
                                if (vmiData.status.conditions) {
                                    def readyCondition = vmiData.status.conditions.find { condition ->
                                        condition.type == "Ready" && condition.status == "True"
                                    }

                                    if (readyCondition) {
                                        // Ready: the guest agent / IP / ping checks that follow wait for the OS itself,
                                        // so no fixed extra wait here
                                        {
                                            log.info "performBasicReadinessCheck: VM ${vmName} is Ready after ${retryCount * retryIntervalSeconds}s"
                                            return ServiceResponse.success([
                                                vmName: vmName,
                                                phase: phase,
                                                bootMethod: "BasicReadiness",
                                                guestAgentConnected: false,
                                                guestAgentReady: false,
                                                readyCondition: readyCondition,
                                                bootTime: new Date()
                                            ])
                                        }
                                    } else {
                                        log.info "performBasicReadinessCheck: VMI ${vmName} is Running but not Ready yet, checking conditions..."
                                        vmiData.status.conditions.each { condition ->
                                            log.info "performBasicReadinessCheck: Condition - type: ${condition.type}, status: ${condition.status}, reason: ${condition.reason}"
                                        }
                                    }
                                } else {
                                    log.info "performBasicReadinessCheck: VMI ${vmName} is Running but no status conditions found yet"
                                }
                            } else if (phase == "Failed") {
                                log.error "performBasicReadinessCheck: VMI ${vmName} failed to boot. Phase: ${phase}"
                                return ServiceResponse.error("VM boot failed - VMI is in Failed state", null, [
                                    vmName: vmName,
                                    phase: phase,
                                    status: vmiData.status
                                ])
                            } else {
                                log.info "performBasicReadinessCheck: VMI ${vmName} is in ${phase} state, waiting for Running..."
                            }
                        } else {
                            log.info "performBasicReadinessCheck: VMI ${vmName} found but no status available yet"
                        }
                    } else {
                        // VMI doesn't exist yet, wait for it to be created
                        log.info "performBasicReadinessCheck: VMI ${vmName} not found yet, waiting for creation... (attempt ${retryCount + 1}/${maxRetries})"
                    }

                } catch (Exception e) {
                    log.warn "performBasicReadinessCheck: Exception during VMI check for ${vmName}: ${e.message}"
                }

                // Wait before next retry
                retryCount++
                if (retryCount < maxRetries) {
                    log.info "performBasicReadinessCheck: Waiting ${retryIntervalSeconds} seconds before retry ${retryCount + 1}..."
                    sleep(retryIntervalSeconds * 1000)
                }
            }

            // Timeout reached
            String timeoutMsg = "Basic readiness check timed out after ${maxRetries * retryIntervalSeconds} seconds for VM: ${vmName}"
            log.error timeoutMsg
            return ServiceResponse.error(timeoutMsg, null, [
                vmName: vmName,
                maxRetries: maxRetries,
                timeoutSeconds: maxRetries * retryIntervalSeconds,
                msg: timeoutMsg
            ])

        } catch (Exception e) {
            String errorMsg = "Exception during basic readiness check for ${vmName}: ${e.message}"
            log.error errorMsg, e
            return ServiceResponse.error(errorMsg, null, [vmName: vmName, exception: e.message])
        }
    }

    static private void removeCloudInitDisk(ComputeServer server, ComputeServerGroup serverGroup,
                                            String namespace, VirtualImage virtualImage) {
        log.info("Attempting to remove cloud-init/sysprep disk for VM ${server.name}")
        try {

            HarvesterApiClient harvesterApiClient = new HarvesterApiClient(serverGroup)
            String vmName = server.name
            // get the disk and volume indices from the VM here
            String vmTypeCode = server.computeServerType?.code
            String diskType = vmTypeCode == 'harvester-windows' ? 'sysprep' : 'cloud-init'

            // Check if cloud-init/sysprep was actually created based on Virtual Image flags
            boolean shouldRemove = false
            if (vmTypeCode == 'harvester-windows') {
                shouldRemove = virtualImage?.getSysprep() == true
                if (!shouldRemove) {
                    log.info("Virtual image does not have sysprep enabled - skipping sysprep disk removal for VM ${server.name}")
                }
            } else if (vmTypeCode == 'harvester-linux') {
                shouldRemove = virtualImage?.getCloudInit() == true
                if (!shouldRemove) {
                    log.info("Virtual image does not have cloud-init enabled - skipping cloud-init disk removal for VM ${server.name}")
                }
            }

            if (!shouldRemove) {
                log.info("Skipping ${diskType} disk removal for VM ${server.name} as it was not created during provisioning")
                return
            }

            log.info("Attempting to remove ${diskType} disk for VM ${server.name}")

            Map indices = ProvisionHelper.getDiskAndVolumeIndices(harvesterApiClient, namespace, vmName, vmTypeCode)
            if (indices) {
                // if indices are found, then do the patch call here
                List patch = ProvisionHelper.buildDiskRemovalPatch(
                        indices.diskIndex.toString(), indices.volumeIndex.toString())
                String apiPath = getVMApiResourcePath(namespace, vmName)
                // convert to json since content-type is application/json-patch+json
                String patchBodyJson = JsonOutput.toJson(patch)
                log.info("Patching VM ${vmName} to remove ${diskType} disk. Path: ${apiPath}, Body : ${patchBodyJson}")
                ServiceResponse patchResponse = harvesterApiClient.patch(
                        apiPath, patchBodyJson, 'application/json-patch+json')
                if (patchResponse.success) {
                    log.info("Successfully patched VM ${vmName} to remove ${diskType} disk.")
                } else {
                    log.error("Failed to patch VM ${vmName} to remove ${diskType} disk: ${patchResponse.msg}")
                }
            } else {
                log.warn("Could not find ${diskType} disk and volume indices for VM ${server.name}. Skipping removal.")
            }

            switch (vmTypeCode) {
                case "harvester-linux":
                    // Delete cloud-init secret
                    try {
                        String secretName = "secret-${server.name}"
                        log.info("Attempting to delete cloud-init secret ${secretName} for VM ${server.name}")
                        ProvisionHelper provisionHelper = new ProvisionHelper(server, null, serverGroup, [:])
                        ServiceResponse deleteResponse = provisionHelper.deleteCloudInitSecret(secretName, namespace)
                        if (deleteResponse.success) {
                            log.info("Successfully deleted cloud-init secret ${secretName} for VM ${server.name}")
                        } else {
                            log.warn("Failed to delete cloud-init secret ${secretName}: ${deleteResponse.msg}")
                        }
                    } catch (Exception ex) {
                        log.error("Error deleting cloud-init secret for VM ${server.name}: ${ex.message}", ex)
                    }
                    break
                case "harvester-windows":
                    // delete config-map
                    try {
                        String configMapName = "${server.name}-sysprep"
                        log.info("Attempting to delete sysprep config map ${configMapName} for VM ${server.name}")
                        def configMapApiHelper = new ConfigMapApiHelper(harvesterApiClient)
                        ServiceResponse deleteResponse = configMapApiHelper.deleteConfigMap(namespace, configMapName)
                        if (deleteResponse?.success) {
                            log.info("Successfully deleted sysprep config map ${configMapName} for VM ${server.name}")
                        } else {
                            log.warn("Failed to delete sysprep config map ${configMapName}: ${deleteResponse?.msg}")
                        }
                    } catch (Exception ex) {
                        log.error("Error deleting sysprep config map for VM ${server.name}: ${ex.message}", ex)
                    }
                    break
            }

        } catch (Exception e) {
            log.error("Error removing cloud-init/sysprep disk for VM ${server.name}: ${e.message}", e)
        }
    }

    private static ServiceResponse configureSysprepOnWindowsVM(HarvesterApiClient harvesterApiClient, String namespace, String vmName, Workload workload,
                                        WorkloadRequest workloadRequest, ComputeServerGroup serverGroup, Map opts, VirtualImage virtualImage) {
        try {
            log.info "Configuring sysprep on Windows VM: ${vmName}"

            // Check if the Virtual Image has sysprep enabled
            if (!virtualImage?.getSysprep()) {
                log.info "Virtual image does not have sysprep enabled - skipping sysprep configuration for VM ${vmName}"
                return ServiceResponse.success()  // Not an error, just no customization needed
            }
            log.info "Virtual image has sysprep enabled - configuring sysprep for VM ${vmName}"

            //1) Get the VM details to extract network interface MAC addresses
            Map ifaceMACAddressMap = [:]
            String vmApiPath = getVMApiResourcePath(namespace, vmName)
            ServiceResponse getVMResponse = harvesterApiClient.get(vmApiPath)
            if (getVMResponse?.success && getVMResponse?.data) {
                def interfaces =  getVMResponse.data.spec?.template?.spec?.domain?.devices?.interfaces
                interfaces?.each { iface ->
                    if (iface?.macAddress && iface?.name) {
                        def sanitizedMacAddress = iface.macAddress.replace(':', '-')
                        ifaceMACAddressMap[iface.name] = sanitizedMacAddress
                    }
                }
            }

            opts.ifaceMACAddressMap = ifaceMACAddressMap
            //2) Update the sysprep XML to use MAC address as network interface identifier. Create the corresponding config map
            ProvisionHelper provHelper = new ProvisionHelper(workload.server, workloadRequest, serverGroup, opts)
            Map sysprepResponse = provHelper.buildSysprepOptions()

            if (!sysprepResponse?.success || !sysprepResponse?.configMapName) {
                log.error "Failed to create sysprep config map for VM ${vmName}"
                return ServiceResponse.error("Failed to create sysprep config map")
            }
            //3) If config map created successfully, update the VM to add the sysprep disk and volume
            String configMapName = sysprepResponse.configMapName
            ServiceResponse addSysprep = provHelper.addSysprepVolumeToVM(harvesterApiClient, namespace, vmName, configMapName)
            if (!addSysprep?.success) {
                log.error "Failed to add sysprep volume to VM ${vmName}: ${addSysprep?.msg}"
                return ServiceResponse.error("Failed to add sysprep volume to VM: ${addSysprep?.msg}")
            }
            log.info "Successfully added sysprep disk and volume on Windows VM: ${vmName}. Powering on VM"
            return ServiceResponse.success()

        } catch (Exception e) {
            log.error("Error in configuring sysprep: ${e.message}")
            return ServiceResponse.error("Error in configuring sysprep: ${e.message}")
        }
    }
    /**
     * Returns the VNC console URL for accessing the KubeVirt virtual machine console
     * @param server the ComputeServer to get console URL for
     * @param opts additional options
     * @return ServiceResponse containing the VNC WebSocket URL
     */
    /**
     * Kubernetes accepts a bearer token on a WebSocket as the subprotocol
     * "base64url.bearer.authorization.k8s.io.<base64url token, no padding>".
     * @return that subprotocol, or null when there is no token
     */
    static String bearerSubprotocol(String token) {
        if (!token) {
            return null
        }
        return 'base64url.bearer.authorization.k8s.io.' +
                Base64.getUrlEncoder().withoutPadding().encodeToString(token.getBytes('UTF-8'))
    }

    ServiceResponse<Map> getNoVNCConsoleUrl(ComputeServer server) {
        log.info("VirtualMachineProvisioning: getNoVNCConsoleUrl called for server ${server?.name}")
        try {
            // Get the namespace and VM name from the server
            def namespace = server.getConfigProperty("project")?.name
            def vmName = server.name
            String serverGroupId = CommonUtils.getServerGroupId(server, morpheusContext)
            ComputeServerGroup serverGroup = morpheusContext.services.cluster.get(serverGroupId.toLong())
            // The console connects through the same Harvester API endpoint (and credentials) as every other call,
            // so keep the base path (e.g. /k8s/clusters/local) in the WebSocket URL
            String serviceUrl = AuthManager.normalizeApiUrl(serverGroup?.serviceUrl)
            def apiUrl = new URI(serviceUrl).toURL()
            def host = apiUrl.host
            def portNum = apiUrl.port > 0 ? apiUrl.port : (apiUrl.defaultPort > 0 ? apiUrl.defaultPort : 443)
            def portStr = ":${portNum}"
            String basePath = apiUrl.path ?: ''
            String vncUrl = "wss://${host}${portStr}${basePath}/apis/subresources.kubevirt.io/v1/namespaces/${namespace}/virtualmachineinstances/${vmName}/vnc"
            String serviceToken = new HarvesterApiClient(serverGroup).getToken()
            // A WebSocket opened by a browser can't carry an Authorization header, so also pass the token the
            // Kubernetes way, as a subprotocol (only when there is a token)
            List<String> protocols = ['plain.kubevirt.io']
            String bearerProtocol = bearerSubprotocol(serviceToken)
            if (bearerProtocol) {
                protocols << bearerProtocol
            } else {
                log.warn("VirtualMachineProvisioning: no Harvester token for the console of ${server?.name}")
            }

            // Prepare the response map with VNC info
            def k8sVncInfo = [
                    success: true,
                    host: host,
                    port: portNum,
                    url: vncUrl,
                    protocols: protocols,
                    headers: [
                            'Authorization': "Bearer ${serviceToken}"
                    ]
            ]

            log.info("VirtualMachineProvisioning: Generated VNC URL for server ${server?.name}: ${vncUrl}")
            return ServiceResponse.success(k8sVncInfo)

        } catch (Exception e) {
            log.error("VirtualMachineProvisioning: Error generating VNC console URL: ${e.message}", e)
            def errorInfo = [
                success: false,
                error: "Error generating VNC console URL: ${e.message}"
            ]
            return ServiceResponse.error("Error generating VNC console URL: ${e.message}", null, errorInfo)
        }
    }
}

