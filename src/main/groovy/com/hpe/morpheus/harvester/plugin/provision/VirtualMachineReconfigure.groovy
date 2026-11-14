package com.hpe.morpheus.harvester.plugin.provision

import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.hpe.morpheus.harvester.plugin.apihelper.StorageClassApiHelper
import com.hpe.morpheus.harvester.plugin.utils.CommonUtils
import com.hpe.morpheus.harvester.plugin.utils.CpuTopology
import com.hpe.morpheus.harvester.plugin.utils.DBUtils
import com.hpe.morpheus.harvester.plugin.utils.DataUnitConverterUtil
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.hpe.morpheus.harvester.plugin.volumes.HarvesterVolumes
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.Instance
import com.morpheusdata.model.Network
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.Workload
import com.morpheusdata.request.ResizeV2Request
import com.morpheusdata.request.UpdateModel
import com.morpheusdata.response.PrepareResizeV2WorkloadResponse
import com.morpheusdata.response.ResizeV2WorkloadResponse
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j

@Slf4j
class VirtualMachineReconfigure {

    private static final String VM_STATUS_RUNNING = 'Running'
    private static final String VM_STATUS_STOPPED = 'Stopped'
    private static final String VM_STATUS_STARTING = 'Starting'
    private static final String VM_STATUS_STOPPING = 'Stopping'
    private static final String VM_STATUS_PAUSED = 'Paused'
    private static final String VM_STATUS_PAUSING = 'Pausing'
    private static final String VM_STATUS_UNPAUSING = 'Unpausing'
    private static final String JSON_PATCH_CONTENT_TYPE = 'application/json-patch+json'
    private static final Map PATCH_DRY_RUN_OPTS = ['dryRun': 'All']

    MorpheusContext morpheusContext
    ComputeServer server
    ComputeServerGroup serverGroup
    String vmName
    String namespace
    HarvesterApiClient harvesterApiClient

    // constructor
    VirtualMachineReconfigure(MorpheusContext morpheusContext, ComputeServer server) {
        this.morpheusContext = morpheusContext
        this.server = server
        initializeFromServer()
    }

    /**
     * Initialize common properties from the server object
     */
    private void initializeFromServer() {
        if (!server) {
            throw new IllegalArgumentException("Server cannot be null")
        }

        String serverGroupId = CommonUtils.getServerGroupId(server, morpheusContext)
        this.serverGroup = morpheusContext.services.cluster.get(serverGroupId.toLong())
        this.vmName = server.getConfigProperty("vmname") ?: server.name
        this.namespace = server.getConfigProperty("project")?.name

        if (!vmName || !namespace) {
            throw new IllegalArgumentException("VM name or namespace not found on server object")
        }

        this.harvesterApiClient = new HarvesterApiClient(serverGroup)
    }

    /**
     * Prepares the resize operation by validating the request and performing a dry-run
     * @param instance the Instance being resized
     * @param workload the Workload being resized
     * @param resizeRequest the resize request containing new CPU/memory values
     * @param opts additional options
     * @return ServiceResponse containing PrepareResizeV2WorkloadResponse
     */
    ServiceResponse<PrepareResizeV2WorkloadResponse> prepareResizeWorkload(
            Instance instance, Workload workload, ResizeV2Request resizeRequest, Map opts) {
        log.info("prepareResizeWorkload called for workload ${workload?.name}")

        try {
            // AUTO-DATASTORE RESOLUTION for volumes being added during reconfigure
            if (resizeRequest.volumesAdd) {
                log.info "Found ${resizeRequest.volumesAdd.size()} volumes to add - checking for auto-datastore"

                // Get volumes with null datastore (auto-datastore selection)
                def volumesWithNullDatastore = resizeRequest.volumesAdd.collect { updateModel ->
                    morpheusContext.services.storage.volume.get(updateModel.existingModel.id)
                }.findAll { it?.datastore == null }

                if (volumesWithNullDatastore) {
                    log.info "Found ${volumesWithNullDatastore.size()} volumes with null datastore " +
                            "(Auto selection) - resolving to cluster default storage class"

                    // Live-fetch storage classes from the K8s API using serverGroup from constructor
                    def storageClassHelper = new StorageClassApiHelper(serverGroup)
                    ServiceResponse defaultResponse = storageClassHelper.getDefaultStorageClass()

                    if (!defaultResponse.success) {
                        return ServiceResponse.error("Auto-datastore selected but ${defaultResponse.msg}")
                    }

                    String defaultStorageClassName = defaultResponse.data.storageClass.name
                    String resolvedFrom = defaultResponse.data.source
                    log.info "Resolved default storage class: '${defaultStorageClassName}' (source: ${resolvedFrom})"

                    def datastoreList = morpheusContext.services.cloud.datastore.list(
                            new DataQuery().withFilters(
                                    DBUtils.datastoreFilter(serverGroup),
                                    new DataFilter('name', defaultStorageClassName),
                            )
                    ).toList()

                    if (!datastoreList) {
                        return ServiceResponse.error(
                                "Default storage class '${defaultStorageClassName}' is not synced " +
                                        "as a Datastore. Run a storage class sync first.")
                    }

                    def resolvedDatastore = datastoreList[0]
                    log.info "Matched to Datastore id=${resolvedDatastore.id}, name=${resolvedDatastore.name}, type=${resolvedDatastore.type}"

                    // Set datastore on all volumes and bulk save
                    volumesWithNullDatastore.each { vol ->
                        vol.datastore = resolvedDatastore
                        log.info "Volume ${vol.id} (${vol.name}) - assigned Datastore: id=${resolvedDatastore.id}, " +
                                "name=${resolvedDatastore.name}, type=${resolvedDatastore.type}"
                    }
                    morpheusContext.services.storage.volume.bulkSave(volumesWithNullDatastore)
                    log.info "Set datastore '${resolvedDatastore.name}' on ${volumesWithNullDatastore.size()} volumes (Auto-resolved)"
                }
            }

            PrepareResizeV2WorkloadResponse response = new PrepareResizeV2WorkloadResponse()
            return ServiceResponse.success(response)
        } catch (Exception e) {
            log.error("Error in prepareResizeWorkload: ${e.message}", e)
            return ServiceResponse.error("Error preparing resize: ${e.message}")
        }
    }

    /**
     * Executes the resize operation by applying CPU/memory changes to the VM
     * @param instance the Instance being resized
     * @param workload the Workload being resized
     * @param resizeRequest the resize request containing new CPU/memory values
     * @param opts additional options
     * @return ServiceResponse containing ResizeV2WorkloadResponse
     */
    ServiceResponse<ResizeV2WorkloadResponse> resizeWorkload(
            Instance instance, Workload workload, ResizeV2Request resizeRequest, Map opts) {
        log.info("resizeWorkload called for workload ${workload?.name}")

        try {
            // UID pre-check: verify the VM in the cluster is actually the one we own.
            // If the name matches but the UID differs, the original VM is already gone
            // (a new VM was recreated with the same name). Abort the reconfigure entirely.
            String serverExternalId = server.externalId
            if (serverExternalId) {
                String vmApiPath = getVMApiResourcePath(namespace, vmName)
                ServiceResponse vmCheckResponse = harvesterApiClient.get(vmApiPath)
                if (vmCheckResponse.success) {
                    String clusterVmUid = vmCheckResponse.data?.metadata?.uid
                    if (clusterVmUid && clusterVmUid != serverExternalId) {
                        log.warn("resizeWorkload: VM ${vmName} exists in cluster but UID mismatch: " +
                                "expected ${serverExternalId}, found ${clusterVmUid}. " +
                                "The original VM is already gone. Skipping reconfigure.")
                        return ServiceResponse.error("VM ${vmName} UID mismatch — original VM no longer exists. Reconfigure skipped.")
                    }
                    log.info("resizeWorkload: VM ${vmName} UID confirmed (${clusterVmUid}), proceeding with reconfigure")
                } else if (vmCheckResponse.data?.code == 404) {
                    log.warn("resizeWorkload: VM ${vmName} not found in cluster (404). Skipping reconfigure.")
                    return ServiceResponse.error("VM ${vmName} not found in cluster. Reconfigure skipped.")
                }
            }

            // first do the cpu and memory changes
            ServiceResponse cpuMemoryResult = applyCpuMemoryChanges(resizeRequest)

            if (!cpuMemoryResult.success) {
                return cpuMemoryResult
            }

            // next do the volume changes
            ServiceResponse volumeResult = applyVolumeChanges(resizeRequest)

            if (!volumeResult.success) {
                return volumeResult
            }

            // check if the volumes are resized, then we need to update the maxStorage on the compute server
            if (volumeResult.data?.volumeChangesHandled) {
                this.server.maxStorage = resizeRequest.maxStorage
                morpheusContext.services.computeServer.bulkSave([this.server])
            }

            // next do the network changes
            ServiceResponse networkResult = applyNetworkChanges(resizeRequest)

            if (!networkResult.success) {
                return networkResult
            }

            ResizeV2WorkloadResponse response = new ResizeV2WorkloadResponse()
            log.info "Successfully completed resizeWorkload for workload ${workload?.name}"
            return ServiceResponse.success(response)

        } catch (Exception e) {
            log.error("Error in resizeWorkload: ${e.message}", e)
            return ServiceResponse.error("Error executing resize: ${e.message}")
        }
    }

