package com.hpe.morpheus.harvester.plugin.apihelper

import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

/**
 * API Helper for Harvester ConfigMap operations specific to sysprep configurations
 *
 * This class handles all interactions with Harvester's ConfigMap API,
 * providing methods to create, retrieve, update, and delete ConfigMaps
 * that contain sysprep unattend.xml configurations.
 *
 * ConfigMap Structure:
 * {
 *   "apiVersion": "v1",
 *   "kind": "ConfigMap",
 *   "metadata": {
 *     "name": "vm-sysprep",
 *     "namespace": "default",
 *     "labels": {
 *       "morpheus.io/sysprep": "true",
 *       "morpheus.io/type": "unattend-xml"
 *     }
 *   },
 *   "data": {
 *     "unattend.xml": "<xml content>"
 *   }
 * }
 *
 * Usage:
 * def helper = new ConfigMapApiHelper(harvesterApiClient)
 * def response = helper.createConfigMap("default", "vm-sysprep", xmlContent)
 */
@Slf4j
class ConfigMapApiHelper {

    private HarvesterApiClient harvesterApiClient

    // API paths
    private static final String API_VERSION = "v1"
    private static final String CONFIGMAP_KIND = "ConfigMap"
    private static final String UNATTEND_XML_KEY = "unattend.xml"

    // Labels for identifying sysprep ConfigMaps
    private static final String LABEL_SYSPREP = "morpheus.io/sysprep"
    private static final String LABEL_TYPE = "morpheus.io/type"
    private static final String LABEL_TYPE_VALUE = "unattend-xml"

    /**
     * @param harvesterApiClient Authenticated Harvester API client
     */
    ConfigMapApiHelper(HarvesterApiClient harvesterApiClient) {
        this.harvesterApiClient = harvesterApiClient
    }

    /**
     * Create a ConfigMap containing sysprep unattend.xml
     *
     * @param namespace The Harvester namespace to create the ConfigMap in
     * @param name The name of the ConfigMap
     * @param xmlContent The unattend.xml content as a String
     * @param additionalLabels Optional additional labels to add
     * @param additionalAnnotations Optional annotations to add
     * @return ServiceResponse with created ConfigMap data or error
     */
    ServiceResponse createConfigMap(
            String namespace,
            String name,
            String xmlContent,
            Map<String, String> additionalLabels = [:],
            Map<String, String> additionalAnnotations = [:]
    ) {
        log.info "Creating sysprep ConfigMap '${name}' in namespace '${namespace}'"

        try {
            // Validate inputs
            if (!namespace || !name || !xmlContent) {
                return ServiceResponse.error("Namespace, name, and xmlContent are required")
            }

            // Build the ConfigMap body
            Map configMapBody = buildConfigMapBody(
                    namespace,
                    name,
                    xmlContent,
                    additionalLabels,
                    additionalAnnotations
            )

            // API path for creating ConfigMap
            String path = "/api/v1/namespaces/${namespace}/configmaps"

            log.debug "Calling Harvester API to create ConfigMap at path: ${path}"

            // Make the API call
            ServiceResponse response = harvesterApiClient.post(path, configMapBody)

            if (response == null) {
                log.error "HarvesterApiClient returned null response"
                return ServiceResponse.error("API client returned null response")
            }

            if (response.success) {
                log.info "Successfully created ConfigMap '${name}' in namespace '${namespace}'"
                return response
            } else {
                // Extract error message from response
                String errorMsg = extractErrorMessage(response)
                log.error "Failed to create ConfigMap '${name}': ${errorMsg}"
                return ServiceResponse.error("Failed to create ConfigMap: ${errorMsg}", null, response.data)
            }

        } catch (Exception e) {
            log.error "Exception creating ConfigMap '${name}' in namespace '${namespace}': ${e.message}", e
            return ServiceResponse.error("Exception creating ConfigMap: ${e.message}")
        }
    }

    /**
     * Get a ConfigMap by name
     *
     * @param namespace The Harvester namespace
     * @param name The name of the ConfigMap
     * @return ServiceResponse with ConfigMap data or error
     */
    ServiceResponse getConfigMap(String namespace, String name) {
        log.info "Retrieving ConfigMap '${name}' from namespace '${namespace}'"

        try {
            if (!namespace || !name) {
                return ServiceResponse.error("Namespace and name are required")
            }

            String path = "/api/v1/namespaces/${namespace}/configmaps/${name}"

            log.debug "Calling Harvester API to get ConfigMap at path: ${path}"

            ServiceResponse response = harvesterApiClient.get(path)

            if (response.success) {
                log.info "Successfully retrieved ConfigMap '${name}'"
                return response
            } else {
                log.warn "ConfigMap '${name}' not found or error: ${response.msg}"
                return response
            }

        } catch (Exception e) {
            log.error "Exception retrieving ConfigMap '${name}': ${e.message}", e
            return ServiceResponse.error("Exception retrieving ConfigMap: ${e.message}")
        }
    }

