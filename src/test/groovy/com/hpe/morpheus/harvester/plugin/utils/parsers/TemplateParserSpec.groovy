package com.hpe.morpheus.harvester.plugin.utils.parsers

import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import spock.lang.Specification

/**
 * Unit tests for TemplateParser class
 */
class TemplateParserSpec extends Specification {

    // parseOperatingSystem Tests

    def "parseOperatingSystem handles null and unknown values"() {
        expect:
        TemplateParser.parseOperatingSystem(null) == [osType: 'unknown', version: 'all']
        TemplateParser.parseOperatingSystem('unknown') == [osType: 'unknown', version: 'all']
        TemplateParser.parseOperatingSystem('') == [osType: 'unknown', version: 'all']
    }

    def "parseOperatingSystem handles CentOS Stream versions"() {
        expect:
        TemplateParser.parseOperatingSystem('centos-stream8') == [osType: 'centos-stream', version: '8']
        TemplateParser.parseOperatingSystem('centos-stream9') == [osType: 'centos-stream', version: '9']
        TemplateParser.parseOperatingSystem('CentOS-Stream8') == [osType: 'centos-stream', version: '8']
    }

    def "parseOperatingSystem handles CentOS versions"() {
        expect:
        TemplateParser.parseOperatingSystem('centos7') == [osType: 'centos', version: '7']
        TemplateParser.parseOperatingSystem('centos8') == [osType: 'centos', version: '8']
        TemplateParser.parseOperatingSystem('CentOS7') == [osType: 'centos', version: '7']
        TemplateParser.parseOperatingSystem('centos') == [osType: 'centos', version: 'all']
    }

    def "parseOperatingSystem handles RHEL versions"() {
        expect:
        TemplateParser.parseOperatingSystem('rhel8') == [osType: 'redhat', version: '8']
        TemplateParser.parseOperatingSystem('rhel9') == [osType: 'redhat', version: '9']
        TemplateParser.parseOperatingSystem('redhat8') == [osType: 'redhat', version: '8']
        TemplateParser.parseOperatingSystem('RHEL9') == [osType: 'redhat', version: '9']
        TemplateParser.parseOperatingSystem('rhel') == [osType: 'redhat', version: 'all']
    }

    def "parseOperatingSystem handles Ubuntu versions"() {
        expect:
        TemplateParser.parseOperatingSystem('ubuntu20.04') == [osType: 'ubuntu', version: '20.04']
        TemplateParser.parseOperatingSystem('ubuntu22.04') == [osType: 'ubuntu', version: '22.04']
        TemplateParser.parseOperatingSystem('ubuntu20') == [osType: 'ubuntu', version: '20.04']
        TemplateParser.parseOperatingSystem('Ubuntu22.04') == [osType: 'ubuntu', version: '22.04']
        TemplateParser.parseOperatingSystem('ubuntu') == [osType: 'ubuntu', version: 'all']
    }

    def "parseOperatingSystem handles Windows Server versions"() {
        expect:
        TemplateParser.parseOperatingSystem('windows2k16') == [osType: 'windows', version: '2016']
        TemplateParser.parseOperatingSystem('windows2k19') == [osType: 'windows', version: '2019']
        TemplateParser.parseOperatingSystem('win2k22') == [osType: 'windows', version: '2022']
        TemplateParser.parseOperatingSystem('Windows2k16') == [osType: 'windows', version: '2016']
    }

    def "parseOperatingSystem handles Windows full year versions"() {
        expect:
        TemplateParser.parseOperatingSystem('windows2016') == [osType: 'windows', version: '2016']
        TemplateParser.parseOperatingSystem('windows2019') == [osType: 'windows', version: '2019']
        TemplateParser.parseOperatingSystem('win2022') == [osType: 'windows', version: '2022']
    }