    ServiceResponse stopVM() {
        log.info("Stopping VM for server: ${server.name}")
        try {
            // first check if the resource is already stopped
            String vmStatusPath = getVMApiResourcePath(namespace, vmName)
            ServiceResponse vmStatusResponse = harvesterApiClient.get(vmStatusPath)

            if(vmStatusResponse.success) {
                def vmDetails = vmStatusResponse.data

                // UID pre-check: ensure we are operating on the correct VM
                String serverExternalId = server.externalId
                if (serverExternalId) {
                    String clusterVmUid = vmDetails?.metadata?.uid
                    if (clusterVmUid && clusterVmUid != serverExternalId) {
                        String mismatchMsg = "VM ${vmName} UID mismatch: expected ${serverExternalId}, " +
                                "found ${clusterVmUid}. The original VM has already been deleted " +
                                "(a new VM with the same name exists in the cluster). " +
                                "Stop operation aborted to avoid affecting the new VM."
                        log.warn(mismatchMsg)
                        // Throw so Morpheus marks the task as failed and surfaces the error in the UI
                        throw new RuntimeException(mismatchMsg)
                    }
                    log.info("VM ${vmName} UID confirmed (${clusterVmUid}), proceeding with stop")
                }

                if(vmDetails.status?.printableStatus == VM_STATUS_STOPPED) {
                    log.info("VM ${vmName} is already stopped.")
                    return ServiceResponse.success("VM is already in the requested state: Stopped.")
                }
                if(vmDetails.status?.printableStatus == VM_STATUS_STOPPING) {
                    log.info("VM ${vmName} is already stopping.")
                    return ServiceResponse.success("VM is already in the requested state: Stopping.")
                }
            } else {
                log.warn("Could not retrieve VM status for ${vmName}. Proceeding with stop command." +
                        " Error: ${vmStatusResponse.msg}")
            }

            String apiPath = getVMApiSubresourcePath(namespace, vmName) + "/stop"

            log.info("Calling stop for VM ${vmName}. Path: ${apiPath}")

            // The stop endpoint is a PUT with an empty body
            ServiceResponse stopResponse = harvesterApiClient.put(apiPath, [:])

            if (stopResponse.success) {
                log.info("Successfully sent stop request for VM ${vmName}.")
                log.info("Waiting for VM ${vmName} to reach 'Stopped' state.")
                waitForState(harvesterApiClient, namespace, vmName, [VM_STATUS_STOPPED])
                log.info("VM ${vmName} has reached 'Stopped' state.")
                return ServiceResponse.success("VM stop request sent successfully.")
            } else {
                log.error("Failed to stop VM ${vmName}: ${stopResponse.msg}")
                return ServiceResponse.error("Failed to stop VM: ${stopResponse.msg}")
            }
        } catch (RuntimeException e) {
            // Propagate RuntimeException (e.g., UID mismatch) so stopWorkload rethrows it
            // and Morpheus core marks the task as failed in the UI
            throw e
        } catch (Exception e) {
            log.error("Error stopping VM: ${e.message}", e)
            return ServiceResponse.error("Error stopping VM: ${e.message}")
        }
    }

    ServiceResponse startVM() {
        log.info("Starting VM for server: ${server.name}")
        try {
            // first check if the resource is already running
            String vmStatusPath = getVMApiResourcePath(namespace, vmName)
            ServiceResponse vmStatusResponse = harvesterApiClient.get(vmStatusPath)

            if(vmStatusResponse.success) {
                def vmDetails = vmStatusResponse.data

                // UID pre-check: ensure we are operating on the correct VM
                String serverExternalId = server.externalId
                if (serverExternalId) {
                    String clusterVmUid = vmDetails?.metadata?.uid
                    if (clusterVmUid && clusterVmUid != serverExternalId) {
                        String mismatchMsg = "VM ${vmName} UID mismatch: expected ${serverExternalId}, " +
                                "found ${clusterVmUid}. The original VM has already been deleted " +
                                "(a new VM with the same name exists in the cluster). " +
                                "Start operation aborted to avoid affecting the new VM."
                        log.warn(mismatchMsg)
                        // Note: Morpheus core (startInstance) ignores startContainer's return value,
                        // so this error won't surface in the UI — but it IS logged clearly above.
                        return ServiceResponse.error(mismatchMsg)
                    }
                    log.info("VM ${vmName} UID confirmed (${clusterVmUid}), proceeding with start")
                }

                String currentStatus = vmDetails.status?.printableStatus
                if(currentStatus == VM_STATUS_RUNNING) {
                    log.info("VM ${vmName} is already running.")
                    return ServiceResponse.success("VM is already in the requested state: Running.")
                }
                if(currentStatus == VM_STATUS_STARTING) {
                    log.info("VM ${vmName} is already starting.")
                    return ServiceResponse.success("VM is already in the requested state: Starting.")
                }
                if(currentStatus == VM_STATUS_PAUSED || currentStatus == VM_STATUS_UNPAUSING) {
                    log.info("VM is in ${currentStatus} state, attempting to unpause.")
                    String unpausePath = getVMIAPISubresourcePath(namespace, vmName) + "/unpause"
                    ServiceResponse unpauseResponse = harvesterApiClient.put(unpausePath, [:])
                    if(unpauseResponse.success) {
                        log.info("Successfully sent unpause request for VM ${vmName}.")
                        return ServiceResponse.success("VM unpause request sent successfully.")
                    } else {
                        log.error("Failed to unpause VM ${vmName}: ${unpauseResponse.msg}")
                        return ServiceResponse.error("Failed to unpause VM: ${unpauseResponse.msg}")
                    }
                }
            } else {
                log.warn("Could not retrieve VM status for ${vmName}. Proceeding with start command." +
                        " Error: ${vmStatusResponse.msg}")
            }

            String apiPath = getVMApiSubresourcePath(namespace, vmName) + "/start"
            log.info("Calling start for VM ${vmName}. Path: ${apiPath}")

            // The start endpoint is a PUT with an empty body
            ServiceResponse startResponse = harvesterApiClient.put(apiPath, [:])

            if (startResponse.success) {
                log.info("Successfully sent start request for VM ${vmName}.")
                log.info("Waiting for VM ${vmName} to reach 'Running' state.")
                waitForState(harvesterApiClient, namespace, vmName, [VM_STATUS_RUNNING])
                log.info("VM ${vmName} has reached 'Running' state.")
                return ServiceResponse.success("VM start request sent successfully.")
            } else {
                // Handle "stop/start already underway" error with retry
                if (startResponse.msg?.contains("stop/start already underway") ||
                    startResponse.data?.message?.contains("stop/start already underway")) {
                    log.info("VM ${vmName} reported 'stop/start already underway', waiting 10 seconds and retrying...")
                    sleep(10000) // Wait 10 seconds
                    
                    // Verify VM is in stopped state
                    ServiceResponse recheckResponse = harvesterApiClient.get(vmStatusPath)
                    if (recheckResponse.success) {
                        String recheckStatus = recheckResponse.data?.status?.printableStatus
                        log.info("VM status after wait: ${recheckStatus}")
                        
                        if (recheckStatus == VM_STATUS_STOPPED) {
                            // Retry the start command
                            log.info("Retrying start for VM ${vmName}")
                            ServiceResponse retryStartResponse = harvesterApiClient.put(apiPath, [:])
                            
                            if (retryStartResponse.success) {
                                log.info("Successfully sent start request for VM ${vmName} on retry.")
                                log.info("Waiting for VM ${vmName} to reach 'Running' state.")
                                waitForState(harvesterApiClient, namespace, vmName, [VM_STATUS_RUNNING])
                                log.info("VM ${vmName} has reached 'Running' state.")
                                return ServiceResponse.success("VM start request sent successfully after retry.")
                            } else {
                                log.error("Failed to start VM ${vmName} on retry: ${retryStartResponse.msg}")
                                return ServiceResponse.error("Failed to start VM after retry: ${retryStartResponse.msg}")
                            }
                        } else {
                            log.error("VM ${vmName} is in '${recheckStatus}' state, expected 'Stopped'")
                            return ServiceResponse.error("VM not in expected state for start: ${recheckStatus}")
                        }
                    }
                }
                
                log.error("Failed to start VM ${vmName}: ${startResponse.msg}")
                return ServiceResponse.error("Failed to start VM: ${startResponse.msg}")
            }
        } catch (Exception e) {
            log.error("Error starting VM: ${e.message}", e)
            return ServiceResponse.error("Error starting VM: ${e.message}")
        }
    }

    ServiceResponse restartVM() {
        log.info("Restarting VM for server: ${server.name}")
        try {
            // first check if the resource is already running
            String vmStatusPath = getVMApiResourcePath(namespace, vmName)
            ServiceResponse vmStatusResponse = harvesterApiClient.get(vmStatusPath)

            if(vmStatusResponse.success) {
                def vmDetails = vmStatusResponse.data

                // UID pre-check: ensure we are operating on the correct VM
                String serverExternalId = server.externalId
                if (serverExternalId) {
                    String clusterVmUid = vmDetails?.metadata?.uid
                    if (clusterVmUid && clusterVmUid != serverExternalId) {
                        String mismatchMsg = "VM ${vmName} UID mismatch: expected ${serverExternalId}, " +
                                "found ${clusterVmUid}. The original VM has already been deleted " +
                                "(a new VM with the same name exists in the cluster). " +
                                "Restart operation aborted to avoid affecting the new VM."
                        log.warn(mismatchMsg)
                        // Note: Morpheus core (rerunContainer) ignores rerunInstance return value,
                        // so this error won't surface in the UI — but it IS logged clearly above.
                        return ServiceResponse.error(mismatchMsg)
                    }
                    log.info("VM ${vmName} UID confirmed (${clusterVmUid}), proceeding with restart")
                }

                if(vmDetails.status?.printableStatus != VM_STATUS_RUNNING && vmDetails.status?.printableStatus != VM_STATUS_PAUSED) {
                    log.info("VM ${vmName} is not in a restartable state. Current state: ${vmDetails.status?.printableStatus}")
                    return ServiceResponse.error("VM is not in a restartable state. Current state: ${vmDetails.status?.printableStatus}")
                }
            } else {
                log.warn("Could not retrieve VM status for ${vmName}. Proceeding with restart command." +
                        " Error: ${vmStatusResponse.msg}")
            }

            String apiPath = getVMApiSubresourcePath(namespace, vmName) + "/restart"

            log.info("Calling restart for VM ${vmName}. Path: ${apiPath}")

            // The restart endpoint is a PUT with an empty body
            ServiceResponse restartResponse = harvesterApiClient.put(apiPath, [:])

            if (restartResponse.success) {
                log.info("Successfully sent restart request for VM ${vmName}.")
                return ServiceResponse.success("VM restart request sent successfully.")
            } else {
                log.error("Failed to restart VM ${vmName}: ${restartResponse.msg}")
                return ServiceResponse.error("Failed to restart VM: ${restartResponse.msg}")
            }
        } catch (Exception e) {
            log.error("Error restarting VM: ${e.message}", e)
            return ServiceResponse.error("Error restarting VM: ${e.message}")
        }
    }

