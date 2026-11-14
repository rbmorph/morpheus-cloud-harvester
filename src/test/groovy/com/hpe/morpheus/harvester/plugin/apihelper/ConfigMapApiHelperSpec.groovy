// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.apihelper

import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Unit tests for ConfigMapApiHelper class
 */
class ConfigMapApiHelperSpec extends Specification {

    ConfigMapApiHelper configMapApiHelper
    HarvesterApiClient mockApiClient

    def setup() {
        mockApiClient = Mock(HarvesterApiClient)
        configMapApiHelper = new ConfigMapApiHelper(mockApiClient)
    }

    def "createConfigMap should create ConfigMap successfully"() {
        given:
        def namespace = "default"
        def name = "test-sysprep"
        def xmlContent = "<?xml version=\"1.0\"?><unattend></unattend>"

        and:
        def expectedResponse = new ServiceResponse(
            success: true,
            data: [
                apiVersion: "v1",
                kind: "ConfigMap",
                metadata: [name: name, namespace: namespace]
            ]
        )

        when:
        def response = configMapApiHelper.createConfigMap(namespace, name, xmlContent)

        then:
        1 * mockApiClient.post("/api/v1/namespaces/${namespace}/configmaps", { body ->
            body.apiVersion == "v1" &&
            body.kind == "ConfigMap" &&
            body.metadata.name == name &&
            body.metadata.namespace == namespace &&
            body.data["unattend.xml"] == xmlContent &&
            body.metadata.labels["morpheus.io/sysprep"] == "true" &&
            body.metadata.labels["morpheus.io/type"] == "unattend-xml"
        }) >> expectedResponse

        response.success == true
        response.data.metadata.name == name
    }

    def "createConfigMap should include additional labels"() {
        given:
        def namespace = "default"
        def name = "test-sysprep"
        def xmlContent = "<?xml version=\"1.0\"?><unattend></unattend>"
        def additionalLabels = ["app": "test-app", "env": "production"]

        and:
        def expectedResponse = new ServiceResponse(success: true, data: [:])

        when:
        def response = configMapApiHelper.createConfigMap(namespace, name, xmlContent, additionalLabels)

        then:
        1 * mockApiClient.post(_, { body ->
            body.metadata.labels["app"] == "test-app" &&
            body.metadata.labels["env"] == "production" &&
            body.metadata.labels["morpheus.io/sysprep"] == "true"
        }) >> expectedResponse

        response.success == true
    }

    def "createConfigMap should include additional annotations"() {
        given:
        def namespace = "default"
        def name = "test-sysprep"
        def xmlContent = "<?xml version=\"1.0\"?><unattend></unattend>"
        def additionalAnnotations = ["description": "Test ConfigMap", "owner": "admin"]

        and:
        def expectedResponse = new ServiceResponse(success: true, data: [:])

        when:
        def response = configMapApiHelper.createConfigMap(
            namespace, name, xmlContent, [:], additionalAnnotations
        )

        then:
        1 * mockApiClient.post(_, { body ->
            body.metadata.annotations["description"] == "Test ConfigMap" &&
            body.metadata.annotations["owner"] == "admin"
        }) >> expectedResponse

        response.success == true
    }

    @Unroll
    def "createConfigMap should validate required parameters: namespace=#namespace, name=#name, xml=#hasXml"() {
        given:
        def xmlContent = hasXml ? "<?xml version=\"1.0\"?><unattend></unattend>" : null

        when:
        def response = configMapApiHelper.createConfigMap(namespace, name, xmlContent)

        then:
        0 * mockApiClient.post(_, _)
        response.success == false
        response.errors?.error?.contains("required")

        where:
        namespace | name        | hasXml
        null      | "test"      | true
        ""        | "test"      | true
        "default" | null        | true
        "default" | ""          | true
        "default" | "test"      | false
        null      | null        | false
    }

    def "createConfigMap should handle API client null response"() {
        given:
        def namespace = "default"
        def name = "test-sysprep"
        def xmlContent = "<?xml version=\"1.0\"?><unattend></unattend>"

        when:
        def response = configMapApiHelper.createConfigMap(namespace, name, xmlContent)

        then:
        1 * mockApiClient.post(_, _) >> null
        response.success == false
        response.errors?.error?.contains("null response")
    }

    def "createConfigMap should handle API failure"() {
        given:
        def namespace = "default"
        def name = "test-sysprep"
        def xmlContent = "<?xml version=\"1.0\"?><unattend></unattend>"

        and:
        def failureResponse = new ServiceResponse(
            success: false,
            msg: "ConfigMap already exists",
            data: [message: "ConfigMap 'test-sysprep' already exists"]
        )

        when:
        def response = configMapApiHelper.createConfigMap(namespace, name, xmlContent)

        then:
        1 * mockApiClient.post(_, _) >> failureResponse
        response.success == false
        response.msg.contains("Failed to create ConfigMap")
    }

