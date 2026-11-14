// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin

import com.hpe.morpheus.harvester.plugin.apihelper.ClusterHealthApiHelper
import com.hpe.morpheus.harvester.plugin.apihelper.CommonApiHelper
import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.hpe.morpheus.harvester.plugin.provision.VirtualMachineDeletion
import com.hpe.morpheus.harvester.plugin.provision.VirtualMachineReconfigure
import com.hpe.morpheus.harvester.plugin.sync.HarvesterInventorySync
import com.hpe.morpheus.harvester.plugin.utils.DBUtils
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.CloudProvider
import com.morpheusdata.core.providers.ProvisionProvider
import com.morpheusdata.model.BackupProvider
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.ComputeServerGroupType
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.NetworkSubnetType
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageControllerType
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.request.ValidateCloudRequest
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

/**
 * Harvester as a Morpheus cloud (Infrastructure > Clouds).
 *
 * The cloud holds the Harvester API URL and credentials: a Harvester API key, or a Harvester username and password.
 * The plugin's sync and provisioning code works against a Harvester connection record (a ComputeServerGroup of type
 * harvester-cluster), so each cloud keeps one such record, created and updated by this provider and flagged with
 * {@link HarvesterConstants#CONFIG_MANAGED_BY_CLOUD}. Nodes, namespaces (resource pools), storage classes
 * (datastores), images, networks and VMs all sync into this cloud.
 */
@Slf4j
class HpeHarvesterCloudProvider implements CloudProvider {

    static final String CONFIG_API_KEY = 'apiKey'
    static final String CONFIG_USERNAME = 'username'
    static final String CONFIG_PASSWORD = 'password'
    static final String CONFIG_IMPORT_EXISTING = 'importExisting'

    protected MorpheusContext context
    protected HpeHarvesterPlugin plugin

    HpeHarvesterCloudProvider(HpeHarvesterPlugin plugin, MorpheusContext ctx) {
        super()
        this.plugin = plugin
        this.context = ctx
    }

    @Override
    MorpheusContext getMorpheus() {
        return this.@context
    }

    @Override
    Plugin getPlugin() {
        return this.@plugin
    }

    @Override
    String getCode() {
        return HarvesterConstants.CLOUD_PROVIDER_CODE
    }

    @Override
    String getName() {
        return HarvesterConstants.CLOUD_PROVIDER_NAME
    }

    @Override
    String getDescription() {
        return HarvesterConstants.CLOUD_PROVIDER_DESCRIPTION
    }

    @Override
    Icon getIcon() {
        return new Icon(path: 'harvester-logo.svg', darkPath: 'harvester-logo-dark.svg')
    }

    @Override
    Icon getCircularIcon() {
        return new Icon(path: 'harvester-circular.svg', darkPath: 'harvester-circular.svg')
    }

