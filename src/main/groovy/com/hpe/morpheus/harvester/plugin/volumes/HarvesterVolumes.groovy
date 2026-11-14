package com.hpe.morpheus.harvester.plugin.volumes

import com.hpe.morpheus.harvester.plugin.provision.CdromHelper
import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.hpe.morpheus.harvester.plugin.events.HarvesterResourceEvents
import com.hpe.morpheus.harvester.plugin.provision.ProvisionHelper
import com.hpe.morpheus.harvester.plugin.utils.CommonUtils
import com.hpe.morpheus.harvester.plugin.utils.DBUtils
import com.hpe.morpheus.harvester.plugin.utils.DataUnitConverterUtil
import com.hpe.morpheus.harvester.plugin.utils.Message
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.StorageController
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.projection.StorageVolumeIdentityProjection
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j

/* This class is used for CRUD operations on Harvester volumes (pvc and dv)
*/

@Slf4j
class HarvesterVolumes {

    // constants here for Harvester Persistent Volume Claim (PVC) API version and kind
    private static final String VOLUME_MODE = "Block" // volume mode for the pvc
    private static final String PVC_API_VERSION = "v1"
    private static final String PVC_KIND = "PersistentVolumeClaim"
    private static final List<String> PVC_HTTP_SUCCESS_CODES = ["200", "201", "202"]
    private static final String PVC_RESOURCE_STATUS_BOUND = "Bound" // status when PVC is successfully bound to a PV
    private static final String PVC_RESOURCE_STATUS_LOST = "Lost" // status when PVC is lost
    private static final String PVC_RESOURCE_STATUS_PENDING = "Pending" // status when PVC is pending

    private static final String DATA_VOLUME_API_VERSION = "cdi.kubevirt.io/v1beta1"
    private static final String DATA_VOLUME_KIND = "DataVolume"
    private static final List<String> ACCEPTABLE_DATA_VOLUME_STATES = ["Succeeded", "Bound"]
    private static final List<String> FAILURE_DATA_VOLUME_STATES = ["Failed"]
    private static final List<String> DV_PENDING_STATE = ["PendingPopulation", "WaitForFirstConsumer", "Pending"]

    private static final Integer VOLUME_NOT_FOUND_HTTP_CODE = 404

    private MorpheusContext morpheusContext // morpheus context
    private HarvesterApiClient harvesterApiClient
    private ComputeServerGroup computeServerGroup

    HarvesterVolumes(MorpheusContext morpheusContext) {
        this.morpheusContext = morpheusContext
    }

    // build the API path for the Harvester resource
    static String getPvcApiResourcePath(String namespace, String resourceName = null) {

        String apiPath = "/api/${PVC_API_VERSION}/namespaces/${namespace}/persistentvolumeclaims"
        if (resourceName) {
            apiPath += "/${resourceName}"
        }
        return apiPath
    }

    // Api resource path for Harvester datavolume
    static String getDataVolumeApiResourcePath(String namespace, String resourceName = null) {

        String apiPath = "/apis/${DATA_VOLUME_API_VERSION}/namespaces/${namespace}/datavolumes"
        if (resourceName) {
            apiPath += "/${resourceName}"
        }
        return apiPath
    }

    // validate the inputs for creating a volume
    static ServiceResponse<StorageVolume> validateVolumeInputs(StorageVolume volume) {

        if (volume == null) {
            log.error "${Message.STORAGE_VOLUME_NULL}"
            return ServiceResponse.error(Message.STORAGE_VOLUME_NULL)
        }

        if (volume.datastore == null) {
            return ServiceResponse.error(Message.STORAGE_VOLUME_DATASTORE_NULL)
        }

        return ServiceResponse.success(volume)
    }

