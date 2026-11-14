// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.sync

import spock.lang.Specification
import spock.lang.Unroll

class VmTagsSyncSpec extends Specification {

    def "first sync merges both sides and Harvester wins a conflict"() {
        when:
        def plan = VmTagsSync.reconcile([env: 'prod', app: 'web'], [env: 'dev', owner: 'bob'], null)

        then:
        plan.morpheusSet == [env: 'prod', app: 'web']
        plan.morpheusRemove.isEmpty()
        plan.harvesterSet == [owner: 'bob']
        plan.harvesterRemove.isEmpty()
        plan.snapshot == [app: 'web', env: 'prod', owner: 'bob']
    }

    def "label added in Harvester is copied to Morpheus"() {
        when:
        def plan = VmTagsSync.reconcile([env: 'prod', team: 'ops'], [env: 'prod'], [env: 'prod'])

        then:
        plan.morpheusSet == [team: 'ops']
        !plan.harvesterSet && !plan.harvesterRemove && !plan.morpheusRemove
        plan.snapshot == [env: 'prod', team: 'ops']
    }

    def "tag removed in Morpheus is removed from Harvester"() {
        when:
        def plan = VmTagsSync.reconcile([env: 'prod', team: 'ops'], [env: 'prod'], [env: 'prod', team: 'ops'])

        then:
        plan.harvesterRemove == ['team'] as Set
        !plan.harvesterSet && !plan.morpheusSet && !plan.morpheusRemove
        plan.snapshot == [env: 'prod']
    }

    def "label removed in Harvester is removed from Morpheus"() {
        when:
        def plan = VmTagsSync.reconcile([env: 'prod'], [env: 'prod', team: 'ops'], [env: 'prod', team: 'ops'])

        then:
        plan.morpheusRemove == ['team'] as Set
        !plan.harvesterSet && !plan.harvesterRemove
    }

    def "tag changed in Morpheus updates Harvester, and Morpheus wins when both changed"() {
        expect:
        VmTagsSync.reconcile([env: 'prod'], [env: 'qa'], [env: 'prod']).harvesterSet == [env: 'qa']
        VmTagsSync.reconcile([env: 'test'], [env: 'qa'], [env: 'prod']).harvesterSet == [env: 'qa']
    }

    def "nothing to do when both sides match"() {
        when:
        def plan = VmTagsSync.reconcile([a: '1'], [a: '1'], [a: '1'])

        then:
        !plan.harvesterSet && !plan.harvesterRemove && !plan.morpheusSet && !plan.morpheusRemove
        plan.snapshot == [a: '1']
    }

    def "confirmedSnapshot keeps the old value for Morpheus writes that didn't take"() {
        expect: 'team was added to Morpheus, owner failed to add, old was removed but is still in Morpheus'
        VmTagsSync.confirmedSnapshot([env: 'prod', team: 'ops', owner: 'bob'], [env: 'prod', old: 'x'],
                [env: 'prod', team: 'ops', old: 'x']) == [env: 'prod', team: 'ops', old: 'x']
    }

    def "userLabels drops system and plugin labels"() {
        expect:
        VmTagsSync.userLabels([
                'harvesterhci.io/creator'          : 'harvester',
                'harvesterhci.io/image-name'       : 'img',
                'kubevirt.io/vm'                   : 'x',
                'vm.kubevirt.io/os'                : 'linux',
                'app.kubernetes.io/name'           : 'x',
                'morpheus-label.1'                 : 'x',
                'morpheus-label.vm.provisioning'   : 'true',
                'env'                              : 'prod',
                'example.com/team'                 : 'ops',
                'empty'                            : null
        ]) == [env: 'prod', 'example.com/team': 'ops', empty: '']
    }

    @Unroll
    def "label key '#key' valid=#valid"() {
        expect:
        VmTagsSync.isValidLabelKey(key) == valid

        where:
        key                        || valid
        'env'                      || true
        'my_tag.v2'                || true
        'example.com/team'         || true
        'Cost Center'              || false
        ''                         || false
        'a' * 64                   || false
        'harvesterhci.io/x'        || false
        'morpheus-label.1'         || false
    }

    @Unroll
    def "label value '#value' valid=#valid"() {
        expect:
        VmTagsSync.isValidLabelValue(value) == valid

        where:
        value          || valid
        ''             || true
        null           || true
        'prod-01'      || true
        'has space'    || false
        'a/b'          || false
        '-lead'        || false
    }

    def "readTags returns null when the model has no tag list"() {
        expect:
        VmTagsSync.readTags(new Object()) == null
        VmTagsSync.readTags(new TagHolder(metadata: [[name: 'env', value: 'prod'], [name: 'x', value: null]])) == [env: 'prod', x: '']
    }

    static class TagHolder {
        List metadata
    }
}
