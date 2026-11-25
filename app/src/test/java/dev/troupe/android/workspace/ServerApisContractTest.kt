package dev.troupe.android.workspace

import dev.troupe.android.core.net.ApiResult
import dev.troupe.android.files.FilesApi
import dev.troupe.android.git.GitApi
import dev.troupe.android.github.GithubApi
import dev.troupe.android.snapshots.AutomationsApi
import dev.troupe.android.testing.FakeTroupeServer
import dev.troupe.android.testing.TestGraph
import dev.troupe.android.worktree.WorktreeApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verifies exact wire compatibility between Android API clients and Troupe Server HTTP routes
 * (`fs_api.rs`, `git_api.rs`, `github_api.rs`, `worktree_api.rs`, `automation_api.rs`, `store`).
 */
class ServerApisContractTest {
    @get:Rule val temp = TemporaryFolder()

    private fun jsonOk(body: String): MockResponse = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    @Test
    fun projectBrowseAndFilesRenameMatchFsApi() = runBlocking {
        FakeTroupeServer().use { server ->
            val renameBodies = mutableListOf<JsonObject>()
            server.onHttpRoute = { req ->
                when {
                    req.url.encodedPath == "/fs/browse" -> {
                        assertEquals("/home/dev", req.url.queryParameter("path"))
                        jsonOk(
                            """{"success":true,"data":[{"name":"troupe","path":"/home/dev/troupe","type":"directory"}]}""",
                        )
                    }
                    req.url.encodedPath == "/fs/rename" -> {
                        renameBodies += Json.parseToJsonElement(req.body?.utf8().orEmpty()).jsonObject
                        jsonOk("""{"success":true,"data":null}""")
                    }
                    else -> null
                }
            }
            TestGraph(temp.newFolder()).use { graph ->
                val projectApi = ProjectApi(graph.api)
                val browse = projectApi.browseDirectory(server.address, server.token, "/home/dev") as ApiResult.Ok
                assertEquals("/home/dev", browse.value.currentPath)
                assertEquals("/home", browse.value.parentPath)
                assertEquals(listOf("troupe"), browse.value.entries.map { it.name })
                assertTrue(browse.value.entries.first().isDirectory)

                val filesApi = FilesApi(graph.api)
                val renamed = filesApi.rename(server.address, server.token, "/a.txt", "/b.txt")
                assertTrue(renamed is ApiResult.Ok)
                assertEquals("/a.txt", renameBodies.single()["from"]?.jsonPrimitive?.content)
                assertEquals("/b.txt", renameBodies.single()["to"]?.jsonPrimitive?.content)
            }
        }
    }

    @Test
    fun gitStatusAndBranchesMatchGitApi() = runBlocking {
        FakeTroupeServer().use { server ->
            server.onHttpRoute = { req ->
                when (req.url.encodedPath) {
                    "/git/status" -> jsonOk(
                        """{"success":true,"data":[{"path":"README.md","status":"modified","staged":true},{"path":"App.kt","status":"untracked","staged":false}]}""",
                    )
                    "/git/commit-context" -> jsonOk(
                        """{"success":true,"data":{"branch":"feat/v2","hasUpstream":true,"ahead":2,"behind":1,"stagedCount":1}}""",
                    )
                    "/git/branch-list" -> jsonOk(
                        """{"success":true,"data":["main","feat/v2","origin/main"]}""",
                    )
                    else -> null
                }
            }
            TestGraph(temp.newFolder()).use { graph ->
                val gitApi = GitApi(graph.api)
                val status = gitApi.status(server.address, server.token, "/repo") as ApiResult.Ok
                assertEquals("feat/v2", status.value.branch)
                assertEquals(2, status.value.ahead)
                assertEquals(1, status.value.behind)
                assertEquals(listOf("README.md"), status.value.stagedFiles().map { it.path })
                assertEquals(listOf("App.kt"), status.value.unstagedFiles().map { it.path })

                val branches = gitApi.branches(server.address, server.token, "/repo") as ApiResult.Ok
                assertEquals(3, branches.value.size)
                assertTrue(branches.value.first { it.name == "feat/v2" }.current)
                assertTrue(branches.value.first { it.name == "origin/main" }.isRemote)
            }
        }
    }

