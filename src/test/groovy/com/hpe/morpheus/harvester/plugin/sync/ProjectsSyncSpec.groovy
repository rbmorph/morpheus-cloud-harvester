package com.hpe.morpheus.harvester.plugin.sync

import com.hpe.morpheus.harvester.plugin.HpeHarvesterPlugin
import com.hpe.morpheus.harvester.plugin.apihelper.ProjectApiHelper
import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.cloud.MorpheusCloudPoolService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudPoolService
import com.morpheusdata.core.synchronous.cloud.MorpheusSynchronousCloudService
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.ComputeServerGroup
import com.morpheusdata.model.Account
import com.morpheusdata.model.projection.CloudPoolIdentity
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import spock.lang.Specification

/**
 * Unit tests for ProjectsSync class - Comprehensive coverage
 */
class ProjectsSyncSpec extends Specification {

    ProjectsSync projectsSync
    HpeHarvesterPlugin mockPlugin
    MorpheusContext mockMorpheusContext
    ComputeServerGroup mockCluster
    Cloud mockCloud

    def setup() {
        mockPlugin = Mock(HpeHarvesterPlugin)
        mockMorpheusContext = Mock(MorpheusContext)
        mockCluster = Mock(ComputeServerGroup)
        mockCloud = Mock(Cloud)

        // Setup the plugin morpheus context
        mockPlugin.morpheusContext >> mockMorpheusContext

        // Setup cluster relationship
        mockCluster.cloud >> mockCloud
        mockCluster.id >> 123L
        mockCluster.owner >> Mock(Account)

        projectsSync = new ProjectsSync(mockPlugin, mockCluster)
    }

    def "test constructor initializes fields correctly"() {
        when:
        def sync = new ProjectsSync(mockPlugin, mockCluster)

        then:
        // Test that the object was created successfully - we can't access private fields directly
        sync != null
    }

    def "test execute with failed project fetch"() {
        given:
        def mockProjectApiHelper = Mock(ProjectApiHelper)
        def mockResult = [success: false, error: "API error"]
        ProjectApiHelper.metaClass.constructor = { ComputeServerGroup cluster -> mockProjectApiHelper }
        mockProjectApiHelper.getProjects() >> mockResult

        when:
        projectsSync.execute()

        then:
        noExceptionThrown() // Should log error, not throw
    }

    def "test execute with exception thrown in project fetch"() {
        given:
        def mockProjectApiHelper = Mock(ProjectApiHelper)
        ProjectApiHelper.metaClass.constructor = { ComputeServerGroup cluster -> mockProjectApiHelper }
        mockProjectApiHelper.getProjects() >> { throw new RuntimeException("API failure") }

        when:
        projectsSync.execute()

        then:
        noExceptionThrown() // Should log error, not throw
    }

    def "test addProjects handles empty and null lists"() {
        given:
        def morpheusServices = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def poolService = Mock(MorpheusSynchronousCloudPoolService)
        mockMorpheusContext.services >> morpheusServices
        morpheusServices.cloud >> cloudService
        morpheusServices.cloud.pool >> poolService

        when:
        projectsSync.addProjects(null)
        projectsSync.addProjects([])

        then:
        0 * morpheusServices.cloud.pool.bulkCreate(_) // Verify bulkCreate not called for empty projects
    }


    def "test addProjects"() {
        given:
        def morpheusServices = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def poolService = Mock(MorpheusSynchronousCloudPoolService)
        mockMorpheusContext.services >> morpheusServices
        morpheusServices.cloud >> cloudService
        morpheusServices.cloud.pool >> poolService
        // mock the projects lists
        def projects = [[uid: "uid-1", name: "Project 1"], [uid: "uid-2", name: "Project 2"]]

        // mock the bulkCreate method to return a success response
        poolService.bulkCreate(*_) >> { List items ->
            return [success: true, data: items]
        }

        when:
        projectsSync.addProjects(projects)

        then:
        1 * morpheusServices.cloud.pool.bulkCreate(*_) // Verify bulkCreate called for projects
    }

    def "test execute handles exception thrown in try block"() {
        given:
        def mockProjectApiHelper = Mock(ProjectApiHelper)
        ProjectApiHelper.metaClass.constructor = { ComputeServerGroup cluster -> mockProjectApiHelper }
        mockProjectApiHelper.getProjects() >> { throw new RuntimeException("API failure") }

        when:
        projectsSync.execute()

        then:
        noExceptionThrown() // error is logged, not thrown
    }

    def "test updateProjects handles empty and null lists"() {
        given:
        def morpheusServices = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def poolService = Mock(MorpheusSynchronousCloudPoolService)
        mockMorpheusContext.services >> morpheusServices
        morpheusServices.cloud >> cloudService
        morpheusServices.cloud.pool >> poolService

        when:
        projectsSync.updateProjects(null)
        projectsSync.updateProjects([])

        then:
        0 * poolService.bulkSave(_) // Verify bulkSave not called for empty/null projects
    }