    def "parseOperatingSystem handles Windows desktop versions"() {
        expect:
        TemplateParser.parseOperatingSystem('windows10') == [osType: 'windows', version: '10']
        TemplateParser.parseOperatingSystem('windows11') == [osType: 'windows', version: '11']
        TemplateParser.parseOperatingSystem('win10') == [osType: 'windows', version: '10']
        TemplateParser.parseOperatingSystem('windows') == [osType: 'windows', version: 'all']
    }

    def "parseOperatingSystem handles Fedora versions"() {
        expect:
        TemplateParser.parseOperatingSystem('fedora') == [osType: 'fedora', version: 'all']
        TemplateParser.parseOperatingSystem('fedora38') == [osType: 'fedora', version: '38']
        TemplateParser.parseOperatingSystem('Fedora39') == [osType: 'fedora', version: '39']
    }

    def "parseOperatingSystem handles Rocky Linux versions"() {
        expect:
        TemplateParser.parseOperatingSystem('rocky8') == [osType: 'rocky', version: '8']
        TemplateParser.parseOperatingSystem('rocky9') == [osType: 'rocky', version: '9']
        TemplateParser.parseOperatingSystem('Rocky9') == [osType: 'rocky', version: '9']
        TemplateParser.parseOperatingSystem('rocky') == [osType: 'rocky', version: 'all']
    }

    def "parseOperatingSystem handles AlmaLinux versions"() {
        expect:
        TemplateParser.parseOperatingSystem('alma8') == [osType: 'almalinux', version: '8']
        TemplateParser.parseOperatingSystem('alma9') == [osType: 'almalinux', version: '9']
        TemplateParser.parseOperatingSystem('almalinux8') == [osType: 'almalinux', version: '8']
        TemplateParser.parseOperatingSystem('Alma9') == [osType: 'almalinux', version: '9']
        TemplateParser.parseOperatingSystem('alma') == [osType: 'almalinux', version: 'all']
    }

    def "parseOperatingSystem handles Debian versions"() {
        expect:
        TemplateParser.parseOperatingSystem('debian11') == [osType: 'debian', version: '11']
        TemplateParser.parseOperatingSystem('debian12') == [osType: 'debian', version: '12']
        TemplateParser.parseOperatingSystem('Debian11') == [osType: 'debian', version: '11']
        TemplateParser.parseOperatingSystem('debian') == [osType: 'debian', version: 'all']
    }

    def "parseOperatingSystem handles unrecognized OS strings"() {
        expect:
        TemplateParser.parseOperatingSystem('customos') == [osType: 'customos', version: 'all']
        TemplateParser.parseOperatingSystem('FreeBSD') == [osType: 'freebsd', version: 'all']
    }

    def "parseOperatingSystem handles whitespace"() {
        expect:
        TemplateParser.parseOperatingSystem('  rhel8  ') == [osType: 'redhat', version: '8']
        TemplateParser.parseOperatingSystem(' centos7 ') == [osType: 'centos', version: '7']
    }

    // extractDataVolumeDetails Tests

    def "extractDataVolumeDetails returns empty list for null vmSpec"() {
        expect:
        TemplateParser.extractDataVolumeDetails(null) == []
    }

    def "extractDataVolumeDetails returns empty list when no volumes"() {
        given:
        def vmSpec = [
            template: [
                spec: [
                    volumes: null,
                    domain: [devices: [disks: []]]
                ]
            ]
        ]

        expect:
        TemplateParser.extractDataVolumeDetails(vmSpec) == []
    }

    def "extractDataVolumeDetails returns empty list when no disks"() {
        given:
        def vmSpec = [
            template: [
                spec: [
                    volumes: [],
                    domain: [devices: [disks: null]]
                ]
            ]
        ]

        expect:
        TemplateParser.extractDataVolumeDetails(vmSpec) == []
    }

