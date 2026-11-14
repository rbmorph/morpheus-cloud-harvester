// (C) Copyright 2025 Hewlett Packard Enterprise Development LP

package com.hpe.morpheus.harvester.plugin.ui

import com.hpe.morpheus.harvester.plugin.apihelper.HarvesterApiClient
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A built-in subset of kubectl for the Harvester console tab. Commands are parsed here and turned into Kubernetes
 * API calls through the cloud's Harvester connection; there is no kubectl binary.
 *
 * Read commands: get, describe, logs, top, events, api-resources, api-versions, version, cluster-info, help.
 * Write commands: delete, apply -f -, create -f - / create namespace, patch, label, annotate, cordon, uncordon,
 * scale, rollout restart.
 *
 * Output follows kubectl: tables come from the API server's own table format (the same columns kubectl shows),
 * and -o yaml / json / name / wide are supported. Secret values are hidden from read-only users.
 */
@Slf4j
class KubectlService {

    static final List<String> READ_VERBS = ['get', 'describe', 'logs', 'top', 'events', 'api-resources',
                                           'api-versions', 'version', 'cluster-info', 'help']
    static final List<String> WRITE_VERBS = ['delete', 'apply', 'create', 'patch', 'label', 'annotate', 'cordon',
                                            'uncordon', 'scale', 'rollout']
    static final Map<String, String> UNSUPPORTED = [
            exec          : 'interactive commands are not available here; use the VM console or SSH',
            attach        : 'interactive commands are not available here',
            'port-forward': 'port forwarding is not available here',
            proxy         : 'proxying is not available here',
            cp            : 'copying files is not available here',
            edit          : "use 'get <type> <name> -o yaml', change it, then 'apply -f -' with the YAML",
            run           : "use 'create -f -' with a manifest",
            drain         : "use Harvester's maintenance mode for nodes",
            config        : "the console always uses the cloud's Harvester connection",
            auth          : 'not available here',
            wait          : 'not available here',
            debug         : 'not available here',
            expose        : "use 'create -f -' with a Service manifest",
            replace       : "use 'apply -f -'"
    ]
    /** Flags that take no value (every other flag takes the next word, or =value) */
    static final List<String> BOOLEAN_FLAGS = ['allNamespaces', 'previous', 'overwrite', 'showLabels', 'timestamps',
                                              'watch', 'noHeaders', 'force', 'all']
    static final String TABLE_ACCEPT = 'application/json;as=Table;v=v1;g=meta.k8s.io,application/json'
    static final String FIELD_MANAGER = 'morpheus-harvester-console'
    static final int MAX_OUTPUT = 1024 * 1024
    static final long DISCOVERY_TTL_MS = 10L * 60L * 1000L
    static final List<String> STRATEGIC_GROUPS = ['apps', 'batch', 'policy', 'autoscaling', 'networking.k8s.io',
                                                  'rbac.authorization.k8s.io', 'storage.k8s.io']

    /** API discovery, per Harvester URL */
    private static final Map<String, Map> DISCOVERY = new ConcurrentHashMap<>()

    private final HarvesterApiClient client
    private final boolean showSecrets
    private final String cacheKey

    /**
     * @param showSecrets false hides Secret values (read-only users)
     */
    KubectlService(HarvesterApiClient client, boolean showSecrets, String cacheKey = null) {
        this.client = client
        this.showSecrets = showSecrets
        this.cacheKey = cacheKey ?: client?.baseUrl ?: 'default'
    }

    // ---- parsing ------------------------------------------------------------------------------------------------

    /** Splits a command line like a shell: quotes ('…' and "…") and backslash escapes */
    static List<String> tokenize(String line) {
        List<String> out = []
        StringBuilder cur = null
        String quote = null
        String s = line ?: ''
        int i = 0
        while (i < s.length()) {
            String c = s.substring(i, i + 1)
            if (quote != null) {
                if (c == quote) {
                    quote = null
                } else if (c == '\\' && quote == '"' && i + 1 < s.length()) {
                    i++
                    cur.append(s.charAt(i))
                } else {
                    cur.append(c)
                }
            } else if (c == "'" || c == '"') {
                quote = c
                if (cur == null) cur = new StringBuilder()
            } else if (c == '\\' && i + 1 < s.length()) {
                if (cur == null) cur = new StringBuilder()
                i++
                cur.append(s.charAt(i))
            } else if (c.trim().isEmpty()) {
                if (cur != null) {
                    out << cur.toString()
                    cur = null
                }
            } else {
                if (cur == null) cur = new StringBuilder()
                cur.append(c)
            }
            i++
        }
        if (quote != null) throw new IllegalArgumentException('unterminated quote')
        if (cur != null) out << cur.toString()
        return out
    }

    static String flagName(String raw) {
        switch (raw) {
            case 'n': case 'namespace': return 'namespace'
            case 'A': case 'all-namespaces': return 'allNamespaces'
            case 'o': case 'output': return 'output'
            case 'l': case 'selector': return 'selector'
            case 'field-selector': return 'fieldSelector'
            case 'c': case 'container': return 'container'
            case 'show-labels': return 'showLabels'
            case 'f': case 'filename': return 'filename'
            case 'w': case 'watch': return 'watch'
            case 'no-headers': return 'noHeaders'
            default: return raw.replaceAll(/-([a-z])/) { String all, String ch -> ch.toUpperCase() }
        }
    }

