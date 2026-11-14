// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.apihelper

import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Unit tests for CommonApiHelper class
 */
class CommonApiHelperSpec extends Specification {

    def "callApi should make successful API call"() {
        given:
        def url = "https://api.example.com"
        def path = "/test/path"
        def token = "test-token"
        def method = "GET"

        and:
        def mockResponse = new ServiceResponse(success: true, data: [result: "success"])
        GroovySpy(HttpApiClient, global: true)
        def mockClient = Mock(HttpApiClient)
        new HttpApiClient() >> mockClient

        when:
        def response = CommonApiHelper.callApi(url, path, token, method)

        then:
        1 * mockClient.callJsonApi(url, path, _, method) >> mockResponse
        response != null
        response.success == true
    }

    def "callApi should handle API failures"() {
        given:
        def url = "https://api.example.com"
        def path = "/test/path"
        def token = "test-token"
        def method = "GET"

        and:
        def mockResponse = new ServiceResponse(success: false, msg: "API Error")
        GroovySpy(HttpApiClient, global: true)
        def mockClient = Mock(HttpApiClient)
        new HttpApiClient() >> mockClient

        when:
        def response = CommonApiHelper.callApi(url, path, token, method)

        then:
        1 * mockClient.callJsonApi(url, path, _, method) >> mockResponse
        response.success == false
    }

    @Unroll
    def "callApiWithPagination should handle first page with continueToken=#continueToken"() {
        given:
        def baseUrl = "https://api.example.com/items"
        def token = "test-token"
        def pageSize = 50

        and:
        def mockResponseData = [
            kind: "List",
            apiVersion: "v1",
            metadata: [
                resourceVersion: "12345",
                continue: nextToken,
                remainingItemCount: remaining
            ],
            items: (1..itemCount).collect { [id: it, name: "item-${it}"] }
        ]
        def mockResponse = new ServiceResponse(success: true, data: mockResponseData)
        GroovySpy(HttpApiClient, global: true)
        def mockClient = Mock(HttpApiClient)
        new HttpApiClient() >> mockClient

        when:
        def response = CommonApiHelper.callApiWithPagination(baseUrl, token, continueToken, pageSize)

        then:
        1 * mockClient.callJsonApi(_, null, _, "GET") >> mockResponse
        response.success == true
        response.data.items.size() == itemCount
        response.data.metadata.continue == nextToken
        response.data.metadata.remainingItemCount == remaining

        where:
        continueToken | nextToken | remaining | itemCount
        null          | "token123"| 100       | 50
        "token123"    | "token456"| 50        | 50
        "token456"    | ""        | 0         | 25
    }

    def "callApiWithPagination should handle response as String"() {
        given:
        def baseUrl = "https://api.example.com/items"
        def token = "test-token"
        def pageSize = 50

        and:
        def responseDataMap = [
            kind: "List",
            apiVersion: "v1",
            metadata: [
                resourceVersion: "12345",
                continue: "",
                remainingItemCount: 0
            ],
            items: [[id: 1, name: "item-1"]]
        ]
        def mockResponse = new ServiceResponse(success: true, data: groovy.json.JsonOutput.toJson(responseDataMap))
        GroovySpy(HttpApiClient, global: true)
        def mockClient = Mock(HttpApiClient)
        new HttpApiClient() >> mockClient

        when:
        def response = CommonApiHelper.callApiWithPagination(baseUrl, token, null, pageSize)

        then:
        1 * mockClient.callJsonApi(_, null, _, "GET") >> mockResponse
        response.success == true
        response.data.items.size() == 1
    }

    def "callApiWithPagination should handle API failure"() {
        given:
        def baseUrl = "https://api.example.com/items"
        def token = "test-token"
        def pageSize = 50

        and:
        def mockResponse = new ServiceResponse(success: false, msg: "Connection timeout")
        GroovySpy(HttpApiClient, global: true)
        def mockClient = Mock(HttpApiClient)
        new HttpApiClient() >> mockClient

        when:
        def response = CommonApiHelper.callApiWithPagination(baseUrl, token, null, pageSize)

        then:
        1 * mockClient.callJsonApi(_, null, _, "GET") >> mockResponse
        response.success == false
    }

    def "callApiWithPagination should build correct URL with pagination params"() {
        given:
        def baseUrl = "https://api.example.com/items"
        def token = "test-token"
        def continueToken = "abc123"
        def pageSize = 25

        and:
        def mockResponse = new ServiceResponse(success: true, data: [
            metadata: [:],
            items: []
        ])
        GroovySpy(HttpApiClient, global: true)
        def mockClient = Mock(HttpApiClient)
        new HttpApiClient() >> mockClient

        when:
        CommonApiHelper.callApiWithPagination(baseUrl, token, continueToken, pageSize)

        then:
        1 * mockClient.callJsonApi({ String url ->
            url.contains("limit=25") && url.contains("continue=abc123")
        }, null, _, "GET") >> mockResponse
    }

