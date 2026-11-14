// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.ui

import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.User
import com.morpheusdata.views.ViewModel
import spock.lang.Specification

class HarvesterVmCdromControllerSpec extends Specification {

    private static User user(Map perms) {
        def u = new User(account: new Account(id: 1L))
        u.permissions = perms
        return u
    }

    def "routes need the CD-ROM permission"() {
        given:
        def controller = new HarvesterVmCdromController(Mock(Plugin), Mock(MorpheusContext))

        expect:
        controller.routes*.url == ['/harvester-vm-cdrom/list', '/harvester-vm-cdrom/isos', '/harvester-vm-cdrom/attach',
                                   '/harvester-vm-cdrom/eject', '/harvester-vm-cdrom/restart']
        controller.routes*.method == ['list', 'isos', 'attach', 'eject', 'restart']
        controller.routes.every { r -> r.permissions*.code == ['harvester-vm-cdrom'] }
        HarvesterVmCdromController.permission().subCategory == 'infrastructure'
    }

    def "only Full on the permission may manage CD-ROMs"() {
        expect:
        HarvesterVmCdromController.canManage(user(['harvester-vm-cdrom': 'full']))
        !HarvesterVmCdromController.canManage(user(['harvester-vm-cdrom': 'none']))
        !HarvesterVmCdromController.canManage(user(['harvester-vm-yaml-edit': 'full']))
        !HarvesterVmCdromController.canManage(null)
    }

    def "a user without the permission gets a message"() {
        given:
        def controller = new HarvesterVmCdromController(Mock(Plugin), Mock(MorpheusContext))
        def model = new ViewModel<Map>()
        model.object = [serverId: '5']
        model.user = user([:])

        when:
        def res = controller.list(model).data

        then:
        !res.success
        res.msg.contains('Harvester: Manage CD-ROM')
    }

    def "the tab shows on Harvester VMs for users with the permission"() {
        given:
        def tab = new HarvesterVmCdromServerTab(Mock(Plugin), Mock(MorpheusContext))
        def vm = new ComputeServer(category: HarvesterConstants.CATEGORY_HARVESTER_VM_PREFIX + '1')

        expect:
        tab.show(vm, user(['harvester-vm-cdrom': 'full']), null)
        !tab.show(vm, user(['harvester-vm-cdrom': 'none']), null)
        tab.show(vm, new User(), null)        // permissions not passed: the endpoints decide
        !tab.show(new ComputeServer(serverType: 'hypervisor'), user(['harvester-vm-cdrom': 'full']), null)
    }
}