    def "extractDataVolumeDetails handles DataVolume with sourceRef"() {
        given:
        def vmSpec = [
            template: [
                spec: [
                    volumes: [
                        [name: 'rootdisk', dataVolume: [name: 'dv-root']]
                    ],
                    domain: [
                        devices: [
                            disks: [
                                [name: 'rootdisk', bootOrder: 1, disk: [bus: 'virtio']]
                            ]
                        ]
                    ]
                ]
            ],
            dataVolumeTemplates: [
                [
                    metadata: [name: 'dv-root'],
                    spec: [
                        sourceRef: [
                            kind: 'DataSource',
                            name: 'rhel9-datasource',
                            namespace: 'harvester-virtualization-os-images'
                        ],
                        storage: [
                            resources: [requests: [storage: '30Gi']],
                            storageClassName: 'longhorn-replicated',
                            accessModes: ['ReadWriteOnce'],
                            volumeMode: 'Block'
                        ]
                    ]
                ]
            ]
        ]

        when:
        def result = TemplateParser.extractDataVolumeDetails(vmSpec)

        then:
        result.size() == 1
        result[0].diskName == 'rootdisk'
        result[0].bootOrder == 1
        result[0].busType == 'virtio'
        result[0].type == HarvesterConstants.VOLUME_TYPE_DATAVOLUME
        result[0].dataVolumeName == 'dv-root'
        result[0].sourceType == HarvesterConstants.VOLUME_TYPE_DATASOURCE
        result[0].sourceRefKind == 'DataSource'
        result[0].sourceRefName == 'rhel9-datasource'
        result[0].sourceRefNamespace == 'harvester-virtualization-os-images'
        result[0].requestedStorage == '30Gi'
        result[0].storageClassName == 'longhorn-replicated'
        result[0].accessModes == ['ReadWriteOnce']
        result[0].volumeMode == 'Block'
        result[0].isBootDisk == true
    }

    def "extractDataVolumeDetails handles DataVolume with source PVC"() {
        given:
        def vmSpec = [
            template: [
                spec: [
                    volumes: [
                        [name: 'rootdisk', dataVolume: [name: 'dv-root']],
                        [name: 'datadisk', dataVolume: [name: 'dv-data']]
                    ],
                    domain: [
                        devices: [
                            disks: [
                                [name: 'rootdisk', bootOrder: 1, disk: [bus: 'virtio']],
                                [name: 'datadisk', bootOrder: 2, disk: [bus: 'scsi']]
                            ]
                        ]
                    ]
                ]
            ],
            dataVolumeTemplates: [
                [metadata: [name: 'dv-root'], spec: [:]],
                [
                    metadata: [name: 'dv-data'],
                    spec: [
                        source: [
                            pvc: [
                                name: 'source-pvc',
                                namespace: 'default'
                            ]
                        ],
                        storage: [
                            resources: [requests: [storage: '50Gi']],
                            storageClassName: 'standard',
                            accessModes: ['ReadWriteMany'],
                            volumeMode: 'Filesystem'
                        ]
                    ]
                ]
            ]
        ]

        when:
        def result = TemplateParser.extractDataVolumeDetails(vmSpec)

        then:
        result.size() == 2
        result[1].diskName == 'datadisk'
        result[1].sourceType == HarvesterConstants.VOLUME_TYPE_PVC
        result[1].sourcePvcName == 'source-pvc'
        result[1].sourcePvcNamespace == 'default'
        result[1].requestedStorage == '50Gi'
        result[1].storageClassName == 'standard'
        result[1].isBootDisk == false
    }

    def "extractDataVolumeDetails handles cloudInitNoCloud volume"() {
        given:
        def vmSpec = [
            template: [
                spec: [
                    volumes: [
                        [name: 'cloudinit', cloudInitNoCloud: [userData: '#cloud-config']]
                    ],
                    domain: [
                        devices: [
                            disks: [
                                [name: 'cloudinit', disk: [bus: 'virtio']]
                            ]
                        ]
                    ]
                ]
            ]
        ]

        when:
        def result = TemplateParser.extractDataVolumeDetails(vmSpec)

        then:
        result.size() == 1
        result[0].type == HarvesterConstants.VOLUME_TYPE_CLOUDINIT
        result[0].sourceType == HarvesterConstants.VOLUME_TYPE_CLOUDINIT
    }