    ServiceResponse suspendVM() {
        log.info("Suspending VM for server: ${server.name}")
        try {
            String vmStatusPath = getVMApiResourcePath(namespace, vmName)
            ServiceResponse vmStatusResponse = harvesterApiClient.get(vmStatusPath)

            if(vmStatusResponse.success) {
                def vmDetails = vmStatusResponse.data

                // UID pre-check: ensure we are operating on the correct VM
                String serverExternalId = server.externalId
                if (serverExternalId) {
                    String clusterVmUid = vmDetails?.metadata?.uid
                    if (clusterVmUid && clusterVmUid != serverExternalId) {
                        String mismatchMsg = "VM ${vmName} UID mismatch: expected ${serverExternalId}, " +
                                "found ${clusterVmUid}. The original VM has already been deleted " +
                                "(a new VM with the same name exists in the cluster). " +
                                "Suspend operation aborted to avoid affecting the new VM."
                        log.warn(mismatchMsg)
                        // Note: Morpheus core ignores suspendContainer's return value,
                        // so this error won't surface in the UI — but it IS logged clearly above.
                        return ServiceResponse.error(mismatchMsg)
                    }
                    log.info("VM ${vmName} UID confirmed (${clusterVmUid}), proceeding with suspend")
                }

                String currentStatus = vmDetails.status?.printableStatus
                if(currentStatus == VM_STATUS_PAUSED) {
                    log.info("VM ${vmName} is already paused.")
                    return ServiceResponse.success("VM is already in the requested state: Paused.")
                }
                if(currentStatus == VM_STATUS_PAUSING) {
                    log.info("VM ${vmName} is already pausing.")
                    return ServiceResponse.success("VM is already in the requested state: Pausing.")
                }
                if(currentStatus != VM_STATUS_RUNNING) {
                    log.warn("VM ${vmName} is not in a running state, cannot suspend. Current state: ${currentStatus}")
                    return ServiceResponse.error("VM cannot be suspended as it is not running. Current state: ${currentStatus}")
                }
            } else {
                log.warn("Could not retrieve VM status for ${vmName}. Proceeding with suspend command. Error: ${vmStatusResponse.msg}")
            }

            String apiPath = getVMIAPISubresourcePath(namespace, vmName) + "/pause"
            log.info("Calling suspend for VM ${vmName}. Path: ${apiPath}")

            ServiceResponse suspendResponse = harvesterApiClient.put(apiPath, [:])

            if (suspendResponse.success) {
                log.info("Successfully sent suspend request for VM ${vmName}.")
                return ServiceResponse.success("VM suspend request sent successfully.")
            } else {
                log.error("Failed to suspend VM ${vmName}: ${suspendResponse.msg}")
                return ServiceResponse.error("Failed to suspend VM: ${suspendResponse.msg}")
            }
        } catch (Exception e) {
            log.error("Error suspending VM: ${e.message}", e)
            return ServiceResponse.error("Error suspending VM: ${e.message}")
        }
    }

    // get subresources path for VM
    static String getVMApiSubresourcePath(String namespace, String vmName) {
        return "/apis/subresources.kubevirt.io/v1/namespaces/${namespace}/virtualmachines/${vmName}"
    }

    static String getVMIAPISubresourcePath(String namespace, String vmName) {
        return "/apis/subresources.kubevirt.io/v1/namespaces/${namespace}/virtualmachineinstances/${vmName}"
    }

    static String getVMApiResourcePath(String namespace, String vmName = null) {
        String apiPath = "/apis/kubevirt.io/v1/namespaces/${namespace}/virtualmachines"
        if (vmName) {
            apiPath += "/${vmName}"
        }
        return apiPath
    }

    // wait for the VM to reach one of the acceptable states
    static boolean waitForState(HarvesterApiClient harvesterApiClient, String namespace, String vmName,
                                List<String> acceptableStates) {
        int maxRetries = 10
        for(int i = 0; i < maxRetries; i++) {
            try {
                String vmStatusPath = getVMApiResourcePath(namespace, vmName)
                ServiceResponse vmStatusResponse = harvesterApiClient.get(vmStatusPath)
                if(vmStatusResponse.success) {
                    def vmDetails = vmStatusResponse.data
                    String currentStatus = vmDetails.status?.printableStatus
                    if(acceptableStates.contains(currentStatus)) {
                        return true
                    }
                }
            } catch (Exception e) {
                log.error("Error waiting for VM state: ${e.message}", e)
            }
            sleep(5000)
        }
        return false
    }

    /**
     * Validates the resize request
     * @param resizeRequest the resize request to validate
     * @param opts additional options
     * @return ServiceResponse indicating if the request is valid
     */
    /**
     * Whether Morpheus can leave the VM running for this reconfigure: the VM has CPU and memory hot add, is running,
     * only CPU and/or memory grow (within the VM's maximums, same cores per socket) and no disks or networks change.
     */
    boolean canHotResize(ResizeV2Request resizeRequest) {
        try {
            // disks added, removed or resized, or networks added or removed, need the usual stop
            // (volumesUpdate lists every disk, so only a size change counts)
            boolean diskResized = resizeRequest?.volumesUpdate?.any { UpdateModel update ->
                def newSize = update?.updateProps?.maxStorage
                newSize != null && (newSize as Long) != (update?.existingModel as StorageVolume)?.maxStorage
            }
            if (!resizeRequest || resizeRequest.volumesAdd || resizeRequest.volumesDelete || diskResized ||
                    resizeRequest.interfacesAdd || resizeRequest.interfacesDelete || networkChanges(resizeRequest)) {
                return false
            }
            ServiceResponse vmResponse = harvesterApiClient.get(getVMApiResourcePath(namespace, vmName))
            Map vm = vmResponse?.success && vmResponse.data instanceof Map ? vmResponse.data as Map : null
            if (!CpuTopology.isHotplugEnabled(vm) || vm?.status?.printableStatus != VM_STATUS_RUNNING) {
                return false
            }
            Map domain = vm?.spec?.template?.spec?.domain as Map
            Long memoryMi = resizeRequest.maxMemory ? (resizeRequest.maxMemory / (1024L * 1024L)) as Long : null
            String blocker = CpuTopology.hotAddBlocker(domain, CommonUtils.toPositiveLong(resizeRequest.maxCores),
                    CommonUtils.toPositiveLong(resizeRequest.coresPerSocket), memoryMi)
            if (blocker) {
                log.info("Reconfigure of ${vmName} needs a restart: ${blocker}")
                return false
            }
            return true
        } catch (Exception e) {
            log.warn("Unable to check hot add for ${vmName}: ${e.message}")
            return false
        }
    }

