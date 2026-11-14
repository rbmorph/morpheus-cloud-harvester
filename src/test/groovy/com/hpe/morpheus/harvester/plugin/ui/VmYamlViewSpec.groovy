// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.ui

import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudType
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Instance
import com.morpheusdata.response.ServiceResponse
import spock.lang.Specification

class VmYamlViewSpec extends Specification {

    def cleanup() {
        VmYamlView.clearCloudCache()
    }

    private Map mockCloudService(MorpheusContext ctx) {
        def services = Mock((Class) Class.forName('com.morpheusdata.core.MorpheusServices'))
        def cloudService = Mock((Class) Class.forName('com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService'))
        ctx.services >> services
        ctx.getServices() >> services
        services.cloud >> cloudService
        services.getCloud() >> cloudService
        return [cloudService: cloudService]
    }

    def "instance check uses the provision type without any lookup"() {
        given:
        def ctx = Mock(MorpheusContext)

        when:
        def result = VmYamlView.isHarvesterInstance(ctx, new Instance(provisionType: HarvesterConstants.PROVISION_TYPE_CODE))

        then:
        0 * ctx._
        result
    }

    def "instance check caches the cloud type"() {
        given:
        def ctx = Mock(MorpheusContext)
        def svc = mockCloudService(ctx)
        def otherCloud = new Cloud(cloudType: new CloudType(code: 'other-cloud'))
        def harvesterCloud = new Cloud(cloudType: new CloudType(code: HarvesterConstants.CLOUD_PROVIDER_CODE))

        when:
        def first = VmYamlView.isHarvesterInstance(ctx, new Instance(provisionType: 'other', provisionZoneId: 5L))
        def second = VmYamlView.isHarvesterInstance(ctx, new Instance(provisionType: 'other', provisionZoneId: 5L))
        def other = VmYamlView.isHarvesterInstance(ctx, new Instance(provisionType: 'manual', provisionZoneId: 21L))

        then:
        1 * svc.cloudService.get(5L) >> otherCloud
        1 * svc.cloudService.get(21L) >> harvesterCloud
        !first && !second
        other
    }

    def "server check skips hosts and matches by category"() {
        given:
        def ctx = Mock(MorpheusContext)

        expect:
        !VmYamlView.isHarvesterVm(ctx, new ComputeServer(serverType: HarvesterConstants.SERVER_TYPE_HYPERVISOR,
                category: HarvesterConstants.CATEGORY_HARVESTER_VM_PREFIX + '1'))
        VmYamlView.isHarvesterVm(ctx, new ComputeServer(category: HarvesterConstants.CATEGORY_HARVESTER_VM_PREFIX + '1'))
    }

    def "cleanForDisplay drops status and managedFields and hides inline cloud-init"() {
        given:
        def vm = [
                metadata: [name: 'vm1', managedFields: [[manager: 'x']],
                           annotations: ['kubectl.kubernetes.io/last-applied-configuration': '{}']],
                spec    : [template: [spec: [volumes: [[name: 'cloudinitdisk',
                                                         cloudInitNoCloud: [userData: "#cloud-config\npassword: secret\n", networkData: 'x']]]]]],
                status  : [printableStatus: 'Running']
        ]

        when:
        def cleaned = VmYamlView.cleanForDisplay(vm)

        then:
        !cleaned.containsKey('status')
        !cleaned.metadata.containsKey('managedFields')
        !cleaned.metadata.containsKey('annotations')
        cleaned.spec.template.spec.volumes[0].cloudInitNoCloud.userData == '(hidden: 2 lines)'
        cleaned.spec.template.spec.volumes[0].cloudInitNoCloud.networkData == '(hidden: 1 lines)'
        !YamlWriter.toYaml(cleaned).contains('secret')

        and: 'the original is untouched'
        vm.status.printableStatus == 'Running'
        vm.spec.template.spec.volumes[0].cloudInitNoCloud.userData.contains('secret')
    }

    def "findVm matches by uid, then by name"() {
        given:
        def client = Mock(HarvesterApiClient)
        client.get('/apis/kubevirt.io/v1/namespaces/ns1/virtualmachines') >> ServiceResponse.success([items: [
                [metadata: [name: 'other', uid: 'u-2']],
                [metadata: [name: 'vm1', uid: 'u-1']]
        ]])

        expect:
        VmYamlView.findVm(client, 'ns1', new ComputeServer(name: 'renamed', externalId: 'u-1')).metadata.name == 'vm1'
        VmYamlView.findVm(client, 'ns1', new ComputeServer(name: 'other', externalId: 'nope')).metadata.uid == 'u-2'
        VmYamlView.findVm(client, 'ns1', new ComputeServer(name: 'missing', externalId: 'nope')) == null
    }

    def "findVm reports a Harvester error"() {
        given:
        def client = Mock(HarvesterApiClient)
        client.get(_ as String) >> ServiceResponse.error('forbidden')

        when:
        VmYamlView.findVm(client, 'ns1', new ComputeServer(name: 'vm1'))

        then:
        def e = thrown(RuntimeException)
        e.message.contains('forbidden')
    }
}