    /**
     * @return [verb, args (positional), flags (name → value, or true for switches)]
     */
    static Map parse(String line) {
        List<String> tokens = tokenize(line?.trim())
        if (tokens && tokens[0] == 'kubectl') tokens = tokens.drop(1)
        if (!tokens) return [verb: null, args: [], flags: [:]]
        String verb = tokens[0]
        List<String> args = []
        Map<String, Object> flags = [:]
        int i = 1
        while (i < tokens.size()) {
            String t = tokens[i]
            if (t == '--') {
                args.addAll(tokens.subList(i + 1, tokens.size()))
                break
            }
            if (t.startsWith('-') && t.length() > 1 && !(t ==~ /-\d+/)) {
                String body = t.startsWith('--') ? t.substring(2) : t.substring(1)
                String value = null
                int eq = body.indexOf('=')
                if (eq >= 0) {
                    value = body.substring(eq + 1)
                    body = body.substring(0, eq)
                } else if (!t.startsWith('--') && body.length() > 1 && body.substring(0, 1) in ['o', 'n', 'l', 'c']) {
                    // -oyaml, -nkube-system
                    value = body.substring(1)
                    body = body.substring(0, 1)
                }
                // -p is "previous" for logs and the patch for patch
                String name = (body == 'p') ? (verb == 'logs' ? 'previous' : 'patch') : flagName(body)
                if (value == null && !(name in BOOLEAN_FLAGS)) {
                    if (i + 1 >= tokens.size()) throw new IllegalArgumentException("flag ${t} needs a value")
                    i++
                    value = tokens[i]
                }
                flags[name] = value == null ? true : value
            } else {
                args << t
            }
            i++
        }
        return [verb: verb, args: args, flags: flags]
    }

    /** Whether a command changes the cluster (needs the read and write permission) */
    static boolean isWrite(Map cmd) {
        return cmd?.verb in WRITE_VERBS
    }

    // ---- running ------------------------------------------------------------------------------------------------

    /**
     * @param input text for "-f -" (YAML or JSON manifests)
     * @return [success, output]
     */
    Map run(String line, String input = null) {
        Map cmd
        try {
            cmd = parse(line)
        } catch (IllegalArgumentException e) {
            return fail("error: ${e.message}".toString())
        }
        String verb = cmd.verb as String
        if (!verb) return [success: true, output: '']
        if (UNSUPPORTED[verb]) return fail("error: '${verb}' isn't supported in this console: ${UNSUPPORTED[verb]}".toString())
        if (cmd.flags.watch) return fail("error: --watch isn't supported in this console; run the command again to refresh")
        try {
            String out
            switch (verb) {
                case 'help': out = help(); break
                case 'get': out = get(cmd); break
                case 'describe': out = describe(cmd); break
                case 'logs': out = logs(cmd); break
                case 'top': out = top(cmd); break
                case 'events': out = get([verb: 'get', args: ['events'], flags: cmd.flags]); break
                case 'api-resources': out = apiResources(); break
                case 'api-versions': out = apiVersions(); break
                case 'version': out = version(); break
                case 'cluster-info': out = "Kubernetes control plane is running at ${client.baseUrl}".toString(); break
                case 'delete': out = delete(cmd); break
                case 'apply': out = applyOrCreate(cmd, input, true); break
                case 'create': out = create(cmd, input); break
                case 'patch': out = patch(cmd); break
                case 'label': out = labelOrAnnotate(cmd, 'labels'); break
                case 'annotate': out = labelOrAnnotate(cmd, 'annotations'); break
                case 'cordon': out = cordon(cmd, true); break
                case 'uncordon': out = cordon(cmd, false); break
                case 'scale': out = scale(cmd); break
                case 'rollout': out = rollout(cmd); break
                default:
                    return fail("error: unknown command \"${verb}\". Type 'help' for the supported commands.".toString())
            }
            return [success: true, output: truncate(out)]
        } catch (KubectlException e) {
            return fail(e.message)
        } catch (Exception e) {
            log.warn("Harvester console: '${line}' failed: ${e.message}", e)
            return fail("error: ${e.message}".toString())
        }
    }

    private static Map fail(String msg) {
        return [success: false, output: msg]
    }

    static String truncate(String s) {
        if (s == null) return ''
        return s.length() > MAX_OUTPUT ? s.substring(0, MAX_OUTPUT) + "\n… output truncated at ${MAX_OUTPUT.intdiv(1024)} KB" : s
    }

    static class KubectlException extends RuntimeException {
        KubectlException(String msg) { super(msg) }
    }

    private static void error(String msg) {
        throw new KubectlException(msg.startsWith('error') || msg.startsWith('Error') ? msg : "error: ${msg}".toString())
    }

    static String help() {
        return '''Built-in kubectl for this Harvester cluster (commands run through the cloud's Harvester connection).

Read:
  get <type>[,<type>] [name...] [-n ns | -A] [-o wide|yaml|json|name] [-l selector] [--field-selector f] [--show-labels]
  get <type>/<name>      get --raw /api/v1/...
  describe <type> [name...] [-n ns]
  logs <pod> [-c container] [-n ns] [--tail N] [--since 10m] [-p] [--timestamps]
  top nodes | top pods [-n ns | -A]
  events [-n ns | -A]
  api-resources   api-versions   version   cluster-info   help

Write (needs the read and write permission):
  apply -f -             (paste YAML or JSON manifests, several separated by ---, in the box below)
  create -f -            create namespace <name>
  delete <type> <name...> [-n ns]
  patch <type> <name> -p '<json>' [--type merge|json|strategic]
  label|annotate <type> <name> key=value key2- [--overwrite]
  cordon|uncordon <node>
  scale <type> <name> --replicas=N
  rollout restart deployment|statefulset|daemonset <name>

Types are resolved like kubectl: plural, singular, short names (vm, vmi, pvc, po, no, ns, ...), kind, or name.group.
The namespace defaults to "default". Not available: exec, attach, port-forward, cp, edit, run, drain, --watch.'''
    }

    // ---- discovery ----------------------------------------------------------------------------------------------