    // Create volume --> creates a pvc in Harvester assumes dynamic provisioning of pv
    ServiceResponse<StorageVolume> createHarvesterVolume(StorageVolume volume, ComputeServer server) {
        // validate if the volume has all the necessary properties to create pvc
        ServiceResponse<StorageVolume> validationResponse = validateVolumeInputs(volume)
        if (!validationResponse.success) {
            log.error "Validation failed for volume creation: ${validationResponse.msg}"
            return validationResponse
        }

        log.info "Determine if we need to clone or create a new volume for volume: ${volume.id} " +
                "with server having source image: ${server?.sourceImage?.id}"
        // determining whether we have to clone or create a new pvc
        String volDeviceName = volume.deviceName
        VirtualImage virtualImage = null
        if (server?.sourceImage?.id) {
            virtualImage = morpheusContext.services.virtualImage.get(server.sourceImage.id as Long)
            // set the computeServerGroup here using the virtualImage
            if (!this.computeServerGroup && virtualImage?.refId) {
                ComputeServerGroup serverGroup = morpheusContext.services.cluster.get(virtualImage.refId as Long)
                this.computeServerGroup = serverGroup
            }
        }

        // If computeServerGroup is still not set (e.g. no sourceImage), derive it from the server's resource pool
        else {
            Long serverGroupId = CommonUtils.getServerGroupId(server, morpheusContext)
            if (serverGroupId) {
                this.computeServerGroup = morpheusContext.services.cluster.get(serverGroupId)
                log.info "Resolved computeServerGroup (id: ${serverGroupId}) from server resource pool"
            } else {
                log.error "Could not determine computeServerGroup for server: ${server?.id}"
                return ServiceResponse.error("Unable to determine cluster for volume creation")
            }
        }

        Map config = [:]
        String sourceVolumeName = ""
        String sourceDatasourceName = ""

        if (virtualImage?.volumes) {
            // check if any of the volumes deviceName matches the current volume deviceName
            log.info "Checking source image volumes for deviceName match: ${volDeviceName}"
            for (StorageVolumeIdentityProjection imgVol : virtualImage.volumes) {
                // get the storage volume details first
                StorageVolume vol = morpheusContext.services.storage.volume.get(imgVol.id)
                if (vol && vol.deviceName == volDeviceName) {
                    log.info("Found matching volume from source image with id ${vol.id}, name ${vol.name}")
                    config = vol.getConfigMap()
                    log.info "Extracted config for source volume: ${config}"
                    break
                }
            }
        }
        // An ISO image isn't the root disk: provisioning attaches it as a CD-ROM, and every disk is created blank
        if (CdromHelper.isIsoImage(virtualImage)) {
            config = [:]
        }
        // from the config we determine if the volume is to be cloned or created new
        if (config?.sourceType && config.sourceType == "pvc") {
            log.info "Cloning volume from source pvc: ${config.sourcePvcName}"
            sourceVolumeName = config?.sourcePvcName
        }else if (config?.sourceType && config.sourceType == "dataSource") {
            log.info "Clone volume from dataSource: ${config.sourceRefName}"
            sourceDatasourceName = config?.sourceRefName
        }
        // Harvester image: the PVC must use the image's storage class and carry the image id annotation
        boolean fromHarvesterImage = config?.sourceType == HarvesterConstants.VOLUME_SOURCE_HARVESTER_IMAGE &&
                config?.imageStorageClassName && config?.imageId
        if (fromHarvesterImage) {
            log.info "Creating volume from Harvester image ${config.imageId} (storage class ${config.imageStorageClassName})"
        }

        // get project (namespace) where the volume has to be created from server config
        Map serverConfig = server.getConfigMap()
        String project = serverConfig?.project?.name ?: DBUtils.namespaceForServer(morpheusContext, server) ?: 'default'
        log.info "Creating the pvc in project ${project}"

        // convert storage to appropriate format
        String storageInGib = DataUnitConverterUtil.dataUnitConverterBigDecimal(
                volume.maxStorage as BigDecimal, DataUnitConverterUtil.DataUnit.B,
                DataUnitConverterUtil.DataUnit.GiB).toString() + "Gi"


        // based on the storage volume type, decide whether to create a dataVolume or a pvc
        StorageVolumeType volumeType = morpheusContext.services.storage.volume.storageVolumeType.get(
                volume.type.id as Long)

        // get prefix based on the volumeType selected and prepare opts for create
        String prefix = volumeType.code == HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC ? "pvc-" : "dv-"
        String volName = generateValidName(server.name, volume.name, prefix)
        Map createOpts = [
                namespace: project,
                name: volName,
                storageClassName: volume.datastore.name,
                storage: storageInGib,
                isClone: false,
                sourcePvcNamespace: config?.sourcePvcNamespace ?: project,
                serverId: server.id
        ]
        if (fromHarvesterImage) {
            createOpts.storageClassName = config.imageStorageClassName
            createOpts.annotations = [(HarvesterConstants.ANNOTATION_IMAGE_ID): config.imageId]
        }
        // if sourceVolumeName is set, we are cloning
        if (sourceVolumeName) {
            createOpts.sourceVolumeName = sourceVolumeName
            createOpts.isClone = true
        }
        // if dataSource is set, we are cloning from a dataSource
        if (sourceDatasourceName){
            createOpts.sourceDatasourceName = sourceDatasourceName
            createOpts.sourceDatasourceNamespace = config?.sourceRefNamespace ?: project
            createOpts.isClone = true
        }
        ServiceResponse serviceResponse = null
        if (needsImageCopy(volume, config, fromHarvesterImage)) {
            // The disk goes on a different storage class than the image's: copy the image there
            return createVolumeFromImageCopy(volume, project, createOpts, config)
        }
        if (fromHarvesterImage && volumeType.code != HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC) {
            // Image-backed disks are always plain PVCs on Harvester
            StorageVolumeType pvcType = morpheusContext.services.storage.volume.storageVolumeType.find(
                    new DataQuery().withFilter('code', HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC))
            if (pvcType) {
                log.info "Switching volume ${volume.id} from ${volumeType.code} to ${pvcType.code} for Harvester image"
                volume.type = pvcType
                volumeType = pvcType
            }
        }
        switch (volumeType.code) {
            case HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME:
                log.info "Creating dataVolume for volume: ${volume.id}"
                serviceResponse = createHarvesterDataVolume(volume, project, createOpts)
                break
            case HarvesterConstants.VOLUME_TYPE_HARVESTER_DATASOURCE:
                log.info "Creating dataVolume from DataSource for volume: ${volume.id}"
                serviceResponse = createHarvesterDataVolume(volume, project, createOpts)
                break
            case HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC:
                log.info "Creating pvc for volume: ${volume.id}"
                serviceResponse = createHarvesterPvcVolume(volume, project, createOpts)
                break
            default:
                log.error "Unsupported volume type: ${volumeType.code} for volume: ${volume.id}"
                return ServiceResponse.error("Unsupported volume type: ${volumeType.code}", null, volume)
        }

        // check for any volume creation errors here
        if (!serviceResponse?.success) {
            log.error "Failed to create harvester resource for volume: ${volume.id}, error: ${serviceResponse?.msg}"
            return ServiceResponse.error(serviceResponse?.msg ?: Message.STORAGE_VOLUME_CREATE_FAILED,
                    null, volume)
        }

        log.info "Successfully created harvester resource for volume: ${volume.id}"
        return ServiceResponse.success(volume)
    }

    // this will return the defaults that are needed for creating a Persistent Volume Claim in Harvester
    static Map buildHarvesterCreatePvcParams(Map opts) {
        Map params = [
                "apiVersion": PVC_API_VERSION,
                "kind": PVC_KIND,
                "metadata": [
                    "name": opts.name,
                    "namespace": opts.namespace,
               ] + (opts.annotations ? ["annotations": opts.annotations] : [:]),
                "spec": [
                    "accessModes": ["ReadWriteMany"],
                    "resources": [
                        "requests": [
                            "storage": opts.storage
                        ]
                    ],
                    "storageClassName": opts.storageClassName,
                    "volumeMode": VOLUME_MODE
                ]
        ]

        if (opts.isClone) {
            if (opts.sourceVolumeName) {
                params.spec.dataSource = [
                        "name"     : opts.sourceVolumeName,
                        "kind"     : PVC_KIND,
                        "namespace": opts.sourcePvcNamespace ?: opts.namespace,
                        "apiGroup" : ""
                ]
            } else if (opts.sourceDatasourceName) {
                params.spec.dataSourceRef = [
                        "name"     : opts.sourceDatasourceName,
                        "kind"     : "DataSource",
                        "namespace": opts.sourceDatasourceNamespace ?: opts.namespace,
                        "apiGroup" : "cdi.kubevirt.io"
                ]
            }
        }

        return params
    }


    // build dataVolume options here
    static Map buildHarvesterDataVolumeCreateParams(Map opts) {
        Map params = [
                "apiVersion": DATA_VOLUME_API_VERSION,
                "kind": DATA_VOLUME_KIND,
                "metadata": [
                        "name": opts.name,
                        "namespace": opts.namespace,
                ],
                "spec": [
                    "pvc": [
                        "accessModes": ["ReadWriteMany"],
                        "resources": [
                            "requests": [
                                "storage": opts.storage
                            ]
                        ],
                        "storageClassName": opts.storageClassName,
                        "volumeMode": VOLUME_MODE
                    ]
                ]
        ]
        if (opts?.isClone) {
            // clone from PVC
            if (opts.sourceVolumeName) {
                params.spec.source = [
                        "pvc": [
                                "name"     : opts.sourceVolumeName,
                                "namespace": opts.sourcePvcNamespace ?: opts.namespace
                        ]
                ]
            } else if (opts.sourceDatasourceName) {
                params.spec.sourceRef = [
                        "name"     : opts.sourceDatasourceName,
                        "kind"     : "DataSource",
                        "namespace": opts.sourceDatasourceNamespace ?: opts.namespace,
                ]
            }
        }else {
            params.spec.source = [
                "blank": [:]
            ]
        }

        return params
    }
    // Clone volume --> creates a pvc in Harvester with the source pvc as the data source,
    static ServiceResponse<StorageVolume> cloneHarvesterVolume(
            StorageVolume volume, ComputeServer server, StorageVolume sourceVolume) {
        return ServiceResponse.success(volume)
    }

