package com.hpe.morpheus.harvester.plugin.provision

import com.hpe.morpheus.harvester.plugin.apihelper.ConfigMapApiHelper
import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j

@Slf4j
class ProvisionHelper {

    private static final Map<String, String> VIRTIO_NETWORK_NAMES_LINUX = [
            'eth0': 'enp1s0',
            'eth1': 'enp2s0',
            'eth2': 'enp3s0',
            'eth3': 'enp4s0',
            'eth4': 'enp5s0',
            'eth5': 'enp6s0',
            'eth6': 'enp7s0',
            'eth7': 'enp8s0',
            'eth8': 'enp9s0',
            'eth9': 'enp10s0'
    ]

    ComputeServer computeServer
    WorkloadRequest workloadRequest
    ComputeServerGroup computeServerGroup
    Map opts
    /**
     * Constructs cloud-init configuration YAML for Linux VMs on Harvester
     * @param config Map containing configuration parameters:
     *        - hostname: String - The hostname to set for the VM
     *        - fqdn: String - Fully qualified domain name (optional)
     *        - manageDnsDomain: Boolean - Whether to manage DNS domain (default: false)
     *        - interfaces: List of interface configurations
     *          Each interface can have:
     *            - name: String - Interface name (e.g., 'eth0', 'eth1')
     *            - type: String - Interface type (e.g., 'ethernet')
     *            - dhcp: Boolean - Use DHCP (default: true)
     *            - ipAddress: String - Static IP address (if dhcp is false)
     *            - netmask: String - Network mask (if dhcp is false)
     *            - gateway: String - Gateway address (if dhcp is false)
     *            - dnsServers: List<String> - DNS server addresses (optional)
     *            - dnsSearch: List<String> - DNS search domains (optional)
     *        - users: List of user configurations (optional)
     *        - packages: List of packages to install (optional)
     *        - runcmd: List of commands to run (optional)
     *        - sshAuthorizedKeys: List of SSH public keys (optional)
     * @return String containing the cloud-init YAML configuration
     */
    ProvisionHelper(ComputeServer computeServer, WorkloadRequest workloadRequest,
                    ComputeServerGroup computeServerGroup, Map opts) {
        this.computeServer = computeServer
        this.workloadRequest = workloadRequest
        this.computeServerGroup = computeServerGroup
        this.opts = opts
    }

