// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.utils

class HarvesterConstants {
    static final String CLUSTER_PROVER_DESCRIPTION = 'Harvester is an open-source hyperconverged infrastructure (HCI) platform built on Kubernetes, KubeVirt and Longhorn for running virtual machines'
    static final String CLUSTER_PROVIDER_CODE = 'harvester-cluster'
    static final String CLUSTER_PROVIDER_TYPE = 'harvester'
    // The provision type has the provisioning provider's code. With a different
    // code (it used to be 'harvester', the cloud code) Morpheus offered no resource pools for the Harvester layout.
    static final String PROVISION_TYPE_CODE = 'harvester-provision-provider-plugin'
    // Provision type code used by earlier versions
    static final String LEGACY_PROVISION_TYPE_CODE = 'harvester'
    static final String CLUSTER_CODE = 'harvester-cluster'
    // Harvester cloud. Same code as the provision type, so Morpheus resolves the provision provider through the cloud
    static final String CLOUD_PROVIDER_CODE = 'harvester'
    static final String CLOUD_PROVIDER_NAME = 'Harvester'
    static final String CLOUD_PROVIDER_DESCRIPTION = 'SUSE Harvester HCI: virtual machines on KubeVirt and Longhorn, managed through the Harvester API'
    // The Harvester connection record a cloud keeps for its inventory (a cluster record with this config flag set)
    static final String CONFIG_MANAGED_BY_CLOUD = 'managedByCloud'
    static final String CATEGORY_CLOUD_CLUSTER_PREFIX = 'harvester.cloud.'
    static final String PROVISION_PROVIDER_CODE = 'harvester-provision-provider-plugin'
    static final String DATASTORE_PROVISION_PROVIDER_CODE = 'harvester-datastore-provider'
    static final String STORAGE_PROVIDER_CODE = 'harvester-storage-provider'

    // Harvester API access. Harvester serves its API (the Kubernetes-style paths below) through the embedded
    // Rancher proxy at https://<vip>/k8s/clusters/local, which accepts Harvester API keys and user login tokens.
    static final String HARVESTER_API_PATH_PREFIX = '/k8s/clusters/local'
    static final String HARVESTER_LOGIN_PATH = '/v3-public/localProviders/local'
    static final String HARVESTER_API_KEY_PREFIX = 'token-'
    static final String HARVESTER_SETTING_SERVER_VERSION = '/apis/harvesterhci.io/v1beta1/settings/server-version'
    // Kubernetes API server port: URLs on this port go straight to the API server and are left unchanged
    static final int KUBE_API_SERVER_PORT = 6443

    // Harvester / Kubernetes APIs
    static final String HARVESTER_API_V1_NAMESPACES = '/api/v1/namespaces'
    static final String HARVESTER_API_V1_NODES = '/api/v1/nodes'
    static final String HARVESTER_API_V1_NODES_METRICS = '/apis/metrics.k8s.io/v1beta1/nodes'
    static final String HARVESTER_API_V1_PVCS = '/api/v1/persistentvolumeclaims'
    static final String HARVESTER_API_V1_DATAVOLUMES = '/apis/cdi.kubevirt.io/v1beta1/datavolumes'

    static final String HARVESTER_API_V1_VMS = '/apis/kubevirt.io/v1/virtualmachines'
    static final String HARVESTER_API_V1_VM_INSTANCES = '/apis/kubevirt.io/v1/virtualmachineinstances'

    // Harvester VM images (harvesterhci.io CRD). Cluster-wide list; namespaced path built in HarvesterImageApiHelper
    static final String HARVESTER_API_GROUP = 'harvesterhci.io'
    static final String HARVESTER_API_VERSION = 'v1beta1'
    static final String HARVESTER_API_V1_VM_IMAGES = '/apis/harvesterhci.io/v1beta1/virtualmachineimages'
    static final String HARVESTER_API_V1_NADS = '/apis/k8s.cni.cncf.io/v1/network-attachment-definitions'

