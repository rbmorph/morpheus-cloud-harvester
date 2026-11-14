// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.auth

import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap

/**
 * Authenticates to the Harvester API.
 *
 * Harvester serves its API (the Kubernetes-style paths in the Harvester API reference) through its embedded
 * Rancher proxy at https://<vip>/k8s/clusters/local. Two kinds of credentials are supported:
 * <ul>
 *   <li><b>Harvester API key</b>: created in the Harvester UI under Account &amp; API Keys. Its bearer token looks
 *       like {@code token-abc12:secret}. It is stored in serviceToken. An access key / secret key pair entered as
 *       username / password is also accepted (the username starts with {@code token-}).</li>
 *   <li><b>Harvester user login</b>: username and password of a Harvester user. The plugin logs in through the
 *       Harvester login endpoint and caches the session token until it expires or is rejected.</li>
 * </ul>
 * A Kubernetes service account token is still accepted in serviceToken for clusters added with older versions.
 */
@Slf4j
class AuthManager {

    // Session tokens obtained with a username/password login, shared across API clients for the same cluster
    private static final Map<String, CachedLogin> LOGIN_CACHE = new ConcurrentHashMap<>()
    // Renew a login token this long before it expires
    private static final long EXPIRY_MARGIN_MS = 5 * 60 * 1000L
    // Lifetime to assume when the login response does not say
    private static final long DEFAULT_LOGIN_TTL_MS = 60 * 60 * 1000L

    private String baseUrl
    private String serviceToken
    private String username
    private String password
    private ComputeServerGroup serverGroup

    AuthManager(ComputeServerGroup serverGroup, String harvesterBaseUrl) {
        this.serverGroup = serverGroup
        this.baseUrl = normalizeApiUrl(harvesterBaseUrl)
        this.serviceToken = stripBearer(serverGroup?.serviceToken)
        this.username = serverGroup?.serviceUsername?.trim() ?: null
        this.password = serverGroup?.servicePassword ?: null
        log.debug "Initialized Harvester Auth Manager for cluster: ${serverGroup?.name}"
    }

    /**
     * Turns the API URL entered by the user into the base URL for Harvester API calls.
     * A bare Harvester address (https://vip) becomes https://vip/k8s/clusters/local, the endpoint that accepts
     * Harvester API keys. URLs with a path, or on the Kubernetes API server port 6443, are used as entered.
     */
    static String normalizeApiUrl(String url) {
        String rtn = url?.trim()
        if (!rtn) {
            return rtn
        }
        while (rtn.endsWith('/')) {
            rtn = rtn[0..-2]
        }
        try {
            URI uri = new URI(rtn)
            if (uri.scheme && uri.host && !uri.rawPath && uri.port != HarvesterConstants.KUBE_API_SERVER_PORT) {
                rtn = rtn + HarvesterConstants.HARVESTER_API_PATH_PREFIX
            }
        } catch (URISyntaxException ignored) {
            // leave as entered; the connection check reports the problem
        }
        return rtn
    }

    /**
     * @return scheme://host[:port] of a URL, or null when it can't be parsed
     */
    static String serverRoot(String url) {
        try {
            URI uri = new URI(url?.trim())
            if (!uri.scheme || !uri.host) {
                return null
            }
            return "${uri.scheme}://${uri.host}${uri.port > 0 ? ':' + uri.port : ''}".toString()
        } catch (Exception ignored) {
            // null or malformed URL
            return null
        }
    }

    private static String stripBearer(String token) {
        String rtn = token?.trim()
        if (rtn?.toLowerCase()?.startsWith('bearer ')) {
            rtn = rtn.substring(7).trim()
        }
        return rtn ?: null
    }

    String getBaseUrl() {
        return baseUrl
    }

    /**
     * @return true when credentials are a username/password login, so a rejected token can be renewed by logging in again
     */
    boolean canReauthenticate() {
        return !serviceToken && !isApiKeyPair() && username && password
    }

    private boolean isApiKeyPair() {
        return username?.startsWith(HarvesterConstants.HARVESTER_API_KEY_PREFIX) && password
    }

