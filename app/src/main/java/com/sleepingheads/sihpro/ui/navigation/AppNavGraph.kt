package com.sleepingheads.sihpro.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.platform.LocalContext
import com.sleepingheads.sihpro.session.UserSessionManager
import com.sleepingheads.sihpro.ui.auth.LoginScreen
import com.sleepingheads.sihpro.ui.diagnostics.DiagnosticsScreen
import com.sleepingheads.sihpro.ui.home.HomeScreen
import com.sleepingheads.sihpro.ui.results.ResultsScreen

@Composable
fun AppNavGraph(
    navController: NavHostController = rememberNavController(),
    sharedNavViewModel: NavigationViewModel = viewModel()
) {
    val context = LocalContext.current

    NavHost(
        navController = navController,
        startDestination = Routes.LOGIN
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                viewModel = sharedNavViewModel,
                onStartDemo = {
                    navController.navigate(Routes.NAVIGATION)
                },
                onViewResults = {
                    navController.navigate(Routes.RESULTS)
                },
                onViewDiagnostics = {
                    navController.navigate(Routes.DIAGNOSTICS)
                },
                onLogout = {
                    UserSessionManager.logout(context)
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.NAVIGATION) {
            NavigationScreen(
                viewModel = sharedNavViewModel,
                onNavigateToResults = {
                    navController.navigate(Routes.RESULTS)
                },
                onNavigateToDiagnostics = {
                    navController.navigate(Routes.DIAGNOSTICS)
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Routes.RESULTS) {
            ResultsScreen(
                viewModel = sharedNavViewModel,
                onReplay = {
                    sharedNavViewModel.restart()
                    navController.navigate(Routes.NAVIGATION) {
                        popUpTo(Routes.NAVIGATION) { inclusive = true }
                    }
                },
                onNavigateHome = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
                onNavigateToDiagnostics = {
                    navController.navigate(Routes.DIAGNOSTICS)
                }
            )
        }

        composable(Routes.DIAGNOSTICS) {
            DiagnosticsScreen(
                viewModel = sharedNavViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
