// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.ui

import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.hpe.morpheus.harvester.plugin.utils.DBUtils
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import groovy.util.logging.Slf4j

/**
 * Loads a Harvester VM for the "Harvester YAML" tab and turns it into display YAML.
 */
@Slf4j
class VmYamlView {

    static final List<String> HIDDEN_ANNOTATIONS = ['kubectl.kubernetes.io/last-applied-configuration']
    static final List<String> SECRET_KEYS = ['userData', 'networkData', 'userDataBase64', 'networkDataBase64']

    private static final long CLOUD_CACHE_TTL_MS = 10 * 60 * 1000L
    private static final Map<Long, List> CLOUD_IS_HARVESTER = new java.util.concurrent.ConcurrentHashMap<Long, List>()
    static final List<String> PROVISION_TYPE_CODES = [HarvesterConstants.PROVISION_TYPE_CODE, HarvesterConstants.LEGACY_PROVISION_TYPE_CODE]

    /**
     * Cheap check for the instance tab, run on every instance page: uses fields already on the instance
     * (provision type, cloud id) and a cached cloud type, no per-instance lookups.
     */
    static boolean isHarvesterInstance(MorpheusContext morpheus, def instance) {
        if (!instance) return false
        if (instance.provisionType in PROVISION_TYPE_CODES) return true
        Long cloudId = instance.provisionZoneId as Long
        return cloudId ? isHarvesterCloud(morpheus, cloudId) : false
    }

    /**
     * @return true when the server is a VM (not a host) in a Harvester cloud. No lookups except a cached cloud type.
     */
    static boolean isHarvesterVm(MorpheusContext morpheus, ComputeServer server) {
        if (!server) return false
        if (server.serverType == HarvesterConstants.SERVER_TYPE_HYPERVISOR) return false
        if (server.category?.startsWith(HarvesterConstants.CATEGORY_HARVESTER_VM_PREFIX)) return true
        Long cloudId = server.cloud?.id
        return cloudId ? isHarvesterCloud(morpheus, cloudId) : false
    }

    static boolean isHarvesterCloud(MorpheusContext morpheus, Long cloudId) {
        long now = System.currentTimeMillis()
        List cached = CLOUD_IS_HARVESTER.get(cloudId)
        if (cached && (now - (cached[1] as long)) < CLOUD_CACHE_TTL_MS) {
            return cached[0] as boolean
        }
        boolean result = false
        try {
            result = morpheus.services.cloud.get(cloudId)?.cloudType?.code == HarvesterConstants.CLOUD_PROVIDER_CODE
        } catch (Exception e) {
            log.debug "VmYamlView: cloud ${cloudId} lookup failed: ${e.message}"
        }
        CLOUD_IS_HARVESTER.put(cloudId, [result, now])
        return result
    }

    static void clearCloudCache() {
        CLOUD_IS_HARVESTER.clear()
        CONNECTIONS?.clear()
    }

    /**
     * The first server of an instance, fully loaded
     */
    static ComputeServer serverForInstance(MorpheusContext morpheus, def instance) {
        if (!instance?.id) return null
        try {
            // the instance passed to the tab usually carries its workloads; only look them up when it doesn't
            Long serverId = null
            try {
                serverId = instance.containers?.find { it?.server?.id }?.server?.id as Long
            } catch (Exception ignored) { }
            if (!serverId) {
                def workloads = morpheus.services.workload.list(new DataQuery().withFilter(new DataFilter('instance.id', instance.id)))
                serverId = workloads?.find { it?.server?.id }?.server?.id as Long
            }
            return serverId ? morpheus.services.computeServer.get(serverId) : null
        } catch (Exception e) {
            log.debug "VmYamlView: no server for instance ${instance?.id}: ${e.message}"
            return null
        }
    }

    /**
     * Fetches the VM and builds the template model: vmName, namespace, yaml, harvesterUrl, error
     */
    static Map load(MorpheusContext morpheus, ComputeServer server) {
        Map model = [serverId: server?.id, serverName: server?.name, fetchedAt: new Date().format("yyyy-MM-dd HH:mm:ss z")]
        long started = System.currentTimeMillis()
        try {
            Map found = resolve(morpheus, server)
            if (found.error) {
                model.error = found.error
                return model
            }
            Map vm = found.vm as Map
            model.vmName = vm.metadata?.name
            model.namespace = vm.metadata?.namespace
            model.printableStatus = vm.status?.printableStatus
            model.yaml = YamlWriter.toYaml(cleanForDisplay(vm))
            model.hiddenSecrets = maskSecrets(deepCopy(vm.spec))
            String root = (found.client as HarvesterApiClient).baseUrl?.replaceFirst('/k8s/clusters/local/?$', '')
            if (root && model.vmName && model.namespace) {
                model.harvesterUrl = "${root}/dashboard/harvester/c/local/kubevirt.io.virtualmachine/${model.namespace}/${model.vmName}".toString()
            }
        } catch (Exception e) {
            log.warn "VmYamlView: loading VM YAML for ${server?.name} failed: ${e.message}", e
            model.error = "Couldn't load the VM from Harvester: ${e.message}"
        } finally {
            log.debug "VmYamlView: YAML for ${server?.name} took ${System.currentTimeMillis() - started} ms"
        }
        return model
    }

