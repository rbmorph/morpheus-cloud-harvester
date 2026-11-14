package com.hpe.morpheus.harvester.plugin.events

import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class HarvesterResourceEvents {
    HarvesterApiClient harvesterApiClient
    ComputeServerGroup computeServerGroup
    // constructor
    HarvesterResourceEvents(ComputeServerGroup computeServerGroup) {
        this.computeServerGroup = computeServerGroup
    }

    /**
     * Fetches events for a given object in a namespace from the Harvester API.
     * Uses lazy initialization for harvesterApiClient.
     * @param namespace The namespace to fetch events for.
     * @param objectName The name of the object to filter events for.
     * @param objectKind The kind of the object to filter events for.
     * @return ServiceResponse containing the events or error information.
     */
    ServiceResponse fetchEventsByNamespace(String namespace, String objectName, String objectKind) {
        // initialize harvesterApiClient if not already done
        if (!harvesterApiClient) {
            // Lazy initialization of harvesterApiClient using computeServerGroup
            harvesterApiClient = new HarvesterApiClient(computeServerGroup)
        }
        // Prepare the resourceUri for the API call
        String resourceUri = "/api/v1/namespaces/${namespace}/events"
        // Append query parameters to filter events by involvedObject name and kind
        Map queryParams = [
            'fieldSelector': "involvedObject.name=${objectName},involvedObject.kind=${objectKind}"
        ]
        log.info "Getting resource events uri ${resourceUri} with query params ${queryParams}"
        // make the rest call here.
        try {
            ServiceResponse response = harvesterApiClient.get(resourceUri, queryParams)
            return response
        } catch (Exception ex) {
            log.error("Exception while initiating API call: ${ex.message}", ex)
            return ServiceResponse.error("Exception while initiating API call: ${ex.message}")
        }
    }
}
