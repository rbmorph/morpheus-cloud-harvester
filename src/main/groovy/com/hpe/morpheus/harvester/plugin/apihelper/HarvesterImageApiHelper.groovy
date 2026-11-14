// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.apihelper

import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

/**
 * Helper for Harvester VirtualMachineImage resources (harvesterhci.io/v1beta1).
 * Harvester images are the source of Morpheus virtual images.
 */
@Slf4j
class HarvesterImageApiHelper {

    private ComputeServerGroup serverGroup
    private HarvesterApiClient apiClient

    HarvesterImageApiHelper(ComputeServerGroup serverGroup = null) {
        this.serverGroup = serverGroup
        this.apiClient = new HarvesterApiClient(serverGroup)
    }

    /** Test seam */
    HarvesterImageApiHelper(ComputeServerGroup serverGroup, HarvesterApiClient apiClient) {
        this.serverGroup = serverGroup
        this.apiClient = apiClient
    }

    static String getImageApiPath(String namespace, String imageName = null) {
        String path = "/apis/${HarvesterConstants.HARVESTER_API_GROUP}/${HarvesterConstants.HARVESTER_API_VERSION}" +
                "/namespaces/${namespace}/virtualmachineimages"
        return imageName ? "${path}/${imageName}" : path
    }

    /**
     * Lists VirtualMachineImages across all namespaces, one page at a time.
     */
    ServiceResponse getVMImages(String continueToken, Integer pageSize) {
        return apiClient.callApiWithPagination(HarvesterConstants.HARVESTER_API_V1_VM_IMAGES, continueToken, pageSize ?: 50)
    }

    /**
     * Gets a single VirtualMachineImage.
     */
    ServiceResponse getVMImage(String namespace, String imageName) {
        return apiClient.get(getImageApiPath(namespace, imageName))
    }

    /**
     * True when the image has finished importing and can back a volume.
     * Harvester reports this as the 'Imported' condition and progress 100.
     */
    static boolean isImageReady(Map image) {
        if (!image || image.metadata?.deletionTimestamp) {
            return false
        }
        def conditions = image.status?.conditions ?: []
        def imported = conditions.find { it?.type == 'Imported' }
        if (imported) {
            return imported.status?.toString()?.equalsIgnoreCase('true')
        }
        return (image.status?.progress as Integer) == 100
    }

    /**
     * The storage class a volume must use to be populated from this image.
     * Harvester names it longhorn-<image name> and reports it in status.storageClassName.
     */
    static String getImageStorageClassName(Map image) {
        return image?.status?.storageClassName ?:
                (image?.metadata?.name ? "longhorn-${image.metadata.name}".toString() : null)
    }

    /**
     * Harvester image id used by the harvesterhci.io/imageId annotation: <namespace>/<name>
     */
    static String getImageId(Map image) {
        return image?.metadata?.namespace && image?.metadata?.name ?
                "${image.metadata.namespace}/${image.metadata.name}".toString() : null
    }

    /**
     * Harvester's own default VM storage class, read from the cluster's storage classes.
     * Falls back to harvester-longhorn.
     */
    String getDefaultStorageClassName() {
        try {
            ServiceResponse response = apiClient.get('/apis/storage.k8s.io/v1/storageclasses')
            if (response?.success) {
                def items = response.data?.items ?: []
                def defaultClass = items.find {
                    it?.metadata?.annotations?.get(HarvesterConstants.ANNOTATION_K8S_DEFAULT_CLASS) == 'true'
                }
                if (defaultClass?.metadata?.name) {
                    return defaultClass.metadata.name
                }
            }
        } catch (Exception e) {
            log.warn "Unable to determine default storage class: ${e.message}"
        }
        return HarvesterConstants.DEFAULT_STORAGE_CLASS
    }
}
