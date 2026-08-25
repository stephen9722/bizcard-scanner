package tw.pentamaster.bizcard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import tw.pentamaster.bizcard.ui.BackupScreen
import tw.pentamaster.bizcard.ui.CameraCaptureScreen
import tw.pentamaster.bizcard.ui.CardDetailScreen
import tw.pentamaster.bizcard.ui.CardEditScreen
import tw.pentamaster.bizcard.ui.CardListScreen
import tw.pentamaster.bizcard.ui.CardViewModel
import tw.pentamaster.bizcard.ui.BizCardTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BizCardTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val nav = rememberNavController()
                    val vm: CardViewModel = viewModel()

                    NavHost(navController = nav, startDestination = "list") {

                        composable("list") {
                            CardListScreen(
                                vm = vm,
                                onOpen = { id -> nav.navigate("detail/$id") },
                                onScan = {
                                    vm.startNewCard()
                                    nav.navigate("capture")
                                },
                                onAddManually = {
                                    vm.startNewCard()
                                    nav.navigate("edit/0")
                                },
                                onBackup = { nav.navigate("backup") }
                            )
                        }

                        composable("backup") {
                            BackupScreen(vm = vm, onBack = { nav.popBackStack() })
                        }

                        composable("capture") {
                            CameraCaptureScreen(
                                vm = vm,
                                onDone = {
                                    nav.navigate("edit/0") {
                                        popUpTo("list")
                                    }
                                },
                                onCancel = { nav.popBackStack() },
                                onManualEntry = {
                                    nav.navigate("edit/0") {
                                        popUpTo("list")
                                    }
                                }
                            )
                        }

                        composable(
                            "detail/{id}",
                            arguments = listOf(navArgument("id") { type = NavType.LongType })
                        ) { entry ->
                            val id = entry.arguments?.getLong("id") ?: 0L
                            CardDetailScreen(
                                vm = vm,
                                cardId = id,
                                onEdit = { nav.navigate("edit/$id") },
                                onBack = { nav.popBackStack() },
                                onDeleted = { nav.popBackStack("list", inclusive = false) }
                            )
                        }

                        composable(
                            "edit/{id}",
                            arguments = listOf(navArgument("id") { type = NavType.LongType })
                        ) { entry ->
                            val id = entry.arguments?.getLong("id") ?: 0L
                            CardEditScreen(
                                vm = vm,
                                cardId = id,
                                onSaved = { newId ->
                                    nav.navigate("detail/$newId") {
                                        popUpTo("list")
                                    }
                                },
                                onBack = { nav.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
