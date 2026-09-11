package com.example.securityalert

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.text.KeyboardOptions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings

import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.core.content.ContextCompat

import com.google.firebase.messaging.FirebaseMessaging

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

import org.json.JSONObject

import java.util.concurrent.TimeUnit


class MainActivity : ComponentActivity() {

    // ---------------------------------------------------------
    // Firebase / Contact information
    // ---------------------------------------------------------

    private var fcmToken by mutableStateOf<String?>(null)

    private var name by mutableStateOf("")
    private var phoneNumber by mutableStateOf("")

    private var isRegistered by mutableStateOf(false)

    private var isSubmitting by mutableStateOf(false)
    private var isCheckingRegistration by mutableStateOf(false)

    private var statusMessage by mutableStateOf("Getting Firebase token...")


    // ---------------------------------------------------------
    // Server connection
    // ---------------------------------------------------------

    private var selectedServerType by mutableStateOf("hosted")

    private var serverInput by mutableStateOf("")

    private var isConnecting by mutableStateOf(false)

    private var isServerConnected by mutableStateOf(false)

    private var connectionMessage by mutableStateOf("")


    // ---------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------

    private var currentPage by mutableStateOf("settings")

    private var drawerOpen by mutableStateOf(false)


    // ---------------------------------------------------------
    // Saved server history
    // ---------------------------------------------------------

    private val preferences by lazy {
        getSharedPreferences(
            "security_alert_preferences",
            Context.MODE_PRIVATE
        )
    }

    private val hostedHistoryKey = "hosted_history"
    private val localHistoryKey = "local_history"

    private val maxHistoryItems = 10


    // ---------------------------------------------------------
    // HTTP client
    // ---------------------------------------------------------

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()


    // ---------------------------------------------------------
    // Notification permission
    // ---------------------------------------------------------

    private val requestNotificationPermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            getFirebaseToken()
        }


    // =========================================================
    // ACTIVITY
    // =========================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SecurityAlertApp(
                currentPage = currentPage,
                drawerOpen = drawerOpen,

                selectedServerType = selectedServerType,
                serverInput = serverInput,

                hostedSuggestions = getFilteredHistory(
                    getHistory(hostedHistoryKey),
                    serverInput
                ),
                localSuggestions = getFilteredHistory(
                    getHistory(localHistoryKey),
                    serverInput
                ),

                isConnecting = isConnecting,
                isServerConnected = isServerConnected,
                connectionMessage = connectionMessage,

                name = name,
                phoneNumber = phoneNumber,
                tokenAvailable = fcmToken != null,
                isRegistered = isRegistered,
                isSubmitting = isSubmitting,
                isCheckingRegistration = isCheckingRegistration,
                statusMessage = statusMessage,

                onOpenDrawer = {
                    drawerOpen = true
                },

                onCloseDrawer = {
                    drawerOpen = false
                },

                onSettingsClick = {
                    currentPage = "settings"
                    drawerOpen = false
                },

                onContactClick = {
                    if (isServerConnected) {
                        currentPage = "contact"
                        drawerOpen = false
                    }
                },

                onServerTypeChange = {
                    selectedServerType = it
                    serverInput = ""
                },

                onServerInputChange = {
                    serverInput = it
                },

                onDeleteSuggestion = { value ->
                    val key = if (selectedServerType == "hosted") {
                        hostedHistoryKey
                    } else {
                        localHistoryKey
                    }

                    deleteHistoryEntry(key, value)
                },

                onSuggestionClick = {
                    serverInput = it
                },

                onConnect = {
                    connectToServer()
                },

                onNameChange = {
                    name = it
                },

                onPhoneChange = {
                    phoneNumber = it
                },

                onSubmit = {
                    registerDevice()
                }
            )
        }

        askNotificationPermission()
    }

// =========================================================
// SERVER URL
// =========================================================

private fun getBackendBaseUrl(): String {

    return if (selectedServerType == "hosted") {

        "https://${serverInput.trim()}"

    } else {

        "http://${serverInput.trim()}:8000"
    }
}


// =========================================================
// SERVER CONNECTION
// =========================================================