    // Harvester annotations / labels
    static final String ANNOTATION_IMAGE_ID = 'harvesterhci.io/imageId'
    static final String ANNOTATION_VOLUME_CLAIM_TEMPLATES = 'harvesterhci.io/volumeClaimTemplates'
    static final String LABEL_IMAGE_DISPLAY_NAME = 'harvesterhci.io/imageDisplayName'
    static final String LABEL_CREATOR = 'harvesterhci.io/creator'
    static final String LABEL_VM_NAME = 'harvesterhci.io/vmName'
    // Set by Harvester's UI on images: 'iso' or 'raw_qcow2'
    static final String LABEL_IMAGE_TYPE = 'harvesterhci.io/image-type'
    // On ISO PVCs the plugin creates for a VM's CD-ROM: the VM name (so they are deleted with the VM)
    static final String LABEL_CDROM_VM = 'morpheus-label.cdrom-vm'
    static final List<String> IMAGE_OS_TYPE_LABELS = ['harvesterhci.io/os-type', 'harvesterhci.io/os', 'os']
    static final List<String> IMAGE_OS_VERSION_LABELS = ['harvesterhci.io/os-version', 'os-version']
    // Per-image Longhorn storage classes are named longhorn-<image name>; they are not real datastores
    static final String IMAGE_STORAGE_CLASS_PREFIX = 'longhorn-image-'
    static final String DEFAULT_STORAGE_CLASS = 'harvester-longhorn'
    // Volume config: the datastore was filled in by "Auto", not chosen
    static final String CONFIG_AUTO_DATASTORE = 'autoDatastore'
    // Volume config: temporary image disk left behind while a copy waits for the VM to start
    static final String CONFIG_IMAGE_COPY_SOURCE = 'imageCopySourcePvc'
    // How long to wait for an image to be copied to another storage class (720 x 5s = 1 hour)
    static final int IMAGE_COPY_WAIT_RETRIES = 720
    // Storage provisioners and classes VMs can use
    static final String LONGHORN_PROVISIONER = 'driver.longhorn.io'
    static final String HARVESTER_SETTING_CSI_DRIVER_CONFIG = '/apis/harvesterhci.io/v1beta1/settings/csi-driver-config'
    // Longhorn's class for statically provisioned volumes: it can't create new disks
    static final List<String> NON_VM_STORAGE_CLASS_NAMES = ['longhorn-static']

    // List of system namespaces to exclude (Harvester, Longhorn, Rancher, RKE2 and Kubernetes internals)
    static final List<String> SYSTEM_NAMESPACES = [
            'kube-system',
            'kube-public',
            'kube-node-lease',
            'harvester-system',
            'harvester-public',
            'longhorn-system',
            'cattle-system',
            'cattle-dashboards',
            'cattle-fleet-system',
            'cattle-fleet-local-system',
            'cattle-fleet-clusters-system',
            'cattle-global-data',
            'cattle-impersonation-system',
            'cattle-logging-system',
            'cattle-monitoring-system',
            'cattle-provisioning-capi-system',
            'cattle-ui-plugin-system',
            'fleet-default',
            'fleet-local',
            'local',
            'cluster-fleet-local-local',
            'p-local',
            'rancher-vcluster',
            'kubevirt',
            'cdi',
            'forklift',
            'tigera-operator',
            'calico-system',
    ]
    // Namespace prefixes that are always system namespaces
    static final List<String> SYSTEM_NAMESPACE_PREFIXES = ['cattle-', 'fleet-', 'kube-', 'harvester-system', 'longhorn-', 'calico-', 'tigera-']
    // Rancher-generated namespaces (projects p-xxxxx, users u-xxxxx, clusters c-m-xxxxx, user-xxxxx)
    static final String RANCHER_GENERATED_NAMESPACE_REGEX = '^(p-[a-z0-9]{5}|c-m-[a-z0-9]{8}|u-[a-z0-9]{10}|user-[a-z0-9]{5})$'
    // Harvester cluster network that backs the pod ("management") network
    static final String HARVESTER_API_MGMT_CLUSTER_NETWORK = '/apis/network.harvesterhci.io/v1beta1/clusternetworks/mgmt'
    static final String HARVESTER_API_KUBE_SYSTEM_NAMESPACE = '/api/v1/namespaces/kube-system'

