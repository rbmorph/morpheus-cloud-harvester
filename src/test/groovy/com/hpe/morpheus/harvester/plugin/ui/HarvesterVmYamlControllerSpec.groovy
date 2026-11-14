// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.ui

import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.User
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.views.ViewModel
import spock.lang.Specification

class HarvesterVmYamlControllerSpec extends Specification {

    def "routes require the Harvester edit permission"() {
        given:
        def controller = new HarvesterVmYamlController(Mock(Plugin), Mock(MorpheusContext))

        expect:
        controller.routes*.url == [HarvesterVmYamlController.ROUTE_SOURCE, HarvesterVmYamlController.ROUTE_SAVE]
        controller.routes*.method == ['source', 'save']
        controller.routes.every { r -> r.permissions*.code == ['harvester-vm-yaml-edit'] }
    }

    def "permission is listed under Infrastructure with None/Full"() {
        expect:
        HarvesterVmYamlController.permission().code == 'harvester-vm-yaml-edit'
        HarvesterVmYamlController.permission().name == 'Harvester: Edit VM YAML'
        HarvesterVmYamlController.permission().subCategory == 'infrastructure'
    }

    def "canEdit needs Full on Harvester: Edit VM YAML"() {
        expect:
        HarvesterVmYamlController.canEdit(user(9L, ['harvester-vm-yaml-edit': 'full']))
        !HarvesterVmYamlController.canEdit(user(1L, ['harvester-vm-yaml-edit': 'none']))
        !HarvesterVmYamlController.canEdit(user(1L, ['admin-servers': 'full']))
        !HarvesterVmYamlController.canEdit(user(1L, [:]))
        !HarvesterVmYamlController.canEdit(null)
    }

    def "a user who may not edit gets a message, not the YAML"() {
        given:
        def controller = new HarvesterVmYamlController(Mock(Plugin), Mock(MorpheusContext))
        def model = new ViewModel<Map>()
        model.object = [serverId: '5']
        model.user = user(9L, [:])

        when:
        def res = controller.source(model).data

        then:
        !res.success
        res.msg.contains('Harvester: Edit VM YAML')
    }

    private static User user(Long accountId, Map perms) {
        def u = new User(account: new Account(id: accountId))
        u.permissions = perms
        return u
    }

    def "canAccess allows the server's tenant and the master tenant only"() {
        given:
        def server = new ComputeServer(account: new Account(id: 7L))

        expect:
        HarvesterVmYamlController.canAccess(new User(account: new Account(id: 7L)), server)
        HarvesterVmYamlController.canAccess(new User(account: new Account(id: 1L)), server)
        HarvesterVmYamlController.canAccess(new User(account: new Account(id: 9L, masterAccount: true)), server)
        !HarvesterVmYamlController.canAccess(new User(account: new Account(id: 9L)), server)
        !HarvesterVmYamlController.canAccess(null, server)
    }

    def "param reads the request, then the model object"() {
        given:
        def model = new ViewModel<Map>()
        model.object = [serverId: '12']

        expect:
        HarvesterVmYamlController.param(model, 'serverId') == '12'
        HarvesterVmYamlController.param(model, 'yaml') == null
    }

    def "source rejects a missing serverId"() {
        given:
        def controller = new HarvesterVmYamlController(Mock(Plugin), Mock(MorpheusContext))
        def model = new ViewModel<Map>()
        model.object = [:]
        model.user = user(1L, ['harvester-vm-yaml-edit': 'full'])

        expect:
        controller.source(model).data == [success: false, msg: 'Missing serverId.']
    }

    def "save does a dry run, then the real update"() {
        given:
        def client = Mock(HarvesterApiClient)
        GroovySpy(VmYamlView, global: true)
        VmYamlView.resolve(_, _) >> [client: client, vm: [metadata: [name: 'vm1', namespace: 'ns1']]]
        String path = '/apis/kubevirt.io/v1/namespaces/ns1/virtualmachines/vm1'

        when:
        def result = VmYamlView.save(Mock(MorpheusContext), new ComputeServer(id: 1L), "kind: VirtualMachine\n")

        then:
        1 * client.putRaw(path, "kind: VirtualMachine\n", 'application/yaml', [dryRun: 'All']) >> ServiceResponse.success([:])
        1 * client.putRaw(path, "kind: VirtualMachine\n", 'application/yaml') >>
                ServiceResponse.success([metadata: [resourceVersion: '42'], status: [conditions: [[type: 'RestartRequired', status: 'True']]]])
        result.success
        result.resourceVersion == '42'
        result.msg.contains('restart')
    }

    def "save stops when the dry run fails and reports Harvester's message"() {
        given:
        def client = Mock(HarvesterApiClient)
        GroovySpy(VmYamlView, global: true)
        VmYamlView.resolve(_, _) >> [client: client, vm: [metadata: [name: 'vm1', namespace: 'ns1']]]

        when:
        def result = VmYamlView.save(Mock(MorpheusContext), new ComputeServer(id: 1L), "kind: VirtualMachine\n")

        then:
        1 * client.putRaw(_, _, _, [dryRun: 'All']) >> new ServiceResponse(success: false, data: [message: 'Operation cannot be fulfilled: the object has been modified'])
        0 * client.putRaw(_, _, _)
        !result.success
        result.msg.contains('has been modified')
    }

    def "save refuses empty YAML and characters outside Latin-1"() {
        expect:
        !VmYamlView.save(Mock(MorpheusContext), new ComputeServer(), '  ').success
        VmYamlView.save(Mock(MorpheusContext), new ComputeServer(), 'name: 中').msg.contains("can't be sent")
    }

    def "errorMessage prefers the Kubernetes Status message"() {
        expect:
        VmYamlView.errorMessage(new ServiceResponse(success: false, data: [message: 'bad'])) == 'bad'
        VmYamlView.errorMessage(new ServiceResponse(success: false, content: '{"kind":"Status","message":"conflict"}')) == 'conflict'
        VmYamlView.errorMessage(new ServiceResponse(success: false, msg: 'boom')) == 'boom'
    }
}