    /**
     * Deletes a volume (PVC or DataVolume) from Harvester.
     * Performs a dry run validation before actual deletion.
     * @param volume The StorageVolume to delete
     * @param server The ComputeServer associated with the volume
     * @param force Whether to force deletion
     * @return ServiceResponse indicating success or failure
     */
    ServiceResponse deleteHarvesterVolume(StorageVolume volume, ComputeServer server, boolean force) {
        log.info "Starting deletion process for volume: ${volume.id}, name ${volume.name}"

        // Get the volume type to determine which API to call
        StorageVolumeType volumeType = morpheusContext.services.storage.volume.storageVolumeType.get(
                volume.type.id as Long)

        if (!volumeType) {
            log.error "Could not retrieve StorageVolumeType for volume: ${volume.id}"
            return ServiceResponse.error("Unable to determine volume type")
        }

        log.info "Volume type determined: ${volumeType.code} for volume: ${volume.name}"

        // Get namespace from server config or volume config
        Map serverConfig = server?.getConfigMap()
        String namespace = serverConfig?.project?.name ?: volume.getConfigProperty('namespace') ?:
                DBUtils.namespaceForServer(morpheusContext, server) ?: 'default'
        log.info "Deleting volume from namespace: ${namespace}"

        // Initialize the API client
        if (!this.computeServerGroup) {
            // Try to get compute server group from server using CommonUtils
            if (server) {
                Long serverGroupId = CommonUtils.getServerGroupId(server, morpheusContext)
                if (serverGroupId) {
                    this.computeServerGroup = morpheusContext.services.cluster.get(serverGroupId)
                    log.info "Retrieved compute server group with ID: ${serverGroupId} from server"
                }
            }

            // Fallback to volume category if server doesn't have server group
            if (!this.computeServerGroup && volume.category?.startsWith(HarvesterConstants.CATEGORY_DATAVOLUME_CLUSTER_PREFIX)) {
                String clusterIdStr = volume.category.replace(
                        HarvesterConstants.CATEGORY_DATAVOLUME_CLUSTER_PREFIX, '')
                try {
                    Long clusterId = Long.parseLong(clusterIdStr)
                    this.computeServerGroup = morpheusContext.services.cluster.get(clusterId)
                    log.info "Retrieved compute server group with ID: ${clusterId} from volume category"
                } catch (NumberFormatException e) {
                    log.error "Failed to parse cluster ID from volume category: ${volume.category}"
                }
            }

            if (!this.computeServerGroup) {
                log.error "Could not determine compute server group for volume deletion"
                return ServiceResponse.error("Unable to determine cluster for volume deletion")
            }
        }

        this.harvesterApiClient = new HarvesterApiClient(this.computeServerGroup)

        String apiPath
        String resourceType

        // Determine API path based on volume type
        switch (volumeType.code) {
            case HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME:
                apiPath = getDataVolumeApiResourcePath(namespace, volume.name)
                resourceType = "DataVolume"
                break
            case HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC:
                apiPath = getPvcApiResourcePath(namespace, volume.name)
                resourceType = "PersistentVolumeClaim"
                break
            default:
                log.error "Unsupported volume type for deletion: ${volumeType.code}"
                return ServiceResponse.error("Unsupported volume type: ${volumeType.code}")
        }

        log.info "Deleting ${resourceType}: ${volume.name} from namespace: ${namespace}"

        try {
            // Perform dry run first to validate deletion
            log.info "Performing dry run for ${resourceType} deletion at path: ${apiPath}"
            ServiceResponse dryRunResponse = executeHarvesterApiCall('delete', apiPath, null, [dryRun: 'All'])

            if (!dryRunResponse.success) {
                // if dry run fails with a 404, it means the resource does not exist, consider it successful
                if (dryRunResponse.data?.code == VOLUME_NOT_FOUND_HTTP_CODE) {
                    log.warn "${resourceType} ${volume.name} not found in namespace: ${namespace}, " +
                            "considering as already deleted."
                    return ServiceResponse.success(volume)
                }
                log.error "Dry run for ${resourceType} deletion failed: ${dryRunResponse.msg}"
                return ServiceResponse.error(
                        "Dry run validation failed for ${resourceType} deletion: ${dryRunResponse.msg}")
            }

            log.info "Dry run succeeded for ${resourceType} deletion"

            // Perform actual deletion
            log.info "Executing actual deletion for ${resourceType} at path: ${apiPath}"
            ServiceResponse deleteResponse = executeHarvesterApiCall('delete', apiPath, null)

            if (!deleteResponse.success) {
                log.error "Failed to delete ${resourceType}: ${deleteResponse.msg}"
                return ServiceResponse.error(
                        "Failed to delete ${resourceType}: ${deleteResponse.msg}")
            }

            log.info "Successfully deleted ${resourceType}: ${volume.name} from namespace: ${namespace}"
            return ServiceResponse.success(volume)

        } catch (Exception ex) {
            log.error "Exception while deleting ${resourceType}: ${ex.message}", ex
            return ServiceResponse.error("Exception during ${resourceType} deletion: ${ex.message}")
        }
    }

    /**
     * Generates a valid PVC name using the server name and volume name, conforming to the Kubernetes regex:
     * [a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*
     * @param serverName the name of the server
     * @param volumeName the name of the volume
     * @return a valid PVC name
     */
    static String generateValidName(String serverName, String volumeName, String prefix = "pvc-") {
        String baseName = (serverName ?: "") + "-" + (volumeName ?: "")
        // Lowercase, replace invalid chars with '-', trim leading/trailing invalid chars
        String sanitized = baseName.toLowerCase().replaceAll(/[^a-z0-9.-]/, "-")
        // Remove leading/trailing non-alphanumeric
        sanitized = sanitized.replaceAll(/^[-.]+|[-.]+$/, "")
        // Prepend with "prefix"
        sanitized = prefix + sanitized
        // Ensure it matches the regex, else fallback to a default
        String regex = "${prefix}[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*"
        log.info "check sanitized name ${sanitized}"

        if (sanitized ==~ regex) {
            return sanitized
        } else {
            // fallback: use hash if not valid
            return prefix + Math.abs(baseName.hashCode())
        }
    }

    /**
     * Queries HarvesterResourceEvents for events related to a created PVC by name and namespace.
     * @param pvcName The name of the PVC resource.
     * @param namespace The namespace of the PVC resource.
     * @return ServiceResponse containing the events or error information.
     */
    private ServiceResponse queryPvcEvents(String pvcName, String namespace) {
        if (!this.computeServerGroup) {
            log.error "ComputeServerGroup is not initialized. Cannot query events."
            return ServiceResponse.error("ComputeServerGroup is not initialized.")
        }
        HarvesterResourceEvents resourceEvents = new HarvesterResourceEvents(this.computeServerGroup)
        // PVC kind is always 'PersistentVolumeClaim'
        ServiceResponse response = resourceEvents.fetchEventsByNamespace(namespace, pvcName, PVC_KIND)
        if (!response.success) {
            log.error "Failed to fetch events for PVC: ${pvcName} in namespace: ${namespace}. Error: ${response.msg}"
            return response
        }
        // validate items before checking
        def items = response.data?.items
        if (!items || !(items instanceof List)) {
            log.error "No event items found for PVC: ${pvcName} in namespace: ${namespace}."
            return ServiceResponse.error("No event items found for PVC: ${pvcName} in namespace: ${namespace}.")
        }

        List<String> errorReasons = []
        items.each { event ->
            if (event?.type != 'Normal') {
                String reason = event?.reason ?: 'Unknown reason'
                errorReasons << reason
                log.warn "${event.type} event for PVC: ${pvcName} in namespace: ${namespace}. Reason: ${reason}"
            }
        }
        if (errorReasons) {
            String errorMsg = "Warning events found for PVC: ${pvcName} in namespace: ${namespace}. " +
                    "Reasons: ${errorReasons.join(', ')}"
            return ServiceResponse.error(errorMsg)
        }

        // if there is no event indicating that there is an error return success
        return ServiceResponse.success()
    }