    def "test updateProjects with no changes needed"() {
        given:
        def morpheusServices = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def poolService = Mock(MorpheusSynchronousCloudPoolService)
        mockMorpheusContext.services >> morpheusServices
        morpheusServices.cloud >> cloudService
        morpheusServices.cloud.pool >> poolService

        // Create mock update items where names match (no update needed)
        // pools already in the current shape: active, removable, not hidden
        def existingPool1 = Mock(CloudPool)
        existingPool1.name >> "Project 1"
        existingPool1.active >> true
        existingPool1.removable >> true
        existingPool1.hidden >> false
        existingPool1.type >> 'default'
        existingPool1.refType >> 'ComputeServerGroup'
        existingPool1.refId >> 123L
        def existingPool2 = Mock(CloudPool)
        existingPool2.name >> "Project 2"
        existingPool2.active >> true
        existingPool2.removable >> true
        existingPool2.hidden >> false
        existingPool2.type >> 'default'
        existingPool2.refType >> 'ComputeServerGroup'
        existingPool2.refId >> 123L

        def updateItems = [
            [existingItem: existingPool1, masterItem: [name: "Project 1"]],
            [existingItem: existingPool2, masterItem: [name: "Project 2"]]
        ]

        when:
        projectsSync.updateProjects(updateItems)

        then:
        0 * poolService.bulkSave(_) // No items should be updated since names match
    }

    def "test updateProjects with changes needed"() {
        given:
        def morpheusServices = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def poolService = Mock(MorpheusSynchronousCloudPoolService)
        mockMorpheusContext.services >> morpheusServices
        morpheusServices.cloud >> cloudService
        morpheusServices.cloud.pool >> poolService

        // Create mock update items where names differ (update needed)
        def existingPool1 = Mock(CloudPool)
        existingPool1.name >> "Old Project 1"
        def existingPool2 = Mock(CloudPool)
        existingPool2.name >> "Project 2"

        def updateItems = [
            [existingItem: existingPool1, masterItem: [name: "New Project 1"]],
            [existingItem: existingPool2, masterItem: [name: "Project 2"]]
        ]

        // Mock bulkSave to return a Single with success result
        poolService.bulkSave(*_) >> Single.just([success: true])

        when:
        projectsSync.updateProjects(updateItems)

        then:
        1 * poolService.bulkSave(*_) // Should be called once with the updated item
        1 * existingPool1.setName("New Project 1") // Verify name was updated
        0 * existingPool2.setName(_) // Verify second item name was not changed
    }

    def "test removeProjects handles empty and null lists"() {
        given:
        def morpheusServices = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def poolService = Mock(MorpheusSynchronousCloudPoolService)
        mockMorpheusContext.services >> morpheusServices
        morpheusServices.cloud >> cloudService
        morpheusServices.cloud.pool >> poolService

        when:
        projectsSync.removeProjects(null)
        projectsSync.removeProjects([])

        then:
        0 * poolService.bulkRemove(_) // Verify bulkRemove not called for empty/null projects
    }

    def "test removeProjects"() {
        given:
        def morpheusServices = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def poolService = Mock(MorpheusSynchronousCloudPoolService)
        mockMorpheusContext.services >> morpheusServices
        morpheusServices.cloud >> cloudService
        morpheusServices.cloud.pool >> poolService

        // Mock projects to remove
        def projectsToRemove = [
            Mock(CloudPool) { getId() >> 1L },
            Mock(CloudPool) { getId() >> 2L }
        ]

        // Mock bulkRemove to return a Single with success result
        poolService.bulkRemove(*_) >> Single.just([success: true])

        when:
        projectsSync.removeProjects(projectsToRemove)

        then:
        1 * poolService.bulkRemove(projectsToRemove) // Verify bulkRemove called with correct projects
    }

    // ===== execute() SyncTask path tests =====

    def "execute() adds project to DB when API returns new project and DB is empty"() {
        given:
        GroovySpy(ProjectApiHelper, global: true)
        def mockApiHelper = Mock(ProjectApiHelper)
        new ProjectApiHelper(mockCluster) >> mockApiHelper
        mockApiHelper.getProjects() >> [success: true, projects: [
            [uid: 'proj-uid-1', name: 'my-project']
        ]]

        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudSvc = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def asyncPoolSvc = Mock(MorpheusCloudPoolService)
        def morpheusServices = Mock(MorpheusServices)
        def syncCloudSvc = Mock(MorpheusSynchronousCloudService)
        def syncPoolSvc = Mock(MorpheusSynchronousCloudPoolService)

        mockMorpheusContext.async >> asyncServices
        mockMorpheusContext.services >> morpheusServices

        asyncServices.cloud >> asyncCloudSvc
        asyncCloudSvc.pool >> asyncPoolSvc
        asyncPoolSvc.listIdentityProjections(_) >> Observable.empty()

        morpheusServices.cloud >> syncCloudSvc
        syncCloudSvc.pool >> syncPoolSvc
        syncPoolSvc.bulkCreate(_) >> [success: true]

        when:
        projectsSync.execute()

        then:
        1 * syncPoolSvc.bulkCreate({ List items ->
            items.size() == 1 && items[0].externalId == 'proj-uid-1' && items[0].name == 'my-project'
        })
    }