    def "callApiWithPagination should handle URL with existing query params"() {
        given:
        def baseUrl = "https://api.example.com/items?filter=active"
        def token = "test-token"
        def pageSize = 30

        and:
        def mockResponse = new ServiceResponse(success: true, data: [
            metadata: [:],
            items: []
        ])
        GroovySpy(HttpApiClient, global: true)
        def mockClient = Mock(HttpApiClient)
        new HttpApiClient() >> mockClient

        when:
        CommonApiHelper.callApiWithPagination(baseUrl, token, null, pageSize)

        then:
        1 * mockClient.callJsonApi({ String url ->
            url.contains("filter=active") && url.contains("&limit=30")
        }, null, _, "GET") >> mockResponse
    }

    def "get should make successful GET request"() {
        given:
        def url = "https://api.example.com"
        def path = "/api/v1/pods"
        def token = "test-token"

        and:
        def mockResponse = new ServiceResponse(success: true, data: [items: []])
        GroovySpy(HttpApiClient, global: true)
        def mockClient = Mock(HttpApiClient)
        new HttpApiClient() >> mockClient

        when:
        def response = CommonApiHelper.get(url, path, token)

        then:
        1 * mockClient.callJsonApi(url, path, _, "GET") >> mockResponse
        response.success == true
    }

    def "get should include query parameters"() {
        given:
        def url = "https://api.example.com"
        def path = "/api/v1/pods"
        def token = "test-token"
        def queryParams = [labelSelector: "app=test", limit: "10"]

        and:
        def mockResponse = new ServiceResponse(success: true, data: [items: []])
        GroovySpy(HttpApiClient, global: true)
        def mockClient = Mock(HttpApiClient)
        new HttpApiClient() >> mockClient

        when:
        def response = CommonApiHelper.get(url, path, token, queryParams)

        then:
        1 * mockClient.callJsonApi(url, path, { opts ->
            opts.queryParams == queryParams
        }, "GET") >> mockResponse
        response.success == true
    }

    def "post should make successful POST request"() {
        given:
        def url = "https://api.example.com"
        def path = "/api/v1/namespaces/default/pods"
        def token = "test-token"
        def body = [kind: "Pod", metadata: [name: "test-pod"]]

        and:
        def mockResponse = new ServiceResponse(success: true, data: body)
        GroovySpy(HttpApiClient, global: true)
        def mockClient = Mock(HttpApiClient)
        new HttpApiClient() >> mockClient

        when:
        def response = CommonApiHelper.post(url, path, token, body)

        then:
        1 * mockClient.callJsonApi(url, path, { opts ->
            opts.body == body
        }, "POST") >> mockResponse
        response.success == true
    }

    def "post should include query parameters when provided"() {
        given:
        def url = "https://api.example.com"
        def path = "/api/v1/namespaces/default/pods"
        def token = "test-token"
        def body = [kind: "Pod"]
        def queryParams = [dryRun: "All"]

        and:
        def mockResponse = new ServiceResponse(success: true, data: body)
        GroovySpy(HttpApiClient, global: true)
        def mockClient = Mock(HttpApiClient)
        new HttpApiClient() >> mockClient

        when:
        def response = CommonApiHelper.post(url, path, token, body, queryParams)

        then:
        1 * mockClient.callJsonApi(url, path, { opts ->
            opts.body == body && opts.queryParams == queryParams
        }, "POST") >> mockResponse
        response.success == true
    }

    def "put should make successful PUT request"() {
        given:
        def url = "https://api.example.com"
        def path = "/api/v1/namespaces/default/pods/test-pod"
        def token = "test-token"
        def body = [kind: "Pod", metadata: [name: "test-pod", resourceVersion: "123"]]

        and:
        def mockResponse = new ServiceResponse(success: true, data: body)
        GroovySpy(HttpApiClient, global: true)
        def mockClient = Mock(HttpApiClient)
        new HttpApiClient() >> mockClient

        when:
        def response = CommonApiHelper.put(url, path, token, body)

        then:
        1 * mockClient.callJsonApi(url, path, { opts ->
            opts.body == body
        }, "PUT") >> mockResponse
        response.success == true
    }