    // method to construct the config in the right format for the cloud-init
    Map buildCloudInitOptions() {
        log.debug "Building cloud-init options from workloadRequest, user data ${workloadRequest?.cloudConfigUser}" +
              "network data ${workloadRequest?.cloudConfigNetwork}, meta data ${workloadRequest?.cloudConfigMeta}" +
              "cloud config opts ${workloadRequest.cloudConfigOpts}"

        Map cloudInitData = [:]

        // Get cloud-init data from workloadRequest
        if (workloadRequest?.cloudConfigUser) {
            cloudInitData.userData = workloadRequest.cloudConfigUser
            log.info "Retrieved userData from workloadRequest.cloudConfigUser"
        }

        if (workloadRequest?.cloudConfigNetwork) {
            cloudInitData.networkData = workloadRequest.cloudConfigNetwork
            log.info "Retrieved networkData from workloadRequest.cloudConfigNetwork"
        }

        if (workloadRequest?.cloudConfigMeta) {
            cloudInitData.metaData = workloadRequest.cloudConfigMeta
            log.info "Retrieved metaData from workloadRequest.cloudConfigMeta"
        }

        log.info "Returning cloud-init data: userData=${cloudInitData.userData ? 'present' : 'null'}, " +
                "networkData=${cloudInitData.networkData ? 'present' : 'null'}, " +
                "metaData=${cloudInitData.metaData ? 'present' : 'null'}"

        String secretName = "secret-${computeServer.name}"
        Map response = [
            success: true,
            configName: secretName
        ]
        ServiceResponse createSecretResponse = createCloudInitSecret(secretName, opts.namespace as String, cloudInitData)
        if (!createSecretResponse.success) {
            response.success = false
            log.error "Failed to create cloud-init secret: ${createSecretResponse.msg}"
        } else {
            log.info "Successfully created cloud-init secret: ${secretName}"
        }

        return response

        // COMMENTED OUT - Old implementation that built cloud-init config manually
        /*
        // this would work with both linux and windows but for now we are only doing linux
        // log all opts here
        log.info "Building cloud-init options with opts: ${opts}"

        Map config = [:]
        // hostname gets auto added when we add the cloudinitdisk
        config.hostname = computeServer.hostname ?: computeServer.name
        config.fqdn = opts.fqdn ?: null

        // construct network interfaces here
        def networkConfig = opts?.networkConfig ?: [:]
        if (networkConfig) {
            // first get the primary interface
            def primaryInterface = networkConfig?.primaryInterface ?: [:]
            List interfaces = []
            if (primaryInterface) {
                Map primaryIfaceConfig = [:]
                primaryIfaceConfig.name = primaryInterface.name
                primaryIfaceConfig.dhcp = primaryInterface.doDhcp != null ? primaryInterface.doDhcp : true
                primaryIfaceConfig.dhcp6 = primaryInterface.doDhcp6 != null ? primaryInterface.doDhcp6 : false
                // add this only if dhcp is set to false
                if (!primaryIfaceConfig.dhcp) {
                    primaryIfaceConfig.ipAddress = primaryInterface.ipAddress
                    primaryIfaceConfig.netmask = primaryInterface.netmask
                    primaryIfaceConfig.gateway = primaryInterface.gateway
                    primaryIfaceConfig.dnsServers = primaryInterface.dnsServers ? primaryInterface.dnsServers.split(',').collect { it.trim() } : []
                    primaryIfaceConfig.dnsSearch = primaryInterface.searchDomains ?: []
                }
                // add IPv6 configuration if static IPv6 is enabled
                if (primaryInterface.doStatic6) {
                    primaryIfaceConfig.ipv6Address = primaryInterface.ipv6Address
                    primaryIfaceConfig.cidr6Suffix = primaryInterface.cidr6Suffix ?: 64
                    primaryIfaceConfig.gateway6 = primaryInterface.gateway6
                    primaryIfaceConfig.dnsServers6 = primaryInterface.dnsServers6 ? primaryInterface.dnsServers6.split(',').collect { it.trim() } : []
                }
                interfaces << primaryIfaceConfig
            }
            // do the same for extra interfaces which is a list
            def extraInterfaces = networkConfig?.extraInterfaces ?: []
            extraInterfaces.eachWithIndex { iface, idx ->
                Map ifaceConfig = [:]
                ifaceConfig.name = iface.name
                ifaceConfig.dhcp = iface.doDhcp != null ? iface.doDhcp : true
                ifaceConfig.dhcp6 = iface.doDhcp6 != null ? iface.doDhcp6 : false
                // add this only if dhcp is set to false
                if (!ifaceConfig.dhcp) {
                    ifaceConfig.ipAddress = iface.ipAddress
                    ifaceConfig.netmask = iface.netmask
                    ifaceConfig.gateway = iface.gateway
                    ifaceConfig.dnsServers = iface.dnsServers ? iface.dnsServers.split(',').collect { it.trim() } : []
                    ifaceConfig.dnsSearch = iface.searchDomains ?: []
                }
                // add IPv6 configuration if static IPv6 is enabled
                if (iface.doStatic6) {
                    ifaceConfig.ipv6Address = iface.ipv6Address
                    ifaceConfig.cidr6Suffix = iface.cidr6Suffix ?: 64
                    ifaceConfig.gateway6 = iface.gateway6
                    ifaceConfig.dnsServers6 = iface.dnsServers6 ? iface.dnsServers6.split(',').collect { it.trim() } : []
                }
                interfaces << ifaceConfig
            }
            // add this to the config
            config.interfaces = interfaces
        }

        log.info "using cloud init config ${config}"

        // now build the full cloud-init config based on platform type here, do only linux now
        Map cloudInitData = buildCloudInitConfigForLinux(config)
        return cloudInitData
        */
    }

    Map buildSysprepOptions() {
        log.debug "Building sysprep options from workloadRequest unattend.xml: ${workloadRequest?.cloudConfigUser}"

        Map response = [success: false]
        Map ifaceMACAddressMap = this.opts?.ifaceMACAddressMap as Map
        // modify the unattend.xml with MAC addresses instead of interface names
        if (ifaceMACAddressMap && ifaceMACAddressMap.size() != 0) {
            workloadRequest.cloudConfigUser = updateSysprepUnattend(workloadRequest.cloudConfigUser, ifaceMACAddressMap)
        }
        HarvesterApiClient harvesterApiClient = new HarvesterApiClient(computeServerGroup)
        def configMapApiHelper = new ConfigMapApiHelper(harvesterApiClient)
        String configMapName = "${computeServer.name}-sysprep"
        ServiceResponse configMapResult = configMapApiHelper.createConfigMap(
                opts.namespace as String,
                configMapName,
                workloadRequest.cloudConfigUser,
                opts.labels as Map<String, String> ?: [:],
                opts.annotations as Map<String, String> ?: [:]
        )
        if (configMapResult?.success) {
            log.info "Successfully created sysprep config map for VM ${computeServer.name} in namespace ${opts.namespace}"
            response.success = true
            response.configMapName = configMapName
        } else {
            log.error "Failed to create sysprep config map for VM ${computeServer.name}: ${configMapResult?.msg} " +
                    "${configMapResult?.errors}"
        }
        return response
    }