    /**
     * Returns a bearer token for the Harvester API.
     */
    AuthResult authenticate() {
        log.debug "Authenticating with Harvester for cluster: ${serverGroup?.name ?: 'unknown'}"
        if (serviceToken) {
            return AuthResult.success(serviceToken, 0)
        }
        if (isApiKeyPair()) {
            // Access key + secret key of a Harvester API key: the bearer token is "access:secret"
            return AuthResult.success("${username}:${password}".toString(), 0)
        }
        if (username && password) {
            return login()
        }
        return AuthResult.failure('No Harvester credentials configured. Enter a Harvester API key, or a Harvester username and password.')
    }

    /**
     * Drops a cached login token so the next call logs in again.
     */
    void invalidate() {
        LOGIN_CACHE.remove(cacheKey())
    }

    /**
     * @return the static token when one is configured (API key or legacy service account token), otherwise null
     */
    String getServiceAccountToken() {
        return serviceToken ?: (isApiKeyPair() ? "${username}:${password}".toString() : null)
    }

    private String cacheKey() {
        return "${serverRoot(baseUrl)}|${username}|${password?.hashCode()}".toString()
    }

    private AuthResult login() {
        String root = serverRoot(baseUrl)
        if (!root) {
            return AuthResult.failure("Invalid Harvester API URL: ${baseUrl}")
        }
        String key = cacheKey()
        CachedLogin cached = LOGIN_CACHE.get(key)
        if (cached && cached.expiresAt - EXPIRY_MARGIN_MS > System.currentTimeMillis()) {
            return AuthResult.success(cached.token, ((cached.expiresAt - System.currentTimeMillis()) / 1000) as long)
        }

        log.info "Logging in to Harvester at ${root} as ${username}"
        ServiceResponse response = callLogin(root)
        if (!response?.success) {
            String reason = response?.errorCode == '401' ? 'invalid username or password' : (response?.msg ?: response?.error ?: 'no response')
            return AuthResult.failure("Harvester login failed for user ${username}: ${reason}")
        }
        Map data = response.data instanceof Map ? (Map) response.data : [:]
        String token = data.token
        if (!token) {
            return AuthResult.failure('Harvester login did not return a token')
        }
        long ttlMs = 0L
        try {
            ttlMs = (data.ttl ?: 0) as long
        } catch (ignored) {
            ttlMs = 0L
        }
        long expiresAt = System.currentTimeMillis() + (ttlMs > 0 ? ttlMs : DEFAULT_LOGIN_TTL_MS)
        LOGIN_CACHE.put(key, new CachedLogin(token: token, expiresAt: expiresAt))
        return AuthResult.success(token, ((expiresAt - System.currentTimeMillis()) / 1000) as long)
    }

    /**
     * POST /v3-public/localProviders/local?action=login. Separate method so tests can stub the HTTP call.
     */
    protected ServiceResponse callLogin(String root) {
        HttpApiClient client = new HttpApiClient()
        try {
            HttpApiClient.RequestOptions opts = new HttpApiClient.RequestOptions()
            opts.ignoreSSL = true
            opts.queryParams = [action: 'login']
            opts.body = [username: username, password: password, responseType: 'token', description: 'Morpheus Harvester plugin']
            return client.callJsonApi(root, HarvesterConstants.HARVESTER_LOGIN_PATH, opts, 'POST')
        } finally {
            client.shutdownClient()
        }
    }

    static void clearLoginCache() {
        LOGIN_CACHE.clear()
    }

    private static class CachedLogin {
        String token
        long expiresAt
    }

    static class AuthResult {
        final boolean success
        final String token
        final String error
        final long expiresAt
        final long expiresIn

        private AuthResult(boolean success, String token, String error, long expiresAt, long expiresIn) {
            this.success = success
            this.token = token
            this.error = error
            this.expiresAt = expiresAt
            this.expiresIn = expiresIn
        }

        static AuthResult success(String token, long expiresIn) {
            long expiresAt = expiresIn > 0 ? System.currentTimeMillis() + (expiresIn * 1000) : 0
            return new AuthResult(true, token, null, expiresAt, expiresIn)
        }

        static AuthResult failure(String error) {
            return new AuthResult(false, null, error, 0, 0)
        }

        boolean isExpired() {
            return expiresAt > 0 && System.currentTimeMillis() > expiresAt
        }

        long getTimeUntilExpiry() {
            return Math.max(0, expiresAt - System.currentTimeMillis())
        }

        @Override
        String toString() {
            if (success) {
                return "AuthResult{success=true, token='${token?.take(10)}...', expiresIn=${expiresIn}s}"
            }
            return "AuthResult{success=false, error='${error}'}"
        }
    }
}