    def "createConfigMap should handle exception"() {
        given:
        def namespace = "default"
        def name = "test-sysprep"
        def xmlContent = "<?xml version=\"1.0\"?><unattend></unattend>"

        when:
        def response = configMapApiHelper.createConfigMap(namespace, name, xmlContent)

        then:
        1 * mockApiClient.post(_, _) >> { throw new RuntimeException("Network error") }
        response.success == false
        response.errors?.error?.contains("Exception creating ConfigMap")
        response.errors?.error?.contains("Network error")
    }

    def "getConfigMap should retrieve ConfigMap successfully"() {
        given:
        def namespace = "default"
        def name = "test-sysprep"

        and:
        def expectedResponse = new ServiceResponse(
            success: true,
            data: [
                apiVersion: "v1",
                kind: "ConfigMap",
                metadata: [name: name, namespace: namespace],
                data: ["unattend.xml": "<?xml version=\"1.0\"?><unattend></unattend>"]
            ]
        )

        when:
        def response = configMapApiHelper.getConfigMap(namespace, name)

        then:
        1 * mockApiClient.get("/api/v1/namespaces/${namespace}/configmaps/${name}") >> expectedResponse
        response.success == true
        response.data.metadata.name == name
    }

    @Unroll
    def "getConfigMap should validate required parameters: namespace=#namespace, name=#name"() {
        when:
        def response = configMapApiHelper.getConfigMap(namespace, name)

        then:
        0 * mockApiClient.get(_)
        response.success == false
        response.errors?.error?.contains("required")

        where:
        namespace | name
        null      | "test"
        ""        | "test"
        "default" | null
        "default" | ""
    }

    def "getConfigMap should handle not found"() {
        given:
        def namespace = "default"
        def name = "non-existent"

        and:
        def notFoundResponse = new ServiceResponse(
            success: false,
            msg: "ConfigMap not found"
        )

        when:
        def response = configMapApiHelper.getConfigMap(namespace, name)

        then:
        1 * mockApiClient.get("/api/v1/namespaces/${namespace}/configmaps/${name}") >> notFoundResponse
        response.success == false
    }

    def "getConfigMap should handle exception"() {
        given:
        def namespace = "default"
        def name = "test-sysprep"

        when:
        def response = configMapApiHelper.getConfigMap(namespace, name)

        then:
        1 * mockApiClient.get(_) >> { throw new RuntimeException("Connection timeout") }
        response.success == false
        response.errors?.error?.contains("Exception retrieving ConfigMap")
    }

    def "updateConfigMap should update existing ConfigMap successfully"() {
        given:
        def namespace = "default"
        def name = "test-sysprep"
        def newXmlContent = "<?xml version=\"1.0\"?><unattend><settings></settings></unattend>"

        and:
        def existingConfigMap = [
            apiVersion: "v1",
            kind: "ConfigMap",
            metadata: [
                name: name,
                namespace: namespace,
                resourceVersion: "12345"
            ],
            data: ["unattend.xml": "old content"]
        ]
        def getResponse = new ServiceResponse(success: true, data: existingConfigMap)
        def updateResponse = new ServiceResponse(success: true, data: existingConfigMap)

        when:
        def response = configMapApiHelper.updateConfigMap(namespace, name, newXmlContent)

        then:
        1 * mockApiClient.get("/api/v1/namespaces/${namespace}/configmaps/${name}") >> getResponse
        1 * mockApiClient.put("/api/v1/namespaces/${namespace}/configmaps/${name}", { body ->
            body.data["unattend.xml"] == newXmlContent &&
            body.metadata.resourceVersion == "12345"
        }) >> updateResponse

        response.success == true
    }

    def "updateConfigMap should fail if ConfigMap does not exist"() {
        given:
        def namespace = "default"
        def name = "non-existent"
        def newXmlContent = "<?xml version=\"1.0\"?><unattend></unattend>"

        and:
        def getResponse = new ServiceResponse(success: false, msg: "Not found")

        when:
        def response = configMapApiHelper.updateConfigMap(namespace, name, newXmlContent)

        then:
        1 * mockApiClient.get(_) >> getResponse
        0 * mockApiClient.put(_, _)
        response.success == false
        response.errors?.error?.contains("does not exist")
    }

