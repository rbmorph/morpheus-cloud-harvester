package com.hpe.morpheus.harvester.plugin.utils

import spock.lang.Specification

class HarvesterConstantsSpec extends Specification {

    def "cluster provider constants are defined correctly"() {
        expect:
        HarvesterConstants.CLUSTER_PROVIDER_CODE == 'harvester-cluster'
        HarvesterConstants.CLUSTER_PROVIDER_TYPE == 'harvester'
        HarvesterConstants.PROVISION_TYPE_CODE == 'harvester-provision-provider-plugin'
        HarvesterConstants.LEGACY_PROVISION_TYPE_CODE == 'harvester'
        HarvesterConstants.CLUSTER_CODE == 'harvester-cluster'
        HarvesterConstants.PROVISION_PROVIDER_CODE == 'harvester-provision-provider-plugin'
        HarvesterConstants.DATASTORE_PROVISION_PROVIDER_CODE == 'harvester-datastore-provider'
        HarvesterConstants.STORAGE_PROVIDER_CODE == 'harvester-storage-provider'
    }

    def "API endpoint constants are defined correctly"() {
        expect:
        HarvesterConstants.HARVESTER_API_V1_NAMESPACES == '/api/v1/namespaces'
        HarvesterConstants.HARVESTER_API_V1_NODES == '/api/v1/nodes'
        HarvesterConstants.HARVESTER_API_V1_NODES_METRICS == '/apis/metrics.k8s.io/v1beta1/nodes'
        HarvesterConstants.HARVESTER_API_V1_PVCS == '/api/v1/persistentvolumeclaims'
        HarvesterConstants.HARVESTER_API_V1_DATAVOLUMES == '/apis/cdi.kubevirt.io/v1beta1/datavolumes'
        HarvesterConstants.HARVESTER_API_V1_VMS == '/apis/kubevirt.io/v1/virtualmachines'
        HarvesterConstants.HARVESTER_API_V1_VM_INSTANCES == '/apis/kubevirt.io/v1/virtualmachineinstances'
        HarvesterConstants.HARVESTER_API_V1_NADS == '/apis/k8s.cni.cncf.io/v1/network-attachment-definitions'
        HarvesterConstants.HARVESTER_API_V1_VM_IMAGES == '/apis/harvesterhci.io/v1beta1/virtualmachineimages'
        HarvesterConstants.HARVESTER_API_MGMT_CLUSTER_NETWORK == '/apis/network.harvesterhci.io/v1beta1/clusternetworks/mgmt'
    }

    def "system namespaces list contains expected entries"() {
        expect:
        HarvesterConstants.SYSTEM_NAMESPACES.contains('kube-system')
        HarvesterConstants.SYSTEM_NAMESPACES.contains('kube-public')
        HarvesterConstants.SYSTEM_NAMESPACES.contains('harvester-system')
        HarvesterConstants.SYSTEM_NAMESPACES.contains('harvester-public')
        HarvesterConstants.SYSTEM_NAMESPACES.contains('longhorn-system')
        HarvesterConstants.SYSTEM_NAMESPACES.contains('cattle-system')
        !HarvesterConstants.SYSTEM_NAMESPACES.contains('default')
    }

    def "status constants are defined correctly"() {
        expect:
        HarvesterConstants.STATUS_RUNNING == 'Running'
        HarvesterConstants.STATUS_STOPPED == 'Stopped'
        HarvesterConstants.STATUS_PENDING == 'Pending'
        HarvesterConstants.STATUS_PAUSED == 'Paused'
        HarvesterConstants.STATUS_PROVISIONED == 'provisioned'
        HarvesterConstants.STATUS_PROVISIONING == 'provisioning'
        HarvesterConstants.STATUS_DEPROVISIONING == 'deprovisioning'
    }

    def "power state constants are defined correctly"() {
        expect:
        HarvesterConstants.POWER_ON == 'on'
        HarvesterConstants.POWER_OFF == 'off'
        HarvesterConstants.POWER_UNKNOWN == 'unknown'
    }

    def "server type constants are defined correctly"() {
        expect:
        HarvesterConstants.SERVER_TYPE_VM == 'vm'
        HarvesterConstants.SERVER_TYPE_UNMANAGED == 'unmanaged'
        HarvesterConstants.SERVER_TYPE_HARVESTER_UNMANAGED == 'harvesterUnmanaged'
        HarvesterConstants.SERVER_TYPE_HARVESTER_NODE == 'harvester-node'
        HarvesterConstants.SERVER_TYPE_MORPHEUS_NODE == 'morpheus-node'
        HarvesterConstants.SERVER_TYPE_HARVESTER_MASTER == 'harvesterMaster'
        HarvesterConstants.SERVER_TYPE_HARVESTER_WORKER == 'harvesterWorker'
    }