    /** All API resources: [name, singular, shortNames, kind, namespaced, group, version, verbs] */
    List<Map> resources() {
        Map cached = DISCOVERY[cacheKey]
        if (cached && System.currentTimeMillis() - (cached.time as long) < DISCOVERY_TTL_MS) {
            return cached.resources as List<Map>
        }
        List<Map> all = []
        ServiceResponse coreResponse = apiGet('/api/v1')
        if (!coreResponse?.success) throw new KubectlException(apiError(coreResponse))
        all.addAll(resourceList(json(coreResponse), '', 'v1'))
        Map groups = json(apiGet('/apis'))
        (groups?.groups ?: []).each { Map g ->
            String gv = g?.preferredVersion?.groupVersion ?: (g?.versions ? (g.versions as List<Map>)[0]?.groupVersion : null)
            if (!gv) return
            try {
                ServiceResponse r = apiGet("/apis/${gv}".toString())
                if (r?.success) {
                    List<String> parts = gv.split('/') as List<String>
                    all.addAll(resourceList(json(r), parts[0], parts.size() > 1 ? parts[1] : ''))
                }
            } catch (Exception e) {
                log.debug("Harvester console: discovery of ${gv} failed: ${e.message}")
            }
        }
        DISCOVERY[cacheKey] = [time: System.currentTimeMillis(), resources: all]
        return all
    }

    static List<Map> resourceList(Map list, String group, String version) {
        return ((list?.resources ?: []) as List<Map>).findAll { !(it.name as String).contains('/') }.collect { Map r ->
            [name      : r.name, singular: r.singularName ?: '', shortNames: (r.shortNames ?: []) as List, kind: r.kind,
             namespaced: r.namespaced == true, group: group, version: version, verbs: (r.verbs ?: []) as List] as Map
        }
    }

    /** Resolves a type the way kubectl does (plural, singular, short name, kind, optionally name.group) */
    static Map resolve(List<Map> resources, String type) {
        String t = type?.toLowerCase()
        if (!t) return null
        String group = null
        String name = t
        int dot = t.indexOf('.')
        if (dot > 0) {
            name = t.substring(0, dot)
            group = t.substring(dot + 1)
        }
        Closure<Boolean> matches = { Map r ->
            (r.name == name || r.singular == name || name in (r.shortNames as List) || (r.kind as String)?.toLowerCase() == name)
        }
        List<Map> found = resources.findAll { Map r ->
            matches(r) && (group == null || r.group == group || (r.group as String).startsWith(group + '.'))
        }
        if (!found && group != null) {
            found = resources.findAll { Map r -> r.name == t }
        }
        if (!found) return null
        // core first, then an exact plural match, as kubectl prefers
        return found.sort(false) { Map a, Map b ->
            ((a.group ? 1 : 0) <=> (b.group ? 1 : 0)) ?: ((a.name == name ? 0 : 1) <=> (b.name == name ? 0 : 1))
        }[0]
    }

    Map resolveOrFail(String type) {
        Map r = resolve(resources(), type)
        if (!r) error("the server doesn't have a resource type \"${type}\"")
        return r
    }

    static String basePath(Map res, String namespace) {
        String prefix = res.group ? "/apis/${res.group}/${res.version}" : '/api/v1'
        return res.namespaced && namespace ? "${prefix}/namespaces/${namespace}/${res.name}".toString() : "${prefix}/${res.name}".toString()
    }

    static String objectPath(Map res, String namespace, String name) {
        return "${basePath(res, namespace)}/${name}".toString()
    }

    /** pod, virtualmachine.kubevirt.io, … (as kubectl names objects in messages) */
    static String typeRef(Map res) {
        String kind = (res.kind as String).toLowerCase()
        return res.group ? "${kind}.${res.group}".toString() : kind
    }

    static String namespaceOf(Map cmd) {
        return (cmd.flags.namespace ?: 'default') as String
    }

    /** "type name..." or "type/name ..." arguments as [[res, names]] */
    List<Map> targets(List<String> args, boolean namesRequired) {
        if (!args) error('you must specify the type of resource')
        List<Map> out = []
        if (args.every { it.contains('/') }) {
            args.each { String a ->
                List<String> p = a.split('/', 2) as List<String>
                Map res = resolveOrFail(p[0])
                Map existing = out.find { it.res == res }
                if (existing) {
                    (existing.names as List) << p[1]
                } else {
                    out << [res: res, names: [p[1]]]
                }
            }
            return out
        }
        if (args[0].contains('/')) error('there is no need to specify a resource type as a separate argument when passing arguments in resource/name form')
        List<String> names = args.drop(1)
        if (namesRequired && !names) error('resource name may not be empty')
        args[0].split(',').each { String type -> out << [res: resolveOrFail(type), names: names] }
        return out
    }

    // ---- HTTP ---------------------------------------------------------------------------------------------------

    private ServiceResponse apiGet(String path, Map query = null, Map headers = null) {
        return client.raw('GET', path, query, headers)
    }

    static Map json(ServiceResponse r) {
        if (!r?.content) return null
        try {
            def parsed = new JsonSlurper().parseText(r.content as String)
            return parsed instanceof Map ? parsed as Map : [items: parsed]
        } catch (Exception ignored) {
            return null
        }
    }

    /** The API server's error message (Status.message), or the HTTP status */
    static String apiError(ServiceResponse r) {
        Map body = json(r)
        String message = body?.message ?: r?.msg ?: (r?.content as String)?.take(300)
        String reason = body?.reason ? " (${body.reason})" : ''
        return "Error from server${reason}: ${message ?: "HTTP ${r?.errorCode}"}".toString()
    }

    private Map getJson(String path, Map query = null) {
        ServiceResponse r = apiGet(path, query)
        if (!r?.success) throw new KubectlException(apiError(r))
        return json(r)
    }

    // ---- read commands ------------------------------------------------------------------------------------------

