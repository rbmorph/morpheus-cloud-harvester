// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.utils

import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.NetAddress
import groovy.util.logging.Slf4j

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.io.IOException

@Slf4j
class CommonUtils {

    /** Memory Harvester reserves for VM overhead (its default harvesterhci.io/reservedMemory). */
    static final long HARVESTER_RESERVED_MEMORY_MI = 100L

    /**
     * Guest-visible memory for a Harvester VM whose memory limit is limitMi, matching what the
     * Harvester UI sets: the limit minus the reserved overhead (no reservation for very small VMs).
     */
    /**
     * Morpheus guest console for a VM: RDP for Windows, SSH otherwise
     */
    static ComputeServer.GuestConsoleType guestConsoleTypeFor(def platformOrOsType) {
        String value = platformOrOsType?.toString()?.toLowerCase() ?: ''
        return value.contains('windows') ? ComputeServer.GuestConsoleType.rdp : ComputeServer.GuestConsoleType.ssh
    }

    /**
     * vCPUs for a Harvester VM: the core count. Only a plan with custom CPU and no custom cores (an older Harvester
     * plan) uses its "CPU" value as the vCPU count; with both, the CPU value is the socket count (see
     * VirtualMachineProvisioning.resolveCpuTopology).
     */
    static Long vcpusFor(def plan, def maxCpu, def maxCores) {
        Long cpu = toPositiveLong(maxCpu)
        Long cores = toPositiveLong(maxCores)
        if (plan?.customCpu == true && plan?.customCores != true && cpu) return cpu
        return cores ?: toPositiveLong(plan?.maxCores) ?: cpu ?: toPositiveLong(plan?.maxCpu) ?: 1L
    }

    /** The CPU value entered on a reconfigure / custom plan, from the request options Morpheus passes */
    static Long requestedCpu(Map opts) {
        def value = opts?.servicePlanOptions instanceof Map ? (opts.servicePlanOptions as Map).maxCpu : null
        return toPositiveLong(value ?: opts?.maxCpu ?: (opts?.config instanceof Map ? (opts.config as Map).maxCpu : null))
    }

    static Long toPositiveLong(def value) {
        if (value == null) return null
        try {
            Long n = value.toString().trim().toBigDecimal().toLong()
            return n > 0 ? n : null
        } catch (Exception ignored) {
            return null
        }
    }

    static long harvesterGuestMemoryMi(long limitMi) {
        return limitMi > 256L ? limitMi - HARVESTER_RESERVED_MEMORY_MI : limitMi
    }

    /**
     * Map Harvester OS types to Morpheus OS type codes
     * @param harvesterOsType The Harvester OS type string
     * @return Corresponding Morpheus OS type code
     */
    static getMorpheusOsTypeCode(String harvesterOsType) {
        switch(harvesterOsType) {
            case 'centos.stream10':
            case 'centos.stream10.desktop':
            case 'centos-stream9':
            case 'centos.stream9.desktop':
            case 'centos.stream9.dpdk':
                return 'cent'
            case 'fedora':
                return 'fedora'
            case 'rhel7':
                return 'redhat.7'
            case 'rhel8':
                return 'redhat.8'
            case 'rhel9':
                return 'redhat'
            case 'windows10':
                return 'windows.10'
            case 'windows11':
                return 'windows.11'
            case 'windows2k16':
            case 'windows2016':
                return 'windows.server.2016'
            case 'windows2k19':
            case 'windows2019':
                return 'windows.server.2019'
            case 'windows2k22':
            case 'windows2022':
                return 'windows.server.2022'
        }

        // if it comes here, means no match found in the switch cases
        if (harvesterOsType.toLowerCase().contains('windows')) {
            return 'windows'
        } else if (harvesterOsType.toLowerCase().contains('rhel')) {
            return 'redhat'
        } else if (harvesterOsType.toLowerCase().contains('centos')) {
            return 'cent'
        } else if (harvesterOsType.toLowerCase().contains('ubuntu')) {
            return 'ubuntu'
        } else if (harvesterOsType.toLowerCase().contains('debian')) {
            return 'debian'
        } else if (harvesterOsType.toLowerCase().contains('suse') || harvesterOsType.toLowerCase().contains('opensuse')) {
            return 'suse'
        } else if (harvesterOsType.toLowerCase().contains('fedora')) {
            return 'fedora'
        } else if (harvesterOsType.toLowerCase().contains('mac') || harvesterOsType.toLowerCase().contains('darwin')) {
            return 'mac'
        } else {
            return 'other.64'
        }
    }