    /**
     * A disk created from a Harvester image normally lives on the image's own storage class (Harvester requires it).
     * When a different storage class was chosen for it (not "Auto"), the image is copied to that class instead.
     */
    static boolean needsImageCopy(StorageVolume volume, Map config, boolean fromHarvesterImage) {
        if (!fromHarvesterImage || !volume?.datastore?.name) {
            return false
        }
        def auto = volume.getConfigProperty(HarvesterConstants.CONFIG_AUTO_DATASTORE)
        if (auto == true || auto == 'true') {
            return false
        }
        String target = volume.datastore.name
        return target != config?.imageStorageClassName &&
                !target.startsWith(HarvesterConstants.IMAGE_STORAGE_CLASS_PREFIX)
    }

    /**
     * Creates a disk from a Harvester image on a storage class other than the image's:
     * 1. a temporary disk is created from the image on the image's storage class (the only way Harvester populates one)
     * 2. CDI copies it into a new DataVolume on the chosen storage class
     * 3. the temporary disk is deleted once the copy has finished
     * When the target class waits for the VM to start (WaitForFirstConsumer), the copy finishes later, so the
     * temporary disk is kept and recorded on the volume.
     */
    private ServiceResponse createVolumeFromImageCopy(StorageVolume volume, String project, Map createOpts, Map config) {
        this.harvesterApiClient = new HarvesterApiClient(this.computeServerGroup)
        String tempName = generateValidName(createOpts.name as String, 'img', 'tmp-')
        String targetClass = volume.datastore.name
        log.info "Copying image ${config.imageId} from storage class ${config.imageStorageClassName} to ${targetClass} " +
                "for volume ${volume.id} (temporary disk ${tempName})"

        // 1. temporary disk from the image, on the image's storage class
        Map tempParams = buildHarvesterCreatePvcParams([
                namespace       : project,
                name            : tempName,
                storageClassName: config.imageStorageClassName,
                storage         : createOpts.storage,
                annotations     : [(HarvesterConstants.ANNOTATION_IMAGE_ID): config.imageId],
                isClone         : false
        ])
        ServiceResponse tempResponse = executeHarvesterApiCall('post', getPvcApiResourcePath(project), tempParams)
        if (!tempResponse?.success) {
            return ServiceResponse.error("Unable to create a disk from image ${config.imageId}: ${tempResponse?.msg}", null, volume)
        }
        ServiceResponse tempReady = waitForResourceStatus(getPvcApiResourcePath(project, tempName), tempName, project,
                ['Bound'], ['Lost'], HarvesterConstants.IMAGE_COPY_WAIT_RETRIES, 5000)
        if (!tempReady?.success) {
            deleteTemporaryPvc(project, tempName)
            return ServiceResponse.error("Disk from image ${config.imageId} did not become ready: ${tempReady?.msg}", null, volume)
        }

        // 2. CDI copy into a DataVolume on the chosen storage class
        Map copyOpts = new LinkedHashMap(createOpts)
        copyOpts.remove('annotations')
        copyOpts.storageClassName = targetClass
        copyOpts.isClone = true
        copyOpts.sourceVolumeName = tempName
        copyOpts.sourcePvcNamespace = project
        copyOpts.waitRetries = HarvesterConstants.IMAGE_COPY_WAIT_RETRIES
        copyOpts.name = (createOpts.name as String)?.replaceFirst(/^pvc-/, 'dv-')
        ServiceResponse copyResponse = createHarvesterDataVolume(volume, project, copyOpts)
        if (!copyResponse?.success) {
            deleteTemporaryPvc(project, tempName)
            return ServiceResponse.error("Copying image ${config.imageId} to storage class ${targetClass} failed: ${copyResponse?.msg}", null, volume)
        }

        // 3. clean up once the copy is done
        ServiceResponse dvState = harvesterApiClient.get(getDataVolumeApiResourcePath(project, copyOpts.name as String))
        if (dvState?.success && dvState.data?.status?.phase == 'Succeeded') {
            deleteTemporaryPvc(project, tempName)
        } else {
            log.info "Copy to ${targetClass} finishes when the VM starts; keeping temporary disk ${project}/${tempName}"
            volume.setConfigProperty(HarvesterConstants.CONFIG_IMAGE_COPY_SOURCE, "${project}/${tempName}".toString())
            morpheusContext.services.storage.volume.save(volume)
        }
        return ServiceResponse.success(volume)
    }

    private void deleteTemporaryPvc(String namespace, String name) {
        try {
            ServiceResponse response = executeHarvesterApiCall('delete', getPvcApiResourcePath(namespace, name), null)
            if (!response?.success && response?.errorCode != '404') {
                log.warn "Unable to delete temporary disk ${namespace}/${name}: ${response?.msg}"
            }
        } catch (Exception e) {
            log.warn "Unable to delete temporary disk ${namespace}/${name}: ${e.message}"
        }
    }

