// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.sync

import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.hpe.morpheus.harvester.plugin.utils.HarvesterConstants
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.MetadataTag
import groovy.util.logging.Slf4j

/**
 * Two-way sync between Harvester VM labels and Morpheus tags.
 *
 * Morpheus gives plugins no event when tags are edited, so this runs with the VM sync on every cloud refresh.
 * The labels agreed at the end of the previous sync are kept on the server (config CONFIG_TAG_SNAPSHOT); comparing
 * each side with that snapshot tells which side changed a key:
 *   - changed only in Harvester  -> copied to Morpheus
 *   - changed only in Morpheus   -> copied to Harvester
 *   - changed on both sides      -> Morpheus wins
 *   - first sync (no snapshot)   -> merged; Harvester wins where both have the key with different values
 * Harvester/Kubernetes system labels and tags that aren't valid Kubernetes labels are left out on both sides.
 *
 * Only the VM's Labels (metadata.labels) are synced, not its Instance Labels (spec.template.metadata.labels).
 */
@Slf4j
class VmTagsSync {

    static final String CONFIG_TAG_SNAPSHOT = 'harvesterLabelSnapshot'
    static final List<String> SYSTEM_LABEL_DOMAINS = ['harvesterhci.io', 'kubevirt.io', 'kubernetes.io', 'k8s.io',
                                                      'cattle.io', 'longhorn.io', 'rancher.io']

    private MorpheusContext morpheusContext
    private ComputeServerGroup cluster

    VmTagsSync(MorpheusContext morpheusContext, ComputeServerGroup cluster) {
        this.morpheusContext = morpheusContext
        this.cluster = cluster
    }

    /**
     * Syncs one VM. vmData is the VM map from VirtualMachineApiHelper.getVMs (uses name, namespace, labels).
     */
    void sync(ComputeServer server, Map vmData, HarvesterApiClient apiClient = null) {
        if (!server || !vmData?.name || !vmData?.namespace) {
            return
        }
        def instance = findInstance(server)
        // Tags are edited on the instance when there is one, otherwise on the server
        def tagSource = instance ?: server
        Map<String, String> morpheusTags = readTags(tagSource)
        if (morpheusTags == null) {
            log.warn "VmTagsSync: can't read Morpheus tags for ${server.name} (${tagSource?.getClass()?.name} has no " +
                    "'metadata' or 'tags' property), skipping tag sync"
            return
        }
        Map<String, String> harvesterLabels = userLabels(vmData.labels as Map)
        Map<String, String> snapshot = readSnapshot(server)
        log.info "VmTagsSync: ${server.name} (server ${server.id}, instance ${instance?.id}): Harvester labels=${harvesterLabels}, " +
                "Morpheus tags=${morpheusTags}, last sync=${snapshot}"
        Map<String, String> morpheusValid = validTags(morpheusTags)

        Map plan = reconcile(harvesterLabels, morpheusValid, snapshot)
        if (!plan.harvesterSet && !plan.harvesterRemove && !plan.morpheusSet && !plan.morpheusRemove
                && snapshot == plan.snapshot) {
            return
        }
        log.info "VmTagsSync: ${server.name}: to Harvester set=${plan.harvesterSet} remove=${plan.harvesterRemove}; " +
                "to Morpheus set=${plan.morpheusSet} remove=${plan.morpheusRemove}"

        // 1. Harvester first: if it fails, nothing else changes and the next sync tries again
        if (plan.harvesterSet || plan.harvesterRemove) {
            Map labelPatch = [:]
            plan.harvesterSet.each { k, v -> labelPatch[k] = v }
            plan.harvesterRemove.each { k -> labelPatch[k] = null }
            def client = apiClient ?: new HarvesterApiClient(cluster)
            String path = "/apis/kubevirt.io/v1/namespaces/${vmData.namespace}/virtualmachines/${vmData.name}"
            def resp = client.patch(path, [metadata: [labels: labelPatch]], 'application/merge-patch+json')
            if (!resp?.success) {
                log.warn "VmTagsSync: updating labels on Harvester VM ${vmData.namespace}/${vmData.name} failed: ${resp?.msg ?: resp?.error}"
                return
            }
        }

        // 2. Morpheus tags: the instance when there is one (that's where they're shown and edited), and the server
        if (plan.morpheusSet || plan.morpheusRemove) {
            if (instance) {
                try {
                    writeTags(instance, plan.morpheusSet as Map, plan.morpheusRemove as Set)
                } catch (Throwable e) {
                    log.warn "VmTagsSync: updating tags on instance ${instance.id} (${server.name}) failed: ${e.class.name}: ${e.message}", e
                }
            }
            try {
                writeTags(reloadServer(server), plan.morpheusSet as Map, plan.morpheusRemove as Set)
            } catch (Throwable e) {
                log.warn "VmTagsSync: updating tags on server ${server.id} (${server.name}) failed: ${e.class.name}: ${e.message}", e
            }
        }

        // 3. Record what both sides now agree on. Read the tags back: a key whose Morpheus write didn't take keeps its
        //    old value, so the next sync retries it instead of mistaking it for a change made in Morpheus.
        Map<String, String> actual = morpheusValid
        if (plan.morpheusSet || plan.morpheusRemove) {
            def reloadedSource = instance ? reloadInstance(instance) : reloadServer(server)
            actual = validTags(readTags(reloadedSource) ?: [:])
            def missed = (plan.morpheusSet as Map).findAll { k, v -> actual[k] != v }.keySet() +
                    (plan.morpheusRemove as Set).findAll { actual.containsKey(it) }
            if (missed) {
                log.warn "VmTagsSync: ${server.name}: Morpheus tags not saved for ${missed}; will retry on the next sync"
            } else {
                log.info "VmTagsSync: ${server.name}: Morpheus tags updated"
            }
        }
        ComputeServer fresh = reloadServer(server)
        saveSnapshot(fresh, confirmedSnapshot(plan.snapshot as Map, snapshot, actual))
    }

