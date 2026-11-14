// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.ui

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Permission
import com.morpheusdata.model.User
import com.morpheusdata.views.JsonResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import groovy.util.logging.Slf4j

/**
 * Endpoints behind the "Harvester CD-ROM" tab, for roles with "Harvester: Manage CD-ROM" at Full:
 *   GET  .../harvester-vm-cdrom/list?serverId=          the VM's CD-ROM drives
 *   GET  .../harvester-vm-cdrom/isos?serverId=          ISO images of the VM's cloud
 *   POST .../harvester-vm-cdrom/attach   serverId, imageId, bootFirst
 *   POST .../harvester-vm-cdrom/eject    serverId, disk
 *   POST .../harvester-vm-cdrom/restart  serverId          (start when stopped)
 * Errors come back as {success: false, msg} with HTTP 200, so the tab can show them.
 */
@Slf4j
class HarvesterVmCdromController implements PluginController {

    static final String PERMISSION_CODE = 'harvester-vm-cdrom'
    static final String PERMISSION_NAME = 'Harvester: Manage CD-ROM'
    static final String ROUTE_LIST = '/harvester-vm-cdrom/list'
    static final String ROUTE_ISOS = '/harvester-vm-cdrom/isos'
    static final String ROUTE_ATTACH = '/harvester-vm-cdrom/attach'
    static final String ROUTE_EJECT = '/harvester-vm-cdrom/eject'
    static final String ROUTE_RESTART = '/harvester-vm-cdrom/restart'

    Plugin plugin
    MorpheusContext morpheus
    String code = 'harvester-vm-cdrom-controller'
    String name = 'Harvester VM CD-ROM'

    HarvesterVmCdromController(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheus = morpheus
    }

    static Permission permission() {
        return Permission.build(PERMISSION_NAME, PERMISSION_CODE, HarvesterVmYamlController.PERMISSION_SUB_CATEGORY,
                [Permission.AccessType.none, Permission.AccessType.full])
    }

    /** Morpheus calls a route only for users whose permissions contain its code; canManage() then requires Full */
    @Override
    List<Route> getRoutes() {
        Permission manage = Permission.build(PERMISSION_CODE, 'full')
        return [
                Route.build(ROUTE_LIST, 'list', manage),
                Route.build(ROUTE_ISOS, 'isos', manage),
                Route.build(ROUTE_ATTACH, 'attach', manage),
                Route.build(ROUTE_EJECT, 'eject', manage),
                Route.build(ROUTE_RESTART, 'restart', manage)
        ]
    }

    static boolean canManage(User user) {
        return user?.permissions?.get(PERMISSION_CODE)?.toString() == 'full'
    }

    def list(ViewModel<Map> model) {
        return handle(model, false) { ComputeServer server -> new VmCdromService(morpheus).list(server) }
    }

    def isos(ViewModel<Map> model) {
        return handle(model, false) { ComputeServer server -> new VmCdromService(morpheus).listIsos(server) }
    }

    def attach(ViewModel<Map> model) {
        return handle(model, true) { ComputeServer server ->
            String imageId = HarvesterVmYamlController.param(model, 'imageId')
            boolean bootFirst = HarvesterVmYamlController.param(model, 'bootFirst') in ['true', 'on', '1']
            log.info("HarvesterVmCdromController: ${model?.user?.username} attaches image ${imageId} to ${server.name}")
            new VmCdromService(morpheus).attach(server, imageId?.isLong() ? imageId.toLong() : null, bootFirst)
        }
    }

    def eject(ViewModel<Map> model) {
        return handle(model, true) { ComputeServer server ->
            String disk = HarvesterVmYamlController.param(model, 'disk')
            log.info("HarvesterVmCdromController: ${model?.user?.username} ejects ${disk} from ${server.name}")
            new VmCdromService(morpheus).eject(server, disk)
        }
    }

    def restart(ViewModel<Map> model) {
        return handle(model, true) { ComputeServer server ->
            log.info("HarvesterVmCdromController: ${model?.user?.username} restarts ${server.name}")
            new VmCdromService(morpheus).restart(server)
        }
    }

    private def handle(ViewModel<Map> model, boolean post, Closure action) {
        try {
            if (post && model?.request?.respondsTo('getMethod') && model.request.method?.toUpperCase() != 'POST') {
                return JsonResponse.of([success: false, msg: 'Use POST.'])
            }
            User user = model?.user
            if (!canManage(user)) {
                log.info("HarvesterVmCdromController: ${user?.username ?: 'unknown user'} may not manage CD-ROMs")
                return JsonResponse.of([success: false, msg: "You need Full access to '${PERMISSION_NAME}' (Administration > Roles, Infrastructure)."])
            }
            String idParam = HarvesterVmYamlController.param(model, 'serverId')
            if (!idParam?.isLong()) return JsonResponse.of([success: false, msg: 'Missing serverId.'])
            ComputeServer server = morpheus.services.computeServer.get(idParam.toLong())
            if (!server || !VmYamlView.isHarvesterVm(morpheus, server)) {
                return JsonResponse.of([success: false, msg: 'Harvester VM not found.'])
            }
            if (!HarvesterVmYamlController.canAccess(user, server)) {
                return JsonResponse.of([success: false, msg: "You don't have access to this VM."])
            }
            return JsonResponse.of(action.call(server) as Map)
        } catch (Exception e) {
            log.warn("HarvesterVmCdromController failed: ${e.message}", e)
            return JsonResponse.of([success: false, msg: e.message])
        }
    }
}