    ServiceResponse validateResizeRequest(ResizeV2Request resizeRequest, Map opts) {
        log.info("Validating ResizeV2Request")

        // Regex pattern for Harvester DNS-1123 subdomain names
        String regex = "^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*"
        String errorMessage = "Name must consist of lower case alphanumeric characters, '-' or '.', and must start and end with an alphanumeric character (e.g. 'example.com')"

        if (resizeRequest) {
            log.info("maxMemory: ${resizeRequest.maxMemory}")
            log.info("maxCores: ${resizeRequest.maxCores}")
            log.info("maxStorage: ${resizeRequest.maxStorage}")
            log.info("volumes add ${resizeRequest.volumesAdd} delete ${resizeRequest.volumesDelete} " +
                    "update ${resizeRequest.volumesUpdate}")

            log.info("interfaces add ${resizeRequest.interfacesAdd}, delete ${resizeRequest.interfacesDelete}, " +
                    "update ${resizeRequest.interfacesUpdate}")
            log.info ("controllers add ${resizeRequest.controllersAdd}, delete ${resizeRequest.controllersDelete}, " +
                    "update ${resizeRequest.controllersUpdate}")

            def validationErrors = []

            // CPU socket validation — fetch live topology from Harvester.
            // validateResizeWorkload is called before Morpheus stops the VM, so failing here
            // prevents the VM from being stopped unnecessarily.
            if (resizeRequest.maxCores) {
                try {
                    ServiceResponse liveVmResponse = harvesterApiClient.get(getVMApiResourcePath(namespace, vmName))
                    if (liveVmResponse.success) {
                        def cpuSpec = liveVmResponse.data?.spec?.template?.spec?.domain?.cpu
                        Long apiCoresPerSocket = cpuSpec?.cores as Long
                        Long apiSockets       = (cpuSpec?.sockets as Long) ?: 1L
                        Long requestedCps = CommonUtils.toPositiveLong(resizeRequest.coresPerSocket)
                        Long keptCps = requestedCps ?: (CpuTopology.isHotplugEnabled(liveVmResponse.data as Map) ? apiCoresPerSocket : null)
                        if (keptCps) {
                            // cores per socket chosen on the form (or kept on a hot add VM): the core count must split into it
                            if (CpuTopology.split(resizeRequest.maxCores, keptCps) == null) {
                                String msg = CpuTopology.splitError(resizeRequest.maxCores, keptCps)
                                log.error(msg)
                                validationErrors << [field: 'maxCores', msg: msg]
                            }
                        } else if (apiCoresPerSocket && apiCoresPerSocket > 0 && apiSockets > 1) {
                            if (resizeRequest.maxCores % apiSockets != 0) {
                                String msg = "Requested vCPU count (${resizeRequest.maxCores}) must be divisible by " +
                                        "the number of CPU sockets (${apiSockets}) on this VM. " +
                                        "Valid values: ${(1..32).collect { it * apiSockets }.findAll { it >= resizeRequest.maxCores - apiSockets && it <= resizeRequest.maxCores + apiSockets }.join(', ')}."
                                log.error(msg)
                                validationErrors << [field: 'maxCores', msg: msg]
                            }
                        }
                    } else {
                        log.warn("Could not fetch live VM topology for CPU validation (${liveVmResponse.msg}), skipping socket check")
                    }
                } catch (Exception apiEx) {
                    log.warn("Exception fetching live VM topology for CPU validation: ${apiEx.message}, skipping socket check")
                }
            }

            // Validate volumes to add validations done here
            if (opts?.volumes) {

                List<String> existingVolumeNames = server.volumes?.collect { it.name } ?: []
                List<String> optsVolumeNames = opts.volumes.collect { it.name }
                List<String> newVolumeNames = optsVolumeNames - existingVolumeNames
                List<String> processedNames = []

                for (String volumeName : newVolumeNames) {
                    log.info("Validating volume add: volumeName=${volumeName}")

                    if (volumeName) {
                        // Validate name format
                        if (!(volumeName ==~ regex)) {
                            log.error "Volume name '${volumeName}' is invalid. ${errorMessage}"
                            validationErrors << [field: 'volume', msg: "Volume name '${volumeName}' is invalid. ${errorMessage}"]
                        }

                        // Check for duplicates in existing volumes
                        if (existingVolumeNames.contains(volumeName)) {
                            log.error "Volume name '${volumeName}' already exists on this server."
                            validationErrors << [field: 'volume', msg: "Volume name '${volumeName}' already exists on this server."]
                        }

                        // Check for duplicates in the add list
                        if (processedNames.contains(volumeName)) {
                            log.error("Duplicate volume name '${volumeName}' in add request.")
                            validationErrors << [field: 'volume', msg: "Duplicate volume name '${volumeName}' in add request."]
                        }

                        processedNames.add(volumeName)
                    }
                }
            }
            // volume updates - handle resizing of existing volumes here (reduce in size not allowed)
            if (resizeRequest.volumesUpdate) {
                for (UpdateModel updateVolume : resizeRequest.volumesUpdate) {
                    log.info "validating volume update with props: ${updateVolume.updateProps}"
                    StorageVolume volume = updateVolume.existingModel as StorageVolume
                    Long newMaxStorage = updateVolume.updateProps?.maxStorage as Long

                    if (newMaxStorage && volume.maxStorage > newMaxStorage) {
                        log.error("Volume '${volume.name}' resize to smaller size is not allowed. Current size: ${volume.maxStorage}, Requested size: ${newMaxStorage}")
                        validationErrors << [field: 'volume', msg: "Volume '${volume.name}' resize to smaller size is not allowed."]
                    }
                }
            }
            // validate networkInterfaces to add
            List newNetworkNames = []
            if (resizeRequest.interfacesAdd) {
                for (UpdateModel updateInterface : resizeRequest.interfacesAdd) {
                    // log updateProps here
                    log.info "validating interface add with props: ${updateInterface.updateProps}"
                    Long networkId = updateInterface.updateProps?.network?.id as Long
                    // collect all new interface names that are being added here
                    newNetworkNames << updateInterface.updateProps?.network?.name

                    if (networkId) {
                        Network network = morpheusContext.services.network.get(networkId)
                        String networkName = network.displayName
                        log.info "network name for interface add is ${networkName}"
                        // Check if network is in the same namespace
                        if (networkName != 'default-pod-network') {
                            // displayName is a combination of namespace and NAD separated by "/"
                            String networkNamespace =  networkName.substring(0, networkName.indexOf('/'))
                            if (networkNamespace && networkNamespace != this.namespace) {
                                validationErrors << [field: 'networkInterface', msg: "Network '${networkName}' is in namespace " +
                                        "'${networkNamespace}', but VM is in '${this.namespace}'." +
                                        " Networks must be in the same namespace."]
                            }
                        }
                    }
                }
            }

            // check for duplicate network names in the newNetworkNames list
            Set<String> duplicateNetworkNames = newNetworkNames.findAll { name ->
                newNetworkNames.count(name) > 1
            } as Set<String>

            duplicateNetworkNames.each { dupName ->
                log.error("Duplicate network name '${dupName}' in interface add request.")
                validationErrors << [field: 'networkInterface', msg: "Duplicate network name '${dupName}' in interface add request."]
            }

            // check if the new interface network names already exist on the server.ComputeServerInterfaces
            List<String> existingNetworkNames = server.interfaces?.collect { it.network?.name }?.findAll { it != null } ?: []

            for (String newNetName : newNetworkNames) {
                if (newNetName && existingNetworkNames.contains(newNetName)) {
                    log.error("Network '${newNetName}' already exists on this server.")
                    validationErrors << [field: 'networkInterface', msg: "Network '${newNetName}' already exists on this server."]
                }
            }

            if (validationErrors) {
                log.error "Validation errors detected during resize request validation. ${validationErrors}"
                ServiceResponse rtn = ServiceResponse.error("Validation failed")
                validationErrors.each { err ->
                    rtn.addError(err.field, err.msg)
                }
                return rtn
            }
        }

        return ServiceResponse.success()
    }

