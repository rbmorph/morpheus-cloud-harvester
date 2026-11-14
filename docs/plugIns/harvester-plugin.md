# Harvester plugin – implementation notes

How the plugin works with Harvester's APIs internally. The [README](../../README.md) describes the behaviour for users.

| Area | Implementation |
|------|----------------|
| Morpheus object | Cloud (`HpeHarvesterCloudProvider`). Each cloud keeps a Harvester connection record (`ComputeServerGroup` of type harvester-cluster, `config.managedByCloud = true`) that the sync and provisioning code works against |
| Auth | Harvester API key (bearer `token-xxx:yyy`) or Harvester user login via `/v3-public/localProviders/local?action=login`, sent to `https://<vip>/k8s/clusters/local` (`AuthManager`). A 401 on a login token triggers one re-login and retry (`AuthWrapper`). No service account is created |
| Connection check | `GET /api/v1/namespaces?limit=1` |
| Projects | Kubernetes namespaces. Harvester, Longhorn, Rancher and fleet system namespaces are hidden (`HarvesterConstants.SYSTEM_NAMESPACES`, `CommonApiHelper.isSystemNamespace`) |
| Image source | `harvesterhci.io/v1beta1` VirtualMachineImages (`HarvesterImageApiHelper`). `VirtualImagesSync.toImageRecord` converts each image to the record shape the sync uses |
| Root disk | PVC on the image's storage class (`status.storageClassName`, normally `longhorn-image-*`) with the `harvesterhci.io/imageId: <ns>/<name>` annotation. Longhorn fills the disk from the backing image (`HarvesterVolumes`) |
| Per-image storage classes | Not synced as datastores (`StorageClassApiHelper.isImageStorageClass`). Volumes that use them are attributed to the default class (`DBUtils.findDatastoreByStorageClass`) |
| Pod network | uid of the `mgmt` ClusterNetwork (`network.harvesterhci.io`), falling back to the `kube-system` namespace uid |
| NADs across namespaces | Any namespace other than the VM's is referenced as `ns/name`, and the mismatch is not an error |
| VM spec | `resources.limits` (Harvester derives guest memory and requests from them). EFI only when the Morpheus image is UEFI or Secure Boot. Adds the `harvesterhci.io/vmName` template label |
| Convert to managed | Boot PVC `harvesterhci.io/imageId` annotation, falling back to the VM's `harvesterhci.io/volumeClaimTemplates` annotation |
| DataVolumes | Synced when CDI is installed. A 404 from the CDI API means there is nothing to sync |
| Volume types | PVC ("Harvester Volume"). DataVolume and DataSource are disabled |