    // create harvester data volume
    private ServiceResponse createHarvesterDataVolume(StorageVolume volume, String project, Map opts) {

        log.info "creating data volume with opts: ${opts}"
        // prepare request body here
        Map params = buildHarvesterDataVolumeCreateParams(opts)
        log.info "Creating datavolume with params: ${params} for name: ${volume.name}"

        this.harvesterApiClient = new HarvesterApiClient(this.computeServerGroup)
        // prepare apiPath and make the API call to create the PVC
        String apiPath = getDataVolumeApiResourcePath(project)

        // Dry run to check if the request is valid
        log.info "Making API call to create datavolume with dryRun at path: ${apiPath}"
        try {
            ServiceResponse dryRunResponse = executeHarvesterApiCall('post', apiPath, params, [dryRun: 'All'])
            if (!dryRunResponse.success) {
                log.error "Dry run for data volume creation failed: ${dryRunResponse.msg}, API Path: ${apiPath}"
                return ServiceResponse.error(dryRunResponse.msg ?: "Data volume dry run validation failed",
                        null, volume)
            }
            log.info "Dry run for data volume creation succeeded."
        } catch (Exception ex) {
            log.error("Exception during data volume dry run: ${ex.message}", ex)
            return ServiceResponse.error("Exception during data volume dry run: ${ex.message}")
        }

        // make the rest call to create the data volume
        log.info "Making API call to create datavolume at path: ${apiPath}"
        try {
            ServiceResponse apiResponse = executeHarvesterApiCall('post', apiPath, params)
            // if REST call is not successful, return the response
            if (!apiResponse.success) {
                log.error "Failed to create data volume: ${apiResponse.msg}, API Path: ${apiPath}"
                return ServiceResponse.error(apiResponse.msg ?: Message.STORAGE_VOLUME_CREATE_FAILED,
                        null, volume)
            }
            // need to track whether the volume is actually created or not
            log.info "Successfully initiated data volume for volume: ${volume.id}"
            String createdResourceName = apiResponse.data?.metadata?.name
            String createdResourceNamespace = apiResponse.data?.metadata?.namespace
            String apiResourcePath = getDataVolumeApiResourcePath(createdResourceNamespace, createdResourceName)
            // wait for the resource to be in proper state before returning
            log.info "Waiting for data volume to be in acceptable state: ${createdResourceName} in namespace: " +
                    "${createdResourceNamespace}"

            // Determine acceptable states based on StorageClass binding mode
            List<String> acceptableDataVolumeStates = new ArrayList<>(ACCEPTABLE_DATA_VOLUME_STATES)

            // If StorageClass uses WaitForFirstConsumer, Pending is acceptable until Pod binding
            if (isWaitForFirstConsumerMode(volume.datastore)) {
                acceptableDataVolumeStates.addAll(DV_PENDING_STATE)
                log.info("Acceptable states: ${acceptableDataVolumeStates}")
                log.info "StorageClass '${volume.datastore.name}' uses WaitForFirstConsumer - " +
                        "accepting Pending state for DataVolume '${createdResourceName}'"
            }

            ServiceResponse resourceResponse = waitForResourceStatus(
                    apiResourcePath, createdResourceName, createdResourceNamespace,
                    acceptableDataVolumeStates, FAILURE_DATA_VOLUME_STATES,
                    (opts.waitRetries ?: 100) as int, 5000
            )
            // check status here
            if (!resourceResponse.success) {
                log.error "Failed to create dv: ${resourceResponse.msg}, API Path: ${apiPath}"
                return ServiceResponse.error(resourceResponse.msg ?: Message.STORAGE_VOLUME_CREATE_FAILED,
                        null, volume)
            }

            log.info "Data volume is in acceptable state: ${createdResourceName} in namespace: " +
                    "${createdResourceNamespace}"

            // Create and save a new storage volume object with DataVolume properties
            def newVolume = createAndSaveNewStorageVolume(resourceResponse.data as Map, createdResourceName, createdResourceNamespace, params,
                    volume, HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME)
            log.info "Updating the existing volume now"
            // overwrite the volume's name here (otherwise sync will fail)
            volume.claimName = createdResourceName
            volume.name = createdResourceName
            // set the request body used to create this dv, will be needed in VM create
            volume.setConfigProperty("body", params)
            volume.volumeType = HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME
            ComputeServer dbServer = morpheusContext.services.computeServer.get(opts.serverId as Long)
            volume.category = DBUtils.buildVMStorageVolumeCategory(dbServer, volume.type, this.computeServerGroup.id)
            //volume.category = "${HarvesterConstants.CATEGORY_DATAVOLUME_CLUSTER_PREFIX}${this.computeServerGroup.id}.vm.${opts.serverId}".toString()
            volume.sourceId = newVolume.id
            volume.externalId = newVolume.id
            volume.poolName = createdResourceNamespace
            // Generate and store disk serial number for this volume
            // Serial numbers are used to uniquely identify disks in VMs, especially with virtio drivers
            // where SCSI paths are not available
            // Uses volume UID from Harvester (metadata.uid) for true uniqueness across the cluster
            try {
                String volumeUid = resourceResponse.data?.metadata?.uid // Get UID directly from Harvester response
                String diskSerial = ProvisionHelper.generateSerialNumber(volumeUid)
                log.info "Generated serial number for volume ${volume.name} (ID: ${volume.id}, UID: ${volumeUid}): ${diskSerial}"
                volume.internalId = diskSerial
                log.info "Stored serial number in volumes internal id for volume ${volume.name} : ${diskSerial}"
                volume.setConfigProperty("serial", diskSerial)
            } catch (Exception e) {
                log.error "Error generating serial number for volume ${volume.name}: ${e.message}", e
                // Don't fail the volume creation if serial generation fails
            }
            log.info "Updating existing volume sourceId to newVolume Id ${newVolume.id}"
            morpheusContext.services.storage.volume.save(volume)

        } catch (Exception ex) {
            log.error("Exception while creating harvester data volume : ${ex.message}", ex)
            return ServiceResponse.error("Exception while creating harvester data volume: ${ex.message}")
        }

        return ServiceResponse.success(volume)
    }

    // execute harvester api call here
    private ServiceResponse executeHarvesterApiCall(String method, String apiPath, Map params, Map queryParams = null) {

        ServiceResponse response = null
        try {
            // make the actual PUT/POST/DELETE api call here
            if (queryParams) {
                response = this.harvesterApiClient."$method"(apiPath, params, queryParams)
            } else {
                response = this.harvesterApiClient."$method"(apiPath, params)
            }

            if (!response.success) {
                log.error "Failed to make API call: ${response.msg}, API: ${apiPath}, Method: ${method}" +
                        "response status code : ${response.statusCode}"
                return response
            }

            log.info "API call successful: ${apiPath}, Method: ${method}, response status code : ${response.statusCode}"
        } catch (Exception e) {
            log.error "Exception while initiating API call: ${e.message}"
            return ServiceResponse.error("Exception while initiating API call: ${e.message}")
        }

        return response
    }

    // wait for resource status generic method
    private ServiceResponse waitForResourceStatus(
            String getResourcePath, String createdResourceName,
            String createdResourceNamespace, List<String> acceptableStates, List<String> failureStates,
            int maxRetries = 100, int waitInterval = 5000) {

        int retries = 0
        while (retries < maxRetries) {
            // make gets here to check if the resource is in acceptable state
            try {
                ServiceResponse getResourceResponse = harvesterApiClient.get(getResourcePath)
                // if this returned a failure then
                if (!getResourceResponse.success) {
                    log.error "GET call to check resource status returned failure: ${getResourceResponse.msg}"
                    log.info "Continuing with retires ${retries}"
                } else {
                    String resourceStatus = getResourceResponse.data?.status?.phase ?: 'Unknown'
                    log.info "Resource status: ${resourceStatus}"
                    // check if the resource is in a "successful" states
                    if (acceptableStates.contains(resourceStatus)) {
                        log.info "Resource is available: ${createdResourceName} in namespace: " +
                                "${createdResourceNamespace}, resource status : ${resourceStatus}"
                        return getResourceResponse
                    } else if (failureStates.contains(resourceStatus)) {
                        // here the resource is in any "failure" state, no recover here
                        log.error "Resource status ${resourceStatus}, underlying pv has a failure"
                        return ServiceResponse.error(
                                "Resource status ${resourceStatus}, underlying pv has a failure")
                    } else {
                        log.info "Resource not yet available, current status: ${resourceStatus}, retrying..."
                    }
                    // TODO: if resourceStatus is "Pending" for too long then do a get on events and check what is wrong

//                    if (resourceStatus == pvcResourceStatusPending && retries > 10) {
//                        // resource is in pending state for too long, get the events for the resource
//                        log.info "Resource in Pending state for too long, checking events for resource: " +
//                                "${createdResourceName} in namespace: ${createdResourceNamespace}"
//                        // query events and return response appropriately.
//                        ServiceResponse queryEventsResponse = queryPvcEvents(
//                                createdResourceName, createdResourceNamespace)
//                        if (!queryEventsResponse.success) {
//                            log.error "Events indicate a problem with the resource: ${queryEventsResponse.msg}"
//                            return queryEventsResponse
//                        }
//                    }
                }
            } catch (Exception ex) {
                log.error "Exception while making GET call to check resource status: ${ex.message}"
            }
            // sleep for the wait interval before retrying
            sleep(waitInterval)
            retries++
        }
        // return error here since the retires are exceeded
        log.error "Resource did not reach acceptable state after ${maxRetries} retries"
        return ServiceResponse.error("Resource did not reach acceptable state after ${maxRetries} retries")
    }