    static Map buildCloudInitConfigForLinux(Map config) {
        log.info "Building cloud-init configuration for Harvester Linux VM"

        StringBuilder userData = new StringBuilder()

        // Set hostname
        if (config.hostname) {
            userData.append("hostname: ${config.hostname}\n")
            log.info "Setting hostname: ${config.hostname}"
        }

        // Set FQDN if provided
        if (config.fqdn) {
            userData.append("fqdn: ${config.fqdn}\n")
            log.info "Setting FQDN: ${config.fqdn}"
        }

        // Add this if required (Manage DNS domain)
        // yaml.append("manage_etc_hosts: ${config.manageDnsDomain ? 'true' : 'false'}\n")

        // Network configuration
        StringBuilder networkData = new StringBuilder()
        if (config.interfaces) {
            networkData.append("version: 2\n")
            // add renderer here
            networkData.append("renderer: networkd\n")
            networkData.append("ethernets:\n")

            config.interfaces.each { iface ->
                String networkInterfaceName = iface.name ?: 'eth0'
                String interfaceName = VIRTIO_NETWORK_NAMES_LINUX[networkInterfaceName] ?: networkInterfaceName
                networkData.append("  ${interfaceName}:\n")

                if (iface.dhcp == false) {
                    // Static IP configuration
                    log.info "Configuring static IP for interface: ${interfaceName}"

                    networkData.append("    dhcp4: false\n")

                    if (iface.ipAddress && iface.netmask) {
                        // Calculate CIDR notation from IP and netmask
                        int cidr = 24 // default
                        if (iface.netmask) {
                            try {
                                String[] octets = iface.netmask.split("\\.")
                                cidr = 0
                                for (String octet : octets) {
                                    int value = Integer.parseInt(octet)
                                    cidr += Integer.bitCount(value)
                                }
                            } catch (Exception e) {
                                log.warn "Failed to convert netmask ${iface.netmask} to CIDR, defaulting to /24: ${e.message}"
                            }
                        }

                        String cidrNotation = "${iface.ipAddress}/${cidr}"
                        networkData.append("    addresses:\n")
                        networkData.append("      - ${cidrNotation}\n")
                        log.info "Static IP configured: ${cidrNotation}"
                    }

                    if (iface.gateway) {
                        networkData.append("    routes:\n")
                        networkData.append("      - to: default\n")
                        networkData.append("        via: ${iface.gateway}\n")
                        log.info "Gateway configured: ${iface.gateway}"
                    }

                    if (iface.dnsServers || iface.dnsSearch) {
                        networkData.append("    nameservers:\n")

                        if (iface.dnsServers) {
                            networkData.append("      addresses:\n")
                            iface.dnsServers.each { dns ->
                                networkData.append("        - ${dns}\n")
                            }
                            log.info "DNS servers configured: ${iface.dnsServers}"
                        }

                        if (iface.dnsSearch) {
                            networkData.append("      search:\n")
                            iface.dnsSearch.each { domain ->
                                networkData.append("        - ${domain}\n")
                            }
                            log.info "DNS search domains configured: ${iface.dnsSearch}"
                        }
                    }
                } else {
                    // DHCP configuration
                    log.info "Configuring DHCP for interface: ${interfaceName}"
                    networkData.append("    dhcp4: true\n")
                }

                // IPv6 configuration
                if (iface.dhcp6 != null && iface.dhcp6) {
                    // DHCPv6 configuration
                    log.info "Configuring DHCPv6 for interface: ${interfaceName}"
                    networkData.append("    dhcp6: true\n")
                } else if (iface.ipv6Address) {
                    // Static IPv6 configuration
                    log.info "Configuring static IPv6 for interface: ${interfaceName}"
                    networkData.append("    dhcp6: false\n")

                    String cidr6Suffix = iface.cidr6Suffix ?: "64"
                    String ipv6CidrNotation = "${iface.ipv6Address}/${cidr6Suffix}"

                    // Add IPv6 address to addresses list (may already have IPv4)
                    if (iface.dhcp == false && iface.ipAddress) {
                        // IPv4 addresses already added, append IPv6
                        networkData.append("      - ${ipv6CidrNotation}\n")
                    } else {
                        // Only IPv6 address
                        networkData.append("    addresses:\n")
                        networkData.append("      - ${ipv6CidrNotation}\n")
                    }
                    log.info "Static IPv6 configured: ${ipv6CidrNotation}"

                    if (iface.gateway6) {
                        // Add IPv6 gateway to routes
                        if (iface.gateway) {
                            // IPv4 routes already added, append IPv6
                            networkData.append("      - to: ::/0\n")
                            networkData.append("        via: ${iface.gateway6}\n")
                        } else {
                            // Only IPv6 route
                            networkData.append("    routes:\n")
                            networkData.append("      - to: ::/0\n")
                            networkData.append("        via: ${iface.gateway6}\n")
                        }
                        log.info "IPv6 Gateway configured: ${iface.gateway6}"
                    }

                    if (iface.dnsServers6) {
                        if (iface.dnsServers) {
                            // IPv4 DNS already added, append IPv6
                            iface.dnsServers6.each { dns ->
                                networkData.append("        - ${dns}\n")
                            }
                        } else {
                            // Only IPv6 DNS
                            networkData.append("    nameservers:\n")
                            networkData.append("      addresses:\n")
                            iface.dnsServers6.each { dns ->
                                networkData.append("        - ${dns}\n")
                            }
                        }
                        log.info "IPv6 DNS servers configured: ${iface.dnsServers6}"
                    }
                }
            }
        }

        // User configuration
        if (config.users) {
            userData.append("users:\n")
            config.users.each { user ->
                userData.append("  - name: ${user.name ?: 'default'}\n")
                if (user.groups) {
                    userData.append("    groups: ${user.groups}\n")
                }
                if (user.sudo) {
                    userData.append("    sudo: ${user.sudo}\n")
                }
                if (user.shell) {
                    userData.append("    shell: ${user.shell}\n")
                }
                if (user.sshAuthorizedKeys) {
                    userData.append("    ssh_authorized_keys:\n")
                    user.sshAuthorizedKeys.each { key ->
                        userData.append("      - ${key}\n")
                    }
                }
            }
            log.info "Adding ${config.users.size()} user configurations"
        }

        // SSH authorized keys (global)
        if (config.sshAuthorizedKeys) {
            userData.append("ssh_authorized_keys:\n")
            config.sshAuthorizedKeys.each { key ->
                userData.append("  - ${key}\n")
            }
            log.info "Adding SSH authorized keys"
        }

        // Package installation
        if (config.packages) {
            userData.append("packages:\n")
            config.packages.each { pkg ->
                userData.append("  - ${pkg}\n")
            }
            log.info "Adding ${config.packages.size()} packages to install"
        }

        // Run commands
        if (config.runcmd) {
            userData.append("runcmd:\n")
            config.runcmd.each { cmd ->
                userData.append("  - ${cmd}\n")
            }
            log.info "Adding ${config.runcmd.size()} commands to run"
        }

        String userDataContent = userData.toString()
        String networkDataContent = networkData.toString()
        log.info "Generated user data YAML:\n${userDataContent}"
        log.info "Generated network data YAML:\n${networkDataContent}"
        return [userData: userDataContent, networkData: networkDataContent]
    }