    def "updateConfigMap should handle PUT failure"() {
        given:
        def namespace = "default"
        def name = "test-sysprep"
        def newXmlContent = "<?xml version=\"1.0\"?><unattend></unattend>"

        and:
        def getResponse = new ServiceResponse(success: true, data: [
            metadata: [name: name, namespace: namespace],
            data: ["unattend.xml": "old"]
        ])
        def putResponse = new ServiceResponse(
            success: false,
            data: [message: "Conflict: resource version mismatch"]
        )

        when:
        def response = configMapApiHelper.updateConfigMap(namespace, name, newXmlContent)

        then:
        1 * mockApiClient.get(_) >> getResponse
        1 * mockApiClient.put(_, _) >> putResponse
        response.success == false
        response.errors?.error?.contains("Failed to update ConfigMap")
    }

    def "updateConfigMap should handle exception"() {
        given:
        def namespace = "default"
        def name = "test-sysprep"
        def newXmlContent = "<?xml version=\"1.0\"?><unattend></unattend>"

        when:
        def response = configMapApiHelper.updateConfigMap(namespace, name, newXmlContent)

        then:
        1 * mockApiClient.get(_) >> { throw new RuntimeException("Network error") }
        response.success == false
        response.errors?.error?.contains("ConfigMap does not exist")
    }

    def "deleteConfigMap should delete ConfigMap successfully"() {
        given:
        def namespace = "default"
        def name = "test-sysprep"

        and:
        def deleteResponse = new ServiceResponse(
            success: true,
            data: [status: "Success"]
        )

        when:
        def response = configMapApiHelper.deleteConfigMap(namespace, name)

        then:
        1 * mockApiClient.delete("/api/v1/namespaces/${namespace}/configmaps/${name}") >> deleteResponse
        response.success == true
    }

    @Unroll
    def "deleteConfigMap should validate required parameters: namespace=#namespace, name=#name"() {
        when:
        def response = configMapApiHelper.deleteConfigMap(namespace, name)

        then:
        0 * mockApiClient.delete(_)
        response.success == false
        response.errors?.error?.contains("required")

        where:
        namespace | name
        null      | "test"
        ""        | "test"
        "default" | null
        "default" | ""
    }

    def "deleteConfigMap should handle API client null response"() {
        given:
        def namespace = "default"
        def name = "test-sysprep"

        when:
        def response = configMapApiHelper.deleteConfigMap(namespace, name)

        then:
        1 * mockApiClient.delete(_) >> null
        response.success == false
        response.errors?.error?.contains("null response")
    }

    def "deleteConfigMap should handle deletion failure"() {
        given:
        def namespace = "default"
        def name = "test-sysprep"

        and:
        def deleteResponse = new ServiceResponse(
            success: false,
            data: [reason: "NotFound", message: "ConfigMap not found"]
        )

        when:
        def response = configMapApiHelper.deleteConfigMap(namespace, name)

        then:
        1 * mockApiClient.delete(_) >> deleteResponse
        response.success == false
        response.msg.contains("Failed to delete ConfigMap")
    }

    def "deleteConfigMap should handle exception"() {
        given:
        def namespace = "default"
        def name = "test-sysprep"

        when:
        def response = configMapApiHelper.deleteConfigMap(namespace, name)

        then:
        1 * mockApiClient.delete(_) >> { throw new RuntimeException("API error") }
        response.success == false
        response.errors?.error?.contains("Exception deleting ConfigMap")
    }

    def "configMapExists should return true when ConfigMap exists"() {
        given:
        def namespace = "default"
        def name = "test-sysprep"

        and:
        def getResponse = new ServiceResponse(success: true, data: [:])

        when:
        def exists = configMapApiHelper.configMapExists(namespace, name)

        then:
        1 * mockApiClient.get("/api/v1/namespaces/${namespace}/configmaps/${name}") >> getResponse
        exists == true
    }

    def "configMapExists should return false when ConfigMap does not exist"() {
        given:
        def namespace = "default"
        def name = "non-existent"

        and:
        def getResponse = new ServiceResponse(success: false, msg: "Not found")

        when:
        def exists = configMapApiHelper.configMapExists(namespace, name)

        then:
        1 * mockApiClient.get(_) >> getResponse
        exists == false
    }

    def "getUnattendXmlContent should return XML content"() {
        given:
        def namespace = "default"
        def name = "test-sysprep"
        def xmlContent = "<?xml version=\"1.0\"?><unattend><settings></settings></unattend>"

        and:
        def getResponse = new ServiceResponse(
            success: true,
            data: [
                metadata: [name: name],
                data: ["unattend.xml": xmlContent]
            ]
        )

        when:
        def result = configMapApiHelper.getUnattendXmlContent(namespace, name)

        then:
        1 * mockApiClient.get(_) >> getResponse
        result == xmlContent
    }

    def "getUnattendXmlContent should return null when ConfigMap not found"() {
        given:
        def namespace = "default"
        def name = "non-existent"

        and:
        def getResponse = new ServiceResponse(success: false, msg: "Not found")

        when:
        def result = configMapApiHelper.getUnattendXmlContent(namespace, name)

        then:
        1 * mockApiClient.get(_) >> getResponse
        result == null
    }

