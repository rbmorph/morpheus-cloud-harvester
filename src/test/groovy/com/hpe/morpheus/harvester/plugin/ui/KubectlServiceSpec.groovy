// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.ui

import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonOutput
import spock.lang.Specification
import spock.lang.Unroll

import java.time.Duration
import java.time.Instant

class KubectlServiceSpec extends Specification {

    HarvesterApiClient client = Mock(HarvesterApiClient)

    private static ServiceResponse ok(def body, String code = '200') {
        def r = new ServiceResponse(true, null, null, null)
        r.content = body instanceof String ? body : JsonOutput.toJson(body)
        r.errorCode = code
        return r
    }

    private static ServiceResponse notFound(String message) {
        def r = new ServiceResponse(false, null, null, null)
        r.content = JsonOutput.toJson([kind: 'Status', reason: 'NotFound', message: message])
        r.errorCode = '404'
        return r
    }

    /** Discovery: pods, nodes, secrets (core) and virtualmachines (kubevirt.io) */
    private void discovery() {
        client.raw('GET', '/api/v1', _, _) >> ok([resources: [
                [name: 'pods', singularName: 'pod', shortNames: ['po'], kind: 'Pod', namespaced: true],
                [name: 'pods/log', kind: 'Pod', namespaced: true],
                [name: 'nodes', singularName: 'node', shortNames: ['no'], kind: 'Node', namespaced: false],
                [name: 'secrets', singularName: 'secret', kind: 'Secret', namespaced: true]]])
        client.raw('GET', '/apis', _, _) >> ok([groups: [[name: 'kubevirt.io', preferredVersion: [groupVersion: 'kubevirt.io/v1']]]])
        client.raw('GET', '/apis/kubevirt.io/v1', _, _) >> ok([resources: [
                [name: 'virtualmachines', singularName: 'virtualmachine', shortNames: ['vm', 'vms'], kind: 'VirtualMachine', namespaced: true]]])
    }

    private KubectlService service(boolean showSecrets = true) {
        return new KubectlService(client, showSecrets, "spec-${UUID.randomUUID()}")
    }

    def "parse splits verb, arguments and flags like kubectl"() {
        expect:
        KubectlService.parse("kubectl get vm -A -o=wide --show-labels -l 'app=a b'") ==
                [verb: 'get', args: ['vm'], flags: [allNamespaces: true, output: 'wide', showLabels: true, selector: 'app=a b']]
        KubectlService.parse('logs mypod -p -c c1 --tail=20 -nkube-system') ==
                [verb: 'logs', args: ['mypod'], flags: [previous: true, container: 'c1', tail: '20', namespace: 'kube-system']]
        KubectlService.parse('''patch vm x -p '{"a":1}' --type merge''').flags == [patch: '{"a":1}', type: 'merge']
        KubectlService.parse('get pods -oyaml').flags.output == 'yaml'
        KubectlService.parse('  ').verb == null
    }

    def "parse rejects an unterminated quote and a flag without its value"() {
        when:
        KubectlService.parse(line)

        then:
        thrown(IllegalArgumentException)

        where:
        line << ["get pods -l 'app=x", 'get pods -n']
    }

    @Unroll
    def "#line is a #kind command"() {
        expect:
        KubectlService.isWrite(KubectlService.parse(line)) == write

        where:
        line                          || write
        'get pods'                    || false
        'describe vm x'               || false
        'logs p'                      || false
        'delete vm x'                 || true
        'apply -f -'                  || true
        'cordon node1'                || true
        'rollout restart deploy x'    || true
        kind = write ? 'write' : 'read'
    }