    /**
     * Check if the datastore (StorageClass) uses WaitForFirstConsumer binding mode
     * When this mode is enabled, PVCs will remain in Pending state until bound to a Pod
     * @param datastore The datastore to check
     * @return true if WaitForFirstConsumer mode, false otherwise
     */
    private boolean isWaitForFirstConsumerMode(def datastore) {
        if (!datastore) {
            return false
        }

        try {
            def configMap = datastore.getConfigMap()
            String bindingMode = configMap?.volumeBindingMode
            boolean isWaitMode = bindingMode == 'WaitForFirstConsumer'

            if (isWaitMode) {
                log.info "StorageClass '${datastore.name}' uses WaitForFirstConsumer binding mode - " +
                        "Pending state is acceptable for PVC/DV creation"
            }

            return isWaitMode
        } catch (Exception e) {
            log.warn "Failed to check volumeBindingMode for datastore '${datastore?.name}': ${e.message}"
            return false
        }
    }

    // create harvester pvc volume
    private ServiceResponse createHarvesterPvcVolume(StorageVolume volume, String project, Map opts) {

        log.info "creating pvc volume with opts: ${opts}"
        
        // Resolve DataSource to underlying PVC if specified
        if (opts.sourceDatasourceName){
            resolveDataSourceToPvc(opts)
        }
        // prepare request body here
        Map params = buildHarvesterCreatePvcParams(opts)
        log.info "Creating pvc with params: ${params} for name: ${volume.name}"

        this.harvesterApiClient = new HarvesterApiClient(this.computeServerGroup)
        // prepare apiPath and make the API call to create the PVC
        String apiPath = getPvcApiResourcePath(project)

        // Dry run to check if the request is valid
        log.info "Making API call to create pvc with dryRun at path: ${apiPath}"
        try {
            ServiceResponse dryRunResponse = executeHarvesterApiCall('post', apiPath, params, [dryRun: 'All'])
            if (!dryRunResponse.success) {
                log.error "Dry run for pvc creation failed: ${dryRunResponse.msg}, API Path: ${apiPath}"
                return ServiceResponse.error(dryRunResponse.msg ?: "PVC dry run validation failed",
                        null, volume)
            }
            log.info "Dry run for pvc creation succeeded."
        } catch (Exception ex) {
            log.error("Exception during pvc dry run: ${ex.message}", ex)
            return ServiceResponse.error("Exception during pvc dry run: ${ex.message}")
        }

        // make the rest call to create the pvc
        log.info "Making API call to create pvc at path: ${apiPath}"
        try {
            ServiceResponse apiResponse = executeHarvesterApiCall('post', apiPath, params)
            // if REST call is not successful, return the response
            if (!apiResponse.success) {
                log.error "Failed to create pvc: ${apiResponse.msg}, API Path: ${apiPath}"
                return ServiceResponse.error(apiResponse.msg ?: Message.STORAGE_VOLUME_CREATE_FAILED,
                        null, volume)
            }
            // need to track whether the volume is actually created or not
            log.info "Successfully initiated pvc for volume: ${volume.id}"
            String createdResourceName = apiResponse.data?.metadata?.name
            String createdResourceNamespace = apiResponse.data?.metadata?.namespace
            String apiResourcePath = getPvcApiResourcePath(createdResourceNamespace, createdResourceName)
            // wait for the resource to be in proper status before returning
            log.info "Waiting for pvc to be in acceptable state: ${createdResourceName} in namespace: " +
                    "${createdResourceNamespace}"

            // Determine acceptable states based on StorageClass binding mode
            List<String> acceptablePvcStates = [PVC_RESOURCE_STATUS_BOUND]

            // If StorageClass uses WaitForFirstConsumer, Pending is acceptable until Pod binding
            if (isWaitForFirstConsumerMode(volume.datastore)) {
                acceptablePvcStates << PVC_RESOURCE_STATUS_PENDING
                log.info "StorageClass '${volume.datastore.name}' uses WaitForFirstConsumer - " +
                        "accepting Pending state for PVC '${createdResourceName}'"
            }

            ServiceResponse resourceResponse = waitForResourceStatus(
                    apiResourcePath, createdResourceName, createdResourceNamespace,
                    acceptablePvcStates, [PVC_RESOURCE_STATUS_LOST],
                    100, 5000
            )
            // check status here
            if (!resourceResponse.success) {
                log.error "Failed to create pvc: ${resourceResponse.msg}, API Path: ${apiPath}"
                return ServiceResponse.error(resourceResponse.msg ?: Message.STORAGE_VOLUME_CREATE_FAILED,
                        null, volume)
            }

            log.info "PVC is in acceptable state: ${createdResourceName} in namespace: " +
                    "${createdResourceNamespace}"
            // Create and save a new storage volume object with DataVolume properties
            def newVolume = createAndSaveNewStorageVolume(resourceResponse.data as Map, createdResourceName, createdResourceNamespace, params,
                    volume, HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC)
            log.info "Updating the existing volume now"
            // overwrite the volume's name here (otherwise sync will fail)
            volume.claimName = createdResourceName
            volume.name = createdResourceName
            // set the request body used to create this pvc, will be needed in VM create
            volume.volumeType = HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC
            ComputeServer dbServer = morpheusContext.services.computeServer.get(opts.serverId as Long)
            volume.category = DBUtils.buildVMStorageVolumeCategory(dbServer, volume.type, this.computeServerGroup.id)
            //volume.category = "${HarvesterConstants.CATEGORY_PVC_CLUSTER_PREFIX}${this.computeServerGroup.id}.vm.${opts.serverId}".toString()
            volume.setConfigProperty("body", params)
            volume.sourceId = newVolume.id
            volume.externalId = newVolume.id
            volume.poolName = createdResourceNamespace
            // Generate and store disk serial number for this volume
            // Serial numbers are used to uniquely identify disks in VMs, especially with virtio drivers
            // where SCSI paths are not available
            // Uses volume UID from Harvester (metadata.uid) for true uniqueness across the cluster
            try {
                String volumeUid = resourceResponse.data?.metadata?.uid // Get UID directly from Harvester response
                String diskSerial = ProvisionHelper.generateSerialNumber(volumeUid)
                log.info "Generated serial number for volume ${volume.name} (ID: ${volume.id}, UID: ${volumeUid}): ${diskSerial}"
                volume.internalId = diskSerial
                log.info "Stored serial number in volumes internal id for volume ${volume.name} : ${diskSerial}"
                volume.setConfigProperty("serial", diskSerial)
            } catch (Exception e) {
                log.error "Error generating serial number for volume ${volume.name}: ${e.message}", e
                // Don't fail the volume creation if serial generation fails
            }
            log.info "Updating existing volume sourceId to newVolume Id ${newVolume.id}"
            morpheusContext.services.storage.volume.save(volume)

        } catch (Exception ex) {
            log.error("Exception while creating harvester pvc : ${ex.message}", ex)
            return ServiceResponse.error("Exception while creating harvester pvc: ${ex.message}")
        }

        return ServiceResponse.success(volume)
    }

