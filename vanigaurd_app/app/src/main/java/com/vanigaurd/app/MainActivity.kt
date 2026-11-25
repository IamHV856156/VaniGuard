package com.vanigaurd.app

import android.content.Intent
import android.telecom.TelecomManager
import android.widget.Toast
import com.vanigaurd.app.data.CallerReputationDatabase
import com.vanigaurd.app.data.CallerReputationRepository
import com.vanigaurd.app.data.CallerProfileEntity
import com.vanigaurd.app.domain.CallerReputationResult
import com.vanigaurd.app.domain.ReputationEvidence
import android.app.role.RoleManager
import androidx.activity.compose.BackHandler
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import kotlinx.coroutines.launch
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.media.AudioRecord
import android.media.AudioFormat
import android.media.MediaRecorder
import android.media.AudioRecord.READ_BLOCKING
import kotlin.concurrent.thread
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.HorizontalDivider
import kotlinx.coroutines.launch
import com.vanigaurd.app.ui.theme.VaniGaurdTheme

// ----------------------------------------------------
// VANIGUARD DESIGN SYSTEM
// ----------------------------------------------------

private val Cream = Color(0xFFF4F7F5)
private val LightCard = Color(0xFFFFFFFF)

private val DarkBackground = Color(0xFF080D12)
private val DarkCard = Color(0xFF111A20)

private val Emerald = Color(0xFF20C997)

private val LightText = Color(0xFF14221D)
private val DarkText = Color(0xFFEAF2EF)

private val MutedLight = Color(0xFF53645D)
private val MutedDark = Color(0xFFA5B5B1)

enum class AppTab {
    RECENT, DIALER, SHIELD, PROFILE
}

enum class AppTheme {
    LIGHT, DARK, SYSTEM
}

data class CallItem(
    val name: String,
    val number: String,
    val time: String,
    val type: String,
    val risk: String = "Unknown"
)
data class CallerReputationUiState(
    val assessment: CallerReputationResult? = null,
    val profile: CallerProfileEntity? = null,
    val evidence: List<ReputationEvidence> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null
)


class MainActivity : ComponentActivity() {

    private var dialNumberFromIntent by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleDialIntent(intent)

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        }

        setContent {
            VaniGuardApp(
                initialDialNumber = dialNumberFromIntent,
                onDialNumberConsumed = {
                    dialNumberFromIntent = null
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDialIntent(intent)
    }

    private fun handleDialIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_DIAL) {
            dialNumberFromIntent =
                intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() }
        }
    }
}


// ----------------------------------------------------
// APP ROOT
// ----------------------------------------------------

@Composable
fun VaniGuardApp(
    initialDialNumber: String? = null,
    onDialNumberConsumed: () -> Unit = {}
) {
    var selectedTab by remember {
        mutableStateOf(
            if (initialDialNumber != null) AppTab.DIALER
            else AppTab.RECENT
        )
    }

    LaunchedEffect(initialDialNumber) {
        if (!initialDialNumber.isNullOrBlank()) {
            selectedTab = AppTab.DIALER
        }
    }
    var selectedTheme by remember { mutableStateOf(AppTheme.SYSTEM) }
    var selectedCall by remember { mutableStateOf<CallItem?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var calls by remember {
        mutableStateOf<List<CallItem>>(emptyList())
    }

    var callsLoading by remember { mutableStateOf(false) }
    var callsError by remember { mutableStateOf<String?>(null) }

    val refreshCalls: () -> Unit = {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            scope.launch {
                callsLoading = true
                callsError = null

                try {
                    val hasContacts = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_CONTACTS
                    ) == PackageManager.PERMISSION_GRANTED

                    calls = loadRecentCalls(context, hasContacts)
                } catch (e: Exception) {
                    callsError = e.message ?: "Unable to load call history"
                } finally {
                    callsLoading = false
                }
            }
        }
    }

// Load call history once when the app opens.
    LaunchedEffect(Unit) {
        refreshCalls()
    }