    @Unroll
    def "patch should make PATCH request with contentType=#contentType"() {
        given:
        def url = "https://api.example.com"
        def path = "/api/v1/namespaces/default/pods/test-pod"
        def token = "test-token"
        def body = [[op: "replace", path: "/spec/replicas", value: 3]]

        and:
        def mockResponse = new ServiceResponse(success: true, data: [:])
        GroovySpy(HttpApiClient, global: true)
        def mockClient = Mock(HttpApiClient)
        new HttpApiClient() >> mockClient

        when:
        def response = CommonApiHelper.patch(url, path, token, body, contentType)

        then:
        1 * mockClient.callJsonApi(url, path, { opts ->
            opts.body == body && opts.contentType == contentType
        }, "PATCH") >> mockResponse
        response.success == true

        where:
        contentType << [
            'application/json-patch+json',
            'application/merge-patch+json',
            'application/strategic-merge-patch+json'
        ]
    }

    def "patch should use default content type"() {
        given:
        def url = "https://api.example.com"
        def path = "/api/v1/namespaces/default/pods/test-pod"
        def token = "test-token"
        def body = [[op: "add", path: "/metadata/labels/new", value: "label"]]

        and:
        def mockResponse = new ServiceResponse(success: true, data: [:])
        GroovySpy(HttpApiClient, global: true)
        def mockClient = Mock(HttpApiClient)
        new HttpApiClient() >> mockClient

        when:
        def response = CommonApiHelper.patch(url, path, token, body)

        then:
        1 * mockClient.callJsonApi(url, path, { opts ->
            opts.contentType == 'application/json-patch+json'
        }, "PATCH") >> mockResponse
        response.success == true
    }

    def "patch should include query parameters"() {
        given:
        def url = "https://api.example.com"
        def path = "/api/v1/namespaces/default/pods/test-pod"
        def token = "test-token"
        def body = [[op: "replace", path: "/spec/replicas", value: 5]]
        def queryParams = [fieldManager: "kubectl"]

        and:
        def mockResponse = new ServiceResponse(success: true, data: [:])
        GroovySpy(HttpApiClient, global: true)
        def mockClient = Mock(HttpApiClient)
        new HttpApiClient() >> mockClient

        when:
        def response = CommonApiHelper.patch(url, path, token, body, 'application/json-patch+json', queryParams)

        then:
        1 * mockClient.callJsonApi(url, path, { opts ->
            opts.queryParams == queryParams
        }, "PATCH") >> mockResponse
        response.success == true
    }

    def "delete should make successful DELETE request"() {
        given:
        def url = "https://api.example.com"
        def path = "/api/v1/namespaces/default/pods/test-pod"
        def token = "test-token"

        and:
        def mockResponse = new ServiceResponse(success: true, data: [status: "Success"])
        GroovySpy(HttpApiClient, global: true)
        def mockClient = Mock(HttpApiClient)
        new HttpApiClient() >> mockClient

        when:
        def response = CommonApiHelper.delete(url, path, token)

        then:
        1 * mockClient.callJsonApi(url, path, _, "DELETE") >> mockResponse
        response.success == true
    }

    def "delete should include request body when provided"() {
        given:
        def url = "https://api.example.com"
        def path = "/api/v1/namespaces/default/pods/test-pod"
        def token = "test-token"
        def body = [gracePeriodSeconds: 30, propagationPolicy: "Foreground"]

        and:
        def mockResponse = new ServiceResponse(success: true, data: [status: "Success"])
        GroovySpy(HttpApiClient, global: true)
        def mockClient = Mock(HttpApiClient)
        new HttpApiClient() >> mockClient

        when:
        def response = CommonApiHelper.delete(url, path, token, body)

        then:
        1 * mockClient.callJsonApi(url, path, { opts ->
            opts.body == body
        }, "DELETE") >> mockResponse
        response.success == true
    }

    def "delete should include query parameters"() {
        given:
        def url = "https://api.example.com"
        def path = "/api/v1/namespaces/default/pods/test-pod"
        def token = "test-token"
        def queryParams = [gracePeriodSeconds: "0"]

        and:
        def mockResponse = new ServiceResponse(success: true, data: [status: "Success"])
        GroovySpy(HttpApiClient, global: true)
        def mockClient = Mock(HttpApiClient)
        new HttpApiClient() >> mockClient

        when:
        def response = CommonApiHelper.delete(url, path, token, null, queryParams)

        then:
        1 * mockClient.callJsonApi(url, path, { opts ->
            opts.queryParams == queryParams
        }, "DELETE") >> mockResponse
        response.success == true
    }

    def "delete should block bulk delete without selector on collection path"() {
        given:
        def url = "https://api.example.com"
        def path = "/api/v1/namespaces/default/pods"
        def token = "test-token"

        when:
        def response = CommonApiHelper.delete(url, path, token)

        then:
        response.success == false
        response.msg.contains("Bulk delete without selector is not allowed")
    }