    @Test
    fun githubItemsAndDetailMatchGithubApi() = runBlocking {
        FakeTroupeServer().use { server ->
            val listBodies = mutableListOf<JsonObject>()
            server.onHttpRoute = { req ->
                when (req.url.encodedPath) {
                    "/github/items" -> {
                        listBodies += Json.parseToJsonElement(req.body?.utf8().orEmpty()).jsonObject
                        jsonOk(
                            """{"success":true,"data":{"repo":{"host":"github.com","owner":"naddrzz","name":"troupe"},"items":[{"kind":"pr","number":42,"title":"V2 Mobile","state":"open","author":"alice","labels":[],"assignees":[],"commentCount":1,"updatedAt":"2026-01-01T00:00:00Z","url":"https://github.com/naddrzz/troupe/pull/42","checks":"success"}],"fetchedAt":"2026-01-01T00:00:00Z"}}""",
                        )
                    }
                    "/github/item" -> jsonOk(
                        """{"success":true,"data":{"repo":{"host":"github.com","owner":"naddrzz","name":"troupe"},"item":{"kind":"pr","number":42,"title":"V2 Mobile","state":"open","author":"alice","labels":[],"assignees":[],"commentCount":1,"updatedAt":"2026-01-01T00:00:00Z","url":"https://github.com/naddrzz/troupe/pull/42","checks":"success"},"body":"Full PR body","createdAt":"2026-01-01T00:00:00Z","comments":[{"id":"c1","author":"bob","isBot":false,"body":"LGTM","createdAt":"2026-01-01T00:00:00Z","url":""}],"timeline":[],"stateReason":null,"pr":null,"fetchedAt":"2026-01-01T00:00:00Z"}}""",
                    )
                    else -> null
                }
            }
            TestGraph(temp.newFolder()).use { graph ->
                val githubApi = GithubApi(graph.api)
                val list = githubApi.listItems(server.address, server.token, "/repo", kind = "pr") as ApiResult.Ok
                assertEquals(1, list.value.size)
                assertEquals(42, list.value.first().number)
                val filterObj = listBodies.single()["filter"]?.jsonObject!!
                assertEquals("origin", filterObj["repo"]?.jsonPrimitive?.content)
                assertEquals("pr", filterObj["kind"]?.jsonPrimitive?.content)

                val detail = githubApi.getItem(server.address, server.token, "/repo", "origin", "pr", 42) as ApiResult.Ok
                assertEquals("V2 Mobile", detail.value.title)
                assertEquals("Full PR body", detail.value.body)
                assertEquals(1, detail.value.comments.size)
            }
        }
    }

    @Test
    fun worktreeAndAutomationsMatchServerRoutes() = runBlocking {
        FakeTroupeServer().use { server ->
            val captured = mutableListOf<Pair<String, String>>()
            server.onHttpRoute = { req ->
                val body = req.body?.utf8().orEmpty()
                captured += (req.url.encodedPath + (req.url.encodedQuery?.let { "?$it" } ?: "")) to body
                when (req.url.encodedPath) {
                    "/worktree/branches" -> jsonOk(
                        """{"success":true,"data":[{"name":"main","isRemote":false,"isCurrent":true,"upstream":"origin/main","hasOtherWorktree":false}]}""",
                    )
                    "/worktree/create" -> jsonOk(
                        """{"success":true,"data":{"name":"feat-wt","branch":"feat/wt","path":"/repo-wt","headCommit":"abc123"}}""",
                    )
                    "/api/automations" -> jsonOk(
                        """{"success":true,"data":[{"id":"auto-1","name":"Nightly Check","enabled":true}]}""",
                    )
                    "/api/automations/auto-1/run" -> jsonOk("""{"success":true,"data":null}""")
                    "/api/automations/auto-1/enabled" -> jsonOk("""{"success":true,"data":null}""")
                    else -> null
                }
            }
            TestGraph(temp.newFolder()).use { graph ->
                val wt = WorktreeApi(graph.api)
                val branches = wt.branches(server.address, server.token, "/repo") as ApiResult.Ok
                assertEquals(listOf("main"), branches.value)

                val created = wt.create(server.address, server.token, "/repo", "feat/wt", "main") as ApiResult.Ok
                assertEquals("/repo-wt", created.value.path)
                val createJson = Json.parseToJsonElement(captured.first { it.first == "/worktree/create" }.second).jsonObject
                assertEquals("feat-wt", createJson["name"]?.jsonPrimitive?.content)
                assertEquals("feat/wt", createJson["branch"]?.jsonPrimitive?.content)
                assertEquals("main", createJson["startRef"]?.jsonPrimitive?.content)

                val autos = AutomationsApi(graph.api)
                val listed = autos.list(server.address, server.token, "p1") as ApiResult.Ok
                assertEquals(listOf("Nightly Check"), listed.value.map { it.name })
                assertTrue(captured.any { it.first == "/api/automations?project_id=p1" })
                assertTrue(autos.trigger(server.address, server.token, "auto-1") is ApiResult.Ok)
                assertTrue(autos.setEnabled(server.address, server.token, "auto-1", false) is ApiResult.Ok)
            }
        }
    }

    @Test
    fun persistenceKeysPartitionSharedLwwCasAndDeviceScopes() {
        assertEquals(
            "shared/lww/settings/app",
            dev.troupe.android.core.store.PersistenceKeys.scopedKey(
                dev.troupe.android.core.store.PersistenceKeys.APP_SETTINGS,
                "dev-android-1",
            ),
        )
        assertEquals(
            "shared/cas/ssh/profiles",
            dev.troupe.android.core.store.PersistenceKeys.scopedKey(
                dev.troupe.android.core.store.PersistenceKeys.SSH_PROFILES,
                "dev-android-1",
            ),
        )
        assertEquals(
            "device/dev-android-1/active-project",
            dev.troupe.android.core.store.PersistenceKeys.scopedKey(
                dev.troupe.android.core.store.PersistenceKeys.ACTIVE_PROJECT,
                "dev-android-1",
            ),
        )
    }
}