    /**
     * Resizes a volume (DataVolume or PVC) based on the volume type
     * @param namespace the namespace of the volume
     * @param volumeType the type of volume ('harvesterDataVolume' or 'harvesterPVC')
     * @param volumeName the name of the volume
     * @param newSizeBytes the new size in bytes
     * @param volumeId the ID of the StorageVolume to update in the database (optional)
     * @return ServiceResponse indicating success or failure
     */
    ServiceResponse resizeVolume(String namespace, String volumeType, String volumeName, Long newSizeBytes, Long volumeId = null) {
        log.info "Resizing volume ${volumeName} (type: ${volumeType}) in namespace ${namespace}"

        if (!volumeType || !volumeName || !newSizeBytes) {
            log.warn "Skipping volume resize due to missing information - type: ${volumeType}, name: ${volumeName}, size: ${newSizeBytes}"
            return ServiceResponse.error("Missing required information for volume resize")
        }

        // Convert size to Gi for Harvester
        String newSizeInGi = DataUnitConverterUtil.dataUnitConverterBigDecimal(
            newSizeBytes as BigDecimal, DataUnitConverterUtil.DataUnit.B,
            DataUnitConverterUtil.DataUnit.GiB).toString() + "Gi"

        log.info "Updating volume ${volumeName} to size: ${newSizeInGi}"

        ServiceResponse resizeResult
        switch (volumeType) {
            case HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME:
                resizeResult = resizeDataVolume(namespace, volumeName, newSizeInGi)
                break
            case HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC:
                resizeResult = resizePvc(namespace, volumeName, newSizeInGi)
                break
            default:
                log.error "Unsupported volume type for resize: ${volumeType}"
                return ServiceResponse.error("Unsupported volume type for resize: ${volumeType}")
        }

        // Update the StorageVolume in the database if resize was successful, core does
        // not do this for updates yet
        if (resizeResult.success && volumeId && newSizeBytes) {
            try {
                StorageVolume storageVolume = morpheusContext.services.storage.volume.get(volumeId)
                if (storageVolume) {
                    storageVolume.maxStorage = newSizeBytes
                    morpheusContext.services.storage.volume.save(storageVolume)
                    log.info("Updated StorageVolume ${storageVolume.id} maxStorage to ${newSizeBytes} bytes")
                } else {
                    log.warn("Could not find StorageVolume with id ${volumeId} to update")
                }
            } catch (Exception e) {
                log.error("Error updating StorageVolume maxStorage: ${e.message}", e)
                // Don't fail the entire operation if just the database update fails
            }
        }

        return resizeResult
    }

    /**
     * Resizes a DataVolume by resizing its underlying PVC
     * Note: DataVolume spec resize is not supported, so we only resize the underlying PVC
     * @param namespace the namespace of the DataVolume
     * @param volumeName the name of the DataVolume (same as the PVC name)
     * @param newSize the new size in Gi format (e.g., "10Gi")
     * @return ServiceResponse indicating success or failure
     */
    private ServiceResponse resizeDataVolume(String namespace, String volumeName, String newSize) {
        log.info "Resizing DataVolume ${volumeName} by resizing its underlying PVC in namespace ${namespace} to ${newSize}"

        // For DataVolume, resize the underlying PVC directly
        return resizePvc(namespace, volumeName, newSize)
    }

    /**
     * Resizes a PVC by updating its storage size
     * @param namespace the namespace of the PVC
     * @param volumeName the name of the PVC
     * @param newSize the new size in Gi format (e.g., "10Gi")
     * @return ServiceResponse indicating success or failure
     */
    private ServiceResponse resizePvc(String namespace, String volumeName, String newSize) {
        log.info "Resizing PVC ${volumeName} in namespace ${namespace} to ${newSize}"

        try {
            String pvcApiPath = getPvcApiResourcePath(namespace, volumeName)

            // Build patch operation
            List patchOperations = [
                [
                    op: 'replace',
                    path: '/spec/resources/requests/storage',
                    value: newSize
                ]
            ]

            String patchBodyJson = JsonOutput.toJson(patchOperations)
            // Perform dry-run validation
            log.info "Performing dry-run for PVC resize with patch: ${patchBodyJson}"
            ServiceResponse dryRunResponse = this.harvesterApiClient.patch(
                pvcApiPath, patchBodyJson, 'application/json-patch+json', ['dryRun': 'All'])

            if (!dryRunResponse.success) {
                log.error "PVC resize dry-run validation failed: ${dryRunResponse.msg}"
                return ServiceResponse.error("PVC resize validation failed: ${dryRunResponse.msg}")
            }

            log.info "Dry-run successful - proceeding with actual PVC resize"

            // Apply the actual patch
            ServiceResponse patchResponse = this.harvesterApiClient.patch(
                pvcApiPath, patchBodyJson, 'application/json-patch+json')

            if (!patchResponse.success) {
                log.error "Failed to resize PVC: ${patchResponse.msg}"
                return ServiceResponse.error("PVC resize failed: ${patchResponse.msg}")
            }

            log.info "Successfully resized PVC ${volumeName}"
            return ServiceResponse.success()

        } catch (Exception e) {
            log.error "Error resizing PVC ${volumeName}: ${e.message}", e
            return ServiceResponse.error("Error resizing PVC: ${e.message}")
        }
    }

    /**
     * Updates a storage volume with controller details parsed from a mount point string.
     * @param volume The StorageVolume to update
     * @param controllerMountPoint The mount point string in format "controllerId:busNumber:controllerType:unitNumber"
     * @return ServiceResponse indicating success or failure
     */
    ServiceResponse updateVolumeController(StorageVolume volume, String controllerMountPoint) {
        log.info "Updating volume ${volume?.name} with controller mount point: ${controllerMountPoint}"

        if (!volume) {
            return ServiceResponse.error("Volume is required")
        }

        if (!controllerMountPoint) {
            return ServiceResponse.error("Controller mount point is required")
        }

        try {
            // Format: "controllerId:busNumber:controllerType:unitNumber"
            // Example: "389:0:13:0"
            String[] tokens = controllerMountPoint.split(":")
            if (tokens.length < 4) {
                return ServiceResponse.error("Invalid controllerMountPoint format. " +
                        "Expected 'controllerId:busNumber:controllerType:unitNumber'")
            }

            Long controllerId = tokens[0].toLong()
            // tokens[1] is busNumber (unused for now)
            // tokens[2] is controllerType (unused for now as we fetch controller by ID)
            String unitNumber = tokens[3]

            // Fetch the controller
            StorageController controller = morpheusContext.services.storage.controller.get(controllerId)
            if (!controller) {
                return ServiceResponse.error("Storage controller not found with id: ${controllerId}")
            }

            // Update the volume
            volume.controller = controller
            volume.unitNumber = unitNumber

            // Save the volume
            morpheusContext.services.storage.volume.save(volume)
            log.info "Successfully updated volume ${volume.name} with controller " +
                    "${controller.name} and unit number ${unitNumber}"

            return ServiceResponse.success(volume)
        } catch (Exception e) {
            log.error("Error updating volume controller: ${e.message}", e)
            return ServiceResponse.error("Error updating volume controller: ${e.message}")
        }
    }