    @Override
    Collection<OptionType> getOptionTypes() {
        Collection<OptionType> options = []
        options << new OptionType(
                name: 'API URL',
                code: 'harvester-cloud-api-url',
                displayOrder: 0,
                fieldContext: 'domain',
                fieldName: 'serviceUrl',
                fieldLabel: 'API URL',
                inputType: OptionType.InputType.TEXT,
                required: true,
                placeHolderText: 'https://harvester.example.com',
                helpText: 'Address of the Harvester cluster (its VIP or hostname), for example https://10.0.0.10. ' +
                        'The plugin calls the Harvester API at /k8s/clusters/local on that address.'
        )
        options << new OptionType(
                name: 'API Key',
                code: 'harvester-cloud-api-key',
                displayOrder: 10,
                fieldContext: 'config',
                fieldName: CONFIG_API_KEY,
                fieldLabel: 'API Key',
                inputType: OptionType.InputType.PASSWORD,
                required: false,
                helpText: 'Bearer token of a Harvester API key (Harvester UI > avatar > Account & API Keys), ' +
                        'for example token-abc12:xyz. Leave blank to sign in with a username and password instead.'
        )
        options << new OptionType(
                code: 'harvester-cloud-credential',
                name: 'Credentials',
                displayOrder: 20,
                fieldContext: 'credential',
                fieldName: 'type',
                fieldLabel: 'Credentials',
                inputType: OptionType.InputType.CREDENTIAL,
                required: false,
                defaultValue: 'local',
                optionSource: 'credentials',
                config: '{"credentialTypes":["username-password"]}'
        )
        options << new OptionType(
                name: 'Username',
                code: 'harvester-cloud-username',
                displayOrder: 30,
                fieldContext: 'domain',
                fieldName: 'serviceUsername',
                fieldLabel: 'Username',
                inputType: OptionType.InputType.TEXT,
                localCredential: true,
                required: false,
                helpText: 'Harvester user, used when no API key is set'
        )
        options << new OptionType(
                name: 'Password',
                code: 'harvester-cloud-password',
                displayOrder: 40,
                fieldContext: 'domain',
                fieldName: 'servicePassword',
                fieldLabel: 'Password',
                inputType: OptionType.InputType.PASSWORD,
                localCredential: true,
                required: false
        )
        options << new OptionType(
                name: 'Sockets per Host',
                code: 'harvester-cloud-sockets-per-host',
                displayOrder: 45,
                fieldContext: 'config',
                fieldName: HarvesterConstants.CONFIG_SOCKETS_PER_HOST,
                fieldLabel: 'Sockets per Host',
                inputType: OptionType.InputType.NUMBER,
                required: false,
                defaultValue: '1',
                helpText: 'Physical CPU sockets in each Harvester host. Harvester reports CPU cores but not sockets, ' +
                        'so the hosts are recorded with this socket count.'
        )
        options << new OptionType(
                name: 'Inventory Existing Instances',
                code: 'harvester-cloud-import-existing',
                displayOrder: 50,
                fieldContext: 'config',
                fieldName: CONFIG_IMPORT_EXISTING,
                fieldLabel: 'Inventory Existing Instances',
                inputType: OptionType.InputType.CHECKBOX,
                required: false,
                defaultValue: 'on'
        )
        // Read by Morpheus itself: offers the hypervisor (noVNC) console on this cloud's VMs
        options << new OptionType(
                name: 'Enable Hypervisor Console',
                code: 'harvester-cloud-enable-vnc',
                displayOrder: 55,
                fieldContext: 'config',
                fieldName: HarvesterConstants.CONFIG_ENABLE_VNC,
                fieldLabel: 'Enable Hypervisor Console',
                inputType: OptionType.InputType.CHECKBOX,
                required: false,
                defaultValue: 'on',
                helpText: "Console access to the VM's screen through Harvester (VNC), without network access to the VM."
        )
        options << new OptionType(
                name: 'Show Cluster Health',
                code: 'harvester-cloud-cluster-health',
                displayOrder: 56,
                fieldContext: 'config',
                fieldName: HarvesterConstants.CONFIG_CLUSTER_HEALTH,
                fieldLabel: 'Show Cluster Health',
                inputType: OptionType.InputType.CHECKBOX,
                required: false,
                defaultValue: 'on',
                helpText: 'Set the cloud status to Warning, with the reason, when Harvester nodes are not ready or under ' +
                        'pressure, Longhorn volumes are degraded or faulted, or monitoring alerts are firing.'
        )
        return options
    }

    @Override
    Collection<ComputeServerType> getComputeServerTypes() {
        // Server types are defined in the scribe resources (harvester-compute-server-types.scribe)
        return []
    }

    @Override
    Collection<ProvisionProvider> getAvailableProvisionProviders() {
        return this.@plugin.getProvidersByType(ProvisionProvider) as Collection<ProvisionProvider>
    }

    @Override
    /**
     * Morpheus asks for the provider by the provision type code ('harvester', the same as this cloud's code) as well as
     * by the provider's own code, so match both. Without this the layout's provider isn't found and the provisioning
     * wizard offers no resource pools.
     */
    ProvisionProvider getProvisionProvider(String providerCode) {
        return getAvailableProvisionProviders().find { it.code == providerCode || it.provisionTypeCode == providerCode }
    }

    @Override
    String getDefaultProvisionTypeCode() {
        return HarvesterConstants.PROVISION_TYPE_CODE
    }

    @Override
    Collection<BackupProvider> getAvailableBackupProviders() {
        return []
    }

    @Override
    Collection<NetworkType> getNetworkTypes() {
        return []
    }

    @Override
    Collection<NetworkSubnetType> getSubnetTypes() {
        return []
    }

    @Override
    Collection<StorageVolumeType> getStorageVolumeTypes() {
        return []
    }

