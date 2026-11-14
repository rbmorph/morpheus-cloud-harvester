# Harvester Plugin for Morpheus – Feature Summary

## Overview

The plugin adds Harvester HCI as a cloud type in HPE Morpheus Enterprise, so Harvester VMs can be provisioned, managed and monitored from Morpheus like any other cloud.

- **Built for:** Morpheus 9.1 (plugin API 1.3.0) and Harvester 1.9.
- **Connection:** the Harvester API (Kubernetes API of the Harvester cluster), using an API key or a Harvester username and password.
- **Changes in Harvester:** only VMs, disks, snapshots, labels and objects you change through the plugin. The sync itself only reads.

## Cloud and inventory sync

On every cloud refresh the plugin brings Harvester's inventory into Morpheus and keeps it current.

| Harvester | Becomes in Morpheus |
| --- | --- |
| Nodes | Hosts (hypervisors), with CPU, memory and storage usage |
| Namespaces | Resource pools |
| Storage classes | Datastores, with Longhorn capacity and free space |
| VM images | Virtual images (ISO images are marked as ISO) |
| VM networks | Networks |
| Virtual machines | Compute servers and instances, with disks and NICs |
| VM snapshots | Snapshots |
| VM labels | Instance tags, synced both ways |

- Existing VMs can be inventoried and converted to managed instances.
- Provisioning onto a datastore without enough free space is blocked.

## Provisioning and plans

VMs are provisioned from Harvester images into a chosen namespace, network and storage class.

- **Images:** disk images become the root disk; ISO images boot from a CD-ROM onto a blank disk.
- **Guest setup:** cloud-init for Linux and sysprep for Windows, including users, keys and network settings.
- **Firmware:** BIOS by default, UEFI and Secure Boot when the image is flagged for them.
- **Plans:** 11 fixed sizes from 1 CPU / 1 GB to 16 CPU / 64 GB, each 2 sockets with the cores split between them, plus a **Custom** plan with every option configurable.
- **CPU layout:** core count and cores per socket from the plan or wizard go straight into the VM's sockets and cores.
- **CPU and Memory Hot Add:** optional at provisioning, so CPUs and memory can be added later without a restart.
- **Skip Network Wait:** finishes provisioning without waiting for an IP address, for ISO installs.

## Day-2 operations

Running VMs can be changed from Morpheus without opening Harvester.

| Feature | What it does |
| --- | --- |
| Power | Start, stop, restart and suspend |
| Reconfigure | Change plan, cores, cores per socket and memory; add, grow or remove disks; add, remove or move NICs |
| Hot add | Grow CPU and memory on a running VM within its maximums, without a restart |
| Snapshots | Create, revert and delete Harvester VM snapshots |
| CD-ROMs | Attach an ISO image, eject it, and optionally boot from it |
| Hypervisor console | The VM's screen through Harvester (VNC), with no network access needed |
| Guest console | SSH for Linux, RDP for Windows |
| Delete | Removes the VM and its disks from Harvester |

## Tabs, monitoring and permissions

Extra Harvester views in Morpheus are gated by the plugin's own role permissions.

- **Harvester YAML tab** (on VMs): view the VM's YAML; edit and save it with the **Harvester: Edit VM YAML** permission.
- **Harvester CD-ROM tab** (on VMs): manage CD-ROMs, with the **Harvester: Manage CD-ROM** permission.
- **Harvester Console tab** (on Harvester hosts): a built-in kubectl subset (get, describe, logs, top, apply, delete and more) against the whole cluster. **Read-only** and **read and write** permissions are separate, and Secret values are hidden from read-only users.
- **Cluster health:** the cloud status shows **Warning**, with the reason, when nodes are not ready, Longhorn volumes are degraded or monitoring alerts are firing.

For full details, see the [README](README.md).