    /**
     * The YAML to edit: everything except status and managedFields, with cloud-init data and all annotations kept,
     * so saving it back changes only what the user edited.
     */
    static Map loadForEdit(MorpheusContext morpheus, ComputeServer server) {
        Map found = resolve(morpheus, server)
        if (found.error) {
            return [success: false, msg: found.error]
        }
        Map vm = deepCopy(found.vm) as Map
        vm.remove('status')
        (vm.metadata as Map)?.remove('managedFields')
        return [success: true, yaml: YamlWriter.toYaml(vm), vmName: vm.metadata?.name, namespace: vm.metadata?.namespace,
                resourceVersion: vm.metadata?.resourceVersion]
    }

    /**
     * Replaces the VM with the edited YAML: Harvester checks it with a dry run first, then applies it. Harvester (Kubernetes)
     * rejects YAML for another VM (name/namespace must match) and edits made on an older copy (resourceVersion conflict).
     */
    static Map save(MorpheusContext morpheus, ComputeServer server, String yamlText) {
        if (!yamlText?.trim()) {
            return [success: false, msg: 'The YAML is empty.']
        }
        int bad = -1
        char[] chars = yamlText.toCharArray()
        for (int i = 0; i < chars.length; i++) {
            if (((int) chars[i]) > 0xFF) { bad = i; break }
        }
        if (bad >= 0) {
            return [success: false, msg: "The YAML contains a character that can't be sent (at position ${bad}). Use plain ASCII/Latin-1 text."]
        }
        Map found = resolve(morpheus, server)
        if (found.error) {
            return [success: false, msg: found.error]
        }
        Map vm = found.vm as Map
        HarvesterApiClient client = found.client as HarvesterApiClient
        String path = "/apis/kubevirt.io/v1/namespaces/${vm.metadata.namespace}/virtualmachines/${vm.metadata.name}"
        def dryRun = client.putRaw(path, yamlText, 'application/yaml', [dryRun: 'All'])
        if (!dryRun?.success) {
            return [success: false, msg: "Harvester rejected the YAML: ${errorMessage(dryRun)}"]
        }
        def resp = client.putRaw(path, yamlText, 'application/yaml')
        if (!resp?.success) {
            return [success: false, msg: "Harvester didn't save the YAML: ${errorMessage(resp)}"]
        }
        log.info "VmYamlView: VM ${vm.metadata.namespace}/${vm.metadata.name} updated from the Harvester YAML tab"
        def conditions = (resp.data?.status?.conditions ?: []) as List
        boolean restart = conditions.any { it?.type == 'RestartRequired' && it?.status?.toString() == 'True' }
        return [success: true, msg: restart ? 'Saved. Harvester says the VM needs a restart for some changes to apply.' : 'Saved.',
                resourceVersion: resp.data?.metadata?.resourceVersion]
    }

    static String errorMessage(def resp) {
        def data = resp?.data
        if (data instanceof Map && data.message) {
            return data.message.toString()
        }
        if (resp?.content) {
            try {
                def parsed = new groovy.json.JsonSlurper().parseText(resp.content.toString())
                if (parsed instanceof Map && parsed.message) return parsed.message.toString()
            } catch (Exception ignored) { }
            return resp.content.toString().take(500)
        }
        return (resp?.msg ?: resp?.errors ?: 'unknown error').toString()
    }

    /**
     * Finds the VM's connection, namespace and current object.
     * @return [server, client, vm] or [error]
     */
    static Map resolve(MorpheusContext morpheus, ComputeServer server) {
        if (server?.id && !server.cloud?.id) {
            server = morpheus.services.computeServer.get(server.id) ?: server
        }
        ComputeServerGroup connection = connectionFor(morpheus, server)
        if (!connection) {
            return [error: "Can't find the Harvester cloud connection for this VM."]
        }
        def project = server.getConfigProperty('project')
        String namespace = project instanceof Map ? project.name : project?.toString()
        if (!namespace) {
            namespace = DBUtils.namespaceForServer(morpheus, server)
        }
        HarvesterApiClient client = new HarvesterApiClient(connection)
        Map vm = findVm(client, namespace, server)
        if (!vm) {
            return [error: "VM ${server?.name} wasn't found in Harvester" + (namespace ? " (namespace ${namespace})." : '.')]
        }
        return [server: server, client: client, vm: vm, connection: connection]
    }