    // method to generate request body for removing cloudinitdisk (devices and volumes) in
    // spec section of the VM using 'application/json-patch+json' content type
    static List buildDiskRemovalPatch(String diskIndex, String volumeIndex) {
        Map diskRemove = [
                op    : 'remove',
                "path": "/spec/template/spec/domain/devices/disks/${diskIndex}"
        ]
        Map volumeRemove = [
                op    : 'remove',
                "path": "/spec/template/spec/volumes/${volumeIndex}"
        ]
        return [diskRemove, volumeRemove]
    }

    static Map getDiskAndVolumeIndices(HarvesterApiClient harvesterApiClient,
                                                String namespace, String vmName, String vmTypeCode, String diskName = null) {
        log.info "Getting disk and volume indices for VM ${vmName} in namespace ${namespace}"
        String apiPath = "/apis/kubevirt.io/v1/namespaces/${namespace}/virtualmachines/${vmName}"
        ServiceResponse response = harvesterApiClient.get(apiPath)
        if (!diskName) {
            diskName = vmTypeCode == 'harvester-windows' ? 'sysprep' : 'cloudinitdisk'
        }

        if (!response.success) {
            log.error "Failed to get VM details for ${vmName}: ${response.msg}"
            return null
        }

        def vm = response.data
        def disks = vm?.spec?.template?.spec?.domain?.devices?.disks
        def volumes = vm?.spec?.template?.spec?.volumes

        // find disk index here
        int diskIndex = -1
        if (disks) {
            diskIndex = disks.findIndexOf { it.name == diskName }
        }

        // find volume index here
        int volumeIndex = -1
        if (volumes) {
            volumeIndex = volumes.findIndexOf { it.name == diskName }
        }

        if (diskIndex == -1 || volumeIndex == -1) {
            log.warn "Could not find ${diskName} disk or volume  for VM ${vmName}"
            return null
        }

        log.info "Found  ${diskName} disk at index ${diskIndex} and volume index ${volumeIndex}"
        return [diskIndex: diskIndex, volumeIndex: volumeIndex]
    }

