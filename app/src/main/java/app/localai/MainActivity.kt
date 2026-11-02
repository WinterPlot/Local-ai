package app.localai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.localai.ui.AppViewModel
import app.localai.ui.ChatScreen
import app.localai.ui.ChatViewModel
import app.localai.ui.HomeScreen
import app.localai.ui.LocalAITheme
import app.localai.ui.ModelsScreen
import app.localai.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LocalAIApp).container
        setContent { LocalAITheme { Root(container) } }
    }
}

private fun <T : ViewModel> factory(create: () -> T) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
}

@Composable
private fun Root(container: AppContainer) {
    val nav = rememberNavController()
    val appVm: AppViewModel = viewModel(factory = factory { AppViewModel(container) })

    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                vm = appVm,
                onOpen = { nav.navigate("chat/$it") },
                onModels = { nav.navigate("models") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("chat/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val vm: ChatViewModel = viewModel(key = "chat-$id", factory = factory { ChatViewModel(container, id) })
            ChatScreen(vm = vm, onBack = { nav.popBackStack() }, onModels = { nav.navigate("models") })
        }
        composable("models") { ModelsScreen(vm = appVm, onBack = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(vm = appVm, onBack = { nav.popBackStack() }) }
    }
}
