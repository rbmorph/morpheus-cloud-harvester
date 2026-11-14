// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.ui

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.User
import spock.lang.Specification

class HarvesterKubectlControllerSpec extends Specification {

    private static User user(Map perms, Long accountId = 1L) {
        def u = new User(account: new Account(id: accountId))
        u.permissions = perms
        return u
    }

    def "the run route accepts either console permission"() {
        given:
        def controller = new HarvesterKubectlController(Mock(Plugin), Mock(MorpheusContext))

        expect:
        controller.routes*.url == ['/harvester-kubectl/run']
        controller.routes[0].permissions*.code == ['harvester-kubectl-read', 'harvester-kubectl-write']
        HarvesterKubectlController.permissions()*.code == ['harvester-kubectl-read', 'harvester-kubectl-write']
        HarvesterKubectlController.permissions().every { it.subCategory == 'infrastructure' }
    }

    def "read and write permissions"() {
        expect:
        HarvesterKubectlController.canRead(user(['harvester-kubectl-read': 'full']))
        !HarvesterKubectlController.canWrite(user(['harvester-kubectl-read': 'full']))
        HarvesterKubectlController.canRead(user(['harvester-kubectl-write': 'full']))
        HarvesterKubectlController.canWrite(user(['harvester-kubectl-write': 'full']))
        !HarvesterKubectlController.canRead(user(['harvester-kubectl-read': 'none']))
        !HarvesterKubectlController.canRead(null)
    }

    def "the console is offered on Harvester hosts only"() {
        given:
        def morpheus = Mock(MorpheusContext)
        def master = new ComputeServer(serverType: 'hypervisor', computeServerType: new ComputeServerType(code: 'harvesterMaster'))
        def worker = new ComputeServer(serverType: 'hypervisor', computeServerType: new ComputeServerType(code: 'harvesterWorker'))
        def vm = new ComputeServer(serverType: 'vm', computeServerType: new ComputeServerType(code: 'harvesterMaster'))

        expect:
        HarvesterKubectlController.isHarvesterHost(morpheus, master)
        HarvesterKubectlController.isHarvesterHost(morpheus, worker)
        !HarvesterKubectlController.isHarvesterHost(morpheus, vm)
        !HarvesterKubectlController.isHarvesterHost(morpheus, null)
    }

    def "users of other tenants can't use another tenant's host"() {
        given:
        def host = new ComputeServer(account: new Account(id: 5L))

        expect:
        HarvesterKubectlController.canAccess(user([:], 5L), host)
        !HarvesterKubectlController.canAccess(user([:], 6L), host)
        HarvesterKubectlController.canAccess(user([:], 1L), host)
    }
}
