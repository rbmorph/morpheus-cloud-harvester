// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.ui

import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.hpe.morpheus.harvester.plugin.provision.CdromHelper
import com.hpe.morpheus.harvester.plugin.provision.VirtualMachineProvisioning
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j

/**
 * Attach an ISO image to a VM as a CD-ROM, eject a CD-ROM, restart the VM. Harvester applies CD-ROM changes when the
 * VM restarts. Results are maps [success, msg, ...] for the Harvester tab.
 */
@Slf4j
class VmCdromService {

    private MorpheusContext morpheus

    VmCdromService(MorpheusContext morpheus) {
        this.morpheus = morpheus
    }

    /** The VM's CD-ROM drives and power state: [success, vmName, namespace, status, cdroms] */
    Map list(ComputeServer server) {
        Map found = VmYamlView.resolve(morpheus, server)
        if (found.error) return [success: false, msg: found.error]
        Map vm = found.vm as Map
        return [success  : true,
                vmName   : vm.metadata?.name,
                namespace: vm.metadata?.namespace,
                status   : vm.status?.printableStatus,
                cdroms   : CdromHelper.cdroms(vm)]
    }

    /** ISO images of the VM's Harvester cloud: [success, isos: [[id, name]]] */
    Map listIsos(ComputeServer server) {
        ComputeServerGroup connection = VmYamlView.connectionFor(morpheus, server)
        if (!connection) return [success: false, msg: "Can't find the Harvester cloud connection for this VM."]
        List images = morpheus.services.virtualImage.list(new DataQuery().withFilters(
                new DataFilter('category', "harvester.image.template.${connection.id}".toString()),
                new DataFilter('imageType', 'iso')
        )) ?: []
        List isos = images.findAll { it?.deleted != true && !(it?.status?.toString() in ['Failed', 'Saving']) }
                .sort { it.name?.toLowerCase() }
                .collect { [id: it.id, name: it.name] }
        return [success: true, isos: isos]
    }

    Map attach(ComputeServer server, Long virtualImageId, boolean bootFirst) {
        Map found = VmYamlView.resolve(morpheus, server)
        if (found.error) return [success: false, msg: found.error]
        Map vm = found.vm as Map
        HarvesterApiClient client = found.client as HarvesterApiClient
        ComputeServerGroup connection = found.connection as ComputeServerGroup

        def image = virtualImageId ? morpheus.services.virtualImage.get(virtualImageId) : null
        if (!image || !CdromHelper.isIsoImage(image) || image.category != "harvester.image.template.${connection?.id}".toString()) {
            return [success: false, msg: 'Choose an ISO image of this Harvester cloud.']
        }
        Map config = image.getConfigMap() ?: [:]
        if (!config.imageId || !config.imageStorageClassName) {
            return [success: false, msg: "ISO image ${image.name} has no Harvester image reference. Refresh the cloud and try again."]
        }

        String namespace = vm.metadata.namespace
        String vmName = vm.metadata.name
        String diskName = CdromHelper.nextCdromName(vm)
        String claimName = CdromHelper.claimName(vmName, diskName)
        Long size = VirtualMachineProvisioning.isoSize(client, image, config)

        ServiceResponse pvc = client.post(CdromHelper.pvcPath(namespace),
                CdromHelper.isoPvcBody(namespace, claimName, vmName, config.imageId as String,
                        config.imageStorageClassName as String, size))
        if (!pvc?.success) {
            return [success: false, msg: "Harvester didn't create the CD-ROM volume: ${VmYamlView.errorMessage(pvc)}"]
        }
        ServiceResponse patched = client.patch(CdromHelper.vmPath(namespace, vmName),
                JsonOutput.toJson(CdromHelper.attachPatch(vm, diskName, claimName, bootFirst)))
        if (!patched?.success) {
            client.delete(CdromHelper.pvcPath(namespace, claimName))
            return [success: false, msg: "Harvester didn't add the CD-ROM: ${VmYamlView.errorMessage(patched)}"]
        }
        log.info("Harvester: attached ISO ${config.imageId} to ${namespace}/${vmName} as ${diskName}")
        return result("ISO ${image.name} attached as ${diskName}.", vm)
    }

    Map eject(ComputeServer server, String diskName) {
        Map found = VmYamlView.resolve(morpheus, server)
        if (found.error) return [success: false, msg: found.error]
        Map vm = found.vm as Map
        HarvesterApiClient client = found.client as HarvesterApiClient
        String namespace = vm.metadata.namespace
        String vmName = vm.metadata.name

        Map cdrom = CdromHelper.cdroms(vm).find { it.name == diskName }
        List patch = cdrom ? CdromHelper.ejectPatch(vm, diskName) : null
        if (!patch) return [success: false, msg: "The VM has no CD-ROM ${diskName}."]
        ServiceResponse patched = client.patch(CdromHelper.vmPath(namespace, vmName), JsonOutput.toJson(patch))
        if (!patched?.success) {
            return [success: false, msg: "Harvester didn't remove the CD-ROM: ${VmYamlView.errorMessage(patched)}"]
        }
        // Delete the ISO volume when it's an image copy (as Harvester's own eject does); keep any other volume
        String claim = cdrom.claimName
        if (claim) {
            ServiceResponse pvc = client.get(CdromHelper.pvcPath(namespace, claim))
            if (pvc?.success && pvc.data?.metadata?.annotations?.get('harvesterhci.io/imageId')) {
                ServiceResponse deleted = client.delete(CdromHelper.pvcPath(namespace, claim))
                if (!deleted?.success) {
                    log.warn("Harvester: CD-ROM volume ${namespace}/${claim} not deleted: ${VmYamlView.errorMessage(deleted)}")
                }
            }
        }
        log.info("Harvester: ejected CD-ROM ${diskName} from ${namespace}/${vmName}")
        return result("CD-ROM ${diskName} ejected.", vm)
    }

    Map restart(ComputeServer server) {
        Map found = VmYamlView.resolve(morpheus, server)
        if (found.error) return [success: false, msg: found.error]
        Map vm = found.vm as Map
        HarvesterApiClient client = found.client as HarvesterApiClient
        String action = vm.status?.printableStatus == 'Stopped' ? 'start' : 'restart'
        ServiceResponse resp = client.put("/apis/subresources.kubevirt.io/v1/namespaces/${vm.metadata.namespace}/virtualmachines/${vm.metadata.name}/${action}".toString(), [:])
        if (!resp?.success) {
            return [success: false, msg: "Harvester didn't ${action} the VM: ${VmYamlView.errorMessage(resp)}"]
        }
        return [success: true, msg: action == 'start' ? 'VM starting.' : 'VM restarting.']
    }

    private static Map result(String done, Map vm) {
        boolean running = vm?.status?.printableStatus && vm.status.printableStatus != 'Stopped'
        return [success: true, restartRequired: running,
                msg: running ? "${done} Harvester applies it when the VM restarts.".toString() : "${done} It takes effect when the VM starts.".toString()]
    }
}