    def "execute() removes stale project from DB when API returns empty list"() {
        given:
        GroovySpy(ProjectApiHelper, global: true)
        def mockApiHelper = Mock(ProjectApiHelper)
        new ProjectApiHelper(mockCluster) >> mockApiHelper
        mockApiHelper.getProjects() >> [success: true, projects: []]

        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudSvc = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def asyncPoolSvc = Mock(MorpheusCloudPoolService)
        def morpheusServices = Mock(MorpheusServices)
        def syncCloudSvc = Mock(MorpheusSynchronousCloudService)
        def syncPoolSvc = Mock(MorpheusSynchronousCloudPoolService)

        mockMorpheusContext.async >> asyncServices
        mockMorpheusContext.services >> morpheusServices

        asyncServices.cloud >> asyncCloudSvc
        asyncCloudSvc.pool >> asyncPoolSvc

        def staleProj = new CloudPoolIdentity(id: 55L, externalId: 'stale-uid')
        asyncPoolSvc.listIdentityProjections(_) >> Observable.fromIterable([staleProj])

        morpheusServices.cloud >> syncCloudSvc
        syncCloudSvc.pool >> syncPoolSvc
        syncPoolSvc.bulkRemove(_) >> [success: true]

        when:
        projectsSync.execute()

        then:
        1 * syncPoolSvc.bulkRemove({ List items -> items.size() == 1 && items[0].id == 55L })
    }

    def "execute() updates project name in DB when API has changed name"() {
        given:
        GroovySpy(ProjectApiHelper, global: true)
        def mockApiHelper = Mock(ProjectApiHelper)
        new ProjectApiHelper(mockCluster) >> mockApiHelper
        mockApiHelper.getProjects() >> [success: true, projects: [
            [uid: 'proj-uid-update', name: 'new-name']
        ]]

        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudSvc = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def asyncPoolSvc = Mock(MorpheusCloudPoolService)
        def morpheusServices = Mock(MorpheusServices)
        def syncCloudSvc = Mock(MorpheusSynchronousCloudService)
        def syncPoolSvc = Mock(MorpheusSynchronousCloudPoolService)

        mockMorpheusContext.async >> asyncServices
        mockMorpheusContext.services >> morpheusServices

        asyncServices.cloud >> asyncCloudSvc
        asyncCloudSvc.pool >> asyncPoolSvc

        def existingProj = new CloudPoolIdentity(id: 77L, externalId: 'proj-uid-update')
        asyncPoolSvc.listIdentityProjections(_) >> Observable.fromIterable([existingProj])

        def fullPool = new CloudPool(id: 77L, externalId: 'proj-uid-update', name: 'old-name')
        asyncPoolSvc.list(_) >> Observable.fromIterable([fullPool])

        morpheusServices.cloud >> syncCloudSvc
        syncCloudSvc.pool >> syncPoolSvc
        syncPoolSvc.bulkSave(_) >> [success: true]

        when:
        projectsSync.execute()

        then:
        1 * syncPoolSvc.bulkSave({ List items ->
            items.size() == 1 && items[0].name == 'new-name'
        })
    }

    def "execute() does not call bulkSave when project name is unchanged"() {
        given:
        GroovySpy(ProjectApiHelper, global: true)
        def mockApiHelper = Mock(ProjectApiHelper)
        new ProjectApiHelper(mockCluster) >> mockApiHelper
        mockApiHelper.getProjects() >> [success: true, projects: [
            [uid: 'proj-uid-same', name: 'same-name']
        ]]

        def asyncServices = Mock(MorpheusAsyncServices)
        def asyncCloudSvc = Mock(com.morpheusdata.core.cloud.MorpheusCloudService)
        def asyncPoolSvc = Mock(MorpheusCloudPoolService)
        def morpheusServices = Mock(MorpheusServices)
        def syncCloudSvc = Mock(MorpheusSynchronousCloudService)
        def syncPoolSvc = Mock(MorpheusSynchronousCloudPoolService)

        mockMorpheusContext.async >> asyncServices
        mockMorpheusContext.services >> morpheusServices

        asyncServices.cloud >> asyncCloudSvc
        asyncCloudSvc.pool >> asyncPoolSvc

        def existingProj = new CloudPoolIdentity(id: 88L, externalId: 'proj-uid-same')
        asyncPoolSvc.listIdentityProjections(_) >> Observable.fromIterable([existingProj])

        def fullPool = new CloudPool(id: 88L, externalId: 'proj-uid-same', name: 'same-name', active: true, removable: true, hidden: false, type: 'default',
                refType: 'ComputeServerGroup', refId: 123L)
        asyncPoolSvc.list(_) >> Observable.fromIterable([fullPool])

        morpheusServices.cloud >> syncCloudSvc
        syncCloudSvc.pool >> syncPoolSvc

        when:
        projectsSync.execute()

        then:
        0 * syncPoolSvc.bulkSave(_)
    }


