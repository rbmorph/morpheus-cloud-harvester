// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.provision

import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.hpe.morpheus.harvester.plugin.utils.CommonUtils
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class VirtualMachineDeletion {

    MorpheusContext morpheusContext

    private static final String KUBEVIRT_API_NAMESPACE_PATH = "/apis/kubevirt.io/v1/namespaces/"
    private static final String VIRTUAL_MACHINE_INSTANCES = "/virtualmachineinstances/"

    // constructor
    VirtualMachineDeletion(MorpheusContext morpheusContext) {
        this.morpheusContext = morpheusContext
    }

    /**
     * Deletes a server by removing the associated VM from Harvester
     * @param server the ComputeServer to be deleted
     * @return ServiceResponse indicating success or failure of the deletion operation
     */
    ServiceResponse deleteServer(ComputeServer server) {
        log.info "VirtualMachineDeletion: deleteServer called for server ${server}"

        try {
            String vmName = server.name
            String namespace = server.getConfigProperty("project")?.name
            String vmUuid = server.uuid

            if (!vmName) {
                log.warn "No VM name found for server ${server?.name}"
                return new ServiceResponse(true, "No VM name found to remove", null, null)
            }

            if (!namespace) {
                log.warn "No namespace found for VM ${vmName}, cannot delete VM"
                return new ServiceResponse(false, "No namespace found for VM deletion", null, null)
            }

            log.info "Removing VM: ${vmName} from namespace: ${namespace} with UUID: ${vmUuid}"
            def serverGroupId = CommonUtils.getServerGroupId(server, morpheusContext)
            ComputeServerGroup serverGroup = morpheusContext.services.cluster.get(serverGroupId.toLong())
            if (!serverGroup) {
                log.error "No server group found for VM ${vmName}"
                return new ServiceResponse(false, "No server group found for VM deletion", null, null)
            }

            // Create Harvester API client
            def harvesterApiClient = new HarvesterApiClient(serverGroup)

            // Pre-check: verify the VM in the cluster matches the server being deleted by comparing UIDs.
            // If the name matches but the UID differs, the original VM is already gone (a new VM was
            // created with the same name). Skip ALL deletion steps to avoid touching the new VM's resources.
            String serverExternalId = server.externalId
            if (serverExternalId) {
                String vmApiPath = getVMApiResourcePath(namespace, vmName)
                log.info "Pre-checking VM UID for ${vmName} against externalId: ${serverExternalId}"
                ServiceResponse preCheckResponse = harvesterApiClient.get(vmApiPath)
                if (preCheckResponse.success) {
                    String clusterVmUid = preCheckResponse.data?.metadata?.uid
                    if (clusterVmUid && clusterVmUid != serverExternalId) {
                        log.warn "VM ${vmName} exists in cluster but UID mismatch: expected ${serverExternalId}, " +
                                "found ${clusterVmUid}. Original VM is already gone (replaced by a new VM with same name). " +
                                "Skipping all deletion steps to avoid touching the new VM's resources."
                        return new ServiceResponse(true, "VM ${vmName} already removed (UID mismatch)", null, null)
                    }
                    log.info "VM ${vmName} UID confirmed (${clusterVmUid}), proceeding with deletion"
                } else if (preCheckResponse.data?.code == 404) {
                    log.info "VM ${vmName} not found in cluster (404) — VM resource is gone but associated " +
                            "resources (VMI, volumes, secrets) may still exist. Proceeding with cleanup steps."
                }
                // For other errors, or 404, fall through and let the individual delete steps handle them
            }

            // Step 1: Delete the VirtualMachine (VM) Resource
            log.info "Deleting VirtualMachine resource ${vmName}"
            boolean vmDeleted = deleteVirtualMachine(harvesterApiClient, namespace, vmName)
            if (!vmDeleted) {
                log.error "Failed to delete VirtualMachine ${vmName}"
                return new ServiceResponse(false, "Failed to delete VirtualMachine", null, null)
            }

            // Step 2: Check and Delete the VirtualMachineInstance (VMI)
            log.info "Checking and deleting VirtualMachineInstance ${vmName}"
            boolean vmiDeleted = deleteVirtualMachineInstance(harvesterApiClient, namespace, vmName)
            if (!vmiDeleted) {
                log.warn "VirtualMachineInstance ${vmName} deletion failed or not found"
            }

            // Step 3: Delete Associated DataVolumes and PVCs
            log.info "Deleting associated DataVolumes and PVCs"
            deleteAssociatedVolumes(harvesterApiClient, server, namespace)

            // ISO volumes of the VM's CD-ROMs (created by the plugin, labelled with the VM name)
            deleteCdromVolumes(harvesterApiClient, namespace, vmName)

            // Step 4: Delete platform-specific resources (Secret for Linux, ConfigMap for Windows)
            String platform = server.platform
            log.info "Server platform is: ${platform}"
            if (platform?.toLowerCase() == 'windows') {
                String configMapName = "${vmName}-sysprep"
                log.info "Windows VM detected, checking and deleting ConfigMap: ${configMapName}"
                deleteConfigMap(harvesterApiClient, namespace, configMapName)
            } else {
                String secretName = "secret-${vmName}"
                log.info "Linux VM detected, checking and deleting Secret: ${secretName}"
                deleteSecret(harvesterApiClient, namespace, secretName)
            }

            log.info "VM ${vmName} and associated resources removed successfully"
            return new ServiceResponse(true, "VM ${vmName} removed successfully", null, null)

        } catch (Exception e) {
            log.error "Exception during VM removal for server ${server?.name}: ${e.message}", e
            return new ServiceResponse(false, "Exception during VM removal: ${e.message}", null, null)
        }
    }

    /**
     * Get the VM API resource path for a given namespace and VM name
     * @param namespace the namespace
     * @param vmName the VM name
     * @return the API path
     */
    static String getVMApiResourcePath(String namespace, String vmName) {
        return "${KUBEVIRT_API_NAMESPACE_PATH}${namespace}/virtualmachines/${vmName}"
    }

    /**
     * Step 1: Delete the VirtualMachine (VM) Resource
     * @param harvesterApiClient the API client to use for calls
     * @param namespace the namespace where the VM is located
     * @param vmName the name of the VM
     * @return boolean indicating success or failure
     */
    static boolean deleteVirtualMachine(def harvesterApiClient, String namespace, String vmName) {
        try {
            String vmApiPath = getVMApiResourcePath(namespace, vmName)
            log.info "Checking if VirtualMachine exists at path: ${vmApiPath}"

            // First, check if the VM exists
            ServiceResponse checkVmResponse = harvesterApiClient.get(vmApiPath)

            if (!checkVmResponse.success && checkVmResponse.data?.code == 404) {
                log.info "VirtualMachine ${vmName} does not exist in the cluster, skipping deletion"
                return true // Consider it success since VM doesn't exist
            } else if (!checkVmResponse.success) {
                log.error "Failed to check VirtualMachine ${vmName} existence: ${checkVmResponse.msg}"
                return false
            }

            // VM exists, log its current state
            String vmPhase = checkVmResponse.data?.status?.printableStatus
            log.info "VirtualMachine ${vmName} exists with status: ${vmPhase}, proceeding with deletion"

            log.info "Deleting VirtualMachine at path: ${vmApiPath}"

            ServiceResponse deleteResponse = harvesterApiClient.delete(vmApiPath)

            if (deleteResponse.success) {
                log.info "Successfully initiated VirtualMachine deletion for ${vmName}"

                // Wait for VM to be deleted - check until 404
                int maxRetries = 20
                int waitInterval = 10000 // 10 seconds

                for (int i = 0; i < maxRetries; i++) {
                    ServiceResponse getResponse = harvesterApiClient.get(vmApiPath)
                    if (!getResponse.success && getResponse.data?.code == 404) {
                        log.info "VirtualMachine ${vmName} successfully deleted (404 Not Found)"
                        return true
                    } else if (getResponse.success) {
                        def vmData = getResponse.data
                        String deletionTimestamp = vmData?.metadata?.deletionTimestamp

                        if (deletionTimestamp) {
                            log.info "VirtualMachine ${vmName} is being deleted (deletionTimestamp: ${deletionTimestamp}), waiting... (${i + 1}/${maxRetries})"
                        } else {
                            log.info "VirtualMachine ${vmName} still exists without deletion timestamp, waiting... (${i + 1}/${maxRetries})"
                        }
                    }

                    if (i < maxRetries - 1) {
                        sleep(waitInterval)
                    }
                }

                log.warn "VirtualMachine ${vmName} deletion check timed out, but deletion was initiated"
                return true

            } else {
                log.error "Failed to delete VirtualMachine ${vmName}: ${deleteResponse.msg}"
                return false
            }

        } catch (Exception e) {
            log.error "Exception while deleting VirtualMachine ${vmName}: ${e.message}", e
            return false
        }
    }

    /**
     * Step 2: Check and Delete the VirtualMachineInstance (VMI)
     * @param harvesterApiClient the API client to use for calls
     * @param namespace the namespace where the VMI is located
     * @param vmName the name of the VMI
     * @return boolean indicating success or failure
     */
    static boolean deleteVirtualMachineInstance(def harvesterApiClient, String namespace, String vmName) {
        try {
            String vmiApiPath = "${KUBEVIRT_API_NAMESPACE_PATH}${namespace}${VIRTUAL_MACHINE_INSTANCES}${vmName}"
            log.info "Checking VirtualMachineInstance at path: ${vmiApiPath}"

            // First check if VMI exists
            ServiceResponse getVmiResponse = harvesterApiClient.get(vmiApiPath)

            if (!getVmiResponse.success && getVmiResponse.data?.code == 404) {
                log.info "VirtualMachineInstance ${vmName} does not exist or already deleted"
                return true
            } else if (!getVmiResponse.success) {
                log.warn "Failed to check VirtualMachineInstance ${vmName}: ${getVmiResponse.msg}"
                return false
            }

            // VMI exists, attempt to delete it
            log.info "VirtualMachineInstance ${vmName} exists, deleting it"
            ServiceResponse deleteVmiResponse = harvesterApiClient.delete(vmiApiPath)

            if (deleteVmiResponse.success) {
                log.info "Successfully initiated VirtualMachineInstance deletion for ${vmName}"

                // Wait for VMI to be deleted
                int maxRetries = 15
                int waitInterval = 10000 // 10 seconds

                for (int i = 0; i < maxRetries; i++) {
                    ServiceResponse checkResponse = harvesterApiClient.get(vmiApiPath)

                    if (!checkResponse.success && checkResponse.data?.code == 404) {
                        log.info "VirtualMachineInstance ${vmName} successfully deleted (404 Not Found)"
                        return true
                    } else if (checkResponse.success) {
                        def vmiData = checkResponse.data
                        String deletionTimestamp = vmiData?.metadata?.deletionTimestamp

                        if (deletionTimestamp) {
                            log.info "VirtualMachineInstance ${vmName} is being deleted (deletionTimestamp: ${deletionTimestamp}), waiting... (${i + 1}/${maxRetries})"
                        } else {
                            log.info "VirtualMachineInstance ${vmName} still exists, waiting... (${i + 1}/${maxRetries})"
                        }
                    }

                    if (i < maxRetries - 1) {
                        sleep(waitInterval)
                    }
                }

                log.warn "VirtualMachineInstance ${vmName} deletion check timed out, but deletion was initiated"
                return true

            } else {
                log.error "Failed to delete VirtualMachineInstance ${vmName}: ${deleteVmiResponse.msg}"
                return false
            }

        } catch (Exception e) {
            log.error "Exception while deleting VirtualMachineInstance ${vmName}: ${e.message}", e
            return false
        }
    }

    /**
     * Step 3: Delete Associated DataVolumes and PVCs
     * @param harvesterApiClient the API client to use for calls
     * @param server the ComputeServer that was deleted
     * @param namespace the namespace where resources are located
     */
    static void deleteAssociatedVolumes(def harvesterApiClient, ComputeServer server, String namespace) {
        log.info "Deleting associated DataVolumes and PVCs for VM ${server.hostname} in namespace: ${namespace}"

        try {
            def serverVolumes = server.volumes
            if (!serverVolumes || serverVolumes.size() == 0) {
                log.info "No volumes found associated with VM ${server.hostname}"
                return
            }

            log.info "Found ${serverVolumes.size()} volumes associated with VM ${server.hostname}"

            serverVolumes.each { volume ->
                String storageVolumeTypeCode = volume?.type?.code
                String volumeName = volume?.name

                if (storageVolumeTypeCode == HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME) {
                    deleteDataVolume(harvesterApiClient, namespace, volumeName)
                } else if (storageVolumeTypeCode == HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC) {
                    // For PVC-typed volumes (e.g. converted/managed VMs), check whether a DataVolume
                    // with the same name exists in the cluster before deciding how to delete.
                    // If a DV exists, delete it — the CDI controller will cascade-delete the PVC.
                    // If we deleted the PVC directly while the DV still exists, the DV controller
                    // would immediately recreate it.
                    // Only if no DV is found do we fall back to direct PVC deletion.
                    String dvApiPath = "/apis/cdi.kubevirt.io/v1beta1/namespaces/${namespace}/datavolumes/${volumeName}"
                    log.info "Checking if DataVolume exists for PVC-typed volume ${volumeName} at path: ${dvApiPath}"
                    ServiceResponse dvCheckResponse = harvesterApiClient.get(dvApiPath)
                    if (dvCheckResponse.success) {
                        log.info "DataVolume ${volumeName} found for PVC-typed volume, deleting DV instead of PVC directly"
                        deleteDataVolume(harvesterApiClient, namespace, volumeName)
                    } else if (dvCheckResponse.data?.code == 404) {
                        log.info "No DataVolume found for ${volumeName} (404), falling back to direct PVC deletion"
                        deletePVC(harvesterApiClient, namespace, volumeName)
                    } else {
                        log.warn "Could not determine if DataVolume exists for ${volumeName}: ${dvCheckResponse.msg}. Falling back to PVC deletion"
                        deletePVC(harvesterApiClient, namespace, volumeName)
                    }
                } else {
                    log.info "Volume ${volume?.name} with type ${storageVolumeTypeCode} - skipping deletion"
                }
            }

        } catch (Exception e) {
            log.warn "Exception during deletion of associated volumes for VM ${server.hostname}: ${e.message}", e
        }
    }

    /**
     * Delete a DataVolume resource
     * @param harvesterApiClient the API client to use for calls
     * @param namespace the namespace where the DataVolume is located
     * @param dataVolumeName the name of the DataVolume
     */
    static void deleteDataVolume(def harvesterApiClient, String namespace, String dataVolumeName) {
        try {
            String dataVolumeApiPath = "/apis/cdi.kubevirt.io/v1beta1/namespaces/${namespace}/datavolumes/${dataVolumeName}"
            log.info "Deleting DataVolume: ${dataVolumeName} at path: ${dataVolumeApiPath}"

            ServiceResponse deleteResponse = harvesterApiClient.delete(dataVolumeApiPath)

            if (deleteResponse.success) {
                log.info "Successfully deleted DataVolume: ${dataVolumeName}"
            } else if (deleteResponse.data?.code == 404) {
                log.info "DataVolume ${dataVolumeName} does not exist or already deleted"
            } else {
                log.warn "Failed to delete DataVolume ${dataVolumeName}: ${deleteResponse.msg}"
            }

        } catch (Exception e) {
            log.warn "Exception while deleting DataVolume ${dataVolumeName}: ${e.message}", e
        }
    }

    /**
     * Delete a PVC (PersistentVolumeClaim) resource
     * @param harvesterApiClient the API client to use for calls
     * @param namespace the namespace where the PVC is located
     * @param pvcName the name of the PVC
     */
    /**
     * Deletes the ISO PVCs the plugin created for the VM's CD-ROMs (label morpheus-label.cdrom-vm=<vm>)
     */
    static void deleteCdromVolumes(def harvesterApiClient, String namespace, String vmName) {
        try {
            ServiceResponse resp = harvesterApiClient.get("/api/v1/namespaces/${namespace}/persistentvolumeclaims".toString(),
                    [labelSelector: "${HarvesterConstants.LABEL_CDROM_VM}=${vmName}".toString()])
            if (!resp?.success) {
                log.warn "Unable to list CD-ROM volumes of VM ${vmName}: ${resp?.msg}"
                return
            }
            ((resp.data?.items ?: []) as List).each { pvc ->
                if (pvc?.metadata?.labels?.get(HarvesterConstants.LABEL_CDROM_VM) == vmName) {
                    deletePVC(harvesterApiClient, namespace, pvc.metadata.name as String)
                }
            }
        } catch (Exception e) {
            log.warn "Exception while deleting CD-ROM volumes of VM ${vmName}: ${e.message}", e
        }
    }

    static void deletePVC(def harvesterApiClient, String namespace, String pvcName) {
        try {
            String pvcApiPath = "/api/v1/namespaces/${namespace}/persistentvolumeclaims/${pvcName}"
            log.info "Deleting PVC: ${pvcName} at path: ${pvcApiPath}"

            ServiceResponse deleteResponse = harvesterApiClient.delete(pvcApiPath)

            if (deleteResponse.success) {
                log.info "Successfully deleted PVC: ${pvcName}"
            } else if (deleteResponse.data?.code == 404) {
                log.info "PVC ${pvcName} does not exist or already deleted"
            } else {
                log.warn "Failed to delete PVC ${pvcName}: ${deleteResponse.msg}"
            }

        } catch (Exception e) {
            log.warn "Exception while deleting PVC ${pvcName}: ${e.message}", e
        }
    }

    /**
     * Delete a Secret resource
     * @param harvesterApiClient the API client to use for calls
     * @param namespace the namespace where the Secret is located
     * @param secretName the name of the Secret
     */
    static void deleteSecret(def harvesterApiClient, String namespace, String secretName) {
        try {
            String secretApiPath = "/api/v1/namespaces/${namespace}/secrets/${secretName}"
            log.info "Checking if Secret exists: ${secretName} at path: ${secretApiPath}"

            // First check if Secret exists
            ServiceResponse getResponse = harvesterApiClient.get(secretApiPath)

            if (!getResponse.success && getResponse.data?.code == 404) {
                log.info "Secret ${secretName} does not exist, skipping deletion"
                return
            } else if (!getResponse.success) {
                log.warn "Failed to check Secret ${secretName}: ${getResponse.msg}"
                return
            }

            // Secret exists, proceed with deletion
            log.info "Secret ${secretName} exists, deleting it"
            ServiceResponse deleteResponse = harvesterApiClient.delete(secretApiPath)

            if (deleteResponse.success) {
                log.info "Successfully deleted Secret: ${secretName}"
            } else if (deleteResponse.data?.code == 404) {
                log.info "Secret ${secretName} does not exist or already deleted"
            } else {
                log.warn "Failed to delete Secret ${secretName}: ${deleteResponse.msg}"
            }

        } catch (Exception e) {
            log.warn "Exception while deleting Secret ${secretName}: ${e.message}", e
        }
    }

    /**
     * Delete a ConfigMap resource
     * @param harvesterApiClient the API client to use for calls
     * @param namespace the namespace where the ConfigMap is located
     * @param configMapName the name of the ConfigMap
     */
    static void deleteConfigMap(def harvesterApiClient, String namespace, String configMapName) {
        try {
            String configMapApiPath = "/api/v1/namespaces/${namespace}/configmaps/${configMapName}"
            log.info "Checking if ConfigMap exists: ${configMapName} at path: ${configMapApiPath}"

            // First check if ConfigMap exists
            ServiceResponse getResponse = harvesterApiClient.get(configMapApiPath)

            if (!getResponse.success && getResponse.data?.code == 404) {
                log.info "ConfigMap ${configMapName} does not exist, skipping deletion"
                return
            } else if (!getResponse.success) {
                log.warn "Failed to check ConfigMap ${configMapName}: ${getResponse.msg}"
                return
            }

            // ConfigMap exists, proceed with deletion
            log.info "ConfigMap ${configMapName} exists, deleting it"
            ServiceResponse deleteResponse = harvesterApiClient.delete(configMapApiPath)

            if (deleteResponse.success) {
                log.info "Successfully deleted ConfigMap: ${configMapName}"
            } else if (deleteResponse.data?.code == 404) {
                log.info "ConfigMap ${configMapName} does not exist or already deleted"
            } else {
                log.warn "Failed to delete ConfigMap ${configMapName}: ${deleteResponse.msg}"
            }

        } catch (Exception e) {
            log.warn "Exception while deleting ConfigMap ${configMapName}: ${e.message}", e
        }
    }
}