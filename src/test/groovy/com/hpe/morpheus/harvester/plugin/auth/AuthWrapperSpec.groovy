// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.auth

import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification

class AuthWrapperSpec extends Specification {

    AuthManager authManager = Mock(AuthManager)
    AuthWrapper wrapper

    def setup() {
        wrapper = new AuthWrapper(new ComputeServerGroup(serviceToken: 'token-x:y'), 'https://h')
        wrapper.@authManager = authManager
    }

    def "constructor builds an AuthManager from the server group"() {
        when:
        def w = new AuthWrapper(new ComputeServerGroup(serviceToken: 'token-x:y', serviceUrl: 'https://h'), 'https://h')

        then:
        w.authManager != null
        w.getValidToken() == 'token-x:y'
        w.hasValidToken()
    }

    def "getValidToken caches a successful result"() {
        when:
        def a = wrapper.getValidToken()
        def b = wrapper.getValidToken()

        then:
        1 * authManager.authenticate() >> AuthManager.AuthResult.success('tok', 0)
        a == 'tok'
        b == 'tok'
    }

    def "getValidToken authenticates again after expiry"() {
        given:
        // private constructor: success, token, error, expiresAt (in the past), expiresIn
        wrapper.@cachedAuth = new AuthManager.AuthResult(true, 'old', null, System.currentTimeMillis() - 1000, 1)

        when:
        def token = wrapper.getValidToken()

        then:
        1 * authManager.authenticate() >> AuthManager.AuthResult.success('new', 0)
        token == 'new'
    }

    def "getValidToken throws when authentication fails"() {
        given:
        authManager.authenticate() >> AuthManager.AuthResult.failure('no creds')

        when:
        wrapper.getValidToken()

        then:
        def e = thrown(RuntimeException)
        e.message.contains('no creds')
    }

    def "withAuthentication passes the token to the call"() {
        given:
        authManager.authenticate() >> AuthManager.AuthResult.success('tok', 0)

        expect:
        wrapper.withAuthentication { token -> "called with ${token}".toString() } == 'called with tok'
    }

    def "a 401 is retried once after logging in again when credentials allow it"() {
        given:
        authManager.canReauthenticate() >> true
        def unauthorized = new ServiceResponse(success: false, errorCode: '401')
        def ok = ServiceResponse.success([items: []])
        def tokens = []

        when:
        def result = wrapper.withAuthentication { token ->
            tokens << token
            tokens.size() == 1 ? unauthorized : ok
        }

        then:
        2 * authManager.authenticate() >>> [AuthManager.AuthResult.success('expired', 0), AuthManager.AuthResult.success('fresh', 0)]
        1 * authManager.invalidate()
        result.is(ok)
        tokens == ['expired', 'fresh']
    }

    def "a 401 with an API key is returned without a retry"() {
        given:
        authManager.canReauthenticate() >> false
        authManager.authenticate() >> AuthManager.AuthResult.success('tok', 0)
        def unauthorized = new ServiceResponse(success: false, errorCode: '401')
        int calls = 0

        when:
        def result = wrapper.withAuthentication { token -> calls++; unauthorized }

        then:
        result.is(unauthorized)
        calls == 1
        0 * authManager.invalidate()
    }

    def "isUnauthorized only matches failed 401 responses"() {
        expect:
        AuthWrapper.isUnauthorized(new ServiceResponse(success: false, errorCode: '401'))
        !AuthWrapper.isUnauthorized(new ServiceResponse(success: false, errorCode: '404'))
        !AuthWrapper.isUnauthorized(ServiceResponse.success())
        !AuthWrapper.isUnauthorized('text')
        !AuthWrapper.isUnauthorized(null)
    }

    def "withAuthentication propagates exceptions from the call"() {
        given:
        authManager.authenticate() >> AuthManager.AuthResult.success('tok', 0)

        when:
        wrapper.withAuthentication { token -> throw new IllegalStateException('boom') }

        then:
        thrown(IllegalStateException)
    }

    def "refreshToken clears the cache and the cached login"() {
        when:
        wrapper.getValidToken()
        wrapper.refreshToken()

        then:
        1 * authManager.invalidate()
        2 * authManager.authenticate() >> AuthManager.AuthResult.success('tok', 0)
    }

    def "token info is empty before authentication"() {
        expect:
        !wrapper.getTokenInfo().authenticated
        !wrapper.hasValidToken()
    }

    def "token info reflects a successful authentication"() {
        given:
        authManager.authenticate() >> AuthManager.AuthResult.success('tok', 3600)

        when:
        wrapper.getValidToken()

        then:
        wrapper.getTokenInfo().authenticated
        wrapper.getTokenInfo().expiresIn > 0
        wrapper.hasValidToken()
    }
}