    /**
     * Finds the most reachable IP address from a list of IPs
     * Priority: SSH reachable > Ping reachable > First available IP
     * @param ipAddresses List of IP addresses to test
     * @param sshPort SSH port to test (default 22)
     * @param timeoutMs Connection timeout in milliseconds (default 3000)
     * @return The most reachable IP address or null if list is empty
     */
    static String findReachableIp(List<String> ipAddresses, int sshPort = 22, int timeoutMs = 3000) {
        if (!ipAddresses || ipAddresses.isEmpty()) {
            return null
        }

        // Filter out null/empty IPs
        def validIps = ipAddresses.findAll { ip -> ip && !ip.trim().isEmpty() && ip != '0.0.0.0' }
        if (validIps.isEmpty()) {
            return ipAddresses.find { it } // Return first non-null IP as fallback
        }

        // First priority: Check SSH reachability
        def sshReachableIp = validIps.find { ip ->
            isPortReachable(ip, sshPort, timeoutMs)
        }
        if (sshReachableIp) {
            return sshReachableIp
        }

        // Second priority: Check ping reachability
        def pingReachableIp = validIps.find { ip ->
            isPingReachable(ip, timeoutMs)
        }
        if (pingReachableIp) {
            return pingReachableIp
        }

        // Fallback: Return first valid IP
        return validIps.first()
    }

    /**
     * Tests if a specific port on an IP address is reachable
     * @param ipAddress IP address to test
     * @param port Port number to test
     * @param timeoutMs Connection timeout in milliseconds
     * @return true if port is reachable, false otherwise
     */
    static boolean isPortReachable(String ipAddress, int port, int timeoutMs) {
        if (!ipAddress || ipAddress.trim().isEmpty()) {
            return false
        }

        Socket socket = null
        try {
            socket = new Socket()
            socket.connect(new InetSocketAddress(ipAddress.trim(), port), timeoutMs)
            return true
        } catch (Exception e) {
            // log.warn("Port ${port} check failed for ${ipAddress}: ${e.message}")
            log.warn("Port ${port} check failed for ${ipAddress}")
            return false
        } finally {
            if (socket) {
                try {
                    socket.close()
                } catch (IOException ignored) {
                    log.info ("Error closing socket: ${ignored.message}" )
                }
            }
        }
    }

    /**
     * Tests if an IP address is ping reachable
     * @param ipAddress IP address to ping
     * @param timeoutMs Ping timeout in milliseconds
     * @return true if IP is ping reachable, false otherwise
     */
    static boolean isPingReachable(String ipAddress, int timeoutMs) {
        if (!ipAddress || ipAddress.trim().isEmpty()) {
            return false
        }

        try {
            InetAddress address = InetAddress.getByName(ipAddress.trim())
            return address.isReachable(timeoutMs)
        } catch (Exception e) {
            // IP not reachable
            log.warn("Ping check failed for ${ipAddress}: ${e.message}")
            return false
        }
    }

    /**
     * Helper method to determine the appropriate morpheus NetworkType code
     * based on Harvester network type
     */
    static String getNetworkTypeCode(String networkType) {

        switch (networkType) {
        // to do: add different network types if needed
            default:
                return HarvesterConstants.HARVESTER_NETWORK_TYPE
        }
    }

    /**
     * Helper method to determine the appropriate morpheus Compute Server Interface Type code
     * based on Harvester network interface model
     */
    static String getInterfaceTypeCode(String interfaceModel) {

        switch (interfaceModel?.toLowerCase()) {
        // to do: add different types if needed
            case ['e1000', 'e1000e']:
                return HarvesterConstants.E1000_INTERFACE_TYPE
            default:
                return HarvesterConstants.VIRTIO_INTERFACE_TYPE
        }
    }

    // Determine if an IP address is IPv4 or IPv6
    static NetAddress.AddressType getIpType(String ipAddress) {
        try {
            def inet = InetAddress.getByName(ipAddress)
            if (inet instanceof Inet4Address) return NetAddress.AddressType.IPV4
            if (inet instanceof Inet6Address) return  NetAddress.AddressType.IPV6
        } catch (Exception e) {
            log.info("getIpType: Invalid IP address format - ${ipAddress} ${e.message}")
        }
        // return ipv4 by default
        return NetAddress.AddressType.IPV4
    }

    static param(String key, Object value, String operator = '=') {
        return [key: key, operator: operator, value: value]
    }

    /**
     * Extracts the server group ID from a ComputeServer's resourcePool
     * @param server ComputeServer instance
     * @param morpheusContext MorpheusContext instance for API access
     * @return The server group ID as a Long, or null if not found
     */
    static Long getServerGroupId(ComputeServer server, def morpheusContext) {
        if (server?.resourcePool?.serverGroup?.id){
            return server.resourcePool.serverGroup.id
        }
        else{
            server = morpheusContext.services.computeServer.get(server.id)
            if (server?.resourcePool?.serverGroup?.id) {
                return server.resourcePool.serverGroup.id
            }
            // namespace pools of a Harvester cloud aren't linked to the connection record: resolve it from the pool
            try {
                return DBUtils.resolveConnection(morpheusContext, server?.resourcePool)?.id
            } catch (Exception e) {
                log.warn("Unable to resolve the Harvester connection of server ${server?.name}: ${e.message}")
                return null
            }
        }
    }
}
