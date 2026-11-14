package com.hpe.morpheus.harvester.plugin.events

import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Unroll

class HarvesterResourceEventsSpec extends Specification {

    def "constructor should assign compute server group"() {
        given:
        def mockCluster = Mock(ComputeServerGroup)

        when:
        def resourceEvents = new HarvesterResourceEvents(mockCluster)

        then:
        resourceEvents.computeServerGroup == mockCluster
        resourceEvents.harvesterApiClient == null
    }

    def "constructor should allow null compute server group"() {
        when:
        def resourceEvents = new HarvesterResourceEvents(null)

        then:
        resourceEvents.computeServerGroup == null
        resourceEvents.harvesterApiClient == null
    }

    def "fetchEventsByNamespace should lazily initialize api client and return response"() {
        given:
        def mockCluster = Mock(ComputeServerGroup)
        def mockClient = Mock(HarvesterApiClient)
        def expectedResponse = new ServiceResponse(success: true, data: [items: [[message: 'event-1']]])
        def namespace = "test-namespace"
        def objectName = "test-vm"
        def objectKind = "VirtualMachine"

        GroovySpy(HarvesterApiClient, global: true)
        def resourceEvents = new HarvesterResourceEvents(mockCluster)

        when:
        def response = resourceEvents.fetchEventsByNamespace(namespace, objectName, objectKind)

        then:
        1 * new HarvesterApiClient(mockCluster) >> mockClient
        1 * mockClient.get(
            "/api/v1/namespaces/test-namespace/events",
            [fieldSelector: "involvedObject.name=test-vm,involvedObject.kind=VirtualMachine"]
        ) >> expectedResponse
        response == expectedResponse
        resourceEvents.harvesterApiClient == mockClient
    }

    def "fetchEventsByNamespace should initialize client once and reuse for subsequent calls"() {
        given:
        def mockCluster = Mock(ComputeServerGroup)
        def mockClient = Mock(HarvesterApiClient)
        def firstResponse = new ServiceResponse(success: true, data: [items: [[message: 'first']]])
        def secondResponse = new ServiceResponse(success: true, data: [items: [[message: 'second']]])

        GroovySpy(HarvesterApiClient, global: true)
        def resourceEvents = new HarvesterResourceEvents(mockCluster)

        when:
        def responseOne = resourceEvents.fetchEventsByNamespace("ns-1", "vm-1", "VirtualMachine")
        def responseTwo = resourceEvents.fetchEventsByNamespace("ns-2", "pod-1", "Pod")

        then:
        1 * new HarvesterApiClient(mockCluster) >> mockClient
        1 * mockClient.get(
            "/api/v1/namespaces/ns-1/events",
            [fieldSelector: "involvedObject.name=vm-1,involvedObject.kind=VirtualMachine"]
        ) >> firstResponse
        1 * mockClient.get(
            "/api/v1/namespaces/ns-2/events",
            [fieldSelector: "involvedObject.name=pod-1,involvedObject.kind=Pod"]
        ) >> secondResponse

        responseOne == firstResponse
        responseTwo == secondResponse
        resourceEvents.harvesterApiClient == mockClient
    }

    def "fetchEventsByNamespace should use existing api client when already initialized"() {
        given:
        def mockCluster = Mock(ComputeServerGroup)
        def mockClient = Mock(HarvesterApiClient)
        def expectedResponse = new ServiceResponse(success: true, data: [items: []])
        def resourceEvents = new HarvesterResourceEvents(mockCluster)
        resourceEvents.harvesterApiClient = mockClient

        when:
        def response = resourceEvents.fetchEventsByNamespace("my-ns", "my-object", "Pod")

        then:
        1 * mockClient.get(
            "/api/v1/namespaces/my-ns/events",
            [fieldSelector: "involvedObject.name=my-object,involvedObject.kind=Pod"]
        ) >> expectedResponse
        response == expectedResponse
    }

    @Unroll
    def "fetchEventsByNamespace should build uri and selector correctly for #namespace/#objectName/#objectKind"() {
        given:
        def mockCluster = Mock(ComputeServerGroup)
        def mockClient = Mock(HarvesterApiClient)
        def expectedResponse = new ServiceResponse(success: true, data: [items: []])
        def resourceEvents = new HarvesterResourceEvents(mockCluster)
        resourceEvents.harvesterApiClient = mockClient

        when:
        def response = resourceEvents.fetchEventsByNamespace(namespace, objectName, objectKind)

        then:
        1 * mockClient.get(expectedUri, [fieldSelector: expectedFieldSelector]) >> expectedResponse
        response == expectedResponse

        where:
        namespace    | objectName  | objectKind                   || expectedUri                                | expectedFieldSelector
        "default"   | "vm-a"     | "VirtualMachine"            || "/api/v1/namespaces/default/events"        | "involvedObject.name=vm-a,involvedObject.kind=VirtualMachine"
        "qa-team"   | "pod-001"  | "Pod"                       || "/api/v1/namespaces/qa-team/events"        | "involvedObject.name=pod-001,involvedObject.kind=Pod"
        "ns"        | ""         | "VirtualMachineInstance"    || "/api/v1/namespaces/ns/events"             | "involvedObject.name=,involvedObject.kind=VirtualMachineInstance"
        "sandbox"   | null        | null                         || "/api/v1/namespaces/sandbox/events"        | "involvedObject.name=null,involvedObject.kind=null"
    }