    private static final long CONNECTION_CACHE_TTL_MS = 5 * 60 * 1000L
    private static final Map<Long, List> CONNECTIONS = new java.util.concurrent.ConcurrentHashMap<Long, List>()

    static ComputeServerGroup connectionFor(MorpheusContext morpheus, ComputeServer server) {
        // A Harvester cloud has one connection record: cache it per cloud
        Long cloudId = server?.cloud?.id
        if (cloudId) {
            List cached = CONNECTIONS.get(cloudId)
            if (cached && (System.currentTimeMillis() - (cached[1] as long)) < CONNECTION_CACHE_TTL_MS) {
                return cached[0] as ComputeServerGroup
            }
            ComputeServerGroup byCloud = morpheus.services.cluster.find(new DataQuery().withFilters(
                    new DataFilter('category', "${HarvesterConstants.CATEGORY_CLOUD_CLUSTER_PREFIX}${cloudId}".toString())))
            if (byCloud) {
                CONNECTIONS.put(cloudId, [byCloud, System.currentTimeMillis()])
                return byCloud
            }
        }
        CloudPool pool = server?.resourcePool
        if (pool?.id && !pool.category) {
            pool = morpheus.services.cloud.pool.get(pool.id)
        }
        ComputeServerGroup connection = pool ? DBUtils.resolveConnection(morpheus, pool) : null
        if (!connection && server?.cloud?.id) {
            connection = morpheus.services.cluster.find(new DataQuery().withFilters(
                    new DataFilter('category', "${HarvesterConstants.CATEGORY_CLOUD_CLUSTER_PREFIX}${server.cloud.id}".toString())))
        }
        return connection
    }

    /** Finds the VM by its uid (the server's externalId), falling back to its name */
    static Map findVm(HarvesterApiClient client, String namespace, ComputeServer server) {
        // Usually the VM has the server's name: fetch just that one VM
        if (namespace && server?.name) {
            def one = client.get("/apis/kubevirt.io/v1/namespaces/${namespace}/virtualmachines/${server.name}")
            Map vm = one?.success ? (one.data as Map) : null
            if (vm?.metadata && (!server.externalId || vm.metadata.uid == server.externalId)) {
                return vm
            }
        }
        String path = namespace ? "/apis/kubevirt.io/v1/namespaces/${namespace}/virtualmachines" : HarvesterConstants.HARVESTER_API_V1_VMS
        def resp = client.get(path)
        if (!resp?.success) {
            throw new RuntimeException("Harvester returned an error listing VMs: ${resp?.msg ?: resp?.error}")
        }
        List items = (resp.data?.items ?: []) as List
        return (items.find { it?.metadata?.uid && it.metadata.uid == server?.externalId } ?:
                items.find { it?.metadata?.name == server?.name }) as Map
    }

    /** Drops what Harvester fills in itself: status, managedFields, the last-applied annotation */
    static Map cleanForDisplay(Map vm) {
        Map copy = deepCopy(vm) as Map
        copy.remove('status')
        Map metadata = copy.metadata as Map
        if (metadata) {
            metadata.remove('managedFields')
            Map annotations = metadata.annotations as Map
            if (annotations) {
                HIDDEN_ANNOTATIONS.each { annotations.remove(it) }
                if (annotations.isEmpty()) metadata.remove('annotations')
            }
        }
        maskSecrets(copy)
        return copy
    }

    /**
     * Replaces inline cloud-init data (can hold passwords) with a placeholder. Mutates the given structure.
     * @return how many values were hidden
     */
    static int maskSecrets(def node) {
        int count = 0
        if (node instanceof Map) {
            Map map = node as Map
            map.keySet().toList().each { k ->
                def v = map[k]
                if (SECRET_KEYS.contains(k?.toString()) && v instanceof CharSequence) {
                    if (!v.toString().startsWith('(hidden')) {
                        map[k] = "(hidden: ${v.toString().readLines().size()} lines)".toString()
                        count++
                    }
                } else {
                    count += maskSecrets(v)
                }
            }
        } else if (node instanceof List) {
            (node as List).each { count += maskSecrets(it) }
        }
        return count
    }

    private static def deepCopy(def v) {
        if (v instanceof Map) {
            Map m = new LinkedHashMap()
            (v as Map).each { k, val -> m[k] = deepCopy(val) }
            return m
        }
        if (v instanceof List) {
            return (v as List).collect { deepCopy(it) }
        }
        return v
    }
}
