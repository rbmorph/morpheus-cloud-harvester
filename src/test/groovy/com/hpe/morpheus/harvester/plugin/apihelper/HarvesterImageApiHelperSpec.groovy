// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.apihelper

import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification
import spock.lang.Unroll

class HarvesterImageApiHelperSpec extends Specification {

    HarvesterApiClient apiClient = Mock(HarvesterApiClient)
    HarvesterImageApiHelper helper = new HarvesterImageApiHelper(Mock(ComputeServerGroup), apiClient)

    def "getImageApiPath builds namespaced paths"() {
        expect:
        HarvesterImageApiHelper.getImageApiPath('default') == '/apis/harvesterhci.io/v1beta1/namespaces/default/virtualmachineimages'
        HarvesterImageApiHelper.getImageApiPath('images', 'image-abc') == '/apis/harvesterhci.io/v1beta1/namespaces/images/virtualmachineimages/image-abc'
    }

    def "getVMImages lists images cluster-wide with pagination"() {
        when:
        def response = helper.getVMImages('tok', 25)

        then:
        1 * apiClient.callApiWithPagination(HarvesterConstants.HARVESTER_API_V1_VM_IMAGES, 'tok', 25) >> ServiceResponse.success([items: []])
        response.success
    }

    def "getVMImage fetches one image"() {
        when:
        helper.getVMImage('default', 'image-abc')

        then:
        1 * apiClient.get('/apis/harvesterhci.io/v1beta1/namespaces/default/virtualmachineimages/image-abc') >> ServiceResponse.success([:])
    }

    @Unroll
    def "isImageReady is #expected for #description"() {
        expect:
        HarvesterImageApiHelper.isImageReady(image) == expected

        where:
        description                     | image                                                                                         || expected
        'Imported=True'                 | [metadata: [:], status: [conditions: [[type: 'Imported', status: 'True']]]]                  || true
        'Imported=False'                | [metadata: [:], status: [progress: 100, conditions: [[type: 'Imported', status: 'False']]]] || false
        'progress 100, no conditions'   | [metadata: [:], status: [progress: 100]]                                                      || true
        'progress 50'                   | [metadata: [:], status: [progress: 50]]                                                       || false
        'being deleted'                 | [metadata: [deletionTimestamp: 'now'], status: [progress: 100]]                               || false
        'null'                          | null                                                                                          || false
    }

    def "storage class and image id come from the image"() {
        given:
        def image = [metadata: [name: 'image-abc', namespace: 'default'], status: [storageClassName: 'longhorn-image-abc']]

        expect:
        HarvesterImageApiHelper.getImageStorageClassName(image) == 'longhorn-image-abc'
        HarvesterImageApiHelper.getImageStorageClassName([metadata: [name: 'image-x']]) == 'longhorn-image-x'
        HarvesterImageApiHelper.getImageStorageClassName(null) == null
        HarvesterImageApiHelper.getImageId(image) == 'default/image-abc'
        HarvesterImageApiHelper.getImageId([metadata: [name: 'n']]) == null
    }

    def "getDefaultStorageClassName reads the default-class annotation"() {
        when:
        def name = helper.getDefaultStorageClassName()

        then:
        1 * apiClient.get('/apis/storage.k8s.io/v1/storageclasses') >> ServiceResponse.success([items: [
                [metadata: [name: 'longhorn-image-abc', annotations: [:]]],
                [metadata: [name: 'fast-ssd', annotations: [(HarvesterConstants.ANNOTATION_K8S_DEFAULT_CLASS): 'true']]]
        ]])
        name == 'fast-ssd'
    }

    def "getDefaultStorageClassName falls back to harvester-longhorn"() {
        when:
        def name = helper.getDefaultStorageClassName()

        then:
        1 * apiClient.get(_) >> response
        name == HarvesterConstants.DEFAULT_STORAGE_CLASS

        where:
        response << [ServiceResponse.error('denied'), ServiceResponse.success([items: []])]
    }
}
