# Morpheus Harvester Plugin

Adds [SUSE Harvester](https://harvesterhci.io) HCI to HPE Morpheus Enterprise as a **cloud**. The plugin syncs
the Harvester cluster's hosts, namespaces, images, storage classes, volumes, networks and VMs into Morpheus. It
provisions and manages VMs on Harvester: create, power, resize, add or remove disks and NICs, console, delete,
and convert discovered VMs to managed. Everything goes through the Harvester API with a Harvester API key or user
login. Nothing is installed in the Harvester cluster.

Harvester runs VMs on KubeVirt and stores disks on Longhorn (or a third-party CSI driver), so most Harvester
objects are Kubernetes resources. This README explains what each Morpheus object corresponds to in Harvester,
what the plugin reads and writes, and how to use it.

Implementation notes (how the plugin maps Harvester's APIs internally) are in
[docs/plugIns/harvester-plugin.md](docs/plugIns/harvester-plugin.md).

---

## Contents

1. [Requirements](#requirements)
2. [Installing the plugin](#installing-the-plugin)
3. [Adding a Harvester cloud](#adding-a-harvester-cloud)
4. [How the cloud works](#how-the-cloud-works)
5. [Mapping reference: Morpheus ↔ Harvester](#mapping-reference-morpheus--harvester)
   - [Cloud ↔ Harvester cluster](#cloud--harvester-cluster)
   - [Hosts ↔ Nodes](#hosts--nodes)
   - [Resource pools ↔ Namespaces](#resource-pools--namespaces)
   - [Datastores ↔ Storage classes](#datastores--storage-classes)
   - [Storage volumes ↔ PVCs and DataVolumes](#storage-volumes--pvcs-and-datavolumes)
   - [Virtual images ↔ VM images](#virtual-images--vm-images)
   - [Networks ↔ VM networks](#networks--vm-networks)
   - [Compute servers and instances ↔ Virtual machines](#compute-servers-and-instances--virtual-machines)
   - [Tags ↔ Labels](#tags--labels)
6. [Provisioning a VM](#provisioning-a-vm)
7. [What a provisioned VM looks like in Harvester](#what-a-provisioned-vm-looks-like-in-harvester)
8. [Day-2 operations](#day-2-operations)
9. [Service plans](#service-plans)
10. [Snapshots](#snapshots)
11. [CD-ROMs](#cd-roms)
12. [Harvester YAML tab](#harvester-yaml-tab)
13. [Harvester Console (kubectl)](#harvester-console-kubectl)
14. [Permissions](#permissions)
15. [Morpheus types the plugin registers](#morpheus-types-the-plugin-registers)
16. [Internal identifiers](#internal-identifiers)
17. [Harvester API calls](#harvester-api-calls)
18. [Troubleshooting](#troubleshooting)
19. [Known limitations](#known-limitations)
20. [Building and development](#building-and-development)

---

## Requirements

| | |
|---|---|
| **Morpheus** | 8.0.13 or newer (tested on 9.1). Plugin API 1.3.0. |
| **Harvester** | 1.2 or newer, 1.4 or newer recommended (tested on 1.9.0). |
| **Credentials** | A Harvester admin user, or an API key created by one with **No Scope**. |
| **Network** | The Morpheus appliance must reach the Harvester VIP over HTTPS (443). To get VM IP addresses confirmed by ping, it must also reach the VM networks (ICMP). Without that, the plugin uses the IPs Harvester reports. |
| **In the cluster** | Nothing. No service accounts, roles, tokens or agents are created. |

Optional Harvester features the plugin uses when present:

- **CDI** (DataVolumes): used to sync DataVolumes, and to copy an image to a different storage class for a root
  disk. If CDI isn't installed, DataVolume sync is skipped.
- **metrics-server** (`metrics.k8s.io`): used for host CPU and memory usage.
- **QEMU guest agent** in the VM image: lets Harvester report the VM's IP addresses sooner. VMs without it still work.

---

## Installing the plugin

1. Build the jar (see [Building and development](#building-and-development)), or use a provided
   `morpheus-harvester-plugin-<version>-all.jar`.
2. In Morpheus, go to **Administration > Integrations > Plugins > Add**, and upload the jar.
3. The plugin shows up as **Harvester**. Uploading a newer jar replaces it in place. Existing clouds keep
   working, and their data is updated on the next refresh.

> Upload the `…-all.jar` from the GitHub release (or `build/libs/` for a local build). The Morpheus plugin API
> jar is not a plugin, and uploading it fails with "Failed to parse plugin file".

---

## Adding a Harvester cloud

### 1. Get credentials

Pick one:

- **API key (recommended).** In Harvester, click your avatar (top right) > **Account & API Keys** > **Create API
  Key**. Leave the scope as **No Scope**, set an expiry if you want, and copy the **Bearer Token**
  (`token-xxxxx:yyyyyyyy…`). Harvester shows it only once.
- **Access key and secret key** of an API key: enter them as the username and password.
- **Username and password** of a Harvester local user with admin rights. You can type them in or pick a stored
  Morpheus credential.

### 2. Create the cloud

Go to **Infrastructure > Clouds > + Add > Harvester**. The cloud fields are:

| Field | Required | What it does |
|---|---|---|
| **API URL** | yes | Harvester address, for example `https://10.0.0.10` (the cluster VIP). The plugin calls the API at `https://10.0.0.10/k8s/clusters/local`. Entering that full URL also works. A Kubernetes API URL ending in `:6443` is used as is. |
| **API Key** | one of the credential options | The bearer token `token-xxxxx:yyyy…`. |
| **Credentials** | | A stored Morpheus username/password credential, or **Local** to type them in below. |
| **Username / Password** | | A Harvester username and password, or an API key's access key and secret key. |
| **Sockets per Host** | no (default `1`) | Number of physical CPU sockets in each Harvester node. The Harvester API reports cores but not sockets. See [Hosts](#hosts--nodes). |
| **Inventory Existing Instances** | no (default on) | Import VMs that already exist in Harvester as discovered (unmanaged) servers. If off, only VMs provisioned from Morpheus are tracked. |
| **Enable Hypervisor Console** | no (default on) | Offer the hypervisor console: the VM's screen over VNC through Harvester, which needs no network access to the VM. Morpheus reads this setting itself. Clouds created before the setting existed have it switched on at their next refresh. |
| **Show Cluster Health** | no (default on) | Set the cloud status to **Warning**, with the reason, when the Harvester cluster has problems. See [Cluster health](#cluster-health). |

Save. Morpheus checks the connection (a test call that lists namespaces) and runs the first sync. Connection
errors explain what went wrong:

- **"Harvester rejected the credentials"** (HTTP 401): wrong, expired or deleted key or password.
- **"…does not have enough permissions"** (HTTP 403): the user isn't an admin, or the key has a scope.
- **"Unable to reach the Harvester API at …"**: wrong address, the network is blocked, or there's a TLS problem.
  The plugin accepts self-signed certificates.

### 3. Set the cloud's group, then provision

Add the cloud to a group (**Infrastructure > Groups**) as with any cloud. Then provision with
**Provisioning > Instances > + Add > Harvester**. See [Provisioning a VM](#provisioning-a-vm).

### Credentials over time

- **API key:** used as is. If it expires or is deleted, the cloud goes **offline** until you edit the cloud and
  enter a new one.
- **Username and password:** the plugin logs in (`POST /v3-public/localProviders/local?action=login`) and caches
  the session token. It logs in again when Harvester rejects the token (HTTP 401), so password-based clouds renew
  themselves.

---

## How the cloud works

### The connection record

Each Harvester cloud has one hidden connection record, a Morpheus `ComputeServerGroup` of type
`harvester-cluster`. It holds the API URL, the credentials and the sync state. The cloud creates, updates and
removes it, and it can appear under **Infrastructure > Clusters** with the cloud's name. **Don't edit or delete
it.** Harvester is a cloud only: there is no "Add Cluster > Harvester" option.

### Refresh cycle

On every cloud refresh (Morpheus's cloud refresh interval, 5 minutes by default, or **Refresh** on the cloud
page), the plugin:

1. Makes sure the connection record matches the cloud's settings (URL, credentials, Inventory Existing Instances).
2. Checks the connection. If it fails, the cloud is marked **offline** with the error, and nothing else runs.
3. Syncs, in this order:

| Order | Harvester | Becomes in Morpheus |
|---|---|---|
| 1 | Nodes | Hosts (hypervisors) |
| 2 | Namespaces | Resource pools |
| 3 | Storage classes, with capacity | Datastores |
| 4 | PersistentVolumeClaims | Storage volumes |
| 5 | DataVolumes (if CDI is installed) | Storage volumes |
| 6 | VM images | Virtual images |
| 7 | NetworkAttachmentDefinitions + management network | Networks |
| 8 | VirtualMachines (+ VirtualMachineInstances) | Compute servers (and their disks, NICs and tags) |
| 9 | VM snapshots (VirtualMachineBackups of type `snapshot`) | Snapshots of those servers |

Each sync adds what's new, updates what changed, and removes what's gone, matching on the Harvester object's
`metadata.uid`. Removing a cloud removes all of its synced records from Morpheus. Nothing in Harvester is touched.

4. Checks the cluster's health (when **Show Cluster Health** is on), below.

### Cluster health

After the sync, the plugin checks the Harvester cluster and sets the cloud status Morpheus shows on the cloud's
summary page and in the clouds list: **OK** when nothing is wrong, otherwise **Warning** with the problems as the
status message, for example `Harvester health: 1 of 3 nodes not ready (node2); 2 Longhorn volumes degraded;
1 critical alert (KubeNodeNotReady)`. The status is updated on every refresh, so it clears by itself.

| Check | Source | Reported when |
|---|---|---|
| Nodes | `GET /api/v1/nodes` | A node's `Ready` condition isn't `True`; `MemoryPressure`, `DiskPressure` or `PIDPressure` is `True`; a node is in maintenance mode (`harvesterhci.io/maintain-status`) or cordoned |
| Storage | `GET /apis/longhorn.io/v1beta2/namespaces/longhorn-system/volumes` | A Longhorn volume's `status.robustness` is `degraded` or `faulted` |
| Alerts | Alertmanager of Harvester's **rancher-monitoring** add-on, through the Kubernetes service proxy: `GET /api/v1/namespaces/cattle-monitoring-system/services/http:rancher-monitoring-alertmanager:9093/proxy/api/v2/alerts?active=true&silenced=false&inhibited=false` | Alerts with severity `critical` or `warning` are firing (`Watchdog` and `InfoInhibitor` are ignored). Silence an alert in Alertmanager to stop it being reported. |

A check whose API isn't there (for example the monitoring add-on is off) is skipped. Health problems never stop
the sync or mark the cloud offline; turn **Show Cluster Health** off to always show OK while connected. Host CPU,
memory and storage usage on the summary page come from the node sync (metrics-server), as before.

---

## Mapping reference: Morpheus ↔ Harvester

### Cloud ↔ Harvester cluster

| Morpheus | Harvester |
|---|---|
| Cloud (type **Harvester**, code `harvester`) | One Harvester cluster, reached at `https://<vip>/k8s/clusters/local` |
| Cloud status | Offline when the connection check fails; Warning with the reason when [cluster health](#cluster-health) finds problems; otherwise OK |
| Cloud > Resources > Pools | Namespaces |
| Cloud > Resources > Datastores | VM-capable storage classes |
| Cloud > Resources > Networks | VM networks (NetworkAttachmentDefinitions) + management network |
| Cloud > Hosts | Nodes |
| Cloud > VMs | VirtualMachines |

### Hosts ↔ Nodes

Each Harvester node becomes a **host** of the cloud: a VM hypervisor, not managed and with no agent. Each VM is
linked to the host it runs on.

| Morpheus host field | Harvester node source |
|---|---|
| Name | `metadata.name` |
| External ID / unique ID | `metadata.uid` |
| Server type | `harvesterMaster` when the node has the `control-plane` or `master` role label, otherwise `harvesterWorker` |
| Type | Hypervisor (`serverType: hypervisor`), not provisionable, `managed: false`, `discovered: true` |
| Internal IP | Node address of type `InternalIP` |
| External IP | Node address of type `ExternalIP` (falls back to the internal IP) |
| Hostname | Node address of type `Hostname` (falls back to the name) |
| SSH host / user | Internal IP / `root` (for reference; the plugin never logs in to nodes) |
| Power state | `on` when the node's `Ready` condition is `True`, otherwise `off` |
| Cores (`maxCores`) | `status.capacity.cpu` |
| Sockets (`maxCpu`) | The cloud's **Sockets per Host** setting |
| Cores per socket | Cores ÷ sockets |
| Memory (`maxMemory`) | `status.capacity.memory` |
| Storage (`maxStorage`) | `status.capacity.ephemeral-storage` |
| CPU / memory used | `metrics.k8s.io` node metrics (0 if metrics-server isn't available) |
| OS | Platform `linux` (Harvester OS). No guest OS is recorded, so hosts aren't counted as guest VMs. |
| Category | `harvester.node.cluster.<connection id>` |

### Resource pools ↔ Namespaces

Each Harvester namespace that can hold VMs becomes a **resource pool** of the cloud (**Infrastructure > Clouds >
cloud > Resources**). VMs are provisioned into the namespace of the pool you pick.

**Not synced:**

- **System namespaces:** `kube-system`, `kube-public`, `kube-node-lease`, `harvester-system`,
  `harvester-public`, `longhorn-system`, every `cattle-*` and `fleet-*` namespace, `local`,
  `cluster-fleet-local-local`, `p-local`, `rancher-vcluster`, `kubevirt`, `cdi`, `forklift`, `tigera-operator` and
  `calico-system`.
- **Namespaces starting with** `cattle-`, `fleet-`, `kube-`, `longhorn-`, `calico-` or `tigera-`.
- **Rancher-generated namespaces** (`p-xxxxx`, `c-m-xxxxxxxx`, `u-xxxxxxxxxx`, `user-xxxxx`).

| Morpheus pool field | Harvester namespace source |
|---|---|
| Name / display name | `metadata.name` |
| External ID | `metadata.uid` |
| Type | `default` |
| Owner | The cloud (`refType: ComputeZone`, `refId: <cloud id>`) |
| Active / removable | Yes / Yes (except `default`, see below) |
| Category | `harvester.namespace.cluster.<connection id>` |

**Managing pools from Morpheus:**

- **Add pool:** creates the namespace in Harvester. The name must be a valid namespace name: lowercase letters,
  digits and `-`, starting and ending with a letter or digit, up to 63 characters. A pool created in Morpheus is
  linked to its namespace straight away. A namespace created in Harvester appears on the next refresh.
- **Delete pool:** deletes the namespace. Deleting a namespace in Harvester deletes everything in it, so the plugin
  **refuses** while the namespace still holds VMs, volumes (PVCs) or images. It never deletes `default` or a
  system namespace. A pool that isn't linked to a namespace is just removed from Morpheus.
- **Rename pool:** not allowed. Namespaces can't be renamed.

Networks and datastores are available in every pool, because Harvester lets a VM in any namespace use any VM
network or storage class.

The cloud also has an internal cluster-wide pool (category `harvester.cluster.<connection id>`). It is hidden and
not selectable.

### Datastores ↔ Storage classes

Each storage class that **VMs can use** becomes a **datastore** of the cloud. That means classes whose provisioner
is Longhorn (`driver.longhorn.io`), or a third-party CSI driver registered in Harvester's `csi-driver-config`
setting.

**Not synced:**

- Harvester's per-image storage classes (`longhorn-image-*` and other image classes).
- `longhorn-static`.
- Classes from any other provisioner.

When a class stops qualifying or is deleted, its datastore is removed. If Morpheus volumes are still on it, it's
deactivated instead.

| Morpheus datastore field | Harvester storage class source |
|---|---|
| Name | `metadata.name` (this is the storage class name used when creating disks) |
| External ID / unique ID | `metadata.uid` |
| Type | `provisioner` |
| Size (`storageSize`) | Capacity, calculated as below (blank when unknown) |
| Free space (`freeSpace`) | Free capacity, calculated as below (blank when unknown) |
| Volume type | **Harvester Volume** (`harvesterPVC`) |
| Owner | The cloud (`refType: ComputeZone`) |
| Assigned pools | Every namespace pool |
| Config | `provisioner`, `parameters`, `allowVolumeExpansion`, `reclaimPolicy`, `volumeBindingMode` |
| Category | `harvester.storageClass.cluster.<connection id>` |

**How capacity is calculated:**

- **Longhorn classes:**
  - **Disks counted:** the Longhorn disks the class can place replicas on. The disk tags must match the class's
    `diskSelector`, the node tags its `nodeSelector`, and scheduling must be enabled on both the node and the disk.
    Disks requested for eviction don't count, and the disk type has to match (`filesystem`, or `block` when
    `dataEngine: v2`).
  - **Size** = Σ (disk maximum − reserved) ÷ replicas.
  - **Free** = Σ min((maximum − reserved) × over-provisioning % − scheduled, available − maximum × minimal-available %) ÷ replicas.
    Both percentages come from the Longhorn settings `storage-over-provisioning-percentage` (default 100) and
    `storage-minimal-available-percentage` (default 25). Free space is what Longhorn will still accept, so with
    over-provisioning above 100% it can be more than the disks physically hold.
  - **Replicas** = the class's `numberOfReplicas` (default 3), capped at the number of matching nodes.
- **Other CSI drivers:** the sum of the driver's `CSIStorageCapacity` objects for the class, as free space. Size is
  left blank. If the driver publishes none, both are blank.

When provisioning, a datastore's free space is checked: see [Disks and datastores](#disks-and-datastores).

### Storage volumes ↔ PVCs and DataVolumes

PVCs and DataVolumes in non-system namespaces are synced as Morpheus **storage volumes**. Volumes that belong to
a VM are also linked to that VM's server: see [VMs](#compute-servers-and-instances--virtual-machines).

| Morpheus volume field | PVC (`harvesterPVC`) | DataVolume (`harvesterDataVolume`) |
|---|---|---|
| Name | `metadata.name` | `metadata.name` |
| External ID | `metadata.uid` | `metadata.uid` |
| Size (`maxStorage`) | `spec.resources.requests.storage` | requested size |
| Datastore | Datastore of `spec.storageClassName` | Datastore of its storage class |
| Pool name | Namespace | Namespace |
| Internal ID | `<storageClass>:<namespace>:<name>` | `<storageClass>:<namespace>:<name>` |
| Config | phase, owner references, data source | phase, owner references, data source, claim name |
| Category | `harvester.persistentVolumeClaim.cluster.<id>` | `harvester.dataVolume.cluster.<id>` |

### Virtual images ↔ VM images

Each Harvester **VM image** (`virtualmachineimages.harvesterhci.io`) that has finished importing becomes a
Morpheus **virtual image**. That means the `Imported` condition is `True`, or progress is 100, and the image isn't
being deleted. Images in every namespace are synced. They're listed under **Library > Virtual Images** and in the
provisioning **Image** list.

| Morpheus virtual image field | Harvester image source |
|---|---|
| Name | `spec.displayName` (or the `harvesterhci.io/imageDisplayName` label, or `metadata.name`) |
| External / unique / internal ID | `metadata.uid` |
| Description | `field.cattle.io/description` annotation |
| Platform | `linux`, `windows` or `mac`, worked out from the OS (below), default `linux` |
| OS type | Morpheus OS type matched from the detected OS and version |
| Image type | `iso` for ISO images, otherwise `raw`. An image is an ISO when its `harvesterhci.io/image-type` label (set by Harvester's UI) is `iso`, or its name, display name or URL ends in `.iso`. |
| Minimum disk | `status.virtualSize` (or `status.size`), so the root disk is at least as large as the image. None for ISO images, which don't become the root disk. |
| Folder | The image's namespace |
| Interface name | `Ethernet` for Windows, `eth0` otherwise |
| Config: `imageName`, `namespace` | `metadata.name`, `metadata.namespace` |
| Config: `imageId` | `<namespace>/<name>`: what Harvester puts in the `harvesterhci.io/imageId` annotation |
| Config: `imageStorageClassName` | `status.storageClassName` (`longhorn-<image>` for Longhorn backing images) |
| Config: `sourceType`, `url` | `spec.sourceType` (download, upload, …), `spec.url` |
| UEFI / Secure Boot | **Not** from Harvester: set these on the virtual image in Morpheus if the image needs UEFI. VMs boot with BIOS otherwise. |
| Category | `harvester.image.template.<connection id>` |

**How the OS is detected:**

1. From the image labels, if present: `harvesterhci.io/os-type` (or `harvesterhci.io/os`, or `os`), plus
   `harvesterhci.io/os-version` (or `os-version`).
2. Otherwise from the display name, for example `ubuntu-22.04-server`, `rocky-9`, `win2022`.

The recognized OS families are CentOS, CentOS Stream, RHEL, Ubuntu, Debian, Fedora, Rocky, Alma, openSUSE, SLES
and Windows (desktop versions, and Server 2016/2019/2022/2025 as `2k16`, `2019` and so on). Anything else is
`unknown`, with platform `linux`. To get the right OS type, label the image in Harvester (Images > Edit > Labels),
or edit the virtual image in Morpheus.

**Using an image in a layout:** Harvester node types have a **Virtual Image / OS Type** picker. Choose
**VM Image** to pick a specific Harvester image (a searchable list), or **OS Type** to resolve the image at
provisioning time.

### Networks ↔ VM networks

Each Harvester **VM network** (`network-attachment-definitions.k8s.cni.cncf.io`) becomes a Morpheus **network**,
with type **Harvester Network**. Harvester's built-in **management network** (the `mgmt` cluster network, used as
the pod network) is added as `default-pod-network`.

| Morpheus network field | Harvester source |
|---|---|
| Name | NAD `metadata.name` |
| Display name | `<namespace>/<name>` (this is what you pick when provisioning) |
| External ID / unique ID | NAD `metadata.uid` (the `mgmt` ClusterNetwork uid for the management network) |
| Bridge | `bridge` in the NAD config |
| VLAN ID | `vlan` in the NAD config |
| CIDR / gateway | `ipam.subnet` / `ipam.gateway` in the NAD config, when set (editable in Morpheus) |
| Config | The full NAD config JSON |
| Assigned pools | Every namespace pool |
| Category | `harvester.network.<connection id>` |

A NIC on `default-pod-network` uses KubeVirt's masquerade (NAT) on the pod network. A NIC on any other network is
bridged onto that VM network.

### Compute servers and instances ↔ Virtual machines

Each Harvester **VirtualMachine** (`virtualmachines.kubevirt.io`) becomes a Morpheus **compute server**. The
running **VirtualMachineInstance** provides the live details: node, IPs and status.

- **VMs provisioned from Morpheus:** a managed server with an **instance**.
- **VMs that already existed in Harvester:** imported as discovered, unmanaged servers (server type **Harvester
  Instance**), when **Inventory Existing Instances** is on. Convert them to managed with **Actions > Convert to
  Managed** (see [Day-2 operations](#day-2-operations)).
- **Skipped:** VMs in system namespaces, and VMs that Morpheus is still provisioning, which carry the label
  `morpheus-label.vm.provisioning=true`.

| Morpheus server field | Harvester source |
|---|---|
| Name | VM `metadata.name` |
| External ID / unique ID | VM `metadata.uid` |
| Resource pool | Pool of the VM's namespace |
| Config `project.name` | The VM's namespace |
| Power state | `status.printableStatus`: `Running` → on, `Stopped` → off, `Paused` → paused, anything else → unknown |
| Cores per socket / sockets | `spec.template.spec.domain.cpu.cores` / `.sockets` |
| Total cores | cores × sockets |
| Memory | `resources.limits.memory`; for a VM with CPU and memory hot add, `spec.template.spec.domain.memory.guest` (the limits hold its maximum) |
| Hot resize | On when the VM has the annotation `harvesterhci.io/enableCPUAndMemoryHotplug: "true"` |
| Plan | A Morpheus plan for the Harvester provision type with the same memory and cores, otherwise the **Custom** plan with the VM's actual sizing |
| OS type | `vm.kubevirt.io/os` annotation on the VM template (or the instancetype preference name), mapped to a Morpheus OS type (for example `rhel9` → Red Hat, `windows2k22` → Windows Server 2022, `ubuntu` → Ubuntu) |
| IP address (internal/external/SSH host) | First IP the VMI reports on its interfaces (`0.0.0.0` until there is one) |
| Host (parent server) | The host of the node in `status.nodeName` |
| Disks (storage volumes) | `spec.template.spec.volumes` + `domain.devices.disks`: one volume per disk, linked to its PVC or DataVolume, with the size, datastore and controller from the disk bus (VirtIO, SCSI, SATA, IDE) |
| NICs (interfaces) | `domain.devices.interfaces` + `networks`: name, MAC address, network (`<namespace>/<NAD>`, or the pod network), and IP from the VMI |
| Tags | VM labels, see [Tags ↔ Labels](#tags--labels) |
| Console | **Hypervisor console** through Harvester's VNC endpoint (when the cloud's **Enable Hypervisor Console** is on). **Guest console** set to SSH (Linux) or RDP (Windows), as on other clouds. |
| Category | `harvester.vm.cluster.<connection id>` |

When a VM is deleted in Harvester, its server is removed from Morpheus on the next refresh.

### Tags ↔ Labels

A VM's **Labels** in Harvester (`metadata.labels`) and its **tags** in Morpheus are kept in sync **both ways** on
every refresh. Tags are on the instance when the VM has one, otherwise on the compute server.

| Change | Result on the next refresh |
|---|---|
| Label added, changed or removed in Harvester | Tag added, changed or removed in Morpheus |
| Tag added, changed or removed in Morpheus | Label added, changed or removed on the VM in Harvester |
| Same tag changed on both sides between refreshes | The **Morpheus** value wins |
| First sync of a VM | Both sides merged; if both have the key with different values, Harvester wins |

The plugin records the labels agreed at the last sync on each server (config `harvesterLabelSnapshot`). Comparing
both sides with that record is how it knows which side changed. If a Morpheus write fails, the record isn't
advanced, so the change is retried on the next refresh rather than mistaken for a deletion.

**Not synced:**

- **System labels:** labels in the domains `harvesterhci.io`, `kubevirt.io`, `kubernetes.io`, `k8s.io`,
  `cattle.io`, `longhorn.io` and `rancher.io`, including their subdomains such as `vm.kubevirt.io` or
  `app.kubernetes.io`.
- **The plugin's own labels:** `morpheus-label.*`.
- **Instance Labels** (`spec.template.metadata.labels`): these are copied onto the running VM pod at start-up and
  are a different thing from the VM's Labels.
- **Morpheus tags that aren't valid Kubernetes labels** stay in Morpheus only. That means names with spaces, a
  value with `/` or spaces, or more than 63 characters. Kubernetes labels need letters, digits, `-`, `_` and `.`,
  starting and ending with a letter or digit, with an optional DNS prefix and `/` in the name.

Adding a label in Harvester's **Edit Config** page makes Harvester offer a restart, because that page saves the
whole VM. Labels don't need one: skip the restart, or add labels through **Edit YAML** or from Morpheus. Writing
labels from Morpheus never restarts the VM.

---

## Provisioning a VM

**Provisioning > Instances > + Add > Harvester**, or a layout on a Harvester node type, then:

| Wizard field | What it maps to |
|---|---|
| **Group / Cloud** | A group that contains the Harvester cloud |
| **Resource Pool** (required) | The Harvester **namespace** the VM, its disks, cloud-init secret and sysprep ConfigMap are created in |
| **Name** | VM name (`metadata.name`). Must be a DNS-1123 name: lowercase letters, digits, `-` and `.`. |
| **Hostname** | `spec.template.spec.hostname` (defaults to the name) |
| **Plan** | Core count (total vCPUs), cores per socket and memory. Pick one of the fixed sizes (see [Service plans](#service-plans)) or **Custom**, where you set **CPU**, **Cores**, **Cores Per Socket**, memory and disk sizes. **Cores** is the total vCPU count and must be a whole multiple of the cores per socket; **CPU** is the number of sockets, used to work out cores per socket when that isn't given (8 cores, CPU 2 → 2 sockets × 4 cores). |
| **Image** (required) | Harvester image for the root disk |
| **Volumes** | Root disk + data disks: name, size and **Datastore** (storage class) each. See below. |
| **Networks** | One or more NICs, each on a Harvester network (`<namespace>/<name>`) or `default-pod-network` |
| **Skip Network Wait** | Finish without waiting for an IP address or the Morpheus agent. See below. |
| **CPU and Memory Hot Add** | Lets CPUs and memory be added while the VM runs. See [CPU and memory hot add](#cpu-and-memory-hot-add). Off by default. |
| **Cloud-init** (Linux) / **sysprep** (Windows) | The usual Morpheus user data, users, keys, scripts and network settings. See below. |
| **Labels** | Morpheus labels, copied to the VM as `morpheus-label.1`, `morpheus-label.2`, … |
| **Tags** | Synced to the VM's Labels after provisioning (see [Tags ↔ Labels](#tags--labels)) |

### Validation before anything is created

- VM and volume names must be valid DNS-1123 names, and volume names must be unique.
- Networks can't repeat, and all NICs must use VM networks from the **same namespace**. `default-pod-network`
  is exempt.
- **Disk sizes per datastore** must fit its free space (see below).
- Harvester **dry-runs** the VM before it's created, so Harvester-side errors show up before any change is made.

### Disks and datastores

Each disk has a **Datastore** list: a Harvester storage class, or **Auto**.

| Disk | Datastore choice | What the plugin creates |
|---|---|---|
| Root | **Auto**, or the image's own storage class | A PVC on the image's storage class (`longhorn-<image>`) with the `harvesterhci.io/imageId` annotation. Longhorn fills it from the image. This is the fastest option and what Harvester's UI does. |
| Root | Any other storage class | A temporary PVC from the image (`tmp-<…>-img`), then a CDI DataVolume (`dv-<server>-<volume>`) that **copies** it onto the chosen class. The temporary PVC is then deleted. This takes roughly as long as copying the image. If the class waits for a consumer (`WaitForFirstConsumer`), the copy finishes when the VM starts, and the temporary PVC is left for you to delete. |
| Data | **Auto** | A PVC on Harvester's default VM storage class |
| Data | A storage class | An empty PVC on that class |

All disks are PVCs named `pvc-<server name>-<volume name>` (DataVolumes `dv-…`), in the VM's namespace, with
`accessModes: [ReadWriteMany]`, `volumeMode: Block`, and the size in GiB. They're attached in order, root disk
first, with `bootOrder` 1, 2, … on the **VirtIO** bus. Each disk's serial is set to the Morpheus volume's ID, so
the guest can tell them apart.

**Free-space check:** the disk sizes chosen for each datastore are added up. If they're more than its free space
(from the last refresh), the wizard shows "Not enough space on datastore …" and nothing is created. **Auto** and
datastores with unknown capacity aren't checked.

### Networks

| Morpheus network | VM interface | VM network entry |
|---|---|---|
| `default-pod-network` | `masquerade`, model `virtio` | `pod: {}` |
| A Harvester VM network | `bridge`, model `virtio` | `multus.networkName: <namespace>/<name>` (or just `<name>` in the VM's own namespace) |

Interface names are the Morpheus NIC names in lowercase without spaces (for example `eth0`). After the VM boots,
each Morpheus NIC gets its MAC address and IP from Harvester.

### Cloud-init (Linux)

For Linux images with **cloud-init** enabled on the virtual image (the default):

1. Morpheus builds the user data (users, SSH keys, agent install, scripts), the network data and the metadata, as
   it does for any cloud.
2. The plugin stores them in a Kubernetes Secret named `secret-<server name>` in the VM's namespace, with keys
   `userdata` and `networkdata`.
3. The VM gets a `cloudinitdisk` (SCSI) that uses `cloudInitNoCloud` with `secretRef` and `networkDataSecretRef`
   pointing to that secret. Inline cloud-init isn't used, so passwords don't appear in the VM spec.
4. After provisioning finishes, the cloud-init disk is detached from the VM (best effort). The secret is deleted
   when the VM is deleted.

### Sysprep (Windows)

For Windows images:

1. The VM is created **stopped** (`runStrategy: Halted`).
2. Morpheus's unattend XML is updated with each NIC's real MAC address, then stored in a ConfigMap named
   `<server name>-sysprep`.
3. The ConfigMap is attached to the VM as a `sysprep` volume, and the VM is started.
4. The ConfigMap is deleted when the VM is deleted.

### Boot, network wait and finishing

Provisioning goes through these steps, shown on the instance's history:

| Step | What happens |
|---|---|
| **Creating Volumes** | PVCs/DataVolumes are created (and image copies made) |
| **Deploy Virtual Machine** | The VM is dry-run, then created. The plugin waits for `Running`, checking every 5 s for up to about 5 minutes. |
| **Wait for VM Boot** | Waits for the VMI's `Ready` condition (every 5 s, up to 5 minutes). Then waits for the QEMU guest agent, every 5 s for up to 90 s, stopping as soon as the VM reports a routable IP. |
| **Wait for Network** | Pings the reported IPs from the Morpheus appliance for about a minute per IP family. If none answers, the primary interface's reported IP is used. |

Then the plugin records the node the VM runs on (the host), the IPs and NIC MAC addresses, and removes the
`morpheus-label.vm.provisioning` label. Morpheus then does its usual finishing work (agent install or network
wait), unless **Skip Network Wait** is set.

**Skip Network Wait**, a checkbox on the Harvester instance type, is for ISO installs and VMs with no network
configured:

- "Wait for VM Boot" ends as soon as the VM is Running and Ready.
- There's no guest-agent wait and no ping.
- Whatever IP Harvester already reports is recorded, if any.
- Morpheus doesn't install the agent or wait for the network.

### Provisioning from an ISO image

When the chosen image is an ISO, the VM is set up the way Harvester installs from an ISO:

- The **root disk** (and any other disk) is created **blank**, at the size and on the datastore you choose.
- The ISO is attached as a **SATA CD-ROM** that **boots first** (`cdrom-1`). Its volume is a PVC created from the ISO
  image, named `<vm>-cdrom-1` and labelled `morpheus-label.cdrom-vm=<vm>`, so it's deleted with the VM.

After the OS is installed, eject the CD-ROM on the **Harvester CD-ROM** tab (see [CD-ROMs](#cd-roms)). For Windows, attach the
VirtIO drivers ISO as a second CD-ROM before the first boot, so the installer can see the VirtIO disk. Use **Skip
Network Wait** for installs that don't bring up a network by themselves.

### CPU and memory hot add

The **CPU and Memory Hot Add** checkbox turns on Harvester's CPU and memory hotplug (Harvester 1.6 and later) for the
new VM, the same as **Enable CPU and memory hotplug** in the Harvester UI. The VM can then grow while it runs, up to
a maximum of its starting size times Harvester's `max-hotplug-ratio` setting (default 4, so a 2-core / 4 GB VM can grow
to 8 cores / 16 GB without a restart).

| VM spec | Value |
|---|---|
| `metadata.annotations` | `harvesterhci.io/enableCPUAndMemoryHotplug: "true"` |
| `domain.cpu` | `{cores: <cores per socket>, sockets: <sockets>, threads: 1, maxSockets: <sockets × ratio>}` |
| `domain.memory` | `{guest: <memory>Mi, maxGuest: <memory × ratio>Mi}` |
| `resources.limits` | `{cpu: <maxSockets × cores per socket>, memory: <maxGuest>Mi}`. Harvester sizes the VM's reservation from these, as for hotplug VMs it creates itself. |

- CPUs are added a **whole socket at a time**, so the core count must stay a multiple of the cores per socket. Use 1
  core per socket for the finest steps.
- The VM needs at least **1 GB** of memory, and must be live-migratable (Harvester moves it to apply a change).
- On reconfigure, Morpheus leaves the VM running when the VM is running, only CPU and/or memory grow, the new size is
  within the maximums and cores per socket doesn't change, and no disks or networks are added, removed or resized.
  The plugin then patches only `domain.cpu.sockets` and `domain.memory.guest`, as Harvester's **Edit CPU and Memory**
  does. Anything else (shrinking, going past the maximums, changing cores per socket) is a normal reconfigure with a
  restart, and the maximums are reset to the new size × ratio.
- ARM64 hosts only support memory hotplug; adding CPUs there needs a restart.

### Firmware and CPU

| Morpheus | VM spec |
|---|---|
| Core count and cores per socket | `domain.cpu: {cores: <cores per socket>, sockets: <core count ÷ cores per socket>, threads: 1}`, `resources.limits.cpu: <core count>`. With no cores per socket: one socket with all the cores. |
| Plan memory | `resources.limits.memory: <memory>Mi`, `domain.memory.guest: <memory − 100>Mi`. Harvester reserves 100 MiB for VM overhead, as its own UI does. |
| **CPU and Memory Hot Add** | See [CPU and memory hot add](#cpu-and-memory-hot-add) |
| Virtual image **UEFI** | `domain.firmware.bootloader.efi: {secureBoot: false}` |
| Virtual image **Secure Boot** | `domain.firmware.bootloader.efi: {secureBoot: true}` + `domain.features.smm.enabled: true` |
| Neither | BIOS (Harvester's default) |
| Always | Machine type `q35`, architecture `amd64`, ACPI on |

---

## What a provisioned VM looks like in Harvester

```yaml
apiVersion: kubevirt.io/v1
kind: VirtualMachine
metadata:
  name: <instance name>
  namespace: <resource pool / namespace>
  labels:
    <image labels>                       # copied from the Harvester image
    harvesterhci.io/image-name: <image>  # the image the VM was built from
    morpheus-label.1: <Morpheus label>   # one per Morpheus label
    <tag>: <value>                       # Morpheus tags, synced after provisioning
  annotations:
    <image annotations>
    vm.kubevirt.io/os: <OS type>
    harvesterhci.io/imageId: <namespace>/<image>
spec:
  runStrategy: Always                    # Windows: Halted until sysprep is attached, then started
  template:
    metadata:
      labels:
        harvesterhci.io/vmName: <name>
    spec:
      hostname: <hostname>
      architecture: amd64
      domain:
        machine: {type: q35}
        features: {acpi: {}}
        cpu: {cores: <cores per socket>, sockets: <sockets>, threads: 1}   # + maxSockets with hot add
        memory: {guest: <memory-100>Mi}
        resources: {limits: {cpu: "<core count>", memory: <memory>Mi}}
        devices:
          disks:
          - {name: <root volume>, bootOrder: 1, disk: {bus: virtio}, serial: <volume id>}
          - {name: <data volume>, bootOrder: 2, disk: {bus: virtio}, serial: <volume id>}
          - {name: cloudinitdisk, disk: {bus: scsi}}         # Linux, removed after provisioning
          interfaces:
          - {name: eth0, model: virtio, bridge: {}}          # masquerade: {} on the pod network
      networks:
      - {name: eth0, multus: {networkName: <namespace>/<network>}}   # pod: {} on the pod network
      volumes:
      - {name: <root volume>, persistentVolumeClaim: {claimName: pvc-<name>-<volume>}}
      - {name: cloudinitdisk, cloudInitNoCloud: {secretRef: {name: secret-<name>}, networkDataSecretRef: {name: secret-<name>}}}
```

Objects created in the namespace for a VM:

| Object | Name | Deleted with the VM |
|---|---|---|
| VirtualMachine | `<name>` | yes |
| PVC per disk | `pvc-<name>-<volume>` | yes |
| DataVolume (root disk copied to another class) | `dv-<name>-<volume>` | yes |
| Temporary image PVC (copy only) | `tmp-<name>-img` | deleted after the copy (left behind with `WaitForFirstConsumer` classes) |
| Cloud-init Secret (Linux) | `secret-<name>` | yes |
| Sysprep ConfigMap (Windows) | `<name>-sysprep` | yes |

---

## Day-2 operations

| Morpheus action | What happens in Harvester |
|---|---|
| **Start** | `PUT …/virtualmachines/<name>/start`, then waits for `Running` |
| **Stop** | `PUT …/virtualmachines/<name>/stop`, then waits for `Stopped` (no-op if already stopped) |
| **Restart** | `PUT …/virtualmachines/<name>/restart` |
| **Suspend** | Pauses the VMI (`…/virtualmachineinstances/<name>/pause`). Shows as **paused**. |
| **Reconfigure: plan / cores / memory** | Sets `domain.cpu.cores` (cores per socket) and `domain.cpu.sockets` from the new core count and cores per socket, plus `resources.limits` and the guest memory. If cores per socket isn't given, the VM keeps its socket count (and the core count must divide by it). The VM is restarted to apply it, except on a hot add VM that only grows (see [CPU and memory hot add](#cpu-and-memory-hot-add)). |
| **Reconfigure: resize a disk** | Expands the PVC (`spec.resources.requests.storage`). Disks can only grow. The storage class must allow expansion. |
| **Reconfigure: add a disk** | Creates a PVC on the chosen datastore and adds it to the VM's disks and volumes |
| **Reconfigure: remove a disk** | Removes the disk and volume from the VM (JSON patch, dry-run first). Morpheus then removes the volume, and the plugin's datastore provider deletes its PVC/DataVolume. |
| **Reconfigure: add a NIC** | Adds an interface and network to the VM. The network must be in the **VM's own namespace**, or be `default-pod-network`. Names can't repeat. |
| **Reconfigure: remove a NIC** | Removes the interface and network from the VM (JSON patch) |
| **Reconfigure: change a NIC's network** | Replaces the NIC's entry in `spec.template.spec.networks` (`multus.networkName`, or `pod` for `default-pod-network`) and its binding (`bridge` or `masquerade`), keeping its MAC address and model. The VM is restarted to apply it. |
| **Console: hypervisor** | noVNC to `wss://<harvester>/k8s/clusters/local/apis/subresources.kubevirt.io/v1/namespaces/<ns>/virtualmachineinstances/<name>/vnc`. The token is sent as a bearer header and as the Kubernetes `base64url.bearer.authorization.k8s.io.<token>` WebSocket subprotocol. The VM must be running, and the cloud's **Enable Hypervisor Console** must be on. |
| **Console: guest** | Morpheus's own guest console: SSH for Linux VMs, RDP for Windows VMs. It connects from the Morpheus appliance to the VM's IP with the instance's credentials, so the appliance needs network access to the VM. |
| **Delete** | Checks the VM's UID first: if a different VM now has that name, nothing is touched. Then deletes the VirtualMachine, the VMI if it's still there, the server's PVCs/DataVolumes, the cloud-init Secret (Linux) and the sysprep ConfigMap (Windows). |
| **Convert to Managed** (discovered VMs) | Finds the image the VM's boot disk was created from (the boot PVC's `harvesterhci.io/imageId`, or the VM's `harvesterhci.io/volumeClaimTemplates` annotation that Harvester's UI sets). It sets that image as the server's source image and turns the server into a managed instance. |
| **Snapshots: create** | Takes a Harvester VM snapshot. See [Snapshots](#snapshots). |
| **Snapshots: revert** | Restores the snapshot onto the VM. A running VM is stopped first and started again afterwards. |
| **Snapshots: delete** | Deletes the Harvester snapshot |
| **Edit tags** | Synced to the VM's Labels on the next refresh |
| **Harvester YAML** (server page) | View or edit the VM's YAML: see the next section |

Delete, stop and suspend first check that the VM in Harvester still has the UID Morpheus knows. That way a VM
that was deleted and recreated with the same name isn't touched by mistake.

---

## Service plans

The plugin creates these plans for the Harvester provision type. Core count and memory are fixed; the root disk size is the default and can be changed, and data disks can be added. Each plan is **2 sockets** with its cores split between them (4 CPU = 2 sockets × 2 cores per socket; the 1 CPU plans are 1 socket × 1 core) and is linked to the default price set. Change **Cores Per Socket** on a plan in **Administration > Plans** for a different layout. Use **Custom** (`custom-harvester`, listed last) for any other size: it has every custom option on (Custom CPU, Custom Cores, Custom Memory, Custom Root Storage, Custom Data Storage and Add Volumes), so you choose CPU (sockets), cores, cores per socket, memory and disks when provisioning.

| Code | Name | vCPUs | Sockets × cores per socket | Memory | Default root disk |
|---|---|---|---|---|---|
| `harvester-vm-1cpu-1gb` | 1 CPU, 1GB Memory | 1 | 1 × 1 | 1 GB | 20 GB |
| `harvester-vm-1cpu-2gb` | 1 CPU, 2GB Memory | 1 | 1 × 1 | 2 GB | 20 GB |
| `harvester-vm-2cpu-2gb` | 2 CPU, 2GB Memory | 2 | 2 × 1 | 2 GB | 40 GB |
| `harvester-vm-2cpu-4gb` | 2 CPU, 4GB Memory | 2 | 2 × 1 | 4 GB | 40 GB |
| `harvester-vm-2cpu-8gb` | 2 CPU, 8GB Memory | 2 | 2 × 1 | 8 GB | 40 GB |
| `harvester-vm-4cpu-8gb` | 4 CPU, 8GB Memory | 4 | 2 × 2 | 8 GB | 80 GB |
| `harvester-vm-4cpu-16gb` | 4 CPU, 16GB Memory | 4 | 2 × 2 | 16 GB | 80 GB |
| `harvester-vm-8cpu-16gb` | 8 CPU, 16GB Memory | 8 | 2 × 4 | 16 GB | 100 GB |
| `harvester-vm-8cpu-32gb` | 8 CPU, 32GB Memory | 8 | 2 × 4 | 32 GB | 100 GB |
| `harvester-vm-16cpu-32gb` | 16 CPU, 32GB Memory | 16 | 2 × 8 | 32 GB | 150 GB |
| `harvester-vm-16cpu-64gb` | 16 CPU, 64GB Memory | 16 | 2 × 8 | 64 GB | 150 GB |

Plans are editable under **Administration > Plans**: you can deactivate sizes you don't want, change prices or limit them to tenants. Morpheus keeps your changes when the plugin is updated. Plans created by an earlier plugin version are set to the 2-socket layout once, on the next cloud refresh; after that your changes are kept. On the next cloud refresh the plugin also turns every custom option on for an existing **Custom** plan, once (later changes are kept), and renames an older custom plan called "2 CPU, 4GB Memory" to **Custom** so it doesn't clash with the fixed plan of that name.

## Snapshots

Morpheus snapshots of Harvester VMs are **Harvester VM snapshots**: the same objects Harvester's **Take Snapshot**
creates (a `VirtualMachineBackup` with `spec.type: snapshot`, kept in the cluster with no backup target needed).
They show under **Backup & Snapshot > VM Snapshots** in Harvester and on the instance's **Snapshots** tab in Morpheus.

| Action | What happens |
|---|---|
| **Create** (instance **Actions > Create Snapshot**) | Creates a `VirtualMachineBackup` named after the snapshot name you enter. The name is turned into a valid Kubernetes name: lowercase, `-` for anything else, at most 63 characters. Without a name, it's `<vm>-<yyyyMMdd-HHmmss>`. The VM can be running. The plugin waits until Harvester reports the snapshot ready (up to 15 minutes), then records it in Morpheus with its creation time and size. |
| **Revert** | Harvester only restores onto a **stopped** VM. A running VM is stopped, then a `VirtualMachineRestore` is created with `newVM: false` and `deletionPolicy: retain`. The plugin waits for it to finish (up to 30 minutes), then starts the VM again if it was running. The restore gets new volumes; the VM's previous volumes are kept, because Harvester doesn't allow deleting them when restoring a snapshot. The VM's disks in Morpheus update on the next refresh. |
| **Delete** | Deletes the `VirtualMachineBackup`. A snapshot already deleted in Harvester is treated as deleted. |
| **Sync** (every refresh) | Ready snapshots of the cloud's VMs that Morpheus doesn't have yet (for example taken in Harvester's UI) are added and linked to their VM. Snapshots deleted in Harvester are removed from Morpheus. |

A Morpheus snapshot's external ID is `<namespace>/<snapshot name>`.

Before revert, both the VM and the snapshot are checked. The VM must still be the one Morpheus knows (same UID),
the snapshot must be ready, and it must be in the VM's namespace, since Harvester restores snapshots only in their
own namespace.

**Requirements and caveats**, mostly from Harvester:

- **Storage:** the VM's disks must be on storage that supports CSI snapshots. Longhorn does. A third-party CSI driver
  needs a volume snapshot class set in Harvester's `csi-driver-config` setting.
- **Running VMs:** snapshots of running VMs freeze the guest filesystems through the QEMU guest agent. On some guests
  (RHEL 9, SLE Micro) this can fail without an SELinux policy, and Windows needs VSS.
- **Ubuntu guests:** set netplan's `dhcp-identifier: mac` before taking snapshots. Otherwise a restored VM can get the
  same DHCP address as another VM.
- **Quotas:** Harvester can limit snapshot space per namespace or per VM.

## CD-ROMs

Harvester VMs (**Infrastructure > Compute**) have a **Harvester CD-ROM** tab for roles with **Harvester: Manage CD-ROM**
at Full (see [Permissions](#permissions)). It manages CD-ROMs the way Harvester's own CD-ROM volumes work:

- **List:** the VM's CD-ROM drives (name, bus, boot order, volume). The tab renders without calling Harvester and
  loads the drives after the page opens, so it adds no wait to the page.
- **Attach ISO:** offers the cloud's **ISO images only**, loaded when you click it. It creates a PVC from the ISO
  (`<vm>-cdrom-<n>`, on the image's storage class with the `harvesterhci.io/imageId` annotation, labelled with the VM)
  and adds a SATA CD-ROM disk using it. **Boot from it first** gives it boot order 1 and moves the VM's other boot
  orders down by one.
- **Eject:** removes the CD-ROM disk and its volume from the VM, and deletes the volume when it's an ISO copy (as
  Harvester's eject does).
- **Restart VM now:** appears after a change to a running VM. Harvester applies CD-ROM changes when the VM restarts.
  A stopped VM gets them when it starts.

Changes are refused if the VM changed after the tab loaded it (`resourceVersion` check): reload the page and try
again. CD-ROMs aren't listed as disks on the instance in Morpheus, and the VM sync skips them.

## Harvester YAML tab

Every Harvester VM's page under **Infrastructure > Compute** (managed or discovered) has a **Harvester YAML** tab.

**Viewing** (anyone who can open the server):

- The VM's YAML as Harvester has it right now, fetched when the page opens. Reload the page to refresh it.
- Its namespace, name and status, and an **Open in Harvester** link.
- **Hidden:** `status`, `metadata.managedFields` and the `kubectl.kubernetes.io/last-applied-configuration`
  annotation, so the YAML is readable. Inline cloud-init `userData`/`networkData` show as "(hidden: N lines)",
  because they can contain passwords.

**Editing** (roles with the **Harvester: Edit VM YAML** permission at Full, see [Permissions](#permissions)):

1. **Edit** loads the complete VM object from Harvester, including cloud-init data and all annotations. Only
   `status` and `managedFields` are left out, and Harvester keeps those itself.
2. **Save** sends the YAML to Harvester as a full replace (`PUT`). Harvester first checks it with a **dry run**,
   and the change is only applied if that passes. Harvester's error message, if any, shows on the tab.
3. If the VM changed after you clicked Edit, Harvester refuses the save (`resourceVersion` conflict). Cancel and
   edit again.
4. YAML for a different VM or namespace is rejected.
5. If Harvester marks the VM as needing a restart (for example after CPU, memory, disk, network or Instance Label
   changes), the tab says so after saving.
6. The YAML can only contain Latin-1 characters (a Morpheus HTTP client limitation). You get a clear message if
   it doesn't.

Users can only open or save VMs in their own tenant, unless they're in the master tenant. Each edit and save is
logged with the user's name.

The tab isn't on instance pages on purpose. Any instance tab makes Morpheus build the plugin's copy of every
instance it shows, which made **every** instance page about 2 seconds slower. To get there from an instance, open
its VM from the instance page, or find it under **Infrastructure > Compute**.

---

## Harvester Console (kubectl)

Every Harvester host (**Infrastructure > Compute > Hosts**, the cloud's Harvester nodes) has a **Harvester Console**
tab: a command line for running kubectl-style commands against the whole Harvester cluster, with the output shown
above it. A plugin can't add a tab to the cloud's own page, and the connection record listed under Clusters doesn't
open as a normal cluster, so the console is on the hosts; it's the same cluster from any of them.

It's a **built-in subset of kubectl**, not the kubectl binary: the plugin reads the command and makes the matching
Kubernetes API calls through the cloud's Harvester connection (the same URL and credentials as the sync). Nothing is
installed on the Morpheus appliance.

| Command | Notes |
|---|---|
| `get <type>[,<type>] [name...]` | `-n <ns>` (default `default`), `-A`, `-o wide\|yaml\|json\|name`, `-l <selector>`, `--field-selector`, `--show-labels`, `--no-headers`, `type/name`, `get --raw /api/...`. Tables use the API server's own columns, so they match kubectl (including Harvester and KubeVirt types). |
| `describe <type> [name...]` | Name, labels, annotations, owner, spec and status, and the object's events |
| `logs <pod>` | `-c <container>`, `--tail N`, `--since 10m`, `-p` (previous), `--timestamps`. Output is capped at 1 MB. |
| `top nodes`, `top pods` | From metrics-server |
| `events`, `api-resources`, `api-versions`, `version`, `cluster-info`, `help` | |
| `apply -f -`, `create -f -` | Paste YAML or JSON in the **Manifest** box (several manifests separated by `---`). `apply` is a server-side apply (field manager `morpheus-harvester-console`). |
| `create namespace <name>`, `delete <type> <name...>` | `delete` needs names (no `--all`) |
| `patch <type> <name> -p '<json>'` | `--type merge\|json\|strategic`; default strategic for built-in types, merge for custom types |
| `label`, `annotate` | `key=value`, `key-` to remove, `--overwrite` |
| `cordon`, `uncordon`, `scale --replicas=N`, `rollout restart` | |

Types resolve like kubectl: plural, singular, short names (`vm`, `vmi`, `pvc`, `po`, `no`, …), kind, or
`name.group`. Not available: `exec`, `attach`, `port-forward`, `cp`, `edit` (use `get -o yaml` then `apply -f -`),
`run`, `drain` (use Harvester's maintenance mode) and `--watch`. The **↑**/**↓** keys recall earlier commands.

**Access** is controlled by two role permissions (Infrastructure section):

- **Harvester: Kubernetes Console (read-only)**: `get`, `describe`, `logs`, `top`, `events` and the other read
  commands. Secret values are shown as `<hidden>`.
- **Harvester: Kubernetes Console (read and write)**: everything, including commands that change the cluster, and
  Secret values.

Users only reach hosts of their own tenant (or any, from the master tenant). Every command is written to the
Morpheus log with the user and the cluster (search the log for `Harvester console`). Plugin API 1.3 has no way to write
to Morpheus's audit log.

## Permissions

| Permission (Administration > Roles) | Section | Effect |
|---|---|---|
| **Harvester: Edit VM YAML** (`harvester-vm-yaml-edit`) | Infrastructure | **Full**: can Edit and Save on the Harvester YAML tab. **None**: view only. Added by the plugin. |
| **Harvester: Manage CD-ROM** (`harvester-vm-cdrom`) | Infrastructure | **Full**: sees the **Harvester CD-ROM** tab and can attach ISO images, eject CD-ROMs and restart the VM from it. Added by the plugin. |
| **Harvester: Kubernetes Console (read-only)** (`harvester-kubectl-read`) | Infrastructure | **Full**: sees the **Harvester Console** tab on Harvester hosts and can run read commands. Secret values are hidden. |
| **Harvester: Kubernetes Console (read and write)** (`harvester-kubectl-write`) | Infrastructure | **Full**: can also run commands that change the cluster (apply, delete, patch, label, cordon, scale, …) and see Secret values. |
| Morpheus's own permissions | | Everything else (clouds, pools, instances, reconfigure, console, delete) uses the standard Morpheus permissions, as for any cloud |

After changing a role, users may need to log out and back in before the change applies to the YAML tab.

Morpheus doesn't remove a permission when a plugin stops registering it. If you see an older uncategorized entry
with the same name, it's the same permission.

---

## Morpheus types the plugin registers

| Kind | Code | Name | Notes |
|---|---|---|---|
| Cloud type | `harvester` | Harvester | Pools, datastores, networks, cloud-init; pool create/delete |
| Provision type | `harvester-provision-provider-plugin` | Harvester | VM provisioning; requires a resource pool; datastores per disk with Auto; root disk resizable; snapshots |
| Instance type | `harvester` | Harvester | With layout `harvester-1.0-single` ("Harvester VM") |
| Server types | `harvesterUnmanaged` | Harvester Instance | Discovered VMs |
| | `harvester-linux`, `harvester-windows` | Harvester Managed Linux / Windows VM | Provisioned/managed VMs, by platform |
| | `harvesterMaster`, `harvesterWorker` | Harvester Master / Worker | Hosts (VM hypervisors) |
| Service plan | `custom-harvester` | Custom | Every custom option on: CPU (sockets), cores, cores per socket (default 1), memory, root and data disks, add volumes |
| Service plans | `harvester-vm-<cpu>cpu-<mem>gb` | e.g. 2 CPU, 4GB Memory | Fixed sizes, listed under [Service plans](#service-plans) |
| Option types | `provisionType.harvester.image` | Image | Image list on the Harvester instance type |
| | `provisionType.harvester.skipNetworkWait` | Skip Network Wait | Checkbox |
| | `provisionType.harvester.cpuMemoryHotAdd` | CPU and Memory Hot Add | Checkbox |
| Node type options | `harvester-node-virtual-image-type`, `-virtual-image`, `-os-type` | VM Image / OS Type | Image picker on Harvester node types |
| Storage volume type | `harvesterPVC` | Harvester Volume | Used for all disks |
| | `harvesterDataVolume`, `harvesterDataSource` | Harvester DataVolume / DataSource | Synced only; not offered when provisioning |
| Storage controller types | `harvester-virtio`, `harvester-scsi`, `harvester-sata`, `harvester-ide` | Harvester VirtIO / SCSI / SATA / IDE | VirtIO and SCSI offered when provisioning |
| Network type | `harvesterNetwork` | Harvester Network | Not creatable from Morpheus |
| Datastore type | `harvester-datastore-provider` | Harvester datastore | Creates and deletes disks |
| Process steps | | Creating Volumes, Deploy Virtual Machine, Wait for VM Boot, Wait for Network | Shown on provisioning history |
| Option sources (datasets) | | Harvester images, node-type images, projects | Fill the image pickers |
| Server tab | `harvester-vm-yaml-server-tab` | Harvester YAML | See above |
| Plugin routes | `/harvester-vm-yaml/source`, `/harvester-vm-yaml/save` | | Behind the YAML tab's Edit/Save |
| Server tab | `harvester-vm-cdrom-server-tab` | Harvester CD-ROM | See [CD-ROMs](#cd-roms) |
| | `/harvester-vm-cdrom/list`, `/isos`, `/attach`, `/eject`, `/restart` | | Behind the CD-ROM tab |
| Permission | `harvester-vm-yaml-edit` | Harvester: Edit VM YAML | Infrastructure section |
| Permission | `harvester-vm-cdrom` | Harvester: Manage CD-ROM | Infrastructure section |

---

## Internal identifiers

These are useful when searching the Morpheus database, API or logs.

| Record | Category / code | Notes |
|---|---|---|
| Connection record (ComputeServerGroup) | category & code `harvester.cloud.<cloud id>` | Config `managedByCloud: true`, `importExisting` |
| Hidden cluster pool | `harvester.cluster.<connection id>` | |
| Namespace pools | `harvester.namespace.cluster.<connection id>` | Type `default` |
| Hosts | `harvester.node.cluster.<connection id>` | `serverType: hypervisor` |
| VMs | `harvester.vm.cluster.<connection id>` | `serverType: vm` |
| Datastores | `harvester.storageClass.cluster.<connection id>` | Code `…<connection id>.<class name>` |
| PVC volumes | `harvester.persistentVolumeClaim.cluster.<connection id>` | |
| DataVolume volumes | `harvester.dataVolume.cluster.<connection id>` | |
| Virtual images | `harvester.image.template.<connection id>` | |
| Networks | `harvester.network.<connection id>` | |

Server config keys:

| Key | Meaning |
|---|---|
| `project.name` | The VM's namespace |
| `harvesterLabelSnapshot` | Labels agreed at the last tag sync |
| `skipNetworkWait` | Skip Network Wait was chosen |
| `cpuMemoryHotAdd` | CPU and Memory Hot Add was chosen |

Volume config keys:

| Key | Meaning |
|---|---|
| `autoDatastore` | The disk's datastore was **Auto** |
| `imageCopySourcePvc` | Temporary image PVC left behind by a `WaitForFirstConsumer` copy |

Labels and annotations the plugin sets in Harvester:

| Key | Where | Meaning |
|---|---|---|
| `morpheus-label.vm.provisioning=true` | VM | Being provisioned by Morpheus (sync skips it). Removed when done. |
| `morpheus-label.<n>` | VM | Morpheus labels |
| `harvesterhci.io/image-name` | VM | Image the VM was built from |
| `harvesterhci.io/vmName` | VM template | VM name, as Harvester's UI sets it |
| `harvesterhci.io/imageId` | VM, root PVC | `<namespace>/<image>` |
| `vm.kubevirt.io/os` | VM | OS type |
| `morpheus-label.cdrom-vm=<vm>` | CD-ROM PVC | ISO volume the plugin created for the VM (deleted with the VM) |

---

## Harvester API calls

All calls go to `https://<harvester>/k8s/clusters/local` with `Authorization: Bearer <token>`. TLS certificates
aren't verified, so self-signed certificates work.

| Purpose | Endpoint |
|---|---|
| Login (username/password) | `POST /v3-public/localProviders/local?action=login` |
| Connection check, namespaces | `GET/POST/DELETE /api/v1/namespaces` |
| Nodes, metrics | `GET /api/v1/nodes`, `GET /apis/metrics.k8s.io/v1beta1/nodes` |
| Storage classes | `GET /apis/storage.k8s.io/v1/storageclasses` |
| VM-capable CSI drivers | `GET /apis/harvesterhci.io/v1beta1/settings/csi-driver-config` |
| Longhorn capacity | `GET /apis/longhorn.io/v1beta2/namespaces/longhorn-system/nodes`, `…/settings/storage-over-provisioning-percentage`, `…/settings/storage-minimal-available-percentage` |
| CSI capacity | `GET /apis/storage.k8s.io/v1/csistoragecapacities` |
| PVCs | `GET /api/v1/persistentvolumeclaims`; `POST/PATCH/DELETE /api/v1/namespaces/<ns>/persistentvolumeclaims[/<name>]` |
| DataVolumes | `GET /apis/cdi.kubevirt.io/v1beta1/datavolumes`; create/delete per namespace |
| Images | `GET /apis/harvesterhci.io/v1beta1/virtualmachineimages` |
| VM networks | `GET /apis/k8s.cni.cncf.io/v1/network-attachment-definitions`, `GET /apis/network.harvesterhci.io/v1beta1/clusternetworks/mgmt` |
| VMs | `GET /apis/kubevirt.io/v1/virtualmachines`, `…/virtualmachineinstances`; `POST/PUT/PATCH/DELETE …/namespaces/<ns>/virtualmachines[/<name>]` (dry run with `?dryRun=All`) |
| Power | `PUT /apis/subresources.kubevirt.io/v1/namespaces/<ns>/virtualmachines/<name>/start\|stop\|restart`, `…/virtualmachineinstances/<name>/pause` |
| Console | `wss://…/apis/subresources.kubevirt.io/v1/namespaces/<ns>/virtualmachineinstances/<name>/vnc` |
| Cloud-init / sysprep | `POST/DELETE /api/v1/namespaces/<ns>/secrets`, `…/configmaps` |
| Snapshots | `GET /apis/harvesterhci.io/v1beta1/virtualmachinebackups`; `POST/GET/DELETE …/namespaces/<ns>/virtualmachinebackups[/<name>]`; `POST/GET …/namespaces/<ns>/virtualmachinerestores[/<name>]` |
| Events (error details) | `GET /api/v1/namespaces/<ns>/events` |
| Version | `GET /apis/harvesterhci.io/v1beta1/settings/server-version` |
| Cluster health | `GET /api/v1/nodes`, `GET /apis/longhorn.io/v1beta2/namespaces/longhorn-system/volumes`, `GET /api/v1/namespaces/cattle-monitoring-system/services/http:rancher-monitoring-alertmanager:9093/proxy/api/v2/alerts` |
| Hot add maximum | `GET /apis/harvesterhci.io/v1beta1/settings/max-hotplug-ratio` |

---

## Troubleshooting

The plugin's log lines are in the Morpheus UI log, with logger names starting `c.h.m.h.p`:

```bash
grep -E "Harvester|VmTagsSync|VmYamlView|HarvesterVmYaml" /var/log/morpheus/morpheus-ui/current | tail -50
```

| Symptom | Check |
|---|---|
| Cloud offline | The error on the cloud page. A 401 means the key or password is wrong, expired or deleted. A 403 means the user isn't an admin or the key has a scope. Otherwise check that the appliance can reach `https://<vip>`. |
| A namespace isn't a pool | It's a system or Rancher-generated namespace, or the next refresh hasn't run yet |
| A storage class isn't a datastore | Its provisioner isn't Longhorn or a driver in `csi-driver-config`, or it's an image class or `longhorn-static` |
| Datastore size/free is blank | Non-Longhorn driver without `CSIStorageCapacity`, or the Longhorn node list couldn't be read |
| An image is missing | It hasn't finished importing (`Imported` not `True`) |
| Image OS type is wrong | Add `harvesterhci.io/os-type` / `os-version` labels to the image, or edit the virtual image's OS type |
| VM stuck at "Wait for VM Boot" | The VM isn't reaching `Ready` (check it in Harvester), or the image has no guest agent and no IP within 90 s. Use **Skip Network Wait** for ISO installs. |
| No IP on the instance | The VM's network has no DHCP, the guest agent is missing, or the appliance can't ping the VM network |
| Root disk on another storage class is slow | Expected: the image is copied with CDI |
| Pool delete refused | The namespace still holds VMs, PVCs or images, or it's `default` or a system namespace |
| Tag didn't sync | Wait for the next refresh. Check that it's a valid Kubernetes label (see above). See the `VmTagsSync` log lines. |
| YAML tab Edit refused (HTTP 403) | The role lacks **Harvester: Edit VM YAML** at Full. Log out and back in after changing the role. |
| Snapshot fails | The error on the instance's history shows Harvester's message, for example a missing volume snapshot class for third-party storage, or a guest filesystem freeze error |
| Snapshot taken in Harvester isn't in Morpheus | It shows after it's ready and the next refresh runs, and only for VMs Morpheus knows |
| No ISO images to attach | The image isn't detected as an ISO: give it the `harvesterhci.io/image-type: iso` label, or a name ending in `.iso`, then refresh the cloud |
| CD-ROM attached but not visible in the guest | The VM needs a restart: use **Restart VM now** |
| Plugin upload fails | Upload the `…-all.jar` from the GitHub release (or `build/libs/`), not the Morpheus plugin API jar. The lines after "Harvester: Initializing Plugin" in the log show the cause. |

---

## Known limitations

- **Licensing:** Morpheus decides per cloud type how a cloud is licensed. This cloud type is licensed per VM, and
  its hosts are counted too. Setting sockets per host doesn't switch it to socket-based licensing, and a plugin
  can't change that.
- **Backups** aren't available: the backup provider is stubbed and not registered.
- **Hot resize** only applies to VMs provisioned with **CPU and Memory Hot Add** (or created in Harvester with hotplug), and only for growing CPU (whole sockets) and memory within the VM's maximums. Hot add can't be switched on for an existing VM from Morpheus.
- **Windows client editions** (Windows 10/11) use at most 2 sockets. Keep cores per socket high enough that they don't need more.
- **DataVolume and DataSource disk types** are synced but not offered when provisioning. All new disks are PVCs.
- **Image backends:** root disks from images use Longhorn backing images, Harvester's default. Images on the CDI
  backend for third-party storage (Harvester 1.5+) are synced, but haven't been tested for provisioning.
- **Instance Labels** (`spec.template.metadata.labels`) aren't synced with tags. Only the VM's Labels are.
- **Tag changes** reach the other side on the next refresh, not instantly.
- **Datastore capacity** is checked against the last refresh, which can be up to one refresh interval old.
- **YAML editing** accepts Latin-1 characters only.

---

## Building and development

**Releases (GitHub Actions):** pushing a tag builds and publishes the plugin. The workflow in
`.github/workflows/release.yml` runs all the tests, builds the jar and attaches it to a GitHub release named after
the tag. The tag sets the version, with a leading `v` dropped: tag `v1.2.0` gives
`morpheus-harvester-plugin-1.2.0-all.jar`. If a test fails, no release is made; the test report is kept as a
workflow artifact either way.

```bash
git tag v1.2.0
git push origin v1.2.0
```

Download the `…-all.jar` from the release and upload it in Morpheus (Administration > Integrations > Plugins).
The workflow can also be started by hand from the repository's **Actions** tab; it then keeps the jar as a workflow
artifact and makes no release.

**Locally** (JDK 11+):

```bash
./gradlew clean test shadowJar     # jar in build/libs/
```

**Source layout** (`src/main/groovy/com/hpe/morpheus/harvester/plugin/`):

| Path | Contents |
|---|---|
| `HpeHarvesterPlugin.groovy` | Plugin entry point: registers providers, the YAML tab, its endpoints and the permission |
| `HpeHarvesterCloudProvider.groovy` | Cloud type: settings, connection record, refresh, pools (namespaces) |
| `HpeHarvesterProvisionProvider.groovy` | Provision type: provisioning, power, resize, console, convert to managed |
| `HpeHarvesterDatastoreTypeProvider.groovy` | Disk create/delete on datastores |
| `HpeHarvesterClusterProvider.groovy` | Internal: cleanup of a cloud's synced data (not registered as a cluster type) |
| `auth/` | API key / login handling and token renewal |
| `apihelper/` | Harvester API client and per-resource helpers, including storage capacity |
| `sync/` | One class per synced resource, plus `HarvesterInventorySync` (order) and `VmTagsSync` (labels ↔ tags) |
| `provision/` | VM creation, reconfigure, power, delete, cloud-init/sysprep |
| `volumes/` | PVC/DataVolume creation, image copy, resize |
| `datasets/` | Option sources for the image pickers |
| `ui/` | Harvester YAML tab, its endpoints and the YAML writer |
| `utils/` | Constants, DB helpers, parsers |
| `src/main/resources/scribe/` | Morpheus type definitions (provision, instance, server, volume, controller, network types) |
| `src/main/resources/renderer/hbs/` | The YAML tab template |