    @Override
    Collection<StorageControllerType> getStorageControllerTypes() {
        return []
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Credentials and the Harvester connection record
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Collects the API URL and credentials of a cloud. Credentials come from the cloud's API key, a stored
     * Morpheus credential, or the username and password typed on the cloud form (serviceUsername/servicePassword).
     * @param request the validate request while the cloud form is being saved, otherwise null
     * @return [apiUrl, apiKey, username, password]
     */
    Map resolveCredentials(Cloud cloud, ValidateCloudRequest request = null) {
        Map rtn = [apiUrl: cloud?.serviceUrl?.trim(), apiKey: null, username: null, password: null]
        rtn.apiKey = (cloud?.getConfigProperty(CONFIG_API_KEY) as String)?.trim() ?: null

        String username = null
        String password = null
        if (request && request.credentialType && request.credentialType != 'local') {
            username = request.credentialUsername
            password = request.credentialPassword
        } else if (!request && cloud?.id) {
            Map auth = DBUtils.getAuthConfig(context, cloud) as Map
            username = auth?.apiUsername
            password = auth?.apiPassword
        }
        username = username ?: (cloud?.getConfigProperty(CONFIG_USERNAME) as String) ?: cloud?.serviceUsername
        password = password ?: (cloud?.getConfigProperty(CONFIG_PASSWORD) as String) ?: cloud?.servicePassword
        rtn.username = username?.trim() ?: null
        rtn.password = password ?: null
        return rtn
    }

    /**
     * Builds an unsaved Harvester connection from a cloud's URL and credentials (for validation).
     */
    static ComputeServerGroup buildConnection(Map credentials) {
        ComputeServerGroup connection = new ComputeServerGroup()
        connection.serviceUrl = credentials.apiUrl
        connection.serviceToken = credentials.apiKey
        connection.serviceUsername = credentials.apiKey ? null : credentials.username
        connection.servicePassword = credentials.apiKey ? null : credentials.password
        return connection
    }

    static String connectionCategory(Cloud cloud) {
        return "${HarvesterConstants.CATEGORY_CLOUD_CLUSTER_PREFIX}${cloud.id}".toString()
    }

    ComputeServerGroup findConnection(Cloud cloud) {
        return context.services.cluster.find(new DataQuery().withFilters(
                new DataFilter('category', connectionCategory(cloud))
        ))
    }

    /**
     * Returns the cloud's Harvester connection record, creating it on first use and keeping its URL, credentials
     * and settings in step with the cloud.
     */
    ComputeServerGroup ensureConnection(Cloud cloud) {
        Map credentials = resolveCredentials(cloud)
        boolean importExisting = isImportExisting(cloud)
        ComputeServerGroup connection = findConnection(cloud)
        if (!connection) {
            ComputeServerGroupType groupType = context.services.cluster.type.find(new DataQuery().withFilters(
                    new DataFilter('code', HarvesterConstants.CLUSTER_CODE)
            ))
            connection = new ComputeServerGroup(
                    // Morpheus requires a uuid on every cluster record and doesn't generate one for plugin creates
                    uuid: UUID.randomUUID().toString(),
                    name: cloud.name,
                    code: connectionCategory(cloud),
                    category: connectionCategory(cloud),
                    description: "Harvester connection for cloud ${cloud.name}".toString(),
                    type: groupType,
                    cloud: cloud,
                    owner: cloud.owner ?: cloud.account,
                    visibility: 'private',
                    enabled: true,
                    managed: false,
                    discovered: false,
                    provisionComplete: true
            )
            applySettings(connection, cloud, credentials, importExisting)
            connection = context.services.cluster.create(connection)
            log.info("Harvester: created Harvester connection ${connection?.id} for cloud ${cloud.name}")
            DBUtils.createDefaultResourcePool(context, connection)
        } else if (applySettings(connection, cloud, credentials, importExisting)) {
            connection = context.services.cluster.save(connection)
        }
        return connection
    }

    /**
     * Copies URL, credentials and settings from the cloud to its connection record.
     * @return true when something changed
     */
    static boolean applySettings(ComputeServerGroup connection, Cloud cloud, Map credentials, boolean importExisting) {
        ComputeServerGroup desired = buildConnection(credentials)
        boolean changed = false
        if (connection.serviceUrl != desired.serviceUrl) {
            connection.serviceUrl = desired.serviceUrl
            changed = true
        }
        if (connection.serviceToken != desired.serviceToken) {
            connection.serviceToken = desired.serviceToken
            changed = true
        }
        if (connection.serviceUsername != desired.serviceUsername) {
            connection.serviceUsername = desired.serviceUsername
            changed = true
        }
        if (connection.servicePassword != desired.servicePassword) {
            connection.servicePassword = desired.servicePassword
            changed = true
        }
        if (cloud.name && connection.name != cloud.name) {
            connection.name = cloud.name
            changed = true
        }
        def managedBy = connection.getConfigProperty(HarvesterConstants.CONFIG_MANAGED_BY_CLOUD)
        if (managedBy != true && managedBy != 'true') {
            connection.setConfigProperty(HarvesterConstants.CONFIG_MANAGED_BY_CLOUD, true)
            changed = true
        }
        String importValue = importExisting ? 'on' : 'off'
        if (connection.getConfigProperty(CONFIG_IMPORT_EXISTING) != importValue) {
            connection.setConfigProperty(CONFIG_IMPORT_EXISTING, importValue)
            changed = true
        }
        return changed
    }

    static boolean isImportExisting(Cloud cloud) {
        def value = cloud?.getConfigProperty(CONFIG_IMPORT_EXISTING)
        return value == true || value == 'on' || value == 'true'
    }

    /**
     * Checks that the Harvester API answers with these credentials and that the URL points at Harvester.
     * @return null when the connection works, otherwise the error to show
     */
    static String checkConnection(HarvesterApiClient apiClient) {
        ServiceResponse response
        try {
            response = apiClient.get(HarvesterConstants.HARVESTER_API_V1_NAMESPACES, [limit: '1'])
        } catch (Exception e) {
            return e.message
        }
        if (!response?.success) {
            if (response?.errorCode == '401') {
                return 'Harvester rejected the credentials. Check the API key, or the username and password.'
            }
            if (response?.errorCode == '403') {
                return 'The Harvester user or API key does not have enough permissions. Use an admin user or an API key with no scope.'
            }
            return "Unable to reach the Harvester API at ${apiClient.baseUrl}: ${response?.msg ?: response?.errorCode ?: 'no response'}"
        }
        ServiceResponse version
        try {
            version = apiClient.get(HarvesterConstants.HARVESTER_SETTING_SERVER_VERSION)
        } catch (Exception ignored) {
            version = null
        }
        if (version?.errorCode == '404') {
            return "${apiClient.baseUrl} answers, but it is not a Harvester cluster (no harvesterhci.io settings found)."
        }
        if (version?.success) {
            log.info("Harvester: connected to Harvester ${version.data?.value ?: version.data?.default ?: ''}")
        }
        return null
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Cloud lifecycle
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    ServiceResponse validate(Cloud cloudInfo, ValidateCloudRequest validateCloudRequest) {
        log.debug("Harvester: validate cloud ${cloudInfo?.name}")
        try {
            if (!cloudInfo) {
                return new ServiceResponse(success: false, msg: 'No cloud found')
            }
            Map credentials = resolveCredentials(cloudInfo, validateCloudRequest)
            Map errors = [:]
            if (!credentials.apiUrl) {
                errors.serviceUrl = 'Enter the Harvester API URL'
            }
            if (!credentials.apiKey && !(credentials.username && credentials.password)) {
                errors[CONFIG_API_KEY] = 'Enter a Harvester API key, or a username and password'
            }
            if (errors) {
                return new ServiceResponse(success: false, msg: errors.values().first(), errors: errors)
            }
            String error = checkConnection(new HarvesterApiClient(buildConnection(credentials)))
            if (error) {
                return new ServiceResponse(success: false, msg: error, errors: [serviceUrl: error])
            }
            return ServiceResponse.success()
        } catch (Exception e) {
            log.error("Harvester: error validating cloud: ${e.message}", e)
            return new ServiceResponse(success: false, msg: "Error validating Harvester cloud: ${e.message}")
        }
    }

    @Override
    ServiceResponse initializeCloud(Cloud cloudInfo) {
        log.info("Harvester: initializing cloud ${cloudInfo?.name}")
        try {
            ensureConnection(cloudInfo)
            return refresh(cloudInfo)
        } catch (Exception e) {
            log.error("Harvester: error initializing cloud ${cloudInfo?.name}: ${e.message}", e)
            return ServiceResponse.error("Error initializing Harvester cloud: ${e.message}")
        }
    }

    /**
     * Syncs the Harvester inventory into the cloud. A failed connection check marks the cloud offline.
     */
    @Override
    ServiceResponse refresh(Cloud cloudInfo) {
        log.info("Harvester: refreshing cloud ${cloudInfo?.name}")
        try {
            ComputeServerGroup connection = ensureConnection(cloudInfo)
            if (!connection) {
                return ServiceResponse.error('Unable to create the Harvester connection record for this cloud')
            }
            hideClusterPool(connection)
            enableHypervisorConsoleIfUnset(cloudInfo)
            setUpCustomHarvesterPlan()
            useDefaultPlanCpuLayout()
            String error = checkConnection(new HarvesterApiClient(connection))
            if (error) {
                log.warn("Harvester: cloud ${cloudInfo.name} is offline: ${error}")
                return ServiceResponse.error(error, null, [status: Cloud.Status.offline])
            }
            new HarvesterInventorySync(this.@plugin, connection, isImportExisting(cloudInfo)).execute()
            return clusterHealthResponse(cloudInfo, new HarvesterApiClient(connection))
        } catch (Exception e) {
            log.error("Harvester: error refreshing cloud ${cloudInfo?.name}: ${e.message}", e)
            return ServiceResponse.error("Error refreshing Harvester cloud: ${e.message}")
        }
    }

    /**
     * The fixed Harvester plans default to 2 sockets with the cores split between them. Morpheus keeps an existing
     * plan's settings when the plugin is updated, so plans seeded by an earlier version are set to that layout once;
     * the plan is then marked, and later changes made in Administration > Plans are kept.
     */
    void useDefaultPlanCpuLayout() {
        HpeHarvesterProvisionProvider.PLAN_SIZES.each { List<Integer> size ->
            String code = HpeHarvesterProvisionProvider.planCode(size[0], size[1])
            try {
                ServicePlan plan = context.services.servicePlan.find(new DataQuery().withFilters(new DataFilter('code', code)))
                if (!plan || plan.getConfigProperty(HpeHarvesterProvisionProvider.PLAN_CPU_LAYOUT_KEY)) {
                    return
                }
                long cores = (plan.maxCores ?: size[0]) as long
                long coresPerSocket = HpeHarvesterProvisionProvider.defaultCoresPerSocket(cores)
                plan.coresPerSocket = coresPerSocket
                plan.maxCpu = (cores / coresPerSocket) as Long
                plan.setConfigProperty(HpeHarvesterProvisionProvider.PLAN_CPU_LAYOUT_KEY, HpeHarvesterProvisionProvider.PLAN_CPU_LAYOUT)
                context.services.servicePlan.save(plan)
                log.info("Harvester: plan ${plan.name} set to ${plan.maxCpu} socket(s) × ${coresPerSocket} core(s)")
            } catch (Exception e) {
                log.warn("Harvester: unable to set the CPU layout on plan ${code}: ${e.message}")
            }
        }
    }

    /**
     * The Harvester "Custom" plan has every custom option on: CPU, cores (and cores per socket), memory, root and
     * data disk sizes and adding disks. Morpheus keeps an existing plan's settings when the plugin is updated, so a
     * plan seeded by an earlier version is switched on once (then marked, so later changes in Administration > Plans
     * are kept). Its old name, which clashes with the fixed "2 CPU, 4GB Memory" plan, is changed to "Custom".
     */
    static final String OLD_CUSTOM_PLAN_NAME = '2 CPU, 4GB Memory'
    static final String CUSTOM_PLAN_MARKER_KEY = 'harvesterCustomPlan'
    static final String CUSTOM_PLAN_MARKER = 'all-custom'

    void setUpCustomHarvesterPlan() {
        try {
            ServicePlan plan = context.services.servicePlan.find(new DataQuery().withFilters(
                    new DataFilter('code', HarvesterConstants.SERVICE_PLAN_CUSTOM_HARVESTER)))
            if (!plan) {
                return
            }
            boolean changed = false
            if (plan.getConfigProperty(CUSTOM_PLAN_MARKER_KEY) != CUSTOM_PLAN_MARKER) {
                plan.customCpu = true
                plan.customCores = true
                plan.customMaxMemory = true
                plan.customMaxStorage = true
                plan.customMaxDataStorage = true
                plan.addVolumes = true
                if (!plan.maxCores) {
                    plan.maxCores = plan.maxCpu ?: 2L
                }
                if (!plan.coresPerSocket) {
                    plan.coresPerSocket = 1L
                }
                plan.setConfigProperty(CUSTOM_PLAN_MARKER_KEY, CUSTOM_PLAN_MARKER)
                changed = true
            }
            if (plan.name == OLD_CUSTOM_PLAN_NAME) {
                plan.name = 'Custom'
                plan.description = 'Custom CPU, memory and disks'
                plan.sortOrder = 100
                changed = true
            }
            if (changed) {
                context.services.servicePlan.save(plan)
                log.info("Harvester: set up the Harvester custom plan ${plan.name} with every custom option on")
            }
        } catch (Exception e) {
            log.warn("Harvester: unable to update the Harvester custom plan: ${e.message}")
        }
    }

    /**
     * The refresh result with the cluster's health: Morpheus sets the cloud status to OK on success, and to the
     * response's data.status (Warning here) with its message otherwise. Health problems never fail the refresh itself.
     */
    ServiceResponse clusterHealthResponse(Cloud cloud, HarvesterApiClient client) {
        if (!isClusterHealthOn(cloud)) {
            return ServiceResponse.success()
        }
        try {
            Map health = new ClusterHealthApiHelper(client).check()
            if (health.healthy) {
                return ServiceResponse.success()
            }
            String message = ClusterHealthApiHelper.message(health.issues as List<String>)
            log.warn("Harvester: cloud ${cloud?.name}: ${message}")
            return new ServiceResponse(false, message, null, [status: Cloud.Status.warning])
        } catch (Exception e) {
            log.warn("Harvester: unable to check cluster health for ${cloud?.name}: ${e.message}")
            return ServiceResponse.success()
        }
    }

    static boolean isClusterHealthOn(Cloud cloud) {
        def value = cloud?.getConfigProperty(HarvesterConstants.CONFIG_CLUSTER_HEALTH)
        return value == null || value == true || value.toString().toLowerCase() in ['on', 'true', '1', 'yes']
    }

    /**
     * Clouds added before the "Enable Hypervisor Console" setting existed have no value for it: switch it on once.
     * A cloud where it was switched off ('off') is left alone.
     */
    void enableHypervisorConsoleIfUnset(Cloud cloud) {
        try {
            if (cloud && cloud.getConfigProperty(HarvesterConstants.CONFIG_ENABLE_VNC) == null) {
                cloud.setConfigProperty(HarvesterConstants.CONFIG_ENABLE_VNC, 'on')
                context.services.cloud.save(cloud)
                log.info("Harvester: enabled the hypervisor console on cloud ${cloud.name}")
            }
        } catch (Exception e) {
            log.warn("Harvester: unable to enable the hypervisor console on cloud ${cloud?.name}: ${e.message}")
        }
    }

    @Override
    void refreshDaily(Cloud cloudInfo) {
        // Everything is synced by the regular refresh
    }

    /**
     * Removes the inventory synced for this cloud and its Harvester connection record. Nothing is changed in Harvester.
     */
    @Override
    ServiceResponse deleteCloud(Cloud cloudInfo) {
        log.info("Harvester: deleting cloud ${cloudInfo?.name}")
        try {
            ComputeServerGroup connection = findConnection(cloudInfo)
            if (connection) {
                new HpeHarvesterClusterProvider(this.@plugin, this.@context).deleteCluster(connection)
                context.services.cluster.remove(connection)
            }
            return ServiceResponse.success()
        } catch (Exception e) {
            log.error("Harvester: error deleting cloud ${cloudInfo?.name}: ${e.message}", e)
            return ServiceResponse.error("Error removing Harvester cloud data: ${e.message}")
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Capabilities
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    Boolean hasComputeZonePools() {
        return true
    }

    @Override
    Boolean provisionRequiresResourcePool() {
        return true
    }

    @Override
    Boolean hasNetworks() {
        return true
    }

    @Override
    Boolean hasFolders() {
        return false
    }

    @Override
    Boolean hasDatastores() {
        return true
    }

    @Override
    Boolean hasBareMetal() {
        return false
    }

    @Override
    Boolean hasCloudInit() {
        return true
    }

    @Override
    Boolean supportsDistributedWorker() {
        return false
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Resource pools = Harvester namespaces
    // ---------------------------------------------------------------------------------------------------------------

    static final String NAMESPACE_NAME_PATTERN = /^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$/

    /**
     * The cluster-wide pool predates namespace pools. It stays (networks and datastores reference it) but is hidden and
     * inactive, so the provisioning wizard offers the namespaces instead.
     */
    void hideClusterPool(ComputeServerGroup connection) {
        try {
            CloudPool clusterPool = DBUtils.getClusterResourcePool(context, connection)
            if (clusterPool && (clusterPool.hidden != true || clusterPool.removable != false || clusterPool.active != false)) {
                clusterPool.hidden = true
                clusterPool.removable = false
                clusterPool.active = false
                context.services.cloud.pool.save(clusterPool)
            }
        } catch (Exception e) {
            log.warn("Harvester: unable to hide the cluster pool of ${connection?.name}: ${e.message}")
        }
    }

    @Override
    Boolean canCreateCloudPools() {
        return true
    }

    @Override
    Boolean canDeleteCloudPools() {
        return true
    }

    /**
     * Adding a resource pool creates a Harvester namespace with the pool's name.
     */
    @Override
    ServiceResponse<CloudPool> createCloudPool(Cloud cloud, CloudPool cloudPool) {
        String name = cloudPool?.name?.trim()
        if (!name || !(name ==~ NAMESPACE_NAME_PATTERN)) {
            return ServiceResponse.error('The pool name is used as the Harvester namespace name: up to 63 lowercase ' +
                    'letters, digits and hyphens, starting and ending with a letter or digit.', [name: 'Invalid namespace name'], cloudPool)
        }
        if (CommonApiHelper.isSystemNamespace(name)) {
            return ServiceResponse.error("${name} is a reserved Harvester namespace".toString(), [name: 'Reserved namespace'], cloudPool)
        }
        try {
            ComputeServerGroup connection = findConnection(cloud)
            if (!connection) {
                return ServiceResponse.error('This cloud has not synced with Harvester yet. Refresh the cloud and try again.', null, cloudPool)
            }
            HarvesterApiClient apiClient = new HarvesterApiClient(connection)
            ServiceResponse response = apiClient.post(HarvesterConstants.HARVESTER_API_V1_NAMESPACES,
                    [apiVersion: 'v1', kind: 'Namespace', metadata: [name: name]])
            if (!response?.success) {
                String reason = response?.errorCode == '409' ? "namespace ${name} already exists in Harvester" :
                        (response?.data instanceof Map ? response.data.message : null) ?: response?.msg ?: response?.errorCode
                return ServiceResponse.error("Unable to create namespace ${name}: ${reason}".toString(), null, cloudPool)
            }
            Map metadata = response.data instanceof Map ? (Map) response.data.metadata : [:]
            applyNamespacePoolFields(cloudPool, cloud, connection, name, metadata?.uid as String)
            log.info("Harvester: created namespace ${name} for cloud ${cloud.name}")
            return ServiceResponse.success(cloudPool)
        } catch (Exception e) {
            log.error("Harvester: error creating namespace ${name}: ${e.message}", e)
            return ServiceResponse.error("Error creating namespace ${name}: ${e.message}".toString(), null, cloudPool)
        }
    }

    /**
     * Fills in a pool the way the namespace sync does, so the sync matches it instead of adding a second one.
     */
    static void applyNamespacePoolFields(CloudPool pool, Cloud cloud, ComputeServerGroup connection, String name, String uid) {
        String category = "${HarvesterConstants.CATEGORY_NAMESPACE_CLUSTER_PREFIX}${connection.id}".toString()
        pool.name = name
        pool.displayName = name
        pool.externalId = uid
        pool.uniqueId = uid
        pool.type = HarvesterConstants.NAMESPACE_TYPE
        pool.cloud = cloud
        pool.owner = pool.owner ?: connection.owner ?: cloud.owner
        // pools of the cloud (refType ComputeZone) are the ones listed on its Resources tab
        pool.refType = HarvesterConstants.COMPUTE_ZONE
        pool.refId = cloud?.id
        // plain cloud pool, like other clouds' pools: the wizard doesn't offer pools linked to a cluster record
        pool.serverGroup = null
        pool.code = category
        pool.category = category
        pool.active = true
        pool.removable = true
        pool.editable = false
        pool.hidden = false
    }

    /**
     * Namespaces can't be renamed in Harvester; other pool settings are Morpheus-side only.
     */
    @Override
    ServiceResponse<CloudPool> updateCloudPool(Cloud cloud, CloudPool cloudPool) {
        try {
            CloudPool existing = cloudPool?.id ? context.services.cloud.pool.get(cloudPool.id) : null
            if (existing && DBUtils.isNamespacePool(existing) && existing.name != cloudPool.name) {
                return ServiceResponse.error('Harvester namespaces cannot be renamed', [name: 'Namespaces cannot be renamed'], cloudPool)
            }
        } catch (Exception e) {
            log.warn("Harvester: unable to check pool ${cloudPool?.name} before update: ${e.message}")
        }
        return ServiceResponse.success(cloudPool)
    }

    /**
     * Removing a resource pool deletes its Harvester namespace. Deleting a namespace deletes everything in it, so
     * this refuses while it still holds VMs, volumes or images, and never touches default or system namespaces.
     */
    @Override
    ServiceResponse<CloudPool> removeCloudPool(Cloud cloud, CloudPool cloudPool) {
        String name = cloudPool?.name
        if (!DBUtils.isNamespacePool(cloudPool) || !cloudPool.externalId) {
            // not linked to a Harvester namespace (e.g. a duplicate saved by Morpheus): remove the record only
            log.info("Harvester: removing pool ${name} without changing Harvester: it is not linked to a namespace")
            return ServiceResponse.success(cloudPool)
        }
        if (name == HarvesterConstants.DEFAULT_NAMESPACE_NAME || CommonApiHelper.isSystemNamespace(name)) {
            return ServiceResponse.error("The ${name} namespace can't be removed".toString(), null, cloudPool)
        }
        try {
            ComputeServerGroup connection = cloudPool.serverGroup ?: findConnection(cloud)
            if (!connection) {
                return ServiceResponse.error('Unable to find the Harvester connection for this cloud', null, cloudPool)
            }
            HarvesterApiClient apiClient = new HarvesterApiClient(connection)
            String inUse = namespaceContents(apiClient, name)
            if (inUse) {
                return ServiceResponse.error("Namespace ${name} still contains ${inUse}. Delete or move them first.".toString(), null, cloudPool)
            }
            ServiceResponse response = apiClient.delete("${HarvesterConstants.HARVESTER_API_V1_NAMESPACES}/${name}".toString())
            if (!response?.success && response?.errorCode != '404') {
                return ServiceResponse.error("Unable to delete namespace ${name}: ${response?.msg ?: response?.errorCode}".toString(), null, cloudPool)
            }
            log.info("Harvester: deleted namespace ${name} for cloud ${cloud?.name}")
            return ServiceResponse.success(cloudPool)
        } catch (Exception e) {
            log.error("Harvester: error deleting namespace ${name}: ${e.message}", e)
            return ServiceResponse.error("Error deleting namespace ${name}: ${e.message}".toString(), null, cloudPool)
        }
    }

    /**
     * @return a description of what a namespace still holds (VMs, volumes, images), or null when it is empty
     * @throws IllegalStateException when Harvester can't be asked
     */
    static String namespaceContents(HarvesterApiClient apiClient, String namespace) {
        Map<String, String> checks = [
                'virtual machines': "/apis/kubevirt.io/v1/namespaces/${namespace}/virtualmachines".toString(),
                'volumes'         : "/api/v1/namespaces/${namespace}/persistentvolumeclaims".toString(),
                'images'          : "/apis/harvesterhci.io/v1beta1/namespaces/${namespace}/virtualmachineimages".toString()
        ]
        List<String> found = []
        checks.each { String label, String path ->
            ServiceResponse response = apiClient.get(path, [limit: '1'])
            if (!response?.success) {
                if (response?.errorCode == '404') {
                    return
                }
                throw new IllegalStateException("unable to check the ${label} in namespace ${namespace}: ${response?.msg ?: response?.errorCode}")
            }
            def items = response.data instanceof Map ? response.data.items : null
            if (items) {
                found << label
            }
        }
        return found ? found.join(', ') : null
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Power and delete for VMs in this cloud
    // ---------------------------------------------------------------------------------------------------------------

    @Override
    ServiceResponse startServer(ComputeServer computeServer) {
        try {
            return new VirtualMachineReconfigure(context, computeServer).startVM()
        } catch (Exception e) {
            log.error("Harvester: error starting ${computeServer?.name}: ${e.message}", e)
            return ServiceResponse.error("Error starting server: ${e.message}")
        }
    }

    @Override
    ServiceResponse stopServer(ComputeServer computeServer) {
        try {
            return new VirtualMachineReconfigure(context, computeServer).stopVM()
        } catch (Exception e) {
            log.error("Harvester: error stopping ${computeServer?.name}: ${e.message}", e)
            return ServiceResponse.error("Error stopping server: ${e.message}")
        }
    }

    @Override
    ServiceResponse deleteServer(ComputeServer computeServer) {
        try {
            return new VirtualMachineDeletion(context).deleteServer(computeServer)
        } catch (Exception e) {
            log.error("Harvester: error deleting ${computeServer?.name}: ${e.message}", e)
            return ServiceResponse.error("Error deleting server: ${e.message}")
        }
    }
}
