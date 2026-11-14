// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.ui

import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Permission
import com.morpheusdata.model.User
import com.morpheusdata.views.JsonResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import groovy.util.logging.Slf4j

/**
 * Endpoint behind the "Harvester Console" tab on Harvester hosts (Infrastructure > Compute > Hosts):
 *   POST .../harvester-kubectl/run   serverId (a Harvester host), command, input (manifests for "-f -")
 * Commands run against the whole Harvester cluster of the host's cloud.
 * Read commands need "Harvester: Kubernetes Console (read-only)" or the read and write permission at Full;
 * commands that change the cluster need "Harvester: Kubernetes Console (read and write)" at Full.
 * Every command is written to the Morpheus log with the user and the cluster.
 * Errors come back as {success: false, output} with HTTP 200, so the tab can show them.
 */
@Slf4j
class HarvesterKubectlController implements PluginController {

    static final String READ_PERMISSION_CODE = 'harvester-kubectl-read'
    static final String READ_PERMISSION_NAME = 'Harvester: Kubernetes Console (read-only)'
    static final String WRITE_PERMISSION_CODE = 'harvester-kubectl-write'
    static final String WRITE_PERMISSION_NAME = 'Harvester: Kubernetes Console (read and write)'
    static final String ROUTE_RUN = '/harvester-kubectl/run'
    static final int MAX_COMMAND = 4096
    static final int MAX_INPUT = 512 * 1024

    Plugin plugin
    MorpheusContext morpheus
    String code = 'harvester-kubectl-controller'
    String name = 'Harvester Kubernetes Console'

    HarvesterKubectlController(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheus = morpheus
    }

    static List<Permission> permissions() {
        return [
                Permission.build(READ_PERMISSION_NAME, READ_PERMISSION_CODE, HarvesterVmYamlController.PERMISSION_SUB_CATEGORY,
                        [Permission.AccessType.none, Permission.AccessType.full]),
                Permission.build(WRITE_PERMISSION_NAME, WRITE_PERMISSION_CODE, HarvesterVmYamlController.PERMISSION_SUB_CATEGORY,
                        [Permission.AccessType.none, Permission.AccessType.full])
        ]
    }

    /** Morpheus calls the route for users holding either code; canRead()/canWrite() then require Full */
    @Override
    List<Route> getRoutes() {
        return [Route.build(ROUTE_RUN, 'run', [Permission.build(READ_PERMISSION_CODE, 'full'), Permission.build(WRITE_PERMISSION_CODE, 'full')])]
    }

    static boolean canWrite(User user) {
        return user?.permissions?.get(WRITE_PERMISSION_CODE)?.toString() == 'full'
    }

    static boolean canRead(User user) {
        return canWrite(user) || user?.permissions?.get(READ_PERMISSION_CODE)?.toString() == 'full'
    }

    /** A Harvester node synced as a Morpheus host (hypervisor) */
    static boolean isHarvesterHost(MorpheusContext morpheus, ComputeServer server) {
        if (!server || server.serverType != HarvesterConstants.SERVER_TYPE_HYPERVISOR) return false
        String typeCode = server.computeServerType?.code
        if (typeCode in [HarvesterConstants.SERVER_TYPE_HARVESTER_MASTER, HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER]) return true
        Long cloudId = server.cloud?.id
        return cloudId ? VmYamlView.isHarvesterCloud(morpheus, cloudId) : false
    }

    /** Same tenant as the host, or a user of the master tenant */
    static boolean canAccess(User user, ComputeServer server) {
        if (!user?.account) return false
        if (user.account.masterAccount || user.account.id == 1L) return true
        return server?.account?.id && server.account.id == user.account.id
    }

    def run(ViewModel<Map> model) {
        try {
            if (model?.request?.respondsTo('getMethod') && model.request.method?.toUpperCase() != 'POST') {
                return JsonResponse.of([success: false, output: 'error: use POST'])
            }
            User user = model?.user
            if (!canRead(user)) {
                return JsonResponse.of([success: false, output: "error: you need Full access to '${READ_PERMISSION_NAME}' or '${WRITE_PERMISSION_NAME}' (Administration > Roles, Infrastructure)."])
            }
            String idParam = HarvesterVmYamlController.param(model, 'serverId')
            if (!idParam?.isLong()) return JsonResponse.of([success: false, output: 'error: missing serverId'])
            ComputeServer host = morpheus.services.computeServer.get(idParam.toLong())
            if (!host || !isHarvesterHost(morpheus, host)) return JsonResponse.of([success: false, output: 'error: Harvester host not found'])
            if (!canAccess(user, host)) return JsonResponse.of([success: false, output: "error: you don't have access to this host"])
            ComputeServerGroup group = VmYamlView.connectionFor(morpheus, host)
            if (!group) return JsonResponse.of([success: false, output: "error: the Harvester connection for this host's cloud was not found"])

            String command = HarvesterVmYamlController.param(model, 'command')?.trim() ?: ''
            String input = HarvesterVmYamlController.param(model, 'input')
            if (command.length() > MAX_COMMAND) return JsonResponse.of([success: false, output: 'error: the command is too long'])
            if (input && input.length() > MAX_INPUT) return JsonResponse.of([success: false, output: 'error: the manifest is too large'])

            Map parsed
            try {
                parsed = KubectlService.parse(command)
            } catch (IllegalArgumentException e) {
                return JsonResponse.of([success: false, output: "error: ${e.message}".toString()])
            }
            boolean write = KubectlService.isWrite(parsed)
            if (write && !canWrite(user)) {
                log.info("Harvester console: ${user?.username} refused (read-only) on cloud ${host.cloud?.name ?: group.name}: ${command}")
                return JsonResponse.of([success: false, output: "error: '${parsed.verb}' changes the cluster and needs Full access to '${WRITE_PERMISSION_NAME}'.".toString()])
            }
            log.info("Harvester console: ${user?.username} on cloud ${host.cloud?.name ?: group.name} (from host ${host.name}): ${command}${input ? " (with ${input.length()} characters of input)" : ''}")
            KubectlService service = new KubectlService(new HarvesterApiClient(group), canWrite(user), "cluster-${group.id}")
            Map result = service.run(command, input)
            if (write && !result.success) {
                log.info("Harvester console: ${user?.username} on cloud ${host.cloud?.name ?: group.name}: '${command}' failed: ${result.output}")
            }
            return JsonResponse.of(result + [canWrite: canWrite(user)])
        } catch (Exception e) {
            log.warn("HarvesterKubectlController failed: ${e.message}", e)
            return JsonResponse.of([success: false, output: "error: ${e.message}".toString()])
        }
    }
}