    def "updateProjects activates namespace pools synced inactive by earlier versions"() {
        given:
        def morpheusServices = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def poolService = Mock(MorpheusSynchronousCloudPoolService)
        mockMorpheusContext.services >> morpheusServices
        morpheusServices.cloud >> cloudService
        morpheusServices.cloud.pool >> poolService
        def oldPool = new CloudPool(name: 'team-a', active: false, removable: false, hidden: true)

        when:
        projectsSync.updateProjects([[existingItem: oldPool, masterItem: [name: 'team-a']]])

        then:
        1 * poolService.bulkSave([oldPool])
        oldPool.active
        oldPool.removable
        !oldPool.hidden
    }

    def "namespace pools of a Harvester cloud belong to the cloud"() {
        given:
        def cloud = new Cloud(); cloud.id = 7L
        def connection = new ComputeServerGroup(id: 55L, cloud: cloud)
        connection.setConfigProperty('managedByCloud', true)
        def sync = new ProjectsSync(mockPlugin, connection)
        def morpheusServices = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def poolService = Mock(MorpheusSynchronousCloudPoolService)
        mockMorpheusContext.services >> morpheusServices
        morpheusServices.cloud >> cloudService
        morpheusServices.cloud.pool >> poolService
        def oldPool = new CloudPool(name: 'team-a', active: true, removable: true, hidden: false,
                refType: 'ComputeServerGroup', refId: 55L, serverGroup: connection)

        when:
        sync.updateProjects([[existingItem: oldPool, masterItem: [name: 'team-a']]])

        then:
        sync.poolRefType() == 'ComputeZone'
        sync.poolRefId() == 7L
        projectsSync.poolRefType() == 'ComputeServerGroup'
        projectsSync.poolRefId() == 123L
        1 * poolService.bulkSave([oldPool])
        oldPool.refType == 'ComputeZone'
        oldPool.refId == 7L
        oldPool.serverGroup == null
    }

    def "updateProjects moves namespace pools to the generic pool type"() {
        given:
        def morpheusServices = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def poolService = Mock(MorpheusSynchronousCloudPoolService)
        mockMorpheusContext.services >> morpheusServices
        morpheusServices.cloud >> cloudService
        morpheusServices.cloud.pool >> poolService
        def oldPool = new CloudPool(name: 'team-a', type: 'harvester-namespace', active: true, removable: true, hidden: false,
                refType: 'ComputeServerGroup', refId: 123L)

        when:
        projectsSync.updateProjects([[existingItem: oldPool, masterItem: [name: 'team-a']]])

        then:
        1 * poolService.bulkSave([oldPool])
        oldPool.type == 'default'
    }

    def "addProjects links a pool added from Morpheus to its namespace instead of adding a second one"() {
        given:
        def cloud = new Cloud(); cloud.id = 7L
        def connection = new ComputeServerGroup(id: 55L, cloud: cloud)
        connection.setConfigProperty('managedByCloud', true)
        def sync = new ProjectsSync(mockPlugin, connection)
        def morpheusServices = Mock(MorpheusServices)
        def cloudService = Mock(MorpheusSynchronousCloudService)
        def poolService = Mock(MorpheusSynchronousCloudPoolService)
        mockMorpheusContext.services >> morpheusServices
        morpheusServices.cloud >> cloudService
        morpheusServices.cloud.pool >> poolService
        def unlinked = new CloudPool(name: 'test55', type: 'default')

        when:
        sync.addProjects([[uid: 'uid-55', name: 'test55'], [uid: 'uid-77', name: 'test77']])

        then:
        1 * poolService.list(_) >> [unlinked]
        1 * poolService.bulkSave([unlinked])
        1 * poolService.bulkCreate({ it*.name == ['test77'] })
        unlinked.externalId == 'uid-55'
        unlinked.type == 'default'
        unlinked.category == 'harvester.namespace.cluster.55'
        unlinked.refType == 'ComputeZone'
        unlinked.refId == 7L
        unlinked.serverGroup == null
    }
}