    // Database constants
    static final String COMPUTE_SERVER_GROUP = 'ComputeServerGroup'
    static final String COMPUTE_ZONE = 'ComputeZone'
    static final String DATASTORE = 'Datastore'

    // Object kinds
    static final String KIND_VIRTUAL_MACHINE = 'VirtualMachine'

    // Default storage class annotation keys (for auto-datastore resolution)
    static final String ANNOTATION_K8S_DEFAULT_CLASS = 'storageclass.kubernetes.io/is-default-class'
    static final String ANNOTATION_KUBEVIRT_DEFAULT_VIRT_CLASS = 'storageclass.kubevirt.io/is-default-virt-class'

    // VM/Server Status and Type Constants
    static final String STATUS_RUNNING = 'Running'
    static final String STATUS_STOPPED = 'Stopped'
    static final String STATUS_PENDING = 'Pending'
    static final String STATUS_PAUSED = 'Paused'
    static final String STATUS_PROVISIONED = 'provisioned'
    static final String STATUS_PROVISIONING = 'provisioning'
    static final String STATUS_DEPROVISIONING = 'deprovisioning'
    static final String POWER_ON = 'on'
    static final String POWER_OFF = 'off'
    static final String POWER_UNKNOWN = 'unknown'
    static final String SERVER_TYPE_VM = 'vm'
    static final String SERVER_TYPE_UNMANAGED = 'unmanaged'
    static final String SERVER_TYPE_HARVESTER_UNMANAGED = 'harvesterUnmanaged'
    static final String SERVER_TYPE_HARVESTER_NODE = 'harvester-node'
    static final String SERVER_TYPE_MORPHEUS_NODE = 'morpheus-node'
    // Harvester hosts sync as VM hypervisor hosts
    static final String SERVER_TYPE_HYPERVISOR = 'hypervisor'
    // Cloud setting: physical CPU sockets per Harvester host (Harvester's API reports cores, not sockets)
    static final String CONFIG_SOCKETS_PER_HOST = 'socketsPerHost'
    // Cloud setting: report Harvester cluster health in the cloud status
    static final String CONFIG_CLUSTER_HEALTH = 'clusterHealth'
    // Cloud setting Morpheus itself reads to offer the hypervisor (noVNC) console
    static final String CONFIG_ENABLE_VNC = 'enableVnc'
    static final String NODE_TYPE_MASTER = 'master'
    static final String SERVER_TYPE_HARVESTER_MASTER = 'harvesterMaster'
    static final String SERVER_TYPE_HARVESTER_WORKER = 'harvesterWorker'
    static final String STORAGE_TYPE_STANDARD = 'standard'
    static final String SERVICE_PLAN_CUSTOM_HARVESTER = 'custom-harvester'
    static final String PROVISION_TYPE_HARVESTER = PROVISION_TYPE_CODE
    static final String RESOURCE_TYPE_SERVICE_PLAN = 'ServicePlan'
    // Pool type of Harvester namespaces. The provisioning wizard only offers pools of types Morpheus knows for a layout,
    // so namespace pools use Morpheus's generic 'default' type. They are told apart
    // from other pools by their category (CATEGORY_NAMESPACE_CLUSTER_PREFIX + connection id).
    static final String NAMESPACE_TYPE = 'default'
    // Types stored by earlier versions
    static final String LEGACY_NAMESPACE_TYPE = 'namespace'
    static final List<String> NAMESPACE_TYPES = [NAMESPACE_TYPE, 'harvester-namespace', LEGACY_NAMESPACE_TYPE]
    static final String DEFAULT_NAMESPACE_NAME = 'default'
    static final String RESOURCE_POOL_TYPE_DEFAULT = 'default'
    static final String CATEGORY_HARVESTER_NODE_PREFIX = 'harvester.node.cluster.'
    static final String CATEGORY_HARVESTER_VM_PREFIX = 'harvester.vm.cluster.'
    static final String CATEGORY_DEFAULT_RESOURCE_POOL_PREFIX = 'harvester.cluster.'
    static final String CATEGORY_NAMESPACE_CLUSTER_PREFIX = 'harvester.namespace.cluster.'
    static final String CATEGORY_PVC_CLUSTER_PREFIX = 'harvester.persistentVolumeClaim.cluster.'
    static final String CATEGORY_DATAVOLUME_CLUSTER_PREFIX = 'harvester.dataVolume.cluster.'
    static final String CATEGORY_NETWORK_CLUSTER_PREFIX = 'harvester.network.'
    static final String DEFAULT_IP = '0.0.0.0'
    static final String SSH_USER_ROOT = 'root'
    static final String SSH_USER_ADMIN = 'Administrator'
    static final String OS_TYPE_LINUX = 'linux'
    static final String OS_TYPE_WINDOWS = 'windows'
    static final String STORAGE_CONTROLLER_TYPE_IDE = 'harvester-ide'
    static final String STORAGE_CONTROLLER_TYPE_SATA = 'harvester-sata'
    static final String STORAGE_CONTROLLER_TYPE_VIRTIO = 'harvester-virtio'
    static final String STORAGE_CONTROLLER_TYPE_SCSI = 'harvester-scsi'
    static final List STORAGE_CONTROLLERS_TYPES = [STORAGE_CONTROLLER_TYPE_IDE, STORAGE_CONTROLLER_TYPE_SATA, STORAGE_CONTROLLER_TYPE_VIRTIO, STORAGE_CONTROLLER_TYPE_SCSI]
    static final String VOLUME_TYPE_HARVESTER_PVC = 'harvesterPVC'
    static final String VOLUME_TYPE_HARVESTER_DATAVOLUME = 'harvesterDataVolume'
    static final List STORAGE_VOLUME_TYPES = [VOLUME_TYPE_HARVESTER_PVC, VOLUME_TYPE_HARVESTER_DATAVOLUME, VOLUME_TYPE_HARVESTER_DATASOURCE]
    static final String HARVESTER_NETWORK_TYPE = 'harvesterNetwork'
    static final String POD_NETWORK_NAME = "default-pod-network"
    static final String VIRTIO_INTERFACE_TYPE = "virtio"
    static final String E1000_INTERFACE_TYPE = "e1000-kvm"
    static  final String CLOUD_INIT_DISK = "cloudinitdisk"
    static final List INTERFACE_TYPES = [VIRTIO_INTERFACE_TYPE, E1000_INTERFACE_TYPE]

    // Morpheus provisioning label - VMs with this label set to 'true' should be skipped during sync
    static final String MORPHEUS_PROVISIONING_LABEL = "morpheus-label.vm.provisioning"

    // Volume type identifiers used in template parsing
    static final String VOLUME_TYPE_DATAVOLUME = 'dataVolume'
    static final String VOLUME_TYPE_PVC = 'pvc'
    static final String VOLUME_TYPE_CLOUDINIT = 'cloudInit'
    static final String VOLUME_TYPE_DATASOURCE = 'dataSource'
    // Boot disk created from a Harvester VirtualMachineImage (PVC on the image's storage class)
    static final String VOLUME_SOURCE_HARVESTER_IMAGE = 'harvesterImage'

    // DataSource related constants
    static final String VOLUME_TYPE_HARVESTER_DATASOURCE = 'harvesterDataSource'
}