// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.apihelper

import com.hpe.morpheus.harvester.plugin.auth.AuthManager
import com.hpe.morpheus.harvester.plugin.auth.AuthWrapper
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

/**
 * Harvester API Client with automatic authentication
 * Provides a simplified interface for Harvester API calls with token management
 */
@Slf4j
class HarvesterApiClient {

    private AuthWrapper authWrapper
    private String baseUrl

    /**
     * Initialize with ComputeServerGroup for credentials and URLs
     * Extracts URLs from serverGroup, falls back to defaults if not provided
     * @param serverGroup ComputeServerGroup containing credentials and URLs
    */
    HarvesterApiClient(ComputeServerGroup serverGroup) {
        // Harvester API key or user login, sent as a bearer token to https://<vip>/k8s/clusters/local
        this.authWrapper = new AuthWrapper(serverGroup, serverGroup?.serviceUrl)
        this.baseUrl = AuthManager.normalizeApiUrl(serverGroup?.serviceUrl)

        log.debug "HarvesterApiClient initialized - API: ${this.baseUrl}"
    }

    /**
     * @return the base URL that API paths are appended to (normalized Harvester API URL)
     */
    String getBaseUrl() {
        return baseUrl
    }

    /**
     * @return a valid bearer token, for callers that open their own connections (e.g. the VNC console)
     */
    String getToken() {
        return authWrapper.getValidToken()
    }


    // Authenticated versions of CommonApiHelper methods

    /**
     * Make a GET request to the Harvester API with optional query parameters
     * @param path The API path
     * @param queryParams Optional map of query parameters
     * @return ServiceResponse
     */
    ServiceResponse get(String path, Map queryParams = null) {
        return authWrapper.withAuthentication { token ->
            return CommonApiHelper.get(baseUrl, path, token, queryParams)
        } as ServiceResponse
    }

    /** Any request; the reply body is left as text in content (see CommonApiHelper.rawRequest) */
    ServiceResponse raw(String method, String path, Map queryParams = null, Map headers = null, String body = null,
                        String contentType = null) {
        return authWrapper.withAuthentication { token ->
            return CommonApiHelper.rawRequest(baseUrl, path, token, method, queryParams, headers, body, contentType)
        } as ServiceResponse
    }

    ServiceResponse post(String path, def body, Map queryParams = null) {
        return authWrapper.withAuthentication { token ->
            return CommonApiHelper.post(baseUrl, path, token, body, queryParams)
        } as ServiceResponse
    }

    ServiceResponse putRaw(String path, String body, String contentType, Map queryParams = null) {
        return authWrapper.withAuthentication { token ->
            return CommonApiHelper.putRaw(baseUrl, path, token, body, contentType, queryParams)
        } as ServiceResponse
    }

    ServiceResponse put(String path, def body) {
        return authWrapper.withAuthentication { token ->
            return CommonApiHelper.put(baseUrl, path, token, body)
        } as ServiceResponse
    }

    ServiceResponse patch(
            String path, def body, String contentType = 'application/json-patch+json', Map queryParams = null) {
        return authWrapper.withAuthentication { token ->
            return CommonApiHelper.patch(baseUrl, path, token, body, contentType, queryParams)
        } as ServiceResponse
    }

    ServiceResponse delete(String path, def body = null, Map queryParams = null) {
        return authWrapper.withAuthentication { token ->
            return CommonApiHelper.delete(baseUrl, path, token, body, queryParams)
        } as ServiceResponse
    }

    ServiceResponse callApiWithPagination(String path, String continueToken = null, int pageSize = 50) {
        return authWrapper.withAuthentication { token ->
            return CommonApiHelper.callApiWithPagination(baseUrl + path, token, continueToken, pageSize)
        } as ServiceResponse
    }

    // Utility methods

    /**
     * Get current token info for debugging
     */
    Map getAuthInfo() {
        return authWrapper.getTokenInfo()
    }

    /**
     * Force token refresh
     */
    void refreshAuth() {
        authWrapper.refreshToken()
    }

    /**
     * Check if authenticated
     */
    boolean isAuthenticated() {
        return authWrapper.hasValidToken()
    }

    /**
     * Execute a closure with automatic authentication
     * This method provides controlled access to the authentication wrapper
     */
    def withAuthentication(Closure apiCall) {
        return authWrapper.withAuthentication(apiCall)
    }
}