    /**
     * The snapshot to keep after a sync: the agreed value for keys Morpheus now has, the previous snapshot value for
     * keys whose Morpheus write didn't take.
     */
    static Map<String, String> confirmedSnapshot(Map<String, String> planned, Map<String, String> previous,
                                                 Map<String, String> actualMorpheus) {
        Map<String, String> result = [:]
        Set<String> keys = new TreeSet<String>()
        keys.addAll((planned ?: [:]).keySet()); keys.addAll((previous ?: [:]).keySet()); keys.addAll((actualMorpheus ?: [:]).keySet())
        keys.each { k ->
            String agreed = planned?.get(k)
            String value = (actualMorpheus?.get(k) == agreed) ? agreed : previous?.get(k)
            if (value != null) result[k] = value
        }
        return result
    }

    static Map<String, String> validTags(Map<String, String> tags) {
        return (tags ?: [:]).findAll { k, v -> isValidLabelKey(k) && isValidLabelValue(v) }
    }

    /**
     * Works out what to change on each side. Maps are label name -> value; snapshot null means first sync.
     * @return [harvesterSet: Map, harvesterRemove: Set, morpheusSet: Map, morpheusRemove: Set, snapshot: Map]
     */
    static Map reconcile(Map<String, String> harvester, Map<String, String> morpheus, Map<String, String> snapshot) {
        harvester = harvester ?: [:]
        morpheus = morpheus ?: [:]
        boolean firstSync = (snapshot == null)
        Map<String, String> last = snapshot ?: [:]
        Set<String> keys = new TreeSet<String>()
        keys.addAll(harvester.keySet()); keys.addAll(morpheus.keySet()); keys.addAll(last.keySet())

        Map result = [harvesterSet: [:], harvesterRemove: [] as Set, morpheusSet: [:], morpheusRemove: [] as Set, snapshot: [:]]
        keys.each { String k ->
            String h = harvester[k]
            String m = morpheus[k]
            String s = last[k]
            String agreed
            if (h == m) {
                agreed = h
            } else if (firstSync) {
                agreed = (h != null) ? h : m
            } else if (m == s) {
                agreed = h          // only Harvester changed
            } else {
                agreed = m          // only Morpheus changed, or both changed (Morpheus wins)
            }
            if (agreed != h) {
                if (agreed == null) { result.harvesterRemove << k } else { result.harvesterSet[k] = agreed }
            }
            if (agreed != m) {
                if (agreed == null) { result.morpheusRemove << k } else { result.morpheusSet[k] = agreed }
            }
            if (agreed != null) {
                result.snapshot[k] = agreed
            }
        }
        return result
    }

    /**
     * Labels a user would treat as tags: drops Harvester/Kubernetes system labels and the plugin's own labels.
     */
    static Map<String, String> userLabels(Map labels) {
        Map<String, String> result = [:]
        labels?.each { k, v ->
            String key = k?.toString()
            if (key && !isSystemLabel(key)) {
                result[key] = v == null ? '' : v.toString()
            }
        }
        return result
    }

    static boolean isSystemLabel(String key) {
        if (key.startsWith('morpheus-label.') || key == HarvesterConstants.MORPHEUS_PROVISIONING_LABEL) {
            return true
        }
        int slash = key.indexOf('/')
        if (slash > 0) {
            String domain = key.substring(0, slash).toLowerCase()
            return SYSTEM_LABEL_DOMAINS.any { domain == it || domain.endsWith('.' + it) }
        }
        return false
    }