    def "resolve finds types by plural, singular, short name, kind and name.group"() {
        given:
        List<Map> rs = KubectlService.resourceList([resources: [
                [name: 'events', singularName: 'event', shortNames: ['ev'], kind: 'Event', namespaced: true]]], '', 'v1') +
                KubectlService.resourceList([resources: [
                        [name: 'events', singularName: 'event', kind: 'Event', namespaced: true]]], 'events.k8s.io', 'v1') +
                KubectlService.resourceList([resources: [
                        [name: 'virtualmachines', singularName: 'virtualmachine', shortNames: ['vm'], kind: 'VirtualMachine', namespaced: true]]], 'kubevirt.io', 'v1')

        expect:
        KubectlService.resolve(rs, 'vm').name == 'virtualmachines'
        KubectlService.resolve(rs, 'VirtualMachine').group == 'kubevirt.io'
        KubectlService.resolve(rs, 'virtualmachines.kubevirt.io').name == 'virtualmachines'
        KubectlService.resolve(rs, 'events').group == ''
        KubectlService.resolve(rs, 'events.events.k8s.io').group == 'events.k8s.io'
        KubectlService.resolve(rs, 'nope') == null
    }

    def "get prints the server's table, with the namespace column for -A and wide columns for -o wide"() {
        given:
        discovery()
        Map table = [kind: 'Table',
                     columnDefinitions: [[name: 'Name', type: 'string', priority: 0], [name: 'Status', type: 'string', priority: 0],
                                         [name: 'Ready', type: 'string', priority: 1]],
                     rows: [[cells: ['vm1', 'Running', 'True'], object: [metadata: [namespace: 'default']]],
                            [cells: ['longer-name', 'Stopped', 'False'], object: [metadata: [namespace: 'prod']]]]]
        client.raw('GET', '/apis/kubevirt.io/v1/virtualmachines', _, { it?.Accept?.contains('as=Table') }) >> ok(table)

        when:
        def plain = service().run('get vm -A')
        def wide = service().run('get vms -A -o wide')

        then:
        plain.success
        plain.output == 'NAMESPACE   NAME          STATUS\ndefault     vm1           Running\nprod        longer-name   Stopped'
        wide.output.readLines()[0] == 'NAMESPACE   NAME          STATUS    READY'
    }

    def "get in an empty namespace says so"() {
        given:
        discovery()
        client.raw('GET', '/api/v1/namespaces/default/pods', _, _) >> ok([kind: 'Table', columnDefinitions: [[name: 'Name', type: 'string']], rows: []])

        expect:
        service().run('get pods').output == 'No resources found in default namespace.'
    }

    def "Secret values are hidden from read-only users and managedFields are dropped"() {
        given:
        discovery()
        client.raw('GET', '/api/v1/namespaces/default/secrets/s1', _, _) >>
                ok([apiVersion: 'v1', kind: 'Secret', metadata: [name: 's1', managedFields: [[a: 1]]], data: [password: 'cGFzcw==']])

        when:
        String readOnly = service(false).run('get secret s1 -o yaml').output
        String full = service(true).run('get secret s1 -o json').output

        then:
        readOnly.contains('<hidden: needs the read and write permission>')
        !readOnly.contains('cGFzcw==')
        !readOnly.contains('managedFields')
        full.contains('cGFzcw==')
    }

    def "unknown types, API errors and unsupported commands come back as errors"() {
        given:
        discovery()
        client.raw('GET', '/api/v1/namespaces/default/pods/nope', _, _) >> notFound('pods "nope" not found')

        expect:
        service().run('get bogus') == [success: false, output: 'error: the server doesn\'t have a resource type "bogus"']
        service().run('get pod nope') == [success: false, output: 'Error from server (NotFound): pods "nope" not found']
        service().run('exec mypod -- sh').output.startsWith("error: 'exec' isn't supported")
        service().run('get pods -w').output.contains('--watch')
        service().run('frobnicate').output.contains('unknown command')
    }

