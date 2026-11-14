// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.provision

import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.hpe.morpheus.harvester.plugin.apihelper.VmSnapshotApiHelper
import com.hpe.morpheus.harvester.plugin.utils.CommonUtils
import com.hpe.morpheus.harvester.plugin.utils.DBUtils
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Snapshot
import com.morpheusdata.model.projection.SnapshotIdentityProjection
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

import java.time.Instant

/**
 * Morpheus snapshots of Harvester VMs, as Harvester VM snapshots (VirtualMachineBackup type "snapshot").
 * A Morpheus snapshot's externalId is "<namespace>/<snapshot name>".
 */
@Slf4j
class VirtualMachineSnapshots {

    // Poll intervals and limits (fields so tests can shorten them)
    long pollIntervalMs = 5000L
    long snapshotTimeoutMs = 15 * 60 * 1000L
    long restoreTimeoutMs = 30 * 60 * 1000L
    long powerTimeoutMs = 10 * 60 * 1000L

    private MorpheusContext morpheusContext
    private VmSnapshotApiHelper api

    VirtualMachineSnapshots(MorpheusContext morpheusContext, VmSnapshotApiHelper api = null) {
        this.morpheusContext = morpheusContext
        this.api = api
    }

    // --- Create -------------------------------------------------------------------------------------------------

    ServiceResponse createSnapshot(ComputeServer server, Map opts) {
        try {
            Map target = resolve(server)
            String namespace = target.namespace
            String vmName = target.vmName
            ServiceResponse check = checkVm(namespace, vmName, server)
            if (!check.success) return check

            String name = snapshotName(opts?.snapshotName?.toString(), vmName)
            log.info("Harvester: creating snapshot ${namespace}/${name} of VM ${vmName}")
            ServiceResponse created = api.create(VmSnapshotApiHelper.backupsPath(namespace),
                    VmSnapshotApiHelper.snapshotBody(namespace, name, vmName))
            if (!created?.success) {
                return fail("Harvester didn't create the snapshot: ${message(created)}")
            }

            Map ready = waitForSnapshot(namespace, name)
            if (ready.error) {
                return fail(ready.error as String)
            }
            Map item = ready.item as Map
            Snapshot saved = saveSnapshotRecord(server, item, opts?.description?.toString())
            return ServiceResponse.success([id: saved?.id, externalId: externalId(namespace, name)])
        } catch (Exception e) {
            log.error("Harvester: error creating snapshot of ${server?.name}: ${e.message}", e)
            return fail("Error creating snapshot: ${e.message}")
        }
    }