    /**
     * Applies CPU and memory changes to the VM
     * @param resizeRequest the resize request containing new CPU/memory values
     * @return ServiceResponse indicating success or failure
     */
    private ServiceResponse applyCpuMemoryChanges(ResizeV2Request resizeRequest) {
        String vmApiPath = getVMApiResourcePath(namespace, vmName)

        // Fetch live CPU topology directly from Harvester — this is the single source of truth.
        // We do NOT rely on the Morpheus DB here because server.coresPerSocket may be stale
        // (e.g. never updated after a VM was imported into Harvester, or a previous reconfigure
        // that ran before this fix was deployed).
        Long currentCores
        Long currentSockets = 1L
        Long currentCoresPerSocket = 1L

        ServiceResponse liveVmResponse = harvesterApiClient.get(vmApiPath)
        def liveDomain = liveVmResponse.success ? liveVmResponse.data?.spec?.template?.spec?.domain : null
        Map newLimits = [:]  // resources.limits values to set (Harvester sizes VMs from the limits)
        if (liveVmResponse.success) {
            def cpuSpec = liveVmResponse.data?.spec?.template?.spec?.domain?.cpu
            Long apiCoresPerSocket = cpuSpec?.cores as Long
            Long apiSockets       = (cpuSpec?.sockets as Long) ?: 1L
            if (apiCoresPerSocket && apiCoresPerSocket > 0) {
                currentCoresPerSocket = apiCoresPerSocket
                currentSockets        = apiSockets
                currentCores          = apiCoresPerSocket * apiSockets
                log.info("Live CPU topology from Harvester: coresPerSocket=${currentCoresPerSocket}, " +
                         "sockets=${currentSockets}, totalvCPU=${currentCores}")
            } else {
                log.warn("Could not parse CPU spec from Harvester response, falling back to DB values")
                currentCores          = server.maxCores
                currentCoresPerSocket = (server.coresPerSocket && server.coresPerSocket > 0) ? server.coresPerSocket : 1L
                currentSockets        = (currentCores && currentCores > 0)
                        ? Math.max((currentCores / currentCoresPerSocket) as Long, 1L) : 1L
            }
        } else {
            log.warn("Could not fetch live VM from Harvester (${liveVmResponse.msg}), falling back to DB values")
            currentCores          = server.maxCores
            currentCoresPerSocket = (server.coresPerSocket && server.coresPerSocket > 0) ? server.coresPerSocket : 1L
            currentSockets        = (currentCores && currentCores > 0)
                    ? Math.max((currentCores / currentCoresPerSocket) as Long, 1L) : 1L
        }

        Long currentMemoryBytes = server.maxMemory
        Long newCoresPerSocket = null  // set inside the CPU-change block; used later to persist to DB

        log.info("Current VM configuration - CPU: ${currentCores}, Memory: ${currentMemoryBytes} bytes")
        log.info("Requested configuration - CPU: ${resizeRequest.maxCores}, Memory: ${resizeRequest.maxMemory} bytes")

        // Build patch operations
        List patchOperations = []

        Map liveVm = liveVmResponse.success && liveVmResponse.data instanceof Map ? liveVmResponse.data as Map : null
        boolean hotplugVm = CpuTopology.isHotplugEnabled(liveVm)
        boolean vmRunning = liveVm?.status?.printableStatus == VM_STATUS_RUNNING
        Long requestedCps = CommonUtils.toPositiveLong(resizeRequest.coresPerSocket)
        Long targetVcpus = resizeRequest.maxCores ?: currentCores
        boolean cpuChange = (resizeRequest.maxCores && resizeRequest.maxCores != currentCores) ||
                (requestedCps && requestedCps != currentCoresPerSocket)
        boolean memoryChange = resizeRequest.maxMemory && resizeRequest.maxMemory != currentMemoryBytes
        Long newSockets = currentSockets

        if (cpuChange) {
            // currentSockets and currentCoresPerSocket are from the live Harvester fetch above.
            log.info("CPU topology from Harvester: totalvCPU=${currentCores}, " +
                     "coresPerSocket=${currentCoresPerSocket}, sockets=${currentSockets}")
            Map<String, Long> topology
            if (requestedCps) {
                // cores per socket chosen on the reconfigure form
                topology = CpuTopology.split(targetVcpus, requestedCps)
                if (topology == null) {
                    String msg = "Resize rejected: ${CpuTopology.splitError(targetVcpus, requestedCps)}"
                    log.error(msg)
                    return ServiceResponse.error(msg)
                }
            } else if (hotplugVm) {
                // hot add VMs grow by whole sockets: keep the cores per socket
                topology = CpuTopology.split(targetVcpus, currentCoresPerSocket)
                if (topology == null) {
                    String msg = "Resize rejected: ${CpuTopology.splitError(targetVcpus, currentCoresPerSocket)}"
                    log.error(msg)
                    return ServiceResponse.error(msg)
                }
            } else {
                // Safety-net enforcement (prepareResizeWorkload already validated this before the VM
                // was stopped, so this should never trigger in normal flow).
                if (currentSockets > 1 && (targetVcpus % currentSockets != 0)) {
                    String msg = "Resize rejected: requested vCPU count (${targetVcpus}) is not divisible " +
                            "by the number of CPU sockets (${currentSockets}) on this VM. " +
                            "Valid values near your request: ${(1..32).collect { it * currentSockets }.findAll { it >= targetVcpus - currentSockets && it <= targetVcpus + currentSockets }.join(', ')}."
                    log.error(msg)
                    return ServiceResponse.error(msg)
                }
                topology = [cores: (targetVcpus / currentSockets) as Long, sockets: currentSockets]
            }
            newCoresPerSocket = topology.cores
            newSockets = topology.sockets
        }

        long targetMemoryMi = memoryChange ? (resizeRequest.maxMemory / (1024L * 1024L)) as long
                : ((currentMemoryBytes ?: 0L) / (1024L * 1024L)) as long

        // CPU and memory hot add: a running hot-add VM that only grows within its maximums is changed live,
        // like Harvester's "Edit CPU and Memory" (sockets and guest memory only)
        if (hotplugVm && vmRunning && (cpuChange || memoryChange) &&
                CpuTopology.hotAddBlocker(liveDomain as Map, targetVcpus, requestedCps, memoryChange ? targetMemoryMi : null) == null) {
            if (cpuChange) {
                patchOperations.add([op: 'replace', path: '/spec/template/spec/domain/cpu/sockets', value: newSockets])
            }
            if (memoryChange) {
                patchOperations.add([op: 'replace', path: '/spec/template/spec/domain/memory/guest',
                                     value: "${targetMemoryMi}Mi".toString()])
            }
            log.info("Hot adding to running VM ${vmName}: sockets ${currentSockets} -> ${newSockets}, " +
                    "memory ${currentMemoryBytes} bytes -> ${targetMemoryMi} MiB")
        } else if (hotplugVm && (cpuChange || memoryChange)) {
            // A stopped hot-add VM (or a change that can't be made live): set the new size and reset the maximums
            long ratio = VirtualMachineProvisioning.hotplugRatio(harvesterApiClient)
            if (cpuChange) {
                long maxSockets = newSockets * ratio
                patchOperations.add([op: 'add', path: '/spec/template/spec/domain/cpu/cores', value: newCoresPerSocket])
                patchOperations.add([op: 'add', path: '/spec/template/spec/domain/cpu/sockets', value: newSockets])
                patchOperations.add([op: 'add', path: '/spec/template/spec/domain/cpu/maxSockets', value: maxSockets])
                newLimits.cpu = (maxSockets * newCoresPerSocket).toString()
            }
            if (memoryChange) {
                long maxGuestMi = targetMemoryMi * ratio
                if (liveDomain == null || liveDomain.memory != null) {
                    patchOperations.add([op: 'add', path: '/spec/template/spec/domain/memory/guest', value: "${targetMemoryMi}Mi".toString()])
                    patchOperations.add([op: 'add', path: '/spec/template/spec/domain/memory/maxGuest', value: "${maxGuestMi}Mi".toString()])
                } else {
                    patchOperations.add([op: 'add', path: '/spec/template/spec/domain/memory',
                                         value: [guest: "${targetMemoryMi}Mi".toString(), maxGuest: "${maxGuestMi}Mi".toString()]])
                }
                newLimits.memory = "${maxGuestMi}Mi".toString()
            }
            log.info("Resizing hot add VM ${vmName} (restart): ${newSockets} socket(s) × ${newCoresPerSocket ?: currentCoresPerSocket} " +
                    "core(s), ${targetMemoryMi} MiB, maximums ×${ratio}")
        } else {
            if (cpuChange) {
                log.info("Patching domain.cpu.cores = ${newCoresPerSocket}, sockets = ${newSockets} " +
                         "(requested total vCPU ${targetVcpus})")
                patchOperations.add([
                    op: 'replace',
                    path: '/spec/template/spec/domain/cpu/cores',
                    value: newCoresPerSocket
                ])
                if (newSockets != currentSockets) {
                    patchOperations.add([op: 'add', path: '/spec/template/spec/domain/cpu/sockets', value: newSockets])
                }
                newLimits.cpu = targetVcpus.toString()
                log.info("Will update CPU: ${currentCores} -> ${targetVcpus} " +
                         "(cores/socket: ${currentCoresPerSocket} -> ${newCoresPerSocket}, sockets: ${currentSockets} -> ${newSockets})")
            }

            if (memoryChange) {
                long newMemoryMi = targetMemoryMi
                String newGuestMemory = "${CommonUtils.harvesterGuestMemoryMi(newMemoryMi)}Mi".toString()

                // 'add' sets the member whether or not it already exists
                if (liveDomain == null || liveDomain.memory != null) {
                    patchOperations.add([
                        op: 'add',
                        path: '/spec/template/spec/domain/memory/guest',
                        value: newGuestMemory
                    ])
                } else {
                    patchOperations.add([
                        op: 'add',
                        path: '/spec/template/spec/domain/memory',
                        value: [guest: newGuestMemory]
                    ])
                }
                newLimits.memory = "${newMemoryMi}Mi".toString()
                log.info("Will update Memory: ${currentMemoryBytes} bytes -> ${resizeRequest.maxMemory} bytes " +
                        "(limit ${newLimits.memory}, guest ${newGuestMemory})")
            }
        }

        // Keep Harvester's resource limits in step with the new size
        if (newLimits) {
            if (liveDomain?.resources?.limits != null) {
                newLimits.each { key, value ->
                    patchOperations.add([
                        op: 'add',
                        path: "/spec/template/spec/domain/resources/limits/${key}".toString(),
                        value: value
                    ])
                }
            } else if (liveDomain?.resources != null) {
                patchOperations.add([op: 'add', path: '/spec/template/spec/domain/resources/limits', value: newLimits])
            } else if (liveDomain != null) {
                patchOperations.add([op: 'add', path: '/spec/template/spec/domain/resources', value: [limits: newLimits]])
            }
        }

        if (patchOperations.isEmpty()) {
            log.info("No CPU or memory changes to apply")
            return ServiceResponse.success()
        }

        // Perform dry-run validation
        String patchBodyJson = JsonOutput.toJson(patchOperations)
        log.info("Performing dry-run with patch: ${patchBodyJson}")

        ServiceResponse dryRunResponse = harvesterApiClient.patch(
            vmApiPath, patchBodyJson, JSON_PATCH_CONTENT_TYPE, PATCH_DRY_RUN_OPTS)

        if (!dryRunResponse.success) {
            log.error("Dry-run validation failed: ${dryRunResponse.msg}")
            return ServiceResponse.error("Resize validation failed: ${dryRunResponse.msg}")
        }

        log.info("Dry-run successful - proceeding with actual resize")
        // Apply the actual patch
        log.info("Applying resize with patch: ${patchBodyJson}")

        ServiceResponse patchResponse = harvesterApiClient.patch(
            vmApiPath, patchBodyJson, JSON_PATCH_CONTENT_TYPE)

        if (!patchResponse.success) {
            log.error("Failed to apply resize: ${patchResponse.msg}")
            return ServiceResponse.error("Resize failed: ${patchResponse.msg}")
        }

        log.info("Successfully applied resize to VM ${vmName}")

        // Verify the changes
        ServiceResponse verifyResponse = harvesterApiClient.get(vmApiPath)
        if (verifyResponse.success) {
            def updatedVmData = verifyResponse.data
            def updatedCores = updatedVmData?.spec?.template?.spec?.domain?.cpu?.cores
            def updatedMemory = updatedVmData?.spec?.template?.spec?.domain?.memory?.guest
            log.info("Verified VM configuration - CPU: ${updatedCores}, Memory: ${updatedMemory}")
        }

        // Persist the updated CPU topology back to Morpheus so subsequent reconfigures
        // compute currentSockets correctly.  Without this, server.coresPerSocket stays at its
        // old value while server.maxCores grows, making currentSockets = maxCores/1 = maxCores.
        if (cpuChange) {
            server.maxCores = targetVcpus
            server.coresPerSocket = newCoresPerSocket
            log.info("Persisting updated CPU topology to Morpheus DB: maxCores=${server.maxCores}, coresPerSocket=${server.coresPerSocket}")
            try {
                morpheusContext.services.computeServer.save(server)
            } catch (Exception saveEx) {
                log.warn("Could not persist CPU topology update to Morpheus DB: ${saveEx.message}", saveEx)
            }
        }

        return ServiceResponse.success()
    }

