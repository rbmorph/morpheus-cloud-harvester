package com.hpe.morpheus.harvester.plugin.utils.parsers

import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import groovy.util.logging.Slf4j

/**
 * Utility class for parsing Harvester template metadata and extracting relevant information
 * such as operating system details and data volume configurations.
 * This class provides methods to normalize OS strings, extract data volume details,
 * and retrieve boot source information from VM specifications.
 */
@Slf4j
class TemplateParser {

    /**
     * Parse operating system information from template metadata
     * @param rawOs The raw OS string from template annotations or labels
     * @return Map containing osType and version
     */
    static Map parseOperatingSystem(String rawOs) {
        if (!rawOs || rawOs == 'unknown') {
            return [osType: 'unknown', version: 'all']
        }

        def normalizedOs = rawOs.toLowerCase().trim()
        log.debug("Parsing Harvester OS: '${rawOs}' -> '${normalizedOs}'")

        // Extract OS type and version using simple patterns
        def osType = 'unknown'
        def version = 'all'

        // CentOS Stream patterns: centos-stream8, centos-stream9
        if (normalizedOs.contains('centos-stream')) {
            osType = 'centos-stream'
            def match = normalizedOs =~ /centos-stream(\d+)/
            if (match) {
                version = match[0][1]
            }
        }
        // Regular CentOS patterns: centos7, centos8
        else if (normalizedOs.contains('centos')) {
            osType = 'centos'
            def match = normalizedOs =~ /centos(\d+)/
            if (match) {
                version = match[0][1]
            }
        }
        // RHEL patterns: rhel8, rhel9, redhat8
        else if (normalizedOs.contains('rhel') || normalizedOs.contains('redhat')) {
            osType = 'redhat'
            def match = normalizedOs =~ /(?:rhel|redhat)(\d+)/
            if (match) {
                version = match[0][1]
            }
        }
        // Ubuntu patterns: ubuntu20.04, ubuntu22.04
        else if (normalizedOs.contains('ubuntu')) {
            osType = 'ubuntu'
            def match = normalizedOs =~ /ubuntu(\d+)(?:\.(\d+))?/
            if (match) {
                def major = match[0][1]
                def minor = match[0][2] ?: '04'
                version = "${major}.${minor}"
            }
        }
        // Windows patterns: windows2k16, windows2k19, win2k22, windows10, etc.
        else if (normalizedOs.contains('windows') || normalizedOs.contains('win')) {
            osType = 'windows'

            // Handle Windows Server patterns: windows2k16, win2k19, etc.
            def serverMatch = normalizedOs =~ /(?:windows|win)2k(\d+)/
            if (serverMatch) {
                def shortYear = serverMatch[0][1]
                version = "20${shortYear}" // Convert 16 -> 2016, 19 -> 2019, etc.
            }
            // Handle Windows Server full year patterns: windows2016, win2019, etc.
            else {
                def fullYearMatch = normalizedOs =~ /(?:windows|win)(20\d{2})/
                if (fullYearMatch) {
                    version = fullYearMatch[0][1]
                }
                // Handle Windows desktop versions: windows10, win11, etc.
                else {
                    def desktopMatch = normalizedOs =~ /(?:windows|win)(\d+)/
                    if (desktopMatch) {
                        version = desktopMatch[0][1]
                    }
                }
            }
        }
        // Fedora patterns: fedora, fedora38
        else if (normalizedOs.contains('fedora')) {
            osType = 'fedora'
            def match = normalizedOs =~ /fedora(\d+)/
            if (match) {
                version = match[0][1]
            }
        }
        // Rocky Linux patterns: rocky8, rocky9
        else if (normalizedOs.contains('rocky')) {
            osType = 'rocky'
            def match = normalizedOs =~ /rocky(\d+)/
            if (match) {
                version = match[0][1]
            }
        }
        // AlmaLinux patterns: alma8, alma9
        else if (normalizedOs.contains('alma')) {
            osType = 'almalinux'
            def match = normalizedOs =~ /alma(?:linux)?(\d+)/
            if (match) {
                version = match[0][1]
            }
        }
        // Debian patterns: debian11, debian12
        else if (normalizedOs.contains('debian')) {
            osType = 'debian'
            def match = normalizedOs =~ /debian(\d+)/
            if (match) {
                version = match[0][1]
            }
        }
        // openSUSE patterns: opensuse15.5, opensuseleap15.6
        else if (normalizedOs.contains('opensuse')) {
            osType = 'opensuse'
            def match = normalizedOs =~ /opensuse(?:leap|tumbleweed)?(\d+)/
            if (match) {
                version = match[0][1]
            }
        }
        // SUSE Linux Enterprise patterns: sles15, sle-micro, suse15
        else if (normalizedOs.contains('sles') || normalizedOs.contains('suse')) {
            osType = 'suse'
            def match = normalizedOs =~ /(?:sles|suse)(\d+)/
            if (match) {
                version = match[0][1]
            }
        }
        else {
            // For unrecognized OS, use the raw value as osType
            osType = normalizedOs
        }

        def result = [osType: osType, version: version]
        log.debug("Parsed OS result: ${result}")
        return result
    }

