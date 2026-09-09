package com.example.securityalert

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private var fcmToken by mutableStateOf<String?>(null)

    private var name by mutableStateOf("")
    private var phoneNumber by mutableStateOf("")

    private var isRegistered by mutableStateOf(false)

    private var isSubmitting by mutableStateOf(false)
    private var isCheckingRegistration by mutableStateOf(false)

    private var statusMessage by mutableStateOf("Getting Firebase token...")

    private val backendUrl = "http://red-object-detection.onrender.com/"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val requestNotificationPermission =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            getFirebaseToken()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SecurityAlertScreen(
                name = name,
                phoneNumber = phoneNumber,
                tokenAvailable = fcmToken != null,
                isRegistered = isRegistered,
                isSubmitting = isSubmitting,
                isCheckingRegistration = isCheckingRegistration,
                statusMessage = statusMessage,

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

                checkRegistration(token)
            }
    }

    private fun checkRegistration(token: String) {

        statusMessage = "Checking device registration..."
        isCheckingRegistration = true

        Thread {

            try {

                val json = JSONObject().apply {
                    put("notification_token", token)
                }

                val mediaType =
                    "application/json; charset=utf-8".toMediaType()

                val requestBody =
                    json.toString().toRequestBody(mediaType)

                val request = Request.Builder()
                    .url("$backendUrl/api/contacts/check")
                    .post(requestBody)
                    .build()

                httpClient.newCall(request).execute().use { response ->

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
                                responseJson.optJSONObject("contact")

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

    private fun registerDevice() {

        val currentToken = fcmToken
        val wasRegistered = isRegistered

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

        Thread {

            try {

                val json = JSONObject().apply {
                    put("name", name.trim())
                    put("phone_number", phoneNumber.trim())
                    put("notification_token", currentToken)
                }

                val mediaType =
                    "application/json; charset=utf-8".toMediaType()

                val requestBody =
                    json.toString().toRequestBody(mediaType)

                val request = Request.Builder()
                    .url("$backendUrl/api/contacts/register")
                    .post(requestBody)
                    .build()

                httpClient.newCall(request).execute().use { response ->

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

@androidx.compose.runtime.Composable
fun SecurityAlertScreen(
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
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {

        Text(
            text = "Security Alert"
        )

        Spacer(
            modifier = Modifier.height(24.dp)
        )

        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text("Name")
            },
            singleLine = true,
            enabled = !isCheckingRegistration && !isSubmitting
        )

        Spacer(
            modifier = Modifier.height(12.dp)
        )

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
            enabled = !isCheckingRegistration && !isSubmitting
        )

        Spacer(
            modifier = Modifier.height(20.dp)
        )

        Text(
            text = statusMessage
        )

        Spacer(
            modifier = Modifier.height(20.dp)
        )

        Button(
            onClick = onSubmit,
            enabled =
                !isSubmitting &&
                        !isCheckingRegistration &&
                        tokenAvailable
        ) {

            if (isSubmitting || isCheckingRegistration) {

                CircularProgressIndicator()

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