    /**
     * Orchestrates volume changes including resize and add operations
     * @param resizeRequest the resize request containing volume updates
     * @return ServiceResponse indicating success or failure
     */
    private ServiceResponse applyVolumeChanges(ResizeV2Request resizeRequest) {
        log.info("applyVolumeChanges called")

        Boolean volumeChangesHandled = false

        try {
            // Resize existing volumes if there are any to resize
            if (resizeRequest.volumesUpdate && !resizeRequest.volumesUpdate.isEmpty()) {
                ServiceResponse resizeResult = resizeExistingVolumes(resizeRequest)
                if (!resizeResult.success) {
                    return resizeResult
                }
                volumeChangesHandled = true
            } else {
                log.info("No volumes to resize")
            }

            // Add new volumes if there are any to add
            if (resizeRequest.volumesAdd && !resizeRequest.volumesAdd.isEmpty()) {
                ServiceResponse addResult = addVolumesToVM(resizeRequest)
                if (!addResult.success) {
                    return addResult
                }
                volumeChangesHandled = true
            } else {
                log.info("No volumes to add")
            }

            // Remove volumes if there are any to delete
            if (resizeRequest.volumesDelete && !resizeRequest.volumesDelete.isEmpty()) {
                ServiceResponse removeResult = removeVolumesFromVM(resizeRequest)
                if (!removeResult.success) {
                    return removeResult
                }
                volumeChangesHandled = true
            } else {
                log.info("No volumes to remove")
            }

            log.info("Successfully applied all volume changes")
            return ServiceResponse.success(data: [volumeChangesHandled: volumeChangesHandled])

        } catch (Exception e) {
            log.error("Error in applyVolumeChanges: ${e.message}", e)
            return ServiceResponse.error("Error applying volume changes: ${e.message}")
        }
    }

    /**
     * Resizes existing volumes in the VM
     * @param resizeRequest the resize request containing volume updates
     * @return ServiceResponse indicating success or failure
     */
    private ServiceResponse resizeExistingVolumes(ResizeV2Request resizeRequest) {
        log.info("resizeExistingVolumes called")

        try {
            // Initialize HarvesterVolumes helper
            HarvesterVolumes volumeHelper = new HarvesterVolumes(morpheusContext)
            volumeHelper.setComputeServerGroup(serverGroup)
            volumeHelper.setHarvesterApiClient(harvesterApiClient)

            for (UpdateModel updateModel : resizeRequest.volumesUpdate) {
                StorageVolume originalVolume = updateModel.existingModel as StorageVolume
                StorageVolume volume
                if (originalVolume.sourceId){
                    log.info "original volume has sourceId ${originalVolume.sourceId}, Getting the volume details using sourceId"
                    volume = morpheusContext.services.storage.volume.get(originalVolume.sourceId as Long)
                }
                else{
                    log.info "volume does not have sourceId, using the existingModel details for volume"
                    volume = originalVolume
                }
                log.info("Processing volume update for volume: ${volume.name}")
                log.info "update props : ${updateModel.updateProps}"

                Long maxStorage = updateModel.updateProps?.maxStorage as Long

                if (volume.maxStorage == maxStorage) {
                    log.info("Volume ${volume.name} maxStorage has not changed. Skipping resize.")
                    continue
                }

                ServiceResponse resizeResult = volumeHelper.resizeVolume(
                    namespace, volume.type?.code,
                    volume.name ?: volume.claimName,
                    maxStorage, volume.id)

                if (!resizeResult.success) {
                    return resizeResult
                }
                volume = morpheusContext.services.storage.volume.get(volume.id)
                originalVolume.maxStorage = volume.maxStorage
                log.info "volume maxStorage updated to ${volume.maxStorage} for volume ${volume.id}, updating the original volume record"
                log.info "original volume ${originalVolume.id} maxStorage updated to ${originalVolume.maxStorage}, saving the volume record"
                morpheusContext.services.storage.volume.save(originalVolume)
            }

            log.info("Successfully resized all volumes")
            return ServiceResponse.success()

        } catch (Exception e) {
            log.error("Error in resizeExistingVolumes: ${e.message}", e)
            return ServiceResponse.error("Error resizing volumes: ${e.message}")
        }
    }

    /**
     * Adds new volumes to the VM by patching both the disks and volumes arrays
     * @param resizeRequest the resize request containing volumes to add
     * @return ServiceResponse indicating success or failure
     */
    private ServiceResponse addVolumesToVM(ResizeV2Request resizeRequest) {
        log.info("addVolumesToVM called")

        try {
            String vmApiPath = getVMApiResourcePath(namespace, vmName)

            // Initialize HarvesterVolumes helper
            HarvesterVolumes volumeHelper = new HarvesterVolumes(morpheusContext)
            volumeHelper.setComputeServerGroup(serverGroup)
            volumeHelper.setHarvesterApiClient(harvesterApiClient)

            // Build patch operations for each volume to add
            List patchOperations = []
            List dataVolumeTemplateOperations = []
            List<StorageVolume> storageVolumesForQuery = []

            for (UpdateModel updateVolume : resizeRequest.volumesAdd) {
                log.info "update props for volume add: ${updateVolume.updateProps}"
                StorageVolume originalVolume = morpheusContext.services.storage.volume.get(updateVolume.existingModel.id)
                storageVolumesForQuery.add(originalVolume)
                // Handle controller update
                String controllerMountPoint = updateVolume.updateProps?.controllerMountPoint
                if (controllerMountPoint) {
                    log.info("Updating controller for volume ${originalVolume.name} with mount point: ${controllerMountPoint}")
                    ServiceResponse updateControllerResponse = volumeHelper.updateVolumeController(originalVolume, controllerMountPoint)
                    if (!updateControllerResponse.success) {
                        log.warn "controller update failed: ${updateControllerResponse.msg}"
                    }
                }
                StorageVolume volume
                if (originalVolume.sourceId){
                    log.info "original volume has sourceId ${originalVolume.sourceId}, Getting the volume details using sourceId"
                    volume = morpheusContext.services.storage.volume.get(originalVolume.sourceId as Long)
                }
                else{
                    log.info "volume does not have sourceId, using the existingModel details for volume"
                    // do an explicit get call to fetch the latest volume details here
                    volume = morpheusContext.services.storage.volume.get(updateVolume.existingModel.id)
                }
                log.info("Adding volume: ${volume.name}, claimName: ${volume.claimName}, type: ${volume.type?.code}")

                // Retrieve serial number from volume config (already generated during volume creation)
                String diskSerial = originalVolume?.internalId
                log.info "Retrieved serial number for volume ${originalVolume.name} (ID: ${originalVolume.id}): ${diskSerial}"

                if (!diskSerial) {
                    log.warn "Serial number not found in volume config for ${originalVolume.name}, disk identification may not work correctly"
                }

                // Build disk configuration with serial number
                Map diskConfig = [
                    disk: [
                        bus: "virtio"
                    ],
                    name: volume.name,
                    serial: diskSerial  // Add serial number for disk identification
                ]

                // Build volume configuration based on volume type
                Map volumeConfig = [name: volume.name ?: volume.claimName]
                String storageVolumeTypeCode = volume.type?.code

                if (storageVolumeTypeCode == HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC) {
                    volumeConfig.persistentVolumeClaim = [
                        claimName: volume.name ?: volume.claimName
                    ]
                    log.info("Adding PVC volume ${volume.name} with claimName: ${volume.name ?: volume.claimName}")
                } else if (storageVolumeTypeCode == HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME) {
                    volumeConfig.dataVolume = [
                        name: volume.name ?: volume.claimName
                    ]
                    log.info("Adding DataVolume ${volume.name} with name: ${volume.name ?: volume.claimName}")

                    // Add dataVolumeTemplate for DataVolume type
                    Map dataVolumeBody = volume.getConfigProperty("body") as Map
                    if (dataVolumeBody) {
                        dataVolumeTemplateOperations.add([
                            op: 'add',
                            path: '/spec/dataVolumeTemplates/-',
                            value: dataVolumeBody
                        ])
                        log.info("Adding DataVolume template for ${volume.name}")
                    } else {
                        log.warn("No body config property found for DataVolume ${volume.name}")
                    }

                } else {
                    log.warn("Unsupported volume type: ${storageVolumeTypeCode}, skipping volume ${volume.name}")
                    continue
                }

                // Add patch operations for disk and volume
                patchOperations.add([
                    op: 'add',
                    path: '/spec/template/spec/domain/devices/disks/-',
                    value: diskConfig
                ])

                patchOperations.add([
                    op: 'add',
                    path: '/spec/template/spec/volumes/-',
                    value: volumeConfig
                ])

            }

            // Add dataVolumeTemplate operations at the beginning so they are created first
            patchOperations.addAll(0, dataVolumeTemplateOperations)

            if (patchOperations.isEmpty()) {
                log.info("No valid volumes to add after processing")
                return ServiceResponse.success()
            }

            // Perform dry-run validation
            String patchBodyJson = JsonOutput.toJson(patchOperations)
            log.info("Performing dry-run for adding volumes with patch: ${patchBodyJson}")

            ServiceResponse dryRunResponse = harvesterApiClient.patch(
                vmApiPath, patchBodyJson, JSON_PATCH_CONTENT_TYPE, PATCH_DRY_RUN_OPTS)

            if (!dryRunResponse.success) {
                log.error("Dry-run validation failed for adding volumes: ${dryRunResponse.msg}")
                return ServiceResponse.error("Add volumes validation failed: ${dryRunResponse.msg}")
            }

            log.info("Dry-run successful - proceeding with adding volumes")

            // Apply the actual patch
            log.info("Applying add volumes patch: ${patchBodyJson}")

            ServiceResponse patchResponse = harvesterApiClient.patch(
                vmApiPath, patchBodyJson, JSON_PATCH_CONTENT_TYPE)

            if (!patchResponse.success) {
                log.error("Failed to add volumes: ${patchResponse.msg}")
                return ServiceResponse.error("Add volumes failed: ${patchResponse.msg}")
            }

            // Query DataVolumes/PVCs for volumes with sourceId
            if (storageVolumesForQuery && !storageVolumesForQuery.isEmpty()) {
                log.info("Querying DataVolumes/PVCs for ${storageVolumesForQuery.size()} volumes with sourceId")
                ProvisionHelper.queryDataVolumesForServerVolumes(
                    server,
                    morpheusContext,
                    harvesterApiClient,
                    namespace,
                    storageVolumesForQuery
                )
            } else {
                log.info("No volumes with sourceId to query")
            }

            log.info("Successfully added ${resizeRequest.volumesAdd.size()} volumes to VM ${vmName}")
            return ServiceResponse.success()

        } catch (Exception e) {
            log.error("Error in addVolumesToVM: ${e.message}", e)
            return ServiceResponse.error("Error adding volumes: ${e.message}")
        }
    }