    private Map waitForSnapshot(String namespace, String name) {
        long deadline = System.currentTimeMillis() + snapshotTimeoutMs
        while (true) {
            ServiceResponse resp = api.get(VmSnapshotApiHelper.backupsPath(namespace, name))
            if (resp?.success) {
                Map item = resp.data as Map
                if (VmSnapshotApiHelper.isReady(item)) {
                    return [item: item]
                }
                String error = VmSnapshotApiHelper.errorOf(item)
                if (error) {
                    return [error: "Harvester couldn't take the snapshot: ${error}".toString()]
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                return [error: "The snapshot ${namespace}/${name} wasn't ready after ${snapshotTimeoutMs.intdiv(60000)} minutes. " +
                        "It may still finish in Harvester; it will then appear here on a later cloud refresh.".toString()]
            }
            sleep(pollIntervalMs)
        }
    }

    /** Morpheus record for a Harvester snapshot, linked to the server; reuses one the cloud sync already added */
    Snapshot saveSnapshotRecord(ComputeServer server, Map item, String description = null) {
        String extId = externalId(item?.metadata?.namespace as String, item?.metadata?.name as String)
        SnapshotIdentityProjection existing = findRecord(server?.cloud?.id, extId)
        if (existing) {
            return morpheusContext.services.snapshot.get(existing.id)
        }
        Snapshot snapshot = buildRecord(item, server, description)
        Snapshot saved = morpheusContext.async.snapshot.create(snapshot).blockingGet()
        if (saved) {
            morpheusContext.async.snapshot.addSnapshot(saved, server).blockingGet()
        }
        return saved
    }

    static Snapshot buildRecord(Map item, ComputeServer server, String description = null) {
        return new Snapshot(
                account: server?.account ?: server?.cloud?.owner,
                name: item?.metadata?.name,
                externalId: externalId(item?.metadata?.namespace as String, item?.metadata?.name as String),
                cloud: server?.cloud,
                snapshotCreated: parseTime(item?.status?.creationTime ?: item?.metadata?.creationTimestamp),
                currentlyActive: true,
                maxStorage: VmSnapshotApiHelper.sizeOf(item),
                description: description ?: "Harvester snapshot of ${item?.spec?.source?.name}".toString()
        )
    }

    private SnapshotIdentityProjection findRecord(Long cloudId, String extId) {
        if (!cloudId || !extId) return null
        try {
            return morpheusContext.async.snapshot.listIdentityProjections(cloudId, null)
                    .filter { it.externalId == extId }.toList().blockingGet()?.find { it }
        } catch (Exception e) {
            log.debug("Harvester: snapshot lookup failed: ${e.message}")
            return null
        }
    }

    // --- Delete -------------------------------------------------------------------------------------------------

    ServiceResponse deleteSnapshot(Snapshot snapshot, Map opts) {
        try {
            ComputeServer server = opts?.serverId ? morpheusContext.services.computeServer.get(opts.serverId as Long) : null
            Map ids = parseExternalId(snapshot?.externalId)
            if (!ids) {
                return fail("Snapshot ${snapshot?.name} has no Harvester reference")
            }
            if (!api) {
                api = new VmSnapshotApiHelper(new HarvesterApiClient(connectionFor(server, snapshot?.cloud?.id)))
            }
            return deleteInHarvester(ids.namespace as String, ids.name as String)
        } catch (Exception e) {
            log.error("Harvester: error deleting snapshot ${snapshot?.name}: ${e.message}", e)
            return fail("Error deleting snapshot: ${e.message}")
        }
    }

    ServiceResponse deleteSnapshots(ComputeServer server, Map opts) {
        try {
            List<SnapshotIdentityProjection> snapshots = (server?.snapshots ?: []) as List
            if (!snapshots) return ServiceResponse.success()
            resolve(server)
            List<String> failed = []
            snapshots.each { s ->
                Map ids = parseExternalId(s.externalId)
                if (ids && !deleteInHarvester(ids.namespace as String, ids.name as String).success) {
                    failed << s.name
                }
            }
            return failed ? fail("Snapshots not deleted in Harvester: ${failed.join(', ')}") : ServiceResponse.success()
        } catch (Exception e) {
            log.error("Harvester: error deleting snapshots of ${server?.name}: ${e.message}", e)
            return fail("Error deleting snapshots: ${e.message}")
        }
    }

    private ServiceResponse deleteInHarvester(String namespace, String name) {
        log.info("Harvester: deleting snapshot ${namespace}/${name}")
        ServiceResponse resp = api.delete(VmSnapshotApiHelper.backupsPath(namespace, name))
        if (resp?.success || VmSnapshotApiHelper.isNotFound(resp)) {
            return ServiceResponse.success()
        }
        return fail("Harvester didn't delete the snapshot ${namespace}/${name}: ${message(resp)}")
    }

    // --- Revert -------------------------------------------------------------------------------------------------

    /**
     * Restores the snapshot onto the VM. Harvester requires the VM to be stopped: a running VM is stopped first and
     * started again afterwards. The VM's previous volumes are kept (the only option Harvester allows for snapshots).
     */
    ServiceResponse revertSnapshot(ComputeServer server, Snapshot snapshot, Map opts) {
        try {
            Map target = resolve(server)
            String namespace = target.namespace
            String vmName = target.vmName
            Map ids = parseExternalId(snapshot?.externalId)
            if (!ids) {
                return fail("Snapshot ${snapshot?.name} has no Harvester reference")
            }
            if (ids.namespace != namespace) {
                return fail("Snapshot ${snapshot.name} is in namespace ${ids.namespace}, the VM is in ${namespace}. " +
                        "Harvester restores snapshots only in their own namespace.")
            }
            ServiceResponse check = checkVm(namespace, vmName, server)
            if (!check.success) return check
            Map vm = check.data as Map

            ServiceResponse snap = api.get(VmSnapshotApiHelper.backupsPath(namespace, ids.name as String))
            if (!snap?.success) {
                return fail("Snapshot ${ids.namespace}/${ids.name} wasn't found in Harvester: ${message(snap)}")
            }
            if (!VmSnapshotApiHelper.isReady(snap.data as Map)) {
                return fail("Snapshot ${ids.namespace}/${ids.name} isn't ready to use in Harvester")
            }

            String status = vm?.status?.printableStatus?.toString()
            boolean wasRunning = status && status != 'Stopped'
            if (wasRunning) {
                log.info("Harvester: stopping VM ${vmName} to restore snapshot ${ids.name}")
                ServiceResponse stop = api.put(VmSnapshotApiHelper.vmSubresourcePath(namespace, vmName, 'stop'), [:])
                if (!stop?.success) {
                    return fail("Couldn't stop the VM before restoring: ${message(stop)}")
                }
                String stopError = waitForVmStatus(namespace, vmName, 'Stopped')
                if (stopError) return fail(stopError)
            }

            String restoreName = restoreName(vmName)
            log.info("Harvester: restoring snapshot ${namespace}/${ids.name} onto VM ${vmName} (${restoreName})")
            ServiceResponse created = api.create(VmSnapshotApiHelper.restoresPath(namespace),
                    VmSnapshotApiHelper.restoreBody(namespace, restoreName, vmName, ids.name as String))
            if (!created?.success) {
                return fail("Harvester didn't start the restore: ${message(created)}")
            }
            String restoreError = waitForRestore(namespace, restoreName)
            if (restoreError) return fail(restoreError)

            if (wasRunning) {
                log.info("Harvester: starting VM ${vmName} after the restore")
                ServiceResponse start = api.put(VmSnapshotApiHelper.vmSubresourcePath(namespace, vmName, 'start'), [:])
                if (!start?.success) {
                    return fail("The snapshot was restored, but the VM didn't start: ${message(start)}")
                }
            }
            return ServiceResponse.success()
        } catch (Exception e) {
            log.error("Harvester: error reverting ${server?.name} to snapshot ${snapshot?.name}: ${e.message}", e)
            return fail("Error restoring snapshot: ${e.message}")
        }
    }

    private String waitForVmStatus(String namespace, String vmName, String wanted) {
        long deadline = System.currentTimeMillis() + powerTimeoutMs
        while (true) {
            ServiceResponse resp = api.get(VmSnapshotApiHelper.vmPath(namespace, vmName))
            if (resp?.success && resp.data?.status?.printableStatus?.toString() == wanted) {
                return null
            }
            if (System.currentTimeMillis() >= deadline) {
                return "VM ${vmName} didn't reach ${wanted} within ${powerTimeoutMs.intdiv(60000)} minutes".toString()
            }
            sleep(pollIntervalMs)
        }
    }

    private String waitForRestore(String namespace, String name) {
        long deadline = System.currentTimeMillis() + restoreTimeoutMs
        while (true) {
            ServiceResponse resp = api.get(VmSnapshotApiHelper.restoresPath(namespace, name))
            if (resp?.success) {
                Map restore = resp.data as Map
                if (VmSnapshotApiHelper.isRestoreComplete(restore)) {
                    return null
                }
                String error = VmSnapshotApiHelper.restoreError(restore)
                if (error) {
                    return "Harvester couldn't restore the snapshot: ${error}".toString()
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                return "The restore ${namespace}/${name} didn't finish within ${restoreTimeoutMs.intdiv(60000)} minutes; " +
                        "check it in Harvester.".toString()
            }
            sleep(pollIntervalMs)
        }
    }

    // --- Helpers ------------------------------------------------------------------------------------------------

    /** Namespace and VM name of the server; creates the API helper from its cloud connection when not given */
    private Map resolve(ComputeServer server) {
        if (!server) throw new IllegalArgumentException('No server')
        def project = server.getConfigProperty('project')
        String namespace = project instanceof Map ? project.name : project?.toString()
        if (!namespace) {
            namespace = DBUtils.namespaceForServer(morpheusContext, server)
        }
        if (!namespace) throw new IllegalStateException("Can't tell which Harvester namespace ${server.name} is in")
        String vmName = server.getConfigProperty('vmname')?.toString() ?: server.name
        if (!api) {
            api = new VmSnapshotApiHelper(new HarvesterApiClient(connectionFor(server, server.cloud?.id)))
        }
        return [namespace: namespace, vmName: vmName]
    }

    private ComputeServerGroup connectionFor(ComputeServer server, Long cloudId) {
        if (server) {
            String id = CommonUtils.getServerGroupId(server, morpheusContext)?.toString()
            if (id?.isLong()) {
                ComputeServerGroup group = morpheusContext.services.cluster.get(id.toLong())
                if (group) return group
            }
        }
        if (cloudId) {
            ComputeServerGroup group = morpheusContext.services.cluster.find(new DataQuery().withFilters(
                    new DataFilter('category', "${HarvesterConstants.CATEGORY_CLOUD_CLUSTER_PREFIX}${cloudId}".toString())))
            if (group) return group
        }
        throw new IllegalStateException('Harvester connection not found')
    }

    /** The VM must exist and be the one Morpheus knows (same UID) */
    private ServiceResponse checkVm(String namespace, String vmName, ComputeServer server) {
        ServiceResponse resp = api.get(VmSnapshotApiHelper.vmPath(namespace, vmName))
        if (!resp?.success) {
            return fail("VM ${namespace}/${vmName} wasn't found in Harvester: ${message(resp)}")
        }
        String uid = resp.data?.metadata?.uid
        if (server?.externalId && uid && uid != server.externalId) {
            return fail("VM ${vmName} in Harvester isn't the VM Morpheus knows (UID ${uid}, expected " +
                    "${server.externalId}); the original VM was deleted and a new one has the same name.")
        }
        return ServiceResponse.success(resp.data)
    }

    static String externalId(String namespace, String name) {
        return namespace && name ? "${namespace}/${name}".toString() : null
    }

    static Map parseExternalId(String externalId) {
        int slash = externalId?.indexOf('/') ?: -1
        if (slash <= 0 || slash == externalId.length() - 1) return null
        return [namespace: externalId.substring(0, slash), name: externalId.substring(slash + 1)]
    }

    /** Kubernetes name from the requested snapshot name, or "<vm>-<timestamp>" */
    static String snapshotName(String requested, String vmName) {
        String base = requested?.trim() ? requested.trim() : "${vmName}-${utcStamp()}"
        String name = base.toLowerCase().replaceAll(/[^a-z0-9.-]/, '-').replaceAll(/-{2,}/, '-').replaceAll(/^[-.]+|[-.]+$/, '')
        if (name.length() > 63) name = name.substring(0, 63).replaceAll(/[-.]+$/, '')
        return name ?: "${vmName}-snapshot-${System.currentTimeMillis()}".toString()
    }

    static String restoreName(String vmName) {
        String name = "restore-${vmName}-${System.currentTimeMillis()}".toLowerCase().replaceAll(/[^a-z0-9.-]/, '-')
        return name.length() > 63 ? name.substring(name.length() - 63).replaceAll(/^[-.]+/, '') : name
    }

    static Date parseTime(def value) {
        if (!value) return null
        try {
            return Date.from(Instant.parse(value.toString()))
        } catch (Exception ignored) {
            return null
        }
    }

    /** Failed response with the text in msg, where Morpheus shows it (error(String) puts it in errors.error) */
    static ServiceResponse fail(String text) {
        return new ServiceResponse(false, text, [error: text], null)
    }

    private static String utcStamp() {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat('yyyyMMdd-HHmmss')
        format.setTimeZone(TimeZone.getTimeZone('UTC'))
        return format.format(new Date())
    }

    private static String message(ServiceResponse resp) {
        def data = resp?.data
        if (data instanceof Map && (data as Map).message) return (data as Map).message.toString()
        return (resp?.msg ?: resp?.errorCode ?: 'no response').toString()
    }
}
