// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.apihelper

import com.hpe.morpheus.harvester.plugin.auth.AuthWrapper
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification

/**
 * Unit tests for HarvesterApiClient class
 */
class HarvesterApiClientSpec extends Specification {

    HarvesterApiClient harvesterApiClient
    AuthWrapper mockAuthWrapper
    ComputeServerGroup mockServerGroup

    def setup() {
        mockServerGroup = Mock(ComputeServerGroup) {
            getServiceUrl() >> "https://api.example.com:6443"
            getConfigProperty('authUrl') >> "https://oauth.example.com"
        }
        mockAuthWrapper = Mock(AuthWrapper)
        harvesterApiClient = new HarvesterApiClient(mockServerGroup)
        harvesterApiClient.authWrapper = mockAuthWrapper
    }

    def "get delegates to authWrapper.withAuthentication"() {
        given:
        def expected = new ServiceResponse(success: true, data: [items: []])
        mockAuthWrapper.withAuthentication(_) >> expected

        when:
        def result = harvesterApiClient.get('/api/v1/nodes')

        then:
        result == expected
    }

    def "get passes query params through"() {
        given:
        def expected = new ServiceResponse(success: true)
        mockAuthWrapper.withAuthentication(_) >> expected

        when:
        def result = harvesterApiClient.get('/api/v1/pods', [labelSelector: 'app=test'])

        then:
        result == expected
    }

    def "post delegates to authWrapper.withAuthentication"() {
        given:
        def expected = new ServiceResponse(success: true)
        mockAuthWrapper.withAuthentication(_) >> expected

        when:
        def result = harvesterApiClient.post('/api/v1/namespaces/default/pods', [kind: 'Pod'])

        then:
        result == expected
    }

    def "put delegates to authWrapper.withAuthentication"() {
        given:
        def expected = new ServiceResponse(success: true)
        mockAuthWrapper.withAuthentication(_) >> expected

        when:
        def result = harvesterApiClient.put('/api/v1/namespaces/default/configmaps/test', [data: [:]])

        then:
        result == expected
    }

    def "patch delegates to authWrapper.withAuthentication"() {
        given:
        def expected = new ServiceResponse(success: true)
        mockAuthWrapper.withAuthentication(_) >> expected

        when:
        def result = harvesterApiClient.patch('/api/v1/namespaces/default/pods/test', [[op: 'replace']])

        then:
        result == expected
    }

    def "delete delegates to authWrapper.withAuthentication"() {
        given:
        def expected = new ServiceResponse(success: true)
        mockAuthWrapper.withAuthentication(_) >> expected

        when:
        def result = harvesterApiClient.delete('/api/v1/namespaces/default/pods/test')

        then:
        result == expected
    }

    def "callApiWithPagination delegates to authWrapper.withAuthentication"() {
        given:
        def expected = new ServiceResponse(success: true)
        mockAuthWrapper.withAuthentication(_) >> expected

        when:
        def result = harvesterApiClient.callApiWithPagination('/api/v1/pods')

        then:
        result == expected
    }

    def "getAuthInfo returns token info from authWrapper"() {
        given:
        def expectedInfo = [hasToken: true, tokenType: 'Bearer']
        mockAuthWrapper.getTokenInfo() >> expectedInfo

        when:
        def result = harvesterApiClient.getAuthInfo()

        then:
        result == expectedInfo
    }

    def "refreshAuth calls authWrapper.refreshToken"() {
        when:
        harvesterApiClient.refreshAuth()

        then:
        1 * mockAuthWrapper.refreshToken()
    }

    def "isAuthenticated delegates to authWrapper.hasValidToken"() {
        given:
        mockAuthWrapper.hasValidToken() >> true

        when:
        def result = harvesterApiClient.isAuthenticated()

        then:
        result == true
    }

    def "withAuthentication delegates closure to authWrapper"() {
        given:
        def expected = new ServiceResponse(success: true)
        mockAuthWrapper.withAuthentication(_) >> expected

        when:
        def result = harvesterApiClient.withAuthentication { token -> 'test' }

        then:
        result == expected
    }
}
