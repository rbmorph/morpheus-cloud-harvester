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
 * Endpoints behind the Harvester YAML tab's Edit / Save, for users with Full on the plugin's "Harvester: Edit VM YAML"
 * permission (listed under Infrastructure on the role).
 * The tenant check is applied on top.
 *   GET  .../harvester-vm-yaml/source?serverId=  -> the VM's YAML for editing (includes cloud-init data)
 *   POST .../harvester-vm-yaml/save   serverId, yaml -> replaces the VM in Harvester (dry run first)
 * Errors come back as {success: false, msg} with HTTP 200, so the tab can show them.
 */
@Slf4j
class HarvesterVmYamlController implements PluginController {

    /** The plugin's own permission (Administration > Roles, Infrastructure section): Full can edit VM YAML */
    static final String PERMISSION_CODE = 'harvester-vm-yaml-edit'
    static final String PERMISSION_NAME = 'Harvester: Edit VM YAML'
    /** Role section the permission is listed in (Morpheus's sub-category code for Infrastructure) */
    static final String PERMISSION_SUB_CATEGORY = 'infrastructure'

    static Permission permission() {
        return Permission.build(PERMISSION_NAME, PERMISSION_CODE, PERMISSION_SUB_CATEGORY,
                [Permission.AccessType.none, Permission.AccessType.full])
    }
    static final String ROUTE_SOURCE = '/harvester-vm-yaml/source'
    static final String ROUTE_SAVE = '/harvester-vm-yaml/save'

    Plugin plugin
    MorpheusContext morpheus
    String code = 'harvester-vm-yaml-controller'
    String name = 'Harvester VM YAML'

    HarvesterVmYamlController(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheus = morpheus
    }

    /**
     * Morpheus's route dispatcher (com.morpheusdata.web.Dispatcher.checkPermissions) calls a route only when its
     * permission code is in the user's permission map, whatever the access level (a route without permissions is
     * always refused). canEdit() then requires Full.
     */
    @Override
    List<Route> getRoutes() {
        Permission edit = Permission.build(PERMISSION_CODE, 'full')
        return [
                Route.build(ROUTE_SOURCE, 'source', edit),
                Route.build(ROUTE_SAVE, 'save', edit)
        ]
    }

    /** Full on Harvester: Edit VM YAML */
    static boolean canEdit(User user) {
        return user?.permissions?.get(PERMISSION_CODE)?.toString() == 'full'
    }

    def source(ViewModel<Map> model) {
        try {
            def found = findServer(model)
            if (found instanceof Map) return JsonResponse.of(found)
            log.info "HarvesterVmYamlController: ${model?.user?.username} opened the YAML of ${(found as ComputeServer).name} for editing"
            return JsonResponse.of(VmYamlView.loadForEdit(morpheus, found as ComputeServer))
        } catch (Exception e) {
            log.warn "HarvesterVmYamlController.source failed: ${e.message}", e
            return JsonResponse.of([success: false, msg: e.message])
        }
    }

    def save(ViewModel<Map> model) {
        try {
            if (model?.request?.respondsTo('getMethod') && model.request.method?.toUpperCase() != 'POST') {
                return JsonResponse.of([success: false, msg: 'Use POST to save.'])
            }
            def found = findServer(model)
            if (found instanceof Map) return JsonResponse.of(found)
            ComputeServer server = found as ComputeServer
            String yaml = param(model, 'yaml')
            log.info "HarvesterVmYamlController: ${model?.user?.username} is saving the YAML of ${server.name} (server ${server.id})"
            return JsonResponse.of(VmYamlView.save(morpheus, server, yaml))
        } catch (Exception e) {
            log.warn "HarvesterVmYamlController.save failed: ${e.message}", e
            return JsonResponse.of([success: false, msg: e.message])
        }
    }

    /** The server named by the serverId parameter, if it's a Harvester VM the user's tenant can see; otherwise an error map */
    private def findServer(ViewModel<Map> model) {
        User user = model?.user
        if (!canEdit(user)) {
            log.info "HarvesterVmYamlController: ${user?.username ?: 'unknown user'} may not edit VM YAML " +
                    "(${PERMISSION_NAME} is ${user?.permissions?.get(PERMISSION_CODE) ?: 'none'})"
            return [success: false, msg: "You need Full access to '${PERMISSION_NAME}' (Administration > Roles, Infrastructure) to edit VM YAML."]
        }
        String idParam = param(model, 'serverId')
        if (!idParam?.isLong()) {
            return [success: false, msg: 'Missing serverId.']
        }
        ComputeServer server = morpheus.services.computeServer.get(idParam.toLong())
        if (!server || !VmYamlView.isHarvesterVm(morpheus, server)) {
            return [success: false, msg: 'Harvester VM not found.']
        }
        if (!canAccess(model?.user, server)) {
            return [success: false, msg: "You don't have access to this VM."]
        }
        return server
    }

    /** Same tenant as the server, or a user of the master tenant */
    static boolean canAccess(User user, ComputeServer server) {
        if (!user?.account) return false
        if (user.account.masterAccount || user.account.id == 1L) return true
        return server?.account?.id && server.account.id == user.account.id
    }

    static String param(ViewModel<Map> model, String name) {
        def value = null
        try {
            value = model?.request?.getParameter(name)
        } catch (Exception ignored) { }
        if (value == null && model?.object instanceof Map) {
            value = (model.object as Map)[name]
        }
        if (value == null && model?.opts instanceof Map) {
            value = (model.opts as Map)[name]
        }
        return value?.toString()
    }
}