    /**
     * Removes volumes from the VM
     * @param resizeRequest the resize request containing volumes to delete
     * @return ServiceResponse indicating success or failure
     */
    private ServiceResponse removeVolumesFromVM(ResizeV2Request resizeRequest) {
        log.info("removeVolumesFromVM called")

        try {
            for (StorageVolume volume : resizeRequest.volumesDelete) {
                String volumeName = volume.name ?: volume.claimName
                log.info("Removing volume: ${volumeName}")

                // Check for data volume template index
                Integer dataVolumeTemplateIndex = ProvisionHelper.getDataVolumeTemplateIndex(
                        harvesterApiClient, namespace, vmName, volumeName)

                // We need to fetch indices for each volume as removing one shifts the indices
                // ProvisionHelper.getDiskAndVolumeIndices fetches the VM details
                Map indices = ProvisionHelper.getDiskAndVolumeIndices(
                        harvesterApiClient, namespace, vmName, server.computeServerType?.code, volumeName)

                if (indices) {
                    List patchOperations = ProvisionHelper.buildDiskRemovalPatch(
                            indices.diskIndex.toString(), indices.volumeIndex.toString())

                    if (dataVolumeTemplateIndex != null && dataVolumeTemplateIndex > -1) {
                        patchOperations.add([
                                op  : 'remove',
                                path: "/spec/dataVolumeTemplates/${dataVolumeTemplateIndex}"
                        ])
                    }

                    String patchBodyJson = JsonOutput.toJson(patchOperations)

                    // Perform dry-run validation
                    log.info("Performing dry-run for removing volume ${volumeName} with patch: ${patchBodyJson}")
                    ServiceResponse dryRunResponse = harvesterApiClient.patch(
                            getVMApiResourcePath(namespace, vmName), patchBodyJson, JSON_PATCH_CONTENT_TYPE, PATCH_DRY_RUN_OPTS)

                    if (!dryRunResponse.success) {
                        log.error("Dry-run validation failed for removing volume ${volumeName}: ${dryRunResponse.msg}")
                        return ServiceResponse.error("Remove volume validation failed: ${dryRunResponse.msg}")
                    }

                    log.info("Dry-run successful - proceeding with removing volume ${volumeName}")
                    log.info("Applying remove volume patch for ${volumeName}: ${patchBodyJson}")

                    ServiceResponse patchResponse = harvesterApiClient.patch(
                            getVMApiResourcePath(namespace, vmName), patchBodyJson, JSON_PATCH_CONTENT_TYPE)

                    if (!patchResponse.success) {
                        log.error("Failed to remove volume ${volumeName}: ${patchResponse.msg}")
                        return ServiceResponse.error("Failed to remove volume ${volumeName}: ${patchResponse.msg}")
                    }
                } else {
                    log.warn("Could not find indices for volume ${volumeName}, skipping removal")
                }
            }
            return ServiceResponse.success()
        } catch (Exception e) {
            log.error("Error in removeVolumesFromVM: ${e.message}", e)
            return ServiceResponse.error("Error removing volumes: ${e.message}")
        }
    }

    /**
     * Orchestrates network changes including add operations
     * @param resizeRequest the resize request containing network updates
     * @return ServiceResponse indicating success or failure
     */
    private ServiceResponse applyNetworkChanges(ResizeV2Request resizeRequest) {
        log.info("applyNetworkChanges called")

        Boolean networkChangesHandled = false

        try {
            // Add new networks if there are any to add
            if (resizeRequest.interfacesAdd && !resizeRequest.interfacesAdd.isEmpty()) {
                ServiceResponse addResult = addNetworksToVM(resizeRequest)
                if (!addResult.success) {
                    return addResult
                }
                networkChangesHandled = true
            } else {
                log.info("No networks to add")
            }

            // Move existing NICs to another network
            List<UpdateModel> networkMoves = networkChanges(resizeRequest)
            if (networkMoves) {
                ServiceResponse updateResult = changeNetworksOnVM(networkMoves)
                if (!updateResult.success) {
                    return updateResult
                }
                networkChangesHandled = true
            }

            // Remove networks if there are any to delete
            if (resizeRequest.interfacesDelete && !resizeRequest.interfacesDelete.isEmpty()) {
                ServiceResponse removeResult = removeNetworksFromVM(resizeRequest)
                if (!removeResult.success) {
                    return removeResult
                }
                networkChangesHandled = true
            } else {
                log.info("No networks to remove")
            }

            log.info("Successfully applied all network changes")
            return ServiceResponse.success(data: [networkChangesHandled: networkChangesHandled])

        } catch (Exception e) {
            log.error("Error in applyNetworkChanges: ${e.message}", e)
            return ServiceResponse.error("Error applying network changes: ${e.message}")
        }
    }

    /**
     * Adds new networks to the VM by patching both the interfaces and networks arrays
     * @param resizeRequest the resize request containing networks to add
     * @return ServiceResponse indicating success or failure
     */
    private ServiceResponse addNetworksToVM(ResizeV2Request resizeRequest) {
        log.info("addNetworksToVM called")

        try {
            String vmApiPath = getVMApiResourcePath(namespace, vmName)

            // Build patch operations for each network to add
            List patchOperations = []
            Map<String, String> interfaceNameMap = [:] // Map to track old name -> new name
            List<ComputeServerInterface> interfacesToUpdate = []

            for (UpdateModel updateInterface : resizeRequest.interfacesAdd) {
                ComputeServerInterface netInterface = morpheusContext.services.computeServer.computeServerInterface.get(updateInterface.existingModel.id)
                String originalInterfaceName = netInterface.name
                String networkName = netInterface.network?.name

                log.info("Adding interface: ${originalInterfaceName}, network: ${networkName}")

                // Sanitize interface name: remove spaces and convert to lowercase
                String sanitizedInterfaceName = originalInterfaceName.replaceAll("\\s+", "").toLowerCase()

                // Track the name change
                if (originalInterfaceName != sanitizedInterfaceName) {
                    interfaceNameMap[originalInterfaceName] = sanitizedInterfaceName
                    log.info "Interface name sanitized: '${originalInterfaceName}' -> '${sanitizedInterfaceName}'"
                    netInterface.name = sanitizedInterfaceName
                    interfacesToUpdate.add(netInterface)
                }

                Map interfaceConfig = [:]
                Map networkConfig = [:]

                // Check if this network is default pod network
                if (networkName != null && networkName.equalsIgnoreCase("default-pod-network")) {
                    log.info "Interface ${sanitizedInterfaceName} is using default pod network"
                    interfaceConfig = [
                        masquerade: [:],
                        model: "virtio",
                        name: sanitizedInterfaceName
                    ]

                    networkConfig = [
                        name: sanitizedInterfaceName,
                        pod: [:]
                    ]
                } else {
                    // Interface uses external NAD
                    log.info "Adding interface ${sanitizedInterfaceName} with NAD: ${networkName}"
                    interfaceConfig = [
                        bridge: [:],
                        model: "virtio",
                        name: sanitizedInterfaceName
                    ]

                    networkConfig = [
                        name: sanitizedInterfaceName,
                        multus: [
                            networkName: networkName
                        ]
                    ]
                }

                // Add patch operations for interface and network
                patchOperations.add([
                    op: 'add',
                    path: '/spec/template/spec/domain/devices/interfaces/-',
                    value: interfaceConfig
                ])

                patchOperations.add([
                    op: 'add',
                    path: '/spec/template/spec/networks/-',
                    value: networkConfig
                ])
            }

            if (patchOperations.isEmpty()) {
                log.info("No valid networks to add after processing")
                return ServiceResponse.success()
            }

            // Perform dry-run validation
            String patchBodyJson = JsonOutput.toJson(patchOperations)
            log.info("Performing dry-run for adding networks with patch: ${patchBodyJson}")

            ServiceResponse dryRunResponse = harvesterApiClient.patch(
                vmApiPath, patchBodyJson, JSON_PATCH_CONTENT_TYPE, PATCH_DRY_RUN_OPTS)

            if (!dryRunResponse.success) {
                log.error("Dry-run validation failed for adding networks: ${dryRunResponse.msg}")
                return ServiceResponse.error("Add networks validation failed: ${dryRunResponse.msg}")
            }

            log.info("Dry-run successful - proceeding with adding networks")

            // Apply the actual patch
            log.info("Applying add networks patch: ${patchBodyJson}")

            ServiceResponse patchResponse = harvesterApiClient.patch(
                vmApiPath, patchBodyJson, JSON_PATCH_CONTENT_TYPE)

            if (!patchResponse.success) {
                log.error("Failed to add networks: ${patchResponse.msg}")
                return ServiceResponse.error("Add networks failed: ${patchResponse.msg}")
            }

            // Update interface names in database if any were sanitized
            if (interfacesToUpdate) {
                log.info "Updating ${interfacesToUpdate.size()} interface names in database"
                try {
                    morpheusContext.async.computeServer.computeServerInterface.bulkSave(interfacesToUpdate).blockingGet()
                    log.info "Successfully updated interface names in database"
                } catch (Exception e) {
                    log.error "Error updating interface names in database: ${e.message}", e
                }
            }

            log.info("Successfully added ${resizeRequest.interfacesAdd.size()} networks to VM ${vmName}")
            return ServiceResponse.success()

        } catch (Exception e) {
            log.error("Error in addNetworksToVM: ${e.message}", e)
            return ServiceResponse.error("Error adding networks: ${e.message}")
        }
    }