    String get(Map cmd) {
        if (cmd.flags.raw) {
            ServiceResponse r = apiGet(cmd.flags.raw as String)
            if (!r?.success) error(apiError(r))
            String content = (r.content ?: '') as String
            try {
                return JsonOutput.prettyPrint(content)
            } catch (Exception ignored) {
                return content
            }
        }
        List<Map> targets = targets(cmd.args as List<String>, false)
        String output = (cmd.flags.output ?: '') as String
        boolean all = cmd.flags.allNamespaces == true
        String ns = all ? null : namespaceOf(cmd)
        Map query = [:]
        if (cmd.flags.selector) query.labelSelector = cmd.flags.selector
        if (cmd.flags.fieldSelector) query.fieldSelector = cmd.flags.fieldSelector

        if (output in ['yaml', 'json']) {
            List<Map> objects = []
            targets.each { Map t ->
                Map res = t.res as Map
                if (t.names) {
                    (t.names as List<String>).each { String n -> objects << clean(getJson(objectPath(res, ns ?: 'default', n))) }
                } else {
                    Map list = getJson(basePath(res, ns), query)
                    ((list?.items ?: []) as List<Map>).each { Map item ->
                        item.apiVersion = item.apiVersion ?: (res.group ? "${res.group}/${res.version}".toString() : 'v1')
                        item.kind = item.kind ?: res.kind
                        objects << clean(item)
                    }
                }
            }
            def value = (objects.size() == 1 && targets.size() == 1 && targets[0].names) ? objects[0] :
                    [apiVersion: 'v1', kind: 'List', items: objects, metadata: [resourceVersion: '']]
            return output == 'json' ? JsonOutput.prettyPrint(JsonOutput.toJson(value)) : YamlWriter.toYaml(value)
        }
        if (output == 'name') {
            List<String> lines = []
            targets.each { Map t ->
                Map res = t.res as Map
                List<String> names = t.names ? t.names as List<String> :
                        (((getJson(basePath(res, ns), query)?.items ?: []) as List<Map>).collect { it.metadata?.name as String })
                names.each { lines << "${typeRef(res)}/${it}".toString() }
            }
            return lines.join('\n')
        }
        if (output && output != 'wide') error("output format \"${output}\" isn't supported here (use wide, yaml, json or name)")
        List<String> parts = []
        targets.each { Map t ->
            parts << table(t.res as Map, t.names as List<String>, ns, all, query, output == 'wide',
                    cmd.flags.showLabels == true, cmd.flags.noHeaders == true)
        }
        return parts.findAll { it }.join('\n\n')
    }

    /** Removes managedFields (as kubectl does by default) and hides Secret values from read-only users */
    Map clean(Map obj) {
        if (obj?.metadata instanceof Map) (obj.metadata as Map).remove('managedFields')
        if (!showSecrets && obj?.kind == 'Secret') {
            ['data', 'stringData'].each { String k ->
                if (obj[k] instanceof Map) {
                    obj[k] = (obj[k] as Map).collectEntries { key, v -> [(key): '<hidden: needs the read and write permission>'] }
                }
            }
            (obj.metadata?.annotations as Map)?.remove('kubectl.kubernetes.io/last-applied-configuration')
        }
        return obj
    }

    String table(Map res, List<String> names, String ns, boolean allNamespaces, Map query, boolean wide, boolean showLabels,
                 boolean noHeaders) {
        Map headers = [Accept: TABLE_ACCEPT]
        Map q = new LinkedHashMap(query ?: [:])
        q.includeObject = showLabels ? 'Metadata' : 'None'
        List<Map> columns = null
        List<List> rows = []
        List<Map> rowObjects = []
        List<String> paths = names ? names.collect { objectPath(res, ns ?: 'default', it) } : [basePath(res, ns)]
        paths.each { String path ->
            ServiceResponse r = apiGet(path, names ? [includeObject: q.includeObject] : q, headers)
            if (!r?.success) throw new KubectlException(apiError(r))
            Map body = json(r)
            if (body?.kind != 'Table') {
                // no table support: name and age
                columns = [[name: 'Name', type: 'string', priority: 0], [name: 'Age', type: 'date', priority: 0]] as List<Map>
                List items = body?.items != null ? body.items as List : [body]
                items.each { def it ->
                    rows << [it?.metadata?.name, it?.metadata?.creationTimestamp]
                    rowObjects << ([metadata: it?.metadata] as Map)
                }
                return
            }
            columns = columns ?: (body.columnDefinitions as List<Map>)
            ((body.rows ?: []) as List<Map>).each { Map row ->
                rows << (row.cells as List)
                rowObjects << ((row.object ?: [:]) as Map)
            }
        }
        if (!rows) {
            if (names) return ''
            return allNamespaces || !res.namespaced ? 'No resources found' : "No resources found in ${ns} namespace.".toString()
        }
        List<Integer> keep = []
        columns.eachWithIndex { Map c, int i -> if (wide || ((c.priority ?: 0) as int) == 0) keep << i }
        List<String> header = keep.collect { int i -> (columns[i].name as String).toUpperCase() }
        List<List<String>> body = rows.collect { List cells ->
            keep.collect { int i -> cellText(cells.size() > i ? cells[i] : null, columns[i].type as String, columns[i].format as String) }
        }
        if (allNamespaces && res.namespaced) {
            header.add(0, 'NAMESPACE')
            body.eachWithIndex { List<String> row, int i -> row.add(0, (rowObjects[i]?.metadata?.namespace ?: '') as String) }
        }
        if (showLabels) {
            header << 'LABELS'
            body.eachWithIndex { List<String> row, int i ->
                Map labels = rowObjects[i]?.metadata?.labels as Map
                row << (labels ? labels.collect { k, v -> "${k}=${v}" }.join(',') : '<none>')
            }
        }
        return formatTable(noHeaders ? null : header, body)
    }

    static String cellText(def value, String type, String format) {
        if (value == null) return '<none>'
        if (type == 'date' || format == 'date-time') return age(value as String)
        if (value instanceof List) return (value as List).join(',')
        if (value instanceof Map) return JsonOutput.toJson(value)
        return value.toString()
    }