    /**
     * Update an existing ConfigMap with new XML content
     *
     * @param namespace The Harvester namespace
     * @param name The name of the ConfigMap
     * @param xmlContent The new unattend.xml content
     * @return ServiceResponse with updated ConfigMap data or error
     */
    ServiceResponse updateConfigMap(String namespace, String name, String xmlContent) {
        log.info "Updating ConfigMap '${name}' in namespace '${namespace}'"

        try {
            // First, get the existing ConfigMap to preserve metadata
            ServiceResponse getResponse = getConfigMap(namespace, name)

            if (!getResponse.success) {
                log.error "Cannot update ConfigMap '${name}': does not exist"
                return ServiceResponse.error("ConfigMap does not exist, cannot update")
            }

            // Update the XML content in the data section
            Map existingConfigMap = getResponse.data
            existingConfigMap.data[UNATTEND_XML_KEY] = xmlContent

            String path = "/api/v1/namespaces/${namespace}/configmaps/${name}"

            log.debug "Calling Harvester API to update ConfigMap at path: ${path}"

            ServiceResponse response = harvesterApiClient.put(path, existingConfigMap)

            if (response.success) {
                log.info "Successfully updated ConfigMap '${name}'"
                return response
            } else {
                String errorMsg = extractErrorMessage(response)
                log.error "Failed to update ConfigMap '${name}': ${errorMsg}"
                return ServiceResponse.error("Failed to update ConfigMap: ${errorMsg}")
            }

        } catch (Exception e) {
            log.error "Exception updating ConfigMap '${name}': ${e.message}", e
            return ServiceResponse.error("Exception updating ConfigMap: ${e.message}")
        }
    }

    /**
     * Delete a ConfigMap
     *
     * @param namespace The Harvester namespace
     * @param name The name of the ConfigMap
     * @return ServiceResponse indicating success or failure
     */
    ServiceResponse deleteConfigMap(String namespace, String name) {
        log.info "Deleting ConfigMap '${name}' from namespace '${namespace}'"

        try {
            if (!namespace || !name) {
                return ServiceResponse.error("Namespace and name are required")
            }

            String path = "/api/v1/namespaces/${namespace}/configmaps/${name}"

            log.debug "Calling Harvester API to delete ConfigMap at path: ${path}"

            ServiceResponse response = harvesterApiClient.delete(path)

            if (response == null) {
                log.error "HarvesterApiClient returned null response"
                return ServiceResponse.error("API client returned null response")
            }


            if (response.success) {
                log.info "Successfully deleted ConfigMap '${name}'"
                return response
            } else {
                String errorMsg = extractErrorMessage(response)
                log.error "Failed to delete ConfigMap '${name}': ${errorMsg}"
                return ServiceResponse.error("Failed to delete ConfigMap: ${errorMsg}", null, response.data)
            }

        } catch (Exception e) {
            log.error "Exception deleting ConfigMap '${name}': ${e.message}", e
            return ServiceResponse.error("Exception deleting ConfigMap: ${e.message}")
        }
    }

    /**
     * Check if a ConfigMap exists
     *
     * @param namespace The Harvester namespace
     * @param name The name of the ConfigMap
     * @return true if exists, false otherwise
     */
    boolean configMapExists(String namespace, String name) {
        ServiceResponse response = getConfigMap(namespace, name)
        return response.success
    }

    /**
     * Get the unattend.xml content from a ConfigMap
     *
     * @param namespace The Harvester namespace
     * @param name The name of the ConfigMap
     * @return The XML content as a String, or null if not found
     */
    String getUnattendXmlContent(String namespace, String name) {
        ServiceResponse response = getConfigMap(namespace, name)

        if (response?.success && response.data) {
            // Safely access the nested data structure
            def configMapData = response.data
            if (configMapData instanceof Map && configMapData.data instanceof Map) {
                return configMapData.data[UNATTEND_XML_KEY]
            }
        }

        return null
    }

    /**
     * Build the ConfigMap body structure
     *
     * @param namespace The namespace
     * @param name The ConfigMap name
     * @param xmlContent The unattend.xml content
     * @param additionalLabels Optional additional labels
     * @param additionalAnnotations Optional annotations
     * @return Map representing the ConfigMap structure
     */
    private Map buildConfigMapBody(
            String namespace,
            String name,
            String xmlContent,
            Map<String, String> additionalLabels,
            Map<String, String> additionalAnnotations
    ) {
        // Build labels
        Map<String, String> labels = [
                (LABEL_SYSPREP): "true",
                (LABEL_TYPE): LABEL_TYPE_VALUE
        ]
        if (additionalLabels) {
            labels.putAll(additionalLabels)
        }

        // Build the ConfigMap structure
        Map configMap = [
                apiVersion: API_VERSION,
                kind: CONFIGMAP_KIND,
                metadata: [
                        name: name,
                        namespace: namespace,
                        labels: labels
                ],
                data: [
                        (UNATTEND_XML_KEY): xmlContent
                ]
        ]

        // Add annotations if provided
        if (additionalAnnotations && !additionalAnnotations.isEmpty()) {
            (configMap.metadata as Map)['annotations'] = additionalAnnotations
        }

        return configMap
    }

    /**
     * Extract error message from ServiceResponse
     * Harvester API errors are often in response.data instead of response.msg
     *
     * @param response The ServiceResponse from API call
     * @return Extracted error message
     */
    private String extractErrorMessage(ServiceResponse response) {
        // Try response.msg first
        if (response.msg) {
            return response.msg
        }

        // Try to extract from response.data (Harvester API error format)
        if (response.data instanceof Map) {
            def data = response.data as Map

            // Check for 'message' field (standard Kubernetes error format)
            if (data.message) {
                return data.message as String
            }

            // Check for 'reason' field
            if (data.reason) {
                return data.reason as String
            }

            // Check for error details
            if (data.details) {
                def details = data.details
                if (details instanceof Map) {
                    String detailsStr = details.collect { k, v -> "${k}=${v}" }.join(', ')
                    return "Error details: ${detailsStr}"
                }
            }
        }

        // Fallback
        return "Unknown error (no error message available)"
    }
}