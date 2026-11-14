// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.auth

import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Unroll

class AuthManagerSpec extends Specification {

    def setup() {
        AuthManager.clearLoginCache()
    }

    private static ComputeServerGroup group(Map props) {
        ComputeServerGroup g = new ComputeServerGroup()
        props.each { k, v -> g."$k" = v }
        return g
    }

    @Unroll
    def "normalizeApiUrl turns #input into #expected"() {
        expect:
        AuthManager.normalizeApiUrl(input) == expected

        where:
        input                                           || expected
        'https://10.0.0.10'                             || 'https://10.0.0.10/k8s/clusters/local'
        'https://harvester.example.com/'                || 'https://harvester.example.com/k8s/clusters/local'
        'https://harvester.example.com:8443'            || 'https://harvester.example.com:8443/k8s/clusters/local'
        'https://10.0.0.10/k8s/clusters/local'          || 'https://10.0.0.10/k8s/clusters/local'
        'https://10.0.0.10/k8s/clusters/local/'         || 'https://10.0.0.10/k8s/clusters/local'
        'https://10.0.0.10:6443'                        || 'https://10.0.0.10:6443'
        '  https://10.0.0.10  '                         || 'https://10.0.0.10/k8s/clusters/local'
        'not-a-valid-url'                               || 'not-a-valid-url'
        null                                            || null
        ''                                              || ''
    }

    def "serverRoot keeps scheme, host and port only"() {
        expect:
        AuthManager.serverRoot('https://10.0.0.10/k8s/clusters/local') == 'https://10.0.0.10'
        AuthManager.serverRoot('https://h.example.com:8443/x') == 'https://h.example.com:8443'
        AuthManager.serverRoot('garbage') == null
        AuthManager.serverRoot(null) == null
    }

    def "an API key in serviceToken is used as the bearer token"() {
        given:
        def auth = new AuthManager(group(serviceToken: '  Bearer token-abc12:secret '), 'https://10.0.0.10')

        when:
        def result = auth.authenticate()

        then:
        result.success
        result.token == 'token-abc12:secret'
        result.expiresIn == 0
        !auth.canReauthenticate()
        auth.getServiceAccountToken() == 'token-abc12:secret'
        auth.baseUrl == 'https://10.0.0.10/k8s/clusters/local'
    }

    def "an access key and secret key entered as username and password form the bearer token"() {
        given:
        def auth = new AuthManager(group(serviceUsername: 'token-abc12', servicePassword: 'secret'), 'https://h')

        expect:
        auth.authenticate().token == 'token-abc12:secret'
        !auth.canReauthenticate()
    }

    def "username and password log in and the token is cached"() {
        given:
        def auth = Spy(AuthManager, constructorArgs: [group(name: 'c1', serviceUsername: 'admin', servicePassword: 'pw'), 'https://10.0.0.10'])

        when:
        def first = auth.authenticate()
        def second = auth.authenticate()

        then:
        1 * auth.callLogin('https://10.0.0.10') >> ServiceResponse.success([token: 'token-sess:abc', ttl: 57600000])
        first.success
        first.token == 'token-sess:abc'
        first.expiresIn > 0
        second.token == 'token-sess:abc'
        auth.canReauthenticate()
        auth.getServiceAccountToken() == null
    }

    def "invalidate forces a new login"() {
        given:
        def auth = Spy(AuthManager, constructorArgs: [group(serviceUsername: 'admin', servicePassword: 'pw'), 'https://10.0.0.10'])

        when:
        auth.authenticate()
        auth.invalidate()
        def result = auth.authenticate()

        then:
        2 * auth.callLogin(_) >>> [ServiceResponse.success([token: 'token-a:1']), ServiceResponse.success([token: 'token-b:2'])]
        result.token == 'token-b:2'
    }

    def "a rejected login reports invalid credentials"() {
        given:
        def auth = Spy(AuthManager, constructorArgs: [group(serviceUsername: 'admin', servicePassword: 'bad'), 'https://10.0.0.10'])
        def rejected = new ServiceResponse(success: false, errorCode: '401')

        when:
        def result = auth.authenticate()

        then:
        1 * auth.callLogin(_) >> rejected
        !result.success
        result.error.contains('invalid username or password')
    }

    def "a login response without a token fails"() {
        given:
        def auth = Spy(AuthManager, constructorArgs: [group(serviceUsername: 'admin', servicePassword: 'pw'), 'https://10.0.0.10'])

        when:
        def result = auth.authenticate()

        then:
        1 * auth.callLogin(_) >> ServiceResponse.success([:])
        !result.success
        result.error.contains('did not return a token')
    }

    def "login needs a valid URL"() {
        expect:
        !new AuthManager(group(serviceUsername: 'admin', servicePassword: 'pw'), 'not-a-url').authenticate().success
    }

    @Unroll
    def "authenticate fails clearly with no credentials (#desc)"() {
        when:
        def result = new AuthManager(serverGroup, 'https://h').authenticate()

        then:
        !result.success
        result.error.contains('No Harvester credentials')

        where:
        desc              | serverGroup
        'null group'      | null
        'empty group'     | new ComputeServerGroup()
        'username only'   | group(serviceUsername: 'admin')
        'blank token'     | group(serviceToken: '   ')
    }

    def "AuthResult success computes expiry when expiresIn is positive"() {
        when:
        def result = AuthManager.AuthResult.success('t', 3600)

        then:
        result.success
        result.expiresAt > System.currentTimeMillis()
        !result.isExpired()
        result.getTimeUntilExpiry() > 0
        result.toString().contains('success=true')
    }

    def "AuthResult failure carries the error"() {
        when:
        def result = AuthManager.AuthResult.failure('boom')

        then:
        !result.success
        result.error == 'boom'
        result.expiresAt == 0
        !result.isExpired()
        result.getTimeUntilExpiry() == 0
        result.toString().contains('boom')
    }
}