    static Integer getDataVolumeTemplateIndex(HarvesterApiClient harvesterApiClient, String namespace, String vmName, String dataVolumeName) {
        log.info "Getting data volume template index for VM ${vmName} in namespace ${namespace} for volume ${dataVolumeName}"
        String apiPath = "/apis/kubevirt.io/v1/namespaces/${namespace}/virtualmachines/${vmName}"
        ServiceResponse response = harvesterApiClient.get(apiPath)

        if (!response.success) {
            log.error "Failed to get VM details for ${vmName}: ${response.msg}"
            return null
        }

        def vm = response.data
        def dataVolumeTemplates = vm?.spec?.dataVolumeTemplates

        int index = -1
        if (dataVolumeTemplates) {
            index = dataVolumeTemplates.findIndexOf { it.metadata?.name == dataVolumeName }
        }

        if (index == -1) {
            log.warn "Could not find data volume template for ${dataVolumeName} in VM ${vmName}"
            return -1
        }

        log.info "Found data volume template for ${dataVolumeName} at index ${index}"
        return index
    }

    /**
     * Creates an Harvester Secret object containing cloud-init configuration data
     * @param secretName the name of the secret to create
     * @param namespace the namespace where the secret will be created
     * @param cloudInitData Map containing userData, networkData, and metaData from buildCloudInitOptions
     * @return ServiceResponse containing the result of the secret creation
     */
    ServiceResponse createCloudInitSecret(String secretName, String namespace, Map cloudInitData) {
        log.info "Creating cloud-init secret '${secretName}' in namespace '${namespace}'"

        try {
            // Create Harvester API client
            HarvesterApiClient harvesterApiClient = new HarvesterApiClient(computeServerGroup)

            // Build secret request body
            Map secretBody = [
                apiVersion: 'v1',
                kind: 'Secret',
                metadata: [
                    name: secretName,
                    namespace: namespace
                ],
                type: 'Opaque',
                data: [:]
            ]

            // Add userData if present (base64 encoded)
            if (cloudInitData?.userData) {
                String userDataBase64 = cloudInitData.userData.bytes.encodeBase64().toString()
                secretBody.data.userdata = userDataBase64
                log.info "Added base64 encoded userData to secret"
            }

            // Add networkData if present (base64 encoded)
            if (cloudInitData?.networkData) {
                String networkDataBase64 = cloudInitData.networkData.bytes.encodeBase64().toString()
                secretBody.data.networkdata = networkDataBase64
                log.info "Added base64 encoded networkData to secret"
            }

            // Add metaData if present (base64 encoded)
            if (cloudInitData?.metaData) {
                String metaDataBase64 = cloudInitData.metaData.bytes.encodeBase64().toString()
                secretBody.data.metadata = metaDataBase64
                log.info "Added base64 encoded metaData to secret"
            }

            // Build API path for creating secret
            String apiPath = "/api/v1/namespaces/${namespace}/secrets"

            log.info "Creating secret at path: ${apiPath}"
            ServiceResponse response = harvesterApiClient.post(apiPath, secretBody)

            if (response.success) {
                log.info "Successfully created cloud-init secret '${secretName}'"
                return ServiceResponse.success(response.data)
            } else {
                log.error "Failed to create cloud-init secret '${secretName}': ${response.msg}"
                return ServiceResponse.error("Failed to create secret: ${response.msg}")
            }

        } catch (Exception e) {
            String errorMsg = "Exception while creating cloud-init secret '${secretName}': ${e.message}"
            log.error errorMsg, e
            return ServiceResponse.error(errorMsg)
        }
    }

    /**
     * Deletes an Harvester Secret object
     * @param secretName the name of the secret to delete
     * @param namespace the namespace where the secret is located
     * @return ServiceResponse containing the result of the secret deletion
     */
    ServiceResponse deleteCloudInitSecret(String secretName, String namespace) {
        log.info "Deleting cloud-init secret '${secretName}' from namespace '${namespace}'"

        try {
            // Create Harvester API client
            HarvesterApiClient harvesterApiClient = new HarvesterApiClient(computeServerGroup)

            // Build API path for deleting secret
            String apiPath = "/api/v1/namespaces/${namespace}/secrets/${secretName}"

            log.info "Deleting secret at path: ${apiPath}"
            ServiceResponse response = harvesterApiClient.delete(apiPath)

            if (response.success) {
                log.info "Successfully deleted cloud-init secret '${secretName}'"
                return ServiceResponse.success(response.data)
            } else {
                log.error "Failed to delete cloud-init secret '${secretName}': ${response.msg}"
                return ServiceResponse.error("Failed to delete secret: ${response.msg}")
            }

        } catch (Exception e) {
            String errorMsg = "Exception while deleting cloud-init secret '${secretName}': ${e.message}"
            log.error errorMsg, e
            return ServiceResponse.error(errorMsg)
        }
    }