    /** kubectl's short age: 45s, 12m, 3h12m, 5d, 2y */
    static String age(String timestamp, Instant now = Instant.now()) {
        if (!timestamp) return '<unknown>'
        try {
            return humanDuration(Duration.between(Instant.parse(timestamp), now))
        } catch (Exception ignored) {
            return timestamp
        }
    }

    static String humanDuration(Duration d) {
        long s = Math.max(0L, d.seconds)
        if (s < 120L) return "${s}s".toString()
        long m = s.intdiv(60L) as long
        if (m < 10L) return (s % 60L ? "${m}m${s % 60L}s" : "${m}m").toString()
        if (m < 180L) return "${m}m".toString()
        long h = m.intdiv(60L) as long
        if (h < 8L) return (m % 60L ? "${h}h${m % 60L}m" : "${h}h").toString()
        if (h < 48L) return "${h}h".toString()
        long days = h.intdiv(24L) as long
        if (days < 8L) return (h % 24L ? "${days}d${h % 24L}h" : "${days}d").toString()
        if (days < 730L) return "${days}d".toString()
        long years = days.intdiv(365L) as long
        return (days % 365L ? "${years}y${days % 365L}d" : "${years}y").toString()
    }

    /** Columns padded to their widest cell with three spaces between, as kubectl prints them */
    static String formatTable(List<String> header, List<List<String>> rows) {
        List<List<String>> all = header ? ([header] + rows) as List<List<String>> : rows
        if (!all) return ''
        int n = all.collect { it.size() }.max() as int
        List<Integer> widths = (0..<n).collect { int i -> all.collect { List<String> r -> i < r.size() ? (r[i] ?: '').length() : 0 }.max() as int }
        return all.collect { List<String> r ->
            (0..<r.size()).collect { int i -> i == r.size() - 1 ? (r[i] ?: '') : (r[i] ?: '').padRight(widths[i] + 3) }.join('')
        }.join('\n')
    }

    String describe(Map cmd) {
        List<Map> targets = targets(cmd.args as List<String>, false)
        String ns = namespaceOf(cmd)
        List<String> out = []
        targets.each { Map t ->
            Map res = t.res as Map
            List<Map> objects = t.names ? (t.names as List<String>).collect { String n -> getJson(objectPath(res, ns, n)) } :
                    ((getJson(basePath(res, res.namespaced ? ns : null))?.items ?: []) as List<Map>)
            if (!objects && !t.names) out << "No resources found in ${ns} namespace.".toString()
            objects.each { Map obj -> out << describeObject(res, clean(obj)) }
        }
        return out.join('\n\n\n')
    }

    String describeObject(Map res, Map obj) {
        Map md = (obj.metadata ?: [:]) as Map
        StringBuilder sb = new StringBuilder()
        Closure line = { String label, def value -> sb.append((label + ':').padRight(14)).append(value ?: '<none>').append('\n') }
        Closure kv = { String label, Map m ->
            if (!m) {
                line(label, '<none>')
            } else {
                boolean first = true
                m.each { k, v ->
                    sb.append(first ? (label + ':').padRight(14) : ''.padRight(14)).append("${k}=${v}\n")
                    first = false
                }
            }
        }
        line('Name', md.name)
        if (res.namespaced) line('Namespace', md.namespace)
        kv('Labels', md.labels as Map)
        Map annotations = new LinkedHashMap((md.annotations ?: [:]) as Map)
        annotations.remove('kubectl.kubernetes.io/last-applied-configuration')
        kv('Annotations', annotations)
        line('API Version', obj.apiVersion ?: (res.group ? "${res.group}/${res.version}" : 'v1'))
        line('Kind', obj.kind ?: res.kind)
        line('Created', md.creationTimestamp ? "${md.creationTimestamp} (${age(md.creationTimestamp as String)} ago)" : null)
        if (md.ownerReferences) line('Controlled By', (md.ownerReferences as List<Map>).collect { "${it.kind}/${it.name}" }.join(', '))
        obj.each { k, value ->
            String key = k as String
            if (key in ['apiVersion', 'kind', 'metadata'] || value == null) return
            sb.append(key.capitalize()).append(':\n')
            sb.append(YamlWriter.toYaml(value).readLines().collect { '  ' + it }.join('\n')).append('\n')
        }
        sb.append('Events:')
        String events = eventsFor(md.uid as String, md.namespace as String)
        sb.append(events ? '\n' + events.readLines().collect { '  ' + it }.join('\n') : '       <none>')
        return sb.toString()
    }

    String eventsFor(String uid, String namespace) {
        if (!uid) return ''
        try {
            String path = namespace ? "/api/v1/namespaces/${namespace}/events".toString() : '/api/v1/events'
            ServiceResponse r = apiGet(path, [fieldSelector: "involvedObject.uid=${uid}".toString()])
            List<Map> items = ((json(r)?.items ?: []) as List<Map>).sort(false) {
                (it.lastTimestamp ?: it.eventTime ?: it.metadata?.creationTimestamp ?: '') as String
            }
            if (!items) return ''
            List<List<String>> rows = items.collect { Map e ->
                [(e.type ?: '') as String, (e.reason ?: '') as String,
                 age((e.lastTimestamp ?: e.eventTime ?: e.metadata?.creationTimestamp) as String),
                 (e.source?.component ?: e.reportingComponent ?: '') as String,
                 ((e.message ?: '') as String).replace('\n', ' ')]
            }
            return formatTable(['Type', 'Reason', 'Age', 'From', 'Message'], rows)
        } catch (Exception ignored) {
            return ''
        }
    }