    /**
     * The network chosen for an existing NIC on the reconfigure form (from the update's properties), or null when it
     * isn't there or didn't change.
     */
    static Long requestedNetworkId(UpdateModel update) {
        Map props = update?.updateProps instanceof Map ? update.updateProps as Map : [:]
        def network = props.network
        def raw = network instanceof Map ? (network as Map).id :
                (network != null && network.hasProperty('id') ? network.id : network)
        Long id = CommonUtils.toPositiveLong(raw ?: props.networkId ?: (props.network instanceof String ? props.network : null))
        Long current = (update?.existingModel as ComputeServerInterface)?.network?.id
        return id && id != current ? id : null
    }

    /** Existing NICs whose network was changed on the reconfigure form */
    static List<UpdateModel> networkChanges(ResizeV2Request resizeRequest) {
        return (resizeRequest?.interfacesUpdate ?: []).findAll { UpdateModel update -> requestedNetworkId(update) != null } as List<UpdateModel>
    }

    /**
     * Interface and network entries for a NIC on a Harvester network (bridge + multus) or the pod network
     * (masquerade), keeping the existing interface's other settings (model, MAC address, boot order).
     */
    static Map nicConfig(Map existingInterface, String nicName, String networkName) {
        boolean pod = networkName == null || networkName.equalsIgnoreCase('default-pod-network')
        Map iface = new LinkedHashMap(existingInterface ?: [model: 'virtio'])
        iface.name = nicName
        iface.remove('bridge')
        iface.remove('masquerade')
        if (pod) {
            iface.masquerade = [:]
        } else {
            iface.bridge = [:]
        }
        Map network = pod ? [name: nicName, pod: [:]] : [name: nicName, multus: [networkName: networkName]]
        return [interface: iface, network: network]
    }

    /**
     * Moves existing NICs to the network chosen on the reconfigure form: replaces the NIC's entry in
     * spec.template.spec.networks (and its binding: bridge for Harvester networks, masquerade for the pod network).
     */
    private ServiceResponse changeNetworksOnVM(List<UpdateModel> updates) {
        try {
            String vmApiPath = getVMApiResourcePath(namespace, vmName)
            ServiceResponse vmResponse = harvesterApiClient.get(vmApiPath)
            if (!vmResponse.success) {
                return ServiceResponse.error("Unable to read VM ${vmName} to change its network: ${vmResponse.msg}")
            }
            List interfaces = (vmResponse.data?.spec?.template?.spec?.domain?.devices?.interfaces ?: []) as List
            List networks = (vmResponse.data?.spec?.template?.spec?.networks ?: []) as List
            List patchOperations = []
            List<ComputeServerInterface> changed = []
            for (UpdateModel update : updates) {
                ComputeServerInterface nic = update.existingModel as ComputeServerInterface
                Network newNetwork = morpheusContext.services.network.get(requestedNetworkId(update))
                if (!newNetwork) {
                    return ServiceResponse.error("Network ${requestedNetworkId(update)} for ${nic?.name} was not found")
                }
                String nicName = nic?.name
                int interfaceIndex = interfaces.findIndexOf { it?.name == nicName }
                if (interfaceIndex < 0 && nicName) {
                    nicName = nicName.replaceAll("\\s+", "").toLowerCase()
                    interfaceIndex = interfaces.findIndexOf { it?.name == nicName }
                }
                int networkIndex = networks.findIndexOf { it?.name == nicName }
                if (interfaceIndex < 0 || networkIndex < 0) {
                    return ServiceResponse.error("NIC ${nic?.name} was not found on VM ${vmName}")
                }
                Map config = nicConfig(interfaces[interfaceIndex] as Map, nicName, newNetwork.name)
                patchOperations << [op: 'test', path: "/spec/template/spec/networks/${networkIndex}/name".toString(), value: nicName]
                patchOperations << [op: 'replace', path: "/spec/template/spec/domain/devices/interfaces/${interfaceIndex}".toString(), value: config.interface]
                patchOperations << [op: 'replace', path: "/spec/template/spec/networks/${networkIndex}".toString(), value: config.network]
                log.info("Moving NIC ${nicName} of ${vmName} to network ${newNetwork.name}")
                nic.network = newNetwork
                changed << nic
            }
            String body = JsonOutput.toJson(patchOperations)
            ServiceResponse dryRun = harvesterApiClient.patch(vmApiPath, body, JSON_PATCH_CONTENT_TYPE, PATCH_DRY_RUN_OPTS)
            if (!dryRun.success) {
                return ServiceResponse.error("Change network validation failed: ${dryRun.msg}")
            }
            ServiceResponse patch = harvesterApiClient.patch(vmApiPath, body, JSON_PATCH_CONTENT_TYPE)
            if (!patch.success) {
                return ServiceResponse.error("Change network failed: ${patch.msg}")
            }
            try {
                morpheusContext.async.computeServer.computeServerInterface.bulkSave(changed).blockingGet()
            } catch (Exception e) {
                log.warn("Network changed in Harvester, but the NICs weren't updated in Morpheus: ${e.message}")
            }
            return ServiceResponse.success()
        } catch (Exception e) {
            log.error("Error in changeNetworksOnVM: ${e.message}", e)
            return ServiceResponse.error("Error changing networks: ${e.message}")
        }
    }

    /**
     * Removes networks from the VM
     * @param resizeRequest the resize request containing networks to delete
     * @return ServiceResponse indicating success or failure
     */
    private ServiceResponse removeNetworksFromVM(ResizeV2Request resizeRequest) {
        log.info("removeNetworksFromVM called")

        try {
            for (ComputeServerInterface netInterface : resizeRequest.interfacesDelete) {
                String interfaceName = netInterface.name
                // Sanitize interface name to match what we likely stored/created
                String sanitizedInterfaceName = interfaceName.replaceAll("\\s+", "").toLowerCase()

                log.info("Removing network interface: ${interfaceName} (sanitized: ${sanitizedInterfaceName})")

                // We need to fetch indices for each interface as removing one shifts the indices
                Map indices = ProvisionHelper.getNetworkInterfaceIndices(
                        harvesterApiClient, namespace, vmName, sanitizedInterfaceName)

                // If not found with sanitized name, try original name just in case
                if (!indices && interfaceName != sanitizedInterfaceName) {
                    log.info("Could not find interface with sanitized name, trying original name: ${interfaceName}")
                    indices = ProvisionHelper.getNetworkInterfaceIndices(
                            harvesterApiClient, namespace, vmName, interfaceName)
                }

                if (indices) {
                    List patchOperations = ProvisionHelper.buildNetworkRemovalPatch(
                            indices.interfaceIndex.toString(), indices.networkIndex.toString())

                    if (patchOperations) {
                        String patchBodyJson = JsonOutput.toJson(patchOperations)

                        // Perform dry-run validation
                        log.info("Performing dry-run for removing network ${interfaceName} with patch: ${patchBodyJson}")
                        ServiceResponse dryRunResponse = harvesterApiClient.patch(
                                getVMApiResourcePath(namespace, vmName), patchBodyJson, JSON_PATCH_CONTENT_TYPE, PATCH_DRY_RUN_OPTS)

                        if (!dryRunResponse.success) {
                            log.error("Dry-run validation failed for removing network ${interfaceName}: ${dryRunResponse.msg}")
                            return ServiceResponse.error("Remove network validation failed: ${dryRunResponse.msg}")
                        }

                        log.info("Dry-run successful - proceeding with removing network ${interfaceName}")
                        log.info("Applying remove network patch for ${interfaceName}: ${patchBodyJson}")

                        ServiceResponse patchResponse = harvesterApiClient.patch(
                                getVMApiResourcePath(namespace, vmName), patchBodyJson, JSON_PATCH_CONTENT_TYPE)

                        if (!patchResponse.success) {
                            log.error("Failed to remove network ${interfaceName}: ${patchResponse.msg}")
                            return ServiceResponse.error("Failed to remove network ${interfaceName}: ${patchResponse.msg}")
                        }
                    } else {
                        log.warn("No patch operations generated for network ${interfaceName}")
                    }
                } else {
                    log.warn("Could not find indices for network ${interfaceName}, skipping removal")
                }
            }
            return ServiceResponse.success()
        } catch (Exception e) {
            log.error("Error in removeNetworksFromVM: ${e.message}", e)
            return ServiceResponse.error("Error removing networks: ${e.message}")
        }
    }
}