    /**
     * Extract detailed information about all data volumes from VM spec
     * @param vmSpec The VM specification object
     * @return List of Maps containing detailed volume information
     */
    static List<Map> extractDataVolumeDetails(def vmSpec) {
        def volumeDetails = []

        try {
            def volumes = vmSpec?.template?.spec?.volumes
            def disks = vmSpec?.template?.spec?.domain?.devices?.disks
            def dataVolumeTemplates = vmSpec?.dataVolumeTemplates

            if (!volumes || !disks) {
                return volumeDetails
            }

            // Process each disk to get complete volume information
            disks.eachWithIndex { disk, diskIndex ->
                def volume = volumes.find { vol -> vol.name == disk.name }
                if (volume) {
                    def volumeInfo = [
                            diskName: disk.name,
                            diskIndex: diskIndex,  // disk index for sorting
                            bootOrder: disk.bootOrder ?: null,
                            busType: disk.disk?.bus ?: 'unknown',
                            type: 'unknown',
                            dataVolumeName: '',
                            pvcName: '',
                            sourceType: 'unknown',
                            sourcePvcName: '',
                            sourcePvcNamespace: '',
                            sourceRefKind: '',
                            sourceRefName: '',
                            sourceRefNamespace: '',
                            requestedStorage: '',
                            storageClassName: '',
                            accessModes: [],
                            volumeMode: '',
                            isBootDisk: false
                    ]

                    // Determine volume type and extract details
                    if (volume.dataVolume) {
                        volumeInfo.type = HarvesterConstants.VOLUME_TYPE_DATAVOLUME
                        volumeInfo.dataVolumeName = volume.dataVolume.name

                        // Find corresponding dataVolumeTemplate for this dataVolume
                        def dvTemplate = dataVolumeTemplates?.find { dv ->
                            dv.metadata?.name == volume.dataVolume.name
                        }

                        if (dvTemplate) {
                            def dvSpec = dvTemplate.spec
                            // Handle sourceRef for DataSource references (takes precedence over source.pvc)
                            if (dvSpec.sourceRef) {
                                log.debug "Found sourceRef in DataVolume template: ${dvSpec.sourceRef}"
                                volumeInfo.sourceType = HarvesterConstants.VOLUME_TYPE_DATASOURCE
                                volumeInfo.sourceRefKind = dvSpec.sourceRef.kind ?: ''
                                volumeInfo.sourceRefName = dvSpec.sourceRef.name ?: ''
                                volumeInfo.sourceRefNamespace = dvSpec.sourceRef.namespace ?: ''
                            } else if (dvSpec.source?.pvc) {
                                log.debug "Found source.pvc in DataVolume template: ${dvSpec.source.pvc}"
                                volumeInfo.sourceType = HarvesterConstants.VOLUME_TYPE_PVC
                                volumeInfo.sourcePvcName = dvSpec.source.pvc.name
                                volumeInfo.sourcePvcNamespace = dvSpec.source.pvc.namespace
                            } else {
                                log.debug "No sourceRef or source.pvc found in DataVolume template ${volume.dataVolume.name}"
                            }

                            // Extract storage information
                            if (dvSpec.storage) {
                                volumeInfo.requestedStorage = dvSpec.storage.resources?.requests?.storage ?: ''
                                volumeInfo.storageClassName = dvSpec.storage.storageClassName ?: ''
                                volumeInfo.accessModes = dvSpec.storage.accessModes ?: []
                                volumeInfo.volumeMode = dvSpec.storage.volumeMode ?: 'Filesystem'
                            }
                        }

                    } else if (volume.cloudInitNoCloud) {
                        volumeInfo.type = HarvesterConstants.VOLUME_TYPE_CLOUDINIT
                        volumeInfo.sourceType = HarvesterConstants.VOLUME_TYPE_CLOUDINIT
                    } else if (volume.persistentVolumeClaim) {
                        // In this case storage Class field is not available directly
                        volumeInfo.type = HarvesterConstants.VOLUME_TYPE_PVC
                        volumeInfo.sourceType = HarvesterConstants.VOLUME_TYPE_PVC
                        volumeInfo.sourcePvcName = volume.persistentVolumeClaim.claimName
                        volumeInfo.pvcName = volume.name
                    }

                    // Determine if this is the boot disk
                    if (disk.bootOrder == 1) {
                        volumeInfo.isBootDisk = true
                    } else if (!disks.any { d -> d.bootOrder == 1 } && disk == disks[0]) {
                        // todo: If no explicit boot order, first disk is boot disk?
                        volumeInfo.isBootDisk = true
                    }

                    volumeDetails.add(volumeInfo)
                }
            }

            // Sort by boot order (boot disk first, then by boot order, then by disk index)
            volumeDetails.sort { a, b ->
                if (a.isBootDisk && !b.isBootDisk) return -1
                if (!a.isBootDisk && b.isBootDisk) return 1
                if (a.bootOrder && b.bootOrder) return a.bootOrder <=> b.bootOrder
                if (a.bootOrder && !b.bootOrder) return -1
                if (!a.bootOrder && b.bootOrder) return 1
                return a.diskIndex <=> b.diskIndex
            }

        } catch (Exception e) {
            log.warn "Error extracting data volume details: ${e.message}"
        }

        return volumeDetails
    }

    /**
     * Extract boot source information from VM spec (legacy method - kept for backward compatibility)
     * @param vmSpec The VM specification object
     * @return Map containing boot source details
     */
    static Map extractBootSource(def vmSpec) {
        def bootSource = [type: 'unknown', source: '', size: '', storageClass: '']

        try {
            def volumeDetails = extractDataVolumeDetails(vmSpec)
            def bootVolume = volumeDetails.find { it.isBootDisk }

            if (bootVolume) {
                bootSource.type = bootVolume.type
                // For DataSource references, use sourceRefName, otherwise use existing logic
                if (bootVolume.sourceType == HarvesterConstants.VOLUME_TYPE_DATASOURCE) {
                    bootSource.source = bootVolume.sourceRefName ?: bootVolume.dataVolumeName
                } else {
                    bootSource.source = bootVolume.dataVolumeName ?: bootVolume.sourcePvcName
                }
                bootSource.size = bootVolume.requestedStorage
                bootSource.storageClass = bootVolume.storageClassName
            }

        } catch (Exception e) {
            log.warn "Error extracting boot source: ${e.message}"
        }

        return bootSource
    }


}
