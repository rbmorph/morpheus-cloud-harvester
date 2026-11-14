// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.auth

import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

/**
 * Supplies a valid Harvester bearer token to API calls.
 * With a username/password login, a call rejected with 401 is retried once after logging in again.
 */
@Slf4j
class AuthWrapper {

    private AuthManager authManager
    private AuthManager.AuthResult cachedAuth

    AuthWrapper(ComputeServerGroup serverGroup, String harvesterBaseUrl) {
        this.authManager = new AuthManager(serverGroup, harvesterBaseUrl)
    }

    AuthManager getAuthManager() {
        return authManager
    }

    String getValidToken() {
        if (!cachedAuth || !cachedAuth.success || cachedAuth.isExpired()) {
            cachedAuth = authManager.authenticate()
            if (!cachedAuth.success) {
                throw new RuntimeException("Failed to authenticate: ${cachedAuth.error}")
            }
        }
        return cachedAuth.token
    }

    /**
     * Runs an API call with a token. When the call returns a 401 and the credentials are a username/password login,
     * logs in again and retries the call once.
     */
    def withAuthentication(Closure apiCall) {
        def result = apiCall.call(getValidToken())
        if (isUnauthorized(result) && authManager.canReauthenticate()) {
            log.info 'Harvester rejected the session token, logging in again'
            refreshToken()
            result = apiCall.call(getValidToken())
        }
        return result
    }

    static boolean isUnauthorized(def result) {
        return result instanceof ServiceResponse && !result.success && result.errorCode == '401'
    }

    boolean hasValidToken() {
        return cachedAuth && cachedAuth.success && !cachedAuth.isExpired()
    }

    /**
     * Forgets the current token (and a cached login) and authenticates again.
     */
    void refreshToken() {
        cachedAuth = null
        authManager.invalidate()
        getValidToken()
    }

    Map getTokenInfo() {
        if (!cachedAuth) {
            return [authenticated: false, message: 'No authentication attempted']
        }
        return [
            authenticated: cachedAuth.success,
            expiresIn: cachedAuth.success ? cachedAuth.getTimeUntilExpiry() / 1000 : 0,
            error: cachedAuth.error
        ]
    }
}
