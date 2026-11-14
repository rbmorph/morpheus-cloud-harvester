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
 * "Harvester YAML" tab on Harvester VMs under Infrastructure > Compute (also covers discovered VMs with no instance).
 */
@Slf4j
class HarvesterVmYamlServerTab extends AbstractServerTabProvider {

    Plugin plugin
    MorpheusContext morpheus
    String code = 'harvester-vm-yaml-server-tab'
    String name = 'Harvester YAML'

    HarvesterVmYamlServerTab(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheus = morpheus
    }

    @Override
    Boolean show(ComputeServer server, User user, Account account) {
        return VmYamlView.isHarvesterVm(morpheus, server)
    }

    @Override
    HTMLResponse renderTemplate(ComputeServer server) {
        ViewModel<Map> model = new ViewModel<>()
        Map data = VmYamlView.load(morpheus, server)
        data.sourceRoute = HarvesterVmYamlController.ROUTE_SOURCE
        data.saveRoute = HarvesterVmYamlController.ROUTE_SAVE
        model.object = data
        return getRenderer().renderTemplate('hbs/harvesterVmYamlTab', model)
    }
}