    private static final String LABEL_NAME = /([A-Za-z0-9]([-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?)/

    /** Kubernetes label key: optional DNS prefix + '/', then a name of up to 63 characters */
    static boolean isValidLabelKey(String key) {
        if (!key) return false
        int slash = key.lastIndexOf('/')
        String name = slash >= 0 ? key.substring(slash + 1) : key
        if (!(name ==~ LABEL_NAME)) return false
        if (slash >= 0) {
            String prefix = key.substring(0, slash)
            if (!prefix || prefix.length() > 253 || !(prefix ==~ /[a-z0-9]([-a-z0-9.]*[a-z0-9])?/)) return false
            if (isSystemLabel(key)) return false
        }
        return !key.startsWith('morpheus-label.')
    }

    /** Kubernetes label value: empty, or up to 63 characters of letters, digits, '-', '_', '.' */
    static boolean isValidLabelValue(String value) {
        return value == null || value == '' || (value ==~ LABEL_NAME)
    }

    // --- Morpheus side ---------------------------------------------------------------------------------------

    private def findInstance(ComputeServer server) {
        Long instanceId = null
        try {
            def workloads = morpheusContext.services.workload.list(new DataQuery().withFilter(new DataFilter('server.id', server.id)))
            instanceId = workloads?.find { it?.instance?.id }?.instance?.id
        } catch (Throwable e) {
            log.info "VmTagsSync: workload lookup for ${server.name} failed: ${e.class.name}: ${e.message}"
        }
        if (!instanceId) {
            try {
                def inst = morpheusContext.services.instance.find(new DataQuery().withFilter(new DataFilter('containers.server.id', server.id)))
                instanceId = inst?.id
            } catch (Throwable e) {
                log.info "VmTagsSync: instance lookup by container for ${server.name} failed: ${e.class.name}: ${e.message}"
            }
        }
        if (!instanceId) {
            return null
        }
        try {
            return morpheusContext.services.instance.get(instanceId)
        } catch (Throwable e) {
            log.info "VmTagsSync: loading instance ${instanceId} for ${server.name} failed: ${e.class.name}: ${e.message}"
            return null
        }
    }

    private def reloadInstance(def instance) {
        try {
            return morpheusContext.services.instance.get(instance.id as Long) ?: instance
        } catch (Throwable ignored) {
            return instance
        }
    }

    /** Tag name -> value, or null when the model doesn't expose its tags */
    static Map<String, String> readTags(def model) {
        List tags = tagList(model)
        if (tags == null) return null
        Map<String, String> result = [:]
        tags.each { tag -> if (tag?.name) result[tag.name.toString()] = tag.value == null ? '' : tag.value.toString() }
        return result
    }

    private static List tagList(def model) {
        if (model == null) return null
        if (model.hasProperty('metadata')) {
            def tags = model.metadata
            return tags == null ? [] : (tags as List)
        }
        if (model.hasProperty('tags')) {
            def tags = model.tags
            return tags == null ? [] : (tags as List)
        }
        return null
    }

    private void writeTags(def model, Map<String, String> set, Set<String> remove) {
        List<MetadataTag> current = (tagList(model) ?: []) as List<MetadataTag>
        List<MetadataTag> removeTags = current.findAll { it.name in remove }
        List<MetadataTag> updateTags = current.findAll { set.containsKey(it.name) && it.value != set[it.name] }
        updateTags.each { it.value = set[it.name] }
        Set<String> existingNames = current.collect { it.name } as Set
        List<MetadataTag> addTags = set.findAll { k, v -> !(k in existingNames) }
                .collect { k, v -> new MetadataTag(name: k, value: v) }

        try {
            if (removeTags) morpheusContext.async.metadataTag.bulkRemove(removeTags).blockingGet()
            if (updateTags) morpheusContext.async.metadataTag.bulkSave(updateTags).blockingGet()
            if (addTags) morpheusContext.async.metadataTag.create(addTags, model).blockingGet()
        } catch (MissingMethodException | MissingPropertyException e) {
            // Older API without the tag service methods: set the list on the model and save it
            log.info "VmTagsSync: metadataTag service not usable (${e.message}), saving tags on the model"
            List<MetadataTag> updated = current.findAll { !(it.name in remove) } + addTags
            model.metadata = updated
            if (model instanceof ComputeServer) {
                morpheusContext.async.computeServer.bulkSave([model as ComputeServer]).blockingGet()
            } else {
                morpheusContext.services.instance.save(model)
            }
        }
    }

    static Map<String, String> readSnapshot(ComputeServer server) {
        def value = server.getConfigProperty(CONFIG_TAG_SNAPSHOT)
        if (value instanceof Map) {
            return (value as Map).collectEntries { k, v -> [(k.toString()): v == null ? '' : v.toString()] } as Map<String, String>
        }
        return null
    }

    private void saveSnapshot(ComputeServer server, Map<String, String> snapshot) {
        if (!server) return
        server.setConfigProperty(CONFIG_TAG_SNAPSHOT, snapshot)
        morpheusContext.async.computeServer.bulkSave([server]).blockingGet()
    }

    private ComputeServer reloadServer(ComputeServer server) {
        try {
            return morpheusContext.services.computeServer.get(server.id) ?: server
        } catch (Exception ignored) {
            return server
        }
    }
}