    static Map getNetworkInterfaceIndices(HarvesterApiClient harvesterApiClient, String namespace, String vmName, String interfaceName) {
        log.info "Getting network interface indices for VM ${vmName} in namespace ${namespace} for interface ${interfaceName}"
        String apiPath = "/apis/kubevirt.io/v1/namespaces/${namespace}/virtualmachines/${vmName}"
        ServiceResponse response = harvesterApiClient.get(apiPath)

        if (!response.success) {
            log.error "Failed to get VM details for ${vmName}: ${response.msg}"
            return null
        }

        def vm = response.data
        def interfaces = vm?.spec?.template?.spec?.domain?.devices?.interfaces
        def networks = vm?.spec?.template?.spec?.networks

        int interfaceIndex = -1
        if (interfaces) {
            interfaceIndex = interfaces.findIndexOf { it.name == interfaceName }
        }

        int networkIndex = -1
        if (networks) {
            networkIndex = networks.findIndexOf { it.name == interfaceName }
        }

        if (interfaceIndex == -1 && networkIndex == -1) {
            log.warn "Could not find interface or network named ${interfaceName} for VM ${vmName}"
            return null
        }

        log.info "Found interface ${interfaceName} at index ${interfaceIndex} and network index ${networkIndex}"
        return [interfaceIndex: interfaceIndex, networkIndex: networkIndex]
    }

    static List buildNetworkRemovalPatch(String interfaceIndex, String networkIndex) {
        List patches = []
        if (interfaceIndex != null && Integer.parseInt(interfaceIndex) > -1) {
            patches.add([
                    op    : 'remove',
                    "path": "/spec/template/spec/domain/devices/interfaces/${interfaceIndex}"
            ])
        }
        if (networkIndex != null && Integer.parseInt(networkIndex) > -1) {
            patches.add([
                    op    : 'remove',
                    "path": "/spec/template/spec/networks/${networkIndex}"
            ])
        }
        return patches
    }

    /**
     * Removes a label from a VM using JSON Patch
     * @param harvesterApiClient the API client
     * @param namespace the namespace
     * @param vmName the VM name
     * @param labelKey the key of the label to remove
     * @return ServiceResponse
     */
    static ServiceResponse removeVMLabel(HarvesterApiClient harvesterApiClient, String namespace, String vmName, String labelKey) {
        log.info "Removing label '${labelKey}' from VM ${vmName} in namespace ${namespace}"

        try {
            String apiPath = "/apis/kubevirt.io/v1/namespaces/${namespace}/virtualmachines/${vmName}"
            List patchBody = createRemoveLabelPatchBody(labelKey)
            String contentType = 'application/json-patch+json'

            // Dry run for label removal first
            try {
                log.debug "Attempting dry-run for label removal"
                ServiceResponse dryRunResponse = harvesterApiClient.patch(apiPath, patchBody, contentType, [dryRun: 'All'])
                if (!dryRunResponse.success) {
                    log.error "Dry run failed for removing label: ${dryRunResponse.msg}"
                } else {
                    log.debug "Dry run successful"
                }
            } catch (Exception ex) {
                log.error "Error during dry-run: ${ex.message}"
            }

            log.info "Sending PATCH request to ${apiPath} with body: ${patchBody}"

            // Using patch method directly on client as the wrapper expects Map params
            ServiceResponse response = harvesterApiClient.patch(apiPath, patchBody, contentType)

            if (!response.success) {
                log.error "Failed to remove label: ${response.msg}"
            } else {
                log.info "Successfully removed label '${labelKey}'"
            }

            return response

        } catch (Exception e) {
            log.error "Error removing label from VM: ${e.message}", e
        }
    }

    /**
     * Creates the JSON Patch body for removing a label
     * @param labelKey the key of the label to remove
     * @return List of patch operations
     */
    static List createRemoveLabelPatchBody(String labelKey) {
        // Escape characters in label key for JSON Pointer
        // ~ -> ~0
        // / -> ~1
        String escapedKey = labelKey.replace("~", "~0").replace("/", "~1")

        return [
            [
                op: "remove",
                path: "/metadata/labels/${escapedKey}"
            ]
        ]
    }

    static String updateSysprepUnattend(String cloudConfigUser,Map ifaceMACAddressMap) {
        // return as-is if input is null or blank
        if (cloudConfigUser == null || cloudConfigUser.trim().isEmpty()) {
            log.warn("unattend.xml is null or blank; returning original input")
            return cloudConfigUser
        }
        try {
            def parser = new groovy.xml.XmlParser(false, false)
            def xml = parser.parseText(cloudConfigUser)

            // Directly select the single <settings pass="specialize"> node
            def specializeSettings = xml?.settings?.find { it.@pass == 'specialize' }

            if (specializeSettings) {
                def components = specializeSettings.component?.findAll {
                    it.@name in ['Microsoft-Windows-TCPIP', 'Microsoft-Windows-DNS-Client']
                }
                if (components) {
                    components.each { componentNode ->
                        componentNode?.Interfaces?.Interface?.each { iface ->
                            iface?.Identifier?.each { idNode ->
                                def currentInterfaceName = idNode?.text()?.trim()
                                String sanitizedInterfaceName = currentInterfaceName.replaceAll("\\s+", "").toLowerCase()
                                if (sanitizedInterfaceName) {
                                    def newValue = ifaceMACAddressMap.get(sanitizedInterfaceName, currentInterfaceName)
                                    idNode?.setValue(newValue)
                                }
                            }
                        }
                    }
                }
            }

            // Serialize XML back to string
            def modifiedXmlString = groovy.xml.XmlUtil.serialize(xml)
            return modifiedXmlString
        } catch (Exception e) {
            log.error("Failed to update unattend.xml, returning original. Error: ${e.message}", e)
            return cloudConfigUser
        }
    }