    def "extractDataVolumeDetails handles persistentVolumeClaim volume"() {
        given:
        def vmSpec = [
            template: [
                spec: [
                    volumes: [
                        [name: 'pvc-vol', persistentVolumeClaim: [claimName: 'my-pvc']]
                    ],
                    domain: [
                        devices: [
                            disks: [
                                [name: 'pvc-vol', disk: [bus: 'sata']]
                            ]
                        ]
                    ]
                ]
            ]
        ]

        when:
        def result = TemplateParser.extractDataVolumeDetails(vmSpec)

        then:
        result.size() == 1
        result[0].type == HarvesterConstants.VOLUME_TYPE_PVC
        result[0].sourcePvcName == 'my-pvc'
        result[0].pvcName == 'pvc-vol'
    }

    def "extractDataVolumeDetails sorts by boot order correctly"() {
        given:
        def vmSpec = [
            template: [
                spec: [
                    volumes: [
                        [name: 'disk3', dataVolume: [name: 'dv-3']],
                        [name: 'disk1', dataVolume: [name: 'dv-1']],
                        [name: 'disk2', dataVolume: [name: 'dv-2']]
                    ],
                    domain: [
                        devices: [
                            disks: [
                                [name: 'disk3', bootOrder: 3, disk: [bus: 'virtio']],
                                [name: 'disk1', bootOrder: 1, disk: [bus: 'virtio']],
                                [name: 'disk2', bootOrder: 2, disk: [bus: 'virtio']]
                            ]
                        ]
                    ]
                ]
            ],
            dataVolumeTemplates: [
                [metadata: [name: 'dv-1'], spec: [:]],
                [metadata: [name: 'dv-2'], spec: [:]],
                [metadata: [name: 'dv-3'], spec: [:]]
            ]
        ]

        when:
        def result = TemplateParser.extractDataVolumeDetails(vmSpec)

        then:
        result.size() == 3
        result[0].diskName == 'disk1'
        result[0].bootOrder == 1
        result[0].isBootDisk == true
        result[1].diskName == 'disk2'
        result[1].bootOrder == 2
        result[2].diskName == 'disk3'
        result[2].bootOrder == 3
    }

    def "extractDataVolumeDetails identifies first disk as boot when no boot order"() {
        given:
        def vmSpec = [
            template: [
                spec: [
                    volumes: [
                        [name: 'disk1', dataVolume: [name: 'dv-1']],
                        [name: 'disk2', dataVolume: [name: 'dv-2']]
                    ],
                    domain: [
                        devices: [
                            disks: [
                                [name: 'disk1', disk: [bus: 'virtio']],
                                [name: 'disk2', disk: [bus: 'virtio']]
                            ]
                        ]
                    ]
                ]
            ],
            dataVolumeTemplates: [
                [metadata: [name: 'dv-1'], spec: [:]],
                [metadata: [name: 'dv-2'], spec: [:]]
            ]
        ]

        when:
        def result = TemplateParser.extractDataVolumeDetails(vmSpec)

        then:
        result.size() == 2
        result[0].diskName == 'disk1'
        result[0].isBootDisk == true
        result[1].diskName == 'disk2'
        result[1].isBootDisk == false
    }

    def "extractDataVolumeDetails handles missing bus type"() {
        given:
        def vmSpec = [
            template: [
                spec: [
                    volumes: [
                        [name: 'disk1', dataVolume: [name: 'dv-1']]
                    ],
                    domain: [
                        devices: [
                            disks: [
                                [name: 'disk1', disk: [:]]
                            ]
                        ]
                    ]
                ]
            ],
            dataVolumeTemplates: [
                [metadata: [name: 'dv-1'], spec: [:]]
            ]
        ]

        when:
        def result = TemplateParser.extractDataVolumeDetails(vmSpec)

        then:
        result.size() == 1
        result[0].busType == 'unknown'
    }

