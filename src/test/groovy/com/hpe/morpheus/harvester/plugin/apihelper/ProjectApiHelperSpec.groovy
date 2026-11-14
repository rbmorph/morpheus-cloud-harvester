// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.apihelper

import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Unit tests for ProjectApiHelper class
 */
class ProjectApiHelperSpec extends Specification {

    ProjectApiHelper projectApiHelper
    HarvesterApiClient mockApiClient
    ComputeServerGroup mockServerGroup

    def setup() {
        mockServerGroup = Mock(ComputeServerGroup)
        mockApiClient = Mock(HarvesterApiClient)
        projectApiHelper = new ProjectApiHelper(mockServerGroup)
        projectApiHelper.apiClient = mockApiClient
    }

    def "getProjects should retrieve projects successfully"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            uid: "project-uid-1",
                            name: "my-app",
                            annotations: [
                                description: "My application project"
                            ]
                        ],
                        status: [
                            phase: "Active"
                        ]
                    ],
                    [
                        metadata: [
                            uid: "project-uid-2",
                            name: "production",
                            annotations: [:]
                        ],
                        status: [
                            phase: "Active"
                        ]
                    ],
                    [
                        metadata: [
                            uid: "project-uid-3",
                            name: "test-env",
                            annotations: [
                                description: "Testing environment"
                            ]
                        ],
                        status: [:]  // No phase
                    ]
                ]
            ]
        )

        when:
        def result = projectApiHelper.getProjects()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NAMESPACES) >> mockResponse

        result.success == true
        result.projects.size() == 3
        result.projects[0].uid == "project-uid-1"
        result.projects[0].name == "my-app"
        result.projects[0].description == "My application project"
        result.projects[0].status == "Active"

        result.projects[1].uid == "project-uid-2"
        result.projects[1].name == "production"
        result.projects[1].description == ""
        result.projects[1].status == "Active"

        result.projects[2].name == "test-env"
        result.projects[2].status == "Unknown"
    }

    def "getProjects should filter out system namespaces"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            uid: "proj-1",
                            name: "my-project",
                            annotations: [:]
                        ],
                        status: [phase: "Active"]
                    ],
                    [
                        metadata: [
                            uid: "proj-2",
                            name: "kube-system",
                            annotations: [:]
                        ],
                        status: [phase: "Active"]
                    ],
                    [
                        metadata: [
                            uid: "proj-3",
                            name: "longhorn-system",
                            annotations: [:]
                        ],
                        status: [phase: "Active"]
                    ],
                    [
                        metadata: [
                            uid: "proj-4",
                            name: "default",
                            annotations: [:]
                        ],
                        status: [phase: "Active"]
                    ],
                    [
                        metadata: [
                            uid: "proj-5",
                            name: "calico-system",
                            annotations: [:]
                        ],
                        status: [phase: "Active"]
                    ],
                    [
                        metadata: [
                            uid: "proj-6",
                            name: "user-workloads",
                            annotations: [:]
                        ],
                        status: [phase: "Active"]
                    ]
                ]
            ]
        )

        when:
        def result = projectApiHelper.getProjects()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NAMESPACES) >> mockResponse

        result.success == true
        result.projects.size() == 3
        result.projects*.name.containsAll(["my-project", "default", "user-workloads"])
        !result.projects*.name.contains("kube-system")
        !result.projects*.name.contains("longhorn-system")
        !result.projects*.name.contains("calico-system")
    }

    def "getProjects should handle empty project list"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [items: []]
        )

        when:
        def result = projectApiHelper.getProjects()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NAMESPACES) >> mockResponse

        result.success == true
        result.projects.size() == 0
    }

    def "getProjects should handle null items"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [:]
        )

        when:
        def result = projectApiHelper.getProjects()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NAMESPACES) >> mockResponse

        result.success == true
        result.projects.size() == 0
    }

    def "getProjects should handle missing annotations"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            uid: "proj-1",
                            name: "no-annotations"
                        ],
                        status: [phase: "Active"]
                    ]
                ]
            ]
        )

        when:
        def result = projectApiHelper.getProjects()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NAMESPACES) >> mockResponse

        result.success == true
        result.projects.size() == 1
        result.projects[0].description == ""
    }

    def "getProjects should handle missing status phase"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            uid: "proj-1",
                            name: "test-project",
                            annotations: [:]
                        ],
                        status: [:]
                    ]
                ]
            ]
        )

        when:
        def result = projectApiHelper.getProjects()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NAMESPACES) >> mockResponse

        result.success == true
        result.projects[0].status == "Unknown"
    }

    def "getProjects should handle API failure"() {
        given:
        def mockResponse = new ServiceResponse(
            success: false,
            msg: "Unauthorized",
            errorCode: "401",
            statusCode: 401
        )

        when:
        def result = projectApiHelper.getProjects()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NAMESPACES) >> mockResponse

        result.success == false
        result.message == "Unauthorized"
    }

    def "getProjects should handle API failure with null message"() {
        given:
        def mockResponse = new ServiceResponse(
            success: false,
            errorCode: "500",
            statusCode: 500
        )

        when:
        def result = projectApiHelper.getProjects()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NAMESPACES) >> mockResponse

        result.success == false
        result.message == "Failed to fetch projects from Harvester"
    }

    @Unroll
    def "getProjects should handle project status: #statusPhase"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            uid: "proj-1",
                            name: "test-project",
                            annotations: [:]
                        ],
                        status: [phase: statusPhase]
                    ]
                ]
            ]
        )

        when:
        def result = projectApiHelper.getProjects()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NAMESPACES) >> mockResponse

        result.success == true
        result.projects[0].status == statusPhase

        where:
        statusPhase << ["Active", "Terminating", "Failed", "Pending"]
    }

    def "getProjects should preserve all project metadata"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [
                            uid: "unique-project-id-12345",
                            name: "detailed-project",
                            annotations: [
                                description: "A very detailed project description with special chars",
                                owner: "admin@example.com",
                                createdBy: "automation"
                            ]
                        ],
                        status: [
                            phase: "Active"
                        ]
                    ]
                ]
            ]
        )

        when:
        def result = projectApiHelper.getProjects()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NAMESPACES) >> mockResponse

        result.success == true
        result.projects.size() == 1
        result.projects[0].uid == "unique-project-id-12345"
        result.projects[0].name == "detailed-project"
        result.projects[0].description == "A very detailed project description with special chars"
        result.projects[0].status == "Active"
    }

    def "getProjects should handle projects with system namespace prefixes"() {
        given:
        def mockResponse = new ServiceResponse(
            success: true,
            data: [
                items: [
                    [
                        metadata: [uid: "1", name: "cattle-custom", annotations: [:]],
                        status: [phase: "Active"]
                    ],
                    [
                        metadata: [uid: "2", name: "kube-custom", annotations: [:]],
                        status: [phase: "Active"]
                    ],
                    [
                        metadata: [uid: "3", name: "my-harvester-app", annotations: [:]],
                        status: [phase: "Active"]
                    ],
                    [
                        metadata: [uid: "4", name: "tigera-prod", annotations: [:]],
                        status: [phase: "Active"]
                    ]
                ]
            ]
        )

        when:
        def result = projectApiHelper.getProjects()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NAMESPACES) >> mockResponse

        result.success == true
        // harvester-custom and kube-custom should be filtered
        // my-harvester-app and tigera-prod (doesn't start with tigera-) should remain
        result.projects.size() == 1
        result.projects[0].name == "my-harvester-app"
    }

    def "getProjects should handle large number of projects"() {
        given:
        def projectItems = (1..100).collect { i ->
            [
                metadata: [
                    uid: "proj-uid-${i}",
                    name: "project-${i}",
                    annotations: [description: "Project ${i}"]
                ],
                status: [phase: i % 2 == 0 ? "Active" : "Terminating"]
            ]
        }
        def mockResponse = new ServiceResponse(
            success: true,
            data: [items: projectItems]
        )

        when:
        def result = projectApiHelper.getProjects()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NAMESPACES) >> mockResponse

        result.success == true
        result.projects.size() == 100
        result.projects.every { it.uid && it.name && it.status }
    }

    def "getProjects should handle null response data"() {
        given:
        def mockResponse = new ServiceResponse(success: true, data: null)

        when:
        def result = projectApiHelper.getProjects()

        then:
        1 * mockApiClient.get(HarvesterConstants.HARVESTER_API_V1_NAMESPACES) >> mockResponse

        result.success == true
        result.projects.size() == 0
    }
}