    def "getUnattendXmlContent should return null when data is missing"() {
        given:
        def namespace = "default"
        def name = "test-sysprep"

        and:
        def getResponse = new ServiceResponse(
            success: true,
            data: [metadata: [name: name]]
        )

        when:
        def result = configMapApiHelper.getUnattendXmlContent(namespace, name)

        then:
        1 * mockApiClient.get(_) >> getResponse
        result == null
    }

    def "getUnattendXmlContent should return null when response is null"() {
        given:
        def namespace = "default"
        def name = "test-sysprep"

        when:
        def result = configMapApiHelper.getUnattendXmlContent(namespace, name)

        then:
        1 * mockApiClient.get(_) >> null
        result == null
    }

    def "getUnattendXmlContent should handle non-Map response data"() {
        given:
        def namespace = "default"
        def name = "test-sysprep"

        and:
        def getResponse = new ServiceResponse(success: true, data: "invalid data")

        when:
        def result = configMapApiHelper.getUnattendXmlContent(namespace, name)

        then:
        1 * mockApiClient.get(_) >> getResponse
        result == null
    }

    def "createConfigMap should extract error from reason when msg is missing"() {
        given:
        def failureResponse = new ServiceResponse(
            success: false,
            data: [reason: 'Forbidden']
        )

        when:
        def response = configMapApiHelper.createConfigMap('default', 'test-sysprep', '<xml/>')

        then:
        1 * mockApiClient.post(_, _) >> failureResponse
        !response.success
        response.msg.contains('Failed to create ConfigMap: Forbidden')
    }

    def "deleteConfigMap should extract error details map when reason and msg are missing"() {
        given:
        def failureResponse = new ServiceResponse(
            success: false,
            data: [details: [kind: 'ConfigMap', name: 'cm-1']]
        )

        when:
        def response = configMapApiHelper.deleteConfigMap('default', 'cm-1')

        then:
        1 * mockApiClient.delete(_) >> failureResponse
        !response.success
        response.msg.contains('Failed to delete ConfigMap: Error details:')
        response.msg.contains('kind=ConfigMap')
        response.msg.contains('name=cm-1')
    }

    def "updateConfigMap should return unknown error fallback when response lacks msg and data fields"() {
        given:
        def existing = [metadata: [name: 'cm-1'], data: ['unattend.xml': 'old']]
        def updateFailure = new ServiceResponse(success: false, data: [:])

        when:
        def response = configMapApiHelper.updateConfigMap('default', 'cm-1', 'new')

        then:
        1 * mockApiClient.get(_) >> ServiceResponse.success(existing)
        1 * mockApiClient.put(_, _) >> updateFailure
        !response.success
        response.errors.error.contains('Failed to update ConfigMap: Unknown error')
    }

    // --- createConfigMap tests ---

    def "createConfigMap returns error on API failure"() {
        given:
        mockApiClient.post(_, _) >> new ServiceResponse(success: false, msg: 'create failed')

        when:
        def response = configMapApiHelper.createConfigMap('default', 'new-cm', '<xml>content</xml>')

        then:
        !response.success
    }

    def "createConfigMap handles exception"() {
        given:
        mockApiClient.post(_, _) >> { throw new RuntimeException('network error') }

        when:
        def response = configMapApiHelper.createConfigMap('default', 'err-cm', '<xml>content</xml>')

        then:
        !response.success
    }

    // --- getConfigMap tests ---

    def "getConfigMap returns error on API failure"() {
        given:
        mockApiClient.get(_) >> new ServiceResponse(success: false, msg: 'not found')

        when:
        def response = configMapApiHelper.getConfigMap('default', 'missing-cm')

        then:
        !response.success
    }

    def "getConfigMap handles exception"() {
        given:
        mockApiClient.get(_) >> { throw new RuntimeException('conn error') }

        when:
        def response = configMapApiHelper.getConfigMap('default', 'err-cm')

        then:
        !response.success
    }

    // --- deleteConfigMap tests ---

    def "deleteConfigMap handles exception"() {
        given:
        mockApiClient.delete(_, _) >> { throw new RuntimeException('delete error') }

        when:
        def response = configMapApiHelper.deleteConfigMap('default', 'err-cm')

        then:
        !response.success
    }

    // --- updateConfigMap tests ---

    def "updateConfigMap handles exception from get call"() {
        given:
        mockApiClient.get(_) >> { throw new RuntimeException('get error') }

        when:
        def response = configMapApiHelper.updateConfigMap('default', 'up-cm', 'new-data')

        then:
        !response.success
    }
}