private fun connectToServer() {

    val input = serverInput.trim()

    if (input.isEmpty()) {

        connectionMessage =
            if (selectedServerType == "hosted") {
                "Please enter the hosted server domain."
            } else {
                "Please enter the local server IP address."
            }

        return
    }

    isConnecting = true
    isServerConnected = false

    connectionMessage = "Connecting to server..."

    val baseUrl = getBackendBaseUrl()

    Thread {

        try {

            val request = Request.Builder()
                .url("$baseUrl/")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->

                if (response.isSuccessful) {

                    saveHistoryEntry(
                        if (selectedServerType == "hosted") {
                            hostedHistoryKey
                        } else {
                            localHistoryKey
                        },
                        input
                    )

                    runOnUiThread {

                        isConnecting = false
                        isServerConnected = true

                        connectionMessage =
                            "Connected successfully."

                        currentPage = "settings"

                        Toast.makeText(
                            this,
                            "Server connected",
                            Toast.LENGTH_SHORT
                        ).show()

                        // If Firebase token is already ready,
                        // check registration now.
                        val token = fcmToken

                        if (token != null) {
                            checkRegistration(token)
                        }
                    }

                } else {

                    runOnUiThread {

                        isConnecting = false
                        isServerConnected = false

                        connectionMessage =
                            "Connection failed.\nHTTP ${response.code}"

                        Toast.makeText(
                            this,
                            "Could not connect to server",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    Log.e(
                        "SecurityAlert",
                        "Server connection failed: HTTP ${response.code}"
                    )
                }
            }

        } catch (exception: Exception) {

            Log.e(
                "SecurityAlert",
                "Server connection failed",
                exception
            )

            runOnUiThread {

                isConnecting = false
                isServerConnected = false

                connectionMessage =
                    "Could not connect to server.\n${exception.message}"

                Toast.makeText(
                    this,
                    "Could not connect to server",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    }.start()
}


// =========================================================
// SERVER HISTORY
// =========================================================

private fun getHistory(key: String): List<String> {

    val value = preferences.getString(key, "") ?: ""

    if (value.isBlank()) {
        return emptyList()
    }

    return value
        .split("|")
        .filter {
            it.isNotBlank()
        }
}


private fun saveHistoryEntry(
    key: String,
    value: String
) {

    val cleanedValue = value.trim()

    if (cleanedValue.isEmpty()) {
        return
    }

    val currentHistory = getHistory(key)
        .filter {
            !it.equals(
                cleanedValue,
                ignoreCase = true
            )
        }
        .toMutableList()

    currentHistory.add(
        0,
        cleanedValue
    )

    val limitedHistory =
        currentHistory.take(maxHistoryItems)

    preferences.edit()
        .putString(
            key,
            limitedHistory.joinToString("|")
        )
        .apply()
}


private fun deleteHistoryEntry(
    key: String,
    value: String
) {

    val updatedHistory =
        getHistory(key)
            .filter {
                !it.equals(
                    value,
                    ignoreCase = true
                )
            }

    preferences.edit()
        .putString(
            key,
            updatedHistory.joinToString("|")
        )
        .apply()
}


private fun getFilteredHistory(
    history: List<String>,
    query: String
): List<String> {

    if (query.isBlank()) {
        return emptyList()
    }

    return history.filter {

        it.contains(
            query.trim(),
            ignoreCase = true
        )
    }
}


// =========================================================
// NOTIFICATION PERMISSION
// =========================================================

private fun askNotificationPermission() {

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            requestNotificationPermission.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )

            return
        }
    }

    getFirebaseToken()
}


// =========================================================
// FIREBASE TOKEN
// =========================================================

private fun getFirebaseToken() {

    statusMessage = "Getting Firebase token..."

    fcmToken = null
    isRegistered = false
    isCheckingRegistration = true

    FirebaseMessaging.getInstance()
        .token
        .addOnCompleteListener { task ->

            if (!task.isSuccessful) {

                isCheckingRegistration = false

                val exception = task.exception

                Log.e(
                    "SecurityAlert",
                    "Failed to get Firebase token",
                    exception
                )

                statusMessage =
                    if (exception != null) {
                        "Failed to get Firebase token:\n${exception.message}"
                    } else {
                        "Failed to get Firebase token."
                    }

                return@addOnCompleteListener
            }

            val token = task.result

            fcmToken = token

            Log.d(
                "SecurityAlert",
                "Firebase token obtained successfully."
            )

            Toast.makeText(
                this,
                "Firebase token obtained",
                Toast.LENGTH_SHORT
            ).show()

            if (isServerConnected) {
                checkRegistration(token)
            } else {

                isCheckingRegistration = false

                statusMessage =
                    "Firebase token ready. Connect to the server."
            }
        }
}


// =========================================================
// CHECK REGISTRATION
// =========================================================

private fun checkRegistration(token: String) {

    if (!isServerConnected) {
        return
    }

    statusMessage = "Checking device registration..."
    isCheckingRegistration = true

    val backendUrl = getBackendBaseUrl()

    Thread {

        try {

            val json = JSONObject().apply {
                put(
                    "notification_token",
                    token
                )
            }

            val mediaType =
                "application/json; charset=utf-8".toMediaType()

            val requestBody =
                json.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url(
                    "$backendUrl/api/contacts/check"
                )
                .post(requestBody)
                .build()

            httpClient.newCall(request)
                .execute()
                .use { response ->

                    val responseBody =
                        response.body?.string().orEmpty()

                    if (response.isSuccessful) {

                        val responseJson =
                            JSONObject(responseBody)

                        val registered =
                            responseJson.optBoolean(
                                "registered",
                                false
                            )

                        if (registered) {

                            val contact =
                                responseJson.optJSONObject(
                                    "contact"
                                )

                            val savedName =
                                contact?.optString(
                                    "name",
                                    ""
                                ).orEmpty()

                            val savedPhone =
                                contact?.optString(
                                    "phone_number",
                                    ""
                                ).orEmpty()

                            runOnUiThread {

                                isRegistered = true

                                name = savedName
                                phoneNumber = savedPhone

                                isCheckingRegistration = false

                                statusMessage =
                                    "Device is already registered."
                            }

                            Log.d(
                                "SecurityAlert",
                                "Device is already registered."
                            )

                        } else {

                            runOnUiThread {

                                isRegistered = false

                                isCheckingRegistration = false

                                statusMessage =
                                    "Device is not registered. Please enter your details."
                            }

                            Log.d(
                                "SecurityAlert",
                                "Device is not registered."
                            )
                        }

                    } else {

                        runOnUiThread {

                            isCheckingRegistration = false

                            statusMessage =
                                "Could not check registration.\nHTTP ${response.code}"

                            Toast.makeText(
                                this,
                                "Could not check device registration",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        Log.e(
                            "SecurityAlert",
                            "Registration check failed: HTTP ${response.code} $responseBody"
                        )
                    }
                }

        } catch (exception: Exception) {

            Log.e(
                "SecurityAlert",
                "Registration check request failed",
                exception
            )

            runOnUiThread {

                isCheckingRegistration = false

                statusMessage =
                    "Could not connect to backend.\n${exception.message}"

                Toast.makeText(
                    this,
                    "Could not connect to backend",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    }.start()
}


// =========================================================
// REGISTER / UPDATE DEVICE
// =========================================================

private fun registerDevice() {

    val currentToken = fcmToken
    val wasRegistered = isRegistered

    if (!isServerConnected) {

        Toast.makeText(
            this,
            "Please connect to a server first.",
            Toast.LENGTH_SHORT
        ).show()

        return
    }

    if (name.trim().isEmpty()) {

        Toast.makeText(
            this,
            "Please enter your name.",
            Toast.LENGTH_SHORT
        ).show()

        return
    }

    if (phoneNumber.trim().isEmpty()) {

        Toast.makeText(
            this,
            "Please enter your contact number.",
            Toast.LENGTH_SHORT
        ).show()

        return
    }

    if (currentToken.isNullOrBlank()) {

        Toast.makeText(
            this,
            "Firebase token is not ready yet.",
            Toast.LENGTH_SHORT
        ).show()

        return
    }

    isSubmitting = true

    statusMessage =
        if (wasRegistered) {
            "Updating device information..."
        } else {
            "Registering device..."
        }

    val backendUrl = getBackendBaseUrl()

    Thread {

        try {

            val json = JSONObject().apply {

                put(
                    "name",
                    name.trim()
                )

                put(
                    "phone_number",
                    phoneNumber.trim()
                )

                put(
                    "notification_token",
                    currentToken
                )
            }

            val mediaType =
                "application/json; charset=utf-8".toMediaType()

            val requestBody =
                json.toString()
                    .toRequestBody(mediaType)

            val request = Request.Builder()
                .url(
                    "$backendUrl/api/contacts/register"
                )
                .post(requestBody)
                .build()

            httpClient.newCall(request)
                .execute()
                .use { response ->

                    val responseBody =
                        response.body?.string().orEmpty()

                    if (response.isSuccessful) {

                        runOnUiThread {

                            isSubmitting = false
                            isRegistered = true

                            statusMessage =
                                if (wasRegistered) {
                                    "Device information updated successfully."
                                } else {
                                    "Device registered successfully."
                                }

                            Toast.makeText(
                                this,
                                if (wasRegistered) {
                                    "Device information updated"
                                } else {
                                    "Registration successful"
                                },
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        Log.d(
                            "SecurityAlert",
                            "Registration/update successful: $responseBody"
                        )

                    } else {

                        runOnUiThread {

                            isSubmitting = false

                            statusMessage =
                                "Request failed.\nHTTP ${response.code}"

                            Toast.makeText(
                                this,
                                "Request failed: HTTP ${response.code}",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        Log.e(
                            "SecurityAlert",
                            "Registration/Update failed: HTTP ${response.code} $responseBody"
                        )
                    }
                }

        } catch (exception: Exception) {

            Log.e(
                "SecurityAlert",
                "Registration request failed",
                exception
            )

            runOnUiThread {

                isSubmitting = false

                statusMessage =
                    "Could not connect to backend.\n${exception.message}"

                Toast.makeText(
                    this,
                    "Could not connect to backend",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    }.start()
}
}


// =============================================================
// MAIN APP UI
// =============================================================

@Composable
fun SecurityAlertApp(
    selectedServerType: String,
    serverInput: String,

    currentPage: String,
    drawerOpen: Boolean,

    isConnecting: Boolean,
    isServerConnected: Boolean,
    connectionMessage: String,

    hostedSuggestions: List<String>,
    localSuggestions: List<String>,

    name: String,
    phoneNumber: String,

    tokenAvailable: Boolean,
    isRegistered: Boolean,
    isSubmitting: Boolean,
    isCheckingRegistration: Boolean,
    statusMessage: String,

    onServerTypeChange: (String) -> Unit,
    onServerInputChange: (String) -> Unit,

    onSuggestionClick: (String) -> Unit,
    onDeleteSuggestion: (String) -> Unit,

    onConnect: () -> Unit,

    onOpenDrawer: () -> Unit,
    onCloseDrawer: () -> Unit,

    onSettingsClick: () -> Unit,
    onContactClick: () -> Unit,

    onNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onSubmit: () -> Unit
) {

    Box(
        modifier = Modifier.fillMaxSize()
    ) {

        // -----------------------------------------------------
        // Main page
        // -----------------------------------------------------

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {

            IconButton(
                onClick = onOpenDrawer,
                modifier = Modifier.size(48.dp)
            ) {

                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Open menu",
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(
                modifier = Modifier.height(12.dp)
            )

            if (currentPage == "settings") {

                ConnectionSettingsScreen(
                    selectedServerType = selectedServerType,
                    serverInput = serverInput,

                    isConnecting = isConnecting,
                    isServerConnected = isServerConnected,
                    connectionMessage = connectionMessage,

                    suggestions =
                        if (selectedServerType == "hosted") {
                            hostedSuggestions
                        } else {
                            localSuggestions
                        },

                    onServerTypeChange = onServerTypeChange,
                    onServerInputChange = onServerInputChange,

                    onSuggestionClick = onSuggestionClick,
                    onDeleteSuggestion = onDeleteSuggestion,

                    onConnect = onConnect
                )

            } else {

                ContactInfoScreen(
                    name = name,
                    phoneNumber = phoneNumber,

                    tokenAvailable = tokenAvailable,
                    isRegistered = isRegistered,
                    isSubmitting = isSubmitting,
                    isCheckingRegistration = isCheckingRegistration,
                    statusMessage = statusMessage,

                    onNameChange = onNameChange,
                    onPhoneChange = onPhoneChange,
                    onSubmit = onSubmit
                )
            }
        }


        // -----------------------------------------------------
        // Sidebar overlay
        // -----------------------------------------------------

        if (drawerOpen) {

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Color.Black.copy(alpha = 0.35f)
                    )
                    .clickable {
                        onCloseDrawer()
                    }
            )


            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(280.dp)
                    .shadow(12.dp)
                    .background(
                        MaterialTheme.colorScheme.surface
                    )
                    .clickable(enabled = false) {}
            ) {

                // Sidebar header

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 20.dp,
                            top = 18.dp,
                            end = 12.dp,
                            bottom = 18.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement =
                        Arrangement.SpaceBetween
                ) {

                    Text(
                        text = "Security Alert",
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold
                    )

                    IconButton(
                        onClick = onCloseDrawer
                    ) {

                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close menu"
                        )
                    }
                }

                HorizontalDivider()

                Spacer(
                    modifier = Modifier.height(12.dp)
                )


                // Settings

                SidebarItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null
                        )
                    },
                    text = "Settings",
                    selected = currentPage == "settings",
                    onClick = onSettingsClick
                )


                // Contact

                SidebarItem(
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null
                        )
                    },
                    text = "Contact",
                    selected = currentPage == "contact",
                    enabled = isServerConnected,
                    onClick = onContactClick
                )
            }
        }
    }
}


// =============================================================
// CONNECTION SETTINGS
// =============================================================

@Composable
fun ConnectionSettingsScreen(
    selectedServerType: String,
    serverInput: String,

    isConnecting: Boolean,
    isServerConnected: Boolean,
    connectionMessage: String,

    suggestions: List<String>,

    onServerTypeChange: (String) -> Unit,
    onServerInputChange: (String) -> Unit,

    onSuggestionClick: (String) -> Unit,
    onDeleteSuggestion: (String) -> Unit,

    onConnect: () -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {

        // Heading

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {

            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(30.dp)
            )

            Spacer(
                modifier = Modifier.width(10.dp)
            )

            Text(
                text = "Connection Settings",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(
            modifier = Modifier.height(30.dp)
        )


        // Hosted Server

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onServerTypeChange("hosted")
                },
            verticalAlignment = Alignment.CenterVertically
        ) {

            RadioButton(
                selected = selectedServerType == "hosted",
                onClick = {
                    onServerTypeChange("hosted")
                }
            )

            Text(
                text = "Hosted Server",
                fontSize = 17.sp
            )
        }


        Spacer(
            modifier = Modifier.height(4.dp)
        )


        // Local Server

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onServerTypeChange("local")
                },
            verticalAlignment = Alignment.CenterVertically
        ) {

            RadioButton(
                selected = selectedServerType == "local",
                onClick = {
                    onServerTypeChange("local")
                }
            )

            Text(
                text = "Local Server",
                fontSize = 17.sp
            )
        }


        Spacer(
            modifier = Modifier.height(26.dp)
        )


        // Input

        Text(
            text =
                if (selectedServerType == "hosted") {
                    "Enter Domain Name"
                } else {
                    "Enter Local Server IP"
                },
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(
            modifier = Modifier.height(8.dp)
        )


        OutlinedTextField(
            value = serverInput,
            onValueChange = onServerInputChange,

            modifier = Modifier.fillMaxWidth(),

            singleLine = true,

            placeholder = {

                Text(
                    text =
                        if (selectedServerType == "hosted") {
                            "red-object-detection.onrender.com"
                        } else {
                            "192.168.1.2"
                        }
                )
            },

            enabled = !isConnecting
        )


        // Suggestions

        if (
            serverInput.isNotBlank() &&
            suggestions.isNotEmpty()
        ) {

            Spacer(
                modifier = Modifier.height(6.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(3.dp)
                    .background(
                        MaterialTheme.colorScheme.surface
                    )
            ) {

                suggestions.forEach { suggestion ->

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSuggestionClick(
                                    suggestion
                                )
                            }
                            .padding(
                                start = 14.dp,
                                top = 12.dp,
                                end = 6.dp,
                                bottom = 12.dp
                            ),
                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {

                        Text(
                            text = suggestion,
                            modifier = Modifier.weight(1f)
                        )

                        Text(
                            text = "×",
                            fontSize = 22.sp,
                            modifier = Modifier
                                .clickable {
                                    onDeleteSuggestion(
                                        suggestion
                                    )
                                }
                                .padding(
                                    horizontal = 10.dp
                                )
                        )
                    }
                }
            }
        }


        Spacer(
            modifier = Modifier.height(22.dp)
        )


        // Connect button

        Button(
            onClick = onConnect,

            enabled =
                serverInput.trim().isNotEmpty() &&
                        !isConnecting
        ) {

            if (isConnecting) {

                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )

                Spacer(
                    modifier = Modifier.width(10.dp)
                )

                Text("Connecting...")

            } else {

                Text("CONNECT")
            }
        }


        Spacer(
            modifier = Modifier.height(18.dp)
        )


        if (connectionMessage.isNotBlank()) {

            Text(
                text = connectionMessage,
                fontSize = 14.sp,
                color =
                    if (isServerConnected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
            )
        }
    }
}


// =============================================================
// CONTACT INFO
// =============================================================

@Composable
fun ContactInfoScreen(
    name: String,
    phoneNumber: String,

    tokenAvailable: Boolean,
    isRegistered: Boolean,
    isSubmitting: Boolean,
    isCheckingRegistration: Boolean,
    statusMessage: String,

    onNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onSubmit: () -> Unit
) {

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {

        // Heading

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {

            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(30.dp)
            )

            Spacer(
                modifier = Modifier.width(10.dp)
            )

            Text(
                text = "Contact Info",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
        }


        Spacer(
            modifier = Modifier.height(30.dp)
        )


        // Name

        OutlinedTextField(
            value = name,

            onValueChange = onNameChange,

            modifier = Modifier.fillMaxWidth(),

            label = {
                Text("Name")
            },

            singleLine = true,

            enabled =
                !isCheckingRegistration &&
                        !isSubmitting
        )


        Spacer(
            modifier = Modifier.height(14.dp)
        )


        // Phone

        OutlinedTextField(
            value = phoneNumber,

            onValueChange = onPhoneChange,

            modifier = Modifier.fillMaxWidth(),

            label = {
                Text("Contact Number")
            },

            singleLine = true,

            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone
            ),

            enabled =
                !isCheckingRegistration &&
                        !isSubmitting
        )


        Spacer(
            modifier = Modifier.height(22.dp)
        )


        // Status

        Text(
            text = statusMessage,
            fontSize = 14.sp
        )


        Spacer(
            modifier = Modifier.height(22.dp)
        )


        // Register / Update

        Button(
            onClick = onSubmit,

            enabled =
                !isSubmitting &&
                        !isCheckingRegistration &&
                        tokenAvailable
        ) {

            if (
                isSubmitting ||
                isCheckingRegistration
            ) {

                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )

                Spacer(
                    modifier = Modifier.width(10.dp)
                )

                Text("Please wait...")

            } else {

                Text(
                    if (isRegistered) {
                        "UPDATE"
                    } else {
                        "REGISTER"
                    }
                )
            }
        }
    }
}


// =============================================================
// SIDEBAR ITEM
// =============================================================

@Composable
fun SidebarItem(
    icon: @Composable () -> Unit,
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {

    val backgroundColor =
        if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                backgroundColor,
                RoundedCornerShape(10.dp)
            )
            .clickable(
                enabled = enabled,
                onClick = onClick
            )
            .padding(
                horizontal = 18.dp,
                vertical = 14.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {

        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }

        Spacer(
            modifier = Modifier.width(14.dp)
        )

        Text(
            text = text,
            fontSize = 17.sp,
            color =
                if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(
                        alpha = 0.4f
                    )
                }
        )
    }
}