    /**
     * Queries DataVolumes or PVCs from Harvester API for volumes with sourceId
     * This method iterates through server volumes or provided volume list and fetches volume details from the API based on volume type
     * @param server The ComputeServer containing volumes to query
     * @param morpheusContext The MorpheusContext for database operations
     * @param harvesterApiClient The HarvesterApiClient for making API calls
     * @param namespace The namespace where volumes are located
     * @param volumesList Optional list of StorageVolume objects to query. If provided, this list is used instead of server.volumes
     */
    static void queryDataVolumesForServerVolumes(ComputeServer server, MorpheusContext morpheusContext,
                                                 HarvesterApiClient harvesterApiClient, String namespace, List<StorageVolume> volumesList = null) {
        log.info "Querying volumes for server: ${server.name} in namespace: ${namespace}"

        // Determine which volumes to iterate over
        def volumesToProcess = volumesList ?: server.volumes

        if (!volumesToProcess || volumesToProcess.isEmpty()) {
            log.info "No volumes to query for server ${server.name}"
            return
        }

        String volumeSource = volumesList ? "provided list" : "server.volumes"
        log.info "Found ${volumesToProcess.size()} volumes from ${volumeSource} for server ${server.name}, checking for volumes with sourceId"

        // Iterate through volumes
        volumesToProcess.each { volume ->
            if (volume.sourceId) {
                log.info "Volume ${volume.name} has sourceId: ${volume.sourceId}, fetching volume details from Harvester API"

                try {
                    // Get the source volume details from database
                    StorageVolume sourceVolume = morpheusContext.services.storage.volume.get(volume.sourceId as Long)

                    if (sourceVolume) {
                        log.info "Found source volume: ${sourceVolume.name} (id: ${sourceVolume.id})"

                        // Determine the volume type and construct appropriate API path
                        String volumeName = sourceVolume.name
                        String apiPath
                        String volumeTypeLabel
                        StorageVolumeType volumeType = morpheusContext.services.storage.volume.storageVolumeType.get(
                                sourceVolume.type.id as Long)
                        if (volumeType.code == HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME) {
                            // DataVolume API path
                            apiPath = "/apis/cdi.kubevirt.io/v1beta1/namespaces/${namespace}/datavolumes/${volumeName}"
                            volumeTypeLabel = "DataVolume"
                        } else if (volumeType.code == HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC) {
                            // PVC API path
                            apiPath = "/api/v1/namespaces/${namespace}/persistentvolumeclaims/${volumeName}"
                            volumeTypeLabel = "PVC"
                        } else {
                            log.warn "Unknown volume type '${sourceVolume.volumeType}' for source volume ${sourceVolume.name}, skipping"
                            return
                        }

                        log.info "Querying ${volumeTypeLabel} from API path: ${apiPath}"
                        ServiceResponse volumeResponse = harvesterApiClient.get(apiPath)

                        if (volumeResponse.success) {
                            log.info "Successfully retrieved ${volumeTypeLabel}: ${volumeName}"

                            if (volumeType.code == HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME) {
                                // Extract ownerReferences and source from DataVolume response
                                def ownerReferences = volumeResponse.data?.metadata?.ownerReferences
                                def dataSource = volumeResponse.data?.spec?.source

                                // Create the dataVolumeConfig structure
                                def dataVolumeConfig = JsonOutput.toJson([
                                        ownerReferences: ownerReferences,
                                        dataSource: dataSource
                                ])

                                // Store in sourceVolume config
                                sourceVolume.setConfigProperty("config", dataVolumeConfig)

                                // Save the updated sourceVolume
                                morpheusContext.services.storage.volume.save(sourceVolume)

                                log.info "Saved dataVolumeConfig to sourceVolume ${sourceVolume.name}"
                            } else if (volumeType.code == HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC) {
                                // Extract ownerReferences and dataSource from PVC response
                                def ownerReferences = volumeResponse.data?.metadata?.ownerReferences
                                def dataSource = volumeResponse.data?.spec?.dataSource

                                // Create the pvcConfig structure
                                def pvcConfig = JsonOutput.toJson([
                                        ownerReferences: ownerReferences,
                                        dataSource: dataSource
                                ])

                                // Store in sourceVolume config
                                sourceVolume.setConfigProperty("config", pvcConfig)

                                // Save the updated sourceVolume
                                morpheusContext.services.storage.volume.save(sourceVolume)

                                log.info "Saved pvcConfig to sourceVolume ${sourceVolume.name}"
                            }

                            //log.info "${volumeTypeLabel} Response:\n${JsonOutput.prettyPrint(JsonOutput.toJson(volumeResponse.data))}"
                        } else {
                            log.error "Failed to retrieve ${volumeTypeLabel} ${volumeName}: ${volumeResponse.msg}"
                        }
                    } else {
                        log.warn "Source volume with id ${volume.sourceId} not found in database"
                    }
                } catch (Exception e) {
                    log.error "Error querying volume for ${volume.name}: ${e.message}", e
                }
            } else {
                log.info "Volume ${volume.name} does not have sourceId, skipping"
            }
        }

        log.info "Completed querying volumes for server ${server.name}"
    }