    def "delete should allow bulk delete with labelSelector"() {
        given:
        def url = "https://api.example.com"
        def path = "/api/v1/namespaces/default/pods"
        def token = "test-token"
        def queryParams = [labelSelector: "app=test"]

        and:
        def mockResponse = new ServiceResponse(success: true, data: [status: "Success"])
        GroovySpy(HttpApiClient, global: true)
        def mockClient = Mock(HttpApiClient)
        new HttpApiClient() >> mockClient

        when:
        def response = CommonApiHelper.delete(url, path, token, null, queryParams)

        then:
        1 * mockClient.callJsonApi(url, path, _, "DELETE") >> mockResponse
        response.success == true
    }

    def "delete should allow bulk delete with fieldSelector"() {
        given:
        def url = "https://api.example.com"
        def path = "/api/v1/namespaces/default/pods"
        def token = "test-token"
        def queryParams = [fieldSelector: "status.phase=Failed"]

        and:
        def mockResponse = new ServiceResponse(success: true, data: [status: "Success"])
        GroovySpy(HttpApiClient, global: true)
        def mockClient = Mock(HttpApiClient)
        new HttpApiClient() >> mockClient

        when:
        def response = CommonApiHelper.delete(url, path, token, null, queryParams)

        then:
        1 * mockClient.callJsonApi(url, path, _, "DELETE") >> mockResponse
        response.success == true
    }

    @Unroll
    def "delete should block bulk delete on collection: #collectionPath"() {
        given:
        def url = "https://api.example.com"
        def token = "test-token"

        when:
        def response = CommonApiHelper.delete(url, collectionPath, token)

        then:
        response.success == false
        response.msg.contains("Bulk delete without selector is not allowed")

        where:
        collectionPath << [
            "/api/v1/namespaces/default/virtualmachines",
            "/api/v1/namespaces/default/virtualmachineinstances/",
            "/api/v1/namespaces/default/datavolumes",
            "/api/v1/namespaces/default/persistentvolumeclaims",
            "/api/v1/namespaces/default/secrets",
            "/api/v1/namespaces/default/configmaps",
            "/api/v1/namespaces/default/pods",
            "/api/v1/namespaces/default/services",
            "/api/v1/nodes",
            "/api/v1/projects",
            "/api/v1/namespaces",
            "/api/v1/serviceaccounts"
        ]
    }

    def "delete should allow single resource deletion"() {
        given:
        def url = "https://api.example.com"
        def path = "/api/v1/namespaces/default/pods/my-specific-pod"
        def token = "test-token"

        and:
        def mockResponse = new ServiceResponse(success: true, data: [status: "Success"])
        GroovySpy(HttpApiClient, global: true)
        def mockClient = Mock(HttpApiClient)
        new HttpApiClient() >> mockClient

        when:
        def response = CommonApiHelper.delete(url, path, token)

        then:
        1 * mockClient.callJsonApi(url, path, _, "DELETE") >> mockResponse
        response.success == true
    }

    @Unroll
    def "isSystemNamespace should return #expected for #namespace"() {
        expect:
        CommonApiHelper.isSystemNamespace(namespace) == expected

        where:
        namespace                      | expected
        null                          | true
        ""                            | true
        "default"                     | false
        "kube-system"                 | true
        "kube-public"                 | true
        "kube-node-lease"             | true
        "harvester-system"            | true
        "harvester-public"            | true
        "longhorn-system"             | true
        "cattle-system"               | true
        "cattle-fleet-system"         | true
        "fleet-local"                 | true
        "p-ab12c"                     | true
        "c-m-abcd1234"                | true
        "calico-system"               | true
        "calico-apiserver"            | true
        "tigera-operator"             | true
        "tigera-compliance"           | true
        "harvester"                   | false
        "my-app"                      | false
        "production"                  | false
        "test-namespace"              | false
        "user-workloads"              | false
    }

    def "isSystemNamespace should filter system namespace prefixes"() {
        expect:
        CommonApiHelper.isSystemNamespace("longhorn-custom") == true
        CommonApiHelper.isSystemNamespace("cattle-custom") == true
        CommonApiHelper.isSystemNamespace("kube-custom") == true
        CommonApiHelper.isSystemNamespace("calico-custom") == true
        CommonApiHelper.isSystemNamespace("tigera-custom") == true
    }

    def "isSystemNamespace should not filter non-system namespaces with similar names"() {
        expect:
        CommonApiHelper.isSystemNamespace("my-harvester-app") == false
        CommonApiHelper.isSystemNamespace("custom-kube") == false
        CommonApiHelper.isSystemNamespace("app-calico") == false
        CommonApiHelper.isSystemNamespace("production-vms") == false
        CommonApiHelper.isSystemNamespace("p-team") == false
    }
}
