package com.hpe.morpheus.harvester.plugin.apihelper

import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class StorageClassApiHelper {

    // Instance fields for configuration
    private ComputeServerGroup serverGroup
    private HarvesterApiClient apiClient

    /**
     * Create instance with ComputeServerGroup credentials
     * @param serverGroup ComputeServerGroup containing credentials
     */
    StorageClassApiHelper(ComputeServerGroup serverGroup = null) {
        this.serverGroup = serverGroup
        this.apiClient = new HarvesterApiClient(serverGroup)
        log.debug "Initialized StorageClassApiHelper with authentication"
    }

    /**
     * Get storage classes from Harvester API with proper authentication
     * @return ServiceResponse containing storage classes
     */
    static boolean isImageStorageClass(def item) {
        // works on raw API items (metadata.name) and on the parsed maps (name)
        String name = (item?.metadata?.name ?: item?.name)?.toString()
        return item?.parameters?.backingImage || name?.startsWith(HarvesterConstants.IMAGE_STORAGE_CLASS_PREFIX)
    }

    /**
     * Storage provisioners Harvester can put VM disks on: Longhorn, plus the third-party CSI drivers registered in
     * Harvester's csi-driver-config setting (Advanced > Settings > csi-driver-config). Falls back to Longhorn only
     * when the setting can't be read. Never returns null.
     */
    Set<String> getVmStorageProvisioners() {
        Set<String> provisioners = [HarvesterConstants.LONGHORN_PROVISIONER] as Set
        try {
            def response = apiClient.get(HarvesterConstants.HARVESTER_SETTING_CSI_DRIVER_CONFIG)
            if (response?.success && response.data instanceof Map) {
                String json = response.data.value ?: response.data.default
                if (json) {
                    def config = new groovy.json.JsonSlurper().parseText(json.toString())
                    if (config instanceof Map) {
                        provisioners.addAll(config.keySet().collect { it.toString() })
                    }
                }
            }
        } catch (Exception e) {
            log.warn "Unable to read Harvester csi-driver-config, using Longhorn storage classes only: ${e.message}"
        }
        return provisioners
    }

    /**
     * @return true when VMs can be deployed to this storage class: its provisioner is one Harvester supports for VM
     *         disks, and it isn't a per-image class or Longhorn's static-provisioning class
     */
    static boolean isVmStorageClass(Map storageClass, Collection<String> vmProvisioners) {
        if (!storageClass || isImageStorageClass(storageClass)) {
            return false
        }
        if (storageClass.name in HarvesterConstants.NON_VM_STORAGE_CLASS_NAMES) {
            return false
        }
        return storageClass.provisioner in vmProvisioners
    }

    ServiceResponse getStorageClasses() {
        def response = apiClient.get('/apis/storage.k8s.io/v1/storageclasses')

        if (response.success && response.data) {
            def responseData = response.data
            // Skip Harvester's per-image storage classes (longhorn-image-*): they only exist to
            // populate volumes from a VM image and are not general-purpose datastores
            def storageClasses = responseData.items?.findAll { item ->
                !isImageStorageClass(item)
            }?.collect { item ->
                [
                        name: item.metadata?.name,
                        provisioner: item.provisioner,
                        parameters: item.parameters ?: [:],
                        reclaimPolicy: item.reclaimPolicy,
                        allowVolumeExpansion: item.allowVolumeExpansion ?: false,
                        volumeBindingMode: item.volumeBindingMode,
                        uid: item.metadata?.uid,
                        creationTimestamp: item.metadata?.creationTimestamp,
                        // Extract default storage class annotations for auto-datastore resolution
                        annotations: item.metadata?.annotations ?: [:],
                        isDefaultClass: item.metadata?.annotations
                                ?.get(HarvesterConstants.ANNOTATION_K8S_DEFAULT_CLASS) == 'true',
                        isDefaultVirtClass: item.metadata?.annotations
                                ?.get(HarvesterConstants.ANNOTATION_KUBEVIRT_DEFAULT_VIRT_CLASS) == 'true',
                ]
            } ?: []

            log.debug("Fetched ${storageClasses.size()} storage classes from Harvester")
            return new ServiceResponse(success: true, data: [storageClasses: storageClasses])
        }

        return response
    }

    
    // Get the default storage class for the cluster.Auto resolve to virt-default if available, otherwise fallback to standard k8s default
    ServiceResponse getDefaultStorageClass() {
        def response = getStorageClasses()
        if (!response.success) return response

        def classes = response.data?.storageClasses ?: []
        log.info "Searching for default storage class among ${classes.size()} classes"

        // Priority 1: KubeVirt virt-default
        def virtDefault = classes.find { it.isDefaultVirtClass }
        if (virtDefault) {
            log.info "Found KubeVirt virt-default storage class: ${virtDefault.name} " +
                    "(annotation: ${HarvesterConstants.ANNOTATION_KUBEVIRT_DEFAULT_VIRT_CLASS})"
            return new ServiceResponse(success: true, data: [
                    storageClass: virtDefault,
                    source: 'kubevirt-virt-default'
            ])
        }

        // Priority 2: K8s standard default (fallback)
        def k8sDefault = classes.find { it.isDefaultClass }
        if (k8sDefault) {
            log.info "Found K8s default storage class: ${k8sDefault.name} " +
                    "(annotation: ${HarvesterConstants.ANNOTATION_K8S_DEFAULT_CLASS})"
            return new ServiceResponse(success: true, data: [
                    storageClass: k8sDefault,
                    source: 'kubernetes-default'
            ])
        }

        log.warn "No default storage class found on cluster"
        return new ServiceResponse(success: false,
                msg: 'No default storage class found on cluster. ' +
                        'Ensure a StorageClass with KubeVirt virt-default annotation ' +
                        "'${HarvesterConstants.ANNOTATION_KUBEVIRT_DEFAULT_VIRT_CLASS}: \"true\"' " +
                        'or Kubernetes default annotation ' +
                        "'${HarvesterConstants.ANNOTATION_K8S_DEFAULT_CLASS}: \"true\"'")
    }

    /**
     * Get authentication status
     * @return Map with authentication information
     */
    Map getAuthStatus() {
        return apiClient.getAuthInfo()
    }

    /**
     * Force refresh of authentication token
     */
    void refreshAuth() {
        apiClient.refreshAuth()
    }
}