    static ServiceResponse addSysprepVolumeToVM(HarvesterApiClient harvesterApiClient, String namespace, String vmName, String configMapName) {
        String vmApiPath = VirtualMachineReconfigure.getVMApiResourcePath(namespace, vmName)
        def patchOperations = []
        Map disksConfig = [
                cdrom:
                        [bus: "sata"],
                name : "sysprep"
        ]
        Map volumesConfig = [
                name   : "sysprep",
                sysprep: [
                        configMap: [
                                name: configMapName
                        ]
                ]
        ]

        // Add/replace runStrategy to Always. Not doing it here, will explicitly trigger start after reconfigure
//        patchOperations.add([
//                op   : 'replace',
//                path : '/spec/runStrategy',
//                value: 'Always'
//        ])
        patchOperations.add([
                op   : 'add',
                path : '/spec/template/spec/domain/devices/disks/-',
                value: disksConfig
        ])

        patchOperations.add([
                op   : 'add',
                path : '/spec/template/spec/volumes/-',
                value: volumesConfig
        ])
        // Perform dry-run validation
        String patchBodyJson = JsonOutput.toJson(patchOperations)
        log.info("Performing dry-run for adding volumes with patch: ${patchBodyJson}")

        ServiceResponse dryRunResponse = harvesterApiClient.patch(
                vmApiPath, patchBodyJson, "application/json-patch+json", ['dryRun': 'All'])

        if (!dryRunResponse.success) {
            log.error("Dry-run validation failed for adding volumes: ${dryRunResponse.msg}")
            return ServiceResponse.error("Add volumes validation failed: ${dryRunResponse.msg}")
        }
        log.info("Dry-run successful - proceeding with adding volumes")

        // Apply the actual patch
        log.info("Applying add volumes patch: ${patchBodyJson}")

        ServiceResponse patchResponse = harvesterApiClient.patch(
                vmApiPath, patchBodyJson, 'application/json-patch+json')

        if (!patchResponse.success) {
            log.error("Failed to add volumes: ${patchResponse.msg}")
            return ServiceResponse.error("Add volumes failed: ${patchResponse.msg}")
        }

        log.info("Successfully added sysprep volumes to VM ${vmName}")
        return ServiceResponse.success()
    }

    /**
     * Generates a unique serial number based on a unique identifier (UID).
     * The serial number is deterministic and based on the provided UID.
     *
     * KubeVirt Requirements:
     * - Maximum length: 20 characters
     * - Allowed characters: alphanumeric and hyphens
     * - Must be unique within a VM
     *
     * Format: {hashCode-12}
     * Example: 123456789012
     *
     * The serial number can be queried from within the guest OS using:
     * - Linux: lsblk -o NAME,SERIAL or ls -l /dev/disk/by-id/virtio-{serial}
     * - Windows: Get-Disk | Select Number, SerialNumber, Size
     *
     * @param uid the unique identifier (if null or empty, a random UUID will be generated)
     * @return a unique serial number string (max 20 characters, alphanumeric and hyphens only)
     */
    static String generateSerialNumber(String uid) {
        log.info("Generating serial number from UID: ${uid}")

        if (!uid || uid.trim().isEmpty()) {
            log.warn("UID is null or empty, generating a random UUID")
            uid = UUID.randomUUID().toString()
        }

        // Generate hash from UID (deterministic)
        int hash = Math.abs(uid.hashCode())

        // Use 12 digits for better uniqueness (allows ~1 trillion values)
        String serial = String.format('%012d', hash % 1000000000000L)

        // Ensure 20 char limit
        if (serial.length() > 20) {
            log.info("Generated serial exceeds 20 chars (${serial.length()}): ${serial}, truncating...")
            serial = serial.substring(0, 20)
        }

        log.info("Generated serial number: ${serial} (length: ${serial.length()})")
        return serial
    }
}
