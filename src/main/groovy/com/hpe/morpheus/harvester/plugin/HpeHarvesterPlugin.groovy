/*
* Copyright 2022 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.hpe.morpheus.harvester.plugin

import com.hpe.morpheus.harvester.plugin.datasets.HpeHarvesterProjectProvider
import com.morpheusdata.core.MorpheusContext
import com.hpe.morpheus.harvester.plugin.ui.HarvesterVmYamlController
import com.hpe.morpheus.harvester.plugin.ui.HarvesterVmCdromController
import com.hpe.morpheus.harvester.plugin.ui.HarvesterVmCdromServerTab
import com.hpe.morpheus.harvester.plugin.ui.HarvesterKubectlHostTab
import com.hpe.morpheus.harvester.plugin.ui.HarvesterKubectlController
import com.morpheusdata.views.HandlebarsRenderer
import com.hpe.morpheus.harvester.plugin.ui.HarvesterVmYamlServerTab
import com.morpheusdata.core.Plugin
import com.hpe.morpheus.harvester.plugin.datasets.HpeHarvesterImageDataSetProvider
import com.hpe.morpheus.harvester.plugin.datasets.HpeHarvesterNodeImageDataSetProvider
import groovy.util.logging.Slf4j

@Slf4j
class HpeHarvesterPlugin extends Plugin {
    private static final String PLUGIN_CODE = 'morpheus-harvester-plugin'
    private static final String PLUGIN_NAME = 'Harvester'
    private static final String PLUGIN_DESCRIPTION = 'Plugin for Harvester Integration'
    private static final String PLUGIN_AUTHOR = 'rbmorph'

    @Override
    String getCode() {
        return PLUGIN_CODE
    }

    @Override
    void initialize() {
        log.info('Harvester: Initializing Plugin')

        this.name = PLUGIN_NAME
        this.description = PLUGIN_DESCRIPTION
        this.author = PLUGIN_AUTHOR

        this.registerProvider(new HpeHarvesterCloudProvider(this, this.morpheus))
        // Harvester is a cloud only. A Harvester cluster type is not registered: it would claim the
        // same provision type and made Morpheus treat Harvester VMs as cluster provisioning (cluster pools only).
        // HpeHarvesterClusterProvider is still used internally to clean up a cloud's synced data.
        this.registerProvider(new HpeHarvesterProvisionProvider(this, this.morpheus))

        // register datastore provider
        this.registerProvider(new HpeHarvesterDatastoreTypeProvider(this, this.morpheus))
        // this.registerProvider(new HpeHarvesterBackupProvider(this, morpheus))

        // register dataset provider
        this.registerProvider(new HpeHarvesterImageDataSetProvider(this, this.morpheus))
        this.registerProvider(new HpeHarvesterNodeImageDataSetProvider(this, this.morpheus))
        this.registerProvider(new HpeHarvesterProjectProvider(this, this.morpheus))

        // "Harvester YAML" tab on Harvester VMs under Infrastructure > Compute. Not on instance pages: any instance tab
        // makes Morpheus build the plugin's copy of every instance it shows (about 2 s per instance page).
        this.registerProvider(new HarvesterVmYamlServerTab(this, this.morpheus))
        // "Harvester CD-ROM" tab: attach ISO images, eject CD-ROMs
        this.registerProvider(new HarvesterVmCdromServerTab(this, this.morpheus))
        // "Harvester Console" tab on Harvester hosts (Infrastructure > Compute > Hosts): built-in kubectl subset
        this.registerProvider(new HarvesterKubectlHostTab(this, this.morpheus))
        // Endpoints behind the tab's Edit / Save, for roles with "Harvester: Edit VM YAML" (Infrastructure section) at Full
        // and the CD-ROM section (attach ISO / eject), for "Harvester: Manage CD-ROM" at Full
        this.setPermissions((this.getPermissions() ?: []) +
                [HarvesterVmYamlController.permission(), HarvesterVmCdromController.permission()] +
                HarvesterKubectlController.permissions())
        // A plugin with controllers and no renderer of its own makes Morpheus add the plugin's templates to its shared
        // template list, which is fixed-size in this Morpheus version (Arrays.asList): the upload then fails with
        // UnsupportedOperationException. With its own renderer, Morpheus skips that step.
        try {
            if (!this.hasCustomRender() && this.getClassLoader()) {
                this.setRenderer(new HandlebarsRenderer(this.getClassLoader()))
            }
        } catch (Exception e) {
            log.warn("Harvester: unable to set the plugin renderer: ${e.message}")
        }
        this.setControllers((this.getControllers() ?: []) +
                [new HarvesterVmYamlController(this, this.morpheus), new HarvesterVmCdromController(this, this.morpheus),
                 new HarvesterKubectlController(this, this.morpheus)])
    }

    /**
     * Called when a plugin is being removed from the plugin manager (aka Uninstalled)
     */
    @Override
    void onDestroy() {
        log.info('Harvester: Destroying Plugin')
    }

    def MorpheusContext getMorpheusContext() {
        this.morpheus
    }
}

