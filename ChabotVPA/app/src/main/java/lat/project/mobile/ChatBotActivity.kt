package lat.project.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventDateTime
import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.cloud.dialogflow.v2.*
import kotlinx.coroutines.*
import lat.project.mobile.adapters.ChatAdapter
import lat.project.mobile.models.Message
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class ChatBotActivity : AppCompatActivity() {
    private var messageList: ArrayList<Message> = ArrayList()
    private lateinit var chatAdapter: ChatAdapter
    private val TAG = "ChatBotActivity"

    // Dialogflow
    private var sessionsClient: SessionsClient? = null
    private var sessionName: SessionName? = null
    private val uuid = UUID.randomUUID().toString()

    private lateinit var chatView: RecyclerView
    private lateinit var btnSend: ImageButton
    private lateinit var editMessage: EditText

    companion object {
        private const val REQUEST_CALENDAR_PERMISSION = 1001
        private const val REQUEST_AUTHORIZATION = 1002
        private const val RC_CALENDAR_PERMISSION = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_bot)

        // Initialize UI components
        chatView = findViewById(R.id.chatView)
        btnSend = findViewById(R.id.btnSend)
        editMessage = findViewById(R.id.editMessage)

        // Setting adapter to recyclerview
        chatAdapter = ChatAdapter(this, messageList)
        chatView.adapter = chatAdapter

        // Onclick listener to update the list and call dialogflow
        btnSend.setOnClickListener {
            val message: String = editMessage.text.toString()
            if (message.isNotEmpty()) {
                addMessageToList(message, false)
                sendMessageToList(message)
            } else {
                Toast.makeText(this, "Please enter text!", Toast.LENGTH_SHORT).show()
            }
        }

        // Initial bot config
        setUpBot()
        checkCalendarPermission()
    }

    private fun sendMessageToList(message: String) {
        // Send message to the bot
        val input = QueryInput.newBuilder()
            .setText(TextInput.newBuilder().setText(message).setLanguageCode("id-ID"))
            .build()

        GlobalScope.launch {
            sendMessageInBg(input)
        }
    }

    private suspend fun sendMessageInBg(queryInput: QueryInput) {
        withContext(Dispatchers.Default) {
            try {
                val detectIntentRequest = DetectIntentRequest.newBuilder()
                    .setSession(sessionName.toString())
                    .setQueryInput(queryInput)
                    .build()
                val result = sessionsClient?.detectIntent(detectIntentRequest)
                if (result != null) {
                    runOnUiThread {
                        updateIU(result)
                    }

                    // Check intent and extract parameters
                    val queryResult = result.queryResult
                    val intentName = queryResult.intent.displayName
                    if (intentName == "pembuatan_jadwal") {
                        val parameters = queryResult.parameters
                        val date = parameters.fieldsMap["date"]?.stringValue
                        val time = parameters.fieldsMap["time"]?.stringValue
                        val activityType = parameters.fieldsMap["activity_type"]?.stringValue

                        // Log the raw parameter values
                        Log.d(TAG, "Date received: $date")
                        Log.d(TAG, "Time received: $time")
                        Log.d(TAG, "Activity Type received: $activityType")

                        if (date != null && time != null && activityType != null) {
                            val calendarService = createCalendarService()
                            if (calendarService != null) {
                                createCalendarEvent(calendarService, date,  time, activityType)
                            } else {
                                Log.e(TAG, "Calendar service is null")
                            }
                        } else {
                            Log.e(TAG, "Incomplete parameters")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "doInBackground: " + e.message)
                e.printStackTrace()
            }
        }
    }

    private fun updateIU(response: DetectIntentResponse) {
        val botReply: String = response.queryResult.fulfillmentText
        if (botReply.isNotEmpty()) {
            addMessageToList(botReply, true)
        } else {
            Toast.makeText(this, "Something went wrong", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setUpBot() {
        // Initialize the dialogflow
        try {
            val stream = this.resources.openRawResource(R.raw.api_dialog)
            val credentials: GoogleCredentials = GoogleCredentials.fromStream(stream)
                .createScoped("https://www.googleapis.com/auth/cloud-platform")
            val projectId: String = (credentials as ServiceAccountCredentials).projectId
            val settingsBuilder: SessionsSettings.Builder = SessionsSettings.newBuilder()
            val sessionsSettings: SessionsSettings = settingsBuilder.setCredentialsProvider(
                FixedCredentialsProvider.create(credentials)
            ).build()
            sessionsClient = SessionsClient.create(sessionsSettings)
            sessionName = SessionName.of(projectId, uuid)
            Log.d(TAG, "projectId: $projectId")
        } catch (e: Exception) {
            Log.d(TAG, "setUpBot: " + e.message)
        }
    }

    private fun addMessageToList(message: String, isReceived: Boolean) {
        // Handle UI change
        messageList.add(Message(message, isReceived))
        editMessage.setText("")
        chatAdapter.notifyDataSetChanged()
        chatView.layoutManager?.scrollToPosition(messageList.size - 1)
    }

    private fun checkCalendarPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_CALENDAR),
                REQUEST_CALENDAR_PERMISSION
            )
        }
    }

    private fun createCalendarService(): Calendar? {
        val account = GoogleSignIn.getLastSignedInAccount(this)
        return if (account != null) {
            if (GoogleSignIn.hasPermissions(
                    account,
                    Scope(CalendarScopes.CALENDAR)
                )
            ) {
                val credential = GoogleAccountCredential.usingOAuth2(
                    this,
                    listOf(CalendarScopes.CALENDAR)
                )
                credential.selectedAccount = account.account
                Calendar.Builder(
                    AndroidHttp.newCompatibleTransport(),
                    GsonFactory.getDefaultInstance(),
                    credential
                )
                    .setApplicationName("Chatbot VPA")
                    .build()
            } else {
                GoogleSignIn.requestPermissions(
                    this,
                    RC_CALENDAR_PERMISSION,
                    account,
                    Scope(CalendarScopes.CALENDAR)
                )
                null
            }
        } else {
            null
        }
    }

    private fun createCalendarEvent(calendarService: Calendar, date: String?, time: String?, activityType: String) {
        try {
            // Parsing tanggal dengan format Indonesia (15 Desember) atau DD-MM atau ISO (2024-12-15)
            val parsedDate = try {
                if (date != null) {
                    val formatter: DateTimeFormatter = when {
                        date.contains("T") -> DateTimeFormatter.ISO_OFFSET_DATE_TIME // Format ISO (2024-12-15T12:00:00+07:00)
                        date.contains("-") -> DateTimeFormatter.ofPattern("dd-MM") // Format DD-MM
                        else -> DateTimeFormatter.ofPattern("d MMMM", Locale("id", "ID")) // Format '15 Desember'
                    }
                    val localDate = LocalDate.parse(date, formatter)
                    // Gabungkan dengan tahun saat ini
                    localDate.withYear(LocalDate.now().year)
                } else {
                    // Default ke tanggal hari ini jika tidak ada input
                    LocalDate.now()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Format tanggal tidak valid: $date. Menggunakan tanggal default.", e)
                LocalDate.now()
            }

            // Parsing waktu atau gunakan waktu default 09:00
            val parsedTime = try {
                if (time != null) {
                    val formatter = DateTimeFormatter.ofPattern("HH:mm") // Format "jam HH:mm"
                    LocalTime.parse(time, formatter)
                } else {
                    LocalTime.of(9, 0) // Default ke 09:00
                }
            } catch (e: Exception) {
                Log.e(TAG, "Format waktu tidak valid: $time. Menggunakan waktu default.", e)
                LocalTime.of(9, 0) // Default ke 09:00 jika parsing gagal
            }

            // Kombinasikan tanggal dan waktu
            val startDateTime = LocalDateTime.of(parsedDate, parsedTime)
            val endDateTime = startDateTime.plusHours(1) // Durasi acara: 1 jam

            // Konversi ke epoch millis dengan zona waktu sistem
            val startDateTimeMillis = startDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val endDateTimeMillis = endDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

            // Log tanggal dan waktu yang digunakan
            Log.d(TAG, "Tanggal dan waktu yang digunakan: ${startDateTime.format(DateTimeFormatter.ofPattern("d MMMM yyyy HH:mm", Locale("id", "ID")))}")

            // Buat objek Event Google Calendar
            val event = Event()
                .setSummary(activityType)
                .setStart(EventDateTime().setDateTime(DateTime(startDateTimeMillis)))
                .setEnd(EventDateTime().setDateTime(DateTime(endDateTimeMillis)))

            // Kirim ke Google Calendar
            calendarService.events().insert("primary", event).execute()

            // Tampilkan notifikasi keberhasilan
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Jadwal $activityType berhasil dibuat untuk ${startDateTime.format(DateTimeFormatter.ofPattern("d MMMM yyyy HH:mm", Locale("id", "ID")))}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating calendar event", e)
            runOnUiThread {
                Toast.makeText(this, "Format tanggal atau waktu tidak valid atau terjadi kesalahan", Toast.LENGTH_SHORT).show()
            }
        }
    }





    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_AUTHORIZATION -> {
                if (resultCode == RESULT_OK) {
                    // Izin diberikan, coba buat event kalender lagi
                    // Anda mungkin perlu menyimpan parameter sebelumnya atau mengimplementasikan ulang logika pembuatan event
                } else {
                    Toast.makeText(this, "Izin akses Google Calendar ditolak", Toast.LENGTH_SHORT).show()
                }
            }
            RC_CALENDAR_PERMISSION -> {
                if (resultCode == RESULT_OK) {
                    // Izin diberikan, coba buat layanan kalender lagi
                    createCalendarService()
                } else {
                    Toast.makeText(this, "Izin akses Google Calendar diperlukan", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}