    def "extractDataVolumeDetails handles DataVolume without matching template"() {
        given:
        def vmSpec = [
            template: [
                spec: [
                    volumes: [
                        [name: 'disk1', dataVolume: [name: 'dv-missing']]
                    ],
                    domain: [
                        devices: [
                            disks: [
                                [name: 'disk1', disk: [bus: 'virtio']]
                            ]
                        ]
                    ]
                ]
            ],
            dataVolumeTemplates: []
        ]

        when:
        def result = TemplateParser.extractDataVolumeDetails(vmSpec)

        then:
        result.size() == 1
        result[0].dataVolumeName == 'dv-missing'
        result[0].sourceType == 'unknown'
    }

    def "extractDataVolumeDetails handles exception gracefully"() {
        given:
        def vmSpec = [
            template: [
                spec: [
                    volumes: 'invalid',  // This will cause an error
                    domain: null
                ]
            ]
        ]

        when:
        def result = TemplateParser.extractDataVolumeDetails(vmSpec)

        then:
        result == []
    }

    def "extractDataVolumeDetails prefers sourceRef over source.pvc and applies defaults"() {
        given:
        def vmSpec = [
            template: [
                spec: [
                    volumes: [
                        [name: 'rootdisk', dataVolume: [name: 'dv-root']]
                    ],
                    domain: [
                        devices: [
                            disks: [
                                [name: 'rootdisk', bootOrder: 1, disk: [bus: 'virtio']]
                            ]
                        ]
                    ]
                ]
            ],
            dataVolumeTemplates: [
                [
                    metadata: [name: 'dv-root'],
                    spec: [
                        sourceRef: [kind: 'DataSource', name: null, namespace: null],
                        source: [pvc: [name: 'ignored-pvc', namespace: 'ignored-ns']],
                        storage: [:]
                    ]
                ]
            ]
        ]

        when:
        def result = TemplateParser.extractDataVolumeDetails(vmSpec)

        then:
        result.size() == 1
        result[0].sourceType == HarvesterConstants.VOLUME_TYPE_DATASOURCE
        result[0].sourceRefKind == 'DataSource'
        result[0].sourceRefName == ''
        result[0].sourceRefNamespace == ''
        result[0].sourcePvcName == ''
        result[0].sourcePvcNamespace == ''
        result[0].requestedStorage == ''
        result[0].storageClassName == ''
        result[0].accessModes == []
        result[0].volumeMode == ''
    }

    def "extractDataVolumeDetails ignores disks without matching volume entries"() {
        given:
        def vmSpec = [
            template: [
                spec: [
                    volumes: [
                        [name: 'existing-disk', cloudInitNoCloud: [userData: '#cloud-config']]
                    ],
                    domain: [
                        devices: [
                            disks: [
                                [name: 'missing-disk', disk: [bus: 'virtio']],
                                [name: 'existing-disk', disk: [bus: 'virtio']]
                            ]
                        ]
                    ]
                ]
            ]
        ]

        when:
        def result = TemplateParser.extractDataVolumeDetails(vmSpec)

        then:
        result.size() == 1
        result[0].diskName == 'existing-disk'
        result[0].type == HarvesterConstants.VOLUME_TYPE_CLOUDINIT
    }

