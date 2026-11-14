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
 * "Harvester Console" tab on Harvester hosts (Infrastructure > Compute > Hosts): a kubectl-style command line against
 * the Harvester cluster the host belongs to (see KubectlService). Renders without calling Harvester.
 * Shown to users whose role has either Harvester Kubernetes Console permission at Full.
 */
@Slf4j
class HarvesterKubectlHostTab extends AbstractServerTabProvider {

    Plugin plugin
    MorpheusContext morpheus
    String code = 'harvester-kubectl-host-tab'
    String name = 'Harvester Console'

    HarvesterKubectlHostTab(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheus = morpheus
    }

    @Override
    Boolean show(ComputeServer server, User user, Account account) {
        if (!HarvesterKubectlController.isHarvesterHost(morpheus, server)) return false
        // When Morpheus doesn't pass the user's permissions, show the tab and let the endpoint decide
        Map perms = user?.permissions as Map
        return perms == null || HarvesterKubectlController.canRead(user)
    }

    @Override
    HTMLResponse renderTemplate(ComputeServer server) {
        ViewModel<Map> model = new ViewModel<>()
        model.object = [
                serverId: server?.id,
                runRoute: HarvesterKubectlController.ROUTE_RUN
        ]
        return getRenderer().renderTemplate('hbs/harvesterKubectlTab', model)
    }
}
