// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.ui

import spock.lang.Specification
import spock.lang.Unroll

class YamlWriterSpec extends Specification {

    def "writes maps and lists in kubectl layout"() {
        given:
        def vm = [
                apiVersion: 'kubevirt.io/v1',
                kind      : 'VirtualMachine',
                metadata  : [name: 'vm1', labels: [env: 'prod'], annotations: [:]],
                spec      : [
                        runStrategy: 'RerunOnFailure',
                        template   : [spec: [
                                domain  : [cpu: [cores: 2], devices: [disks: [[name: 'rootdisk', bootOrder: 1, disk: [bus: 'virtio']]]]],
                                networks: [[name: 'default', multus: [networkName: 'default/vlan1']]],
                                volumes : []
                        ]]
                ]
        ]

        expect:
        YamlWriter.toYaml(vm) == '''\
apiVersion: kubevirt.io/v1
kind: VirtualMachine
metadata:
  name: vm1
  labels:
    env: prod
  annotations: {}
spec:
  runStrategy: RerunOnFailure
  template:
    spec:
      domain:
        cpu:
          cores: 2
        devices:
          disks:
          - name: rootdisk
            bootOrder: 1
            disk:
              bus: virtio
      networks:
      - name: default
        multus:
          networkName: default/vlan1
      volumes: []
'''
    }

    def "multi-line strings become literal blocks"() {
        expect:
        YamlWriter.toYaml([userData: "#cloud-config\npackages:\n  - vim\n"]) == '''\
userData: |
  #cloud-config
  packages:
    - vim
'''
        YamlWriter.toYaml([a: "x\ny"]) == "a: |-\n  x\n  y\n"
        YamlWriter.toYaml([a: "  lead\nx"]) == 'a: "  lead\\nx"\n'
    }

    def "lists of scalars and nested lists"() {
        expect:
        YamlWriter.toYaml([finalizers: ['a', 'b'], m: [[1, 2]]]) == "finalizers:\n- a\n- b\nm:\n- - 1\n  - 2\n"
    }

    @Unroll
    def "scalar #value -> #yaml"() {
        expect:
        YamlWriter.scalar(value) == yaml

        where:
        value                  || yaml
        null                   || 'null'
        true                   || 'true'
        42                     || '42'
        'plain'                || 'plain'
        ''                     || '""'
        'true'                 || '"true"'
        'yes'                  || '"yes"'
        '123'                  || '"123"'
        '1.5'                  || '"1.5"'
        '0755'                 || '"0755"'
        '*'                    || '"*"'
        'a: b'                 || '"a: b"'
        'say "hi"'             || 'say "hi"'
        '"q"'                  || '"\\"q\\""'
        '2026-11-12T10:00:00Z' || '2026-11-12T10:00:00Z'
        '100Gi'                || '100Gi'
        'kubevirt.io/v1'       || 'kubevirt.io/v1'
    }
}