    def "extractDataVolumeDetails keeps pvc source type when pvc name and namespace are null"() {
        given:
        def vmSpec = [
            template: [
                spec: [
                    volumes: [
                        [name: 'disk-pvc', dataVolume: [name: 'dv-pvc']]
                    ],
                    domain: [
                        devices: [
                            disks: [
                                [name: 'disk-pvc', disk: [bus: 'scsi']]
                            ]
                        ]
                    ]
                ]
            ],
            dataVolumeTemplates: [
                [
                    metadata: [name: 'dv-pvc'],
                    spec: [
                        source: [pvc: [name: null, namespace: null]]
                    ]
                ]
            ]
        ]

        when:
        def result = TemplateParser.extractDataVolumeDetails(vmSpec)

        then:
        result.size() == 1
        result[0].sourceType == HarvesterConstants.VOLUME_TYPE_PVC
        result[0].sourcePvcName == null
        result[0].sourcePvcNamespace == null
        result[0].busType == 'scsi'
    }

    // extractBootSource Tests

    def "extractBootSource returns boot disk details with DataSource"() {
        given:
        def vmSpec = [
            template: [
                spec: [
                    volumes: [
                        [name: 'rootdisk', dataVolume: [name: 'dv-root']]
                    ],
                    domain: [
                        devices: [
                            disks: [
                                [name: 'rootdisk', bootOrder: 1, disk: [bus: 'virtio']]
                            ]
                        ]
                    ]
                ]
            ],
            dataVolumeTemplates: [
                [
                    metadata: [name: 'dv-root'],
                    spec: [
                        sourceRef: [
                            kind: 'DataSource',
                            name: 'rhel9-datasource',
                            namespace: 'harvester-virtualization-os-images'
                        ],
                        storage: [
                            resources: [requests: [storage: '30Gi']],
                            storageClassName: 'longhorn-replicated'
                        ]
                    ]
                ]
            ]
        ]

        when:
        def result = TemplateParser.extractBootSource(vmSpec)

        then:
        result.type == HarvesterConstants.VOLUME_TYPE_DATAVOLUME
        result.source == 'rhel9-datasource'
        result.size == '30Gi'
        result.storageClass == 'longhorn-replicated'
    }

    def "extractBootSource returns boot disk details with PVC source"() {
        given:
        def vmSpec = [
            template: [
                spec: [
                    volumes: [
                        [name: 'rootdisk', dataVolume: [name: 'dv-root']]
                    ],
                    domain: [
                        devices: [
                            disks: [
                                [name: 'rootdisk', bootOrder: 1, disk: [bus: 'virtio']]
                            ]
                        ]
                    ]
                ]
            ],
            dataVolumeTemplates: [
                [
                    metadata: [name: 'dv-root'],
                    spec: [
                        source: [
                            pvc: [
                                name: 'source-pvc',
                                namespace: 'default'
                            ]
                        ],
                        storage: [
                            resources: [requests: [storage: '50Gi']],
                            storageClassName: 'standard'
                        ]
                    ]
                ]
            ]
        ]

        when:
        def result = TemplateParser.extractBootSource(vmSpec)

        then:
        result.type == HarvesterConstants.VOLUME_TYPE_DATAVOLUME
        result.source == 'dv-root'
        result.size == '50Gi'
        result.storageClass == 'standard'
    }

    def "extractBootSource returns unknown for no boot disk"() {
        given:
        def vmSpec = [
            template: [
                spec: [
                    volumes: [],
                    domain: [devices: [disks: []]]
                ]
            ]
        ]

        when:
        def result = TemplateParser.extractBootSource(vmSpec)

        then:
        result.type == 'unknown'
        result.source == ''
        result.size == ''
        result.storageClass == ''
    }

    def "extractBootSource handles null vmSpec"() {
        when:
        def result = TemplateParser.extractBootSource(null)

        then:
        result.type == 'unknown'
        result.source == ''
    }

    def "extractBootSource handles exception gracefully"() {
        given:
        def vmSpec = [
            template: [
                spec: [
                    volumes: 'invalid',
                    domain: null
                ]
            ]
        ]

        when:
        def result = TemplateParser.extractBootSource(vmSpec)

        then:
        result.type == 'unknown'
    }
}