    def "apply sends each manifest as a server-side apply"() {
        given:
        discovery()
        String yaml = '''apiVersion: kubevirt.io/v1
kind: VirtualMachine
metadata:
  name: vm9   # comment
  namespace: prod
  labels:
    name: not-the-name
spec: {}
---
# only a comment
'''

        when:
        def result = service().run('apply -f -', yaml)

        then:
        1 * client.raw('PATCH', '/apis/kubevirt.io/v1/namespaces/prod/virtualmachines/vm9',
                [fieldManager: 'morpheus-harvester-console', force: 'true'], null, { it.contains('kind: VirtualMachine') },
                'application/apply-patch+yaml') >> ok([kind: 'VirtualMachine'], '201')
        result == [success: true, output: 'virtualmachine.kubevirt.io/vm9 created']
    }

    def "apply needs -f - and a manifest"() {
        given:
        discovery()

        expect:
        service().run('apply -f vm.yaml', 'x').output.contains("use '-f -'")
        service().run('apply -f -', '  ').output.contains('no manifest')
    }

    def "delete, cordon and scale call the API and report like kubectl"() {
        given:
        discovery()

        when:
        def deleted = service().run('delete vm a b -n prod')
        def cordoned = service().run('cordon node1')

        then:
        1 * client.raw('DELETE', '/apis/kubevirt.io/v1/namespaces/prod/virtualmachines/a') >> ok([:])
        1 * client.raw('DELETE', '/apis/kubevirt.io/v1/namespaces/prod/virtualmachines/b') >> ok([:])
        1 * client.raw('PATCH', '/api/v1/nodes/node1', _, null, '{"spec":{"unschedulable":true}}', 'application/merge-patch+json') >> ok([:])
        deleted.output == 'virtualmachine "a" deleted\nvirtualmachine "b" deleted'
        cordoned.output == 'node/node1 cordoned'
    }

    def "label refuses to change an existing value without --overwrite"() {
        given:
        discovery()
        client.raw('GET', '/api/v1/nodes/n1', _, _) >> ok([metadata: [name: 'n1', labels: [env: 'prod']]])

        expect:
        service().run('label node n1 env=dev').output.contains('--overwrite is false')
    }

    def "splitDocuments and manifestHead read YAML and JSON manifests"() {
        expect:
        KubectlService.splitDocuments('a: 1\n---\nb: 2\n---\n# c\n') == ['a: 1', 'b: 2']
        KubectlService.splitDocuments('[{"kind":"A"},{"kind":"B"}]').size() == 2
        KubectlService.manifestHead('{"apiVersion":"v1","kind":"Pod","metadata":{"name":"p","namespace":"n"}}') ==
                [apiVersion: 'v1', kind: 'Pod', name: 'p', namespace: 'n']
        KubectlService.manifestHead("apiVersion: v1\nkind: 'ConfigMap'\nmetadata:\n  name: \"cm\"\ndata:\n  name: x") ==
                [apiVersion: 'v1', kind: 'ConfigMap', name: 'cm']
    }

    @Unroll
    def "humanDuration(#seconds s) = #expected"() {
        expect:
        KubectlService.humanDuration(Duration.ofSeconds(seconds)) == expected

        where:
        seconds              || expected
        45                   || '45s'
        5 * 60 + 7           || '5m7s'
        45 * 60              || '45m'
        3 * 3600 + 12 * 60   || '3h12m'
        20 * 3600            || '20h'
        4 * 86400 + 3 * 3600 || '4d3h'
        40 * 86400           || '40d'
    }

    def "age turns a timestamp into kubectl's short age"() {
        expect:
        KubectlService.age('2026-11-10T00:00:00Z', Instant.parse('2026-11-13T00:00:00Z')) == '3d'
        KubectlService.age(null) == '<unknown>'
    }

    def "quantities for top"() {
        expect:
        KubectlService.milliCores('123456789n') == 123L
        KubectlService.milliCores('250m') == 250L
        KubectlService.milliCores('2') == 2000L
        KubectlService.bytes('2048Ki') == 2097152L
        KubectlService.bytes('1Gi') == 1073741824L
    }

    def "formatTable pads columns with three spaces"() {
        expect:
        KubectlService.formatTable(['A', 'BB'], [['x', 'y'], ['long', 'z']]) == 'A      BB\nx      y\nlong   z'
    }
}
