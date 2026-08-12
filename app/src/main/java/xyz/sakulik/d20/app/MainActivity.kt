package xyz.sakulik.d20.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import xyz.sakulik.d20.app.data.local.AppDatabase
import xyz.sakulik.d20.app.data.repository.ContextAssembler
import xyz.sakulik.d20.app.data.repository.InventoryRepository
import xyz.sakulik.d20.app.data.repository.LlmRepositoryImpl
import xyz.sakulik.d20.app.data.security.EncryptedLlmKeyManager
import xyz.sakulik.d20.app.ui.archive.ArchiveScreen
import xyz.sakulik.d20.app.ui.archive.ArchiveViewModel
import xyz.sakulik.d20.app.ui.common.SensoryController
import xyz.sakulik.d20.app.ui.creation.CreationScreen
import xyz.sakulik.d20.app.ui.creation.CreationViewModel
import xyz.sakulik.d20.app.ui.main.MainChatScreen
import xyz.sakulik.d20.app.ui.main.MainViewModel
import xyz.sakulik.d20.app.ui.settings.SettingsScreen
import xyz.sakulik.d20.app.ui.settings.SettingsViewModel
import xyz.sakulik.d20.app.ui.setup.SetupScreen
import xyz.sakulik.d20.app.ui.setup.SetupViewModel
import xyz.sakulik.d20.app.ui.setup.WorldBuilderScreen
import xyz.sakulik.d20.app.ui.setup.WorldBuilderViewModel
import xyz.sakulik.d20.app.ui.theme.D20AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 启用全屏沉浸式布局，支持 WindowInsets 适配
        enableEdgeToEdge()

        // 1. 基础基础架构初始化
        val sensoryController = SensoryController.getInstance(applicationContext)
        val keyManager = EncryptedLlmKeyManager(this)
        val database = AppDatabase.getDatabase(this)

        // 2. Repository 与 Dao 初始化
        val campaignDao = database.campaignDao()
        val characterDao = database.characterDao()
        val messageDao = database.messageDao()
        val loreEntryDao = database.loreEntryDao()
        val itemDao = database.itemDao()
        val combatantDao = database.combatantDao()
        val combatSessionDao = database.combatSessionDao()
        val gameStateDao = database.gameStateDao()
        val inventoryRepository = InventoryRepository(itemDao)
        val llmRepository = LlmRepositoryImpl(keyManager)
        val contextAssembler =
            ContextAssembler(
                applicationContext,
                campaignDao,
                characterDao,
                messageDao,
                loreEntryDao,
                combatantDao,
                combatSessionDao,
                keyManager
            )
        val validator = xyz.sakulik.d20.app.domain.common.validator.CampaignIntegrityValidator(
            applicationContext, campaignDao, characterDao, messageDao, itemDao
        )

        setContent {
            D20AppTheme {
                // 性能与视觉优化：为 NavHost 提供全屏背景 Surface，防止 Transition 期间出现黑屏闪烁
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    val navController = rememberNavController()

                    NavHost(navController = navController, startDestination = "archive") {
                        // --- 存档列表 ---
                        composable("archive") {
                            val vm: ArchiveViewModel = viewModel(factory = viewModelFactory {
                                ArchiveViewModel(campaignDao, characterDao, validator)
                            })
                            ArchiveScreen(
                                viewModel = vm,
                                onNavigateToSetup = { navController.navigate("setup") },
                                onOpenCampaign = { campaign, isReady ->
                                    if (isReady) {
                                        navController.navigate("chat/${campaign.id}")
                                    } else {
                                        navController.navigate(
                                            "worldbuilder/${campaign.id}/${campaign.systemId}"
                                        )
                                    }
                                },
                                onNavigateToSettings = { navController.navigate("settings") }
                            )
                        }

                        // --- 其余路由保持不变 ---
                        // ...

                        // --- 设置页面 ---
                        composable("settings") {
                            val vm: SettingsViewModel = viewModel(factory = viewModelFactory {
                                SettingsViewModel(keyManager)
                            })
                            SettingsScreen(
                                viewModel = vm,
                                onBack = { navController.popBackStack() },
                                onNavigateToUpdater = { navController.navigate("updater") }
                            )
                        }

                        // --- 规则包更新中心 ---
                        composable("updater") {
                            val vm: xyz.sakulik.d20.app.ui.settings.PluginManagerViewModel =
                                viewModel(factory = viewModelFactory {
                                    xyz.sakulik.d20.app.ui.settings.PluginManagerViewModel(
                                        applicationContext as android.app.Application,
                                        xyz.sakulik.d20.app.domain.common.updater.PluginType.RULESET
                                    )
                                })
                            xyz.sakulik.d20.app.ui.settings.RulesetManagerScreen(
                                viewModel = vm,
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // --- 世界观设定集市 ---
                        composable("worldview_market") {
                            val vm: xyz.sakulik.d20.app.ui.settings.PluginManagerViewModel =
                                viewModel(factory = viewModelFactory {
                                    xyz.sakulik.d20.app.ui.settings.PluginManagerViewModel(
                                        applicationContext as android.app.Application,
                                        xyz.sakulik.d20.app.domain.common.updater.PluginType.WORLDVIEW
                                    )
                                })
                            xyz.sakulik.d20.app.ui.settings.WorldviewManagerScreen(
                                viewModel = vm,
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // --- 初始化设置 (API Key & 规则选择) ---
                        composable("setup") {
                            val vm: SetupViewModel = viewModel(factory = viewModelFactory {
                                SetupViewModel(this@MainActivity, keyManager, campaignDao)
                            })
                            SetupScreen(
                                viewModel = vm,
                                onNavigateToWorldBuilder = { cid, rid ->
                                    navController.navigate("worldbuilder/$cid/$rid")
                                },
                                onNavigateToSettings = {
                                    navController.navigate("settings")
                                }
                            )
                        }

                        // --- 世界观构建 ---
                        composable("worldbuilder/{campaignId}/{rulesetId}") { backStack ->
                            val cid = backStack.arguments?.getString("campaignId") ?: ""
                            val rid = backStack.arguments?.getString("rulesetId") ?: "coc_7e"
                            val vm: WorldBuilderViewModel = viewModel(factory = viewModelFactory {
                                WorldBuilderViewModel(
                                    applicationContext,
                                    campaignDao,
                                    keyManager,
                                    llmRepository
                                )
                            })
                            LaunchedEffect(cid, rid) {
                                vm.init(cid, rid)
                            }
                            WorldBuilderScreen(
                                viewModel = vm,
                                onNavigateToCreation = { campaignId, rulesetId ->
                                    navController.navigate("creation/$campaignId/$rulesetId")
                                },
                                onNavigateToMarket = {
                                    navController.navigate("worldview_market")
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }

                        // --- 角色创建 ---
                        composable("creation/{campaignId}/{rulesetId}") { backStack ->
                            val cid = backStack.arguments?.getString("campaignId") ?: ""
                            val rid = backStack.arguments?.getString("rulesetId") ?: "coc_7e"
                            val vm: CreationViewModel = viewModel(factory = viewModelFactory {
                                CreationViewModel(
                                    context = applicationContext,
                                    characterDao = characterDao,
                                    messageDao = messageDao,
                                    repository = llmRepository,
                                    inventoryRepository = inventoryRepository,
                                    keyManager = keyManager
                                )
                            })
                            LaunchedEffect(cid, rid) {
                                vm.init(cid, rid)
                            }
                            CreationScreen(
                                viewModel = vm,
                                onSuccess = {
                                    navController.navigate("chat/$cid") {
                                        popUpTo("archive") { inclusive = false }
                                    }
                                }
                            )
                        }

                        // --- 主聊天界面 ---
                        composable(
                            route = "chat/{campaignId}",
                            enterTransition = {
                                androidx.compose.animation.fadeIn(
                                    animationSpec = androidx.compose.animation.core.tween(
                                        400
                                    )
                                )
                            },
                            exitTransition = {
                                androidx.compose.animation.fadeOut(
                                    animationSpec = androidx.compose.animation.core.tween(
                                        400
                                    )
                                )
                            }
                        ) { backStack ->
                            val cid = backStack.arguments?.getString("campaignId") ?: ""
                            val vm: MainViewModel = viewModel(factory = viewModelFactory {
                                MainViewModel(
                                    context = applicationContext,
                                    campaignId = cid,
                                    repository = llmRepository,
                                    contextAssembler = contextAssembler,
                                    characterDao = characterDao,
                                    messageDao = messageDao,
                                    inventoryRepository = inventoryRepository,
                                    keyManager = keyManager,
                                    loreEntryDao = loreEntryDao,
                                    combatantDao = combatantDao,
                                    gameStateDao = gameStateDao,
                                    sensoryController = sensoryController
                                )
                            })
                            MainChatScreen(viewModel = vm)
                        }
                    }
                }
            }
        }
    }

    /**
     * 助手函数：简化手动 ViewModel 工厂创建
     * 使用 Inline reified 来辅助类型推断
     */
    inline fun <reified VM : ViewModel> viewModelFactory(crossinline initializer: () -> VM): ViewModelProvider.Factory {
        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = initializer() as T
        }
    }
}