    /**
     * Creates a new storage volume object and saves it with the provided properties
     * @param originalVolume the original StorageVolume to copy essential properties from
     * @param resourceResponse the response from creating the Harvester resource
     * @param createdResourceName the name of the created resource
     * @param createdResourceNamespace the namespace of the created resource
     * @param params the request body parameters used to create the resource
     * @param volumeType the volume type (e.g., VOLUME_TYPE_HARVESTER_PVC or VOLUME_TYPE_HARVESTER_DATAVOLUME)
     * @return the newly created and saved StorageVolume
     */
    StorageVolume createAndSaveNewStorageVolume(Map resourceResponse, String createdResourceName, String createdResourceNamespace, Map params, StorageVolume volume, String volumeTypeCode) {
        log.info "Creating new storage volume object for resource: ${createdResourceName} with volumeTypeCode: ${volumeTypeCode}"
        // Create a new StorageVolume and copy all properties from the original volume
        StorageVolume newVolume = new StorageVolume()
        newVolume.externalId = resourceResponse?.metadata?.uid
        newVolume.uniqueId = resourceResponse?.metadata?.uid
        newVolume.name = createdResourceName
        newVolume.claimName = createdResourceName
        newVolume.poolName = createdResourceNamespace
        newVolume.type = volume.type
        newVolume.category = DBUtils.buildStorageVolumeCategory(volume.type, this.computeServerGroup.id)
        newVolume.volumeType = volume.type.code
        newVolume.maxStorage = volume.maxStorage
        newVolume.account = volume.account
        newVolume.datastore = volume.datastore
        newVolume.datastoreOption = volume.datastoreOption
        newVolume.rootVolume = volume.rootVolume
        newVolume.refType = HarvesterConstants.COMPUTE_SERVER_GROUP
        newVolume.refId = this.computeServerGroup.id
        newVolume.cloudId = volume.cloudId
        newVolume.internalId = "${params?.spec?.pvc?.storageClassName}:${createdResourceNamespace}:${createdResourceName}"
        newVolume.setConfigProperty("body", params)
        // Save the new volume to the database
        StorageVolume existingVol = morpheusContext.services.storage.volume.find(new DataQuery().withFilters(
                new DataFilter('name', createdResourceName),
                new DataFilter('volumeType', volume.type.code),
                new DataFilter('externalId', newVolume.externalId),
                new DataFilter('category', newVolume.category),
        ))
        if (existingVol) {
            log.info("Harvester: volume with name: ${createdResourceName}(${existingVol.id}) and" +
                    " externalId: ${newVolume.externalId}, category: ${newVolume.category} alreday exists," +
                     "skipping new volume creation")
            newVolume = existingVol
        }
        else{
            newVolume = morpheusContext.services.storage.volume.create(newVolume)
            log.info "Created and saved new storage volume with id: ${newVolume.id}"
        }

        return newVolume
    }

    /**
     * Resolves a DataSource reference to the underlying PVC if possible
     */
    private void resolveDataSourceToPvc(Map opts) {
        if (!opts.sourceDatasourceName) {
            return
        }
        
        log.info "DataSource specified: ${opts.sourceDatasourceName}, attempting to fetch DataSource details"
        
        String datasourceNamespace = opts.sourceDatasourceNamespace ?: opts.namespace
        ServiceResponse datasourceResponse = fetchDataSource(opts.sourceDatasourceName as String, datasourceNamespace)
        
        if (!datasourceResponse.success) {
            log.warn "Failed to fetch DataSource ${opts.sourceDatasourceName}: ${datasourceResponse.msg}. " +
                    "Continuing with dataSourceRef in PVC spec."
            return
        }
        
        // Extract source from DataSource - can be either pvc, snapshot, or another dataSource
        def sourceSpec = datasourceResponse.data?.spec?.source
        
        if (sourceSpec?.pvc) {
            handlePvcSource(opts, sourceSpec, datasourceNamespace)
        } else if (sourceSpec?.dataSource) {
            handleNestedDataSource(opts, sourceSpec, datasourceNamespace)
        } else if (sourceSpec?.snapshot) {
            log.info "DataSource references VolumeSnapshot: ${sourceSpec.snapshot.name}. " +
                    "Continuing with dataSourceRef in PVC spec."
        } else {
            log.warn "DataSource ${opts.sourceDatasourceName} does not have a recognized source type " +
                    "(pvc, dataSource, or snapshot). Continuing with dataSourceRef in PVC spec."
        }
    }

    /**
     * Fetches a DataSource from the KubeVirt CDI API.
     * @param datasourceName The name of the DataSource
     * @param datasourceNamespace The namespace of the DataSource
     * @return ServiceResponse with the DataSource data or error
     */
    private ServiceResponse fetchDataSource(String datasourceName, String datasourceNamespace) {
        this.harvesterApiClient = new HarvesterApiClient(this.computeServerGroup)
        String datasourceApiPath = "/apis/cdi.kubevirt.io/v1beta1/namespaces/${datasourceNamespace}/datasources/${datasourceName}"
        
        try {
            return this.harvesterApiClient.get(datasourceApiPath)
        } catch (Exception ex) {
            log.warn "Exception while fetching DataSource: ${ex.message}", ex
            return ServiceResponse.error("Exception while fetching DataSource: ${ex.message}")
        }
    }

    /**
     * Handles a DataSource that directly references a PVC.
     * Extracts the PVC name and namespace, updating opts for direct cloning.
     */
    private void handlePvcSource(Map opts, def sourceSpec, String datasourceNamespace) {
        String sourcePvcName = sourceSpec.pvc.name
        String sourcePvcNamespace = sourceSpec.pvc.namespace ?: datasourceNamespace
        
        log.info "DataSource references PVC: ${sourcePvcName} in namespace: ${sourcePvcNamespace}"
        
        // Update opts to use the extracted PVC for cloning
        if (sourcePvcName){
            opts.sourceVolumeName = sourcePvcName
            opts.sourcePvcNamespace = sourcePvcNamespace
        }
    }

    /**
     * Handles a DataSource that references another DataSource (nested reference).
     * Attempts to fetch the nested DataSource and extract the underlying PVC.
     * Maximum reference depth is 1 according to KubeVirt CDI spec.
     */
    private void handleNestedDataSource(Map opts, def sourceSpec, String datasourceNamespace) {
        String nestedDsName = sourceSpec.dataSource.name
        String nestedDsNamespace = sourceSpec.dataSource.namespace ?: datasourceNamespace
        
        log.info "DataSource references another DataSource: ${nestedDsName} in namespace: ${nestedDsNamespace}"
        log.info "Attempting to fetch nested DataSource to extract PVC (max depth: 1)"
        
        // Fetch the nested DataSource using the reusable method
        ServiceResponse nestedDsResponse = fetchDataSource(nestedDsName, nestedDsNamespace)
        
        if (!nestedDsResponse.success) {
            log.warn "Failed to fetch nested DataSource ${nestedDsName}: ${nestedDsResponse.msg}. " +
                    "Using dataSourceRef to reference parent DataSource."
            return
        }
        
        def nestedSourceSpec = nestedDsResponse.data?.spec?.source
        
        if (nestedSourceSpec?.pvc) {
            // Reuse handlePvcSource for PVC extraction logic
            handlePvcSource(opts, nestedSourceSpec, nestedDsNamespace)
        } else {
            // Nested DataSource doesn't reference a PVC - depth limit reached
            log.warn "Nested DataSource ${nestedDsName} does not reference a PVC. "
        }
    }


    /**
     * Set the compute server group for this instance
     * @param serverGroup the ComputeServerGroup to set
     */
    void setComputeServerGroup(ComputeServerGroup serverGroup) {
        this.computeServerGroup = serverGroup
    }

    /**
     * Set the Harvester API client for this instance
     * @param apiClient the HarvesterApiClient to set
     */
    void setHarvesterApiClient(HarvesterApiClient apiClient) {
        this.harvesterApiClient = apiClient
    }
}