    def "fetchEventsByNamespace should return error response when api client throws exception"() {
        given:
        def mockCluster = Mock(ComputeServerGroup)
        def mockClient = Mock(HarvesterApiClient)
        def resourceEvents = new HarvesterResourceEvents(mockCluster)
        resourceEvents.harvesterApiClient = mockClient

        when:
        def response = resourceEvents.fetchEventsByNamespace("ns", "obj", "Pod")

        then:
        1 * mockClient.get(
            "/api/v1/namespaces/ns/events",
            [fieldSelector: "involvedObject.name=obj,involvedObject.kind=Pod"]
        ) >> { throw new RuntimeException("boom") }

        !response.success
        response.errors?.error?.contains("Exception while initiating API call: boom")
    }

    def "fetchEventsByNamespace should return error response when lazily initialized client throws exception"() {
        given:
        def mockCluster = Mock(ComputeServerGroup)
        def mockClient = Mock(HarvesterApiClient)

        GroovySpy(HarvesterApiClient, global: true)
        def resourceEvents = new HarvesterResourceEvents(mockCluster)

        when:
        def response = resourceEvents.fetchEventsByNamespace("ops", "vm-fail", "VirtualMachine")

        then:
        1 * new HarvesterApiClient(mockCluster) >> mockClient
        1 * mockClient.get(
            "/api/v1/namespaces/ops/events",
            [fieldSelector: "involvedObject.name=vm-fail,involvedObject.kind=VirtualMachine"]
        ) >> { throw new RuntimeException("lazy-fail") }

        !response.success
        response.errors?.error?.contains("Exception while initiating API call: lazy-fail")
        resourceEvents.harvesterApiClient == mockClient
    }

    def "fetchEventsByNamespace should reinitialize when existing client evaluates to false"() {
        given:
        def mockCluster = Mock(ComputeServerGroup)
        def falseyClient = GroovyMock(HarvesterApiClient)
        def replacementClient = Mock(HarvesterApiClient)
        def expectedResponse = new ServiceResponse(success: true, data: [items: [[message: 'reinitialized']]])

        GroovySpy(HarvesterApiClient, global: true)
        def resourceEvents = new HarvesterResourceEvents(mockCluster)
        resourceEvents.harvesterApiClient = falseyClient
        falseyClient.asBoolean() >> false

        when:
        def response = resourceEvents.fetchEventsByNamespace("falsey-ns", "vm-f", "VirtualMachine")

        then:
        1 * new HarvesterApiClient(mockCluster) >> replacementClient
        1 * replacementClient.get(
            "/api/v1/namespaces/falsey-ns/events",
            [fieldSelector: "involvedObject.name=vm-f,involvedObject.kind=VirtualMachine"]
        ) >> expectedResponse
        response == expectedResponse
        resourceEvents.harvesterApiClient == replacementClient
    }

    def "fetchEventsByNamespace should include null message text when exception message is null"() {
        given:
        def mockCluster = Mock(ComputeServerGroup)
        def mockClient = Mock(HarvesterApiClient)
        def resourceEvents = new HarvesterResourceEvents(mockCluster)
        resourceEvents.harvesterApiClient = mockClient

        when:
        def response = resourceEvents.fetchEventsByNamespace("null-msg-ns", "obj", "Pod")

        then:
        1 * mockClient.get(
            "/api/v1/namespaces/null-msg-ns/events",
            [fieldSelector: "involvedObject.name=obj,involvedObject.kind=Pod"]
        ) >> { throw new RuntimeException((String) null) }

        !response.success
        response.errors?.error?.contains("Exception while initiating API call: null")
    }

    def "fetchEventsByNamespace should support null namespace and generate uri with null literal"() {
        given:
        def mockCluster = Mock(ComputeServerGroup)
        def mockClient = Mock(HarvesterApiClient)
        def expectedResponse = new ServiceResponse(success: true, data: [items: []])
        def resourceEvents = new HarvesterResourceEvents(mockCluster)
        resourceEvents.harvesterApiClient = mockClient

        when:
        def response = resourceEvents.fetchEventsByNamespace(null, 'obj', 'Pod')

        then:
        1 * mockClient.get(
            "/api/v1/namespaces/null/events",
            [fieldSelector: "involvedObject.name=obj,involvedObject.kind=Pod"]
        ) >> expectedResponse
        response.success
    }

    def "fetchEventsByNamespace should initialize api client even when computeServerGroup is null"() {
        given:
        def mockClient = Mock(HarvesterApiClient)
        def expectedResponse = new ServiceResponse(success: true, data: [items: [[message: 'ok']]])

        GroovySpy(HarvesterApiClient, global: true)
        def resourceEvents = new HarvesterResourceEvents(null)

        when:
        def response = resourceEvents.fetchEventsByNamespace('ns-null-group', 'obj', 'Pod')

        then:
        1 * new HarvesterApiClient(null) >> mockClient
        1 * mockClient.get(
            "/api/v1/namespaces/ns-null-group/events",
            [fieldSelector: "involvedObject.name=obj,involvedObject.kind=Pod"]
        ) >> expectedResponse
        response.success
        resourceEvents.harvesterApiClient == mockClient
    }
}