    String logs(Map cmd) {
        List<String> args = cmd.args as List<String>
        if (!args) error('expected POD or TYPE/NAME')
        String pod = args[0]
        if (pod.contains('/')) {
            List<String> p = pod.split('/', 2) as List<String>
            if (!(p[0] in ['pod', 'pods', 'po'])) error("logs here need a pod name (find it with 'get pods -n <namespace>')")
            pod = p[1]
        }
        Map query = [:]
        String container = (cmd.flags.container ?: (args.size() > 1 ? args[1] : null)) as String
        if (container) query.container = container
        if (cmd.flags.tail != null && cmd.flags.tail != '-1') query.tailLines = cmd.flags.tail.toString()
        if (cmd.flags.previous) query.previous = 'true'
        if (cmd.flags.timestamps) query.timestamps = 'true'
        if (cmd.flags.since) query.sinceSeconds = parseSince(cmd.flags.since as String).toString()
        query.limitBytes = MAX_OUTPUT.toString()
        ServiceResponse r = apiGet("/api/v1/namespaces/${namespaceOf(cmd)}/pods/${pod}/log".toString(), query)
        if (!r?.success) error(apiError(r))
        return (r.content ?: '') as String
    }

    static long parseSince(String s) {
        def m = s =~ /^(\d+)([smhd]?)$/
        if (!m.matches()) throw new KubectlException("error: invalid --since value \"${s}\" (use e.g. 30s, 10m, 2h)".toString())
        long n = m.group(1) as long
        switch (m.group(2)) {
            case 'm': return n * 60L
            case 'h': return n * 3600L
            case 'd': return n * 86400L
            default: return n
        }
    }

    String top(Map cmd) {
        String what = (cmd.args as List) ? (cmd.args as List)[0] as String : null
        if (what in ['node', 'nodes', 'no']) {
            Map metrics = getMetrics('/apis/metrics.k8s.io/v1beta1/nodes')
            Map<String, Map> alloc = ((getJson('/api/v1/nodes')?.items ?: []) as List<Map>).collectEntries { Map it ->
                [(it.metadata?.name): (it.status?.allocatable ?: [:])]
            } as Map<String, Map>
            List<List<String>> rows = ((metrics?.items ?: []) as List<Map>).sort(false) { it.metadata?.name as String }.collect { Map m ->
                String name = m.metadata?.name
                long cpu = milliCores(m.usage?.cpu)
                long mem = bytes(m.usage?.memory)
                long cpuAlloc = milliCores(alloc[name]?.cpu)
                long memAlloc = bytes(alloc[name]?.memory)
                [name, "${cpu}m".toString(), cpuAlloc ? "${(cpu * 100L).intdiv(cpuAlloc)}%".toString() : '<unknown>',
                 "${mem.intdiv(1024L * 1024L)}Mi".toString(), memAlloc ? "${(mem * 100L).intdiv(memAlloc)}%".toString() : '<unknown>']
            }
            return formatTable(['NAME', 'CPU(cores)', 'CPU(%)', 'MEMORY(bytes)', 'MEMORY(%)'], rows)
        }
        if (what in ['pod', 'pods', 'po']) {
            boolean all = cmd.flags.allNamespaces == true
            String path = all ? '/apis/metrics.k8s.io/v1beta1/pods' : "/apis/metrics.k8s.io/v1beta1/namespaces/${namespaceOf(cmd)}/pods".toString()
            Map metrics = getMetrics(path)
            List<Map> items = ((metrics?.items ?: []) as List<Map>).sort(false) { "${it.metadata?.namespace}/${it.metadata?.name}".toString() }
            if (!items) return all ? 'No resources found' : "No resources found in ${namespaceOf(cmd)} namespace.".toString()
            List<List<String>> rows = items.collect { Map m ->
                List<Map> containers = (m.containers ?: []) as List<Map>
                long cpu = containers.collect { milliCores(it.usage?.cpu) }.sum(0L) as long
                long mem = containers.collect { bytes(it.usage?.memory) }.sum(0L) as long
                List<String> row = [m.metadata?.name as String, "${cpu}m".toString(), "${mem.intdiv(1024L * 1024L)}Mi".toString()]
                if (all) row.add(0, m.metadata?.namespace as String)
                row
            }
            return formatTable(all ? ['NAMESPACE', 'NAME', 'CPU(cores)', 'MEMORY(bytes)'] : ['NAME', 'CPU(cores)', 'MEMORY(bytes)'], rows)
        }
        error('use "top nodes" or "top pods"')
        return null
    }

    private Map getMetrics(String path) {
        ServiceResponse r = apiGet(path)
        if (!r?.success) error("Metrics API not available (${apiError(r)})".toString())
        return json(r)
    }

    /** CPU quantity (e.g. 250m, 2, 123456789n) in millicores */
    static long milliCores(def q) {
        if (q == null) return 0L
        def m = q.toString().trim() =~ /^([0-9.]+)([numk]?)$/
        if (!m.matches()) return 0L
        BigDecimal n = new BigDecimal(m.group(1))
        switch (m.group(2)) {
            case 'n': return (n / 1000000G).longValue()
            case 'u': return (n / 1000G).longValue()
            case 'm': return n.longValue()
            case 'k': return (n * 1000000G).longValue()
            default: return (n * 1000G).longValue()
        }
    }

    /** Memory quantity (Ki, Mi, Gi, K, M, G, plain) in bytes */
    static long bytes(def q) {
        if (q == null) return 0L
        def m = q.toString().trim() =~ /^([0-9.]+)([KMGTE]i?)?$/
        if (!m.matches()) return 0L
        BigDecimal n = new BigDecimal(m.group(1))
        Map<String, BigDecimal> units = [Ki: 1024G, Mi: 1048576G, Gi: 1073741824G, Ti: 1099511627776G,
                                         K : 1000G, M: 1000000G, G: 1000000000G, T: 1000000000000G]
        String unit = m.group(2)
        return (n * (unit ? (units[unit] ?: 1G) : 1G)).longValue()
    }

    String apiResources() {
        List<Map> rs = resources().sort(false) { Map a, Map b -> ((a.group ?: '') <=> (b.group ?: '')) ?: (a.name <=> b.name) }
        return formatTable(['NAME', 'SHORTNAMES', 'APIVERSION', 'NAMESPACED', 'KIND'], rs.collect { Map r ->
            [r.name as String, (r.shortNames as List).join(','), (r.group ? "${r.group}/${r.version}" : 'v1').toString(),
             r.namespaced.toString(), r.kind as String]
        })
    }

