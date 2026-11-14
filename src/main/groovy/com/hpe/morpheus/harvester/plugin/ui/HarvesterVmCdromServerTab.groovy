// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.ui

import com.morpheusdata.core.AbstractServerTabProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.User
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

/**
 * "Harvester CD-ROM" tab on Harvester VMs (Infrastructure > Compute): list, attach ISO, eject, restart.
 * Renders without calling Harvester (the drives are fetched by the page after it loads), so it adds no wait to the page.
 * Shown to users whose role has "Harvester: Manage CD-ROM" at Full.
 */
@Slf4j
class HarvesterVmCdromServerTab extends AbstractServerTabProvider {

    Plugin plugin
    MorpheusContext morpheus
    String code = 'harvester-vm-cdrom-server-tab'
    String name = 'Harvester CD-ROM'

    HarvesterVmCdromServerTab(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheus = morpheus
    }

    @Override
    Boolean show(ComputeServer server, User user, Account account) {
        if (!VmYamlView.isHarvesterVm(morpheus, server)) return false
        // When Morpheus doesn't pass the user's permissions, show the tab and let the endpoints decide
        Map perms = user?.permissions as Map
        return perms == null || HarvesterVmCdromController.canManage(user)
    }

    @Override
    HTMLResponse renderTemplate(ComputeServer server) {
        ViewModel<Map> model = new ViewModel<>()
        model.object = [
                serverId    : server?.id,
                listRoute   : HarvesterVmCdromController.ROUTE_LIST,
                isosRoute   : HarvesterVmCdromController.ROUTE_ISOS,
                attachRoute : HarvesterVmCdromController.ROUTE_ATTACH,
                ejectRoute  : HarvesterVmCdromController.ROUTE_EJECT,
                restartRoute: HarvesterVmCdromController.ROUTE_RESTART
        ]
        return getRenderer().renderTemplate('hbs/harvesterVmCdromTab', model)
    }
}
