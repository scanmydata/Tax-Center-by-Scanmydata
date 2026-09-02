package gr.scanmydata.taxcenter.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import gr.scanmydata.taxcenter.BuildConfig
import gr.scanmydata.taxcenter.R
import gr.scanmydata.taxcenter.gdpr.Retention
import gr.scanmydata.taxcenter.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
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
    val context = LocalContext.current

    val appContext = LocalContext.current.applicationContext
    var update by remember { mutableStateOf<UpdateChecker.Release?>(null) }

    // Η ξενάγηση ξεκινά μία φορά, και από εκεί και πέρα ζει στο `TourState`:
    // διασχίζει οθόνες, οπότε δεν μπορεί να είναι κατάσταση μιας οθόνης.
    LaunchedEffect(Unit) {
        if (!container.settings.tourSeen) {
            container.settings.tourSeen = true
            TourState.start()
        }
    }

    // Τα λίγα δεδομένα που κρίνουν αν ένα βήμα της ξενάγησης έγινε στ' αλήθεια.
    //
    // Συλλέγονται **πάντα**, όχι μόνο όσο τρέχει η ξενάγηση: ένα `collectAsState`
    // μέσα σε `if` αλλάζει το πλήθος των composable κλήσεων μεταξύ συνθέσεων και
    // ρίχνει το Compose τη στιγμή που θα άλλαζε η συνθήκη. Το κόστος είναι δύο
    // ερωτήματα με `LIMIT 1` — ασήμαντο μπροστά στο να σκάει η εφαρμογή.
    val clientCount by container.repository.observeClients()
        .map { it.size }
        .collectAsState(initial = 0)
    val documentCount by container.db.documents().observeRecent(1)
        .map { it.size }
        .collectAsState(initial = 0)

    // Η πολιτική διατήρησης τρέχει μία φορά ανά εκκίνηση, μετά το ξεκλείδωμα.
    // Όχι στο Application.onCreate: εκεί θα άνοιγε τη βάση SQLCipher πριν καν
    // ταυτοποιηθεί ο χρήστης, και θα καθυστερούσε την εκκίνηση.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { Retention.apply(appContext, container.db, container.settings) }
        }
    }

    /**
     * Έλεγχος ενημέρωσης σε κάθε άνοιγμα.
     *
     * Ένα ανώνυμο GET στο δημόσιο API του GitHub — δεν στέλνεται τίποτα από τη
     * συσκευή ή τους πελάτες. Η αποτυχία αγνοείται σιωπηλά: η εφαρμογή
     * σχεδιάστηκε να δουλεύει και χωρίς δίκτυο, και ένα μήνυμα «δεν βρέθηκε
     * ενημέρωση» σε κάθε εκκίνηση θα ήταν θόρυβος.
     *
     * Η **εγκατάσταση** δεν γίνεται ποτέ μόνη της: εμφανίζεται ειδοποίηση και
     * αποφασίζει ο χρήστης.
     */
    LaunchedEffect(Unit) {
        val release = withContext(Dispatchers.IO) {
            runCatching { UpdateChecker.latest() }.getOrNull()
        } ?: return@LaunchedEffect
        if (UpdateChecker.isNewer(release.version)) update = release
    }

    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val base = route?.substringBefore('?')

    // Η «Λήψη εντύπων» έχει δύο διαδρομές — με και χωρίς προεπιλεγμένο πελάτη —
    // και πρέπει να φωτίζεται στο μενού και στις δύο.
    val current = Destination.entries.firstOrNull { it.route == base }
        ?: if (base?.startsWith(Destination.Fetch.route + "/") == true) {
            Destination.Fetch
        } else {
            Destination.NewClient
        }

    // Το όνομα του ανοιχτού πελάτη, για την κεφαλίδα. Σε οθόνη με τρεις
    // καρτέλες, το ποιανού είναι πρέπει να φαίνεται χωρίς να γυρίσεις πίσω.
    val openClientId = backStack?.arguments?.getLong("id") ?: 0L
    var openClientName by remember { mutableStateOf("") }
    LaunchedEffect(openClientId) {
        openClientName = if (openClientId == 0L) {
            ""
        } else {
            withContext(Dispatchers.IO) {
                container.db.clients().byId(openClientId)?.displayName.orEmpty()
            }
        }
    }

    val title = when {
        base?.startsWith("$CLIENT_ROUTE/") != true -> current.label
        openClientName.isNotBlank() -> "Καρτέλα · " + openClientName
        else -> "Καρτέλα πελάτη"
    }

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
            Box(Modifier.fillMaxSize().padding(inner)) {
            NavHost(
                navController = navController,
                startDestination = Destination.Clients.route,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Destination.Clients.route) {
                    ClientsScreen(
                        container = container,
                        onOpenClient = { id -> navController.navigate("$CLIENT_ROUTE/$id") },
                        onOpenDocuments = { id ->
                            navController.navigate("$CLIENT_ROUTE/$id/documents")
                        },
                    )
                }
                composable(
                    route = "$CLIENT_ROUTE/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                ) { entry ->
                    ClientCardScreen(
                        container = container,
                        clientId = entry.arguments?.getLong("id") ?: 0L,
                        onDone = { navController.popBackStack() },
                        onFetchFor = { id -> navController.navigate("fetch/$id") },
                    )
                }
                // Ίδια οθόνη, ανοιγμένη στην καρτέλα «Έγγραφα». Χωριστή
                // διαδρομή αντί για optional argument, για τον ίδιο λόγο με τη
                // λήψη: ο matcher δεν πρέπει να έχει περιθώριο επιλογής.
                composable(
                    route = "$CLIENT_ROUTE/{id}/documents",
                    arguments = listOf(navArgument("id") { type = NavType.LongType }),
                ) { entry ->
                    ClientCardScreen(
                        container = container,
                        clientId = entry.arguments?.getLong("id") ?: 0L,
                        onDone = { navController.popBackStack() },
                        onFetchFor = { id -> navController.navigate("fetch/$id") },
                        initialTab = 1,
                    )
                }
                composable(Destination.NewClient.route) {
                    NewClientScreen(
                        container = container,
                        onDone = { navController.navigate(Destination.Clients.route) },
                    )
                }
                composable(Destination.Fetch.route) { FetchScreen(container) }
                // Ξεχωριστή διαδρομή αντί για optional argument. Το
                // «fetch?client={client}» έκανε τον matcher να κρατά την
                // παραμετρική μορφή ακόμη και για το σκέτο «fetch», και η
                // επιστροφή στους Πελάτες άφηνε την οθόνη λήψης στη θέση της.
                composable(
                    route = "${Destination.Fetch.route}/{client}",
                    arguments = listOf(navArgument("client") { type = NavType.LongType }),
                ) { entry ->
                    FetchScreen(
                        container = container,
                        preselectedClient = entry.arguments?.getLong("client") ?: 0L,
                    )
                }
                composable(Destination.Documents.route) { DocumentsScreen(container) }
                composable(Destination.Calendar.route) { SendCalendarScreen(container) }
                composable(Destination.Help.route) { HelpScreen(container) }
                composable(Destination.SettingsScreen.route) {
                    SettingsScreen(
                        container = container,
                        onOpenLogs = { navController.navigate(LOGS_ROUTE) },
                    )
                }
                // Το αρχείο ενεργειών δεν είναι θέση του μενού: ανοίγει από τις
                // Ρυθμίσεις. Δεν είναι καθημερινή δουλειά — είναι κάτι που
                // ανοίγεις όταν σου το ζητήσουν.
                composable(LOGS_ROUTE) { LogsScreen(container) }
            }

            TourBar(
                facts = TourFacts(
                    googleConnected = container.settings.googleConnected,
                    clients = clientCount,
                    documents = documentCount,
                    currentRoute = route,
                ),
                onNavigate = { destination ->
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
            }
        }
    }

    update?.let { release ->
        AlertDialog(
            onDismissRequest = { update = null },
            title = { Text("Διαθέσιμη ενημέρωση ${release.tag}") },
            text = {
                Column {
                    Text(
                        "Τρέχεις την ${BuildConfig.VERSION_NAME}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        release.notes.lines().take(8).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    update = null
                    scope.launch {
                        val apk = withContext(Dispatchers.IO) {
                            runCatching { UpdateChecker.download(context, release) }.getOrNull()
                        }
                        if (apk != null) UpdateChecker.install(context, apk)
                    }
                }) { Text("Λήψη και εγκατάσταση") }
            },
            dismissButton = {
                TextButton(onClick = { update = null }) { Text("Αργότερα") }
            },
        )
    }
}

/** Η καρτέλα πελάτη δεν είναι θέση του μενού — ανοίγει από τη λίστα. */
private const val CLIENT_ROUTE = "client"

/** Ούτε το αρχείο ενεργειών — ανοίγει από τις Ρυθμίσεις. */
private const val LOGS_ROUTE = "logs"
