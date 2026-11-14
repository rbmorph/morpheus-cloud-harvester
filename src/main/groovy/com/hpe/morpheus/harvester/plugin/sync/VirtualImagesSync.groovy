// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.sync

import com.hpe.morpheus.harvester.plugin.HpeHarvesterPlugin
import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterImageApiHelper
import com.hpe.morpheus.harvester.plugin.utils.DataUnitConverterUtil
import com.hpe.morpheus.harvester.plugin.utils.DBUtils
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.hpe.morpheus.harvester.plugin.utils.parsers.TemplateParser
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Datastore
import com.morpheusdata.model.StorageController
import com.morpheusdata.model.StorageControllerType
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.projection.VirtualImageIdentityProjection
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class VirtualImagesSync {
    private ComputeServerGroup cluster
    private MorpheusContext morpheusContext
    private HpeHarvesterPlugin plugin

    VirtualImagesSync(HpeHarvesterPlugin plugin, ComputeServerGroup cluster) {
        this.plugin = plugin
        this.cluster = cluster
        this.morpheusContext = plugin.morpheusContext
    }

    def execute() {
        log.info "Harvester: execute Virtual Image Sync: ${cluster}"
        try {
            // Load OS types for mapping
            def osTypes = morpheusContext.services.osType.list(
                    new DataQuery()
            )
            log.info "Harvester: OS Types loaded: ${osTypes.size()} OS types from database"

            // Load storage controller types for controller synchronization (similar to VM sync)
            Map<String, StorageControllerType> storageControllerTypeMap = DBUtils.getStorageControllerTypeMap(morpheusContext)

            def vmTemplates = getAllVMTemplates()
            if (vmTemplates) {
                log.info "Harvester: Found ${vmTemplates.vmTemplates?.size()} VM images for cluster ${cluster.name}"
                if(vmTemplates.success && vmTemplates.vmTemplates) {
                    def templates = vmTemplates.vmTemplates

                    log.info "Harvester: VM Templates: ${templates.size()}"


                    def virtualImageList
                    try {
                        virtualImageList = morpheusContext.services.virtualImage.listIdentityProjections(
                                new DataQuery().withFilters(
                                        new DataFilter('refType', HarvesterConstants.COMPUTE_SERVER_GROUP),
                                        new DataFilter('refId', cluster.id),
                                        new DataFilter('category', "harvester.image.template.${cluster.id}"),
                                )
                        )
                        log.info("Harvester: Found ${virtualImageList.size()} existing virtual images in Morpheus")
                    } catch(e) {
                        log.error("Harvester: Error retrieving existing virtual images: ${e.message}", e)
                        throw e
                    }

                    // Create an Observable from the list
                    Observable<VirtualImageIdentityProjection> domainRecords = Observable.fromIterable(virtualImageList)

                    SyncTask<VirtualImageIdentityProjection, Map, VirtualImage> syncTask = new SyncTask<>(domainRecords, templates ?: [])
                    syncTask.addMatchFunction { VirtualImageIdentityProjection domainObject, Map apiItem ->
                        domainObject.externalId == apiItem.uid
                    }.onDelete { removeItems ->
                        removeVirtualImages(removeItems)
                    }.onUpdate { List<SyncTask.UpdateItem<VirtualImage, Map>> updateItems ->
                        updateVirtualImages(updateItems, osTypes, storageControllerTypeMap)
                    }.onAdd { itemsToAdd ->
                        addVirtualImages(itemsToAdd, osTypes, storageControllerTypeMap)
                    }.withLoadObjectDetailsFromFinder { updateItems ->
                        def fullVirtualImageList = morpheusContext.services.virtualImage.list(
                                new DataQuery().withFilters(
                                        new DataOrFilter(updateItems.collect {new DataFilter('id', it.existingItem.id) }),
                                ))
                        return Observable.fromIterable(fullVirtualImageList)
                    }.start()
                }
            } else {
                log.warn "Harvester: No VM Templates found for cluster ${cluster.name}"
            }
        } catch(e) {
            log.error("Harvester: virtualImages error: ${e}", e)
        }
    }

    private addVirtualImages(addList, osTypes, storageControllerTypeMap) {
        log.info("Harvester: Adding virtual images: ${addList?.size()}")
        def itemsToAdd = []

        // Get storage volume types for volume synchronization (similar to VM sync pattern)
        Map<String, StorageVolumeType> volumeTypes = getStorageVolumeTypes()

        log.info "addList: ${addList.size()} templates to process"

        addList?.each { Map template ->
            def imageName = template.name ?: "VM Template ${template.uid}"
            def isUserDefined = isUserDefinedTemplate(template)

            log.debug ("Harvester: Adding virtual image: ${imageName} (userDefined=${isUserDefined})")

            // Get the appropriate osType for this template using already-parsed values
            def osType = getOsTypeForOperatingSystem(
                    template.operatingSystem as String,
                    template.osVersion as String, osTypes,
                    template.name as String)
            if (osType) {
                log.debug("Harvester: Mapped template '${imageName}' osType='${template.operatingSystem}' version='${template.osVersion}' to osType '${osType.name}'")
            }

            log.info "volume info : ${template.volumes}"

            // Determine platform and interface name based on OS
            def platform = mapOperatingSystemToPlatform(template.operatingSystem as String)
            def interfaceName = (platform == 'windows') ? 'Ethernet' : 'eth0'

            def imageConfig = [
                    refType: HarvesterConstants.COMPUTE_SERVER_GROUP,
                    refId: cluster.id,
                    category: "harvester.image.template.${cluster.id}",
                    owner: cluster.owner,
                    account: cluster.cloud.account,

                    // Core identification fields
                    name: imageName,
                    code: "harvester.image.template.${cluster.id}.${template.operatingSystem}.${template.osVersion}",
                    uniqueId: template.uid,
                    externalId: template.uid,
                    internalId: template.uid,

                    // Image details
                    description: template.description ?: '',
                    platform: platform,
                    osType: osType, // Add the osType object
                    interfaceName: interfaceName, // 'Ethernet' for Windows, 'eth0' for others

                    // Template specific fields
                    // ISO images are 'iso': provisioning attaches them as a CD-ROM next to a blank root disk
                    imageType: template.iso ? 'iso' : 'raw',

                    // Resource specifications from template
                    minRam: DataUnitConverterUtil.parseKubernetesUnitsToBytes(template.memory?.toString()),
                    // an ISO doesn't set the root disk's size
                    minDisk: template.iso ? null : DataUnitConverterUtil.parseKubernetesUnitsToBytes(template.bootSource?.size?.toString()),

                    // Template metadata - Updated based on user-defined detection
                    userDefined: isUserDefined,
                    userUploaded: false, // Always false since templates come from Harvester API sync, not user upload
                    isPublic: false,

                    // Force customization and system image settings
                    systemImage: false,

                    // Harvester specific fields in config - Convert to JSON string
                    config: JsonOutput.toJson([
                            templateType: template.templateType ?: 'vm',
                            namespace: template.namespace,
                            displayName: template.displayName,
                            iconClass: template.iconClass,
                            workloadProfile: template.workloadProfile,
                            flavor: template.flavor,
                            parameters: template.parameters,
                            bootSource: template.bootSource,
                            volumes: template.volumes,  // Store volume information in config
                            clusterId: cluster.id,
                            labels: template.rawTemplate?.metadata?.labels ?: [:],
                            annotations: template.rawTemplate?.metadata?.annotations ?: [:],
                            nodeSelector: template.nodeSelector ?: [:],
                            imageName: template.imageName,
                            imageId: template.imageId,
                            imageStorageClassName: template.imageStorageClassName,
                            sourceType: template.sourceType,
                            url: template.url,
                            iso: template.iso == true,
                            virtualSize: template.virtualSize
                    ]),

                    visibility: 'private',
                    isForceCustomization: false,

                    // Status and flags that exist in the model
                    status: 'Active',
                    deleted: false,
                    vmToolsInstalled: true,
                    folderName: "${template.namespace}",
            ]
            log.debug "Virtual image config (userDefined=${isUserDefined}): ${imageConfig.name}"
            def virtualImage = new VirtualImage(imageConfig)

            // Set isCloudInit to false using proper setter method
            // virtualImage.setIsCloudInit(false)

            virtualImage = morpheusContext.async.virtualImage.create(virtualImage).blockingGet()


            // Then handle volumes (which will associate with controllers)
            if (template.volumes) {
                def processedVolumes = processVolumeDetailsForSync(template.volumes)

                if (processedVolumes) {
                    // Sync storage controllers first (this is kept in separate if condition so that later if condition changes)
                    syncStorageControllers(processedVolumes, storageControllerTypeMap, virtualImage)
                    // Sync volumes after controllers
                    addVolumesToVirtualImageWithTypes(virtualImage, processedVolumes, volumeTypes, storageControllerTypeMap)
                }
            }

            // Get latest virtual image after creation
            virtualImage = morpheusContext.services.virtualImage.get(virtualImage.id)
            log.info( "Harvester: Created virtual image: ${virtualImage.name} (ID: ${virtualImage.id}) (Volumes: ${virtualImage.volumes?.size()})")

            // Sync virtual image location
            syncImageLocationForVirtualImage(virtualImage, template)

            itemsToAdd << virtualImage
        }

        if(itemsToAdd) {
            log.info("Harvester: Successfully added virtual images: ${itemsToAdd.size()}")
        }
    }

    private updateVirtualImages(updateList, osTypes, storageControllerTypeMap) {
        def itemsToUpdate = []

        // Get storage volume types for volume synchronization
        Map<String, StorageVolumeType> volumeTypes = getStorageVolumeTypes()

        updateList?.each { updateMap ->
            VirtualImage virtualImage = updateMap.existingItem
            def template = updateMap.masterItem
            def doSave = false

            if (virtualImage.refType != HarvesterConstants.COMPUTE_SERVER_GROUP) {
                virtualImage.refType = HarvesterConstants.COMPUTE_SERVER_GROUP
                doSave = true
            }
            if (virtualImage.refId != cluster.id) {
                virtualImage.refId = cluster.id
                doSave = true
            }
            if (virtualImage.category != "harvester.image.template.${cluster.id}") {
                virtualImage.category = "harvester.image.template.${cluster.id}"
                doSave = true
            }
            if (virtualImage.owner?.id != cluster.owner?.id) {
                virtualImage.owner = cluster.owner
                doSave = true
            }
            if (virtualImage.account?.id != cluster.cloud.account?.id) {
                virtualImage.account = cluster.cloud.account
                doSave = true
            }

            // Update core identification fields
            if (virtualImage.name != template.name) {
                virtualImage.name = template.name
                doSave = true
            }

            // Update code field that's set during creation
            def expectedCode = "harvester.image.template.${cluster.id}.${template.operatingSystem}.${template.osVersion}"
            if (virtualImage.code != expectedCode) {
                virtualImage.code = expectedCode
                doSave = true
            }

            if (virtualImage.description != template.description) {
                virtualImage.description = template.description ?: ''
                doSave = true
            }

            def mappedPlatform = mapOperatingSystemToPlatform(template.operatingSystem)
            if (virtualImage.platform != mappedPlatform) {
                virtualImage.platform = mappedPlatform
                doSave = true
            }

            // Update interfaceName based on platform - 'Ethernet' for Windows, 'eth0' for others
            def expectedInterfaceName = (mappedPlatform == 'windows') ? 'Ethernet' : 'eth0'
            if (virtualImage.interfaceName != expectedInterfaceName) {
                virtualImage.interfaceName = expectedInterfaceName
                doSave = true
            }

            // Update osType using already-parsed values
            def newOsType = getOsTypeForOperatingSystem(template.operatingSystem as String, template.osVersion as String, osTypes, template.name as String)
            if (virtualImage.osType != newOsType) {
                virtualImage.osType = newOsType
                if (newOsType) {
                    log.debug("Harvester: Updated osType for '${virtualImage.name}' to '${newOsType.name}'")
                } else {
                    log.debug("Harvester: Cleared osType for '${virtualImage.name}' (no match found)")
                }
                doSave = true
            }

            // Update user-defined status
            def isUserDefined = isUserDefinedTemplate(template)
            if (virtualImage.userDefined != isUserDefined) {
                virtualImage.userDefined = isUserDefined
                doSave = true
            }
            if (virtualImage.userUploaded != false) {
                virtualImage.userUploaded = false // Always false since templates come from Harvester API sync, not user upload
                doSave = true
            }
            if (virtualImage.uniqueId != template.uid) {
                virtualImage.uniqueId = template.uid
                doSave = true
            }
            if (virtualImage.externalId != template.uid) {
                virtualImage.externalId = template.uid
                doSave = true
            }
            if (virtualImage.internalId != template.uid) {
                virtualImage.internalId = template.uid
                doSave = true
            }

            // Update resource specifications
            def newMinRam = DataUnitConverterUtil.parseKubernetesUnitsToBytes(template.memory?.toString())
            if (virtualImage.minRam != newMinRam) {
                virtualImage.minRam = newMinRam
                doSave = true
            }

            def newMinDisk = template.iso ? null : DataUnitConverterUtil.parseKubernetesUnitsToBytes(template.bootSource?.size?.toString())
            if (virtualImage.minDisk != newMinDisk) {
                virtualImage.minDisk = newMinDisk
                doSave = true
            }

            String newImageType = template.iso ? 'iso' : 'raw'
            if (virtualImage.imageType != newImageType) {
                virtualImage.imageType = newImageType
                doSave = true
            }

            if (virtualImage.visibility != 'private') {
                virtualImage.visibility = 'private'
                doSave = true
            }

//            if (virtualImage.getForceCustomization() != false) {
//                virtualImage.setForceCustomization(false)
//                doSave = true
//            }

            if (virtualImage.systemImage != false) {
                virtualImage.systemImage = false
                doSave = true
            }

            if (virtualImage.isPublic != false) {
                virtualImage.isPublic = false
                doSave = true
            }

//            if (virtualImage.getCloudInit() != false) {
//                virtualImage.setIsCloudInit(false)
//                doSave = true
//            }

            if (virtualImage.status != 'Active') {
                virtualImage.status = 'Active'
                doSave = true
            }

            if (virtualImage.deleted != false) {
                virtualImage.deleted = false
                doSave = true
            }
//
//            if (virtualImage.vmToolsInstalled != true) {
//                virtualImage.vmToolsInstalled = true
//                doSave = true
//            }

            // Update folder name based on namespace
            def expectedFolderName = "${template.namespace}"
            if (virtualImage.folderName != expectedFolderName) {
                virtualImage.folderName = expectedFolderName
                doSave = true
            }

            // Update config
            def newConfig = [
                    templateType: template.templateType ?: 'vm',
                    namespace: template.namespace,
                    displayName: template.displayName,
                    iconClass: template.iconClass,
                    workloadProfile: template.workloadProfile,
                    flavor: template.flavor,
                    parameters: template.parameters,
                    bootSource: template.bootSource,
                    volumes: template.volumes,  // Store volume information in config
                    clusterId: cluster.id,
                    labels: template.rawTemplate?.metadata?.labels ?: [:],
                    annotations: template.rawTemplate?.metadata?.annotations ?: [:],
                    nodeSelector: template.nodeSelector ?: [:],
                    imageName: template.imageName,
                    imageId: template.imageId,
                    imageStorageClassName: template.imageStorageClassName,
                    sourceType: template.sourceType,
                    url: template.url,
                    iso: template.iso == true,
                    virtualSize: template.virtualSize
            ]
            if (virtualImage.getConfigMap() != newConfig) {
                virtualImage.setConfigMap(newConfig)
                doSave = true
            }

            if (doSave) {
                itemsToUpdate << virtualImage
            }

            // Sync storage controllers first (like VM sync)
            if (template.volumes) {
                def processedVolumes = processVolumeDetailsForSync(template.volumes)
                if (processedVolumes) {
                    def controllerSyncResult = syncStorageControllers(processedVolumes, storageControllerTypeMap, virtualImage)
                    
                    // Remove obsolete controllers after syncing
                    def controllersToRemove = controllerSyncResult.removeList
                    if (controllersToRemove) {
                        log.info("Removing ${controllersToRemove.size()} obsolete controllers from VirtualImage: ${virtualImage.name}")
                        morpheusContext.async.storage.controller.remove(controllersToRemove, virtualImage).blockingGet()
                    }
                }
            }

            // Then handle volume synchronization (which will associate with controllers)
            if (template.volumes && volumeTypes) {
                def processedVolumes = processVolumeDetailsForSync(template.volumes)
                if (processedVolumes || virtualImage.volumes) {
                    syncVolumesForVirtualImage(virtualImage, processedVolumes ?: [], volumeTypes, storageControllerTypeMap)
                }
            }

            // Get latest virtual image after updates
            virtualImage = morpheusContext.services.virtualImage.get(virtualImage.id)
            log.info( "Harvester: Updated virtual image: ${virtualImage.name} (ID: ${virtualImage.id}) (Volumes: ${virtualImage.volumes?.size()})")

            // Sync virtual image location
            syncImageLocationForVirtualImage(virtualImage, template)
        }

        if(itemsToUpdate) {
            morpheusContext.services.virtualImage.bulkSave(itemsToUpdate)
            log.info("Harvester: Successfully updated virtual images: ${itemsToUpdate.size()}")
        }
    }

    private removeVirtualImages(removeList) {
        log.info("Harvester: Removing virtual images: ${removeList?.size()}")
        if (removeList) {
            morpheusContext.services.virtualImage.bulkRemove(removeList)
        }
    }

    /**
     * This is just stop gap for now, we can improve this later
     * Maps Harvester VM template operating system to broader platform categories
     * @param operatingSystem The OS from template (e.g., "fedora", "centos", "rhel", "windows10")
     * @return Platform category: "linux", "windows", or "mac"
     */
    private static String mapOperatingSystemToPlatform(String operatingSystem) {
        if (!operatingSystem) {
            return 'linux' // Default to linux for unknown OS
        }

        def os = operatingSystem.toLowerCase().trim()

        // Linux distributions
        if (os.contains('fedora') ||
                os.contains('centos') ||
                os.contains('rhel') ||
                os.contains('red hat') ||
                os.contains('redhat') ||
                os.contains('ubuntu') ||
                os.contains('debian') ||
                os.contains('suse') ||
                os.contains('opensuse') ||
                os.contains('alpine') ||
                os.contains('rocky') ||
                os.contains('alma') ||
                os.contains('oracle') ||
                os.contains('linux') ||
                os.contains('stream')) {
            return 'linux'
        }

        // Windows variants
        if (os.contains('windows') ||
                os.contains('win') ||
                os.contains('server 2016') ||
                os.contains('server 2019') ||
                os.contains('server 2022') ||
                os.contains('server 2025') ||
                os.contains('win10') ||
                os.contains('win11') ||
                os.contains('2k16') ||
                os.contains('2k19') ||
                os.contains('2k22') ||
                os.contains('2k25')) {
            return 'windows'
        }

        // macOS variants
        if (os.contains('macos') ||
                os.contains('mac os') ||
                os.contains('darwin') ||
                os.contains('osx') ||
                os.contains('os x')) {
            return 'mac'
        }

        // Default to linux for unknown distributions
        log.debug("Harvester: Unknown operating system '${operatingSystem}', defaulting to 'linux'")
        return 'linux'
    }

    /**
     * Determine if a template is user-defined based on its metadata
     * @param template The template object (transformed structure from HarvesterApiHelper)
     * @return true if user-defined, false otherwise
     */
    private static boolean isUserDefinedTemplate(Map template) {
        // Harvester images are always uploaded or downloaded by users; there are no vendor-managed base images
        return true
    }

    /**
     * Method to find OS type in the list by searching through osTypes
     * @param osType The OS type (e.g., "centos", "centos-stream", "windows")
     * @param version The version (e.g., "8", "7", "2019")
     * @param osTypes List of OS type objects from database
     * @return The best matching OsType or null
     */
    private static def findOsTypeInList(String osType, String version, List osTypes) {
        if (!osType || !osTypes) {
            return null
        }

        log.debug("Searching for osType='${osType}', version='${version}' in ${osTypes.size()} OS types")

        // Strategy 1: Find exact version match with 64-bit preference
        if (version && version != 'all') {
            def exactMatches = osTypes.findAll { osTypeObj ->
                def category = osTypeObj.category?.toLowerCase()
                def osVersion = osTypeObj.osVersion

                // Handle special case for centos-stream
                if (osType == 'centos-stream') {
                    return (category == 'centos' && osVersion == version &&
                            osTypeObj.code?.toLowerCase()?.contains('stream'))
                }

                return (category == osType && osVersion == version)
            }

            if (exactMatches) {
                // Prefer 64-bit version if available
                def match64 = exactMatches.find { it.code?.toLowerCase()?.contains('.64') }
                if (match64) {
                    log.debug("Found exact 64-bit match: ${match64.name}")
                    return match64
                }
                log.debug("Found exact match: ${exactMatches[0].name}")
                return exactMatches[0]
            }
        }

        // Strategy 2: Find generic "all" version match with 64-bit preference
        def genericMatches = osTypes.findAll { osTypeObj ->
            def category = osTypeObj.category?.toLowerCase()
            def osVersion = osTypeObj.osVersion

            // Handle special case for centos-stream
            if (osType == 'centos-stream') {
                return (category == 'centos' && osVersion == 'all' &&
                        osTypeObj.code?.toLowerCase()?.contains('stream'))
            }

            return (category == osType && osVersion == 'all')
        }

        if (genericMatches) {
            // Prefer 64-bit version if available
            def match64 = genericMatches.find { it.code?.toLowerCase()?.contains('.64') }
            if (match64) {
                log.debug("Found generic 64-bit match: ${match64.name}")
                return match64
            }
            log.debug("Found generic match: ${genericMatches[0].name}")
            return genericMatches[0]
        }

        // Strategy 3: Fallback for centos-stream to regular centos with "all" version
        if (osType == 'centos-stream') {
            def centosAllMatches = osTypes.findAll { osTypeObj ->
                def category = osTypeObj.category?.toLowerCase()
                def osVersion = osTypeObj.osVersion
                return (category == 'centos' && osVersion == 'all')
            }

            if (centosAllMatches) {
                // Prefer 64-bit version if available
                def match64 = centosAllMatches.find { it.code?.toLowerCase()?.contains('.64') }
                if (match64) {
                    log.debug("Found centos-stream fallback to centos 64-bit match: ${match64.name}")
                    return match64
                }
                log.debug("Found centos-stream fallback to centos match: ${centosAllMatches[0].name}")
                return centosAllMatches[0]
            }
        }

        // Strategy 4: General fallback - if we can't find exact osType, try to find a broader category
        // This helps when we have specific OS types that don't exist in DB but have broader categories
        def categoryFallbacks = [
                'centos-stream': 'centos',
                'rhel': 'redhat',
                'rocky': 'centos',
                'almalinux': 'centos',
                'alma': 'centos',
                'opensuse': 'suse'
        ]

        def fallbackCategory = categoryFallbacks[osType]
        if (fallbackCategory) {
            def fallbackMatches = osTypes.findAll { osTypeObj ->
                def category = osTypeObj.category?.toLowerCase()
                def osVersion = osTypeObj.osVersion
                return (category == fallbackCategory && osVersion == 'all')
            }

            if (fallbackMatches) {
                // Prefer 64-bit version if available
                def match64 = fallbackMatches.find { it.code?.toLowerCase()?.contains('.64') }
                if (match64) {
                    log.debug("Found fallback ${osType} -> ${fallbackCategory} 64-bit match: ${match64.name}")
                    return match64
                }
                log.debug("Found fallback ${osType} -> ${fallbackCategory} match: ${fallbackMatches[0].name}")
                return fallbackMatches[0]
            }
        }

        log.debug("No match found for osType='${osType}', version='${version}'")
        return null
    }

    /**
     * OS type matching that uses already-parsed OS information from template
     * @param osType The already-parsed OS type from template (e.g., "centos", "centos-stream")
     * @param osVersion The already-parsed OS version from template (e.g., "8", "7")
     * @param osTypes List of OS type objects from database
     * @param templateName Template name for logging context
     * @return The best matching OsType or null
     */
    private static def getOsTypeForOperatingSystem(String osType, String osVersion, List osTypes, String templateName = null) {
        if (!osType || !osTypes) {
            return null
        }

        log.debug("Harvester: Finding OS match for osType='${osType}', version='${osVersion}' (template: '${templateName}')")

        // Find the best match in the osTypes list using already-parsed values
        def match = findOsTypeInList(osType, osVersion ?: 'all', osTypes)

        if (match) {
            log.debug("Harvester: MATCHED osType='${osType}' version='${osVersion}' -> '${match.name}' (${match.code})")
            return match
        } else {
            log.warn("Harvester: NO MATCH for osType='${osType}', version='${osVersion}' (template: '${templateName}')")
            return null
        }
    }



    /**
     * Fetches all VM templates with automatic authentication and pagination
     * @param pageSize The number of items to fetch per page (default is 50)
     * @param maxPages The maximum number of pages to fetch (default is 100, use -1 for unlimited)
     * @return A map containing the success status, list of all VM templates, total pages, total items, and any errors
     */
    Map getAllVMTemplates(int pageSize = 50, int maxPages = -1) {
        try {
            def allTemplates = []
            def continueToken = null
            def hasMore = true
            def currentPage = 0
            def totalFilteredItems = 0
            def errors = []

            // Determine if we should fetch unlimited pages
            boolean unlimitedPages = (maxPages == -1)

            log.info "Starting to fetch all VM templates with pagination${unlimitedPages ? ' (unlimited pages)' : ' (max ' + maxPages + ' pages)'}"

            while (hasMore && (unlimitedPages || currentPage < maxPages)) {
                currentPage++

                def pageResult = getVMTemplatesWithPagination(continueToken, pageSize)

                if (pageResult.success) {
                    allTemplates.addAll(pageResult.vmTemplates)
                    totalFilteredItems += pageResult.filteredCount
                    continueToken = pageResult.continueToken
                    hasMore = pageResult.hasMore

                    log.debug "Page ${currentPage}: ${pageResult.vmTemplates.size()} VM templates, continue: ${hasMore}"

                    // Safety check for unlimited pages - prevent runaway loops
                    if (unlimitedPages && currentPage > 1000) {
                        log.warn "Safety limit reached: Stopped at page ${currentPage} to prevent runaway pagination"
                        errors << "Safety limit reached: Stopped at page ${currentPage}"
                        break
                    }
                } else {
                    errors << "Page ${currentPage} failed: ${pageResult.message}"
                    log.error "Failed to fetch page ${currentPage}: ${pageResult.message}"
                    hasMore = false
                }
            }

            if (!unlimitedPages && currentPage >= maxPages && hasMore) {
                log.warn "Stopped pagination at maximum pages (${maxPages}), there may be more data"
            }

            log.info "Completed pagination: ${allTemplates.size()} total VM templates across ${currentPage} pages"
            return [
                    success: allTemplates.size() > 0 || errors.isEmpty(),
                    vmTemplates: allTemplates,
                    totalPages: currentPage,
                    totalItems: totalFilteredItems,
                    truncated: !unlimitedPages && currentPage >= maxPages && hasMore,
                    errors: errors
            ]
        } catch (Exception e) {
            log.error "Error in getAllVMTemplates: ${e.message}", e
            return [success: false, errors: [e.message], vmTemplates: [], totalPages: 0, totalItems: 0, truncated: false]
        }
    }

    /**
     * Fetches one page of Harvester VirtualMachineImages and converts each ready image into the
     * image record used by the rest of this sync (name, uid, namespace, OS info, bootSource and a single boot volume).
     * @param continueToken The token for pagination, if available.
     * @param pageSize The number of items to fetch per page.
     * @return A map with success, vmTemplates (image records), continueToken and paging metadata.
     */
    Map getVMTemplatesWithPagination(String continueToken = null, int pageSize = 50) {
        def ret = [success: false, vmTemplates: [], continueToken: "", hasMore: false]

        try {
            log.info "Fetching Harvester VM images with pagination (page size: ${pageSize})"
            def imageHelper = getImageApiHelper()
            def response = imageHelper.getVMImages(continueToken, pageSize)

            if (response.success && response.data) {
                def responseData = response.data
                def currentItems = responseData.items ?: []
                def nextContinueToken = responseData.metadata?.continue ?: ""

                if (defaultStorageClassName == null) {
                    defaultStorageClassName = imageHelper.getDefaultStorageClassName()
                }

                def readyImages = currentItems.findAll { item ->
                    boolean ready = HarvesterImageApiHelper.isImageReady(item as Map)
                    if (!ready) {
                        log.debug "Skipping Harvester image '${item.metadata?.namespace}/${item.metadata?.name}' - not imported yet"
                    }
                    return ready
                }
                String storageClassForImages = defaultStorageClassName
                def imageRecords = readyImages.collect { item -> toImageRecord(item as Map, storageClassForImages) }

                ret.vmTemplates = imageRecords
                ret.continueToken = nextContinueToken
                ret.hasMore = !nextContinueToken.isEmpty()
                ret.success = true
                ret.currentPageSize = currentItems.size()
                ret.filteredCount = imageRecords.size()
                ret.unfilteredCount = currentItems.size()
                ret.remainingItemCount = responseData.metadata?.remainingItemCount ?: 0

                log.info "Retrieved ${imageRecords.size()} ready images from ${currentItems.size()} Harvester images, has more: ${ret.hasMore}"
            } else {
                log.warn "Failed to fetch Harvester VM images: ${response.msg}"
                ret.message = response.msg ?: 'Failed to fetch VM images'
            }
        } catch (Exception e) {
            log.error "Error getting Harvester VM images with pagination: ${e.message}", e
            ret.success = false
            ret.message = e.message
        }
        return ret
    }

    // cached per sync run
    protected String defaultStorageClassName

    /** Test seam */
    protected HarvesterImageApiHelper getImageApiHelper() {
        return new HarvesterImageApiHelper(this.cluster)
    }

    /**
     * Converts a Harvester VirtualMachineImage into an image record.
     * The boot volume keeps the image's own storage class (longhorn-image-*) and image id in its
     * config so provisioning can create the root PVC from the image; the Morpheus datastore is the
     * cluster's default storage class because per-image classes are not synced as datastores.
     */
    static Map toImageRecord(Map image, String defaultStorageClass) {
        def metadata = image?.metadata ?: [:]
        Map labels = (metadata.labels ?: [:]) as Map
        Map annotations = (metadata.annotations ?: [:]) as Map
        String name = metadata.name
        String namespace = metadata.namespace
        String displayName = image?.spec?.displayName ?: labels[HarvesterConstants.LABEL_IMAGE_DISPLAY_NAME] ?: name

        Map osInfo = parseImageOperatingSystem(labels, displayName)

        Long virtualSize = toLong(image?.status?.virtualSize) ?: toLong(image?.status?.size)
        String requestedStorage = bytesToGiString(virtualSize)
        String imageStorageClass = HarvesterImageApiHelper.getImageStorageClassName(image)
        String imageId = HarvesterImageApiHelper.getImageId(image)

        Map bootVolume = [
                diskName: 'disk-0',
                diskIndex: 0,
                bootOrder: 1,
                busType: 'virtio',
                type: HarvesterConstants.VOLUME_TYPE_PVC,
                dataVolumeName: '',
                pvcName: 'root',
                sourceType: HarvesterConstants.VOLUME_SOURCE_HARVESTER_IMAGE,
                sourcePvcName: '',
                sourcePvcNamespace: '',
                sourceRefKind: 'VirtualMachineImage',
                sourceRefName: name,
                sourceRefNamespace: namespace,
                requestedStorage: requestedStorage,
                storageClassName: defaultStorageClass ?: HarvesterConstants.DEFAULT_STORAGE_CLASS,
                imageStorageClassName: imageStorageClass,
                imageId: imageId,
                accessModes: ['ReadWriteMany'],
                volumeMode: 'Block',
                isBootDisk: true
        ]

        return [
                name: displayName,
                uid: metadata.uid,
                namespace: namespace,
                displayName: displayName,
                description: annotations['field.cattle.io/description'] ?: annotations.description ?: '',
                iconClass: null,
                tags: null,
                deprecated: false,
                workloadProfile: null,
                operatingSystem: osInfo.osType,
                osVersion: osInfo.version,
                cpu: null,
                memory: null,
                bootSource: [type: HarvesterConstants.VOLUME_SOURCE_HARVESTER_IMAGE, source: imageId,
                             size: requestedStorage, storageClass: imageStorageClass],
                volumes: [bootVolume],
                flavor: null,
                parameters: [],
                templateType: 'image',
                iso: isIsoImage(image),
                virtualSize: virtualSize,
                imageName: name,
                imageId: imageId,
                imageStorageClassName: imageStorageClass,
                sourceType: image?.spec?.sourceType,
                url: image?.spec?.url,
                rawTemplate: [metadata: [labels: [:], annotations: [:]]],
                nodeSelector: null
        ]
    }

    /**
     * OS detection: explicit OS labels on the image first, then the display name
     * (e.g. "ubuntu-22.04-server-cloudimg" -> ubuntu 22.04, "Win2022" -> windows 2022).
     */
    /**
     * Harvester has no image-type field: an image is an ISO when its harvesterhci.io/image-type label says so (set by
     * Harvester's UI), or its name, display name or URL ends in .iso
     */
    static boolean isIsoImage(Map image) {
        Map labels = (image?.metadata?.labels ?: [:]) as Map
        String type = labels[HarvesterConstants.LABEL_IMAGE_TYPE]?.toString()?.toLowerCase()
        if (type == 'iso') return true
        if (type && type != 'iso') return false
        return [image?.metadata?.name, image?.spec?.displayName, image?.spec?.url].any { v ->
            String text = v?.toString()?.toLowerCase()?.replaceFirst(/[?#].*$/, '')
            text?.endsWith('.iso')
        }
    }

    static Map parseImageOperatingSystem(Map labels, String displayName) {
        String rawOs = HarvesterConstants.IMAGE_OS_TYPE_LABELS.findResult { labels?.get(it) }?.toString()
        String rawVersion = HarvesterConstants.IMAGE_OS_VERSION_LABELS.findResult { labels?.get(it) }?.toString()
        if (rawOs) {
            String compact = "${rawOs}${rawVersion ?: ''}".replaceAll(/[\s_-]/, '')
            Map parsed = TemplateParser.parseOperatingSystem(compact)
            if (rawVersion && parsed.version == 'all') {
                parsed.version = rawVersion
            }
            return parsed
        }
        String compactName = (displayName ?: '').toLowerCase().replaceAll(/[\s_-]/, '')
        Map parsed = TemplateParser.parseOperatingSystem(compactName)
        // parseOperatingSystem echoes unrecognised input back as the osType; treat that as unknown
        if (parsed.osType == compactName) {
            return [osType: 'unknown', version: 'all']
        }
        return parsed
    }

    private static Long toLong(def value) {
        if (value == null) {
            return null
        }
        try {
            return value as Long
        } catch (Exception ignored) {
            return null
        }
    }

    /**
     * Bytes to a whole-GiB Kubernetes quantity, rounded up, minimum 1Gi.
     */
    static String bytesToGiString(Long bytes) {
        if (!bytes || bytes <= 0) {
            return ''
        }
        long gib = 1024L * 1024L * 1024L
        long rounded = (long) Math.ceil(bytes / (double) gib)
        return "${Math.max(1L, rounded)}Gi".toString()
    }

    /**
     * Synchronizes storage volumes for a VirtualImage with proper volume type detection
     * @param virtualImage The VirtualImage to sync volumes for
     * @param processedVolumes List of processed volumes with volume type information
     * @param volumeTypes Map of volume type identifiers to StorageVolumeType objects
     * @param isNewImage true if this is a new image (add scenario), false for update
     */
    /**
     * Synchronizes storage volumes for an existing VirtualImage (similar to VMsSync.syncVolumesForExistingVM)
     * @param virtualImage The VirtualImage to sync volumes for
     * @param processedVolumes List of processed volumes from cloud
     * @param volumeTypes Map of volume type identifiers to StorageVolumeType objects
     * @param storageControllerTypeMap Map of storage controller types
     */
    private syncVolumesForVirtualImage(VirtualImage virtualImage, List processedVolumes, 
                                       Map<String, StorageVolumeType> volumeTypes,
                                       Map<String, StorageControllerType> storageControllerTypeMap) {
        log.debug("Syncing volumes for VirtualImage: ${virtualImage.name} - ${processedVolumes.size()} processed volumes")

        if (!volumeTypes || volumeTypes.isEmpty()) {
            log.warn("No storage volume types available, skipping volume synchronization for ${virtualImage.name}")
            return
        }

        List<StorageVolume> cleanupVolumes = []

        if (!virtualImage.volumes || virtualImage.volumes.isEmpty()) {
            // No existing volumes - add all volumes
            log.debug("VirtualImage ${virtualImage.name} has no volumes, creating all ${processedVolumes.size()} volumes")
            addVolumesToVirtualImageWithTypes(virtualImage, processedVolumes, volumeTypes, storageControllerTypeMap)
        } else {
            // Has existing volumes - process updates/adds/removes
            log.debug("VirtualImage ${virtualImage.name} has ${virtualImage.volumes.size()} existing volumes, processing updates")
            
            // Handle volume removals
            if (virtualImage.volumes.size() > processedVolumes.size()) {
                log.debug("Removing volumes from VirtualImage: ${virtualImage.id} - ${virtualImage.name}")

                def processedVolumeNames = processedVolumes.collect { vol ->
                    vol.volumeType == HarvesterConstants.VOLUME_TYPE_DATAVOLUME ?
                        vol.dataVolumeName : (vol.pvcName ?: vol.dataVolumeName ?: vol.name)
                }

                def removeVolumes = virtualImage.volumes.findAll { vol ->
                    !processedVolumeNames.contains(vol.name)
                }
                
                if (removeVolumes) {
                    removeVolumes.each { vol ->
                        virtualImage.getVolumes().remove(vol)
                        cleanupVolumes << vol
                    }
                }
            }

            // Handle volume additions and updates
            updateVolumesForVirtualImageWithTypes(virtualImage, processedVolumes, volumeTypes, storageControllerTypeMap)
        }

        // Clean up removed volumes
        if (cleanupVolumes) {
            log.info("Removing ${cleanupVolumes.size()} removed volumes from VirtualImage: ${virtualImage.name}")
            morpheusContext.async.storage.volume.remove(cleanupVolumes, virtualImage).blockingGet()
        }
    }

    /**
     * Find pvc by name from storage volumes of type HarvesterPVC
    */
    private StorageVolume findPvcByName(String pvcName) {
        if (!pvcName) {
            return null
        }

        log.debug("Looking up existing PVC volume by name: ${pvcName}")

        StorageVolumeType volType = morpheusContext.services.storage.volume.storageVolumeType.find(
                new DataQuery().withFilter('code', HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC))

        log.debug("StorageVolumeType lookup for '${HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC}': ${volType ? 'FOUND' : 'NOT FOUND'}")

        def pvcVolume = morpheusContext.services.storage.volume.find(new DataQuery().withFilters(
                new DataFilter('name', pvcName),
                new DataFilter('type', volType),
                new DataFilter('category', "harvester.pvc.cluster.${cluster.id}"),
        ))

        if (pvcVolume) {
            log.debug("Found existing PVC volume: ${pvcVolume.name} (ID: ${pvcVolume.id})")
        } else {
            log.debug("No existing PVC volume found with name: ${pvcName}")
        }

        return pvcVolume
    }

    /**
     * Add volumes to a VirtualImage (similar to VMsSync.createVolumesForNewVM)
     * @param virtualImage The VirtualImage to add volumes to
     * @param processedVolumes List of processed volume data
     * @param volumeTypes Map of volume type identifiers to StorageVolumeType objects
     * @param storageControllerTypeMap Map of storage controller types
     */
    private addVolumesToVirtualImageWithTypes(VirtualImage virtualImage, List processedVolumes, 
                                              Map<String, StorageVolumeType> volumeTypes,
                                              Map<String, StorageControllerType> storageControllerTypeMap) {
        if (!processedVolumes) return

        def osType = virtualImage?.osType?.platform?.toString()
        def volumes = []

        processedVolumes.eachWithIndex { vol, i ->
            // Get the appropriate StorageVolumeType for this volume
            StorageVolumeType volType = volumeTypes[vol.volumeType] ?: volumeTypes['default']
            if (!volType) {
                log.warn("No storage volume type found for volume type '${vol.volumeType}', skipping volume: ${vol.name}")
                return
            }

            // Get the StorageControllerType for this volume
            StorageControllerType storageControllerType = storageControllerTypeMap[vol.storageController]
            if (!storageControllerType) {
                log.warn("Skipping volume: ${vol.name} with no supported storage controller: ${vol.storageController}")
                return
            }

            // Find datastore by storage class name
            Datastore datastore = morpheusContext.services.cloud.datastore.find(new DataQuery().withFilters(
                    new DataFilter('name', vol.storageClassName),
                    DBUtils.datastoreFilter(cluster),
            ))

            if (!datastore) {
                log.warn("No datastore found for storageClass '${vol.storageClassName}', skipping volume: ${vol.name}")
                return
            }

            // Use displayOrder from processed volume (which excludes cloud-init disks)
            def displayOrder = vol.displayOrder ?: i as int

            // Convert display order to device name
            def deviceName = convertDisplayOrderToDeviceName(osType, displayOrder)

            // Parse requested storage size
            def maxStorage = parseStorageSize(vol.requestedStorage)

            // Find source StorageVolume by sourcePvcName
            StorageVolume sourceVolume = null
            if (vol.sourcePvcName) {
                sourceVolume = morpheusContext.services.storage.volume.find(new DataQuery().withFilters(
                        new DataFilter('name', vol.sourcePvcName),
                        new DataFilter('refType', HarvesterConstants.DATASTORE),
                        new DataFilter('refId', datastore?.id)
                ))
                if (sourceVolume) {
                    log.info("Found source volume for PVC '${vol.sourcePvcName}': StorageVolume ID ${sourceVolume.id}")
                } else {
                    log.info("No source volume found for PVC name '${vol.sourcePvcName}'")
                }
            }

            // Build config with volume-specific information
            def configMap = [:]
            if (vol.sourceType) {
                configMap.sourceType = vol.sourceType
            }
            if (vol.sourcePvcName) {
                configMap.sourcePvcName = vol.sourcePvcName
            }
            if (vol.sourcePvcNamespace) {
                configMap.sourcePvcNamespace = vol.sourcePvcNamespace
            }
            // Add sourceRef information for DataSource volumes
            if (vol.sourceRefKind) {
                configMap.sourceRefKind = vol.sourceRefKind
            }
            if (vol.sourceRefName) {
                configMap.sourceRefName = vol.sourceRefName
            }
            if (vol.sourceRefNamespace) {
                configMap.sourceRefNamespace = vol.sourceRefNamespace
            }
            if (vol.imageStorageClassName) {
                configMap.imageStorageClassName = vol.imageStorageClassName
            }
            if (vol.imageId) {
                configMap.imageId = vol.imageId
            }
            configMap.clusterId = cluster.id
            configMap.volumeType = vol.volumeType

            // Determine volume name - use name as fallback to prevent null names
            def volumeName = vol.volumeType == HarvesterConstants.VOLUME_TYPE_DATAVOLUME ?
                vol.dataVolumeName : (vol.pvcName ?: vol.dataVolumeName ?: vol.name)

            def newVolume = new StorageVolume(
                    rootVolume: vol.isBootDisk ?: false,
                    type: volType,
                    name: volumeName,
                    maxStorage: maxStorage,
                    deviceDisplayName: deviceName,
                    deviceName: deviceName,
                    displayOrder: displayOrder,
                    unitNumber: i.toString(),
                    config: JsonOutput.toJson(configMap),
                    refType: HarvesterConstants.DATASTORE,
                    refId: datastore?.id,
                    cloudId: cluster.cloud.id
            )

            // Refresh to get controllers (like VM sync does)
            virtualImage = morpheusContext.services.virtualImage.get(virtualImage.id)
            
            // Associate volume with controller (like VM sync)
            StorageController storageController = findStorageControllerByType(virtualImage, storageControllerType)
            newVolume.controller = storageController
            newVolume.datastore = datastore

            // Set source volume if found
            if (sourceVolume) {
                newVolume.sourceId = sourceVolume.id.toString()
            }

            volumes << newVolume
            log.debug("Created volume for VirtualImage: ${newVolume.name} (type: ${volType.code}, controller: ${storageController?.type?.code})")
        }

        if (volumes) {
            log.info("Adding ${volumes.size()} volumes to VirtualImage: ${virtualImage.name}")
            morpheusContext.async.storage.volume.create(volumes, virtualImage).blockingGet()
        }
    }

    /**
     * Update existing volumes for a VirtualImage (similar to VMsSync pattern)
     * @param virtualImage The VirtualImage with existing volumes
     * @param processedVolumes List of processed volume data from cloud
     * @param volumeTypes Map of volume type identifiers to StorageVolumeType objects
     * @param storageControllerTypeMap Map of storage controller types
     */
    private updateVolumesForVirtualImageWithTypes(VirtualImage virtualImage, List processedVolumes, 
                                                   Map<String, StorageVolumeType> volumeTypes,
                                                   Map<String, StorageControllerType> storageControllerTypeMap) {
        if (!processedVolumes) return
        
        def newVolumes = []
        def volumesToUpdate = []

        processedVolumes.eachWithIndex { processedVol, i ->
            // Find existing volume using the same naming logic as volume creation
            def expectedVolumeName = processedVol.volumeType == HarvesterConstants.VOLUME_TYPE_DATAVOLUME ?
                processedVol.dataVolumeName : (processedVol.pvcName ?: processedVol.dataVolumeName ?: processedVol.name)
            def existingVol = virtualImage.volumes?.find { vol -> vol.name == expectedVolumeName }

            // Get the appropriate StorageVolumeType for this volume
            StorageVolumeType volType = volumeTypes[processedVol.volumeType] ?: volumeTypes['default']
            if (!volType) {
                log.warn("No storage volume type found for volume type '${processedVol.volumeType}', skipping volume: ${processedVol.name}")
                return
            }

            // Get the StorageControllerType for this volume
            StorageControllerType storageControllerType = storageControllerTypeMap[processedVol.storageController]
            if (!storageControllerType) {
                log.warn("Skipping volume: ${processedVol.name} with no supported storage controller: ${processedVol.storageController}")
                return
            }

            def osType = virtualImage?.osType?.platform?.toString()
            if (existingVol) {
                // Update existing volume with proper volume type and controller
                def updated = updateExistingVolumeWithType(existingVol, processedVol, volType, storageControllerType, virtualImage, i)
                if (updated) {
                    volumesToUpdate << existingVol
                }
            } else {
                // Add new volume
                Datastore datastore = morpheusContext.services.cloud.datastore.find(new DataQuery().withFilters(
                        new DataFilter('name', processedVol.storageClassName),
                        DBUtils.datastoreFilter(cluster),
                ))

                if (!datastore) {
                    log.warn("No datastore found for storageClass '${processedVol.storageClassName}', skipping volume: ${processedVol.name}")
                    return
                }

                def displayOrder = processedVol.displayOrder ?: i as int
                def maxStorage = parseStorageSize(processedVol.requestedStorage)
                def deviceName = convertDisplayOrderToDeviceName(osType, displayOrder)

                // Find source StorageVolume by sourcePvcName
                StorageVolume sourceVolume = null
                if (processedVol.sourcePvcName) {
                    sourceVolume = morpheusContext.services.storage.volume.find(new DataQuery().withFilters(
                            new DataFilter('name', processedVol.sourcePvcName),
                            new DataFilter('refType', HarvesterConstants.DATASTORE),
                            new DataFilter('refId', datastore.id)
                    ))
                    if (sourceVolume) {
                        log.debug("Found source volume for PVC '${processedVol.sourcePvcName}': StorageVolume ID ${sourceVolume.id}")
                    }
                }

                // Build config
                def configMap = [:]
                if (processedVol.sourceType) {
                    configMap.sourceType = processedVol.sourceType
                }
                if (processedVol.sourcePvcName) {
                    configMap.sourcePvcName = processedVol.sourcePvcName
                }
                if (processedVol.sourcePvcNamespace) {
                    configMap.sourcePvcNamespace = processedVol.sourcePvcNamespace
                }
                if (processedVol.sourceRefKind) {
                    configMap.sourceRefKind = processedVol.sourceRefKind
                }
                if (processedVol.sourceRefName) {
                    configMap.sourceRefName = processedVol.sourceRefName
                }
                if (processedVol.sourceRefNamespace) {
                    configMap.sourceRefNamespace = processedVol.sourceRefNamespace
                }
                if (processedVol.imageStorageClassName) {
                    configMap.imageStorageClassName = processedVol.imageStorageClassName
                }
                if (processedVol.imageId) {
                    configMap.imageId = processedVol.imageId
                }
                configMap.clusterId = cluster.id
                configMap.volumeType = processedVol.volumeType

                // Determine volume name
                def volumeName = processedVol.volumeType == HarvesterConstants.VOLUME_TYPE_DATAVOLUME ?
                    processedVol.dataVolumeName : (processedVol.pvcName ?: processedVol.dataVolumeName)

                def newVolume = new StorageVolume(
                        rootVolume: processedVol.isBootDisk ?: false,
                        type: volType,
                        name: volumeName,
                        maxStorage: maxStorage,
                        deviceName: deviceName,
                        deviceDisplayName: deviceName,
                        unitNumber: i.toString(),
                        displayOrder: displayOrder,
                        config: JsonOutput.toJson(configMap),
                        refType: HarvesterConstants.DATASTORE,
                        refId: datastore.id,
                        cloudId: cluster.cloud.id
                )

                // Refresh to get controllers (like VM sync does)
                virtualImage = morpheusContext.services.virtualImage.get(virtualImage.id)
                
                // Associate volume with controller (like VM sync)
                StorageController storageController = findStorageControllerByType(virtualImage, storageControllerType)
                newVolume.controller = storageController
                newVolume.datastore = datastore

                // Set source volume if found
                if (sourceVolume) {
                    newVolume.sourceId = sourceVolume.id.toString()
                }

                newVolumes << newVolume
                log.debug("Created new volume for VirtualImage: ${processedVol.dataVolumeName} (type: ${volType.code}, controller: ${storageController?.type?.code})")
            }
        }

        if (newVolumes) {
            log.info("Adding ${newVolumes.size()} new volumes to VirtualImage: ${virtualImage.name}")
            morpheusContext.async.storage.volume.create(newVolumes, virtualImage).blockingGet()
        }

        if (volumesToUpdate) {
            log.info("Updating ${volumesToUpdate.size()} existing volumes for VirtualImage: ${virtualImage.name}")
            morpheusContext.services.storage.volume.bulkSave(volumesToUpdate)
        }
    }

    /**
     * Update properties of an existing volume with proper volume type detection
     * @return true if volume was updated, false otherwise
     */
    /**
     * Update existing volume with type and controller (like VM sync)
     * @param existingVol The existing StorageVolume to update
     * @param processedVol The processed volume data from cloud
     * @param volType The StorageVolumeType to use
     * @param storageControllerType The StorageControllerType for this volume
     * @param virtualImage The VirtualImage this volume belongs to
     * @param index The index position for display order
     * @return true if volume was updated
     */
    private boolean updateExistingVolumeWithType(StorageVolume existingVol, Map processedVol, StorageVolumeType volType, 
                                                  StorageControllerType storageControllerType, VirtualImage virtualImage, int index) {
        def saveVol = false
        def osType = virtualImage?.osType?.platform?.toString()

        // Todo: find storage class name for pvc as well else datastore find will be null
        // WARNING: PVC volumes may not have storageClassName, causing datastore lookup to fail
        // Find datastore for template volume
        Datastore datastore = morpheusContext.services.cloud.datastore.find(new DataQuery().withFilters(
                new DataFilter('name', processedVol.storageClassName),
                DBUtils.datastoreFilter(cluster),
        ))

        // WARNING: If datastore is null, source volume lookup will fail and refId updates will be skipped
        // Todo: will fix this by seeing how to find datastore for pvc volumes
        if (!datastore && processedVol.storageClassName) {
            log.warn("No datastore found for storageClassName '${processedVol.storageClassName}' - this may cause issues with PVC volumes")
        }

        // Parse requested storage size
        def maxStorage = parseStorageSize(processedVol.requestedStorage)
        def displayOrder = processedVol.displayOrder ?: index as int

        // Convert display order to device name
        def expectedDeviceName = convertDisplayOrderToDeviceName(osType, displayOrder)

        // Find source StorageVolume by sourcePvcName
        StorageVolume sourceVolume = null
        if (processedVol.sourcePvcName) {
            if (datastore) {
                sourceVolume = morpheusContext.services.storage.volume.find(new DataQuery().withFilters(
                        new DataFilter('name', processedVol.sourcePvcName),
                        new DataFilter('refType', HarvesterConstants.DATASTORE),
                        new DataFilter('refId', datastore.id)
                ))
                if (sourceVolume) {
                    log.debug("Found source volume for PVC '${processedVol.sourcePvcName}': StorageVolume ID ${sourceVolume.id}")
                } else {
                    log.debug("No source volume found for PVC name '${processedVol.sourcePvcName}'")
                }
            } else {
                log.warn("Cannot lookup source volume '${processedVol.sourcePvcName}' - datastore is null")
            }
        }

        // Update StorageVolumeType if it has changed
        if (existingVol.type?.id != volType?.id) {
            existingVol.type = volType
            saveVol = true
            log.debug("Updated volume type for '${existingVol.name}' to '${volType.code}'")
        }

        // Update controller if it has changed (like VM sync)
        StorageController storageController = findStorageControllerByType(virtualImage, storageControllerType)
        if (existingVol.controller?.id != storageController?.id) {
            existingVol.controller = storageController
            saveVol = true
            log.debug("Updated controller for '${existingVol.name}' to '${storageController?.type?.code}'")
        }

        // Update volume name based on volume type - handle PVC vs DataVolume naming
        def expectedVolumeName = processedVol.volumeType == HarvesterConstants.VOLUME_TYPE_DATAVOLUME ?
            processedVol.dataVolumeName : (processedVol.pvcName ?: processedVol.dataVolumeName ?: processedVol.name)

        if (existingVol.name != expectedVolumeName) {
            existingVol.name = expectedVolumeName
            saveVol = true
            log.debug("Updated volume name for volume ID ${existingVol.id} from '${existingVol.name}' to '${expectedVolumeName}'")
        }

        // Check and update volume properties
        if (existingVol.maxStorage != maxStorage) {
            existingVol.maxStorage = maxStorage
            saveVol = true
        }

        if (existingVol.datastore?.id != datastore?.id) {
            existingVol.datastore = datastore
            saveVol = true
        }

        if (existingVol.displayOrder != displayOrder) {
            existingVol.displayOrder = displayOrder
            saveVol = true
        }

        // Update device names based on display order
        if (existingVol.deviceName != expectedDeviceName) {
            existingVol.deviceName = expectedDeviceName
            saveVol = true
        }

        if (existingVol.deviceDisplayName != expectedDeviceName) {
            existingVol.deviceDisplayName = expectedDeviceName
            saveVol = true
        }

        // Update unitNumber based on index (auto-incrementing starting from 0)
        def expectedUnitNumber = index.toString()
        if (existingVol.unitNumber != expectedUnitNumber) {
            existingVol.unitNumber = expectedUnitNumber
            saveVol = true
            log.debug("Updated unitNumber for volume '${existingVol.name}' to '${expectedUnitNumber}'")
        }

        // Update source storage volume reference
        if (existingVol.sourceId != sourceVolume?.id?.toString()) {
            existingVol.sourceId = sourceVolume?.id?.toString()
            saveVol = true
        }

        if (!existingVol.cloudId || existingVol.cloudId != cluster.cloud.id) {
            existingVol.cloudId = cluster.cloud.id
            saveVol = true
        }

        if (!existingVol.refType || existingVol.refType != HarvesterConstants.DATASTORE) {
            existingVol.refType = HarvesterConstants.DATASTORE
            saveVol = true
        }

        if (!existingVol.refId || existingVol.refId != datastore?.id) {
            existingVol.refId = datastore?.id
            saveVol = true
        }

        // Update config with volume-specific information
        def newConfigMap = [:]
        if (processedVol.sourceType) {
            newConfigMap.sourceType = processedVol.sourceType
        }
        if (processedVol.sourcePvcName) {
            newConfigMap.sourcePvcName = processedVol.sourcePvcName
        }
        if (processedVol.sourcePvcNamespace) {
            newConfigMap.sourcePvcNamespace = processedVol.sourcePvcNamespace
        }
        // Add sourceRef information for DataSource volumes
        if (processedVol.sourceRefKind) {
            newConfigMap.sourceRefKind = processedVol.sourceRefKind
        }
        if (processedVol.sourceRefName) {
            newConfigMap.sourceRefName = processedVol.sourceRefName
        }
        if (processedVol.sourceRefNamespace) {
            newConfigMap.sourceRefNamespace = processedVol.sourceRefNamespace
        }
        if (processedVol.imageStorageClassName) {
            newConfigMap.imageStorageClassName = processedVol.imageStorageClassName
        }
        if (processedVol.imageId) {
            newConfigMap.imageId = processedVol.imageId
        }
        newConfigMap.clusterId = cluster.id
        newConfigMap.volumeType = processedVol.volumeType // Store the detected volume type

        // Check if config has changed - handle potential JSON parsing errors gracefully
        def currentConfig = [:]
        try {
            currentConfig = existingVol.getConfigMap() ?: [:]
        } catch (Exception e) {
            log.warn("Failed to parse existing config for volume ${existingVol.id}: ${e.message}. Will update with new config.")
            currentConfig = [:]
        }

        if (currentConfig != newConfigMap) {
            existingVol.setConfigMap(newConfigMap)
            saveVol = true
        }

        if (saveVol) {
            log.debug("Updated volume ${existingVol.id}: ${existingVol.name} (type: ${volType.code})")
        }

        return saveVol
    }

    /**
     * Find storage controller by type for VirtualImage
     * Since VirtualImage.controllers is a list of identity projections, we need to query the database
     * to get the full StorageController with the type information
     * @param virtualImage The VirtualImage containing controllers
     * @param storageControllerType The StorageControllerType to find
     * @return The matching StorageController or null if not found
     */
    private StorageController findStorageControllerByType(VirtualImage virtualImage, StorageControllerType storageControllerType) {
        try {
            // Build the uniqueId for this controller type on this virtual image
            String uniqueId = "${storageControllerType.code}.image.${virtualImage.id}"
            
            // Query database to get the full StorageController object with type information
            StorageController storageController = morpheusContext.services.storage.controller.find(
                    new DataQuery().withFilters(
                            new DataFilter('uniqueId', uniqueId)
                    )
            )
            
            if (!storageController) {
                log.warn("Harvester: No storage controller found for type: ${storageControllerType.code} (uniqueId: ${uniqueId}) on VirtualImage: ${virtualImage.name}")
            }
            
            return storageController
        } catch(e) {
            log.error("Harvester: get storage controller error for VirtualImage: ${e}", e)
            return null
        }
    }

    /**
     * Convert display order to device name (/dev/vda, /dev/vdb, etc.)
     * @param displayOrder The display order (0-based integer)
     * @return Device name like /dev/vda, /dev/vdb, etc.
     */
    private static String convertDisplayOrderToDeviceName(String osType, int displayOrder) {
        if (displayOrder < 0 || displayOrder > 25) {
            log.warn("Display order ${displayOrder} is out of valid range (0-25), defaulting to /dev/vda")
            if (osType?.toLowerCase()?.contains('windows')) {
                return 'disk 1'
            }
            return '/dev/vda'
        }

        // Use displayOrder directly since it's already 0-based
        char deviceLetter = (char) (97 + displayOrder)  // 97 is ASCII value of 'a'
        if (osType?.toLowerCase()?.contains('windows')) {
            def deviceName = "disk ${displayOrder + 1}"
            log.info("Windows OS detected, setting deviceName ${deviceName}")
            return deviceName
        }
        return "/dev/vd${deviceLetter}"
    }

    /**
     * Parse storage size from Kubernetes format (e.g., "100Gi", "30Gi") to bytes
     */
    private parseStorageSize(String storageString) {
        if (!storageString) return 0

        try {
            return DataUnitConverterUtil.parseKubernetesUnitsToBytes(storageString)
        } catch (Exception e) {
            log.warn("Failed to parse storage size '${storageString}': ${e.message}")
            return 0
        }
    }

    /**
     * Get storage volume types for different Harvester volume types (similar to VM sync pattern)
     * @return Map of volume type identifiers to StorageVolumeType objects
     */
    private Map<String, StorageVolumeType> getStorageVolumeTypes() {
        Map<String, StorageVolumeType> volumeTypes = [:]

        try {
            // Get Harvester-specific storage volume types
            StorageVolumeType pvcType = morpheusContext.services.storage.volume.storageVolumeType.find(
                    new DataQuery().withFilter('code', HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC))

            StorageVolumeType dataVolumeType = morpheusContext.services.storage.volume.storageVolumeType.find(
                    new DataQuery().withFilter('code', HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME))

            StorageVolumeType dataSourceType = morpheusContext.services.storage.volume.storageVolumeType.find(
                    new DataQuery().withFilter('code', HarvesterConstants.VOLUME_TYPE_HARVESTER_DATASOURCE))

            volumeTypes[HarvesterConstants.VOLUME_TYPE_PVC] = pvcType
            volumeTypes[HarvesterConstants.VOLUME_TYPE_DATAVOLUME] = dataVolumeType
            volumeTypes[HarvesterConstants.VOLUME_TYPE_DATASOURCE] = dataSourceType
            volumeTypes['default'] = dataVolumeType // Default fallback

            log.info("Loaded storage volume types: PVC=${volumeTypes[HarvesterConstants.VOLUME_TYPE_PVC]?.code}, " +
                     "DataVolume=${volumeTypes[HarvesterConstants.VOLUME_TYPE_DATAVOLUME]?.code}, " +
                     "DataSource=${volumeTypes[HarvesterConstants.VOLUME_TYPE_DATASOURCE]?.code}, " +
                     "Default=${volumeTypes['default']?.code}")

        } catch (Exception e) {
            log.error("Error loading storage volume types: ${e.message}", e)
            // Fallback to empty map - will cause volume sync to skip
            volumeTypes = [:]
        }

        return volumeTypes
    }

    /**
     * Process volume details from TemplateParser.extractDataVolumeDetails for sync operations
     * @param volumeDetails List of volume details from TemplateParser.extractDataVolumeDetails
     * @return List of processed volume objects suitable for syncVolumesForVirtualImage
     */
    private List processVolumeDetailsForSync(List volumeDetails) {
        if (!volumeDetails) {
            return []
        }

        log.debug("Processing ${volumeDetails.size()} volume details for sync")

        List processedVolumes = []

        volumeDetails.each { volumeDetail ->
            // Skip cloud-init volumes
            if (volumeDetail.type == HarvesterConstants.VOLUME_TYPE_CLOUDINIT) {
                log.debug("Filtered out cloud-init volume: ${volumeDetail.diskName}")
                return
            }

            // Map busType to storageController type (KubeVirt uses virtio, scsi, sata, etc.)
            def storageController = mapBusTypeToStorageController(volumeDetail.busType)

            // Convert TemplateParser volume detail format to sync format
            def processedVolume = [
                    name: volumeDetail.diskName,
                    dataVolumeName: volumeDetail.dataVolumeName,
                    volumeType: volumeDetail.type,
                    sourceType: volumeDetail.sourceType,
                    sourcePvcName: volumeDetail.sourcePvcName,
                    sourcePvcNamespace: volumeDetail.sourcePvcNamespace,
                    pvcName: volumeDetail.pvcName,
                    // for dataSource volumes, store the sourceRef information in the config for volume creation/updates
                    sourceRefKind: volumeDetail.sourceRefKind,
                    sourceRefName: volumeDetail.sourceRefName,
                    sourceRefNamespace: volumeDetail.sourceRefNamespace,
                    storageClassName: volumeDetail.storageClassName,
                    imageStorageClassName: volumeDetail.imageStorageClassName,
                    imageId: volumeDetail.imageId,
                    requestedStorage: volumeDetail.requestedStorage,
                    displayOrder: volumeDetail.diskIndex,  // Will be reassigned below
                    isBootDisk: volumeDetail.isBootDisk ?: false,
                    storageController: storageController
            ]

            // treat it as a DataSource volume for storage volume type assignment
            if (processedVolume.sourceType == HarvesterConstants.VOLUME_TYPE_DATASOURCE) {
                processedVolume.volumeType = HarvesterConstants.VOLUME_TYPE_DATASOURCE
                log.debug("Corrected volume type from dataVolume to dataSource for volume '${processedVolume.name}' (sourced from DataSource)")
            }

            // For PVC volumes without storage class, try to find it using sourcePvcName
            if (processedVolume.volumeType == HarvesterConstants.VOLUME_TYPE_PVC &&
                (processedVolume.storageClassName == null || processedVolume.storageClassName == '') &&
                processedVolume.sourcePvcName) {

                log.debug("PVC volume '${processedVolume.name}' missing storageClassName, looking up via sourcePvcName: ${processedVolume.sourcePvcName}")

                def sourcePvc = findPvcByName(processedVolume.sourcePvcName)
                if (sourcePvc?.datastore?.name) {
                    processedVolume.storageClassName = sourcePvc.datastore.name
                    log.info("Found storage class '${processedVolume.storageClassName}' for PVC volume '${processedVolume.name}' via sourcePvcName")
                } else {
                    log.warn("Could not find storage class for PVC volume '${processedVolume.name}' via sourcePvcName: ${processedVolume.sourcePvcName}")
                }
            }

            processedVolumes << processedVolume
            log.debug("Processed volume for sync: ${processedVolume.name} (type: ${processedVolume.volumeType}, controller: ${storageController})")
        }

        // Sort by boot disk first, then by boot order, then by original disk index
        processedVolumes.sort { a, b ->
            // Boot disk comes first
            if (a.isBootDisk && !b.isBootDisk) return -1
            if (!a.isBootDisk && b.isBootDisk) return 1

            // If both or neither are boot disks, sort by original disk index
            return a.displayOrder <=> b.displayOrder
        }

        // Reassign displayOrder to be sequential after filtering and sorting
        processedVolumes.eachWithIndex { volume, index ->
            volume.displayOrder = index
            log.debug("Reassigned displayOrder ${index} to volume: ${volume.name}")
        }

        log.debug("Processed ${processedVolumes.size()} volumes for sync (filtered out ${volumeDetails.size() - processedVolumes.size()} cloud-init volumes)")
        return processedVolumes
    }

    /**
     * Synchronizes VirtualImageLocation for a VirtualImage
     * Creates or updates a single location record associated with the cluster
     * @param virtualImage The VirtualImage to sync location for
     * @param template The template data from Harvester
     */
    private void syncImageLocationForVirtualImage(VirtualImage virtualImage, Map template) {
        log.debug("Syncing image location for VirtualImage: ${virtualImage.name}")

        try {
            // Generate a unique code for this location
            def locationCode = "harvester.image.location.${cluster.id}.${virtualImage.externalId}"

            // Check if location already exists
            def existingLocation = morpheusContext.services.virtualImage.location.find(
                new DataQuery().withFilters(
                    new DataFilter('code', locationCode),
                    new DataFilter('virtualImage.id', virtualImage.id)
                )
            )

            if (existingLocation) {
                // Update existing location
                def updated = updateImageLocation(existingLocation, template)
                if (updated) {
                    morpheusContext.services.virtualImage.location.save(existingLocation)
                    log.debug("Updated image location for VirtualImage: ${virtualImage.name}")
                }
            } else {
                // Create new location
                def newLocation = createImageLocation(virtualImage, template, locationCode)

                // Create the location using the correct API signature (location, cloud)
                def createdLocation = morpheusContext.services.virtualImage.location.create(newLocation, cluster.cloud)

                if (createdLocation) {
                    // Add the location to the virtual image's imageLocations list
                    if (!virtualImage.imageLocations) {
                        virtualImage.imageLocations = []
                    }
                    virtualImage.imageLocations.add(createdLocation)

                    // Save the updated virtual image with the new location reference
                    morpheusContext.services.virtualImage.save(virtualImage)

                    log.debug("Created and linked image location for VirtualImage: ${virtualImage.name}")
                } else {
                    log.error("Failed to create image location for VirtualImage: ${virtualImage.name}")
                }
            }
        } catch (Exception e) {
            log.error("Error syncing image location for VirtualImage ${virtualImage.name}: ${e.message}", e)
        }
    }

    /**
     * Create a new VirtualImageLocation for a VirtualImage
     * @param virtualImage The VirtualImage to link this location to
     * @param template The template data from Harvester
     * @param locationCode Unique code for this location
     * @return New VirtualImageLocation instance
     */
    private VirtualImageLocation createImageLocation(VirtualImage virtualImage, Map template, String locationCode) {
        def location = new VirtualImageLocation()

        // Link to the virtual image
        location.virtualImage = virtualImage

        // Core identification
        location.code = locationCode
        location.internalId = template.uid
        location.externalId = template.uid
        location.uuid = template.uid

        // Reference back to cluster (ComputeServerGroup)
        location.refType = HarvesterConstants.COMPUTE_SERVER_GROUP
        location.refId = cluster.id

        // Region and folder information
        location.imageRegion = cluster.cloud?.regionCode ?: cluster.cloud?.name
        location.imageFolder = template.namespace

        location.systemImage = false

        // Owner information
        location.owner = cluster.owner?.id?.toString()

        log.debug("Created VirtualImageLocation with code: ${locationCode}, region: ${location.imageRegion}, folder: ${location.imageFolder}")

        return location
    }

    /**
     * Update an existing VirtualImageLocation
     * @param location Existing VirtualImageLocation to update
     * @param template The template data from Harvester
     * @return true if location was updated, false otherwise
     */
    private boolean updateImageLocation(VirtualImageLocation location, Map template) {
        def updated = false

        // Update internalId if changed
        if (location.internalId != template.uid) {
            location.internalId = template.uid
            updated = true
        }

        // Update externalId if changed
        if (location.externalId != template.uid) {
            location.externalId = template.uid
            updated = true
        }

        // Update uuid if changed
        if (location.uuid != template.uid) {
            location.uuid = template.uid
            updated = true
        }

        // Update refType if changed
        if (location.refType != HarvesterConstants.COMPUTE_SERVER_GROUP) {
            location.refType = HarvesterConstants.COMPUTE_SERVER_GROUP
            updated = true
        }

        // Update refId if changed
        if (location.refId != cluster.id) {
            location.refId = cluster.id
            updated = true
        }

        // Update region
        def expectedRegion = cluster.cloud?.regionCode ?: cluster.cloud?.name
        if (location.imageRegion != expectedRegion) {
            location.imageRegion = expectedRegion
            updated = true
        }

        // Update folder (namespace)
        if (location.imageFolder != template.namespace) {
            location.imageFolder = template.namespace
            updated = true
        }

        // Update system image flag
        if (location.systemImage != false) {
            location.systemImage = false
            updated = true
        }

        // Update owner
        def expectedOwner = cluster.owner?.id?.toString()
        if (location.owner != expectedOwner) {
            location.owner = expectedOwner
            updated = true
        }

        if (updated) {
            log.debug("Updated VirtualImageLocation: ${location.code}")
        }

        return updated
    }


    /**
     * Map KubeVirt/Harvester busType to Morpheus storage controller type
     * @param busType The bus type from KubeVirt disk configuration (e.g., "virtio", "scsi", "sata")
     * @return Storage controller type code with 'harvester-' prefix
     */
    private static String mapBusTypeToStorageController(String busType) {
        if (!busType) {
            log.debug("No busType provided, defaulting to '${HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO}'")
            return HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO
        }

        def bus = busType.toLowerCase().trim()

        // Map KubeVirt bus types to Morpheus storage controller types using constants
        switch (bus) {
            case 'virtio':
                return HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO
            case 'scsi':
                return HarvesterConstants.STORAGE_CONTROLLER_TYPE_SCSI
            case 'sata':
                return HarvesterConstants.STORAGE_CONTROLLER_TYPE_SATA
            case 'ide':
                return HarvesterConstants.STORAGE_CONTROLLER_TYPE_IDE
            default:
                log.debug("Unknown busType '${busType}', defaulting to '${HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO}'")
                return HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO  // Default to harvester-virtio for unknown bus types
        }
    }

    /**
     * Synchronizes storage controllers for a VirtualImage based on volume bus types
     * Handles add, update, and remove operations similar to volume sync
     * @param vmVolumes List of volumes from the template
     * @param storageControllerTypeMap Map of controller type codes to StorageControllerType objects
     * @param virtualImage The VirtualImage to sync controllers for
     * @return Map containing removeList of controllers to be removed
     */
    private syncStorageControllers(
            List vmVolumes,
            Map<String, StorageControllerType> storageControllerTypeMap,
            VirtualImage virtualImage) {
        def result = ["removeList": []]

        if (!vmVolumes) {
            log.debug("No volumes provided for VirtualImage: ${virtualImage.name}, skipping controller sync")
            return result
        }

        log.debug("Syncing storage controllers for VirtualImage: ${virtualImage.name} with ${vmVolumes.size()} volumes")

        // Extract unique controller types from volumes based on their bus information
        Set<String> requiredControllerTypes = new HashSet<String>()

        vmVolumes.each { vol ->
            def storageControllerValue = vol.storageController

            if (storageControllerValue) {
                String storageController = storageControllerValue.toString()

                if (storageControllerTypeMap.containsKey(storageController)) {
                    requiredControllerTypes.add(storageController)
                    log.debug("Added required controller type: ${storageController} for VirtualImage: ${virtualImage.name}")
                } else {
                    log.warn("Storage controller type '${storageController}' not found in available types: ${storageControllerTypeMap.keySet()}")
                }
            } else {
                log.warn("Volume ${vol.name} has no storageController value!")
            }
        }

        log.debug("Extracted required controller types for VirtualImage ${virtualImage.name}: ${requiredControllerTypes}")

        List<StorageController> controllersToAdd = []
        List<StorageController> controllersToUpdate = []

        // Process each required controller type
        requiredControllerTypes.each { requiredController ->
            String uniqueId = "${requiredController}.image.${virtualImage.id}"

            // Query database directly to check if controller already exists by uniqueId
            def existingController = morpheusContext.services.storage.controller.find(
                    new DataQuery().withFilters(
                            new DataFilter('uniqueId', uniqueId),
                    )
            )
            log.debug("Query for existing controller with uniqueId '${uniqueId}' returned ${existingController ? 'found' : 'null'}")

            if (!existingController) {
                // Create new controller
                StorageControllerType controllerType = storageControllerTypeMap[requiredController]
                if (controllerType) {
                    StorageController newController = new StorageController(
                            type: controllerType,
                            name: controllerType.name,
                            active: true,
                            busNumber: '0',
                            unitNumber: '0',
                            displayOrder: controllerType.displayOrder,
                            description: controllerType.description,
                            uniqueId: uniqueId,
                    )
                    controllersToAdd << newController
                    log.info("Will create storage controller: ${requiredController} for VirtualImage: ${virtualImage.name}")
                } else {
                    log.warn("Storage controller type not found for code: ${requiredController}, VirtualImage: ${virtualImage.name}")
                }
            } else {
                // Update existing controller if properties changed
                def updated = updateExistingController(existingController, storageControllerTypeMap[requiredController])
                if (updated) {
                    controllersToUpdate << existingController
                    log.info("Will update storage controller: ${requiredController} (id: ${existingController.id}) for VirtualImage: ${virtualImage.name}")
                } else {
                    log.debug("Storage controller ${requiredController} (id: ${existingController.id}) is up to date for VirtualImage: ${virtualImage.name}")
                }
            }
        }

        // Add new controllers to the virtual image BEFORE checking for obsolete ones
        if (controllersToAdd) {
            log.info("Creating ${controllersToAdd.size()} storage controllers for VirtualImage: ${virtualImage.name}")
            morpheusContext.async.storage.controller.create(controllersToAdd, virtualImage).blockingGet()
        }

        // Update existing controllers
        if (controllersToUpdate) {
            log.info("Updating ${controllersToUpdate.size()} storage controllers for VirtualImage: ${virtualImage.name}")
            morpheusContext.async.storage.controller.bulkSave(controllersToUpdate).blockingGet()
        }

        // Refresh virtualImage to get updated controllers list after create/update
        if (controllersToAdd || controllersToUpdate) {
            virtualImage = morpheusContext.services.virtualImage.get(virtualImage.id)
        }

        // Query all existing controllers for this virtual image to identify obsolete ones
        // We need to query the database to get full StorageController objects with uniqueId
        def existingControllerIds = virtualImage.controllers?.collect { it.id }
        List<StorageController> existingControllers = []
        
        if (existingControllerIds) {
            existingControllers = morpheusContext.services.storage.controller.list(
                    new DataQuery().withFilters(
                            new DataFilter('id', 'in', existingControllerIds)
                    )
            )
        }

        log.debug("Found ${existingControllers.size()} existing controllers for VirtualImage: ${virtualImage.name}")

        // Identify controllers to remove (those not in required types)
        def requiredUniqueIds = requiredControllerTypes.collect { "${it}.image.${virtualImage.id}" } as Set<String>

        def controllersToRemove = existingControllers.findAll { controller ->
            // Use string comparison to avoid type issues with LinkedHashSet
            boolean shouldRemove = !requiredUniqueIds.any { it.toString() == controller.uniqueId?.toString() }
            if (shouldRemove) {
                log.info("Controller marked for removal - ID: ${controller.id}, uniqueId: ${controller.uniqueId}, type: ${controller.type?.code}")
            }
            return shouldRemove
        }

        if (controllersToRemove) {
            result.removeList = controllersToRemove
            log.info("Identified ${controllersToRemove.size()} obsolete controllers for removal from VirtualImage: ${virtualImage.name}")
        }

        if (!controllersToAdd && !controllersToUpdate && !controllersToRemove) {
            log.debug("No controller changes needed for VirtualImage: ${virtualImage.name}")
        }

        return result
    }

    /**
     * Update properties of an existing storage controller
     * @param existingController The existing controller to update
     * @param controllerType The target controller type
     * @return true if controller was updated, false otherwise
     */
    private boolean updateExistingController(StorageController existingController, StorageControllerType controllerType) {
        if (!controllerType) {
            return false
        }

        def updated = false

        // Update controller type if changed
        if (existingController.type?.id != controllerType.id) {
            existingController.type = controllerType
            updated = true
            log.debug("Updated controller type for controller ${existingController.id}")
        }

        // Update name if changed
        if (existingController.name != controllerType.name) {
            existingController.name = controllerType.name
            updated = true
            log.debug("Updated controller name for controller ${existingController.id}")
        }

        // Update display order if changed
        if (existingController.displayOrder != controllerType.displayOrder) {
            existingController.displayOrder = controllerType.displayOrder
            updated = true
            log.debug("Updated controller displayOrder for controller ${existingController.id}")
        }

        // Update description if changed
        if (existingController.description != controllerType.description) {
            existingController.description = controllerType.description
            updated = true
            log.debug("Updated controller description for controller ${existingController.id}")
        }

        // Ensure active is true
        if (existingController.active != true) {
            existingController.active = true
            updated = true
            log.debug("Updated controller active status for controller ${existingController.id}")
        }

        // Ensure busNumber is set (default to '0' if null)
        if (existingController.busNumber == null) {
            existingController.busNumber = '0'
            updated = true
            log.debug("Updated controller busNumber to '0' for controller ${existingController.id}")
        }

        // Ensure unitNumber is set (default to '0' if null)
        if (existingController.unitNumber == null) {
            existingController.unitNumber = '0'
            updated = true
            log.debug("Updated controller unitNumber to '0' for controller ${existingController.id}")
        }

        return updated
    }
}