// Handle Android's physical/system Back button.
    BackHandler(enabled = selectedCall != null) {
        selectedCall = null
    }

    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val isDark = when (selectedTheme) {
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
        AppTheme.SYSTEM -> systemDark
    }

    val background = if (isDark) DarkBackground else Cream
    val card = if (isDark) DarkCard else LightCard
    val text = if (isDark) DarkText else LightText
    val muted = if (isDark) MutedDark else MutedLight

    VaniGaurdTheme(
        darkTheme = isDark
    ) {
        Scaffold(
            containerColor = background,
            bottomBar = {
                FloatingDock(
                    selectedTab = selectedTab,
                    onTabSelected = {
                        selectedTab = it
                        selectedCall = null
                    },
                    isDark = isDark,
                    card = card,
                    text = text,
                    muted = muted
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (selectedTab) {
                    AppTab.RECENT -> {
                        val call = selectedCall

                        if (call != null) {
                            CallerDetailsScreen(
                                call = call,
                                card = card,
                                text = text,
                                muted = muted,
                                onBack = { selectedCall = null }
                            )
                        } else {
                            RecentCallsScreen(
                                isDark = isDark,
                                card = card,
                                text = text,
                                muted = muted,
                                calls = calls,
                                loading = callsLoading,
                                loadError = callsError,
                                onRefresh = refreshCalls,
                                onCallClick = { selectedCall = it }
                            )
                        }
                    }

                    AppTab.DIALER -> DialerScreen(
                        isDark = isDark,
                        card = card,
                        text = text,
                        muted = muted,
                        initialNumber = initialDialNumber,
                        onInitialNumberConsumed = onDialNumberConsumed
                    )

                    AppTab.SHIELD -> LiveShieldScreen(
                        isDark = isDark,
                        card = card,
                        text = text,
                        muted = muted
                    )

                    AppTab.PROFILE -> ProfileScreen(
                        isDark = isDark,
                        card = card,
                        text = text,
                        muted = muted,
                        selectedTheme = selectedTheme,
                        onThemeSelected = { selectedTheme = it }
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------

// ----------------------------------------------------
// RECENT CALLS HOME

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentCallsScreen(
    isDark: Boolean,
    card: Color,
    text: Color,
    muted: Color,
    calls: List<CallItem>,
    loading: Boolean,
    loadError: String?,
    onRefresh: () -> Unit,
    onCallClick: (CallItem) -> Unit
) {
    val context = LocalContext.current

    var search by remember { mutableStateOf("") }

    var hasCallPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALL_LOG
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var hasContactsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasCallPermission =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALL_LOG
            ) == PackageManager.PERMISSION_GRANTED

        hasContactsPermission =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED

        if (hasCallPermission) onRefresh()
    }

    val filteredCalls = calls.filter {
        it.name.contains(search, ignoreCase = true) ||
                it.number.contains(search, ignoreCase = true)
    }

    val pageBackground = if (isDark) {
        Color(0xFF080D12)
    } else {
        Color(0xFFF4F7F5)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(pageBackground)
    ) {
        when {
            !hasCallPermission -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = 20.dp,
                        vertical = 18.dp
                    )
                ) {
                    item {
                        CompactVaniGuardHeader(text = text)
                        Spacer(Modifier.height(25.dp))
                        Text(
                            "Recent Calls",
                            fontSize = 23.sp,
                            fontWeight = FontWeight.Bold,
                            color = text
                        )
                        Spacer(Modifier.height(18.dp))

                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = card
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(25.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.Call,
                                    contentDescription = null,
                                    tint = Emerald,
                                    modifier = Modifier.size(36.dp)
                                )

                                Spacer(Modifier.height(14.dp))

                                Text(
                                    "Connect your call history",
                                    color = text,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(Modifier.height(7.dp))

                                Text(
                                    "Allow access to your recent calls so VaniGuard can display your call activity.",
                                    color = muted,
                                    fontSize = 12.sp
                                )

                                Spacer(Modifier.height(18.dp))

                                Button(
                                    onClick = {
                                        permissionLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.READ_CALL_LOG,
                                                Manifest.permission.READ_CONTACTS
                                            )
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Emerald,
                                        contentColor = Color(0xFF06251D)
                                    )
                                ) {
                                    Text("Grant permission")
                                }
                            }
                        }
                    }
                }
            }

            else -> {
                PullToRefreshBox(
                    isRefreshing = loading,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = 20.dp,
                            vertical = 16.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(11.dp)
                    ) {
                        // COMPACT HEADER
                        item {
                            CompactVaniGuardHeader(text = text)
                        }

                        // TITLE
                        item {
                            Column {
                                Spacer(Modifier.height(13.dp))

                                Text(
                                    "Recent Calls",
                                    color = text,
                                    fontSize = 23.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(Modifier.height(4.dp))

                                Text(
                                    "${calls.size} calls in your history",
                                    color = muted,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // SEARCH
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(15.dp),
                                color = card
                            ) {
                                Row(
                                    modifier = Modifier.padding(
                                        horizontal = 15.dp,
                                        vertical = 14.dp
                                    ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = "Search",
                                        tint = muted
                                    )

                                    Spacer(Modifier.width(11.dp))

                                    BasicTextField(
                                        value = search,
                                        onValueChange = { search = it },
                                        singleLine = true,
                                        cursorBrush = SolidColor(Emerald),
                                        textStyle = LocalTextStyle.current.copy(
                                            color = text,
                                            fontSize = 14.sp
                                        ),
                                        decorationBox = { inner ->
                                            Box {
                                                if (search.isEmpty()) {
                                                    Text(
                                                        "Search name or number",
                                                        color = muted,
                                                        fontSize = 13.sp
                                                    )
                                                }
                                                inner()
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )

                                    if (search.isNotEmpty()) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Clear search",
                                            tint = muted,
                                            modifier = Modifier.clickable {
                                                search = ""
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // ERROR
                        if (loadError != null) {
                            item {
                                Text(
                                    loadError,
                                    color = Color(0xFFE46C6C),
                                    fontSize = 13.sp
                                )
                            }
                        }

                        // LOADING
                        if (loading && calls.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(35.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = Emerald)
                                }
                            }
                        }

                        // EMPTY STATE
                        if (!loading && filteredCalls.isEmpty()) {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 45.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        Icons.Default.History,
                                        contentDescription = null,
                                        tint = muted,
                                        modifier = Modifier.size(38.dp)
                                    )

                                    Spacer(Modifier.height(12.dp))

                                    Text(
                                        if (search.isEmpty()) {
                                            "No recent calls"
                                        } else {
                                            "No matching calls"
                                        },
                                        color = text,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    Spacer(Modifier.height(5.dp))

                                    Text(
                                        if (search.isEmpty()) {
                                            "Your call history will appear here."
                                        } else {
                                            "Try another name or number."
                                        },
                                        color = muted,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }

                        // CALL HISTORY
                        items(filteredCalls) { call ->
                            CallRow(
                                call = call,
                                card = card,
                                text = text,
                                muted = muted,
                                onClick = { onCallClick(call) }
                            )
                        }

                        item {
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactVaniGuardHeader(text: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(11.dp),
            color = Emerald.copy(alpha = 0.13f)
        ) {
            Icon(
                Icons.Default.Shield,
                contentDescription = "VaniGuard logo",
                tint = Emerald,
                modifier = Modifier
                    .padding(8.dp)
                    .size(21.dp)
            )
        }

        Spacer(Modifier.width(10.dp))

        Text(
            "VaniGuard",
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = text
        )
    }
}



//--------------------------------------------
// LOAD RECENT CALLS
//--------------------------------------------
private suspend fun loadRecentCalls(
    context: Context,
    canReadContacts: Boolean
): List<CallItem> = withContext(Dispatchers.IO) {

    val result = mutableListOf<CallItem>()

    val projection = arrayOf(
        CallLog.Calls.NUMBER,
        CallLog.Calls.CACHED_NAME,
        CallLog.Calls.DATE,
        CallLog.Calls.TYPE
    )

    val cursor = context.contentResolver.query(
        CallLog.Calls.CONTENT_URI,
        projection,
        null,
        null,
        "${CallLog.Calls.DATE} DESC"
    )

    cursor?.use {
        val numberIndex = it.getColumnIndexOrThrow(
            CallLog.Calls.NUMBER
        )

        val nameIndex = it.getColumnIndexOrThrow(
            CallLog.Calls.CACHED_NAME
        )

        val dateIndex = it.getColumnIndexOrThrow(
            CallLog.Calls.DATE
        )

        val typeIndex = it.getColumnIndexOrThrow(
            CallLog.Calls.TYPE
        )

        while (it.moveToNext() && result.size < 100) {
            val number = it.getString(numberIndex) ?: ""
            val cachedName = it.getString(nameIndex)
            val timestamp = it.getLong(dateIndex)
            val callType = it.getInt(typeIndex)

            val contactName = if (!cachedName.isNullOrBlank()) {
                cachedName
            } else if (canReadContacts && number.isNotBlank()) {
                findContactName(context, number) ?: number
            } else {
                number.ifBlank { "Unknown Caller" }
            }

            val type = when (callType) {
                CallLog.Calls.INCOMING_TYPE -> "incoming"
                CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                CallLog.Calls.MISSED_TYPE -> "missed"
                CallLog.Calls.REJECTED_TYPE -> "missed"
                CallLog.Calls.BLOCKED_TYPE -> "missed"
                else -> "incoming"
            }

            val calendar = Calendar.getInstance()
            val today = Calendar.getInstance()

            calendar.timeInMillis = timestamp

            val time = if (
                calendar.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                calendar.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
            ) {
                SimpleDateFormat(
                    "hh:mm a",
                    Locale.getDefault()
                ).format(Date(timestamp))
            } else {
                SimpleDateFormat(
                    "dd MMM, hh:mm a",
                    Locale.getDefault()
                ).format(Date(timestamp))
            }

            result.add(
                CallItem(
                    name = contactName,
                    number = number,
                    time = time,
                    type = type,
                    risk = "Not checked"
                )
            )
        }
    }

    result
}

private fun findContactName(
    context: Context,
    number: String
): String? {
    val uri = Uri.withAppendedPath(
        ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
        Uri.encode(number)
    )

    val projection = arrayOf(
        ContactsContract.PhoneLookup.DISPLAY_NAME
    )

    return context.contentResolver.query(
        uri,
        projection,
        null,
        null,
        null
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getString(0)
        } else {
            null
        }
    }
}


@Composable

private fun CallRow(
    call: CallItem,
    card: Color,
    text: Color,
    muted: Color,
    onClick: () -> Unit
) {
    val icon = when (call.type) {
        "incoming" -> Icons.Default.CallReceived
        "outgoing" -> Icons.Default.CallMade
        else -> Icons.Default.CallMissed
    }

    val iconColor = when (call.type) {
        "missed" -> Color(0xFFE46C6C)
        else -> Emerald
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(19.dp),
        color = card,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 15.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            // CALLER AVATAR
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = iconColor.copy(alpha = 0.12f)
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier
                        .padding(13.dp)
                        .size(23.dp)
                )
            }

            Spacer(Modifier.width(13.dp))

            // CALLER INFORMATION
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = call.name,
                    color = text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = call.number,
                    color = muted,
                    fontSize = 12.sp,
                    maxLines = 1
                )

                Spacer(Modifier.height(9.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(14.dp)
                    )

                    Spacer(Modifier.width(6.dp))

                    Text(
                        text = call.time,
                        color = muted,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // REPUTATION LABEL AND NAVIGATION
            Column(
                horizontalAlignment = Alignment.End
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = muted.copy(alpha = 0.10f)
                ) {
                    Text(
                        text = call.risk,
                        color = muted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.padding(
                            horizontal = 9.dp,
                            vertical = 6.dp
                        )
                    )
                }

                Spacer(Modifier.height(10.dp))

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "View caller details",
                    tint = muted,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

// ----------------------------------------------------
// CALLER DATABASE_BACKED REPUTATION CARD
// ----------------------------------------------------
@Composable
private fun CallerReputationCard(
    state: CallerReputationUiState,
    card: Color,
    text: Color,
    muted: Color
) {
    val warning = Color(0xFFE6A45D)
    val danger = Color(0xFFE46C6C)

    val assessment = state.assessment

    val level = assessment?.level?.name ?: "UNVERIFIED"

    val statusColor = when (level) {
        "LOW_RISK" -> Emerald
        "SUSPICIOUS" -> warning
        "HIGH_RISK" -> danger
        else -> warning
    }

    val statusLabel = when (level) {
        "LOW_RISK" -> "LOW RISK"
        "SUSPICIOUS" -> "SUSPICIOUS"
        "HIGH_RISK" -> "HIGH RISK"
        else -> "NO REPORTS FOUND"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = card)
    ) {
        Column(
            modifier = Modifier.padding(19.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Shield,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(27.dp)
                )

                Spacer(Modifier.width(11.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        "CALLER REPUTATION",
                        color = muted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        when {
                            state.loading -> "Loading assessment..."
                            state.error != null -> "Unable to load"
                            else -> statusLabel
                        },
                        color = statusColor,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusColor.copy(alpha = 0.13f)
                ) {
                    Text(
                        if (assessment?.isCached == true) "CACHED"
                        else if (assessment != null) "ASSESSED"
                        else "NO DATA",
                        color = statusColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(
                            horizontal = 9.dp,
                            vertical = 6.dp
                        )
                    )
                }
            }

            HorizontalDivider(color = muted.copy(alpha = 0.2f))

            when {
                state.loading -> {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Emerald
                    )
                    Text(
                        "Retrieving the saved caller assessment.",
                        color = muted,
                        fontSize = 12.sp
                    )
                }

                state.error != null -> {
                    Text(
                        state.error,
                        color = danger,
                        fontSize = 12.sp
                    )
                }

                assessment == null -> {
                    Text(
                        "No saved reputation assessment was found for this number.",
                        color = muted,
                        fontSize = 12.sp,
                        lineHeight = 19.sp
                    )

                    Text(
                        "Unverified does not mean safe or fraudulent.",
                        color = text,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                "Risk index",
                                color = muted,
                                fontSize = 11.sp
                            )
                            Text(
                                "${assessment.riskScore}/100",
                                color = text,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "Confidence",
                                color = muted,
                                fontSize = 11.sp
                            )
                            Text(
                                assessment.confidence.name,
                                color = text,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Community reports",
                            color = muted,
                            fontSize = 12.sp
                        )
                        Text(
                            assessment.reportCount.toString(),
                            color = text,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        assessment.explanation,
                        color = muted,
                        fontSize = 12.sp,
                        lineHeight = 19.sp
                    )

                    Text(
                        "Assessment time: " +
                                SimpleDateFormat(
                                    "dd MMM yyyy, hh:mm a",
                                    Locale.getDefault()
                                ).format(
                                    Date(assessment.checkedAtEpochMillis)
                                ),
                        color = muted,
                        fontSize = 10.sp
                    )
                }
            }

            // Caller metadata
            state.profile?.let { profile ->
                HorizontalDivider(color = muted.copy(alpha = 0.2f))

                Text(
                    "CALLER METADATA",
                    color = muted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )

                profile.regionCode?.let {
                    CallerInfoRow(
                        Icons.Default.Public,
                        "Region",
                        it,
                        text,
                        muted
                    )
                }

                profile.numberType?.let {
                    CallerInfoRow(
                        Icons.Default.Phone,
                        "Number type",
                        it.replace('_', ' '),
                        text,
                        muted
                    )
                }

                profile.carrierName?.let {
                    CallerInfoRow(
                        Icons.Default.NetworkCell,
                        "Carrier",
                        it,
                        text,
                        muted
                    )
                }

                if (profile.identityVerified) {
                    CallerInfoRow(
                        Icons.Default.VerifiedUser,
                        "Identity verification",
                        profile.identityProvider ?: "Verified provider",
                        text,
                        muted
                    )
                }
            }

            // Supporting evidence
            if (state.evidence.isNotEmpty()) {
                HorizontalDivider(color = muted.copy(alpha = 0.2f))

                Text(
                    "REPUTATION EVIDENCE",
                    color = muted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )

                state.evidence.forEach { evidence ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            evidence.type.name.replace('_', ' '),
                            color = text,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )

                        Text(
                            evidence.description,
                            color = muted,
                            fontSize = 11.sp,
                            lineHeight = 17.sp
                        )

                        if (evidence.reportCount > 0) {
                            Text(
                                "Reports: ${evidence.reportCount}",
                                color = muted,
                                fontSize = 10.sp
                            )
                        }

                        if (evidence.isVerified) {
                            Text(
                                "Verified evidence source",
                                color = Emerald,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            Text(
                "Caller reputation is independent of conversation analysis.",
                color = muted,
                fontSize = 10.sp,
                lineHeight = 15.sp
            )
        }
    }
}

// CALLER DETAILS Screen
//-----------------------------------------------------

@Composable
private fun CallerDetailsScreen(
    call: CallItem,
    card: Color,
    text: Color,
    muted: Color,
    onBack: () -> Unit
) {
    val warning = Color(0xFFE6A45D)

    val context = LocalContext.current

    var reputationState by remember(call.number) {
        mutableStateOf(CallerReputationUiState())
    }

    LaunchedEffect(call.number) {
        reputationState = CallerReputationUiState(loading = true)

        try {
            val result = withContext(Dispatchers.IO) {
                val database = CallerReputationDatabase.getInstance(
                    context.applicationContext
                )

                val repository = CallerReputationRepository(
                    database.callerReputationDao()
                )

                val assessment = repository.getCachedAssessment(call.number)
                val profile = repository.getCallerProfile(call.number)
                val evidence = repository.getCallerEvidence(call.number)

                Triple(assessment, profile, evidence)
            }

            reputationState = CallerReputationUiState(
                assessment = result.first,
                profile = result.second,
                evidence = result.third,
                loading = false
            )
        } catch (exception: Exception) {
            reputationState = CallerReputationUiState(
                loading = false,
                error = exception.message ?: "Unable to retrieve caller reputation."
            )
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {

        Spacer(Modifier.height(20.dp))

        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = text
                )
            }

            Text(
                "Caller Details",
                color = text,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(25.dp))

        // Caller identity
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = Emerald.copy(alpha = 0.13f),
                modifier = Modifier.size(85.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = Emerald,
                        modifier = Modifier.size(43.dp)
                    )
                }
            }

            Spacer(Modifier.height(15.dp))

            Text(
                call.name,
                color = text,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(5.dp))

            Text(
                call.number,
                color = muted,
                fontSize = 14.sp
            )
        }

        Spacer(Modifier.height(27.dp))
        // Live database-backed caller reputation

        CallerReputationCard(
            state = reputationState,
            card = card,
            text = text,
            muted = muted
        )

        Spacer(Modifier.height(23.dp))

        // Call information
        Text(
            "CALL INFORMATION",
            color = muted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.3.sp
        )

        Spacer(Modifier.height(11.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = card
            )
        ) {
            Column(
                modifier = Modifier.padding(17.dp),
                verticalArrangement = Arrangement.spacedBy(17.dp)
            ) {
                CallerInfoRow(
                    icon = Icons.Default.Call,
                    label = "Call type",
                    value = call.type.replaceFirstChar {
                        it.uppercase()
                    },
                    text = text,
                    muted = muted
                )

                CallerInfoRow(
                    icon = Icons.Default.Schedule,
                    label = "Date and time",
                    value = call.time,
                    text = text,
                    muted = muted
                )

                CallerInfoRow(
                    icon = Icons.Default.Phone,
                    label = "Phone number",
                    value = call.number,
                    text = text,
                    muted = muted
                )
            }
        }

        Spacer(Modifier.height(23.dp))

        // Analysis information
        Text(
            "PROTECTION ANALYSIS",
            color = muted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.3.sp
        )

        Spacer(Modifier.height(11.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = card
            )
        ) {
            Column(
                modifier = Modifier.padding(17.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = Emerald
                    )

                    Spacer(Modifier.width(10.dp))

                    Text(
                        "Conversation Analysis",
                        color = text,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(Modifier.height(9.dp))

                Text(
                    "No conversation analysis is available for this call.",
                    color = muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(Modifier.height(25.dp))
    }
}

@Composable
private fun CallerInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    text: Color,
    muted: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Emerald,
            modifier = Modifier.size(21.dp)
        )

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = muted,
                fontSize = 11.sp
            )

            Spacer(Modifier.height(3.dp))

            Text(
                value,
                color = text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ----------------------------------------------------
// DIALER
// ----------------------------------------------------

@Composable
fun DialerScreen(
    isDark: Boolean,
    card: Color,
    text: Color,
    muted: Color,
    initialNumber: String? = null,
    onInitialNumberConsumed: () -> Unit = {}
) {
    val context = LocalContext.current

    var number by remember { mutableStateOf(initialNumber.orEmpty()) }

    LaunchedEffect(initialNumber) {
        if (!initialNumber.isNullOrBlank()) {
            number = initialNumber
            onInitialNumberConsumed()
        }
    }

    fun startCall() {
        val dialableNumber = number.filter {
            it.isDigit() || it == '+' || it == '*' || it == '#'
        }

        if (dialableNumber.isBlank()) {
            Toast.makeText(
                context,
                "Enter a valid phone number",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        try {
            val telecomManager =
                context.getSystemService(TelecomManager::class.java)

            if (telecomManager == null) {
                Toast.makeText(
                    context,
                    "Telecom service is unavailable",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            telecomManager.placeCall(
                Uri.fromParts("tel", dialableNumber, null),
                Bundle()
            )

        } catch (e: SecurityException) {
            Toast.makeText(
                context,
                "Phone permission is required to make calls",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Unable to place call: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val callPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startCall()
            } else {
                Toast.makeText(
                    context,
                    "Phone permission denied",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    fun requestAndPlaceCall() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startCall()
        } else {
            callPermissionLauncher.launch(
                Manifest.permission.CALL_PHONE
            )
        }
    }

    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("*", "0", "#")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(30.dp))

        Text(
            "Dialer",
            color = text,
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(40.dp))

        Text(
            if (number.isEmpty()) "Enter phone number" else number,
            color = text,
            fontSize = 26.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(Modifier.height(24.dp))

        keys.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { key ->
                    Surface(
                        shape = CircleShape,
                        color = card,
                        modifier = Modifier
                            .padding(7.dp)
                            .size(72.dp)
                            .clickable { number += key }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(key, color = text, fontSize = 25.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.weight(1f))

            Surface(
                shape = CircleShape,
                color = Emerald,
                modifier = Modifier
                    .size(62.dp)
                    .clickable {
                        requestAndPlaceCall()
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Call,
                        contentDescription = "Call",
                        tint = Color.Black,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            IconButton(
                onClick = {
                    if (number.isNotEmpty()) {
                        number = number.dropLast(1)
                    }
                }
            ) {
                Icon(
                    Icons.Default.Backspace,
                    contentDescription = "Delete",
                    tint = muted
                )
            }
        }

        Text(
            "Calls are placed through Android Telecom.",
            color = muted,
            fontSize = 10.sp
        )
    }
}


// ----------------------------------------------------
// PROFILE / EXISTING DASHBOARD
// ----------------------------------------------------

@Composable
fun ProfileScreen(
    isDark: Boolean,
    card: Color,
    text: Color,
    muted: Color,
    selectedTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit
) {
    val context = LocalContext.current

    val roleManager = remember {
        context.getSystemService(RoleManager::class.java)
    }

    var hasCallScreeningRole by remember {
        mutableStateOf(
            roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        )
    }

    val callScreeningRoleLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            hasCallScreeningRole =
                roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        }

    val callScreeningRoleAvailable = remember {
        roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)
    }
    // Default dialer role
    var hasDialerRole by remember {
        mutableStateOf(
            roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
        )
    }

    val dialerRoleAvailable = remember {
        roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)
    }

    val dialerRoleLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            hasDialerRole =
                roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
        }
    var protectionEnabled by remember { mutableStateOf(false) }
    var analysisMode by remember { mutableStateOf("Automatic") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(24.dp))

        Text(
            "My VaniGuard",
            color = text,
            fontSize = 25.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Your personal protection centre",
            color = muted,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(23.dp))

        // Protection status
        Card(
            colors = CardDefaults.cardColors(containerColor = card),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(
                modifier = Modifier.padding(19.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("PROTECTION STATUS", color = muted, fontSize = 10.sp)
                        Spacer(Modifier.height(7.dp))
                        Text(
                            if (protectionEnabled) "Shield is active"
                            else "Shield is inactive",
                            color = text,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = null,
                        tint = Emerald,
                        modifier = Modifier.size(42.dp)
                    )
                }

                Button(
                    onClick = { protectionEnabled = !protectionEnabled },
                    modifier = Modifier.fillMaxWidth().height(49.dp),
                    shape = RoundedCornerShape(13.dp)
                ) {
                    Icon(
                        if (protectionEnabled) Icons.Default.Stop
                        else Icons.Default.Shield,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (protectionEnabled) "Stop Protection" else "Start Protection")
                }

                Text(
                    "Dashboard controls are not connected to call services yet.",
                    color = muted,
                    fontSize = 10.sp
                )
            }
        }

        Spacer(Modifier.height(23.dp))

        ProfileSectionTitle("DEFAULT PHONE APP", muted)

        Card(
            colors = CardDefaults.cardColors(containerColor = card),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(17.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Call,
                        contentDescription = null,
                        tint = Emerald,
                        modifier = Modifier.size(27.dp)
                    )

                    Spacer(Modifier.width(12.dp))

                    Column(Modifier.weight(1f)) {
                        Text(
                            "VaniGuard Phone",
                            color = text,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            when {
                                hasDialerRole ->
                                    "VaniGuard is your default phone app"
                                else ->
                                    "Required for VaniGuard's in-call service"
                            },
                            color = muted,
                            fontSize = 11.sp
                        )
                    }

                    Text(
                        if (hasDialerRole) "ACTIVE" else "NOT SET",
                        color = if (hasDialerRole) Emerald
                        else Color(0xFFE6A45D),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = {
                        if (
                            dialerRoleAvailable &&
                            !hasDialerRole
                        ) {
                            dialerRoleLauncher.launch(
                                roleManager.createRequestRoleIntent(
                                    RoleManager.ROLE_DIALER
                                )
                            )
                        }
                    },
                    enabled = dialerRoleAvailable && !hasDialerRole,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(47.dp),
                    shape = RoundedCornerShape(13.dp)
                ) {
                    Text(
                        when {
                            hasDialerRole -> "Default Dialer Enabled"
                            !dialerRoleAvailable -> "Not Available"
                            else -> "Set as Default Phone App"
                        }
                    )
                }

                Text(
                    "VaniGuard needs this role to manage incoming and " +
                            "ongoing call screens through Android Telecom. " +
                            "You can change your default phone app again in Android Settings.",
                    color = muted,
                    fontSize = 10.sp,
                    lineHeight = 16.sp
                )
            }
        }

        ProfileSectionTitle("CALL SCREENING PERMISSION", muted)

        Card(
            colors = CardDefaults.cardColors(containerColor = card),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(17.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = null,
                        tint = Emerald,
                        modifier = Modifier.size(27.dp)
                    )

                    Spacer(Modifier.width(12.dp))

                    Column(Modifier.weight(1f)) {
                        Text(
                            "Android Call Screening",
                            color = text,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            if (hasCallScreeningRole)
                                "VaniGuard is your screening app"
                            else
                                "Permission required to screen incoming calls",
                            color = muted,
                            fontSize = 11.sp
                        )
                    }

                    Text(
                        if (hasCallScreeningRole) "ACTIVE" else "NOT SET",
                        color = if (hasCallScreeningRole) Emerald
                        else Color(0xFFE6A45D),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = {
                        if (callScreeningRoleAvailable &&
                            !hasCallScreeningRole
                        ) {
                            callScreeningRoleLauncher.launch(
                                roleManager.createRequestRoleIntent(
                                    RoleManager.ROLE_CALL_SCREENING
                                )
                            )
                        }
                    },
                    enabled = callScreeningRoleAvailable &&
                            !hasCallScreeningRole,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(47.dp),
                    shape = RoundedCornerShape(13.dp)
                ) {
                    Text(
                        when {
                            hasCallScreeningRole -> "Screening Enabled"
                            !callScreeningRoleAvailable -> "Not Available"
                            else -> "Enable Call Screening"
                        }
                    )
                }

                Text(
                    "Android must authorize VaniGuard before it can " +
                            "receive incoming call screening requests.",
                    color = muted,
                    fontSize = 10.sp,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(Modifier.height(23.dp))

        ProfileSectionTitle("SYSTEM CONNECTION", muted)

        ProfileInfoCard(
            icon = Icons.Default.Laptop,
            title = "Laptop AI Server",
            description = "Backend not connected",
            card = card,
            text = text,
            muted = muted,
            trailing = "OFFLINE"
        )

        Spacer(Modifier.height(22.dp))

        ProfileSectionTitle("PROTECTION FEATURES", muted)

        ProfileInfoCard(
            icon = Icons.Default.Call,
            title = "Caller Reputation",
            description = "Incoming caller reputation checks",
            card = card,
            text = text,
            muted = muted,
            trailing = "ALWAYS ON"
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = card),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = Emerald
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Conversation Analysis", color = text, fontWeight = FontWeight.SemiBold)
                        Text("Analyze available call audio", color = muted, fontSize = 11.sp)
                    }
                }

                Text("ANALYSIS MODE", color = muted, fontSize = 10.sp)

                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf("Automatic", "Manual").forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = analysisMode == mode,
                            onClick = { analysisMode = mode },
                            shape = SegmentedButtonDefaults.itemShape(index, 2)
                        ) {
                            Text(mode, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(23.dp))

        ProfileSectionTitle("APPEARANCE", muted)

        Card(
            colors = CardDefaults.cardColors(containerColor = card),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Palette,
                        contentDescription = null,
                        tint = Emerald
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("App Theme", color = text, fontWeight = FontWeight.SemiBold)
                        Text("Choose your preferred appearance", color = muted, fontSize = 11.sp)
                    }
                }

                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf(
                        AppTheme.LIGHT,
                        AppTheme.DARK,
                        AppTheme.SYSTEM
                    ).forEachIndexed { index, theme ->
                        SegmentedButton(
                            selected = selectedTheme == theme,
                            onClick = { onThemeSelected(theme) },
                            shape = SegmentedButtonDefaults.itemShape(index, 3)
                        ) {
                            Text(
                                when (theme) {
                                    AppTheme.LIGHT -> "Day"
                                    AppTheme.DARK -> "Night"
                                    AppTheme.SYSTEM -> "System"
                                },
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(23.dp))

        ProfileSectionTitle("MORE", muted)

        ProfileInfoCard(
            icon = Icons.Default.Description,
            title = "Reports & Evidence",
            description = "Call history and saved evidence",
            card = card,
            text = text,
            muted = muted
        )

        ProfileInfoCard(
            icon = Icons.Default.Settings,
            title = "Settings",
            description = "Manage app preferences",
            card = card,
            text = text,
            muted = muted
        )

        ProfileInfoCard(
            icon = Icons.Default.Info,
            title = "About VaniGuard",
            description = "Learn more about VaniGuard",
            card = card,
            text = text,
            muted = muted
        )

        Spacer(Modifier.height(22.dp))

        Text(
            "VaniGuard • Protecting conversations, protecting people.",
            color = muted,
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ProfileSectionTitle(title: String, muted: Color) {
    Text(
        title,
        color = muted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
private fun ProfileInfoCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    card: Color,
    text: Color,
    muted: Color,
    trailing: String? = null
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = card),
        shape = RoundedCornerShape(17.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp)
    ) {
        Row(
            modifier = Modifier.padding(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = Emerald, modifier = Modifier.size(25.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = text, fontWeight = FontWeight.SemiBold)
                Text(description, color = muted, fontSize = 11.sp)
            }
            if (trailing != null) {
                Text(
                    trailing,
                    color = if (trailing == "OFFLINE") Color(0xFFE6A45D) else Emerald,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = muted)
            }
        }
    }
}

// ----------------------------------------------------
// FLOATING BOTTOM DOCK
// ----------------------------------------------------


@Composable
fun FloatingDock(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    isDark: Boolean,
    card: Color,
    text: Color,
    muted: Color
) {
    Surface(
        color = card,
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 12.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DockItem(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.History,
                label = "Recent",
                selected = selectedTab == AppTab.RECENT,
                text = text,
                muted = muted,
                onClick = { onTabSelected(AppTab.RECENT) }
            )

            DockItem(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Dialpad,
                label = "Dialer",
                selected = selectedTab == AppTab.DIALER,
                text = text,
                muted = muted,
                onClick = { onTabSelected(AppTab.DIALER) }
            )

            DockItem(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Shield,
                label = "Shield",
                selected = selectedTab == AppTab.SHIELD,
                text = text,
                muted = muted,
                onClick = { onTabSelected(AppTab.SHIELD) }
            )

            DockItem(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Person,
                label = "Profile",
                selected = selectedTab == AppTab.PROFILE,
                text = text,
                muted = muted,
                onClick = { onTabSelected(AppTab.PROFILE) }
            )
        }
    }
}

@Composable
private fun DockItem(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    text: Color,
    muted: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .padding(horizontal = 2.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(17.dp),
        color = if (selected) {
            Emerald.copy(alpha = 0.13f)
        } else {
            Color.Transparent
        }
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = 4.dp,
                vertical = 10.dp
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) Emerald else muted,
                modifier = Modifier.size(22.dp)
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = label,
                color = if (selected) Emerald else text,
                fontSize = 10.sp,
                fontWeight = if (selected) {
                    FontWeight.Bold
                } else {
                    FontWeight.Medium
                }
            )
        }
    }
}


@Composable
fun LiveShieldScreen(
    isDark: Boolean,
    card: Color,
    text: Color,
    muted: Color
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isRecording by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready to protect") }
    var audioLevel by remember { mutableFloatStateOf(0f) }

    var transcript by remember { mutableStateOf("") }
    var prediction by remember { mutableStateOf("NOT ANALYZED") }
    var riskLevel by remember { mutableStateOf("—") }
    var fraudScore by remember { mutableFloatStateOf(0f) }
    var analysisWarning by remember { mutableStateOf("") }

    var sessionId by remember { mutableStateOf("") }
    var segmentCount by remember { mutableIntStateOf(0) }
    var finalReportReady by remember { mutableStateOf(false) }

    val running = remember {
        java.util.concurrent.atomic.AtomicBoolean(false)
    }

    val recorderRef = remember {
        java.util.concurrent.atomic.AtomicReference<AudioRecord?>(null)
    }

    val recordingThreadRef = remember {
        java.util.concurrent.atomic.AtomicReference<Thread?>(null)
    }

    val uploadChannelRef = remember {
        java.util.concurrent.atomic.AtomicReference<
                kotlinx.coroutines.channels.Channel<ByteArray>?
                >(null)
    }

    val riskColor = when (riskLevel.uppercase()) {
        "HIGH" -> Color(0xFFE05252)
        "MEDIUM" -> Color(0xFFE6A23C)
        "LOW" -> Emerald
        else -> muted
    }

    fun stopCapture() {
        if (!running.compareAndSet(true, false)) return

        isRecording = false
        audioLevel = 0f
        status = "Stopping microphone and processing remaining audio..."

        val recorder = recorderRef.get()

        try {
            recorder?.stop()
        } catch (_: Exception) {
        }

        scope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    recordingThreadRef.get()?.join(5000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }

                try {
                    recorderRef.getAndSet(null)?.release()
                } catch (_: Exception) {
                }
            }

            uploadChannelRef.getAndSet(null)?.close()
            status = "Finishing final call analysis..."
        }
    }

    fun startCapture() {
        if (running.get() || isRecording) return

        scope.launch {
            try {
                status = "Connecting to VaniGuard AI..."
                finalReportReady = false
                transcript = ""
                prediction = "NOT ANALYZED"
                riskLevel = "—"
                fraudScore = 0f
                segmentCount = 0
                analysisWarning = ""

                // Start a new backend session.
                val session = LiveAudioApi.createSession()
                sessionId = session.sessionId

                val sampleRate = 16000
                val chunkSamples = sampleRate * 15

                val bufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                if (bufferSize <= 0) {
                    status = "Microphone is unavailable"
                    return@launch
                }

                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(bufferSize * 2, 8192)
                )

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    recorder.release()
                    status = "Unable to initialize microphone"
                    return@launch
                }

                val channel =
                    kotlinx.coroutines.channels.Channel<ByteArray>(
                        capacity = kotlinx.coroutines.channels.Channel.UNLIMITED
                    )

                uploadChannelRef.set(channel)
                recorderRef.set(recorder)

                // One worker uploads segments in order.
                scope.launch {
                    try {
                        for (wavBytes in channel) {
                            isUploading = true
                            status = "Transcribing and analyzing conversation..."

                            try {
                                val result = LiveAudioApi.uploadSessionSegment(
                                    sessionId = session.sessionId,
                                    wavBytes = wavBytes
                                )

                                segmentCount = result.segmentNumber
                                transcript = result.fullTranscript
                                prediction = result.analysis.prediction
                                riskLevel = result.analysis.riskLevel
                                fraudScore = result.analysis.fraudScore.toFloat()
                                analysisWarning = result.analysis.warning

                                status = "Segment ${result.segmentNumber} analyzed"
                            } catch (e: Exception) {
                                status = "Segment upload failed: ${e.message}"
                                analysisWarning =
                                    "Some audio could not be analyzed. Check your server connection."
                            } finally {
                                isUploading = false
                            }
                        }
                    } finally {
                        isUploading = false

                        try {
                            val report = LiveAudioApi.finishSession(session.sessionId)

                            transcript = report.fullTranscript
                            prediction = report.finalAnalysis.prediction
                            riskLevel = report.finalAnalysis.riskLevel
                            fraudScore = report.finalAnalysis.fraudScore.toFloat()
                            analysisWarning = report.finalAnalysis.warning
                            segmentCount = report.segmentCount

                            finalReportReady = true
                            status = "Call analysis completed"
                        } catch (e: Exception) {
                            status = "Final report failed: ${e.message}"
                        }
                    }
                }

                recorder.startRecording()

                running.set(true)
                isRecording = true
                status = "Microphone active — listening"

                val readBuffer = ShortArray(2048)

                val recordingThread = Thread {
                    val chunk = ShortArray(chunkSamples)
                    var chunkPosition = 0
                    var meterCounter = 0

                    while (running.get()) {
                        val count = try {
                            recorder.read(
                                readBuffer,
                                0,
                                readBuffer.size,
                                AudioRecord.READ_BLOCKING
                            )
                        } catch (_: Exception) {
                            break
                        }

                        if (count <= 0) continue

                        var peak = 0

                        for (i in 0 until count) {
                            peak = maxOf(
                                peak,
                                kotlin.math.abs(readBuffer[i].toInt())
                            )
                        }

                        meterCounter++

                        if (meterCounter % 5 == 0) {
                            val level = (peak / 32767f).coerceIn(0f, 1f)
                            scope.launch {
                                audioLevel = level
                            }
                        }

                        var sourcePosition = 0

                        while (
                            sourcePosition < count &&
                            chunkPosition < chunkSamples
                        ) {
                            val copyCount = minOf(
                                count - sourcePosition,
                                chunkSamples - chunkPosition
                            )

                            System.arraycopy(
                                readBuffer,
                                sourcePosition,
                                chunk,
                                chunkPosition,
                                copyCount
                            )

                            sourcePosition += copyCount
                            chunkPosition += copyCount
                        }

                        if (chunkPosition >= chunkSamples) {
                            val completedChunk = chunk.copyOf()
                            chunkPosition = 0

                            val wavBytes =
                                LiveAudioApi.pcmToWav(shortArrayToPcmBytes(completedChunk))

                            try {
                                kotlinx.coroutines.runBlocking {
                                    channel.send(wavBytes)
                                }
                            } catch (_: Exception) {
                                break
                            }
                        }
                    }

                    // Preserve the final partial segment when monitoring stops.
                    if (chunkPosition > 0) {
                        val finalChunk = chunk.copyOf(chunkPosition)
                        val wavBytes = LiveAudioApi.pcmToWav(shortArrayToPcmBytes(finalChunk))

                        try {
                            kotlinx.coroutines.runBlocking {
                                channel.send(wavBytes)
                            }
                        } catch (_: Exception) {
                        }
                    }
                }

                recordingThreadRef.set(recordingThread)
                recordingThread.start()

            } catch (e: SecurityException) {
                status = "Microphone permission denied"
                running.set(false)
                isRecording = false
            } catch (e: Exception) {
                status = "Unable to start monitoring: ${e.message}"
                running.set(false)
                isRecording = false

                try {
                    recorderRef.getAndSet(null)?.release()
                } catch (_: Exception) {
                }

                uploadChannelRef.getAndSet(null)?.close()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCapture()
        } else {
            status = "Microphone permission is required"
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            running.set(false)

            try {
                recorderRef.getAndSet(null)?.let { recorder ->
                    recorder.stop()
                    recorder.release()
                }
            } catch (_: Exception) {
            }

            uploadChannelRef.getAndSet(null)?.close()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDark) DarkBackground else Cream)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 20.dp)
    ) {

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(15.dp),
                color = Emerald.copy(alpha = 0.14f)
            ) {
                Icon(
                    Icons.Default.Shield,
                    contentDescription = null,
                    tint = Emerald,
                    modifier = Modifier.padding(12.dp).size(28.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "LIVE PROTECTION",
                    color = muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )

                Text(
                    "VaniGuard Shield",
                    color = text,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Surface(
                shape = RoundedCornerShape(50.dp),
                color = if (isRecording)
                    Emerald.copy(alpha = 0.15f)
                else muted.copy(alpha = 0.12f)
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = 11.dp,
                        vertical = 7.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .background(
                                if (isRecording) Emerald else muted,
                                RoundedCornerShape(50.dp)
                            )
                    )

                    Spacer(Modifier.width(6.dp))

                    Text(
                        if (isRecording) "ACTIVE" else "STANDBY",
                        color = if (isRecording) Emerald else muted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        // Main protection card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = card
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "CALL RISK ASSESSMENT",
                            color = muted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )

                        Spacer(Modifier.height(5.dp))

                        Text(
                            prediction.replace("_", " "),
                            color = riskColor,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Box(
                        modifier = Modifier.size(82.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = { (fraudScore / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxSize(),
                            color = riskColor,
                            trackColor = muted.copy(alpha = 0.15f),
                            strokeWidth = 7.dp
                        )

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "${fraudScore.toInt()}",
                                color = text,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "/ 100",
                                color = muted,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = muted.copy(alpha = 0.18f))
                Spacer(Modifier.height(15.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("RISK LEVEL", color = muted, fontSize = 10.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            riskLevel,
                            color = riskColor,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text("SEGMENTS ANALYZED", color = muted, fontSize = 10.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            segmentCount.toString(),
                            color = text,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (isUploading) {
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Emerald,
                        trackColor = muted.copy(alpha = 0.15f)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "AI is processing the conversation...",
                        color = muted,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Microphone monitor
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = card
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isRecording) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = null,
                        tint = if (isRecording) Emerald else muted,
                        modifier = Modifier.size(23.dp)
                    )

                    Spacer(Modifier.width(9.dp))

                    Text(
                        "Audio monitoring",
                        color = text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

                Spacer(Modifier.height(17.dp))

                LinearProgressIndicator(
                    progress = { audioLevel },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                    color = Emerald,
                    trackColor = muted.copy(alpha = 0.18f)
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    status,
                    color = muted,
                    fontSize = 12.sp
                )

                Spacer(Modifier.height(17.dp))

                Button(
                    onClick = {
                        if (isRecording) {
                            stopCapture()
                        } else {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED

                            if (granted) {
                                startCapture()
                            } else {
                                permissionLauncher.launch(
                                    Manifest.permission.RECORD_AUDIO
                                )
                            }
                        }
                    },
                    enabled = !isUploading || isRecording,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording)
                            Color(0xFFB3261E)
                        else Emerald,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Icon(
                        if (isRecording) Icons.Default.Stop
                        else Icons.Default.PlayArrow,
                        contentDescription = null
                    )

                    Spacer(Modifier.width(8.dp))

                    Text(
                        if (isRecording) "Stop monitoring"
                        else "Start protection",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Conversation transcript
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = card
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Notes,
                        contentDescription = null,
                        tint = Emerald
                    )

                    Spacer(Modifier.width(9.dp))

                    Text(
                        "Conversation intelligence",
                        color = text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(13.dp))

                Text(
                    if (transcript.isBlank())
                        "Your conversation transcript will appear here as audio segments are processed."
                    else transcript,
                    color = if (transcript.isBlank()) muted else text,
                    fontSize = 13.sp,
                    lineHeight = 21.sp
                )

                if (analysisWarning.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        analysisWarning,
                        color = muted,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        if (finalReportReady) {
            Spacer(Modifier.height(14.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Emerald.copy(alpha = 0.12f)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Emerald
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Final call report ready",
                            color = text,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    Text(
                        "Session completed • $segmentCount segments analyzed",
                        color = muted,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Session information
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = card
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "SESSION INFORMATION",
                    color = muted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )

                Spacer(Modifier.height(9.dp))

                Text(
                    if (sessionId.isBlank())
                        "No active session"
                    else "ID: $sessionId",
                    color = muted,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Text(
            "VaniGuard uses microphone audio for experimental fraud-risk analysis. " +
                    "Cellular call audio capture is device-dependent and may require speakerphone. " +
                    "This assessment is not proof of fraud.",
            color = muted,
            fontSize = 11.sp,
            lineHeight = 17.sp
        )

        Spacer(Modifier.height(20.dp))
    }
}
private fun shortArrayToPcmBytes(samples: ShortArray): ByteArray {
    val bytes = ByteArray(samples.size * 2)

    for (i in samples.indices) {
        val sample = samples[i].toInt()
        bytes[i * 2] = (sample and 0xFF).toByte()
        bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
    }

    return bytes
}
