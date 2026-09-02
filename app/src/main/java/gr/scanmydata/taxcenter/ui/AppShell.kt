package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import gr.scanmydata.taxcenter.BuildConfig
import gr.scanmydata.taxcenter.R
import gr.scanmydata.taxcenter.data.db.RunLogEntity
import gr.scanmydata.taxcenter.gdpr.Retention
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Το κέλυφος: συρτάρι μενού και NavHost.
 *
 * Το λογότυπο στην κεφαλίδα διαλέγεται από το σύστημα ανάλογα με το θέμα —
 * `drawable/logo.png` για ανοιχτό, `drawable-night/logo.png` για σκοτεινό. Οι
 * δύο παραλλαγές δεν είναι αντιστροφή χρωμάτων: η σκοτεινή έχει λάμψη γύρω από
 * τον φάκελο.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(container: AppContainer) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val appContext = LocalContext.current.applicationContext

    // Η πολιτική διατήρησης τρέχει μία φορά ανά εκκίνηση, μετά το ξεκλείδωμα.
    // Όχι στο Application.onCreate: εκεί θα άνοιγε τη βάση SQLCipher πριν καν
    // ταυτοποιηθεί ο χρήστης, και θα καθυστερούσε την εκκίνηση.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { Retention.apply(appContext, container.db, container.settings) }
        }
    }

    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val current = Destination.entries.firstOrNull { it.route == route } ?: Destination.Clients
    val title = if (route?.startsWith(CLIENT_ROUTE) == true) "Καρτέλα πελάτη" else current.label

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(16.dp)) {
                    Image(
                        painter = painterResource(R.drawable.logo),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("ScanMyData Tax Center", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "έκδοση ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Destination.entries.forEach { destination ->
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                painter = painterResource(destination.icon),
                                contentDescription = null,
                            )
                        },
                        label = { Text(destination.label) },
                        selected = destination == current,
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Μενού")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            },
        ) { inner ->
            NavHost(
                navController = navController,
                startDestination = Destination.Clients.route,
                modifier = Modifier.fillMaxSize().padding(inner),
            ) {
                composable(Destination.Clients.route) {
                    ClientsScreen(
                        container = container,
                        onOpenClient = { id -> navController.navigate("$CLIENT_ROUTE/$id") },
                    )
                }
                composable(
                    route = "$CLIENT_ROUTE/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                ) { entry ->
                    ClientEditScreen(
                        container = container,
                        clientId = entry.arguments?.getLong("id") ?: 0L,
                        onDone = { navController.popBackStack() },
                    )
                }
                composable(Destination.Import.route) { ImportScreen(container) }
                composable(Destination.Fetch.route) { FetchScreen(container) }
                composable(Destination.Documents.route) { DocumentsScreen(container) }
                composable(Destination.Calendar.route) { SendCalendarScreen(container) }
                composable(Destination.Logs.route) { RunLogsScreen(container) }
                composable(Destination.SettingsScreen.route) { SettingsScreen(container) }
            }
        }
    }
}

/**
 * Ιστορικό εκτελέσεων.
 *
 * Ο runner θα έγραφε `run.log` δίπλα στα PDF· εδώ οι γραμμές είναι στη βάση,
 * καθαρισμένες από τον `Redactor`, και ο φάκελος του πελάτη μένει καθαρός.
 */
@Composable
fun RunLogsScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val logs: List<RunLogEntity> by container.db.runLogs().observeRecent()
        .collectAsState(initial = emptyList())

    LazyColumn(modifier.padding(16.dp)) {
        items(logs, key = { it.id }) { log ->
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        "${log.configId} — ${log.afm}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        buildString {
                            append(if (log.ok) "✓ " else "✗ ")
                            append(if (log.ok) "${log.fileCount} αρχεία" else log.reason)
                            append(" · ${log.durationMs / 1000}s")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (log.ok) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.error,
                    )
                    if (log.lines.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            log.lines.lines().takeLast(6).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

/** Η καρτέλα πελάτη δεν είναι θέση του μενού — ανοίγει από τη λίστα. */
private const val CLIENT_ROUTE = "client"