    String apiVersions() {
        List<String> versions = ['v1']
        Map groups = json(apiGet('/apis'))
        (groups?.groups ?: []).each { Map g -> (g.versions ?: []).each { Map v -> versions << (v.groupVersion as String) } }
        return versions.unique().sort().join('\n')
    }

    String version() {
        Map v = getJson('/version')
        return "Client: Morpheus Harvester plugin (built-in kubectl subset)\nServer Version: ${v?.gitVersion ?: 'unknown'}".toString()
    }

    // ---- write commands -----------------------------------------------------------------------------------------

    String delete(Map cmd) {
        List<Map> targets = targets(cmd.args as List<String>, true)
        String ns = namespaceOf(cmd)
        List<String> out = []
        targets.each { Map t ->
            Map res = t.res as Map
            (t.names as List<String>).each { String name ->
                ServiceResponse r = client.raw('DELETE', objectPath(res, ns, name))
                if (!r?.success) error(apiError(r))
                out << "${(res.kind as String).toLowerCase()} \"${name}\" deleted".toString()
            }
        }
        return out.join('\n')
    }

    String create(Map cmd, String input) {
        List<String> args = cmd.args as List<String>
        if (args && args[0] in ['namespace', 'ns']) {
            if (args.size() < 2) error('namespace name is required')
            String body = JsonOutput.toJson([apiVersion: 'v1', kind: 'Namespace', metadata: [name: args[1]]])
            ServiceResponse r = client.raw('POST', '/api/v1/namespaces', null, null, body, 'application/json')
            if (!r?.success) error(apiError(r))
            return "namespace/${args[1]} created".toString()
        }
        return applyOrCreate(cmd, input, false)
    }

    /** apply -f - (server-side apply) or create -f -, with the manifests pasted in the input box */
    String applyOrCreate(Map cmd, String input, boolean apply) {
        if (cmd.flags.filename != '-') error("use '-f -' and paste the manifest in the box below the command line")
        List<String> docs = splitDocuments(input)
        if (!docs) error('no manifest: paste YAML or JSON in the box below the command line')
        List<String> out = []
        docs.each { String doc ->
            Map head = manifestHead(doc)
            if (!head.apiVersion || !head.kind) error('each manifest needs apiVersion and kind')
            if (!head.name) error("the ${head.kind} manifest has no metadata.name".toString())
            String apiVersion = head.apiVersion as String
            Map res = resources().find { Map it -> it.kind == head.kind && (it.group ? "${it.group}/${it.version}".toString() : 'v1') == apiVersion }
            if (!res) {
                String group = apiVersion.contains('/') ? apiVersion.split('/')[0] : ''
                res = resources().find { Map it -> it.kind == head.kind && (it.group ?: '') == group }
            }
            if (!res) error("no resource for ${apiVersion} ${head.kind} on this cluster".toString())
            String ns = res.namespaced ? (head.namespace ?: cmd.flags.namespace ?: 'default') as String : null
            ServiceResponse r
            if (apply) {
                r = client.raw('PATCH', objectPath(res, ns, head.name as String), [fieldManager: FIELD_MANAGER, force: 'true'], null,
                        doc, 'application/apply-patch+yaml')
            } else {
                r = client.raw('POST', basePath(res, ns), [fieldManager: FIELD_MANAGER], null, doc, 'application/yaml')
            }
            if (!r?.success) error(apiError(r))
            String done = apply ? (r.errorCode?.toString() == '201' ? 'created' : 'configured') : 'created'
            out << "${typeRef(res)}/${head.name} ${done}".toString()
        }
        return out.join('\n')
    }

    /** Manifests separated by --- lines (or one JSON object, a JSON array, or a JSON List) */
    static List<String> splitDocuments(String input) {
        String text = input?.trim()
        if (!text) return []
        if (text.startsWith('{') || text.startsWith('[')) {
            def parsed = new JsonSlurper().parseText(text)
            List items = parsed instanceof List ? parsed as List : (parsed?.kind == 'List' ? parsed.items as List : [parsed])
            return items.collect { JsonOutput.toJson(it) }
        }
        return (text.split(/(?m)^---\s*$/) as List<String>).collect { it.trim() }.findAll { String doc ->
            doc.readLines().any { String l -> l.trim() && !l.trim().startsWith('#') }
        }
    }

    /** apiVersion, kind, metadata.name and metadata.namespace of a YAML or JSON manifest */
    static Map manifestHead(String doc) {
        String text = doc?.trim() ?: ''
        if (text.startsWith('{')) {
            Map m = new JsonSlurper().parseText(text) as Map
            return [apiVersion: m.apiVersion, kind: m.kind, name: m.metadata?.name, namespace: m.metadata?.namespace]
        }
        Map head = [:]
        List<String> lines = text.readLines()
        int i = 0
        while (i < lines.size()) {
            String l = stripComment(lines[i])
            def top = l =~ /^([A-Za-z]+):\s*(.*)$/
            if (top.matches()) {
                String key = top.group(1)
                String value = unquote(top.group(2))
                if (key in ['apiVersion', 'kind'] && value) head[key] = value
                if (key == 'metadata' && !value) {
                    int indent = -1
                    i++
                    while (i < lines.size()) {
                        String child = stripComment(lines[i])
                        if (!child.trim()) {
                            i++
                            continue
                        }
                        int ci = child.length() - child.replaceAll(/^\s+/, '').length()
                        if (ci == 0) {
                            i--
                            break
                        }
                        if (indent < 0) indent = ci
                        if (ci == indent) {
                            def kv = child.trim() =~ /^(name|namespace):\s*(.*)$/
                            if (kv.matches()) head[kv.group(1)] = unquote(kv.group(2))
                        }
                        i++
                    }
                }
            }
            i++
        }
        return head
    }