    def "storage and resource constants are defined correctly"() {
        expect:
        HarvesterConstants.STORAGE_TYPE_STANDARD == 'standard'
        HarvesterConstants.SERVICE_PLAN_CUSTOM_HARVESTER == 'custom-harvester'
        HarvesterConstants.PROVISION_TYPE_HARVESTER == 'harvester-provision-provider-plugin'
        HarvesterConstants.RESOURCE_TYPE_SERVICE_PLAN == 'ServicePlan'
        HarvesterConstants.NAMESPACE_TYPE == 'default'
        HarvesterConstants.NAMESPACE_TYPES == ['default', 'harvester-namespace', 'namespace']
        HarvesterConstants.RESOURCE_POOL_TYPE_DEFAULT == 'default'
    }

    def "category prefix constants are defined correctly"() {
        expect:
        HarvesterConstants.CATEGORY_HARVESTER_NODE_PREFIX == 'harvester.node.cluster.'
        HarvesterConstants.CATEGORY_HARVESTER_VM_PREFIX == 'harvester.vm.cluster.'
        HarvesterConstants.CATEGORY_DEFAULT_RESOURCE_POOL_PREFIX == 'harvester.cluster.'
        HarvesterConstants.CATEGORY_NAMESPACE_CLUSTER_PREFIX == 'harvester.namespace.cluster.'
        HarvesterConstants.CATEGORY_PVC_CLUSTER_PREFIX == 'harvester.persistentVolumeClaim.cluster.'
        HarvesterConstants.CATEGORY_DATAVOLUME_CLUSTER_PREFIX == 'harvester.dataVolume.cluster.'
        HarvesterConstants.CATEGORY_NETWORK_CLUSTER_PREFIX == 'harvester.network.'
    }

    def "default values are defined correctly"() {
        expect:
        HarvesterConstants.DEFAULT_IP == '0.0.0.0'
        HarvesterConstants.SSH_USER_ROOT == 'root'
        HarvesterConstants.SSH_USER_ADMIN == 'Administrator'
        HarvesterConstants.OS_TYPE_LINUX == 'linux'
        HarvesterConstants.OS_TYPE_WINDOWS == 'windows'
    }

    def "storage controller types are defined correctly"() {
        expect:
        HarvesterConstants.STORAGE_CONTROLLER_TYPE_IDE == 'harvester-ide'
        HarvesterConstants.STORAGE_CONTROLLER_TYPE_SATA == 'harvester-sata'
        HarvesterConstants.STORAGE_CONTROLLER_TYPE_VIRTIO == 'harvester-virtio'
        HarvesterConstants.STORAGE_CONTROLLER_TYPE_SCSI == 'harvester-scsi'
        HarvesterConstants.STORAGE_CONTROLLERS_TYPES == [
            'harvester-ide',
            'harvester-sata',
            'harvester-virtio',
            'harvester-scsi'
        ]
    }

    def "volume type constants are defined correctly"() {
        expect:
        HarvesterConstants.VOLUME_TYPE_HARVESTER_PVC == 'harvesterPVC'
        HarvesterConstants.VOLUME_TYPE_HARVESTER_DATAVOLUME == 'harvesterDataVolume'
        HarvesterConstants.VOLUME_TYPE_HARVESTER_DATASOURCE == 'harvesterDataSource'
        HarvesterConstants.STORAGE_VOLUME_TYPES == [
            'harvesterPVC',
            'harvesterDataVolume',
            'harvesterDataSource'
        ]
    }

    def "network type constants are defined correctly"() {
        expect:
        HarvesterConstants.HARVESTER_NETWORK_TYPE == 'harvesterNetwork'
        HarvesterConstants.POD_NETWORK_NAME == 'default-pod-network'
    }

    def "interface type constants are defined correctly"() {
        expect:
        HarvesterConstants.VIRTIO_INTERFACE_TYPE == 'virtio'
        HarvesterConstants.E1000_INTERFACE_TYPE == 'e1000-kvm'
        HarvesterConstants.INTERFACE_TYPES == ['virtio', 'e1000-kvm']
    }

    def "cloud init and provisioning constants are defined correctly"() {
        expect:
        HarvesterConstants.CLOUD_INIT_DISK == 'cloudinitdisk'
        HarvesterConstants.MORPHEUS_PROVISIONING_LABEL == 'morpheus-label.vm.provisioning'
    }

    def "volume type identifier constants are defined correctly"() {
        expect:
        HarvesterConstants.VOLUME_TYPE_DATAVOLUME == 'dataVolume'
        HarvesterConstants.VOLUME_TYPE_PVC == 'pvc'
        HarvesterConstants.VOLUME_TYPE_CLOUDINIT == 'cloudInit'
        HarvesterConstants.VOLUME_TYPE_DATASOURCE == 'dataSource'
    }

    def "database object constants are defined correctly"() {
        expect:
        HarvesterConstants.COMPUTE_SERVER_GROUP == 'ComputeServerGroup'
        HarvesterConstants.COMPUTE_ZONE == 'ComputeZone'
        HarvesterConstants.DATASTORE == 'Datastore'
    }

    def "object kind constants are defined correctly"() {
        expect:
        HarvesterConstants.KIND_VIRTUAL_MACHINE == 'VirtualMachine'
    }

    def "node type constants are defined correctly"() {
        expect:
        HarvesterConstants.NODE_TYPE_MASTER == 'master'
    }
}