    private static String stripComment(String l) {
        return l.replaceAll(/\s+#.*$/, '').replaceAll(/^#.*$/, '')
    }

    private static String unquote(String v) {
        String s = v?.trim()
        if (s && s.length() >= 2 && ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1)
        }
        return s
    }

    private ServiceResponse patchObject(Map res, String ns, String name, def body, String contentType, String subresource = null) {
        String path = objectPath(res, ns, name) + (subresource ? "/${subresource}" : '')
        String text = body instanceof String ? body as String : JsonOutput.toJson(body)
        return client.raw('PATCH', path, [fieldManager: FIELD_MANAGER], null, text, contentType)
    }

    /** kubectl defaults to a strategic merge patch, which only built-in types support; custom types get a merge patch */
    static String defaultPatchType(Map res) {
        return !res.group || (res.group as String) in STRATEGIC_GROUPS ? 'application/strategic-merge-patch+json' : 'application/merge-patch+json'
    }

    String patch(Map cmd) {
        List<Map> targets = targets(cmd.args as List<String>, true)
        if (!cmd.flags.patch) error("patch needs -p '<json>'")
        String type = (cmd.flags.type ?: '') as String
        if (!(type in ['', 'merge', 'json', 'strategic'])) error('--type must be merge, json or strategic')
        List<String> out = []
        targets.each { Map t ->
            Map res = t.res as Map
            String contentType = type == 'merge' ? 'application/merge-patch+json' :
                    type == 'json' ? 'application/json-patch+json' :
                            type == 'strategic' ? 'application/strategic-merge-patch+json' : defaultPatchType(res)
            (t.names as List<String>).each { String name ->
                ServiceResponse r = patchObject(res, namespaceOf(cmd), name, cmd.flags.patch as String, contentType)
                if (!r?.success) error(apiError(r))
                out << "${typeRef(res)}/${name} patched".toString()
            }
        }
        return out.join('\n')
    }

    String labelOrAnnotate(Map cmd, String field) {
        List<String> args = cmd.args as List<String>
        List<String> changes = args.drop(1).findAll { it.contains('=') || (it.endsWith('-') && !it.contains('/')) }
        List<String> rest = args - changes
        if (!changes) error("at least one ${field == 'labels' ? 'label' : 'annotation'} update is required".toString())
        List<Map> targets = targets(rest, true)
        Map<String, Object> updates = [:]
        changes.each { String c ->
            if (c.endsWith('-') && !c.contains('=')) {
                updates[c.substring(0, c.length() - 1)] = null
            } else {
                int eq = c.indexOf('=')
                updates[c.substring(0, eq)] = c.substring(eq + 1)
            }
        }
        List<String> out = []
        String ns = namespaceOf(cmd)
        targets.each { Map t ->
            Map res = t.res as Map
            (t.names as List<String>).each { String name ->
                if (!cmd.flags.overwrite) {
                    Map metadata = (getJson(objectPath(res, ns, name))?.metadata ?: [:]) as Map
                    Map current = (metadata[field] ?: [:]) as Map
                    updates.each { k, v ->
                        if (v != null && current.containsKey(k) && current[k] != v) {
                            error("'${k}' already has a value (${current[k]}), and --overwrite is false".toString())
                        }
                    }
                }
                ServiceResponse r = patchObject(res, ns, name, [metadata: [(field): updates]], 'application/merge-patch+json')
                if (!r?.success) error(apiError(r))
                out << "${typeRef(res)}/${name} ${field == 'labels' ? 'labeled' : 'annotated'}".toString()
            }
        }
        return out.join('\n')
    }

    String cordon(Map cmd, boolean on) {
        List<String> names = (cmd.args as List<String>).collect { String a -> a.contains('/') ? a.split('/', 2)[1] : a }
        if (!names) error('node name is required')
        Map res = resolveOrFail('nodes')
        return names.collect { String name ->
            ServiceResponse r = patchObject(res, null, name, [spec: [unschedulable: on ? true : null]], 'application/merge-patch+json')
            if (!r?.success) error(apiError(r))
            "node/${name} ${on ? 'cordoned' : 'uncordoned'}".toString()
        }.join('\n')
    }

    String scale(Map cmd) {
        if (cmd.flags.replicas == null || !(cmd.flags.replicas.toString() ==~ /\d+/)) error('--replicas=N is required')
        List<Map> targets = targets(cmd.args as List<String>, true)
        List<String> out = []
        targets.each { Map t ->
            Map res = t.res as Map
            (t.names as List<String>).each { String name ->
                ServiceResponse r = patchObject(res, namespaceOf(cmd), name, [spec: [replicas: cmd.flags.replicas.toString() as int]],
                        'application/merge-patch+json', 'scale')
                if (!r?.success) error(apiError(r))
                out << "${typeRef(res)}/${name} scaled".toString()
            }
        }
        return out.join('\n')
    }

    String rollout(Map cmd) {
        List<String> args = cmd.args as List<String>
        if (!args || args[0] != 'restart') error("only 'rollout restart' is supported here")
        List<Map> targets = targets(args.drop(1), true)
        String now = Instant.now().toString()
        List<String> out = []
        targets.each { Map t ->
            Map res = t.res as Map
            if (!(res.kind in ['Deployment', 'StatefulSet', 'DaemonSet'])) error('rollout restart works on deployments, statefulsets and daemonsets')
            (t.names as List<String>).each { String name ->
                ServiceResponse r = patchObject(res, namespaceOf(cmd), name,
                        [spec: [template: [metadata: [annotations: ['kubectl.kubernetes.io/restartedAt': now]]]]], 'application/merge-patch+json')
                if (!r?.success) error(apiError(r))
                out << "${typeRef(res)}/${name} restarted".toString()
            }
        }
        return out.join('\n')
    }
}
